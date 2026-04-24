# Materiálový systém

## Základní princip

Materiálový systém je dnes graph-driven:

- uzly drží své vlastní defaulty,
- inspektor upravuje konkrétní instanci uzlu,
- `MaterialGraphEvaluator` převádí graf do společné evaluované podoby pro renderery.

## Source of truth

Primární zdroj pravdy:

- `MaterialNodeGraph`

Kompatibilní obálka:

- `PhongMaterial`

Tohle rozdělení je důležité hlavně proto, aby nedocházelo k dřívějšímu problému, kdy více nodálních shaderů sdílelo skrytě stejné hodnoty přes globální material fields.

## Praktické workflow

Materiálový workspace obsahuje:

- lookdev preview,
- node canvas,
- node inspector,
- quick summary kompatibility a routingu.

## Podporované materiálové oblasti

- Principled surface authoring,
- glass / transmission,
- emissive materiály,
- homogenní volume médium,
- image textures,
- normal maps,
- PBR texture-set import workflow.

## Poctivé limity

- raster preview je silně praktičtější než fyzikálně přesný,
- volume workflow je homogenní a zjednodušené,
- některé pokročilé closure kombinace dávají největší smysl až v Ray/Path režimech.
