package engine.physics;

import engine.math.Ray;
import engine.math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Represents jednoduchý fyzikální svět pro rigid body.
 */
public class PhysicsWorld {

    private Vec3 gravity;
    private final List<RigidBody> bodies;
    private final CollisionDetector detector;
    private final CollisionResolver resolver;
    private double fixedTimeStep;

    public PhysicsWorld() {
        this(new Vec3(0.0, -9.81, 0.0));
    }

    public PhysicsWorld(Vec3 gravity) {
        this.gravity = gravity;
        this.bodies = new ArrayList<>();
        this.detector = new CollisionDetector();
        this.resolver = new CollisionResolver();
        this.fixedTimeStep = 1.0 / 60.0;
    }

 // spravuje těla ve fyzikálním světě.
    public void addBody(RigidBody body) {
        if (body != null) {
            bodies.add(body);
        }
    }

    public void removeBody(RigidBody body) {
        bodies.remove(body);
    }

    public List<RigidBody> getBodies() {
        return Collections.unmodifiableList(bodies);
    }

 /**
 * posunu fyziku o jeden fixed timestep.
 *
 * @param dt fixed delta čas v sekundách
 */
    public void step(double dt) {
        double step = dt > 0.0 ? dt : fixedTimeStep;
        for (RigidBody body : bodies) {
            body.integrate(step, gravity);
        }
        List<CollisionResult> contacts = detector.detectCollisions(bodies);
        resolver.resolve(contacts);
    }

 // Represents základní konfiguraci světa.
    public void setGravity(Vec3 g) {
        gravity = g;
    }

    public Vec3 getGravity() {
        return gravity;
    }

    public double getFixedTimeStep() {
        return fixedTimeStep;
    }

    public void setFixedTimeStep(double fixedTimeStep) {
        this.fixedTimeStep = Math.max(1e-4, fixedTimeStep);
    }

 // Handles raycasty proti fyzikálním tělesům.
    public RaycastResult raycast(Ray ray, double maxDistance) {
        List<RaycastResult> all = raycastAll(ray, maxDistance);
        return all.isEmpty() ? new RaycastResult() : all.get(0);
    }

    public List<RaycastResult> raycastAll(Ray ray, double maxDistance) {
        List<RaycastResult> hits = new ArrayList<>();
        for (RigidBody body : bodies) {
            if (body.getCollider() == null) {
                continue;
            }
            double t = body.getCollider().getWorldAABB(body.getPosition()).intersectRay(ray);
            if (t != Double.MAX_VALUE && t >= 0.0 && t <= maxDistance) {
                Vec3 point = ray.pointAt(t);
                Vec3 normal = point.sub(body.getPosition()).normalize();
                RaycastResult result = new RaycastResult();
                result.set(t, point, normal, body);
                hits.add(result);
            }
        }
        hits.sort(Comparator.comparingDouble(RaycastResult::getDistance));
        return hits;
    }
}