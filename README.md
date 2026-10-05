# Simple ME Terminal (Fabric, Minecraft 26.3)

Eine kleine Quality-of-Life-Mod für unbegrenzten Item-Speicher: **ME-Netzwerke**.
Keine Laufwerke, keine Speicherzellen, keine Kabel. Terminal hinstellen, rechtsklicken, Netzwerk anlegen, fertig.
Beliebig viele Terminals, **Transferkisten** (alles Hineingelegte landet im Netzwerk) und **Funk-Terminals**
(Zugriff aus beliebiger Entfernung) können mit demselben Netzwerk verbunden werden.

Quellcode und Fehlermeldungen: https://github.com/Timske6/simpleme

> **Minecraft 1.7.10 / GT New Horizons?** Die Forge-Version für 1.7.10 (getestet mit GTNH 2.8.4, mit NEI-Integration)
> liegt im Branch [`1.7.10`](https://github.com/Timske6/simpleme/tree/1.7.10).

## Features

- **Unbegrenzter Speicher**: beliebig viele Item-Sorten, pro Sorte bis zu 9.223.372.036.854.775.807 Stück (`Long.MAX_VALUE`).
  Items mit Daten (verzauberte, beschädigte, umbenannte Items, Tränke …) werden korrekt getrennt gespeichert.
- **ME-Netzwerke**: Die Items gehören dem Netzwerk, nicht einem Block. Terminals, Transferkisten und Funk-Terminals
  sind nur Zugänge dazu, beliebig viele pro Netzwerk. Netzwerke sind privat oder öffentlich (siehe unten).
- **Übersicht über alle Items** mit Suchfeld, Scrollbar und Sortierung (Name / Anzahl / Mod).
  Jedes Terminal und jedes Funk-Terminal merkt sich seine eigene Sortierung, auch nach einem Neustart.
  Große Mengen werden kompakt angezeigt (z. B. `12.3k`, `1.5M`), die genaue Zahl steht im Tooltip.
  Die Reihenfolge bleibt stehen, solange das Terminal offen ist: Beim Herausnehmen springt nichts herum,
  eine leer genommene Sorte zeigt `0`. Neu sortiert (und leere Sorten entfernt) wird beim nächsten Öffnen.
- **Gruppen**: ein ausklappbares Menü rechts am Terminal und Funk-Terminal (siehe [Gruppen](#gruppen)).
  Wichtige Items bleiben dort immer sichtbar, auch wenn gerade keine im Netzwerk sind.
- **Crafting direkt aus dem Netzwerk**:
  - 3×3-Werkbank im Terminal. Nach jedem Craft füllt sich das Raster automatisch aus dem Netzwerk nach.
  - **Rezeptbuch**: Rezept anklicken, dann werden die Zutaten direkt aus dem Netzwerk (und dem Inventar) eingelegt.
    Das Rezeptbuch zeigt auch Rezepte als craftbar an, deren Zutaten nur im Netzwerk liegen.
  - **JEI** (optional): In jedem Werkbank-Rezept erscheint im Terminal und im Funk-Terminal ein `+`.
    Klick legt einen Satz Zutaten aus dem Netzwerk (und dem Inventar) ins Raster, nochmal klicken legt einen weiteren dazu,
    Shift + Klick so viele wie möglich. Fehlende Zutaten markiert JEI rot.
    `R` / `U` über einem Item im Terminal zeigen dessen Rezepte bzw. Verwendungen. Das klappt auch, während das
    Suchfeld aktiv ist: Liegt die Maus über einem Item (im Terminal, in einer Gruppe, in JEIs Itemliste oder
    in den Lesezeichen), öffnet JEI die Rezepte, sonst wird der Buchstabe ganz normal getippt.
  - Rückstände wie leere Eimer (z. B. beim Kuchen) wandern automatisch zurück ins Netzwerk.
  - `×`-Knopf: legt das Werkbankraster zurück ins Netzwerk.
- **Terminal abbauen ist ungefährlich**: Die Items bleiben im Netzwerk, das Terminal droppt als normales, stapelbares Item.
- **Trichter** (und Item-Rohre anderer Mods, die die Fabric Transfer API nutzen) können Items in ein Terminal **hineinschieben**.
  Herausziehen per Trichter geht absichtlich nicht, damit ein Trichter darunter nicht das ganze Netzwerk leert.
- **ME-Transferkiste** (54 Slots wie eine Doppelkiste): Items, die hineingelegt werden, verschwinden und landen im Netzwerk.
  Mit einem **An/Aus-Knopf** in der Kiste.
- **Funk-ME-Terminal**: öffnet ein Netzwerk von überall aus, **ohne Reichweitenbegrenzung**, in jeder Dimension und
  ganz ohne Terminal-Block. Mit Suche, Crafting und Rezeptbuch wie am Block.
- **Pick-Block aus dem Netzwerk**: Mittelklick auf einen Block (Überlebensmodus). Ist das Item nicht im Inventar,
  kommt bis zu ein Stack davon aus dem Netzwerk direkt in die Hand, sofern ein Funk-Terminal irgendwo im Inventar liegt.

## Netzwerke

Im Terminal, im Funk-Terminal und in der Transferkiste gibt es einen **Netzwerk-Knopf** (im Terminal oben links, wo sonst
der Titel steht, in der Kiste über dem Inventar). Er zeigt das verbundene Netzwerk und öffnet die Netzwerkliste:

- **Neu**: legt ein neues Netzwerk mit dem Namen aus dem Textfeld an und verbindet sich damit (Enter geht auch).
- **Verbinden**: verbindet mit dem markierten Netzwerk (Doppelklick in der Liste geht auch).
- **Umbenennen**: gibt dem markierten Netzwerk den Namen aus dem Textfeld.
- **Teilen / Privat**: Neue Netzwerke sind **privat**, nur du siehst und nutzt sie. Geteilte (öffentliche) Netzwerke
  kann jeder Spieler nutzen. Umbenennen, Teilen und Löschen darf nur der Ersteller.
- **Löschen**: nur leere Netzwerke, damit nie Items verloren gehen.

Ein neues Terminal (oder Funk-Terminal) ohne Netzwerk öffnet die Liste von selbst. Wechselt man im offenen Terminal
das Netzwerk, wandert das Werkbankraster vorher zurück ins alte Netzwerk.

**Abkürzung:** Funk-Terminal oder Transferkiste in die Hand nehmen und **schleichend auf ein Terminal rechtsklicken**:
dann bekommen sie dessen Netzwerk. Mit einem Funk-Terminal schleichend auf eine platzierte Transferkiste rechtsklicken
verbindet die Kiste mit dem Netzwerk des Funk-Terminals. Der Tooltip zeigt jeweils den Netzwerknamen.

## Transferkiste und Funk-Terminal

- **Funk-Terminal:** Rechtsklick (in die Luft oder auf einen Block) öffnet das Netzwerk. Funktioniert in jeder Entfernung
  und Dimension, auch ohne platziertes Terminal. Das Fenster bleibt offen, solange du das Funk-Terminal in der Hand hältst.
- **Pick-Block (Mittelklick):** Liegt ein Funk-Terminal irgendwo im Inventar und hast du den angeklickten Block nicht dabei,
  holt der Mittelklick höchstens einen Stack davon aus dessen Netzwerk in die Hand.
  Hast du das Item schon im Inventar, passiert das Normale (es wird nur in die Hand gewechselt).
  Ist kein Inventarplatz frei, wird nichts entnommen und eine Meldung erscheint. Im Kreativmodus bleibt alles wie in Vanilla.
- **Transferkiste:** Solange sie **an** und mit einem Netzwerk verbunden ist, wandert alles, was in der Kiste landet,
  sofort ins Netzwerk: von Hand hineingelegt, per Trichter oder per Item-Rohr. Ist sie **aus** (oder ohne Netzwerk),
  verhält sie sich wie eine normale Kiste. Items, die nicht ins Netzwerk dürfen, bleiben in der Kiste.
  Beim Abbauen behält die Kiste ihr Netzwerk und lässt ihren Inhalt fallen wie eine normale Kiste.

## Gruppen

Der Truhen-Reiter rechts am Terminal klappt das Gruppen-Menü auf (`×` klappt es wieder ein):

- **`+`** legt eine neue Gruppe an. Der Name ist gleich markiert: einfach lostippen, Enter speichert.
- **Stift** (✎) neben dem Gruppennamen: Gruppe bearbeiten. Dann gilt:
  - Items aus dem Netzwerk-Raster **in die Gruppe ziehen** (Maustaste gedrückt halten). Es wird dabei nichts entnommen.
    Ein Item auf dem Cursor kommt mit einem Klick auf die Gruppe hinein.
  - Klick auf ein Item der Gruppe entfernt es aus der Gruppe.
  - Unten: Name ändern, **Teilen / Privat**, **Löschen** (zweimal klicken), **Fertig**.
- **Klick auf den Gruppennamen** blendet die Gruppe ein oder aus (Kästchen links). Das gilt nur für dich,
  andere Spieler wählen ihre angezeigten Gruppen selbst.
- Außerhalb des Bearbeitens funktionieren die Items einer Gruppe wie im Raster: herausnehmen, Item vom Cursor einlagern,
  `R` / `U` mit JEI. Sorten, die gerade nicht im Netzwerk sind, stehen grau mit `0` da.

Gruppen gehören zum Netzwerk und sind zuerst **privat**. Geteilte Gruppen (blauer Name) sehen alle, die das
Netzwerk nutzen. Bearbeiten, teilen und löschen darf nur, wer die Gruppe angelegt hat. Wird ein Netzwerk gelöscht,
verschwinden auch seine Gruppen.

## Umstieg von Version 1.0

In Version 1.0 hatte jedes Terminal seinen eigenen Speicher. Diese Speicher werden beim ersten Benutzen automatisch zu
Netzwerken (Name „Terminal x, y, z“). Weil früher jeder an das Terminal herankam, sind diese Netzwerke **öffentlich und
gehören allen** (jeder darf sie umbenennen; teilen/privat geht bei ihnen nicht). Das gilt auch für volle Terminal-Items
aus Version 1.0, sobald sie platziert werden. Es geht dabei nichts verloren.

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
| Suchfeld | Filtert nach Name, `@minecraft` filtert nach Mod. Rechtsklick leert die Suche. Ein Klick irgendwo anders (auch neben das Terminal, z. B. auf JEI) beendet die Eingabe |
| Mausrad über dem Raster | Scrollen |
| Netzwerk-Knopf (oben links) | Netzwerk wählen, anlegen, umbenennen, teilen oder löschen |
| Sortier-Knopf (oben rechts) | Name / Anzahl / Mod, jedes Terminal merkt sich seine Sortierung |
| Truhen-Reiter (rechts) | Gruppen-Menü auf- und zuklappen |

Die Anzahl der sichtbaren Zeilen passt sich der Fensterhöhe an (3 bis 8 Zeilen).

## Rezepte

ME-Terminal:

```
Eisen   Glasscheibe  Eisen
Redstone  Truhe    Redstone
Eisen    Diamant    Eisen
```

ME-Transferkiste:

```
Eisen    Enderperle  Eisen
Redstone   Truhe    Redstone
Eisen      Eisen     Eisen
```

Funk-ME-Terminal:

```
   -      Enderauge      -
Eisen    Glasscheibe   Eisen
Redstone   Diamant    Redstone
```

## Installation

1. [Fabric Loader](https://fabricmc.net/use/) für Minecraft **26.3** installieren.
2. [Fabric API](https://modrinth.com/mod/fabric-api) (Version für 26.3) in den `mods`-Ordner legen.
3. `build/libs/simpleme-1.0.1+26.3.jar` in den `mods`-Ordner legen.
4. Optional: [JEI](https://modrinth.com/mod/jei) für 26.3 (Fabric) – dann gibt es den `+`-Knopf in den Rezepten.

Die Mod muss auf dem Server **und** auf dem Client installiert sein.

## Für Server-Admins

- **Wo liegen die Items?** Jedes Netzwerk hat eine eigene Datei in der Welt: `<Welt>/data/simpleme/storage/<id>.dat`.
  Die Liste der Netzwerke (Name, Besitzer, öffentlich/privat) steht in `<Welt>/data/simpleme/networks.dat`,
  die Gruppen stehen in `<Welt>/data/simpleme/groups.dat`.
  Blöcke und Items tragen nur die Netzwerk-ID, dadurch bleiben sie immer klein
  (sonst könnte z. B. ein Item voller Bücher Spieler vom Server kicken, wie beim alten „Book-Ban“).
- **Backups** müssen den `data`-Ordner der Welt enthalten (bei normalen Welt-Backups ist das automatisch so).
- **Pro Spieler höchstens 64 eigene Netzwerke**, damit niemand die Weltdaten mit leeren Netzwerken zumüllt.
- **Mod entfernen = Items weg.** Wie bei jeder Speicher-Mod verschwinden die Terminals samt Inhalt, wenn die Mod entfernt wird.
- Items einer entfernten *anderen* Mod werden beim Laden übersprungen, der Rest des Speichers bleibt erhalten.
- Performance: ein Terminal mit 20.000 Item-Sorten öffnet in unter 20 ms; Klick-Spam wird auf einen Sync pro Tick begrenzt.

## Tests

Die Mod hat eine automatische Testsuite (Fabric GameTest):

- `./gradlew runGameTest`: 42 Logik-Tests auf einem Test-Server (Speicher, Transaktionen, Trichter, Abbauen/Explosion,
  Speichern/Laden, Crafting inkl. Rezeptbuch/Reste/Shift-Klick, Dupe-Checks mit zwei Spielern, Performance mit 20.000 Sorten,
  Netzwerke anlegen/wechseln/teilen/löschen inkl. Rechte zweier Spieler, Umstieg von Version 1.0,
  Funk-Terminal ohne Terminal-Block, Transferkiste an/aus/ohne Netzwerk, Trichter → Transferkiste → Netzwerk,
  Pick-Block inkl. vollem Inventar und fremden privaten Netzwerken).
- `./gradlew runClientGameTest`: startet Minecraft und testet im echten Spiel (Einzelspieler **und** dedizierter Server):
  GUI, Klicks, Suche, Sortierung, Scrollen, Fenstergrößen, Rezeptbuch, Deutsch/Englisch, Abbauen/Tragen/Platzieren,
  Neuladen der Welt, Netzwerkliste mit der Maus bedienen (verbinden, neues Netzwerk anlegen), An/Aus-Knopf der Transferkiste,
  Funk-Terminal und Pick-Block 3000 Blöcke entfernt und ganz ohne Terminal-Block. Screenshots landen in `build/run/clientGameTest/screenshots`.

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
