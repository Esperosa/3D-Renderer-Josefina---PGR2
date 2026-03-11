package engine.physics;

import engine.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Tady detekuju kolizní páry mezi fyzikálními těly.
 */
public class CollisionDetector {

    private final List<CollisionResult> contacts;

    public CollisionDetector() {
        this.contacts = new ArrayList<>();
    }

    /**
     * Tady spustím detekci kolizí pro všechny dvojice těles.
     *
     * @param bodies sem předám seznam aktivních rigid body
     * @return vrátím seznam nalezených kontaktů
     */
    public List<CollisionResult> detectCollisions(List<RigidBody> bodies) {
        contacts.clear();
        for (int i = 0; i < bodies.size(); i++) {
            RigidBody a = bodies.get(i);
            if (a.getCollider() == null) {
                continue;
            }
            for (int j = i + 1; j < bodies.size(); j++) {
                RigidBody b = bodies.get(j);
                if (b.getCollider() == null) {
                    continue;
                }
                if (a.getInverseMass() <= 0.0 && b.getInverseMass() <= 0.0) {
                    continue;
                }

                Vec3 posA = a.getPosition();
                Vec3 posB = b.getPosition();

                if (!a.getCollider().getWorldAABB(posA).intersects(b.getCollider().getWorldAABB(posB))) {
                    continue;
                }

                CollisionResult result = new CollisionResult();
                if (a.getCollider().testCollision(posA, b.getCollider(), posB, result)) {
                    result.set(
                            result.getContactNormal(),
                            result.getPenetrationDepth(),
                            result.getContactPoint(),
                            a,
                            b
                    );
                    contacts.add(result);
                }
            }
        }
        return contacts;
    }
}
