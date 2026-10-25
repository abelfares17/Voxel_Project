package engine.render;

import engine.world.*;
import engine.time.TimeManager;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Run in a temporary directory. Exact CPU expansion, GL raster equivalence and EBO lifetime. */
public final class MeshIndexTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        BlockRegistry.registerAll();
        Chunk source = new Chunk(-2, -3);
        World world = new World("Index test", 1, World.Mode.FLAT, 0, new TimeManager()) {
            @Override public Block getBlockAt(int x, int y, int z) {
                return source.getBlock(x + 32, y, z + 48);
            }
        };
        check(glfwInit(), "GLFW init");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(64, 64, "Index test", 0, 0);
        check(window != 0, "window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        try {
            compare(source, world, false); // empty
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                for (int y = 0; y < 8 + (x * x + z) % 13; y++) source.setBlock(x, y, z, (short) 1);
            source.setBlock(15, 255, 15, (short) 1);
            compare(source, world, false);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                for (int y = 0; y < 32; y++) source.setBlock(x, y, z, (short) ((x + y + z) % 2));
            compare(source, world, true);
            check(MeshData.COUNT.get() == 0, "native mesh leak");
            check(glGetError() == GL_NO_ERROR, "GL error");
            System.out.println("PASS indexed meshes: exact 9-float expansion, empty/terrain/AO/edge/uint32, four GPU projections, EBO replacement/cleanup");
        } finally {
            world.cleanup();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    static void compare(Chunk source, World world, boolean large) {
        MeshData a = MeshBuilder.generateMeshData(source, world, false, false);
        MeshData b = MeshBuilder.generateMeshData(source, world, true, engine.core.config.RenderConfig.COMPACT_VERTICES);
        Chunk gpu = new Chunk(0, 0);
        int program = 0;
        try {
            check(a.vertexCount == b.indexCount, "reference count");
            check(java.util.Objects.equals(a.bounds, b.bounds), "bounds differ");
            if (large) check(b.vertexCount > 65536, "uint32 fixture too small");
            for (int i = 0; i < b.indexCount; i++) {
                int index = b.indexBuffer.get(i);
                check(index >= 0 && index < b.vertexCount, "index range");
                for (int c = 0; c < 9; c++) check(Float.floatToRawIntBits(a.vertexBuffer.get(i * 9 + c))
                        == Float.floatToRawIntBits(attribute(b, index, c)), "expanded attribute mismatch");
            }
            program = glCreateProgram();
            int vs = shader(GL_VERTEX_SHADER, "#version 150\nin vec3 p; in vec2 uv; in vec3 n; in float ao; uniform mat4 m; out vec3 color; void main(){gl_Position=m*vec4(p,1);color=vec3(uv,ao)*0.5+abs(n)*0.5;}");
            int fs = shader(GL_FRAGMENT_SHADER, "#version 150\nin vec3 color; out vec4 result; void main(){result=vec4(color,1);}");
            glAttachShader(program, vs); glAttachShader(program, fs);
            glBindAttribLocation(program, 0, "p"); glBindAttribLocation(program, 1, "uv");
            glBindAttribLocation(program, 2, "n"); glBindAttribLocation(program, 3, "ao");
            glLinkProgram(program);
            check(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
            glDeleteShader(vs); glDeleteShader(fs);
            glUseProgram(program);
            for (int view = 0; view < 4; view++) {
                Matrix4f matrix = view == 3
                    ? new Matrix4f().perspective(1.2f, 1, .1f, 400).lookAt(30, 35, 40, 8, 10, 8, 0, 1, 0)
                    : new Matrix4f().ortho(-30, 30, -140, 140, -300, 300).rotateY(view * .7f).translate(-8, -120, -8);
                glUniformMatrix4fv(glGetUniformLocation(program, "m"), false, matrix.get(new float[16]));
                install(gpu, a);
                float[] reference = pixels(gpu);
                if (a.vertexCount > 0) {
                    boolean covered = false;
                    for (int i = 64 * 64 * 4; i < reference.length; i++) covered |= reference[i] < 1;
                    check(covered, "empty raster would make comparison vacuous");
                }
                install(gpu, b);
                check(java.util.Arrays.equals(reference, pixels(gpu)), "GPU color/depth mismatch view=" + view);
                int oldEbo = gpu.getEbo();
                install(gpu, a);
                check(!glIsBuffer(oldEbo) && gpu.getEbo() == 0, "indexed to legacy leak");
            }
            install(gpu, b);
            int ebo = gpu.getEbo();
            gpu.cleanup();
            check(!glIsBuffer(ebo), "EBO cleanup leak");
            System.out.println("Fixture PASS: stored=" + b.vertexCount + "; references=" + b.indexCount);
        } finally {
            gpu.cleanup(); a.free(); b.free();
            if (program != 0) { glUseProgram(0); glDeleteProgram(program); }
        }
    }

    static void install(Chunk chunk, MeshData data) {
        int vao = glGenVertexArrays(), vbo = glGenBuffers();
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo);
        MeshVertexLayout.upload(data);
        int ebo = MeshBuilder.uploadIndices(data);
        glBindVertexArray(0);
        chunk.setMesh(vao, vbo, data.vertexCount, data.bounds, ebo, data.indexCount, data.vertexStride());
    }

    static float attribute(MeshData data, int vertex, int component) {
        if (data.packedVertices == null) return data.vertexBuffer.get(vertex * 9 + component);
        int base = vertex * 24;
        if (component < 3) return Short.toUnsignedInt(data.packedVertices.getShort(base + component * 2));
        if (component < 5) return data.packedVertices.getFloat(base + 12 + (component - 3) * 4);
        if (component < 8) return data.packedVertices.get(base + 6 + component - 5);
        return data.packedVertices.getFloat(base + 20);
    }

    static float[] pixels(Chunk chunk) {
        glViewport(0, 0, 64, 64); glEnable(GL_DEPTH_TEST); glDepthMask(true);
        glClearColor(.1f, .2f, .3f, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glBindVertexArray(chunk.getVao()); MeshBuilder.drawBoundChunk(chunk); glBindVertexArray(0);
        FloatBuffer buffer = MemoryUtil.memAllocFloat(64 * 64 * 5);
        try {
            glReadPixels(0, 0, 64, 64, GL_RGBA, GL_FLOAT, buffer);
            buffer.position(64 * 64 * 4);
            glReadPixels(0, 0, 64, 64, GL_DEPTH_COMPONENT, GL_FLOAT, buffer);
            buffer.position(0);
            float[] result = new float[buffer.capacity()]; buffer.get(result); return result;
        } finally { MemoryUtil.memFree(buffer); }
    }

    static int shader(int type, String code) {
        int s = glCreateShader(type); glShaderSource(s, code); glCompileShader(s);
        check(glGetShaderi(s, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(s)); return s;
    }
}
