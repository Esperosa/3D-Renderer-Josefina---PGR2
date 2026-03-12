package engine.render.ray;

import engine.camera.Camera;
import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.MaterialGraphEvaluator;
import engine.material.Material;
import engine.material.PhongMaterial;
import engine.material.TextureMap;
import engine.math.Mat3;
import engine.math.Mat4;
import engine.math.Quaternion;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.Texture;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.scene.Scene;
import engine.scene.Transform;
import engine.util.ThreadPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tady držím progresivní CPU ray tracer s BVH akcelerací, tiled renderingem a volitelným odšuměním.
 */
public class RayTracerRenderer implements Renderer {

    private static final double RAY_EPS = 1e-4;
    private static final double INF_T = 1e30;
    private static final double INV_GAMMA = 1.0 / 2.2;

    private int width = 1;
    private int height = 1;
    private int workerCount = ThreadPool.recommendedWorkerCount();
    private int samplesPerFrame = 1;
    private int maxDepth = 3;
    private int tileSize = 24;
    private int leafSize = 8;
    private double exposure = 1.22;
    private boolean directLighting = true;
    private boolean shadowsEnabled = true;
    private boolean reflectionsEnabled = true;
    private boolean skyEnabled = true;
    private boolean denoiseEnabled = true;
    private int denoiseStartSamples = 6;
    private int denoiseRadius = 1;
    private double denoiseStrength = 0.28;

    private ThreadPool threadPool;

    private double[] accumR = new double[1];
    private double[] accumG = new double[1];
    private double[] accumB = new double[1];
    private double[] denoiseR = new double[1];
    private double[] denoiseG = new double[1];
    private double[] denoiseB = new double[1];
    private float[] guideDepth = new float[1];
    private float[] guideNormal = new float[3];
    private long accumulatedSamples = 0;

    private Triangle[] triangles = new Triangle[0];
    private int triangleCount = 0;
    private int[] triangleOrder = new int[0];
    private double[] centroidX = new double[0];
    private double[] centroidY = new double[0];
    private double[] centroidZ = new double[0];
    private BVHNode bvhRoot;

    private DirLightCache[] dirLights = new DirLightCache[0];
    private PointLightCache[] pointLights = new PointLightCache[0];
    private double ambientR = 0.1;
    private double ambientG = 0.1;
    private double ambientB = 0.1;
    private double backgroundR = 0.06;
    private double backgroundG = 0.07;
    private double backgroundB = 0.09;
    private double environmentStrength = 1.0;

    private long geometrySignature = Long.MIN_VALUE;
    private long lightingSignature = Long.MIN_VALUE;
    private long cameraSignature = Long.MIN_VALUE;

    @Override
    public synchronized void init(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        allocateAccumulation(this.width, this.height);
        rebuildThreadPool();
        resetAccumulation();
    }

    @Override
    public synchronized void render(Scene scene, Camera camera, FrameBuffer fb, double time) {
        int fbWidth = fb.getWidth();
        int fbHeight = fb.getHeight();
        if (fbWidth != width || fbHeight != height) {
            resize(fbWidth, fbHeight);
        }

        long gSig = computeGeometrySignature(scene);
        if (gSig != geometrySignature) {
            geometrySignature = gSig;
            rebuildGeometry(scene);
            resetAccumulation();
        }

        long lSig = computeLightingSignature(scene);
        if (lSig != lightingSignature) {
            lightingSignature = lSig;
            rebuildLightCache(scene);
            resetAccumulation();
        }

        long cSig = computeCameraSignature(camera, fbWidth, fbHeight);
        if (cSig != cameraSignature) {
            cameraSignature = cSig;
            resetAccumulation();
        }

        if (triangleCount == 0 || bvhRoot == null) {
            fillBackground(fb);
            return;
        }

        Arrays.fill(fb.getDepthBuffer(), 1.0f);
        int[] outColor = fb.getColorBuffer();
        if (!ensureRuntimeBuffers(fbWidth, fbHeight, outColor)) {
            fillBackground(fb);
            return;
        }

        final long sampleTarget = accumulatedSamples + samplesPerFrame;
        final double invSamples = 1.0 / Math.max(1L, sampleTarget);
        final boolean captureGuides = denoiseEnabled;

        CameraState cam = buildCameraState(camera, fbWidth, fbHeight);
        int tileW = Math.max(8, tileSize);
        int tileH = tileW;
        int tileCols = (fbWidth + tileW - 1) / tileW;
        int tileRows = (fbHeight + tileH - 1) / tileH;
        int tileCount = tileCols * tileRows;

        if (workerCount <= 1 || threadPool == null || tileCount <= 1) {
            TraceContext ctx = new TraceContext();
            SplitMix64 rng = new SplitMix64(seedForWorker(0, sampleTarget));
            for (int tile = 0; tile < tileCount; tile++) {
                renderTile(tile, tileCols, tileW, tileH, fbWidth, fbHeight, cam, outColor, invSamples, captureGuides, rng, ctx);
            }
            accumulatedSamples = sampleTarget;
        } else {
            AtomicInteger tileCursor = new AtomicInteger(0);
            Runnable[] tasks = new Runnable[workerCount];
            for (int w = 0; w < workerCount; w++) {
                final int workerIndex = w;
                tasks[w] = () -> {
                    TraceContext ctx = new TraceContext();
                    SplitMix64 rng = new SplitMix64(seedForWorker(workerIndex, sampleTarget));
                    int tile;
                    while ((tile = tileCursor.getAndIncrement()) < tileCount) {
                        renderTile(tile, tileCols, tileW, tileH, fbWidth, fbHeight, cam, outColor, invSamples, captureGuides, rng, ctx);
                    }
                };
            }
            threadPool.submitAndWait(tasks);
            accumulatedSamples = sampleTarget;
        }

        if (denoiseEnabled && accumulatedSamples >= 2) {
            applyDenoiseAndResolve(outColor, invSamples);
        }
    }

