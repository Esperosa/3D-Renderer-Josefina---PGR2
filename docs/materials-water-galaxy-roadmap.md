# Materials, Water, Galaxy Roadmap

> Poznámka: tento soubor je doplňková roadmap poznámka. Aktuální technický přehled projektu je v `README.md` a v `docs/`.

## Current project state

- `engine.scene` is simple and solid: `Scene` owns `Entity` and `Light`, `Entity` owns mesh/material/transform.
- Realtime rendering is split cleanly:
  - `RasterRenderer` for fast viewport shading.
  - `RayTracerRenderer` and `PathTracerRenderer` for progressive CPU output.
  - post stylization renderers already exist and are easy to keep.
- Import pipeline is already useful:
  - OBJ + MTL
  - STL
  - glTF / GLB
- Editor stack is stronger than the material stack:
  - outliner
  - inspector
  - timeline
  - output render controller
- There was already a usable CPU water particle simulation in `engine.sim.water`, but it was not wired into the engine loop.

## Main constraints found

- Materials were effectively still `Phong`-centric.
- Advanced look-dev data for water, glass, fog, emission, density, or transmission had nowhere shared to live.
- Existing realtime water existed only as isolated classes, not as a scene-level model/entity workflow.
- Galaxy simulation needs to be a system of many bodies, not a single mesh, so it should start as simulation scaffolding rather than fake geometry.
- Output rendering still does not simulate/render water particles yet; viewport support was the right first integration step.

## What this pass adds

- Hybrid material foundation:
  - domain (`SURFACE`, `VOLUME`, `PARTICLE`, `CELESTIAL`)
  - shading model (`PHONG`, `TRANSMISSIVE`, `VOLUMETRIC`, `PARTICLE_FLUID`, `EMISSIVE`)
  - roughness / metallic / transmission
  - medium color / density / anisotropy / thickness
  - emission color / strength
  - preset tracking
- Preset library:
  - `Matte`
  - `Glossy`
  - `Metallic`
  - `Water`
  - `Glass`
  - `Fog`
  - `Emissive`
- Inspector support for the new material parameters.
- Real water emitter model:
  - `WaterEmitterEntity`
  - auto-registration into `WaterSimulation`
  - viewport simulation update + particle rendering
- Galaxy scaffolding k dopracování:
  - `GalaxySystemEntity`
  - `GalaxySimulation`
  - `GalaxyBody`

## Recommended next steps

1. Add true transparent / refractive handling to ray/path tracing so `Water` and `Glass` stop being preview-only in offline modes.
2. Add volumetric passes for fog/cloud media instead of surface approximations.
3. Move from preset-driven materials to a small node graph:
   - constant/color node
   - texture sample node
   - mix node
   - fresnel node
   - emission node
   - volume medium node
4. Add output-render water support by simulating emitters deterministically for output time/frame.
5. Build galaxy simulation as an instanced-body renderer with Barnes-Hut or another accelerated gravity approximation.
