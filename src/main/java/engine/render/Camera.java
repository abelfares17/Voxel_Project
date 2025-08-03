package engine.render;

import org.joml.Matrix4f;

public class Camera {
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix       = new Matrix4f();

    // On stocke ces paramètres pour pouvoir les réappliquer
    private float fov, aspect, near, far;

    public Camera(float fov, float aspect, float near, float far) {
        this.fov    = fov;
        this.aspect = aspect;
        this.near   = near;
        this.far    = far;
        updateProjection();
    }

    private void updateProjection() {
        projectionMatrix.identity();
        projectionMatrix.setPerspective(
                (float)Math.toRadians(fov),
                aspect,
                near,
                far
        );
    }

    /** Permet de mettre à jour l’aspect ratio à la volée */
    public void setAspect(float newAspect) {
        this.aspect = newAspect;
        updateProjection();
    }

    public float getAspect() {
        return aspect;
    }

    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    public Matrix4f getViewMatrix(
            org.joml.Vector3f position,
            float yaw,
            float pitch
    ) {
        viewMatrix.identity()
                .rotate((float)Math.toRadians(pitch), new org.joml.Vector3f(1,0,0))
                .rotate((float)Math.toRadians(yaw),   new org.joml.Vector3f(0,1,0))
                .translate(-position.x, -position.y, -position.z);
        return viewMatrix;
    }
}