package engine.render.raster;

/**
 * Tady definuju callback pro stínování po pixelech, který si TriangleRasterizer volá pro každý fragment.
 * Implementací z něj počítám finální barvu pixelu z interpolovaných atributů.
 */
@FunctionalInterface
public interface FragmentShader {

    /**
     * Tady spočítám barvu ARGB pro jeden pixel.
     *
     * @param x sem předám obrazovou souřadnici x
     * @param y sem předám obrazovou souřadnici y
     * @param depth sem předám interpolovanou hloubku
     * @param worldPos sem předám interpolovanou pozici ve světě [wx, wy, wz]
     * @param worldNormal sem předám interpolovanou normálu ve světě [nx, ny, nz]
     * @param uv0 sem předám interpolované UV0 [u, v], případně null
     * @param uv1 sem předám interpolované UV1 [u, v], případně null
     * @param worldTangent sem předám interpolovaný tečný vektor ve světě [tx, ty, tz], případně null
     * @return tím vrátím zabalenou ARGB barvu pixelu
     */
    int shade(int x, int y, float depth,
              float[] worldPos, float[] worldNormal,
              float[] uv0, float[] uv1, float[] worldTangent);
}
