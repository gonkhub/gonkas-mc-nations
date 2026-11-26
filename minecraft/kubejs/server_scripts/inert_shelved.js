// Inert cobblestone is shelved (gonkhub/gonkas-mc-nations#31, thee 1.49.0).
//
// Generators make normal cobblestone and stone again: thee's `generators.inert` switch is off by
// default. The inert blocks themselves stay registered, because worlds already hold them and
// unregistering a block deletes every placed one and every stack. This lets players turn what
// they already have back into the real block, one for one, in a crafting grid.
//
// That is exactly the laundering inert_crafting.js forbids, and it is safe only while the switch
// is off: a generator now makes the real block anyway, so converting inert cobble gains nothing a
// generator does not already give. If `generators.inert` is ever turned back on, DELETE THIS FILE
// first, or every generator is an ore farm again through this one recipe.
//
// inert_crafting.js is left in place. It only adds crafts for the inert items and removes ten
// Silent Gear salvage recipes, which does no harm with the switch off and is needed again the
// day it comes back on.

ServerEvents.recipes(event => {
    event.shapeless('minecraft:cobblestone', ['thee:inert_cobblestone']).id('thee:inert_shelved/cobblestone');
    event.shapeless('minecraft:stone', ['thee:inert_stone']).id('thee:inert_shelved/stone');
});
