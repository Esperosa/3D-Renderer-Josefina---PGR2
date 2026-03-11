package engine.core;

import engine.geometry.Mesh;
import engine.io.FileUtil;
import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.material.PhongMaterial;
import engine.math.AABB;
import engine.render.Texture;
import engine.scene.Entity;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SceneImportController {
    private static final int APPLY_CHUNK_ENTRIES = 4;
    private static final int APPLY_CHUNK_TRIANGLE_BUDGET = 220_000;

    private static final class LoadedImport {
        final String filePath;
        final String ext;
        final ImportedScene importedScene;
        final Texture diffuseTexture;

        LoadedImport(String filePath, String ext, ImportedScene importedScene, Texture diffuseTexture) {
            this.filePath = filePath;
            this.ext = ext;
            this.importedScene = importedScene;
            this.diffuseTexture = diffuseTexture;
        }
    }

    private static final class ApplyJob {
        final LoadedImport loaded;
        final int totalEntries;
        int entryIndex;
        int added;
        int totalTriangles;
        Entity firstAdded;
        AABB importedBounds;
        final List<Entity> addedEntities;

        ApplyJob(LoadedImport loaded) {
            this.loaded = loaded;
            this.totalEntries = loaded == null || loaded.importedScene == null ? 0 : loaded.importedScene.getEntries().size();
            this.entryIndex = 0;
            this.added = 0;
            this.totalTriangles = 0;
            this.firstAdded = null;
            this.importedBounds = null;
            this.addedEntities = new ArrayList<>();
        }
    }

    private volatile boolean importInProgress;
    private volatile LoadedImport loadedImport;
    private volatile RuntimeException importFailure;
    private ApplyJob applyJob;

    private JDialog progressDialog;
    private JLabel progressLabel;
    private JProgressBar progressBar;

    SceneImportController() {
        this.importInProgress = false;
        this.loadedImport = null;
        this.importFailure = null;
        this.applyJob = null;
        this.progressDialog = null;
        this.progressLabel = null;
        this.progressBar = null;
    }

    boolean isBusy() {
        return importInProgress || loadedImport != null || applyJob != null;
    }

    void startImport(Engine engine, String filePath) {
        if (engine == null || filePath == null || filePath.isBlank()) {
            return;
        }
        if (isBusy()) {
            System.out.println("Scene import already in progress.");
            return;
        }
        if (!FileUtil.exists(filePath)) {
            throw new RuntimeException("Import file not found: " + filePath);
        }

        importInProgress = true;
        loadedImport = null;
        importFailure = null;
        applyJob = null;
        String fileName = Path.of(filePath).getFileName().toString();
        openProgressDialog(engine, fileName);
        updateProgress(6, true, "Reading " + fileName);

        Thread worker = new Thread(() -> runImportWorker(engine, filePath), "scene-import-worker");
        worker.setDaemon(true);
        worker.start();
    }

    void update(Engine engine) {
        RuntimeException failure = importFailure;
        if (failure != null) {
            importFailure = null;
            importInProgress = false;
            loadedImport = null;
            applyJob = null;
            closeProgressDialog();
            System.out.println("Import failed: " + failure.getMessage());
            return;
        }

        if (applyJob == null && loadedImport != null) {
            applyJob = new ApplyJob(loadedImport);
            loadedImport = null;
            updateProgress(72, false, "Building scene objects...");
        }

        if (applyJob != null) {
            processApplyChunk(engine, applyJob);
        }
    }

    void dispose() {
        importInProgress = false;
        loadedImport = null;
        importFailure = null;
        applyJob = null;
        closeProgressDialog();
    }

    private void runImportWorker(Engine engine, String filePath) {
        try {
            updateProgress(16, true, "Parsing scene data...");
            ImportedScene imported = new ModelImporter().importScene(filePath);
            String ext = FileUtil.getExtension(filePath);

            updateProgress(56, true, "Resolving textures...");
            Texture diffuseTexture = EngineSceneActions.tryLoadObjDiffuseTexture(engine, ext, filePath);

            loadedImport = new LoadedImport(filePath, ext, imported, diffuseTexture);
            updateProgress(68, true, "Scheduling scene build...");
        } catch (RuntimeException ex) {
            importFailure = ex;
        }
    }

    private void processApplyChunk(Engine engine, ApplyJob job) {
        if (engine == null || job == null || job.loaded == null || job.loaded.importedScene == null) {
            return;
        }

        int processedEntries = 0;
        int remainingTriangleBudget = APPLY_CHUNK_TRIANGLE_BUDGET;
        List<ImportedScene.Entry> entries = job.loaded.importedScene.getEntries();
        while (job.entryIndex < job.totalEntries
                && processedEntries < APPLY_CHUNK_ENTRIES
                && remainingTriangleBudget > 0) {
            ImportedScene.Entry entry = entries.get(job.entryIndex++);
            if (entry == null || entry.getMesh() == null) {
                continue;
            }

            Mesh mesh = entry.getMesh();
            int triangles = mesh.getTriangleCount();
            PhongMaterial material = entry.getMaterial() != null
                    ? EngineSceneActions.cloneMaterial(entry.getMaterial())
                    : EngineSceneActions.createImportMaterial(entry.getName(), job.entryIndex);
            if (job.loaded.diffuseTexture != null && material.getDiffuseTexture() == null) {
                material.setDiffuseTexture(job.loaded.diffuseTexture);
                material.setTextureFilteringLinear(true);
            }

            String uniqueName = EngineSceneActions.uniqueEntityName(engine, EngineSceneActions.sanitizeName(entry.getName()));
            Entity entity = new Entity(uniqueName, mesh, material);
            entity.getTransform().setPosition(entry.getPosition());
            entity.getTransform().setRotation(entry.getRotation());
            entity.getTransform().setScale(entry.getScale());
            engine.scene.addEntity(entity);
            engine.stateFor(entity);
            entity.computeWorldBounds();

            job.added++;
            job.totalTriangles += triangles;
            if (job.firstAdded == null) {
                job.firstAdded = entity;
            }
            job.addedEntities.add(entity);
            job.importedBounds = EngineSceneActions.mergeBounds(job.importedBounds, entity.getWorldBounds());

            processedEntries++;
            remainingTriangleBudget -= Math.max(1, triangles);
        }

        int percent = job.totalEntries <= 0
                ? 96
                : 72 + (int) Math.round((job.entryIndex / (double) job.totalEntries) * 26.0);
        updateProgress(Math.min(98, percent),
                false,
                "Building scene objects... " + Math.min(job.entryIndex, job.totalEntries) + " / " + job.totalEntries);

        if (job.entryIndex < job.totalEntries) {
            return;
        }

        if (job.added <= 0) {
            importInProgress = false;
            applyJob = null;
            closeProgressDialog();
            System.out.println("Import produced no valid mesh entities: " + job.loaded.filePath);
            return;
        }

        engine.loadedModelPath = job.loaded.filePath;
        engine.loadedDiffuseTexturePath = null;
        if ("obj".equals(job.loaded.ext)) {
            engine.loadedDiffuseTexturePath = EngineSceneBootstrap.findDiffuseTextureForObj(engine, job.loaded.filePath);
        }

        if (job.firstAdded != null) {
            engine.setCurrentEntitySelection(job.firstAdded);
            EngineSceneActions.focusCameraOnImportedBounds(engine, job.importedBounds, job.firstAdded, job.addedEntities);
        }
        engine.refreshObjectInspectorValues();
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
        engine.applySceneVisibility(false);

        updateProgress(100, false, "Import complete");
        closeProgressDialog();
        importInProgress = false;
        applyJob = null;
        System.out.println("Imported " + job.added + " object(s), " + job.totalTriangles
                + " triangles from: " + job.loaded.filePath);
    }

    private void openProgressDialog(Engine engine, String fileName) {
        SwingUtilities.invokeLater(() -> {
            closeProgressDialog();
            JDialog dialog = new JDialog(engine != null && engine.window != null ? engine.window.getFrame() : null,
                    "Import Scene", false);
            dialog.setLayout(new BorderLayout(10, 10));

            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(74, 118, 160), 1, true),
                    BorderFactory.createEmptyBorder(12, 12, 12, 12)
            ));
            content.setBackground(new Color(19, 31, 45));

            JLabel title = new JLabel("Loading: " + fileName);
            title.setForeground(new Color(232, 244, 252));
            content.add(title, BorderLayout.NORTH);

            progressLabel = new JLabel("Preparing import...");
            progressLabel.setForeground(new Color(180, 223, 250));
            content.add(progressLabel, BorderLayout.CENTER);

            progressBar = new JProgressBar(0, 100);
            progressBar.setValue(0);
            progressBar.setStringPainted(true);
            progressBar.setIndeterminate(true);
            content.add(progressBar, BorderLayout.SOUTH);

            dialog.setContentPane(content);
            dialog.pack();
            dialog.setResizable(false);
            dialog.setLocationRelativeTo(engine != null && engine.window != null ? engine.window.getFrame() : null);
            dialog.setVisible(true);
            progressDialog = dialog;
        });
    }

    private void closeProgressDialog() {
        SwingUtilities.invokeLater(() -> {
            if (progressDialog != null) {
                progressDialog.dispose();
                progressDialog = null;
            }
            progressLabel = null;
            progressBar = null;
        });
    }

    private void updateProgress(int percent, boolean indeterminate, String message) {
        SwingUtilities.invokeLater(() -> {
            if (progressLabel != null && message != null && !message.isBlank()) {
                progressLabel.setText(message);
            }
            if (progressBar != null) {
                progressBar.setIndeterminate(indeterminate);
                progressBar.setValue(Math.max(0, Math.min(100, percent)));
            }
        });
    }
}
