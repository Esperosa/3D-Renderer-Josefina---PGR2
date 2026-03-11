# Output / export workflow

## Cíl

Output vrstva je navržená jako samostatný render workflow mimo realtime viewport.

## Podporované výstupy

- still image,
- image sequence,
- animated GIF,
- AVI (MJPEG).

## Session folders

Každý export může vytvořit vlastní session složku, do které se ukládají:

- výsledné soubory,
- `preview.png`,
- `manifest.json`,
- `log.txt`.

To zjednodušuje archivaci, opakovatelnost a kontrolu render jobů.
Typicky jde o lokální ignorovanou složku `renders/` nebo jiný uživatelem zvolený adresář; tyto výstupy nemají být součástí čistého source stromu.

## Metadata

Manifest zachycuje:

- export type,
- renderer,
- rozlišení,
- sampling / depth nastavení,
- frame range,
- fps,
- generated files,
- duration,
- cancelled / success stav.

## AVI bez externích knihoven

AVI export používá čistě JDK implementaci MJPEG AVI writeru. Projekt nepoužívá ffmpeg ani jiný externí proces.
