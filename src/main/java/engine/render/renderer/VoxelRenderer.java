package engine.render.renderer;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttrib1f;
import static org.lwjgl.opengl.GL20.glVertexAttrib2f;
import static org.lwjgl.opengl.GL20.glVertexAttrib3f;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.*;

import engine.core.config.GameConfig;
import engine.core.config.RenderConfig;
import engine.physics.RaycastHit;
import engine.render.MeshBuilder;
import engine.render.ShadowMesh;
import engine.render.Shader;
import engine.render.camera.Camera;
import engine.render.camera.PerspectiveCamera;
import engine.render.core.RenderContext;
import engine.render.shadow.CascadeData;
import engine.world.Chunk;

/**
 * Renderer responsible for rendering voxel chunks with lighting and CSM shadows.
 */
public class VoxelRenderer implements Renderer {

    private final Shader voxelShader;
    private final ShadowRenderer shadowRenderer;
    private final int atlasTextureId;
    private RaycastHit selectedBlockHit;
    private int outlineVao = 0;
    private int outlineVbo = 0;

    public int getAtlasTextureId() { return atlasTextureId; }
    public void setSelectedBlockHit(RaycastHit hit) { this.selectedBlockHit = hit; }
    public RaycastHit getSelectedBlockHit() { return selectedBlockHit; }

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
    private int normalOffsetMode = 1;
    private boolean wireframe = false;
    private boolean shadowMeshWireframe = false;
    private boolean shadowFade = RenderConfig.SHADOW_FADE;
    public boolean isShadowFade() { return shadowFade; }
    public void toggleShadowFade() { shadowFade=!shadowFade; }
    public boolean isWireframe() { return wireframe; }
    public boolean isShadowMeshWireframe() { return shadowMeshWireframe; }
    public void toggleWireframe() {
        wireframe = !wireframe;
        if (!wireframe) shadowMeshWireframe = false;
    }
    public void toggleShadowMeshWireframe() {
        shadowMeshWireframe = !shadowMeshWireframe;
        wireframe = shadowMeshWireframe;
    }
    private final float[] shadowDepthWorldSpans = new float[RenderConfig.CSM_CASCADE_COUNT];

    private final Matrix4f chunkModelMatrix = new Matrix4f();
    private final Matrix4f projViewMatrix = new Matrix4f();
    private final FrustumIntersection cameraFrustum = new FrustumIntersection();

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

    public static void checkGLErrors(String location) {
    checkGLError(location);
}

    public static void checkGLError(String location) {
    int error;
    while ((error = glGetError()) != GL_NO_ERROR) {
        String errorName = switch (error) {
            case GL_INVALID_ENUM -> "GL_INVALID_ENUM";
            case GL_INVALID_VALUE -> "GL_INVALID_VALUE";
            case GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL_STACK_OVERFLOW -> "GL_STACK_OVERFLOW";
            case GL_STACK_UNDERFLOW -> "GL_STACK_UNDERFLOW";
            case GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
            case GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            default -> "0x" + Integer.toHexString(error);
        };
        System.err.println("[GL ERROR] " + errorName + " at: " + location);
    }
}

    @Override
    public void render(RenderContext context) {
        render(context, RenderConfig.TIGHT_CAMERA_BOUNDS, true);
    }

    /** Diagnostic replay can keep the same meshes and temporal sample as the preceding draw. */
    public void render(RenderContext context, boolean tightBounds, boolean advanceFrame) {
        render(context, tightBounds, advanceFrame, RenderConfig.SKIP_UNLIT_SHADOWS);
    }

