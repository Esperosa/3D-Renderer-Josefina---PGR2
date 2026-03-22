import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.post.DitherRenderer;
import engine.render.post.HexMosaicRenderer;
import engine.render.post.TemporalNoiseRenderer;
import engine.render.raster.RasterRenderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.PointLight;
import engine.scene.Scene;
import engine.util.ThreadPool;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class RendererBenchmarkSuite {

    private static final String RESULT_PREFIX = "BENCHMARK_RESULT";
    private static final String DEFAULT_MODE = "standard";
    private static final String DEFAULT_ISOLATED = "true";
    private static final double DEFAULT_START_TIME = 0.35;
    private static final double DEFAULT_TIME_STEP = 0.17;

    private RendererBenchmarkSuite() {
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 0 && "--run-case".equals(args[0])) {
            BenchmarkCaseSpec spec = BenchmarkCaseSpec.fromArgs(Arrays.copyOfRange(args, 1, args.length));
            BenchmarkCaseResult result = runCaseInCurrentJvm(spec);
            System.out.println(result.toMachineLine());
            return;
        }

        BenchmarkMatrixReport report = runMatrixBenchmarks();
        System.out.println(report.toCsv());
    }

    static BenchmarkMatrixReport runMatrixBenchmarks() throws Exception {
        BenchmarkMode mode = BenchmarkMode.fromText(System.getProperty("metrics.benchmark.mode", DEFAULT_MODE));
        boolean isolated = !"false".equalsIgnoreCase(System.getProperty("metrics.benchmark.isolated", DEFAULT_ISOLATED));
        BenchmarkRuntimeMetadata hostMetadata = BenchmarkRuntimeMetadata.capture();
        int availableProcessors = Math.max(1, hostMetadata.runtimeProcessors);
        List<CoreProfile> coreProfiles = buildCoreProfiles(availableProcessors);
        List<SceneProfile> sceneProfiles = buildSceneProfiles();
        List<SceneProfileSummary> sceneSummaries = summarizeScenes(sceneProfiles);
        List<ResolutionProfile> viewportResolutions = viewportResolutions(mode);
        List<ResolutionProfile> offlineResolutions = offlineResolutions(mode);
        List<RendererSpec> renderers = buildRendererSpecs();
        BenchmarkTuning viewportTuning = viewportTuning(mode);
        BenchmarkTuning offlineTuning = offlineTuning(mode);

        List<BenchmarkCaseSpec> cases = buildCaseMatrix(
                renderers,
                sceneProfiles,
                coreProfiles,
                viewportResolutions,
                offlineResolutions,
                viewportTuning,
                offlineTuning
        );

        List<BenchmarkCaseResult> results = new ArrayList<>(cases.size());
        for (BenchmarkCaseSpec spec : cases) {
            results.add(isolated ? runCaseIsolated(spec) : runCaseInCurrentJvm(spec));
        }
        results.sort(Comparator
                .comparing((BenchmarkCaseResult value) -> value.rendererLabel)
                .thenComparing(value -> value.workloadLabel)
                .thenComparing(value -> value.sceneLabel)
                .thenComparing(value -> value.resolutionHeight)
                .thenComparing(value -> value.resolutionWidth)
                .thenComparing(value -> value.coreProfileLabel));

        List<RendererAggregate> aggregates = aggregate(results);
        return new BenchmarkMatrixReport(
                mode,
                isolated,
                availableProcessors,
                hostMetadata,
                coreProfiles,
                viewportResolutions,
                offlineResolutions,
                sceneSummaries,
                results,
                aggregates
        );
    }

    private static BenchmarkCaseResult runCaseIsolated(BenchmarkCaseSpec spec) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable().toString());
        command.add("-Djava.awt.headless=true");
        command.add("-XX:ActiveProcessorCount=" + Math.max(2, spec.workerCount + 1));
        command.add("-cp");
        command.add(System.getProperty("java.class.path", "."));
        command.add(RendererBenchmarkSuite.class.getName());
        command.add("--run-case");
        command.addAll(spec.toArgs());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        long timeoutSeconds = timeoutSeconds(spec);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Renderer benchmark case timed out after "
                    + timeoutSeconds + " s: " + spec.caseLabel());
        }

        String resultLine = null;
        StringBuilder fullOutput = new StringBuilder();
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                fullOutput.append(line).append('\n');
                if (line.startsWith(RESULT_PREFIX + "|")) {
                    resultLine = line;
                }
            }
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Renderer benchmark child JVM failed for "
                    + spec.caseLabel() + ":\n" + fullOutput);
        }
        if (resultLine == null) {
            throw new IllegalStateException("Renderer benchmark child JVM produced no machine result for "
                    + spec.caseLabel() + ":\n" + fullOutput);
        }
        return BenchmarkCaseResult.fromMachineLine(spec, resultLine);
    }

    private static long timeoutSeconds(BenchmarkCaseSpec spec) {
        long pixels = (long) spec.resolutionWidth * (long) spec.resolutionHeight;
        long timeout = spec.rendererFamily == RendererFamily.OFFLINE ? 90L : 30L;
        if (pixels >= 640L * 360L) {
            timeout += spec.rendererFamily == RendererFamily.OFFLINE ? 90L : 20L;
        }
        if (pixels >= 1920L * 1080L) {
            timeout += spec.rendererFamily == RendererFamily.OFFLINE ? 300L : 40L;
        }
        if ("heavy-many".equals(spec.sceneId)) {
            timeout += spec.rendererFamily == RendererFamily.OFFLINE ? 90L : 20L;
        }
        return timeout;
    }

    private static Path resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home", "");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String fileName = windows ? "java.exe" : "java";
        if (!javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return Path.of(fileName);
    }

    private static BenchmarkCaseResult runCaseInCurrentJvm(BenchmarkCaseSpec spec) {
        BenchmarkRuntimeMetadata runtimeMetadata = BenchmarkRuntimeMetadata.capture();
        SceneBundle bundle = buildSceneBundle(spec.sceneId);
        PerspectiveCamera camera = buildCamera(spec.resolutionWidth, spec.resolutionHeight, bundle.cameraTargetZBias);

        primeRenderer(spec, bundle, camera);

        double[] firstFrameSamples = new double[spec.coldSamples];
        for (int sample = 0; sample < spec.coldSamples; sample++) {
            FrameBuffer fb = new FrameBuffer(spec.resolutionWidth, spec.resolutionHeight);
            Renderer renderer = createRenderer(spec.rendererId, spec.workerCount);
            double time = spec.startTime + sample * spec.timeStep;
            prepareFrameState(spec, bundle, camera, time, sample + 2);
            long started = System.nanoTime();
            renderer.init(spec.resolutionWidth, spec.resolutionHeight);
            renderer.render(bundle.scene, camera, fb, time);
            firstFrameSamples[sample] = nanosToMillis(System.nanoTime() - started);
            shutdownRenderer(renderer);
            cleanupBenchmarkResources();
        }

        List<Double> steadySamples = new ArrayList<>(spec.steadyPasses * spec.steadyRuns);
        for (int pass = 0; pass < spec.steadyPasses; pass++) {
            FrameBuffer fb = new FrameBuffer(spec.resolutionWidth, spec.resolutionHeight);
            Renderer renderer = createRenderer(spec.rendererId, spec.workerCount);
            renderer.init(spec.resolutionWidth, spec.resolutionHeight);
            double time = spec.startTime + 1.0 + pass * spec.timeStep * 3.0;
            int frameIndex = 64 + pass * (spec.steadyWarmups + spec.steadyRuns);

            for (int warmup = 0; warmup < spec.steadyWarmups; warmup++) {
                prepareFrameState(spec, bundle, camera, time, frameIndex++);
                renderer.render(bundle.scene, camera, fb, time);
                time += spec.timeStep;
            }
            for (int run = 0; run < spec.steadyRuns; run++) {
                prepareFrameState(spec, bundle, camera, time, frameIndex++);
                long started = System.nanoTime();
                renderer.render(bundle.scene, camera, fb, time);
                steadySamples.add(nanosToMillis(System.nanoTime() - started));
                time += spec.timeStep;
            }
            shutdownRenderer(renderer);
            cleanupBenchmarkResources();
        }

        return new BenchmarkCaseResult(
                spec.rendererId,
                spec.rendererLabel,
                spec.rendererFamily.label,
                spec.workloadId,
                spec.workloadLabel,
                spec.dynamicSequence,
                spec.sceneId,
                bundle.summary.label,
                bundle.summary.meshEntities,
                bundle.summary.lights,
                bundle.summary.totalTriangles,
                spec.resolutionId,
                spec.resolutionLabel,
                spec.resolutionWidth,
                spec.resolutionHeight,
                spec.coreProfileId,
                spec.coreProfileLabel,
                spec.workerCount,
                runtimeMetadata,
                BenchmarkStats.fromSamples(firstFrameSamples),
                BenchmarkStats.fromSamples(toDoubleArray(steadySamples))
        );
    }

    private static void primeRenderer(BenchmarkCaseSpec spec, SceneBundle bundle, PerspectiveCamera camera) {
        FrameBuffer fb = new FrameBuffer(spec.resolutionWidth, spec.resolutionHeight);
        Renderer renderer = createRenderer(spec.rendererId, spec.workerCount);
        renderer.init(spec.resolutionWidth, spec.resolutionHeight);
        prepareFrameState(spec, bundle, camera, spec.startTime - spec.timeStep, 0);
        renderer.render(bundle.scene, camera, fb, spec.startTime - spec.timeStep);
        prepareFrameState(spec, bundle, camera, spec.startTime, 1);
        renderer.render(bundle.scene, camera, fb, spec.startTime);
        shutdownRenderer(renderer);
        cleanupBenchmarkResources();
    }

    private static void prepareFrameState(BenchmarkCaseSpec spec,
                                          SceneBundle bundle,
                                          PerspectiveCamera camera,
                                          double time,
                                          int frameIndex) {
        if (spec.dynamicSequence) {
            double orbitAngle = Math.toRadians(-18.0 + frameIndex * 4.5);
            double orbitRadius = 7.05 + bundle.cameraTargetZBias + Math.cos(time * 0.7) * 0.32;
            double x = Math.sin(orbitAngle) * 1.45;
            double y = 1.16 + Math.sin(time * 1.1) * 0.18;
            double z = orbitRadius + Math.cos(orbitAngle) * 0.82;
            camera.setPosition(new Vec3(x, y, z));
            camera.lookAt(new Vec3(
                    Math.sin(time * 0.8) * 0.30,
                    0.16 + Math.cos(time * 0.6) * 0.10,
                    Math.sin(orbitAngle * 0.55) * 0.26
            ));
            bundle.scene.update(time);
            return;
        }

        camera.setPosition(new Vec3(0.0, 1.3, 7.4 + bundle.cameraTargetZBias));
        camera.lookAt(new Vec3(0.0, 0.2, 0.0));
        bundle.scene.update(0.0);
    }

    private static void shutdownRenderer(Renderer renderer) {
        if (renderer == null) {
            return;
        }
        try {
            renderer.setParameter("shutdown", true);
        } catch (RuntimeException ignored) {
            // Some renderers do not use an explicit shutdown flag.
        }
    }

    private static void cleanupBenchmarkResources() {
        System.gc();
        System.runFinalization();
    }

    private static Renderer createRenderer(String rendererId, int workerCount) {
        Renderer renderer;
        switch (rendererId) {
            case "raster-phong" -> {
                RasterRenderer raster = new RasterRenderer();
                raster.setParameter("parallel", workerCount > 1);
                raster.setParameter("workerCount", workerCount);
                raster.setParameter("unlitMode", false);
                raster.setParameter("frustumCulling", true);
                raster.setParameter("backfaceCulling", true);
                renderer = raster;
            }
            case "dither-blue-noise" -> {
                DitherRenderer dither = new DitherRenderer();
                dither.setParameter("parallel", workerCount > 1);
                dither.setParameter("workerCount", workerCount);
                dither.setParameter("style", DitherRenderer.DitherStyle.BLUE_NOISE);
                dither.setParameter("toneCount", 4);
                dither.setParameter("frustumCulling", true);
                dither.setParameter("backfaceCulling", true);
                renderer = dither;
            }
            case "temporal-noise" -> {
                TemporalNoiseRenderer temporal = new TemporalNoiseRenderer();
                temporal.setParameter("parallel", workerCount > 1);
                temporal.setParameter("workerCount", workerCount);
                temporal.setParameter("debugView", TemporalNoiseRenderer.DebugView.FINAL);
                temporal.setParameter("frustumCulling", true);
                temporal.setParameter("backfaceCulling", true);
                renderer = temporal;
            }
            case "hex-mosaic" -> {
                HexMosaicRenderer hex = new HexMosaicRenderer();
                hex.setParameter("parallel", workerCount > 1);
                hex.setParameter("workerCount", workerCount);
                hex.setParameter("frustumCulling", true);
                hex.setParameter("backfaceCulling", true);
                hex.setParameter("edgeAware", true);
                renderer = hex;
            }
            case "ray-tracing" -> {
                RayTracerRenderer ray = new RayTracerRenderer();
                ray.setParameter("workerCount", workerCount);
                ray.setParameter("tileSize", 24);
                ray.setParameter("samplesPerFrame", 1);
                ray.setParameter("maxDepth", 4);
                ray.setParameter("directLighting", true);
                ray.setParameter("shadows", true);
                ray.setParameter("reflections", true);
                ray.setParameter("sky", false);
                ray.setParameter("denoise", false);
                renderer = ray;
            }
            case "path-tracing" -> {
                PathTracerRenderer path = new PathTracerRenderer();
                path.setParameter("workerCount", workerCount);
                path.setParameter("tileSize", 24);
                path.setParameter("samplesPerFrame", 1);
                path.setParameter("maxDepth", 4);
                path.setParameter("directLighting", true);
                path.setParameter("sky", false);
                path.setParameter("denoise", false);
                renderer = path;
            }
            default -> throw new IllegalArgumentException("Unsupported renderer benchmark id: " + rendererId);
        }
        return renderer;
    }

    private static PerspectiveCamera buildCamera(int width, int height, double targetZBias) {
        double aspect = width / (double) Math.max(1, height);
        PerspectiveCamera camera = new PerspectiveCamera(60.0, aspect, 0.1, 120.0);
        camera.setPosition(new Vec3(0.0, 1.3, 7.4 + targetZBias));
        camera.lookAt(new Vec3(0.0, 0.2, 0.0));
        return camera;
    }

    private static List<BenchmarkCaseSpec> buildCaseMatrix(List<RendererSpec> renderers,
                                                           List<SceneProfile> sceneProfiles,
                                                           List<CoreProfile> coreProfiles,
                                                           List<ResolutionProfile> viewportResolutions,
                                                           List<ResolutionProfile> offlineResolutions,
                                                           BenchmarkTuning viewportTuning,
                                                           BenchmarkTuning offlineTuning) {
        List<BenchmarkCaseSpec> cases = new ArrayList<>();
        for (RendererSpec renderer : renderers) {
            List<ResolutionProfile> resolutions = renderer.family == RendererFamily.VIEWPORT
                    ? viewportResolutions
                    : offlineResolutions;
            List<WorkloadProfile> workloads = renderer.family == RendererFamily.VIEWPORT
                    ? viewportWorkloads()
                    : offlineWorkloads();
            BenchmarkTuning tuning = renderer.family == RendererFamily.VIEWPORT ? viewportTuning : offlineTuning;
            for (SceneProfile scene : sceneProfiles) {
                for (CoreProfile coreProfile : coreProfiles) {
                    for (WorkloadProfile workload : workloads) {
                        for (ResolutionProfile resolution : resolutions) {
                            cases.add(new BenchmarkCaseSpec(
                                    renderer.id,
                                    renderer.label,
                                    renderer.family,
                                    workload.id,
                                    workload.label,
                                    workload.dynamicSequence,
                                    scene.id,
                                    scene.label,
                                    resolution.id,
                                    resolution.label,
                                    resolution.width,
                                    resolution.height,
                                    coreProfile.id,
                                    coreProfile.label,
                                    coreProfile.workerCount,
                                    tuning.coldSamples,
                                    tuning.steadyWarmups,
                                    tuning.steadyRuns,
                                    tuning.steadyPasses,
                                    tuning.startTime,
                                    tuning.timeStep
                            ));
                        }
                    }
                }
            }
        }
        return cases;
    }

    private static List<RendererAggregate> aggregate(List<BenchmarkCaseResult> results) {
        LinkedHashMap<String, List<BenchmarkCaseResult>> grouped = new LinkedHashMap<>();
        for (BenchmarkCaseResult result : results) {
            String key = result.rendererId + "|" + result.coreProfileId + "|" + result.workloadId;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
        }

        List<RendererAggregate> aggregates = new ArrayList<>();
        for (Map.Entry<String, List<BenchmarkCaseResult>> entry : grouped.entrySet()) {
            List<BenchmarkCaseResult> rows = entry.getValue();
            BenchmarkCaseResult first = rows.get(0);
            double[] firstMedians = new double[rows.size()];
            double[] steadyMedians = new double[rows.size()];
            double worstFirst = Double.NEGATIVE_INFINITY;
            double worstSteady = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < rows.size(); i++) {
                BenchmarkCaseResult row = rows.get(i);
                firstMedians[i] = row.firstFrame.medianMs;
                steadyMedians[i] = row.steadyFrame.medianMs;
                worstFirst = Math.max(worstFirst, row.firstFrame.medianMs);
                worstSteady = Math.max(worstSteady, row.steadyFrame.medianMs);
            }
            aggregates.add(new RendererAggregate(
                    first.rendererId,
                    first.rendererLabel,
                    first.rendererFamily,
                    first.workloadId,
                    first.workloadLabel,
                    first.coreProfileId,
                    first.coreProfileLabel,
                    rows.size(),
                    geometricMean(firstMedians),
                    geometricMean(steadyMedians),
                    worstFirst,
                    worstSteady
            ));
        }
        aggregates.sort(Comparator
                .comparing((RendererAggregate value) -> value.workloadLabel)
                .thenComparing(value -> value.coreProfileLabel)
                .thenComparingDouble(value -> value.steadyGeoMeanMedianMs)
                .thenComparing(value -> value.rendererLabel));
        return aggregates;
    }

    private static double geometricMean(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double sumLog = 0.0;
        for (double value : values) {
            sumLog += Math.log(Math.max(1e-9, value));
        }
        return Math.exp(sumLog / values.length);
    }

    private static List<SceneProfileSummary> summarizeScenes(List<SceneProfile> profiles) {
        List<SceneProfileSummary> summaries = new ArrayList<>(profiles.size());
        for (SceneProfile profile : profiles) {
            SceneBundle bundle = buildSceneBundle(profile.id);
            summaries.add(bundle.summary);
        }
        summaries.sort(Comparator.comparing(SceneProfileSummary::label));
        return summaries;
    }

    private static List<RendererSpec> buildRendererSpecs() {
        List<RendererSpec> specs = new ArrayList<>();
        specs.add(new RendererSpec("raster-phong", "Raster / PHONG", RendererFamily.VIEWPORT));
        specs.add(new RendererSpec("dither-blue-noise", "Dithering", RendererFamily.VIEWPORT));
        specs.add(new RendererSpec("temporal-noise", "Temporal Noise", RendererFamily.VIEWPORT));
        specs.add(new RendererSpec("hex-mosaic", "Hex Mosaic", RendererFamily.VIEWPORT));
        specs.add(new RendererSpec("ray-tracing", "Ray Tracing", RendererFamily.OFFLINE));
        specs.add(new RendererSpec("path-tracing", "Path Tracing", RendererFamily.OFFLINE));
        return specs;
    }

    private static List<SceneProfile> buildSceneProfiles() {
        List<SceneProfile> profiles = new ArrayList<>();
        profiles.add(new SceneProfile("light-few", "Lehka scena / malo svetel"));
        profiles.add(new SceneProfile("light-many", "Lehka scena / vice svetel"));
        profiles.add(new SceneProfile("heavy-few", "Tezka scena / malo svetel"));
        profiles.add(new SceneProfile("heavy-many", "Tezka scena / vice svetel"));
        return profiles;
    }

    private static List<WorkloadProfile> viewportWorkloads() {
        List<WorkloadProfile> workloads = new ArrayList<>();
        workloads.add(new WorkloadProfile("static-steady", "Static steady", false));
        workloads.add(new WorkloadProfile("dynamic-sequence", "Dynamic sequence", true));
        return workloads;
    }

    private static List<WorkloadProfile> offlineWorkloads() {
        List<WorkloadProfile> workloads = new ArrayList<>();
        workloads.add(new WorkloadProfile("static-steady", "Static steady", false));
        return workloads;
    }

    private static List<ResolutionProfile> viewportResolutions(BenchmarkMode mode) {
        List<ResolutionProfile> values = new ArrayList<>();
        values.add(new ResolutionProfile("small", "320x180", 320, 180));
        values.add(new ResolutionProfile("sub-hd", "640x360", 640, 360));
        if (mode != BenchmarkMode.QUICK) {
            values.add(new ResolutionProfile("full-hd", "1920x1080", 1920, 1080));
        }
        return values;
    }

    private static List<ResolutionProfile> offlineResolutions(BenchmarkMode mode) {
        List<ResolutionProfile> values = new ArrayList<>();
        values.add(new ResolutionProfile("mini", "160x90", 160, 90));
        values.add(new ResolutionProfile("small", "320x180", 320, 180));
        if (mode != BenchmarkMode.QUICK) {
            values.add(new ResolutionProfile("sub-hd", "640x360", 640, 360));
        }
        if (mode == BenchmarkMode.FULL) {
            values.add(new ResolutionProfile("full-hd", "1920x1080", 1920, 1080));
        }
        return values;
    }

    private static List<CoreProfile> buildCoreProfiles(int availableProcessors) {
        List<CoreProfile> profiles = new ArrayList<>();
        profiles.add(new CoreProfile("single", "Single core", 1));
        double cpuScale = resolveCpuScale();
        int scaledWorkers = Math.max(1, Math.min(ThreadPool.recommendedWorkerCount(),
                (int) Math.ceil(availableProcessors * cpuScale)));
        if (scaledWorkers != 1) {
            profiles.add(new CoreProfile("scaled-cpu", "Scaled CPU " + formatPercent(cpuScale), scaledWorkers));
        }
        return profiles;
    }

    private static double resolveCpuScale() {
        String raw = System.getProperty("metrics.cpu.scale");
        if (raw == null || raw.isBlank()) {
            return 0.7;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) {
                return 0.7;
            }
            return Math.max(0.1, Math.min(1.0, value));
        } catch (NumberFormatException ex) {
            return 0.7;
        }
    }

    private static String formatPercent(double value) {
        long percent = Math.round(value * 100.0);
        return "(" + percent + "%)";
    }

    private static BenchmarkTuning viewportTuning(BenchmarkMode mode) {
        return switch (mode) {
            case QUICK -> new BenchmarkTuning(2, 1, 3, 2, DEFAULT_START_TIME, DEFAULT_TIME_STEP);
            case FULL -> new BenchmarkTuning(4, 2, 5, 3, DEFAULT_START_TIME, DEFAULT_TIME_STEP);
            case STANDARD -> new BenchmarkTuning(3, 2, 4, 3, DEFAULT_START_TIME, DEFAULT_TIME_STEP);
        };
    }

    private static BenchmarkTuning offlineTuning(BenchmarkMode mode) {
        return switch (mode) {
            case QUICK -> new BenchmarkTuning(2, 1, 2, 1, DEFAULT_START_TIME, DEFAULT_TIME_STEP);
            case FULL -> new BenchmarkTuning(3, 1, 3, 2, DEFAULT_START_TIME, DEFAULT_TIME_STEP);
            case STANDARD -> new BenchmarkTuning(2, 1, 3, 2, DEFAULT_START_TIME, DEFAULT_TIME_STEP);
        };
    }

    private static SceneBundle buildSceneBundle(String sceneId) {
        return switch (sceneId) {
            case "light-few" -> buildLightScene(false);
            case "light-many" -> buildLightScene(true);
            case "heavy-few" -> buildHeavyScene(false);
            case "heavy-many" -> buildHeavyScene(true);
            default -> throw new IllegalArgumentException("Unsupported benchmark scene id: " + sceneId);
        };
    }

    private static SceneBundle buildLightScene(boolean manyLights) {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.10, 0.10, 0.12));
        scene.setBackgroundColor(new Vec3(0.04, 0.05, 0.07));

        Entity floor = new Entity("floor",
                MeshGenerator.plane(12.0, 12.0, 8, 8),
                new PhongMaterial(new Vec3(0.44, 0.46, 0.50), 18.0));
        floor.getTransform().setPosition(new Vec3(0.0, -1.2, 0.0));
        scene.addEntity(floor);

        Entity cube = new Entity("cube",
                MeshGenerator.cube(1.3),
                new PhongMaterial(new Vec3(0.86, 0.40, 0.18), 28.0));
        cube.getTransform().setPosition(new Vec3(-1.75, -0.25, 0.45));
        cube.getTransform().setEulerAngles(Math.toRadians(12.0), Math.toRadians(26.0), Math.toRadians(-8.0));
        scene.addEntity(cube);

        Entity sphere = new Entity("sphere",
                MeshGenerator.sphere(0.85, 26, 20),
                new PhongMaterial(new Vec3(0.24, 0.66, 0.92), 56.0));
        sphere.getTransform().setPosition(new Vec3(1.45, -0.18, 0.15));
        scene.addEntity(sphere);

        Entity cylinder = new Entity("cylinder",
                MeshGenerator.cylinder(0.48, 1.9, 28, 2),
                new PhongMaterial(new Vec3(0.74, 0.76, 0.80), 20.0));
        cylinder.getTransform().setPosition(new Vec3(0.10, -0.18, -1.05));
        cylinder.getTransform().setEulerAngles(0.0, Math.toRadians(-12.0), 0.0);
        scene.addEntity(cylinder);

        Entity torus = new Entity("torus",
                MeshGenerator.torus(0.72, 0.22, 28, 14),
                new PhongMaterial(new Vec3(0.78, 0.62, 0.21), 34.0));
        torus.getTransform().setPosition(new Vec3(0.15, 0.50, 0.85));
        torus.getTransform().setEulerAngles(Math.toRadians(72.0), Math.toRadians(18.0), 0.0);
        scene.addEntity(torus);

        addFewLights(scene);
        if (manyLights) {
            addSupplementalLights(scene);
        }

        scene.update(0.0);
        return new SceneBundle(scene, summarizeScene(scene, manyLights ? "Lehka scena / vice svetel" : "Lehka scena / malo svetel"), 0.0);
    }

    private static SceneBundle buildHeavyScene(boolean manyLights) {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.09, 0.09, 0.10));
        scene.setBackgroundColor(new Vec3(0.03, 0.035, 0.045));

        Entity floor = new Entity("floor",
                MeshGenerator.plane(18.0, 18.0, 24, 24),
                new PhongMaterial(new Vec3(0.34, 0.36, 0.40), 16.0));
        floor.getTransform().setPosition(new Vec3(0.0, -1.6, 0.0));
        scene.addEntity(floor);

        Entity knot = new Entity("knot",
                MeshGenerator.torusKnot(0.95, 0.34, 0.12, 220, 24, 2, 3),
                new PhongMaterial(new Vec3(0.87, 0.46, 0.20), 34.0));
        knot.getTransform().setPosition(new Vec3(0.0, 0.05, 0.0));
        knot.getTransform().setEulerAngles(Math.toRadians(22.0), Math.toRadians(28.0), Math.toRadians(10.0));
        scene.addEntity(knot);

        Entity torus = new Entity("torus",
                MeshGenerator.torus(1.05, 0.20, 60, 28),
                new PhongMaterial(new Vec3(0.17, 0.71, 0.84), 42.0));
        torus.getTransform().setPosition(new Vec3(-2.30, 0.10, -0.55));
        torus.getTransform().setEulerAngles(Math.toRadians(84.0), Math.toRadians(-10.0), Math.toRadians(8.0));
        scene.addEntity(torus);

        Entity sphereA = new Entity("sphere-a",
                MeshGenerator.sphere(0.95, 46, 34),
                new PhongMaterial(new Vec3(0.24, 0.62, 0.98), 58.0));
        sphereA.getTransform().setPosition(new Vec3(2.20, -0.20, 0.20));
        scene.addEntity(sphereA);

        Entity sphereB = new Entity("sphere-b",
                MeshGenerator.sphere(0.56, 40, 30),
                new PhongMaterial(new Vec3(0.72, 0.86, 0.28), 26.0));
        sphereB.getTransform().setPosition(new Vec3(1.25, 0.85, -1.20));
        scene.addEntity(sphereB);

        Entity cylinder = new Entity("cylinder",
                MeshGenerator.cylinder(0.42, 2.4, 42, 4),
                new PhongMaterial(new Vec3(0.72, 0.74, 0.78), 18.0));
        cylinder.getTransform().setPosition(new Vec3(-0.60, -0.15, -2.05));
        cylinder.getTransform().setEulerAngles(0.0, Math.toRadians(22.0), 0.0);
        scene.addEntity(cylinder);

        Entity cone = new Entity("cone",
                MeshGenerator.cone(0.72, 1.75, 40),
                new PhongMaterial(new Vec3(0.84, 0.22, 0.32), 18.0));
        cone.getTransform().setPosition(new Vec3(2.90, -0.30, -1.55));
        cone.getTransform().setEulerAngles(0.0, Math.toRadians(-18.0), 0.0);
        scene.addEntity(cone);

        Entity crystal = new Entity("crystal",
                MeshGenerator.crystal(0.82, 2.10, 12),
                new PhongMaterial(new Vec3(0.72, 0.84, 0.96), 52.0));
        crystal.getTransform().setPosition(new Vec3(-3.10, -0.22, 1.20));
        crystal.getTransform().setEulerAngles(0.0, Math.toRadians(28.0), 0.0);
        scene.addEntity(crystal);

        Entity prism = new Entity("prism",
                MeshGenerator.prism(0.78, 1.55, 10),
                new PhongMaterial(new Vec3(0.58, 0.41, 0.90), 24.0));
        prism.getTransform().setPosition(new Vec3(-2.45, -0.30, 2.20));
        prism.getTransform().setEulerAngles(0.0, Math.toRadians(-22.0), 0.0);
        scene.addEntity(prism);

        addFewLights(scene);
        if (manyLights) {
            addSupplementalLights(scene);
            addHeavySceneExtraLights(scene);
        }

        scene.update(0.0);
        return new SceneBundle(scene, summarizeScene(scene, manyLights ? "Tezka scena / vice svetel" : "Tezka scena / malo svetel"), 0.45);
    }

    private static void addFewLights(Scene scene) {
        scene.addLight(new DirectionalLight(new Vec3(-0.58, -1.0, -0.36), new Vec3(1.0, 0.96, 0.92), 1.30));
        scene.addLight(new DirectionalLight(new Vec3(0.38, -0.42, 0.28), new Vec3(0.38, 0.46, 0.66), 0.42));
    }

    private static void addSupplementalLights(Scene scene) {
        PointLight p0 = new PointLight(new Vec3(-2.4, 1.6, 1.8), new Vec3(1.0, 0.44, 0.24), 2.8);
        p0.setAttenuation(1.0, 0.045, 0.012);
        scene.addLight(p0);

        PointLight p1 = new PointLight(new Vec3(2.5, 1.8, 1.2), new Vec3(0.18, 0.72, 1.0), 2.4);
        p1.setAttenuation(1.0, 0.05, 0.014);
        scene.addLight(p1);

        PointLight p2 = new PointLight(new Vec3(0.0, 2.4, -2.6), new Vec3(0.92, 0.82, 0.30), 2.2);
        p2.setAttenuation(1.0, 0.05, 0.015);
        scene.addLight(p2);

        ConeLight cone = new ConeLight(new Vec3(0.0, 3.6, 2.4), new Vec3(0.86, 0.88, 1.0), 3.4);
        cone.setDirection(new Vec3(0.0, -1.0, -0.45));
        cone.setConeAngleDegrees(34.0);
        cone.setSoftness(0.35);
        cone.setAttenuation(1.0, 0.03, 0.010);
        scene.addLight(cone);

        AreaLight area = new AreaLight(new Vec3(-3.2, 2.8, -1.2), new Vec3(0.44, 0.92, 0.76), 2.6);
        area.setEmissionDirection(new Vec3(0.7, -0.75, 0.1));
        area.setSpreadAngleDegrees(70.0);
        area.setAttenuation(1.0, 0.035, 0.012);
        scene.addLight(area);
    }

    private static void addHeavySceneExtraLights(Scene scene) {
        PointLight rim = new PointLight(new Vec3(3.8, 2.6, -2.8), new Vec3(0.98, 0.26, 0.52), 2.8);
        rim.setAttenuation(1.0, 0.04, 0.014);
        scene.addLight(rim);

        PointLight fill = new PointLight(new Vec3(-4.0, 2.1, 3.4), new Vec3(0.22, 0.64, 1.0), 2.3);
        fill.setAttenuation(1.0, 0.04, 0.014);
        scene.addLight(fill);

        ConeLight spot = new ConeLight(new Vec3(1.0, 4.4, 4.2), new Vec3(1.0, 0.88, 0.56), 3.8);
        spot.setDirection(new Vec3(-0.15, -0.96, -0.24));
        spot.setConeAngleDegrees(30.0);
        spot.setSoftness(0.30);
        spot.setAttenuation(1.0, 0.025, 0.009);
        scene.addLight(spot);
    }

    private static SceneProfileSummary summarizeScene(Scene scene, String label) {
        int meshEntities = 0;
        int totalTriangles = 0;
        for (Entity entity : scene.getEntities()) {
            Mesh mesh = entity.getMesh();
            if (mesh != null) {
                meshEntities++;
                totalTriangles += mesh.getTriangleCount();
            }
        }
        return new SceneProfileSummary(
                sceneLabelToId(label),
                label,
                meshEntities,
                scene.getLights().size(),
                totalTriangles
        );
    }

    private static String sceneLabelToId(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.contains("lehka") && lower.contains("malo")) {
            return "light-few";
        }
        if (lower.contains("lehka")) {
            return "light-many";
        }
        if (lower.contains("tezka") && lower.contains("malo")) {
            return "heavy-few";
        }
        return "heavy-many";
    }

    private static double[] toDoubleArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    enum BenchmarkMode {
        QUICK,
        STANDARD,
        FULL;

        static BenchmarkMode fromText(String value) {
            if (value == null) {
                return STANDARD;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "quick" -> QUICK;
                case "full" -> FULL;
                default -> STANDARD;
            };
        }
    }

    private enum RendererFamily {
        VIEWPORT("Viewport"),
        OFFLINE("Offline");

        private final String label;

        RendererFamily(String label) {
            this.label = label;
        }
    }

    private record RendererSpec(String id, String label, RendererFamily family) {
    }

    private record SceneProfile(String id, String label) {
    }

    private record SceneBundle(Scene scene, SceneProfileSummary summary, double cameraTargetZBias) {
    }

    record SceneProfileSummary(String id,
                               String label,
                               int meshEntities,
                               int lights,
                               int totalTriangles) {
    }

    record CoreProfile(String id, String label, int workerCount) {
    }

    private record ResolutionProfile(String id, String label, int width, int height) {
    }

    private record BenchmarkTuning(int coldSamples,
                                   int steadyWarmups,
                                   int steadyRuns,
                                   int steadyPasses,
                                   double startTime,
                                   double timeStep) {
    }

    private record WorkloadProfile(String id, String label, boolean dynamicSequence) {
    }

    private record BenchmarkCaseSpec(String rendererId,
                                     String rendererLabel,
                                     RendererFamily rendererFamily,
                                     String workloadId,
                                     String workloadLabel,
                                     boolean dynamicSequence,
                                     String sceneId,
                                     String sceneLabel,
                                     String resolutionId,
                                     String resolutionLabel,
                                     int resolutionWidth,
                                     int resolutionHeight,
                                     String coreProfileId,
                                     String coreProfileLabel,
                                     int workerCount,
                                     int coldSamples,
                                     int steadyWarmups,
                                     int steadyRuns,
                                     int steadyPasses,
                                     double startTime,
                                     double timeStep) {

        String caseLabel() {
            return rendererLabel + " | " + workloadLabel + " | " + sceneLabel + " | "
                    + resolutionLabel + " | " + coreProfileLabel;
        }

        List<String> toArgs() {
            List<String> args = new ArrayList<>();
            args.add("--renderer");
            args.add(rendererId);
            args.add("--workload-id");
            args.add(workloadId);
            args.add("--workload-label");
            args.add(workloadLabel);
            args.add("--dynamic-sequence");
            args.add(Boolean.toString(dynamicSequence));
            args.add("--scene");
            args.add(sceneId);
            args.add("--resolution-id");
            args.add(resolutionId);
            args.add("--resolution-label");
            args.add(resolutionLabel);
            args.add("--width");
            args.add(Integer.toString(resolutionWidth));
            args.add("--height");
            args.add(Integer.toString(resolutionHeight));
            args.add("--core-id");
            args.add(coreProfileId);
            args.add("--core-label");
            args.add(coreProfileLabel);
            args.add("--workers");
            args.add(Integer.toString(workerCount));
            args.add("--cold-samples");
            args.add(Integer.toString(coldSamples));
            args.add("--steady-warmups");
            args.add(Integer.toString(steadyWarmups));
            args.add("--steady-runs");
            args.add(Integer.toString(steadyRuns));
            args.add("--steady-passes");
            args.add(Integer.toString(steadyPasses));
            args.add("--start-time");
            args.add(Double.toString(startTime));
            args.add("--time-step");
            args.add(Double.toString(timeStep));
            return args;
        }

        static BenchmarkCaseSpec fromArgs(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length - 1; i += 2) {
                values.put(args[i], args[i + 1]);
            }
            String rendererId = requireArg(values, "--renderer");
            RendererSpec renderer = rendererById(rendererId);
            String sceneId = requireArg(values, "--scene");
            SceneProfile scene = sceneById(sceneId);
            return new BenchmarkCaseSpec(
                    renderer.id,
                    renderer.label,
                    renderer.family,
                    requireArg(values, "--workload-id"),
                    requireArg(values, "--workload-label"),
                    Boolean.parseBoolean(requireArg(values, "--dynamic-sequence")),
                    scene.id,
                    scene.label,
                    requireArg(values, "--resolution-id"),
                    requireArg(values, "--resolution-label"),
                    Integer.parseInt(requireArg(values, "--width")),
                    Integer.parseInt(requireArg(values, "--height")),
                    requireArg(values, "--core-id"),
                    requireArg(values, "--core-label"),
                    Integer.parseInt(requireArg(values, "--workers")),
                    Integer.parseInt(requireArg(values, "--cold-samples")),
                    Integer.parseInt(requireArg(values, "--steady-warmups")),
                    Integer.parseInt(requireArg(values, "--steady-runs")),
                    Integer.parseInt(requireArg(values, "--steady-passes")),
                    Double.parseDouble(requireArg(values, "--start-time")),
                    Double.parseDouble(requireArg(values, "--time-step"))
            );
        }

        private static String requireArg(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Missing benchmark argument " + key);
            }
            return value;
        }
    }

    private static RendererSpec rendererById(String rendererId) {
        for (RendererSpec spec : buildRendererSpecs()) {
            if (spec.id.equals(rendererId)) {
                return spec;
            }
        }
        throw new IllegalArgumentException("Unknown renderer id " + rendererId);
    }

    private static SceneProfile sceneById(String sceneId) {
        for (SceneProfile spec : buildSceneProfiles()) {
            if (spec.id.equals(sceneId)) {
                return spec;
            }
        }
        throw new IllegalArgumentException("Unknown scene id " + sceneId);
    }

    static final class BenchmarkMatrixReport {
        final BenchmarkMode mode;
        final boolean isolated;
        final int availableProcessors;
        final BenchmarkRuntimeMetadata hostMetadata;
        final List<CoreProfile> coreProfiles;
        final List<ResolutionProfile> viewportResolutions;
        final List<ResolutionProfile> offlineResolutions;
        final List<SceneProfileSummary> scenes;
        final List<BenchmarkCaseResult> results;
        final List<RendererAggregate> aggregates;

        BenchmarkMatrixReport(BenchmarkMode mode,
                              boolean isolated,
                              int availableProcessors,
                              BenchmarkRuntimeMetadata hostMetadata,
                              List<CoreProfile> coreProfiles,
                              List<ResolutionProfile> viewportResolutions,
                              List<ResolutionProfile> offlineResolutions,
                              List<SceneProfileSummary> scenes,
                              List<BenchmarkCaseResult> results,
                              List<RendererAggregate> aggregates) {
            this.mode = mode;
            this.isolated = isolated;
            this.availableProcessors = availableProcessors;
            this.hostMetadata = hostMetadata;
            this.coreProfiles = coreProfiles;
            this.viewportResolutions = viewportResolutions;
            this.offlineResolutions = offlineResolutions;
            this.scenes = scenes;
            this.results = results;
            this.aggregates = aggregates;
        }

        String viewportResolutionLabels() {
            return joinResolutionLabels(viewportResolutions);
        }

        String offlineResolutionLabels() {
            return joinResolutionLabels(offlineResolutions);
        }

        List<RendererAggregate> aggregatesForWorkload(String workloadId) {
            List<RendererAggregate> selected = new ArrayList<>();
            for (RendererAggregate aggregate : aggregates) {
                if (aggregate.workloadId.equals(workloadId)) {
                    selected.add(aggregate);
                }
            }
            return selected;
        }

        List<BenchmarkCaseResult> stressCaseRows() {
            return stressCaseRows("static-steady");
        }

        List<BenchmarkCaseResult> stressCaseRows(String workloadId) {
            int viewportMaxArea = maxResolutionArea(viewportResolutions);
            int offlineMaxArea = maxResolutionArea(offlineResolutions);
            List<BenchmarkCaseResult> selected = new ArrayList<>();
            for (BenchmarkCaseResult result : results) {
                if (!workloadId.equals(result.workloadId)) {
                    continue;
                }
                if (!"heavy-many".equals(result.sceneId)) {
                    continue;
                }
                int area = result.resolutionWidth * result.resolutionHeight;
                if ("Viewport".equals(result.rendererFamily) && area == viewportMaxArea) {
                    selected.add(result);
                }
                if ("Offline".equals(result.rendererFamily) && area == offlineMaxArea) {
                    selected.add(result);
                }
            }
            selected.sort(Comparator
                    .comparing((BenchmarkCaseResult value) -> value.coreProfileLabel)
                    .thenComparing(value -> value.rendererLabel));
            return selected;
        }

        List<DynamicViewportAuditRow> dynamicViewportAuditRows() {
            int viewportMaxArea = maxResolutionArea(viewportResolutions);
            Map<String, BenchmarkCaseResult> staticRows = new LinkedHashMap<>();
            for (BenchmarkCaseResult result : results) {
                if (!"Viewport".equals(result.rendererFamily) || !"static-steady".equals(result.workloadId)) {
                    continue;
                }
                staticRows.put(dynamicAuditKey(result), result);
            }

            List<DynamicViewportAuditRow> selected = new ArrayList<>();
            for (BenchmarkCaseResult result : results) {
                if (!"Viewport".equals(result.rendererFamily)
                        || !"dynamic-sequence".equals(result.workloadId)
                        || !"heavy-many".equals(result.sceneId)) {
                    continue;
                }
                int area = result.resolutionWidth * result.resolutionHeight;
                if (area != viewportMaxArea) {
                    continue;
                }
                BenchmarkCaseResult staticRow = staticRows.get(dynamicAuditKey(result));
                if (staticRow == null) {
                    continue;
                }
                selected.add(new DynamicViewportAuditRow(
                        result.rendererId,
                        result.rendererLabel,
                        result.coreProfileId,
                        result.coreProfileLabel,
                        result.sceneId,
                        result.sceneLabel,
                        result.resolutionId,
                        result.resolutionLabel,
                        staticRow.firstFrame.medianMs,
                        staticRow.steadyFrame.medianMs,
                        result.firstFrame.medianMs,
                        result.steadyFrame.medianMs,
                        result.steadyFrame.medianMs / Math.max(1e-9, staticRow.steadyFrame.medianMs)
                ));
            }
            selected.sort(Comparator
                    .comparing((DynamicViewportAuditRow value) -> value.coreProfileLabel)
                    .thenComparingDouble(value -> value.dynamicSteadyMedianMs)
                    .thenComparing(value -> value.rendererLabel));
            return selected;
        }

        String toCsv() {
            StringBuilder out = new StringBuilder();
            out.append("# Renderer benchmark matrix\n");
            out.append("# mode=").append(mode.name().toLowerCase(Locale.ROOT)).append('\n');
            out.append("# isolated=").append(isolated).append('\n');
            out.append("# available_processors=").append(availableProcessors).append('\n');
            appendMetadataComment(out, "host_runtime_processors", Integer.toString(hostMetadata.runtimeProcessors));
            appendMetadataComment(out, "host_max_memory_mb", Long.toString(hostMetadata.maxMemoryMb));
            appendMetadataComment(out, "host_java_version", hostMetadata.javaVersion);
            appendMetadataComment(out, "host_java_vm_name", hostMetadata.javaVmName);
            appendMetadataComment(out, "host_java_vendor", hostMetadata.javaVendor);
            appendMetadataComment(out, "host_os_name", hostMetadata.osName);
            appendMetadataComment(out, "host_os_version", hostMetadata.osVersion);
            appendMetadataComment(out, "host_os_arch", hostMetadata.osArch);
            appendMetadataComment(out, "host_cpu_descriptor", hostMetadata.cpuDescriptor);
            out.append("renderer_id,renderer_label,renderer_family,workload_id,workload_label,dynamic_sequence,");
            out.append("scene_id,scene_label,mesh_entities,lights,triangles,");
            out.append("resolution_id,resolution_label,width,height,core_profile_id,core_profile_label,worker_count,");
            out.append("runtime_processors,max_memory_mb,java_version,java_vm_name,java_vendor,os_name,os_version,os_arch,cpu_descriptor,");
            out.append("first_samples,first_min_ms,first_median_ms,first_mean_ms,first_p90_ms,first_max_ms,first_stddev_ms,");
            out.append("steady_samples,steady_min_ms,steady_median_ms,steady_mean_ms,steady_p90_ms,steady_max_ms,steady_stddev_ms\n");
            for (BenchmarkCaseResult row : results) {
                out.append(csv(row.rendererId)).append(',');
                out.append(csv(row.rendererLabel)).append(',');
                out.append(csv(row.rendererFamily)).append(',');
                out.append(csv(row.workloadId)).append(',');
                out.append(csv(row.workloadLabel)).append(',');
                out.append(row.dynamicSequence).append(',');
                out.append(csv(row.sceneId)).append(',');
                out.append(csv(row.sceneLabel)).append(',');
                out.append(row.meshEntities).append(',');
                out.append(row.lights).append(',');
                out.append(row.totalTriangles).append(',');
                out.append(csv(row.resolutionId)).append(',');
                out.append(csv(row.resolutionLabel)).append(',');
                out.append(row.resolutionWidth).append(',');
                out.append(row.resolutionHeight).append(',');
                out.append(csv(row.coreProfileId)).append(',');
                out.append(csv(row.coreProfileLabel)).append(',');
                out.append(row.workerCount).append(',');
                appendRuntimeMetadata(out, row.runtimeMetadata);
                out.append(',');
                appendStats(out, row.firstFrame);
                out.append(',');
                appendStats(out, row.steadyFrame);
                out.append('\n');
            }
            return out.toString();
        }

        private static void appendStats(StringBuilder out, BenchmarkStats stats) {
            out.append(stats.sampleCount).append(',');
            out.append(formatDouble(stats.minMs)).append(',');
            out.append(formatDouble(stats.medianMs)).append(',');
            out.append(formatDouble(stats.meanMs)).append(',');
            out.append(formatDouble(stats.p90Ms)).append(',');
            out.append(formatDouble(stats.maxMs)).append(',');
            out.append(formatDouble(stats.stdDevMs));
        }

        private static int maxResolutionArea(List<ResolutionProfile> values) {
            int best = 0;
            for (ResolutionProfile value : values) {
                best = Math.max(best, value.width * value.height);
            }
            return best;
        }

        private static String dynamicAuditKey(BenchmarkCaseResult result) {
            return result.rendererId + "|" + result.coreProfileId + "|" + result.sceneId + "|" + result.resolutionId;
        }

        private static String joinResolutionLabels(List<ResolutionProfile> values) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(values.get(i).label);
            }
            return out.toString();
        }

        private static String csv(String value) {
            String safe = value == null ? "" : value.replace("\"", "\"\"");
            return "\"" + safe + "\"";
        }

        private static void appendRuntimeMetadata(StringBuilder out, BenchmarkRuntimeMetadata metadata) {
            out.append(metadata.runtimeProcessors).append(',');
            out.append(metadata.maxMemoryMb).append(',');
            out.append(csv(metadata.javaVersion)).append(',');
            out.append(csv(metadata.javaVmName)).append(',');
            out.append(csv(metadata.javaVendor)).append(',');
            out.append(csv(metadata.osName)).append(',');
            out.append(csv(metadata.osVersion)).append(',');
            out.append(csv(metadata.osArch)).append(',');
            out.append(csv(metadata.cpuDescriptor));
        }

        private static void appendMetadataComment(StringBuilder out, String key, String value) {
            out.append("# ").append(key).append("=").append(value == null ? "" : value).append('\n');
        }
    }

    static final class BenchmarkCaseResult {
        final String rendererId;
        final String rendererLabel;
        final String rendererFamily;
        final String workloadId;
        final String workloadLabel;
        final boolean dynamicSequence;
        final String sceneId;
        final String sceneLabel;
        final int meshEntities;
        final int lights;
        final int totalTriangles;
        final String resolutionId;
        final String resolutionLabel;
        final int resolutionWidth;
        final int resolutionHeight;
        final String coreProfileId;
        final String coreProfileLabel;
        final int workerCount;
        final BenchmarkRuntimeMetadata runtimeMetadata;
        final BenchmarkStats firstFrame;
        final BenchmarkStats steadyFrame;

        BenchmarkCaseResult(String rendererId,
                            String rendererLabel,
                            String rendererFamily,
                            String workloadId,
                            String workloadLabel,
                            boolean dynamicSequence,
                            String sceneId,
                            String sceneLabel,
                            int meshEntities,
                            int lights,
                            int totalTriangles,
                            String resolutionId,
                            String resolutionLabel,
                            int resolutionWidth,
                            int resolutionHeight,
                            String coreProfileId,
                            String coreProfileLabel,
                            int workerCount,
                            BenchmarkRuntimeMetadata runtimeMetadata,
                            BenchmarkStats firstFrame,
                            BenchmarkStats steadyFrame) {
            this.rendererId = rendererId;
            this.rendererLabel = rendererLabel;
            this.rendererFamily = rendererFamily;
            this.workloadId = workloadId;
            this.workloadLabel = workloadLabel;
            this.dynamicSequence = dynamicSequence;
            this.sceneId = sceneId;
            this.sceneLabel = sceneLabel;
            this.meshEntities = meshEntities;
            this.lights = lights;
            this.totalTriangles = totalTriangles;
            this.resolutionId = resolutionId;
            this.resolutionLabel = resolutionLabel;
            this.resolutionWidth = resolutionWidth;
            this.resolutionHeight = resolutionHeight;
            this.coreProfileId = coreProfileId;
            this.coreProfileLabel = coreProfileLabel;
            this.workerCount = workerCount;
            this.runtimeMetadata = runtimeMetadata;
            this.firstFrame = firstFrame;
            this.steadyFrame = steadyFrame;
        }

        String toMachineLine() {
            return RESULT_PREFIX
                    + "|" + firstFrame.sampleCount
                    + "|" + formatDouble(firstFrame.minMs)
                    + "|" + formatDouble(firstFrame.medianMs)
                    + "|" + formatDouble(firstFrame.meanMs)
                    + "|" + formatDouble(firstFrame.p90Ms)
                    + "|" + formatDouble(firstFrame.maxMs)
                    + "|" + formatDouble(firstFrame.stdDevMs)
                    + "|" + steadyFrame.sampleCount
                    + "|" + formatDouble(steadyFrame.minMs)
                    + "|" + formatDouble(steadyFrame.medianMs)
                    + "|" + formatDouble(steadyFrame.meanMs)
                    + "|" + formatDouble(steadyFrame.p90Ms)
                    + "|" + formatDouble(steadyFrame.maxMs)
                    + "|" + formatDouble(steadyFrame.stdDevMs)
                    + runtimeMetadata.toMachineFields();
        }

        static BenchmarkCaseResult fromMachineLine(BenchmarkCaseSpec spec, String line) {
            String[] parts = line.split("\\|");
            if (parts.length != 24 || !RESULT_PREFIX.equals(parts[0])) {
                throw new IllegalArgumentException("Invalid benchmark machine line: " + line);
            }
            SceneBundle bundle = buildSceneBundle(spec.sceneId);
            return new BenchmarkCaseResult(
                    spec.rendererId,
                    spec.rendererLabel,
                    spec.rendererFamily.label,
                    spec.workloadId,
                    spec.workloadLabel,
                    spec.dynamicSequence,
                    spec.sceneId,
                    bundle.summary.label,
                    bundle.summary.meshEntities,
                    bundle.summary.lights,
                    bundle.summary.totalTriangles,
                    spec.resolutionId,
                    spec.resolutionLabel,
                    spec.resolutionWidth,
                    spec.resolutionHeight,
                    spec.coreProfileId,
                    spec.coreProfileLabel,
                    spec.workerCount,
                    BenchmarkRuntimeMetadata.fromMachineParts(parts, 15),
                    BenchmarkStats.fromMachineParts(parts, 1),
                    BenchmarkStats.fromMachineParts(parts, 8)
            );
        }
    }

    static final class BenchmarkStats {
        final int sampleCount;
        final double minMs;
        final double medianMs;
        final double meanMs;
        final double p90Ms;
        final double maxMs;
        final double stdDevMs;

        BenchmarkStats(int sampleCount,
                       double minMs,
                       double medianMs,
                       double meanMs,
                       double p90Ms,
                       double maxMs,
                       double stdDevMs) {
            this.sampleCount = sampleCount;
            this.minMs = minMs;
            this.medianMs = medianMs;
            this.meanMs = meanMs;
            this.p90Ms = p90Ms;
            this.maxMs = maxMs;
            this.stdDevMs = stdDevMs;
        }

        static BenchmarkStats fromSamples(double[] samples) {
            if (samples == null || samples.length == 0) {
                return new BenchmarkStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            }
            double[] sorted = samples.clone();
            Arrays.sort(sorted);
            double sum = 0.0;
            double sumSq = 0.0;
            for (double sample : samples) {
                sum += sample;
                sumSq += sample * sample;
            }
            double mean = sum / samples.length;
            double variance = Math.max(0.0, sumSq / samples.length - mean * mean);
            return new BenchmarkStats(
                    samples.length,
                    sorted[0],
                    medianOfSorted(sorted),
                    mean,
                    percentileOfSorted(sorted, 0.90),
                    sorted[sorted.length - 1],
                    Math.sqrt(variance)
            );
        }

        static BenchmarkStats fromMachineParts(String[] parts, int offset) {
            return new BenchmarkStats(
                    Integer.parseInt(parts[offset]),
                    Double.parseDouble(parts[offset + 1]),
                    Double.parseDouble(parts[offset + 2]),
                    Double.parseDouble(parts[offset + 3]),
                    Double.parseDouble(parts[offset + 4]),
                    Double.parseDouble(parts[offset + 5]),
                    Double.parseDouble(parts[offset + 6])
            );
        }

        private static double medianOfSorted(double[] sorted) {
            int length = sorted.length;
            if ((length & 1) == 1) {
                return sorted[length / 2];
            }
            return (sorted[length / 2 - 1] + sorted[length / 2]) * 0.5;
        }

        private static double percentileOfSorted(double[] sorted, double percentile) {
            if (sorted.length == 1) {
                return sorted[0];
            }
            double position = clamp(percentile, 0.0, 1.0) * (sorted.length - 1);
            int lower = (int) Math.floor(position);
            int upper = (int) Math.ceil(position);
            if (lower == upper) {
                return sorted[lower];
            }
            double t = position - lower;
            return sorted[lower] * (1.0 - t) + sorted[upper] * t;
        }
    }

    static final class RendererAggregate {
        final String rendererId;
        final String rendererLabel;
        final String rendererFamily;
        final String workloadId;
        final String workloadLabel;
        final String coreProfileId;
        final String coreProfileLabel;
        final int caseCount;
        final double firstGeoMeanMedianMs;
        final double steadyGeoMeanMedianMs;
        final double worstFirstMedianMs;
        final double worstSteadyMedianMs;

        RendererAggregate(String rendererId,
                          String rendererLabel,
                          String rendererFamily,
                          String workloadId,
                          String workloadLabel,
                          String coreProfileId,
                          String coreProfileLabel,
                          int caseCount,
                          double firstGeoMeanMedianMs,
                          double steadyGeoMeanMedianMs,
                          double worstFirstMedianMs,
                          double worstSteadyMedianMs) {
            this.rendererId = rendererId;
            this.rendererLabel = rendererLabel;
            this.rendererFamily = rendererFamily;
            this.workloadId = workloadId;
            this.workloadLabel = workloadLabel;
            this.coreProfileId = coreProfileId;
            this.coreProfileLabel = coreProfileLabel;
            this.caseCount = caseCount;
            this.firstGeoMeanMedianMs = firstGeoMeanMedianMs;
            this.steadyGeoMeanMedianMs = steadyGeoMeanMedianMs;
            this.worstFirstMedianMs = worstFirstMedianMs;
            this.worstSteadyMedianMs = worstSteadyMedianMs;
        }
    }

    record DynamicViewportAuditRow(String rendererId,
                                   String rendererLabel,
                                   String coreProfileId,
                                   String coreProfileLabel,
                                   String sceneId,
                                   String sceneLabel,
                                   String resolutionId,
                                   String resolutionLabel,
                                   double staticFirstMedianMs,
                                   double staticSteadyMedianMs,
                                   double dynamicFirstMedianMs,
                                   double dynamicSteadyMedianMs,
                                   double dynamicSteadySlowdown) {
    }

    static final class BenchmarkRuntimeMetadata {
        final int runtimeProcessors;
        final long maxMemoryMb;
        final String javaVersion;
        final String javaVmName;
        final String javaVendor;
        final String osName;
        final String osVersion;
        final String osArch;
        final String cpuDescriptor;

        BenchmarkRuntimeMetadata(int runtimeProcessors,
                                 long maxMemoryMb,
                                 String javaVersion,
                                 String javaVmName,
                                 String javaVendor,
                                 String osName,
                                 String osVersion,
                                 String osArch,
                                 String cpuDescriptor) {
            this.runtimeProcessors = runtimeProcessors;
            this.maxMemoryMb = maxMemoryMb;
            this.javaVersion = javaVersion;
            this.javaVmName = javaVmName;
            this.javaVendor = javaVendor;
            this.osName = osName;
            this.osVersion = osVersion;
            this.osArch = osArch;
            this.cpuDescriptor = cpuDescriptor;
        }

        static BenchmarkRuntimeMetadata capture() {
            return new BenchmarkRuntimeMetadata(
                    Runtime.getRuntime().availableProcessors(),
                    Math.round(Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0),
                    System.getProperty("java.version", "unknown"),
                    System.getProperty("java.vm.name", "unknown"),
                    System.getProperty("java.vendor", "unknown"),
                    System.getProperty("os.name", "unknown"),
                    System.getProperty("os.version", "unknown"),
                    System.getProperty("os.arch", "unknown"),
                    resolveCpuDescriptor()
            );
        }

        static BenchmarkRuntimeMetadata fromMachineParts(String[] parts, int offset) {
            return new BenchmarkRuntimeMetadata(
                    Integer.parseInt(parts[offset]),
                    Long.parseLong(parts[offset + 1]),
                    machineDecode(parts[offset + 2]),
                    machineDecode(parts[offset + 3]),
                    machineDecode(parts[offset + 4]),
                    machineDecode(parts[offset + 5]),
                    machineDecode(parts[offset + 6]),
                    machineDecode(parts[offset + 7]),
                    machineDecode(parts[offset + 8])
            );
        }

        String toMachineFields() {
            return "|"
                    + runtimeProcessors
                    + "|" + maxMemoryMb
                    + "|" + machineEncode(javaVersion)
                    + "|" + machineEncode(javaVmName)
                    + "|" + machineEncode(javaVendor)
                    + "|" + machineEncode(osName)
                    + "|" + machineEncode(osVersion)
                    + "|" + machineEncode(osArch)
                    + "|" + machineEncode(cpuDescriptor);
        }

        private static String resolveCpuDescriptor() {
            String envDescriptor = System.getenv("PROCESSOR_IDENTIFIER");
            if (envDescriptor != null && !envDescriptor.isBlank()) {
                return envDescriptor.trim();
            }

            Path cpuInfo = Path.of("/proc/cpuinfo");
            if (Files.exists(cpuInfo)) {
                try {
                    for (String line : Files.readAllLines(cpuInfo, StandardCharsets.UTF_8)) {
                        String prefix = "model name";
                        if (line.startsWith(prefix) && line.contains(":")) {
                            return line.substring(line.indexOf(':') + 1).trim();
                        }
                    }
                } catch (IOException ignored) {
                    // Fall back to os.arch when cpu info is unavailable.
                }
            }
            return System.getProperty("os.arch", "unknown");
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static String machineEncode(String value) {
        String safe = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String machineDecode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
