package engine.material;

import engine.math.Vec3;
import engine.render.Texture;

/**
 * Tady držím materiál s parametry pro Phong/Blinn-Phong stínování.
 * Používám ho v rasterizéru i ray traceru pro realističtější nasvícení.
 */
public class PhongMaterial extends Material {

    public enum AlphaMode {
        OPAQUE,
        MASK,
        BLEND
    }

    private Vec3 ambientColor;
    private Vec3 diffuseColor;
    private Vec3 specularColor;
    private double shininess;
    private double reflectivity;
    private double refractiveIndex;
    private final TextureMap diffuseMap;
    private final TextureMap normalMap;
    private final TextureMap metallicRoughnessMap;
    private final TextureMap emissiveMap;
    private double normalScale;
    private double clearcoatFactor;
    private double clearcoatRoughness;
    private double specularFactor;
    private Vec3 specularColorFactor;
    private Vec3 sheenColor;
    private double sheenRoughness;
    private AlphaMode alphaMode;
    private double alphaCutoff;
    private MaterialNodeGraph nodeGraph;

    public PhongMaterial() {
        this(new Vec3(0.7, 0.7, 0.7), 32.0);
    }

    public PhongMaterial(Vec3 diffuse, double shininess) {
        super(diffuse);
        this.diffuseColor = diffuse;
        this.ambientColor = diffuse.mul(0.1);
        this.specularColor = Vec3.ONE;
        this.shininess = shininess;
        this.reflectivity = 0.0;
        this.refractiveIndex = 1.0;
        this.diffuseMap = new TextureMap();
        this.normalMap = new TextureMap();
        this.metallicRoughnessMap = new TextureMap();
        this.emissiveMap = new TextureMap();
        this.normalScale = 1.0;
        this.clearcoatFactor = 0.0;
        this.clearcoatRoughness = 0.0;
        this.specularFactor = 1.0;
        this.specularColorFactor = Vec3.ONE;
        this.sheenColor = Vec3.ZERO;
        this.sheenRoughness = 0.0;
        this.alphaMode = AlphaMode.OPAQUE;
        this.alphaCutoff = 0.5;
        this.nodeGraph = null;
        setShadingModel(Material.ShadingModel.PHONG);
        setRoughness(roughnessFromShininess(shininess));
    }

    // Tady držím přístupové metody.
    public Vec3 getAmbientColor() {
        return ambientColor;
    }

    public void setAmbientColor(Vec3 c) {
        this.ambientColor = c;
    }

    public Vec3 getDiffuseColor() {
        return diffuseColor;
    }

    public void setDiffuseColor(Vec3 c) {
        this.diffuseColor = c;
        setBaseColor(c);
    }

    public Vec3 getSpecularColor() {
        return specularColor;
    }

    public void setSpecularColor(Vec3 c) {
        this.specularColor = c;
    }

    public double getShininess() {
        return shininess;
    }

    public void setShininess(double s) {
        this.shininess = Math.max(1.0, s);
        setRoughness(roughnessFromShininess(this.shininess));
    }

    public double getReflectivity() {
        return reflectivity;
    }

    public void setReflectivity(double r) {
        this.reflectivity = Math.max(0.0, Math.min(1.0, r));
    }

    public double getRefractiveIndex() {
        return refractiveIndex;
    }

    public void setRefractiveIndex(double n) {
        this.refractiveIndex = Math.max(1.0, n);
    }

    public Texture getDiffuseTexture() {
        return diffuseMap.getTexture();
    }

    public void setDiffuseTexture(Texture diffuseTexture) {
        this.diffuseMap.setTexture(diffuseTexture);
    }

    public boolean hasDiffuseTexture() {
        return diffuseMap.hasTexture();
    }

    public boolean isTextureFilteringLinear() {
        return diffuseMap.isLinear();
    }

    public void setTextureFilteringLinear(boolean textureFilteringLinear) {
        this.diffuseMap.setLinear(textureFilteringLinear);
    }

    public TextureMap getDiffuseMap() {
        return diffuseMap;
    }

    public TextureMap getNormalMap() {
        return normalMap;
    }

    public TextureMap getMetallicRoughnessMap() {
        return metallicRoughnessMap;
    }

    public TextureMap getEmissiveMap() {
        return emissiveMap;
    }

    public Texture getNormalTexture() {
        return normalMap.getTexture();
    }

    public void setNormalTexture(Texture normalTexture) {
        normalMap.setTexture(normalTexture);
    }

    public boolean hasNormalTexture() {
        return normalMap.hasTexture();
    }

    public Texture getMetallicRoughnessTexture() {
        return metallicRoughnessMap.getTexture();
    }

    public void setMetallicRoughnessTexture(Texture metallicRoughnessTexture) {
        metallicRoughnessMap.setTexture(metallicRoughnessTexture);
    }

    public boolean hasMetallicRoughnessTexture() {
        return metallicRoughnessMap.hasTexture();
    }

    public Texture getEmissiveTexture() {
        return emissiveMap.getTexture();
    }

