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

- Colonies in the same nation trade for free. When a colony needs something, or its
  Caravan Station is below its minimum stock, another colony of the nation sends it at no
  charge: no price and no freight, so the receiving colony needs no money. The caravan still
  packs food for the road and travels as long as any other, but it sets off as soon as the
  goods are ready instead of waiting for the next trade round. If the sending colony's
  station holds none, it fetches them from its own Warehouse first.
- A Caravan Station now looks at everything its colony is waiting on, not only builder
  materials: upgrades, crafters' ingredients, food, fuel, tools, and items a citizen's quest
  asks for. Requests that any of several items can fill (any food, any planks) are filled
  with whatever a seller has. Research is not included: its cost comes from your own
  inventory when you start it.
- The Caravan Station's tab is now **Minimum Stock**, the same list craftsman huts have:
  press Add, search for an item, type how many to keep, and press Done. X removes a line.
  The old export screen is gone.
- New **Trade Stats** tab on the Caravan Station: every item the colony has sent and
  received, spurs earned, spent and net, and caravans departed and received. It counts from
  this update on.


## 0.2.3 - 2026-11-22

- The Caravan Station tab no longer cuts the last digit off an export target: a target
  of 256 showed as "0/25".


## 0.2.2 - 2026-11-22

- The Caravan Station now does its job. Before, it almost never asked Couriers for the
  goods you set it to keep, so nothing reached it and nothing shipped.
- New export screen: press **Manage exports** on the Caravan Station's tab, put an item in
  the slot (your item is not taken), type how many to keep ready, and press Set. The tab
  itself now shows what is kept, what is held, and what is on the road.
- Colonies trade what they need. If your Builder is short of stone bricks and a colony
  you are linked to by road or rail is exporting them, a caravan is sent once your
  colony can pay for the goods and the freight. Small orders are filled too.
- A colony no longer orders the same goods twice while a delivery is still being carried
  into its Warehouse, and no longer re-sells goods it just imported.
- Caravans waiting to leave are shown on the station tab with how much food they still
  need. A station that exports should keep some food (bread, for example) for the road.
- Trade can travel by Create train: a colony with a train station beside its Caravan
  Station can send and receive goods on a shared rail network.


## 0.2.1 - 2026-11-22

- Opening the Caravan Station's tab no longer crashes the game.


## 0.2.0 - 2026-11-22

- New world generation (Continents and Cascades): land comes in large continents separated
  by wide oceans, with reshaped terrain. Only newly generated land is affected.
- Vassalage is replaced by colony membership. A nation now invites colonies directly
  (`/nation invite <nation> <colony>`), or a colony asks to join (`/nation join <colony>
  <nation>`), and the other side answers with `/nation accept`. Inviting a colony no longer
  fails with "no nation with that name". Colonies can leave at any time; a capital has to
  dissolve its nation before joining another. Old vassal links are dropped, so former
  vassal nations start out independent.
- A nation's founder can draw its territory on Xaero's World Map with
  `/nation border draw <nation>`: drag a loop around your colonies and press Enter. It
  shows as a dashed outline around the colonies' own borders, and nations that have not
  drawn one get an automatic outline.
- The Caravan Station now has its own screen. Open the hut to see what it is holding, set
  how much of each good it keeps ready to ship, and watch caravans coming and going with
  the time until they arrive.
- Setting what a station trades no longer needs a command or operator rights: hold an
  item, open the station and press Keep. Anyone with permission to manage the colony's
  buildings can do it.
- Dropped leftover compatibility patches for Apokinetics, which this pack no longer
  includes. They did nothing, and one of them reported a failure on every server start.
- Vanilla villages no longer generate, and wandering traders and zombie villagers are gone.
  Villagers themselves stay, for Create: Thaumaturgy's Arcane Engineers.
- Food prices at a colony now rise when its citizens are going hungry, read from the
  colonists themselves rather than from what the caravan station holds. A well-fed colony
  no longer pays famine prices.
- Courier packages are removed. They could be claimed but never delivered.
- A caravan station no longer makes its own colony import the goods it was set to export.
- The Sculk can now invade a colony that is not part of a nation.
- Messages no longer tell you to run `/settlement` commands that do not exist.
- Fixed a crash on the first start of a new world, when a structure near spawn spawned
  mobs before Mob Champions was ready.


## 0.1.5 - 2026-11-22

- Browsing to the Caravan Station in the build tool no longer crashes the game.
- The Caravan Station is now listed under Infrastructure in whatever building style you
  have selected, so there is no need to switch style packs to find or place one.


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
