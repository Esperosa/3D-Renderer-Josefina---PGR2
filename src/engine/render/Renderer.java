package engine.render;

import engine.camera.Camera;
import engine.scene.Scene;

/**
 * Defines společný kontrakt pro všechny render módy.
 */
public interface Renderer {

 /**
 * Performs jednorázovou inicializaci rendereru.
 *
 * @param width šířku framebufferu v pixelech
 * @param height výšku framebufferu v pixelech
 */
    void init(int width, int height);

 /**
 * Renders scénu z pohledu kamery do framebufferu.
 *
 * @param scene scénu k vykreslení
 * @param camera aktivní kameru
 * @param fb cílový framebuffer
 * @param time uplynulý čas v sekundách
 */
    void render(Scene scene, Camera camera, FrameBuffer fb, double time);

 /**
 * zareaguju na resize framebufferu.
 *
 * @param width novou šířku
 * @param height novou výšku
 */
    void resize(int width, int height);

 /**
 * za běhu aplikuju renderer-specific parametry.
 *
 * @param key jméno parametru
 * @param value hodnotu parametru
 */
    void setParameter(String key, Object value);

 /** @return vrátí uživatelsky čitelné jméno render módu */
    String getName();
}