    /** Same-frame shader comparison override; no global state toggle. */
    public void render(RenderContext context, boolean tightBounds, boolean advanceFrame, boolean skipUnlit) {
        checkGLErrors("VoxelRender.render() entry point");

        var celestial = context.getCelestialState();

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
        voxelShader.setUniform("lightDirection", celestial.lightDirection());
        voxelShader.setUniform("lightColor", celestial.lightColor());
        voxelShader.setUniform("ambient", celestial.ambient());
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
        voxelShader.setUniform("debugAO", debugAO ? 1 : 0);

        // Hemisphere ambient lighting
        voxelShader.setUniform("skyAmbientColor", skyAmbientColor);
        voxelShader.setUniform("groundAmbientColor", groundAmbientColor);

        // Debug modes
        voxelShader.setUniform("debugShadow", debugShadow);
        voxelShader.setUniform("forceCascade", forceCascade);
        voxelShader.setUniformFloatArray("shadowDepthBiasScales", shadowRenderer.getDepthBiasScales());
        voxelShader.setUniform("biasMode", biasMode);
        voxelShader.setUniform("normalOffsetMode", normalOffsetMode);
        voxelShader.setUniform("greedyVisual", RenderConfig.GREEDY_VISUAL ? 1 : 0);
        voxelShader.setUniform("shimmerFix", RenderConfig.SHIMMER_FIX ? 1 : 0);
        voxelShader.setUniform("naturalTextures",
                (RenderConfig.NATURAL_TEXTURES && !"legacy".equalsIgnoreCase(RenderConfig.TEXTURE_PACK)) ? 1 : 0);
        var coverage=context.getShadowCoverage();
        voxelShader.setUniform("shadowFadeEnabled",coverage.fadeEnabled()?1:0);
        voxelShader.setUniform("shadowFadeStart",coverage.fadeStart());
        voxelShader.setUniform("shadowFadeEnd",coverage.fadeEnd());
        voxelShader.setUniform("skipUnlitShadows", skipUnlit ? 1 : 0);

        // Temporal jitter
        voxelShader.setUniform("frameIndex", advanceFrame ? frameIndex : (frameIndex + 7) % 8);

        // CSM uniforms
        CascadeData cascadeData = shadowRenderer.getCascadeData();

        voxelShader.setUniform("cascadeCount", cascadeData == null ? 0 : cascadeData.cascadeCount());
        if (cascadeData != null) {
            for(int i=0;i<cascadeData.cascadeCount();i++)
                shadowDepthWorldSpans[i]=engine.render.shadow.ShadowDistance.depthSpan(cascadeData.lightSpaceMatrices()[i]);
            voxelShader.setUniformFloatArray("shadowDepthWorldSpans",shadowDepthWorldSpans);
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
        voxelShader.setUniform("shadowsEnabled", RenderConfig.SHADOWS_ENABLED
                && shadowRenderer.hasCurrentShadowMap(context) ? 1 : 0);

        // One direct sun/moon atlas (three cascades, sampler2DArray on unit 1).
        voxelShader.setTextureArray("shadowAtlas", shadowRenderer.getShadowAtlas(), 1);

        // Legacy interface: A and B both reference the same three direct cascades.
        voxelShader.setUniform("atlasOffsetA", shadowRenderer.getAtlasOffsetA());
        voxelShader.setUniform("atlasOffsetB", shadowRenderer.getAtlasOffsetB());

        // Bind GL_R32F color test array if allocated (micro-test A — texture unit 2)
        if (shadowRenderer.getColorTestArray() != 0) {
            voxelShader.setTextureArray("colorTestArray", shadowRenderer.getColorTestArray(), 2);
        }

        // Upload pending meshes
        // Mesh uploads happen before the shadow pass in Game.render, not between passes.

        // Render chunks
        if (wireframe) {
            // Pass 1: Solid shaded clay fill with depth offset so underground caves are 100% occluded
            voxelShader.setUniform("wireframePass", 1);
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(1.0f, 1.0f);
            renderVisibleChunks(context, tightBounds, false);
            glDisable(GL_POLYGON_OFFSET_FILL);

            // Pass 2: Crisp wireframe edge overlay on visible surfaces only
            voxelShader.setUniform("wireframePass", shadowMeshWireframe ? 3 : 2);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(false);
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            try {
                renderVisibleChunks(context, tightBounds, shadowMeshWireframe);
            } finally {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
                glDepthMask(true);
                glDepthFunc(GL_LESS);
                voxelShader.setUniform("wireframePass", 0);
            }
        } else {
            voxelShader.setUniform("wireframePass", 0);
            renderVisibleChunks(context, tightBounds, false);
        }

        if (advanceFrame && selectedBlockHit != null) {
            renderSelectedBlockOutline();
        }
        if (advanceFrame) frameIndex = (frameIndex + 1) % 8;

        voxelShader.unbind();
    }

    private int drawCalls;
    private long drawnVertices;

    public int getDrawCalls() { return drawCalls; }
    public long getDrawnVertices() { return drawnVertices; }

    private void renderVisibleChunks(RenderContext context, boolean tightBounds, boolean useShadowMesh) {
        drawCalls = 0;
        drawnVertices = 0;
        Camera camera = context.getCamera();
        Vector3f camPos = camera.getPosition();
        Matrix4f viewMatrix = (camera instanceof PerspectiveCamera pc) ? pc.getViewMatrix() : camera.getViewMatrix(camPos, 0, 0);
        Matrix4f projMatrix = camera.getProjectionMatrix();

        // Compute 3D camera frustum
        projViewMatrix.set(projMatrix).mul(viewMatrix);
        cameraFrustum.set(projViewMatrix);

        int cx = (int) Math.floor(camPos.x / GameConfig.CHUNK_WIDTH);
        int cz = (int) Math.floor(camPos.z / GameConfig.CHUNK_DEPTH);

        int radius = context.getWorld().getRenderDistance();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int targetChunkX = cx + dx;
                int targetChunkZ = cz + dz;

                float minX = targetChunkX * Chunk.WIDTH;
                float minY = -32.0f;
                float minZ = targetChunkZ * Chunk.DEPTH;
                float maxX = minX + Chunk.WIDTH;
                float maxY = 288.0f;
                float maxZ = minZ + Chunk.DEPTH;

                // Preserve the existing broad-phase test and legacy fallback.
                if (!cameraFrustum.testAab(minX, minY, minZ, maxX, maxY, maxZ)) {
                    continue;
                }

                Chunk chunk = context.getWorld().getChunk(targetChunkX, targetChunkZ);
                if (chunk == null || !chunk.isReady()) continue;
                if (tightBounds && chunk.getMeshBounds() != null
                        && !chunk.getMeshBounds().intersects(cameraFrustum, minX, minZ)) continue;

                chunkModelMatrix.translation(minX, 0, minZ);
                voxelShader.setUniform("modelMatrix", chunkModelMatrix);
                if (useShadowMesh && chunk.getShadowMesh() != null && chunk.getShadowMesh().count > 0) {
                    glVertexAttrib2f(1, 0.0f, 0.0f);
                    glVertexAttrib3f(2, 0.0f, 1.0f, 0.0f);
                    glVertexAttrib1f(3, 1.0f);
                    ShadowMesh sm = chunk.getShadowMesh();
                    sm.draw();
                    glBindVertexArray(0);
                    drawCalls++;
                    drawnVertices += sm.count;
                } else {
                    MeshBuilder.renderChunk(chunk);
                    if (chunk.getVao() > 0 && chunk.getVertexCount() > 0) {
                        drawCalls++;
                        drawnVertices += chunk.getDrawCount();
                    }
                }
            }
        }
    }

    private void initOutlineMeshIfNeeded() {
        if (outlineVao != 0) return;
        float min = -0.0025f;
        float max = 1.0025f;
        float[] v = {
            // Bottom rectangle
            min, min, min,  max, min, min,
            max, min, min,  max, min, max,
            max, min, max,  min, min, max,
            min, min, max,  min, min, min,
            // Top rectangle
            min, max, min,  max, max, min,
            max, max, min,  max, max, max,
            max, max, max,  min, max, max,
            min, max, max,  min, max, min,
            // Vertical pillars
            min, min, min,  min, max, min,
            max, min, min,  max, max, min,
            max, min, max,  max, max, max,
            min, min, max,  min, max, max
        };
        outlineVao = glGenVertexArrays();
        outlineVbo = glGenBuffers();
        glBindVertexArray(outlineVao);
        glBindBuffer(GL_ARRAY_BUFFER, outlineVbo);
        glBufferData(GL_ARRAY_BUFFER, v, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void renderSelectedBlockOutline() {
        if (selectedBlockHit == null) return;
        initOutlineMeshIfNeeded();

        chunkModelMatrix.translation(
                selectedBlockHit.blockX(),
                selectedBlockHit.blockY(),
                selectedBlockHit.blockZ()
        );
        voxelShader.setUniform("modelMatrix", chunkModelMatrix);
        voxelShader.setUniform("wireframePass", 4);

        glDepthFunc(GL_LEQUAL);
        glBindVertexArray(outlineVao);
        glVertexAttrib2f(1, 0.0f, 0.0f);
        glVertexAttrib3f(2, 0.0f, 1.0f, 0.0f);
        glVertexAttrib1f(3, 1.0f);
        glDrawArrays(GL_LINES, 0, 24);
        glBindVertexArray(0);
        glDepthFunc(GL_LESS);
        voxelShader.setUniform("wireframePass", 0);
    }

    @Override
    public void cleanup() {
        if (outlineVao != 0) {
            glDeleteVertexArrays(outlineVao);
            outlineVao = 0;
        }
        if (outlineVbo != 0) {
            glDeleteBuffers(outlineVbo);
            outlineVbo = 0;
        }
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
