package engine.sim.water;

import engine.camera.Camera;
import engine.math.Mat4;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.util.ScreenProjectionUtil;

import java.util.Arrays;

/**
 * Tady držím kompozitor v obrazovém prostoru pro spray a splash částice ze simulace vody.
 * Skládám si v něm tloušťku a barevný nádech a pak výsledek vystíním jako poloprůhledný kapalinový overlay.
 */
public final class WaterParticleRenderer {

    private static float[] thicknessBuffer = new float[0];
    private static float[] tintRBuffer = new float[0];
    private static float[] tintGBuffer = new float[0];
    private static float[] tintBBuffer = new float[0];
    private static float[] depthBuffer = new float[0];

    private static int cachedWidth;
    private static int cachedHeight;

    private WaterParticleRenderer() {
    }

    public static void render(WaterSimulation simulation, Camera camera, FrameBuffer fb) {
        if (simulation == null || camera == null || fb == null || simulation.getActiveParticleCount() <= 0) {
            return;
        }

        final int width = fb.getWidth();
        final int height = fb.getHeight();
        final int[] color = fb.getColorBuffer();
        final float[] sceneDepth = fb.getDepthBuffer();
        if (color == null || sceneDepth == null || color.length != width * height || sceneDepth.length != width * height) {
            return;
        }

        ensureBuffers(width, height);
        Arrays.fill(thicknessBuffer, 0, width * height, 0.0f);
        Arrays.fill(tintRBuffer, 0, width * height, 0.0f);
        Arrays.fill(tintGBuffer, 0, width * height, 0.0f);
        Arrays.fill(tintBBuffer, 0, width * height, 0.0f);
        Arrays.fill(depthBuffer, 0, width * height, Float.POSITIVE_INFINITY);

        final Mat4 vp = camera.getProjectionMatrix().multiply(camera.getViewMatrix());
        final int[] screenPos = new int[2];
        final int[] radiusPos = new int[2];
        final double[] depthOut = new double[1];
        final double[] depthOutRadius = new double[1];
        final Vec3 camRight = camera.getRight().normalize();
        final Vec3 camUp = camera.getUp().normalize();

        simulation.forEachParticle((emitter, px, py, pz, vx, vy, vz, lifeRemaining, lifeMax, radiusWorld) -> {
            Vec3 world = new Vec3(px, py, pz);
            if (!ScreenProjectionUtil.projectWorldPointWithDepth(world, vp, width, height, screenPos, depthOut)) {
                return;
            }
            double particleDepth = depthOut[0];
            if (particleDepth < 0.0 || particleDepth > 1.0) {
                return;
            }

            Vec3 probe = world.add(camRight.mul(radiusWorld));
            if (!ScreenProjectionUtil.projectWorldPointWithDepth(probe, vp, width, height, radiusPos, depthOutRadius)) {
                probe = world.add(camUp.mul(radiusWorld));
                if (!ScreenProjectionUtil.projectWorldPointWithDepth(probe, vp, width, height, radiusPos, depthOutRadius)) {
                    return;
                }
            }

            int radiusPx = (int) Math.round(Math.hypot(radiusPos[0] - screenPos[0], radiusPos[1] - screenPos[1]));
            radiusPx = Math.max(2, Math.min(22, radiusPx));

            double lifeT = lifeMax > 1e-9 ? (lifeRemaining / lifeMax) : 0.0;
            lifeT = clamp01(lifeT);
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double speedLift = Math.min(0.28, speed * 0.035);
            double deposit = emitter.getOpacity() * (0.22 + 0.78 * lifeT) + speedLift;
            deposit = Math.max(0.02, Math.min(0.95, deposit));

            accumulateParticle(
                    width,
                    height,
                    sceneDepth,
                    screenPos[0],
                    screenPos[1],
                    radiusPx,
                    particleDepth,
                    emitter.getTint(),
                    deposit
            );
        });

        compositeOverlay(color, width, height);
    }

