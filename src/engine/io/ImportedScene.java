package engine.io;

import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Quaternion;
import engine.math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tady držím výsledný kontejner pro import modelu nebo scény.
 * Každou položkou v něm reprezentuju jednu mesh instanci s transformací.
 */
public final class ImportedScene {

    public static final class Entry {
        private final String name;
        private final Mesh mesh;
        private final Vec3 position;
        private final Quaternion rotation;
        private final Vec3 scale;
        private final PhongMaterial material;

        public Entry(String name, Mesh mesh, Vec3 position, Quaternion rotation, Vec3 scale) {
            this(name, mesh, position, rotation, scale, null);
        }

        public Entry(String name, Mesh mesh, Vec3 position, Quaternion rotation, Vec3 scale, PhongMaterial material) {
            this.name = (name == null || name.isBlank()) ? "ImportedMesh" : name;
            this.mesh = mesh;
            this.position = position == null ? Vec3.ZERO : position;
            this.rotation = rotation == null ? new Quaternion() : rotation.normalize();
            this.scale = scale == null ? Vec3.ONE : scale;
            this.material = material;
        }

        public String getName() {
            return name;
        }

        public Mesh getMesh() {
            return mesh;
        }

        public Vec3 getPosition() {
            return position;
        }

        public Quaternion getRotation() {
            return rotation;
        }

        public Vec3 getScale() {
            return scale;
        }

        public PhongMaterial getMaterial() {
            return material;
        }
    }

    private final List<Entry> entries;
    private String sourcePath;

    public ImportedScene() {
        this.entries = new ArrayList<>();
        this.sourcePath = null;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void addEntry(Entry entry) {
        if (entry == null || entry.getMesh() == null) {
            return;
        }
        entries.add(entry);
    }

    public void addEntry(String name, Mesh mesh) {
        addEntry(new Entry(name, mesh, Vec3.ZERO, new Quaternion(), Vec3.ONE, null));
    }

    public void addEntry(String name, Mesh mesh, PhongMaterial material) {
        addEntry(new Entry(name, mesh, Vec3.ZERO, new Quaternion(), Vec3.ONE, material));
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
