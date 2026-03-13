import engine.camera.PerspectiveCamera;
import engine.core.RenderMode;
import engine.geometry.MeshGenerator;
import engine.material.MaterialNodeGraph;
import engine.material.MaterialPresets;
import engine.material.MaterialPreviewRenderer;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.post.DitherRenderer;
import engine.render.post.HexMosaicRenderer;
import engine.render.post.TemporalNoiseRenderer;
import engine.render.raster.RasterRenderer;
import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.util.AnimatedGifWriter;
import engine.util.MjpegAviWriter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tady generuju reprodukovatelný report s metrikami projektu a lehkým renderer benchmarkem.
 * Používám ho jako zdroj tvrdých dat pro README, aby tabulky nevznikaly ručně.
 */
public final class ProjectMetricsReport {

    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern UI_IMPORT_PATTERN = Pattern.compile("endsWith\\(\"\\.([a-z0-9]+)\"\\)");
    private static final Pattern IMPORT_CASE_PATTERN = Pattern.compile("case\\s+\"([a-z0-9]+)\"");
    private static final ResolutionCase SUB_HD = new ResolutionCase("Sub HD", 640, 360);
    private static final ResolutionCase HD = new ResolutionCase("HD", 1280, 720);
    private static final ResolutionCase FULL_HD = new ResolutionCase("Full HD", 1920, 1080);
    private static final ResolutionCase[] RESOLUTION_CASES = {SUB_HD, HD, FULL_HD};

    private ProjectMetricsReport() {
    }

    public static void main(String[] args) throws Exception {
        MetricsReport report = collectReport();
        String markdown = report.toMarkdown();
        Path out = Path.of("build", "tests", "project-metrics-report.md");
        Path csvOut = Path.of("build", "tests", "renderer-benchmark-matrix.csv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, markdown, StandardCharsets.UTF_8);
        Files.writeString(csvOut, report.benchmarkMatrix().toCsv(), StandardCharsets.UTF_8);
        System.out.println(markdown);
    }

    private static MetricsReport collectReport() throws Exception {
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        Path srcRoot = repoRoot.resolve("src");
        Path testsRoot = repoRoot.resolve("tests");

        JavaTreeStats srcStats = scanJavaTree(srcRoot);
        JavaTreeStats testStats = scanJavaTree(testsRoot);
        List<String> suiteEntries = readSuiteEntries(testsRoot.resolve("test-class-list.txt"));

        LinkedHashMap<String, Integer> projectCounts = new LinkedHashMap<>();
        projectCounts.put("Java soubory v src", srcStats.fileCount());
        projectCounts.put("Neblank Java řádky v src", srcStats.nonBlankLineCount());
        projectCounts.put("Java soubory v tests", testStats.fileCount());
        projectCounts.put("Neblank Java řádky v tests", testStats.nonBlankLineCount());
        projectCounts.put("Automatické test suite entry pointy", suiteEntries.size());
        projectCounts.put("Render módy", RenderMode.values().length);
        projectCounts.put("Node typy materiálového graphu", MaterialNodeGraph.NodeType.values().length);
        projectCounts.put("Materiálové presety", MaterialPresets.Preset.values().length);
        projectCounts.put("Preview primitiva", MaterialPreviewRenderer.PreviewPrimitive.values().length);
        projectCounts.put("Preview light presety", MaterialPreviewRenderer.LightingPreset.values().length);
        projectCounts.put("Preview background režimy", MaterialPreviewRenderer.BackgroundMode.values().length);
        projectCounts.put("Preview render režimy", MaterialPreviewRenderer.PreviewMode.values().length);
        projectCounts.put("Typy exportu", reflectEnumSize("engine.core.OutputRenderRequestType"));
        projectCounts.put("Základní primitiva", invokeStringArraySize("engine.core.EngineSceneActions", "basicPrimitiveTypes"));
        projectCounts.put("Featured primitiva", invokeStringArraySize("engine.core.EngineSceneActions", "featuredPrimitiveTypes"));

        ImportStats importStats = collectImportStats(repoRoot);
        EnumMap<TestBucket, Integer> testBuckets = classifyTestSuites(suiteEntries);
        BenchmarkEnvironment environment = BenchmarkEnvironment.capture();
        RendererBenchmarkSuite.BenchmarkMatrixReport benchmarkMatrix = RendererBenchmarkSuite.runMatrixBenchmarks();
        Scene scene = buildBenchmarkScene();
        PerspectiveCamera camera = buildBenchmarkCamera();
        SceneComplexityStats sceneComplexity = measureSceneComplexity(scene);
        List<ExportBenchmarkResult> exportBenchmarks = runExportBenchmarks(scene, camera);

        return new MetricsReport(
                LocalDateTime.now(),
                environment,
                projectCounts,
                importStats,
                testBuckets,
                benchmarkMatrix,
                sceneComplexity,
                exportBenchmarks
        );
    }

    private static JavaTreeStats scanJavaTree(Path root) throws Exception {
        int files = 0;
        int totalLines = 0;
        int nonBlankLines = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
            files = javaFiles.size();
            for (Path javaFile : javaFiles) {
                List<String> lines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
                totalLines += lines.size();
                for (String line : lines) {
                    if (!line.isBlank()) {
                        nonBlankLines++;
                    }
                }
            }
        }
        return new JavaTreeStats(files, totalLines, nonBlankLines);
    }

