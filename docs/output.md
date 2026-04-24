# Výstupní / exportní workflow

## Cíl

Výstupní vrstva je navržená jako samostatný render workflow mimo realtime viewport.

## Podporované výstupy

- statický snímek,
- obrazová sekvence,
- animovaný GIF,
- AVI (MJPEG).

## Session složky

Každý export může vytvořit vlastní session složku, do které se ukládají:

- výsledné soubory,
- `preview.png`,
- `manifest.json`,
- `log.txt`.

To zjednodušuje archivaci, opakovatelnost a kontrolu render jobů.
Typicky jde o lokální ignorovanou složku `renders/` nebo jiný uživatelem zvolený adresář; tyto výstupy nemají být součástí čistého source stromu.

## Metadata

Manifest zachycuje:

- typ exportu,
- renderer,
- rozlišení,
- nastavení samplingu / hloubky,
- rozsah snímků,
- fps,
- vygenerované soubory,
- délka,
- stav zrušeno / úspěch.

## AVI bez externích knihoven

AVI export používá čistě JDK implementaci MJPEG AVI writeru. Projekt nepoužívá ffmpeg ani jiný externí proces.
