package engine.render.shadow;

import engine.render.MeshBounds;
import org.joml.Matrix4f;

/** Extends an orthographic receiver projection toward the light, never its XY footprint.
 * AABB projection is conservative: false positives cost work, false negatives lose shadows.
 */
public final class ShadowCasterVolume {
    private final Matrix4f receiver;
    private float near = -1f;
    public ShadowCasterVolume(Matrix4f receiver) { this.receiver = new Matrix4f(receiver); }

    public void include(MeshBounds bounds, float originX, float originZ) {
        float cx = originX + (bounds.minX()+bounds.maxX())*.5f;
        float cy = (bounds.minY()+bounds.maxY())*.5f;
        float cz = originZ + (bounds.minZ()+bounds.maxZ())*.5f;
        float ex = (bounds.maxX()-bounds.minX())*.5f + MeshBounds.CULLING_PADDING;
        float ey = (bounds.maxY()-bounds.minY())*.5f + MeshBounds.CULLING_PADDING;
        float ez = (bounds.maxZ()-bounds.minZ())*.5f + MeshBounds.CULLING_PADDING;
        float x = receiver.m00()*cx+receiver.m10()*cy+receiver.m20()*cz+receiver.m30();
        float y = receiver.m01()*cx+receiver.m11()*cy+receiver.m21()*cz+receiver.m31();
        float z = receiver.m02()*cx+receiver.m12()*cy+receiver.m22()*cz+receiver.m32();
        float rx = Math.abs(receiver.m00())*ex+Math.abs(receiver.m10())*ey+Math.abs(receiver.m20())*ez;
        float ry = Math.abs(receiver.m01())*ex+Math.abs(receiver.m11())*ey+Math.abs(receiver.m21())*ez;
        float rz = Math.abs(receiver.m02())*ex+Math.abs(receiver.m12())*ey+Math.abs(receiver.m22())*ez;
        if (x+rx < -1 || x-rx > 1 || y+ry < -1 || y-ry > 1 || z-rz > 1) return;
        near = Math.min(near, z-rz);
    }

    /** Returns old-depth-span/new-depth-span to preserve the existing world-space bias. */
    public float apply(Matrix4f destination) {
        float scale = 2f/(1f-near);
        float offset = -(1f+near)/(1f-near);
        destination.set(receiver);
        destination.m02(receiver.m02()*scale);
        destination.m12(receiver.m12()*scale);
        destination.m22(receiver.m22()*scale);
        destination.m32(receiver.m32()*scale+offset);
        return scale;
    }
}
