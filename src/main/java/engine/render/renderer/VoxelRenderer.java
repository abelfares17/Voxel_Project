package engine.render.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CCW;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glDepthFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFrontFace;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_COMPARE_R_TO_TEXTURE;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.render.MeshBuilder;
import engine.render.Shader;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.shadow.CascadeData;
import engine.time.SunData;
import engine.world.Chunk;

/**
 * Renderer responsible for rendering voxel chunks with lighting and CSM shadows.
 */
public class VoxelRenderer implements Renderer {

    private final Shader voxelShader;
    private final ShadowRenderer shadowRenderer;
    private final int atlasTextureId;

    // AO tuning
    private float aoMin = 0.5f;
    private float aoPower = 2.0f;
    private boolean debugAO = false;

    // Shadow debug mode:
    // 0=off, 1=shadow factor, 2=cascade colors, 3=world-cell hash,
    // 4=fog factor, 5=cascade solid, 6=shadow A/B diff, 7=lit before fog
    private int debugShadow = 0;
    private static final int DEBUG_MODE_COUNT = 8;

    // Cascade isolation (-1=normal, 0/1/2=force specific cascade)
    private int forceCascade = -1;

    // Bias isolation mode (0=normal, 1=constant, 2=zero, 3=doubled)
    private int biasMode = 0;
    private static final int BIAS_MODE_COUNT = 4;

    // Normal-offset shadow mode (0=off, 1=on)
    private int normalOffsetMode = 0;

    // Temporal jitter frame counter for shadow anti-aliasing
    private int frameIndex = 0;

    // Hemisphere ambient colors
    private final Vector3f skyAmbientColor = new Vector3f(0.6f, 0.7f, 0.9f);
    private final Vector3f groundAmbientColor = new Vector3f(0.4f, 0.35f, 0.3f);

    public VoxelRenderer(Shader voxelShader, ShadowRenderer shadowRenderer, int atlasTextureId) {
        this.voxelShader = voxelShader;
        this.shadowRenderer = shadowRenderer;
        this.atlasTextureId = atlasTextureId;
    }

    private void drainGLErrors() {
        while (glGetError() != GL_NO_ERROR) {
            // Silently drain errors
        }
    }

    @Override
    public void render(RenderContext context) {
        drainGLErrors();

        SunData sunData = context.getWorld().getTimeManager().getSunData();
        Vector3f sunDir = context.getSunDirLighting();
        float sunY = sunData.sunY();

        Vector3f sunColor = RenderConfig.getSunColor(sunY);

        Vector3f cameraPos = context.getCamera().getPosition();
        Matrix4f proj = context.getCamera().getProjectionMatrix();

        Matrix4f view;
        if (context.getCamera() instanceof PerspectiveCamera perspectiveCamera) {
            view = perspectiveCamera.getViewMatrix();
        } else {
            view = context.getCamera().getViewMatrix(cameraPos, 0, 0);
        }

        // Setup main pass state
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, context.getWindowWidth(), context.getWindowHeight());
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glFrontFace(GL_CCW);
        glCullFace(GL_BACK);
        glEnable(GL_CULL_FACE);
        glColorMask(true, true, true, true);

        Vector3f skyColor = context.getSkyColor();

        voxelShader.bind();
        voxelShader.setUniform("sunDir", sunDir);
        voxelShader.setUniform("sunColor", sunColor);
        voxelShader.setUniform("ambient", context.getWorld().getTimeManager().getAmbient());
        voxelShader.setUniform("projectionMatrix", proj);
        voxelShader.setUniform("viewMatrix", view);
        voxelShader.setUniform("cameraPos", cameraPos);
        voxelShader.setUniform("fogColor", skyColor);

        int renderDistance = context.getWorld().getRenderDistance();
        voxelShader.setUniform("fogStart", RenderConfig.getFogStart(renderDistance));
        voxelShader.setUniform("fogEnd", RenderConfig.getFogEnd(renderDistance));

        // AO uniforms
        voxelShader.setUniform("aoMin", aoMin);
        voxelShader.setUniform("aoPower", aoPower);
        voxelShader.setUniform("debugAO", 0 /*debugAO ? 1 : 0*/);

        // Hemisphere ambient lighting
        voxelShader.setUniform("skyAmbientColor", skyAmbientColor);
        voxelShader.setUniform("groundAmbientColor", groundAmbientColor);

        // Debug modes
        voxelShader.setUniform("debugShadow", 0/*debugShadow*/);
        voxelShader.setUniform("forceCascade", -1 /*forceCascade*/);
        voxelShader.setUniform("biasMode", biasMode);
        voxelShader.setUniform("normalOffsetMode", normalOffsetMode);

        // Temporal jitter
        voxelShader.setUniform("frameIndex", frameIndex);
        frameIndex = (frameIndex + 1) % 8;

        // CSM uniforms
        CascadeData cascadeData = shadowRenderer.getCascadeData();

        if (cascadeData != null) {
            voxelShader.setUniformMat4Array("lightSpaceMatrices", cascadeData.lightSpaceMatrices());
            voxelShader.setUniformFloatArray("cascadeSplits", cascadeData.splitDistances());
            voxelShader.setUniformFloatArray("cascadeTexelSizes", cascadeData.texelSizes());
            voxelShader.setUniform("cascadeCount", cascadeData.cascadeCount());
            voxelShader.setUniform("shadowMapInvSize", 1.0f / RenderConfig.CSM_SHADOW_MAP_SIZE);
        }

