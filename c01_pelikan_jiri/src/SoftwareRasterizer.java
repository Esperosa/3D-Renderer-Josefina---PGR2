import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class SoftwareRasterizer {
    private static final double NEAR = 0.08;
    private static final double FAR = 80.0;

    private BufferedImage image;
    private double[] zBuffer;
    private int[] ownerBuffer;
    private int width;
    private int height;

    BufferedImage render(SceneModel scene, Camera camera) {
        ensureBuffers();
        clear();
        drawAxes(camera, scene.perspective);

        for (int i = 0; i < scene.entities.size(); i++) {
            Entity entity = scene.entities.get(i);
            boolean selected = i == scene.selectedIndex;
            renderEntity(scene, camera, entity, i + 1, selected);
        }

        drawOverlay(scene, camera);
        return image;
    }

    int pickOwner(int x, int y) {
        if (ownerBuffer == null || x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return ownerBuffer[y * width + x];
    }

    void resize(int width, int height) {
        int safeW = Math.max(32, width);
        int safeH = Math.max(32, height);
        if (safeW != this.width || safeH != this.height) {
            this.width = safeW;
            this.height = safeH;
            image = new BufferedImage(safeW, safeH, BufferedImage.TYPE_INT_ARGB);
            zBuffer = new double[safeW * safeH];
            ownerBuffer = new int[safeW * safeH];
        }
    }

    private void ensureBuffers() {
        if (image == null) {
            resize(900, 620);
        }
    }

    private void clear() {
        int bgTop = rgb(new Vec3(0.055, 0.065, 0.080));
        int bgBottom = rgb(new Vec3(0.090, 0.096, 0.105));
        for (int y = 0; y < height; y++) {
            double t = y / (double) Math.max(1, height - 1);
            int row = blend(bgTop, bgBottom, t);
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, row);
            }
        }
        Arrays.fill(zBuffer, Double.POSITIVE_INFINITY);
        Arrays.fill(ownerBuffer, 0);
    }

    private void renderEntity(SceneModel scene, Camera camera, Entity entity, int owner, boolean selected) {
        CachedVertex[] cache = new CachedVertex[entity.mesh.vertices.size()];
        for (int i = 0; i < entity.mesh.vertices.size(); i++) {
            Vertex v = entity.mesh.vertices.get(i);
            Vec3 world = entity.transform.applyPoint(v.position);
            Vec3 normal = entity.transform.applyDirection(v.normal);
            Vec3 view = camera.toView(world);
            cache[i] = new CachedVertex(world, view, normal, v.uv, v.color, entity);
        }

        if (scene.filled) {
            for (Face face : entity.mesh.faces) {
                drawClippedTriangle(scene, camera, cache[face.a], cache[face.b], cache[face.c], owner);
            }
            if (selected) {
                for (Edge edge : entity.mesh.edges) {
                    drawLine(camera, cache[edge.a], cache[edge.b], new Vec3(1.0, 0.96, 0.16), owner, scene.perspective, -0.002);
                }
            }
        } else {
            Vec3 color = selected ? new Vec3(1.0, 0.96, 0.16) : entity.baseColor.mul(1.15).clamp01();
            if (entity.lightMarker) {
                color = scene.lightColor;
            }
            for (Edge edge : entity.mesh.edges) {
                drawLine(camera, cache[edge.a], cache[edge.b], color, owner, scene.perspective, selected ? -0.003 : 0.0);
            }
        }
    }

    private void drawClippedTriangle(SceneModel scene, Camera camera, CachedVertex a, CachedVertex b, CachedVertex c, int owner) {
        if ((a.view.z > FAR && b.view.z > FAR && c.view.z > FAR)
                || (a.view.z < NEAR && b.view.z < NEAR && c.view.z < NEAR)) {
            return;
        }
        List<CachedVertex> polygon = new ArrayList<>(4);
        polygon.add(a);
        polygon.add(b);
        polygon.add(c);
        polygon = clipNear(polygon);
        if (polygon.size() < 3) {
            return;
        }
        ScreenVertex root = project(camera, polygon.get(0), scene.perspective);
        for (int i = 1; i < polygon.size() - 1; i++) {
            ScreenVertex b1 = project(camera, polygon.get(i), scene.perspective);
            ScreenVertex c1 = project(camera, polygon.get(i + 1), scene.perspective);
            rasterTriangle(scene, camera, root, b1, c1, owner);
        }
    }

    private List<CachedVertex> clipNear(List<CachedVertex> input) {
        List<CachedVertex> out = new ArrayList<>(4);
        for (int i = 0; i < input.size(); i++) {
            CachedVertex current = input.get(i);
            CachedVertex previous = input.get((i + input.size() - 1) % input.size());
            boolean currentInside = current.view.z >= NEAR;
            boolean previousInside = previous.view.z >= NEAR;
            if (currentInside != previousInside) {
                double t = (NEAR - previous.view.z) / (current.view.z - previous.view.z);
                out.add(CachedVertex.lerp(previous, current, t));
            }
            if (currentInside) {
                out.add(current);
            }
        }
        return out;
    }

    private ScreenVertex project(Camera camera, CachedVertex v, boolean perspective) {
        double sx;
        double sy;
        double invZ;
        if (perspective) {
            double focal = height * 0.84;
            invZ = 1.0 / Math.max(NEAR, v.view.z);
            sx = width * 0.5 + v.view.x * focal * invZ;
            sy = height * 0.55 - v.view.y * focal * invZ;
        } else {
            double scale = Math.min(width, height) * 0.18;
            invZ = 1.0;
            sx = width * 0.5 + v.view.x * scale;
            sy = height * 0.55 - v.view.y * scale;
        }
        return new ScreenVertex(sx, sy, Math.max(NEAR, v.view.z), invZ, v.world, v.normal, v.uv, v.color, v.entity);
    }

    private void rasterTriangle(SceneModel scene, Camera camera, ScreenVertex a, ScreenVertex b, ScreenVertex c, int owner) {
        if (outsideScreen(a, b, c)) {
            return;
        }
        double area = edge(a.x, a.y, b.x, b.y, c.x, c.y);
        if (Math.abs(area) < 1.0e-6) {
            return;
        }
        int minX = clampInt((int) Math.floor(Math.min(a.x, Math.min(b.x, c.x))), 0, width - 1);
        int maxX = clampInt((int) Math.ceil(Math.max(a.x, Math.max(b.x, c.x))), 0, width - 1);
        int minY = clampInt((int) Math.floor(Math.min(a.y, Math.min(b.y, c.y))), 0, height - 1);
        int maxY = clampInt((int) Math.ceil(Math.max(a.y, Math.max(b.y, c.y))), 0, height - 1);
        if (minX > maxX || minY > maxY) {
            return;
        }
        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double w0 = edge(b.x, b.y, c.x, c.y, px, py) / area;
                double w1 = edge(c.x, c.y, a.x, a.y, px, py) / area;
                double w2 = 1.0 - w0 - w1;
                if (w0 < -1.0e-5 || w1 < -1.0e-5 || w2 < -1.0e-5) {
                    continue;
                }
                double depth;
                Interpolated p;
                if (scene.perspective) {
                    double denom = w0 * a.invZ + w1 * b.invZ + w2 * c.invZ;
                    if (denom <= 1.0e-9) {
                        continue;
                    }
                    depth = 1.0 / denom;
                    p = interpolatePerspective(a, b, c, w0, w1, w2, denom);
                } else {
                    depth = w0 * a.depth + w1 * b.depth + w2 * c.depth;
                    p = interpolateLinear(a, b, c, w0, w1, w2);
                }
                int index = y * width + x;
                if (depth >= NEAR && depth <= FAR && depth < zBuffer[index]) {
                    zBuffer[index] = depth;
                    ownerBuffer[index] = owner;
                    image.setRGB(x, y, shade(scene, camera, p, a.entity));
                }
            }
        }
    }

    private boolean outsideScreen(ScreenVertex a, ScreenVertex b, ScreenVertex c) {
        return (a.x < 0 && b.x < 0 && c.x < 0)
                || (a.x >= width && b.x >= width && c.x >= width)
                || (a.y < 0 && b.y < 0 && c.y < 0)
                || (a.y >= height && b.y >= height && c.y >= height);
    }

    private Interpolated interpolatePerspective(ScreenVertex a, ScreenVertex b, ScreenVertex c,
                                                double w0, double w1, double w2, double denom) {
        double ka = w0 * a.invZ / denom;
        double kb = w1 * b.invZ / denom;
        double kc = w2 * c.invZ / denom;
        return new Interpolated(
                a.world.mul(ka).add(b.world.mul(kb)).add(c.world.mul(kc)),
                a.normal.mul(ka).add(b.normal.mul(kb)).add(c.normal.mul(kc)).normalize(),
                new Vec2(a.uv.x * ka + b.uv.x * kb + c.uv.x * kc, a.uv.y * ka + b.uv.y * kb + c.uv.y * kc),
                a.color.mul(ka).add(b.color.mul(kb)).add(c.color.mul(kc)).clamp01()
        );
    }

    private Interpolated interpolateLinear(ScreenVertex a, ScreenVertex b, ScreenVertex c, double w0, double w1, double w2) {
        return new Interpolated(
                a.world.mul(w0).add(b.world.mul(w1)).add(c.world.mul(w2)),
                a.normal.mul(w0).add(b.normal.mul(w1)).add(c.normal.mul(w2)).normalize(),
                new Vec2(a.uv.x * w0 + b.uv.x * w1 + c.uv.x * w2, a.uv.y * w0 + b.uv.y * w1 + c.uv.y * w2),
                a.color.mul(w0).add(b.color.mul(w1)).add(c.color.mul(w2)).clamp01()
        );
    }

    private int shade(SceneModel scene, Camera camera, Interpolated p, Entity entity) {
        if (entity.lightMarker) {
            return rgb(scene.lightColor.mul(1.25).clamp01());
        }
        Vec3 base = p.color;
        if (entity.textureEnabled && entity.texture != null) {
            base = entity.texture.sample(p.uv.x, p.uv.y).hadamard(p.color.mul(0.35).add(new Vec3(0.65, 0.65, 0.65))).clamp01();
        }

        Vec3 lightPos = scene.lightEntity().transform.position;
        Vec3 toLight = lightPos.sub(p.world).normalize();
        Vec3 viewDir = camera.position.sub(p.world).normalize();
        double diffuse = Math.max(0.0, p.normal.dot(toLight));
        Vec3 reflect = p.normal.mul(2.0 * p.normal.dot(toLight)).sub(toLight).normalize();
        double specular = Math.pow(Math.max(0.0, reflect.dot(viewDir)), 38.0) * 0.42;
        Vec3 ambient = base.mul(0.22);
        Vec3 lit = ambient.add(base.hadamard(scene.lightColor).mul(0.78 * diffuse)).add(scene.lightColor.mul(specular));
        return rgb(lit.clamp01());
    }

    private void drawLine(Camera camera, CachedVertex a, CachedVertex b, Vec3 color, int owner,
                          boolean perspective, double depthBias) {
        if (a.view.z < NEAR && b.view.z < NEAR) {
            return;
        }
        CachedVertex ca = a;
        CachedVertex cb = b;
        if (ca.view.z < NEAR || cb.view.z < NEAR) {
            double t = (NEAR - ca.view.z) / (cb.view.z - ca.view.z);
            CachedVertex mid = CachedVertex.lerp(ca, cb, t);
            if (ca.view.z < NEAR) {
                ca = mid;
            } else {
                cb = mid;
            }
        }
        ScreenVertex pa = project(camera, ca, perspective);
        ScreenVertex pb = project(camera, cb, perspective);
        if ((pa.x < 0 && pb.x < 0) || (pa.x >= width && pb.x >= width)
                || (pa.y < 0 && pb.y < 0) || (pa.y >= height && pb.y >= height)) {
            return;
        }
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(pb.x - pa.x), Math.abs(pb.y - pa.y))));
        int argb = rgb(color);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(pa.x + (pb.x - pa.x) * t);
            int y = (int) Math.round(pa.y + (pb.y - pa.y) * t);
            if (x < 0 || y < 0 || x >= width || y >= height) {
                continue;
            }
            double depth = pa.depth * (1.0 - t) + pb.depth * t + depthBias;
            int index = y * width + x;
            if (depth < zBuffer[index] + 0.01) {
                zBuffer[index] = Math.min(zBuffer[index], depth);
                ownerBuffer[index] = owner;
                image.setRGB(x, y, argb);
            }
        }
    }

    private void drawAxes(Camera camera, boolean perspective) {
        drawAxis(camera, new Vec3(0, 0, 0), new Vec3(2.4, 0, 0), new Vec3(1.0, 0.12, 0.10), perspective);
        drawAxis(camera, new Vec3(0, 0, 0), new Vec3(0, 2.0, 0), new Vec3(0.18, 0.9, 0.25), perspective);
        drawAxis(camera, new Vec3(0, 0, 0), new Vec3(0, 0, 2.4), new Vec3(0.16, 0.34, 1.0), perspective);
    }

    private void drawAxis(Camera camera, Vec3 start, Vec3 end, Vec3 color, boolean perspective) {
        CachedVertex a = simpleVertex(camera, start, color);
        CachedVertex b = simpleVertex(camera, end, color);
        drawLine(camera, a, b, color, 0, perspective, -0.004);
        Vec3 dir = end.sub(start).normalize();
        Vec3 side = Math.abs(dir.y) > 0.8 ? new Vec3(0.15, 0, 0) : new Vec3(0, 0.15, 0);
        Vec3 p1 = end;
        Vec3 p2 = end.sub(dir.mul(0.28)).add(side);
        Vec3 p3 = end.sub(dir.mul(0.28)).sub(side);
        ScreenVertex s1 = project(camera, simpleVertex(camera, p1, color), perspective);
        ScreenVertex s2 = project(camera, simpleVertex(camera, p2, color), perspective);
        ScreenVertex s3 = project(camera, simpleVertex(camera, p3, color), perspective);
        rasterUnlitTriangle(s1, s2, s3, color);
    }

    private CachedVertex simpleVertex(Camera camera, Vec3 world, Vec3 color) {
        Vec3 view = camera.toView(world);
        return new CachedVertex(world, view, new Vec3(0, 1, 0), new Vec2(0, 0), color, null);
    }

    private void rasterUnlitTriangle(ScreenVertex a, ScreenVertex b, ScreenVertex c, Vec3 color) {
        double area = edge(a.x, a.y, b.x, b.y, c.x, c.y);
        if (Math.abs(area) < 1.0e-6 || outsideScreen(a, b, c)) {
            return;
        }
        int minX = clampInt((int) Math.floor(Math.min(a.x, Math.min(b.x, c.x))), 0, width - 1);
        int maxX = clampInt((int) Math.ceil(Math.max(a.x, Math.max(b.x, c.x))), 0, width - 1);
        int minY = clampInt((int) Math.floor(Math.min(a.y, Math.min(b.y, c.y))), 0, height - 1);
        int maxY = clampInt((int) Math.ceil(Math.max(a.y, Math.max(b.y, c.y))), 0, height - 1);
        int argb = rgb(color);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                double w0 = edge(b.x, b.y, c.x, c.y, px, py) / area;
                double w1 = edge(c.x, c.y, a.x, a.y, px, py) / area;
                double w2 = 1.0 - w0 - w1;
                if (w0 < -1.0e-5 || w1 < -1.0e-5 || w2 < -1.0e-5) {
                    continue;
                }
                double depth = w0 * a.depth + w1 * b.depth + w2 * c.depth - 0.006;
                int index = y * width + x;
                if (depth < zBuffer[index]) {
                    zBuffer[index] = depth;
                    image.setRGB(x, y, argb);
                }
            }
        }
    }

    private void drawOverlay(SceneModel scene, Camera camera) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(255, 255, 255, 220));
        g.drawString("P: " + (scene.perspective ? "perspektiva" : "pravoúhlá")
                + "   M: " + (scene.filled ? "plochy" : "drát")
                + "   U: textura aktivního tělesa   WASD+myš: kamera", 14, 22);
        g.drawString("Výběr: 1-7 nebo klik   T/R/Y + šipky: posun/rotace/scale   C: barva světla   mezerník: animace",
                14, 42);
        g.setColor(new Color(255, 230, 120, 230));
        g.drawString("Aktivní: " + scene.selected().name, 14, height - 16);
        g.setColor(new Color(210, 220, 235, 180));
        g.drawString(String.format("Kamera [%.1f, %.1f, %.1f]", camera.position.x, camera.position.y, camera.position.z),
                width - 170, height - 16);
        g.dispose();
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int rgb(Vec3 c) {
        int r = clampInt((int) Math.round(c.x * 255.0), 0, 255);
        int g = clampInt((int) Math.round(c.y * 255.0), 0, 255);
        int b = clampInt((int) Math.round(c.z * 255.0), 0, 255);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int blend(int a, int b, double t) {
        int ar = (a >> 16) & 255;
        int ag = (a >> 8) & 255;
        int ab = a & 255;
        int br = (b >> 16) & 255;
        int bg = (b >> 8) & 255;
        int bb = b & 255;
        int r = (int) Math.round(ar * (1.0 - t) + br * t);
        int g = (int) Math.round(ag * (1.0 - t) + bg * t);
        int blue = (int) Math.round(ab * (1.0 - t) + bb * t);
        return 0xff000000 | (r << 16) | (g << 8) | blue;
    }

    private static final class CachedVertex {
        final Vec3 world;
        final Vec3 view;
        final Vec3 normal;
        final Vec2 uv;
        final Vec3 color;
        final Entity entity;

        CachedVertex(Vec3 world, Vec3 view, Vec3 normal, Vec2 uv, Vec3 color, Entity entity) {
            this.world = world;
            this.view = view;
            this.normal = normal;
            this.uv = uv;
            this.color = color;
            this.entity = entity;
        }

        static CachedVertex lerp(CachedVertex a, CachedVertex b, double t) {
            return new CachedVertex(
                    Vec3.lerp(a.world, b.world, t),
                    Vec3.lerp(a.view, b.view, t),
                    Vec3.lerp(a.normal, b.normal, t).normalize(),
                    a.uv.mul(1.0 - t).add(b.uv.mul(t)),
                    Vec3.lerp(a.color, b.color, t),
                    a.entity
            );
        }
    }

    private static final class ScreenVertex {
        final double x;
        final double y;
        final double depth;
        final double invZ;
        final Vec3 world;
        final Vec3 normal;
        final Vec2 uv;
        final Vec3 color;
        final Entity entity;

        ScreenVertex(double x, double y, double depth, double invZ,
                     Vec3 world, Vec3 normal, Vec2 uv, Vec3 color, Entity entity) {
            this.x = x;
            this.y = y;
            this.depth = depth;
            this.invZ = invZ;
            this.world = world;
            this.normal = normal;
            this.uv = uv;
            this.color = color;
            this.entity = entity;
        }
    }

    private record Interpolated(Vec3 world, Vec3 normal, Vec2 uv, Vec3 color) {
    }
}
