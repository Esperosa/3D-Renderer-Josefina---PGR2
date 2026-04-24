package engine.sim.water;

import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;
import engine.material.MaterialPresets;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.scene.Entity;

/**
 * Represents scénovou entitu, která zároveň slouží jako viditelný značkovač spray emitoru.
 * Lokální záporná osa Y mi určuje výchozí směr spray proudu.
 */
public final class WaterEmitterEntity extends Entity {

    private static final Mesh DEFAULT_MESH = MeshGenerator.cube(1.0);

    private final WaterEmitter emitter;

    public WaterEmitterEntity(String name) {
        super(name == null || name.isBlank() ? "spray-emitter" : name, DEFAULT_MESH, createDefaultMaterial());
        this.emitter = new WaterEmitter(this);
        getTransform().setScale(new Vec3(0.22, 0.38, 0.22));
        setStatic(true);
    }

    public WaterEmitter getEmitter() {
        return emitter;
    }

    public static WaterEmitterEntity createDefault(String name, Vec3 position) {
        WaterEmitterEntity entity = new WaterEmitterEntity(name);
        if (position != null) {
            entity.getTransform().setPosition(position);
        }
        return entity;
    }

    private static PhongMaterial createDefaultMaterial() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.22, 0.26, 0.30), 96.0);
        material.setName("spray-emitter");
        MaterialPresets.apply(MaterialPresets.Preset.METALLIC, material);
        material.setDiffuseColor(new Vec3(0.24, 0.29, 0.34));
        material.setAmbientColor(new Vec3(0.03, 0.04, 0.05));
        material.setSpecularColor(new Vec3(0.85, 0.90, 0.94));
        material.setEmissionColor(new Vec3(0.10, 0.20, 0.26));
        material.setEmissionStrength(0.0);
        material.setName("spray-emitter");
        return material;
    }
}
