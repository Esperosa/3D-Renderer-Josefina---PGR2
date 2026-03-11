package engine.render.post;

import java.util.Random;

/**
 * Tady držím generátor prahové mapy podobné modrému šumu.
 * Protože nemám externí knihovny, používám aproximaci typu void-and-cluster
 * nebo malou předpočítanou dlaždici, kterou opakuju beze švu.
 */
public final class BlueNoiseGenerator {

    private final int[][] thresholdMap;
    private final int mapSize;
    private final int maxLevel;

    public BlueNoiseGenerator(int size) {
        this(size, 256);
    }

    public BlueNoiseGenerator(int size, int maxLevel) {
        this.mapSize = Math.max(2, size);
        this.maxLevel = Math.max(2, maxLevel);
        this.thresholdMap = new int[this.mapSize][this.mapSize];
        generate();
    }

    /**
     * Tady vygeneruju prahovou mapu podobnou modrému šumu.
     * Používám zjednodušený algoritmus typu void-and-cluster:
     * 1. začnu s prázdnou mřížkou
     * 2. iterativně pokládám body tam, kde vyjde nejnižší energie
     * 3. podle pořadí pokládání přiřadím pořadí prahu
     */
    public final void generate() {
        int total = mapSize * mapSize;
        boolean[] used = new boolean[total];
        int[] order = new int[total];
        Random random = new Random(0x9E3779B97F4A7C15L ^ (long) mapSize * 73856093L);

        int placed = 0;
        while (placed < total) {
            int bestIndex = -1;
            double bestDist = -1.0;

            int candidateCount = placed == 0 ? 1 : Math.min(64, total - placed + 8);
            for (int c = 0; c < candidateCount; c++) {
                int idx = nextFreeIndex(used, random, total);
                double dist = minToroidalDistanceSq(idx, order, placed);
                if (dist > bestDist) {
                    bestDist = dist;
                    bestIndex = idx;
                }
            }

            used[bestIndex] = true;
            order[placed++] = bestIndex;
        }

        int denom = Math.max(1, total - 1);
        for (int rank = 0; rank < total; rank++) {
            int idx = order[rank];
            int x = idx % mapSize;
            int y = idx / mapSize;
            int level = (int) Math.round((rank / (double) denom) * (maxLevel - 1));
            thresholdMap[y][x] = level;
        }
    }

    /**
     * Tady vrátím threshold hodnotu na tiled souřadnicích.
     *
     * @param x sem předám pixelovou souřadnici x opakovanou přes modulo
     * @param y sem předám pixelovou souřadnici y opakovanou přes modulo
     * @return tím vrátím práh v intervalu [0, maxLevel)
     */
    public int getThreshold(int x, int y) {
        int tx = Math.floorMod(x, mapSize);
        int ty = Math.floorMod(y, mapSize);
        return thresholdMap[ty][tx];
    }

    public int getMapSize() {
        return mapSize;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    private int nextFreeIndex(boolean[] used, Random random, int total) {
        int idx = random.nextInt(total);
        if (!used[idx]) {
            return idx;
        }
        for (int i = 1; i < total; i++) {
            int probe = (idx + i) % total;
            if (!used[probe]) {
                return probe;
            }
        }
        return 0;
    }

    private double minToroidalDistanceSq(int index, int[] order, int count) {
        if (count <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        int x = index % mapSize;
        int y = index / mapSize;
        double minDistSq = Double.POSITIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            int other = order[i];
            int ox = other % mapSize;
            int oy = other / mapSize;

            int dx = Math.abs(x - ox);
            int dy = Math.abs(y - oy);
            dx = Math.min(dx, mapSize - dx);
            dy = Math.min(dy, mapSize - dy);

            double d2 = dx * dx + dy * dy;
            if (d2 < minDistSq) {
                minDistSq = d2;
            }
        }
        return minDistSq;
    }
}
