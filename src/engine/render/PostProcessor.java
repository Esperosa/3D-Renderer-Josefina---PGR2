package engine.render;

/**
 * Defines rozhraní pro postproces efekty aplikované po hlavním průchodu vykreslení.
 * Pracuju v něm přímo s pixelovými daty framebufferu.
 */
public interface PostProcessor {

 /**
 * aplikuju postproces efekt přímo do framebufferu.
 *
 * @param fb framebuffer ke zpracování
 * @param time uplynulý čas pro animované efekty
 */
    void process(FrameBuffer fb, double time);

 /** @return tím vrátí zobrazovaný název tohoto postprocesoru */
    String getName();
}