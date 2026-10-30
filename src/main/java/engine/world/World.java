package engine.world;

import java.util.*;
import engine.core.config.GameConfig;

import engine.core.config.RenderConfig;
import engine.render.MeshBounds;
import engine.render.MeshWorkerManager;
import engine.render.Shader;
import engine.time.TimeManager;
import org.joml.Matrix4f;
import org.joml.FrustumIntersection;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL30.*;

/**
 * Représente un monde entier, contenant des chunks.
 * Player management has been moved to Game.java.
 */
public class World {
    private final Map<ChunkPos, Chunk> chunks = new java.util.concurrent.ConcurrentHashMap<>();
    private ShadowCasterResidency.Plan casterPlan = new ShadowCasterResidency.Plan(List.of(),0,0,false);
    private Set<ChunkPos> casterChunks = Set.of();
    private int plannedX=Integer.MIN_VALUE, plannedZ=Integer.MIN_VALUE, initialX, initialZ;
    private engine.time.CelestialState.Light plannedLight;
    private final Vector3f plannedDirection = new Vector3f();
    private engine.time.CelestialState frameCelestial;
    public engine.time.CelestialState getFrameCelestial() { return frameCelestial; }
    public boolean usesCoherentResidency() {
        return RenderConfig.SHADOW_CASTER_RESIDENCY && !RenderConfig.LEGACY_CASTER_RESIDENCY;
    }
    private int readyCount(Collection<ChunkPos> positions) {
        int n=0;for(var p:positions){var c=chunks.get(p);if(c!=null&&c.isMeshCurrent())n++;}return n;
    }
    public engine.render.shadow.ShadowCoverage snapshotShadowCoverage(boolean fade) {
        return new engine.render.shadow.ShadowCoverage(fade,RenderConfig.SHADOW_FADE_START,RenderConfig.SHADOW_FADE_END,
                usesCoherentResidency(),readyCount(casterPlan.required()),casterPlan.required().size(),
                readyCount(casterPlan.prefetch()),casterPlan.prefetch().size(),
                allVisibleChunksReady(plannedX,plannedZ,renderDistance),true,casterPlan.budgetLimited());
    }

    public int getCasterRequested() { return casterChunks.size(); }
    public int getCasterReady() {
        return readyCount(casterChunks);
    }
    public ShadowCasterResidency.Plan getCasterPlan() { return casterPlan; }
    private void planCasters(int cx,int cz) {
        var sky=frameCelestial;var dir=usesCoherentResidency()?ShadowCasterResidency.residencyDirection(sky):sky.lightDirection();
        // Coherent mode uses the exact direction of this frame. Do not spend its sampling
        // guard on an unaccounted angular-cache error.
        if(cx==plannedX&&cz==plannedZ&&sky.activeLight()==plannedLight
                && (usesCoherentResidency()?dir.equals(plannedDirection):dir.distanceSquared(plannedDirection)<.000025f))return;
        plannedX=cx;plannedZ=cz;plannedLight=sky.activeLight();plannedDirection.set(dir);
        casterPlan=usesCoherentResidency()
                ?ShadowCasterResidency.coherentPlan(cx,cz,renderDistance,sky,RenderConfig.SHADOW_FADE_END,RenderConfig.SHADOW_CASTER_BUDGET)
                :ShadowCasterResidency.plan(cx,cz,renderDistance,sky,
                    RenderConfig.SHADOW_CASTER_RESIDENCY?RenderConfig.SHADOW_CASTER_REACH:0,RenderConfig.SHADOW_CASTER_BUDGET);
        casterChunks=new HashSet<>(casterPlan.extra());
    }
    private void loadCasters(int budget) {
        int outside=0;
        if(usesCoherentResidency())for(var p:chunks.keySet())
            if(Math.abs(p.x()-plannedX)>renderDistance||Math.abs(p.z()-plannedZ)>renderDistance)outside++;
        for(var pos:casterPlan.extra()) {
            if(chunks.containsKey(pos))continue;
            // A failed save can retain old chunks: do not exceed the budget by adding more.
            if(usesCoherentResidency() && outside>=RenderConfig.SHADOW_CASTER_BUDGET)break;
            addChunk(pos);
            outside++;
            if(--budget==0)break;
        }
    }
    private void addChunk(ChunkPos pos) {
        Chunk chunk=generateChunk(pos);chunks.put(pos,chunk);
        meshManager.queueChunk(chunk);
        // Also required for disk-loaded chunks: the previously exposed cut plane is now internal.
        markNeighborsDirty(pos.x(),pos.z());
    }
    private final long seed;
    private final WorldStorage storage;  // Replaced WorldSaver with WorldStorage
    private final int renderDistance;
    private final WorldGenerator worldGenerator;
    private final Mode mode;
    private final MeshWorkerManager meshManager;
    private TimeManager timeManager;
    private final String worldName;
    private final Matrix4f depthModelMatrix = new Matrix4f();
    private int depthDrawCalls;
    private long depthVertices;

