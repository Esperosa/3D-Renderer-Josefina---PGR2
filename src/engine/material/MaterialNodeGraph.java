package engine.material;

import engine.material.node.MaterialNodeDefinition;
import engine.material.node.MaterialNodeRegistry;
import engine.math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lehký materiálový graf používaný editorem i sdíleným vyhodnocením v rendererech.
 */
public final class MaterialNodeGraph {

    public enum ValueType {
        COLOR,
        VALUE,
        VECTOR,
        SURFACE,
        VOLUME;

        public boolean isNumericLike() {
            return this == COLOR || this == VALUE;
        }
    }

    public enum MathOperation {
        ADD("Add"),
        SUBTRACT("Subtract"),
        MULTIPLY("Multiply"),
        DIVIDE("Divide"),
        POWER("Power"),
        MIN("Min"),
        MAX("Max"),
        MODULO("Modulo"),
        ABS("Abs"),
        SINE("Sine"),
        COSINE("Cosine");

        private final String displayName;

        MathOperation(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum BlendMode {
        MIX("Mix"),
        ADD("Add"),
        MULTIPLY("Multiply"),
        SCREEN("Screen"),
        SUBTRACT("Subtract");

        private final String displayName;

        BlendMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum CoordinateSource {
        UV0("UV0"),
        UV1("UV1"),
        WORLD("World");

        private final String displayName;

        CoordinateSource(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum TextureColorSpace {
        SRGB("sRGB"),
        DATA("Data");

        private final String displayName;

        TextureColorSpace(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static final class SocketDefinition {
        private final String key;
        private final String label;
        private final ValueType valueType;
        private final boolean input;

        private SocketDefinition(String key, String label, ValueType valueType, boolean input) {
            this.key = key;
            this.label = label;
            this.valueType = valueType;
            this.input = input;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public ValueType valueType() {
            return valueType;
        }

        public boolean isInput() {
            return input;
        }
    }

    public enum NodeType {
        OUTPUT_MATERIAL,
        PRINCIPLED_BSDF,
        GLASS_BSDF,
        EMISSION_SHADER,
        MIX_SHADER,
        VOLUME_MEDIUM,
        IMPORTED_BASE_COLOR,
        IMPORTED_METAL_ROUGHNESS,
        IMPORTED_EMISSIVE,
        TEXTURE_COORDINATE,
        MAPPING,
        IMAGE_TEXTURE,
        NORMAL_MAP,
        TRANSPARENT_BSDF,
        SEPARATE_RGB,
        COMBINE_RGB,
        RGB,
        VALUE,
        NOISE_TEXTURE,
        COLOR_RAMP,
        MIX_COLOR,
        MATH,
        CLAMP,
        MAP_RANGE;

        public String title() {
            return definition().title();
        }

        public String category() {
            return definition().category();
        }

        public int accentRgb() {
            return definition().accentRgb();
        }

        public boolean isDeletable() {
            return definition().isDeletable();
        }

        public SocketDefinition[] inputs() {
            return definition().inputs();
        }

        public SocketDefinition[] outputs() {
            return definition().outputs();
        }

        public SocketDefinition input(String key) {
            return findSocket(inputs(), key);
        }

        public SocketDefinition output(String key) {
            return findSocket(outputs(), key);
        }

        public void applyDefaults(Node node) {
            definition().applyDefaults(node);
        }

        private MaterialNodeDefinition definition() {
            return MaterialNodeRegistry.definition(this);
        }

        private static SocketDefinition findSocket(SocketDefinition[] definitions, String key) {
            if (definitions == null || key == null) {
                return null;
            }
            for (SocketDefinition definition : definitions) {
                if (definition.key.equalsIgnoreCase(key.trim())) {
                    return definition;
                }
            }
            return null;
        }
    }

    public static final class Node {
        private final int id;
        private final NodeType type;
        private double x;
        private double y;
        private final Map<String, Double> numbers = new HashMap<>();
        private final Map<String, Vec3> colors = new HashMap<>();
        private final Map<String, String> enums = new HashMap<>();
        private final Map<String, String> texts = new HashMap<>();

        private Node(int id, NodeType type, double x, double y) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            if (type != null) {
                type.applyDefaults(this);
            }
        }

        public int getId() {
            return id;
        }

        public NodeType getType() {
            return type;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }

        public double getNumber(String key, double fallback) {
            return key == null ? fallback : numbers.getOrDefault(normalizeKey(key), fallback);
        }

        public void setNumber(String key, double value) {
            if (key != null) {
                numbers.put(normalizeKey(key), value);
            }
        }

        public Vec3 getColor(String key, Vec3 fallback) {
            return key == null ? fallback : colors.getOrDefault(normalizeKey(key), fallback);
        }

        public void setColor(String key, Vec3 value) {
            if (key != null && value != null) {
                colors.put(normalizeKey(key), value);
            }
        }

        public String getEnum(String key, String fallback) {
            return key == null ? fallback : enums.getOrDefault(normalizeKey(key), fallback);
        }

        public void setEnum(String key, String value) {
            if (key != null && value != null && !value.isBlank()) {
                enums.put(normalizeKey(key), value.trim());
            }
        }

        public String getText(String key, String fallback) {
            return key == null ? fallback : texts.getOrDefault(normalizeKey(key), fallback);
        }

        public void setText(String key, String value) {
            if (key == null) {
                return;
            }
            String normalizedKey = normalizeKey(key);
            if (value == null || value.isBlank()) {
                texts.remove(normalizedKey);
                return;
            }
            texts.put(normalizedKey, value.trim());
        }

        public Node copy() {
            Node copy = new Node(id, type, x, y);
            copy.numbers.clear();
            copy.numbers.putAll(numbers);
            copy.colors.clear();
            for (Map.Entry<String, Vec3> entry : colors.entrySet()) {
                Vec3 color = entry.getValue();
                copy.colors.put(entry.getKey(), color == null ? null : new Vec3(color.x, color.y, color.z));
            }
            copy.enums.clear();
            copy.enums.putAll(enums);
            copy.texts.clear();
            copy.texts.putAll(texts);
            return copy;
        }

        public Map<String, Double> numberEntries() {
            return Collections.unmodifiableMap(new HashMap<>(numbers));
        }

        public Map<String, Vec3> colorEntries() {
            HashMap<String, Vec3> copy = new HashMap<>();
            for (Map.Entry<String, Vec3> entry : colors.entrySet()) {
                Vec3 color = entry.getValue();
                copy.put(entry.getKey(), color == null ? null : new Vec3(color.x, color.y, color.z));
            }
            return Collections.unmodifiableMap(copy);
        }

        public Map<String, String> enumEntries() {
            return Collections.unmodifiableMap(new HashMap<>(enums));
        }

        public Map<String, String> textEntries() {
            return Collections.unmodifiableMap(new HashMap<>(texts));
        }

 /**
 * Normalizaci klíčů v nejteplejší cestě vykreslení nedělám přes převod závislý na jazykovém prostředí.
 * sockety a interní klíče drží v ASCII, takže je pro mě levnější i stabilnější
 * držet vlastní malou normalizaci bez volání String.toLowerCase(...).
 */
        private static String normalizeKey(String key) {
            if (key == null) {
                return "";
            }
            String trimmed = key.trim();
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

    public static final class Link {
        private final int fromNodeId;
        private final String fromSocket;
        private final int toNodeId;
        private final String toSocket;

        private Link(int fromNodeId, String fromSocket, int toNodeId, String toSocket) {
            this.fromNodeId = fromNodeId;
            this.fromSocket = fromSocket;
            this.toNodeId = toNodeId;
            this.toSocket = toSocket;
        }

        public int getFromNodeId() {
            return fromNodeId;
        }

        public String getFromSocket() {
            return fromSocket;
        }

        public int getToNodeId() {
            return toNodeId;
        }

        public String getToSocket() {
            return toSocket;
        }

        public Link copy() {
            return new Link(fromNodeId, fromSocket, toNodeId, toSocket);
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private int nextNodeId = 1;
    private int selectedNodeId = -1;
    private double viewOffsetX = 72.0;
    private double viewOffsetY = 52.0;
    private double zoom = 1.0;

    public static MaterialNodeGraph createDefault() {
        MaterialNodeGraph graph = new MaterialNodeGraph();
        Node baseColor = graph.addNode(NodeType.IMPORTED_BASE_COLOR, 46, 84);
        Node metalRough = graph.addNode(NodeType.IMPORTED_METAL_ROUGHNESS, 46, 272);
        Node emissive = graph.addNode(NodeType.IMPORTED_EMISSIVE, 46, 440);
        Node bsdf = graph.addNode(NodeType.PRINCIPLED_BSDF, 402, 132);
        Node volume = graph.addNode(NodeType.VOLUME_MEDIUM, 420, 470);
        Node output = graph.addNode(NodeType.OUTPUT_MATERIAL, 816, 248);
        graph.connect(baseColor.getId(), "color", bsdf.getId(), "base_color");
        graph.connect(baseColor.getId(), "alpha", bsdf.getId(), "opacity");
        graph.connect(metalRough.getId(), "roughness", bsdf.getId(), "roughness");
        graph.connect(metalRough.getId(), "metallic", bsdf.getId(), "metallic");
        graph.connect(emissive.getId(), "color", bsdf.getId(), "emission");
        graph.connect(bsdf.getId(), "bsdf", output.getId(), "surface");
        graph.connect(volume.getId(), "volume", output.getId(), "volume");
        graph.selectedNodeId = bsdf.getId();
        return graph;
    }

    public MaterialNodeGraph copy() {
        MaterialNodeGraph copy = new MaterialNodeGraph();
        for (Node node : nodes) {
            copy.nodes.add(node.copy());
        }
        for (Link link : links) {
            copy.links.add(link.copy());
        }
        copy.nextNodeId = nextNodeId;
        copy.selectedNodeId = selectedNodeId;
        copy.viewOffsetX = viewOffsetX;
        copy.viewOffsetY = viewOffsetY;
        copy.zoom = zoom;
        return copy;
    }

    public List<Node> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<Link> getLinks() {
        return Collections.unmodifiableList(links);
    }

    public int getNextNodeId() {
        return nextNodeId;
    }

    public Node addNode(NodeType type, double x, double y) {
        Node node = new Node(nextNodeId++, type, x, y);
        nodes.add(node);
        selectedNodeId = node.getId();
        return node;
    }

    public Node duplicateNode(int nodeId, double offsetX, double offsetY) {
        Node source = getNodeById(nodeId);
        if (source == null || !source.getType().isDeletable()) {
            return null;
        }
        Node copy = new Node(nextNodeId++, source.getType(), source.getX() + offsetX, source.getY() + offsetY);
        copy.numbers.clear();
        copy.numbers.putAll(source.numbers);
        copy.colors.clear();
        for (Map.Entry<String, Vec3> entry : source.colors.entrySet()) {
            Vec3 color = entry.getValue();
            copy.colors.put(entry.getKey(), color == null ? null : new Vec3(color.x, color.y, color.z));
        }
        copy.enums.clear();
        copy.enums.putAll(source.enums);
        copy.texts.clear();
        copy.texts.putAll(source.texts);
        nodes.add(copy);
        selectedNodeId = copy.getId();
        return copy;
    }

    public void bringNodeToFront(int nodeId) {
        Node node = getNodeById(nodeId);
        if (node == null || nodes.isEmpty() || nodes.get(nodes.size() - 1) == node) {
            return;
        }
        nodes.remove(node);
        nodes.add(node);
    }

    public boolean removeNode(int nodeId) {
        Node node = getNodeById(nodeId);
        if (node == null || !node.getType().isDeletable()) {
            return false;
        }
        nodes.remove(node);
        links.removeIf(link -> link.getFromNodeId() == nodeId || link.getToNodeId() == nodeId);
        if (selectedNodeId == nodeId) {
            selectedNodeId = -1;
        }
        return true;
    }

    public void clearAndReset() {
        nodes.clear();
        links.clear();
        nextNodeId = 1;
        selectedNodeId = -1;
        MaterialNodeGraph defaults = createDefault();
        for (Node node : defaults.nodes) {
            nodes.add(node.copy());
        }
        for (Link link : defaults.links) {
            links.add(link.copy());
        }
        nextNodeId = defaults.nextNodeId;
        selectedNodeId = defaults.selectedNodeId;
        viewOffsetX = defaults.viewOffsetX;
        viewOffsetY = defaults.viewOffsetY;
        zoom = defaults.zoom;
    }

    public Node getNodeById(int nodeId) {
        for (Node node : nodes) {
            if (node.getId() == nodeId) {
                return node;
            }
        }
        return null;
    }

    public Node findFirstNode(NodeType type) {
        if (type == null) {
            return null;
        }
        for (Node node : nodes) {
            if (node.getType() == type) {
                return node;
            }
        }
        return null;
    }

    public Link findInputLink(int toNodeId, String toSocket) {
        if (toSocket == null) {
            return null;
        }
        for (Link link : links) {
            if (link.getToNodeId() == toNodeId && link.getToSocket().equalsIgnoreCase(toSocket)) {
                return link;
            }
        }
        return null;
    }

    public List<Link> getLinksForNode(int nodeId) {
        List<Link> out = new ArrayList<>();
        for (Link link : links) {
            if (link.getFromNodeId() == nodeId || link.getToNodeId() == nodeId) {
                out.add(link);
            }
        }
        return out;
    }

    public boolean disconnectInput(int toNodeId, String toSocket) {
        return toSocket != null
                && links.removeIf(link -> link.getToNodeId() == toNodeId && link.getToSocket().equalsIgnoreCase(toSocket));
    }

    public boolean disconnectOutput(int fromNodeId, String fromSocket) {
        return fromSocket != null
                && links.removeIf(link -> link.getFromNodeId() == fromNodeId && link.getFromSocket().equalsIgnoreCase(fromSocket));
    }

    public boolean connect(int fromNodeId, String fromSocket, int toNodeId, String toSocket) {
        Node fromNode = getNodeById(fromNodeId);
        Node toNode = getNodeById(toNodeId);
        if (fromNode == null || toNode == null || fromNodeId == toNodeId || fromSocket == null || toSocket == null) {
            return false;
        }
        SocketDefinition output = fromNode.getType().output(fromSocket);
        SocketDefinition input = toNode.getType().input(toSocket);
        if (output == null || input == null || !canConnect(output.valueType(), input.valueType())) {
            return false;
        }
        disconnectInput(toNodeId, toSocket);
        links.add(new Link(fromNodeId, output.key(), toNodeId, input.key()));
        return true;
    }

    public int getSelectedNodeId() {
        return selectedNodeId;
    }

    public void setSelectedNodeId(int selectedNodeId) {
        this.selectedNodeId = selectedNodeId;
    }

    public double getViewOffsetX() {
        return viewOffsetX;
    }

    public void setViewOffsetX(double viewOffsetX) {
        this.viewOffsetX = viewOffsetX;
    }

    public double getViewOffsetY() {
        return viewOffsetY;
    }

    public void setViewOffsetY(double viewOffsetY) {
        this.viewOffsetY = viewOffsetY;
    }

    public double getZoom() {
        return zoom;
    }

    public void setZoom(double zoom) {
        this.zoom = clamp(zoom, 0.45, 1.85);
    }

    public long signature() {
        long hash = 0xcbf29ce484222325L;
        hash = mixHash(hash, nextNodeId);
        for (Node node : nodes) {
            hash = mixHash(hash, node.getId());
            hash = mixHash(hash, node.getType().ordinal());
            for (Map.Entry<String, Double> entry : node.numbers.entrySet()) {
                hash = mixHash(hash, entry.getKey().hashCode());
                hash = mixHash(hash, Double.doubleToLongBits(entry.getValue()));
            }
            for (Map.Entry<String, Vec3> entry : node.colors.entrySet()) {
                hash = mixHash(hash, entry.getKey().hashCode());
                Vec3 color = entry.getValue();
                hash = mixHash(hash, Double.doubleToLongBits(color.x));
                hash = mixHash(hash, Double.doubleToLongBits(color.y));
                hash = mixHash(hash, Double.doubleToLongBits(color.z));
            }
            for (Map.Entry<String, String> entry : node.enums.entrySet()) {
                hash = mixHash(hash, entry.getKey().hashCode());
                hash = mixHash(hash, entry.getValue().hashCode());
            }
            for (Map.Entry<String, String> entry : node.texts.entrySet()) {
                hash = mixHash(hash, entry.getKey().hashCode());
                hash = mixHash(hash, entry.getValue().hashCode());
            }
        }
        for (Link link : links) {
            hash = mixHash(hash, link.getFromNodeId());
            hash = mixHash(hash, link.getFromSocket().hashCode());
            hash = mixHash(hash, link.getToNodeId());
            hash = mixHash(hash, link.getToSocket().hashCode());
        }
        return hash;
    }

    public static boolean canConnect(ValueType outputType, ValueType inputType) {
        if (outputType == null || inputType == null) {
            return false;
        }
        if (outputType == inputType) {
            return true;
        }
        return outputType.isNumericLike() && inputType.isNumericLike();
    }

    public static NodeType[] addableNodeTypes() {
        List<NodeType> types = new ArrayList<>();
        for (NodeType type : NodeType.values()) {
            if (type != NodeType.OUTPUT_MATERIAL) {
                types.add(type);
            }
        }
        return types.toArray(new NodeType[0]);
    }

    public static SocketDefinition inputSocket(String key, String label, ValueType valueType) {
        return new SocketDefinition(key, label, valueType, true);
    }

    public static SocketDefinition outputSocket(String key, String label, ValueType valueType) {
        return new SocketDefinition(key, label, valueType, false);
    }

    private static long mixHash(long hash, long value) {
        long out = hash ^ value;
        return out * 0x100000001b3L;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}