    private static List<String> readSuiteEntries(Path file) throws Exception {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .collect(Collectors.toList());
    }

    private static int reflectEnumSize(String fqcn) throws Exception {
        Class<?> enumType = Class.forName(fqcn);
        Object[] constants = enumType.getEnumConstants();
        return constants == null ? 0 : constants.length;
    }

    private static int invokeStringArraySize(String fqcn, String methodName) throws Exception {
        Class<?> type = Class.forName(fqcn);
        Method method = type.getDeclaredMethod(methodName);
        method.setAccessible(true);
        Object value = method.invoke(null);
        if (value instanceof String[] array) {
            return array.length;
        }
        return 0;
    }

    private static ImportStats collectImportStats(Path repoRoot) throws Exception {
        Set<String> uiFormats = new LinkedHashSet<>();
        Set<String> importerCases = new LinkedHashSet<>();

        String sceneActions = Files.readString(repoRoot.resolve("src").resolve("engine").resolve("core").resolve("EngineSceneActions.java"));
        Matcher uiMatcher = UI_IMPORT_PATTERN.matcher(sceneActions);
        while (uiMatcher.find()) {
            uiFormats.add(uiMatcher.group(1).toUpperCase(Locale.ROOT));
        }

        String modelImporter = Files.readString(repoRoot.resolve("src").resolve("engine").resolve("io").resolve("ModelImporter.java"));
        Matcher importerMatcher = IMPORT_CASE_PATTERN.matcher(modelImporter);
        while (importerMatcher.find()) {
            importerCases.add(importerMatcher.group(1).toUpperCase(Locale.ROOT));
        }

        Set<String> nativeFormats = importerCases.stream()
                .filter(format -> !"FBX".equals(format))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean fbxPlaceholder = importerCases.contains("FBX");

        return new ImportStats(uiFormats, nativeFormats, fbxPlaceholder);
    }

    private static EnumMap<TestBucket, Integer> classifyTestSuites(List<String> suiteEntries) {
        EnumMap<TestBucket, Integer> counts = new EnumMap<>(TestBucket.class);
        for (TestBucket bucket : TestBucket.values()) {
            counts.put(bucket, 0);
        }
        for (String suite : suiteEntries) {
            TestBucket bucket = bucketForSuite(suite);
            counts.put(bucket, counts.get(bucket) + 1);
        }
        return counts;
    }

    private static TestBucket bucketForSuite(String suite) {
        String lower = suite.toLowerCase(Locale.ROOT);
        if (lower.contains("obj") || lower.contains("gltf") || lower.contains("importedtexture") || lower.contains("import")) {
            return TestBucket.IMPORT_IO;
        }
        if (lower.contains("safety") || lower.contains("undo") || lower.contains("shortcut")
                || lower.contains("timeline") || lower.contains("viewportstability")
                || lower.contains("camera")) {
            return TestBucket.EDITOR_CORE;
        }
        if (lower.contains("material")) {
            return TestBucket.MATERIALS;
        }
        if (lower.contains("dither") || lower.contains("temporal") || lower.contains("ray")
                || lower.contains("raster") || lower.contains("shadow") || lower.contains("smoothshading")
                || lower.contains("hex") || lower.contains("wireframe") || lower.contains("ascii")) {
            return TestBucket.RENDERING;
        }
        if (lower.contains("simulation") || lower.contains("presentation")) {
            return TestBucket.QUALITY_PRESENTATION;
        }
        return TestBucket.OTHER;
    }

