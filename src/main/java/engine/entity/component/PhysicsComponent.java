package engine.entity.component;

/**
 * Physics component holding velocity and physics state.
 * Extracted from Player to support all entities with physics.
 */
public class PhysicsComponent {
    private float vx, vy, vz;
    private boolean onGround;
    private boolean affectedByGravity;

    /**
     * Create a physics component with default values.
     */
    public PhysicsComponent() {
        this.vx = 0;
        this.vy = 0;
        this.vz = 0;
        this.onGround = false;
        this.affectedByGravity = true;
    }

    /**
     * Create a physics component.
     *
     * @param affectedByGravity Whether this entity is affected by gravity
     */
    public PhysicsComponent(boolean affectedByGravity) {
        this();
        this.affectedByGravity = affectedByGravity;
    }

    // Velocity getters
    public float getVelocityX() { return vx; }
    public float getVelocityY() { return vy; }
    public float getVelocityZ() { return vz; }

    // Velocity setters
    public void setVelocityX(float vx) { this.vx = vx; }
    public void setVelocityY(float vy) { this.vy = vy; }
    public void setVelocityZ(float vz) { this.vz = vz; }

    public void setVelocity(float vx, float vy, float vz) {
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }

    /**
     * Add to current velocity (for impulses).
     *
     * @param dvx Delta velocity X
     * @param dvy Delta velocity Y
     * @param dvz Delta velocity Z
     */
    public void addVelocity(float dvx, float dvy, float dvz) {
        this.vx += dvx;
        this.vy += dvy;
        this.vz += dvz;
    }

    /**
     * Reset all velocity to zero.
     */
    public void resetVelocity() {
        this.vx = 0;
        this.vy = 0;
        this.vz = 0;
    }

    // Ground state
    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }

    // Gravity flag
    public boolean isAffectedByGravity() { return affectedByGravity; }
    public void setAffectedByGravity(boolean affectedByGravity) {
        this.affectedByGravity = affectedByGravity;
    }
}
