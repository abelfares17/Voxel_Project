// src/main/java/engine/Game.java
package engine;

import engine.input.Input;
import engine.player.Player;
import engine.render.Camera;
import engine.render.MeshBuilder;
import engine.render.Shader;
import engine.time.TimeManager;
import engine.world.Chunk;
import engine.world.ChunkPos;
import engine.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

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
    private final TimeManager timeManager= new TimeManager();

    public Game(long window, Input input, World world, Shader shader, int atlasTextureId) {
        this.window         = window;
        this.input          = input;
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

    public void update() {
        long now = System.currentTimeMillis();
        long dt = (long) ((now - lastTime) / 1000f);
        lastTime = now;
        timeManager.update(dt);
        camera.setAspect(aspect);

        world.update(input);
        if (input.isKeyPressed(GLFW_KEY_P)) {
            player.setSpectatorMode(!player.isSpectatorMode());
        }
        //player.setPosition(player.getX()+1, player.getY(), player.getZ());
        //System.out.println("POS : (x,y,z) = " + player.getX() + ", " + player.getY() + ", " + player.getZ());
    }

    public void render() {
        glClearColor(0.53f, 0.81f, 0.92f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        shader.bind();
        shader.setUniform("ambient", 0.5f);
        shader.setUniform("brightness", 0.5f);
        shader.setUniform("sunColor", new Vector3f(1,1,1));
        shader.setUniform("sunDir", new Vector3f(0,1,0));
        /*
        Vector3f cameraPos = new Vector3f(-3, 8, -3);      // Juste au-dessus du centre du chunk
        float yaw = 90f;    // peu importe, car pitch = -90f
        float pitch = 10f; // regarde droit vers le bas
        */
        Vector3f playerPos = new Vector3f(player.getX(),player.getY() + HEIGHT, player.getZ());
        //Matrix4f view = camera.getViewMatrix(cameraPos, yaw, pitch);
        Matrix4f viewPlayer = camera.getViewMatrix(playerPos, player.getYaw(), player.getPitch());
        Matrix4f proj = camera.getProjectionMatrix(); // fov=70~90°, near=0.1, far=1000

        shader.setUniform("projectionMatrix", proj);
        shader.setUniform("viewMatrix", viewPlayer);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTextureId);
        shader.setUniform("textureSampler", 0);

        int cx = player.getChunkX(), cz = player.getChunkZ(), radius = world.getRenderDistance();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = world.getChunk(cx + dx, cz + dz);
                if (chunk == null) continue;

                if (chunk.isMeshDirty()) {
                    MeshBuilder.buildChunkMesh(chunk);
                }
                // Calcul la translation (en unités monde) pour ce chunk :
                Matrix4f model = new Matrix4f()
                        .translation(chunk.getChunkX() * Chunk.WIDTH, 0, chunk.getChunkZ() * Chunk.DEPTH);
                // Envoie la matrice modèle au shader :
                shader.setUniform("modelMatrix", model);
                MeshBuilder.renderChunk(chunk);

            }
        }

        shader.unbind();
    }

    public Shader getShader() {
        return shader;
    }

    public World getWorld() {
        return world;
    }
}