    private static List<BenchmarkResult> runBenchmarks() {
        Scene scene = buildBenchmarkScene();
        PerspectiveCamera camera = buildBenchmarkCamera();
        List<BenchmarkResult> results = new ArrayList<>();
        results.add(benchmarkRenderer("Raster / PHONG", 256, 256, 3, 2, 5, 0.35, () -> {
            RasterRenderer renderer = new RasterRenderer();
            renderer.setParameter("parallel", false);
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("unlitMode", false);
            renderer.setParameter("backfaceCulling", false);
            return renderer;
        }, scene, camera));
        results.add(benchmarkRenderer("Dithering", 256, 256, 3, 2, 5, 0.35, () -> {
            DitherRenderer renderer = new DitherRenderer();
            renderer.setParameter("parallel", false);
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("style", DitherRenderer.DitherStyle.BLUE_NOISE);
            return renderer;
        }, scene, camera));
        results.add(benchmarkRenderer("Temporal Noise", 256, 256, 3, 2, 5, 0.35, () -> {
            TemporalNoiseRenderer renderer = new TemporalNoiseRenderer();
            renderer.setParameter("parallel", false);
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("debugView", TemporalNoiseRenderer.DebugView.FINAL);
            return renderer;
        }, scene, camera));
        results.add(benchmarkRenderer("Hex Mosaic", 256, 256, 3, 2, 5, 0.35, () -> {
            HexMosaicRenderer renderer = new HexMosaicRenderer();
            renderer.setParameter("parallel", false);
            renderer.setParameter("workerCount", 1);
            return renderer;
        }, scene, camera));
        results.add(benchmarkRenderer("Ray Tracing", 96, 96, 3, 1, 5, 0.35, () -> {
            RayTracerRenderer renderer = new RayTracerRenderer();
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("samplesPerFrame", 1);
            renderer.setParameter("maxDepth", 3);
            renderer.setParameter("sky", false);
            renderer.setParameter("denoise", false);
            return renderer;
        }, scene, camera));
        results.add(benchmarkRenderer("Path Tracing", 96, 96, 3, 1, 5, 0.35, () -> {
            PathTracerRenderer renderer = new PathTracerRenderer();
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("samplesPerFrame", 1);
            renderer.setParameter("maxDepth", 3);
            renderer.setParameter("sky", false);
            renderer.setParameter("denoise", false);
            return renderer;
        }, scene, camera));
        return results;
    }

    private static List<ResolutionScalingResult> runResolutionScalingBenchmarks(Scene scene, PerspectiveCamera camera) {
        List<ResolutionScalingResult> rows = new ArrayList<>();
        rows.add(benchmarkScalingRow("Raster / PHONG", scene, camera, () -> {
            RasterRenderer renderer = new RasterRenderer();
            renderer.setParameter("parallel", false);
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("unlitMode", false);
            renderer.setParameter("backfaceCulling", false);
            return renderer;
        }));
        rows.add(benchmarkScalingRow("Temporal Noise", scene, camera, () -> {
            TemporalNoiseRenderer renderer = new TemporalNoiseRenderer();
            renderer.setParameter("parallel", false);
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("debugView", TemporalNoiseRenderer.DebugView.FINAL);
            return renderer;
        }));
        rows.add(benchmarkScalingRow("Ray Tracing", scene, camera, () -> {
            RayTracerRenderer renderer = new RayTracerRenderer();
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("samplesPerFrame", 1);
            renderer.setParameter("maxDepth", 3);
            renderer.setParameter("sky", false);
            renderer.setParameter("denoise", false);
            return renderer;
        }));
        rows.add(benchmarkScalingRow("Path Tracing", scene, camera, () -> {
            PathTracerRenderer renderer = new PathTracerRenderer();
            renderer.setParameter("workerCount", 1);
            renderer.setParameter("samplesPerFrame", 1);
            renderer.setParameter("maxDepth", 3);
            renderer.setParameter("sky", false);
            renderer.setParameter("denoise", false);
            return renderer;
        }));
        return rows;
    }

    private static ResolutionScalingResult benchmarkScalingRow(String label,
                                                               Scene scene,
                                                               PerspectiveCamera camera,
                                                               RendererFactory factory) {
        List<ResolutionBenchmarkSample> samples = new ArrayList<>();
        for (ResolutionCase resolution : RESOLUTION_CASES) {
            samples.add(benchmarkResolutionCase(label, resolution, scene, camera, factory));
        }
        return new ResolutionScalingResult(label, samples);
    }

    private static ResolutionBenchmarkSample benchmarkResolutionCase(String label,
                                                                     ResolutionCase resolution,
                                                                     Scene scene,
                                                                     PerspectiveCamera camera,
                                                                     RendererFactory factory) {
        int warmups = 1;
        int passes = 2;
        int runs = 3;
        double[] medians = new double[passes];
        double[] means = new double[passes];
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (int pass = 0; pass < passes; pass++) {
            Renderer renderer = factory.create();
            renderer.init(resolution.width(), resolution.height());
            FrameBuffer fb = new FrameBuffer(resolution.width(), resolution.height());
            double time = 0.25 + pass * 0.37;

            for (int i = 0; i < warmups; i++) {
                renderer.render(scene, camera, fb, time);
                time += 0.17;
            }

            double[] passSamples = new double[runs];
            for (int i = 0; i < runs; i++) {
                long started = System.nanoTime();
                renderer.render(scene, camera, fb, time);
                passSamples[i] = (System.nanoTime() - started) / 1_000_000.0;
                time += 0.17;
            }
            double[] sorted = passSamples.clone();
            Arrays.sort(sorted);
            min = Math.min(min, sorted[0]);
            max = Math.max(max, sorted[sorted.length - 1]);
            medians[pass] = medianOfSorted(sorted);
            means[pass] = Arrays.stream(passSamples).average().orElse(0.0);
        }

        double[] sortedMedians = medians.clone();
        Arrays.sort(sortedMedians);
        double median = medianOfSorted(sortedMedians);
        double mean = Arrays.stream(means).average().orElse(0.0);
        return new ResolutionBenchmarkSample(label, resolution, passes, runs, min, median, mean, max);
    }

