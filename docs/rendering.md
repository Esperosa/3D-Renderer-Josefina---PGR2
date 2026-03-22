# Renderování

Tento dokument je aktuální technický popis rendererů podle současné implementace v kódu.

## Aktuální přehled render režimů

Režimy odpovídají enumu `RenderMode`:

| Režim | Třída/pipeline | Typ použití |
| --- | --- | --- |
| `MODEL` | `RasterRenderer` (`modelPreviewMode`) | extrémně lehký navigační náhled |
| `BASIC` | `RasterRenderer` (`unlit/flat` profil) | rychlý technický náhled bez plné světelné odezvy |
| `PHONG` | `RasterRenderer` + `PhongShader` | hlavní realtime viewport režim |
| `WIREFRAME` | `WireframeRenderer` | topologie, siluety, skryté hrany |
| `DITHERING` | `DitherRenderer` (BLUE_NOISE/PATTERN/ASCII) | stylizovaný post proces |
| `TEMPORAL_NOISE` | `TemporalNoiseRenderer` | regionálně řízený časový šum |
| `HEX_MOSAIC` | `HexMosaicRenderer` | buněčná stylizace do hex mřížky |
| `RAY_TRACING` | `RayTracerRenderer` | kvalitnější offline carrier tracing |
| `PATH_TRACING` | `PathTracerRenderer` | referenční Monte Carlo výstup |

## Matematický základ společný rendererům

### Prostorové transformace

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

Normála se převádí přes inverse-transpose část modelové matice.

### Paprsek (ray/path)

$$
\mathbf{r}(t)=\mathbf{o}+t\mathbf{d}
$$

## Raster větev (`MODEL`, `BASIC`, `PHONG`)

### Výpočetní logika

1. Frustum culling entity.
2. Převod vrcholů do clip/NDC/screen prostoru.
3. Rasterizace trojúhelníků po dlaždicích.
4. Z-buffer test.
5. Fragment shading podle aktivního profilu.

### Světelný model `PHONG`

V `PhongShader` se používá ambient + Lambert + Blinn-Phong:

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

Pro bodová světla se navíc násobí vzdálenostní a úhlové zeslabení (`attenuation`, `angularAttenuation`).

### Proč je takto navržený

- Je stabilní a rychlý pro editor.
- Používá sdílenou evaluaci materiálu, takže přechod na ray/path drží stejný autorský zdroj.
- Je explicitně preview renderer, ne fyzikálně referenční integrátor.

## `WIREFRAME` renderer

### Výpočetní logika

- Hrany se kreslí z trojúhelníků po projekci do screen prostoru.
- Volitelně běží depth test (`depthHiddenLines`).
- Jas hrany se moduluje vzdáleností a siluetou.

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

### Proč je takto navržený

- Čitelná topologie bez potřeby plného shadingu.
- Silueta zvýrazní tvar i na hustých modelech.
- Přerušované hrany usnadní technické debug pohledy.

## `DITHERING` renderer (BLUE_NOISE / PATTERN / ASCII)

### Výpočetní logika

1. Základní obraz připraví interní `RasterRenderer`.
2. Připraví se luminance a adaptivní kontrast.
3. Podle stylu se provede kvantizace přes threshold mapu nebo ASCII matching.

### Matematika

Luminance:

$$
Y = 0.2126R + 0.7152G + 0.0722B
$$

Kvantizace do `N` tónů (obecný tvar):

$$
q = \frac{round\left(clamp(Y+\tau,0,1)(N-1)\right)}{N-1}
$$

kde $\tau$ je threshold z blue-noise nebo Bayer mapy.

ASCII režim vybírá glyph s minimální chybou mezi blokem a bitmapou znaku:

$$
g^* = \arg\min_g \|B - G_g\|_2^2
$$

### Proč je takto navržený

- Blue-noise/pattern dávají rychlou stylizaci bez těžké geometrie.
- ASCII porovnává skutečný blok proti glyph bitmapám, takže výstup není jen prahování textu.
- Sdílený kontrastní pipeline drží konzistentní vzhled napříč styly.

## `TEMPORAL_NOISE` renderer

### Výpočetní logika

1. Z G-bufferu (`objectId`, `faceId`, `depth`, `normal`, `worldPos`) se vytvoří regionální motion parametry.
2. Pro region se spočítá osový směr, rychlost a fáze.
3. Finální obraz používá integer posuv stabilního 2D zrna po buněčné mřížce.
4. Hrany se blendují přes `edgeMask`, ne přes globální blur.

### Matematika (podle implementačních vztahů)

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

### Proč je takto navržený

- Je levnější než ray/path, protože re-useuje hotový raster G-buffer.
- Integer posuv drží zrno stabilní bez subpixel deformací.
- Regionální parametry zmenšují shimmering a rozpady na hranách.

## `HEX_MOSAIC` renderer

### Výpočetní logika

