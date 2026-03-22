# Renderovani

Tento dokument je aktualni technicky popis rendereru podle soucasne implementace v kodu.

## Aktualni prehled render rezimu

Rezimy odpovidaji enumu RenderMode:

| Rezim | Trida/pipeline | Typ pouziti |
| --- | --- | --- |
| MODEL | RasterRenderer (modelPreviewMode) | extremne lehky navigacni nahled |
| BASIC | RasterRenderer (unlit/flat profil) | rychly technicky nahled bez plne svetelne odezvy |
| PHONG | RasterRenderer + PhongShader | hlavni realtime viewport rezim |
| WIREFRAME | WireframeRenderer | topologie, siluety, skryte hrany |
| DITHERING | DitherRenderer (BLUE_NOISE/PATTERN/ASCII) | stylizovany post proces |
| TEMPORAL_NOISE | TemporalNoiseRenderer | regionalne rizeny casovy sum |
| HEX_MOSAIC | HexMosaicRenderer | bunecna stylizace do hex mrizky |
| RAY_TRACING | RayTracerRenderer | kvalitnejsi offline carrier tracing |
| PATH_TRACING | PathTracerRenderer | referencni Monte Carlo vystup |

## Matematicky zaklad spolecny rendererum

### Prostorove transformace

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

Normala se prevadi pres inverse-transpose cast modelove matice.

### Paprsek (ray/path)

$$
\mathbf{r}(t)=\mathbf{o}+t\mathbf{d}
$$

## Raster vetev (MODEL, BASIC, PHONG)

### Vypocetni logika

1. Frustum culling entity.
2. Prevod vrcholu do clip/NDC/screen prostoru.
3. Rasterizace trojuhelniku po dlazdicich.
4. Z-buffer test.
5. Fragment shading podle aktivniho profilu.

### Svetelny model PHONG

V PhongShader se pouziva ambient + Lambert + Blinn-Phong:

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

Pro bodova svetla se navic nasobi vzdalenostni a uhlove zeslabeni.

### Proc je takto navrzeny

- Je stabilni a rychly pro editor.
- Pouziva sdilenou evaluaci materialu, takze prechod na ray/path drzi stejny autorsky zdroj.
- Je to preview renderer, ne fyzikalne referencni integrator.

## WIREFRAME renderer

### Vypocetni logika

- Hrany se kresli z trojuhelniku po projekci do screen prostoru.
- Volitelne bezi depth test (depthHiddenLines).
- Jas hrany se moduluje vzdalenosti a siluetou.

### Matematika

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

### Proc je takto navrzeny

- Citelna topologie bez potreby plneho shadingu.
- Silueta zvyrazni tvar i na hustych modelech.
- Prerusovane hrany usnadni technicke debug pohledy.

## DITHERING renderer (BLUE_NOISE / PATTERN / ASCII)

### Vypocetni logika

1. Zakladni obraz pripravi interni RasterRenderer.
2. Pripravi se luminance a adaptivni kontrast.
3. Podle stylu se provede kvantizace pres threshold mapu nebo ASCII matching.

### Matematika

Luminance:

$$
Y = 0.2126R + 0.7152G + 0.0722B
$$

Kvantizace do N tonu:

$$
q = \frac{round\left(clamp(Y+\tau,0,1)(N-1)\right)}{N-1}
$$

kde $\tau$ je threshold z blue-noise nebo Bayer mapy.

ASCII rezim vybira glyph s minimalni chybou mezi blokem a bitmapou znaku:

$$
g^* = \arg\min_g \|B - G_g\|_2^2
$$

### Proc je takto navrzeny

- Blue-noise/pattern davaji rychlou stylizaci bez tezke geometrie.
- ASCII porovnava skutecny blok proti glyph bitmapam.
- Sdileny kontrastni pipeline drzi konzistentni vzhled napric styly.

## TEMPORAL_NOISE renderer

### Vypocetni logika

1. Z G-bufferu (objectId, faceId, depth, normal, worldPos) se vytvori regionalni motion parametry.
2. Pro region se spocita osovy smer, rychlost a faze.
3. Finalni obraz pouziva integer posuv stabilniho 2D zrna po bunecne mrizce.
4. Hrany se blenduji pres edgeMask, ne pres globalni blur.

### Matematika

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

Signal se kvantizuje do palety paletteLevels a mapuje na grayscale interval.

### Proc je takto navrzeny

- Je levnejsi nez ray/path, protoze re-useuje hotovy raster G-buffer.
- Integer posuv drzi zrno stabilni bez subpixel deformaci.
- Regionalni parametry zmensuji shimmering a rozpady na hranach.

## HEX_MOSAIC renderer

### Vypocetni logika

1. Vezme raster base frame.
2. Rozdeli pixely do hex bunek (axial koordinaty).
3. V kazde bunce akumuluje barvu + hloubku.
4. Kvantizuje luminanci, vrati barvu se stejnou energii.
5. Prida outline/edge styl a volitelny wow mod (classic, prism, neon).

