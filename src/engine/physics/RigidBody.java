package engine.physics;

import engine.math.Vec3;
import engine.scene.Entity;

/**
 * Tady držím fyzikální tělo navázané na scénovou entitu.
 */
public class RigidBody {

    /**
     * Tady rozliším typ fyzikálního těla.
     */
    public enum BodyType {
        /** Tady nechám tělo ovlivňovat silami i kolizemi. */
        DYNAMIC,
        /** Tady tělo posouvám kódem, ale kolize pořád řeším. */
        KINEMATIC,
        /** Tady tělo nikdy nehýbu a beru ho jako nekonečně těžké. */
        STATIC
    }

    private final Entity entity;
    private final BodyType type;
    private final double mass;
    private final double inverseMass;
    private Vec3 velocity;
    private Vec3 acceleration;
    private Vec3 force;
    private double restitution;
    private double friction;
    private Collider collider;
    private boolean sleeping;

    public RigidBody(Entity entity, BodyType type, double mass) {
        this.entity = entity;
        this.type = type == null ? BodyType.DYNAMIC : type;
        if (this.type == BodyType.STATIC) {
            this.mass = Double.POSITIVE_INFINITY;
            this.inverseMass = 0.0;
        } else {
            double safeMass = Math.max(1e-6, mass);
            this.mass = safeMass;
            this.inverseMass = 1.0 / safeMass;
        }
        this.velocity = Vec3.ZERO;
        this.acceleration = Vec3.ZERO;
        this.force = Vec3.ZERO;
        this.restitution = 0.3;
        this.friction = 0.5;
        this.sleeping = false;
        if (entity != null) {
            entity.setRigidBody(this);
        }
    }

    // Tady pracuju se silami a impulzy.
    public void applyForce(Vec3 force) {
        this.force = this.force.add(force);
        sleeping = false;
    }

    public void applyImpulse(Vec3 impulse) {
        if (inverseMass <= 0.0) {
            return;
        }
        this.velocity = this.velocity.add(impulse.mul(inverseMass));
        sleeping = false;
    }

    public void clearForces() {
        this.force = Vec3.ZERO;
    }

    // Tady provádím integraci pohybu.
    public void integrate(double dt, Vec3 gravity) {
        if (type == BodyType.STATIC || sleeping || entity == null) {
            clearForces();
            return;
        }

        this.acceleration = force.mul(inverseMass).add(gravity);
        this.velocity = velocity.add(acceleration.mul(dt));
        Vec3 pos = entity.getTransform().getPosition().add(velocity.mul(dt));
        entity.getTransform().setPosition(pos);
        clearForces();
    }

    // Tady držím přístupové metody.
    public Entity getEntity() {
        return entity;
    }

    public BodyType getType() {
        return type;
    }

    public double getMass() {
        return mass;
    }

    public double getInverseMass() {
        return inverseMass;
    }

    public Vec3 getVelocity() {
        return velocity;
    }

    public void setVelocity(Vec3 v) {
        this.velocity = v == null ? Vec3.ZERO : v;
    }

    public Collider getCollider() {
        return collider;
    }

    public void setCollider(Collider c) {
        this.collider = c;
    }

    public double getRestitution() {
        return restitution;
    }

    public void setRestitution(double r) {
        this.restitution = Math.max(0.0, Math.min(1.0, r));
    }

    public double getFriction() {
        return friction;
    }

    public void setFriction(double friction) {
        this.friction = Math.max(0.0, friction);
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public void setSleeping(boolean sleeping) {
        this.sleeping = sleeping;
    }

    public Vec3 getPosition() {
        if (entity == null) {
            return Vec3.ZERO;
        }
        return entity.getTransform().getPosition();
    }

    public void setPosition(Vec3 position) {
        if (entity != null) {
            entity.getTransform().setPosition(position);
        }
    }
}
