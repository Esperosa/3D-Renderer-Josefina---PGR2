package engine.sim.galaxy;

import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;
import engine.material.MaterialPresets;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.scene.Entity;

/**
 * Tady držím experimentální kotevní entitu pro budoucí galaxy systém.
 * Dnes ji používám jen jako viditelný zástupný objekt pro práci se scénou a layoutem.
 */
public final class GalaxySystemEntity extends Entity {

    private static final Mesh ANCHOR_MESH = MeshGenerator.sphere(0.35, 10, 14);

    private int armCount;
    private int targetBodyCount;
    private double diskRadius;
    private double coreRadius;
    private double orbitalVelocityScale;
    private double softeningLength;

    public GalaxySystemEntity(String name) {
        super(name == null || name.isBlank() ? "experimental-galaxy-system" : name, ANCHOR_MESH, createAnchorMaterial());
        this.armCount = 4;
        this.targetBodyCount = 12000;
        this.diskRadius = 18.0;
        this.coreRadius = 2.6;
        this.orbitalVelocityScale = 1.0;
        this.softeningLength = 0.18;
        setStatic(true);
    }

    public int getArmCount() {
        return armCount;
    }

    public void setArmCount(int armCount) {
        this.armCount = Math.max(1, armCount);
    }

    public int getTargetBodyCount() {
        return targetBodyCount;
    }

    public void setTargetBodyCount(int targetBodyCount) {
        this.targetBodyCount = Math.max(256, targetBodyCount);
    }

    public double getDiskRadius() {
        return diskRadius;
    }

    public void setDiskRadius(double diskRadius) {
        this.diskRadius = Math.max(0.1, diskRadius);
    }

    public double getCoreRadius() {
        return coreRadius;
    }

    public void setCoreRadius(double coreRadius) {
        this.coreRadius = Math.max(0.05, coreRadius);
    }

    public double getOrbitalVelocityScale() {
        return orbitalVelocityScale;
    }

    public void setOrbitalVelocityScale(double orbitalVelocityScale) {
        this.orbitalVelocityScale = Math.max(0.0, orbitalVelocityScale);
    }

    public double getSofteningLength() {
        return softeningLength;
    }

    public void setSofteningLength(double softeningLength) {
        this.softeningLength = Math.max(1e-4, softeningLength);
    }

    private static PhongMaterial createAnchorMaterial() {
        PhongMaterial material = new PhongMaterial(new Vec3(1.0, 0.82, 0.45), 64.0);
        MaterialPresets.apply(MaterialPresets.Preset.EMISSIVE, material);
        material.setEmissionColor(new Vec3(1.0, 0.78, 0.42));
        material.setEmissionStrength(1.8);
        material.setName("experimental-galaxy-anchor");
        return material;
    }
}
