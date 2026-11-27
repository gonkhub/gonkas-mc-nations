package com.gonkhub.track_planner;

import com.simibubi.create.AllTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a survival build gets its track and tools and puts what it digs up: a player's inventory, or the
 * chests and vaults on a track-laying train. actor() is who the build acts as, for claims and permissions.
 */
public interface Supplies {
    ServerPlayer actor();

    /** Nothing is charged (a creative player). */
    boolean free();

    /** True when Create's own placement takes the track from actor()'s inventory (a survival player). */
    boolean createTakesTrack();

    /** The Create track kind carried most of, or null. */
    Item track();

    int count(Item item);

    void take(Item item, int n);

    /** A tool that mines the block properly and has more than `used` durability to spare; null if none. */
    Tool tool(BlockState st, Map<Object, Integer> used);

    void give(ItemStack drop);

    /** A tool and how to wear it down. key identifies it across a build (the same slot or stack). */
    record Tool(Object key, ItemStack stack, Runnable wear) {}

    static boolean isTrack(ItemStack st) { return !st.isEmpty() && AllTags.AllBlockTags.TRACKS.matches(st); }

    /** Better tool for the block than `best`, or null. */
    static boolean better(ItemStack stack, BlockState st, float bestSpeed, int spare) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return false;
        if (stack.getDestroySpeed(st) <= bestSpeed) return false;
        if (st.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(st)) return false;
        return spare > 1;   // never break the tool outright
    }

    // ---- a player's inventory ----
    final class PlayerInventory implements Supplies {
        final ServerPlayer p;

        public PlayerInventory(ServerPlayer p) { this.p = p; }

        public ServerPlayer actor() { return p; }
        public boolean free() { return p.isCreative(); }
        public boolean createTakesTrack() { return true; }

        List<ItemStack> stacks() {
            List<ItemStack> l = new ArrayList<>(p.getInventory().items);
            l.addAll(p.getInventory().offhand);
            return l;
        }

        public Item track() {
            Map<Item, Integer> counts = new HashMap<>();
            for (ItemStack st : stacks()) if (isTrack(st)) counts.merge(st.getItem(), st.getCount(), Integer::sum);
            return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }

        public int count(Item item) {
            int n = 0;
            for (ItemStack st : stacks()) if (st.is(item)) n += st.getCount();
            return n;
        }

        public void take(Item item, int n) {
            for (ItemStack st : stacks()) {
                if (n <= 0) return;
                if (!st.is(item)) continue;
                int k = Math.min(n, st.getCount());
                st.shrink(k);
                n -= k;
            }
        }

        public Tool tool(BlockState st, Map<Object, Integer> used) {
            ItemStack best = null;
            float speed = 1.0f;
            for (ItemStack stack : stacks()) {
                int spare = stack.getMaxDamage() - stack.getDamageValue() - used.getOrDefault(stack, 0);
                if (better(stack, st, speed, spare)) { best = stack; speed = stack.getDestroySpeed(st); }
            }
            if (best == null) return null;
            ItemStack tool = best;
            return new Tool(tool, tool, () -> tool.hurtAndBreak(1, p.serverLevel(), p, item -> {}));
        }

        public void give(ItemStack drop) {
            ItemStack copy = drop.copy();
            if (!p.getInventory().add(copy) && !copy.isEmpty()) p.drop(copy, false);
        }
    }

    // ---- a storage (a train's chests and vaults) ----
    final class Storage implements Supplies {
        final IItemHandlerModifiable inv;
        final ServerPlayer actor;
        final ServerLevel level;
        final BlockPos spill;

        /** spill: where drops that don't fit go */
        public Storage(IItemHandlerModifiable inv, ServerPlayer actor, ServerLevel level, BlockPos spill) {
            this.inv = inv;
            this.actor = actor;
            this.level = level;
            this.spill = spill;
        }

        public ServerPlayer actor() { return actor; }
        public boolean free() { return false; }
        public boolean createTakesTrack() { return false; }

        public Item track() {
            Map<Item, Integer> counts = new HashMap<>();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (isTrack(st)) counts.merge(st.getItem(), st.getCount(), Integer::sum);
            }
            return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }

        public int count(Item item) {
            int n = 0;
            for (int i = 0; i < inv.getSlots(); i++) if (inv.getStackInSlot(i).is(item)) n += inv.getStackInSlot(i).getCount();
            return n;
        }

        public void take(Item item, int n) {
            for (int i = 0; i < inv.getSlots() && n > 0; i++) {
                if (!inv.getStackInSlot(i).is(item)) continue;
                n -= inv.extractItem(i, n, false).getCount();
            }
        }

        public Tool tool(BlockState st, Map<Object, Integer> used) {
            int bestSlot = -1;
            float speed = 1.0f;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                int spare = stack.getMaxDamage() - stack.getDamageValue() - used.getOrDefault(i, 0);
                if (better(stack, st, speed, spare)) { bestSlot = i; speed = stack.getDestroySpeed(st); }
            }
            if (bestSlot < 0) return null;
            int slot = bestSlot;
            return new Tool(slot, inv.getStackInSlot(slot), () -> {
                // storages hand out copies on some handlers: wear a copy and put it back
                ItemStack t = inv.getStackInSlot(slot).copy();
                int wear = EnchantmentHelper.processDurabilityChange(level, t, 1);
                t.setDamageValue(t.getDamageValue() + wear);
                inv.setStackInSlot(slot, t.getDamageValue() >= t.getMaxDamage() ? ItemStack.EMPTY : t);
            });
        }

        public void give(ItemStack drop) {
            ItemStack rest = ItemHandlerHelper.insertItemStacked(inv, drop.copy(), false);
            if (!rest.isEmpty()) Block.popResource(level, spill, rest);
        }
    }
}
