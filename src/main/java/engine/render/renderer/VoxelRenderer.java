package engine.render.renderer;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.render.MeshBuilder;
import engine.render.Shader;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.time.SunData;
import engine.world.Chunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;

/**
 * Renderer responsible for rendering voxel chunks with lighting and shadows.
 */
public class VoxelRenderer implements Renderer {

    private final Shader voxelShader;
    private final ShadowRenderer shadowRenderer;
    private final int atlasTextureId;

    /**
     * Create a new voxel renderer.
     *
     * @param voxelShader     Shader for voxel rendering
     * @param shadowRenderer  Shadow renderer for shadow mapping
     * @param atlasTextureId  Texture atlas ID
     */
    public VoxelRenderer(Shader voxelShader, ShadowRenderer shadowRenderer, int atlasTextureId) {
        this.voxelShader = voxelShader;
        this.shadowRenderer = shadowRenderer;
        this.atlasTextureId = atlasTextureId;
    }

    @Override
    public void render(RenderContext context) {
        // Get sun data
        SunData sunData = context.getWorld().getTimeManager().getSunData();
        Vector3f sunDir = sunData.sunDir();
        float sunY = sunData.sunY();

        // Calculate sun color
        Vector3f sunColor = RenderConfig.getSunColor(sunY);

        // Get camera position and matrices
        Vector3f cameraPos = context.getCamera().getPosition();
        Matrix4f proj = context.getCamera().getProjectionMatrix();

        // Get view matrix with player's rotation
        Matrix4f view;
        if (context.getCamera() instanceof PerspectiveCamera perspectiveCamera) {
            view = perspectiveCamera.getViewMatrix(); // Uses player's yaw and pitch
        } else {
            view = context.getCamera().getViewMatrix(cameraPos, 0, 0);
        }

        // Render shadow map first
        shadowRenderer.render(context);

        // Calculate sky color for fog
        float timeOfDay = context.getWorld().getTimeManager().getTimeOfDay();
        Vector3f skyColor = RenderConfig.getSkyColor(timeOfDay);

        // Bind voxel shader and set uniforms
        voxelShader.bind();
        voxelShader.setUniform("sunDir", sunDir);
        voxelShader.setUniform("sunColor", sunColor);
        voxelShader.setUniform("ambient", context.getWorld().getTimeManager().getAmbient());
        voxelShader.setUniform("projectionMatrix", proj);
        voxelShader.setUniform("viewMatrix", view);
        voxelShader.setUniform("lightSpaceMatrix", shadowRenderer.getLightSpaceMatrix());
        voxelShader.setUniform("cameraPos", cameraPos);
        voxelShader.setUniform("fogColor", skyColor);

        // Set fog distances based on render distance
        int renderDistance = context.getWorld().getRenderDistance();
        voxelShader.setUniform("fogStart", RenderConfig.getFogStart(renderDistance));
        voxelShader.setUniform("fogEnd", RenderConfig.getFogEnd(renderDistance));

        // Bind texture atlas
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTextureId);
        voxelShader.setUniform("textureSampler", 0);

        // Bind shadow map
        voxelShader.setTexture("shadowMap", shadowRenderer.getDepthTexture(), 1);

        // Upload any pending meshes from worker threads
        context.getWorld().getMeshManager().uploadMeshesIfAny();

        // Render all visible chunks
        renderVisibleChunks(context);

        voxelShader.unbind();
    }

    /**
     * Render all chunks within render distance.
     *
     * @param context Render context
     */
    private void renderVisibleChunks(RenderContext context) {
        // Get player chunk position (assuming player is at camera position)
        Vector3f camPos = context.getCamera().getPosition();
        int cx = (int) Math.floor(camPos.x / GameConfig.CHUNK_WIDTH);
        int cz = (int) Math.floor(camPos.z / GameConfig.CHUNK_DEPTH);

        int radius = context.getWorld().getRenderDistance();

        // Iterate through chunks in render distance
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = context.getWorld().getChunk(cx + dx, cz + dz);
                if (chunk == null) continue;

                // Calculate model matrix (translation to chunk position)
                Matrix4f model = new Matrix4f()
                        .translation(
                                chunk.getChunkX() * Chunk.WIDTH,
                                0,
                                chunk.getChunkZ() * Chunk.DEPTH
                        );

                // Set model matrix uniform
                voxelShader.setUniform("modelMatrix", model);

                // Render chunk mesh
                MeshBuilder.renderChunk(chunk);
            }
        }
    }

    @Override
    public void cleanup() {
        voxelShader.cleanup();
        shadowRenderer.cleanup();
    }

    /**
     * Get the shadow renderer used by this voxel renderer.
     *
     * @return Shadow renderer instance
     */
    public ShadowRenderer getShadowRenderer() {
        return shadowRenderer;
    }
}
