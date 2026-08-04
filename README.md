# LevelBlock

Zwei Challenges für Paper, im Stil einer BastiGHG-Challenge. Kein Menü, kein Scoreboard,
keine Bossbar – nur ein Timer in der Actionbar und Befehle.

**Level = Block** – ihr startet auf einer 3×3-Fläche. Jedes XP-Level gibt Guthaben. Lauf
einfach gegen die rote Linie am Boden, dann wird der Block davor freigeschaltet. Kein Item,
kein Menü, und pro Schritt immer nur genau ein Block.

**Level = Border** – jedes XP-Level vergrößert die Worldborder. Die Level aller Spieler
zählen zusammen.

Ein Tod beendet die Challenge für alle. Der Gestorbene behält sein komplettes Inventar,
alle Spieler kommen in den Zuschauermodus.

## Befehle

| Befehl | Was es macht |
| --- | --- |
| `/timer start` | Challenge dort starten, wo du stehst |
| `/timer pause` / `resume` | Uhr anhalten und weiterlaufen lassen |
| `/timer reset` | Fortschritt und Zeit zurücksetzen |
| `/timer set <zeit>` | Zeit setzen, z. B. `90`, `10m`, `1:30:00` |
| `/lb` | Status und eigenes Guthaben |
| `/lb top` | Rangliste der gesammelten Level |
| `/lb stop` | Challenge beenden |
| `/lb mode <level_block\|level_border>` | Spielmodus umstellen |
| `/lb xp <individual\|shared>` | Erfahrungsmodell umstellen |
| `/lb credits <set\|give\|take> <spieler> <menge>` | Guthaben verwalten |
| `/lb config <option> [wert]` | Jede Einstellung live ändern |
| `/lb bypass` | Begrenzung für dich selbst ignorieren |
| `/lb save` / `/lb reload` | Speichern bzw. Config neu laden |
| `/reset confirm` | Alle Welten löschen und mit neuem Seed neu generieren |

`/lb` heißt auch `/levelblock` und `/levelborder`.

Rechte: `levelblock.play` (Standard: alle), `levelblock.admin` und `levelblock.bypass`
(Standard: OP).

## Erfahrungsmodelle

* `INDIVIDUAL` – jeder hat seine eigene Erfahrung und kauft seine eigenen Blöcke. Bei
  `LEVEL_BORDER` zählen die Level aller Spieler trotzdem zusammen für die Border.
* `SHARED` – alle haben immer exakt gleich viel Erfahrung und teilen ein gemeinsames
  Guthaben.

## Zwei Dinge, die man wissen sollte

**Der Weltspawn wird verschoben.** Minecraft lässt im Umkreis von 24 Blöcken um den
Weltspawn grundsätzlich nichts spawnen, und `spawn-protection` aus der `server.properties`
verbietet Nicht-Ops dort das Bauen. Wer genau auf dem Weltspawn startet, hätte also weder
Mobs noch die Möglichkeit, Blöcke abzubauen. Darum schiebt `/timer start` den Weltspawn um
`start.spawn-point-distance` Blöcke (Standard 512) weg. Respawns landen trotzdem am
Startpunkt. Mit `0` bleibt der Weltspawn unangetastet.

**`/reset confirm` stoppt den Server.** Gelöscht wird beim Herunterfahren, weil der Server
vorher noch Dateien offen hat; im selben Schritt wird ein neuer Zufalls-Seed in die
`server.properties` geschrieben. Die meisten Hoster starten danach automatisch neu. Falls
nicht: `reset.shutdown-server: false` setzen, dann wird der Reset nur vorbereitet und du
stoppst selbst.

Die Border in `LEVEL_BORDER` wird immer **nur den Spielern** gesetzt, nie der Welt. Dadurch
spawnen und laufen Mobs weiterhin überall – nur die Spieler kommen nicht raus.

## Bauen

```
./gradlew build
```

Ergebnis: `build/libs/LevelBlock-1.0.0.jar`. Benötigt JDK 25 und Paper 26.2.
