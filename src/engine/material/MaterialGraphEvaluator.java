package engine.material;

import engine.math.MathUtil;
import engine.math.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tady vyhodnocuju materiálový graf uzlů do konkrétních parametrů shaderu pro všechny režimy vykreslení.
 */
public final class MaterialGraphEvaluator {

    public static final class Context {
        private double worldX;
        private double worldY;
        private double worldZ;
        private boolean hasUv0;
        private double uv0U;
        private double uv0V;
        private boolean hasUv1;
        private double uv1U;
        private double uv1V;

        public Context() {
            reset(0.0, 0.0, 0.0, false, 0.0, 0.0, false, 0.0, 0.0);
        }

        public Context(double worldX,
                       double worldY,
                       double worldZ,
                       boolean hasUv0,
                       double uv0U,
                       double uv0V,
                       boolean hasUv1,
                       double uv1U,
                       double uv1V) {
            reset(worldX, worldY, worldZ, hasUv0, uv0U, uv0V, hasUv1, uv1U, uv1V);
        }

        public Context reset(double worldX,
                             double worldY,
                             double worldZ,
                             boolean hasUv0,
                             double uv0U,
                             double uv0V,
                             boolean hasUv1,
                             double uv1U,
                             double uv1V) {
            this.worldX = worldX;
            this.worldY = worldY;
            this.worldZ = worldZ;
            this.hasUv0 = hasUv0;
            this.uv0U = uv0U;
            this.uv0V = uv0V;
            this.hasUv1 = hasUv1;
            this.uv1U = uv1U;
            this.uv1V = uv1V;
            return this;
        }

        public Context resetRaster(double worldX,
                                   double worldY,
                                   double worldZ,
                                   float[] uv0,
                                   float[] uv1) {
            return reset(
                    worldX,
                    worldY,
                    worldZ,
                    uv0 != null && uv0.length >= 2,
                    uv0 != null && uv0.length >= 2 ? uv0[0] : 0.0,
                    uv0 != null && uv0.length >= 2 ? uv0[1] : 0.0,
                    uv1 != null && uv1.length >= 2,
                    uv1 != null && uv1.length >= 2 ? uv1[0] : 0.0,
                    uv1 != null && uv1.length >= 2 ? uv1[1] : 0.0
            );
        }

        public Context resetTriangle(double worldX,
                                     double worldY,
                                     double worldZ,
                                     boolean hasUv0,
                                     double uv0U,
                                     double uv0V,
                                     boolean hasUv1,
                                     double uv1U,
                                     double uv1V) {
            return reset(worldX, worldY, worldZ, hasUv0, uv0U, uv0V, hasUv1, uv1U, uv1V);
        }

        public static Context ofRaster(double worldX,
                                       double worldY,
                                       double worldZ,
                                       float[] uv0,
                                       float[] uv1) {
            return new Context().resetRaster(worldX, worldY, worldZ, uv0, uv1);
        }

        public static Context ofTriangle(double worldX,
                                         double worldY,
                                         double worldZ,
                                         boolean hasUv0,
                                         double uv0U,
                                         double uv0V,
                                         boolean hasUv1,
                                         double uv1U,
                                         double uv1V) {
            return new Context().resetTriangle(worldX, worldY, worldZ, hasUv0, uv0U, uv0V, hasUv1, uv1U, uv1V);
        }

        public boolean hasUv(int texCoord) {
            return texCoord > 0 ? hasUv1 : hasUv0;
        }

        public double uvU(int texCoord) {
            return texCoord > 0 ? uv1U : uv0U;
        }

        public double uvV(int texCoord) {
            return texCoord > 0 ? uv1V : uv0V;
        }

        public double worldX() {
            return worldX;
        }

        public double worldY() {
            return worldY;
        }

        public double worldZ() {
            return worldZ;
        }
    }

    public static final class Result {
        public boolean graphApplied;
        public boolean surfaceConnected;
        public boolean volumeConnected;
        public final Vec3 baseColor = new Vec3();
        public double opacity;
        public double roughness;
        public double metallic;
        public double specularFactor;
        public double refractiveIndex;
        public double dispersion;
        public double transmission;
        public final Vec3 emissionColor = new Vec3();
        public double emissionStrength;
        public double clearcoatFactor;
        public double clearcoatRoughness;
        public final Vec3 sheenColor = new Vec3();
        public double sheenRoughness;
        public final Vec3 mediumColor = new Vec3();
        public double density;
        public double anisotropy;
        public double thickness;

