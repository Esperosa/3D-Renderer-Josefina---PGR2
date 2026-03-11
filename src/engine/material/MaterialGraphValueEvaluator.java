package engine.material;

import engine.math.MathUtil;
import engine.math.Vec3;
import engine.render.Texture;

/**
 * Vyhodnocení hodnot, barev, vektorů a textur v materiálovém grafu.
 */
final class MaterialGraphValueEvaluator {

    private MaterialGraphValueEvaluator() {
    }

    static Vec3 resolveColorInput(MaterialGraphEvaluator.EvalState state,
                                  MaterialNodeGraph.Node node,
                                  String inputSocket,
                                  Vec3 fallback) {
        MaterialNodeGraph.Link link = state.graph.findInputLink(node.getId(), inputSocket);
        if (link == null) {
            return fallback;
        }
        MaterialNodeGraph.Node sourceNode = state.graph.getNodeById(link.getFromNodeId());
        if (sourceNode == null) {
            return fallback;
        }
        MaterialNodeGraph.SocketDefinition sourceSocket = sourceNode.getType().output(link.getFromSocket());
        if (sourceSocket == null) {
            return fallback;
        }
        return sourceSocket.valueType() == MaterialNodeGraph.ValueType.VALUE
                ? gray(evaluateValueOutput(state, sourceNode, sourceSocket.key(), luminance(fallback)), state.borrowColor())
                : evaluateColorOutput(state, sourceNode, sourceSocket.key(), fallback);
    }

    static Vec3 resolveVectorInput(MaterialGraphEvaluator.EvalState state,
                                   MaterialNodeGraph.Node node,
                                   String inputSocket,
                                   Vec3 fallback) {
        MaterialNodeGraph.Link link = state.graph.findInputLink(node.getId(), inputSocket);
        if (link == null) {
            return fallback;
        }
        MaterialNodeGraph.Node sourceNode = state.graph.getNodeById(link.getFromNodeId());
        if (sourceNode == null) {
            return fallback;
        }
        MaterialNodeGraph.SocketDefinition sourceSocket = sourceNode.getType().output(link.getFromSocket());
        if (sourceSocket == null || sourceSocket.valueType() != MaterialNodeGraph.ValueType.VECTOR) {
            return fallback;
        }
        return evaluateVectorOutput(state, sourceNode, sourceSocket.key(), fallback);
    }

    static double resolveValueInput(MaterialGraphEvaluator.EvalState state,
                                    MaterialNodeGraph.Node node,
                                    String inputSocket,
                                    double fallback) {
        MaterialNodeGraph.Link link = state.graph.findInputLink(node.getId(), inputSocket);
        if (link == null) {
            return fallback;
        }
        MaterialNodeGraph.Node sourceNode = state.graph.getNodeById(link.getFromNodeId());
        if (sourceNode == null) {
            return fallback;
        }
        MaterialNodeGraph.SocketDefinition sourceSocket = sourceNode.getType().output(link.getFromSocket());
        if (sourceSocket == null) {
            return fallback;
        }
        return sourceSocket.valueType() == MaterialNodeGraph.ValueType.COLOR
                ? luminance(evaluateColorOutput(state, sourceNode, sourceSocket.key(), gray(fallback, state.borrowColor())))
                : evaluateValueOutput(state, sourceNode, sourceSocket.key(), fallback);
    }

    static Vec3 evaluateVectorOutput(MaterialGraphEvaluator.EvalState state,
                                     MaterialNodeGraph.Node node,
                                     String outputSocket,
                                     Vec3 fallback) {
        String cacheKey = "vector:" + node.getId() + ":" + outputSocket;
        if (state.vectorCache.containsKey(cacheKey)) {
            return state.vectorCache.get(cacheKey);
        }
        if (!state.activeKeys.add(cacheKey)) {
            return fallback;
        }
        Vec3 value;
        switch (node.getType()) {
            case TEXTURE_COORDINATE -> value = evaluateTextureCoordinate(state, outputSocket);
            case MAPPING -> value = evaluateMapping(state, node);
            case NORMAL_MAP -> value = evaluateNormalVector(state, node);
            default -> value = copyColor(fallback, state.borrowVector());
        }
        state.activeKeys.remove(cacheKey);
        state.vectorCache.put(cacheKey, value);
        return value;
    }