    public void resetDepthStats() { depthDrawCalls = 0; depthVertices = 0; }
    public int getDepthDrawCalls() { return depthDrawCalls; }
    /** GL/main-thread view: callers must not mutate the loaded chunk collection. */
    public Collection<Chunk> getLoadedChunksForRendering() { return Collections.unmodifiableCollection(chunks.values()); }
    public long getDepthVertices() { return depthVertices; }

    public enum Mode {
        FLAT, PERLIN
    }

    public World(String worldName, long seed, Mode mode, int renderDistance, TimeManager timeManager) {
        this.worldName = worldName;
        this.timeManager = timeManager;
        this.mode = mode;
        this.renderDistance = renderDistance;
        this.seed = seed;
        this.storage = new WorldStorage(worldName, seed, mode.toString());
        if (!GameConfig.TEST_HARNESS_ENABLED) timeManager.restoreWorldDays(storage.getWorldDays());
        frameCelestial=timeManager.celestialState();
        if(usesCoherentResidency()) {
            if(RenderConfig.SHADOW_FADE_END+engine.render.shadow.ShadowDistance.SAMPLING_GUARD>RenderConfig.SHADOW_CASTER_REACH)
                throw new IllegalArgumentException("shadowCasterReach must cover shadowFadeEnd + sampling guard");
            ShadowCasterResidency.validateOrbitBudget(renderDistance,RenderConfig.SHADOW_FADE_END,RenderConfig.SHADOW_CASTER_BUDGET);
        }
        this.worldGenerator = new WorldGenerator(seed, this);
        this.meshManager = new MeshWorkerManager(this, Runtime.getRuntime().availableProcessors());

        // Note: No preload here - caller must call startAsyncChunkGeneration()
    }



    public TimeManager getTimeManager() {
        return timeManager;
    }

    public Vector3f getSunDir() {
        return timeManager.getSunData().sunDir();
    }

    /**
     * Preload initial chunks around a position.
     *
     * @param centerChunkX Center chunk X coordinate
     * @param centerChunkZ Center chunk Z coordinate
     */
    public void preloadInitialChunks(int centerChunkX, int centerChunkZ) {
        startAsyncChunkGeneration(centerChunkX, centerChunkZ);
        while (!isInitialLoadComplete()) {
            processChunkLoading();
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Check if all visible chunks around a position are ready.
     *
     * @param centerChunkX Center chunk X
     * @param centerChunkZ Center chunk Z
     * @param radius       Chunk radius
     * @return True if all chunks are ready
     */
    public boolean allVisibleChunksReady(int centerChunkX, int centerChunkZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = chunks.get(new ChunkPos(centerChunkX + dx, centerChunkZ + dz));
                if (chunk == null || !chunk.isMeshCurrent()) return false;
            }
        }
        return true;
    }

