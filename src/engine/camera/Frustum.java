package engine.camera;

import engine.math.AABB;
import engine.math.BoundingSphere;
import engine.math.Mat4;
import engine.math.Plane;
import engine.math.Vec3;

/**
 * Tady držím šestirovinný view frustum vytažený z view-projection matice.
 */
public class Frustum {

    private static final int LEFT = 0;
    private static final int RIGHT = 1;
    private static final int BOTTOM = 2;
    private static final int TOP = 3;
    private static final int NEAR = 4;
    private static final int FAR = 5;

    private final Plane[] planes = new Plane[6];

    public Frustum() {
        for (int i = 0; i < planes.length; i++) {
            planes[i] = new Plane(new Vec3(0.0, 1.0, 0.0), 0.0);
        }
    }

    /**
     * Tady vytáhnu roviny frusta z kombinované view-projection matice.
     *
     * @param vp sem předám kombinovanou view-projection matici
     */
    public void extractFromMatrix(Mat4 vp) {
        if (vp == null) {
            return;
        }
        // Tady skládám levou rovinu jako row3 + row0.
        planes[LEFT] = planeFromRows(vp, 3, 0, 1.0);
        // Tady skládám pravou rovinu jako row3 - row0.
        planes[RIGHT] = planeFromRows(vp, 3, 0, -1.0);
        // Tady skládám spodní rovinu jako row3 + row1.
        planes[BOTTOM] = planeFromRows(vp, 3, 1, 1.0);
        // Tady skládám horní rovinu jako row3 - row1.
        planes[TOP] = planeFromRows(vp, 3, 1, -1.0);
        // Tady skládám near rovinu jako row3 + row2.
        planes[NEAR] = planeFromRows(vp, 3, 2, 1.0);
        // Tady skládám far rovinu jako row3 - row2.
        planes[FAR] = planeFromRows(vp, 3, 2, -1.0);
    }

    /**
     * Tady otestuju AABB proti frustu.
     *
     * @param aabb sem předám box zarovnaný podle os ve světovém prostoru
     * @return vrátím true, když box aspoň částečně leží ve frustu
     */
    public boolean intersects(AABB aabb) {
        if (aabb == null) {
            return true;
        }
        Vec3 min = aabb.getMin();
        Vec3 max = aabb.getMax();

        for (Plane plane : planes) {
            if (plane == null) {
                continue;
            }
            Vec3 n = plane.getNormal();
            Vec3 positive = new Vec3(
                    n.x >= 0.0 ? max.x : min.x,
                    n.y >= 0.0 ? max.y : min.y,
                    n.z >= 0.0 ? max.z : min.z
            );
            if (plane.distanceTo(positive) < 0.0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tady otestuju ohraničující kouli proti frustu.
     *
     * @param sphere sem předám ohraničující kouli ve světovém prostoru
     * @return vrátím true, když koule aspoň částečně leží ve frustu
     */
    public boolean intersects(BoundingSphere sphere) {
        if (sphere == null) {
            return true;
        }
        for (Plane plane : planes) {
            if (plane == null) {
                continue;
            }
            if (plane.distanceTo(sphere.getCenter()) < -sphere.getRadius()) {
                return false;
            }
        }
        return true;
    }

    private Plane planeFromRows(Mat4 m, int rowA, int rowB, double signB) {
        double a = m.get(rowA, 0) + signB * m.get(rowB, 0);
        double b = m.get(rowA, 1) + signB * m.get(rowB, 1);
        double c = m.get(rowA, 2) + signB * m.get(rowB, 2);
        double d = m.get(rowA, 3) + signB * m.get(rowB, 3);
        return new Plane(new Vec3(a, b, c), d);
    }
}
