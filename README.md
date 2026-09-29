# Simple ME Terminal – 1.7.10 / GT New Horizons

Version für **Minecraft 1.7.10 (Forge)**, gebaut und getestet für **GT New Horizons 2.8.4**.
Die Version für Minecraft 26.2 (Fabric) liegt im Branch [`main`](https://github.com/Timske6/simpleme/tree/main).

Eine kleine Quality-of-Life-Mod: **ein einziger Block**, das **ME-Terminal**, speichert beliebig viele Items.
Keine Laufwerke, keine Speicherzellen, keine Kabel. Block hinstellen, rechtsklicken, fertig.

## Features

- **Unbegrenzter Speicher**: beliebig viele Item-Sorten, pro Sorte praktisch unbegrenzte Anzahl.
  Items mit Metadaten und NBT (GregTech-Items, Werkzeuge, verzauberte oder umbenannte Items …) werden korrekt getrennt.
- **Übersicht über alle Items** mit Suchfeld, Scrollbar und Sortierung (Name / Anzahl / Mod).
  Große Mengen werden kompakt angezeigt (z. B. `12.3k`, `1.5M`), die genaue Zahl steht im Tooltip.
- **Crafting direkt aus dem Speicher**:
  - 3×3-Werkbank im Terminal. Nach jedem Craft füllt sich das Raster automatisch aus dem Speicher nach.
  - **NEI-Integration**: Im NEI-Rezept auf „+“ (Rezept übertragen) klicken, dann werden die Zutaten direkt aus
    dem Speicher (und dem Inventar) eingelegt, mit Shift ein ganzer Stack. Das „?“ zeigt das Rezept auf dem Raster;
    NEI markiert Zutaten als vorhanden, wenn sie im Speicher liegen.
  - **R / U** und der NEI-Tooltip funktionieren auch auf den Items im Terminal.
  - **GregTech-Werkzeuge** (Hammer, Schraubenschlüssel, Säge …) bleiben beim Craften im Raster und nutzen sich ab.
  - Leere Eimer und andere Reste wandern in den Speicher.
  - `x`-Knopf: legt das Werkbankraster zurück in den Speicher.
- **Items bleiben beim Abbauen im Block**. Auch im Kreativmodus, bei Explosionen und mit Behutsamkeit geht nichts verloren.
  Der Tooltip zeigt, wie viel drin ist.
- **Trichter, GregTech-Förderbänder, Item-Rohre und AE2-Export-Busse** können Items **hineinschieben**.
  Herausziehen geht absichtlich nicht, damit nichts den Speicher leert.

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
| Shift + Klick im eigenen Inventar | Legt den Stack in den Speicher |
| Shift + Klick auf das Crafting-Ergebnis | Craftet einen ganzen Stack ins Inventar (oder das ganze Raster, wenn mehr drin liegt) |
| Suchfeld | Filtert nach Name, `@gregtech` filtert nach Mod. Rechtsklick leert die Suche |
| Mausrad über dem Raster | Scrollen |

Die Anzahl der sichtbaren Zeilen passt sich der Fensterhöhe an (3 bis 8 Zeilen).

## Rezept

```
Eisenbarren  Glasscheibe  Eisenbarren
Redstone        Truhe       Redstone
Eisenbarren    Diamant    Eisenbarren
```

(Eisen, Redstone und Diamant über das Ore Dictionary, die Truhe über `chestWood`, falls vorhanden.)

## Installation (GTNH im Prism Launcher)

1. `simpleme-1.0.0+1.7.10.jar` in den `mods`-Ordner der Instanz legen
   (Prism: Instanz auswählen → *Ordner* → `.minecraft/mods`).
2. Auf einem Server muss die Mod auf dem Server **und** bei allen Spielern installiert sein.

NEI ist optional: ohne NEI funktioniert alles außer der Rezept-Übertragung.

## Für Server-Admins

- **Wo liegen die Items?** Jedes Terminal hat eine eigene Datei in der Welt: `<Welt>/data/simpleme_<id>.dat`.
  Block und Item tragen nur die ID (plus eine Zusammenfassung für den Tooltip). Dadurch bleibt ein
  abgebautes Terminal-Item immer klein, egal wie viel drin ist.
- **Backups** müssen den `data`-Ordner der Welt enthalten (bei normalen Welt-Backups ist das automatisch so).
- **Duplizierte Terminal-Items** teilen sich denselben Speicher, die Items darin werden **nicht** mit dupliziert.
- **Verlorene Terminals wiederherstellen:** Die Datei bleibt erhalten. Ein Admin kann das Terminal mit der ID aus
  dem Dateinamen zurückholen:
  `/give Steve simpleme:terminal 1 0 {SimpleME:{StorageId:"2922a0c2-84d6-498f-8640-c8ccdad552db"}}`
- **Mod entfernen = Items weg.** Wie bei jeder Speicher-Mod verschwinden die Terminals samt Inhalt.
- Items einer entfernten *anderen* Mod werden beim Laden übersprungen, der Rest bleibt erhalten.

## Selbst bauen

Benötigt Java 17 oder neuer zum Bauen (der Build lädt die nötigen Java-Versionen selbst).

```bash
./gradlew build
```

Die fertige Mod liegt danach in `build/libs/` (die Datei ohne `-dev`/`-sources`).

### Automatischer Ingame-Test

Die Mod hat einen eingebauten Selbsttest, der nur in Test-Builds enthalten ist:

- `./gradlew runClient25 -Pselftest`: startet Minecraft mit NEI, legt eine Testwelt an, prüft Speicher, Abbauen,
  Explosion, Trichter, GUI, NEI-Übertragung, Crafting mit Werkzeugen/Eimern, Klicks und Neuladen der Welt,
  macht Screenshots und schließt sich wieder. Ergebnis: `run/client/simpleme-selftest.txt`.
- `./gradlew build -Pselftest` baut ein Test-Jar. In einer beliebigen Instanz (z. B. einer Kopie der GTNH-Instanz)
  mit der JVM-Option `-Dsimpleme.selftest=true` gestartet, läuft derselbe Test mit allen Mods des Packs.

## Lizenz

MIT, siehe [LICENSE](LICENSE).
