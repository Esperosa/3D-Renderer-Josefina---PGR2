package engine.render.ray.bvh;

import engine.math.Ray;

/**
 * Tady je společný kontrakt pro prostorové akcelerační struktury
 * používané v ray traceru i path traceru.
 * Výchozí implementace v tomto modulu je BVH.
 */
public interface AccelerationStructure {

    /**
     * Tady se struktura sestaví nebo znovu přestaví ze scénových mesh dat.
     *
     * @param positions pole pozic po jednotlivých meshích
     * @param indices indexová pole po jednotlivých meshích
     * @param modelMats světové transformace meshí jako ploché double[16]
     * @param meshCount počet meshí, které se mají zpracovat
     */
    void build(float[][] positions, int[][] indices, double[][] modelMats, int meshCount);

    /**
     * Tady se hledá nejbližší průsečík paprsku se scénovou geometrií.
     *
     * @param ray testovaný paprsek
     * @param tMin spodní parametrická mez
     * @param tMax horní parametrická mez
     * @param record výstupní hit record vyplněný při zásahu
     * @return true, když je nalezen průsečík
     */
    boolean intersect(Ray ray, double tMin, double tMax, HitRecord record);

    /**
     * Tady se testuje, jestli je paprsek zakrytý nějakou geometrií,
     * typicky pro stínové paprsky.
     * Implementace může skončit dřív bez dopočtu plného hit recordu.
     *
     * @param ray testovaný stínový paprsek
     * @param tMin spodní mez vzdálenosti
     * @param tMax horní mez vzdálenosti
     * @return true, když existuje libovolný průsečík
     */
    boolean intersectAny(Ray ray, double tMin, double tMax);
}
