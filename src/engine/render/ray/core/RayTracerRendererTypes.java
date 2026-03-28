package engine.render.ray.core;

import engine.render.ray.bvh.*;
import java.util.concurrent.atomic.LongAdder;

import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.EnvironmentMap;
import engine.scene.PointLight;

final class RayCameraState {
    int width;
    int height;
    boolean perspective;
    double px, py, pz;
    double fx, fy, fz;
    double rx, ry, rz;
    double ux, uy, uz;
    double near;
    double tanHalfFov;
    double aspect;
    double orthoM00, orthoM11, orthoM03, orthoM13;
}

final class RayTriangle {
    final double ax, ay, az;
    final double bx, by, bz;
    final double cx, cy, cz;
    final double e1x, e1y, e1z;
    final double e2x, e2y, e2z;
    final double n0x, n0y, n0z;
    final double n1x, n1y, n1z;
    final double n2x, n2y, n2z;
    final double faceNx, faceNy, faceNz;
    final boolean flatNormal;
    final double u0, v0, u1, v1, u2, v2;
    final boolean hasUV;
    final double u0b, v0b, u1b, v1b, u2b, v2b;
    final boolean hasUV2;
    final double tangentX, tangentY, tangentZ;
    final double baseR, baseG, baseB;
    final double specR, specG, specB;
    final double reflectivity;
    final double roughness;
    final double area;
    final boolean textureLinear;
    final boolean floorGrid;
    final PhongMaterial material;
    final double minX, minY, minZ;
    final double maxX, maxY, maxZ;
    final double centroidX, centroidY, centroidZ;

    RayTriangle(double ax, double ay, double az,
                double bx, double by, double bz,
                double cx, double cy, double cz,
                double n0x, double n0y, double n0z,
                double n1x, double n1y, double n1z,
                double n2x, double n2y, double n2z,
                double u0, double v0, double u1, double v1, double u2, double v2, boolean hasUV,
                double u0b, double v0b, double u1b, double v1b, double u2b, double v2b, boolean hasUV2,
                double tangentX, double tangentY, double tangentZ,
                double baseR, double baseG, double baseB,
                double specR, double specG, double specB,
                double reflectivity, double roughness,
                boolean textureLinear, boolean floorGrid,
                PhongMaterial material) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;

        this.e1x = bx - ax;
        this.e1y = by - ay;
        this.e1z = bz - az;
        this.e2x = cx - ax;
        this.e2y = cy - ay;
        this.e2z = cz - az;

        this.n0x = n0x;
        this.n0y = n0y;
        this.n0z = n0z;
        this.n1x = n1x;
        this.n1y = n1y;
        this.n1z = n1z;
        this.n2x = n2x;
        this.n2y = n2y;
        this.n2z = n2z;

        double fnx = e1y * e2z - e1z * e2y;
        double fny = e1z * e2x - e1x * e2z;
        double fnz = e1x * e2y - e1y * e2x;
        double fLen = Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
        if (fLen > 1e-14) {
            double inv = 1.0 / fLen;
            fnx *= inv;
            fny *= inv;
            fnz *= inv;
        } else {
            fnx = 0.0;
            fny = 1.0;
            fnz = 0.0;
        }
        this.faceNx = fnx;
        this.faceNy = fny;
        this.faceNz = fnz;

        double n0LenSq = n0x * n0x + n0y * n0y + n0z * n0z;
        double n1LenSq = n1x * n1x + n1y * n1y + n1z * n1z;
        double n2LenSq = n2x * n2x + n2y * n2y + n2z * n2z;
        this.flatNormal = n0LenSq < 1e-12 || n1LenSq < 1e-12 || n2LenSq < 1e-12;

        this.u0 = u0;
        this.v0 = v0;
        this.u1 = u1;
        this.v1 = v1;
        this.u2 = u2;
        this.v2 = v2;
        this.hasUV = hasUV;
        this.u0b = u0b;
        this.v0b = v0b;
        this.u1b = u1b;
        this.v1b = v1b;
        this.u2b = u2b;
        this.v2b = v2b;
        this.hasUV2 = hasUV2;
        this.tangentX = tangentX;
        this.tangentY = tangentY;
        this.tangentZ = tangentZ;

        this.baseR = baseR;
        this.baseG = baseG;
        this.baseB = baseB;
        this.specR = specR;
        this.specG = specG;
        this.specB = specB;
        this.reflectivity = reflectivity;
        this.roughness = roughness;
        this.textureLinear = textureLinear;
        this.floorGrid = floorGrid;
        this.material = material;

        this.minX = Math.min(ax, Math.min(bx, cx));
        this.minY = Math.min(ay, Math.min(by, cy));
        this.minZ = Math.min(az, Math.min(bz, cz));
        this.maxX = Math.max(ax, Math.max(bx, cx));
        this.maxY = Math.max(ay, Math.max(by, cy));
        this.maxZ = Math.max(az, Math.max(bz, cz));
        this.centroidX = (ax + bx + cx) / 3.0;
        this.centroidY = (ay + by + cy) / 3.0;
        this.centroidZ = (az + bz + cz) / 3.0;

        this.area = fLen * 0.5;
    }
}

final class RayBVHNode {
    double minX, minY, minZ;
    double maxX, maxY, maxZ;
    RayBVHNode left;
    RayBVHNode right;
    int start;
    int end;
}

