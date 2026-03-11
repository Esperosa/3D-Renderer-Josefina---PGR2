package engine.core;

import engine.camera.Camera;
import engine.physics.PhysicsWorld;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.scene.Scene;

/**
 * Tady držím herní smyčku s pevným krokem a proměnlivou render frekvencí.
 */
public class GameLoop {

    private Scene scene;
    private Camera camera;
    private PhysicsWorld physicsWorld;
    private Renderer renderer;
    private FrameBuffer frameBuffer;
    private Input input;
    private final Time time;
    private Runnable presentCallback;
    private int maxUpdatesPerFrame;
    private volatile boolean running;

    public GameLoop() {
        this(null, null, null, null, null, null, null);
    }

    public GameLoop(Scene scene,
                    Camera camera,
                    PhysicsWorld physicsWorld,
                    Renderer renderer,
                    FrameBuffer frameBuffer,
                    Input input,
                    Runnable presentCallback) {
        this.scene = scene;
        this.camera = camera;
        this.physicsWorld = physicsWorld;
        this.renderer = renderer;
        this.frameBuffer = frameBuffer;
        this.input = input;
        this.presentCallback = presentCallback;
        this.time = new Time(1.0 / 60.0);
        this.maxUpdatesPerFrame = 6;
        this.running = false;
    }

    /**
     * Tady vstoupím do hlavní smyčky a zůstanu v ní, dokud nepožádám o vypnutí.
     */
    public void run() {
        if (renderer == null || scene == null || camera == null || frameBuffer == null) {
            return;
        }

        running = true;
        while (running) {
            time.tick();
            if (input != null) {
                input.poll();
            }

            int updates = 0;
            while (time.consumeFixedStep() && updates < maxUpdatesPerFrame) {
                update(time.getFixedTimeStep());
                updates++;
            }

            renderer.render(scene, camera, frameBuffer, time.getElapsedTime());
            if (presentCallback != null) {
                presentCallback.run();
            }

            if (updates == 0) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    /**
     * Tady provedu jeden fixed-step update tick.
     *
     * @param dt sem předám fixed delta čas v sekundách
     */
    private void update(double dt) {
        if (physicsWorld != null) {
            physicsWorld.step(dt);
        }
        if (scene != null) {
            scene.update(dt);
        }
    }

    /**
     * Tady požádám o ukončení smyčky po dokončení aktuálního framu.
     */
    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public void setScene(Scene scene) {
        this.scene = scene;
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    public void setPhysicsWorld(PhysicsWorld physicsWorld) {
        this.physicsWorld = physicsWorld;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setFrameBuffer(FrameBuffer frameBuffer) {
        this.frameBuffer = frameBuffer;
    }

    public void setInput(Input input) {
        this.input = input;
    }

    public void setPresentCallback(Runnable presentCallback) {
        this.presentCallback = presentCallback;
    }

    public Time getTime() {
        return time;
    }

    public void setMaxUpdatesPerFrame(int maxUpdatesPerFrame) {
        this.maxUpdatesPerFrame = Math.max(1, maxUpdatesPerFrame);
    }
}
