package engine.render.ray;

import engine.math.Ray;
import engine.math.Vec3;

/**
 * Tady držím informace o průsečíku paprsku se scénou.
 * Naplňuju je v intersection rutinách a čtu je při shadingu.
 */
public class HitRecord {

    private double t;
    private Vec3 point;
    private Vec3 normal;
    private Vec3 uv;
    private int entityId;
    private int triangleIndex;
    private boolean frontFace;

    public HitRecord() {
        clear();
    }

    public void clear() {
        t = Double.POSITIVE_INFINITY;
        point = Vec3.ZERO;
        normal = Vec3.UP;
        uv = Vec3.ZERO;
        entityId = -1;
        triangleIndex = -1;
        frontFace = true;
    }

    public void set(double t, Vec3 point, Vec3 normal, boolean frontFace) {
        this.t = t;
        this.point = point == null ? Vec3.ZERO : point;
        this.normal = normal == null ? Vec3.UP : normal.normalize();
        this.frontFace = frontFace;
    }

    public void setFaceNormal(Ray ray, Vec3 outwardNormal) {
        Vec3 out = outwardNormal == null ? Vec3.UP : outwardNormal.normalize();
        frontFace = ray.getDirection().dot(out) < 0.0;
        normal = frontFace ? out : out.negate();
    }

    public double getT() {
        return t;
    }

    public Vec3 getPoint() {
        return point;
    }

    public Vec3 getNormal() {
        return normal;
    }

    public Vec3 getUv() {
        return uv;
    }

    public void setUv(Vec3 uv) {
        this.uv = uv == null ? Vec3.ZERO : uv;
    }

    public int getEntityId() {
        return entityId;
    }

    public void setEntityId(int entityId) {
        this.entityId = entityId;
    }

    public int getTriangleIndex() {
        return triangleIndex;
    }

    public void setTriangleIndex(int triangleIndex) {
        this.triangleIndex = triangleIndex;
    }

    public boolean isFrontFace() {
        return frontFace;
    }
}
