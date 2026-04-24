package engine.sim.water;

import engine.math.AABB;
import engine.math.Mat4;
import engine.math.Vec3;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.util.RuntimeInstrumentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Represents deterministickou simulaci spray a splash částic běžící na procesoru pro entity vodního emitoru.
 * Záměrně z ní nedělám objemový fluid solver. Modeluju v ní směrový kapalinový spray,
 * nárazy do podlahy a jednoduché proxy kolize se scénovou geometrií.
 */
public final class WaterSimulation {

    private static final long RANDOM_SEED = 0x5A175EEDL;
    private static final int DEFAULT_CAPACITY = 5600;
    private static final int MAX_COLLISION_PROXIES = 256;
    private static final double MAX_DT = 1.0 / 24.0;
    private static final double REPLAY_STEP = 1.0 / 60.0;
    private static final double SUBSTEP = 1.0 / 120.0;
    private static final double COLLISION_EPS = 1e-4;

    private final Random random;
    private final List<WaterEmitter> emitters;
    private final WaterEmitter[] particleEmitter;
    private final double[] px;
    private final double[] py;
    private final double[] pz;
    private final double[] vx;
    private final double[] vy;
    private final double[] vz;
    private final double[] lifeRemaining;
    private final double[] lifeMax;
    private final double[] radius;
    private final boolean[] alive;
    private final double[] colliderMinX;
    private final double[] colliderMinY;
    private final double[] colliderMinZ;
    private final double[] colliderMaxX;
    private final double[] colliderMaxY;
    private final double[] colliderMaxZ;
    private int nextSpawnIndex;
    private int activeCount;
    private int collisionProxyCount;
    private double simulationTimeSeconds;

    public WaterSimulation() {
        this(DEFAULT_CAPACITY);
    }

    public WaterSimulation(int capacity) {
        int safeCapacity = Math.max(128, capacity);
        this.random = new Random(RANDOM_SEED);
        this.emitters = new ArrayList<>();
        this.particleEmitter = new WaterEmitter[safeCapacity];
        this.px = new double[safeCapacity];
        this.py = new double[safeCapacity];
        this.pz = new double[safeCapacity];
        this.vx = new double[safeCapacity];
        this.vy = new double[safeCapacity];
        this.vz = new double[safeCapacity];
        this.lifeRemaining = new double[safeCapacity];
        this.lifeMax = new double[safeCapacity];
        this.radius = new double[safeCapacity];
        this.alive = new boolean[safeCapacity];
        this.colliderMinX = new double[MAX_COLLISION_PROXIES];
        this.colliderMinY = new double[MAX_COLLISION_PROXIES];
        this.colliderMinZ = new double[MAX_COLLISION_PROXIES];
        this.colliderMaxX = new double[MAX_COLLISION_PROXIES];
        this.colliderMaxY = new double[MAX_COLLISION_PROXIES];
        this.colliderMaxZ = new double[MAX_COLLISION_PROXIES];
        this.nextSpawnIndex = 0;
        this.activeCount = 0;
        this.collisionProxyCount = 0;
        this.simulationTimeSeconds = 0.0;
    }

    public int getCapacity() {
        return alive.length;
    }

    public int getActiveParticleCount() {
        return activeCount;
    }

    public double getSimulationTimeSeconds() {
        return simulationTimeSeconds;
    }

    public List<WaterEmitter> getEmitters() {
        return Collections.unmodifiableList(emitters);
    }

    public boolean isEmitter(Entity entity) {
        return getEmitter(entity) != null;
    }

    public WaterEmitter getEmitter(Entity entity) {
        if (entity == null) {
            return null;
        }
        for (WaterEmitter emitter : emitters) {
            if (emitter.getEntity() == entity) {
                return emitter;
            }
        }
        return null;
    }

