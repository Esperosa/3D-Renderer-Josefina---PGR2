package engine.geometry;

import engine.math.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tady procedurálně vytvářím běžné primitivní meshe.
 */
public final class MeshGenerator {
    private static final double DEFAULT_AUTO_SMOOTH_ANGLE_DEGREES = 30.0;

    private MeshGenerator() {
    }

    public static Mesh cube(double size) {
        float s = (float) (size * 0.5);
        float[] positions = new float[]{
                // Tady skládám přední stěnu.
                -s, -s, s,  s, -s, s,  s, s, s,  -s, s, s,
                // Tady skládám zadní stěnu.
                s, -s, -s,  -s, -s, -s,  -s, s, -s,  s, s, -s,
                // Tady skládám levou stěnu.
                -s, -s, -s,  -s, -s, s,  -s, s, s,  -s, s, -s,
                // Tady skládám pravou stěnu.
                s, -s, s,  s, -s, -s,  s, s, -s,  s, s, s,
                // Tady skládám horní stěnu.
                -s, s, s,  s, s, s,  s, s, -s,  -s, s, -s,
                // Tady skládám spodní stěnu.
                -s, -s, -s,  s, -s, -s,  s, -s, s,  -s, -s, s
        };
        float[] normals = new float[]{
                0, 0, 1,  0, 0, 1,  0, 0, 1,  0, 0, 1,
                0, 0, -1,  0, 0, -1,  0, 0, -1,  0, 0, -1,
                -1, 0, 0,  -1, 0, 0,  -1, 0, 0,  -1, 0, 0,
                1, 0, 0,  1, 0, 0,  1, 0, 0,  1, 0, 0,
                0, 1, 0,  0, 1, 0,  0, 1, 0,  0, 1, 0,
                0, -1, 0,  0, -1, 0,  0, -1, 0,  0, -1, 0
        };
        float[] uvs = new float[]{
                0, 1,  1, 1,  1, 0,  0, 0,
                0, 1,  1, 1,  1, 0,  0, 0,
                0, 1,  1, 1,  1, 0,  0, 0,
                0, 1,  1, 1,  1, 0,  0, 0,
                0, 1,  1, 1,  1, 0,  0, 0,
                0, 1,  1, 1,  1, 0,  0, 0
        };
        int[] indices = new int[]{
                0, 1, 2,  0, 2, 3,
                4, 5, 6,  4, 6, 7,
                8, 9, 10,  8, 10, 11,
                12, 13, 14,  12, 14, 15,
                16, 17, 18,  16, 18, 19,
                20, 21, 22,  20, 22, 23
        };
        return buildMesh("cube", positions, normals, uvs, indices);
    }

