package engine.core;

import engine.camera.OrthographicCamera;
import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;
import engine.io.FileUtil;
import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.material.PhongMaterial;
import engine.math.AABB;
import engine.math.Vec3;
import engine.physics.AABBCollider;
import engine.physics.RigidBody;
import engine.physics.SphereCollider;
import engine.render.Texture;
import engine.scene.Entity;
import engine.sim.water.WaterEmitterEntity;
import engine.ui.UiStrings;

import java.awt.FileDialog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class EngineSceneActions {
    private static final String[] BASIC_PRIMITIVES = {
            "cube", "sphere", "plane", "cylinder", "cone", "capsule", "torus", "pyramid", "prism"
    };
    private static final String[] FEATURED_PRIMITIVES = {
            "crystal", "torus-knot"
    };

    private EngineSceneActions() {
    }

    static final class SceneAddAction {
        private final String label;
        private final Consumer<Engine> command;

        private SceneAddAction(String label, Consumer<Engine> command) {
            this.label = label;
            this.command = command;
        }

        String label() {
            return label;
        }

        void invoke(Engine engine) {
            command.accept(engine);
        }
    }

    static final class SceneAddGroup {
        private final String title;
        private final boolean buttonGrid;
        private final List<SceneAddAction> actions;

        private SceneAddGroup(String title, boolean buttonGrid, List<SceneAddAction> actions) {
            this.title = title;
            this.buttonGrid = buttonGrid;
            this.actions = List.copyOf(actions);
        }

        String title() {
            return title;
        }

        boolean buttonGrid() {
            return buttonGrid;
        }

        List<SceneAddAction> actions() {
            return actions;
        }
    }

    static String primitiveLabel(String type) {
        if (type == null) {
            return "Primitivum";
        }
        return switch (type) {
            case "cube" -> "Krychle";
            case "sphere" -> "Koule";
            case "plane" -> "Rovina";
            case "cylinder" -> "Válec";
            case "cone" -> "Kužel";
            case "capsule" -> "Kapsle";
            case "torus" -> "Torus";
            case "pyramid" -> "Pyramida";
            case "prism" -> "Hranol";
            case "crystal" -> "Krystal";
            case "torus-knot" -> "Torus Knot";
            default -> type;
        };
    }

    static List<SceneAddGroup> sceneAddGroups() {
        List<SceneAddGroup> groups = new ArrayList<>();
        groups.add(primitiveGroup(UiStrings.Scene.BASIC_OBJECTS, BASIC_PRIMITIVES));
        groups.add(primitiveGroup(UiStrings.Scene.FEATURED_OBJECTS, FEATURED_PRIMITIVES));
        groups.add(linearGroup(UiStrings.Scene.IMPORT,
                new SceneAddAction(UiStrings.ContextMenu.IMPORT_MODEL_SCENE, Engine::importModelOrSceneFromDialog)));
        groups.add(linearGroup(UiStrings.Scene.LIGHTS,
                new SceneAddAction(UiStrings.ContextMenu.ADD_POINT_LIGHT, Engine::addPointLight),
                new SceneAddAction(UiStrings.ContextMenu.ADD_AREA_LIGHT, Engine::addAreaLight),
                new SceneAddAction(UiStrings.ContextMenu.ADD_CONE_LIGHT, Engine::addConeLight)));
        groups.add(linearGroup(UiStrings.Scene.FORCE_FIELDS,
                new SceneAddAction(UiStrings.ContextMenu.ADD_VECTOR_FORCE, Engine::addVectorForceField),
                new SceneAddAction(UiStrings.ContextMenu.ADD_POINT_ATTRACTOR, engine -> engine.addPointForceField(true)),
                new SceneAddAction(UiStrings.ContextMenu.ADD_POINT_REPULSOR, engine -> engine.addPointForceField(false)),
                new SceneAddAction(UiStrings.ContextMenu.ADD_TURBULENCE, Engine::addTurbulenceForceField)));
        groups.add(linearGroup(UiStrings.Scene.SIMULATION,
                new SceneAddAction(UiStrings.ContextMenu.ADD_WATER_EMITTER, Engine::addWaterEmitter)));
        return List.copyOf(groups);
    }

    private static SceneAddGroup primitiveGroup(String title, String[] primitiveTypes) {
        List<SceneAddAction> actions = new ArrayList<>(primitiveTypes.length);
        for (String type : primitiveTypes) {
            String primitiveType = type;
            actions.add(new SceneAddAction(
                    UiStrings.ContextMenu.ADD_PREFIX + primitiveLabel(primitiveType),
                    engine -> engine.addPrimitive(primitiveType)));
        }
        return new SceneAddGroup(title, true, actions);
    }

    private static SceneAddGroup linearGroup(String title, SceneAddAction... actions) {
        return new SceneAddGroup(title, false, List.of(actions));
    }

    static void addPrimitive(Engine engine, String type) {
        Mesh mesh;
        switch (type) {
            case "cylinder":
                mesh = MeshGenerator.cylinder(0.55, 1.35, 24, 1);
                break;
            case "cone":
                mesh = MeshGenerator.cone(0.7, 1.35, 24);
                break;
            case "capsule":
                mesh = MeshGenerator.capsule(0.45, 1.25, 10, 24);
                break;
            case "torus":
                mesh = MeshGenerator.torus(0.72, 0.22, 36, 18);
                break;
            case "pyramid":
                mesh = MeshGenerator.pyramid(1.25, 1.25, 1.35);
                break;
            case "prism":
                mesh = MeshGenerator.prism(0.7, 1.3, 6);
                break;
            case "crystal":
                mesh = MeshGenerator.crystal(0.75, 1.6, 6);
                break;
            case "torus-knot":
                mesh = MeshGenerator.torusKnot(0.62, 0.22, 0.14, 180, 18, 2, 3);
                break;
            case "sphere":
                mesh = MeshGenerator.sphere(0.7, 16, 24);
                break;
            case "plane":
                mesh = MeshGenerator.plane(2.0, 2.0, 1, 1);
                break;
            case "cube":
            default:
                mesh = MeshGenerator.cube(1.2);
                break;
        }

        Vec3 color = new Vec3(
                0.35 + engine.random.nextDouble() * 0.6,
                0.35 + engine.random.nextDouble() * 0.6,
                0.35 + engine.random.nextDouble() * 0.6
        );
        PhongMaterial material = new PhongMaterial(color, 26.0);
        material.setSpecularColor(new Vec3(0.8, 0.8, 0.8));
        if ("crystal".equals(type)) {
            material.setTransmission(0.28);
            material.setRefractiveIndex(1.32);
            material.setRoughness(0.08);
            material.setReflectivity(0.24);
            material.setSpecularColor(new Vec3(0.95, 0.98, 1.0));
        } else if ("torus-knot".equals(type)) {
            material.setMetallic(0.78);
            material.setRoughness(0.22);
            material.setReflectivity(0.42);
        }

        String name = type + "-" + (++engine.spawnCounter);
        Entity entity = new Entity(name, mesh, material);
        Vec3 spawnPos = engine.spawnPositionFromPointer(3.0);
        entity.getTransform().setPosition(spawnPos);
        engine.scene.addEntity(entity);
        engine.stateFor(entity);

        if ("plane".equals(type)) {
            entity.setStatic(true);
            RigidBody body = new RigidBody(entity, RigidBody.BodyType.STATIC, 1.0);
            body.setCollider(new AABBCollider(new Vec3(1.0, 0.05, 1.0)));
            engine.physicsWorld.addBody(body);
        } else if (engine.physicsEnabled) {
            RigidBody body = new RigidBody(entity, RigidBody.BodyType.DYNAMIC, 1.0);
            double radius = mesh.getBounds() != null ? mesh.getBounds().getRadius() : 0.7;
            body.setCollider(new SphereCollider(Math.max(0.2, radius)));
            engine.physicsWorld.addBody(body);
        }

        engine.setCurrentEntitySelection(entity);
        engine.cameraController.frameTarget(entity.getTransform().getPosition());
        System.out.println("Added: " + name);
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }

    static void addWaterEmitter(Engine engine) {
        if (engine == null || engine.scene == null) {
            return;
        }
        String name = "spray-emitter-" + (++engine.spawnCounter);
        Vec3 spawnPos = engine.spawnPositionFromPointer(2.8).add(new Vec3(0.0, 0.6, 0.0));
        WaterEmitterEntity entity = WaterEmitterEntity.createDefault(name, spawnPos);
        engine.scene.addEntity(entity);
        engine.stateFor(entity);
        engine.waterSimulation.addEmitter(entity);
        engine.setCurrentEntitySelection(entity);
        engine.cameraController.frameTarget(entity.getTransform().getPosition());
        System.out.println("Přidán částicový emitor: " + name);
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }

    static void importModelOrSceneFromDialog(Engine engine) {
        if (engine == null || engine.window == null || engine.window.getFrame() == null) {
            return;
        }
        FileDialog dialog = new FileDialog(engine.window.getFrame(), "Import Model/Scene", FileDialog.LOAD);
        dialog.setFilenameFilter((dir, name) -> {
            String lower = name == null ? "" : name.toLowerCase();
            return lower.endsWith(".obj")
                    || lower.endsWith(".stl")
                    || lower.endsWith(".gltf")
                    || lower.endsWith(".glb")
                    || lower.endsWith(".fbx");
        });
        dialog.setVisible(true);
        String file = dialog.getFile();
        if (file == null || file.isBlank()) {
            return;
        }
        String directory = dialog.getDirectory();
        Path fullPath = (directory == null || directory.isBlank())
                ? Path.of(file)
                : Path.of(directory, file);
        engine.importModelOrSceneAsync(fullPath.toAbsolutePath().normalize().toString());
    }

    static int importModelOrScene(Engine engine, String filePath) {
        if (engine == null || filePath == null || filePath.isBlank()) {
            return 0;
        }
        if (!FileUtil.exists(filePath)) {
            throw new RuntimeException("Import file not found: " + filePath);
        }

        ImportedScene imported = new ModelImporter().importScene(filePath);
        String ext = FileUtil.getExtension(filePath);
        Texture diffuseTexture = tryLoadObjDiffuseTexture(engine, ext, filePath);

        int added = 0;
        int totalTriangles = 0;
        Entity firstAdded = null;
        List<Entity> addedEntities = new ArrayList<>();
        AABB importedBounds = null;
        int idx = 0;
        for (ImportedScene.Entry entry : imported.getEntries()) {
            if (entry == null || entry.getMesh() == null) {
                idx++;
                continue;
            }
            Mesh mesh = entry.getMesh();
            totalTriangles += mesh.getTriangleCount();

            PhongMaterial material = entry.getMaterial() != null
                    ? cloneMaterial(entry.getMaterial())
                    : createImportMaterial(entry.getName(), idx);
            if (diffuseTexture != null && material.getDiffuseTexture() == null) {
                material.setDiffuseTexture(diffuseTexture);
                material.setTextureFilteringLinear(true);
            }

            String uniqueName = uniqueEntityName(engine, sanitizeName(entry.getName()));
            Entity entity = new Entity(uniqueName, mesh, material);
            entity.getTransform().setPosition(entry.getPosition());
            entity.getTransform().setRotation(entry.getRotation());
            entity.getTransform().setScale(entry.getScale());

            engine.scene.addEntity(entity);
            engine.stateFor(entity);
            entity.computeWorldBounds();
            added++;
            if (firstAdded == null) {
                firstAdded = entity;
            }
            addedEntities.add(entity);
            importedBounds = mergeBounds(importedBounds, entity.getWorldBounds());
            idx++;
        }

        if (added == 0) {
            throw new RuntimeException("Import produced no valid mesh entities: " + filePath);
        }

        engine.loadedModelPath = filePath;
        engine.loadedDiffuseTexturePath = null;
        if ("obj".equals(ext)) {
            String diffuse = EngineSceneBootstrap.findDiffuseTextureForObj(engine, filePath);
            engine.loadedDiffuseTexturePath = diffuse;
        }

        if (firstAdded != null) {
            engine.setCurrentEntitySelection(firstAdded);
            focusCameraOnImportedBounds(engine, importedBounds, firstAdded, addedEntities);
        }
        engine.refreshObjectInspectorValues();
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
        System.out.println("Imported " + added + " object(s), " + totalTriangles + " triangles from: " + filePath);
        return added;
    }

    static Texture tryLoadObjDiffuseTexture(Engine engine, String ext, String filePath) {
        if (!"obj".equals(ext)) {
            return null;
        }
        String diffusePath = EngineSceneBootstrap.findDiffuseTextureForObj(engine, filePath);
        if (diffusePath == null || !FileUtil.exists(diffusePath)) {
            return null;
        }
        try {
            return Texture.load(diffusePath);
        } catch (RuntimeException ex) {
            System.out.println("OBJ diffuse texture load failed: " + ex.getMessage());
            return null;
        }
    }

    static PhongMaterial createImportMaterial(String baseName, int index) {
        int h = (baseName == null ? 0 : baseName.hashCode()) ^ (index * 0x9E3779B9);
        double r = 0.35 + (((h >>> 16) & 0xFF) / 255.0) * 0.45;
        double g = 0.35 + (((h >>> 8) & 0xFF) / 255.0) * 0.45;
        double b = 0.35 + ((h & 0xFF) / 255.0) * 0.45;
        PhongMaterial material = new PhongMaterial(new Vec3(r, g, b), 24.0);
        material.setSpecularColor(new Vec3(0.85, 0.85, 0.85));
        return material;
    }

    static PhongMaterial cloneMaterial(PhongMaterial source) {
        if (source == null) {
            return createImportMaterial("imported", 0);
        }
        return source.copy();
    }

    static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "imported";
        }
        String clean = raw.trim().replace('\\', '_').replace('/', '_').replace(':', '_');
        return clean.isBlank() ? "imported" : clean;
    }

    static String uniqueEntityName(Engine engine, String base) {
        String candidate = base;
        int suffix = 1;
        while (engine.scene.findByName(candidate) != null) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    static void focusCameraOnImportedBounds(Engine engine,
                                            AABB bounds,
                                            Entity fallback,
                                            List<Entity> importedEntities) {
        if (engine == null || engine.camera == null || engine.cameraController == null) {
            return;
        }
        AABB resolvedBounds = bounds;
        if (resolvedBounds == null && importedEntities != null) {
            for (Entity entity : importedEntities) {
                if (entity == null) {
                    continue;
                }
                entity.computeWorldBounds();
                resolvedBounds = mergeBounds(resolvedBounds, entity.getWorldBounds());
            }
        }
        if (resolvedBounds == null) {
            if (fallback != null) {
                engine.cameraController.frameTarget(fallback.getTransform().getPosition());
            }
            return;
        }

        Vec3 center = resolvedBounds.center();
        double radius = Math.max(0.6, resolvedBounds.getMax().sub(center).length());
        Vec3 forward = safeDirection(engine.camera.getForward());
        double distance = defaultFocusDistance(radius);
        if (engine.camera instanceof PerspectiveCamera) {
            PerspectiveCamera perspective = (PerspectiveCamera) engine.camera;
            double fovY = Math.max(Math.toRadians(18.0), perspective.getFovY());
            double aspect = Math.max(0.15, perspective.getAspectRatio());
            double fovX = 2.0 * Math.atan(Math.tan(fovY * 0.5) * aspect);
            double fitFov = Math.max(Math.toRadians(12.0), Math.min(fovY, fovX));
            distance = Math.max(distance, radius / Math.tan(fitFov * 0.5) + radius * 0.35);
        }
        if (engine.camera instanceof OrthographicCamera) {
            double aspect = engine.perspectiveCamera != null
                    ? Math.max(0.15, engine.perspectiveCamera.getAspectRatio())
                    : 1.0;
            double halfHeight = Math.max(1.0, radius * 1.20);
            double halfWidth = halfHeight * aspect;
            ((OrthographicCamera) engine.camera).setExtents(-halfWidth, halfWidth, -halfHeight, halfHeight);
        }

        Vec3 position = center.sub(forward.mul(distance));
        engine.cameraController.setOrbitTarget(center);
        EngineCameraRuntime.applyCameraPose(engine, position, center.sub(position));
        engine.cameraController.setOrbitTarget(center);
    }

    static AABB mergeBounds(AABB current, AABB next) {
        if (current == null) {
            return next;
        }
        if (next == null) {
            return current;
        }
        return AABB.merge(current, next);
    }

    private static Vec3 safeDirection(Vec3 direction) {
        Vec3 normalized = direction == null ? Vec3.ZERO : direction.normalize();
        if (normalized.lengthSquared() < 1e-10) {
            return new Vec3(0.0, 0.0, -1.0);
        }
        return normalized;
    }

    private static double defaultFocusDistance(double radius) {
        return Math.max(3.0, radius * 2.8 + 0.8);
    }

    static void printHelp() {
        System.out.println("=== Ovládání editoru ===");
        System.out.println("Zobrazení a navigace:");
        System.out.println("  Aplikace startuje ve FPS režimu se zachyceným kurzorem.");
        System.out.println("  Q = FPS navigace, E = Blender navigace.");
        System.out.println("  Blender režim: MMB orbit, Shift+MMB posun, kolečko zoom.");
        System.out.println("  Levé tlačítko na objekt/ikonu = výběr.");
        System.out.println("  Levé tlačítko mimo objekt = zachytit kurzor pro FPS pohled.");
        System.out.println("  Myš = rozhlížení ve FPS, kolečko = zoom v orbit režimu.");
        System.out.println("  Pravé tlačítko = přidávací menu objektů, světel, sil a částicových emitorů.");
        System.out.println("  I/J/K/L = rozhlížení z klávesnice.");
        System.out.println("  WASD nebo šipky = pohyb, Space/Ctrl = nahoru/dolů.");
        System.out.println("  Esc = uvolnit kurzor nebo fokus, dalším Esc zavřít aplikaci.");
        System.out.println("  Tab = přepnutí režimu kamery.");
        System.out.println("  F4 nebo O = perspektiva / ortho.");
        System.out.println("  Ctrl+Numpad 1/3/7 = přední / pravý / horní pohled, F = zaměřit výběr.");
        System.out.println("Časová osa:");
        System.out.println("  Spodní dock střídá Časovou osu a Materiál.");
        System.out.println("  Mezerník v Blender režimu = přehrát / pauza animace.");
        System.out.println("  Vlevo/Vpravo = předchozí / další snímek.");
        System.out.println("  Insert = vložit klíč, Shift+Insert = smazat klíč.");
        System.out.println("  Ctrl+Insert = vložit klíč pro všechny animovatelné cíle.");
        System.out.println("  K = vložit klíč pro aktuální výběr, Shift+K = release klíč pro fyziku.");
        System.out.println("Render:");
        System.out.println("  G = Model, 1..9 + numpad = rychlé režimy renderu.");
        System.out.println("  Model / Basic / Phong / Wireframe / Dither / ASCII / Temporal / Ray / Path / Hex.");
        System.out.println("  F1 nebo ` = přepnout styl ditheru.");
        System.out.println("  V v Temporal režimu = přepnout variantu noise.");
        System.out.println("  U v Hex režimu = přepnout wow styl, Y = debug buněk.");
        System.out.println("  Alt+, / Alt+. = temporal separation - / +.");
        System.out.println("  Z = cyklus render režimů.");
        System.out.println("Scéna:");
        System.out.println("  Shift+A menu: C krychle, S koule, P rovina, Y válec, N kužel, T torus, H kapsle, R pyramida, D krystal, K torus knot.");
        System.out.println("  " + UiStrings.ContextMenu.ADD_WATER_EMITTER + " = " + FeatureMaturityNotes.SPRAY_PARTICLE_SYSTEM);
        System.out.println("  Import modelu / scény: OBJ, STL, GLTF, GLB, FBX.");
        System.out.println("  [ a ] = vybrat předchozí / další objekt.");
        System.out.println("  Delete = odstranit vybraný objekt, světlo nebo sílu.");
        System.out.println("Transformace:");
        System.out.println("  Alt+G / Alt+R / Alt+S = posun / rotace / měřítko.");
        System.out.println("  S v Blender režimu = uniformní scale.");
        System.out.println("  X/Y/Z = osa, Enter = potvrdit, Esc = zrušit.");
        System.out.println("Systém:");
        System.out.println("  F5 = frustum culling, F6 = backface culling.");
        System.out.println("  F7 = fyzika, F8 = demo auto-rotace, H = nápověda.");
        System.out.println("  F2 = filtr upscale, F3 = post AA.");
        System.out.println("  F9 = paralelní raster, F10 = render scale.");
        System.out.println("  B = debug HUD, N = editor overlay.");
        System.out.println("  F11/F12 = počet workerů -, +.");
        System.out.println("  PgDown/PgUp = samples per frame -, +.");
        System.out.println("  -/+ = rychlost pohybu, ,/. = citlivost rozhlížení.");
        System.out.println("  " + EditorKeymap.shortcutLabel(EditorActionId.UNDO) + " = zpět, "
                + EditorKeymap.shortcutLabel(EditorActionId.REDO) + " = znovu.");
        System.out.println("  " + EditorKeymap.shortcutLabel(EditorActionId.CANCEL)
                + " = zrušit dočasnou operaci, "
                + EditorKeymap.shortcutLabel(EditorActionId.FRAME_ALL)
                + " = materiálový graf nebo časová osa podle kontextu.");
        System.out.println("UI:");
        System.out.println("  Horní toolbar = rychlé přepínače navigace, renderu a runtime.");
        System.out.println("  Pravý panel = Scene Browser + vlastnosti: Položka, Prostředí, Zobrazení, Render, Výstup.");
        System.out.println("  Výstup = session-based render workflow pro snímek, sekvenci, GIF a AVI.");
    }
}
