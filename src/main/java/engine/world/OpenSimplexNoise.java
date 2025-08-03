package engine.world;

public class OpenSimplexNoise {
    private static final int PSIZE = 256;
    private static final int PMASK = PSIZE - 1;

    private final short[] perm;

    public OpenSimplexNoise(long seed) {
        perm = new short[PSIZE];
        short[] source = new short[PSIZE];
        for (short i = 0; i < PSIZE; i++) source[i] = i;

        java.util.Random random = new java.util.Random(seed);
        for (int i = PSIZE - 1; i >= 0; i--) {
            int r = random.nextInt(i + 1);
            perm[i] = source[r];
            source[r] = source[i];
        }
    }

    public double eval(double x, double y) {
        int xi = fastFloor(x);
        int yi = fastFloor(y);

        double xf = x - xi;
        double yf = y - yi;

        double u = fade(xf);
        double v = fade(yf);

        int aa = hash(xi,     yi);
        int ab = hash(xi,     yi + 1);
        int ba = hash(xi + 1, yi);
        int bb = hash(xi + 1, yi + 1);

        double x1, x2;
        x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u);
        x2 = lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u);
        return (lerp(x1, x2, v) + 1) / 2; // Normalisé entre 0 et 1
    }

    private int hash(int x, int y) {
        return perm[(perm[x & PMASK] + y) & PMASK];
    }

    private static int fastFloor(double x) {
        return x >= 0 ? (int)x : (int)x - 1;
    }

    private static double fade(double t) {
        // Courbe de lissage classique pour bruit de Perlin
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y) {
        // gradients simplifiés (Perlin classique)
        switch (hash & 3) {
            case 0: return  x + y;
            case 1: return -x + y;
            case 2: return  x - y;
            case 3: return -x - y;
            default: return 0; // unreachable
        }
    }
}