package engine.ui.component;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.*;

import engine.entity.Player;
import engine.input.Input;
import engine.render.Shader;
import engine.render.TextRenderer;
import engine.render.TextureCoords;
import engine.world.Block;
import engine.world.BlockRegistry;

/**
 * Single-draw-call batched Minecraft-style 9-slot Hotbar & Crosshair UI component:
 * - Main beveled stone/slate bar at the bottom center of the screen
 * - 9 recessed slots with 3D isometric block icons (showing top face + 2 shaded lateral faces)
 * - Thick, protruding square Slot Selector frame around the active slot
 * - Center crosshair
 * - English block name banner above the bar
 *
 * All geometry (Crosshair + Bar + 9 Slots + 9 Isometric 3D Cubes + Slot Selector) is cached
 * in a static GPU VBO and only rebuilt when (windowWidth, windowHeight, selectedSlot) changes,
 * rendering every frame in 1 single glDrawArrays call with zero CPU-GPU stalls.
 */
public class Hotbar implements UIComponent {

    private static final int FLOATS_PER_VERTEX = 9; // x, y, u, v, r, g, b, a, texMode

    private final Player player;
    private final TextRenderer textRenderer;
    private final int atlasTextureId;
    private final Shader hotbarShader;

    private int vao;
    private int vbo;
    private int cachedVertexCount = 0;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private int cachedSelectedSlot = -1;

    private float[] buildBuffer = new float[8192];
    private int floatCursor = 0;

    private float x;
    private float y;
    private float width;
    private float height;
    private boolean visible = true;

    private static final float BASE_SLOT_SIZE = 58.0f;
    private static final float BASE_SLOT_GAP = 4.0f;
    private static final float BASE_BAR_PADDING = 6.0f;

    public Hotbar(Player player, TextRenderer textRenderer, int atlasTextureId) {
        this.player = player;
        this.textRenderer = textRenderer;
        this.atlasTextureId = atlasTextureId;
        this.hotbarShader = new Shader("shaders/hotbar_vertex.glsl", "shaders/hotbar_fragment.glsl");
        initGlBuffers();
    }

