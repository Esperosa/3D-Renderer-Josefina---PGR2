package engine.render.ray.core;

import engine.math.Ray;

/**
 * Tady definuju rozhranÃ­ pro prostorovÃ© akceleraÄnÃ­ struktury pouÅ¾Ã­vanÃ© v ray tracingu.
 * Jako hlavnÃ­ implementaci v praxi pouÅ¾Ã­vÃ¡m BVH.
 */
public interface AccelerationStructure {

    /**
     * Tady strukturu sestavÃ­m nebo znovu pÅ™estavÃ­m ze vÅ¡ech scÃ©novÃ½ch meshÃ­.
     *
     * @param positions sem pÅ™edÃ¡m pole pozic pro jednotlivÃ© meshe
     * @param indices sem pÅ™edÃ¡m indexovÃ¡ pole pro jednotlivÃ© meshe
     * @param modelMats sem pÅ™edÃ¡m matice jednotlivÃ½ch meshÃ­ ve svÄ›tovÃ©m prostoru jako plochÃ© double[16]
     * @param meshCount sem pÅ™edÃ¡m poÄet meshÃ­
     */
    void build(float[][] positions, int[][] indices, double[][] modelMats, int meshCount);

    /**
     * Tady najdu nejbliÅ¾Å¡Ã­ prÅ¯seÄÃ­k paprsku se scÃ©nou.
     *
     * @param ray sem pÅ™edÃ¡m testovanÃ½ paprsek
     * @param tMin sem pÅ™edÃ¡m minimÃ¡lnÃ­ parametrickou vzdÃ¡lenost
     * @param tMax sem pÅ™edÃ¡m maximÃ¡lnÃ­ parametrickou vzdÃ¡lenost
     * @param record sem pÅ™edÃ¡m vÃ½stupnÃ­ hit record, kterÃ½ naplnÃ­m pÅ™i zÃ¡sahu
     * @return tÃ­m vrÃ¡tÃ­m true, kdyÅ¾ najdu prÅ¯seÄÃ­k
     */
    boolean intersect(Ray ray, double tMin, double tMax, HitRecord record);

    /**
     * Tady otestuju, jestli paprsek zakrÃ½vÃ¡ nÄ›jakÃ¡ geometrie, typicky pro stÃ­novÃ© paprsky.
     * MÅ¯Å¾u skonÄit dÅ™Ã­v bez dopoÄtu plnÃ©ho hit recordu.
     *
     * @param ray sem pÅ™edÃ¡m stÃ­novÃ½ paprsek
     * @param tMin sem pÅ™edÃ¡m minimÃ¡lnÃ­ vzdÃ¡lenost
     * @param tMax sem pÅ™edÃ¡m maximÃ¡lnÃ­ vzdÃ¡lenost
     * @return tÃ­m vrÃ¡tÃ­m true, kdyÅ¾ existuje libovolnÃ½ prÅ¯seÄÃ­k
     */
    boolean intersectAny(Ray ray, double tMin, double tMax);
}

