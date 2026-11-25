// Gives the saddle a crafting recipe (gonkhub/gonkas-mc-nations#23).
//
// Vanilla has none: saddles only come from chests, fishing and trades, and this pack cuts
// most of those. Wandering traders are gone (no_village_life.js) and so are villages. The
// pack's only saddle recipes un-make one (Create milling, Farmer's Delight salvaging), so
// there was no way to make a saddle when you wanted to ride.
//
// Leather over a string-and-iron girth, with iron for the stirrups:
//
//   L L L
//   S I S
//   I . I
//
// Three leather, two string, three iron: a real cost, but nothing rare. It uses a kubejs: id
// because no mod has a saddle crafting recipe for this to replace.

ServerEvents.recipes(event => {
    event.shaped('minecraft:saddle', [
        'LLL',
        'SIS',
        'I I'
    ], {
        L: 'minecraft:leather',
        S: 'minecraft:string',
        I: 'minecraft:iron_ingot'
    }).id('kubejs:saddle')
})
