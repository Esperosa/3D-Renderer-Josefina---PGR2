package engine.render.raster;

import engine.math.Vec3;
import engine.scene.DirectionalLight;
import engine.scene.Light;
import engine.scene.PointLight;

/**
 * Represents výpočet nasvícení typu Phong / Blinn-Phong.
 * Metody nechávám bezstavové a volám je po fragmentech z rasterizačního potrubí.
 */
public class PhongShader {

    private Vec3 ambientColor;
    private double ambientIntensity;
    private Light[] lights;
    private double[] dirLightX;
    private double[] dirLightY;
    private double[] dirLightZ;
    private double[] dirLightR;
    private double[] dirLightG;
    private double[] dirLightB;
    private double[] pointPosX;
    private double[] pointPosY;
    private double[] pointPosZ;
    private double[] pointLightR;
    private double[] pointLightG;
    private double[] pointLightB;
    private PointLight[] pointLightRefs;
    private int dirCount;
    private int pointCount;
    private boolean previewDiffuseOnlyLighting;
    private boolean previewPointLightsDiffuseOnly;

    public PhongShader() {
        this.ambientColor = new Vec3(0.1, 0.1, 0.1);
        this.ambientIntensity = 1.0;
        this.lights = new Light[0];
        this.dirLightX = new double[0];
        this.dirLightY = new double[0];
        this.dirLightZ = new double[0];
        this.dirLightR = new double[0];
        this.dirLightG = new double[0];
        this.dirLightB = new double[0];
        this.pointPosX = new double[0];
        this.pointPosY = new double[0];
        this.pointPosZ = new double[0];
        this.pointLightR = new double[0];
        this.pointLightG = new double[0];
        this.pointLightB = new double[0];
        this.pointLightRefs = new PointLight[0];
        this.dirCount = 0;
        this.pointCount = 0;
        this.previewDiffuseOnlyLighting = false;
        this.previewPointLightsDiffuseOnly = false;
    }

 /**
 * shader nastavím pro aktuální snímek.
 *
 * @param ambientColor globální ambientní barvu
 * @param ambientIntensity sílu ambientu
 * @param lights světla aktivní v tomto framu
 */
    public void setup(Vec3 ambientColor, double ambientIntensity, Light[] lights) {
        this.ambientColor = ambientColor == null ? new Vec3(0.1, 0.1, 0.1) : ambientColor;
        this.ambientIntensity = ambientIntensity;
        this.lights = lights == null ? new Light[0] : lights;
        rebuildLightCache();
    }

    public void setPreviewProfile(boolean diffuseOnlyLighting, boolean pointLightsDiffuseOnly) {
        this.previewDiffuseOnlyLighting = diffuseOnlyLighting;
        this.previewPointLightsDiffuseOnly = pointLightsDiffuseOnly;
    }

