package engine.physics;

import engine.math.AABB;
import engine.math.Vec3;

/**
 * Defines abstraktní kolizní tvar.
 */
public abstract class Collider {

    protected Vec3 offset = Vec3.ZERO;

 /**
 * spočítá AABB ve světovém prostoru pro širokou fázi kolizního testu.
 *
 * @param worldPos pozici entity ve světovém prostoru
 * @return vrátí box zarovnaný podle os ve světovém prostoru
 */
    public abstract AABB getWorldAABB(Vec3 worldPos);

 /**
 * Performs úzkou fázi testu proti jinému koliznímu tvaru.
 *
 * @param posA pozici tohoto těla ve světovém prostoru
 * @param other druhý kolizní tvar
 * @param posB pozici druhého těla ve světovém prostoru
 * @param result výstupní kontakt kolize
 * @return vrátí true, když se kolizní tvary překrývají
 */
    public abstract boolean testCollision(Vec3 posA, Collider other, Vec3 posB, CollisionResult result);

    public Vec3 getOffset() {
        return offset;
    }

    public void setOffset(Vec3 offset) {
        this.offset = offset == null ? Vec3.ZERO : offset;
    }
}