package engine.entity;

import org.joml.Vector3f;

/**
 * Transform component holding position, rotation, and scale.
 * Extracted from Player to support all entities.
 */
public class Transform {
    private float x, y, z;
    private float yaw, pitch, roll;
    private float scaleX, scaleY, scaleZ;

    /**
     * Create a transform at a position with default rotation and scale.
     *
     * @param x X position
     * @param y Y position
     * @param z Z position
     */
    public Transform(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = 0;
        this.pitch = 0;
        this.roll = 0;
        this.scaleX = 1;
        this.scaleY = 1;
        this.scaleZ = 1;
    }

    // Position getters/setters
    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }

    public void setX(float x) { this.x = x; }
    public void setY(float y) { this.y = y; }
    public void setZ(float z) { this.z = z; }

    public void setPosition(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f getPosition() {
        return new Vector3f(x, y, z);
    }

    // Rotation getters/setters (in degrees)
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getRoll() { return roll; }

    public void setYaw(float yaw) { this.yaw = yaw; }
    public void setPitch(float pitch) { this.pitch = pitch; }
    public void setRoll(float roll) { this.roll = roll; }

    // Scale getters/setters
    public float getScaleX() { return scaleX; }
    public float getScaleY() { return scaleY; }
    public float getScaleZ() { return scaleZ; }

    public void setScale(float scale) {
        this.scaleX = scale;
        this.scaleY = scale;
        this.scaleZ = scale;
    }

    public void setScale(float scaleX, float scaleY, float scaleZ) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
    }
}
