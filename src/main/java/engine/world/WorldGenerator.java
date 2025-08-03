package engine.world;

public class WorldGenerator {

    private final World world;

    // Variables de configuration facilement modifiables :
    private static final int    BUFFER_DEPTH = 20; // hauteur de la couche minable (tampon)
    private static final double PERLIN_SCALE = 0.01;        // "Zoom" du bruit
    public static double HEIGHT_BASE = -54;
    private static final double HEIGHT_AMPLITUDE = 228.0; // Plus grand = montagnes hautes
    public static boolean PERLIN_TERRACING = false;
    public static int PERLIN_TERRACE_HEIGHT = 8;
    private static final double RAVINE_FREQ = 0.02; // Baisse = moins de ravines, plus larges
    private static final double RAVINE_THRESHOLD = 0.65; // Plus haut = moins de ravines
    private static final double RAVINE_DEPTH = 0.4; // 0.1 (léger) à 1.0 (très profond)
    // Configuration globale pour les effets de terrain
    private static final boolean ENABLE_MOUNTAINS = true;
    private static final boolean ENABLE_RAVINES = false;
    private static final boolean ENABLE_FALAISES = false;

    private static final double PERLIN_EXPONENT_MOUNTAINS = 7.0; // Plus grand = pics plus abrupts
    private static final double FALAISE_STEEPNESS = 7.0; // >5 = falaises nettes

    private static final int TUNNELS_PAR_CHUNK = 2;
    private static final float PROBA_SURFACE = 0.05f;
    private static final int TUNNEL_LONGUEUR_MIN = 20;
    private static final int TUNNEL_LONGUEUR_VAR = 50;        // max length = min + var
    private static final int GROTTE_RAYON = 4;                // rayon du tunnel
    private static final double SINUOSITE = 0.2;             // plus haut = plus de zigzag
    private static final double PITCH_VAR = 0.08;             // variation verticale
    private static final int REGION_RADIUS = 4;
    private static final int PROFONDEUR_ENTREE = 2; // À combien de blocs sous la surface commence le tunnel

    private static OpenSimplexNoise noise = new OpenSimplexNoise(0);
    private static OpenSimplexNoise noise2 = new OpenSimplexNoise(0); // Pour ravines ou falaises

    public WorldGenerator(long seed, World world) {
        noise = new OpenSimplexNoise(seed);
        noise2 = new OpenSimplexNoise(seed + 42);
        this.world = world;
    }