    private static void accumulateParticle(int width,
                                           int height,
                                           float[] sceneDepth,
                                           int cx,
                                           int cy,
                                           int radiusPx,
                                           double particleDepth,
                                           Vec3 tint,
                                           double deposit) {
        int minX = Math.max(0, cx - radiusPx);
        int maxX = Math.min(width - 1, cx + radiusPx);
        int minY = Math.max(0, cy - radiusPx);
        int maxY = Math.min(height - 1, cy + radiusPx);
        double invR2 = 1.0 / Math.max(1.0, radiusPx * radiusPx);
        float tr = (float) clamp01(tint == null ? 0.28 : tint.x);
        float tg = (float) clamp01(tint == null ? 0.56 : tint.y);
        float tb = (float) clamp01(tint == null ? 0.88 : tint.z);

        for (int y = minY; y <= maxY; y++) {
            int dy = y - cy;
            int row = y * width;
            for (int x = minX; x <= maxX; x++) {
                int dx = x - cx;
                double d2 = (double) dx * dx + (double) dy * dy;
                if (d2 > radiusPx * radiusPx) {
                    continue;
                }
                int idx = row + x;
                if (particleDepth > sceneDepth[idx] + 0.0012f) {
                    continue;
                }

                double radial = 1.0 - d2 * invR2;
                double soft = radial * radial;
                double core = soft * (0.65 + 0.35 * Math.sqrt(Math.max(0.0, radial)));
                float contribution = (float) (deposit * core);
                if (contribution < 0.003f) {
                    continue;
                }

                thicknessBuffer[idx] += contribution;
                tintRBuffer[idx] += tr * contribution;
                tintGBuffer[idx] += tg * contribution;
                tintBBuffer[idx] += tb * contribution;
                float frontDepth = (float) Math.max(0.0, particleDepth - soft * 0.0025);
                if (frontDepth < depthBuffer[idx]) {
                    depthBuffer[idx] = frontDepth;
                }
            }
        }
    }

    private static void compositeOverlay(int[] colorBuffer, int width, int height) {
        Vec3 lightDir = new Vec3(-0.30, -0.42, 0.86).normalize();
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                float thickness = thicknessBuffer[idx];
                if (thickness <= 0.01f) {
                    continue;
                }

                float left = x > 0 ? thicknessBuffer[idx - 1] : thickness;
                float right = x < width - 1 ? thicknessBuffer[idx + 1] : thickness;
                float up = y > 0 ? thicknessBuffer[idx - width] : thickness;
                float down = y < height - 1 ? thicknessBuffer[idx + width] : thickness;
                double nx = left - right;
                double ny = up - down;
                double nz = 1.0 / (1.0 + thickness * 1.35);
                double invLen = 1.0 / Math.max(1e-6, Math.sqrt(nx * nx + ny * ny + nz * nz));
                nx *= invLen;
                ny *= invLen;
                nz *= invLen;

                double meanR = tintRBuffer[idx] / Math.max(1e-6f, thickness);
                double meanG = tintGBuffer[idx] / Math.max(1e-6f, thickness);
                double meanB = tintBBuffer[idx] / Math.max(1e-6f, thickness);
                double density = Math.min(1.0, thickness * 0.78);
                double absorption = Math.exp(-thickness * 0.82);
                double fresnel = Math.pow(1.0 - Math.max(0.0, nz), 2.6);
                double ndotl = Math.max(0.0, nx * lightDir.x + ny * lightDir.y + nz * lightDir.z);
                double specular = Math.pow(ndotl, 18.0) * (0.18 + fresnel * 0.82);
                double rim = Math.pow(1.0 - Math.max(0.0, nz), 3.0) * 0.22;

                int dst = colorBuffer[idx];
                int dr = (dst >>> 16) & 0xFF;
                int dg = (dst >>> 8) & 0xFF;
                int db = dst & 0xFF;

                double tintedR = dr * absorption + meanR * 255.0 * density * 0.92;
                double tintedG = dg * absorption + meanG * 255.0 * density * 0.97;
                double tintedB = db * absorption + meanB * 255.0 * density * 1.05;
                double highlight = 255.0 * (specular * 0.22 + rim * 0.12);
                double alpha = Math.min(0.88, 0.16 + thickness * 0.42);

                int rr = clamp8((int) Math.round(lerp(dr, tintedR + highlight, alpha)));
                int rg = clamp8((int) Math.round(lerp(dg, tintedG + highlight, alpha)));
                int rb = clamp8((int) Math.round(lerp(db, tintedB + highlight * 1.08, alpha)));
                colorBuffer[idx] = (0xFF << 24) | (rr << 16) | (rg << 8) | rb;
            }
        }
    }

    private static void ensureBuffers(int width, int height) {
        if (width == cachedWidth && height == cachedHeight && thicknessBuffer.length >= width * height) {
            return;
        }
        int size = Math.max(1, width * height);
        thicknessBuffer = new float[size];
        tintRBuffer = new float[size];
        tintGBuffer = new float[size];
        tintBBuffer = new float[size];
        depthBuffer = new float[size];
        cachedWidth = width;
        cachedHeight = height;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int clamp8(int v) {
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return v;
    }
}
