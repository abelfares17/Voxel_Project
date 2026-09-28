package engine.render;

import engine.world.Chunk;
import engine.world.World;

import java.util.concurrent.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static engine.render.MeshBuilder.STRIDE;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class MeshWorkerManager {
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Chunk> chunkQueue;
    private final Set<Chunk> queuedSet = ConcurrentHashMap.newKeySet();
    private final World world;
    private final MeshUploadQueue uploadQueue = new MeshUploadQueue();

    public MeshWorkerManager(World world, int numThreads) {
        this.executor = Executors.newFixedThreadPool(numThreads);
        this.chunkQueue = new ConcurrentLinkedQueue<>();
        this.world = world;
    }

    public void queueChunk(Chunk chunk) {
        if (queuedSet.add(chunk)) {
            chunkQueue.offer(chunk);
            //System.out.println("[MeshWorkerManager] Queued chunk for meshing: " + chunk.getChunkX() + "," + chunk.getChunkZ());
        }
    }

    // À appeler à chaque frame, thread principal
    public void processMeshes() {
        Chunk chunk;
        while ((chunk = chunkQueue.poll()) != null) {
            final Chunk chunkToMesh = chunk;
            //System.out.println("[MeshWorkerManager] Submitting mesh build for chunk: " + chunkToMesh.getChunkX() + "," + chunkToMesh.getChunkZ());
            executor.submit(() -> {
                try {
                    //System.out.println("[MeshWorkerManager] BEGIN mesh for chunk " + chunkToMesh.getChunkX() + "," + chunkToMesh.getChunkZ());
                    MeshData mesh = MeshBuilder.generateMeshData(chunkToMesh, world);
                    //System.out.println("[MeshWorkerManager] OK mesh for chunk " + chunkToMesh.getChunkX() + "," + chunkToMesh.getChunkZ());
                    uploadQueue.add(mesh);
                } catch (Exception e) {
                    System.err.println("[MeshWorkerManager] EXCEPTION mesh for chunk " + chunkToMesh.getChunkX() + "," + chunkToMesh.getChunkZ());
                    e.printStackTrace();
                }
                queuedSet.remove(chunkToMesh);
            });
        }
    }

    // À appeler sur le thread OpenGL AVANT de rendre les chunks
    public void uploadMeshesIfAny() {
        MeshData data;
        while ((data = uploadQueue.poll()) != null) {
            //System.out.println("[MeshWorkerManager] Uploading mesh for chunk: " + data.chunk.getChunkX() + "," + data.chunk.getChunkZ());
            int vao = glGenVertexArrays();
            int vbo = glGenBuffers();

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, data.vertexBuffer, GL_STATIC_DRAW);

            // location = 0 : aPos
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0L);

            // location = 1 : aTex
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, (long)(3 * Float.BYTES));

            // location = 2 : aNormal
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 3, GL_FLOAT, false, STRIDE, (long)((3 + 2) * Float.BYTES));

            // location = 3 : aAO (CPU shadow removed - GPU shadow mapping only)
            glEnableVertexAttribArray(3);
            glVertexAttribPointer(3, 1, GL_FLOAT, false, STRIDE, (long)((3 + 2 + 3) * Float.BYTES));

            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            data.chunk.setMesh(vao, vbo, data.vertexCount);
            //System.out.println("[MeshWorkerManager] Mesh uploaded and ready for chunk: " + data.chunk.getChunkX() + "," + data.chunk.getChunkZ());
            data.free();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        // Vider la file pour libérer la mémoire non uploadée
        MeshData data;
        while((data = uploadQueue.poll()) != null){
            data.free();
        }
        queuedSet.clear();
    }
}