import engine.math.Ray;
import engine.math.Vec2;
import engine.math.Vec3;
import engine.render.ray.BVHBuilder;
import engine.render.ray.BVHNode;
import engine.render.ray.HitRecord;
import engine.render.ray.Sampler;

public class RayModuleTests {

    private static final double EPS = 1e-6;

    public static void main(String[] args) {
        testHitRecordFaceNormal();
        testSamplerRangesAndHemisphere();
        testBVHNodeConstructors();
        testBVHBuilderIntersection();
        testBVHBuilderModelTransform();
        System.out.println("RayModuleTests: ALL TESTS PASSED");
    }

    private static void testHitRecordFaceNormal() {
        Ray ray = new Ray(new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 0.0, -1.0));
        HitRecord record = new HitRecord();
        record.set(1.0, new Vec3(0.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0), true);

        record.setFaceNormal(ray, new Vec3(0.0, 0.0, 1.0));
        assertTrue(record.isFrontFace(), "Front-face should be true for opposing normal");
        assertVecNear(new Vec3(0.0, 0.0, 1.0), record.getNormal(), EPS, "Normal mismatch");

        record.setFaceNormal(ray, new Vec3(0.0, 0.0, -1.0));
        assertTrue(!record.isFrontFace(), "Front-face should be false for same-direction normal");
        assertVecNear(new Vec3(0.0, 0.0, 1.0), record.getNormal(), EPS, "Normal should be flipped");
    }

    private static void testSamplerRangesAndHemisphere() {
        Sampler sampler = new Sampler(1234L);
        Vec3 up = new Vec3(0.0, 1.0, 0.0);
        for (int i = 0; i < 10_000; i++) {
            Vec3 a = sampler.cosineWeightedHemisphere(up);
            Vec3 b = sampler.uniformHemisphere(up);
            assertTrue(a.dot(up) >= -1e-9, "Cosine hemisphere sampled below plane");
            assertTrue(b.dot(up) >= -1e-9, "Uniform hemisphere sampled below plane");

            Vec2 jitter = sampler.subPixelJitter();
            assertTrue(jitter.x >= 0.0 && jitter.x < 1.0, "Jitter X out of [0,1)");
            assertTrue(jitter.y >= 0.0 && jitter.y < 1.0, "Jitter Y out of [0,1)");

            Vec2 disk = sampler.uniformDisk();
            assertTrue(disk.x * disk.x + disk.y * disk.y <= 1.000001, "Disk sample outside unit disk");
        }
    }

    private static void testBVHNodeConstructors() {
        BVHNode leaf = new BVHNode(new engine.math.AABB(new Vec3(0, 0, 0), new Vec3(1, 1, 1)), 3, 5);
        assertTrue(leaf.isLeaf(), "Leaf node must report isLeaf()");
        assertTrue(leaf.getTriangleStart() == 3, "Leaf triangleStart mismatch");
        assertTrue(leaf.getTriangleCount() == 5, "Leaf triangleCount mismatch");

        BVHNode left = new BVHNode(new engine.math.AABB(new Vec3(0, 0, 0), new Vec3(1, 1, 1)), 0, 2);
        BVHNode right = new BVHNode(new engine.math.AABB(new Vec3(1, 0, 0), new Vec3(2, 1, 1)), 2, 2);
        BVHNode internal = new BVHNode(new engine.math.AABB(new Vec3(0, 0, 0), new Vec3(2, 1, 1)), left, right);
        assertTrue(!internal.isLeaf(), "Internal node must not report isLeaf()");
        assertTrue(internal.getLeft() == left && internal.getRight() == right, "Internal children mismatch");
    }

    private static void testBVHBuilderIntersection() {
        float[][] positions = new float[][]{
                new float[]{
                        0f, 0f, 0f,
                        1f, 0f, 0f,
                        0f, 1f, 0f
                }
        };
        int[][] indices = new int[][]{{0, 1, 2}};
        double[][] mats = new double[][]{identity()};

        BVHBuilder builder = new BVHBuilder();
        builder.build(positions, indices, mats, 1);

        HitRecord rec = new HitRecord();
        Ray hitRay = new Ray(new Vec3(0.25, 0.25, 1.0), new Vec3(0.0, 0.0, -1.0));
        boolean hit = builder.intersect(hitRay, 0.0, 100.0, rec);
        assertTrue(hit, "Expected ray hit");
        assertTrue(builder.intersectAny(hitRay, 0.0, 100.0), "Expected intersectAny hit");
        assertTrue(rec.getT() > 0.9 && rec.getT() < 1.1, "Hit t should be around 1.0");
        assertVecNear(new Vec3(0.25, 0.25, 0.0), rec.getPoint(), 1e-4, "Hit point mismatch");

        Ray missRay = new Ray(new Vec3(2.0, 2.0, 1.0), new Vec3(0.0, 0.0, -1.0));
        assertTrue(!builder.intersect(missRay, 0.0, 100.0, new HitRecord()), "Expected miss");
        assertTrue(!builder.intersectAny(missRay, 0.0, 100.0), "Expected intersectAny miss");
    }

    private static void testBVHBuilderModelTransform() {
        float[][] positions = new float[][]{
                new float[]{
                        0f, 0f, 0f,
                        1f, 0f, 0f,
                        0f, 1f, 0f
                }
        };
        int[][] indices = new int[][]{{0, 1, 2}};
        double[][] mats = new double[][]{translation(2.0, 0.0, 0.0)};

        BVHBuilder builder = new BVHBuilder();
        builder.build(positions, indices, mats, 1);
        HitRecord rec = new HitRecord();
        Ray ray = new Ray(new Vec3(2.25, 0.25, 1.0), new Vec3(0.0, 0.0, -1.0));
        assertTrue(builder.intersect(ray, 0.0, 100.0, rec), "Transformed triangle should be hittable");
        assertVecNear(new Vec3(2.25, 0.25, 0.0), rec.getPoint(), 1e-4, "Transformed hit point mismatch");
    }

    private static double[] identity() {
        return new double[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
    }

    private static double[] translation(double tx, double ty, double tz) {
        return new double[]{
                1, 0, 0, tx,
                0, 1, 0, ty,
                0, 0, 1, tz,
                0, 0, 0, 1
        };
    }

    private static void assertVecNear(Vec3 expected, Vec3 actual, double eps, String message) {
        double delta = expected.sub(actual).length();
        if (delta > eps) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual + " delta=" + delta);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
