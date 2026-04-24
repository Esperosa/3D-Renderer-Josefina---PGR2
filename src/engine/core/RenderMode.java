package engine.core;

/**
 * vyjmenovává všechny dostupné render módy.
 */
public enum RenderMode {

 /** Represents extrémně lehký model preview bez materiálů a textur. */
    MODEL,

 /** Represents rychlý nerastrovaný flat-color režim pro debugging. */
    BASIC,

 /** Represents per-pixel Phong/Blinn-Phong rasterizér. */
    PHONG,

 /** Represents gradientní wireframe s modulací podle vzdálenosti a úhlu. */
    WIREFRAME,

 /** Represents CPU ray tracer se stíny a odrazy. */
    RAY_TRACING,

 /** Represents progresivní Monte Carlo path tracer s akumulací. */
    PATH_TRACING,

 /** Represents stylizovaný režim ditheringu. */
    DITHERING,

 /** Represents temporal-noise renderer, kde tvar čtu ze změny šumu v čase. */
    TEMPORAL_NOISE,

 /** Represents hexagonální adaptivní mozaiku. */
    HEX_MOSAIC
}