# Changelog

Player-facing changes to Gonka's MC Nations, newest first.

Each released heading is a git tag and a GitHub release, and the body of that heading is
what the release notes say — `scripts/release.py publish` reads this file and refuses to
publish a version whose section is empty or still a placeholder. Write under
**Unreleased** as you work; `scripts/release.py bump` dates it and opens a fresh one.

Keep entries in players' terms. "Vassalage now requires a road between the two seats" is
an entry; "re-pointed `ColonyBridge` at the resolver queues" is not. The engineering
record lives in `docs/`, which deliberately stays out of this repo.

<!-- new entries go under Unreleased -->

## Unreleased

- **You can found more than one colony.** Empire builders were limited to a single
  colony each, which made it impossible to run several provinces yourself — you could
  only ever grow by taking vassals. Founding a second colony no longer requires
  abandoning the first.
- **Popped Purpurite and Cracked Light Jade Tiles can be made again.** Both had no
  working recipe at all: Astrological ships its smelting recipes in a newer format than
  this Minecraft version can read, so the game dropped them and left two blocks nothing
  could produce.
- **The white Placard can be dyed again** — the one placard colour Create Deco's broken
  recipe left unobtainable.
- **The Alternator and Electric Motor can be automated with a Packager.** Their stock
  recipes are too wide for a 3×3 crafter array, so a Packager silently refused them;
  there are now 3×3 versions of both. The originals still work if you have already built
  a wide crafter wall. Slightly cheaper on iron and brass plate, same of everything
  expensive.

## 0.0.1 - 2026-11-20

First built package, not released to anyone.

- Minecraft 1.21.1, NeoForge 21.1.265, 134 mods.
- Minecolonies as the settlement layer; vanilla villages and villagers removed entirely.
- `thee` 1.28.0: nations and empires, vassalage by agreement, inter-colony markets and
  caravans on the rail network, the Sculk as the world's antagonist and its only source
  of new money.
- Numismatics is the currency; treasuries are ledgers denominated in cogs.
- BlueMap draws empire territory, colony borders, the Sculk and Create's rail network.