    private static List<ExportBenchmarkResult> runExportBenchmarks(Scene scene, PerspectiveCamera camera) throws Exception {
        List<ExportBenchmarkResult> rows = new ArrayList<>();
        rows.add(benchmarkExportFormat("PNG still", scene, camera, (tempRoot, frames) -> benchmarkStillPng(tempRoot, frames.get(0))));
        rows.add(benchmarkExportFormat("JPG still", scene, camera, (tempRoot, frames) -> benchmarkStillJpg(tempRoot, frames.get(0), 0.92f)));
        rows.add(benchmarkExportFormat("PNG sequence", scene, camera, ProjectMetricsReport::benchmarkPngSequence));
        rows.add(benchmarkExportFormat("GIF", scene, camera, (tempRoot, frames) -> benchmarkGif(tempRoot, frames, 24)));
        rows.add(benchmarkExportFormat("AVI MJPEG", scene, camera, (tempRoot, frames) -> benchmarkAvi(tempRoot, frames, 24.0, 0.90)));
        return rows;
    }

    private static ExportBenchmarkResult benchmarkExportFormat(String label,
                                                               Scene scene,
                                                               PerspectiveCamera camera,
                                                               ExportWriter writer) throws Exception {
        List<ExportResolutionSample> samples = new ArrayList<>();
        for (ResolutionCase resolution : RESOLUTION_CASES) {
            List<BufferedImage> frames = renderBenchmarkFrames(scene, camera, resolution, 8);
            samples.add(measureExportResolution(label, resolution, frames, writer));
        }
        return new ExportBenchmarkResult(label, samples);
    }

    private static ExportResolutionSample measureExportResolution(String label,
                                                                  ResolutionCase resolution,
                                                                  List<BufferedImage> frames,
                                                                  ExportWriter writer) throws Exception {
        int passes = 2;
        int runs = 2;
        double[] medians = new double[passes];
        double[] means = new double[passes];
        long[] sizes = new long[passes];
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (int pass = 0; pass < passes; pass++) {
            double[] passSamples = new double[runs];
            long passSize = 0L;
            for (int run = 0; run < runs; run++) {
                Path tempDir = Files.createTempDirectory(Path.of("build", "tests"), "export-bench-");
                try {
                    long started = System.nanoTime();
                    passSize = writer.write(tempDir, frames);
                    passSamples[run] = (System.nanoTime() - started) / 1_000_000.0;
                } finally {
                    deleteRecursively(tempDir);
                }
            }
            double[] sorted = passSamples.clone();
            Arrays.sort(sorted);
            min = Math.min(min, sorted[0]);
            max = Math.max(max, sorted[sorted.length - 1]);
            medians[pass] = medianOfSorted(sorted);
            means[pass] = Arrays.stream(passSamples).average().orElse(0.0);
            sizes[pass] = passSize;
        }

        double[] sortedMedians = medians.clone();
        Arrays.sort(sortedMedians);
        double median = medianOfSorted(sortedMedians);
        double mean = Arrays.stream(means).average().orElse(0.0);
        long medianSize = medianOfSorted(sizes);
        return new ExportResolutionSample(label, resolution, passes, runs, min, median, mean, max, medianSize);
    }

    private static BenchmarkResult benchmarkRenderer(String label,
                                                     int width,
                                                     int height,
                                                     int passes,
                                                     int warmups,
                                                     int runs,
                                                     double startTime,
                                                     RendererFactory factory,
                                                     Scene scene,
                                                     PerspectiveCamera camera) {
        double[] medians = new double[passes];
        double[] means = new double[passes];
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (int pass = 0; pass < passes; pass++) {
            Renderer renderer = factory.create();
            renderer.init(width, height);
            FrameBuffer fb = new FrameBuffer(width, height);
            double time = startTime + pass * 0.41;

            for (int i = 0; i < warmups; i++) {
                renderer.render(scene, camera, fb, time);
                time += 0.19;
            }

            double[] samples = new double[runs];
            for (int i = 0; i < runs; i++) {
                long started = System.nanoTime();
                renderer.render(scene, camera, fb, time);
                long elapsed = System.nanoTime() - started;
                samples[i] = elapsed / 1_000_000.0;
                time += 0.19;
            }

            double[] sorted = samples.clone();
            Arrays.sort(sorted);
            min = Math.min(min, sorted[0]);
            max = Math.max(max, sorted[sorted.length - 1]);
            medians[pass] = medianOfSorted(sorted);
            means[pass] = Arrays.stream(samples).average().orElse(0.0);
        }

        double[] sortedMedians = medians.clone();
        Arrays.sort(sortedMedians);
        double median = medianOfSorted(sortedMedians);
        double mean = Arrays.stream(means).average().orElse(0.0);
        return new BenchmarkResult(label, width, height, passes, runs, min, median, mean, max);
    }