1. Vezme raster base frame.
2. Rozdělí pixely do hex buněk (axial koordináty).
3. V každé buňce akumuluje barvu + hloubku.
4. Kvantizuje luminanci, vrátí barvu se stejnou energií.
5. Přidá outline/edge styl a volitelný wow mód (`classic`, `prism`, `neon`).

### Matematika

Převod pixelu do axial souřadnic:

$$
q = \frac{\frac{\sqrt{3}}{3}x - \frac{1}{3}y}{r},\qquad
r_a = \frac{\frac{2}{3}y}{r}
$$

Luminance buňky:

$$
Y = 0.2126R + 0.7152G + 0.0722B
$$

Kvantizace:

$$
Y_q = \frac{round(clamp(Y,0,1)(L-1))}{L-1}
$$

Zachování poměru barev přes gain:

$$
gain = \frac{Y_q}{Y + \varepsilon},\qquad
\mathbf{c}_{out}=clamp(\mathbf{c}_{avg}\cdot gain)
$$

Hex edge síla má v kódu explicitní náběh od `edgeStart = 0.86`.

### Proč je takto navržený

- Buněčná akumulace dává čitelný styl i při pohybu kamery.
- Kvantizace přes luminanci drží konzistentní kontrast.
- Depth-aware hrany zvýrazní strukturu bez nutnosti složitých segmentačních passů.

## `RAY_TRACING` renderer

### Výpočetní logika

- BVH akcelerace průsečíků.
- Přímé světlo přes directional/point/area/emissive/environment větve.
- Sekundární paprsek pokračuje dominantní větví (odraz/přenos) do `maxDepth`.
- Důraz na stabilní offline preview s menší stochastickou variabilitou.

### Matematika

Lokální BRDF část používá Lambert + GGX + Fresnel/clearcoat/sheen:

$$
L_o = L_d + L_{ggx}\cdot F + L_{clearcoat}\cdot F_c + L_{sheen}
$$

Fresnel (Schlick):

$$
F(\cos\theta)=F_0 + (1-F_0)(1-\cos\theta)^5
$$

Akumulace sekundární větve:

$$
L \leftarrow L + T\odot L_{local},\qquad
T \leftarrow T\odot w_{secondary}
$$

### Proč je takto navržený

- Oproti path traceru je méně náhodný a rychlejší v interaktivním preview.
- Udržuje kvalitní interpretaci reflektivních/transmisivních materiálů.
- Carrier profil dovoluje škálovat kvalitu při pohybu bez úplného vypnutí klíčových jevů.

## `PATH_TRACING` renderer

### Výpočetní logika

1. Monte Carlo integrace po bounce krocích do `maxBounces`.
2. Větvení mezi transmission/specular/diffuse/clearcoat podle pravděpodobností.
3. Throughput se kompenzuje přes branch ratio.
4. Přímé světlo + environment/emissive sampling.
5. Russian roulette od vyšších bounce úrovní.

### Matematika

Pravděpodobnosti větví (konceptuálně):

$$
p_t = transmissionProbability,\quad
p_s = specProbability,\quad
p_d = 1 - p_t - p_s - p_c
$$

Throughput update:

$$
\mathbf{T}_{k+1}=\mathbf{T}_k \odot \frac{\mathbf{w}_{branch}}{p_{branch}}
$$

Přímý příspěvek:

$$
\mathbf{L} \leftarrow \mathbf{L} + \mathbf{T}\odot \mathbf{L}_{direct}
$$

Russian roulette (v implementaci od `bounce >= 2`):

$$
rr = clamp(\max(T_r,T_g,T_b),\ 0.05,\ 0.98)
$$

$$
	ext{continue with prob. } rr,\qquad
\mathbf{T} \leftarrow \frac{\mathbf{T}}{rr}
$$

### Proč je takto navržený

- Je to referenční mód pro věrnější světelnou odezvu.
- Branch-PDF kompenzace drží estimator blíž fyzikálně správné integraci.
- Russian roulette zkracuje dlouhé dráhy bez systematického biasu.

## Přesnost vs. výkon: praktické rozhodnutí

| Režim | Fyzikální věrnost | Časová cena | Typický účel |
| --- | --- | --- | --- |
| `MODEL`, `BASIC` | nízká | velmi nízká | navigace, blokování scény |
| `PHONG` | střední | nízká | běžná práce ve viewportu |
| `WIREFRAME` | nerelevantní (diagnostický) | nízká | topologie a silueta |
| `DITHERING`, `TEMPORAL_NOISE`, `HEX_MOSAIC` | stylizovaná, ne-fyzikální | střední | výtvarný look |
| `RAY_TRACING` | vyšší | vysoká | kvalitní offline preview |
| `PATH_TRACING` | nejvyšší v projektu | nejvyšší | finální referenční výstup |

## Shrnutí

- „Přehled renderů“ je zde aktualizovaný na všechny současné režimy.
- Dokument obsahuje samostatnou matematickou sekci a vzorce pro každý renderer.
- U každého rendereru je explicitně popsaná logika i důvod návrhu (proč je implementovaný právě takto).
