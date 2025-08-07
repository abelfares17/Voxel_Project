// src/main/java/engine/Game.java
package engine;

import engine.input.Input;
import engine.player.Player;
import engine.render.*;
import engine.time.TimeManager;
import engine.world.Chunk;
import engine.world.ChunkPos;
import engine.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import javax.swing.text.View;
import java.nio.IntBuffer;

import static engine.player.Player.HEIGHT;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

public class Game {
    private final long window;
    private final Input input;
    private final World world;
    private final Player player;
    private final Camera camera;
    private final Shader shader;
    private final int atlasTextureId;
    private long lastTime;
    private float aspect;
    private int winWidth;
    private int winHeight;
    private final TimeManager timeManager= new TimeManager();
    private TextRenderer textRenderer;
    private Sun sun;

    private Vector3f lerp(Vector3f a, Vector3f b, float t) {
        return new Vector3f(
                a.x * (1 - t) + b.x * t,
                a.y * (1 - t) + b.y * t,
                a.z * (1 - t) + b.z * t
        );
    }

    public Game(long window, Input input, World world, Shader shader, int atlasTextureId, TextRenderer textRenderer, Sun sun) {
        this.window         = window;
        this.input          = input;
        this.textRenderer = textRenderer;
        this.sun            = sun;
        this.world          = world;
        this.player         = world.getPlayer();
        camera = new Camera(90f, this.aspect, 0.1f, 1000f);
        this.shader         = shader;
        this.atlasTextureId = atlasTextureId;
        this.lastTime       = System.currentTimeMillis();
        this.player.setYaw(0f);
        this.player.setPitch(0f);
    }

    public void setAspect(float aspect) {
       this.aspect = aspect;
    }
    public void setWinDim(int winWidth, int winHeight) {
        this.winWidth = winWidth;
        this.winHeight = winHeight;
    }

    public void update() {
        long now = System.currentTimeMillis();
        long dt = (now - lastTime);
        lastTime = now;
        timeManager.update(dt);
        camera.setAspect(aspect);

        world.update(input);
        if (input.isKeyPressed(GLFW_KEY_P)) {
            player.setSpectatorMode(!player.isSpectatorMode());
        }

    }