    private static Scene buildBenchmarkScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.08, 0.08, 0.08));
        scene.setBackgroundColor(new Vec3(0.04, 0.05, 0.07));

        PhongMaterial floorMaterial = new PhongMaterial(new Vec3(0.46, 0.48, 0.52), 18.0);
        Entity floor = new Entity("floor", MeshGenerator.plane(6.0, 6.0, 1, 1), floorMaterial);
        floor.getTransform().setPosition(new Vec3(0.0, -1.0, 0.0));
        scene.addEntity(floor);

        PhongMaterial cubeMaterial = new PhongMaterial(new Vec3(0.86, 0.42, 0.19), 32.0);
        Entity cube = new Entity("cube", MeshGenerator.cube(1.15), cubeMaterial);
        cube.getTransform().setPosition(new Vec3(-0.95, -0.2, 0.15));
        cube.getTransform().setEulerAngles(Math.toRadians(12.0), Math.toRadians(30.0), Math.toRadians(-4.0));
        scene.addEntity(cube);

        PhongMaterial sphereMaterial = new PhongMaterial(new Vec3(0.28, 0.66, 0.92), 54.0);
        Entity sphere = new Entity("sphere", MeshGenerator.sphere(0.72, 22, 18), sphereMaterial);
        sphere.getTransform().setPosition(new Vec3(0.9, -0.18, 0.24));
        scene.addEntity(sphere);

        PhongMaterial cylinderMaterial = new PhongMaterial(new Vec3(0.78, 0.79, 0.82), 24.0);
        Entity cylinder = new Entity("cylinder", MeshGenerator.cylinder(0.42, 1.6, 20, 1), cylinderMaterial);
        cylinder.getTransform().setPosition(new Vec3(0.05, -0.15, -0.72));
        cylinder.getTransform().setEulerAngles(Math.toRadians(0.0), Math.toRadians(-18.0), Math.toRadians(0.0));
        scene.addEntity(cylinder);

        scene.addLight(new DirectionalLight(new Vec3(-0.65, -1.0, -0.42), new Vec3(1.0, 0.96, 0.90), 1.35));
        scene.addLight(new DirectionalLight(new Vec3(0.55, -0.35, 0.22), new Vec3(0.42, 0.48, 0.66), 0.45));
        scene.update(0.0);
        return scene;
    }

    private static SceneComplexityStats measureSceneComplexity(Scene scene) {
        int meshEntities = 0;
        int totalTriangles = 0;
        for (Entity entity : scene.getEntities()) {
            if (entity.getMesh() != null) {
                meshEntities++;
                totalTriangles += entity.getMesh().getTriangleCount();
            }
        }
        return new SceneComplexityStats(meshEntities, scene.getLights().size(), totalTriangles);
    }

    private static PerspectiveCamera buildBenchmarkCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 0.5, 4.4));
        camera.lookAt(new Vec3(0.0, -0.15, 0.0));
        return camera;
    }

    private static List<BufferedImage> renderBenchmarkFrames(Scene scene,
                                                             PerspectiveCamera baseCamera,
                                                             ResolutionCase resolution,
                                                             int frameCount) {
        RasterRenderer renderer = new RasterRenderer();
        renderer.setParameter("parallel", false);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("unlitMode", false);
        renderer.setParameter("backfaceCulling", false);
        renderer.init(resolution.width(), resolution.height());

        List<BufferedImage> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            double angle = Math.toRadians(-8.0 + frame * 2.0);
            PerspectiveCamera camera = new PerspectiveCamera(baseCamera.getFovY(), 1.0, 0.1, 80.0);
            camera.setPosition(new Vec3(Math.sin(angle) * 0.35, 0.5, 4.4 - Math.cos(angle) * 0.15));
            camera.lookAt(new Vec3(0.0, -0.15, 0.0));

            FrameBuffer fb = new FrameBuffer(resolution.width(), resolution.height());
            renderer.render(scene, camera, fb, frame * 0.1);
            frames.add(framebufferToImage(fb));
        }
        return frames;
    }

    private static BufferedImage framebufferToImage(FrameBuffer fb) {
        int width = fb.getWidth();
        int height = fb.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, fb.getColorBuffer(), 0, width);
        return image;
    }

    private static long benchmarkStillPng(Path tempRoot, BufferedImage frame) throws Exception {
        Path temp = tempRoot.resolve("still.png");
        ImageIO.write(frame, "png", temp.toFile());
        return Files.size(temp);
    }

    private static long benchmarkStillJpg(Path tempRoot, BufferedImage frame, float quality) throws Exception {
        Path temp = tempRoot.resolve("still.jpg");
        writeJpeg(frame, temp, quality);
        return Files.size(temp);
    }

    private static long benchmarkPngSequence(Path tempRoot, List<BufferedImage> frames) throws Exception {
        long size = 0L;
        for (int i = 0; i < frames.size(); i++) {
            Path framePath = tempRoot.resolve(String.format(Locale.ROOT, "frame_%04d.png", i));
            ImageIO.write(frames.get(i), "png", framePath.toFile());
            size += Files.size(framePath);
        }
        return size;
    }

    private static long benchmarkGif(Path tempRoot, List<BufferedImage> frames, int fps) throws Exception {
        Path temp = tempRoot.resolve("animation.gif");
        try (AnimatedGifWriter writer = new AnimatedGifWriter(temp, Math.max(10, (int) Math.round(1000.0 / fps)), true)) {
            for (BufferedImage frame : frames) {
                writer.writeFrame(frame);
            }
        }
        return Files.size(temp);
    }

    private static long benchmarkAvi(Path tempRoot, List<BufferedImage> frames, double fps, double jpegQuality) throws Exception {
        Path temp = tempRoot.resolve("animation.avi");
        try (MjpegAviWriter writer = new MjpegAviWriter(temp, frames.get(0).getWidth(), frames.get(0).getHeight(), fps, jpegQuality)) {
            for (BufferedImage frame : frames) {
                writer.writeFrame(frame);
            }
        }
        return Files.size(temp);
    }

    private static double medianOfSorted(double[] sorted) {
        if (sorted == null || sorted.length == 0) {
            return 0.0;
        }
        int length = sorted.length;
        if ((length & 1) == 1) {
            return sorted[length / 2];
        }
        return (sorted[length / 2 - 1] + sorted[length / 2]) * 0.5;
    }

    private static long medianOfSorted(long[] sorted) {
        if (sorted == null || sorted.length == 0) {
            return 0L;
        }
        long[] copy = sorted.clone();
        Arrays.sort(copy);
        int length = copy.length;
        if ((length & 1) == 1) {
            return copy[length / 2];
        }
        long a = copy[length / 2 - 1];
        long b = copy[length / 2];
        return Math.round((a + b) * 0.5);
    }

    private static void writeJpeg(BufferedImage image, Path out, float quality) throws Exception {
        ImageWriter writer = null;
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (writers.hasNext()) {
            writer = writers.next();
        }
        if (writer == null) {
            throw new IOException("Nenašel jsem JPEG ImageIO writer.");
        }
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0.05f, Math.min(1.0f, quality)));
        }
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out.toFile())) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(toRgb(image), null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = rgb.createGraphics();
        try {
            g2.drawImage(image, 0, 0, null);
        } finally {
            g2.dispose();
        }
        return rgb;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).collect(Collectors.toList());
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }

    private enum TestBucket {
        RENDERING("Rendering"),
        MATERIALS("Materiály"),
        IMPORT_IO("Import / IO"),
        EDITOR_CORE("Editor / core"),
        QUALITY_PRESENTATION("Kvalita / prezentace"),
        OTHER("Ostatní");

        private final String label;

        TestBucket(String label) {
            this.label = label;
        }
    }

    private record JavaTreeStats(int fileCount, int totalLineCount, int nonBlankLineCount) {
    }

    private record ImportStats(Set<String> uiFormats, Set<String> nativeFormats, boolean fbxPlaceholder) {
    }

    private record SceneComplexityStats(int meshEntities, int lights, int totalTriangles) {
    }

    private record BenchmarkEnvironment(String javaVersion, String vmName, String osName, String osArch, int processors) {
        static BenchmarkEnvironment capture() {
            return new BenchmarkEnvironment(
                    System.getProperty("java.version", "unknown"),
                    System.getProperty("java.vm.name", "unknown"),
                    System.getProperty("os.name", "unknown"),
                    System.getProperty("os.arch", "unknown"),
                    Runtime.getRuntime().availableProcessors()
            );
        }
    }

    private record BenchmarkResult(String label,
                                   int width,
                                   int height,
                                   int passes,
                                   int runsPerPass,
                                   double minMs,
                                   double medianMs,
                                   double meanMs,
                                   double maxMs) {
    }

    @FunctionalInterface
    private interface RendererFactory {
        Renderer create();
    }

    private record ResolutionCase(String label, int width, int height) {
    }

    private record ResolutionBenchmarkSample(String label,
                                             ResolutionCase resolution,
                                             int passes,
                                             int runsPerPass,
                                             double minMs,
                                             double medianMs,
                                             double meanMs,
                                             double maxMs) {
    }

    private record ResolutionScalingResult(String label, List<ResolutionBenchmarkSample> samples) {
    }

    private record ExportResolutionSample(String label,
                                          ResolutionCase resolution,
                                          int passes,
                                          int runsPerPass,
                                          double minMs,
                                          double medianMs,
                                          double meanMs,
                                          double maxMs,
                                          long medianSizeBytes) {
    }

    private record ExportBenchmarkResult(String label, List<ExportResolutionSample> samples) {
    }

    @FunctionalInterface
    private interface ExportWriter {
        long write(Path tempRoot, List<BufferedImage> frames) throws Exception;
    }

    private record MetricsReport(LocalDateTime generatedAt,
                                 BenchmarkEnvironment environment,
                                 LinkedHashMap<String, Integer> projectCounts,
                                 ImportStats importStats,
                                 EnumMap<TestBucket, Integer> testBuckets,
                                 RendererBenchmarkSuite.BenchmarkMatrixReport benchmarkMatrix,
                                 SceneComplexityStats sceneComplexity,
                                 List<ExportBenchmarkResult> exportBenchmarks) {

        String toMarkdown() {
            StringBuilder out = new StringBuilder();
            out.append("# Projektové metriky a benchmarky\n\n");
            out.append("- Vygenerováno: `").append(STAMP_FORMAT.format(generatedAt)).append("`\n");
            out.append("- Java: `").append(environment.javaVersion()).append("` (`").append(environment.vmName()).append("`)\n");
            out.append("- OS: `").append(environment.osName()).append("` / `").append(environment.osArch()).append("`\n");
            out.append("- Logické procesory: `").append(environment.processors()).append("`\n\n");

            out.append("## Statistika codebase\n\n");
            out.append("| Metrika | Hodnota |\n");
            out.append("| --- | ---: |\n");
            for (Map.Entry<String, Integer> entry : projectCounts.entrySet()) {
                out.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
            }
            out.append('\n');

            out.append("## Import a export\n\n");
            out.append("| Oblast | Hodnota |\n");
            out.append("| --- | --- |\n");
            out.append("| Import filtr v UI | ").append(String.join(", ", importStats.uiFormats())).append(" |\n");
            out.append("| Nativně obsloužené import cesty | ").append(String.join(", ", importStats.nativeFormats())).append(" |\n");
            out.append("| FBX větev | ").append(importStats.fbxPlaceholder() ? "filtr existuje, importer ji poctivě hlásí jako unsupported" : "není přítomná").append(" |\n");
            out.append("| Výstupní typy | STILL, IMAGE_SEQUENCE, ANIMATED_GIF, ANIMATED_AVI |\n\n");

            out.append("## Rozdělení automatických testů\n\n");
            out.append("| Kategorie | Počet suite entry pointů |\n");
            out.append("| --- | ---: |\n");
            for (TestBucket bucket : TestBucket.values()) {
                out.append("| ").append(bucket.label).append(" | ").append(testBuckets.get(bucket)).append(" |\n");
            }
            out.append('\n');

            out.append("## Renderer benchmark matrix\n\n");
            out.append("| Parametr | Hodnota |\n");
            out.append("| --- | --- |\n");
            out.append("| Benchmark mode | ").append(benchmarkMatrix.mode.name().toLowerCase(Locale.ROOT)).append(" |\n");
            out.append("| Izolace případů | ").append(benchmarkMatrix.isolated ? "samostatny child JVM proces pro kazdy case" : "spolecny JVM proces").append(" |\n");
            out.append("| Core profily | ");
            for (int i = 0; i < benchmarkMatrix.coreProfiles.size(); i++) {
                RendererBenchmarkSuite.CoreProfile profile = benchmarkMatrix.coreProfiles.get(i);
                if (i > 0) {
                    out.append(", ");
                }
                out.append(profile.label()).append(" = ").append(profile.workerCount()).append(" worker");
            }
            out.append(" |\n");
            out.append("| Viewport rozliseni | ").append(benchmarkMatrix.viewportResolutionLabels()).append(" |\n");
            out.append("| Offline rozliseni | ").append(benchmarkMatrix.offlineResolutionLabels()).append(" |\n");
            out.append("| Workload faze | first-frame = init + prvni render na cerstve instanci po case primingu, steady-frame = render po warm-upu |\n");
            out.append("| Statistika | min, median, mean, p90, max, stddev z realnych sample, zadny median-z-mediannu |\n");
            out.append("| CSV dataset | `build/tests/renderer-benchmark-matrix.csv` |\n");
            out.append("| Kamera | perspective, FOV 60 deg, aspect podle rozliseni, pozice `(0.0, 1.3, 7.4 +/- bias)` |\n");
            out.append("| Poznamka | viewport a offline renderery maji oddelene resolution matice, aby benchmark zustal pouzitelny i pro CPU ray/path |\n");
            out.append("| Interpretace Temporal Noise | steady-frame ve staticke scene typicky reuseuje analyzu; pro dynamicke sekvence sleduj first-frame i stress rows |\n");
            out.append('\n');

            out.append("## Benchmark scenare\n\n");
            out.append("| Scena | Mesh entity | Svetla | Trojuhelniky |\n");
            out.append("| --- | ---: | ---: | ---: |\n");
            for (RendererBenchmarkSuite.SceneProfileSummary sceneSummary : benchmarkMatrix.scenes) {
                out.append("| ").append(sceneSummary.label())
                        .append(" | ").append(sceneSummary.meshEntities())
                        .append(" | ").append(sceneSummary.lights())
                        .append(" | ").append(sceneSummary.totalTriangles())
                        .append(" |\n");
            }
            out.append('\n');

            out.append("## Agregovane renderer benchmarky\n\n");
            out.append("| Renderer | Core profil | Pocet case | First-frame geo median [ms] | Steady-frame geo median [ms] | Worst steady median [ms] |\n");
            out.append("| --- | --- | ---: | ---: | ---: | ---: |\n");
            for (RendererBenchmarkSuite.RendererAggregate aggregate : benchmarkMatrix.aggregates) {
                out.append("| ").append(aggregate.rendererLabel)
                        .append(" | ").append(aggregate.coreProfileLabel)
                        .append(" | ").append(aggregate.caseCount)
                        .append(" | ").append(formatMs(aggregate.firstGeoMeanMedianMs))
                        .append(" | ").append(formatMs(aggregate.steadyGeoMeanMedianMs))
                        .append(" | ").append(formatMs(aggregate.worstSteadyMedianMs))
                        .append(" |\n");
            }
            out.append('\n');

            out.append("## Stress case vysledky\n\n");
            out.append("| Renderer | Core profil | Scena | Rozliseni | First median [ms] | Steady median [ms] | Steady p90 [ms] |\n");
            out.append("| --- | --- | --- | ---: | ---: | ---: | ---: |\n");
            for (RendererBenchmarkSuite.BenchmarkCaseResult row : benchmarkMatrix.stressCaseRows()) {
                out.append("| ").append(row.rendererLabel)
                        .append(" | ").append(row.coreProfileLabel)
                        .append(" | ").append(row.sceneLabel)
                        .append(" | ").append(row.resolutionLabel)
                        .append(" | ").append(formatMs(row.firstFrame.medianMs))
                        .append(" | ").append(formatMs(row.steadyFrame.medianMs))
                        .append(" | ").append(formatMs(row.steadyFrame.p90Ms))
                        .append(" |\n");
            }
            out.append('\n');

            out.append("## Export benchmark parametry\n\n");
            out.append("| Parametr | Hodnota |\n");
            out.append("| --- | --- |\n");
            out.append("| Export benchmark scena | ").append(sceneComplexity.meshEntities()).append(" mesh entity, ")
                    .append(sceneComplexity.lights()).append(" svetla, ")
                    .append(sceneComplexity.totalTriangles()).append(" trojuhelniku |\n");
            out.append("| Export benchmark zdroj | 8 předpřipravených PHONG frameů na rozlišení |\n");
            out.append("| Export benchmark formáty | PNG still, JPG still, PNG sequence, GIF, AVI MJPEG |\n");
            out.append("| JPG kvalita | 0.92 |\n");
            out.append("| GIF | 8 snímků, 24 FPS, loop forever |\n");
            out.append("| AVI | 8 snímků, 24 FPS, MJPEG quality 0.90 |\n\n");

            out.append("## Export benchmark podle formátu a rozlišení\n\n");
            out.append("| Formát | 640x360 median [ms] | 640x360 velikost | 1280x720 median [ms] | 1280x720 velikost | 1920x1080 median [ms] | 1920x1080 velikost |\n");
            out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (ExportBenchmarkResult export : exportBenchmarks) {
                ExportResolutionSample low = export.samples().get(0);
                ExportResolutionSample mid = export.samples().get(1);
                ExportResolutionSample high = export.samples().get(2);
                out.append("| ").append(export.label())
                        .append(" | ").append(formatMs(low.medianMs()))
                        .append(" | ").append(formatSize(low.medianSizeBytes()))
                        .append(" | ").append(formatMs(mid.medianMs()))
                        .append(" | ").append(formatSize(mid.medianSizeBytes()))
                        .append(" | ").append(formatMs(high.medianMs()))
                        .append(" | ").append(formatSize(high.medianSizeBytes()))
                        .append(" |\n");
            }
            out.append('\n');
            return out.toString();
        }

        private static String formatMs(double value) {
            return String.format(Locale.US, "%.2f", value);
        }

        private static String formatSize(long bytes) {
            return String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0);
        }
    }
}
