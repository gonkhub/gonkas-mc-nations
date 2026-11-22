// Vanilla village life is gutted in this pack: Minecolonies colonies are the only towns, and
// thee's economy is built on them. Village worldgen is switched off in
// config/sparsestructures.json5; this script removes the two things worldgen does not cover.
//
// - Wandering traders (and the llamas they bring). A merchant who appears at your door with
//   goods from nowhere is a free import that skips every colony, caravan and price in the pack.
// - Zombie villagers. They spawn naturally (a share of ordinary zombie spawns) and from a
//   zombie killing a villager. Without villages they only exist to be cured into villagers.
//
// VILLAGERS THEMSELVES ARE KEPT, on purpose. Create: Thaumaturgy's Arcane Engineer is a
// villager profession that lives in its own Engineer houses, and it is how players get blank
// Arcane Formulas and the maps to the Workshop and the Colossus Foundry. Removing villagers
// would delete that content with no other source.
//
// Cancelling the spawn also catches these entities loading from an old save, since NeoForge
// fires the same join event for both, so a world generated before this script loses them too.
// A villager killed by a zombie simply dies instead of converting.
const GUTTED = ['minecraft:wandering_trader', 'minecraft:trader_llama', 'minecraft:zombie_villager']

GUTTED.forEach(type => {
  EntityEvents.spawned(type, event => {
    event.cancel()
  })
})
