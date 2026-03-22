package engine.render.ray.bvh;

import engine.math.Ray;

/**
 * Spatial acceleration structure contract used by ray and path tracing renderers.
 * The default implementation in this module is BVH.
 */
public interface AccelerationStructure {

 /**
 * Builds or rebuilds the structure from scene mesh data.
 *
 * @param positions per-mesh vertex position arrays
 * @param indices per-mesh triangle index arrays
 * @param modelMats per-mesh world transforms in flattened double[16] form
 * @param meshCount number of meshes to process
 */
    void build(float[][] positions, int[][] indices, double[][] modelMats, int meshCount);

 /**
 * Finds the nearest hit between the ray and scene geometry.
 *
 * @param ray tested ray
 * @param tMin lower parametric bound
 * @param tMax upper parametric bound
 * @param record output hit record populated on hit
 * @return true when an intersection is found
 */
    boolean intersect(Ray ray, double tMin, double tMax, HitRecord record);

 /**
 * Tests whether any geometry occludes the ray, typically for shadow rays.
 * Implementations may early-out without producing a full hit record.
 *
 * @param ray tested shadow ray
 * @param tMin lower distance bound
 * @param tMax upper distance bound
 * @return true when any intersection exists
 */
    boolean intersectAny(Ray ray, double tMin, double tMax);
}
