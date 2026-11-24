# Desire Paths (NeoForge 1.21.1)

A NeoForge port of [Desire Paths by Matjojo](https://github.com/matjojo/desire-paths). The original is a Fabric mod for 1.15.2 under GPL-3.0, so this port is GPL-3.0 too. The textures are the original mod's. Tracking issue: #12.

Ground wears down slowly where players, villagers, MineColonies colonists and visitors, and caravan porters from the E walk:

    grass / podzol / mycelium -> trampled (6 stages) -> dirt -> trampled dirt (6) -> coarse dirt
    -> worn coarse dirt (6, slower) -> dirt path

- Partly worn blocks grow back one stage at a time when nobody walks on them.
- Plain dirt grows back through normal grass spread.
- Coarse dirt and dirt paths stay until someone changes them.
- **No block in the chain changes movement speed.** The `noSpeedChange` game test enforces this.

## Who wears paths

The entity type tag `desire_paths:wears_paths` decides who wears paths. Add to it with a datapack or KubeJS; no rebuild needed. Players who are sneaking, spectating or in adventure mode never wear paths. An animal wears paths when something in the tag is riding it, so caravan horses do; `mountsWearPaths` turns that off.

## Config

The config is per world, in `serverconfig/desire_paths-server.toml`. Put a copy in `minecraft/defaultconfigs/` to seed new worlds.

| Option | Default | Meaning |
|---|---|---|
| `wearPerBlockWalked` | 0.15 | Average wear stages added per block walked. About 47 crossings turn grass into dirt, and about 47 more turn dirt into coarse dirt. |
| `pathStageMultiplier` | 0.25 | Slows the coarse dirt -> dirt path step. About 190 crossings at the defaults. |
| `regrowChance` | 0.115 | Chance per random tick to grow back one stage. About one stage every 10 minutes. |
| `minStepPerTick` | 0.03 | Minimum blocks moved in one tick for a step to count. |
| `mountsWearPaths` | true | Whether ridden animals wear paths. |

## Build and test

    ./gradlew build               # -> build/libs/desire_paths-<version>.jar
    ./gradlew runGameTestServer   # in-game tests, in src/gametest (not shipped)

Building needs `maven.neoforged.net` and Mojang's download hosts.

To ship a build:

1. Bump `mod_version` in `gradle.properties`.
2. Copy the jar into `minecraft/mods/` and remove the old one.
3. Update `hand-installed.sha1`.
4. Run `python scripts/verify-custom-jars.py --record`.

The ledger refuses a rebuilt jar under a version it has already seen.