    static Vec3 evaluateColorOutput(MaterialGraphEvaluator.EvalState state,
                                    MaterialNodeGraph.Node node,
                                    String outputSocket,
                                    Vec3 fallback) {
        String cacheKey = "color:" + node.getId() + ":" + outputSocket;
        if (state.colorCache.containsKey(cacheKey)) {
            return state.colorCache.get(cacheKey);
        }
        if (!state.activeKeys.add(cacheKey)) {
            return fallback;
        }
        Vec3 value;
        switch (node.getType()) {
            case COMBINE_RGB -> value = evaluateCombineRgb(state, node);
            case RGB -> value = copyColor(node.getColor("color", fallback), state.borrowColor());
            case NOISE_TEXTURE -> value = gray(noiseFactor(state, node, true), state.borrowColor());
            case COLOR_RAMP -> value = evaluateRampColor(state, node);
            case MIX_COLOR -> value = evaluateMixColor(state, node);
            case IMPORTED_BASE_COLOR -> value = sampleTextureColor(
                    state.material.getDiffuseMap(), state.context, fallback, state.borrowColor());
            case IMPORTED_EMISSIVE -> value = sampleTextureColor(
                    state.material.getEmissiveMap(), state.context, fallback, state.borrowColor());
            case IMAGE_TEXTURE -> value = sampleImageTextureColor(state, node, state.context, fallback, state.borrowColor());
            default -> value = copyColor(fallback, state.borrowColor());
        }
        clampColorInPlace(value);
        state.activeKeys.remove(cacheKey);
        state.colorCache.put(cacheKey, value);
        return value;
    }

    static double evaluateValueOutput(MaterialGraphEvaluator.EvalState state,
                                      MaterialNodeGraph.Node node,
                                      String outputSocket,
                                      double fallback) {
        String cacheKey = "value:" + node.getId() + ":" + outputSocket;
        if (state.valueCache.containsKey(cacheKey)) {
            return state.valueCache.get(cacheKey);
        }
        if (!state.activeKeys.add(cacheKey)) {
            return fallback;
        }
        double value;
        switch (node.getType()) {
            case VALUE -> value = node.getNumber("value", fallback);
            case NOISE_TEXTURE -> value = noiseFactor(state, node, false);
            case COLOR_RAMP -> value = evaluateRampFactor(state, node);
            case MATH -> value = evaluateMath(state, node);
            case CLAMP -> value = evaluateClamp(state, node);
            case MAP_RANGE -> value = evaluateMapRange(state, node);
            case SEPARATE_RGB -> value = evaluateSeparateRgb(state, node, outputSocket, fallback);
            case IMPORTED_BASE_COLOR -> value = sampleTextureAlpha(state.material.getDiffuseMap(), state.context, fallback);
            case IMPORTED_METAL_ROUGHNESS -> {
                int mr = sampleTexture(state.material.getMetallicRoughnessMap(), state.context);
                if (mr == Integer.MIN_VALUE) {
                    value = fallback;
                } else if ("roughness".equalsIgnoreCase(outputSocket)) {
                    value = ((mr >> 8) & 0xFF) / 255.0;
                } else if ("metallic".equalsIgnoreCase(outputSocket)) {
                    value = (mr & 0xFF) / 255.0;
                } else {
                    value = fallback;
                }
            }
            case IMAGE_TEXTURE -> value = sampleImageTextureValue(state, node, state.context, outputSocket, fallback);
            case MIX_COLOR -> value = luminance(evaluateMixColor(state, node));
            default -> value = fallback;
        }
        state.activeKeys.remove(cacheKey);
        state.valueCache.put(cacheKey, value);
        return value;
    }

    static Vec3 clampColorInPlace(Vec3 color) {
        if (color == null) {
            return Vec3.ZERO;
        }
        color.x = MathUtil.clamp01(color.x);
        color.y = MathUtil.clamp01(color.y);
        color.z = MathUtil.clamp01(color.z);
        return color;
    }

    static Vec3 copyColor(Vec3 source, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        if (source == null) {
            return out.zero();
        }
        return out.set(source);
    }

