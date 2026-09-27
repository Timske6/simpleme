# Simple ME Terminal (Fabric, Minecraft 26.2)

Eine kleine Quality-of-Life-Mod: **ein einziger Block**, das **ME-Terminal**, speichert beliebig viele Items.
Keine Laufwerke, keine Speicherzellen, keine Kabel. Block hinstellen, rechtsklicken, fertig.

Quellcode und Fehlermeldungen: https://github.com/Timske6/simpleme

## Features

- **Unbegrenzter Speicher**: beliebig viele Item-Sorten, pro Sorte praktisch unbegrenzte Anzahl.
  Items mit Daten (verzauberte, beschädigte, umbenannte Items, Tränke …) werden korrekt getrennt gespeichert.
- **Übersicht über alle Items** mit Suchfeld, Scrollbar und Sortierung (Name / Anzahl / Mod).
  Große Mengen werden kompakt angezeigt (z. B. `12.3k`, `1.5M`), die genaue Zahl steht im Tooltip.
- **Crafting direkt aus dem Speicher**:
  - 3×3-Werkbank im Terminal. Nach jedem Craft füllt sich das Raster automatisch aus dem Speicher nach.
  - **Rezeptbuch**: Rezept anklicken, dann werden die Zutaten direkt aus dem Speicher (und dem Inventar) eingelegt.
    Das Rezeptbuch zeigt auch Rezepte als craftbar an, deren Zutaten nur im Speicher liegen.
  - Rückstände wie leere Eimer (z. B. beim Kuchen) wandern automatisch zurück in den Speicher.
  - `×`-Knopf: legt das Werkbankraster zurück in den Speicher.
- **Items bleiben beim Abbauen im Block** (wie bei einer Shulkerkiste). Der Tooltip zeigt, wie viel drin ist.
  Auch im Kreativmodus und bei Explosionen geht nichts verloren. Ein volles Terminal-Item verbrennt nicht in Lava/Feuer.
- **Trichter** (und Item-Rohre anderer Mods, die die Fabric Transfer API nutzen) können Items **hineinschieben**.
  Herausziehen per Trichter geht absichtlich nicht, damit ein Trichter darunter nicht den ganzen Speicher leert.

## Steuerung im Terminal

| Aktion | Wirkung |
|---|---|
| Linksklick auf ein Item | Nimmt einen Stack auf den Cursor |
| Rechtsklick auf ein Item | Nimmt einen halben Stack |
| Shift + Rechtsklick | Nimmt ein einzelnes Item |
| Shift + Linksklick | Verschiebt einen Stack direkt ins Inventar |
| Mittelklick (Kreativ) | Kopiert einen vollen Stack |
| Linksklick mit Item auf dem Cursor | Legt alles vom Cursor in den Speicher |
| Rechtsklick mit Item auf dem Cursor | Legt ein einzelnes Item in den Speicher |
| Shift + Klick im eigenen Inventar | Legt den Stack in den Speicher (Doppelklick mit Shift: alle gleichen Items) |
| Shift + Klick auf das Crafting-Ergebnis | Craftet einen ganzen Stack ins Inventar (oder das ganze Raster, wenn mehr drin liegt) |
| Strg + Q auf das Crafting-Ergebnis | Wirft höchstens einen Stack weg |
| Suchfeld | Filtert nach Name, `@minecraft` filtert nach Mod. Rechtsklick leert die Suche |
| Mausrad über dem Raster | Scrollen |

Die Anzahl der sichtbaren Zeilen passt sich der Fensterhöhe an (3 bis 8 Zeilen).

## Rezept

```
Eisen   Glasscheibe  Eisen
Redstone  Truhe    Redstone
Eisen    Diamant    Eisen
```

## Installation

1. [Fabric Loader](https://fabricmc.net/use/) für Minecraft **26.2** installieren.
2. [Fabric API](https://modrinth.com/mod/fabric-api) (Version für 26.2) in den `mods`-Ordner legen.
3. `build/libs/simpleme-1.0.0+26.2.jar` in den `mods`-Ordner legen.

Die Mod muss auf dem Server **und** auf dem Client installiert sein.

## Für Server-Admins

- **Wo liegen die Items?** Jedes Terminal hat eine eigene Datei in der Welt:
  `<Welt>/data/simpleme/storage/<id>.dat`. Block und Item tragen nur die ID (plus eine Zusammenfassung für den Tooltip).
  Dadurch bleibt ein abgebautes Terminal-Item immer klein, egal wie viel drin ist
  (sonst könnte ein Terminal voller Bücher Spieler vom Server kicken, wie beim alten „Book-Ban“).
- **Backups** müssen den `data`-Ordner der Welt enthalten (bei normalen Welt-Backups ist das automatisch so).
- **Duplizierte Terminal-Items** (z. B. durch Glitches anderer Mods oder Kreativ-Kopien) teilen sich denselben Speicher,
  die Items darin werden dabei **nicht** mit dupliziert.
- **Verlorene Terminals wiederherstellen:** Geht ein volles Terminal-Item verloren (Void, Despawn), bleibt seine Datei erhalten.
  Ein Admin kann es mit der ID aus dem Dateinamen zurückholen, z. B.:
  `/give Steve simpleme:terminal[simpleme:storage={id:"2922a0c2-84d6-498f-8640-c8ccdad552db"}]`
- **Mod entfernen = Items weg.** Wie bei jeder Speicher-Mod verschwinden die Terminals samt Inhalt, wenn die Mod entfernt wird.
- Items einer entfernten *anderen* Mod werden beim Laden übersprungen, der Rest des Speichers bleibt erhalten.
- Performance: ein Terminal mit 20.000 Item-Sorten öffnet in unter 20 ms; Klick-Spam wird auf einen Sync pro Tick begrenzt.

## Tests

Die Mod hat eine automatische Testsuite (Fabric GameTest):

- `./gradlew runGameTest`: 30 Logik-Tests auf einem Test-Server (Speicher, Transaktionen, Trichter, Abbauen/Explosion,
  Speichern/Laden, Crafting inkl. Rezeptbuch/Reste/Shift-Klick, Dupe-Checks mit zwei Spielern, Performance mit 20.000 Sorten).
- `./gradlew runClientGameTest`: startet Minecraft und testet im echten Spiel (Einzelspieler **und** dedizierter Server):
  GUI, Klicks, Suche, Sortierung, Scrollen, Fenstergrößen, Rezeptbuch, Deutsch/Englisch, Abbauen/Tragen/Platzieren,
  Neuladen der Welt. Screenshots landen in `build/run/clientGameTest/screenshots`.

## Selbst bauen

Benötigt Java 25. Im Projektordner liegt ein portables JDK unter `.jdk/`.

```bash
./gradlew build
```

Die fertige Mod liegt danach in `build/libs/`.

Weitere Gradle-Aufgaben:

- `./gradlew runClient`: startet ein Test-Minecraft mit der Mod
- `./gradlew runGameTest` / `./gradlew runClientGameTest`: die automatischen Tests (siehe oben)

## Lizenz

MIT, siehe [LICENSE](LICENSE). Du darfst die Mod frei nutzen, in Modpacks packen und den Code weiterverwenden,
solange der Copyright-Hinweis erhalten bleibt.
