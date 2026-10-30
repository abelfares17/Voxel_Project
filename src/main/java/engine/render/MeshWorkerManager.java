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
    private volatile boolean closing;

    public MeshWorkerManager(World world, int numThreads) {
        this.executor = Executors.newFixedThreadPool(numThreads);
        this.chunkQueue = new ConcurrentLinkedQueue<>();
        this.world = world;
    }

    public void queueChunk(Chunk chunk) {
        if(closing || chunk.isRetired())return;
        chunk.requestMeshRevision();
        scheduleChunk(chunk);
    }

    /**
     * Immediately rebuild and upload a chunk's mesh on the GL thread (for interactive block break/place).
     */
    public void rebuildChunkImmediate(Chunk chunk) {
        if (closing || chunk == null || chunk.isRetired()) return;
        long revision = chunk.requestMeshRevision();
        MeshData data = MeshBuilder.generateMeshData(chunk, world);
        data.meshRevision = revision;
        try {
            int vao = glGenVertexArrays();
            int vbo = glGenBuffers();

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            MeshVertexLayout.upload(data);

            int ebo = MeshBuilder.uploadIndices(data);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            chunk.setMesh(vao, vbo, data.vertexCount, data.bounds, ebo, data.indexCount, data.vertexStride());
            if (data.shadowData != null) {
                chunk.setShadowMesh(ShadowMesh.upload(data.shadowData));
            }
        } finally {
            data.free();
        }
    }

    private void scheduleChunk(Chunk chunk) {
        if(closing || chunk.isRetired())return;
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
                MeshData mesh = null;
                try {
                    if(closing || chunkToMesh.isRetired())return;
                    long revision=chunkToMesh.getMeshRevision();
                    //System.out.println("[MeshWorkerManager] BEGIN mesh for chunk " + chunkToMesh.getChunkX() + "," + chunkToMesh.getChunkZ());
                    mesh = MeshBuilder.generateMeshData(chunkToMesh, world);
                    mesh.meshRevision=revision;
                    //System.out.println("[MeshWorkerManager] OK mesh for chunk " + chunkToMesh.getChunkX() + "," + chunkToMesh.getChunkZ());
                } catch (Exception e) {
                    System.err.println("[MeshWorkerManager] EXCEPTION mesh for chunk " + chunkToMesh.getChunkX() + "," + chunkToMesh.getChunkZ());
                    e.printStackTrace();
                } finally {
                    queuedSet.remove(chunkToMesh);
                    if(mesh!=null) synchronized(uploadQueue) {
                        if(closing || chunkToMesh.isRetired())mesh.free();
                        else uploadQueue.add(mesh);
                    }
                }
            });
        }
    }

    // À appeler sur le thread OpenGL AVANT de rendre les chunks
    public void uploadMeshesIfAny() {
        MeshData data;
        while ((data = uploadQueue.poll()) != null) {
            if(data.chunk.isRetired() || data.meshRevision!=data.chunk.getMeshRevision()) {
                Chunk chunk=data.chunk;data.free();
                // Revision already advanced when the neighbor/data changed. Do not invalidate
                // a newer build which may already be running for that revision.
                if(!chunk.isRetired())scheduleChunk(chunk);
                continue;
            }
            //System.out.println("[MeshWorkerManager] Uploading mesh for chunk: " + data.chunk.getChunkX() + "," + data.chunk.getChunkZ());
            int vao = glGenVertexArrays();
            int vbo = glGenBuffers();

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            MeshVertexLayout.upload(data);

            int ebo = MeshBuilder.uploadIndices(data);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            data.chunk.setMesh(vao, vbo, data.vertexCount, data.bounds, ebo, data.indexCount, data.vertexStride());
            try {
                if (data.shadowData != null) data.chunk.setShadowMesh(ShadowMesh.upload(data.shadowData));
            } finally { data.free(); }
            //System.out.println("[MeshWorkerManager] Mesh uploaded and ready for chunk: " + data.chunk.getChunkX() + "," + data.chunk.getChunkZ());
        }
    }

    public void shutdown() {
        closing=true;
        executor.shutdownNow();
        // Vider la file pour libérer la mémoire non uploadée
        synchronized(uploadQueue) {
            MeshData data;
            while((data = uploadQueue.poll()) != null) data.free();
        }
        chunkQueue.clear();
        queuedSet.clear();
    }
}
