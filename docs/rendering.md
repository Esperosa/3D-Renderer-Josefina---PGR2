# RenderovÃ¡nÃ­

Tento dokument je aktuÃ¡lnÃ­ technickÃ½ popis rendererÅ¯ podle souÄasnÃ© implementace v kÃ³du.

## AktuÃ¡lnÃ­ pÅ™ehled render reÅ¾imÅ¯

ReÅ¾imy odpovÃ­dajÃ­ enumu `RenderMode`:

| ReÅ¾im | TÅ™Ã­da/pipeline | Typ pouÅ¾itÃ­ |
| --- | --- | --- |
| `MODEL` | `RasterRenderer` (`modelPreviewMode`) | extrÃ©mnÄ› lehkÃ½ navigaÄnÃ­ nÃ¡hled |
| `BASIC` | `RasterRenderer` (`unlit/flat` profil) | rychlÃ½ technickÃ½ nÃ¡hled bez plnÃ© svÄ›telnÃ© odezvy |
| `PHONG` | `RasterRenderer` + `PhongShader` | hlavnÃ­ realtime viewport reÅ¾im |
| `WIREFRAME` | `WireframeRenderer` | topologie, siluety, skrytÃ© hrany |
| `DITHERING` | `DitherRenderer` (BLUE_NOISE/PATTERN/ASCII) | stylizovanÃ½ post proces |
| `TEMPORAL_NOISE` | `TemporalNoiseRenderer` | regionÃ¡lnÄ› Å™Ã­zenÃ½ ÄasovÃ½ Å¡um |
| `HEX_MOSAIC` | `HexMosaicRenderer` | bunÄ›ÄnÃ¡ stylizace do hex mÅ™Ã­Å¾ky |
| `RAY_TRACING` | `RayTracerRenderer` | kvalitnÄ›jÅ¡Ã­ offline carrier tracing |
| `PATH_TRACING` | `PathTracerRenderer` | referenÄnÃ­ Monte Carlo vÃ½stup |

## MatematickÃ½ zÃ¡klad spoleÄnÃ½ rendererÅ¯m

### ProstorovÃ© transformace

$$
\mathbf{p}_{world} = M\,\begin{bmatrix}x\\y\\z\\1\end{bmatrix},\qquad
\mathbf{p}_{clip} = VP\,\mathbf{p}_{world}
$$

$$
\mathbf{p}_{ndc} = \frac{\mathbf{p}_{clip.xyz}}{w_{clip}}
$$

$$
x_{screen}=\left(\frac{x_{ndc}}{2}+\frac{1}{2}\right)(W-1),\quad
y_{screen}=\left(1-\left(\frac{y_{ndc}}{2}+\frac{1}{2}\right)\right)(H-1)
$$

NormÃ¡la se pÅ™evÃ¡dÃ­ pÅ™es inverse-transpose ÄÃ¡st modelovÃ© matice.

### Paprsek (ray/path)

$$
\mathbf{r}(t)=\mathbf{o}+t\mathbf{d}
$$

## Raster vÄ›tev (`MODEL`, `BASIC`, `PHONG`)

### VÃ½poÄetnÃ­ logika

1. Frustum culling entity.
2. PÅ™evod vrcholÅ¯ do clip/NDC/screen prostoru.
3. Rasterizace trojÃºhelnÃ­kÅ¯ po dlaÅ¾dicÃ­ch.
4. Z-buffer test.
5. Fragment shading podle aktivnÃ­ho profilu.

### SvÄ›telnÃ½ model `PHONG`

V `PhongShader` se pouÅ¾Ã­vÃ¡ ambient + Lambert + Blinn-Phong:

$$
L = L_{ambient} + \sum_{lights}\left(L_d + L_s\right)
$$

$$
L_d \propto \max(0,\mathbf{n}\cdot\mathbf{l})
$$

$$
\mathbf{h}=\frac{\mathbf{l}+\mathbf{v}}{\|\mathbf{l}+\mathbf{v}\|},\qquad
L_s \propto \max(0,\mathbf{n}\cdot\mathbf{h})^{shininess}
$$

Pro bodovÃ¡ svÄ›tla se navÃ­c nÃ¡sobÃ­ vzdÃ¡lenostnÃ­ a ÃºhlovÃ© zeslabenÃ­ (`attenuation`, `angularAttenuation`).

