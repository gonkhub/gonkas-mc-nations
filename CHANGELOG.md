# Changelog

Player-facing changes to Gonka's MC Nations, newest first.

Each released heading is a git tag and a GitHub release. **This file is not published**
— release bodies on GitHub are deliberately empty — so this is the only record of what
each version changed, and it is what a rollback gets chosen from. `scripts/release.py
publish` still refuses a version whose section is empty or a placeholder, for that
reason. Write under **Unreleased** as you work; `scripts/release.py bump` dates it and
opens a fresh one.

Keep entries in players' terms. "Vassalage now requires a road between the two seats" is
an entry; "re-pointed `ColonyBridge` at the resolver queues" is not. The engineering
record lives in `docs/`, which deliberately stays out of this repo.

<!-- new entries go under Unreleased -->

## Unreleased

- _nothing yet_


## 0.1.4 - 2026-11-21

- A newly found Ancient City can now be woken. Killing things beside one feeds it;
  before this a fresh city ignored everything you did to it.
- The sculk now turns ground at the scale its territory claims, growing outward in
  real sculk fields instead of a few patches around catalysts.


## 0.1.3 - 2026-11-21

- Each Ancient City now has its own pool of points it spends to spread and to send
  invasions. A city nobody bothers earns almost nothing and can never afford a wave.
- Cutting a city's sculk back now feeds it. Clearing a hotspot is a commitment: get in
  and kill the Heart, or leave it angrier than you found it.
- `/sculk pressure` shows each city's balance, its income and what an invasion costs.
- Roads between connected colonies are drawn on the web map, following the signs.


## 0.1.2 - 2026-11-21

- Sculk mobs now hunt animals, so the sculk grows where there is life to kill.
- The pack download no longer carries developer files it never needed.


## 0.1.1 - 2026-11-21

- The sculk no longer claims whole chunks. It spreads from catalysts, growing where
  things die, instead of converting the ground around it on sight.
- Ancient cities are quiet again when you find them. They grow because an empire moves
  in next to them, not because you walked past.
- Sculk territory is drawn in purple on the map.
- Sculk raids now take ground, not just lives.
- Distant Horizons draws a shorter distance, fixing the blocky terrain on the server.


## 0.1.0 - 2026-11-21

- The End has been rebuilt on a new mod, The Beyond, replacing Astrological. **The old End
  was regenerated: anything you built there is gone.**
- Caravans between settlements are now real. NPC porters carry the goods, travel faster and
  safer with a saddle on the shelf, can be robbed on the road, and bring a counter-load home.
- Ancient cities now read as lost from the surface: the ground above one is already sculk.
- Climb walls and ceilings with the Tentacle Rig (Create: Octo Arms).
- Better horses, cameras, cooking, brewing and worldgen decoration: 19 mods added.

## 0.0.1 - 2026-11-21

First public package. Minecraft 1.21.1, NeoForge 21.1.265, 134 mods.

- Popped Purpurite and Cracked Light Jade Tiles are craftable again.
- The white Placard can be dyed again.
- The Alternator and Electric Motor can be automated with a Packager.
- A player may found more than one colony.
