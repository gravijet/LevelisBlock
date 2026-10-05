# LevelisBlock

Paper challenge plugin with two modes: spend XP levels to unlock blocks, or use accumulated levels to expand the world border.

## Build

Requires JDK 25 and Paper 26.2.

```sh
./gradlew build
```

The JAR is written to `build/libs/`.

## Commands

| Command | Purpose |
| --- | --- |
| `/timer start`, `/timer pause`, `/timer resume` | Control the challenge timer |
| `/timer reset`, `/timer set <time>` | Reset or set elapsed time |
| `/levels`, `/blocks`, `/border` | View progress |
| `/lb mode <level_block|level_border>` | Select the challenge |
| `/lb xp <individual|shared>` | Select the XP model |
| `/lb config <option> [value]` | Change settings |
| `/lb save`, `/lb reload` | Save or reload configuration |
| `/reset confirm` | Regenerate worlds; stops the server by default |

Permissions: `levelblock.play`, `levelblock.admin` and `levelblock.bypass`. A player death pauses the challenge and moves players to spectator mode; `/timer resume` restores survival mode.
