package engine.player;

import engine.input.Input;
import engine.world.Block;
import engine.world.Chunk;
import engine.world.World;

public class Player {
    // Position réelle (centre du joueur)
    private float x, y, z;
    // Vitesse par axe
    private float vx, vy, vz;
    // Orientation (en degrés)
    private float yaw, pitch;
    // Mode de jeu
    private boolean spectatorMode = true;

    // Constantes physiques
    private static final float SPEED = 1.0f;
    private static final float WIDTH = 0.6f;
    public static final float HEIGHT = 2f;
    private static final float GRAVITY = -0.04f;
    private static final float JUMP_VELOCITY = 0.42f;
    private static final float FRICTION = 0.6f;
    private boolean jumpPressedLastFrame = false;
    private boolean onGround = false;

    public Player(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void update(Chunk chunk, Input input, World world) {
        handleInput(input, world);

        // Souris : yaw/pitch (clamp pitch pour éviter flip)
        yaw   += input.getMouseDeltaX() * 0.1f; // Signe - pour comportement "FPS classique"
        pitch += input.getMouseDeltaY() * 0.1f;
        pitch = Math.max(-89f, Math.min(89f, pitch));

        if (spectatorMode) {
            float speed = SPEED;
            // Monter/Descendre
            if (input.jump) vy += 1.0f;
            // Ajoute cette condition pour shift/crouch
            if (input.crouch) vy -= 1.0f;

            // Normalisation
            float len = (float) Math.sqrt(vx*vx + vy*vy + vz*vz);
            if (len > 0.001f) {
                vx = vx/len * speed;
                vy = vy/len * speed;
                vz = vz/len * speed;
            }

            // Pas de collision ni gravité
            x += vx;
            y += vy;
            z += vz;
            vx = 0;
            vy = 0;
            vz = 0;
            return;
        }

        // Gravité
        if (!onGround) {
            vy += GRAVITY;
        }


        // Saut (un seul par pression, uniquement au sol)
        boolean jumpPressed = input.jump;
        if (jumpPressed && !jumpPressedLastFrame && onGround) {
            vy = JUMP_VELOCITY;
            onGround = false;
        }
        jumpPressedLastFrame = jumpPressed;

        // --- PHYSIQUE AXIS PAR AXIS ---
        float dx = vx, dy = vy, dz = vz;

        // *** COLLISIONS (AABB rigide, pas d’auto-step) ***
        x = moveAxis(x, vx, WIDTH, HEIGHT, WIDTH, Axis.X, chunk);
        y = moveAxis(y, vy, WIDTH, HEIGHT, WIDTH, Axis.Y, chunk);
        z = moveAxis(z, vz, WIDTH, HEIGHT, WIDTH, Axis.Z, chunk);
        // --- Collision au sol (vérifie le bloc juste sous les pieds) ---
        if (vy <= 0) { // On tombe
            int feetY = (int)Math.floor(y - 0.01f);
            int cx = (int)Math.floor(x);
            int cz = (int)Math.floor(z);
            if (chunk.inBounds(cx, feetY, cz) && chunk.getBlockId(cx, feetY, cz) != 0) {
                y = feetY + 1.0f; // pose les pieds sur le dessus du bloc
                vy = 0;
                onGround = true;
            } else {
                onGround = false;
            }
        } else {
            onGround = false;
        }

        // --- Détection du sol stricte : onGround SI ET SEULEMENT SI bloc sous les pieds ---
        //onGround = isStandingOnSolid(chunk);

        // Friction horizontale
        vx *= FRICTION;
        vz *= FRICTION;
    }

    /** Déplacement relatif à la caméra (WASD/ZQSD, Yaw) */
    private void handleInput(Input input, World world) {
        float speed = SPEED; // Ajuste à ton goût

        float rad = (float) Math.toRadians(yaw);
        float dirX = (float) Math.sin(rad);
        float dirZ = (float) -Math.cos(rad);

        // Strafe vector (à droite du regard)
        float strafeX = (float) Math.sin(rad - Math.PI / 2);
        float strafeZ = (float) -Math.cos(rad - Math.PI / 2);

        if (input.forward) {
            vx += dirX * speed;
            vz += dirZ * speed;
        }
        if (input.backward) {
            vx -= dirX * speed;
            vz -= dirZ * speed;
        }
        if (input.left) {
            vx += strafeX * speed;
            vz += strafeZ * speed;
        }
        if (input.right) {
            vx -= strafeX * speed;
            vz -= strafeZ * speed;
        }
        if (input.coords) {
            System.out.println("Coords : (x,y,z) = " + x + ", " + y + ", " + z);
            System.out.println("Coords_int = " + (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z));
            int Cx = (int) x / Chunk.WIDTH;
            int Cz = (int) z / Chunk.WIDTH;
            System.out.println("GroundY = " + getGroundY(world.getChunk(Cx, Cz)));
        }
    }

    /**
     * Applique les déplacements au joueur en fonction des vitesses calculées à partir des inputs
     * @param pos position du joueur sur l'axe
     * @param d vitesse calculée pour cette frame
     * @param sx épaisseur du joueur
     * @param sy hauteur du joueur
     * @param sz largeur du joueur
     * @param axis axe de déplacement
     * @param chunk
     * @return
     */
    private float moveAxis(float pos, float d, float sx, float sy, float sz, Axis axis, Chunk chunk) {
        float step = (d > 0) ? 1 : -1;
        while (Math.abs(d) > 0.0001f) {
            float move = Math.min(Math.abs(d), 0.05f) * step;
            d -= move;
            float next = pos + move;
            if (!collides(next, sx, sy, sz, axis, chunk)) {
                pos = next;
            } else {
                break;
            }
        }
        return pos;
    }
    /**
     * Applique les déplacements au joueur sur l'axe Y uniquement en fonction des vitesses calculées à partir des inputs
     * @param pos position du joueur sur l'axe
     * @param d vitesse calculée pour cette frame
     * @param sx épaisseur du joueur
     * @param sy hauteur du joueur
     * @param sz largeur du joueur
     * @param chunk
     * @return
     */
    private float moveAxisY(float pos, float d, float sx, float sy, float sz, Chunk chunk) {
        float step = (d > 0) ? 1 : -1;
        while (Math.abs(d) > 0.0001f) {
            float move = Math.min(Math.abs(d), 0.05f) * step;
            d -= move;
            float next = pos + move;
            if (!collides(next, sx, sy, sz, Axis.Y, chunk)) {
                pos = next;
            } else {
                if (move < 0) {
                    // Si tu tombes : pose-toi juste au-dessus du bloc solide
                    float feet = pos - HEIGHT / 2;
                    float blockY = (float) Math.floor(feet);
                    pos = blockY + HEIGHT / 2 + 0.01f; // 0.01f = epsilon sécurité
                    vy = 0;
                } else if (move > 0) {
                    // Si tu montes : bloque juste sous le plafond
                    float head = pos + HEIGHT / 2;
                    float blockY = (float) Math.floor(head + 0.01f);
                    pos = blockY - HEIGHT / 2 - 0.01f;
                    vy = 0;
                }
                break;
            }
        }
        return pos;
    }

    /** Test collision stricte : ne permet pas de "step up" d'un bloc comme Minecraft */
    private boolean collides(float pos, float sx, float sy, float sz, Axis axis, Chunk chunk) {
        // Teste la box du joueur dans le chunk courant
        float px = (axis == Axis.X) ? pos : x;
        float py = (axis == Axis.Y) ? pos : y;
        float pz = (axis == Axis.Z) ? pos : z;

        // y = pieds -> AABB de y à y+HEIGHT
        int minX = (int)Math.floor(px - sx / 2);
        int maxX = (int)Math.floor(px + sx / 2 - 1e-6);
        int minY = (int)Math.floor(py);
        int maxY = (int)Math.floor(py + sy - 1e-6);
        int minZ = (int)Math.floor(pz - sz / 2);
        int maxZ = (int)Math.floor(pz + sz / 2 - 1e-6);

        for (int xi = minX; xi <= maxX; xi++) {
            for (int yi = minY; yi <= maxY; yi++) {
                for (int zi = minZ; zi <= maxZ; zi++) {
                    if (!chunk.inBounds(xi, yi, zi)) continue;
                    if (chunk.getBlockId(xi, yi, zi) != 0) return true;
                }
            }
        }
        if (axis == Axis.Y) {
            if (py >= getGroundY(chunk) + 0.01f){
                return false;
            }
            return true;
        } else {
            return false;
        }
    }

    public int getGroundY(Chunk chunk) {
        int py = (int) Math.floor(y);
        int px = (int) Math.floor(x);
        int pz = (int) Math.floor(z);
        for (int i = py; i >= 0; i--){
            if (chunk.getBlockId(px,i,pz) == 0){
                return i;
            }
        }
        return py;
    }

    /** Détection stricte du "sol" (un bloc solide sous les pieds) */
    private boolean isStandingOnSolid(Chunk chunk) {
        float feetY = y - HEIGHT/2 - 0.05f; // base du joueur - petit epsilon
        int minX = (int)Math.floor(x - WIDTH / 2);
        int maxX = (int)Math.floor(x + WIDTH / 2);
        int minZ = (int)Math.floor(z - WIDTH / 2);
        int maxZ = (int)Math.floor(z + WIDTH / 2);
        int yFeet = (int)Math.floor(feetY);

        for (int xi = minX; xi <= maxX; xi++) {
            for (int zi = minZ; zi <= maxZ; zi++) {
                if (!chunk.inBounds(xi, yFeet, zi)) continue;
                if (chunk.getBlockId(xi, yFeet, zi) != 0) return true;
            }
        }
        return false;
    }

    private enum Axis { X, Y, Z }

    // ===== Getters & setters =====
    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }

    public void setPosition(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    public void setYaw(float yaw) { this.yaw = yaw; }
    public void setPitch(float pitch) { this.pitch = pitch; }

    public int getChunkX() { return (int)Math.floor(x / Chunk.WIDTH); }
    public int getChunkZ() { return (int)Math.floor(z / Chunk.DEPTH); }
    public void setSpectatorMode(boolean enabled) { this.spectatorMode = enabled; }
    public boolean isSpectatorMode() { return spectatorMode; }
}