package engine.material;

/**
 * Represents centralizovanou poctivou support matici pro materiálové uzly napříč Raster, Ray a Path renderery.
 * Záměrně ji drží konzervativní, aby mi sloužila pro UI souhrny místo rozházených textů podpory.
 */
public final class MaterialSupportMatrix {

    public enum SupportLevel {
        FULL("plně", 3),
        APPROXIMATE("aproximace", 2),
        LIMITED("částečně", 1),
        UNSUPPORTED("nepodporováno", 0);

        private final String label;
        private final int score;

        SupportLevel(String label, int score) {
            this.label = label;
            this.score = score;
        }

        public String label() {
            return label;
        }

        private static SupportLevel worstOf(SupportLevel a, SupportLevel b) {
            if (a == null) {
                return b == null ? FULL : b;
            }
            if (b == null) {
                return a;
            }
            return a.score <= b.score ? a : b;
        }
    }

    public static final class NodeSupport {
        private final SupportLevel raster;
        private final SupportLevel ray;
        private final SupportLevel path;
        private final String note;

        private NodeSupport(SupportLevel raster,
                            SupportLevel ray,
                            SupportLevel path,
                            String note) {
            this.raster = raster;
            this.ray = ray;
            this.path = path;
            this.note = note == null ? "" : note;
        }

        public SupportLevel raster() {
            return raster;
        }

        public SupportLevel ray() {
            return ray;
        }

        public SupportLevel path() {
            return path;
        }

        public String note() {
            return note;
        }

        public String compactSummary() {
            return "Raster: " + raster.label()
                    + " | Ray: " + ray.label()
                    + " | Path: " + path.label();
        }
    }

    public static final class GraphSupport {
        private final SupportLevel raster;
        private final SupportLevel ray;
        private final SupportLevel path;

        private GraphSupport(SupportLevel raster,
                             SupportLevel ray,
                             SupportLevel path) {
            this.raster = raster;
            this.ray = ray;
            this.path = path;
        }

        public SupportLevel raster() {
            return raster;
        }

        public SupportLevel ray() {
            return ray;
        }

        public SupportLevel path() {
            return path;
        }

        public String compactSummary() {
            return "Raster: " + raster.label()
                    + " | Ray: " + ray.label()
                    + " | Path: " + path.label();
        }
    }

    private MaterialSupportMatrix() {
    }

    public static NodeSupport forNode(MaterialNodeGraph.NodeType type) {
        if (type == null) {
            return new NodeSupport(
                    SupportLevel.UNSUPPORTED,
                    SupportLevel.UNSUPPORTED,
                    SupportLevel.UNSUPPORTED,
                    "Typ uzlu není rozpoznaný."
            );
        }
        return switch (type) {
            case OUTPUT_MATERIAL -> new NodeSupport(
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    "Výstup rozděluje Surface a Volume. Displacement v této iteraci engine nepodporuje."
            );
            case PRINCIPLED_BSDF -> new NodeSupport(
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    "Hlavní praktický shader. Výchozí sockety jsou uložené přímo v instanci uzlu."
            );
            case GLASS_BSDF -> new NodeSupport(
                    SupportLevel.APPROXIMATE,
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    "Raster ukazuje zjednodušený náhled přenosu a odrazu. Ray a Path jsou referenční režimy."
            );
            case TRANSPARENT_BSDF -> new NodeSupport(
                    SupportLevel.APPROXIMATE,
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    "Raster drží poctivou aproximaci opacity a přenosu, ne plně fyzikální transparentní closure."
            );
            case EMISSION_SHADER -> new NodeSupport(
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    "Emise je sdílená napříč všemi renderery."
            );
            case MIX_SHADER -> new NodeSupport(
                    SupportLevel.APPROXIMATE,
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    "Míchá surface closure semanticky. Raster z toho skládá konzistentní aproximovaný material sample."
            );
            case VOLUME_MEDIUM -> new NodeSupport(
                    SupportLevel.APPROXIMATE,
                    SupportLevel.LIMITED,
                    SupportLevel.FULL,
                    "Homogenní objemové médium. Raster je preview-only aproximace, Ray částečný a Path nejvěrnější."
            );
            case IMPORTED_BASE_COLOR,
                 IMPORTED_METAL_ROUGHNESS,
                 IMPORTED_EMISSIVE,
                 IMPORTED_NORMAL,
                 TEXTURE_COORDINATE,
                 MAPPING,
                 IMAGE_TEXTURE,
                 NORMAL_MAP,
                 SEPARATE_RGB,
                 COMBINE_RGB,
                 RGB,
                 VALUE,
                 NOISE_TEXTURE,
                 COLOR_RAMP,
                 MIX_COLOR,
                 MATH,
                 CLAMP,
                 MAP_RANGE -> new NodeSupport(
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    SupportLevel.FULL,
                    "Utility uzel je vyhodnocovaný společným graph evaluatorem a sdílený mezi všemi renderery."
            );
        };
    }

    public static GraphSupport summarize(MaterialNodeGraph graph) {
        SupportLevel raster = SupportLevel.FULL;
        SupportLevel ray = SupportLevel.FULL;
        SupportLevel path = SupportLevel.FULL;
        if (graph == null) {
            return new GraphSupport(raster, ray, path);
        }
        for (MaterialNodeGraph.Node node : graph.getNodes()) {
            if (node == null) {
                continue;
            }
            NodeSupport support = forNode(node.getType());
            raster = SupportLevel.worstOf(raster, support.raster());
            ray = SupportLevel.worstOf(ray, support.ray());
            path = SupportLevel.worstOf(path, support.path());
        }
        return new GraphSupport(raster, ray, path);
    }
}
