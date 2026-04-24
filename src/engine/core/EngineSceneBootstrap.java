package engine.core;

import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;
import engine.io.FileUtil;
import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.io.ObjLoader;
import engine.material.PhongMaterial;
import engine.math.AABB;
import engine.math.Vec3;
import engine.physics.AABBCollider;
import engine.physics.RigidBody;
import engine.render.Texture;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class EngineSceneBootstrap {
    static final String STARTUP_MODEL_PATH = "assets/models/StartModel.glb";
    static final String STARTUP_MODEL_DISPLAY_NAME = "Josefína";
    private static final double STARTUP_FILMIC_WORLD_STRENGTH = 1.10;
    private static final double STARTUP_FILMIC_ENV_EXPOSURE_BOOST = 1.12;

    private EngineSceneBootstrap() {
    }

    static Scene createDefaultScene(Engine engine) {
        Scene defaultScene = new Scene();
        double floorY = 0.0;
        if (!loadStartupScene(engine, defaultScene, floorY)) {
            Mesh mesh = loadDefaultMesh(engine);
            PhongMaterial demoMaterial = new PhongMaterial(new Vec3(0.78, 0.85, 0.95), 42.0);
            demoMaterial.setSpecularColor(new Vec3(1.0, 1.0, 1.0));
            if (engine.loadedDiffuseTexturePath != null && FileUtil.exists(engine.loadedDiffuseTexturePath)) {
                try {
                    demoMaterial.setDiffuseTexture(Texture.load(engine.loadedDiffuseTexturePath));
                    demoMaterial.setTextureFilteringLinear(true);
                    System.out.println("Loaded diffuse texture: " + engine.loadedDiffuseTexturePath);
                } catch (RuntimeException ex) {
                    System.out.println("Failed to load diffuse texture: " + ex.getMessage());
                }
            }
            engine.demoEntity = new Entity("demo-mesh", mesh, demoMaterial);
            double rawHeight = mesh.getAABB() != null
                    ? (mesh.getAABB().getMax().y - mesh.getAABB().getMin().y)
                    : 1.0;
            double targetHeight = 2.0;
            double uniformScale = rawHeight > 1e-6 ? targetHeight / rawHeight : 1.0;
            engine.demoEntity.getTransform().setScale(new Vec3(uniformScale, uniformScale, uniformScale));
            engine.demoEntity.getTransform().setPosition(new Vec3(0.0, floorY + 3.5, 0.0));
            defaultScene.addEntity(engine.demoEntity);

            RigidBody demoBody = new RigidBody(engine.demoEntity, RigidBody.BodyType.DYNAMIC, 1.0);
            demoBody.setCollider(engine.createFittedAabbCollider(mesh, uniformScale));
            demoBody.setRestitution(0.02);
            engine.physicsWorld.addBody(demoBody);
        }
        engine.selectedEntity = null;
        engine.selectedLight = null;
        engine.selectedForceField = null;

        double floorSize = 120.0;
        Mesh floorMesh = MeshGenerator.plane(floorSize, floorSize, 16, 16);
        PhongMaterial floorMaterial = new PhongMaterial(new Vec3(0.50, 0.52, 0.55), 6.0);
        floorMaterial.setSpecularColor(new Vec3(0.07, 0.07, 0.07));
        floorMaterial.setRoughness(0.02);
        floorMaterial.setMetallic(1.0);
        floorMaterial.setName("floor-grid");
        engine.floorEntity = new Entity("floor", floorMesh, floorMaterial);
        engine.floorEntity.getTransform().setPosition(new Vec3(0.0, floorY, 0.0));
        engine.floorEntity.setStatic(true);
        defaultScene.addEntity(engine.floorEntity);

        RigidBody floorBody = new RigidBody(engine.floorEntity, RigidBody.BodyType.STATIC, 1.0);
        double floorHalfHeight = 0.30;
        AABBCollider floorCollider = new AABBCollider(new Vec3(floorSize * 0.5, floorHalfHeight, floorSize * 0.5));
        floorCollider.setOffset(new Vec3(0.0, -floorHalfHeight, 0.0));
        floorBody.setCollider(floorCollider);
        engine.physicsWorld.addBody(floorBody);

        engine.outputCameraEntity = engine.createOutputCameraEntity();
        defaultScene.addEntity(engine.outputCameraEntity);

        engine.sunLight = new DirectionalLight(
                new Vec3(-0.34, -1.0, -0.18),
                new Vec3(1.0, 0.95, 0.88),
                2.18
        );
        engine.fillLight = new DirectionalLight(
            new Vec3(0.22, -1.0, 0.34),
            new Vec3(0.90, 0.95, 1.0),
            1.52
        );
        engine.warmWorldLight = null;
        engine.coolWorldLight = null;

        defaultScene.addLight(engine.sunLight);
        defaultScene.addLight(engine.fillLight);
        EngineWorldManager.registerLightName(engine, engine.sunLight, "Sun");
        EngineWorldManager.registerLightName(engine, engine.fillLight, "Top Sun");
        EngineWorldManager.applyPresetState(engine, WorldPresetCatalog.defaultPreset());
        WorldEnvironmentLibrary.EnvironmentBinding binding = WorldEnvironmentLibrary.resolveForPreset(engine.worldPresetKey);
        if (binding != null) {
            defaultScene.setEnvironmentMapKey(binding.assetKey);
            defaultScene.setEnvironmentMap(binding.map);
            defaultScene.setEnvironmentExposure(binding.exposure * STARTUP_FILMIC_ENV_EXPOSURE_BOOST);
        }
        engine.worldLightStrength = Math.max(engine.worldLightStrength, STARTUP_FILMIC_WORLD_STRENGTH);
        engine.worldLightAppliedStrength = engine.worldLightStrength;
        defaultScene.setAmbientColor(engine.worldLightColor.mul(engine.worldLightStrength));
        defaultScene.setEnvironmentStrength(engine.worldLightStrength);
        defaultScene.setBackgroundColor(engine.worldBackgroundColor);
        return defaultScene;
    }

    static Mesh loadDefaultMesh(Engine engine) {
        engine.loadedModelPath = null;
        engine.loadedDiffuseTexturePath = null;
        String defaultPath = "assets/models/cube.obj";
        if (FileUtil.exists(defaultPath)) {
            try {
                System.out.println("Loading model: " + defaultPath);
                Mesh mesh = new ObjLoader().load(defaultPath);
                engine.loadedModelPath = defaultPath;
                engine.loadedDiffuseTexturePath = findDiffuseTextureForObj(engine, defaultPath);
                return mesh;
            } catch (RuntimeException ex) {
                System.out.println("Failed to load default OBJ, using fallback: " + ex.getMessage());
            }
        }
        System.out.println("Using built-in fallback cube mesh.");
        return MeshGenerator.cube(1.0);
    }

    static void focusInitialScene(Engine engine) {
        if (engine == null || engine.scene == null || engine.camera == null || engine.cameraController == null) {
            return;
        }
        List<Entity> focusEntities = new ArrayList<>();
        AABB bounds = null;
        for (Entity entity : engine.scene.getEntities()) {
            if (entity == null || entity == engine.floorEntity || entity == engine.outputCameraEntity || entity.getMesh() == null) {
                continue;
            }
            entity.computeWorldBounds();
            focusEntities.add(entity);
            bounds = mergeBounds(bounds, entity.getWorldBounds());
        }
        if (focusEntities.isEmpty()) {
            return;
        }
        EngineSceneActions.focusCameraOnImportedBounds(
                engine,
                bounds,
                engine.demoEntity != null ? engine.demoEntity : focusEntities.get(0),
                focusEntities
        );
    }

    private static boolean loadStartupScene(Engine engine, Scene defaultScene, double floorY) {
        engine.loadedModelPath = null;
        engine.loadedDiffuseTexturePath = null;
        if (!FileUtil.exists(STARTUP_MODEL_PATH)) {
            System.out.println("Startup model not found, using fallback: " + STARTUP_MODEL_PATH);
            return false;
        }
        try {
            ImportedScene imported = new ModelImporter().importScene(STARTUP_MODEL_PATH);
            List<Entity> importedEntities = new ArrayList<>();
            int index = 0;
            for (ImportedScene.Entry entry : imported.getEntries()) {
                if (entry == null || entry.getMesh() == null) {
                    continue;
                }
                PhongMaterial material = entry.getMaterial() != null
                        ? entry.getMaterial().copy()
                        : createBootstrapMaterial(entry.getName(), index);
                configureStartupGlassMaterial(material);
                String name = index == 0
                        ? STARTUP_MODEL_DISPLAY_NAME
                        : sanitizeBootstrapName(entry.getName(), index);
                Entity entity = new Entity(name, entry.getMesh(), material);
                entity.getTransform().setPosition(entry.getPosition());
                entity.getTransform().setRotation(entry.getRotation());
                entity.getTransform().setScale(entry.getScale());
                defaultScene.addEntity(entity);
                importedEntities.add(entity);
                index++;
            }
            if (importedEntities.isEmpty()) {
                return false;
            }
            centerEntitiesOnFloor(importedEntities, floorY);
            engine.demoEntity = importedEntities.get(0);
            engine.demoEntity.setName(STARTUP_MODEL_DISPLAY_NAME);
            engine.loadedModelPath = STARTUP_MODEL_PATH;
            System.out.println("Loading startup model: " + STARTUP_MODEL_PATH);
            return true;
        } catch (RuntimeException ex) {
            System.out.println("Failed to load startup model, using fallback: " + ex.getMessage());
            return false;
        }
    }

    private static void centerEntitiesOnFloor(List<Entity> entities, double floorY) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        AABB bounds = null;
        for (Entity entity : entities) {
            entity.computeWorldBounds();
            bounds = mergeBounds(bounds, entity.getWorldBounds());
        }
        if (bounds == null) {
            return;
        }
        Vec3 center = bounds.center();
        Vec3 offset = new Vec3(-center.x, floorY - bounds.getMin().y, -center.z);
        for (Entity entity : entities) {
            entity.getTransform().setPosition(entity.getTransform().getPosition().add(offset));
            entity.computeWorldBounds();
        }
    }

    private static AABB mergeBounds(AABB current, AABB next) {
        if (current == null) {
            return next;
        }
        if (next == null) {
            return current;
        }
        return AABB.merge(current, next);
    }

    private static PhongMaterial createBootstrapMaterial(String baseName, int index) {
        int h = (baseName == null ? 0 : baseName.hashCode()) ^ (index * 0x9E3779B9);
        double r = 0.35 + (((h >>> 16) & 0xFF) / 255.0) * 0.45;
        double g = 0.35 + (((h >>> 8) & 0xFF) / 255.0) * 0.45;
        double b = 0.35 + ((h & 0xFF) / 255.0) * 0.45;
        PhongMaterial material = new PhongMaterial(new Vec3(r, g, b), 24.0);
        material.setSpecularColor(new Vec3(0.85, 0.85, 0.85));
        return material;
    }

    private static String sanitizeBootstrapName(String raw, int index) {
        String base = raw == null || raw.isBlank() ? "start-model-" + index : raw.trim();
        String clean = base.replace('\\', '_').replace('/', '_').replace(':', '_');
        return clean.isBlank() ? "start-model-" + index : clean;
    }

    private static void configureStartupGlassMaterial(PhongMaterial material) {
        if (material == null) {
            return;
        }
        boolean hasDiffuseTexture = material.hasDiffuseTexture();
        material.setTransmission(hasDiffuseTexture ? 0.41 : 0.52);
        material.setRefractiveIndex(1.42);
        material.setRoughness(hasDiffuseTexture ? 0.032 : 0.028);
        material.setMetallic(0.0);
        material.setReflectivity(0.24);
        material.setSpecularColor(new Vec3(1.0, 1.0, 1.0));
        material.setSpecularFactor(1.0);
        material.setMediumColor(hasDiffuseTexture ? new Vec3(0.90, 0.95, 0.99) : new Vec3(0.94, 0.98, 1.0));
        material.setDensity(hasDiffuseTexture ? 0.014 : 0.032);
        material.setThickness(hasDiffuseTexture ? 0.028 : 0.042);
    }

    static String findDiffuseTextureForObj(Engine engine, String objPath) {
        Path obj = Path.of(objPath).toAbsolutePath();
        Path dir = obj.getParent();
        if (dir == null || !Files.exists(obj)) {
            return null;
        }

        List<Path> mtlCandidates = new ArrayList<>();
        try {
            List<String> objLines = FileUtil.readLines(objPath);
            for (String raw : objLines) {
                String line = raw.trim();
                if (line.startsWith("mtllib ")) {
                    String[] tokens = line.substring(7).trim().split("\\s+");
                    for (String token : tokens) {
                        if (!token.isBlank()) {
                            mtlCandidates.add(dir.resolve(token).normalize());
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
 // spadnu do náhradní větve se scanem textur.
        }

        for (Path mtlPath : mtlCandidates) {
            if (!Files.exists(mtlPath)) {
                continue;
            }
            try {
                for (String raw : FileUtil.readLines(mtlPath.toString())) {
                    String line = raw.trim();
                    if (!line.startsWith("map_Kd ")) {
                        continue;
                    }
                    String mapRef = line.substring(7).trim();
                    String resolved = resolveTextureReference(engine, dir, mapRef);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            } catch (RuntimeException ignored) {
 // Skips rozbitý MTL soubor a pokračuju dál.
            }
        }

 // jako náhradní větev projdu OBJ složku a zkusím v ní najít běžné názvy obrázků.
        String[] preferred = {"albedo", "diffuse", "basecolor", "color", "skin", "tex"};
        File[] files = dir.toFile().listFiles();
        if (files == null) {
            return null;
        }
        String firstImage = null;
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName().toLowerCase();
            if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".bmp") || name.endsWith(".tga"))) {
                continue;
            }
            if (firstImage == null) {
                firstImage = file.getAbsolutePath();
            }
            for (String key : preferred) {
                if (name.contains(key)) {
                    return file.getAbsolutePath();
                }
            }
        }
        return firstImage;
    }

    static String resolveTextureReference(Engine engine, Path baseDir, String rawRef) {
        if (rawRef == null || rawRef.isBlank()) {
            return null;
        }

        String candidateText = rawRef.trim();
        Path direct = baseDir.resolve(candidateText).normalize();
        if (Files.exists(direct)) {
            return direct.toString();
        }

        String[] tokens = candidateText.split("\\s+");
        for (int i = tokens.length - 1; i >= 0; i--) {
            String token = tokens[i].trim();
            if (token.isEmpty() || token.startsWith("-")) {
                continue;
            }
            Path p = baseDir.resolve(token).normalize();
            if (Files.exists(p)) {
                return p.toString();
            }
        }
        return null;
    }
}