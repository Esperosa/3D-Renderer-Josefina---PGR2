package engine.io;

import engine.geometry.Mesh;

import java.nio.file.Path;

/**
 * Represents hlavní dispatcher pro import modelů podle přípony souboru.
 */
public class ModelImporter {

    public ImportedScene importScene(String filePath) {
        String ext = FileUtil.getExtension(filePath);
        ImportedScene scene;
        switch (ext) {
            case "obj":
                scene = importObj(filePath);
                break;
            case "stl":
                scene = importStl(filePath);
                break;
            case "gltf":
            case "glb":
                scene = new GltfLoader().load(filePath);
                break;
            case "fbx":
                throw new RuntimeException("FBX is not supported in pure-Java importer yet. Use GLB/GLTF/OBJ/STL.");
            default:
                throw new RuntimeException("Unsupported import format: ." + ext);
        }
        if (scene == null || scene.isEmpty()) {
            throw new RuntimeException("Import produced empty scene: " + filePath);
        }
        scene.setSourcePath(filePath);
        return scene;
    }

    private ImportedScene importObj(String filePath) {
        return new ObjLoader().loadScene(filePath);
    }

    private ImportedScene importStl(String filePath) {
        Mesh mesh = new StlLoader().load(filePath);
        ImportedScene out = new ImportedScene();
        out.addEntry(baseName(filePath), mesh);
        return out;
    }

    private static String baseName(String filePath) {
        String name = Path.of(filePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