        public Result resetFromMaterial(PhongMaterial material) {
            graphApplied = false;
            surfaceConnected = false;
            volumeConnected = false;
            if (material == null) {
                baseColor.set(0.7, 0.7, 0.7);
                opacity = 1.0;
                roughness = 0.55;
                metallic = 0.0;
                specularFactor = 1.0;
                refractiveIndex = 1.0;
                dispersion = 0.0;
                transmission = 0.0;
                emissionColor.zero();
                emissionStrength = 0.0;
                clearcoatFactor = 0.0;
                clearcoatRoughness = 0.0;
                sheenColor.zero();
                sheenRoughness = 0.0;
                mediumColor.set(0.85, 0.9, 1.0);
                density = 0.0;
                anisotropy = 0.0;
                thickness = 0.1;
                return this;
            }
            baseColor.set(material.getDiffuseColor());
            opacity = material.getOpacity();
            roughness = material.getRoughness();
            metallic = material.getMetallic();
            specularFactor = material.getSpecularFactor();
            refractiveIndex = material.getRefractiveIndex();
            dispersion = material.getDispersion();
            transmission = material.getTransmission();
            emissionColor.set(material.getEmissionColor());
            emissionStrength = material.getEmissionStrength();
            clearcoatFactor = material.getClearcoatFactor();
            clearcoatRoughness = material.getClearcoatRoughness();
            sheenColor.set(material.getSheenColor());
            sheenRoughness = material.getSheenRoughness();
            mediumColor.set(material.getMediumColor());
            density = material.getDensity();
            anisotropy = material.getAnisotropy();
            thickness = material.getThickness();
            return this;
        }

        public static Result fromMaterial(PhongMaterial material) {
            return new Result().resetFromMaterial(material);
        }

        public boolean isPureVolume() {
            return volumeConnected && !surfaceConnected;
        }
    }

    static final class SurfaceData {
        final Vec3 baseColor = new Vec3();
        double roughness;
        double metallic;
        double specularFactor;
        double refractiveIndex;
        double dispersion;
        double transmission;
        double opacity;
        final Vec3 emissionColor = new Vec3();
        double emissionStrength;
        double clearcoatFactor;
        double clearcoatRoughness;
        final Vec3 sheenColor = new Vec3();
        double sheenRoughness;
    }

    static final class VolumeData {
        final Vec3 mediumColor = new Vec3();
        double density;
        double anisotropy;
        double thickness;
    }

    static final class EvalState {
        PhongMaterial material;
        MaterialNodeGraph graph;
        Context context;
        final Set<String> activeKeys = new HashSet<>();
        final Map<String, Double> valueCache = new HashMap<>();
        final Map<String, Vec3> colorCache = new HashMap<>();
        final Map<String, Vec3> vectorCache = new HashMap<>();
        final Map<Integer, SurfaceData> surfaceCache = new HashMap<>();
        final Map<Integer, VolumeData> volumeCache = new HashMap<>();
        final java.util.ArrayList<SurfaceData> surfacePool = new java.util.ArrayList<>();
        final java.util.ArrayList<VolumeData> volumePool = new java.util.ArrayList<>();
        final java.util.ArrayList<Vec3> colorPool = new java.util.ArrayList<>();
        final java.util.ArrayList<Vec3> vectorPool = new java.util.ArrayList<>();
        int surfacePoolCursor;
        int volumePoolCursor;
        int colorPoolCursor;
        int vectorPoolCursor;

        EvalState reset(PhongMaterial material, MaterialNodeGraph graph, Context context) {
            this.material = material;
            this.graph = graph;
            this.context = context;
            activeKeys.clear();
            valueCache.clear();
            colorCache.clear();
            vectorCache.clear();
            surfaceCache.clear();
            volumeCache.clear();
            surfacePoolCursor = 0;
            volumePoolCursor = 0;
            colorPoolCursor = 0;
            vectorPoolCursor = 0;
            return this;
        }

        SurfaceData borrowSurface() {
            if (surfacePoolCursor >= surfacePool.size()) {
                surfacePool.add(new SurfaceData());
            }
            return surfacePool.get(surfacePoolCursor++);
        }

        VolumeData borrowVolume() {
            if (volumePoolCursor >= volumePool.size()) {
                volumePool.add(new VolumeData());
            }
            return volumePool.get(volumePoolCursor++);
        }

        Vec3 borrowColor() {
            if (colorPoolCursor >= colorPool.size()) {
                colorPool.add(new Vec3());
            }
            return colorPool.get(colorPoolCursor++);
        }

        Vec3 borrowVector() {
            if (vectorPoolCursor >= vectorPool.size()) {
                vectorPool.add(new Vec3());
            }
            return vectorPool.get(vectorPoolCursor++);
        }
    }

    private static final ThreadLocal<Context> SHARED_CONTEXT =
            ThreadLocal.withInitial(Context::new);
    private static final ThreadLocal<Context> DEFAULT_CONTEXT =
            ThreadLocal.withInitial(Context::new);
    private static final ThreadLocal<EvalState> SHARED_EVAL_STATE =
            ThreadLocal.withInitial(EvalState::new);
    private static final ThreadLocal<Result> SHARED_RESULT =
            ThreadLocal.withInitial(Result::new);

    private MaterialGraphEvaluator() {
    }

