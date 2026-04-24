import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;

public final class PrimitiveLibraryTests {

    private PrimitiveLibraryTests() {
    }

    public static void main(String[] args) {
        assertMesh("cube", MeshGenerator.cube(1.0), 12, true);
        assertMesh("sphere", MeshGenerator.sphere(1.0, 12, 18), 100, true);
        assertMesh("plane", MeshGenerator.plane(2.0, 2.0, 4, 4), 32, true);
        Mesh cylinder = MeshGenerator.cylinder(0.5, 1.2, 20, 1);
        assertMesh("cylinder", cylinder, 40, true);
        assertMesh("cone", MeshGenerator.cone(0.6, 1.2, 20), 20, true);
        assertMesh("capsule", MeshGenerator.capsule(0.4, 1.0, 8, 18), 100, true);
        assertMesh("torus", MeshGenerator.torus(0.7, 0.2, 24, 12), 200, true);
        assertMesh("pyramid", MeshGenerator.pyramid(1.0, 1.0, 1.4), 6, true);
        Mesh prism = MeshGenerator.prism(0.6, 1.2, 6);
        assertMesh("prism", prism, 20, true);
        assertMesh("crystal", MeshGenerator.crystal(0.7, 1.5, 6), 20, true);
        assertMesh("torus-knot", MeshGenerator.torusKnot(0.6, 0.22, 0.14, 120, 14, 2, 3), 400, true);
        assertCylinderAutosmooth(cylinder);
        assertPrismAutosmooth(prism);
        System.out.println("PrimitiveLibraryTests: ALL TESTS PASSED");
    }

    private static void assertMesh(String label, Mesh mesh, int minTriangles, boolean requireUvs) {
        if (mesh == null) {
            throw new AssertionError(label + " mesh is null");
        }
        if (mesh.getVertexCount() <= 0) {
            throw new AssertionError(label + " has no vertices");
        }
        if (mesh.getTriangleCount() < minTriangles) {
            throw new AssertionError(label + " triangle count too low: " + mesh.getTriangleCount());
        }
        if (mesh.getAABB() == null || mesh.getBounds() == null) {
            throw new AssertionError(label + " bounds were not computed");
        }
        if (requireUvs && (mesh.getUVs() == null || mesh.getUVs().length != mesh.getVertexCount() * 2)) {
            throw new AssertionError(label + " should expose texture UVs");
        }
    }

    private static void assertCylinderAutosmooth(Mesh mesh) {
        float[] positions = mesh.getPositions();
        float[] normals = mesh.getNormals();
        if (positions == null || normals == null) {
            throw new AssertionError("cylinder should expose positions and normals");
        }
        int sideMatches = 0;
        for (int i = 0; i < mesh.getVertexCount(); i++) {
            int base = i * 3;
            double x = positions[base];
            double z = positions[base + 2];
            double ny = normals[base + 1];
            if (Math.abs(x - 0.5) < 1e-4 && Math.abs(z) < 1e-4 && Math.abs(ny) < 1e-3) {
                sideMatches++;
                if (Math.abs(normals[base] - 1.0) > 0.05 || Math.abs(normals[base + 2]) > 0.05) {
                    throw new AssertionError("cylinder side normal should stay rounded/radial");
                }
            }
        }
        if (sideMatches == 0) {
            throw new AssertionError("cylinder autosmooth test could not find radial side vertices");
        }
    }

    private static void assertPrismAutosmooth(Mesh mesh) {
        float[] positions = mesh.getPositions();
        float[] normals = mesh.getNormals();
        if (positions == null || normals == null) {
            throw new AssertionError("prism should expose positions and normals");
        }
        double targetX = 0.6 * Math.cos(Math.PI / 3.0);
        double targetZ = 0.6 * Math.sin(Math.PI / 3.0);
        double[][] uniqueNormals = new double[8][3];
        int uniqueCount = 0;
        for (int i = 0; i < mesh.getVertexCount(); i++) {
            int base = i * 3;
            double x = positions[base];
            double z = positions[base + 2];
            double ny = normals[base + 1];
            if (Math.abs(x - targetX) > 1e-3 || Math.abs(z - targetZ) > 1e-3 || Math.abs(ny) > 0.2) {
                continue;
            }
            double nx = normals[base];
            double nz = normals[base + 2];
            boolean duplicate = false;
            for (int n = 0; n < uniqueCount; n++) {
                if (Math.abs(uniqueNormals[n][0] - nx) < 1e-3
                        && Math.abs(uniqueNormals[n][1] - ny) < 1e-3
                        && Math.abs(uniqueNormals[n][2] - nz) < 1e-3) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                uniqueNormals[uniqueCount][0] = nx;
                uniqueNormals[uniqueCount][1] = ny;
                uniqueNormals[uniqueCount][2] = nz;
                uniqueCount++;
            }
        }
        if (uniqueCount < 2) {
            throw new AssertionError("prism autosmooth should split sharp corner normals");
        }
    }
}