final class RayHit {
    double t;
    double u;
    double v;
    double px, py, pz;
    RayTriangle triangle;

    void copyFrom(RayHit other) {
        t = other.t;
        u = other.u;
        v = other.v;
        px = other.px;
        py = other.py;
        pz = other.pz;
        triangle = other.triangle;
    }
}

final class RayDirLightCache {
    final double lx, ly, lz;
    final double r, g, b;

    RayDirLightCache(double lx, double ly, double lz, double r, double g, double b) {
        this.lx = lx;
        this.ly = ly;
        this.lz = lz;
        this.r = r;
        this.g = g;
        this.b = b;
    }
}

final class RayPointLightCache {
    final PointLight light;
    final double px, py, pz;
    final double r, g, b;
    final double luminance;

    RayPointLightCache(PointLight light, double px, double py, double pz, double r, double g, double b) {
        this.light = light;
        this.px = px;
        this.py = py;
        this.pz = pz;
        this.r = r;
        this.g = g;
        this.b = b;
        this.luminance = DenoiseSupport.luminance(r, g, b);
    }
}

final class RayCarrierTraceMetrics {
    final LongAdder primaryIntersectionNanos = new LongAdder();
    final LongAdder surfaceSampleNanos = new LongAdder();
    final LongAdder directLightNanos = new LongAdder();
    final LongAdder shadowQueryNanos = new LongAdder();
    final LongAdder environmentNanos = new LongAdder();
    final LongAdder extraMaterialLobesNanos = new LongAdder();
    final LongAdder directionalLightNanos = new LongAdder();
    final LongAdder pointLightNanos = new LongAdder();
    final LongAdder spotLightNanos = new LongAdder();
    final LongAdder areaLightNanos = new LongAdder();
    final LongAdder localLightCandidates = new LongAdder();
    final LongAdder localLightShaded = new LongAdder();
    final LongAdder localLightShadowed = new LongAdder();
}

final class RaySurfaceState {
    double nx, ny, nz;
    double smoothNx, smoothNy, smoothNz;
    double geomNx, geomNy, geomNz;
    double baseR, baseG, baseB;
    double specR, specG, specB;
    double roughness;
    double reflectivity;
    double clearcoatFactor;
    double clearcoatRoughness;
    double opacity;
    double transmission;
    double refractiveIndex;
    double dispersion;
    double emissionR, emissionG, emissionB;
    double mediumR, mediumG, mediumB;
    double density;
    double thickness;
    double sheenR, sheenG, sheenB;
    double sheenRoughness;
    boolean discard;
}

final class RayTraceContext {
    RayBVHNode[] nodeStack = new RayBVHNode[1024];
    final RayHit hit = new RayHit();
    final RayHit tempHit = new RayHit();
    final RaySurfaceState surface = new RaySurfaceState();
    final RaySurfaceState shadowSurface = new RaySurfaceState();
    final Vec3 tmpVec0 = new Vec3();
    final Vec3 tmpVec1 = new Vec3();
    final Vec3 tmpVec2 = new Vec3();
    final Vec3 tmpVec3 = new Vec3();
    final Vec3 tmpVec4 = new Vec3();
    final Vec3 tmpVec5 = new Vec3();
    final EnvironmentMap.Sample environmentSample = new EnvironmentMap.Sample();
    double rayOx, rayOy, rayOz;
    double rayDx, rayDy, rayDz;
    double shadowBaseX, shadowBaseY, shadowBaseZ;
    double shadowOx, shadowOy, shadowOz;
    double shadowTMin;
    double environmentDirX, environmentDirY, environmentDirZ;
    double envR, envG, envB;
    double secondaryOx, secondaryOy, secondaryOz;
    double secondaryDx, secondaryDy, secondaryDz;
    double secondaryThroughputR, secondaryThroughputG, secondaryThroughputB;
    double outR, outG, outB;
    RayCarrierTraceMetrics carrierMetrics;
    boolean carrierDisableSheen;
    boolean carrierDisableClearcoat;
    boolean carrierDisableDirectionalShadows;
    boolean carrierDisablePointLightShadows;
    boolean carrierPointLightsDiffuseOnly;
    boolean carrierDiffuseOnlyDirectLighting;
    int carrierMaxLocalLights = -1;
    int carrierMaxShadowedLocalLights = -1;
    int carrierLeafSampleScale;
    final int[] selectedLocalLightIndices = new int[4];
    final double[] selectedLocalLightImportance = new double[4];
    final double[] selectedLocalLightLx = new double[4];
    final double[] selectedLocalLightLy = new double[4];
    final double[] selectedLocalLightLz = new double[4];
    final double[] selectedLocalLightDist = new double[4];
    final double[] selectedLocalLightAtt = new double[4];
    final double[] selectedLocalLightNDotL = new double[4];
    int selectedLocalLightCandidateCount;
    boolean primaryGuidePending;
    boolean primaryGuideCaptured;
    boolean primaryGuideHasHit;
    double primaryGuideTravel;
    double primaryGuideDepth;
    double primaryGuideNx, primaryGuideNy, primaryGuideNz;
    double primaryGuideBaseR, primaryGuideBaseG, primaryGuideBaseB;
    double primaryGuideRoughness;
}

final class RaySplitMix64 {
    private long state;

    RaySplitMix64(long seed) {
        this.state = seed;
    }

    long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    double nextDouble() {
        return ((nextLong() >>> 11) * 0x1.0p-53);
    }
}

