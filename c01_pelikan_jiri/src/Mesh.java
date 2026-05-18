import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@FunctionalInterface
interface ProceduralTexture {
    Vec3 sample(double u, double v);
}

final class Vertex {
    final Vec3 position;
    final Vec3 normal;
    final Vec2 uv;
    final Vec3 color;

    Vertex(Vec3 position, Vec3 normal, Vec2 uv, Vec3 color) {
        this.position = position;
        this.normal = normal;
        this.uv = uv;
        this.color = color;
    }
}

final class Face {
    final int a;
    final int b;
    final int c;

    Face(int a, int b, int c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }
}

final class Edge {
    final int a;
    final int b;

    Edge(int a, int b) {
        this.a = a;
        this.b = b;
    }
}

final class Mesh {
    final List<Vertex> vertices = new ArrayList<>();
    final List<Face> faces = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    final Set<Long> edgeKeys = new HashSet<>();
    String topologyNote = "triangles + edges";

    int addVertex(Vertex v) {
        vertices.add(v);
        return vertices.size() - 1;
    }

    void addTriangle(int a, int b, int c) {
        faces.add(new Face(a, b, c));
        addEdge(a, b);
        addEdge(b, c);
        addEdge(c, a);
    }

    void addQuad(int a, int b, int c, int d) {
        topologyNote = "quads triangulated for rasterization";
        addTriangle(a, b, c);
        addTriangle(a, c, d);
        addEdge(d, a);
    }

    private void addEdge(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        long key = (((long) lo) << 32) | (hi & 0xffffffffL);
        if (edgeKeys.add(key)) {
            edges.add(new Edge(a, b));
        }
    }
}

final class Entity {
    final String name;
    final Mesh mesh;
    final Transform transform = new Transform();
    final Vec3 baseColor;
    final ProceduralTexture texture;
    boolean textureEnabled = true;
    boolean lightMarker;

    Entity(String name, Mesh mesh, Vec3 baseColor, ProceduralTexture texture) {
        this.name = name;
        this.mesh = mesh;
        this.baseColor = baseColor;
        this.texture = texture;
    }

    @Override
    public String toString() {
        return name;
    }
}
