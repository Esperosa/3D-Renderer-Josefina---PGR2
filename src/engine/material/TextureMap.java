package engine.material;

import engine.render.Texture;

/**
 * Represents texturový slot s výběrem UV sady a transformačními parametry ve stylu glTF.
 */
public final class TextureMap {

    private Texture texture;
    private boolean linear;
    private int texCoord;
    private double offsetU;
    private double offsetV;
    private double scaleU;
    private double scaleV;
    private double rotation;
    private boolean flipV;

    public TextureMap() {
        this.texture = null;
        this.linear = true;
        this.texCoord = 0;
        this.offsetU = 0.0;
        this.offsetV = 0.0;
        this.scaleU = 1.0;
        this.scaleV = 1.0;
        this.rotation = 0.0;
        this.flipV = true;
    }

    public Texture getTexture() {
        return texture;
    }

    public void setTexture(Texture texture) {
        this.texture = texture;
    }

    public boolean isLinear() {
        return linear;
    }

    public void setLinear(boolean linear) {
        this.linear = linear;
    }

    public int getTexCoord() {
        return texCoord;
    }

    public void setTexCoord(int texCoord) {
        this.texCoord = Math.max(0, texCoord);
    }

    public double getOffsetU() {
        return offsetU;
    }

    public void setOffsetU(double offsetU) {
        this.offsetU = offsetU;
    }

    public double getOffsetV() {
        return offsetV;
    }

    public void setOffsetV(double offsetV) {
        this.offsetV = offsetV;
    }

    public double getScaleU() {
        return scaleU;
    }

    public void setScaleU(double scaleU) {
        this.scaleU = scaleU;
    }

    public double getScaleV() {
        return scaleV;
    }

    public void setScaleV(double scaleV) {
        this.scaleV = scaleV;
    }

    public double getRotation() {
        return rotation;
    }

    public void setRotation(double rotation) {
        this.rotation = rotation;
    }

    public boolean isFlipV() {
        return flipV;
    }

    public void setFlipV(boolean flipV) {
        this.flipV = flipV;
    }

    public boolean hasTexture() {
        return texture != null;
    }

    public TextureMap copy() {
        TextureMap out = new TextureMap();
        out.copyFrom(this);
        return out;
    }

    public void copyFrom(TextureMap source) {
        if (source == null) {
            texture = null;
            linear = true;
            texCoord = 0;
            offsetU = 0.0;
            offsetV = 0.0;
            scaleU = 1.0;
            scaleV = 1.0;
            rotation = 0.0;
            flipV = true;
            return;
        }
        texture = source.texture;
        linear = source.linear;
        texCoord = source.texCoord;
        offsetU = source.offsetU;
        offsetV = source.offsetV;
        scaleU = source.scaleU;
        scaleV = source.scaleV;
        rotation = source.rotation;
        flipV = source.flipV;
    }
}
