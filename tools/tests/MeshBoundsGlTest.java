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
    /** Observe deletion before the worker allocates the next shadow mesh (names may be reused). */
    private static final class CheckedChunk extends Chunk {
        CheckedChunk(int x, int z) { super(x, z); }
        @Override public void setMesh(int vao, int vbo, int count, MeshBounds bounds,
                                      int ebo, int indices, int stride) {
            int oldVao = getVao(), oldVbo = getVbo(), oldEbo = getEbo();
            int shadowVao = shadowHandle(getShadowMesh(), "vao");
            int shadowVbo = shadowHandle(getShadowMesh(), "vbo");
            super.setMesh(vao, vbo, count, bounds, ebo, indices, stride);
            if (oldVao != 0 && oldVao != vao) require(!glIsVertexArray(oldVao), "old VAO not deleted at replacement");
            if (oldVbo != 0 && oldVbo != vbo) require(!glIsBuffer(oldVbo), "old VBO not deleted at replacement");
            if (oldEbo != 0 && oldEbo != ebo) require(!glIsBuffer(oldEbo), "old EBO not deleted at replacement");
            if (shadowVao != 0) require(!glIsVertexArray(shadowVao), "old shadow VAO not deleted");
            if (shadowVbo != 0) require(!glIsBuffer(shadowVbo), "old shadow VBO not deleted");
        }
    }
    private static int shadowHandle(ShadowMesh mesh, String name) {
        if (mesh == null) return 0;
        try {
            var field = ShadowMesh.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(mesh);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
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
        Chunk chunk = new CheckedChunk(-2, -3);
        try {
            chunk.setBlock(2, 4, 3, (short) 1);
            upload(world, chunk);
            require(chunk.getMeshBounds().equals(new MeshBounds(2, 4, 3, 3, 5, 4)), "low mesh");
            var firstShadow = chunk.getShadowMesh();
            int oldVao = chunk.getVao(), oldVbo = chunk.getVbo();
            MeshBounds oldBounds = chunk.getMeshBounds();
            chunk.setBlock(15, 255, 15, (short) 1);
            require(chunk.getMeshBounds() == oldBounds, "pending mesh changed active bounds");
            upload(world, chunk);
            require(chunk.getMeshBounds().maxY() == 256 && chunk.getMeshBounds().maxX() == 16, "top/edge face");
            if (engine.core.config.RenderConfig.GREEDY_SHADOWS) {
                require(chunk.getShadowMesh() != firstShadow, "shadow not replaced on worker remesh");
                require(chunk.getShadowMesh().count > firstShadow.count, "added block missing from shadow geometry");
            }
            // Deletion was checked synchronously inside setMesh, before any new allocation.
            if (glIsVertexArray(oldVao)) {
                require(shadowHandle(chunk.getShadowMesh(), "vao") == oldVao, "unexpected live VAO name");
                System.out.println("Confirmed name reuse: old visual VAO " + oldVao + " is now the new shadow VAO");
            }
            if (glIsBuffer(oldVbo)) {
                require(shadowHandle(chunk.getShadowMesh(), "vbo") == oldVbo, "unexpected live VBO name");
                System.out.println("Confirmed name reuse: old visual VBO " + oldVbo + " is now the new shadow VBO");
            }
            chunk.setBlock(15, 255, 15, (short) 0);
            upload(world, chunk);
            require(chunk.getMeshBounds().maxY() == 5, "bounds did not shrink");
            oldVao = chunk.getVao(); oldVbo = chunk.getVbo();
            chunk.cleanup();
            require(chunk.getMeshBounds() == null && !chunk.isReady(), "unload bounds");
            require(chunk.getShadowMesh() == null, "unload shadow mesh");
            require(!glIsVertexArray(oldVao) && !glIsBuffer(oldVbo), "unload GPU objects");
            upload(world, chunk);
            require(chunk.getMeshBounds().maxY() == 5, "re-upload bounds");
            MeshBuilder.buildChunkMesh(chunk, world);
            require(chunk.getMeshBounds().maxY() == 5, "direct upload path");
            chunk.setBlock(2, 4, 3, (short) 0);
            // Empty chunks are not isReady() by the engine's existing contract; test directly.
            MeshBuilder.buildChunkMesh(chunk, world);
            require(chunk.getVertexCount() == 0 && chunk.getMeshBounds() == null, "empty upload");
            if (engine.core.config.RenderConfig.GREEDY_SHADOWS)
                require(chunk.getShadowMesh() != null && chunk.getShadowMesh().count == 0, "stale shadow on empty chunk");
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
        if (engine.core.config.RenderConfig.GREEDY_SHADOWS) {
            require(chunk.getShadowMesh() != null && chunk.getShadowMesh().count > 0,
                    "worker replacement missing shadow mesh");
        }
    }
}
