package engine.render.post;

import engine.camera.Camera;
import engine.camera.Frustum;
import engine.geometry.Mesh;
import engine.material.Material;
import engine.material.WireframeMaterial;
import engine.math.Mat4;
import engine.math.MathUtil;
import engine.math.Vec3;
import engine.math.Vec4;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.scene.Entity;
import engine.scene.Scene;

/**
 * Represents gradientní wireframe renderer.
 */
public class WireframeRenderer implements Renderer {

    private boolean depthHiddenLines;
    private boolean silhouetteBoost;
    private boolean dashedMode;
    private final Frustum frustum;

    public WireframeRenderer() {
        this.depthHiddenLines = true;
        this.silhouetteBoost = true;
        this.dashedMode = false;
        this.frustum = new Frustum();
    }

    @Override
    public void init(int width, int height) {
 // nepotřebuju žádné další buffery.
    }

    @Override
    public void render(Scene scene, Camera camera, FrameBuffer fb, double time) {
        int clearColor = 0xFF000000 | scene.getBackgroundColor().toIntRGB();
        fb.clear(clearColor, 1.0f);

        Mat4 view = camera.getViewMatrix();
        Mat4 projection = camera.getProjectionMatrix();
        Mat4 vp = projection.multiply(view);
        frustum.extractFromMatrix(vp);

        float[] sx = new float[3];
        float[] sy = new float[3];
        float[] sz = new float[3];
        Vec3[] wp = new Vec3[3];
        Vec3[] wn = new Vec3[3];

        for (Entity entity : scene.getAllMeshEntities()) {
            entity.computeWorldBounds();
            if (entity.getWorldBounds() != null && !frustum.intersects(entity.getWorldBounds())) {
                continue;
            }

            Mesh mesh = entity.getMesh();
            WireframeMaterial material = toWireframeMaterial(entity.getMaterial());
            Mat4 model = entity.getWorldMatrix();
            Mat4 mvp = vp.multiply(model);

            float[] positions = mesh.getPositions();
            float[] normals = mesh.getNormals();
            int[] indices = mesh.getIndices();

            for (int t = 0; t < indices.length; t += 3) {
                boolean skip = false;
                for (int k = 0; k < 3; k++) {
                    int vi = indices[t + k];
                    int p = vi * 3;

                    Vec3 pos = new Vec3(positions[p], positions[p + 1], positions[p + 2]);
                    Vec4 clip = mvp.transform(new Vec4(pos, 1.0));
                    if (clip.w <= 1e-5) {
                        skip = true;
                        break;
                    }

                    double invW = 1.0 / clip.w;
                    Vec3 ndc = new Vec3(clip.x * invW, clip.y * invW, clip.z * invW);
                    sx[k] = (float) ((ndc.x * 0.5 + 0.5) * (fb.getWidth() - 1));
                    sy[k] = (float) ((1.0 - (ndc.y * 0.5 + 0.5)) * (fb.getHeight() - 1));
                    sz[k] = (float) (ndc.z * 0.5 + 0.5);
                    wp[k] = model.transformPoint(pos);

                    if (normals != null && normals.length >= p + 3) {
                        wn[k] = model.transformDirection(
                                new Vec3(normals[p], normals[p + 1], normals[p + 2]).normalize()
                        ).normalize();
                    } else {
                        wn[k] = new Vec3(0.0, 1.0, 0.0);
                    }
                }

                if (skip) {
                    continue;
                }

                float area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sy[1] - sy[0]) * (sx[2] - sx[0]);
                if (area >= 0.0f) {
                    continue;
                }

                int c0 = endpointColor(material, camera, wp[0], wn[0]);
                int c1 = endpointColor(material, camera, wp[1], wn[1]);
                int c2 = endpointColor(material, camera, wp[2], wn[2]);

                drawEdge(fb, sx[0], sy[0], sx[1], sy[1], c0, c1, sz[0], sz[1], material);
                drawEdge(fb, sx[1], sy[1], sx[2], sy[2], c1, c2, sz[1], sz[2], material);
                drawEdge(fb, sx[2], sy[2], sx[0], sy[0], c2, c0, sz[2], sz[0], material);
            }
        }
    }

    @Override
    public void resize(int width, int height) {
 // Nothing to resize.
    }

    @Override
    public void setParameter(String key, Object value) {
        if ("depthHiddenLines".equalsIgnoreCase(key) && value instanceof Boolean) {
            depthHiddenLines = (Boolean) value;
            return;
        }
        if ("silhouetteBoost".equalsIgnoreCase(key) && value instanceof Boolean) {
            silhouetteBoost = (Boolean) value;
            return;
        }
        if ("dashedMode".equalsIgnoreCase(key) && value instanceof Boolean) {
            dashedMode = (Boolean) value;
        }
    }

    @Override
    public String getName() {
        return "Wireframe";
    }

    public boolean isDepthHiddenLines() {
        return depthHiddenLines;
    }

    public boolean isSilhouetteBoost() {
        return silhouetteBoost;
    }

    public boolean isDashedMode() {
        return dashedMode;
    }

    private WireframeMaterial toWireframeMaterial(Material material) {
        if (material instanceof WireframeMaterial) {
            return (WireframeMaterial) material;
        }
        Vec3 base = material != null ? material.getBaseColor() : new Vec3(0.85, 0.9, 1.0);
        return new WireframeMaterial(base);
    }

    private int endpointColor(WireframeMaterial material, Camera camera, Vec3 worldPos, Vec3 worldNormal) {
        Vec3 viewDir = camera.getPosition().sub(worldPos).normalize();
        double dist = camera.getPosition().sub(worldPos).length();
        double t = MathUtil.clamp01(MathUtil.inverseLerp(material.getDistanceNear(), material.getDistanceFar(), dist));
        double brightness = MathUtil.lerp(material.getMaxBrightness(), material.getMinBrightness(), t);

        double emphasis = 1.0;
        if (silhouetteBoost) {
            double silhouette = 1.0 - Math.abs(worldNormal.normalize().dot(viewDir));
            emphasis += silhouette * material.getSilhouetteBoost();
        }

        Vec3 c = material.getBaseColor().mul(brightness * emphasis);
        int r = (int) (MathUtil.clamp01(c.x) * 255.0 + 0.5);
        int g = (int) (MathUtil.clamp01(c.y) * 255.0 + 0.5);
        int b = (int) (MathUtil.clamp01(c.z) * 255.0 + 0.5);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawEdge(FrameBuffer fb, float x0, float y0, float x1, float y1,
                          int color0, int color1, float depth0, float depth1, WireframeMaterial material) {
        int ix0 = (int) Math.round(x0);
        int iy0 = (int) Math.round(y0);
        int ix1 = (int) Math.round(x1);
        int iy1 = (int) Math.round(y1);

        int dx = Math.abs(ix1 - ix0);
        int dy = Math.abs(iy1 - iy0);
        int steps = Math.max(dx, dy);
        if (steps == 0) {
            if (depthHiddenLines) {
                fb.setPixelIfCloser(ix0, iy0, depth0, color0);
            } else {
                fb.setPixel(ix0, iy0, color0);
            }
            return;
        }

        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            if (dashedMode || material.isDashEnabled()) {
                float dashLen = (float) material.getDashLength();
                if (((int) (i / dashLen)) % 2 == 1) {
                    continue;
                }
            }
            int x = Math.round(ix0 + (ix1 - ix0) * t);
            int y = Math.round(iy0 + (iy1 - iy0) * t);
            float depth = depth0 + (depth1 - depth0) * t;
            int color = lerpColor(color0, color1, t);
            if (depthHiddenLines) {
                fb.setPixelIfCloser(x, y, depth, color);
            } else {
                fb.setPixel(x, y, color);
            }
        }
    }

    private int lerpColor(int c0, int c1, float t) {
        int r0 = (c0 >> 16) & 0xFF;
        int g0 = (c0 >> 8) & 0xFF;
        int b0 = c0 & 0xFF;
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;

        int r = (int) (r0 + (r1 - r0) * t);
        int g = (int) (g0 + (g1 - g0) * t);
        int b = (int) (b0 + (b1 - b0) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}