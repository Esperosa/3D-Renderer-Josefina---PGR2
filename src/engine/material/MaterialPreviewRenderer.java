package engine.material;

import engine.math.MathUtil;
import engine.math.Vec3;
import engine.render.Texture;

import java.awt.image.BufferedImage;

public final class MaterialPreviewRenderer {

    public enum PreviewPrimitive {
        SPHERE("Koule"),
        ROUNDED_CUBE("Zaoblená kostka"),
        PLANE("Rovina");

        private final String label;

        PreviewPrimitive(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum LightingPreset {
        STUDIO_SOFT("Studio Soft"),
        HARD_RIM("Hard Rim"),
        WARM_SUNSET("Warm Sunset"),
        NEUTRAL_WHITE("Neutral White"),
        DARK_CONTRAST("Dark Contrast");

        private final String label;

        LightingPreset(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum BackgroundMode {
        DARK("Tmavé"),
        GRAY("Šedé"),
        CHECKER("Checker");

        private final String label;

        BackgroundMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum PreviewMode {
        FAST("Fast Preview (Raster)"),
        RAY("Ray Preview"),
        PATH("Path Preview");

        private final String label;

        PreviewMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final class Settings {
        public PreviewPrimitive primitive = PreviewPrimitive.SPHERE;
        public LightingPreset lightingPreset = LightingPreset.STUDIO_SOFT;
        public BackgroundMode backgroundMode = BackgroundMode.DARK;
        public PreviewMode previewMode = PreviewMode.FAST;

        public Settings copy() {
            Settings copy = new Settings();
            copy.primitive = primitive;
            copy.lightingPreset = lightingPreset;
            copy.backgroundMode = backgroundMode;
            copy.previewMode = previewMode;
            return copy;
        }
    }

    private static final class PreviewLight {
        final Vec3 direction;
        final Vec3 color;
        final double intensity;

        private PreviewLight(Vec3 direction, Vec3 color, double intensity) {
            this.direction = direction;
            this.color = color;
            this.intensity = intensity;
        }
    }

    private MaterialPreviewRenderer() {
    }

    public static long signature(PhongMaterial material, Settings settings) {
        long hash = 0xcbf29ce484222325L;
        if (material != null) {
            hash = mix(hash, material.getName() == null ? 0L : material.getName().hashCode());
            hash = mix(hash, Double.doubleToLongBits(material.getOpacity()));
            hash = mix(hash, Double.doubleToLongBits(material.getTransmission()));
            hash = mix(hash, Double.doubleToLongBits(material.getRoughness()));
            hash = mix(hash, Double.doubleToLongBits(material.getMetallic()));
            hash = mix(hash, Double.doubleToLongBits(material.getEmissionStrength()));
            hash = mix(hash, Double.doubleToLongBits(material.getNormalScale()));
            hash = mix(hash, material.hasNodeGraph() ? material.getNodeGraph().signature() : 0L);
            hash = mix(hash, textureSignature(material.getNormalMap()));
        }
        if (settings != null) {
            hash = mix(hash, settings.primitive.ordinal());
            hash = mix(hash, settings.lightingPreset.ordinal());
            hash = mix(hash, settings.backgroundMode.ordinal());
            hash = mix(hash, settings.previewMode.ordinal());
        }
        return hash;
    }

    public static BufferedImage render(PhongMaterial material, Settings settings, int width, int height) {
        int w = Math.max(32, width);
        int h = Math.max(32, height);
        Settings safe = settings == null ? new Settings() : settings;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        MaterialGraphEvaluator.Context context = new MaterialGraphEvaluator.Context();
        PreviewLight[] lights = buildLights(safe.lightingPreset, safe.previewMode);
        Vec3 lightAccum = new Vec3();
        Vec3 background = new Vec3();
        Vec3 transmissionColor = new Vec3();
        Vec3 shaded = new Vec3();
        Vec3 viewDir = new Vec3(0.0, 0.0, 1.0);
        Vec3 normal = new Vec3();
        Vec3 mappedNormal = new Vec3();
        Vec3 tangent = new Vec3(1.0, 0.0, 0.0);
        Vec3 bitangent = new Vec3(0.0, 1.0, 0.0);
        Vec3 halfVector = new Vec3();

        for (int py = 0; py < h; py++) {
            double sy = (py + 0.5) / h * 2.0 - 1.0;
            for (int px = 0; px < w; px++) {
                double sx = (px + 0.5) / w * 2.0 - 1.0;
                sampleBackground(safe.backgroundMode, sx, sy, background);
                Sample sample = samplePrimitive(safe.primitive, sx, sy, normal);
                if (sample == null) {
                    image.setRGB(px, py, toArgb(background, 1.0));
                    continue;
                }
                tangent.set(sample.tangent);
                bitangent.set(sample.bitangent);
                context.resetTriangle(
                        sample.worldPos.x,
                        sample.worldPos.y,
                        sample.worldPos.z,
                        true,
                        sample.uv.x,
                        sample.uv.y,
                        false,
                        0.0,
                        0.0
                );
                MaterialGraphEvaluator.Result result = MaterialGraphEvaluator.evaluateShared(material, context);
                mappedNormal.set(applyPreviewNormal(material, sample.uv.x, sample.uv.y, normal, tangent, bitangent)).normalizeInPlace();

                double ndotv = Math.max(0.0, mappedNormal.dot(viewDir));
                double roughness = MathUtil.clamp01(result.roughness);
                double metallic = MathUtil.clamp01(result.metallic);
                double specular = Math.max(0.0, result.specularFactor);
                double clearcoat = MathUtil.clamp01(result.clearcoatFactor);
                double opacity = MathUtil.clamp01(result.opacity);
                double transmission = MathUtil.clamp01(result.transmission);

                shaded.set(result.baseColor).mulInPlace(ambientFactor(safe.previewMode));
                transmissionColor.set(background);

                for (PreviewLight light : lights) {
                    lightAccum.set(light.direction).normalizeInPlace();
                    double ndotl = Math.max(0.0, mappedNormal.dot(lightAccum));
                    if (ndotl <= 0.0) {
                        continue;
                    }
                    double diffuseEnergy = light.intensity * ndotl * (1.0 - metallic * 0.25);
                    shaded.x += result.baseColor.x * light.color.x * diffuseEnergy;
                    shaded.y += result.baseColor.y * light.color.y * diffuseEnergy;
                    shaded.z += result.baseColor.z * light.color.z * diffuseEnergy;

                    halfVector.set(lightAccum).addInPlace(viewDir).normalizeInPlace();
                    double specPow = MathUtil.lerp(8.0, safe.previewMode == PreviewMode.PATH ? 200.0 : 140.0, 1.0 - roughness);
                    double specTerm = Math.pow(Math.max(0.0, mappedNormal.dot(halfVector)), specPow);
                    double fresnel = Math.pow(1.0 - ndotv, 5.0);
                    double metalTint = MathUtil.lerp(0.08, 0.9, metallic);
                    double coatBoost = clearcoat * (1.0 - result.clearcoatRoughness * 0.65);
                    double specEnergy = light.intensity * specTerm * (0.12 + specular * 0.38 + metalTint * 0.28 + coatBoost * 0.35 + fresnel * 0.18);
                    shaded.x += light.color.x * specEnergy;
                    shaded.y += light.color.y * specEnergy;
                    shaded.z += light.color.z * specEnergy;
                }

                if (result.sheenColor.lengthSquared() > 1e-8) {
                    double sheen = Math.pow(1.0 - ndotv, 1.5 + result.sheenRoughness * 4.0);
                    shaded.x += result.sheenColor.x * sheen * 0.32;
                    shaded.y += result.sheenColor.y * sheen * 0.32;
                    shaded.z += result.sheenColor.z * sheen * 0.32;
                }

                if (result.emissionStrength > 0.0) {
                    shaded.x += result.emissionColor.x * result.emissionStrength * 0.35;
                    shaded.y += result.emissionColor.y * result.emissionStrength * 0.35;
                    shaded.z += result.emissionColor.z * result.emissionStrength * 0.35;
                }

                if (result.isPureVolume()) {
                    double fog = MathUtil.clamp01(result.density * 0.26 + result.thickness * 0.08);
                    shaded.set(background).mulInPlace(1.0 - fog).addScaledInPlace(result.mediumColor, fog);
                    opacity = Math.max(opacity, 0.22);
                } else if (transmission > 1e-4 || opacity < 0.999) {
                    double absorb = MathUtil.clamp01(result.density * result.thickness * 0.35);
                    transmissionColor.x = MathUtil.lerp(background.x, result.mediumColor.x, absorb);
                    transmissionColor.y = MathUtil.lerp(background.y, result.mediumColor.y, absorb);
                    transmissionColor.z = MathUtil.lerp(background.z, result.mediumColor.z, absorb);
                    double mix = MathUtil.clamp01(transmission * 0.62 + (1.0 - opacity) * 0.55);
                    shaded.x = MathUtil.lerp(shaded.x, transmissionColor.x, mix);
                    shaded.y = MathUtil.lerp(shaded.y, transmissionColor.y, mix);
                    shaded.z = MathUtil.lerp(shaded.z, transmissionColor.z, mix);
                }

                clampInPlace(shaded);
                image.setRGB(px, py, toArgb(shaded, MathUtil.clamp01(Math.max(opacity, 0.92))));
            }
        }
        return image;
    }

    private static double ambientFactor(PreviewMode mode) {
        return switch (mode) {
            case FAST -> 0.18;
            case RAY -> 0.14;
            case PATH -> 0.10;
        };
    }

    private static PreviewLight[] buildLights(LightingPreset preset, PreviewMode mode) {
        double gain = switch (mode) {
            case FAST -> 1.0;
            case RAY -> 1.1;
            case PATH -> 1.18;
        };
        return switch (preset) {
            case HARD_RIM -> new PreviewLight[]{
                    new PreviewLight(new Vec3(-0.45, 0.72, 0.54), new Vec3(1.0, 0.98, 0.96), 1.25 * gain),
                    new PreviewLight(new Vec3(0.82, -0.12, 0.54), new Vec3(0.72, 0.86, 1.0), 0.95 * gain)
            };
            case WARM_SUNSET -> new PreviewLight[]{
                    new PreviewLight(new Vec3(-0.32, 0.76, 0.56), new Vec3(1.0, 0.76, 0.48), 1.18 * gain),
                    new PreviewLight(new Vec3(0.64, -0.18, 0.52), new Vec3(0.44, 0.58, 0.92), 0.72 * gain)
            };
            case NEUTRAL_WHITE -> new PreviewLight[]{
                    new PreviewLight(new Vec3(-0.30, 0.72, 0.62), new Vec3(1.0, 1.0, 1.0), 1.12 * gain),
                    new PreviewLight(new Vec3(0.48, -0.08, 0.56), new Vec3(0.82, 0.86, 0.92), 0.58 * gain)
            };
            case DARK_CONTRAST -> new PreviewLight[]{
                    new PreviewLight(new Vec3(-0.55, 0.65, 0.52), new Vec3(0.96, 0.98, 1.0), 1.08 * gain),
                    new PreviewLight(new Vec3(0.84, -0.32, 0.34), new Vec3(0.34, 0.48, 0.84), 0.42 * gain)
            };
            case STUDIO_SOFT -> new PreviewLight[]{
                    new PreviewLight(new Vec3(-0.28, 0.78, 0.56), new Vec3(1.0, 0.98, 0.96), 1.08 * gain),
                    new PreviewLight(new Vec3(0.44, -0.18, 0.64), new Vec3(0.74, 0.82, 0.96), 0.55 * gain)
            };
        };
    }

    private static Sample samplePrimitive(PreviewPrimitive primitive, double sx, double sy, Vec3 normalOut) {
        return switch (primitive) {
            case ROUNDED_CUBE -> sampleRoundedCube(sx, sy, normalOut);
            case PLANE -> samplePlane(sx, sy, normalOut);
            case SPHERE -> sampleSphere(sx, sy, normalOut);
        };
    }

    private static Sample sampleSphere(double sx, double sy, Vec3 normalOut) {
        double radius = 0.78;
        double x = sx / radius;
        double y = sy / radius;
        double rr = x * x + y * y;
        if (rr > 1.0) {
            return null;
        }
        double z = Math.sqrt(Math.max(0.0, 1.0 - rr));
        Vec3 normal = normalOut.set(x, -y, z).normalizeInPlace();
        double u = 0.5 + Math.atan2(normal.x, normal.z) / (Math.PI * 2.0);
        double v = 0.5 - Math.asin(normal.y) / Math.PI;
        Vec3 tangent = new Vec3(normal.z, 0.0, -normal.x).normalizeInPlace();
        if (tangent.lengthSquared() < 1e-6) {
            tangent.set(1.0, 0.0, 0.0);
        }
        Vec3 bitangent = normal.cross(tangent, new Vec3()).normalizeInPlace();
        return new Sample(new Vec3(normal.x, normal.y, normal.z), new Vec3(u, v, 0.0), tangent, bitangent);
    }

    private static Sample sampleRoundedCube(double sx, double sy, Vec3 normalOut) {
        double radius = 0.84;
        double x = sx / radius;
        double y = sy / radius;
        double p = 4.0;
        double ax = Math.pow(Math.abs(x), p);
        double ay = Math.pow(Math.abs(y), p);
        if (ax + ay > 1.0) {
            return null;
        }
        double z = Math.pow(Math.max(0.0, 1.0 - ax - ay), 1.0 / p);
        Vec3 normal = normalOut.set(
                Math.signum(x) * Math.pow(Math.abs(x), p - 1.0),
                -Math.signum(y) * Math.pow(Math.abs(y), p - 1.0),
                Math.pow(z, p - 1.0)
        ).normalizeInPlace();
        double u = MathUtil.clamp01(x * 0.5 + 0.5);
        double v = MathUtil.clamp01(y * -0.5 + 0.5);
        Vec3 tangent = new Vec3(1.0, 0.0, 0.0);
        Vec3 bitangent = normal.cross(tangent, new Vec3()).normalizeInPlace();
        if (bitangent.lengthSquared() < 1e-6) {
            tangent.set(0.0, 1.0, 0.0);
            bitangent = normal.cross(tangent, bitangent).normalizeInPlace();
        }
        tangent = bitangent.cross(normal, tangent).normalizeInPlace();
        return new Sample(new Vec3(x, y, z), new Vec3(u, v, 0.0), tangent, bitangent);
    }

    private static Sample samplePlane(double sx, double sy, Vec3 normalOut) {
        if (Math.abs(sx) > 0.84 || Math.abs(sy) > 0.62) {
            return null;
        }
        Vec3 normal = normalOut.set(0.12, 0.28, 0.95).normalizeInPlace();
        double u = MathUtil.clamp01(sx * 0.58 + 0.5);
        double v = MathUtil.clamp01(sy * -0.72 + 0.5);
        Vec3 world = new Vec3(sx, -sy, 0.08 * (sx - sy));
        Vec3 tangent = new Vec3(1.0, 0.0, 0.0);
        Vec3 bitangent = normal.cross(tangent, new Vec3()).normalizeInPlace();
        tangent = bitangent.cross(normal, tangent).normalizeInPlace();
        return new Sample(world, new Vec3(u, v, 0.0), tangent, bitangent);
    }

    private static Vec3 applyPreviewNormal(PhongMaterial material,
                                           double u,
                                           double v,
                                           Vec3 normal,
                                           Vec3 tangent,
                                           Vec3 bitangent) {
        if (material == null || material.getNormalMap() == null || !material.getNormalMap().hasTexture()) {
            return new Vec3(normal.x, normal.y, normal.z);
        }
        TextureMap map = material.getNormalMap();
        double scaledU = u * map.getScaleU();
        double scaledV = v * map.getScaleV();
        double sin = Math.sin(map.getRotation());
        double cos = Math.cos(map.getRotation());
        double rotatedU = scaledU * cos - scaledV * sin + map.getOffsetU();
        double rotatedV = scaledU * sin + scaledV * cos + map.getOffsetV();
        Texture texture = map.getTexture();
        int texel = map.isLinear()
                ? texture.sampleBilinear(rotatedU, rotatedV, map.isFlipV())
                : texture.sampleNearest(rotatedU, rotatedV, map.isFlipV());
        double tx = (((texel >> 16) & 0xFF) / 255.0 * 2.0 - 1.0) * material.getNormalScale();
        double ty = (((texel >> 8) & 0xFF) / 255.0 * 2.0 - 1.0) * material.getNormalScale();
        double tz = (texel & 0xFF) / 255.0 * 2.0 - 1.0;
        return new Vec3(tangent.x, tangent.y, tangent.z)
                .mulInPlace(tx)
                .addScaledInPlace(bitangent, ty)
                .addScaledInPlace(normal, tz)
                .normalizeInPlace();
    }

    private static void sampleBackground(BackgroundMode mode, double sx, double sy, Vec3 out) {
        switch (mode) {
            case CHECKER -> {
                int cx = (int) Math.floor((sx + 1.0) * 6.0);
                int cy = (int) Math.floor((sy + 1.0) * 6.0);
                boolean even = ((cx + cy) & 1) == 0;
                if (even) {
                    out.set(0.18, 0.19, 0.21);
                } else {
                    out.set(0.11, 0.12, 0.14);
                }
            }
            case GRAY -> out.set(MathUtil.lerp(0.28, 0.18, (sy + 1.0) * 0.5), MathUtil.lerp(0.29, 0.19, (sy + 1.0) * 0.5), MathUtil.lerp(0.31, 0.21, (sy + 1.0) * 0.5));
            case DARK -> out.set(MathUtil.lerp(0.12, 0.05, (sy + 1.0) * 0.5), MathUtil.lerp(0.14, 0.06, (sy + 1.0) * 0.5), MathUtil.lerp(0.18, 0.08, (sy + 1.0) * 0.5));
        }
    }

    private static void clampInPlace(Vec3 color) {
        color.x = MathUtil.clamp01(color.x);
        color.y = MathUtil.clamp01(color.y);
        color.z = MathUtil.clamp01(color.z);
    }

    private static int toArgb(Vec3 color, double alpha) {
        int a = (int) Math.round(MathUtil.clamp01(alpha) * 255.0);
        int r = (int) Math.round(MathUtil.clamp01(color.x) * 255.0);
        int g = (int) Math.round(MathUtil.clamp01(color.y) * 255.0);
        int b = (int) Math.round(MathUtil.clamp01(color.z) * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static long textureSignature(TextureMap map) {
        if (map == null || !map.hasTexture()) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, map.getTexture().getWidth());
        hash = mix(hash, map.getTexture().getHeight());
        hash = mix(hash, Double.doubleToLongBits(map.getOffsetU()));
        hash = mix(hash, Double.doubleToLongBits(map.getOffsetV()));
        hash = mix(hash, Double.doubleToLongBits(map.getScaleU()));
        hash = mix(hash, Double.doubleToLongBits(map.getScaleV()));
        hash = mix(hash, Double.doubleToLongBits(map.getRotation()));
        hash = mix(hash, map.getTexCoord());
        hash = mix(hash, map.isLinear() ? 1 : 0);
        hash = mix(hash, map.isFlipV() ? 1 : 0);
        return hash;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static final class Sample {
        final Vec3 worldPos;
        final Vec3 uv;
        final Vec3 tangent;
        final Vec3 bitangent;

        private Sample(Vec3 worldPos, Vec3 uv, Vec3 tangent, Vec3 bitangent) {
            this.worldPos = worldPos;
            this.uv = uv;
            this.tangent = tangent;
            this.bitangent = bitangent;
        }
    }
}
