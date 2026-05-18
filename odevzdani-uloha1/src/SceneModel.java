import java.util.ArrayList;
import java.util.List;

final class SceneModel {
    final List<Entity> entities = new ArrayList<>();
    int selectedIndex = 0;
    boolean perspective = true;
    boolean filled = true;
    boolean lightAnimation = true;
    Vec3 lightColor = new Vec3(1.0, 0.26, 0.18);
    double lightAngle = 0.4;

    static SceneModel createDefault() {
        SceneModel scene = new SceneModel();

        Entity cube = new Entity("1 Krychle", GeometryFactory.cube(1.15, new Vec3(0.88, 0.43, 0.20)),
                new Vec3(0.88, 0.43, 0.20), Textures.checker(new Vec3(0.95, 0.72, 0.38), new Vec3(0.20, 0.12, 0.08), 8));
        cube.transform.position = new Vec3(-1.35, 0.0, 2.35);
        cube.transform.rotation = new Vec3(0.15, 0.35, 0.0);
        scene.entities.add(cube);

        Entity sphere = new Entity("2 Koule", GeometryFactory.sphere(0.68, 18, 28, new Vec3(0.25, 0.62, 0.92)),
                new Vec3(0.25, 0.62, 0.92), Textures.stripes(new Vec3(0.15, 0.42, 0.90), new Vec3(0.80, 0.96, 1.00), 10));
        sphere.transform.position = new Vec3(0.35, 0.05, 2.00);
        scene.entities.add(sphere);

        Entity cylinder = new Entity("3 Válec", GeometryFactory.cylinder(0.45, 1.45, 24, new Vec3(0.45, 0.82, 0.56)),
                new Vec3(0.45, 0.82, 0.56), Textures.grid(new Vec3(0.20, 0.55, 0.34), new Vec3(0.90, 1.0, 0.72), 7));
        cylinder.transform.position = new Vec3(1.65, 0.02, 2.70);
        cylinder.transform.rotation = new Vec3(0.0, -0.35, 0.0);
        scene.entities.add(cylinder);

        Entity cone = new Entity("4 Kužel", GeometryFactory.cone(0.55, 1.25, 26, new Vec3(0.76, 0.55, 0.94)),
                new Vec3(0.76, 0.55, 0.94), Textures.dots(new Vec3(0.42, 0.22, 0.62), new Vec3(0.94, 0.82, 1.00), 8));
        cone.transform.position = new Vec3(-0.20, -0.08, 3.30);
        cone.transform.rotation = new Vec3(0.0, 0.48, 0.0);
        scene.entities.add(cone);

        Entity tetra = new Entity("5 Čtyřstěn", GeometryFactory.tetrahedron(1.05, new Vec3(0.95, 0.78, 0.26)),
                new Vec3(0.95, 0.78, 0.26), Textures.checker(new Vec3(0.94, 0.82, 0.16), new Vec3(0.25, 0.20, 0.05), 5));
        tetra.transform.position = new Vec3(1.10, -0.18, 1.30);
        tetra.transform.rotation = new Vec3(0.0, -0.70, 0.12);
        scene.entities.add(tetra);

        Entity patch = new Entity("6 Bikubická plocha", GeometryFactory.bicubicPatch(12, new Vec3(0.40, 0.88, 0.86)),
                new Vec3(0.40, 0.88, 0.86), Textures.grid(new Vec3(0.10, 0.48, 0.50), new Vec3(0.82, 1.0, 0.95), 6));
        patch.transform.position = new Vec3(-1.40, -0.45, 3.80);
        patch.transform.rotation = new Vec3(-0.45, 0.40, 0.0);
        patch.transform.scale = 0.75;
        scene.entities.add(patch);

        Entity light = new Entity("7 Světlo", GeometryFactory.sphere(0.18, 10, 16, new Vec3(1.0, 0.26, 0.18)),
                new Vec3(1.0, 0.26, 0.18), Textures.solid(new Vec3(1.0, 0.26, 0.18)));
        light.transform.position = new Vec3(0.0, 1.85, 1.15);
        light.textureEnabled = false;
        light.lightMarker = true;
        scene.entities.add(light);

        return scene;
    }

    Entity selected() {
        if (selectedIndex < 0 || selectedIndex >= entities.size()) {
            selectedIndex = 0;
        }
        return entities.get(selectedIndex);
    }

    Entity lightEntity() {
        for (Entity entity : entities) {
            if (entity.lightMarker) {
                return entity;
            }
        }
        return entities.get(entities.size() - 1);
    }

    void updateLight(double dt) {
        Entity light = lightEntity();
        if (!lightAnimation) {
            return;
        }
        Entity selected = selected();
        if (selected != light) {
            selected.transform.rotation = selected.transform.rotation.add(new Vec3(0.0, dt * 0.35, 0.0));
        }
        if (selected == light) {
            return;
        }
        lightAngle += dt * 0.75;
        light.transform.position = new Vec3(Math.cos(lightAngle) * 2.2, 1.65 + Math.sin(lightAngle * 1.7) * 0.35,
                2.25 + Math.sin(lightAngle) * 1.25);
    }

    void cycleLightColor() {
        Vec3[] colors = {
                new Vec3(1.0, 0.26, 0.18),
                new Vec3(0.30, 0.75, 1.0),
                new Vec3(0.72, 1.0, 0.38),
                new Vec3(1.0, 0.88, 0.35)
        };
        int next = 0;
        for (int i = 0; i < colors.length; i++) {
            if (Math.abs(lightColor.x - colors[i].x) < 0.01
                    && Math.abs(lightColor.y - colors[i].y) < 0.01
                    && Math.abs(lightColor.z - colors[i].z) < 0.01) {
                next = (i + 1) % colors.length;
                break;
            }
        }
        lightColor = colors[next];
        Entity light = lightEntity();
        light.textureEnabled = false;
    }
}

final class Textures {
    private Textures() {
    }

    static ProceduralTexture solid(Vec3 color) {
        return (u, v) -> color;
    }

    static ProceduralTexture checker(Vec3 a, Vec3 b, int cells) {
        return (u, v) -> {
            int x = (int) Math.floor(wrap(u) * cells);
            int y = (int) Math.floor(wrap(v) * cells);
            return ((x + y) & 1) == 0 ? a : b;
        };
    }

    static ProceduralTexture stripes(Vec3 a, Vec3 b, int cells) {
        return (u, v) -> ((int) Math.floor(wrap(u) * cells) & 1) == 0 ? a : b;
    }

    static ProceduralTexture grid(Vec3 a, Vec3 b, int cells) {
        return (u, v) -> {
            double fu = wrap(u) * cells;
            double fv = wrap(v) * cells;
            double line = Math.min(Math.abs(fu - Math.round(fu)), Math.abs(fv - Math.round(fv)));
            return line < 0.06 ? b : a;
        };
    }

    static ProceduralTexture dots(Vec3 a, Vec3 b, int cells) {
        return (u, v) -> {
            double fu = wrap(u) * cells;
            double fv = wrap(v) * cells;
            double du = fu - Math.floor(fu) - 0.5;
            double dv = fv - Math.floor(fv) - 0.5;
            return du * du + dv * dv < 0.08 ? b : a;
        };
    }

    private static double wrap(double v) {
        double f = v - Math.floor(v);
        return f < 0.0 ? f + 1.0 : f;
    }
}
