# Architektura projektu

Tento dokument shrnuje hlavní modulární členění projektu a odpovědnosti jednotlivých balíků. Nejde o formální enterprise architekturu, ale o praktický popis studentského editoru a render sandboxu.

## Hlavní vrstvy

- `engine.core`
  - editorové controllery,
  - lifecycle aplikace,
  - toolbar, properties, docky,
  - output workflow a session export.
- `engine.render`
  - raster renderer,
  - ray tracer,
  - path tracer,
  - stylizované post/procedurální viewport režimy.
- `engine.material`
  - `PhongMaterial` jako kontejner a kompatibilní bridge,
  - `MaterialNodeGraph` jako authoring source of truth,
  - `MaterialGraphEvaluator` jako sdílené vyhodnocení grafu.
- `engine.scene`
  - entity,
  - světla,
  - základní scéna.
- `engine.sim`
  - experimentální simulace a overlay subsystémy.
- `engine.io`
  - import modelů a parsování dat.

## Praktické architektonické rozhodnutí

### 1. Jeden materiálový zdroj pravdy

Materiálový graph je authoring source of truth. `PhongMaterial` zůstal kvůli kompatibilitě s renderery a import pipeline, ale už nemá skrytě nahrazovat node defaulty.

### 2. Raster jako preview, Ray/Path jako kvalitativní reference

Projekt záměrně nerozmazává hranice mezi rychlým realtime preview a kvalitnějším offline CPU renderingem.

- `RasterRenderer` je hlavně pracovní viewport.
- `RayTracerRenderer` a `PathTracerRenderer` nesou hlavní lighting / material reference kvalitu.

### 3. Output workflow je oddělené od viewportu

Output rendering běží přes `OutputRenderController`, který vytváří vlastní session context, výstupní složky a metadata.

### 4. Editorové UI zůstává praktické, ne framework-heavy

Projekt nepřidává velký interní UI framework. Základ tvoří Swing/AWT + tenká vlastní vrstva:

- `UiTheme`
- `UiStrings`
- `UiBuilder`

To udržuje projekt relativně lehký a čitelný.

## Maturity poznámky

- water/spray vrstva je poctivě označená jako částicový overlay,
- galaxy subsystém zůstává experimentální scaffold,
- volumetrika je homogenní a zjednodušená.
