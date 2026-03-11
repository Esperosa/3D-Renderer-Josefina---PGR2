package engine.physics;

import engine.math.AABB;
import engine.math.Vec3;

/**
 * Tady definuju abstraktní kolizní tvar.
 */
public abstract class Collider {

    protected Vec3 offset = Vec3.ZERO;

    /**
     * Tady spočítám AABB ve světovém prostoru pro širokou fázi kolizního testu.
     *
     * @param worldPos sem předám pozici entity ve světovém prostoru
     * @return vrátím box zarovnaný podle os ve světovém prostoru
     */
    public abstract AABB getWorldAABB(Vec3 worldPos);

    /**
     * Tady provedu úzkou fázi testu proti jinému koliznímu tvaru.
     *
     * @param posA sem předám pozici tohoto těla ve světovém prostoru
     * @param other sem předám druhý kolizní tvar
     * @param posB sem předám pozici druhého těla ve světovém prostoru
     * @param result sem předám výstupní kontakt kolize
     * @return vrátím true, když se kolizní tvary překrývají
     */
    public abstract boolean testCollision(Vec3 posA, Collider other, Vec3 posB, CollisionResult result);

    public Vec3 getOffset() {
        return offset;
    }

    public void setOffset(Vec3 offset) {
        this.offset = offset == null ? Vec3.ZERO : offset;
    }
}
