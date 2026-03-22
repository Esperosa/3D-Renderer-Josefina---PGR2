package engine.sim.galaxy;

import engine.scene.Scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents experimentální scaffold pro budoucí galaxy simulátor.
 * Zatím v něm jen sleduju kotevní entity galaxy systému a záměrně netvrdím,
 * že už umím orbitální nebo N-body chování.
 */
public final class GalaxySimulation {

    private final List<GalaxySystemEntity> systems;

    public GalaxySimulation() {
        this.systems = new ArrayList<>();
    }

    public static boolean isExperimentalScaffold() {
        return true;
    }

    public List<GalaxySystemEntity> getSystems() {
        return Collections.unmodifiableList(systems);
    }

    public void addSystem(GalaxySystemEntity system) {
        if (system != null && !systems.contains(system)) {
            systems.add(system);
        }
    }

    public void removeSystem(GalaxySystemEntity system) {
        systems.remove(system);
    }

    public void syncScene(Scene scene) {
        if (scene == null) {
            return;
        }
        for (engine.scene.Entity entity : scene.getEntities()) {
            if (entity instanceof GalaxySystemEntity) {
                addSystem((GalaxySystemEntity) entity);
            }
        }
        systems.removeIf(system -> system == null || system.getParent() == null && !scene.getEntities().contains(system));
    }

    public void update(Scene scene, double dtSeconds, double elapsedSeconds) {
        syncScene(scene);
 // záměrně končím jen u scaffoldingu, protože ještě nemám orbitální solver ani vzorkovaná galaxy tělesa.
    }
}