    static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static Vec3 evaluateTextureCoordinate(MaterialGraphEvaluator.EvalState state, String outputSocket) {
        Vec3 out = state.borrowVector();
        String key = normalizeSocketKey(outputSocket, "uv0");
        return switch (key) {
            case "uv1" -> state.context.hasUv(1)
                    ? out.set(state.context.uvU(1), state.context.uvV(1), 0.0)
                    : out.zero();
            case "world" -> out.set(state.context.worldX(), state.context.worldY(), state.context.worldZ());
            default -> state.context.hasUv(0)
                    ? out.set(state.context.uvU(0), state.context.uvV(0), 0.0)
                    : out.zero();
        };
    }

    private static Vec3 evaluateMapping(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        Vec3 input = resolveVectorInput(state, node, "vector", state.borrowVector().set(
                state.context.hasUv(0) ? state.context.uvU(0) : 0.0,
                state.context.hasUv(0) ? state.context.uvV(0) : 0.0,
                0.0
        ));
        Vec3 out = state.borrowVector().set(input);
        out.x *= node.getNumber("scale_x", 1.0);
        out.y *= node.getNumber("scale_y", 1.0);
        out.z *= node.getNumber("scale_z", 1.0);
        rotateAroundX(out, node.getNumber("rotation_x", 0.0));
        rotateAroundY(out, node.getNumber("rotation_y", 0.0));
        rotateAroundZ(out, node.getNumber("rotation_z", 0.0));
        out.x += node.getNumber("location_x", 0.0);
        out.y += node.getNumber("location_y", 0.0);
        out.z += node.getNumber("location_z", 0.0);
        return out;
    }

    private static Vec3 evaluateNormalVector(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        Vec3 color = resolveColorInput(state, node, "color", node.getColor("color", state.borrowColor().set(0.5, 0.5, 1.0)));
        double strength = Math.max(0.0, resolveValueInput(state, node, "strength", node.getNumber("strength", 1.0)));
        return state.borrowVector().set(
                (color.x * 2.0 - 1.0) * strength,
                (color.y * 2.0 - 1.0) * strength,
                color.z * 2.0 - 1.0
        ).normalizeInPlace();
    }

    private static Vec3 evaluateCombineRgb(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        double r = MathUtil.clamp01(resolveValueInput(state, node, "red", node.getNumber("red", 0.8)));
        double g = MathUtil.clamp01(resolveValueInput(state, node, "green", node.getNumber("green", 0.8)));
        double b = MathUtil.clamp01(resolveValueInput(state, node, "blue", node.getNumber("blue", 0.8)));
        return state.borrowColor().set(r, g, b);
    }

    private static double evaluateSeparateRgb(MaterialGraphEvaluator.EvalState state,
                                              MaterialNodeGraph.Node node,
                                              String outputSocket,
                                              double fallback) {
        Vec3 color = resolveColorInput(state, node, "color", gray(fallback, state.borrowColor()));
        return switch (normalizeSocketKey(outputSocket, "")) {
            case "red" -> MathUtil.clamp01(color.x);
            case "green" -> MathUtil.clamp01(color.y);
            case "blue" -> MathUtil.clamp01(color.z);
            default -> fallback;
        };
    }

    private static Vec3 evaluateMixColor(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        Vec3 colorA = resolveColorInput(state, node, "color_a", node.getColor("color_a", state.borrowColor().set(0.2, 0.2, 0.2)));
        Vec3 colorB = resolveColorInput(state, node, "color_b", node.getColor("color_b", state.borrowColor().set(0.8, 0.8, 0.8)));
        double factor = MathUtil.clamp01(resolveValueInput(state, node, "factor", node.getNumber("factor", 0.5)));
        MaterialNodeGraph.BlendMode mode = parseEnum(
                node.getEnum("blend_mode", MaterialNodeGraph.BlendMode.MIX.name()),
                MaterialNodeGraph.BlendMode.MIX,
                MaterialNodeGraph.BlendMode.values()
        );
        Vec3 out = state.borrowColor();
        return switch (mode) {
            case ADD -> clampColorInPlace(out.set(colorA).addScaledInPlace(colorB, factor));
            case MULTIPLY -> out.set(
                    mix(colorA.x, colorA.x * colorB.x, factor),
                    mix(colorA.y, colorA.y * colorB.y, factor),
                    mix(colorA.z, colorA.z * colorB.z, factor)
            );
            case SCREEN -> out.set(
                    mix(colorA.x, 1.0 - (1.0 - colorA.x) * (1.0 - colorB.x), factor),
                    mix(colorA.y, 1.0 - (1.0 - colorA.y) * (1.0 - colorB.y), factor),
                    mix(colorA.z, 1.0 - (1.0 - colorA.z) * (1.0 - colorB.z), factor)
            );
            case SUBTRACT -> out.set(
                    Math.max(0.0, colorA.x - colorB.x * factor),
                    Math.max(0.0, colorA.y - colorB.y * factor),
                    Math.max(0.0, colorA.z - colorB.z * factor)
            );
            case MIX -> Vec3.lerp(colorA, colorB, factor, out);
        };
    }