    public void generateFlat(Chunk chunk) {
        for (int x = 0; x < Chunk.WIDTH; x++) {
            for (int z = 0; z < Chunk.DEPTH; z++) {
                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    if (y == 0) chunk.setBlock(x, y, z, (short) 3); // stone
                    else if (y <= 3) chunk.setBlock(x, y, z, (short) 2); // dirt
                        // grass
                    else if (y == 4) {
                        chunk.setBlock(x, y, z, (short) 1);
                    }
                    else chunk.setBlock(x, y, z, (short) 0); // air
                }
            }
        }
    }


    public void generateChunkGrottes(World world, Chunk chunk, java.util.Random random) {
        int baseChunkX = chunk.getChunkX();
        int baseChunkZ = chunk.getChunkZ();
        int baseX = baseChunkX * Chunk.WIDTH;
        int baseZ = baseChunkZ * Chunk.DEPTH;

        // Génére les tunnels de la "région" autour du chunk courant
        for (int cx = baseChunkX - REGION_RADIUS; cx <= baseChunkX + REGION_RADIUS; cx++) {
            for (int cz = baseChunkZ - REGION_RADIUS; cz <= baseChunkZ + REGION_RADIUS; cz++) {
                long tunnelSeed = world.getSeed() ^ (cx * 341873128712L) ^ (cz * 132897987541L);
                java.util.Random tunnelRandom = new java.util.Random(tunnelSeed);

                for (int t = 0; t < TUNNELS_PAR_CHUNK; t++) {
                    int entryX = cx * Chunk.WIDTH + tunnelRandom.nextInt(Chunk.WIDTH);
                    int entryZ = cz * Chunk.DEPTH + tunnelRandom.nextInt(Chunk.DEPTH);

                    // Détermine si le tunnel commence à la surface ou en profondeur
                    boolean startAtSurface = tunnelRandom.nextFloat() < PROBA_SURFACE;
                    int entryY;
                    if (startAtSurface) {
                        entryY = findSurfaceYInWorld(world, entryX, entryZ) - 1;
                        if (entryY < 4) entryY = 8 + tunnelRandom.nextInt(24);
                    } else {
                        entryY = 6 + tunnelRandom.nextInt(36);
                    }

                    // Direction initiale du tunnel
                    double angle = tunnelRandom.nextDouble() * Math.PI * 2;
                    double pitch = (tunnelRandom.nextDouble() - 0.25) * 0.15 - 0.15;
                    int longueur = TUNNEL_LONGUEUR_MIN + tunnelRandom.nextInt(TUNNEL_LONGUEUR_VAR);

                    // Parcours du tunnel
                    double x = entryX + 0.5;
                    double y = entryY + 0.5;
                    double z = entryZ + 0.5;

                    for (int i = 0; i < longueur; i++) {
                        int bx = (int) Math.round(x) - baseX;
                        int by = (int) Math.round(y);
                        int bz = (int) Math.round(z) - baseZ;
                        if (chunk.inBounds(bx, by, bz)) {
                            for (int dx = -GROTTE_RAYON; dx <= GROTTE_RAYON; dx++)
                                for (int dy = -GROTTE_RAYON; dy <= GROTTE_RAYON; dy++)
                                    for (int dz = -GROTTE_RAYON; dz <= GROTTE_RAYON; dz++) {
                                        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                                        if (dist <= GROTTE_RAYON + 0.3) {
                                            int gx = bx + dx;
                                            int gy = by + dy;
                                            int gz = bz + dz;
                                            if (chunk.inBounds(gx, gy, gz)) {
                                                chunk.setBlock(gx, gy, gz, (short) 0); // creuser (air)
                                            }
                                        }
                                    }
                        }
                        // Avance le tunnel
                        x += Math.cos(angle) * Math.cos(pitch);
                        y += Math.sin(pitch);
                        z += Math.sin(angle) * Math.cos(pitch);

                        angle += (tunnelRandom.nextDouble() - 0.5) * SINUOSITE;
                        pitch += (tunnelRandom.nextDouble() - 0.5) * PITCH_VAR;

                        if (y < 2) break; // stop si trop profond
                    }
                }
            }
        }
    }

    // Cherche la surface pour (x,z) absolu dans le monde
    private static int findSurfaceYInWorld(World world, int x, int z) {
        for (int y = Chunk.HEIGHT - 2; y > 0; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (b != null && b.getId() != 0) return y;
        }
        return 0;
    }

    public void generatePerlin(Chunk chunk) {
        int baseX = chunk.getChunkX() * Chunk.WIDTH;
        int baseZ = chunk.getChunkZ() * Chunk.DEPTH;

        for (int x = 0; x < Chunk.WIDTH; x++) {
            for (int z = 0; z < Chunk.DEPTH; z++) {
                double worldX = baseX + x;
                double worldZ = baseZ + z;

                double n = noise.eval(worldX * PERLIN_SCALE, worldZ * PERLIN_SCALE);

                // --- Appliquer différents effets ---
                // 1. MONTAGNES (pics et plateaux)
                if (ENABLE_MOUNTAINS) {
                    n = Math.abs(n);
                    n = Math.pow(n, PERLIN_EXPONENT_MOUNTAINS);
                }

                // 2. FALAISES (transition abrupte, plateaux avec "falaises" verticales)
                if (ENABLE_FALAISES) {
                    n = Math.tanh(n * FALAISE_STEEPNESS);
                }
                /*
                // 3. RAVINS (creuse des trous ou failles)
                if (ENABLE_RAVINES) {
                    // Ajout d'un bruit secondaire pour les ravins (possible d'utiliser une autre seed/noise)
                    double ravineNoise = noise.eval((worldX + 1000) * 0.04, (worldZ - 1000) * 0.04);
                    double ravine = Math.abs(Math.sin(ravineNoise * Math.PI * 2.0)); // bandes sinusoïdales
                    if (ravine > (1.0 - RAVINE_DEPTH)) {
                        // On creuse un ravin
                        n -= 0.5 + 0.5 * (ravine - (1.0 - RAVINE_DEPTH)) / RAVINE_DEPTH;
                    }
                }
                */
                // --- Calcul de la hauteur finale ---
                double hF = ((n + 1.0) / 2.0) * HEIGHT_AMPLITUDE + HEIGHT_BASE;

                if (PERLIN_TERRACING) {
                    hF = Math.round(hF / PERLIN_TERRACE_HEIGHT) * PERLIN_TERRACE_HEIGHT;
                }
                if (ENABLE_RAVINES) {
                    double ravine = Math.abs(noise2.eval(worldX * RAVINE_FREQ, worldZ * RAVINE_FREQ));
                    if (ravine > RAVINE_THRESHOLD) {
                        double d = (ravine - RAVINE_THRESHOLD) / (1.0 - RAVINE_THRESHOLD);
                        d = Math.pow(d, 1.5);
                        hF -= d * RAVINE_DEPTH * HEIGHT_AMPLITUDE;
                    }
                }
                int groundY = BUFFER_DEPTH + (int) hF;

                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    if (y < BUFFER_DEPTH) {
                        chunk.setBlock(x, y, z, (short) 3); // stone
                    } else if (y > groundY) {
                        chunk.setBlock(x, y, z, (short) 0); // air
                    } else if (y == groundY) {
                        chunk.setBlock(x, y, z, (short) 1); // grass
                    } else if (y >= groundY - 3) {
                        chunk.setBlock(x, y, z, (short) 2); // dirt
                    } else {
                        chunk.setBlock(x, y, z, (short) 3); // stone
                    }
                }
            }
        }
        java.util.Random random = new java.util.Random(
                (chunk.getChunkX() * 341873128712L) ^ (chunk.getChunkZ() * 132897987541L)
        );
        generateChunkGrottes(this.world, chunk, random);
        for (int x = 0; x < Chunk.WIDTH; x++) {
            for (int z = 0; z < Chunk.DEPTH; z++) {
                chunk.setBlock(x, 0, z, (short) 4);
            }
        }
    }
}