    public WaterEmitter addEmitter(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof WaterEmitterEntity waterEntity) {
            WaterEmitter bound = waterEntity.getEmitter();
            if (!containsEmitter(bound)) {
                emitters.add(bound);
            }
            return bound;
        }
        WaterEmitter existing = getEmitter(entity);
        if (existing != null) {
            return existing;
        }
        WaterEmitter emitter = new WaterEmitter(entity);
        emitters.add(emitter);
        return emitter;
    }

    public void removeEmitter(Entity entity) {
        if (entity == null) {
            return;
        }
        Iterator<WaterEmitter> it = emitters.iterator();
        while (it.hasNext()) {
            WaterEmitter emitter = it.next();
            if (emitter.getEntity() == entity) {
                it.remove();
                deactivateParticlesForEmitter(emitter);
                break;
            }
        }
    }

    public void clear() {
        for (WaterEmitter emitter : emitters) {
            if (emitter != null) {
                emitter.spawnAccumulator = 0.0;
            }
        }
        emitters.clear();
        resetParticleState();
    }

    public void resetState() {
        resetParticleState();
        for (WaterEmitter emitter : emitters) {
            if (emitter != null) {
                emitter.spawnAccumulator = 0.0;
            }
        }
    }

    public void update(Scene scene,
                       double dtSeconds,
                       double elapsedSeconds,
                       Vec3 gravity,
                       double floorY,
                       boolean simulationEnabled) {
        update(scene, dtSeconds, gravity, floorY, simulationEnabled);
    }

    public void update(Scene scene,
                       double dtSeconds,
                       Vec3 gravity,
                       double floorY,
                       boolean simulationEnabled) {
        if (!simulationEnabled && activeCount == 0 && emitters.isEmpty()) {
            return;
        }
        pruneEmittersNotInScene(scene);
        rebuildCollisionProxies(scene, floorY);

        double dt = Math.max(0.0, Math.min(MAX_DT, dtSeconds));
        if (!simulationEnabled || dt <= 0.0) {
            return;
        }

        Vec3 g = gravity == null ? new Vec3(0.0, -9.81, 0.0) : gravity;
        advanceTime(dt, g, floorY);
    }

    public void syncToTime(Scene scene,
                           double targetTimeSeconds,
                           Vec3 gravity,
                           double floorY,
                           boolean simulationEnabled) {
        if (!simulationEnabled && activeCount == 0 && emitters.isEmpty()) {
            return;
        }
        pruneEmittersNotInScene(scene);
        rebuildCollisionProxies(scene, floorY);

        if (!simulationEnabled) {
            resetState();
            return;
        }

        double target = Math.max(0.0, targetTimeSeconds);
        if (target + 1e-9 < simulationTimeSeconds) {
            resetState();
            pruneEmittersNotInScene(scene);
            rebuildCollisionProxies(scene, floorY);
        }

        Vec3 g = gravity == null ? new Vec3(0.0, -9.81, 0.0) : gravity;
        while (simulationTimeSeconds + 1e-9 < target) {
            double step = Math.min(REPLAY_STEP, target - simulationTimeSeconds);
            advanceTime(step, g, floorY);
        }
    }

    public long stateSignature() {
        long hash = 0xCBF29CE484222325L;
        hash = mix(hash, emitters.size());
        hash = mix(hash, activeCount);
        hash = mix(hash, Double.doubleToLongBits(simulationTimeSeconds));
        for (int i = 0; i < alive.length; i++) {
            if (!alive[i]) {
                continue;
            }
            hash = mix(hash, i);
            hash = mix(hash, Double.doubleToLongBits(px[i]));
            hash = mix(hash, Double.doubleToLongBits(py[i]));
            hash = mix(hash, Double.doubleToLongBits(pz[i]));
            hash = mix(hash, Double.doubleToLongBits(vx[i]));
            hash = mix(hash, Double.doubleToLongBits(vy[i]));
            hash = mix(hash, Double.doubleToLongBits(vz[i]));
            hash = mix(hash, Double.doubleToLongBits(lifeRemaining[i]));
            hash = mix(hash, Double.doubleToLongBits(radius[i]));
            WaterEmitter emitter = particleEmitter[i];
            hash = mix(hash, emitter == null ? -1L : emitters.indexOf(emitter));
        }
        return hash;
    }

    public void forEachParticle(ParticleConsumer consumer) {
        if (consumer == null) {
            return;
        }
        for (int i = 0; i < alive.length; i++) {
            if (!alive[i]) {
                continue;
            }
            WaterEmitter emitter = particleEmitter[i];
            if (emitter == null) {
                continue;
            }
            consumer.accept(
                    emitter,
                    px[i], py[i], pz[i],
                    vx[i], vy[i], vz[i],
                    Math.max(0.0, lifeRemaining[i]),
                    Math.max(1e-9, lifeMax[i]),
                    radius[i]
            );
        }
    }

    public static boolean hasEmitterEntities(Scene scene) {
        if (scene == null) {
            return false;
        }
        for (Entity entity : scene.getEntities()) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.ENTITIES_VISITED, 1L);
            if (entity instanceof WaterEmitterEntity) {
                return true;
            }
        }
        return false;
    }

    public static double resolveFloorY(Scene scene) {
        if (scene == null) {
            return 0.0;
        }
        for (Entity entity : scene.getEntities()) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.ENTITIES_VISITED, 1L);
            if (isFloorCandidate(entity)) {
                return entity.getTransform().getPosition().y;
            }
        }
        return 0.0;
    }

    private void advanceTime(double dt, Vec3 gravity, double floorY) {
        double remaining = Math.max(0.0, dt);
        while (remaining > 1e-8) {
            double step = Math.min(remaining, SUBSTEP);
            advanceSubstep(step, gravity, floorY);
            simulationTimeSeconds += step;
            remaining -= step;
        }
    }

    private void advanceSubstep(double dt, Vec3 gravity, double floorY) {
        for (WaterEmitter emitter : emitters) {
            spawnFromEmitter(emitter, dt);
        }
        integrateParticles(dt, simulationTimeSeconds, gravity, floorY);
    }

    private void spawnFromEmitter(WaterEmitter emitter, double dt) {
        if (emitter == null || !emitter.isEnabled() || emitter.getEntity() == null) {
            return;
        }
        Entity entity = emitter.getEntity();
        if (!entity.isVisible()) {
            return;
        }

        double emits = emitter.getEmitRate() * dt + emitter.spawnAccumulator;
        int spawnCount = (int) Math.floor(emits);
        emitter.spawnAccumulator = emits - spawnCount;
        spawnCount = Math.max(0, Math.min(220, spawnCount));
        if (spawnCount <= 0) {
            return;
        }

        Mat4 world = entity.getWorldMatrix();
        Vec3 origin = world.transformPoint(Vec3.ZERO);
        Vec3 down = world.transformDirection(new Vec3(0.0, -1.0, 0.0)).normalize();
        if (down.lengthSquared() < 1e-9) {
            down = new Vec3(0.0, -1.0, 0.0);
        }
        Vec3 axisA = buildPerpendicular(down);
        Vec3 axisB = down.cross(axisA).normalize();
        if (axisB.lengthSquared() < 1e-9) {
            axisB = new Vec3(0.0, 0.0, 1.0);
        }
        double spreadRad = Math.toRadians(emitter.getSpreadAngleDegrees());
        double spawnOffset = Math.max(0.03, emitter.getParticleRadius() * 2.0);

        for (int n = 0; n < spawnCount; n++) {
            int slot = reserveParticleSlot();
            if (slot < 0) {
                break;
            }

            double u1 = random.nextDouble();
            double u2 = random.nextDouble();
            double angle = spreadRad * Math.sqrt(u1);
            double az = 2.0 * Math.PI * u2;
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);
            Vec3 dir = down.mul(cos)
                    .add(axisA.mul(Math.cos(az) * sin))
                    .add(axisB.mul(Math.sin(az) * sin))
                    .normalize();
            if (dir.lengthSquared() < 1e-9) {
                dir = down;
            }

            double jitterScale = emitter.getJitter();
            Vec3 jitter = axisA.mul((random.nextDouble() - 0.5) * jitterScale * 0.18)
                    .add(axisB.mul((random.nextDouble() - 0.5) * jitterScale * 0.18));
            Vec3 p = origin.add(dir.mul(spawnOffset)).add(jitter);
            double speed = emitter.getInitialSpeed() * (0.82 + random.nextDouble() * 0.36);
            Vec3 v = dir.mul(speed);

            if (jitterScale > 0.0) {
                double wobble = jitterScale * 0.7;
                v = v.add(axisA.mul((random.nextDouble() - 0.5) * wobble))
                        .add(axisB.mul((random.nextDouble() - 0.5) * wobble));
            }

            alive[slot] = true;
            particleEmitter[slot] = emitter;
            px[slot] = p.x;
            py[slot] = p.y;
            pz[slot] = p.z;
            vx[slot] = v.x;
            vy[slot] = v.y;
            vz[slot] = v.z;
            lifeMax[slot] = emitter.getParticleLifetime() * (0.85 + 0.3 * random.nextDouble());
            lifeRemaining[slot] = lifeMax[slot];
            radius[slot] = emitter.getParticleRadius() * (0.85 + random.nextDouble() * 0.3);
            activeCount++;
        }
    }

    private void integrateParticles(double dt, double elapsedSeconds, Vec3 gravity, double floorY) {
        for (int i = 0; i < alive.length; i++) {
            if (!alive[i]) {
                continue;
            }
            WaterEmitter emitter = particleEmitter[i];
            if (emitter == null || !emitter.isEnabled()) {
                deactivateSlot(i);
                continue;
            }

            lifeRemaining[i] -= dt;
            if (lifeRemaining[i] <= 0.0) {
                deactivateSlot(i);
                continue;
            }

            double gScale = emitter.getGravityScale();
            vx[i] += gravity.x * gScale * dt;
            vy[i] += gravity.y * gScale * dt;
            vz[i] += gravity.z * gScale * dt;

            double drag = Math.exp(-Math.max(0.0, emitter.getDrag()) * dt);
            vx[i] *= drag;
            vy[i] *= drag;
            vz[i] *= drag;

            double swirl = 0.22 * emitter.getJitter();
            if (swirl > 1e-6) {
                double t = elapsedSeconds + i * 0.0173;
                vx[i] += Math.sin(t * 2.2 + py[i] * 1.5) * swirl * dt;
                vz[i] += Math.cos(t * 2.7 + px[i] * 1.2) * swirl * dt;
            }

            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            pz[i] += vz[i] * dt;

            collideWithFloor(i, emitter, floorY);
            collideWithSceneProxies(i, emitter);

            double speedSq = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            if (speedSq < 0.22 && lifeRemaining[i] < lifeMax[i] * 0.45) {
                deactivateSlot(i);
            }
        }
    }

    private void collideWithFloor(int index, WaterEmitter emitter, double floorY) {
        double floor = floorY + radius[index];
        if (py[index] >= floor) {
            return;
        }
        py[index] = floor;
        if (vy[index] < 0.0) {
            vy[index] = -vy[index] * emitter.getBounce();
        }
        double damp = emitter.getSurfaceDamping();
        vx[index] *= damp;
        vz[index] *= damp;
    }

    private void collideWithSceneProxies(int index, WaterEmitter emitter) {
        double particleRadius = radius[index];
        for (int c = 0; c < collisionProxyCount; c++) {
            double minX = colliderMinX[c] - particleRadius;
            double minY = colliderMinY[c] - particleRadius;
            double minZ = colliderMinZ[c] - particleRadius;
            double maxX = colliderMaxX[c] + particleRadius;
            double maxY = colliderMaxY[c] + particleRadius;
            double maxZ = colliderMaxZ[c] + particleRadius;

            if (px[index] <= minX || px[index] >= maxX
                    || py[index] <= minY || py[index] >= maxY
                    || pz[index] <= minZ || pz[index] >= maxZ) {
                continue;
            }

            double pushMinX = px[index] - minX;
            double pushMaxX = maxX - px[index];
            double pushMinY = py[index] - minY;
            double pushMaxY = maxY - py[index];
            double pushMinZ = pz[index] - minZ;
            double pushMaxZ = maxZ - pz[index];

            int axis = 0;
            double push = pushMinX;
            if (pushMaxX < push) {
                axis = 1;
                push = pushMaxX;
            }
            if (pushMinY < push) {
                axis = 2;
                push = pushMinY;
            }
            if (pushMaxY < push) {
                axis = 3;
                push = pushMaxY;
            }
            if (pushMinZ < push) {
                axis = 4;
                push = pushMinZ;
            }
            if (pushMaxZ < push) {
                axis = 5;
                push = pushMaxZ;
            }

            double bounce = emitter.getBounce();
            double damp = emitter.getSurfaceDamping();
            switch (axis) {
                case 0 -> {
                    px[index] = minX - COLLISION_EPS;
                    if (vx[index] > 0.0) {
                        vx[index] = -vx[index] * bounce;
                    }
                    vy[index] *= damp;
                    vz[index] *= damp;
                }
                case 1 -> {
                    px[index] = maxX + COLLISION_EPS;
                    if (vx[index] < 0.0) {
                        vx[index] = -vx[index] * bounce;
                    }
                    vy[index] *= damp;
                    vz[index] *= damp;
                }
                case 2 -> {
                    py[index] = minY - COLLISION_EPS;
                    if (vy[index] > 0.0) {
                        vy[index] = -vy[index] * bounce;
                    }
                    vx[index] *= damp;
                    vz[index] *= damp;
                }
                case 3 -> {
                    py[index] = maxY + COLLISION_EPS;
                    if (vy[index] < 0.0) {
                        vy[index] = -vy[index] * bounce;
                    }
                    vx[index] *= damp;
                    vz[index] *= damp;
                }
                case 4 -> {
                    pz[index] = minZ - COLLISION_EPS;
                    if (vz[index] > 0.0) {
                        vz[index] = -vz[index] * bounce;
                    }
                    vx[index] *= damp;
                    vy[index] *= damp;
                }
                default -> {
                    pz[index] = maxZ + COLLISION_EPS;
                    if (vz[index] < 0.0) {
                        vz[index] = -vz[index] * bounce;
                    }
                    vx[index] *= damp;
                    vy[index] *= damp;
                }
            }
        }
    }

    private void rebuildCollisionProxies(Scene scene, double floorY) {
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.WATER_PROXY_REBUILDS, 1L);
        collisionProxyCount = 0;
        if (scene == null) {
            return;
        }
        for (Entity entity : scene.getEntities()) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.ENTITIES_VISITED, 1L);
            if (collisionProxyCount >= colliderMinX.length) {
                break;
            }
            if (entity == null || !entity.isVisible() || entity instanceof WaterEmitterEntity || entity.getMesh() == null) {
                continue;
            }
            if (isFloorCandidate(entity)) {
                continue;
            }
            entity.computeWorldBounds();
            AABB bounds = entity.getWorldBounds();
            if (bounds == null) {
                continue;
            }
            Vec3 min = bounds.getMin();
            Vec3 max = bounds.getMax();
            if (!isFinite(min) || !isFinite(max)) {
                continue;
            }
            if (max.y <= floorY - 0.05) {
                continue;
            }
            int slot = collisionProxyCount++;
            colliderMinX[slot] = min.x;
            colliderMinY[slot] = min.y;
            colliderMinZ[slot] = min.z;
            colliderMaxX[slot] = max.x;
            colliderMaxY[slot] = max.y;
            colliderMaxZ[slot] = max.z;
        }
    }

    private int reserveParticleSlot() {
        int len = alive.length;
        for (int n = 0; n < len; n++) {
            int idx = (nextSpawnIndex + n) % len;
            if (!alive[idx]) {
                nextSpawnIndex = (idx + 1) % len;
                return idx;
            }
        }

        for (int n = 0; n < len; n++) {
            int idx = (nextSpawnIndex + n) % len;
            if (lifeRemaining[idx] < 0.15) {
                nextSpawnIndex = (idx + 1) % len;
                deactivateSlot(idx);
                return idx;
            }
        }
        return -1;
    }

    private void deactivateSlot(int idx) {
        if (idx < 0 || idx >= alive.length || !alive[idx]) {
            return;
        }
        alive[idx] = false;
        particleEmitter[idx] = null;
        lifeRemaining[idx] = 0.0;
        lifeMax[idx] = 0.0;
        radius[idx] = 0.0;
        if (activeCount > 0) {
            activeCount--;
        }
    }

    private void deactivateParticlesForEmitter(WaterEmitter emitter) {
        if (emitter == null) {
            return;
        }
        for (int i = 0; i < particleEmitter.length; i++) {
            if (particleEmitter[i] == emitter) {
                deactivateSlot(i);
            }
        }
    }

    private void pruneEmittersNotInScene(Scene scene) {
        if (scene == null) {
            return;
        }
        List<Entity> entities = scene.getEntities();
        for (Entity entity : entities) {
            if (entity instanceof WaterEmitterEntity waterEntity) {
                WaterEmitter emitter = waterEntity.getEmitter();
                if (!containsEmitter(emitter)) {
                    emitters.add(emitter);
                }
            }
        }
        Iterator<WaterEmitter> it = emitters.iterator();
        while (it.hasNext()) {
            WaterEmitter emitter = it.next();
            Entity entity = emitter.getEntity();
            if (entity == null || !entities.contains(entity)) {
                it.remove();
                deactivateParticlesForEmitter(emitter);
            }
        }
    }

    private boolean containsEmitter(WaterEmitter emitter) {
        if (emitter == null) {
            return false;
        }
        for (WaterEmitter existing : emitters) {
            if (existing == emitter) {
                return true;
            }
        }
        return false;
    }

    private void resetParticleState() {
        random.setSeed(RANDOM_SEED);
        for (int i = 0; i < alive.length; i++) {
            alive[i] = false;
            particleEmitter[i] = null;
            px[i] = 0.0;
            py[i] = 0.0;
            pz[i] = 0.0;
            vx[i] = 0.0;
            vy[i] = 0.0;
            vz[i] = 0.0;
            lifeRemaining[i] = 0.0;
            lifeMax[i] = 0.0;
            radius[i] = 0.0;
        }
        nextSpawnIndex = 0;
        activeCount = 0;
        collisionProxyCount = 0;
        simulationTimeSeconds = 0.0;
    }

    private static boolean isFloorCandidate(Entity entity) {
        if (entity == null) {
            return false;
        }
        String name = entity.getName();
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toLowerCase();
        return "floor-grid".equals(normalized)
                || normalized.contains("floor")
                || normalized.contains("podlaha");
    }

    private static boolean isFinite(Vec3 value) {
        return value != null
                && Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    private static Vec3 buildPerpendicular(Vec3 axis) {
        Vec3 candidate = Math.abs(axis.y) < 0.92 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        Vec3 tangent = axis.cross(candidate).normalize();
        if (tangent.lengthSquared() < 1e-10) {
            tangent = axis.cross(new Vec3(0.0, 0.0, 1.0)).normalize();
        }
        if (tangent.lengthSquared() < 1e-10) {
            return new Vec3(1.0, 0.0, 0.0);
        }
        return tangent;
    }

    private static long mix(long hash, long value) {
        hash ^= value + 0x9E3779B97F4A7C15L + (hash << 6) + (hash >> 2);
        return hash;
    }

    @FunctionalInterface
    public interface ParticleConsumer {
        void accept(WaterEmitter emitter,
                    double px,
                    double py,
                    double pz,
                    double vx,
                    double vy,
                    double vz,
                    double lifeRemaining,
                    double lifeMax,
                    double radius);
    }
}