    private static double evaluateMath(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        double a = resolveValueInput(state, node, "a", node.getNumber("a", 0.5));
        double b = resolveValueInput(state, node, "b", node.getNumber("b", 0.5));
        MaterialNodeGraph.MathOperation operation = parseEnum(
                node.getEnum("operation", MaterialNodeGraph.MathOperation.MULTIPLY.name()),
                MaterialNodeGraph.MathOperation.MULTIPLY,
                MaterialNodeGraph.MathOperation.values()
        );
        return switch (operation) {
            case ADD -> a + b;
            case SUBTRACT -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> Math.abs(b) < 1e-9 ? 0.0 : a / b;
            case POWER -> Math.pow(Math.max(0.0, a), b);
            case MIN -> Math.min(a, b);
            case MAX -> Math.max(a, b);
            case MODULO -> Math.abs(b) < 1e-9 ? 0.0 : a % b;
            case ABS -> Math.abs(a);
            case SINE -> Math.sin(a);
            case COSINE -> Math.cos(a);
        };
    }

    private static double evaluateClamp(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        double value = resolveValueInput(state, node, "value", node.getNumber("value", 0.5));
        double min = resolveValueInput(state, node, "min", node.getNumber("min", 0.0));
        double max = resolveValueInput(state, node, "max", node.getNumber("max", 1.0));
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        return MathUtil.clamp(value, min, max);
    }

    private static double evaluateMapRange(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        double value = resolveValueInput(state, node, "value", node.getNumber("value", 0.5));
        double fromMin = resolveValueInput(state, node, "from_min", node.getNumber("from_min", 0.0));
        double fromMax = resolveValueInput(state, node, "from_max", node.getNumber("from_max", 1.0));
        double toMin = resolveValueInput(state, node, "to_min", node.getNumber("to_min", 0.0));
        double toMax = resolveValueInput(state, node, "to_max", node.getNumber("to_max", 1.0));
        double t = Math.abs(fromMax - fromMin) < 1e-9 ? 0.0 : (value - fromMin) / (fromMax - fromMin);
        return mix(toMin, toMax, t);
    }

    private static Vec3 evaluateRampColor(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        double factor = evaluateRampFactor(state, node);
        Vec3 a = node.getColor("color_a", state.borrowColor().set(0.08, 0.08, 0.08));
        Vec3 b = node.getColor("color_b", state.borrowColor().set(0.95, 0.95, 0.95));
        return Vec3.lerp(a, b, factor, state.borrowColor());
    }

    private static double evaluateRampFactor(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node) {
        double source = resolveValueInput(state, node, "factor", 0.5);
        double a = MathUtil.clamp01(node.getNumber("position_a", 0.18));
        double b = Math.max(a + 1e-4, MathUtil.clamp01(node.getNumber("position_b", 0.82)));
        return MathUtil.smoothstep(a, b, MathUtil.clamp01(source));
    }