 /**
 * spočítá Blinn-Phong nasvícení v bodě povrchu.
 *
 * @param worldPos pozici povrchu
 * @param worldNormal jednotkovou normálu povrchu
 * @param viewDir jednotkový směr z povrchu ke kameře
 * @param diffuse difuzní barvu materiálu
 * @param specular spekulární barvu materiálu
 * @param shininess exponent odlesku
 * @return tím vrátí finální nasvícenou barvu jako zabalené ARGB
 */
    public int shade(Vec3 worldPos, Vec3 worldNormal, Vec3 viewDir,
                     Vec3 diffuse, Vec3 specular, double shininess) {
        Vec3 n = worldNormal.normalize();
        Vec3 v = viewDir.normalize();
        Vec3 color = new Vec3(
                ambientColor.x * ambientIntensity * diffuse.x,
                ambientColor.y * ambientIntensity * diffuse.y,
                ambientColor.z * ambientIntensity * diffuse.z
        );

        for (Light light : lights) {
            if (light == null || !light.isEnabled()) {
                continue;
            }
            Vec3 l = light.directionFrom(worldPos).normalize();
            double ndotl = Math.max(0.0, n.dot(l));
            if (ndotl <= 0.0) {
                continue;
            }

            double distance = 1.0;
            double angular = 1.0;
            if (light instanceof PointLight) {
                PointLight pl = (PointLight) light;
                distance = pl.getPosition().sub(worldPos).length();
                angular = pl.angularAttenuation(worldPos);
            }
            double att = light.attenuation(distance) * light.getIntensity() * angular;
            if (att <= 0.0) {
                continue;
            }
            Vec3 lc = light.getColor().mul(att);
            Vec3 diffuseTerm = new Vec3(
                    diffuse.x * lc.x * ndotl,
                    diffuse.y * lc.y * ndotl,
                    diffuse.z * lc.z * ndotl
            );

            Vec3 h = l.add(v).normalize();
            double ndoth = Math.max(0.0, n.dot(h));
            double specPower = Math.pow(ndoth, Math.max(1.0, shininess));
            Vec3 specularTerm = new Vec3(
                    specular.x * lc.x * specPower,
                    specular.y * lc.y * specPower,
                    specular.z * lc.z * specPower
            );

            color = color.add(diffuseTerm).add(specularTerm);
        }

        int r = (int) (Math.max(0.0, Math.min(1.0, color.x)) * 255.0 + 0.5);
        int g = (int) (Math.max(0.0, Math.min(1.0, color.y)) * 255.0 + 0.5);
        int b = (int) (Math.max(0.0, Math.min(1.0, color.z)) * 255.0 + 0.5);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public int shadeLambert(Vec3 worldPos, Vec3 worldNormal, Vec3 diffuse) {
        return shade(worldPos, worldNormal, new Vec3(0.0, 0.0, 1.0), diffuse, Vec3.ZERO, 1.0);
    }

    public int shadeFlat(Vec3 color) {
        int rgb = color.toIntRGB();
        return 0xFF000000 | rgb;
    }

 /**
 * Represents Phong shading cestu bez dalších alokací pro software rasterizer.
 */
    public int shadeFast(
            float wx, float wy, float wz,
            float nx, float ny, float nz,
            double viewX, double viewY, double viewZ,
            double diffuseR, double diffuseG, double diffuseB,
            double specR, double specG, double specB,
            double shininess
    ) {
        double nLen2 = nx * nx + ny * ny + nz * nz;
        if (nLen2 < 1e-20) {
            nx = 0.0f;
            ny = 1.0f;
            nz = 0.0f;
        } else {
            double invN = 1.0 / Math.sqrt(nLen2);
            nx *= invN;
            ny *= invN;
            nz *= invN;
        }

        double vLen2 = viewX * viewX + viewY * viewY + viewZ * viewZ;
        if (vLen2 < 1e-20) {
            viewX = 0.0;
            viewY = 0.0;
            viewZ = 1.0;
        } else {
            double invV = 1.0 / Math.sqrt(vLen2);
            viewX *= invV;
            viewY *= invV;
            viewZ *= invV;
        }

        double outR = ambientColor.x * ambientIntensity * diffuseR;
        double outG = ambientColor.y * ambientIntensity * diffuseG;
        double outB = ambientColor.z * ambientIntensity * diffuseB;

        for (int i = 0; i < dirCount; i++) {
            double lx = dirLightX[i];
            double ly = dirLightY[i];
            double lz = dirLightZ[i];

            double ndotl = nx * lx + ny * ly + nz * lz;
            if (ndotl <= 0.0) {
                continue;
            }

            double lr = dirLightR[i];
            double lg = dirLightG[i];
            double lb = dirLightB[i];

            outR += diffuseR * lr * ndotl;
            outG += diffuseG * lg * ndotl;
            outB += diffuseB * lb * ndotl;

            if (!previewDiffuseOnlyLighting) {
                double hx = lx + viewX;
                double hy = ly + viewY;
                double hz = lz + viewZ;
                double hLen2 = hx * hx + hy * hy + hz * hz;
                if (hLen2 > 1e-20) {
                    double invH = 1.0 / Math.sqrt(hLen2);
                    hx *= invH;
                    hy *= invH;
                    hz *= invH;
                    double ndoth = nx * hx + ny * hy + nz * hz;
                    if (ndoth > 0.0) {
                        double specPower = Math.pow(ndoth, Math.max(1.0, shininess));
                        outR += specR * lr * specPower;
                        outG += specG * lg * specPower;
                        outB += specB * lb * specPower;
                    }
                }
            }
        }

        for (int i = 0; i < pointCount; i++) {
            double lx = pointPosX[i] - wx;
            double ly = pointPosY[i] - wy;
            double lz = pointPosZ[i] - wz;
            double dist2 = lx * lx + ly * ly + lz * lz;
            if (dist2 < 1e-20) {
                continue;
            }
            double dist = Math.sqrt(dist2);
            double invDist = 1.0 / dist;
            lx *= invDist;
            ly *= invDist;
            lz *= invDist;

            double ndotl = nx * lx + ny * ly + nz * lz;
            if (ndotl <= 0.0) {
                continue;
            }

            double att = pointLightRefs[i].attenuation(dist)
 * pointLightRefs[i].angularAttenuation(wx, wy, wz);
            if (att <= 1e-12) {
                continue;
            }
            double lr = pointLightR[i] * att;
            double lg = pointLightG[i] * att;
            double lb = pointLightB[i] * att;

            outR += diffuseR * lr * ndotl;
            outG += diffuseG * lg * ndotl;
            outB += diffuseB * lb * ndotl;

            if (!(previewDiffuseOnlyLighting || previewPointLightsDiffuseOnly)) {
                double hx = lx + viewX;
                double hy = ly + viewY;
                double hz = lz + viewZ;
                double hLen2 = hx * hx + hy * hy + hz * hz;
                if (hLen2 > 1e-20) {
                    double invH = 1.0 / Math.sqrt(hLen2);
                    hx *= invH;
                    hy *= invH;
                    hz *= invH;
                    double ndoth = nx * hx + ny * hy + nz * hz;
                    if (ndoth > 0.0) {
                        double specPower = Math.pow(ndoth, Math.max(1.0, shininess));
                        outR += specR * lr * specPower;
                        outG += specG * lg * specPower;
                        outB += specB * lb * specPower;
                    }
                }
            }
        }

        int r = (int) (Math.max(0.0, Math.min(1.0, outR)) * 255.0 + 0.5);
        int g = (int) (Math.max(0.0, Math.min(1.0, outG)) * 255.0 + 0.5);
        int b = (int) (Math.max(0.0, Math.min(1.0, outB)) * 255.0 + 0.5);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void rebuildLightCache() {
        int dirTotal = 0;
        int pointTotal = 0;
        for (Light light : lights) {
            if (light == null || !light.isEnabled()) {
                continue;
            }
            if (light instanceof DirectionalLight) {
                dirTotal++;
            } else if (light instanceof PointLight) {
                pointTotal++;
            }
        }

        ensureDirCapacity(dirTotal);
        ensurePointCapacity(pointTotal);
        dirCount = 0;
        pointCount = 0;

        for (Light light : lights) {
            if (light == null || !light.isEnabled()) {
                continue;
            }

            Vec3 color = light.getColor();
            double lr = color.x * light.getIntensity();
            double lg = color.y * light.getIntensity();
            double lb = color.z * light.getIntensity();

            if (light instanceof DirectionalLight) {
                DirectionalLight dl = (DirectionalLight) light;
                Vec3 d = dl.getDirection();
                dirLightX[dirCount] = -d.x;
                dirLightY[dirCount] = -d.y;
                dirLightZ[dirCount] = -d.z;
                dirLightR[dirCount] = lr;
                dirLightG[dirCount] = lg;
                dirLightB[dirCount] = lb;
                dirCount++;
                continue;
            }

            if (light instanceof PointLight) {
                PointLight pl = (PointLight) light;
                Vec3 p = pl.getPosition();
                pointPosX[pointCount] = p.x;
                pointPosY[pointCount] = p.y;
                pointPosZ[pointCount] = p.z;
                pointLightR[pointCount] = lr;
                pointLightG[pointCount] = lg;
                pointLightB[pointCount] = lb;
                pointLightRefs[pointCount] = pl;
                pointCount++;
            }
        }
    }

    private void ensureDirCapacity(int size) {
        if (dirLightX.length >= size) {
            return;
        }
        dirLightX = new double[size];
        dirLightY = new double[size];
        dirLightZ = new double[size];
        dirLightR = new double[size];
        dirLightG = new double[size];
        dirLightB = new double[size];
    }

    private void ensurePointCapacity(int size) {
        if (pointPosX.length >= size) {
            return;
        }
        pointPosX = new double[size];
        pointPosY = new double[size];
        pointPosZ = new double[size];
        pointLightR = new double[size];
        pointLightG = new double[size];
        pointLightB = new double[size];
        pointLightRefs = new PointLight[size];
    }
}