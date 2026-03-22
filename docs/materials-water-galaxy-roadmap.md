# Roadmap: Materiály, voda, galaxie

> Poznámka: tento soubor je doplňková roadmap poznámka. Aktuální technický přehled projektu je v `README.md` a v `docs/`.

## Aktuální stav projektu

- `engine.scene` je jednoduchý a stabilní: `Scene` vlastní `Entity` a `Light`, `Entity` drží mesh/material/transform.
- Realtime renderování je čistě rozdělené:
  - `RasterRenderer` pro rychlý viewport shading.
  - `RayTracerRenderer` a `PathTracerRenderer` pro progresivní CPU výstup.
  - stylizované post renderery už existují a dají se dlouhodobě držet.
- Import pipeline je prakticky použitelná:
  - OBJ + MTL
  - STL
  - glTF / GLB
- Editor stack je momentálně silnější než materiálová větev:
  - outliner
  - inspector
  - timeline
  - output render controller
- V `engine.sim.water` už byla použitelná CPU simulace vodních částic, ale nebyla připojená do hlavní engine smyčky.

## Hlavní omezení

- Materiály byly stále prakticky `Phong`-centrické.
- Pokročilá lookdev data pro water, glass, fog, emission, density nebo transmission neměla jedno společné místo.
- Existující realtime water fungovala jen jako izolované třídy, ne jako scene-level entity workflow.
- Galaxy simulace potřebuje systém mnoha těles, ne jeden mesh, proto má začínat jako simulační scaffold a ne jako fake geometrie.
- Output rendering zatím nesimuluje ani nerenderuje vodní částice; nejdřív se řešila správná viewport integrace.

## Co tento pass přináší

| Oblast | Přidaný základ |
| --- | --- |
| Hybridní materiálový základ | doména (`SURFACE`, `VOLUME`, `PARTICLE`, `CELESTIAL`), shading model (`PHONG`, `TRANSMISSIVE`, `VOLUMETRIC`, `PARTICLE_FLUID`, `EMISSIVE`), roughness/metallic/transmission, medium data, emission data, tracking presetů |
| Knihovna presetů | `Matte`, `Glossy`, `Metallic`, `Water`, `Glass`, `Fog`, `Emissive` |
| Inspector | podpora nových materiálových parametrů |
| Water entity model | `WaterEmitterEntity`, auto-registrace do `WaterSimulation`, viewport update + particle rendering |
| Galaxy scaffold | `GalaxySystemEntity`, `GalaxySimulation`, `GalaxyBody` |

## Doporučené další kroky

1. Doplnit skutečný transparentní/refrakční handling do ray/path tracingu, aby `Water` a `Glass` nebyly offline jen preview.
2. Přidat volumetrické passy pro fog/cloud media místo povrchových aproximací.
3. Posunout materiály z preset-driven modelu na menší node graph:
   - constant/color node
   - texture sample node
   - mix node
   - fresnel node
   - emission node
   - volume medium node
4. Doplnit output-render podporu pro water přes deterministickou simulaci emitterů podle output času/snímku.
5. Rozšířit galaxy simulaci na instancované tělesové renderování s Barnes-Hut nebo jinou akcelerovanou aproximací gravitace.