    public static Mesh plane(double width, double depth, int segmentsX, int segmentsZ) {
        int sx = Math.max(1, segmentsX);
        int sz = Math.max(1, segmentsZ);
        int vertexCount = (sx + 1) * (sz + 1);
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        int[] indices = new int[sx * sz * 6];

        int vi = 0;
        int ui = 0;
        for (int z = 0; z <= sz; z++) {
            for (int x = 0; x <= sx; x++) {
                double tx = x / (double) sx;
                double tz = z / (double) sz;
                positions[vi] = (float) ((tx - 0.5) * width);
                positions[vi + 1] = 0.0f;
                positions[vi + 2] = (float) ((tz - 0.5) * depth);
                normals[vi] = 0.0f;
                normals[vi + 1] = 1.0f;
                normals[vi + 2] = 0.0f;
                uvs[ui] = (float) tx;
                uvs[ui + 1] = (float) (1.0 - tz);
                vi += 3;
                ui += 2;
            }
        }

        int ii = 0;
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int i0 = z * (sx + 1) + x;
                int i1 = i0 + 1;
                int i2 = i0 + (sx + 1);
                int i3 = i2 + 1;
                indices[ii++] = i0;
                indices[ii++] = i2;
                indices[ii++] = i1;
                indices[ii++] = i1;
                indices[ii++] = i2;
                indices[ii++] = i3;
            }
        }
        return buildMesh("plane", positions, normals, uvs, indices);
    }

    public static Mesh sphere(double radius, int rings, int sectors) {
        int rCount = Math.max(3, rings);
        int sCount = Math.max(3, sectors);
        int vertexCount = (rCount + 1) * (sCount + 1);
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        int[] indices = new int[rCount * sCount * 6];

        int vi = 0;
        int ui = 0;
        for (int r = 0; r <= rCount; r++) {
            double v = r / (double) rCount;
            double phi = Math.PI * v;
            double y = Math.cos(phi);
            double sinPhi = Math.sin(phi);
            for (int s = 0; s <= sCount; s++) {
                double u = s / (double) sCount;
                double theta = u * Math.PI * 2.0;
                double x = Math.cos(theta) * sinPhi;
                double z = Math.sin(theta) * sinPhi;
                normals[vi] = (float) x;
                normals[vi + 1] = (float) y;
                normals[vi + 2] = (float) z;
                positions[vi] = (float) (x * radius);
                positions[vi + 1] = (float) (y * radius);
                positions[vi + 2] = (float) (z * radius);
                uvs[ui] = (float) u;
                uvs[ui + 1] = (float) (1.0 - v);
                vi += 3;
                ui += 2;
            }
        }

        int ii = 0;
        for (int r = 0; r < rCount; r++) {
            for (int s = 0; s < sCount; s++) {
                int i0 = r * (sCount + 1) + s;
                int i1 = i0 + 1;
                int i2 = i0 + (sCount + 1);
                int i3 = i2 + 1;
                indices[ii++] = i0;
                indices[ii++] = i2;
                indices[ii++] = i1;
                indices[ii++] = i1;
                indices[ii++] = i2;
                indices[ii++] = i3;
            }
        }

        return buildMesh("sphere", positions, normals, uvs, indices);
    }

    public static Mesh cylinder(double radius, double height, int radialSegments, int heightSegments) {
        return frustum("cylinder", radius, radius, height, radialSegments, heightSegments, true);
    }

    public static Mesh cone(double radius, double height, int radialSegments) {
        return frustum("cone", radius, 0.0, height, radialSegments, 1, true);
    }

    public static Mesh prism(double radius, double height, int sides) {
        return frustum("prism", radius, radius, height, Math.max(3, sides), 1, true);
    }

    public static Mesh torus(double majorRadius, double minorRadius, int majorSegments, int minorSegments) {
        int major = Math.max(8, majorSegments);
        int minor = Math.max(6, minorSegments);
        int vertexCount = (major + 1) * (minor + 1);
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        int[] indices = new int[major * minor * 6];

        int vi = 0;
        int ui = 0;
        for (int i = 0; i <= major; i++) {
            double u = i / (double) major;
            double theta = u * Math.PI * 2.0;
            double cosTheta = Math.cos(theta);
            double sinTheta = Math.sin(theta);
            for (int j = 0; j <= minor; j++) {
                double v = j / (double) minor;
                double phi = v * Math.PI * 2.0;
                double cosPhi = Math.cos(phi);
                double sinPhi = Math.sin(phi);

                double ringRadius = majorRadius + minorRadius * cosPhi;
                double x = ringRadius * cosTheta;
                double y = minorRadius * sinPhi;
                double z = ringRadius * sinTheta;
                double nx = cosTheta * cosPhi;
                double ny = sinPhi;
                double nz = sinTheta * cosPhi;

                positions[vi] = (float) x;
                positions[vi + 1] = (float) y;
                positions[vi + 2] = (float) z;
                normals[vi] = (float) nx;
                normals[vi + 1] = (float) ny;
                normals[vi + 2] = (float) nz;
                uvs[ui] = (float) u;
                uvs[ui + 1] = (float) (1.0 - v);
                vi += 3;
                ui += 2;
            }
        }

        int ii = 0;
        for (int i = 0; i < major; i++) {
            for (int j = 0; j < minor; j++) {
                int i0 = i * (minor + 1) + j;
                int i1 = i0 + 1;
                int i2 = i0 + (minor + 1);
                int i3 = i2 + 1;
                indices[ii++] = i0;
                indices[ii++] = i2;
                indices[ii++] = i1;
                indices[ii++] = i1;
                indices[ii++] = i2;
                indices[ii++] = i3;
            }
        }
        return buildMesh("torus", positions, normals, uvs, indices);
    }

    public static Mesh capsule(double radius, double height, int rings, int sectors) {
        int hemiRings = Math.max(3, rings);
        int bodySegments = Math.max(1, rings / 2);
        int sectorsSafe = Math.max(6, sectors);
        int stackCount = hemiRings * 2 + bodySegments;
        double halfCylinder = Math.max(0.0, height * 0.5);
        double halfSpan = halfCylinder + radius;

        int vertexCount = (stackCount + 1) * (sectorsSafe + 1);
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        int[] indices = new int[stackCount * sectorsSafe * 6];

        int vi = 0;
        int ui = 0;
        for (int stack = 0; stack <= stackCount; stack++) {
            double v = stack / (double) stackCount;
            double y = -halfSpan + v * (halfSpan * 2.0);
            double localY;
            double ringRadius;
            double normalY;

            if (y > halfCylinder) {
                localY = y - halfCylinder;
                ringRadius = Math.sqrt(Math.max(0.0, radius * radius - localY * localY));
                normalY = localY / Math.max(1e-6, radius);
            } else if (y < -halfCylinder) {
                localY = y + halfCylinder;
                ringRadius = Math.sqrt(Math.max(0.0, radius * radius - localY * localY));
                normalY = localY / Math.max(1e-6, radius);
            } else {
                localY = 0.0;
                ringRadius = radius;
                normalY = 0.0;
            }

            double radialNormal = radius > 1e-6 ? ringRadius / radius : 0.0;
            for (int sector = 0; sector <= sectorsSafe; sector++) {
                double u = sector / (double) sectorsSafe;
                double theta = u * Math.PI * 2.0;
                double cosTheta = Math.cos(theta);
                double sinTheta = Math.sin(theta);
                double x = cosTheta * ringRadius;
                double z = sinTheta * ringRadius;

                positions[vi] = (float) x;
                positions[vi + 1] = (float) y;
                positions[vi + 2] = (float) z;
                normals[vi] = (float) (cosTheta * radialNormal);
                normals[vi + 1] = (float) normalY;
                normals[vi + 2] = (float) (sinTheta * radialNormal);
                uvs[ui] = (float) u;
                uvs[ui + 1] = (float) (1.0 - v);
                vi += 3;
                ui += 2;
            }
        }

        int ii = 0;
        for (int stack = 0; stack < stackCount; stack++) {
            for (int sector = 0; sector < sectorsSafe; sector++) {
                int i0 = stack * (sectorsSafe + 1) + sector;
                int i1 = i0 + 1;
                int i2 = i0 + (sectorsSafe + 1);
                int i3 = i2 + 1;
                indices[ii++] = i0;
                indices[ii++] = i2;
                indices[ii++] = i1;
                indices[ii++] = i1;
                indices[ii++] = i2;
                indices[ii++] = i3;
            }
        }
        return buildMesh("capsule", positions, normals, uvs, indices);
    }

    public static Mesh pyramid(double width, double depth, double height) {
        double hw = width * 0.5;
        double hd = depth * 0.5;
        double halfHeight = height * 0.5;
        Vec3 p0 = new Vec3(-hw, -halfHeight, -hd);
        Vec3 p1 = new Vec3(hw, -halfHeight, -hd);
        Vec3 p2 = new Vec3(hw, -halfHeight, hd);
        Vec3 p3 = new Vec3(-hw, -halfHeight, hd);
        Vec3 top = new Vec3(0.0, halfHeight, 0.0);

        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        addFacetedTriangle(positions, normals, uvs, indices, p0, p2, p1, 0.0, 1.0, 1.0, 0.0, 1.0, 1.0);
        addFacetedTriangle(positions, normals, uvs, indices, p0, p3, p2, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0);
        addFacetedTriangle(positions, normals, uvs, indices, p0, p1, top, 0.0, 1.0, 1.0, 1.0, 0.5, 0.0);
        addFacetedTriangle(positions, normals, uvs, indices, p1, p2, top, 0.0, 1.0, 1.0, 1.0, 0.5, 0.0);
        addFacetedTriangle(positions, normals, uvs, indices, p2, p3, top, 0.0, 1.0, 1.0, 1.0, 0.5, 0.0);
        addFacetedTriangle(positions, normals, uvs, indices, p3, p0, top, 0.0, 1.0, 1.0, 1.0, 0.5, 0.0);

        return buildMesh("pyramid", toFloatArray(positions), toFloatArray(normals), toFloatArray(uvs), toIntArray(indices));
    }

    public static Mesh crystal(double radius, double height, int sides) {
        int count = Math.max(5, sides);
        double upperY = height * 0.16;
        double lowerY = -height * 0.10;
        double upperRadius = radius * 0.58;
        double lowerRadius = radius * 0.82;
        Vec3 top = new Vec3(0.0, height * 0.5, 0.0);
        Vec3 bottom = new Vec3(0.0, -height * 0.5, 0.0);

        Vec3[] upper = new Vec3[count];
        Vec3[] lower = new Vec3[count];
        for (int i = 0; i < count; i++) {
            double angle = i / (double) count * Math.PI * 2.0;
            double lowerAngle = angle + Math.PI / count;
            upper[i] = new Vec3(Math.cos(angle) * upperRadius, upperY, Math.sin(angle) * upperRadius);
            lower[i] = new Vec3(Math.cos(lowerAngle) * lowerRadius, lowerY, Math.sin(lowerAngle) * lowerRadius);
        }

        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            double u0 = i / (double) count;
            double u1 = (i + 1.0) / count;
            addFacetedTriangle(positions, normals, uvs, indices, top, upper[next], upper[i], 0.5, 0.0, u1, 1.0, u0, 1.0);
            addFacetedTriangle(positions, normals, uvs, indices, upper[i], upper[next], lower[next], u0, 0.0, u1, 0.0, u1, 1.0);
            addFacetedTriangle(positions, normals, uvs, indices, upper[i], lower[next], lower[i], u0, 0.0, u1, 1.0, u0, 1.0);
            addFacetedTriangle(positions, normals, uvs, indices, bottom, lower[i], lower[next], 0.5, 0.0, u0, 1.0, u1, 1.0);
        }

        return buildMesh("crystal", toFloatArray(positions), toFloatArray(normals), toFloatArray(uvs), toIntArray(indices));
    }

    public static Mesh torusKnot(double majorRadius,
                                 double knotRadius,
                                 double tubeRadius,
                                 int tubularSegments,
                                 int radialSegments,
                                 int p,
                                 int q) {
        int tubes = Math.max(48, tubularSegments);
        int radial = Math.max(6, radialSegments);
        int pSafe = Math.max(1, p);
        int qSafe = Math.max(1, q);

        int vertexCount = (tubes + 1) * (radial + 1);
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        int[] indices = new int[tubes * radial * 6];

        int vi = 0;
        int ui = 0;
        double epsilon = Math.PI * 2.0 / tubes * 0.25;
        for (int i = 0; i <= tubes; i++) {
            double u = i / (double) tubes;
            double t = u * Math.PI * 2.0;
            Vec3 center = torusKnotPoint(majorRadius, knotRadius, pSafe, qSafe, t);
            Vec3 next = torusKnotPoint(majorRadius, knotRadius, pSafe, qSafe, t + epsilon);
            Vec3 tangent = next.sub(center).normalize();
            Vec3 reference = Math.abs(tangent.y) < 0.92 ? Vec3.UP : new Vec3(1.0, 0.0, 0.0);
            Vec3 normal = tangent.cross(reference).normalize();
            if (normal.lengthSquared() < 1e-8) {
                normal = tangent.cross(new Vec3(0.0, 0.0, 1.0)).normalize();
            }
            Vec3 binormal = tangent.cross(normal).normalize();

            for (int j = 0; j <= radial; j++) {
                double v = j / (double) radial;
                double angle = v * Math.PI * 2.0;
                Vec3 ringOffset = normal.mul(Math.cos(angle) * tubeRadius)
                        .add(binormal.mul(Math.sin(angle) * tubeRadius));
                Vec3 pos = center.add(ringOffset);
                Vec3 nrm = ringOffset.normalize();

                positions[vi] = (float) pos.x;
                positions[vi + 1] = (float) pos.y;
                positions[vi + 2] = (float) pos.z;
                normals[vi] = (float) nrm.x;
                normals[vi + 1] = (float) nrm.y;
                normals[vi + 2] = (float) nrm.z;
                uvs[ui] = (float) u;
                uvs[ui + 1] = (float) (1.0 - v);
                vi += 3;
                ui += 2;
            }
        }

        int ii = 0;
        for (int i = 0; i < tubes; i++) {
            for (int j = 0; j < radial; j++) {
                int i0 = i * (radial + 1) + j;
                int i1 = i0 + 1;
                int i2 = i0 + (radial + 1);
                int i3 = i2 + 1;
                indices[ii++] = i0;
                indices[ii++] = i2;
                indices[ii++] = i1;
                indices[ii++] = i1;
                indices[ii++] = i2;
                indices[ii++] = i3;
            }
        }
        return buildMesh("torus-knot", positions, normals, uvs, indices);
    }

    private static Mesh frustum(String name,
                                double bottomRadius,
                                double topRadius,
                                double height,
                                int radialSegments,
                                int heightSegments,
                                boolean capped) {
        int radial = Math.max(3, radialSegments);
        int heightSegs = Math.max(1, heightSegments);
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        int[][] side = new int[heightSegs + 1][radial + 1];
        double safeHeight = Math.max(1e-6, height);
        double slopeY = (bottomRadius - topRadius) / safeHeight;

        for (int y = 0; y <= heightSegs; y++) {
            double v = y / (double) heightSegs;
            double ringRadius = lerp(bottomRadius, topRadius, v);
            double py = (v - 0.5) * height;
            for (int s = 0; s <= radial; s++) {
                double u = s / (double) radial;
                double theta = u * Math.PI * 2.0;
                double cosTheta = Math.cos(theta);
                double sinTheta = Math.sin(theta);
                Vec3 nrm = new Vec3(cosTheta, slopeY, sinTheta).normalize();
                side[y][s] = appendVertex(
                        positions, normals, uvs,
                        cosTheta * ringRadius, py, sinTheta * ringRadius,
                        nrm.x, nrm.y, nrm.z,
                        u, 1.0 - v
                );
            }
        }

        for (int y = 0; y < heightSegs; y++) {
            for (int s = 0; s < radial; s++) {
                int i0 = side[y][s];
                int i1 = side[y][s + 1];
                int i2 = side[y + 1][s];
                int i3 = side[y + 1][s + 1];
                indices.add(i0);
                indices.add(i2);
                indices.add(i1);
                indices.add(i1);
                indices.add(i2);
                indices.add(i3);
            }
        }

        if (capped && bottomRadius > 1e-6) {
            int center = appendVertex(positions, normals, uvs, 0.0, -height * 0.5, 0.0, 0.0, -1.0, 0.0, 0.5, 0.5);
            int[] ring = new int[radial + 1];
            for (int s = 0; s <= radial; s++) {
                double u = s / (double) radial;
                double theta = u * Math.PI * 2.0;
                double cosTheta = Math.cos(theta);
                double sinTheta = Math.sin(theta);
                ring[s] = appendVertex(
                        positions, normals, uvs,
                        cosTheta * bottomRadius, -height * 0.5, sinTheta * bottomRadius,
                        0.0, -1.0, 0.0,
                        0.5 + cosTheta * 0.5, 0.5 + sinTheta * 0.5
                );
            }
            for (int s = 0; s < radial; s++) {
                indices.add(center);
                indices.add(ring[s + 1]);
                indices.add(ring[s]);
            }
        }

        if (capped && topRadius > 1e-6) {
            int center = appendVertex(positions, normals, uvs, 0.0, height * 0.5, 0.0, 0.0, 1.0, 0.0, 0.5, 0.5);
            int[] ring = new int[radial + 1];
            for (int s = 0; s <= radial; s++) {
                double u = s / (double) radial;
                double theta = u * Math.PI * 2.0;
                double cosTheta = Math.cos(theta);
                double sinTheta = Math.sin(theta);
                ring[s] = appendVertex(
                        positions, normals, uvs,
                        cosTheta * topRadius, height * 0.5, sinTheta * topRadius,
                        0.0, 1.0, 0.0,
                        0.5 + cosTheta * 0.5, 0.5 - sinTheta * 0.5
                );
            }
            for (int s = 0; s < radial; s++) {
                indices.add(center);
                indices.add(ring[s]);
                indices.add(ring[s + 1]);
            }
        }

        return buildAutoSmoothMesh(name,
                toFloatArray(positions),
                toFloatArray(normals),
                toFloatArray(uvs),
                toIntArray(indices),
                DEFAULT_AUTO_SMOOTH_ANGLE_DEGREES);
    }

    private static Vec3 torusKnotPoint(double majorRadius, double knotRadius, int p, int q, double t) {
        double cosP = Math.cos(p * t);
        double sinP = Math.sin(p * t);
        double cosQ = Math.cos(q * t);
        double sinQ = Math.sin(q * t);
        double radius = majorRadius + knotRadius * cosQ;
        return new Vec3(radius * cosP, knotRadius * sinQ, radius * sinP);
    }

    private static Mesh buildMesh(String name, float[] positions, float[] normals, float[] uvs, int[] indices) {
        Mesh mesh = new Mesh(name, positions, normals, indices);
        if (uvs != null && uvs.length == mesh.getVertexCount() * 2) {
            mesh.setUVs(uvs);
            mesh.computeTangents();
        }
        return mesh;
    }

    private static Mesh buildAutoSmoothMesh(String name,
                                            float[] positions,
                                            float[] normals,
                                            float[] uvs,
                                            int[] indices,
                                            double angleDegrees) {
        AutoSmoothMeshData data = autoSmooth(positions, normals, uvs, indices, angleDegrees);
        return buildMesh(name, data.positions, data.normals, data.uvs, data.indices);
    }

    private static int appendVertex(List<Float> positions,
                                    List<Float> normals,
                                    List<Float> uvs,
                                    double x,
                                    double y,
                                    double z,
                                    double nx,
                                    double ny,
                                    double nz,
                                    double u,
                                    double v) {
        int index = positions.size() / 3;
        positions.add((float) x);
        positions.add((float) y);
        positions.add((float) z);
        normals.add((float) nx);
        normals.add((float) ny);
        normals.add((float) nz);
        uvs.add((float) u);
        uvs.add((float) v);
        return index;
    }

    private static void addFacetedTriangle(List<Float> positions,
                                           List<Float> normals,
                                           List<Float> uvs,
                                           List<Integer> indices,
                                           Vec3 a,
                                           Vec3 b,
                                           Vec3 c,
                                           double u0,
                                           double v0,
                                           double u1,
                                           double v1,
                                           double u2,
                                           double v2) {
        Vec3 nrm = b.sub(a).cross(c.sub(a)).normalize();
        int base = appendVertex(positions, normals, uvs, a.x, a.y, a.z, nrm.x, nrm.y, nrm.z, u0, v0);
        appendVertex(positions, normals, uvs, b.x, b.y, b.z, nrm.x, nrm.y, nrm.z, u1, v1);
        appendVertex(positions, normals, uvs, c.x, c.y, c.z, nrm.x, nrm.y, nrm.z, u2, v2);
        indices.add(base);
        indices.add(base + 1);
        indices.add(base + 2);
    }

    private static float[] toFloatArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    @SuppressWarnings("unchecked")
    private static AutoSmoothMeshData autoSmooth(float[] positions,
                                                 float[] sourceNormals,
                                                 float[] uvs,
                                                 int[] indices,
                                                 double angleDegrees) {
        if (positions == null || positions.length == 0 || indices == null || indices.length == 0) {
            int normalLength = positions == null ? 0 : positions.length;
            return new AutoSmoothMeshData(
                    positions == null ? new float[0] : positions.clone(),
                    new float[normalLength],
                    uvs == null ? null : uvs.clone(),
                    indices == null ? new int[0] : indices.clone()
            );
        }

        int vertexCount = positions.length / 3;
        int triangleCount = indices.length / 3;
        double clampedAngle = Math.max(0.0, Math.min(180.0, angleDegrees));
        double cosThreshold = Math.cos(Math.toRadians(clampedAngle));
        boolean hasUvs = uvs != null && uvs.length == vertexCount * 2;
        boolean hasSourceNormals = sourceNormals != null && sourceNormals.length == positions.length;

        double[] faceNx = new double[triangleCount];
        double[] faceNy = new double[triangleCount];
        double[] faceNz = new double[triangleCount];
        double[] faceAx = new double[triangleCount];
        double[] faceAy = new double[triangleCount];
        double[] faceAz = new double[triangleCount];
        for (int face = 0; face < triangleCount; face++) {
            int base = face * 3;
            int i0 = indices[base];
            int i1 = indices[base + 1];
            int i2 = indices[base + 2];
            int p0 = i0 * 3;
            int p1 = i1 * 3;
            int p2 = i2 * 3;
            double ax = positions[p1] - positions[p0];
            double ay = positions[p1 + 1] - positions[p0 + 1];
            double az = positions[p1 + 2] - positions[p0 + 2];
            double bx = positions[p2] - positions[p0];
            double by = positions[p2 + 1] - positions[p0 + 1];
            double bz = positions[p2 + 2] - positions[p0 + 2];
            double cx = ay * bz - az * by;
            double cy = az * bx - ax * bz;
            double cz = ax * by - ay * bx;
            faceAx[face] = cx;
            faceAy[face] = cy;
            faceAz[face] = cz;
            double lenSq = cx * cx + cy * cy + cz * cz;
            if (lenSq > 1e-20) {
                double invLen = 1.0 / Math.sqrt(lenSq);
                faceNx[face] = cx * invLen;
                faceNy[face] = cy * invLen;
                faceNz[face] = cz * invLen;
            } else {
                faceNx[face] = 0.0;
                faceNy[face] = 1.0;
                faceNz[face] = 0.0;
            }
        }

        List<Integer>[] incidentCorners = (List<Integer>[]) new List<?>[vertexCount];
        for (int corner = 0; corner < indices.length; corner++) {
            int vertex = indices[corner];
            List<Integer> corners = incidentCorners[vertex];
            if (corners == null) {
                corners = new ArrayList<>();
                incidentCorners[vertex] = corners;
            }
            corners.add(corner);
        }

        int[] logicalGroupByVertex = new int[vertexCount];
        List<List<Integer>> logicalVertexGroups = new ArrayList<>();
        Map<VertexPositionKey, Integer> logicalGroupLookup = new LinkedHashMap<>();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int base = vertex * 3;
            VertexPositionKey key = VertexPositionKey.from(
                    positions[base],
                    positions[base + 1],
                    positions[base + 2]
            );
            Integer group = logicalGroupLookup.get(key);
            if (group == null) {
                group = logicalVertexGroups.size();
                logicalGroupLookup.put(key, group);
                logicalVertexGroups.add(new ArrayList<>());
            }
            logicalGroupByVertex[vertex] = group;
            logicalVertexGroups.get(group).add(vertex);
        }

        List<Float> outPositions = new ArrayList<>(positions.length);
        List<Float> outNormals = new ArrayList<>(positions.length);
        List<Float> outUvs = hasUvs ? new ArrayList<>(uvs.length) : null;
        int[] remappedIndices = new int[indices.length];

        for (int logicalGroup = 0; logicalGroup < logicalVertexGroups.size(); logicalGroup++) {
            List<Integer> vertices = logicalVertexGroups.get(logicalGroup);
            List<Integer> corners = new ArrayList<>();
            for (int i = 0; i < vertices.size(); i++) {
                List<Integer> vertexCorners = incidentCorners[vertices.get(i)];
                if (vertexCorners != null && !vertexCorners.isEmpty()) {
                    corners.addAll(vertexCorners);
                }
            }
            if (corners == null || corners.isEmpty()) {
                continue;
            }
            int count = corners.size();
            int[] parent = new int[count];
            for (int i = 0; i < count; i++) {
                parent[i] = i;
            }

            for (int a = 0; a < count; a++) {
                int faceA = corners.get(a) / 3;
                for (int b = a + 1; b < count; b++) {
                    int faceB = corners.get(b) / 3;
                    if (!facesShareLogicalEdgeAtVertex(indices, logicalGroupByVertex, faceA, faceB, logicalGroup)) {
                        continue;
                    }
                    double dot = faceNx[faceA] * faceNx[faceB]
                            + faceNy[faceA] * faceNy[faceB]
                            + faceNz[faceA] * faceNz[faceB];
                    if (dot >= cosThreshold) {
                        union(parent, a, b);
                    }
                }
            }

            boolean preserveSourceNormal = hasSourceNormals;
            int[] roots = new int[count];
            int[] rootSourceVertices = new int[count];
            int[] groupVertexIndices = new int[count];
            int groupCount = 0;
            for (int i = 0; i < count; i++) {
                int root = find(parent, i);
                int sourceVertex = indices[corners.get(i)];
                int group = -1;
                for (int g = 0; g < groupCount; g++) {
                    if (roots[g] == root && rootSourceVertices[g] == sourceVertex) {
                        group = g;
                        break;
                    }
                }
                if (group < 0) {
                    roots[groupCount] = root;
                    rootSourceVertices[groupCount] = sourceVertex;
                    groupVertexIndices[groupCount] = appendAutoSmoothVertex(
                            outPositions,
                            outNormals,
                            outUvs,
                            positions,
                            sourceNormals,
                            uvs,
                            hasUvs,
                            preserveSourceNormal,
                            sourceVertex,
                            corners,
                            parent,
                            root,
                            faceNx,
                            faceNy,
                            faceNz,
                            faceAx,
                            faceAy,
                            faceAz
                    );
                    group = groupCount;
                    groupCount++;
                }
                remappedIndices[corners.get(i)] = groupVertexIndices[group];
            }
        }

        return new AutoSmoothMeshData(
                toFloatArray(outPositions),
                toFloatArray(outNormals),
                outUvs == null ? null : toFloatArray(outUvs),
                remappedIndices
        );
    }

    private static int appendAutoSmoothVertex(List<Float> outPositions,
                                              List<Float> outNormals,
                                              List<Float> outUvs,
                                              float[] positions,
                                              float[] sourceNormals,
                                              float[] uvs,
                                              boolean hasUvs,
                                              boolean preserveSourceNormal,
                                              int sourceVertex,
                                              List<Integer> corners,
                                              int[] parent,
                                              int root,
                                              double[] faceNx,
                                              double[] faceNy,
                                              double[] faceNz,
                                              double[] faceAx,
                                              double[] faceAy,
                                              double[] faceAz) {
        double nx = 0.0;
        double ny = 0.0;
        double nz = 0.0;
        double fallbackNx = 0.0;
        double fallbackNy = 1.0;
        double fallbackNz = 0.0;
        boolean hasFallback = false;
        int rootFaceCount = 0;
        for (int i = 0; i < corners.size(); i++) {
            if (find(parent, i) != root) {
                continue;
            }
            int face = corners.get(i) / 3;
            rootFaceCount++;
            nx += faceAx[face];
            ny += faceAy[face];
            nz += faceAz[face];
            if (!hasFallback) {
                fallbackNx = faceNx[face];
                fallbackNy = faceNy[face];
                fallbackNz = faceNz[face];
                hasFallback = true;
            }
        }

        double lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq > 1e-20) {
            double invLen = 1.0 / Math.sqrt(lenSq);
            nx *= invLen;
            ny *= invLen;
            nz *= invLen;
        } else {
            nx = fallbackNx;
            ny = fallbackNy;
            nz = fallbackNz;
        }

        if (preserveSourceNormal && sourceNormals != null && rootFaceCount > 1) {
            int sourceBase = sourceVertex * 3;
            double sx = sourceNormals[sourceBase];
            double sy = sourceNormals[sourceBase + 1];
            double sz = sourceNormals[sourceBase + 2];
            double sourceLenSq = sx * sx + sy * sy + sz * sz;
            if (sourceLenSq > 1e-20) {
                double invSourceLen = 1.0 / Math.sqrt(sourceLenSq);
                sx *= invSourceLen;
                sy *= invSourceLen;
                sz *= invSourceLen;
                double alignment = nx * sx + ny * sy + nz * sz;
                if (alignment >= 0.95) {
                    nx = sx;
                    ny = sy;
                    nz = sz;
                }
            }
        }

        int pos = sourceVertex * 3;
        int newIndex = outPositions.size() / 3;
        outPositions.add(positions[pos]);
        outPositions.add(positions[pos + 1]);
        outPositions.add(positions[pos + 2]);
        outNormals.add((float) nx);
        outNormals.add((float) ny);
        outNormals.add((float) nz);
        if (hasUvs && outUvs != null) {
            int uv = sourceVertex * 2;
            outUvs.add(uvs[uv]);
            outUvs.add(uvs[uv + 1]);
        }
        return newIndex;
    }

    private static boolean facesShareLogicalEdgeAtVertex(int[] indices,
                                                         int[] logicalGroupByVertex,
                                                         int faceA,
                                                         int faceB,
                                                         int logicalVertexGroup) {
        int baseA = faceA * 3;
        int baseB = faceB * 3;
        int[] otherA = new int[2];
        int[] otherB = new int[2];
        int aCount = 0;
        int bCount = 0;
        for (int i = 0; i < 3; i++) {
            int indexA = indices[baseA + i];
            int groupA = logicalGroupByVertex[indexA];
            if (groupA != logicalVertexGroup && aCount < 2) {
                otherA[aCount++] = groupA;
            }
            int indexB = indices[baseB + i];
            int groupB = logicalGroupByVertex[indexB];
            if (groupB != logicalVertexGroup && bCount < 2) {
                otherB[bCount++] = groupB;
            }
        }
        for (int i = 0; i < aCount; i++) {
            for (int j = 0; j < bCount; j++) {
                if (otherA[i] == otherB[j]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[index] != index) {
            int next = parent[index];
            parent[index] = root;
            index = next;
        }
        return root;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[rootB] = rootA;
        }
    }

    private static final class AutoSmoothMeshData {
        private final float[] positions;
        private final float[] normals;
        private final float[] uvs;
        private final int[] indices;

        private AutoSmoothMeshData(float[] positions, float[] normals, float[] uvs, int[] indices) {
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.indices = indices;
        }
    }

    private record VertexPositionKey(long xBits, long yBits, long zBits) {
        private static final double QUANTIZATION = 1_000_000.0;

        private static VertexPositionKey from(float x, float y, float z) {
            return new VertexPositionKey(quantize(x), quantize(y), quantize(z));
        }

        private static long quantize(float value) {
            return Math.round(value * QUANTIZATION);
        }
    }
}
