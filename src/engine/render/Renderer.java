package engine.render;

import engine.camera.Camera;
import engine.scene.Scene;

/**
 * Tady definuju společný kontrakt pro všechny render módy.
 */
public interface Renderer {

    /**
     * Tady provedu jednorázovou inicializaci rendereru.
     *
     * @param width sem předám šířku framebufferu v pixelech
     * @param height sem předám výšku framebufferu v pixelech
     */
    void init(int width, int height);

    /**
     * Tady vyrenderuju scénu z pohledu kamery do framebufferu.
     *
     * @param scene sem předám scénu k vykreslení
     * @param camera sem předám aktivní kameru
     * @param fb sem předám cílový framebuffer
     * @param time sem předám uplynulý čas v sekundách
     */
    void render(Scene scene, Camera camera, FrameBuffer fb, double time);

    /**
     * Tady zareaguju na resize framebufferu.
     *
     * @param width sem předám novou šířku
     * @param height sem předám novou výšku
     */
    void resize(int width, int height);

    /**
     * Tady za běhu aplikuju renderer-specific parametry.
     *
     * @param key sem předám jméno parametru
     * @param value sem předám hodnotu parametru
     */
    void setParameter(String key, Object value);

    /** @return vrátím uživatelsky čitelné jméno render módu */
    String getName();
}