    public void setEmissiveTexture(Texture emissiveTexture) {
        emissiveMap.setTexture(emissiveTexture);
    }

    public boolean hasEmissiveTexture() {
        return emissiveMap.hasTexture();
    }

    public double getNormalScale() {
        return normalScale;
    }

    public void setNormalScale(double normalScale) {
        this.normalScale = Math.max(0.0, normalScale);
    }

    public double getClearcoatFactor() {
        return clearcoatFactor;
    }

    public void setClearcoatFactor(double clearcoatFactor) {
        this.clearcoatFactor = Math.max(0.0, Math.min(1.0, clearcoatFactor));
    }

    public double getClearcoatRoughness() {
        return clearcoatRoughness;
    }

    public void setClearcoatRoughness(double clearcoatRoughness) {
        this.clearcoatRoughness = Math.max(0.0, Math.min(1.0, clearcoatRoughness));
    }

    public double getSpecularFactor() {
        return specularFactor;
    }

    public void setSpecularFactor(double specularFactor) {
        this.specularFactor = Math.max(0.0, Math.min(2.0, specularFactor));
    }

    public Vec3 getSpecularColorFactor() {
        return specularColorFactor;
    }

    public void setSpecularColorFactor(Vec3 specularColorFactor) {
        this.specularColorFactor = specularColorFactor == null ? Vec3.ONE : specularColorFactor;
    }

    public Vec3 getSheenColor() {
        return sheenColor;
    }

    public void setSheenColor(Vec3 sheenColor) {
        this.sheenColor = sheenColor == null ? Vec3.ZERO : sheenColor;
    }

    public double getSheenRoughness() {
        return sheenRoughness;
    }

    public void setSheenRoughness(double sheenRoughness) {
        this.sheenRoughness = Math.max(0.0, Math.min(1.0, sheenRoughness));
    }

    public AlphaMode getAlphaMode() {
        return alphaMode;
    }

    public void setAlphaMode(AlphaMode alphaMode) {
        this.alphaMode = alphaMode == null ? AlphaMode.OPAQUE : alphaMode;
    }

    public double getAlphaCutoff() {
        return alphaCutoff;
    }

    public void setAlphaCutoff(double alphaCutoff) {
        this.alphaCutoff = Math.max(0.0, Math.min(1.0, alphaCutoff));
    }

    public MaterialNodeGraph getNodeGraph() {
        return nodeGraph;
    }

    public boolean hasNodeGraph() {
        return nodeGraph != null;
    }

    public MaterialNodeGraph getOrCreateNodeGraph() {
        if (nodeGraph == null) {
            nodeGraph = MaterialNodeGraph.createDefault();
        }
        return nodeGraph;
    }

    public void setNodeGraph(MaterialNodeGraph nodeGraph) {
        this.nodeGraph = nodeGraph == null ? null : nodeGraph.copy();
    }

    public void clearNodeGraph() {
        this.nodeGraph = null;
    }

    @Override
    public void copyFrom(Material source) {
        super.copyFrom(source);
        if (source instanceof PhongMaterial phong) {
            copyFrom(phong);
        }
    }

    public void copyFrom(PhongMaterial source) {
        if (source == null) {
            return;
        }
        super.copyFrom(source);
        setAmbientColor(source.getAmbientColor());
        setDiffuseColor(source.getDiffuseColor());
        setSpecularColor(source.getSpecularColor());
        setShininess(source.getShininess());
        setReflectivity(source.getReflectivity());
        setRefractiveIndex(source.getRefractiveIndex());
        setDiffuseTexture(source.getDiffuseTexture());
        setTextureFilteringLinear(source.isTextureFilteringLinear());
        diffuseMap.copyFrom(source.getDiffuseMap());
        normalMap.copyFrom(source.getNormalMap());
        metallicRoughnessMap.copyFrom(source.getMetallicRoughnessMap());
        emissiveMap.copyFrom(source.getEmissiveMap());
        setNormalScale(source.getNormalScale());
        setClearcoatFactor(source.getClearcoatFactor());
        setClearcoatRoughness(source.getClearcoatRoughness());
        setSpecularFactor(source.getSpecularFactor());
        setSpecularColorFactor(source.getSpecularColorFactor());
        setSheenColor(source.getSheenColor());
        setSheenRoughness(source.getSheenRoughness());
        setAlphaMode(source.getAlphaMode());
        setAlphaCutoff(source.getAlphaCutoff());
        setNodeGraph(source.getNodeGraph());
    }

    public PhongMaterial copy() {
        PhongMaterial out = new PhongMaterial(getDiffuseColor(), getShininess());
        out.copyFrom(this);
        return out;
    }

    public static double roughnessFromShininess(double shininess) {
        double s = Math.max(1.0, shininess);
        return Math.max(0.0, Math.min(1.0, Math.sqrt(2.0 / (s + 2.0))));
    }

    public static double shininessFromRoughness(double roughness) {
        double r = Math.max(0.04, Math.min(1.0, roughness));
        return Math.max(1.0, Math.min(1024.0, 2.0 / (r * r) - 2.0));
    }
}
