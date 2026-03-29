package engine.render.raster;

import engine.camera.Camera;
import engine.camera.Frustum;
import engine.geometry.Mesh;
import engine.material.MaterialGraphEvaluator;
import engine.material.Material;
import engine.material.PhongMaterial;
import engine.math.Mat3;
import engine.material.TextureMap;
import engine.math.Mat4;
import engine.math.Vec3;
import engine.math.Vec4;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.Texture;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.Scene;
import engine.util.RuntimeInstrumentation;
import engine.util.ThreadPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents software rasterizer s volitelným unlit a Phong shadingem.
 */
public class RasterRenderer implements Renderer {

    private final Object workerStateLock = new Object();
    private TriangleRasterizer rasterizer;
    private final PhongShader phongShader;
    private final Frustum frustum;
    private ThreadPool threadPool;
    private TriangleRasterizer[] workerRasterizers;
    private int workerCount;
    private boolean parallelEnabled;
    private int bufferWidth;
    private int bufferHeight;
    private int tileSize;
    private boolean flatShading;
    private boolean unlitMode;
    private boolean modelPreviewMode;
    private boolean frustumCulling;
    private boolean backfaceCulling;
    private boolean previewFastMaterialMode;
    private boolean previewDisableNormalMap;
    private boolean previewDisableMetallicRoughnessMap;
    private boolean previewDisableEmissiveMap;
    private boolean previewDisableTransmissionPreview;
    private boolean previewDisableSheen;
    private boolean previewDisableClearcoat;
    private boolean previewPointLightsDiffuseOnly;
    private boolean previewDiffuseOnlyLighting;
    private boolean previewBaseIdentityMode;
    private MaterialProfile materialProfile;
    private static final double CLIP_EPS = 1e-6;

    private enum MaterialProfile {
        PHONG,
        DITHER
    }

    private static class PreparedEntity {
        final int objectId;
        final Mesh mesh;
        final PhongMaterial material;
        final float[] clipPositions;
        final float[] screenPositions;
        final float[] worldPositions;
        final float[] worldNormals;
        final float[] worldTangents;
        FragmentShader shader;

        PreparedEntity(int objectId,
                       Mesh mesh,
                       PhongMaterial material,
                       float[] clipPositions,
                       float[] screenPositions,
                       float[] worldPositions,
                       float[] worldNormals,
                       float[] worldTangents) {
            this.objectId = objectId;
            this.mesh = mesh;
            this.material = material;
            this.clipPositions = clipPositions;
            this.screenPositions = screenPositions;
            this.worldPositions = worldPositions;
            this.worldNormals = worldNormals;
            this.worldTangents = worldTangents;
        }
    }

    private static class ClipVertex {
        final Vec4 clip = new Vec4();
        final Vec3 worldPos = new Vec3();
        final Vec3 worldNormal = new Vec3();
        final Vec3 worldTangent = new Vec3();
        double u0;
        double v0;
        double u1;
        double v1;

        ClipVertex set(ClipVertex other) {
            clip.set(other.clip);
            worldPos.set(other.worldPos);
            worldNormal.set(other.worldNormal);
            worldTangent.set(other.worldTangent);
            u0 = other.u0;
            v0 = other.v0;
            u1 = other.u1;
            v1 = other.v1;
            return this;
        }
    }

    private static final class NormalMapScratch {
        final Vec3 normal = new Vec3();
        final Vec3 tangent = new Vec3();
        final Vec3 bitangent = new Vec3();
        final Vec3 fallbackTangent = new Vec3();
        final Vec3 axis = new Vec3();
        final Vec3 result = new Vec3();
    }

    private static final class TileRenderState {
        final float[] sx = new float[3];
        final float[] sy = new float[3];
        final float[] sz = new float[3];
        final float[] sw = new float[3];
        final float[][] attrs = new float[15][3];
        final Vec3 ndcScratch = new Vec3();
        final ClipVertex v0 = new ClipVertex();
        final ClipVertex v1 = new ClipVertex();
        final ClipVertex v2 = new ClipVertex();
    }

    private static final class LongList {
        private long[] values = new long[16];
        private int size;

