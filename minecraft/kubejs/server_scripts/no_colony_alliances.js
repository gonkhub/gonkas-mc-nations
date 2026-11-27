// One colony-to-colony trade system: thee's (gonkhub/gonkas-mc-nations#35, supersedes #30).
//
// Create: Colony Logistics 1.3.3 adds colony alliances through its Foremen's Hut: an allied colony's
// orders filled from another colony's crafters or Create storage and sent by train. That is a
// second trade system beside thee's caravans, boats and trains, with its own rules, none of thee's
// prices or routes, and no way to switch it off in its config. The user's call (2026-11-26): fold
// it into thee, so the Foremen's Hut can no longer be crafted.
//
// KEPT: the Freight Depot and its Logistics Coordinator and Packer Agents, which link a colony to
// its own player's Create storage. That is not trade between colonies, and thee has no equivalent.
//
// A Foremen's Hut already built keeps working; this only stops new ones. Re-registering nothing
// under the recipe id means a future Colony Logistics update that changes the recipe stays removed.
ServerEvents.recipes(event => {
  event.remove({ id: 'cclogistics:foremens_hut' })
})