    private static double noiseFactor(MaterialGraphEvaluator.EvalState state, MaterialNodeGraph.Node node, boolean colorOutput) {
        double scale = Math.max(0.0001, resolveValueInput(state, node, "scale", node.getNumber("scale", 5.0)));
        double detail = MathUtil.clamp(resolveValueInput(state, node, "detail", node.getNumber("detail", 4.0)), 1.0, 8.0);
        double roughness = MathUtil.clamp01(resolveValueInput(state, node, "roughness", node.getNumber("roughness", 0.55)));
        double distortion = resolveValueInput(state, node, "distortion", node.getNumber("distortion", 0.15));
        int seed = (int) Math.round(node.getNumber("seed", 1.0));
        MaterialNodeGraph.CoordinateSource source = parseEnum(
                node.getEnum("coordinate_source", MaterialNodeGraph.CoordinateSource.UV0.name()),
                MaterialNodeGraph.CoordinateSource.UV0,
                MaterialNodeGraph.CoordinateSource.values()
        );
        double x;
        double y;
        double z;
        if (source == MaterialNodeGraph.CoordinateSource.WORLD) {
            x = state.context.worldX() * scale;
            y = state.context.worldY() * scale;
            z = state.context.worldZ() * scale;
        } else {
            int texCoord = source == MaterialNodeGraph.CoordinateSource.UV1 ? 1 : 0;
            if (!state.context.hasUv(texCoord)) {
                x = state.context.worldX() * scale * 0.2;
                y = state.context.worldY() * scale * 0.2;
                z = state.context.worldZ() * scale * 0.2;
            } else {
                x = state.context.uvU(texCoord) * scale;
                y = state.context.uvV(texCoord) * scale;
                z = colorOutput ? scale * 0.21 : 0.0;
            }
        }
        if (Math.abs(distortion) > 1e-6) {
            double warp = fbm(x + 17.0, y - 9.0, z + 4.0, Math.max(1.0, detail - 1.0), roughness, seed + 91);
            x += (warp - 0.5) * distortion * 3.2;
            y += (warp - 0.5) * distortion * 2.6;
            z += (warp - 0.5) * distortion * 1.9;
        }
        return MathUtil.clamp01(fbm(x, y, z, detail, roughness, seed));
    }

    private static double fbm(double x, double y, double z, double detail, double roughness, int seed) {
        int octaves = Math.max(1, (int) Math.round(detail));
        double sum = 0.0;
        double amp = 0.5;
        double norm = 0.0;
        for (int i = 0; i < octaves; i++) {
            sum += valueNoise(x, y, z, seed + i * 17) * amp;
            norm += amp;
            x *= 2.01;
            y *= 2.01;
            z *= 2.01;
            amp *= Math.max(0.05, roughness);
        }
        return norm < 1e-9 ? 0.0 : sum / norm;
    }

