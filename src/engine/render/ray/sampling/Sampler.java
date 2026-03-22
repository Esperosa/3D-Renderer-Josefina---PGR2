package engine.render.ray.core;

import engine.render.ray.bvh.*;
import engine.math.Vec2;
import engine.math.Vec3;

/**
 * Random sampling utilities for Monte Carlo ray tracing.
 */
public class Sampler {

    private long state;

    public Sampler() {
        this(System.nanoTime());
    }

    public Sampler(long seed) {
        state = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
    }

 // Hemisphere sampling.
    public Vec3 cosineWeightedHemisphere(Vec3 normal) {
        Vec3 n = safeNormal(normal);
        Vec3[] onb = buildOnb(n);
        Vec3 tangent = onb[0];
        Vec3 bitangent = onb[1];

        double r1 = randomDouble();
        double r2 = randomDouble();
        double r = Math.sqrt(r1);
        double theta = 2.0 * Math.PI * r2;

        double x = r * Math.cos(theta);
        double y = r * Math.sin(theta);
        double z = Math.sqrt(Math.max(0.0, 1.0 - r1));

        Vec3 dir = tangent.mul(x).add(bitangent.mul(y)).add(n.mul(z));
        return dir.normalize();
    }

    public Vec3 uniformHemisphere(Vec3 normal) {
        Vec3 n = safeNormal(normal);
        Vec3[] onb = buildOnb(n);
        Vec3 tangent = onb[0];
        Vec3 bitangent = onb[1];

        double u = randomDouble();
        double v = randomDouble();
        double z = u;
        double r = Math.sqrt(Math.max(0.0, 1.0 - z * z));
        double phi = 2.0 * Math.PI * v;
        double x = r * Math.cos(phi);
        double y = r * Math.sin(phi);

        Vec3 dir = tangent.mul(x).add(bitangent.mul(y)).add(n.mul(z));
        return dir.normalize();
    }

 // Disk and pixel sampling.
    public Vec2 uniformDisk() {
        double u = randomDouble() * 2.0 - 1.0;
        double v = randomDouble() * 2.0 - 1.0;
        if (u == 0.0 && v == 0.0) {
            return new Vec2(0.0, 0.0);
        }

        double r;
        double theta;
        if (Math.abs(u) > Math.abs(v)) {
            r = u;
            theta = Math.PI * 0.25 * (v / u);
        } else {
            r = v;
            theta = Math.PI * 0.5 - Math.PI * 0.25 * (u / v);
        }
        return new Vec2(r * Math.cos(theta), r * Math.sin(theta));
    }

    public Vec2 subPixelJitter() {
        return new Vec2(randomDouble(), randomDouble());
    }

 // Utility methods.
    public double randomDouble() {
        long z = nextLong();
        return ((z >>> 11) & ((1L << 53) - 1)) * 0x1.0p-53;
    }

    public double randomInRange(double min, double max) {
        if (min > max) {
            double tmp = min;
            min = max;
            max = tmp;
        }
        return min + (max - min) * randomDouble();
    }

    private long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private Vec3 safeNormal(Vec3 normal) {
        if (normal == null) {
            return Vec3.UP;
        }
        Vec3 n = normal.normalize();
        return n.lengthSquared() < 1e-10 ? Vec3.UP : n;
    }

    private Vec3[] buildOnb(Vec3 n) {
        Vec3 tangent = Math.abs(n.y) < 0.999
                ? n.cross(Vec3.UP).normalize()
                : n.cross(new Vec3(1.0, 0.0, 0.0)).normalize();
        if (tangent.lengthSquared() < 1e-10) {
            tangent = new Vec3(1.0, 0.0, 0.0);
        }
        Vec3 bitangent = tangent.cross(n).normalize();
        if (bitangent.lengthSquared() < 1e-10) {
            bitangent = new Vec3(0.0, 0.0, 1.0);
        }
        return new Vec3[]{tangent, bitangent};
    }
}
