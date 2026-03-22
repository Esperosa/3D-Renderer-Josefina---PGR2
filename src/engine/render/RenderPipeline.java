package engine.render;

import engine.camera.Camera;
import engine.scene.Scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds celý render pipeline pro jeden snímek.
 */
public class RenderPipeline {

    private Renderer activeRenderer;
    private final List<PostProcessor> postProcessors;

    public RenderPipeline() {
        this(null);
    }

    public RenderPipeline(Renderer activeRenderer) {
        this.activeRenderer = activeRenderer;
        this.postProcessors = new ArrayList<>();
    }

 /**
 * Runs celý render pipeline pro jeden snímek.
 *
 * @param scene scénu k vykreslení
 * @param camera kameru
 * @param fb cílový framebuffer
 * @param time uplynulý čas
 */
    public void execute(Scene scene, Camera camera, FrameBuffer fb, double time) {
        if (fb == null) {
            return;
        }
        if (activeRenderer == null) {
            fb.clear(0xFF000000, 1.0f);
            return;
        }
        activeRenderer.render(scene, camera, fb, time);
        for (PostProcessor pp : postProcessors) {
            pp.process(fb, time);
        }
    }

    public void setActiveRenderer(Renderer renderer) {
        this.activeRenderer = renderer;
    }

    public Renderer getActiveRenderer() {
        return activeRenderer;
    }

    public void addPostProcessor(PostProcessor pp) {
        if (pp != null) {
            postProcessors.add(pp);
        }
    }

    public void clearPostProcessors() {
        postProcessors.clear();
    }

    public List<PostProcessor> getPostProcessors() {
        return Collections.unmodifiableList(postProcessors);
    }
}