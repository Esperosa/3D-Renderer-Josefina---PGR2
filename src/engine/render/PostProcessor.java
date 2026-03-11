package engine.render;

/**
 * Tady definuju rozhraní pro postproces efekty aplikované po hlavním průchodu vykreslení.
 * Pracuju v něm přímo s pixelovými daty framebufferu.
 */
public interface PostProcessor {

    /**
     * Tady aplikuju postproces efekt přímo do framebufferu.
     *
     * @param fb sem předám framebuffer ke zpracování
     * @param time sem předám uplynulý čas pro animované efekty
     */
    void process(FrameBuffer fb, double time);

    /** @return tím vrátím zobrazovaný název tohoto postprocesoru */
    String getName();
}
