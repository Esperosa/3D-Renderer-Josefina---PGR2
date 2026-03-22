# Specifikace Java CPU inference (produkční pilot)

## Rozsah
Ruční CPU implementace v Javě bez závislosti na externím ML runtime.

## Rozložení tenzorů
- Inference tensory používají NCHW float32.
- Pořadí vstupních kanálů: beauty.R, beauty.G, beauty.B, albedo.R, albedo.G, albedo.B, normal.X, normal.Y, normal.Z.
- Pořadí výstupních kanálů: denoised beauty RGB.

## Předzpracování vstupu
- beauty kanály: log1p(max(x, 0)).
- albedo kanály: beze změny jako lineární guidance hodnoty v [0, 1].
- normal kanály: beze změny jako world-space guidance přibližně v [-1, 1].

## Definice konvoluce
Pro výstupní kanál o na pixelu (y, x):
out[o,y,x] = bias[o] + sum_i sum_ky sum_kx weight[o,i,ky,kx] * in[i, y+ky-pad, x+kx-pad]
- Velikost kernelu: 3x3.
- Padding: zero padding, 1 pixel na všech stranách.
- Stride: 1, pouze downsample konvoluce mají stride 2.

## Aktivace
- LeakyReLU s negative_slope = 0.1.
- Definice: f(x)=x když x>=0, jinak 0.1*x.

## Downsampling a upsampling
- Downsample: konvoluce se stride 2.
- Upsample: bilineární 2x (chování align_corners=false), potom 3x3 konvoluce.

## Skip connections
- U-Net skip merge používá channel concat a následně fúzi přes 3x3 konvoluci.
- Residual bloky: x + conv2(leaky_relu(conv1(x))) a potom LeakyReLU.
- Finální residual výstup: output = beauty_log_space + residual_pred.

## Převod výstupu
- Predikovaný výstup je v log-komprimované doméně.
- Převod zpět do lineárního HDR přes expm1(max(x, 0)).
- Clamp provádět jen ve fázi file formátu/výstupu, ne uvnitř modelové matematiky.

## Tiled inference
- Použít velikost tile T a overlap O (O < T/2).
- Step = T - 2*O.
- Blend přes separovatelné feather okno násobené v osách XY.
- Akumulovat weighted sum a dělit weight sum po jednotlivých pixelech.

## Soubory vah
- Formát: raw float32 binární data pro každý tenzor + JSON manifest.
- Endianness: little-endian.
- Layout konvolučních vah: OIHW.
- Cesta k manifestu: C:\Users\jirka\Documents\GitHub\3D-Render-Physics\runtime\denoiser-package\java_weights\weights_manifest.json

## Shrnutí modelu
- Název: JavaCpuMiniUNet
- Počet parametrů: 340595
- Odhad receptive field: {'rf_h': 69, 'rf_w': 69}

## Checklist Java implementace
1. Načíst všechny tensory z manifest souborů jako float32 pole.
2. Implementovat NCHW conv kernel se zero padding a stride 1/2.
3. Implementovat bilineární upsample (ekvivalent align_corners=false).
4. Implementovat LeakyReLU a residual add přesně podle specifikace.
5. Implementovat tiled inference blend bez seam artefaktů.
6. Ověřit paritu s fixním referenčním výstupem na jednom fixním EXR vzorku.
