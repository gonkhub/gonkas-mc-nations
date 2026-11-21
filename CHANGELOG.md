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

_nothing yet_

## 0.0.1 - 2026-11-21

The first public package: Minecraft 1.21.1, NeoForge 21.1.265, 134 mods.

**What the pack is**

- Minecolonies is the settlement layer. Vanilla villages and villagers are removed
  entirely - every settlement in the world is one somebody built and staffed.
- Nations and empires, with vassalage by agreement rather than conquest: a vassal keeps
  its own name, its own colony and its own citizens.
- Inter-colony markets and caravans that ride Create's rail network, so trade between
  two colonies is a train that can be raided, not a menu.
- Numismatics is the currency. Treasuries are ledgers denominated in cogs.
- The Sculk is the world's antagonist and its only source of new money.
- BlueMap draws empire territory, colony borders, the Sculk and the rail network.

**You can found more than one colony.** Empire builders were limited to a single colony
each, which made it impossible to run several provinces yourself - you could only ever
grow by taking vassals. Founding a second colony no longer requires abandoning the first.

**Recipes repaired, carried over from The Factory Must Gonk**

- **Popped Purpurite and Cracked Light Jade Tiles can be made again.** Both had no
  working recipe at all: Astrological ships its smelting recipes in a newer format than
  this Minecraft version can read, so the game dropped them and left two blocks nothing
  could produce.
- **The white Placard can be dyed again** - the one placard colour Create Deco's broken
  recipe left unobtainable.
- **The Alternator and Electric Motor can be automated with a Packager.** Their stock
  recipes are too wide for a 3x3 crafter array, so a Packager silently refused them;
  there are now 3x3 versions of both. The originals still work if you have already built
  a wide crafter wall. Slightly cheaper on iron and brass plate, same of everything
  expensive.