    /**
     * Start async chunk generation for initial world load.
     * Non-blocking - call processChunkLoading() each frame to continue.
     *
     * @param centerChunkX Center X coordinate
     * @param centerChunkZ Center Z coordinate
     */
    public void startAsyncChunkGeneration(int centerChunkX, int centerChunkZ) {
        initialX=centerChunkX;initialZ=centerChunkZ;
        frameCelestial=timeManager.celestialState();
        planCasters(centerChunkX,centerChunkZ);
        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                ChunkPos pos = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                if (!chunks.containsKey(pos)) {
                    addChunk(pos);
                }
            }
        }
        System.out.println("All chunks queued for generation");
    }

    /**
     * Process chunk loading for one frame.
     * Call this repeatedly until isInitialLoadComplete() returns true.
     */
    public void processChunkLoading() {
        loadCasters(RenderConfig.SHADOW_CASTER_LOADS_PER_UPDATE);
        meshManager.processMeshes();
        meshManager.uploadMeshesIfAny();
    }

    /**
     * Check if initial chunks are ready for rendering.
     */
    public boolean isInitialLoadComplete() {
        return allVisibleChunksReady(initialX, initialZ, renderDistance) && getCasterReady()==getCasterRequested();
    }

    /**
     * Get loading progress (0.0 to 1.0).
     */
    public float getLoadingProgress() {
        int totalChunks = (2 * renderDistance + 1) * (2 * renderDistance + 1);
        int readyChunks = 0;

        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                Chunk chunk = chunks.get(new ChunkPos(initialX+dx, initialZ+dz));
                if (chunk != null && chunk.isMeshCurrent()) {
                    readyChunks++;
                }
            }
        }

        return (float) (readyChunks+getCasterReady()) / (totalChunks+getCasterRequested());
    }

    public int getSpawnY(){
        Chunk chunk = chunks.get(new ChunkPos(0, 0));
        if(chunk==null)throw new IllegalStateException("Origin must be loaded before choosing a new player's spawn");
        for (int y = Chunk.HEIGHT; y >= 0; y--) {
            if (chunk.getBlockId(0, y, 0) != 0){
                return y;
            }
        }
        return 256;
    }

    public MeshWorkerManager getMeshManager() {
        return meshManager;
    }

    public Chunk getChunk(int chunkX, int chunkZ) {
        return chunks.get(new ChunkPos(chunkX, chunkZ));
    }

    public int getChunkCount() {
        return chunks.size();
    }

    public long getMeshBytes() {
        long total = 0;
        for (Chunk chunk : chunks.values()) total += chunk.getMeshBytes();
        return total;
    }

    public long getStoredVertices() {
        long total = 0;
        for (Chunk chunk : chunks.values()) total += chunk.getVertexCount();
        return total;
    }

    public Block getBlockAt(int x, int y, int z) {
        int chunkX = Math.floorDiv(x, Chunk.WIDTH);
        int chunkZ = Math.floorDiv(z, Chunk.DEPTH);
        int localX = Math.floorMod(x, Chunk.WIDTH);
        int localZ = Math.floorMod(z, Chunk.DEPTH);

        Chunk chunk = getChunk(chunkX, chunkZ);
        if (chunk == null) return BlockRegistry.getBlock((short) 0);
        return chunk.getBlock(localX, y, localZ);
    }

    public void setBlockAt(int x, int y, int z, short id) {
        int chunkX = Math.floorDiv(x, Chunk.WIDTH);
        int chunkZ = Math.floorDiv(z, Chunk.DEPTH);
        int localX = Math.floorMod(x, Chunk.WIDTH);
        int localZ = Math.floorMod(z, Chunk.DEPTH);

        Chunk chunk = getChunk(chunkX, chunkZ);
        if (chunk != null) {
            chunk.setBlock(localX, y, localZ, id);
            meshManager.queueChunk(chunk);
            if(localX==0 || localX==Chunk.WIDTH-1 || localZ==0 || localZ==Chunk.DEPTH-1)
                markNeighborsDirty(chunkX,chunkZ);
        }
    }

    /**
     * Interactive block modification (player break/place) with immediate same-frame remeshing
     * of the modified chunk and any affected boundary neighbor chunks (including corner AO neighbors).
     */
    public boolean setBlockAtInteractive(int x, int y, int z, short id) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;
        int chunkX = Math.floorDiv(x, Chunk.WIDTH);
        int chunkZ = Math.floorDiv(z, Chunk.DEPTH);
        int localX = Math.floorMod(x, Chunk.WIDTH);
        int localZ = Math.floorMod(z, Chunk.DEPTH);

        Chunk chunk = getChunk(chunkX, chunkZ);
        if (chunk == null) return false;

        chunk.setBlock(localX, y, localZ, id);
        meshManager.rebuildChunkImmediate(chunk);

        int dx = (localX == 0) ? -1 : (localX == Chunk.WIDTH - 1 ? 1 : 0);
        int dz = (localZ == 0) ? -1 : (localZ == Chunk.DEPTH - 1 ? 1 : 0);
        if (dx != 0) {
            Chunk nx = getChunk(chunkX + dx, chunkZ);
            if (nx != null) meshManager.rebuildChunkImmediate(nx);
        }
        if (dz != 0) {
            Chunk nz = getChunk(chunkX, chunkZ + dz);
            if (nz != null) meshManager.rebuildChunkImmediate(nz);
        }
        if (dx != 0 && dz != 0) {
            Chunk nDiag = getChunk(chunkX + dx, chunkZ + dz);
            if (nDiag != null) meshManager.rebuildChunkImmediate(nDiag);
        }
        return true;
    }

    public Chunk generateChunk(ChunkPos pos) {
        // Try to load from disk first
        Chunk loaded = storage.loadChunk(pos.x(), pos.z());
        if (loaded != null) {
            // Chunk was loaded from disk, it's clean
            return loaded;
        }

        // If not on disk, generate new chunk
        Chunk generated = new Chunk(pos.x(), pos.z());
        if (mode == Mode.FLAT) {
            this.worldGenerator.generateFlat(generated);
        } else {
            this.worldGenerator.generatePerlin(generated);
        }

        // Mark as needing save (newly generated)
        generated.setDataDirty(true);

        return generated;
    }

    private void markNeighborsDirty(int chunkX, int chunkZ) {
        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
        for (int[] dir : dirs) {
            int nx = chunkX + dir[0];
            int nz = chunkZ + dir[1];
            Chunk neighbor = getChunk(nx, nz);
            if (neighbor != null) {
                meshManager.queueChunk(neighbor);
            }
        }
    }

    public void save() {
        if (GameConfig.TEST_HARNESS_ENABLED) return;
        if (!timeManager.hasLaunchOverrides()) storage.setWorldDays(timeManager.getWorldDays());
        // Save all dirty chunks (modified data)
        for (Chunk chunk : chunks.values()) {
            if (chunk.isDataDirty()) {
                storage.saveChunkAsync(chunk);
            }
        }

        // Save metadata
        storage.saveMetadata();

        // Player saving is handled separately by Game.java
    }

    public WorldStorage getStorage() {
        return storage;
    }

    public Map<ChunkPos, Chunk> getAllChunks() {
        return chunks;
    }

    /**
     * Update world (process meshes, update chunks).
     * Player update is handled separately in Game.java.
     *
     * @param playerChunkX Player chunk X coordinate
     * @param playerChunkZ Player chunk Z coordinate
     */
    public void update(int playerChunkX, int playerChunkZ) {
        frameCelestial=timeManager.celestialState();
        updateVisibleChunks(playerChunkX, playerChunkZ, renderDistance);
        planCasters(playerChunkX,playerChunkZ);
        unloadFarChunks(playerChunkX, playerChunkZ, renderDistance + (usesCoherentResidency()?0:2));
        loadCasters(RenderConfig.SHADOW_CASTER_LOADS_PER_UPDATE);
        meshManager.processMeshes();
    }

    /**
     * Get all visible chunks around a position.
     *
     * @param centerChunkX Center chunk X
     * @param centerChunkZ Center chunk Z
     * @param radius       Chunk radius
     * @return List of visible chunks
     */
    public List<Chunk> getVisibleChunks(int centerChunkX, int centerChunkZ, int radius) {
        List<Chunk> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                Chunk chunk = chunks.get(pos);
                if (chunk != null) {
                    list.add(chunk);
                }
            }
        }
        return list;
    }

    /**
     * Update visible chunks around player position.
     *
     * @param centerChunkX Center chunk X
     * @param centerChunkZ Center chunk Z
     * @param radius       Chunk radius
     */
    private void updateVisibleChunks(int centerChunkX, int centerChunkZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                if (!chunks.containsKey(pos)) {
                    addChunk(pos);
                }
            }
        }
    }

    /**
     * Unload chunks that are too far from player.
     *
     * @param centerChunkX Center chunk X
     * @param centerChunkZ Center chunk Z
     * @param maxDistance  Maximum distance in chunks
     */
    private void unloadFarChunks(int centerChunkX, int centerChunkZ, int maxDistance) {
        var removed=new ArrayList<ChunkPos>();
        for(var entry:chunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            int dx = Math.abs(pos.x() - centerChunkX);
            int dz = Math.abs(pos.z() - centerChunkZ);
            boolean toRemove = (dx > maxDistance || dz > maxDistance) && !casterChunks.contains(pos);
            if (toRemove) {
                Chunk chunk=entry.getValue();
                if(chunk.isDataDirty() && !GameConfig.TEST_HARNESS_ENABLED) {
                    try { storage.saveChunk(chunk); }
                    catch(java.io.IOException e) { System.err.println("Cannot unload unsaved chunk "+pos+": "+e);continue; }
                }
                chunks.remove(pos,chunk);chunk.retire();chunk.cleanup();removed.add(pos);
            }
        }
        // A retired neighbor is air again; rebuild the boundary, not just its old shadow mesh.
        for(var pos:removed)markNeighborsDirty(pos.x(),pos.z());
    }

    public void renderDepth(Shader depthShader) {
        renderDepth(depthShader, null, -1.0f);
    }

    /**
     * Render depth for shadow mapping with conservative cascade distance culling.
     *
     * @param depthShader Shader used for shadow depth pass
     * @param center World-space center of the cascade bounding volume (nullable)
     * @param radius Maximum conservative radius for chunks casting/receiving shadows (negative = no culling)
     */
    public void renderDepth(Shader depthShader, Vector3f center, float radius) {
        renderDepth(depthShader, center, radius, null);
    }

    /** Optional light-space frustum, never the camera frustum: off-screen casters stay eligible. */
    public void renderDepth(Shader depthShader, Vector3f center, float radius, FrustumIntersection lightFrustum) {
        renderDepth(depthShader, center, radius, lightFrustum, RenderConfig.GREEDY_SHADOWS);
    }

    public void renderDepth(Shader depthShader, Vector3f center, float radius, FrustumIntersection lightFrustum, boolean greedy) {
        float radiusSq = radius > 0 ? radius * radius : -1.0f;
        float centerX = center != null ? center.x : 0.0f;
        float centerZ = center != null ? center.z : 0.0f;

        for (Chunk chunk : chunks.values()) {
            if (!chunk.isReady()) continue; // le mesh n’est pas encore prêt
            int vao = chunk.getVao();
            int vertexCount = chunk.getVertexCount();
            if (vao == -1 || vertexCount == 0) continue;

            int worldX = chunk.getChunkX() * Chunk.WIDTH;
            int worldZ = chunk.getChunkZ() * Chunk.DEPTH;

            if (radiusSq > 0) {
                float midX = worldX + (Chunk.WIDTH * 0.5f);
                float midZ = worldZ + (Chunk.DEPTH * 0.5f);
                float dx = midX - centerX;
                float dz = midZ - centerZ;
                if (dx * dx + dz * dz > radiusSq) {
                    continue;
                }
            }

            if (lightFrustum != null) {
                MeshBounds bounds = chunk.getMeshBounds();
                boolean visible = RenderConfig.TIGHT_SHADOW_BOUNDS && bounds != null
                        ? bounds.intersects(lightFrustum, worldX, worldZ)
                        : intersectsShadowFrustum(lightFrustum, worldX, worldZ);
                if (!visible) continue;
            }

            depthModelMatrix.translation(worldX, 0, worldZ);
            depthShader.setMat4("modelMatrix", depthModelMatrix);

            boolean useShadow = greedy && chunk.getShadowMesh() != null;
            if (useShadow) chunk.getShadowMesh().draw();
            else {
                glBindVertexArray(vao);
                engine.render.MeshBuilder.drawBoundChunk(chunk);
            }
            depthDrawCalls++;
            depthVertices += useShadow ? chunk.getShadowMesh().count : chunk.getDrawCount();
        }

        glBindVertexArray(0);
    }

    /** Full chunk bounds, including the top face at HEIGHT; padding keeps boundary cases conservative. */
    static boolean intersectsShadowFrustum(FrustumIntersection frustum, float worldX, float worldZ) {
        final float padding = 0.01f;
        return frustum.testAab(worldX - padding, -padding, worldZ - padding,
                worldX + Chunk.WIDTH + padding, Chunk.HEIGHT + padding, worldZ + Chunk.DEPTH + padding);
    }

    private Chunk getOrGenerateChunk(int x, int z) {
        ChunkPos pos = new ChunkPos(x, z);
        return chunks.computeIfAbsent(pos, this::generateChunk);
    }

    public long getSeed(){
        return seed;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getRenderDistance(){
        return renderDistance;
    }

    /**
     * Load an existing world from disk.
     * @param worldName World name
     * @param renderDistance Render distance
     * @param timeManager Time manager
     * @return Loaded world, or null if world doesn't exist
     */
    public static World loadFromSave(String worldName, int renderDistance, TimeManager timeManager) {
        WorldMetadata metadata = WorldStorage.loadMetadata(worldName);
        if (metadata == null) {
            System.err.println("World not found: " + worldName);
            return null;
        }

        // Parse generation mode
        Mode mode;
        try {
            mode = Mode.valueOf(metadata.mode);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown generation mode: " + metadata.mode);
            mode = Mode.PERLIN;
        }

        // Create world with loaded metadata
        World world = new World(worldName, metadata.seed, mode, renderDistance, timeManager);

        System.out.println("Loaded world: " + worldName +
                          " (seed=" + metadata.seed + ", mode=" + mode + ")");

        return world;
    }

    /**
     * Cleanup world resources.
     * CRITICAL: Must be called when world is no longer needed to stop background threads.
     */
    public void cleanup() {
        // Shutdown mesh generation threads
        meshManager.shutdown();

        // Shutdown storage executor
        storage.shutdown();

        // Détruire tous les meshes GPU des chunks actifs
        for (Chunk chunk : chunks.values()) {
            chunk.retire();
            chunk.cleanup();
        }
        chunks.clear();
        }
}
