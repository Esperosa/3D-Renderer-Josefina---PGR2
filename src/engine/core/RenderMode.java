package engine.core;

/**
 * Tady vyjmenovávám všechny dostupné render módy.
 */
public enum RenderMode {

    /** Tady držím extrémně lehký model preview bez materiálů a textur. */
    MODEL,

    /** Tady držím rychlý nerastrovaný flat-color režim pro debugging. */
    BASIC,

    /** Tady držím per-pixel Phong/Blinn-Phong rasterizér. */
    PHONG,

    /** Tady držím gradientní wireframe s modulací podle vzdálenosti a úhlu. */
    WIREFRAME,

    /** Tady držím CPU ray tracer se stíny a odrazy. */
    RAY_TRACING,

    /** Tady držím progresivní Monte Carlo path tracer s akumulací. */
    PATH_TRACING,

    /** Tady držím stylizovaný režim ditheringu. */
    DITHERING,

    /** Tady držím temporal-noise renderer, kde tvar čtu ze změny šumu v čase. */
    TEMPORAL_NOISE,

    /** Tady držím hexagonální adaptivní mozaiku. */
    HEX_MOSAIC
}
