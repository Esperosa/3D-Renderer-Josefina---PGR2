# Rendering

## Render stack

Projekt obsahuje tři hlavní render větve:

- `RasterRenderer`
- `RayTracerRenderer`
- `PathTracerRenderer`

Vedle nich existují stylizované preview režimy jako Wireframe, Dither, Temporal Noise nebo Hex Mosaic.

## Raster renderer

Raster renderer je navržený jako rychlý viewport pracovní režim.

Umí:

- rychlé zobrazení mesh a materiálového výsledku,
- normálové mapy přes kompatibilní bridge,
- transparentní a volumetrické preview v aproximované podobě,
- stylizované režimy navázané na viewport workflow.

Neumí a ani nepředstírá:

- plně fyzikálně věrnou transparentní closure logiku,
- plnou offline volumetriku,
- kvalitu srovnatelnou s path tracerem.

## Ray tracer

Ray tracer je kvalitnější offline CPU renderer s důrazem na:

- odrazy,
- přenos,
- ostřejší interpretaci materiálů,
- konzistentní využití evaluovaného material sample.

## Path tracer

Path tracer je v projektu referenční režim pro světelnou odezvu materiálů.

Je vhodný hlavně pro:

- glass / transmission materiály,
- emisivní materiály,
- lookdev s roughness / metallic response,
- homogenní volume médium v nejlepší dostupné podobě projektu.

## Materiály a renderery

Všechny tři větve používají společnou material evaluaci. Rozdíl není v tom, že by měl každý renderer úplně jiný materiálový model, ale v tom, jak přesně si umí výsledný sample interpretovat.

To je důvod, proč dokumentace poctivě rozlišuje:

- **plnou podporu**,
- **aproximaci**,
- **částečnou podporu**.