### ProÄ je takto navrÅ¾enÃ½

- Je stabilnÃ­ a rychlÃ½ pro editor.
- PouÅ¾Ã­vÃ¡ sdÃ­lenou evaluaci materiÃ¡lu, takÅ¾e pÅ™echod na ray/path drÅ¾Ã­ stejnÃ½ autorskÃ½ zdroj.
- Je explicitnÄ› preview renderer, ne fyzikÃ¡lnÄ› referenÄnÃ­ integrÃ¡tor.

## `WIREFRAME` renderer

### VÃ½poÄetnÃ­ logika

- Hrany se kreslÃ­ z trojÃºhelnÃ­kÅ¯ po projekci do screen prostoru.
- VolitelnÄ› bÄ›Å¾Ã­ depth test (`depthHiddenLines`).
- Jas hrany se moduluje vzdÃ¡lenostÃ­ a siluetou.

### Matematika

V implementaci endpoint barvy:

$$
t = clamp01\left(inverseLerp(d_{near}, d_{far}, d)\right)
$$

$$
brightness = lerp(maxBrightness,\ minBrightness,\ t)
$$

$$
silhouette = 1-|\mathbf{n}\cdot\mathbf{v}|,
\quad emphasis = 1 + silhouette\cdot silhouetteBoost
$$

$$
\mathbf{c}_{edge}=\mathbf{base}\cdot brightness\cdot emphasis
$$

### ProÄ je takto navrÅ¾enÃ½

- ÄŒitelnÃ¡ topologie bez potÅ™eby plnÃ©ho shadingu.
- Silueta zvÃ½raznÃ­ tvar i na hustÃ½ch modelech.
- PÅ™eruÅ¡ovanÃ© hrany usnadnÃ­ technickÃ© debug pohledy.

## `DITHERING` renderer (BLUE_NOISE / PATTERN / ASCII)

### VÃ½poÄetnÃ­ logika

1. ZÃ¡kladnÃ­ obraz pÅ™ipravÃ­ internÃ­ `RasterRenderer`.
2. PÅ™ipravÃ­ se luminance a adaptivnÃ­ kontrast.
3. Podle stylu se provede kvantizace pÅ™es threshold mapu nebo ASCII matching.

### Matematika

Luminance:

$$
Y = 0.2126R + 0.7152G + 0.0722B
$$

Kvantizace do `N` tÃ³nÅ¯ (obecnÃ½ tvar):

$$
q = \frac{round\left(clamp(Y+\tau,0,1)(N-1)\right)}{N-1}
$$

kde $\tau$ je threshold z blue-noise nebo Bayer mapy.

ASCII reÅ¾im vybÃ­rÃ¡ glyph s minimÃ¡lnÃ­ chybou mezi blokem a bitmapou znaku:

$$
g^* = \arg\min_g \|B - G_g\|_2^2
$$

### ProÄ je takto navrÅ¾enÃ½

- Blue-noise/pattern dÃ¡vajÃ­ rychlou stylizaci bez tÄ›Å¾kÃ© geometrie.
- ASCII porovnÃ¡vÃ¡ skuteÄnÃ½ blok proti glyph bitmapÃ¡m, takÅ¾e vÃ½stup nenÃ­ jen prahovÃ¡nÃ­ textu.
- SdÃ­lenÃ½ kontrastnÃ­ pipeline drÅ¾Ã­ konzistentnÃ­ vzhled napÅ™Ã­Ä styly.

## `TEMPORAL_NOISE` renderer

### VÃ½poÄetnÃ­ logika

1. Z G-bufferu (`objectId`, `faceId`, `depth`, `normal`, `worldPos`) se vytvoÅ™Ã­ regionÃ¡lnÃ­ motion parametry.
2. Pro region se spoÄÃ­tÃ¡ osovÃ½ smÄ›r, rychlost a fÃ¡ze.
3. FinÃ¡lnÃ­ obraz pouÅ¾Ã­vÃ¡ integer posuv stabilnÃ­ho 2D zrna po bunÄ›ÄnÃ© mÅ™Ã­Å¾ce.
4. Hrany se blendujÃ­ pÅ™es `edgeMask`, ne pÅ™es globÃ¡lnÃ­ blur.

### Matematika (podle implementaÄnÃ­ch vztahÅ¯)

$$
grazing = 1 - facing
$$

