package engine.render.raster;

/**
 * Defines callback pro stínování po pixelech, který si TriangleRasterizer volá pro každý fragment.
 * Implementací z něj počítá finální barvu pixelu z interpolovaných atributů.
 */
@FunctionalInterface
public interface FragmentShader {

 /**
 * spočítá barvu ARGB pro jeden pixel.
 *
 * @param x obrazovou souřadnici x
 * @param y obrazovou souřadnici y
 * @param depth interpolovanou hloubku
 * @param worldPos interpolovanou pozici ve světě [wx, wy, wz]
 * @param worldNormal interpolovanou normálu ve světě [nx, ny, nz]
 * @param uv0 interpolované UV0 [u, v], případně null
 * @param uv1 interpolované UV1 [u, v], případně null
 * @param worldTangent interpolovaný tečný vektor ve světě [tx, ty, tz], případně null
 * @return tím vrátí zabalenou ARGB barvu pixelu
 */
    int shade(int x, int y, float depth,
              float[] worldPos, float[] worldNormal,
              float[] uv0, float[] uv1, float[] worldTangent);
}