# Dokumentace projektu

- Název projektu: PGRF2 2026 - Úloha 1, software rasterizer
- Jméno autora: Jiří Pelikán
- Ročník studia studenta: třetí - 2026
- Datum odevzdání zadání: 10. 5. 2026
- Cvičící a číslo cvičení: C01
- GitHub: https://github.com/Esperosa/3D-Renderer-Josefina---PGR2

## 1. Naplnění cílů projektu

Vytvořil jsem jedno oknovou Java aplikaci pro zobrazení jednoduché 3D grafické scény. Scéna obsahuje kouli, krychli, čtyřstěn, válec, kužel, bikubickou plochu a znázorněný zdroj světla. Tělesa zobrazuji jako texturované a osvětlené plochy, lze přepnout drátový model, měnit projekci a transformovat aktivní těleso. Kamera podporuje pohyb WSAD a rozhlížení myší.

## 2. Platforma

Java JDK 17 nebo novější, Java Swing/AWT. Projekt nepoužívá externí knihovny.

## 3. Skutečný postup řešení

- Vytvořil jsem samostatnou odevzdávací verzi projektu se strukturou `src`, `res`, `doc` a `readme.md`.
- Základní matematiku jsem rozdělil do tříd `Vec2`, `Vec3`, `Transform` a `Camera`.
- Geometrii ukládám ve třídě `Mesh`, která obsahuje vrcholy, hrany a plochy.
- Rozšířený vertex obsahuje pozici, normálu, barvu a UV souřadnice.
- Tělesa generuji v `GeometryFactory`: krychli, kouli, čtyřstěn, válec, kužel a bikubickou Bezierovu plochu.
- Rasterizaci jsem implementoval ve třídě `SoftwareRasterizer`.
- Viditelnost řeším Z-bufferem pro plochy i hrany.
- Ořezání řeším near plane ořezem pro úsečky i trojúhelníky a XY ořezem bounding boxu při rasterizaci.
- Textury jsem vytvořil procedurálně přes funkcionální interface `ProceduralTexture`.
- Osvětlení obsahuje ambientní, difúzní a spekulární složku.
- UI obsahuje výběr tělesa, přepínání projekce, přepínání ploch/drátu, textur a animace.

## 4. Popis způsobu řešení překážek a problémů

Nejdříve jsem oddělil odevzdávací verzi od původního rozsáhlého projektu. Vytvořil jsem samostatnou jednoduchou aplikaci bez importu modelů, ray/path traceru, benchmarků a testovací infrastruktury. Při rasterizaci jsem musel vyřešit stabilní Z-buffer a ořezání objektů u near plane, aby tělesa při přiblížení kamery nemizela. U textur jsem doplnil perspektivně korektní interpolaci přes hodnotu `1/z`, aby textura na krychli a dalších tělesech nebyla viditelně deformovaná.

## 5. Rozsah použití AI

AI jsem použil při vytvoření odevzdávací verze, přípravě dokumentace, kontrole struktury ZIP souboru a doplnění hodnoticí tabulky. Výsledný kód jsem následně zkompiloval a spustil z rozbaleného ZIP souboru.

## 6. Výsledek

Jedná se o jedno oknovou aplikaci bez menu. Ovládání:

- WSAD a myš - kamera
- 1 až 7, klik nebo combobox - výběr aktivního tělesa
- T + šipky - posun
- R + šipky - rotace
- Y + šipky - scale
- PageUp/PageDown - osa Z
- P - perspektivní nebo pravoúhlá projekce
- M - drátový model nebo vyplněné plochy
- U - zapnutí/vypnutí textury na aktivním tělese
- C - změna barvy světla
- Mezerník - zapnutí/vypnutí animace

## 7. Závěr a hodnocení

Projekt podle mé kontroly splňuje hlavní požadavky úlohy 1: zobrazení více těles, transformace, kamera, projekce, rasterizace hran i ploch, Z-buffer, textury, osvětlení a jednoduché UI. Program jsem záměrně ponechal pouze v rozsahu požadavků zadání.

## 8. Dodatek

Projekt ukazuje základní principy 3D pipeline bez externích knihoven: tvorbu geometrie, transformace, projekci, ořezání, rasterizaci, interpolaci atributů, texturování a osvětlení.
