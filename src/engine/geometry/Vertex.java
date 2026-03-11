package engine.geometry;

import engine.math.Vec2;
import engine.math.Vec3;

import java.util.Objects;

/**
 * Tady držím jeden vertex se všemi běžnými atributy pro stavbu meshe.
 */
public class Vertex {

    private final Vec3 position;
    private final Vec3 normal;
    private final Vec2 uv;
    private final Vec3 color;
    private final Vec3 tangent;
    private final Vec3 bitangent;
    private final int objectId;
    private final int faceId;

    public Vertex(Vec3 position, Vec3 normal) {
        this(position, normal, null);
    }

    public Vertex(Vec3 position, Vec3 normal, Vec2 uv) {
        this(position, normal, uv, null, null, null, -1, -1);
    }

    public Vertex(Vec3 position,
                  Vec3 normal,
                  Vec2 uv,
                  Vec3 color,
                  Vec3 tangent,
                  Vec3 bitangent,
                  int objectId,
                  int faceId) {
        this.position = position == null ? Vec3.ZERO : position;
        this.normal = normal == null ? Vec3.ZERO : normal;
        this.uv = uv;
        this.color = color;
        this.tangent = tangent;
        this.bitangent = bitangent;
        this.objectId = objectId;
        this.faceId = faceId;
    }

    // Tady řeším interpolaci vertexů.
    public static Vertex lerp(Vertex a, Vertex b, double t) {
        if (a == null && b == null) {
            return null;
        }
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }

        Vec3 pos = Vec3.lerp(a.position, b.position, t);
        Vec3 nrm = Vec3.lerp(a.normal, b.normal, t).normalize();
        Vec2 tex = lerpNullable(a.uv, b.uv, t);
        Vec3 col = lerpNullable(a.color, b.color, t);
        Vec3 tan = lerpNullable(a.tangent, b.tangent, t);
        Vec3 bit = lerpNullable(a.bitangent, b.bitangent, t);
        int objId = t < 0.5 ? a.objectId : b.objectId;
        int fId = t < 0.5 ? a.faceId : b.faceId;
        return new Vertex(pos, nrm, tex, col, tan, bit, objId, fId);
    }

    public Vec3 getPosition() {
        return position;
    }

    public Vec3 getNormal() {
        return normal;
    }

    public Vec2 getUv() {
        return uv;
    }

    public Vec3 getColor() {
        return color;
    }

    public Vec3 getTangent() {
        return tangent;
    }

    public Vec3 getBitangent() {
        return bitangent;
    }

    public int getObjectId() {
        return objectId;
    }

    public int getFaceId() {
        return faceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Vertex)) {
            return false;
        }
        Vertex other = (Vertex) o;
        return equalsVec3(position, other.position)
                && equalsVec3(normal, other.normal)
                && equalsVec2(uv, other.uv);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                hashVec3(position),
                hashVec3(normal),
                hashVec2(uv)
        );
    }

    @Override
    public String toString() {
        return "Vertex{"
                + "position=" + position
                + ", normal=" + normal
                + ", uv=" + uv
                + ", objectId=" + objectId
                + ", faceId=" + faceId
                + '}';
    }

    private static boolean equalsVec3(Vec3 a, Vec3 b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return same(a.x, b.x) && same(a.y, b.y) && same(a.z, b.z);
    }

    private static boolean equalsVec2(Vec2 a, Vec2 b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return same(a.x, b.x) && same(a.y, b.y);
    }

    private static int hashVec3(Vec3 v) {
        if (v == null) {
            return 0;
        }
        int h = 17;
        h = 31 * h + Double.hashCode(v.x);
        h = 31 * h + Double.hashCode(v.y);
        h = 31 * h + Double.hashCode(v.z);
        return h;
    }

    private static int hashVec2(Vec2 v) {
        if (v == null) {
            return 0;
        }
        int h = 17;
        h = 31 * h + Double.hashCode(v.x);
        h = 31 * h + Double.hashCode(v.y);
        return h;
    }

    private static boolean same(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    private static Vec2 lerpNullable(Vec2 a, Vec2 b, double t) {
        if (a == null && b == null) {
            return null;
        }
        if (a == null) {
            return t < 0.5 ? null : b;
        }
        if (b == null) {
            return t < 0.5 ? a : null;
        }
        return Vec2.lerp(a, b, t);
    }

    private static Vec3 lerpNullable(Vec3 a, Vec3 b, double t) {
        if (a == null && b == null) {
            return null;
        }
        if (a == null) {
            return t < 0.5 ? null : b;
        }
        if (b == null) {
            return t < 0.5 ? a : null;
        }
        return Vec3.lerp(a, b, t);
    }
}