    public static Result evaluate(PhongMaterial material, Context context) {
        return evaluateInto(new Result(), material, context);
    }

    public static Result evaluateShared(PhongMaterial material, Context context) {
        return evaluateInto(SHARED_RESULT.get(), material, context);
    }

    public static Result evaluateRasterShared(PhongMaterial material,
                                              double worldX,
                                              double worldY,
                                              double worldZ,
                                              float[] uv0,
                                              float[] uv1) {
        Context context = SHARED_CONTEXT.get().resetRaster(worldX, worldY, worldZ, uv0, uv1);
        return evaluateInto(SHARED_RESULT.get(), material, context);
    }

    public static Result evaluateTriangleShared(PhongMaterial material,
                                                double worldX,
                                                double worldY,
                                                double worldZ,
                                                boolean hasUv0,
                                                double uv0U,
                                                double uv0V,
                                                boolean hasUv1,
                                                double uv1U,
                                                double uv1V) {
        Context context = SHARED_CONTEXT.get().resetTriangle(worldX, worldY, worldZ, hasUv0, uv0U, uv0V, hasUv1, uv1U, uv1V);
        return evaluateInto(SHARED_RESULT.get(), material, context);
    }

    private static Result evaluateInto(Result out, PhongMaterial material, Context context) {
        out.resetFromMaterial(material);
        if (material == null || material.getNodeGraph() == null) {
            return out;
        }
        MaterialNodeGraph graph = material.getNodeGraph();
        MaterialNodeGraph.Node outputNode = graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        if (outputNode == null) {
            return out;
        }

        Context evalContext = context == null
                ? DEFAULT_CONTEXT.get().reset(0.0, 0.0, 0.0, false, 0.0, 0.0, false, 0.0, 0.0)
                : context;
        EvalState state = SHARED_EVAL_STATE.get().reset(material, graph, evalContext);
        out.graphApplied = true;

        MaterialNodeGraph.Link surfaceLink = graph.findInputLink(outputNode.getId(), "surface");
        MaterialNodeGraph.Link volumeLink = graph.findInputLink(outputNode.getId(), "volume");
        out.surfaceConnected = surfaceLink != null;
        out.volumeConnected = volumeLink != null;

        if (surfaceLink != null) {
            SurfaceData surface = MaterialGraphSurfaceEvaluator.evaluate(state, surfaceLink.getFromNodeId());
            if (surface != null) {
                out.baseColor.set(surface.baseColor);
                out.roughness = surface.roughness;
                out.metallic = surface.metallic;
                out.specularFactor = surface.specularFactor;
                out.refractiveIndex = surface.refractiveIndex;
                out.dispersion = surface.dispersion;
                out.transmission = surface.transmission;
                out.opacity = surface.opacity;
                out.emissionColor.set(surface.emissionColor);
                out.emissionStrength = surface.emissionStrength;
                out.clearcoatFactor = surface.clearcoatFactor;
                out.clearcoatRoughness = surface.clearcoatRoughness;
                out.sheenColor.set(surface.sheenColor);
                out.sheenRoughness = surface.sheenRoughness;
            }
        }

        if (volumeLink != null) {
            VolumeData volume = MaterialGraphVolumeEvaluator.evaluate(state, volumeLink.getFromNodeId());
            if (volume != null) {
                out.mediumColor.set(volume.mediumColor);
                out.density = volume.density;
                out.anisotropy = volume.anisotropy;
                out.thickness = volume.thickness;
            }
        } else {
            out.density = 0.0;
        }

        if (!out.surfaceConnected && out.volumeConnected) {
            out.baseColor.set(out.mediumColor).mulInPlace(MathUtil.clamp01(0.22 + out.density * 0.18));
            out.opacity = MathUtil.clamp01(0.14 + out.density * 0.12);
            out.roughness = 1.0;
            out.metallic = 0.0;
            out.specularFactor = 0.0;
            out.transmission = 1.0;
            out.emissionColor.zero();
            out.emissionStrength = 0.0;
        } else if (!out.surfaceConnected && !out.volumeConnected) {
            out.baseColor.zero();
            out.opacity = 0.0;
            out.roughness = 1.0;
            out.metallic = 0.0;
            out.specularFactor = 0.0;
            out.transmission = 1.0;
            out.emissionColor.zero();
            out.emissionStrength = 0.0;
            out.density = 0.0;
        }

        out.opacity = MathUtil.clamp01(out.opacity);
        out.roughness = MathUtil.clamp01(out.roughness);
        out.metallic = MathUtil.clamp01(out.metallic);
        out.transmission = MathUtil.clamp01(out.transmission);
        out.dispersion = MathUtil.clamp01(out.dispersion);
        out.clearcoatFactor = MathUtil.clamp01(out.clearcoatFactor);
        out.clearcoatRoughness = MathUtil.clamp01(out.clearcoatRoughness);
        out.sheenRoughness = MathUtil.clamp01(out.sheenRoughness);
        return out;
    }
}