        void add(long value) {
            if (size >= values.length) {
                long[] next = new long[values.length << 1];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        long get(int index) {
            return values[index];
        }

        int size() {
            return size;
        }
    }

    private static final class TileRange {
        int minTileX;
        int minTileY;
        int maxTileX;
        int maxTileY;

        void reset() {
            minTileX = Integer.MAX_VALUE;
            minTileY = Integer.MAX_VALUE;
            maxTileX = Integer.MIN_VALUE;
            maxTileY = Integer.MIN_VALUE;
        }

        void include(int tileMinX, int tileMinY, int tileMaxX, int tileMaxY) {
            minTileX = Math.min(minTileX, tileMinX);
            minTileY = Math.min(minTileY, tileMinY);
            maxTileX = Math.max(maxTileX, tileMaxX);
            maxTileY = Math.max(maxTileY, tileMaxY);
        }

        boolean isValid() {
            return minTileX <= maxTileX && minTileY <= maxTileY;
        }
    }

    private static final class TileBins {
        final LongList[] bins;
        final int tileCols;
        final int tileRows;
        final int tileWidth;
        final int tileHeight;

        TileBins(LongList[] bins, int tileCols, int tileRows, int tileWidth, int tileHeight) {
            this.bins = bins;
            this.tileCols = tileCols;
            this.tileRows = tileRows;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
        }
    }

    private static final ThreadLocal<NormalMapScratch> NORMAL_MAP_SCRATCH =
            ThreadLocal.withInitial(NormalMapScratch::new);

    @FunctionalInterface
    private interface ClipDistance {
        double eval(ClipVertex v);
    }

    public RasterRenderer() {
        this.rasterizer = new TriangleRasterizer();
        this.phongShader = new PhongShader();
        this.frustum = new Frustum();
        this.threadPool = null;
        this.workerRasterizers = new TriangleRasterizer[0];
        this.workerCount = ThreadPool.recommendedWorkerCount();
        this.parallelEnabled = this.workerCount > 1;
        this.bufferWidth = 1;
        this.bufferHeight = 1;
        this.tileSize = 64;
        this.flatShading = false;
        this.unlitMode = true;
        this.modelPreviewMode = false;
        this.frustumCulling = true;
        this.backfaceCulling = true;
        this.previewFastMaterialMode = false;
        this.previewDisableNormalMap = false;
        this.previewDisableMetallicRoughnessMap = false;
        this.previewDisableEmissiveMap = false;
        this.previewDisableTransmissionPreview = false;
        this.previewDisableSheen = false;
        this.previewDisableClearcoat = false;
        this.previewPointLightsDiffuseOnly = false;
        this.previewDiffuseOnlyLighting = false;
        this.previewBaseIdentityMode = false;
        this.materialProfile = MaterialProfile.PHONG;
    }

    @Override
    public synchronized void init(int width, int height) {
        rasterizer = new TriangleRasterizer();
        synchronized (workerStateLock) {
            bufferWidth = Math.max(1, width);
            bufferHeight = Math.max(1, height);
            rebuildWorkers();
        }
    }

    @Override
    public synchronized void render(Scene scene, Camera camera, FrameBuffer fb, double time) {
        int fbWidth = fb.getWidth();
        int fbHeight = fb.getHeight();
        synchronized (workerStateLock) {
            bufferWidth = fbWidth;
            bufferHeight = fbHeight;
            boolean needsWorkers = parallelEnabled && workerCount > 1;
            boolean hasWorkers = threadPool != null && workerRasterizers.length == workerCount;
            if (needsWorkers != hasWorkers) {
                rebuildWorkers();
            }
        }

        long baseSetupStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        int clearColor = 0xFF000000 | scene.getBackgroundColor().toIntRGB();
        Mat4 view = camera.getViewMatrix();
        Mat4 projection = camera.getProjectionMatrix();
        Mat4 vp = projection.multiply(view);
        frustum.extractFromMatrix(vp);

        Light[] lights = scene.getLights().toArray(new Light[0]);
        phongShader.setup(scene.getAmbientColor(), 1.0, lights);
        phongShader.setPreviewProfile(previewDiffuseOnlyLighting, previewPointLightsDiffuseOnly);
        Vec3 cameraPosition = camera.getPosition();
        fb.clear(clearColor, 1.0f);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_SETUP_NS,
                    System.nanoTime() - baseSetupStart);
        }
        long prepareStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        List<PreparedEntity> prepared = prepareEntities(scene, vp, fbWidth, fbHeight, cameraPosition);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_PREPARE_NS,
                    System.nanoTime() - prepareStart);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_BASE_PREPARE_TRANSFORM_NS,
                    System.nanoTime() - prepareStart);
        }
        if (prepared.isEmpty()) {
            return;
        }

        long tileBinStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        TileBins tileBins = buildTileBins(prepared, fbWidth, fbHeight);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_BINNING_NS,
                    System.nanoTime() - tileBinStart);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_BASE_TILE_BINNING_NS,
                    System.nanoTime() - tileBinStart);
        }
        if (tileBins.bins.length == 0) {
            return;
        }

        long rasterStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        if (!parallelEnabled || workerCount <= 1 || threadPool == null || workerRasterizers.length == 0 || tileBins.bins.length <= 1) {
            renderTilesSequential(prepared, tileBins, fb, rasterizer);
            if (RuntimeInstrumentation.isEnabled()) {
                long elapsed = System.nanoTime() - rasterStart;
                RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_RASTER_NS, elapsed);
            }
            return;
        }

        renderParallel(prepared, tileBins, fb);
        if (RuntimeInstrumentation.isEnabled()) {
            long elapsed = System.nanoTime() - rasterStart;
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_RASTER_NS, elapsed);
        }
    }

    @Override
    public synchronized void resize(int width, int height) {
        synchronized (workerStateLock) {
            bufferWidth = Math.max(1, width);
            bufferHeight = Math.max(1, height);
            rebuildWorkers();
        }
    }

    @Override
    public synchronized void setParameter(String key, Object value) {
        if ("flatShading".equalsIgnoreCase(key) && value instanceof Boolean) {
            flatShading = (Boolean) value;
            return;
        }
        if ("unlitMode".equalsIgnoreCase(key) && value instanceof Boolean) {
            unlitMode = (Boolean) value;
            return;
        }
        if ("modelPreviewMode".equalsIgnoreCase(key) && value instanceof Boolean) {
            modelPreviewMode = (Boolean) value;
            return;
        }
        if ("frustumCulling".equalsIgnoreCase(key) && value instanceof Boolean) {
            frustumCulling = (Boolean) value;
            return;
        }
        if ("backfaceCulling".equalsIgnoreCase(key) && value instanceof Boolean) {
            backfaceCulling = (Boolean) value;
            return;
        }
        if ("previewFastMaterialMode".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewFastMaterialMode = (Boolean) value;
            return;
        }
        if ("previewDisableNormalMap".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewDisableNormalMap = (Boolean) value;
            return;
        }
        if ("previewDisableMetallicRoughnessMap".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewDisableMetallicRoughnessMap = (Boolean) value;
            return;
        }
        if ("previewDisableEmissiveMap".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewDisableEmissiveMap = (Boolean) value;
            return;
        }
        if ("previewDisableTransmissionPreview".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewDisableTransmissionPreview = (Boolean) value;
            return;
        }
        if ("previewDisableSheen".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewDisableSheen = (Boolean) value;
            return;
        }
        if ("previewDisableClearcoat".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewDisableClearcoat = (Boolean) value;
            return;
        }
        if ("previewPointLightsDiffuseOnly".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewPointLightsDiffuseOnly = (Boolean) value;
            return;
        }
        if ("previewDiffuseOnlyLighting".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewDiffuseOnlyLighting = (Boolean) value;
            return;
        }
        if ("previewBaseIdentityMode".equalsIgnoreCase(key) && value instanceof Boolean) {
            previewBaseIdentityMode = (Boolean) value;
            return;
        }
        if ("materialprofile".equalsIgnoreCase(key) && value != null) {
            materialProfile = parseMaterialProfile(String.valueOf(value));
            return;
        }
        if ("parallel".equalsIgnoreCase(key) && value instanceof Boolean) {
            synchronized (workerStateLock) {
                parallelEnabled = (Boolean) value;
                rebuildWorkers();
            }
            return;
        }
        if ("workerCount".equalsIgnoreCase(key) && value instanceof Number) {
            int requested = Math.max(1, Math.min(ThreadPool.recommendedWorkerCount(), ((Number) value).intValue()));
            synchronized (workerStateLock) {
                if (requested != workerCount) {
                    workerCount = requested;
                    rebuildWorkers();
                }
            }
            return;
        }
        if ("tilesize".equalsIgnoreCase(key) && value instanceof Number) {
            tileSize = Math.max(8, Math.min(256, ((Number) value).intValue()));
            return;
        }
        if ("shutdown".equalsIgnoreCase(key) && value instanceof Boolean && (Boolean) value) {
            synchronized (workerStateLock) {
                if (threadPool != null) {
                    threadPool.shutdown();
                    threadPool = null;
                }
                workerRasterizers = new TriangleRasterizer[0];
            }
        }
    }

    @Override
    public String getName() {
        if (modelPreviewMode) {
            return "Raster (Model Preview)";
        }
        if (unlitMode) {
            return "Raster (Basic Unlit)";
        }
        return "Raster (Phong)";
    }

    private List<PreparedEntity> prepareEntities(Scene scene,
                                                 Mat4 vp,
                                                 int screenWidth,
                                                 int screenHeight,
                                                 Vec3 cameraPosition) {
        List<PreparedEntity> prepared = new ArrayList<>();
        int objectId = 0;
        Vec3 positionScratch = new Vec3();
        Vec3 worldPosScratch = new Vec3();
        Vec3 normalScratch = new Vec3();
        Vec3 tangentScratch = new Vec3();
        Vec4 clipScratch = new Vec4();
        Vec3 ndcScratch = new Vec3();
        for (Entity entity : scene.getAllMeshEntities()) {
            entity.computeWorldBounds();
            if (frustumCulling && entity.getWorldBounds() != null && !frustum.intersects(entity.getWorldBounds())) {
                continue;
            }
            Mesh mesh = entity.getMesh();
            float[] positions = mesh == null ? null : mesh.getPositions();
            if (mesh == null || positions == null || mesh.getIndices() == null || mesh.getIndices().length == 0) {
                continue;
            }
            Material baseMat = entity.getMaterial();
            PhongMaterial material = toPhongMaterial(baseMat);
            if (material.hasNormalTexture() && mesh.getTangents() == null) {
                mesh.computeTangents();
            }

            Mat4 model = entity.getWorldMatrix();
            Mat4 mvp = vp.multiply(model);
            Mat3 normalMatrix;
            try {
                normalMatrix = model.inverse().transpose().toMat3();
            } catch (IllegalStateException ex) {
                normalMatrix = Mat3.identity();
            }
            int vertexCount = positions.length / 3;
            float[] clipPositions = new float[vertexCount * 4];
            float[] screenPositions = new float[vertexCount * 3];
            float[] worldPositions = new float[vertexCount * 3];
            float[] worldNormals = new float[vertexCount * 3];
            float[] worldTangents = new float[vertexCount * 3];
            float[] normals = mesh.getNormals();
            float[] tangents = mesh.getTangents();

            for (int i = 0; i < vertexCount; i++) {
                int pBase = i * 3;
                int worldBase = i * 3;
                int clipBase = i * 4;
                int screenBase = i * 3;

                positionScratch.set(positions[pBase], positions[pBase + 1], positions[pBase + 2]);
                model.transformPoint(positionScratch, worldPosScratch);
                worldPositions[worldBase] = (float) worldPosScratch.x;
                worldPositions[worldBase + 1] = (float) worldPosScratch.y;
                worldPositions[worldBase + 2] = (float) worldPosScratch.z;

                clipScratch.set(positionScratch.x, positionScratch.y, positionScratch.z, 1.0);
                mvp.transform(clipScratch, clipScratch);
                clipPositions[clipBase] = (float) clipScratch.x;
                clipPositions[clipBase + 1] = (float) clipScratch.y;
                clipPositions[clipBase + 2] = (float) clipScratch.z;
                clipPositions[clipBase + 3] = (float) clipScratch.w;

                clipScratch.perspectiveDivide(ndcScratch);
                screenPositions[screenBase] = (float) ((ndcScratch.x * 0.5 + 0.5) * (screenWidth - 1));
                screenPositions[screenBase + 1] = (float) ((1.0 - (ndcScratch.y * 0.5 + 0.5)) * (screenHeight - 1));
                screenPositions[screenBase + 2] = (float) (ndcScratch.z * 0.5 + 0.5);

                if (normals != null && normals.length >= pBase + 3) {
                    normalScratch.set(normals[pBase], normals[pBase + 1], normals[pBase + 2]).normalizeInPlace();
                    normalMatrix.transform(normalScratch, normalScratch).normalizeInPlace();
                } else {
                    normalScratch.set(0.0, 1.0, 0.0);
                }
                worldNormals[worldBase] = (float) normalScratch.x;
                worldNormals[worldBase + 1] = (float) normalScratch.y;
                worldNormals[worldBase + 2] = (float) normalScratch.z;

                buildFallbackTangent(normalScratch, tangentScratch, worldPosScratch);
                if (tangents != null && tangents.length >= pBase + 3) {
                    tangentScratch.set(tangents[pBase], tangents[pBase + 1], tangents[pBase + 2]).normalizeInPlace();
                    if (tangentScratch.lengthSquared() > 1e-9) {
                        model.transformDirection(tangentScratch, tangentScratch).normalizeInPlace();
                    }
                }
                worldTangents[worldBase] = (float) tangentScratch.x;
                worldTangents[worldBase + 1] = (float) tangentScratch.y;
                worldTangents[worldBase + 2] = (float) tangentScratch.z;
            }

            int currentObjectId = objectId++;
            PreparedEntity preparedEntity = new PreparedEntity(
                    currentObjectId,
                    mesh,
                    material,
                    clipPositions,
                    screenPositions,
                    worldPositions,
                    worldNormals,
                    worldTangents
            );
            Vec3 cameraCopy = new Vec3(cameraPosition.x, cameraPosition.y, cameraPosition.z);
            preparedEntity.shader = (x, y, depth, worldPos, worldNormal, uv0, uv1, worldTangent) ->
                    shadeFragment(x, y, currentObjectId, material, cameraCopy, worldPos, worldNormal, uv0, uv1, worldTangent);
            prepared.add(preparedEntity);
        }
        return prepared;
    }

    private void renderParallel(List<PreparedEntity> prepared, TileBins tileBins, FrameBuffer output) {
        ThreadPool localThreadPool;
        TriangleRasterizer[] localRasterizers;
        synchronized (workerStateLock) {
            if (!parallelEnabled || workerCount <= 1 || threadPool == null || workerRasterizers.length == 0) {
                renderTilesSequential(prepared, tileBins, output, rasterizer);
                return;
            }
            localThreadPool = threadPool;
            localRasterizers = workerRasterizers;
        }

        int scheduledWorkers = Math.max(1, Math.min(localRasterizers.length, countNonEmptyTiles(tileBins)));
        AtomicInteger tileCursor = new AtomicInteger();
        Runnable[] tasks = new Runnable[scheduledWorkers];
        for (int i = 0; i < tasks.length; i++) {
            final TriangleRasterizer workerRasterizer = localRasterizers[i];
            tasks[i] = () -> renderTilesWorker(prepared, tileBins, output, workerRasterizer, tileCursor);
        }
        localThreadPool.submitAndWait(tasks);
    }

    private void renderTilesSequential(List<PreparedEntity> prepared,
                                       TileBins tileBins,
                                       FrameBuffer output,
                                       TriangleRasterizer activeRasterizer) {
        TileRenderState state = new TileRenderState();
        for (int tileIndex = 0; tileIndex < tileBins.bins.length; tileIndex++) {
            renderTile(prepared, tileBins, tileIndex, output, activeRasterizer, state);
        }
    }

    private void renderTilesWorker(List<PreparedEntity> prepared,
                                   TileBins tileBins,
                                   FrameBuffer output,
                                   TriangleRasterizer activeRasterizer,
                                   AtomicInteger tileCursor) {
        TileRenderState state = new TileRenderState();
        while (true) {
            int tileIndex = tileCursor.getAndIncrement();
            if (tileIndex >= tileBins.bins.length) {
                return;
            }
            renderTile(prepared, tileBins, tileIndex, output, activeRasterizer, state);
        }
    }

    private void renderTile(List<PreparedEntity> prepared,
                            TileBins tileBins,
                            int tileIndex,
                            FrameBuffer fb,
                            TriangleRasterizer activeRasterizer,
                            TileRenderState state) {
        LongList refs = tileBins.bins[tileIndex];
        if (refs == null || refs.size() == 0) {
            return;
        }

        int tileX = tileIndex % tileBins.tileCols;
        int tileY = tileIndex / tileBins.tileCols;
        int tileMinX = tileX * tileBins.tileWidth;
        int tileMinY = tileY * tileBins.tileHeight;
        int tileMaxX = Math.min(fb.getWidth() - 1, tileMinX + tileBins.tileWidth - 1);
        int tileMaxY = Math.min(fb.getHeight() - 1, tileMinY + tileBins.tileHeight - 1);

        for (int i = 0; i < refs.size(); i++) {
            long triangleRef = refs.get(i);
            int entityIndex = decodeEntityIndex(triangleRef);
            int faceId = decodeFaceId(triangleRef);
            if (entityIndex < 0 || entityIndex >= prepared.size()) {
                continue;
            }

            PreparedEntity entity = prepared.get(entityIndex);
            int[] indices = entity.mesh.getIndices();
            int triangleBase = faceId * 3;
            if (triangleBase < 0 || triangleBase + 2 >= indices.length) {
                continue;
            }

            populateClipVertex(entity, indices[triangleBase], state.v0);
            populateClipVertex(entity, indices[triangleBase + 1], state.v1);
            populateClipVertex(entity, indices[triangleBase + 2], state.v2);

            if (isInsideClip(state.v0) && isInsideClip(state.v1) && isInsideClip(state.v2)) {
                rasterizeClipTriangle(state.v0, state.v1, state.v2, entity.material, entity.objectId, faceId,
                        fb, activeRasterizer, entity.shader,
                        tileMinX, tileMinY, tileMaxX, tileMaxY,
                        state.sx, state.sy, state.sz, state.sw, state.attrs, state.ndcScratch);
                continue;
            }

            List<ClipVertex[]> clippedTriangles = clipTriangleClipSpace(state.v0, state.v1, state.v2);
            for (ClipVertex[] tri : clippedTriangles) {
                rasterizeClipTriangle(tri[0], tri[1], tri[2], entity.material, entity.objectId, faceId,
                        fb, activeRasterizer, entity.shader,
                        tileMinX, tileMinY, tileMaxX, tileMaxY,
                        state.sx, state.sy, state.sz, state.sw, state.attrs, state.ndcScratch);
            }
        }
    }

    private int countNonEmptyTiles(TileBins tileBins) {
        if (tileBins == null || tileBins.bins == null || tileBins.bins.length == 0) {
            return 1;
        }
        int count = 0;
        for (LongList bin : tileBins.bins) {
            if (bin != null && bin.size() > 0) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private TileBins buildTileBins(List<PreparedEntity> prepared, int width, int height) {
        int tileWidth = Math.max(8, tileSize);
        int tileHeight = tileWidth;
        int tileCols = Math.max(1, (width + tileWidth - 1) / tileWidth);
        int tileRows = Math.max(1, (height + tileHeight - 1) / tileHeight);
        LongList[] bins = new LongList[tileCols * tileRows];
        TileRange range = new TileRange();
        ClipVertex v0 = new ClipVertex();
        ClipVertex v1 = new ClipVertex();
        ClipVertex v2 = new ClipVertex();

        for (int entityIndex = 0; entityIndex < prepared.size(); entityIndex++) {
            PreparedEntity entity = prepared.get(entityIndex);
            int[] indices = entity.mesh.getIndices();
            for (int triangleBase = 0; triangleBase + 2 < indices.length; triangleBase += 3) {
                int faceId = triangleBase / 3;
                range.reset();

                int i0 = indices[triangleBase];
                int i1 = indices[triangleBase + 1];
                int i2 = indices[triangleBase + 2];

                populateClipVertex(entity, i0, v0);
                populateClipVertex(entity, i1, v1);
                populateClipVertex(entity, i2, v2);

                if (isInsideClip(v0) && isInsideClip(v1) && isInsideClip(v2)) {
                    includePreparedTriangleTiles(entity, i0, i1, i2, width, height, tileWidth, tileHeight, tileCols, tileRows, range);
                } else {
                    List<ClipVertex[]> clippedTriangles = clipTriangleClipSpace(v0, v1, v2);
                    for (ClipVertex[] tri : clippedTriangles) {
                        includeClippedTriangleTiles(tri[0], tri[1], tri[2],
                                width, height, tileWidth, tileHeight, tileCols, tileRows, range);
                    }
                }

                if (!range.isValid()) {
                    continue;
                }

                long triangleRef = encodeTriangleRef(entityIndex, faceId);
                for (int tileY = range.minTileY; tileY <= range.maxTileY; tileY++) {
                    int rowBase = tileY * tileCols;
                    for (int tileX = range.minTileX; tileX <= range.maxTileX; tileX++) {
                        int tileIndex = rowBase + tileX;
                        LongList list = bins[tileIndex];
                        if (list == null) {
                            list = new LongList();
                            bins[tileIndex] = list;
                        }
                        list.add(triangleRef);
                    }
                }
            }
        }
        return new TileBins(bins, tileCols, tileRows, tileWidth, tileHeight);
    }

    private boolean includePreparedTriangleTiles(PreparedEntity entity,
                                                 int i0,
                                                 int i1,
                                                 int i2,
                                                 int width,
                                                 int height,
                                                 int tileWidth,
                                                 int tileHeight,
                                                 int tileCols,
                                                 int tileRows,
                                                 TileRange range) {
        float[] screen = entity.screenPositions;
        int base0 = i0 * 3;
        int base1 = i1 * 3;
        int base2 = i2 * 3;
        return includeScreenBounds(
                screen[base0], screen[base0 + 1],
                screen[base1], screen[base1 + 1],
                screen[base2], screen[base2 + 1],
                width, height, tileWidth, tileHeight, tileCols, tileRows, range
        );
    }

    private boolean includeClippedTriangleTiles(ClipVertex a,
                                                ClipVertex b,
                                                ClipVertex c,
                                                int width,
                                                int height,
                                                int tileWidth,
                                                int tileHeight,
                                                int tileCols,
                                                int tileRows,
                                                TileRange range) {
        double aw = a.clip.w;
        double bw = b.clip.w;
        double cw = c.clip.w;
        if (aw <= CLIP_EPS || bw <= CLIP_EPS || cw <= CLIP_EPS) {
            return false;
        }
        double sx0 = ((a.clip.x / aw) * 0.5 + 0.5) * (width - 1);
        double sy0 = (1.0 - ((a.clip.y / aw) * 0.5 + 0.5)) * (height - 1);
        double sx1 = ((b.clip.x / bw) * 0.5 + 0.5) * (width - 1);
        double sy1 = (1.0 - ((b.clip.y / bw) * 0.5 + 0.5)) * (height - 1);
        double sx2 = ((c.clip.x / cw) * 0.5 + 0.5) * (width - 1);
        double sy2 = (1.0 - ((c.clip.y / cw) * 0.5 + 0.5)) * (height - 1);
        return includeScreenBounds(
                sx0, sy0, sx1, sy1, sx2, sy2,
                width, height, tileWidth, tileHeight, tileCols, tileRows, range
        );
    }

    private boolean includeScreenBounds(double sx0,
                                        double sy0,
                                        double sx1,
                                        double sy1,
                                        double sx2,
                                        double sy2,
                                        int width,
                                        int height,
                                        int tileWidth,
                                        int tileHeight,
                                        int tileCols,
                                        int tileRows,
                                        TileRange range) {
        double minXf = Math.min(sx0, Math.min(sx1, sx2));
        double maxXf = Math.max(sx0, Math.max(sx1, sx2));
        double minYf = Math.min(sy0, Math.min(sy1, sy2));
        double maxYf = Math.max(sy0, Math.max(sy1, sy2));

        int minX = Math.max(0, (int) Math.floor(minXf));
        int maxX = Math.min(width - 1, (int) Math.ceil(maxXf));
        int minY = Math.max(0, (int) Math.floor(minYf));
        int maxY = Math.min(height - 1, (int) Math.ceil(maxYf));
        if (minX > maxX || minY > maxY) {
            return false;
        }

        int minTileX = Math.max(0, minX / tileWidth);
        int maxTileX = Math.min(tileCols - 1, maxX / tileWidth);
        int minTileY = Math.max(0, minY / tileHeight);
        int maxTileY = Math.min(tileRows - 1, maxY / tileHeight);
        if (minTileX > maxTileX || minTileY > maxTileY) {
            return false;
        }

        range.include(minTileX, minTileY, maxTileX, maxTileY);
        return true;
    }

    private static long encodeTriangleRef(int entityIndex, int faceId) {
        return ((long) entityIndex << 32) | (faceId & 0xFFFFFFFFL);
    }

    private static int decodeEntityIndex(long triangleRef) {
        return (int) (triangleRef >>> 32);
    }

    private static int decodeFaceId(long triangleRef) {
        return (int) triangleRef;
    }

    private void rebuildWorkers() {
        if (threadPool != null) {
            threadPool.shutdown();
            threadPool = null;
        }

        if (!parallelEnabled || workerCount <= 1) {
            workerRasterizers = new TriangleRasterizer[0];
            return;
        }

        threadPool = new ThreadPool(workerCount);
        workerRasterizers = new TriangleRasterizer[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workerRasterizers[i] = new TriangleRasterizer();
        }
    }

    private boolean isInsideClip(ClipVertex v) {
        double w = v.clip.w;
        if (w <= CLIP_EPS) {
            return false;
        }
        return v.clip.x >= -w - CLIP_EPS && v.clip.x <= w + CLIP_EPS
                && v.clip.y >= -w - CLIP_EPS && v.clip.y <= w + CLIP_EPS
                && v.clip.z >= -w - CLIP_EPS && v.clip.z <= w + CLIP_EPS;
    }

    private void rasterizeClipTriangle(
            ClipVertex a, ClipVertex b, ClipVertex c,
            PhongMaterial material,
            int objectId, int faceId,
            FrameBuffer fb, TriangleRasterizer activeRasterizer, FragmentShader shader,
            int tileMinX, int tileMinY, int tileMaxX, int tileMaxY,
            float[] sx, float[] sy, float[] sz, float[] sw, float[][] attrs,
            Vec3 ndcScratch
    ) {
        for (int k = 0; k < 3; k++) {
            ClipVertex v = (k == 0) ? a : (k == 1 ? b : c);
            Vec4 clip = v.clip;
            if (clip.w <= CLIP_EPS) {
                return;
            }
            double invW = 1.0 / clip.w;
            Vec3 ndc = (ndcScratch != null) ? ndcScratch : new Vec3();
            ndc.set(clip.x * invW, clip.y * invW, clip.z * invW);
            sx[k] = (float) ((ndc.x * 0.5 + 0.5) * (fb.getWidth() - 1));
            sy[k] = (float) ((1.0 - (ndc.y * 0.5 + 0.5)) * (fb.getHeight() - 1));
            sz[k] = (float) (ndc.z * 0.5 + 0.5);
            sw[k] = (float) (1.0 / clip.w);

            attrs[0][k] = (float) v.worldPos.x;
            attrs[1][k] = (float) v.worldPos.y;
            attrs[2][k] = (float) v.worldPos.z;
            attrs[3][k] = (float) v.worldNormal.x;
            attrs[4][k] = (float) v.worldNormal.y;
            attrs[5][k] = (float) v.worldNormal.z;
            attrs[6][k] = (float) v.u0;
            attrs[7][k] = (float) v.v0;
            attrs[8][k] = (float) v.u1;
            attrs[9][k] = (float) v.v1;
            attrs[10][k] = (float) v.worldTangent.x;
            attrs[11][k] = (float) v.worldTangent.y;
            attrs[12][k] = (float) v.worldTangent.z;
            attrs[13][k] = objectId;
            attrs[14][k] = faceId;
        }

        if (backfaceCulling && !material.isDoubleSided()) {
            float area = TriangleRasterizer.edgeFunction(
                    sx[0], sy[0], sx[1], sy[1], sx[2], sy[2]
            );
            if (area >= 0.0f) {
                return;
            }
        }

        activeRasterizer.rasterize(fb, sx, sy, sz, sw, attrs, shader, tileMinX, tileMinY, tileMaxX, tileMaxY);
    }

    private void populateClipVertex(PreparedEntity entity, int vertexIndex, ClipVertex out) {
        int clipBase = vertexIndex * 4;
        int worldBase = vertexIndex * 3;
        int uvBase = vertexIndex * 2;

        out.clip.set(
                entity.clipPositions[clipBase],
                entity.clipPositions[clipBase + 1],
                entity.clipPositions[clipBase + 2],
                entity.clipPositions[clipBase + 3]
        );
        out.worldPos.set(
                entity.worldPositions[worldBase],
                entity.worldPositions[worldBase + 1],
                entity.worldPositions[worldBase + 2]
        );
        out.worldNormal.set(
                entity.worldNormals[worldBase],
                entity.worldNormals[worldBase + 1],
                entity.worldNormals[worldBase + 2]
        );
        out.worldTangent.set(
                entity.worldTangents[worldBase],
                entity.worldTangents[worldBase + 1],
                entity.worldTangents[worldBase + 2]
        );

        float[] uvs = entity.mesh.getUVs();
        float[] uv2s = entity.mesh.getUV2s();
        out.u0 = (uvs != null && uvBase + 1 < uvs.length) ? uvs[uvBase] : 0.0;
        out.v0 = (uvs != null && uvBase + 1 < uvs.length) ? uvs[uvBase + 1] : 0.0;
        out.u1 = (uv2s != null && uvBase + 1 < uv2s.length) ? uv2s[uvBase] : out.u0;
        out.v1 = (uv2s != null && uvBase + 1 < uv2s.length) ? uv2s[uvBase + 1] : out.v0;
    }

    private List<ClipVertex[]> clipTriangleClipSpace(ClipVertex a, ClipVertex b, ClipVertex c) {
        List<ClipVertex> poly = new ArrayList<>(3);
        poly.add(a);
        poly.add(b);
        poly.add(c);

 // Canonical clip-space planes: -w <= x,y,z <= w
        poly = clipAgainstPlane(poly, v -> v.clip.x + v.clip.w - CLIP_EPS); // left
        poly = clipAgainstPlane(poly, v -> v.clip.w - v.clip.x - CLIP_EPS); // right
        poly = clipAgainstPlane(poly, v -> v.clip.y + v.clip.w - CLIP_EPS); // bottom
        poly = clipAgainstPlane(poly, v -> v.clip.w - v.clip.y - CLIP_EPS); // top
        poly = clipAgainstPlane(poly, v -> v.clip.z + v.clip.w - CLIP_EPS); // near
        poly = clipAgainstPlane(poly, v -> v.clip.w - v.clip.z - CLIP_EPS); // far

        List<ClipVertex[]> triangles = new ArrayList<>(2);
        if (poly.size() < 3) {
            return triangles;
        }
        ClipVertex v0 = poly.get(0);
        for (int i = 1; i < poly.size() - 1; i++) {
            triangles.add(new ClipVertex[]{v0, poly.get(i), poly.get(i + 1)});
        }
        return triangles;
    }

    private List<ClipVertex> clipAgainstPlane(List<ClipVertex> input, ClipDistance distance) {
        if (input.isEmpty()) {
            return input;
        }
        List<ClipVertex> output = new ArrayList<>(Math.max(3, input.size() + 2));
        for (int i = 0; i < input.size(); i++) {
            ClipVertex current = input.get(i);
            ClipVertex next = input.get((i + 1) % input.size());

            double dCurrent = distance.eval(current);
            double dNext = distance.eval(next);
            boolean currentInside = dCurrent >= 0.0;
            boolean nextInside = dNext >= 0.0;

            if (currentInside && nextInside) {
                output.add(next);
            } else if (currentInside) {
                output.add(intersectPlane(current, next, dCurrent, dNext));
            } else if (nextInside) {
                output.add(intersectPlane(current, next, dCurrent, dNext));
                output.add(next);
            }
        }
        return output;
    }

    private ClipVertex intersectPlane(ClipVertex a, ClipVertex b, double da, double db) {
        double denom = da - db;
        double t = Math.abs(denom) < CLIP_EPS ? 0.0 : da / denom;
        t = Math.max(0.0, Math.min(1.0, t));

        ClipVertex out = new ClipVertex();
        Vec4.lerp(a.clip, b.clip, t, out.clip);
        Vec3.lerp(a.worldPos, b.worldPos, t, out.worldPos);
        Vec3.lerp(a.worldNormal, b.worldNormal, t, out.worldNormal).normalizeInPlace();
        Vec3.lerp(a.worldTangent, b.worldTangent, t, out.worldTangent).normalizeInPlace();
        out.u0 = a.u0 + (b.u0 - a.u0) * t;
        out.v0 = a.v0 + (b.v0 - a.v0) * t;
        out.u1 = a.u1 + (b.u1 - a.u1) * t;
        out.v1 = a.v1 + (b.v1 - a.v1) * t;
        return out;
    }

    private PhongMaterial toPhongMaterial(Material material) {
        if (material instanceof PhongMaterial) {
            return (PhongMaterial) material;
        }
        Vec3 base = material != null ? material.getBaseColor() : new Vec3(0.8, 0.8, 0.8);
        PhongMaterial out = new PhongMaterial(base, 32.0);
        if (material != null) {
            out.copyFrom(material);
        }
        return out;
    }

    private int shadeFragment(int x, int y, int objectId, PhongMaterial material, Vec3 cameraPosition,
                              float[] worldPos, float[] worldNormal, float[] uv0, float[] uv1, float[] worldTangent) {
        final boolean sampleMetrics = RuntimeInstrumentation.isEnabled() && ((x & 7) == 0) && ((y & 7) == 0);
        final long metricScale = 64L;
        final long shadingStart = sampleMetrics ? System.nanoTime() : 0L;
        final long materialStart = sampleMetrics ? System.nanoTime() : 0L;
        float wx = worldPos[0];
        float wy = worldPos[1];
        float wz = worldPos[2];
        float nx = worldNormal[0];
        float ny = worldNormal[1];
        float nz = worldNormal[2];

        if (modelPreviewMode) {
            int hash = objectId * 0x9E3779B9 + 0x7F4A7C15;
            double baseR = 0.28 + (((hash >>> 16) & 0xFF) / 255.0) * 0.52;
            double baseG = 0.28 + (((hash >>> 8) & 0xFF) / 255.0) * 0.52;
            double baseB = 0.28 + ((hash & 0xFF) / 255.0) * 0.52;
            double boost = flatShading
                    ? Math.max(0.35, Math.min(1.0, Math.abs(ny)))
                    : 0.76 + 0.24 * Math.max(0.0, ny * 0.5 + 0.5);
            return packColor(baseR * boost, baseG * boost, baseB * boost, 1.0);
        }

        if (previewBaseIdentityMode) {
            return shadeBaseIdentity(material, uv0, uv1);
        }

        boolean fastMaterial = previewFastMaterialMode;
        MaterialGraphEvaluator.Result graph = !fastMaterial && material != null && material.hasNodeGraph()
                ? MaterialGraphEvaluator.evaluateRasterShared(material, wx, wy, wz, uv0, uv1)
                : null;
        boolean graphApplied = graph != null && graph.graphApplied;
        double opacity = clamp01(graphApplied ? graph.opacity : material.getOpacity());
        double roughness = clamp01(graphApplied ? graph.roughness : material.getRoughness());
        double metallic = clamp01(graphApplied ? graph.metallic : material.getMetallic());
        Vec3 baseColor = graphApplied ? graph.baseColor : material.getDiffuseColor();
        Vec3 emissionColor = graphApplied ? graph.emissionColor : material.getEmissionColor();
        double emissionStrength = graphApplied ? graph.emissionStrength : material.getEmissionStrength();
        double baseR = baseColor.x;
        double baseG = baseColor.y;
        double baseB = baseColor.z;
        double emissionR = emissionColor.x * emissionStrength;
        double emissionG = emissionColor.y * emissionStrength;
        double emissionB = emissionColor.z * emissionStrength;

        if ("floor-grid".equals(material.getName())) {
            double cell = 2.0;
            int gx = (int) Math.floor(wx / cell);
            int gz = (int) Math.floor(wz / cell);
            boolean even = ((gx + gz) & 1) == 0;
            if (even) {
 // White tiles: mirror-like glossy look.
                baseR = 0.97;
                baseG = 0.97;
                baseB = 0.97;
                roughness = 0.0;
                metallic = 0.0;
            } else {
 // Dark tiles: 50% metallic + 50% roughness.
                baseR = 0.06;
                baseG = 0.06;
                baseB = 0.06;
                roughness = 0.5;
                metallic = 0.5;
            }
            double fx = Math.abs((wx / cell) - Math.floor(wx / cell) - 0.5);
            double fz = Math.abs((wz / cell) - Math.floor(wz / cell) - 0.5);
            if (fx > 0.485 || fz > 0.485) {
                baseR = Math.min(1.0, baseR + (even ? 0.015 : 0.10));
                baseG = Math.min(1.0, baseG + (even ? 0.015 : 0.10));
                baseB = Math.min(1.0, baseB + (even ? 0.015 : 0.10));
            }
        }

        if (!graphApplied) {
            TextureMap diffuseMap = material.getDiffuseMap();
            if (canSampleTextureMap(diffuseMap, uv0, uv1)) {
                int baseTexel = sampleTextureMap(diffuseMap, uv0, uv1);
                baseR *= ((baseTexel >> 16) & 0xFF) / 255.0;
                baseG *= ((baseTexel >> 8) & 0xFF) / 255.0;
                baseB *= (baseTexel & 0xFF) / 255.0;
                opacity *= ((baseTexel >>> 24) & 0xFF) / 255.0;
            }
        }

        if (material.getAlphaMode() == PhongMaterial.AlphaMode.MASK
                && opacity < material.getAlphaCutoff()) {
            return 0x00000000;
        }

        if (!graphApplied) {
            TextureMap metallicRoughnessMap = previewDisableMetallicRoughnessMap ? null : material.getMetallicRoughnessMap();
            if (canSampleTextureMap(metallicRoughnessMap, uv0, uv1)) {
                int mrTexel = sampleTextureMap(metallicRoughnessMap, uv0, uv1);
                roughness *= ((mrTexel >> 8) & 0xFF) / 255.0;
                metallic *= (mrTexel & 0xFF) / 255.0;
            }
        }
        roughness = clamp01(roughness);
        metallic = clamp01(metallic);

        Vec3 mappedNormal = previewDisableNormalMap
                ? new Vec3(nx, ny, nz).normalizeInPlace()
                : applyNormalMap(material, nx, ny, nz, uv0, uv1, worldTangent);
        nx = (float) mappedNormal.x;
        ny = (float) mappedNormal.y;
        nz = (float) mappedNormal.z;

        if (!graphApplied) {
            TextureMap emissiveMap = previewDisableEmissiveMap ? null : material.getEmissiveMap();
            if (canSampleTextureMap(emissiveMap, uv0, uv1)) {
                int emissiveTexel = sampleTextureMap(emissiveMap, uv0, uv1);
                emissionR *= ((emissiveTexel >> 16) & 0xFF) / 255.0;
                emissionG *= ((emissiveTexel >> 8) & 0xFF) / 255.0;
                emissionB *= (emissiveTexel & 0xFF) / 255.0;
            }
        }

        double transmission = graphApplied ? graph.transmission : material.getTransmission();
        boolean pureVolume = graphApplied
                ? graph.isPureVolume()
                : material.getDomain() == Material.Domain.VOLUME
                || material.getShadingModel() == Material.ShadingModel.VOLUMETRIC;
        boolean volumeActive = graphApplied ? graph.volumeConnected : pureVolume;
        Vec3 medium = graphApplied ? graph.mediumColor : material.getMediumColor();

        if (!previewDisableTransmissionPreview
                && !pureVolume
                && (transmission > 0.01 || (graphApplied && volumeActive && graph.density > 0.01))) {
            double previewMix = clamp01(transmission * 0.32 + (1.0 - opacity) * 0.45 + (volumeActive ? graph.density * 0.08 : 0.0));
            baseR = mix(baseR, medium.x, previewMix);
            baseG = mix(baseG, medium.y, previewMix);
            baseB = mix(baseB, medium.z, previewMix);
        } else if (!previewDisableTransmissionPreview && pureVolume) {
            double density = graphApplied ? graph.density : material.getDensity();
            double fogMix = clamp01(density * 0.18 + (1.0 - opacity) * 0.40);
            baseR = mix(baseR, medium.x, fogMix);
            baseG = mix(baseG, medium.y, fogMix);
            baseB = mix(baseB, medium.z, fogMix);
        }

        if (materialProfile == MaterialProfile.DITHER) {
 // Dither benefits from stronger diffuse separation and tamer glossy glass highlights.
            roughness = clamp01(roughness * 1.18 + 0.05);
            metallic = clamp01(metallic * 0.65);
            transmission = clamp01(transmission * 0.55);
            baseR = clamp01(Math.pow(baseR, 0.92));
            baseG = clamp01(Math.pow(baseG, 0.92));
            baseB = clamp01(Math.pow(baseB, 0.92));
        }

        Vec3 specularFactor = material.getSpecularColorFactor();
        double clearcoatFactor = previewDisableClearcoat ? 0.0 : (graphApplied ? graph.clearcoatFactor : material.getClearcoatFactor());
        double clearcoatRoughness = graphApplied ? graph.clearcoatRoughness : material.getClearcoatRoughness();
        double specular = graphApplied ? graph.specularFactor : material.getSpecularFactor();
        if (materialProfile == MaterialProfile.DITHER) {
            specular = clamp01(specular * 0.82);
        }
        Vec3 sheenColor = previewDisableSheen ? Vec3.ZERO : (graphApplied ? graph.sheenColor : material.getSheenColor());
        double sheenRoughness = graphApplied ? graph.sheenRoughness : material.getSheenRoughness();
        double clearcoatBoost = clearcoatFactor * (1.0 - clearcoatRoughness * 0.65);
        double specR = clamp01(material.getSpecularColor().x * specular * specularFactor.x
                + metallic * 0.35 + clearcoatBoost * 0.28);
        double specG = clamp01(material.getSpecularColor().y * specular * specularFactor.y
                + metallic * 0.35 + clearcoatBoost * 0.28);
        double specB = clamp01(material.getSpecularColor().z * specular * specularFactor.z
                + metallic * 0.35 + clearcoatBoost * 0.28);
        double shininess = PhongMaterial.shininessFromRoughness(Math.max(0.03, roughness * (1.0 - clearcoatBoost * 0.25)));

        if (unlitMode) {
            int color = packColor(baseR + emissionR, baseG + emissionG, baseB + emissionB,
                    effectivePreviewAlpha(material, opacity, transmission));
            if (flatShading) {
                double boost = Math.max(0.2, Math.min(1.0, Math.abs(ny)));
                int rr = (int) (((color >> 16) & 0xFF) * boost);
                int gg = (int) (((color >> 8) & 0xFF) * boost);
                int bb = (int) ((color & 0xFF) * boost);
                int aa = (color >>> 24) & 0xFF;
                return (aa << 24) | (rr << 16) | (gg << 8) | bb;
            }
            return color;
        }

        if (sampleMetrics) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_BASE_MATERIAL_NS,
                    (System.nanoTime() - materialStart) * metricScale);
        }
        long directLightStart = sampleMetrics ? System.nanoTime() : 0L;
        int shaded = phongShader.shadeFast(
                wx, wy, wz,
                nx, ny, nz,
                cameraPosition.x - wx,
                cameraPosition.y - wy,
                cameraPosition.z - wz,
                baseR, baseG, baseB,
                specR,
                specG,
                specB,
                shininess
        );
        if (sampleMetrics) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_BASE_DIRECT_LIGHT_NS,
                    (System.nanoTime() - directLightStart) * metricScale);
        }
        double vx = cameraPosition.x - wx;
        double vy = cameraPosition.y - wy;
        double vz = cameraPosition.z - wz;
        double vLen = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double ndotv = vLen > 1e-9 ? Math.max(0.0, (nx * vx + ny * vy + nz * vz) / vLen) : 1.0;
        double sheenWeight = Math.pow(1.0 - ndotv, 1.0 + sheenRoughness * 5.0);
        emissionR += sheenColor.x * sheenWeight * 0.35;
        emissionG += sheenColor.y * sheenWeight * 0.35;
        emissionB += sheenColor.z * sheenWeight * 0.35;
        int result = applyAlpha(addEmission(shaded, emissionR, emissionG, emissionB), effectivePreviewAlpha(material, opacity, transmission));
        if (sampleMetrics) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_BASE_SHADING_NS,
                    (System.nanoTime() - shadingStart) * metricScale);
        }
        return result;
    }

    private static MaterialProfile parseMaterialProfile(String raw) {
        if (raw == null) {
            return MaterialProfile.PHONG;
        }
        String key = raw.trim().toUpperCase();
        if ("DITHER".equals(key) || "DITHERING".equals(key)) {
            return MaterialProfile.DITHER;
        }
        return MaterialProfile.PHONG;
    }

    private int shadeBaseIdentity(PhongMaterial material, float[] uv0, float[] uv1) {
        if (material == null) {
            return 0xFF808080;
        }
        double opacity = clamp01(material.getOpacity());
        Vec3 diffuse = material.getDiffuseColor();
        double baseR = diffuse.x;
        double baseG = diffuse.y;
        double baseB = diffuse.z;

        TextureMap diffuseMap = material.getDiffuseMap();
        if (canSampleTextureMap(diffuseMap, uv0, uv1)) {
            int texel = sampleTextureMap(diffuseMap, uv0, uv1);
            baseR *= ((texel >> 16) & 0xFF) / 255.0;
            baseG *= ((texel >> 8) & 0xFF) / 255.0;
            baseB *= (texel & 0xFF) / 255.0;
            opacity *= ((texel >>> 24) & 0xFF) / 255.0;
        }

        if (material.getAlphaMode() == PhongMaterial.AlphaMode.MASK
                && opacity < material.getAlphaCutoff()) {
            return 0x00000000;
        }

        return packColor(
                baseR,
                baseG,
                baseB,
                effectivePreviewAlpha(material, opacity, 0.0));
    }

    private static int sampleTextureMap(TextureMap map, float[] uv0, float[] uv1) {
        float[] uv = selectUv(map, uv0, uv1);
        double scaledU = uv[0] * map.getScaleU();
        double scaledV = uv[1] * map.getScaleV();
        double sin = Math.sin(map.getRotation());
        double cos = Math.cos(map.getRotation());
        double rotatedU = scaledU * cos - scaledV * sin + map.getOffsetU();
        double rotatedV = scaledU * sin + scaledV * cos + map.getOffsetV();
        Texture texture = map.getTexture();
        return map.isLinear()
                ? texture.sampleBilinear(rotatedU, rotatedV, map.isFlipV())
                : texture.sampleNearest(rotatedU, rotatedV, map.isFlipV());
    }

    private static boolean canSampleTextureMap(TextureMap map, float[] uv0, float[] uv1) {
        return map != null && map.hasTexture() && selectUv(map, uv0, uv1) != null;
    }

    private static float[] selectUv(TextureMap map, float[] uv0, float[] uv1) {
        if (map != null && map.getTexCoord() > 0 && uv1 != null) {
            return uv1;
        }
        return uv0 != null ? uv0 : uv1;
    }

    private static Vec3 applyNormalMap(PhongMaterial material,
                                       float nx,
                                       float ny,
                                       float nz,
                                       float[] uv0,
                                       float[] uv1,
                                       float[] worldTangent) {
        NormalMapScratch scratch = NORMAL_MAP_SCRATCH.get();
        Vec3 normal = scratch.normal.set(nx, ny, nz).normalizeInPlace();
        if (!canSampleTextureMap(material.getNormalMap(), uv0, uv1)) {
            return normal;
        }
        int texel = sampleTextureMap(material.getNormalMap(), uv0, uv1);
        double tx = ((texel >> 16) & 0xFF) / 255.0 * 2.0 - 1.0;
        double ty = ((texel >> 8) & 0xFF) / 255.0 * 2.0 - 1.0;
        double tz = (texel & 0xFF) / 255.0 * 2.0 - 1.0;
        tx *= material.getNormalScale();
        ty *= material.getNormalScale();
        Vec3 tangent = scratch.tangent;
        if (worldTangent != null) {
            tangent.set(worldTangent[0], worldTangent[1], worldTangent[2]).normalizeInPlace();
        } else {
            buildFallbackTangent(normal, tangent, scratch.axis);
        }
        if (tangent.lengthSquared() < 1e-9) {
            buildFallbackTangent(normal, tangent, scratch.axis);
        }
        Vec3 bitangent = normal.cross(tangent, scratch.bitangent).normalizeInPlace();
        if (bitangent.lengthSquared() < 1e-9) {
            buildFallbackTangent(normal, scratch.fallbackTangent, scratch.axis);
            bitangent = scratch.fallbackTangent.cross(normal, bitangent).normalizeInPlace();
        }
        bitangent.cross(normal, tangent).normalizeInPlace();
        return scratch.result.set(tangent)
                .mulInPlace(tx)
                .addScaledInPlace(bitangent, ty)
                .addScaledInPlace(normal, tz)
                .normalizeInPlace();
    }

    private static Vec3 buildFallbackTangent(Vec3 normal) {
        return buildFallbackTangent(normal, new Vec3(), new Vec3());
    }

    private static Vec3 buildFallbackTangent(Vec3 normal, Vec3 out, Vec3 axisScratch) {
        Vec3 axis = axisScratch == null ? new Vec3() : axisScratch;
        if (Math.abs(normal.y) < 0.92) {
            axis.set(Vec3.UP);
        } else {
            axis.set(1.0, 0.0, 0.0);
        }
        axis.cross(normal, out).normalizeInPlace();
        if (out.lengthSquared() < 1e-9) {
            axis.set(0.0, 0.0, 1.0);
            axis.cross(normal, out).normalizeInPlace();
        }
        if (out.lengthSquared() < 1e-9) {
            out.set(1.0, 0.0, 0.0);
        }
        return out;
    }

    private static int addEmission(int argb, double r, double g, double b) {
        int cr = (argb >>> 16) & 0xFF;
        int cg = (argb >>> 8) & 0xFF;
        int cb = argb & 0xFF;
        int ca = (argb >>> 24) & 0xFF;
        int rr = (int) (clamp01(cr / 255.0 + r) * 255.0 + 0.5);
        int rg = (int) (clamp01(cg / 255.0 + g) * 255.0 + 0.5);
        int rb = (int) (clamp01(cb / 255.0 + b) * 255.0 + 0.5);
        return (ca << 24) | (rr << 16) | (rg << 8) | rb;
    }

    private static int applyAlpha(int argb, double alpha) {
        int a = (int) (clamp01(alpha) * 255.0 + 0.5);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int packColor(double r, double g, double b, double alpha) {
        int a = (int) (clamp01(alpha) * 255.0 + 0.5);
        int ir = (int) (clamp01(r) * 255.0 + 0.5);
        int ig = (int) (clamp01(g) * 255.0 + 0.5);
        int ib = (int) (clamp01(b) * 255.0 + 0.5);
        return (a << 24) | (ir << 16) | (ig << 8) | ib;
    }

    private static double effectivePreviewAlpha(PhongMaterial material, double opacity, double transmission) {
        if (material == null) {
            return clamp01(opacity);
        }
        if (material.getAlphaMode() == PhongMaterial.AlphaMode.BLEND
                || transmission > 0.01
                || opacity < 0.999) {
            return Math.max(0.14, clamp01(opacity));
        }
        return 1.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }
}