        // Leapfrog crossfade uniforms
        boolean leapfrogTransitioning = shadowRenderer.isLeapfrogTransitioning();
        float crossfadeProgress = shadowRenderer.getCrossfadeProgress();
        voxelShader.setUniform("leapfrogActive", 0 /*leapfrogTransitioning ? 1 : 0*/);
        voxelShader.setUniform("shadowCrossfade", crossfadeProgress);
        voxelShader.setUniform("blendMode", shadowRenderer.getBlendMode());

        if (leapfrogTransitioning) {
            CascadeData cascadeDataB = shadowRenderer.getSecondaryCascadeData();
            if (cascadeDataB != null) {
                voxelShader.setUniformMat4Array("lightSpaceMatricesB", cascadeDataB.lightSpaceMatrices());
                voxelShader.setUniformFloatArray("cascadeSplitsB", cascadeDataB.splitDistances());
                voxelShader.setUniformFloatArray("cascadeTexelSizesB", cascadeDataB.texelSizes());
            }
        } else if (cascadeData != null) {
            // Set secondary matrices to primary values to prevent undefined behavior
            voxelShader.setUniformMat4Array("lightSpaceMatricesB", cascadeData.lightSpaceMatrices());
            voxelShader.setUniformFloatArray("cascadeSplitsB", cascadeData.splitDistances());
            voxelShader.setUniformFloatArray("cascadeTexelSizesB", cascadeData.texelSizes());
        }

        // Bind texture atlas
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTextureId);
        voxelShader.setUniform("textureSampler", 0);

        // Master shadow switch (shader uses this to skip fetches without changing bindings)
        voxelShader.setUniform("shadowsEnabled", RenderConfig.SHADOWS_ENABLED ? 1 : 0);

        // Unified shadow atlas (A+B in one sampler2DArray — unit 1)
        voxelShader.setTextureArray("shadowAtlas", shadowRenderer.getShadowAtlas(), 1);

        // Atlas offsets: tell shader which half of the atlas is A and which is B
        voxelShader.setUniform("atlasOffsetA", shadowRenderer.getAtlasOffsetA());
        voxelShader.setUniform("atlasOffsetB", shadowRenderer.getAtlasOffsetB());

        // Bind GL_R32F color test array (micro-test A — texture unit 2)
        voxelShader.setTextureArray("colorTestArray", shadowRenderer.getColorTestArray(), 2);

        // Hardware shadow compare (micro-tests stages 14-15 only).
        // We temporarily set GL_COMPARE_REF_TO_TEXTURE on the atlas, bind it on unit 3
        // as shadowAtlasShadow, then restore GL_NONE after rendering.
        // This is safe: the shadow depth pass never reads the atlas.
        int atlasId = shadowRenderer.getShadowAtlas();
        glActiveTexture(GL_TEXTURE0 + 3);
        glBindTexture(GL_TEXTURE_2D_ARRAY, atlasId);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_R_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        voxelShader.setUniform("shadowAtlasShadow", 3);
        glActiveTexture(GL_TEXTURE0);

        // Upload pending meshes
        context.getWorld().getMeshManager().uploadMeshesIfAny();

        // Render chunks
        renderVisibleChunks(context);

        // Restore GL_NONE compare mode on the atlas (required for manual depth reads in other stages)
        glActiveTexture(GL_TEXTURE0 + 3);
        glBindTexture(GL_TEXTURE_2D_ARRAY, atlasId);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glActiveTexture(GL_TEXTURE0);

        voxelShader.unbind();
    }

    private void renderVisibleChunks(RenderContext context) {
        Vector3f camPos = context.getCamera().getPosition();
        int cx = (int) Math.floor(camPos.x / GameConfig.CHUNK_WIDTH);
        int cz = (int) Math.floor(camPos.z / GameConfig.CHUNK_DEPTH);

        int radius = context.getWorld().getRenderDistance();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = context.getWorld().getChunk(cx + dx, cz + dz);
                if (chunk == null) continue;

                Matrix4f model = new Matrix4f()
                        .translation(
                                chunk.getChunkX() * Chunk.WIDTH,
                                0,
                                chunk.getChunkZ() * Chunk.DEPTH
                        );

                voxelShader.setUniform("modelMatrix", model);
                MeshBuilder.renderChunk(chunk);
            }
        }
    }

    @Override
    public void cleanup() {
        voxelShader.cleanup();
        shadowRenderer.cleanup();
    }

    public ShadowRenderer getShadowRenderer() {
        return shadowRenderer;
    }

    public void toggleDebugAO() {
        this.debugAO = !this.debugAO;
    }

    public boolean isDebugAO() {
        return debugAO;
    }

    public void setAoMin(float aoMin) {
        this.aoMin = Math.max(0.0f, Math.min(1.0f, aoMin));
    }

    public float getAoMin() {
        return aoMin;
    }

    public void cycleShadowDebug() {
        this.debugShadow = (this.debugShadow + 1) % DEBUG_MODE_COUNT;
    }

    public int getDebugShadow() {
        return debugShadow;
    }

    public void cycleForceCascade() {
        this.forceCascade = (this.forceCascade + 2) % 4 - 1;
    }

    public int getForceCascade() {
        return forceCascade;
    }

    public void cycleBiasMode() {
        this.biasMode = (this.biasMode + 1) % BIAS_MODE_COUNT;
    }

    public int getBiasMode() {
        return biasMode;
    }

    public void toggleNormalOffset() {
        this.normalOffsetMode = (this.normalOffsetMode + 1) % 2;
    }

    public int getNormalOffsetMode() {
        return normalOffsetMode;
    }
}