    @Override
    public synchronized void resize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        allocateAccumulation(this.width, this.height);
        resetAccumulation();
    }

    @Override
    public synchronized void setParameter(String key, Object value) {
        if (key == null) {
            return;
        }
        String k = key.trim().toLowerCase();
        if (("workercount".equals(k) || "threads".equals(k)) && value instanceof Number) {
            int next = Math.max(1, Math.min(ThreadPool.recommendedWorkerCount(), ((Number) value).intValue()));
            if (next != workerCount) {
                workerCount = next;
                rebuildThreadPool();
            }
            return;
        }
        if (("samplesperframe".equals(k) || "spp".equals(k)) && value instanceof Number) {
            samplesPerFrame = Math.max(1, Math.min(64, ((Number) value).intValue()));
            resetAccumulation();
            return;
        }
        if (("maxdepth".equals(k) || "maxbounces".equals(k) || "depth".equals(k)) && value instanceof Number) {
            maxDepth = Math.max(1, Math.min(16, ((Number) value).intValue()));
            resetAccumulation();
            return;
        }
        if ("tilesize".equals(k) && value instanceof Number) {
            tileSize = Math.max(8, Math.min(128, ((Number) value).intValue()));
            return;
        }
        if ("leafsize".equals(k) && value instanceof Number) {
            leafSize = Math.max(2, Math.min(32, ((Number) value).intValue()));
            rebuildBvh();
            resetAccumulation();
            return;
        }
        if ("exposure".equals(k) && value instanceof Number) {
            exposure = Math.max(0.05, Math.min(10.0, ((Number) value).doubleValue()));
            return;
        }
        if ("directlighting".equals(k) && value instanceof Boolean) {
            directLighting = (Boolean) value;
            resetAccumulation();
            return;
        }
        if ("shadows".equals(k) && value instanceof Boolean) {
            shadowsEnabled = (Boolean) value;
            resetAccumulation();
            return;
        }
        if ("reflections".equals(k) && value instanceof Boolean) {
            reflectionsEnabled = (Boolean) value;
            resetAccumulation();
            return;
        }
        if ("sky".equals(k) && value instanceof Boolean) {
            skyEnabled = (Boolean) value;
            resetAccumulation();
            return;
        }
        if (("denoise".equals(k) || "denoiseenabled".equals(k)) && value instanceof Boolean) {
            denoiseEnabled = (Boolean) value;
            return;
        }
        if ("denoisestartsamples".equals(k) && value instanceof Number) {
            denoiseStartSamples = Math.max(1, Math.min(10000, ((Number) value).intValue()));
            return;
        }
        if ("denoiseradius".equals(k) && value instanceof Number) {
            denoiseRadius = Math.max(1, Math.min(4, ((Number) value).intValue()));
            return;
        }
        if ("denoisestrength".equals(k) && value instanceof Number) {
            denoiseStrength = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if ("reset".equals(k) || "resetaccumulation".equals(k)) {
            resetAccumulation();
            return;
        }
        if ("shutdown".equals(k) && value instanceof Boolean && (Boolean) value) {
            shutdownPool();
        }
    }

    @Override
    public String getName() {
        return "Ray Tracer (CPU)";
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public long getAccumulatedSamples() {
        return accumulatedSamples;
    }

    public int getSamplesPerFrame() {
        return samplesPerFrame;
    }

    private void renderTile(int tileIndex,
                            int tileCols,
                            int tileW,
                            int tileH,
                            int fbWidth,
                            int fbHeight,
                            CameraState camera,
                            int[] outColor,
                            double invSamples,
                            boolean captureGuides,
                            SplitMix64 rng,
                            TraceContext ctx) {
        int tileX = tileIndex % tileCols;
        int tileY = tileIndex / tileCols;
        int x0 = tileX * tileW;
        int y0 = tileY * tileH;
        int x1 = Math.min(fbWidth, x0 + tileW);
        int y1 = Math.min(fbHeight, y0 + tileH);

        for (int y = y0; y < y1; y++) {
            int row = y * fbWidth;
            for (int x = x0; x < x1; x++) {
                int idx = row + x;
                if (idx < 0 || idx >= accumR.length || idx >= outColor.length) {
                    continue;
                }

                if (captureGuides) {
                    capturePrimaryGuide(camera, x, y, idx, ctx);
                }

                double batchR = 0.0;
                double batchG = 0.0;
                double batchB = 0.0;

                for (int s = 0; s < samplesPerFrame; s++) {
                    generatePrimaryRay(camera, x, y, rng, ctx);
                    traceRay(ctx);
                    batchR += ctx.outR;
                    batchG += ctx.outG;
                    batchB += ctx.outB;
                }

                accumR[idx] += batchR;
                accumG[idx] += batchG;
                accumB[idx] += batchB;

                outColor[idx] = packColor(
                        toneMap(accumR[idx] * invSamples),
                        toneMap(accumG[idx] * invSamples),
                        toneMap(accumB[idx] * invSamples)
                );
            }
        }
    }

    private void traceRay(TraceContext ctx) {
        double ox = ctx.rayOx;
        double oy = ctx.rayOy;
        double oz = ctx.rayOz;
        double dx = ctx.rayDx;
        double dy = ctx.rayDy;
        double dz = ctx.rayDz;

        double throughputR = 1.0;
        double throughputG = 1.0;
        double throughputB = 1.0;

        double radianceR = 0.0;
        double radianceG = 0.0;
        double radianceB = 0.0;

        for (int depth = 0; depth < maxDepth; depth++) {
            if (!intersectClosest(ox, oy, oz, dx, dy, dz, RAY_EPS, INF_T, ctx.hit, ctx)) {
                if (hasVisibleEnvironment()) {
                    sampleEnvironment(dx, dy, dz, ctx);
                    radianceR += throughputR * ctx.envR;
                    radianceG += throughputG * ctx.envG;
                    radianceB += throughputB * ctx.envB;
                }
                break;
            }

            Triangle tri = ctx.hit.triangle;
            sampleSurface(tri, ctx.hit, dx, dy, dz, ctx.surface, ctx);
            if (ctx.surface.discard) {
                ox = ctx.hit.px + dx * RAY_EPS;
                oy = ctx.hit.py + dy * RAY_EPS;
                oz = ctx.hit.pz + dz * RAY_EPS;
                continue;
            }

            double nx = ctx.surface.nx;
            double ny = ctx.surface.ny;
            double nz = ctx.surface.nz;
            double baseR = ctx.surface.baseR;
            double baseG = ctx.surface.baseG;
            double baseB = ctx.surface.baseB;
            double lightR = 0.0;
            double lightG = 0.0;
            double lightB = 0.0;

            if (directLighting) {
                double vx = -dx;
                double vy = -dy;
                double vz = -dz;
                double specPower = 8.0 + (1.0 - ctx.surface.roughness) * 112.0;

                for (DirLightCache light : dirLights) {
                    double nDotL = nx * light.lx + ny * light.ly + nz * light.lz;
                    if (nDotL <= 0.0) {
                        continue;
                    }
                    prepareShadowRay(ctx.hit, ctx.surface, light.lx, light.ly, light.lz, ctx);
                    if (shadowsEnabled && intersectAny(
                            ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                            light.lx, light.ly, light.lz,
                            ctx.shadowTMin, INF_T, ctx)) {
                        continue;
                    }

                    double hx = light.lx + vx;
                    double hy = light.ly + vy;
                    double hz = light.lz + vz;
                    double hLenSq = hx * hx + hy * hy + hz * hz;
                    double spec = 0.0;
                    if (hLenSq > 1e-14) {
                        double invH = 1.0 / Math.sqrt(hLenSq);
                        hx *= invH;
                        hy *= invH;
                        hz *= invH;
                        double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                        spec = Math.pow(nDotH, specPower);
                    }

                    lightR += baseR * light.r * nDotL;
                    lightG += baseG * light.g * nDotL;
                    lightB += baseB * light.b * nDotL;
                    lightR += ctx.surface.specR * light.r * spec;
                    lightG += ctx.surface.specG * light.g * spec;
                    lightB += ctx.surface.specB * light.b * spec;
                }

                for (PointLightCache light : pointLights) {
                    double lx = light.px - ctx.hit.px;
                    double ly = light.py - ctx.hit.py;
                    double lz = light.pz - ctx.hit.pz;
                    double distSq = lx * lx + ly * ly + lz * lz;
                    if (distSq < 1e-12) {
                        continue;
                    }
                    double dist = Math.sqrt(distSq);
                    double invDist = 1.0 / dist;
                    lx *= invDist;
                    ly *= invDist;
                    lz *= invDist;

                    double nDotL = nx * lx + ny * ly + nz * lz;
                    if (nDotL <= 0.0) {
                        continue;
                    }
                    prepareShadowRay(ctx.hit, ctx.surface, lx, ly, lz, ctx);
                    if (shadowsEnabled && intersectAny(
                            ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                            lx, ly, lz,
                            ctx.shadowTMin, Math.max(ctx.shadowTMin, dist - RAY_EPS), ctx)) {
                        continue;
                    }

                    double att = light.light.attenuation(dist)
                            * light.light.angularAttenuation(ctx.hit.px, ctx.hit.py, ctx.hit.pz);
                    if (att <= 0.0) {
                        continue;
                    }

                    double hx = lx + vx;
                    double hy = ly + vy;
                    double hz = lz + vz;
                    double hLenSq = hx * hx + hy * hy + hz * hz;
                    double spec = 0.0;
                    if (hLenSq > 1e-14) {
                        double invH = 1.0 / Math.sqrt(hLenSq);
                        hx *= invH;
                        hy *= invH;
                        hz *= invH;
                        double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                        spec = Math.pow(nDotH, specPower);
                    }

                    lightR += baseR * light.r * att * nDotL;
                    lightG += baseG * light.g * att * nDotL;
                    lightB += baseB * light.b * att * nDotL;
                    lightR += ctx.surface.specR * light.r * att * spec;
                    lightG += ctx.surface.specG * light.g * att * spec;
                    lightB += ctx.surface.specB * light.b * att * spec;
                }
            }

            double ndotv = Math.max(0.0, -(dx * nx + dy * ny + dz * nz));
            double fresnel = schlickFresnel(ndotv, ctx.surface.refractiveIndex);
            double reflectionStrength = reflectionsEnabled
                    ? clamp01(Math.max(ctx.surface.reflectivity, fresnel + tri.material.getClearcoatFactor() * 0.14))
                    : 0.0;
            double transmissionStrength = clamp01(ctx.surface.transmission * (1.0 - fresnel));
            double localWeight = clamp01((1.0 - transmissionStrength) * Math.max(0.12, 1.0 - reflectionStrength * 0.35));
            double ambientLightR = baseR * ambientR * localWeight;
            double ambientLightG = baseG * ambientG * localWeight;
            double ambientLightB = baseB * ambientB * localWeight;
            double sheenWeight = Math.pow(1.0 - ndotv, 1.0 + tri.material.getSheenRoughness() * 5.0);

            radianceR += throughputR * (ambientLightR + lightR * localWeight + ctx.surface.emissionR + ctx.surface.sheenR * sheenWeight * 0.25);
            radianceG += throughputG * (ambientLightG + lightG * localWeight + ctx.surface.emissionG + ctx.surface.sheenG * sheenWeight * 0.25);
            radianceB += throughputB * (ambientLightB + lightB * localWeight + ctx.surface.emissionB + ctx.surface.sheenB * sheenWeight * 0.25);

            if (depth + 1 >= maxDepth) {
                break;
            }

            if (transmissionStrength > reflectionStrength && transmissionStrength > 1e-5) {
                Vec3 refracted = ctx.tmpVec0.set(dx, dy, dz)
                        .refract(ctx.tmpVec1.set(nx, ny, nz), ctx.surface.refractiveIndex, ctx.tmpVec0);
                if (refracted.lengthSquared() > 1e-10) {
                    double tint = clamp01(ctx.surface.density * Math.max(0.05, ctx.surface.thickness) + transmissionStrength * 0.25);
                    throughputR *= transmissionStrength * mix(1.0, ctx.surface.mediumR, tint);
                    throughputG *= transmissionStrength * mix(1.0, ctx.surface.mediumG, tint);
                    throughputB *= transmissionStrength * mix(1.0, ctx.surface.mediumB, tint);
                    if ((throughputR + throughputG + throughputB) < 1e-4) {
                        break;
                    }
                    ox = ctx.hit.px + refracted.x * RAY_EPS;
                    oy = ctx.hit.py + refracted.y * RAY_EPS;
                    oz = ctx.hit.pz + refracted.z * RAY_EPS;
                    dx = refracted.x;
                    dy = refracted.y;
                    dz = refracted.z;
                    continue;
                }
                reflectionStrength = Math.max(reflectionStrength, 0.9);
            }

            if (!reflectionsEnabled || reflectionStrength <= 1e-5) {
                break;
            }

            throughputR *= reflectionStrength * Math.max(0.05, ctx.surface.specR);
            throughputG *= reflectionStrength * Math.max(0.05, ctx.surface.specG);
            throughputB *= reflectionStrength * Math.max(0.05, ctx.surface.specB);
            if ((throughputR + throughputG + throughputB) < 1e-4) {
                break;
            }

            double rDotN = dx * nx + dy * ny + dz * nz;
            double nextDx = dx - 2.0 * rDotN * nx;
            double nextDy = dy - 2.0 * rDotN * ny;
            double nextDz = dz - 2.0 * rDotN * nz;
            double invLen = 1.0 / Math.sqrt(nextDx * nextDx + nextDy * nextDy + nextDz * nextDz);
            nextDx *= invLen;
            nextDy *= invLen;
            nextDz *= invLen;

            ox = ctx.hit.px + nx * RAY_EPS;
            oy = ctx.hit.py + ny * RAY_EPS;
            oz = ctx.hit.pz + nz * RAY_EPS;
            dx = nextDx;
            dy = nextDy;
            dz = nextDz;
        }

        ctx.outR = radianceR;
        ctx.outG = radianceG;
        ctx.outB = radianceB;
    }

    private void sampleSurface(Triangle tri, Hit hit, double dx, double dy, double dz, SurfaceState out, TraceContext ctx) {
        double w0 = 1.0 - hit.u - hit.v;
        double geomNx = tri.faceNx;
        double geomNy = tri.faceNy;
        double geomNz = tri.faceNz;
        if ((geomNx * dx + geomNy * dy + geomNz * dz) > 0.0) {
            geomNx = -geomNx;
            geomNy = -geomNy;
            geomNz = -geomNz;
        }
        double nx;
        double ny;
        double nz;
        if (tri.flatNormal) {
            nx = geomNx;
            ny = geomNy;
            nz = geomNz;
        } else {
            nx = tri.n0x * w0 + tri.n1x * hit.u + tri.n2x * hit.v;
            ny = tri.n0y * w0 + tri.n1y * hit.u + tri.n2y * hit.v;
            nz = tri.n0z * w0 + tri.n1z * hit.u + tri.n2z * hit.v;
            double nLenSq = nx * nx + ny * ny + nz * nz;
            if (nLenSq < 1e-14) {
                nx = geomNx;
                ny = geomNy;
                nz = geomNz;
            } else {
                double inv = 1.0 / Math.sqrt(nLenSq);
                nx *= inv;
                ny *= inv;
                nz *= inv;
            }
        }
        if ((nx * dx + ny * dy + nz * dz) > 0.0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }

        PhongMaterial material = tri.material;
        double uv0U = tri.hasUV ? tri.u0 * w0 + tri.u1 * hit.u + tri.u2 * hit.v : 0.0;
        double uv0V = tri.hasUV ? tri.v0 * w0 + tri.v1 * hit.u + tri.v2 * hit.v : 0.0;
        double uv1U = tri.hasUV2 ? tri.u0b * w0 + tri.u1b * hit.u + tri.u2b * hit.v : 0.0;
        double uv1V = tri.hasUV2 ? tri.v0b * w0 + tri.v1b * hit.u + tri.v2b * hit.v : 0.0;
        MaterialGraphEvaluator.Result graph = material != null && material.hasNodeGraph()
                ? MaterialGraphEvaluator.evaluateTriangleShared(
                material,
                hit.px,
                hit.py,
                hit.pz,
                tri.hasUV,
                uv0U,
                uv0V,
                tri.hasUV2,
                uv1U,
                uv1V
        ) : null;
        boolean graphApplied = graph != null && graph.graphApplied;
        out.discard = false;
        out.smoothNx = nx;
        out.smoothNy = ny;
        out.smoothNz = nz;
        out.nx = nx;
        out.ny = ny;
        out.nz = nz;
        out.geomNx = geomNx;
        out.geomNy = geomNy;
        out.geomNz = geomNz;
        out.baseR = graphApplied ? graph.baseColor.x : tri.baseR;
        out.baseG = graphApplied ? graph.baseColor.y : tri.baseG;
        out.baseB = graphApplied ? graph.baseColor.z : tri.baseB;
        if (tri.floorGrid) {
            double cell = 2.0;
            int gx = (int) Math.floor(hit.px / cell);
            int gz = (int) Math.floor(hit.pz / cell);
            boolean even = ((gx + gz) & 1) == 0;
            if (even) {
                out.baseR = 0.56;
                out.baseG = 0.58;
                out.baseB = 0.61;
            } else {
                out.baseR = 0.36;
                out.baseG = 0.39;
                out.baseB = 0.43;
            }
        }

        out.opacity = clamp01(graphApplied ? graph.opacity : material.getOpacity());
        if (!graphApplied) {
            TextureMap diffuseMap = material.getDiffuseMap();
            if (canSampleTextureMap(diffuseMap, tri)) {
                int baseTexel = sampleTextureMap(diffuseMap, tri, w0, hit.u, hit.v);
                out.baseR *= ((baseTexel >> 16) & 0xFF) / 255.0;
                out.baseG *= ((baseTexel >> 8) & 0xFF) / 255.0;
                out.baseB *= (baseTexel & 0xFF) / 255.0;
                out.opacity *= ((baseTexel >>> 24) & 0xFF) / 255.0;
            }
        }
        if (material.getAlphaMode() == PhongMaterial.AlphaMode.MASK
                && out.opacity < material.getAlphaCutoff()) {
            out.discard = true;
            return;
        }

        double roughness = clamp01(graphApplied ? graph.roughness : material.getRoughness());
        double metallic = clamp01(graphApplied ? graph.metallic : material.getMetallic());
        if (!graphApplied) {
            TextureMap metallicRoughnessMap = material.getMetallicRoughnessMap();
            if (canSampleTextureMap(metallicRoughnessMap, tri)) {
                int mrTexel = sampleTextureMap(metallicRoughnessMap, tri, w0, hit.u, hit.v);
                roughness *= ((mrTexel >> 8) & 0xFF) / 255.0;
                metallic *= (mrTexel & 0xFF) / 255.0;
            }
        }
        out.roughness = clamp01(roughness);
        double clearcoatFactor = graphApplied ? graph.clearcoatFactor : material.getClearcoatFactor();
        double clearcoatRoughness = graphApplied ? graph.clearcoatRoughness : material.getClearcoatRoughness();
        double specular = graphApplied ? graph.specularFactor : material.getSpecularFactor();
        double clearcoatBoost = clearcoatFactor * (1.0 - clearcoatRoughness * 0.65);
        Vec3 specularColorFactor = material.getSpecularColorFactor();
        double dielectricSpecR = material.getSpecularColor().x * specular * specularColorFactor.x;
        double dielectricSpecG = material.getSpecularColor().y * specular * specularColorFactor.y;
        double dielectricSpecB = material.getSpecularColor().z * specular * specularColorFactor.z;
        out.specR = clamp01(mix(dielectricSpecR, Math.max(dielectricSpecR, out.baseR), metallic)
                + clearcoatBoost * 0.28);
        out.specG = clamp01(mix(dielectricSpecG, Math.max(dielectricSpecG, out.baseG), metallic)
                + clearcoatBoost * 0.28);
        out.specB = clamp01(mix(dielectricSpecB, Math.max(dielectricSpecB, out.baseB), metallic)
                + clearcoatBoost * 0.28);
        double maxBase = Math.max(out.baseR, Math.max(out.baseG, out.baseB));
        double dielectricReflectivity = 0.04 * specular + clearcoatBoost * 0.22;
        double metallicReflectivity = metallic * mix(0.35, 0.85, maxBase);
        out.reflectivity = clamp01(Math.max(material.getReflectivity(), dielectricReflectivity + metallicReflectivity));
        double transmission = graphApplied ? graph.transmission : material.getTransmission();
        out.transmission = clamp01(Math.max(transmission,
                material.getAlphaMode() == PhongMaterial.AlphaMode.BLEND ? 1.0 - out.opacity : transmission));
        out.refractiveIndex = Math.max(1.0, graphApplied ? graph.refractiveIndex : material.getRefractiveIndex());
        Vec3 emissionColor = graphApplied ? graph.emissionColor : material.getEmissionColor();
        double emissionStrength = graphApplied ? graph.emissionStrength : material.getEmissionStrength();
        out.emissionR = emissionColor.x * emissionStrength;
        out.emissionG = emissionColor.y * emissionStrength;
        out.emissionB = emissionColor.z * emissionStrength;
        if (!graphApplied) {
            TextureMap emissiveMap = material.getEmissiveMap();
            if (canSampleTextureMap(emissiveMap, tri)) {
                int emissiveTexel = sampleTextureMap(emissiveMap, tri, w0, hit.u, hit.v);
                out.emissionR *= ((emissiveTexel >> 16) & 0xFF) / 255.0;
                out.emissionG *= ((emissiveTexel >> 8) & 0xFF) / 255.0;
                out.emissionB *= (emissiveTexel & 0xFF) / 255.0;
            }
        }
        Vec3 mediumColor = graphApplied ? graph.mediumColor : material.getMediumColor();
        out.mediumR = mediumColor.x;
        out.mediumG = mediumColor.y;
        out.mediumB = mediumColor.z;
        out.density = graphApplied ? graph.density : material.getDensity();
        out.thickness = Math.max(0.02, graphApplied ? graph.thickness : material.getThickness());
        Vec3 sheenColor = graphApplied ? graph.sheenColor : material.getSheenColor();
        out.sheenR = sheenColor.x;
        out.sheenG = sheenColor.y;
        out.sheenB = sheenColor.z;

        Vec3 mappedNormal = applyNormalMap(material, tri, w0, hit.u, hit.v, out.nx, out.ny, out.nz, ctx);
        out.nx = mappedNormal.x;
        out.ny = mappedNormal.y;
        out.nz = mappedNormal.z;
    }

    private void computeShadowTerminatorPoint(Hit hit,
                                              Triangle tri,
                                              SurfaceState surface,
                                              TraceContext ctx) {
        if (hit == null || surface == null || ctx == null || tri == null) {
            ctx.shadowBaseX = hit != null ? hit.px : 0.0;
            ctx.shadowBaseY = hit != null ? hit.py : 0.0;
            ctx.shadowBaseZ = hit != null ? hit.pz : 0.0;
            return;
        }
        if (tri.flatNormal) {
            ctx.shadowBaseX = hit.px;
            ctx.shadowBaseY = hit.py;
            ctx.shadowBaseZ = hit.pz;
            return;
        }

        double w0 = 1.0 - hit.u - hit.v;

        double geomNx = surface.geomNx;
        double geomNy = surface.geomNy;
        double geomNz = surface.geomNz;

        double n0x = tri.n0x;
        double n0y = tri.n0y;
        double n0z = tri.n0z;
        double n1x = tri.n1x;
        double n1y = tri.n1y;
        double n1z = tri.n1z;
        double n2x = tri.n2x;
        double n2y = tri.n2y;
        double n2z = tri.n2z;

        if (n0x * geomNx + n0y * geomNy + n0z * geomNz < 0.0) {
            n0x = -n0x;
            n0y = -n0y;
            n0z = -n0z;
        }
        if (n1x * geomNx + n1y * geomNy + n1z * geomNz < 0.0) {
            n1x = -n1x;
            n1y = -n1y;
            n1z = -n1z;
        }
        if (n2x * geomNx + n2y * geomNy + n2z * geomNz < 0.0) {
            n2x = -n2x;
            n2y = -n2y;
            n2z = -n2z;
        }

        double t0x = hit.px - tri.ax;
        double t0y = hit.py - tri.ay;
        double t0z = hit.pz - tri.az;

        double t1x = hit.px - tri.bx;
        double t1y = hit.py - tri.by;
        double t1z = hit.pz - tri.bz;

        double t2x = hit.px - tri.cx;
        double t2y = hit.py - tri.cy;
        double t2z = hit.pz - tri.cz;

        double d0 = Math.min(0.0, t0x * n0x + t0y * n0y + t0z * n0z);
        double d1 = Math.min(0.0, t1x * n1x + t1y * n1y + t1z * n1z);
        double d2 = Math.min(0.0, t2x * n2x + t2y * n2y + t2z * n2z);

        double q0x = t0x - d0 * n0x;
        double q0y = t0y - d0 * n0y;
        double q0z = t0z - d0 * n0z;

        double q1x = t1x - d1 * n1x;
        double q1y = t1y - d1 * n1y;
        double q1z = t1z - d1 * n1z;

        double q2x = t2x - d2 * n2x;
        double q2y = t2y - d2 * n2y;
        double q2z = t2z - d2 * n2z;

        double shadowBaseX = hit.px + w0 * q0x + hit.u * q1x + hit.v * q2x;
        double shadowBaseY = hit.py + w0 * q0y + hit.u * q1y + hit.v * q2y;
        double shadowBaseZ = hit.pz + w0 * q0z + hit.u * q1z + hit.v * q2z;
        if (!Double.isFinite(shadowBaseX)
                || !Double.isFinite(shadowBaseY)
                || !Double.isFinite(shadowBaseZ)) {
            ctx.shadowBaseX = hit.px;
            ctx.shadowBaseY = hit.py;
            ctx.shadowBaseZ = hit.pz;
            return;
        }

        ctx.shadowBaseX = shadowBaseX;
        ctx.shadowBaseY = shadowBaseY;
        ctx.shadowBaseZ = shadowBaseZ;
    }

    private void prepareShadowRay(Hit hit, SurfaceState surface, double lx, double ly, double lz, TraceContext ctx) {
        Triangle tri = hit.triangle;

        computeShadowTerminatorPoint(hit, tri, surface, ctx);

        double geomNx = surface.geomNx;
        double geomNy = surface.geomNy;
        double geomNz = surface.geomNz;
        double sign = (geomNx * lx + geomNy * ly + geomNz * lz) >= 0.0 ? 1.0 : -1.0;
        double eps = RAY_EPS;
        double originEps = tri == null ? Math.max(RAY_EPS, 1e-3) : eps;

        ctx.shadowOx = ctx.shadowBaseX + geomNx * sign * originEps;
        ctx.shadowOy = ctx.shadowBaseY + geomNy * sign * originEps;
        ctx.shadowOz = ctx.shadowBaseZ + geomNz * sign * originEps;
        ctx.shadowTMin = eps;
    }

    private Vec3 applyNormalMap(PhongMaterial material,
                                Triangle tri,
                                double w0,
                                double u,
                                double v,
                                double nx,
                                double ny,
                                double nz,
                                TraceContext ctx) {
        if (material == null || !canSampleTextureMap(material.getNormalMap(), tri)) {
            return ctx.tmpVec0.set(nx, ny, nz).normalizeInPlace();
        }
        int texel = sampleTextureMap(material.getNormalMap(), tri, w0, u, v);
        Vec3 normal = ctx.tmpVec0.set(nx, ny, nz).normalizeInPlace();
        Vec3 tangent = ctx.tmpVec1.set(tri.tangentX, tri.tangentY, tri.tangentZ).normalizeInPlace();
        if (tangent.lengthSquared() < 1e-10) {
            tangent = buildFallbackTangent(normal, tangent, ctx.tmpVec3);
        }
        Vec3 bitangent = normal.cross(tangent, ctx.tmpVec2).normalizeInPlace();
        if (bitangent.lengthSquared() < 1e-10) {
            buildFallbackTangent(normal, ctx.tmpVec4, ctx.tmpVec3);
            bitangent = ctx.tmpVec4.cross(normal, bitangent).normalizeInPlace();
        }
        tangent = bitangent.cross(normal, tangent).normalizeInPlace();
        double tx = (((texel >> 16) & 0xFF) / 255.0 * 2.0 - 1.0) * material.getNormalScale();
        double ty = (((texel >> 8) & 0xFF) / 255.0 * 2.0 - 1.0) * material.getNormalScale();
        double tz = (texel & 0xFF) / 255.0 * 2.0 - 1.0;
        return ctx.tmpVec5.set(tangent)
                .mulInPlace(tx)
                .addScaledInPlace(bitangent, ty)
                .addScaledInPlace(normal, tz)
                .normalizeInPlace();
    }

    private boolean canSampleTextureMap(TextureMap map, Triangle tri) {
        if (map == null || !map.hasTexture() || tri == null) {
            return false;
        }
        return map.getTexCoord() > 0 ? tri.hasUV2 : tri.hasUV;
    }

    private int sampleTextureMap(TextureMap map, Triangle tri, double w0, double u, double v) {
        boolean useUv1 = map.getTexCoord() > 0 && tri.hasUV2;
        double uvU = useUv1
                ? tri.u0b * w0 + tri.u1b * u + tri.u2b * v
                : tri.u0 * w0 + tri.u1 * u + tri.u2 * v;
        double uvV = useUv1
                ? tri.v0b * w0 + tri.v1b * u + tri.v2b * v
                : tri.v0 * w0 + tri.v1 * u + tri.v2 * v;
        double scaledU = uvU * map.getScaleU();
        double scaledV = uvV * map.getScaleV();
        double sin = Math.sin(map.getRotation());
        double cos = Math.cos(map.getRotation());
        double rotatedU = scaledU * cos - scaledV * sin + map.getOffsetU();
        double rotatedV = scaledU * sin + scaledV * cos + map.getOffsetV();
        Texture texture = map.getTexture();
        return map.isLinear()
                ? texture.sampleBilinear(rotatedU, rotatedV, map.isFlipV())
                : texture.sampleNearest(rotatedU, rotatedV, map.isFlipV());
    }

    private static Vec3 computeTriangleTangent(Vec3 a,
                                               Vec3 b,
                                               Vec3 c,
                                               double u0,
                                               double v0,
                                               double u1,
                                               double v1,
                                               double u2,
                                               double v2,
                                               Vec3 fallbackNormal) {
        double du1 = u1 - u0;
        double dv1 = v1 - v0;
        double du2 = u2 - u0;
        double dv2 = v2 - v0;
        double denom = du1 * dv2 - du2 * dv1;
        if (Math.abs(denom) < 1e-8) {
            return buildFallbackTangent(fallbackNormal);
        }
        double r = 1.0 / denom;
        Vec3 tangent = b.sub(a).mul(dv2).sub(c.sub(a).mul(dv1)).mul(r).normalize();
        if (tangent.lengthSquared() < 1e-10) {
            return buildFallbackTangent(fallbackNormal);
        }
        return tangent;
    }

    private static Vec3 buildFallbackTangent(Vec3 normal) {
        return buildFallbackTangent(normal, new Vec3(), new Vec3());
    }

    private static Vec3 buildFallbackTangent(Vec3 normal, Vec3 out, Vec3 axisScratch) {
        Vec3 axis = axisScratch == null ? new Vec3() : axisScratch;
        if (Math.abs(normal.y) < 0.92) {
            axis.set(Vec3.UP);
        } else {
            axis.set(1.0, 0.0, 0.0);
        }
        axis.cross(normal, out).normalizeInPlace();
        if (out.lengthSquared() < 1e-10) {
            axis.set(0.0, 0.0, 1.0);
            axis.cross(normal, out).normalizeInPlace();
        }
        if (out.lengthSquared() < 1e-10) {
            out.set(1.0, 0.0, 0.0);
        }
        return out;
    }

    private static double schlickFresnel(double cosTheta, double ior) {
        double r0 = (1.0 - ior) / (1.0 + ior);
        r0 *= r0;
        double m = 1.0 - clamp01(cosTheta);
        return clamp01(r0 + (1.0 - r0) * m * m * m * m * m);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }

    private boolean intersectClosest(double ox, double oy, double oz,
                                     double dx, double dy, double dz,
                                     double tMin, double tMax,
                                     Hit outHit, TraceContext ctx) {
        if (bvhRoot == null) {
            return false;
        }

        double invDx = Math.abs(dx) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dx;
        double invDy = Math.abs(dy) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dy;
        double invDz = Math.abs(dz) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dz;

        int sp = 0;
        ctx.nodeStack[sp++] = bvhRoot;
        boolean hitAnything = false;
        double closest = tMax;

        while (sp > 0) {
            BVHNode node = ctx.nodeStack[--sp];
            if (!intersectsAabb(node, ox, oy, oz, invDx, invDy, invDz, tMin, closest)) {
                continue;
            }

            if (node.left == null) {
                for (int i = node.start; i < node.end; i++) {
                    Triangle tri = triangles[triangleOrder[i]];
                    if (intersectTriangle(tri, ox, oy, oz, dx, dy, dz, tMin, closest, ctx.tempHit)) {
                        hitAnything = true;
                        closest = ctx.tempHit.t;
                        outHit.copyFrom(ctx.tempHit);
                    }
                }
                continue;
            }

            BVHNode left = node.left;
            BVHNode right = node.right;
            double tLeft = left != null ? aabbEntry(left, ox, oy, oz, invDx, invDy, invDz, tMin, closest) : INF_T;
            double tRight = right != null ? aabbEntry(right, ox, oy, oz, invDx, invDy, invDz, tMin, closest) : INF_T;

            if (tLeft < tRight) {
                if (tRight < INF_T) {
                    ctx.nodeStack[sp++] = right;
                }
                if (tLeft < INF_T) {
                    ctx.nodeStack[sp++] = left;
                }
            } else {
                if (tLeft < INF_T) {
                    ctx.nodeStack[sp++] = left;
                }
                if (tRight < INF_T) {
                    ctx.nodeStack[sp++] = right;
                }
            }
        }

        return hitAnything;
    }

    private boolean intersectAny(double ox, double oy, double oz,
                                 double dx, double dy, double dz,
                                 double tMin, double tMax,
                                 TraceContext ctx) {
        if (bvhRoot == null) {
            return false;
        }

        double invDx = Math.abs(dx) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dx;
        double invDy = Math.abs(dy) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dy;
        double invDz = Math.abs(dz) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dz;

        int sp = 0;
        ctx.nodeStack[sp++] = bvhRoot;

        while (sp > 0) {
            BVHNode node = ctx.nodeStack[--sp];
            if (!intersectsAabb(node, ox, oy, oz, invDx, invDy, invDz, tMin, tMax)) {
                continue;
            }
            if (node.left == null) {
                for (int i = node.start; i < node.end; i++) {
                    Triangle tri = triangles[triangleOrder[i]];
                    if (intersectTriangle(tri, ox, oy, oz, dx, dy, dz, tMin, tMax, ctx.tempHit)
                            && shadowOccludes(tri, ctx.tempHit, dx, dy, dz, ctx)) {
                        return true;
                    }
                }
                continue;
            }
            if (node.left != null) {
                ctx.nodeStack[sp++] = node.left;
            }
            if (node.right != null) {
                ctx.nodeStack[sp++] = node.right;
            }
        }
        return false;
    }

    private boolean shadowOccludes(Triangle tri, Hit hit, double dx, double dy, double dz, TraceContext ctx) {
        sampleSurface(tri, hit, dx, dy, dz, ctx.shadowSurface, ctx);
        if (ctx.shadowSurface.discard) {
            return false;
        }
        double occlusion = clamp01(ctx.shadowSurface.opacity * (1.0 - ctx.shadowSurface.transmission));
        return occlusion > 0.15;
    }

    private boolean intersectTriangle(Triangle tri,
                                      double ox, double oy, double oz,
                                      double dx, double dy, double dz,
                                      double tMin, double tMax,
                                      Hit out) {
        double pvx = dy * tri.e2z - dz * tri.e2y;
        double pvy = dz * tri.e2x - dx * tri.e2z;
        double pvz = dx * tri.e2y - dy * tri.e2x;

        double det = tri.e1x * pvx + tri.e1y * pvy + tri.e1z * pvz;
        if (Math.abs(det) < 1e-12) {
            return false;
        }
        double invDet = 1.0 / det;

        double tvx = ox - tri.ax;
        double tvy = oy - tri.ay;
        double tvz = oz - tri.az;
        double u = (tvx * pvx + tvy * pvy + tvz * pvz) * invDet;
        if (u < 0.0 || u > 1.0) {
            return false;
        }

        double qvx = tvy * tri.e1z - tvz * tri.e1y;
        double qvy = tvz * tri.e1x - tvx * tri.e1z;
        double qvz = tvx * tri.e1y - tvy * tri.e1x;
        double v = (dx * qvx + dy * qvy + dz * qvz) * invDet;
        if (v < 0.0 || (u + v) > 1.0) {
            return false;
        }

        double t = (tri.e2x * qvx + tri.e2y * qvy + tri.e2z * qvz) * invDet;
        if (t <= tMin || t >= tMax) {
            return false;
        }

        out.t = t;
        out.u = u;
        out.v = v;
        out.px = ox + dx * t;
        out.py = oy + dy * t;
        out.pz = oz + dz * t;
        out.triangle = tri;
        return true;
    }

    private boolean intersectTriangleAny(Triangle tri,
                                         double ox, double oy, double oz,
                                         double dx, double dy, double dz,
                                         double tMin, double tMax) {
        double pvx = dy * tri.e2z - dz * tri.e2y;
        double pvy = dz * tri.e2x - dx * tri.e2z;
        double pvz = dx * tri.e2y - dy * tri.e2x;

        double det = tri.e1x * pvx + tri.e1y * pvy + tri.e1z * pvz;
        if (Math.abs(det) < 1e-12) {
            return false;
        }
        double invDet = 1.0 / det;

        double tvx = ox - tri.ax;
        double tvy = oy - tri.ay;
        double tvz = oz - tri.az;
        double u = (tvx * pvx + tvy * pvy + tvz * pvz) * invDet;
        if (u < 0.0 || u > 1.0) {
            return false;
        }

        double qvx = tvy * tri.e1z - tvz * tri.e1y;
        double qvy = tvz * tri.e1x - tvx * tri.e1z;
        double qvz = tvx * tri.e1y - tvy * tri.e1x;
        double v = (dx * qvx + dy * qvy + dz * qvz) * invDet;
        if (v < 0.0 || (u + v) > 1.0) {
            return false;
        }

        double t = (tri.e2x * qvx + tri.e2y * qvy + tri.e2z * qvz) * invDet;
        return t > tMin && t < tMax;
    }

    private boolean intersectsAabb(BVHNode node,
                                   double ox, double oy, double oz,
                                   double invDx, double invDy, double invDz,
                                   double tMin, double tMax) {
        double t0 = (node.minX - ox) * invDx;
        double t1 = (node.maxX - ox) * invDx;
        double near = Math.min(t0, t1);
        double far = Math.max(t0, t1);

        t0 = (node.minY - oy) * invDy;
        t1 = (node.maxY - oy) * invDy;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));

        t0 = (node.minZ - oz) * invDz;
        t1 = (node.maxZ - oz) * invDz;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));

        return far >= Math.max(tMin, near) && near <= tMax;
    }

    private double aabbEntry(BVHNode node,
                             double ox, double oy, double oz,
                             double invDx, double invDy, double invDz,
                             double tMin, double tMax) {
        double t0 = (node.minX - ox) * invDx;
        double t1 = (node.maxX - ox) * invDx;
        double near = Math.min(t0, t1);
        double far = Math.max(t0, t1);

        t0 = (node.minY - oy) * invDy;
        t1 = (node.maxY - oy) * invDy;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));

        t0 = (node.minZ - oz) * invDz;
        t1 = (node.maxZ - oz) * invDz;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));

        if (far >= Math.max(tMin, near) && near <= tMax) {
            return near;
        }
        return INF_T;
    }

    private void generatePrimaryRay(CameraState camera, int px, int py, SplitMix64 rng, TraceContext ctx) {
        generatePrimaryRay(camera, px, py, rng.nextDouble(), rng.nextDouble(), ctx);
    }

    private void generatePrimaryRay(CameraState camera, int px, int py, double sampleX, double sampleY, TraceContext ctx) {
        double ndcX = ((px + sampleX) / camera.width) * 2.0 - 1.0;
        double ndcY = 1.0 - ((py + sampleY) / camera.height) * 2.0;

        if (camera.perspective) {
            double sx = ndcX * camera.aspect * camera.tanHalfFov;
            double sy = ndcY * camera.tanHalfFov;
            double dx = camera.fx + camera.rx * sx + camera.ux * sy;
            double dy = camera.fy + camera.ry * sx + camera.uy * sy;
            double dz = camera.fz + camera.rz * sx + camera.uz * sy;
            double invLen = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);

            ctx.rayOx = camera.px;
            ctx.rayOy = camera.py;
            ctx.rayOz = camera.pz;
            ctx.rayDx = dx * invLen;
            ctx.rayDy = dy * invLen;
            ctx.rayDz = dz * invLen;
            return;
        }

        double camX = (ndcX - camera.orthoM03) / camera.orthoM00;
        double camY = (ndcY - camera.orthoM13) / camera.orthoM11;
        ctx.rayOx = camera.px + camera.rx * camX + camera.ux * camY + camera.fx * camera.near;
        ctx.rayOy = camera.py + camera.ry * camX + camera.uy * camY + camera.fy * camera.near;
        ctx.rayOz = camera.pz + camera.rz * camX + camera.uz * camY + camera.fz * camera.near;
        ctx.rayDx = camera.fx;
        ctx.rayDy = camera.fy;
        ctx.rayDz = camera.fz;
    }

    private void capturePrimaryGuide(CameraState camera, int px, int py, int idx, TraceContext ctx) {
        generatePrimaryRay(camera, px, py, 0.5, 0.5, ctx);
        double ox = ctx.rayOx;
        double oy = ctx.rayOy;
        double oz = ctx.rayOz;
        double dx = ctx.rayDx;
        double dy = ctx.rayDy;
        double dz = ctx.rayDz;
        double totalT = 0.0;

        for (int step = 0; step < 8; step++) {
            if (!intersectClosest(ox, oy, oz, dx, dy, dz, RAY_EPS, INF_T, ctx.hit, ctx)) {
                guideDepth[idx] = Float.POSITIVE_INFINITY;
                int base = idx * 3;
                guideNormal[base] = 0.0f;
                guideNormal[base + 1] = 0.0f;
                guideNormal[base + 2] = 0.0f;
                return;
            }

            sampleSurface(ctx.hit.triangle, ctx.hit, dx, dy, dz, ctx.surface, ctx);
            if (!ctx.surface.discard) {
                guideDepth[idx] = (float) (totalT + ctx.hit.t);
                int base = idx * 3;
                guideNormal[base] = (float) ctx.surface.nx;
                guideNormal[base + 1] = (float) ctx.surface.ny;
                guideNormal[base + 2] = (float) ctx.surface.nz;
                return;
            }

            totalT += ctx.hit.t;
            ox = ctx.hit.px + dx * RAY_EPS;
            oy = ctx.hit.py + dy * RAY_EPS;
            oz = ctx.hit.pz + dz * RAY_EPS;
        }
        guideDepth[idx] = Float.POSITIVE_INFINITY;
        int base = idx * 3;
        guideNormal[base] = 0.0f;
        guideNormal[base + 1] = 0.0f;
        guideNormal[base + 2] = 0.0f;
    }

    private CameraState buildCameraState(Camera camera, int width, int height) {
        CameraState s = new CameraState();
        s.width = width;
        s.height = height;
        s.px = camera.getPosition().x;
        s.py = camera.getPosition().y;
        s.pz = camera.getPosition().z;

        Vec3 f = camera.getForward().normalize();
        Vec3 r = camera.getRight().normalize();
        Vec3 u = camera.getUp().normalize();
        s.fx = f.x;
        s.fy = f.y;
        s.fz = f.z;
        s.rx = r.x;
        s.ry = r.y;
        s.rz = r.z;
        s.ux = u.x;
        s.uy = u.y;
        s.uz = u.z;
        s.near = camera.getNear();

        if (camera instanceof PerspectiveCamera) {
            PerspectiveCamera pc = (PerspectiveCamera) camera;
            s.perspective = true;
            s.aspect = pc.getAspectRatio();
            s.tanHalfFov = Math.tan(pc.getFovY() * 0.5);
            s.orthoM00 = 1.0;
            s.orthoM11 = 1.0;
            s.orthoM03 = 0.0;
            s.orthoM13 = 0.0;
            return s;
        }

        Mat4 proj = camera.getProjectionMatrix();
        s.perspective = false;
        s.aspect = (double) width / Math.max(1.0, (double) height);
        s.tanHalfFov = Math.tan(Math.toRadians(35.0));
        s.orthoM00 = Math.abs(proj.get(0, 0)) < 1e-12 ? 1.0 : proj.get(0, 0);
        s.orthoM11 = Math.abs(proj.get(1, 1)) < 1e-12 ? 1.0 : proj.get(1, 1);
        s.orthoM03 = proj.get(0, 3);
        s.orthoM13 = proj.get(1, 3);
        return s;
    }

    private void rebuildGeometry(Scene scene) {
        List<Triangle> built = new ArrayList<>();
        for (Entity entity : scene.getAllMeshEntities()) {
            Mesh mesh = entity.getMesh();
            if (mesh == null) {
                continue;
            }
            float[] positions = mesh.getPositions();
            int[] indices = mesh.getIndices();
            if (positions == null || indices == null || indices.length < 3) {
                continue;
            }

            float[] normals = mesh.getNormals();
            float[] uvs = mesh.getUVs();
            float[] uv2s = mesh.getUV2s();
            PhongMaterial material = toPhongMaterial(entity.getMaterial());
            Texture texture = material.getDiffuseTexture();
            Mat4 model = entity.getWorldMatrix();
            Mat3 normalMatrix;
            try {
                normalMatrix = model.inverse().transpose().toMat3();
            } catch (IllegalStateException ex) {
                normalMatrix = Mat3.identity();
            }

            double baseR = material.getDiffuseColor().x;
            double baseG = material.getDiffuseColor().y;
            double baseB = material.getDiffuseColor().z;
            double specR = material.getSpecularColor().x;
            double specG = material.getSpecularColor().y;
            double specB = material.getSpecularColor().z;
            double reflectivity = Math.max(0.0, Math.min(0.98, material.getReflectivity()));
            double roughness = Math.sqrt(2.0 / (Math.max(1.0, material.getShininess()) + 2.0));
            boolean floorGrid = "floor-grid".equals(material.getName());
            boolean textureLinear = material.isTextureFilteringLinear();

            for (int i = 0; i < indices.length; i += 3) {
                int i0 = indices[i];
                int i1 = indices[i + 1];
                int i2 = indices[i + 2];

                int p0 = i0 * 3;
                int p1 = i1 * 3;
                int p2 = i2 * 3;

                Vec3 a = model.transformPoint(new Vec3(positions[p0], positions[p0 + 1], positions[p0 + 2]));
                Vec3 b = model.transformPoint(new Vec3(positions[p1], positions[p1 + 1], positions[p1 + 2]));
                Vec3 c = model.transformPoint(new Vec3(positions[p2], positions[p2 + 1], positions[p2 + 2]));

                Vec3 na;
                Vec3 nb;
                Vec3 nc;
                if (normals != null && normals.length >= p2 + 3) {
                    na = normalMatrix.transform(new Vec3(normals[p0], normals[p0 + 1], normals[p0 + 2])).normalize();
                    nb = normalMatrix.transform(new Vec3(normals[p1], normals[p1 + 1], normals[p1 + 2])).normalize();
                    nc = normalMatrix.transform(new Vec3(normals[p2], normals[p2 + 1], normals[p2 + 2])).normalize();
                } else {
                    Vec3 fn = b.sub(a).cross(c.sub(a)).normalize();
                    na = fn;
                    nb = fn;
                    nc = fn;
                }

                double u0 = 0.0;
                double v0 = 0.0;
                double u1 = 0.0;
                double v1 = 0.0;
                double u2 = 0.0;
                double v2 = 0.0;
                boolean hasUV = false;
                if (uvs != null) {
                    int uv0 = i0 * 2;
                    int uv1 = i1 * 2;
                    int uv2 = i2 * 2;
                    if (uv2 + 1 < uvs.length) {
                        u0 = uvs[uv0];
                        v0 = uvs[uv0 + 1];
                        u1 = uvs[uv1];
                        v1 = uvs[uv1 + 1];
                        u2 = uvs[uv2];
                        v2 = uvs[uv2 + 1];
                        hasUV = true;
                    }
                }

                double u0b = u0;
                double v0b = v0;
                double u1b = u1;
                double v1b = v1;
                double u2b = u2;
                double v2b = v2;
                boolean hasUV2 = false;
                if (uv2s != null) {
                    int uv0i = i0 * 2;
                    int uv1i = i1 * 2;
                    int uv2i = i2 * 2;
                    if (uv2i + 1 < uv2s.length) {
                        u0b = uv2s[uv0i];
                        v0b = uv2s[uv0i + 1];
                        u1b = uv2s[uv1i];
                        v1b = uv2s[uv1i + 1];
                        u2b = uv2s[uv2i];
                        v2b = uv2s[uv2i + 1];
                        hasUV2 = true;
                    }
                }

                boolean tangentUsesUv1 = material.getNormalMap().getTexCoord() > 0 && hasUV2;
                Vec3 tangent = computeTriangleTangent(
                        a, b, c,
                        tangentUsesUv1 ? u0b : u0,
                        tangentUsesUv1 ? v0b : v0,
                        tangentUsesUv1 ? u1b : u1,
                        tangentUsesUv1 ? v1b : v1,
                        tangentUsesUv1 ? u2b : u2,
                        tangentUsesUv1 ? v2b : v2,
                        na
                );

                built.add(new Triangle(
                        a.x, a.y, a.z,
                        b.x, b.y, b.z,
                        c.x, c.y, c.z,
                        na.x, na.y, na.z,
                        nb.x, nb.y, nb.z,
                        nc.x, nc.y, nc.z,
                        u0, v0, u1, v1, u2, v2, hasUV,
                        u0b, v0b, u1b, v1b, u2b, v2b, hasUV2,
                        tangent.x, tangent.y, tangent.z,
                        baseR, baseG, baseB,
                        specR, specG, specB,
                        reflectivity, roughness,
                        texture, textureLinear, floorGrid,
                        material
                ));
            }
        }

        triangles = built.toArray(new Triangle[0]);
        triangleCount = triangles.length;
        centroidX = new double[triangleCount];
        centroidY = new double[triangleCount];
        centroidZ = new double[triangleCount];
        for (int i = 0; i < triangleCount; i++) {
            centroidX[i] = triangles[i].centroidX;
            centroidY[i] = triangles[i].centroidY;
            centroidZ[i] = triangles[i].centroidZ;
        }
        rebuildBvh();
    }

    private void rebuildBvh() {
        if (triangleCount == 0) {
            triangleOrder = new int[0];
            bvhRoot = null;
            return;
        }
        triangleOrder = new int[triangleCount];
        for (int i = 0; i < triangleCount; i++) {
            triangleOrder[i] = i;
        }
        bvhRoot = buildNode(0, triangleCount);
    }

    private BVHNode buildNode(int start, int end) {
        BVHNode node = new BVHNode();
        node.start = start;
        node.end = end;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        double cMinX = Double.POSITIVE_INFINITY;
        double cMinY = Double.POSITIVE_INFINITY;
        double cMinZ = Double.POSITIVE_INFINITY;
        double cMaxX = Double.NEGATIVE_INFINITY;
        double cMaxY = Double.NEGATIVE_INFINITY;
        double cMaxZ = Double.NEGATIVE_INFINITY;

        for (int i = start; i < end; i++) {
            Triangle tri = triangles[triangleOrder[i]];
            minX = Math.min(minX, tri.minX);
            minY = Math.min(minY, tri.minY);
            minZ = Math.min(minZ, tri.minZ);
            maxX = Math.max(maxX, tri.maxX);
            maxY = Math.max(maxY, tri.maxY);
            maxZ = Math.max(maxZ, tri.maxZ);
            cMinX = Math.min(cMinX, tri.centroidX);
            cMinY = Math.min(cMinY, tri.centroidY);
            cMinZ = Math.min(cMinZ, tri.centroidZ);
            cMaxX = Math.max(cMaxX, tri.centroidX);
            cMaxY = Math.max(cMaxY, tri.centroidY);
            cMaxZ = Math.max(cMaxZ, tri.centroidZ);
        }

        node.minX = minX;
        node.minY = minY;
        node.minZ = minZ;
        node.maxX = maxX;
        node.maxY = maxY;
        node.maxZ = maxZ;

        int count = end - start;
        if (count <= leafSize) {
            return node;
        }

        SahBvhUtil.Split split = SahBvhUtil.findBestSplit(
                triangleOrder,
                start,
                end,
                8,
                this::centroidAxis,
                (triIndex, out) -> {
                    Triangle tri = triangles[triIndex];
                    out.set(tri.minX, tri.minY, tri.minZ, tri.maxX, tri.maxY, tri.maxZ);
                }
        );

        int mid = -1;
        if (split.isValid()) {
            mid = SahBvhUtil.partitionByAxis(triangleOrder, start, end, split.axis, split.position, this::centroidAxis);
        }
        if (mid <= start || mid >= end) {
            double ex = cMaxX - cMinX;
            double ey = cMaxY - cMinY;
            double ez = cMaxZ - cMinZ;
            int axis = 0;
            if (ey > ex && ey >= ez) {
                axis = 1;
            } else if (ez > ex && ez > ey) {
                axis = 2;
            }
            double extent = axis == 0 ? ex : (axis == 1 ? ey : ez);
            if (extent < 1e-10) {
                return node;
            }
            sortRangeByAxis(start, end - 1, axis);
            mid = (start + end) >>> 1;
        }
        if (mid <= start || mid >= end) {
            return node;
        }
        node.left = buildNode(start, mid);
        node.right = buildNode(mid, end);
        return node;
    }

    private void sortRangeByAxis(int lo, int hi, int axis) {
        SahBvhUtil.sortByAxis(triangleOrder, lo, hi, axis, this::centroidAxis);
    }

    private double centroidAxis(int triIndex, int axis) {
        if (axis == 0) {
            return centroidX[triIndex];
        }
        if (axis == 1) {
            return centroidY[triIndex];
        }
        return centroidZ[triIndex];
    }

    private void rebuildLightCache(Scene scene) {
        Vec3 ambient = scene.getAmbientColor();
        Vec3 background = scene.getBackgroundColor();
        if (ambient != null) {
            ambientR = ambient.x;
            ambientG = ambient.y;
            ambientB = ambient.z;
        }
        if (background != null) {
            backgroundR = background.x;
            backgroundG = background.y;
            backgroundB = background.z;
        }
        environmentStrength = Math.max(0.0, scene.getEnvironmentStrength());

        List<DirLightCache> dir = new ArrayList<>();
        List<PointLightCache> points = new ArrayList<>();
        for (Light light : scene.getLights()) {
            if (light == null || !light.isEnabled()) {
                continue;
            }
            double lr = light.getColor().x * light.getIntensity();
            double lg = light.getColor().y * light.getIntensity();
            double lb = light.getColor().z * light.getIntensity();

            if (light instanceof DirectionalLight) {
                Vec3 d = ((DirectionalLight) light).getDirection();
                double lx = -d.x;
                double ly = -d.y;
                double lz = -d.z;
                double lenSq = lx * lx + ly * ly + lz * lz;
                if (lenSq < 1e-14) {
                    continue;
                }
                double invLen = 1.0 / Math.sqrt(lenSq);
                dir.add(new DirLightCache(lx * invLen, ly * invLen, lz * invLen, lr, lg, lb));
            } else if (light instanceof PointLight) {
                PointLight pl = (PointLight) light;
                Vec3 p = pl.getPosition();
                points.add(new PointLightCache(pl, p.x, p.y, p.z, lr, lg, lb));
            }
        }
        dirLights = dir.toArray(new DirLightCache[0]);
        pointLights = points.toArray(new PointLightCache[0]);
    }

    private long computeGeometrySignature(Scene scene) {
        long h = 0xcbf29ce484222325L;
        for (Entity e : scene.getAllMeshEntities()) {
            Mesh mesh = e.getMesh();
            if (mesh == null) {
                continue;
            }
            h = mixHash(h, System.identityHashCode(mesh));
            h = mixMaterialSignature(h, e.getMaterial());
            h = mixHash(h, e.isVisible() ? 1 : 0);

            Transform t = e.getTransform();
            Vec3 p = t.getPosition();
            Vec3 s = t.getScale();
            Quaternion q = t.getRotation();

            h = mixHash(h, quantizedBits(p.x, 1e-4));
            h = mixHash(h, quantizedBits(p.y, 1e-4));
            h = mixHash(h, quantizedBits(p.z, 1e-4));
            h = mixHash(h, quantizedBits(s.x, 1e-4));
            h = mixHash(h, quantizedBits(s.y, 1e-4));
            h = mixHash(h, quantizedBits(s.z, 1e-4));
            h = mixHash(h, quantizedBits(q.x, 1e-4));
            h = mixHash(h, quantizedBits(q.y, 1e-4));
            h = mixHash(h, quantizedBits(q.z, 1e-4));
            h = mixHash(h, quantizedBits(q.w, 1e-4));
        }
        return h;
    }

    private long mixMaterialSignature(long hash, Material baseMaterial) {
        if (baseMaterial == null) {
            return mixHash(hash, 0);
        }
        PhongMaterial material = toPhongMaterial(baseMaterial);
        hash = mixHash(hash, System.identityHashCode(baseMaterial));
        hash = mixHash(hash, Double.doubleToLongBits(material.getDiffuseColor().x));
        hash = mixHash(hash, Double.doubleToLongBits(material.getDiffuseColor().y));
        hash = mixHash(hash, Double.doubleToLongBits(material.getDiffuseColor().z));
        hash = mixHash(hash, Double.doubleToLongBits(material.getSpecularColor().x));
        hash = mixHash(hash, Double.doubleToLongBits(material.getSpecularColor().y));
        hash = mixHash(hash, Double.doubleToLongBits(material.getSpecularColor().z));
        hash = mixHash(hash, Double.doubleToLongBits(material.getOpacity()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getRoughness()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getMetallic()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getTransmission()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getRefractiveIndex()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getEmissionStrength()));
        hash = mixHash(hash, material.isDoubleSided() ? 1 : 0);
        hash = mixHash(hash, material.getAlphaMode().ordinal());
        hash = mixTextureMapSignature(hash, material.getDiffuseMap());
        hash = mixTextureMapSignature(hash, material.getNormalMap());
        hash = mixTextureMapSignature(hash, material.getMetallicRoughnessMap());
        hash = mixTextureMapSignature(hash, material.getEmissiveMap());
        hash = mixHash(hash, material.hasNodeGraph() ? material.getNodeGraph().signature() : 0L);
        return hash;
    }

    private long mixTextureMapSignature(long hash, TextureMap map) {
        if (map == null) {
            return mixHash(hash, 0);
        }
        hash = mixHash(hash, System.identityHashCode(map.getTexture()));
        hash = mixHash(hash, map.isLinear() ? 1 : 0);
        hash = mixHash(hash, map.getTexCoord());
        hash = mixHash(hash, Double.doubleToLongBits(map.getOffsetU()));
        hash = mixHash(hash, Double.doubleToLongBits(map.getOffsetV()));
        hash = mixHash(hash, Double.doubleToLongBits(map.getScaleU()));
        hash = mixHash(hash, Double.doubleToLongBits(map.getScaleV()));
        hash = mixHash(hash, Double.doubleToLongBits(map.getRotation()));
        return hash;
    }

    private long computeLightingSignature(Scene scene) {
        long h = 0xcbf29ce484222325L;
        Vec3 ambient = scene.getAmbientColor();
        Vec3 background = scene.getBackgroundColor();
        if (ambient != null) {
            h = mixHash(h, Double.doubleToLongBits(ambient.x));
            h = mixHash(h, Double.doubleToLongBits(ambient.y));
            h = mixHash(h, Double.doubleToLongBits(ambient.z));
        }
        if (background != null) {
            h = mixHash(h, Double.doubleToLongBits(background.x));
            h = mixHash(h, Double.doubleToLongBits(background.y));
            h = mixHash(h, Double.doubleToLongBits(background.z));
        }
        h = mixHash(h, Double.doubleToLongBits(scene.getEnvironmentStrength()));
        for (Light light : scene.getLights()) {
            if (light == null) {
                continue;
            }
            h = mixHash(h, light.isEnabled() ? 1 : 0);
            h = mixHash(h, System.identityHashCode(light.getClass()));
            h = mixHash(h, Double.doubleToLongBits(light.getIntensity()));
            Vec3 c = light.getColor();
            h = mixHash(h, Double.doubleToLongBits(c.x));
            h = mixHash(h, Double.doubleToLongBits(c.y));
            h = mixHash(h, Double.doubleToLongBits(c.z));
            if (light instanceof DirectionalLight) {
                Vec3 d = ((DirectionalLight) light).getDirection();
                h = mixHash(h, Double.doubleToLongBits(d.x));
                h = mixHash(h, Double.doubleToLongBits(d.y));
                h = mixHash(h, Double.doubleToLongBits(d.z));
            } else if (light instanceof PointLight) {
                PointLight pl = (PointLight) light;
                Vec3 p = pl.getPosition();
                h = mixHash(h, Double.doubleToLongBits(p.x));
                h = mixHash(h, Double.doubleToLongBits(p.y));
                h = mixHash(h, Double.doubleToLongBits(p.z));
                h = mixHash(h, Double.doubleToLongBits(pl.getConstant()));
                h = mixHash(h, Double.doubleToLongBits(pl.getLinear()));
                h = mixHash(h, Double.doubleToLongBits(pl.getQuadratic()));
                if (pl instanceof ConeLight) {
                    ConeLight cl = (ConeLight) pl;
                    Vec3 d = cl.getDirection();
                    h = mixHash(h, Double.doubleToLongBits(d.x));
                    h = mixHash(h, Double.doubleToLongBits(d.y));
                    h = mixHash(h, Double.doubleToLongBits(d.z));
                    h = mixHash(h, Double.doubleToLongBits(cl.getConeAngleDegrees()));
                    h = mixHash(h, Double.doubleToLongBits(cl.getSoftness()));
                } else if (pl instanceof AreaLight) {
                    AreaLight al = (AreaLight) pl;
                    Vec3 d = al.getEmissionDirection();
                    h = mixHash(h, Double.doubleToLongBits(d.x));
                    h = mixHash(h, Double.doubleToLongBits(d.y));
                    h = mixHash(h, Double.doubleToLongBits(d.z));
                    h = mixHash(h, Double.doubleToLongBits(al.getSpreadAngleDegrees()));
                }
            }
        }
        return h;
    }

    private long computeCameraSignature(Camera camera, int width, int height) {
        long h = 0xcbf29ce484222325L;
        h = mixHash(h, width);
        h = mixHash(h, height);
        h = mixHash(h, System.identityHashCode(camera.getClass()));

        Vec3 p = camera.getPosition();
        Vec3 f = camera.getForward();
        Vec3 u = camera.getUp();
        Vec3 r = camera.getRight();
        h = mixHash(h, Double.doubleToLongBits(p.x));
        h = mixHash(h, Double.doubleToLongBits(p.y));
        h = mixHash(h, Double.doubleToLongBits(p.z));
        h = mixHash(h, Double.doubleToLongBits(f.x));
        h = mixHash(h, Double.doubleToLongBits(f.y));
        h = mixHash(h, Double.doubleToLongBits(f.z));
        h = mixHash(h, Double.doubleToLongBits(u.x));
        h = mixHash(h, Double.doubleToLongBits(u.y));
        h = mixHash(h, Double.doubleToLongBits(u.z));
        h = mixHash(h, Double.doubleToLongBits(r.x));
        h = mixHash(h, Double.doubleToLongBits(r.y));
        h = mixHash(h, Double.doubleToLongBits(r.z));
        h = mixHash(h, Double.doubleToLongBits(camera.getNear()));
        h = mixHash(h, Double.doubleToLongBits(camera.getFar()));

        Mat4 proj = camera.getProjectionMatrix();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                h = mixHash(h, Double.doubleToLongBits(proj.get(row, col)));
            }
        }
        return h;
    }

    private void sampleEnvironment(double dx, double dy, double dz, TraceContext ctx) {
        if (!hasVisibleEnvironment()) {
            ctx.envR = 0.0;
            ctx.envG = 0.0;
            ctx.envB = 0.0;
            return;
        }
        double envScale = Math.max(0.0, environmentStrength);
        double up = clamp01(dy * 0.5 + 0.5);
        double horizon = 1.0 - Math.abs(dy);
        double zenithR = clamp01(backgroundR * 1.35 + 0.05);
        double zenithG = clamp01(backgroundG * 1.45 + 0.08);
        double zenithB = clamp01(backgroundB * 1.70 + 0.16);
        double horizonR = clamp01(backgroundR * 0.95 + 0.18);
        double horizonG = clamp01(backgroundG * 1.00 + 0.16);
        double horizonB = clamp01(backgroundB * 1.05 + 0.12);
        double groundR = clamp01(backgroundR * 0.32 + 0.015);
        double groundG = clamp01(backgroundG * 0.34 + 0.017);
        double groundB = clamp01(backgroundB * 0.36 + 0.020);

        double skyBlend = Math.pow(up, 0.70);
        double skyR = mix(horizonR, zenithR, skyBlend);
        double skyG = mix(horizonG, zenithG, skyBlend);
        double skyB = mix(horizonB, zenithB, skyBlend);
        double groundMix = clamp01(-dy * 0.75);
        ctx.envR = mix(skyR, groundR, groundMix);
        ctx.envG = mix(skyG, groundG, groundMix);
        ctx.envB = mix(skyB, groundB, groundMix);

        double haze = Math.pow(Math.max(0.0, horizon), 4.0) * 0.035;
        ctx.envR = mix(ctx.envR, horizonR, haze);
        ctx.envG = mix(ctx.envG, horizonG, haze);
        ctx.envB = mix(ctx.envB, horizonB, haze);
        ctx.envR *= envScale;
        ctx.envG *= envScale;
        ctx.envB *= envScale;

        for (DirLightCache light : dirLights) {
            double sun = Math.max(0.0, dx * light.lx + dy * light.ly + dz * light.lz);
            if (sun <= 1e-6) {
                continue;
            }
            double glow = Math.pow(sun, 220.0) * 6.5 + Math.pow(sun, 32.0) * 0.25;
            ctx.envR += light.r * glow;
            ctx.envG += light.g * glow;
            ctx.envB += light.b * glow;
        }

        ctx.envR = Math.max(0.0, ctx.envR);
        ctx.envG = Math.max(0.0, ctx.envG);
        ctx.envB = Math.max(0.0, ctx.envB);
    }

    private void fillBackground(FrameBuffer fb) {
        if (hasVisibleEnvironment()) {
            Arrays.fill(fb.getColorBuffer(), packColor(
                    toneMap(backgroundR * environmentStrength),
                    toneMap(backgroundG * environmentStrength),
                    toneMap(backgroundB * environmentStrength)
            ));
        } else {
            Arrays.fill(fb.getColorBuffer(), packColor(0.0, 0.0, 0.0));
        }
        Arrays.fill(fb.getDepthBuffer(), 1.0f);
    }

    private boolean hasVisibleEnvironment() {
        return skyEnabled
                && environmentStrength > 1e-6
                && (backgroundR > 1e-6 || backgroundG > 1e-6 || backgroundB > 1e-6);
    }

    private void resetAccumulation() {
        Arrays.fill(accumR, 0.0);
        Arrays.fill(accumG, 0.0);
        Arrays.fill(accumB, 0.0);
        Arrays.fill(denoiseR, 0.0);
        Arrays.fill(denoiseG, 0.0);
        Arrays.fill(denoiseB, 0.0);
        Arrays.fill(guideDepth, Float.POSITIVE_INFINITY);
        Arrays.fill(guideNormal, 0.0f);
        accumulatedSamples = 0;
    }

    private void allocateAccumulation(int w, int h) {
        int count = safePixelCount(w, h);
        accumR = new double[count];
        accumG = new double[count];
        accumB = new double[count];
        denoiseR = new double[count];
        denoiseG = new double[count];
        denoiseB = new double[count];
        guideDepth = new float[count];
        guideNormal = new float[count * 3];
    }

    private void applyDenoiseAndResolve(int[] outColor, double invSamples) {
        JointBilateralDenoiser.apply(
                width,
                height,
                workerCount,
                threadPool,
                denoiseRadius,
                clamp01(denoiseStrength),
                exposure,
                invSamples,
                accumR,
                accumG,
                accumB,
                guideDepth,
                guideNormal,
                denoiseR,
                denoiseG,
                denoiseB,
                outColor
        );
    }

    private boolean ensureRuntimeBuffers(int fbWidth, int fbHeight, int[] outColor) {
        int count = safePixelCount(fbWidth, fbHeight);
        if (outColor == null || outColor.length < count) {
            return false;
        }
        if (accumR.length < count
                || accumG.length < count
                || accumB.length < count
                || denoiseR.length < count
                || denoiseG.length < count
                || denoiseB.length < count
                || guideDepth.length < count
                || guideNormal.length < count * 3) {
            allocateAccumulation(fbWidth, fbHeight);
            accumulatedSamples = 0L;
        }
        return true;
    }

    private int safePixelCount(int w, int h) {
        long count = (long) Math.max(1, w) * (long) Math.max(1, h);
        if (count > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) count;
    }

    private void rebuildThreadPool() {
        shutdownPool();
        if (workerCount > 1) {
            threadPool = new ThreadPool(workerCount);
        }
    }

    private void shutdownPool() {
        if (threadPool != null) {
            threadPool.shutdown();
            threadPool = null;
        }
    }

    private long seedForWorker(int workerIndex, long sampleTarget) {
        long x = 0x9E3779B97F4A7C15L;
        x ^= (sampleTarget + 0xBF58476D1CE4E5B9L);
        x ^= ((long) workerIndex + 1L) * 0x94D049BB133111EBL;
        return x;
    }

    private double toneMap(double c) {
        double mapped = 1.0 - Math.exp(-Math.max(0.0, c) * exposure);
        mapped = Math.max(0.0, Math.min(1.0, mapped));
        return Math.pow(mapped, INV_GAMMA);
    }

    private int packColor(double r, double g, double b) {
        int ir = (int) (Math.max(0.0, Math.min(1.0, r)) * 255.0 + 0.5);
        int ig = (int) (Math.max(0.0, Math.min(1.0, g)) * 255.0 + 0.5);
        int ib = (int) (Math.max(0.0, Math.min(1.0, b)) * 255.0 + 0.5);
        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }

    private long mixHash(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private long quantizedBits(double value, double step) {
        return Math.round(value / step);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private PhongMaterial toPhongMaterial(Material base) {
        if (base instanceof PhongMaterial) {
            return (PhongMaterial) base;
        }
        Vec3 c = base != null ? base.getBaseColor() : new Vec3(0.75, 0.75, 0.75);
        PhongMaterial out = new PhongMaterial(c, 32.0);
        if (base != null) {
            out.copyFrom(base);
        }
        return out;
    }

    private static final class CameraState {
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

    private static final class Triangle {
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
        final Texture texture;
        final boolean textureLinear;
        final boolean floorGrid;
        final PhongMaterial material;
        final double minX, minY, minZ;
        final double maxX, maxY, maxZ;
        final double centroidX, centroidY, centroidZ;

        Triangle(double ax, double ay, double az,
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
                 Texture texture, boolean textureLinear, boolean floorGrid,
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
            this.texture = texture;
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
        }
    }

    private static final class BVHNode {
        double minX, minY, minZ;
        double maxX, maxY, maxZ;
        BVHNode left;
        BVHNode right;
        int start;
        int end;
    }

    private static final class Hit {
        double t;
        double u;
        double v;
        double px, py, pz;
        Triangle triangle;

        void copyFrom(Hit other) {
            t = other.t;
            u = other.u;
            v = other.v;
            px = other.px;
            py = other.py;
            pz = other.pz;
            triangle = other.triangle;
        }
    }

    private static final class DirLightCache {
        final double lx, ly, lz;
        final double r, g, b;

        DirLightCache(double lx, double ly, double lz, double r, double g, double b) {
            this.lx = lx;
            this.ly = ly;
            this.lz = lz;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    private static final class PointLightCache {
        final PointLight light;
        final double px, py, pz;
        final double r, g, b;

        PointLightCache(PointLight light, double px, double py, double pz, double r, double g, double b) {
            this.light = light;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    private static final class SurfaceState {
        double nx, ny, nz;
        double smoothNx, smoothNy, smoothNz;
        double geomNx, geomNy, geomNz;
        double baseR, baseG, baseB;
        double specR, specG, specB;
        double roughness;
        double reflectivity;
        double opacity;
        double transmission;
        double refractiveIndex;
        double emissionR, emissionG, emissionB;
        double mediumR, mediumG, mediumB;
        double density;
        double thickness;
        double sheenR, sheenG, sheenB;
        boolean discard;
    }

    private static final class TraceContext {
        final BVHNode[] nodeStack = new BVHNode[1024];
        final Hit hit = new Hit();
        final Hit tempHit = new Hit();
        final SurfaceState surface = new SurfaceState();
        final SurfaceState shadowSurface = new SurfaceState();
        final Vec3 tmpVec0 = new Vec3();
        final Vec3 tmpVec1 = new Vec3();
        final Vec3 tmpVec2 = new Vec3();
        final Vec3 tmpVec3 = new Vec3();
        final Vec3 tmpVec4 = new Vec3();
        final Vec3 tmpVec5 = new Vec3();
        double rayOx, rayOy, rayOz;
        double rayDx, rayDy, rayDz;
        double shadowBaseX, shadowBaseY, shadowBaseZ;
        double shadowOx, shadowOy, shadowOz;
        double shadowTMin;
        double envR, envG, envB;
        double outR, outG, outB;
    }

    private static final class SplitMix64 {
        private long state;

        SplitMix64(long seed) {
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
}
