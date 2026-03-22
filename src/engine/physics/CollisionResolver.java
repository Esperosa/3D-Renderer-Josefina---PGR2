package engine.physics;

import engine.math.Vec3;

import java.util.List;

/**
 * Handles nalezené kolize přes impulzní odezvu.
 */
public class CollisionResolver {

    private final double positionCorrectionFactor;
    private final double slop;

    public CollisionResolver() {
        this.positionCorrectionFactor = 0.8;
        this.slop = 0.01;
    }

 /**
 * vyřeší všechny kontakty kolizí pro aktuální snímek.
 *
 * @param contacts seznam detekovaných kolizí
 */
    public void resolve(List<CollisionResult> contacts) {
        for (CollisionResult contact : contacts) {
            RigidBody a = contact.getBodyA();
            RigidBody b = contact.getBodyB();
            if (a == null || b == null) {
                continue;
            }

            Vec3 normal = contact.getContactNormal().normalize();
            Vec3 relVelocity = b.getVelocity().sub(a.getVelocity());
            double velAlongNormal = relVelocity.dot(normal);
            if (velAlongNormal > 0.0) {
                continue;
            }

            double invMassA = a.getInverseMass();
            double invMassB = b.getInverseMass();
            double invMassSum = invMassA + invMassB;
            if (invMassSum <= 1e-12) {
                continue;
            }

            double e = Math.min(a.getRestitution(), b.getRestitution());
            double j = -(1.0 + e) * velAlongNormal / invMassSum;
            Vec3 impulse = normal.mul(j);

            a.setVelocity(a.getVelocity().sub(impulse.mul(invMassA)));
            b.setVelocity(b.getVelocity().add(impulse.mul(invMassB)));

            double penetration = Math.max(contact.getPenetrationDepth() - slop, 0.0);
            if (penetration <= 0.0) {
                continue;
            }

            Vec3 correction = normal.mul((penetration / invMassSum) * positionCorrectionFactor);
            a.setPosition(a.getPosition().sub(correction.mul(invMassA)));
            b.setPosition(b.getPosition().add(correction.mul(invMassB)));
        }
    }
}