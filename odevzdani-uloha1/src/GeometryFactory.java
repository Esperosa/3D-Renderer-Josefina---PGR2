final class GeometryFactory {
    private GeometryFactory() {
    }

    static Mesh cube(double size, Vec3 color) {
        Mesh m = new Mesh();
        double h = size * 0.5;
        addCubeFace(m, new Vec3(-h, -h, h), new Vec3(h, -h, h), new Vec3(h, h, h), new Vec3(-h, h, h),
                new Vec3(0, 0, 1), color);
        addCubeFace(m, new Vec3(h, -h, -h), new Vec3(-h, -h, -h), new Vec3(-h, h, -h), new Vec3(h, h, -h),
                new Vec3(0, 0, -1), color.mul(0.92));
        addCubeFace(m, new Vec3(-h, -h, -h), new Vec3(-h, -h, h), new Vec3(-h, h, h), new Vec3(-h, h, -h),
                new Vec3(-1, 0, 0), color.mul(0.84));
        addCubeFace(m, new Vec3(h, -h, h), new Vec3(h, -h, -h), new Vec3(h, h, -h), new Vec3(h, h, h),
                new Vec3(1, 0, 0), color.mul(1.06).clamp01());
        addCubeFace(m, new Vec3(-h, h, h), new Vec3(h, h, h), new Vec3(h, h, -h), new Vec3(-h, h, -h),
                new Vec3(0, 1, 0), color.mul(1.12).clamp01());
        addCubeFace(m, new Vec3(-h, -h, -h), new Vec3(h, -h, -h), new Vec3(h, -h, h), new Vec3(-h, -h, h),
                new Vec3(0, -1, 0), color.mul(0.75));
        return m;
    }

    private static void addCubeFace(Mesh m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 normal, Vec3 color) {
        int ia = m.addVertex(new Vertex(a, normal, new Vec2(0, 1), color));
        int ib = m.addVertex(new Vertex(b, normal, new Vec2(1, 1), color.mul(0.96).clamp01()));
        int ic = m.addVertex(new Vertex(c, normal, new Vec2(1, 0), color.mul(1.06).clamp01()));
        int id = m.addVertex(new Vertex(d, normal, new Vec2(0, 0), color.mul(1.02).clamp01()));
        m.addQuad(ia, ib, ic, id);
    }

    static Mesh tetrahedron(double size, Vec3 color) {
        Mesh m = new Mesh();
        double h = size * 0.65;
        Vec3[] p = {
                new Vec3(0, h, 0),
                new Vec3(-size * 0.55, -h * 0.45, size * 0.48),
                new Vec3(size * 0.55, -h * 0.45, size * 0.48),
                new Vec3(0, -h * 0.45, -size * 0.62)
        };
        addFlatTri(m, p[0], p[1], p[2], color, new Vec2(0.5, 0), new Vec2(0, 1), new Vec2(1, 1));
        addFlatTri(m, p[0], p[2], p[3], color.mul(0.92), new Vec2(0.5, 0), new Vec2(0, 1), new Vec2(1, 1));
        addFlatTri(m, p[0], p[3], p[1], color.mul(1.08).clamp01(), new Vec2(0.5, 0), new Vec2(0, 1), new Vec2(1, 1));
        addFlatTri(m, p[1], p[3], p[2], color.mul(0.82), new Vec2(0, 1), new Vec2(0.5, 0), new Vec2(1, 1));
        return m;
    }

    static Mesh sphere(double radius, int lat, int lon, Vec3 color) {
        Mesh m = new Mesh();
        int[][] index = new int[lat + 1][lon + 1];
        for (int y = 0; y <= lat; y++) {
            double v = y / (double) lat;
            double phi = Math.PI * v;
            for (int x = 0; x <= lon; x++) {
                double u = x / (double) lon;
                double theta = Math.PI * 2.0 * u;
                Vec3 n = new Vec3(Math.cos(theta) * Math.sin(phi), Math.cos(phi), Math.sin(theta) * Math.sin(phi));
                Vec3 vc = color.mul(0.85 + 0.25 * v).clamp01();
                index[y][x] = m.addVertex(new Vertex(n.mul(radius), n, new Vec2(u, v), vc));
            }
        }
        for (int y = 0; y < lat; y++) {
            for (int x = 0; x < lon; x++) {
                int a = index[y][x];
                int b = index[y][x + 1];
                int c = index[y + 1][x + 1];
                int d = index[y + 1][x];
                if (y > 0) {
                    m.addTriangle(a, b, d);
                }
                if (y < lat - 1) {
                    m.addTriangle(b, c, d);
                }
            }
        }
        m.topologyNote = "latitude/longitude triangle strips";
        return m;
    }

    static Mesh cylinder(double radius, double height, int sides, Vec3 color) {
        Mesh m = new Mesh();
        int[] bottom = new int[sides];
        int[] top = new int[sides];
        double half = height * 0.5;
        for (int i = 0; i < sides; i++) {
            double u = i / (double) sides;
            double a = Math.PI * 2.0 * u;
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;
            Vec3 normal = new Vec3(Math.cos(a), 0, Math.sin(a));
            bottom[i] = m.addVertex(new Vertex(new Vec3(x, -half, z), normal, new Vec2(u, 1), color.mul(0.9)));
            top[i] = m.addVertex(new Vertex(new Vec3(x, half, z), normal, new Vec2(u, 0), color.mul(1.1).clamp01()));
        }
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            m.addQuad(bottom[i], bottom[j], top[j], top[i]);
        }
        int topCenter = m.addVertex(new Vertex(new Vec3(0, half, 0), new Vec3(0, 1, 0), new Vec2(0.5, 0.5), color));
        int bottomCenter = m.addVertex(new Vertex(new Vec3(0, -half, 0), new Vec3(0, -1, 0), new Vec2(0.5, 0.5), color.mul(0.8)));
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            m.addTriangle(topCenter, top[i], top[j]);
            m.addTriangle(bottomCenter, bottom[j], bottom[i]);
        }
        m.topologyNote = "side quads + cap fans";
        return m;
    }

    static Mesh cone(double radius, double height, int sides, Vec3 color) {
        Mesh m = new Mesh();
        int[] rim = new int[sides];
        double half = height * 0.5;
        Vec3 apex = new Vec3(0, half, 0);
        for (int i = 0; i < sides; i++) {
            double u = i / (double) sides;
            double a = Math.PI * 2.0 * u;
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;
            Vec3 normal = new Vec3(Math.cos(a), radius / height, Math.sin(a)).normalize();
            rim[i] = m.addVertex(new Vertex(new Vec3(x, -half, z), normal, new Vec2(u, 1), color.mul(0.92)));
        }
        int[] apexes = new int[sides];
        for (int i = 0; i < sides; i++) {
            double u = (i + 0.5) / sides;
            double a = Math.PI * 2.0 * u;
            Vec3 normal = new Vec3(Math.cos(a), radius / height, Math.sin(a)).normalize();
            apexes[i] = m.addVertex(new Vertex(apex, normal, new Vec2(u, 0), color.mul(1.12).clamp01()));
        }
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            m.addTriangle(rim[i], rim[j], apexes[i]);
        }
        int center = m.addVertex(new Vertex(new Vec3(0, -half, 0), new Vec3(0, -1, 0), new Vec2(0.5, 0.5), color.mul(0.8)));
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            m.addTriangle(center, rim[j], rim[i]);
        }
        m.topologyNote = "triangle fan";
        return m;
    }

    static Mesh bicubicPatch(int steps, Vec3 color) {
        Mesh m = new Mesh();
        Vec3[][] cp = {
                {new Vec3(-0.9, -0.25, -0.9), new Vec3(-0.3, 0.25, -0.9), new Vec3(0.3, -0.1, -0.9), new Vec3(0.9, 0.20, -0.9)},
                {new Vec3(-0.9, 0.15, -0.3), new Vec3(-0.3, 0.70, -0.3), new Vec3(0.3, 0.35, -0.3), new Vec3(0.9, 0.45, -0.3)},
                {new Vec3(-0.9, -0.10, 0.3), new Vec3(-0.3, 0.35, 0.3), new Vec3(0.3, 0.85, 0.3), new Vec3(0.9, 0.15, 0.3)},
                {new Vec3(-0.9, 0.20, 0.9), new Vec3(-0.3, -0.10, 0.9), new Vec3(0.3, 0.45, 0.9), new Vec3(0.9, -0.20, 0.9)}
        };
        int[][] index = new int[steps + 1][steps + 1];
        for (int y = 0; y <= steps; y++) {
            double v = y / (double) steps;
            for (int x = 0; x <= steps; x++) {
                double u = x / (double) steps;
                Vec3 p = bezier(cp, u, v);
                Vec3 du = bezier(cp, Math.min(1.0, u + 0.01), v).sub(bezier(cp, Math.max(0.0, u - 0.01), v));
                Vec3 dv = bezier(cp, u, Math.min(1.0, v + 0.01)).sub(bezier(cp, u, Math.max(0.0, v - 0.01)));
                Vec3 normal = dv.cross(du).normalize();
                Vec3 vc = color.mul(0.75 + 0.35 * v).clamp01();
                index[y][x] = m.addVertex(new Vertex(p, normal, new Vec2(u, v), vc));
            }
        }
        for (int y = 0; y < steps; y++) {
            for (int x = 0; x < steps; x++) {
                m.addQuad(index[y][x], index[y][x + 1], index[y + 1][x + 1], index[y + 1][x]);
            }
        }
        m.topologyNote = "bicubic Bezier patch";
        return m;
    }

    private static Vec3 bezier(Vec3[][] cp, double u, double v) {
        double[] bu = bernstein(u);
        double[] bv = bernstein(v);
        Vec3 p = Vec3.ZERO;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                p = p.add(cp[y][x].mul(bu[x] * bv[y]));
            }
        }
        return p;
    }

    private static double[] bernstein(double t) {
        double s = 1.0 - t;
        return new double[]{s * s * s, 3.0 * t * s * s, 3.0 * t * t * s, t * t * t};
    }

    private static void addFlatTri(Mesh m, Vec3 a, Vec3 b, Vec3 c, Vec3 color, Vec2 uva, Vec2 uvb, Vec2 uvc) {
        Vec3 normal = b.sub(a).cross(c.sub(a)).normalize();
        int ia = m.addVertex(new Vertex(a, normal, uva, color));
        int ib = m.addVertex(new Vertex(b, normal, uvb, color.mul(0.95).clamp01()));
        int ic = m.addVertex(new Vertex(c, normal, uvc, color.mul(1.1).clamp01()));
        m.addTriangle(ia, ib, ic);
    }
}
