// Lets generator cobble and stone (thee:inert_cobblestone / thee:inert_stone) into every craft
// that cannot feed an AFK farm (gonkhub/gonkas-mc-nations#25).
//
// thee swaps the block a lava+water generator makes for an "inert" twin with no tags, so it fits
// no recipe and an unattended generator cannot be refined into ores. That was broader than the
// point. The farm that matters is cobble -> gravel -> sand -> sifted ores, plus anything that
// launders inert cobble back into the real block, which re-enters that chain. Stairs, slabs,
// walls, levers, pistons, furnaces and stone tools do none of that, so refusing them only made
// generator cobble useless for building.
//
// The rule: inert cobble/stone may go into a crafting-table, stonecutter or mechanical-crafter
// recipe unless the output can be refined or turned back into the real block. Smelting, milling,
// crushing, mixing, haunting and every other processing recipe are untouched, so inert cobble
// still cannot be smelted into stone or milled into gravel.
//
// Audited 2026-11-25 over every recipe in the pack (vanilla, NeoForge tags and all 164 mods):
// 141 recipes take cobblestone or stone. Each crafted output was checked for what consumes it.
// These are the only crafted outputs that lead back into the farm, and they stay real-only:
//
//   andesite, diorite     Create crushes diorite into quartz, both crush via Ex Nihilo, andesite
//                         mills back into cobblestone and mixes into andesite alloy.
//   mossy_cobblestone     It is in c:cobblestones itself: lava mixing, blackstone, netherrack.
//   quark:sturdy_stone    Its uncompress recipe gives back 4 real cobblestone.
//   silentgear crude_*    Silent Gear parts salvage back into the material's item, cobblestone.
//
// Stone tools are allowed, but Silent Gear can salvage a stone tool (or the Silent Gear tool it
// converts into) back into three real cobblestone. Those ten recipes are removed below. Nobody
// salvages a stone pickaxe for its value, so nothing else is lost.
//
// Anything outside this audit is not covered: a new mod that crushes stairs, or recycles any of
// these outputs, needs this file re-checked.
//
// Thaumaturge's arcane workbench recipes (smelter, recharge pedestal, tube restrictor) are left
// out. KubeJS has no schema for that recipe type, so they cannot be edited from here.

const INERT_COBBLE = 'thee:inert_cobblestone'
const INERT_STONE = 'thee:inert_stone'

// Crafted outputs that lead back into the farm (see above).
const FARM_FEEDERS = [
    'minecraft:andesite',
    'minecraft:diorite',
    'minecraft:mossy_cobblestone',
    'quark:sturdy_stone',
    'silentgear:crude_hammer',
    'silentgear:crude_knife',
    'silentgear:crude_tool_parts'
]

// Each exact ingredient a recipe can use for cobblestone or stone, and its inert twin. `exact`
// matters: a loose match on 'minecraft:cobblestone' would also catch a '#c:cobblestones'
// ingredient (because it contains cobblestone) and replace the whole tag with two items.
const TWINS = [
    ['minecraft:cobblestone', INERT_COBBLE],
    ['#c:cobblestones', INERT_COBBLE],
    ['#c:cobblestones/normal', INERT_COBBLE],
    ['minecraft:stone', INERT_STONE],
    ['#c:stones', INERT_STONE],
    ['#createbigcannons:stone', INERT_STONE]
]

ServerEvents.tags('item', event => {
    if (!Platform.isLoaded('thee')) return

    // Stone tools, furnaces and brewing stands take these tags rather than an item. Every recipe
    // reading them was in the audit and is a crafted tool or workstation.
    event.add('minecraft:stone_tool_materials', INERT_COBBLE)
    event.add('minecraft:stone_crafting_materials', INERT_COBBLE)
    event.add('quark:stone_tool_materials', INERT_COBBLE)
})

ServerEvents.recipes(event => {
    if (!Platform.isLoaded('thee')) return

    const crafting = {
        or: [
            { type: 'minecraft:crafting_shaped' },
            { type: 'minecraft:crafting_shapeless' },
            { type: 'minecraft:stonecutting' },
            { type: 'create:mechanical_crafting' }
        ],
        not: FARM_FEEDERS.map(item => ({ output: item }))
    }

    TWINS.forEach(twin => {
        event.replaceInput(crafting, { match: twin[0], exact: true }, Ingredient.of([twin[0], twin[1]]))
    })

    if (Platform.isLoaded('silentgear')) {
        ['sword', 'pickaxe', 'axe', 'shovel', 'hoe'].forEach(tool => {
            event.remove({ id: 'silentgear:salvaging/stone_' + tool })
            event.remove({ id: 'silentgear:gear/convert/stone_' + tool })
        })
    }
})
