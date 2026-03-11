package engine.core;

import engine.geometry.Mesh;
import engine.math.Ray;
import engine.math.Vec3;
import engine.scene.Entity;

final class EngineSpawnPlacementController {
    private EngineSpawnPlacementController() {
    }

    static int currentPointerCanvasX(Engine engine) {
        if (engine.window == null) {
            return 0;
        }
        if (engine.mouseCaptured) {
            return Math.max(0, engine.window.getCanvas().getWidth() / 2);
        }
        if (engine.input != null) {
            return engine.input.getMouseX();
        }
        return Math.max(0, engine.window.getCanvas().getWidth() / 2);
    }

    static int currentPointerCanvasY(Engine engine) {
        if (engine.window == null) {
            return 0;
        }
        if (engine.mouseCaptured) {
            return Math.max(0, engine.window.getCanvas().getHeight() / 2);
        }
        if (engine.input != null) {
            return engine.input.getMouseY();
        }
        return Math.max(0, engine.window.getCanvas().getHeight() / 2);
    }

    static Vec3 spawnPositionFromPointer(Engine engine, double fallbackDistance) {
        double fallback = Math.max(0.4, fallbackDistance);
        if (engine.camera == null || engine.scene == null || engine.window == null) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        int mx = currentPointerCanvasX(engine);
        int my = currentPointerCanvasY(engine);
        Ray ray = engine.buildPickRay(mx, my);
        if (ray == null) {
            return engine.camera.getPosition().add(engine.camera.getForward().mul(fallback));
        }
        Vec3 origin = ray.getOrigin();
        Vec3 dir = ray.getDirection();

        double sceneT = intersectRayScene(engine, origin, dir, Double.POSITIVE_INFINITY);
        if (Double.isFinite(sceneT) && sceneT > 0.1) {
            double nearT = Math.max(0.25, sceneT - 0.35);
            return origin.add(dir.mul(nearT));
        }

        if (Math.abs(dir.y) > 1e-6) {
            double tPlane = -origin.y / dir.y;
            if (tPlane > 0.1) {
                return origin.add(dir.mul(tPlane));
            }
        }
        return origin.add(dir.mul(fallback));
    }

    static double intersectRayScene(Engine engine, Vec3 origin, Vec3 direction, double maxT) {
        double bestT = maxT;
        for (Entity entity : engine.scene.getAllMeshEntities()) {
            Mesh mesh = entity.getMesh();
            if (mesh == null || mesh.getIndices() == null || mesh.getPositions() == null) {
                continue;
            }
            double t = engine.intersectRayMesh(origin, direction, mesh, entity.getWorldMatrix(), bestT);
            if (Double.isFinite(t) && t < bestT) {
                bestT = t;
            }
        }
        return bestT;
    }
}
