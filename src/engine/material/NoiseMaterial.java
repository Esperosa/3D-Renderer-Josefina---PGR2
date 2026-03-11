package engine.material;

/**
 * Tady držím parametry materiálu pro temporal-noise renderer.
 * Používám je jen jako datový kontejner pro kompatibilitu starších cest.
 */
public class NoiseMaterial extends Material {

    public enum FlowDirectionMode {
        CAMERA_PROJECTED,
        NORMAL_PROJECTED,
        HYBRID
    }

    private double phaseOffset;
    private double noiseScale;
    private double temporalSpeed;
    private double flowStrength;
    private double cameraInfluence;
    private double contrast;
    private int paletteSize;
    private FlowDirectionMode flowDirectionMode;

    public NoiseMaterial() {
        this.phaseOffset = 0.0;
        this.noiseScale = 1.0;
        this.temporalSpeed = 1.0;
        this.flowStrength = 1.0;
        this.cameraInfluence = 1.0;
        this.contrast = 1.0;
        this.paletteSize = 8;
        this.flowDirectionMode = FlowDirectionMode.HYBRID;
    }

    public double getPhaseOffset() {
        return phaseOffset;
    }

    public void setPhaseOffset(double phaseOffset) {
        this.phaseOffset = phaseOffset;
    }

    public double getTemporalPhase() {
        return phaseOffset;
    }

    public void setTemporalPhase(double temporalPhase) {
        this.phaseOffset = temporalPhase;
    }

    public double getNoiseScale() {
        return noiseScale;
    }

    public void setNoiseScale(double noiseScale) {
        this.noiseScale = Math.max(0.001, noiseScale);
    }

    public double getTemporalSpeed() {
        return temporalSpeed;
    }

    public void setTemporalSpeed(double temporalSpeed) {
        this.temporalSpeed = Math.max(0.01, temporalSpeed);
    }

    public double getFlowStrength() {
        return flowStrength;
    }

    public void setFlowStrength(double flowStrength) {
        this.flowStrength = Math.max(0.0, flowStrength);
    }

    public double getCameraInfluence() {
        return cameraInfluence;
    }

    public void setCameraInfluence(double cameraInfluence) {
        this.cameraInfluence = Math.max(0.0, cameraInfluence);
    }

    public double getContrast() {
        return contrast;
    }

    public void setContrast(double contrast) {
        this.contrast = Math.max(0.1, contrast);
    }

    public int getPaletteSize() {
        return paletteSize;
    }

    public void setPaletteSize(int paletteSize) {
        this.paletteSize = Math.max(2, paletteSize);
    }

    public FlowDirectionMode getFlowDirectionMode() {
        return flowDirectionMode;
    }

    public void setFlowDirectionMode(FlowDirectionMode flowDirectionMode) {
        if (flowDirectionMode == null) {
            return;
        }
        this.flowDirectionMode = flowDirectionMode;
    }
}
