package engine.render.ray.bvh;

public final class SahBvhUtil {

    private static final double EPS = 1e-9;

    private SahBvhUtil() {
    }

    @FunctionalInterface
    public interface AxisAccessor {
        double axis(int itemIndex, int axis);
    }

    @FunctionalInterface
    public interface BoundsAccessor {
        void bounds(int itemIndex, Bounds out);
    }

    public static final class Bounds {
        public double minX;
        public double minY;
        public double minZ;
        public double maxX;
        public double maxY;
        public double maxZ;

        public Bounds set(double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            return this;
        }

        public Bounds reset() {
            minX = Double.POSITIVE_INFINITY;
            minY = Double.POSITIVE_INFINITY;
            minZ = Double.POSITIVE_INFINITY;
            maxX = Double.NEGATIVE_INFINITY;
            maxY = Double.NEGATIVE_INFINITY;
            maxZ = Double.NEGATIVE_INFINITY;
            return this;
        }

        public int longestAxis() {
            double ex = maxX - minX;
            double ey = maxY - minY;
            double ez = maxZ - minZ;
            if (ey > ex && ey >= ez) {
                return 1;
            }
            if (ez > ex && ez > ey) {
                return 2;
            }
            return 0;
        }
    }

    public static final class Split {
        public final int axis;
        public final double position;
        public final double cost;

        public Split(int axis, double position, double cost) {
            this.axis = axis;
            this.position = position;
            this.cost = cost;
        }

        public boolean isValid() {
            return axis >= 0 && Double.isFinite(cost);
        }
    }

    public static Split findBestSplit(int[] order,
                                      int start,
                                      int end,
                                      int binCount,
                                      AxisAccessor centroidAccessor,
                                      BoundsAccessor boundsAccessor) {
        int bins = Math.max(4, binCount);
        int bestAxis = -1;
        double bestPosition = 0.0;
        double bestCost = Double.POSITIVE_INFINITY;

        for (int axis = 0; axis < 3; axis++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int i = start; i < end; i++) {
                double centroid = centroidAccessor.axis(order[i], axis);
                min = Math.min(min, centroid);
                max = Math.max(max, centroid);
            }
            if (max - min < EPS) {
                continue;
            }

            for (int bin = 1; bin < bins; bin++) {
                double split = min + (max - min) * (bin / (double) bins);
                double cost = evaluateSah(order, start, end, axis, split, centroidAccessor, boundsAccessor);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestAxis = axis;
                    bestPosition = split;
                }
            }
        }
        return new Split(bestAxis, bestPosition, bestCost);
    }

    public static int partitionByAxis(int[] order,
                                      int start,
                                      int end,
                                      int axis,
                                      double split,
                                      AxisAccessor centroidAccessor) {
        int left = start;
        int right = end - 1;
        while (left <= right) {
            while (left <= right && centroidAccessor.axis(order[left], axis) < split) {
                left++;
            }
            while (left <= right && centroidAccessor.axis(order[right], axis) >= split) {
                right--;
            }
            if (left < right) {
                int tmp = order[left];
                order[left] = order[right];
                order[right] = tmp;
                left++;
                right--;
            }
        }
        return left;
    }

    public static void sortByAxis(int[] order, int low, int high, int axis, AxisAccessor centroidAccessor) {
        if (low >= high) {
            return;
        }
        int i = low;
        int j = high;
        double pivot = centroidAccessor.axis(order[(low + high) >>> 1], axis);
        while (i <= j) {
            while (centroidAccessor.axis(order[i], axis) < pivot) {
                i++;
            }
            while (centroidAccessor.axis(order[j], axis) > pivot) {
                j--;
            }
            if (i <= j) {
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
                i++;
                j--;
            }
        }
        if (low < j) {
            sortByAxis(order, low, j, axis, centroidAccessor);
        }
        if (i < high) {
            sortByAxis(order, i, high, axis, centroidAccessor);
        }
    }

    private static double evaluateSah(int[] order,
                                      int start,
                                      int end,
                                      int axis,
                                      double split,
                                      AxisAccessor centroidAccessor,
                                      BoundsAccessor boundsAccessor) {
        Bounds scratch = new Bounds();
        double leftMinX = Double.POSITIVE_INFINITY;
        double leftMinY = Double.POSITIVE_INFINITY;
        double leftMinZ = Double.POSITIVE_INFINITY;
        double leftMaxX = Double.NEGATIVE_INFINITY;
        double leftMaxY = Double.NEGATIVE_INFINITY;
        double leftMaxZ = Double.NEGATIVE_INFINITY;
        double rightMinX = Double.POSITIVE_INFINITY;
        double rightMinY = Double.POSITIVE_INFINITY;
        double rightMinZ = Double.POSITIVE_INFINITY;
        double rightMaxX = Double.NEGATIVE_INFINITY;
        double rightMaxY = Double.NEGATIVE_INFINITY;
        double rightMaxZ = Double.NEGATIVE_INFINITY;
        int leftCount = 0;
        int rightCount = 0;

        for (int i = start; i < end; i++) {
            int item = order[i];
            boundsAccessor.bounds(item, scratch);
            if (centroidAccessor.axis(item, axis) < split) {
                leftMinX = Math.min(leftMinX, scratch.minX);
                leftMinY = Math.min(leftMinY, scratch.minY);
                leftMinZ = Math.min(leftMinZ, scratch.minZ);
                leftMaxX = Math.max(leftMaxX, scratch.maxX);
                leftMaxY = Math.max(leftMaxY, scratch.maxY);
                leftMaxZ = Math.max(leftMaxZ, scratch.maxZ);
                leftCount++;
            } else {
                rightMinX = Math.min(rightMinX, scratch.minX);
                rightMinY = Math.min(rightMinY, scratch.minY);
                rightMinZ = Math.min(rightMinZ, scratch.minZ);
                rightMaxX = Math.max(rightMaxX, scratch.maxX);
                rightMaxY = Math.max(rightMaxY, scratch.maxY);
                rightMaxZ = Math.max(rightMaxZ, scratch.maxZ);
                rightCount++;
            }
        }

        if (leftCount == 0 || rightCount == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return surfaceArea(leftMinX, leftMinY, leftMinZ, leftMaxX, leftMaxY, leftMaxZ) * leftCount
                + surfaceArea(rightMinX, rightMinY, rightMinZ, rightMaxX, rightMaxY, rightMaxZ) * rightCount;
    }

    private static double surfaceArea(double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        double ex = Math.max(0.0, maxX - minX);
        double ey = Math.max(0.0, maxY - minY);
        double ez = Math.max(0.0, maxZ - minZ);
        return 2.0 * (ex * ey + ey * ez + ez * ex);
    }
}