    private static double valueNoise(double x, double y, double z, int seed) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        int z0 = fastFloor(z);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;
        double tx = smooth(x - x0);
        double ty = smooth(y - y0);
        double tz = smooth(z - z0);
        double c000 = hashUnit(x0, y0, z0, seed);
        double c100 = hashUnit(x1, y0, z0, seed);
        double c010 = hashUnit(x0, y1, z0, seed);
        double c110 = hashUnit(x1, y1, z0, seed);
        double c001 = hashUnit(x0, y0, z1, seed);
        double c101 = hashUnit(x1, y0, z1, seed);
        double c011 = hashUnit(x0, y1, z1, seed);
        double c111 = hashUnit(x1, y1, z1, seed);
        double a = mix(c000, c100, tx);
        double b = mix(c010, c110, tx);
        double c = mix(c001, c101, tx);
        double d = mix(c011, c111, tx);
        double e = mix(a, b, ty);
        double f = mix(c, d, ty);
        return mix(e, f, tz);
    }

    private static double hashUnit(int x, int y, int z, int seed) {
        long h = x * 0x8da6b343L;
        h ^= y * 0xd8163841L;
        h ^= z * 0xcb1ab31fL;
        h ^= seed * 0x9e3779b9L;
        h ^= h >>> 16;
        h *= 0x45d9f3bL;
        h ^= h >>> 15;
        h *= 0x45d9f3bL;
        h ^= h >>> 16;
        return (h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL;
    }

    private static int fastFloor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static int sampleTexture(TextureMap map, MaterialGraphEvaluator.Context context) {
        if (map == null || context == null || !map.hasTexture() || !context.hasUv(map.getTexCoord())) {
            return Integer.MIN_VALUE;
        }
        double scaledU = context.uvU(map.getTexCoord()) * map.getScaleU();
        double scaledV = context.uvV(map.getTexCoord()) * map.getScaleV();
        double sin = Math.sin(map.getRotation());
        double cos = Math.cos(map.getRotation());
        double rotatedU = scaledU * cos - scaledV * sin + map.getOffsetU();
        double rotatedV = scaledU * sin + scaledV * cos + map.getOffsetV();
        Texture texture = map.getTexture();
        return map.isLinear()
                ? texture.sampleBilinear(rotatedU, rotatedV, map.isFlipV())
                : texture.sampleNearest(rotatedU, rotatedV, map.isFlipV());
    }

    private static Vec3 sampleTextureColor(TextureMap map, MaterialGraphEvaluator.Context context, Vec3 fallback, Vec3 out) {
        int texel = sampleTexture(map, context);
        return texel == Integer.MIN_VALUE
                ? copyColor(fallback, out)
                : out.set(((texel >> 16) & 0xFF) / 255.0, ((texel >> 8) & 0xFF) / 255.0, (texel & 0xFF) / 255.0);
    }

    private static double sampleTextureAlpha(TextureMap map, MaterialGraphEvaluator.Context context, double fallback) {
        int texel = sampleTexture(map, context);
        return texel == Integer.MIN_VALUE ? fallback : ((texel >>> 24) & 0xFF) / 255.0;
    }

    private static int sampleImageTexture(MaterialGraphEvaluator.EvalState state,
                                          MaterialNodeGraph.Node node,
                                          MaterialGraphEvaluator.Context context) {
        if (node == null || context == null) {
            return Integer.MIN_VALUE;
        }
        Texture texture = NodeTextureLibrary.load(node.getText("file_path", null));
        if (texture == null) {
            return Integer.MIN_VALUE;
        }
        Vec3 coords = resolveImageTextureCoords(state, node, context);
        if (coords == null) {
            return Integer.MIN_VALUE;
        }
        double scaledU = coords.x * node.getNumber("scale_u", 1.0);
        double scaledV = coords.y * node.getNumber("scale_v", 1.0);
        double rotation = node.getNumber("rotation", 0.0);
        double sin = Math.sin(rotation);
        double cos = Math.cos(rotation);
        double rotatedU = scaledU * cos - scaledV * sin + node.getNumber("offset_u", 0.0);
        double rotatedV = scaledU * sin + scaledV * cos + node.getNumber("offset_v", 0.0);
        boolean linear = node.getNumber("linear", 1.0) >= 0.5;
        boolean flipV = node.getNumber("flip_v", 0.0) >= 0.5;
        return linear ? texture.sampleBilinear(rotatedU, rotatedV, flipV) : texture.sampleNearest(rotatedU, rotatedV, flipV);
    }

    private static Vec3 sampleImageTextureColor(MaterialGraphEvaluator.EvalState state,
                                                MaterialNodeGraph.Node node,
                                                MaterialGraphEvaluator.Context context,
                                                Vec3 fallback,
                                                Vec3 out) {
        int texel = sampleImageTexture(state, node, context);
        if (texel == Integer.MIN_VALUE) {
            return clampColorInPlace(copyColor(node == null ? fallback : node.getColor("fallback_color", fallback), out));
        }
        out.set(((texel >> 16) & 0xFF) / 255.0, ((texel >> 8) & 0xFF) / 255.0, (texel & 0xFF) / 255.0);
        return applyImageColorSpace(node, out);
    }

    private static double sampleImageTextureValue(MaterialGraphEvaluator.EvalState state,
                                                  MaterialNodeGraph.Node node,
                                                  MaterialGraphEvaluator.Context context,
                                                  String outputSocket,
                                                  double fallback) {
        int texel = sampleImageTexture(state, node, context);
        if (texel == Integer.MIN_VALUE) {
            Vec3 fallbackColor = node == null
                    ? gray(fallback, state.borrowColor())
                    : node.getColor("fallback_color", gray(fallback, state.borrowColor()));
            return switch (normalizeSocketKey(outputSocket, "")) {
                case "red" -> MathUtil.clamp01(fallbackColor.x);
                case "green" -> MathUtil.clamp01(fallbackColor.y);
                case "blue" -> MathUtil.clamp01(fallbackColor.z);
                case "alpha" -> MathUtil.clamp01(node == null ? fallback : node.getNumber("fallback_alpha", fallback));
                default -> fallback;
            };
        }
        return switch (normalizeSocketKey(outputSocket, "")) {
            case "red" -> ((texel >> 16) & 0xFF) / 255.0;
            case "green" -> ((texel >> 8) & 0xFF) / 255.0;
            case "blue" -> (texel & 0xFF) / 255.0;
            case "alpha" -> ((texel >>> 24) & 0xFF) / 255.0;
            default -> fallback;
        };
    }

    private static Vec3 resolveImageTextureCoords(MaterialGraphEvaluator.EvalState state,
                                                  MaterialNodeGraph.Node node,
                                                  MaterialGraphEvaluator.Context context) {
        MaterialNodeGraph.Link vectorLink = state == null || node == null ? null : state.graph.findInputLink(node.getId(), "vector");
        if (vectorLink != null) {
            MaterialNodeGraph.Node sourceNode = state.graph.getNodeById(vectorLink.getFromNodeId());
            MaterialNodeGraph.SocketDefinition sourceSocket = sourceNode == null ? null : sourceNode.getType().output(vectorLink.getFromSocket());
            if (sourceNode != null && sourceSocket != null && sourceSocket.valueType() == MaterialNodeGraph.ValueType.VECTOR) {
                return evaluateVectorOutput(state, sourceNode, sourceSocket.key(), state.borrowVector());
            }
        }
        int texCoord = "UV1".equalsIgnoreCase(node.getEnum("uv_set", "UV0")) ? 1 : 0;
        if (!context.hasUv(texCoord)) {
            return null;
        }
        return state.borrowVector().set(context.uvU(texCoord), context.uvV(texCoord), 0.0);
    }

    private static Vec3 applyImageColorSpace(MaterialNodeGraph.Node node, Vec3 color) {
        if (node == null || color == null) {
            return color;
        }
        MaterialNodeGraph.TextureColorSpace colorSpace = parseEnum(
                node.getEnum("color_space", MaterialNodeGraph.TextureColorSpace.SRGB.name()),
                MaterialNodeGraph.TextureColorSpace.SRGB,
                MaterialNodeGraph.TextureColorSpace.values()
        );
        if (colorSpace != MaterialNodeGraph.TextureColorSpace.SRGB) {
            return color;
        }
        color.x = srgbToLinear(color.x);
        color.y = srgbToLinear(color.y);
        color.z = srgbToLinear(color.z);
        return color;
    }

    private static Vec3 gray(double value, Vec3 out) {
        double v = MathUtil.clamp01(value);
        return out.set(v, v, v);
    }

    private static double luminance(Vec3 color) {
        if (color == null) {
            return 0.0;
        }
        return MathUtil.clamp01(color.x * 0.2126 + color.y * 0.7152 + color.z * 0.0722);
    }

    private static void rotateAroundX(Vec3 vector, double angle) {
        if (vector == null || Math.abs(angle) < 1e-9) {
            return;
        }
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double y = vector.y * cos - vector.z * sin;
        double z = vector.y * sin + vector.z * cos;
        vector.y = y;
        vector.z = z;
    }

    private static void rotateAroundY(Vec3 vector, double angle) {
        if (vector == null || Math.abs(angle) < 1e-9) {
            return;
        }
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double x = vector.x * cos + vector.z * sin;
        double z = -vector.x * sin + vector.z * cos;
        vector.x = x;
        vector.z = z;
    }

    private static void rotateAroundZ(Vec3 vector, double angle) {
        if (vector == null || Math.abs(angle) < 1e-9) {
            return;
        }
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double x = vector.x * cos - vector.y * sin;
        double y = vector.x * sin + vector.y * cos;
        vector.x = x;
        vector.y = y;
    }

    private static double srgbToLinear(double value) {
        double clamped = MathUtil.clamp01(value);
        if (clamped <= 0.04045) {
            return clamped / 12.92;
        }
        return Math.pow((clamped + 0.055) / 1.055, 2.4);
    }

    private static <E extends Enum<E>> E parseEnum(String value, E fallback, E[] values) {
        if (value == null || values == null) {
            return fallback;
        }
        for (E candidate : values) {
            if (candidate.name().equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        return fallback;
    }

    /**
     * Tady klíče socketů v materiálovém grafu držím jako ASCII identifikátory.
     * V nejteplejší cestě vykreslení proto nepoužívám převod na malá písmena závislý na jazykovém prostředí.
     */
    private static String normalizeSocketKey(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        char[] buffer = null;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            char lowered = ch >= 'A' && ch <= 'Z' ? (char) (ch + ('a' - 'A')) : ch;
            if (buffer != null) {
                buffer[i] = lowered;
            } else if (lowered != ch) {
                buffer = trimmed.toCharArray();
                buffer[i] = lowered;
            }
        }
        return buffer == null ? trimmed : new String(buffer);
    }
}