    public void render() {
        //Sky Color
        float t = timeManager.getTimeOfDay();
        Vector3f nightColor = new Vector3f(0.02f, 0.04f, 0.08f);   // Nuit
        Vector3f dawnColor  = new Vector3f(0.6f, 0.35f, 0.22f);    // Aube/crépuscule
        Vector3f dayColor   = new Vector3f(0.53f, 0.81f, 0.92f);   // Jour
        Vector3f skyColor;
        t = (t + 0.25f) % 1.0f;

        float dawnStart    = 4f / 24f;   // 0.1667
        float dawnMid      = 6f / 24f;   // 0.25
        float dayStart     = 8f / 24f;   // 0.3333
        float duskStart    = 16f / 24f;  // 0.6667
        float duskMid      = 18f / 24f;  // 0.75
        float nightStart   = 20f / 24f;  // 0.8333

        if (t < dawnStart) { // Nuit profonde (avant 4h)
            skyColor = nightColor;
        } else if (t < dawnMid) { // 4h–6h transition nuit → aube
            float alpha = (t - dawnStart) / (dawnMid - dawnStart);
            skyColor = lerp(nightColor, dawnColor, alpha);
        } else if (t < dayStart) { // 6h–8h transition aube → jour
            float alpha = (t - dawnMid) / (dayStart - dawnMid);
            skyColor = lerp(dawnColor, dayColor, alpha);
        } else if (t < duskStart) { // 8h–16h jour
            skyColor = dayColor;
        } else if (t < duskMid) { // 16h–18h transition jour → crépuscule
            float alpha = (t - duskStart) / (duskMid - duskStart);
            skyColor = lerp(dayColor, dawnColor, alpha);
        } else if (t < nightStart) { // 18h–20h transition crépuscule → nuit
            float alpha = (t - duskMid) / (nightStart - duskMid);
            skyColor = lerp(dawnColor, nightColor, alpha);
        } else { // 20h–4h nuit
            skyColor = nightColor;
        }
        glClearColor(skyColor.x, skyColor.y, skyColor.z, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        //Sun Direction
        float timeOfDay = t;
        float angle = (float) (2 * Math.PI * (t-0.25));
        float sunY = (float) Math.sin(angle);
        float sunX = (float) Math.cos(angle);
        Vector3f sunDir = new Vector3f(sunX, sunY, 0.2f).normalize();
        // Sun fade parameters
        final float fadeStart = -0.2f; // Commence le fade-out quand sunY < -0.2
        final float fadeEnd   = -0.3f; // Noir total à sunY < -0.3

        float fade = 1.0f;
        if (sunY < fadeStart) {
            if (sunY > fadeEnd) {
                fade = (sunY - fadeEnd) / (fadeStart - fadeEnd); // 1 → 0
            } else {
                fade = 0.0f;
            }
        }
        // Couleur et intensité du soleil
        float intensity = Math.max(0.15f, sunY);
        Vector3f baseColor = new Vector3f(
                1.0f,
                Math.max(0.6f, intensity),
                Math.max(0.5f, intensity * 0.8f)
        );
        Vector3f sunColor = new Vector3f(
                baseColor.x * fade,
                baseColor.y * fade,
                baseColor.z * fade
        );
        //Player pos
        Vector3f playerPos = new Vector3f(player.getX(),player.getY() + HEIGHT, player.getZ());
        Matrix4f viewPlayer = camera.getViewMatrix(playerPos, player.getYaw(), player.getPitch());
        Matrix4f proj = camera.getProjectionMatrix(); // fov=70~90°, near=0.1, far=1000
        //Sun rendering
        sun.render(playerPos, sunDir, viewPlayer, proj, sunColor);
        //Shader binding
        shader.bind();
        shader.setUniform("sunDir", sunDir);
        shader.setUniform("sunColor", sunColor);
        shader.setUniform("ambient", timeManager.getAmbient());
        shader.setUniform("brightness", timeManager.getBrightness());
        shader.setUniform("projectionMatrix", proj);
        shader.setUniform("viewMatrix", viewPlayer);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTextureId);
        shader.setUniform("textureSampler", 0);

        // Upload des chunks calculés dans le thread pool vers le main thread
        world.getMeshManager().uploadMeshesIfAny();

        int cx = player.getChunkX(), cz = player.getChunkZ(), radius = world.getRenderDistance();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = world.getChunk(cx + dx, cz + dz);
                if (chunk == null) continue;
                // Calcul la translation (en unités monde) pour ce chunk :
                Matrix4f model = new Matrix4f()
                        .translation(chunk.getChunkX() * Chunk.WIDTH, 0, chunk.getChunkZ() * Chunk.DEPTH);
                // Envoie la matrice modèle au shader :
                shader.setUniform("modelMatrix", model);
                MeshBuilder.renderChunk(chunk);

            }
        }

        Matrix4f ortho = new Matrix4f().ortho2D(0, winWidth, winHeight, 0);

        // 2. Calcul de l’heure : 0.0 = 6h du matin, 1.0 = 6h du matin suivant
        int totalMinutes = (int) (timeOfDay * 24 * 60);
        totalMinutes = totalMinutes % (24 * 60);              // re-boucle sur 24h
        int hour = totalMinutes / 60;
        int minute = totalMinutes % 60;
        String heureStr = String.format("%02d:%02d", hour, minute);

        // 3. Affichage (en haut à gauche)
        float text_scale = 2.0f;
        textRenderer.drawText(
                heureStr, 20, winHeight - 100,     // (x, y) position en pixels
                new Vector3f(1, 1, 0.85f),        // couleur (jaune pâle)
                ortho, winHeight, text_scale
        );

        String fpsStr = String.format("FPS: %d", timeManager.getFps());
        textRenderer.drawText(
                fpsStr, 20, winHeight - 140,
                new Vector3f(0.9f, 1f, 0.9f),
                ortho, winHeight, text_scale
        );

        String NbrChunk = String.format("Nombre de Chunk en mémoire : %d", world.getChunkCount());
        textRenderer.drawText(
                NbrChunk, 20, winHeight - 180,
                new Vector3f(0.9f, 1f, 0.9f),
                ortho, winHeight, text_scale
        );

        String NbrMesh = String.format("Nombre de Mesh en mémoire : %d", MeshData.COUNT.get());
        textRenderer.drawText(
                NbrMesh, 20, winHeight - 220,
                new Vector3f(0.9f, 1f, 0.9f),
                ortho, winHeight, text_scale
        );


        shader.unbind();
    }

    public Shader getShader() {
        return shader;
    }

    public World getWorld() {
        return world;
    }
}