    private void initGlBuffers() {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        // 0: aPos (vec2)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        // 1: aTexCoord (vec2)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        // 2: aColor (vec4)
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);
        // 3: aTexMode (float)
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 8L * Float.BYTES);
        glEnableVertexAttribArray(3);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    @Override
    public void update(float deltaTime) {
        // State is driven by Player
    }

    @Override
    public void render(Matrix4f orthoProjection, int windowWidth, int windowHeight, float scale) {
        if (!visible || player == null) return;

        int selectedSlot = player.getSelectedHotbarSlot();
        if (windowWidth != cachedWidth || windowHeight != cachedHeight || selectedSlot != cachedSelectedSlot) {
            rebuildMesh(windowWidth, windowHeight, selectedSlot);
            cachedWidth = windowWidth;
            cachedHeight = windowHeight;
            cachedSelectedSlot = selectedSlot;
        }

        if (cachedVertexCount > 0) {
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, atlasTextureId);

            hotbarShader.bind();
            hotbarShader.setUniform("uProjection", orthoProjection);
            hotbarShader.setUniform("uTexture", 0);

            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, cachedVertexCount);
            glBindVertexArray(0);

            hotbarShader.unbind();
            glBindTexture(GL_TEXTURE_2D, 0);
            glDisable(GL_BLEND);
        }

        // Render English block name above the Hotbar
        short[] slots = player.getHotbarBlocks();
        if (selectedSlot >= 0 && selectedSlot < slots.length && textRenderer != null) {
            Block activeBlock = BlockRegistry.getBlock(slots[selectedSlot]);
            if (activeBlock != null) {
                float uiScale = Math.max(0.85f, Math.min(1.45f, windowHeight / 960.0f));
                String label = formatBlockDisplayName(activeBlock.getName());
                float textScale = 1.6f * uiScale;
                float textWidth = textRenderer.getTextWidth(label, textScale);
                float labelX = (windowWidth - textWidth) * 0.5f;
                float labelY = this.y - 14.0f * uiScale;
                textRenderer.drawText(label, labelX + 2f, labelY + 2f,
                        new Vector3f(0.05f, 0.05f, 0.07f), orthoProjection, windowHeight, textScale);
                textRenderer.drawText(label, labelX, labelY,
                        new Vector3f(0.96f, 0.97f, 1.0f), orthoProjection, windowHeight, textScale);
            }
        }
    }

    private void rebuildMesh(int windowWidth, int windowHeight, int selectedSlot) {
        floatCursor = 0;

        short[] slots = player.getHotbarBlocks();
        int slotCount = slots.length;

        float uiScale = Math.max(0.85f, Math.min(1.45f, windowHeight / 960.0f));
        float slotSize = Math.round(BASE_SLOT_SIZE * uiScale);
        float slotGap = Math.round(BASE_SLOT_GAP * uiScale);
        float barPad = Math.round(BASE_BAR_PADDING * uiScale);

        this.width = slotCount * slotSize + (slotCount - 1) * slotGap + barPad * 2.0f;
        this.height = slotSize + barPad * 2.0f;
        this.x = Math.round((windowWidth - this.width) * 0.5f);
        this.y = Math.round(windowHeight - this.height - 16.0f * uiScale);

        // 0. Center Crosshair
        emitCrosshair(windowWidth, windowHeight);

        // 1. Main Hotbar outer frame & beveled panel
        emitBarBackground(uiScale);

        // 2. All 9 recessed slots + 3D isometric block icons
        float firstSlotX = this.x + barPad;
        float slotY = this.y + barPad;
        float iconRadius = slotSize * 0.36f;

        for (int i = 0; i < slotCount; i++) {
            float slotX = firstSlotX + i * (slotSize + slotGap);
            boolean isSelected = (i == selectedSlot);

            emitRecessedSlot(slotX, slotY, slotSize, isSelected, uiScale);

            Block block = BlockRegistry.getBlock(slots[i]);
            if (block != null && block.getId() != 0) {
                float cx = slotX + slotSize * 0.5f;
                float cy = slotY + slotSize * 0.52f;
                float r = isSelected ? iconRadius * 1.08f : iconRadius;
                emitIsometricBlockIcon(cx, cy, r, block);
            }
        }

        // 3. Thick protruding square Slot Selector around the active slot
        if (selectedSlot >= 0 && selectedSlot < slotCount) {
            float selX = firstSlotX + selectedSlot * (slotSize + slotGap);
            emitSlotSelector(selX, slotY, slotSize, uiScale);
        }

        cachedVertexCount = floatCursor / FLOATS_PER_VERTEX;
        float[] uploadData = java.util.Arrays.copyOf(buildBuffer, floatCursor);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, uploadData, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void emitCrosshair(int winWidth, int winHeight) {
        float cx = winWidth * 0.5f;
        float cy = winHeight * 0.5f;
        float armLen = 10.0f;
        float thickness = 2.0f;

        Vector4f shadow = new Vector4f(0.05f, 0.05f, 0.08f, 0.78f);
        Vector4f bright = new Vector4f(0.95f, 0.97f, 1.0f, 0.92f);

        emitRect(cx - armLen - 1f, cy - thickness * 0.5f - 1f, armLen * 2f + 2f, thickness + 2f, shadow);
        emitRect(cx - thickness * 0.5f - 1f, cy - armLen - 1f, thickness + 2f, armLen * 2f + 2f, shadow);

        emitRect(cx - armLen, cy - thickness * 0.5f, armLen * 2f, thickness, bright);
        emitRect(cx - thickness * 0.5f, cy - armLen, thickness, armLen * 2f, bright);
    }

    private void emitBarBackground(float uiScale) {
        float b = Math.max(2.0f, Math.round(2.5f * uiScale));

        // Dark outer border
        emitRect(x - b, y - b, width + b * 2f, height + b * 2f,
                new Vector4f(0.07f, 0.08f, 0.10f, 0.92f));

        // Main bar body
        emitRect(x, y, width, height,
                new Vector4f(0.24f, 0.25f, 0.29f, 0.88f));

        // Top & left light bevel highlight
        emitRect(x, y, width, b,
                new Vector4f(0.45f, 0.47f, 0.52f, 0.85f));
        emitRect(x, y, b, height,
                new Vector4f(0.45f, 0.47f, 0.52f, 0.85f));

        // Bottom & right dark bevel shadow
        emitRect(x, y + height - b, width, b,
                new Vector4f(0.14f, 0.15f, 0.18f, 0.90f));
        emitRect(x + width - b, y, b, height,
                new Vector4f(0.14f, 0.15f, 0.18f, 0.90f));
    }

    private void emitRecessedSlot(float sx, float sy, float size, boolean isSelected, float uiScale) {
        float bevel = Math.max(2.0f, Math.round(2.5f * uiScale));

        Vector4f interiorColor = isSelected
                ? new Vector4f(0.26f, 0.28f, 0.33f, 0.95f)
                : new Vector4f(0.18f, 0.19f, 0.23f, 0.90f);
        emitRect(sx, sy, size, size, interiorColor);

        Vector4f insetDark = new Vector4f(0.10f, 0.11f, 0.14f, 0.95f);
        emitRect(sx, sy, size, bevel, insetDark);
        emitRect(sx, sy, bevel, size, insetDark);

        Vector4f insetLight = new Vector4f(0.38f, 0.40f, 0.46f, 0.90f);
        emitRect(sx, sy + size - bevel, size, bevel, insetLight);
        emitRect(sx + size - bevel, sy, bevel, size, insetLight);
    }

    private void emitSlotSelector(float sx, float sy, float size, float uiScale) {
        float expand = Math.round(4.0f * uiScale);
        float frameThickness = Math.max(4.0f, Math.round(5.5f * uiScale));
        float outline = Math.max(1.5f, Math.round(2.0f * uiScale));

        float bx = sx - expand;
        float by = sy - expand;
        float bw = size + expand * 2.0f;
        float bh = size + expand * 2.0f;

        Vector4f darkOutline = new Vector4f(0.06f, 0.07f, 0.09f, 0.98f);
        Vector4f brightFrame = new Vector4f(0.94f, 0.95f, 0.98f, 1.0f);
        Vector4f topHighlight = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
        Vector4f bottomShade = new Vector4f(0.68f, 0.72f, 0.78f, 1.0f);

        // 1. Outer dark contour around the selector square
        emitBorder(bx, by, bw, bh, darkOutline, outline);

        // 2. Main thick selector square ring
        emitRect(bx, by, bw, frameThickness, brightFrame);
        emitRect(bx, by + bh - frameThickness, bw, frameThickness, brightFrame);
        emitRect(bx, by, frameThickness, bh, brightFrame);
        emitRect(bx + bw - frameThickness, by, frameThickness, bh, brightFrame);

        // 3. 3D bevel highlights on the thick selector ring
        float bevel = Math.max(1.5f, Math.round(2.0f * uiScale));
        emitRect(bx, by, bw, bevel, topHighlight);
        emitRect(bx, by, bevel, bh, topHighlight);
        emitRect(bx, by + bh - bevel, bw, bevel, bottomShade);
        emitRect(bx + bw - bevel, by, bevel, bh, bottomShade);

        // 4. Inner dark contour framing the selected slot interior
        float innerX = bx + frameThickness;
        float innerY = by + frameThickness;
        float innerW = bw - frameThickness * 2.0f;
        float innerH = bh - frameThickness * 2.0f;
        emitBorder(innerX, innerY, innerW, innerH, darkOutline, outline * 0.85f);
    }

    private void emitIsometricBlockIcon(float centerX, float centerY, float radius, Block block) {
        float w = radius * 0.8660254f; // cos(30 deg)
        float h = radius * 0.5f;       // sin(30 deg)

        // Outer dark silhouette (dilated by 1.5px)
        float rOut = radius + 1.5f;
        float wOut = rOut * 0.8660254f;
        float hOut = rOut * 0.5f;
        Vector4f outlineCol = new Vector4f(0.06f, 0.07f, 0.09f, 0.80f);
        emitQuad(
                centerX - wOut, centerY - hOut, 0f, 0f,
                centerX,        centerY - rOut, 0f, 0f,
                centerX + wOut, centerY - hOut, 0f, 0f,
                centerX,        centerY,        0f, 0f,
                outlineCol, 0f
        );
        emitQuad(
                centerX - wOut, centerY - hOut, 0f, 0f,
                centerX,        centerY,        0f, 0f,
                centerX,        centerY + rOut, 0f, 0f,
                centerX - wOut, centerY + hOut, 0f, 0f,
                outlineCol, 0f
        );
        emitQuad(
                centerX,        centerY,        0f, 0f,
                centerX + wOut, centerY - hOut, 0f, 0f,
                centerX + wOut, centerY + hOut, 0f, 0f,
                centerX,        centerY + rOut, 0f, 0f,
                outlineCol, 0f
        );

        float tx   = centerX,     ty   = centerY - radius;
        float trx  = centerX + w, try_ = centerY - h;
        float tlx  = centerX - w, tly  = centerY - h;
        float cx   = centerX,     cy   = centerY;
        float blx  = centerX - w, bly  = centerY + h;
        float brx  = centerX + w, bry  = centerY + h;
        float bx   = centerX,     by   = centerY + radius;

        final float uvEps = 0.00025f;

        // 1. TOP FACE (diamond: TL -> T -> TR -> C) using block.getTopTexture()
        TextureCoords topTC = block.getTopTexture();
        float tu0 = topTC.getUMin() + uvEps;
        float tv0 = topTC.getVMin() + uvEps;
        float tu1 = topTC.getUMax() - uvEps;
        float tv1 = topTC.getVMax() - uvEps;
        emitQuad(
                tlx, tly,  tu0, tv1,
                tx,  ty,   tu1, tv1,
                trx, try_, tu1, tv0,
                cx,  cy,   tu0, tv0,
                new Vector4f(1.02f, 1.02f, 1.00f, 1.0f), 2f
        );

        // 2. LEFT LATERAL FACE (parallelogram: TL -> C -> B -> BL) using block.getSideTexture()
        TextureCoords sideTC = block.getSideTexture();
        float su0 = sideTC.getUMin() + uvEps;
        float sv0 = sideTC.getVMin() + uvEps;
        float su1 = sideTC.getUMax() - uvEps;
        float sv1 = sideTC.getVMax() - uvEps;
        emitQuad(
                tlx, tly,  su0, sv1,
                cx,  cy,   su1, sv1,
                bx,  by,   su1, sv0,
                blx, bly,  su0, sv0,
                new Vector4f(0.82f, 0.81f, 0.79f, 1.0f), 2f
        );

        // 3. RIGHT LATERAL FACE (parallelogram: C -> TR -> BR -> B) using block.getSideTexture()
        emitQuad(
                cx,  cy,   su0, sv1,
                trx, try_, su1, sv1,
                brx, bry,  su1, sv0,
                bx,  by,   su0, sv0,
                new Vector4f(0.58f, 0.58f, 0.62f, 1.0f), 2f
        );
    }

    private void emitBorder(float rx, float ry, float rw, float rh, Vector4f color, float bw) {
        emitRect(rx - bw, ry - bw, rw + 2f * bw, bw, color);
        emitRect(rx - bw, ry + rh, rw + 2f * bw, bw, color);
        emitRect(rx - bw, ry, bw, rh, color);
        emitRect(rx + rw, ry, bw, rh, color);
    }

    private void emitRect(float rx, float ry, float rw, float rh, Vector4f color) {
        emitQuad(
                rx,      ry,      0f, 0f,
                rx + rw, ry,      0f, 0f,
                rx + rw, ry + rh, 0f, 0f,
                rx,      ry + rh, 0f, 0f,
                color, 0f
        );
    }

    private void emitQuad(
            float x0, float y0, float u0, float v0,
            float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            Vector4f c, float texMode) {
        ensureCapacity(6 * FLOATS_PER_VERTEX);
        pushVertex(x0, y0, u0, v0, c, texMode);
        pushVertex(x1, y1, u1, v1, c, texMode);
        pushVertex(x2, y2, u2, v2, c, texMode);

        pushVertex(x2, y2, u2, v2, c, texMode);
        pushVertex(x3, y3, u3, v3, c, texMode);
        pushVertex(x0, y0, u0, v0, c, texMode);
    }

    private void pushVertex(float vx, float vy, float u, float v, Vector4f c, float texMode) {
        buildBuffer[floatCursor++] = vx;
        buildBuffer[floatCursor++] = vy;
        buildBuffer[floatCursor++] = u;
        buildBuffer[floatCursor++] = v;
        buildBuffer[floatCursor++] = c.x;
        buildBuffer[floatCursor++] = c.y;
        buildBuffer[floatCursor++] = c.z;
        buildBuffer[floatCursor++] = c.w;
        buildBuffer[floatCursor++] = texMode;
    }

    private void ensureCapacity(int additionalFloats) {
        if (floatCursor + additionalFloats > buildBuffer.length) {
            buildBuffer = java.util.Arrays.copyOf(buildBuffer, Math.max(buildBuffer.length * 2, floatCursor + additionalFloats));
        }
    }

    private static String formatBlockDisplayName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "";
        return switch (rawName.toLowerCase()) {
            case "grass"       -> "Grass";
            case "dirt"        -> "Dirt";
            case "stone"       -> "Stone";
            case "cobblestone" -> "Cobblestone";
            case "log"         -> "Oak Log";
            case "planks"      -> "Oak Planks";
            case "leaves"      -> "Oak Leaves";
            case "sand"        -> "Sand";
            case "bricks"      -> "Bricks";
            case "glass"       -> "Glass";
            case "bedrock"     -> "Bedrock";
            default            -> Character.toUpperCase(rawName.charAt(0)) + rawName.substring(1);
        };
    }

    public void cleanup() {
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        hotbarShader.cleanup();
    }

    @Override
    public void handleInput(Input input) {}

    @Override
    public boolean contains(float px, float py) {
        return visible && px >= x && px <= x + width && py >= y && py <= y + height;
    }

    @Override
    public float getX() { return x; }

    @Override
    public float getY() { return y; }

    @Override
    public float getWidth() { return width; }

    @Override
    public float getHeight() { return height; }

    @Override
    public void setPosition(float x, float y) { this.x = x; this.y = y; }

    @Override
    public void setSize(float width, float height) { this.width = width; this.height = height; }

    @Override
    public boolean isVisible() { return visible; }

    @Override
    public void setVisible(boolean visible) { this.visible = visible; }
}