### Matematika

Prevod pixelu do axial souradnic:

$$
q = \frac{\frac{\sqrt{3}}{3}x - \frac{1}{3}y}{r},\qquad
r_a = \frac{\frac{2}{3}y}{r}
$$

Luminance bunky:

$$
Y = 0.2126R + 0.7152G + 0.0722B
$$

Kvantizace:

$$
Y_q = \frac{round(clamp(Y,0,1)(L-1))}{L-1}
$$

Zachovani pomeru barev pres gain:

$$
gain = \frac{Y_q}{Y + \varepsilon},\qquad
\mathbf{c}_{out}=clamp(\mathbf{c}_{avg}\cdot gain)
$$

Hex edge sila ma v kodu explicitni nabeh od edgeStart = 0.86.

### Proc je takto navrzeny

- Bunecna akumulace dava citelny styl i pri pohybu kamery.
- Kvantizace pres luminanci drzi konzistentni kontrast.
- Depth-aware hrany zvyrazni strukturu bez slozitych segmentacnich passu.

## RAY_TRACING renderer

### Vypocetni logika

- BVH akcelerace pruseciku.
- Prime svetlo pres directional/point/area/emissive/environment vetve.
- Sekundarni paprsek pokracuje dominantni vetvi (odraz/prenos) do maxDepth.
- Duraz na stabilni offline preview s mensi stochastickou variabilitou.

### Matematika

Lokalni BRDF cast pouziva Lambert + GGX + Fresnel/clearcoat/sheen:

$$
L_o = L_d + L_{ggx}\cdot F + L_{clearcoat}\cdot F_c + L_{sheen}
$$

Fresnel (Schlick):

$$
F(\cos\theta)=F_0 + (1-F_0)(1-\cos\theta)^5
$$

Akumulace sekundarni vetve:

$$
L \leftarrow L + T\odot L_{local},\qquad
T \leftarrow T\odot w_{secondary}
$$

### Proc je takto navrzeny

- Oproti path traceru je mene nahodny a rychlejsi v interaktivnim preview.
- Drzi kvalitni interpretaci reflektivnich/transmisivnich materialu.
- Carrier profil umozni skalovat kvalitu pri pohybu bez vypnuti klicovych jevu.

## PATH_TRACING renderer

### Vypocetni logika

1. Monte Carlo integrace po bounce krocich do maxBounces.
2. Vetveni mezi transmission/specular/diffuse/clearcoat podle pravdepodobnosti.
3. Throughput se kompenzuje pres branch ratio.
4. Prime svetlo + environment/emissive sampling.
5. Russian roulette od vyssich bounce urovni.

### Matematika

Pravdepodobnosti vetvi (konceptualne):

$$
p_t = transmissionProbability,\quad
p_s = specProbability,\quad
p_d = 1 - p_t - p_s - p_c
$$

Throughput update:

$$
\mathbf{T}_{k+1}=\mathbf{T}_k \odot \frac{\mathbf{w}_{branch}}{p_{branch}}
$$

Primy prispevek:

$$
\mathbf{L} \leftarrow \mathbf{L} + \mathbf{T}\odot \mathbf{L}_{direct}
$$

Russian roulette (v implementaci od bounce >= 2):

$$
rr = clamp(\max(T_r,T_g,T_b),\ 0.05,\ 0.98)
$$

$$
\text{continue with prob. } rr,\qquad
\mathbf{T} \leftarrow \frac{\mathbf{T}}{rr}
$$

### Proc je takto navrzeny

- Je to referencni mod pro vernejsi svetelnou odezvu.
- Branch-PDF kompenzace drzi estimator bliz fyzikalne spravne integraci.
- Russian roulette zkracuje dlouhe drahy bez systematickeho biasu.

## Presnost vs. vykon: prakticke rozhodnuti

| Rezim | Fyzikalni vernost | Casova cena | Typicky ucel |
| --- | --- | --- | --- |
| MODEL, BASIC | nizka | velmi nizka | navigace, blokovani sceny |
| PHONG | stredni | nizka | bezna prace ve viewportu |
| WIREFRAME | nerelevantni (diagnosticky) | nizka | topologie a silueta |
| DITHERING, TEMPORAL_NOISE, HEX_MOSAIC | stylizovana, ne-fyzikalni | stredni | vytvarny look |
| RAY_TRACING | vyssi | vysoka | kvalitni offline preview |
| PATH_TRACING | nejvyssi v projektu | nejvyssi | finalni referencni vystup |

## Shrnuti

- Prehled rendereru je aktualizovany na vsechny soucasne rezimy.
- Dokument obsahuje samostatnou matematickou sekci a vzorce pro kazdy renderer.
- U kazdeho rendereru je explicitne popsana logika i duvod navrhu.