$$
speed \approx
0.45 + 1.20\left(
0.90\,depthNearContribution\,depthNear +
1.02\,grazingContribution\,grazing +
0.46\,orientationContrast +
c\,perspectiveContrast
\right)+regionBias
$$

$$
shift(t)=\left\lfloor t\cdot temporalTickRate\cdot speed + phase \right\rfloor
$$

$$
signal = 0.72\,a + 0.28\,b
$$

Signal se kvantizuje do palety (`paletteLevels`) a mapuje na grayscale interval.

### ProÄ je takto navrÅ¾enÃ½

- Je levnÄ›jÅ¡Ã­ neÅ¾ ray/path, protoÅ¾e re-useuje hotovÃ½ raster G-buffer.
- Integer posuv drÅ¾Ã­ zrno stabilnÃ­ bez subpixel deformacÃ­.
- RegionÃ¡lnÃ­ parametry zmenÅ¡ujÃ­ shimmering a rozpady na hranÃ¡ch.

## `HEX_MOSAIC` renderer

### VÃ½poÄetnÃ­ logika

1. Vezme raster base frame.
2. RozdÄ›lÃ­ pixely do hex bunÄ›k (axial koordinÃ¡ty).
3. V kaÅ¾dÃ© buÅˆce akumuluje barvu + hloubku.
4. Kvantizuje luminanci, vrÃ¡tÃ­ barvu se stejnou energiÃ­.
5. PÅ™idÃ¡ outline/edge styl a volitelnÃ½ wow mÃ³d (`classic`, `prism`, `neon`).

### Matematika

PÅ™evod pixelu do axial souÅ™adnic:

$$
q = \frac{\frac{\sqrt{3}}{3}x - \frac{1}{3}y}{r},\qquad
r_a = \frac{\frac{2}{3}y}{r}
$$

Luminance buÅˆky:

$$
Y = 0.2126R + 0.7152G + 0.0722B
$$

Kvantizace:

$$
Y_q = \frac{round(clamp(Y,0,1)(L-1))}{L-1}
$$

ZachovÃ¡nÃ­ pomÄ›ru barev pÅ™es gain:

$$
gain = \frac{Y_q}{Y + \varepsilon},\qquad
\mathbf{c}_{out}=clamp(\mathbf{c}_{avg}\cdot gain)
$$

Hex edge sÃ­la mÃ¡ v kÃ³du explicitnÃ­ nÃ¡bÄ›h od `edgeStart = 0.86`.

### ProÄ je takto navrÅ¾enÃ½

- BunÄ›ÄnÃ¡ akumulace dÃ¡vÃ¡ ÄitelnÃ½ styl i pÅ™i pohybu kamery.
- Kvantizace pÅ™es luminanci drÅ¾Ã­ konzistentnÃ­ kontrast.
- Depth-aware hrany zvÃ½raznÃ­ strukturu bez nutnosti sloÅ¾itÃ½ch segmentaÄnÃ­ch passÅ¯.

## `RAY_TRACING` renderer

### VÃ½poÄetnÃ­ logika

- BVH akcelerace prÅ¯seÄÃ­kÅ¯.
- PÅ™Ã­mÃ© svÄ›tlo pÅ™es directional/point/area/emissive/environment vÄ›tve.
- SekundÃ¡rnÃ­ paprsek pokraÄuje dominantnÃ­ vÄ›tvÃ­ (odraz/pÅ™enos) do `maxDepth`.
- DÅ¯raz na stabilnÃ­ offline preview s menÅ¡Ã­ stochastickou variabilitou.

### Matematika

LokÃ¡lnÃ­ BRDF ÄÃ¡st pouÅ¾Ã­vÃ¡ Lambert + GGX + Fresnel/clearcoat/sheen:

$$
L_o = L_d + L_{ggx}\cdot F + L_{clearcoat}\cdot F_c + L_{sheen}
$$

Fresnel (Schlick):

$$
F(\cos\theta)=F_0 + (1-F_0)(1-\cos\theta)^5
$$

Akumulace sekundÃ¡rnÃ­ vÄ›tve:

$$
L \leftarrow L + T\odot L_{local},\qquad
T \leftarrow T\odot w_{secondary}
$$

### ProÄ je takto navrÅ¾enÃ½

