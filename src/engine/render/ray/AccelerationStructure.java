package engine.render.ray;

import engine.math.Ray;

/**
 * Tady definuju rozhraní pro prostorové akcelerační struktury používané v ray tracingu.
 * Jako hlavní implementaci v praxi používám BVH.
 */
public interface AccelerationStructure {

    /**
     * Tady strukturu sestavím nebo znovu přestavím ze všech scénových meshí.
     *
     * @param positions sem předám pole pozic pro jednotlivé meshe
     * @param indices sem předám indexová pole pro jednotlivé meshe
     * @param modelMats sem předám matice jednotlivých meshí ve světovém prostoru jako ploché double[16]
     * @param meshCount sem předám počet meshí
     */
    void build(float[][] positions, int[][] indices, double[][] modelMats, int meshCount);

    /**
     * Tady najdu nejbližší průsečík paprsku se scénou.
     *
     * @param ray sem předám testovaný paprsek
     * @param tMin sem předám minimální parametrickou vzdálenost
     * @param tMax sem předám maximální parametrickou vzdálenost
     * @param record sem předám výstupní hit record, který naplním při zásahu
     * @return tím vrátím true, když najdu průsečík
     */
    boolean intersect(Ray ray, double tMin, double tMax, HitRecord record);

    /**
     * Tady otestuju, jestli paprsek zakrývá nějaká geometrie, typicky pro stínové paprsky.
     * Můžu skončit dřív bez dopočtu plného hit recordu.
     *
     * @param ray sem předám stínový paprsek
     * @param tMin sem předám minimální vzdálenost
     * @param tMax sem předám maximální vzdálenost
     * @return tím vrátím true, když existuje libovolný průsečík
     */
    boolean intersectAny(Ray ray, double tMin, double tMax);
}
