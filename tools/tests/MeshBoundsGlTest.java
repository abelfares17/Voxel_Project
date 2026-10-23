package engine.render;

import engine.time.TimeManager;
import engine.world.BlockRegistry;
import engine.world.Chunk;
import engine.world.World;
import static engine.render.MeshBoundsTest.require;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import org.lwjgl.opengl.GL;

/** Run from a temporary directory: its isolated WorldStorage must never use a player's save. */
public final class MeshBoundsGlTest {
    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(64, 64, "Bounds regression", 0, 0);
        if (window == 0) throw new IllegalStateException("GL context creation failed");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        BlockRegistry.registerAll();
        World world = new World("Bounds regression", 1, World.Mode.FLAT, 0, new TimeManager());
        Chunk chunk = new Chunk(-2, -3);
        try {
            chunk.setBlock(2, 4, 3, (short) 1);
            upload(world, chunk);
            require(chunk.getMeshBounds().equals(new MeshBounds(2, 4, 3, 3, 5, 4)), "low mesh");
            int oldVao = chunk.getVao(), oldVbo = chunk.getVbo();
            MeshBounds oldBounds = chunk.getMeshBounds();
            chunk.setBlock(15, 255, 15, (short) 1);
            require(chunk.getMeshBounds() == oldBounds, "pending mesh changed active bounds");
            upload(world, chunk);
            require(chunk.getMeshBounds().maxY() == 256 && chunk.getMeshBounds().maxX() == 16, "top/edge face");
            require(!glIsVertexArray(oldVao) && !glIsBuffer(oldVbo), "replaced GPU mesh still alive");
            chunk.setBlock(15, 255, 15, (short) 0);
            upload(world, chunk);
            require(chunk.getMeshBounds().maxY() == 5, "bounds did not shrink");
            oldVao = chunk.getVao(); oldVbo = chunk.getVbo();
            chunk.cleanup();
            require(chunk.getMeshBounds() == null && !chunk.isReady(), "unload bounds");
            require(!glIsVertexArray(oldVao) && !glIsBuffer(oldVbo), "unload GPU objects");
            upload(world, chunk);
            require(chunk.getMeshBounds().maxY() == 5, "re-upload bounds");
            MeshBuilder.buildChunkMesh(chunk, world);
            require(chunk.getMeshBounds().maxY() == 5, "direct upload path");
            chunk.setBlock(2, 4, 3, (short) 0);
            // Empty chunks are not isReady() by the engine's existing contract; test directly.
            MeshBuilder.buildChunkMesh(chunk, world);
            require(chunk.getVertexCount() == 0 && chunk.getMeshBounds() == null, "empty upload");
            require(MeshData.COUNT.get() == 0, "unfreed native MeshData");
            require(glGetError() == GL_NO_ERROR, "GL error");
            System.out.println("PASS GL MeshBounds: worker upload, low-high-low, replacement deletion, cleanup/re-upload, direct path, empty mesh");
        } finally {
            chunk.cleanup();
            world.cleanup();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static void upload(World world, Chunk chunk) throws Exception {
        world.getMeshManager().queueChunk(chunk);
        world.getMeshManager().processMeshes();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!chunk.isReady() && System.nanoTime() < deadline) {
            world.getMeshManager().uploadMeshesIfAny();
            Thread.sleep(2);
        }
        require(chunk.isReady(), "worker upload timeout");
    }
}