- Oproti path traceru je mÃ©nÄ› nÃ¡hodnÃ½ a rychlejÅ¡Ã­ v interaktivnÃ­m preview.
- UdrÅ¾uje kvalitnÃ­ interpretaci reflektivnÃ­ch/transmisivnÃ­ch materiÃ¡lÅ¯.
- Carrier profil dovoluje Å¡kÃ¡lovat kvalitu pÅ™i pohybu bez ÃºplnÃ©ho vypnutÃ­ klÃ­ÄovÃ½ch jevÅ¯.

## `PATH_TRACING` renderer

### VÃ½poÄetnÃ­ logika

1. Monte Carlo integrace po bounce krocÃ­ch do `maxBounces`.
2. VÄ›tvenÃ­ mezi transmission/specular/diffuse/clearcoat podle pravdÄ›podobnostÃ­.
3. Throughput se kompenzuje pÅ™es branch ratio.
4. PÅ™Ã­mÃ© svÄ›tlo + environment/emissive sampling.
5. Russian roulette od vyÅ¡Å¡Ã­ch bounce ÃºrovnÃ­.

### Matematika

PravdÄ›podobnosti vÄ›tvÃ­ (konceptuÃ¡lnÄ›):

$$
p_t = transmissionProbability,\quad
p_s = specProbability,\quad
p_d = 1 - p_t - p_s - p_c
$$

Throughput update:

$$
\mathbf{T}_{k+1}=\mathbf{T}_k \odot \frac{\mathbf{w}_{branch}}{p_{branch}}
$$

PÅ™Ã­mÃ½ pÅ™Ã­spÄ›vek:

$$
\mathbf{L} \leftarrow \mathbf{L} + \mathbf{T}\odot \mathbf{L}_{direct}
$$

Russian roulette (v implementaci od `bounce >= 2`):

$$
rr = clamp(\max(T_r,T_g,T_b),\ 0.05,\ 0.98)
$$

$$
\text{continue with prob. } rr,\qquad
\mathbf{T} \leftarrow \frac{\mathbf{T}}{rr}
$$

### ProÄ je takto navrÅ¾enÃ½

- Je to referenÄnÃ­ mÃ³d pro vÄ›rnÄ›jÅ¡Ã­ svÄ›telnou odezvu.
- Branch-PDF kompenzace drÅ¾Ã­ estimator blÃ­Å¾ fyzikÃ¡lnÄ› sprÃ¡vnÃ© integraci.
- Russian roulette zkracuje dlouhÃ© drÃ¡hy bez systematickÃ©ho biasu.

## PÅ™esnost vs. vÃ½kon: praktickÃ© rozhodnutÃ­

| ReÅ¾im | FyzikÃ¡lnÃ­ vÄ›rnost | ÄŒasovÃ¡ cena | TypickÃ½ ÃºÄel |
| --- | --- | --- | --- |
| `MODEL`, `BASIC` | nÃ­zkÃ¡ | velmi nÃ­zkÃ¡ | navigace, blokovÃ¡nÃ­ scÃ©ny |
| `PHONG` | stÅ™ednÃ­ | nÃ­zkÃ¡ | bÄ›Å¾nÃ¡ prÃ¡ce ve viewportu |
| `WIREFRAME` | nerelevantnÃ­ (diagnostickÃ½) | nÃ­zkÃ¡ | topologie a silueta |
| `DITHERING`, `TEMPORAL_NOISE`, `HEX_MOSAIC` | stylizovanÃ¡, ne-fyzikÃ¡lnÃ­ | stÅ™ednÃ­ | vÃ½tvarnÃ½ look |
| `RAY_TRACING` | vyÅ¡Å¡Ã­ | vysokÃ¡ | kvalitnÃ­ offline preview |
| `PATH_TRACING` | nejvyÅ¡Å¡Ã­ v projektu | nejvyÅ¡Å¡Ã­ | finÃ¡lnÃ­ referenÄnÃ­ vÃ½stup |

## ShrnutÃ­

- â€žPÅ™ehled renderÅ¯â€œ je zde aktualizovanÃ½ na vÅ¡echny souÄasnÃ© reÅ¾imy.
- Dokument obsahuje samostatnou matematickou sekci a vzorce pro kaÅ¾dÃ½ renderer.
- U kaÅ¾dÃ©ho rendereru je explicitnÄ› popsanÃ¡ logika i dÅ¯vod nÃ¡vrhu (proÄ je implementovanÃ½ prÃ¡vÄ› takto).


