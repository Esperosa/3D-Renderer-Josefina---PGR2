package engine.material.node;

import engine.material.MaterialNodeGraph;

import java.util.EnumMap;
import java.util.Map;

public final class MaterialNodeRegistry {
    private static final Map<MaterialNodeGraph.NodeType, MaterialNodeDefinition> DEFINITIONS =
            new EnumMap<>(MaterialNodeGraph.NodeType.class);

    static {
        register(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL, new OutputMaterialNodeDefinition());
        register(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF, new PrincipledBsdfNodeDefinition());
        register(MaterialNodeGraph.NodeType.GLASS_BSDF, new GlassBsdfNodeDefinition());
        register(MaterialNodeGraph.NodeType.EMISSION_SHADER, new EmissionShaderNodeDefinition());
        register(MaterialNodeGraph.NodeType.MIX_SHADER, new MixShaderNodeDefinition());
        register(MaterialNodeGraph.NodeType.VOLUME_MEDIUM, new VolumeMediumNodeDefinition());
        register(MaterialNodeGraph.NodeType.IMPORTED_BASE_COLOR, new ImportedBaseColorNodeDefinition());
        register(MaterialNodeGraph.NodeType.IMPORTED_METAL_ROUGHNESS, new ImportedMetalRoughnessNodeDefinition());
        register(MaterialNodeGraph.NodeType.IMPORTED_EMISSIVE, new ImportedEmissiveNodeDefinition());
        register(MaterialNodeGraph.NodeType.IMPORTED_NORMAL, new ImportedNormalNodeDefinition());
        register(MaterialNodeGraph.NodeType.TEXTURE_COORDINATE, new TextureCoordinateNodeDefinition());
        register(MaterialNodeGraph.NodeType.MAPPING, new MappingNodeDefinition());
        register(MaterialNodeGraph.NodeType.IMAGE_TEXTURE, new ImageTextureNodeDefinition());
        register(MaterialNodeGraph.NodeType.NORMAL_MAP, new NormalMapNodeDefinition());
        register(MaterialNodeGraph.NodeType.TRANSPARENT_BSDF, new TransparentBsdfNodeDefinition());
        register(MaterialNodeGraph.NodeType.SEPARATE_RGB, new SeparateRgbNodeDefinition());
        register(MaterialNodeGraph.NodeType.COMBINE_RGB, new CombineRgbNodeDefinition());
        register(MaterialNodeGraph.NodeType.RGB, new RgbNodeDefinition());
        register(MaterialNodeGraph.NodeType.VALUE, new ValueNodeDefinition());
        register(MaterialNodeGraph.NodeType.NOISE_TEXTURE, new NoiseTextureNodeDefinition());
        register(MaterialNodeGraph.NodeType.COLOR_RAMP, new ColorRampNodeDefinition());
        register(MaterialNodeGraph.NodeType.MIX_COLOR, new MixColorNodeDefinition());
        register(MaterialNodeGraph.NodeType.MATH, new MathNodeDefinition());
        register(MaterialNodeGraph.NodeType.CLAMP, new ClampNodeDefinition());
        register(MaterialNodeGraph.NodeType.MAP_RANGE, new MapRangeNodeDefinition());
    }

    private MaterialNodeRegistry() {
    }

    public static MaterialNodeDefinition definition(MaterialNodeGraph.NodeType type) {
        MaterialNodeDefinition definition = DEFINITIONS.get(type);
        if (definition == null) {
            throw new IllegalArgumentException("Missing node definition for " + type);
        }
        return definition;
    }

    private static void register(MaterialNodeGraph.NodeType type, MaterialNodeDefinition definition) {
        DEFINITIONS.put(type, definition);
    }
}
