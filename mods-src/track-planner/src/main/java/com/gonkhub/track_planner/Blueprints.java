package com.gonkhub.track_planner;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Every unbuilt blueprint in the world, saved with the overworld and mirrored to every player. */
public final class Blueprints extends SavedData {
    private final Map<Integer, Blueprint> all = new LinkedHashMap<>();
    private int nextId = 1;

    public static Blueprints get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(Blueprints::new, Blueprints::load), TrackPlanner.MOD_ID + "_blueprints");
    }

    public int nextId() { return nextId++; }

    public Collection<Blueprint> all() { return all.values(); }

    public Blueprint byId(int id) { return all.get(id); }

    public Blueprint last() {
        Blueprint b = null;
        for (Blueprint x : all.values()) b = x;
        return b;
    }

    public void add(MinecraftServer server, Blueprint b) {
        all.put(b.id(), b);
        setDirty();
        sync(server, null);
    }

    public boolean remove(MinecraftServer server, int id) {
        boolean removed = all.remove(id) != null;
        if (removed) { setDirty(); sync(server, null); }
        return removed;
    }

    /** Marks a piece built; clients hear about it within two seconds (the whole set is resent, so not every piece). */
    public void markBuilt(Blueprint b, int move) {
        b.built().set(move);
        setDirty();
        syncSoon = true;
    }

    boolean syncSoon;

    /** Sends a pending change, at most every 40 ticks. Called from the server tick. */
    void flush(MinecraftServer server) {
        if (syncSoon && server.getTickCount() % 40 == 0) {
            syncSoon = false;
            sync(server, null);
        }
    }

    public void clear(MinecraftServer server) {
        all.clear();
        setDirty();
        sync(server, null);
    }

    /** Sends the whole set to one player, or to everyone when player is null. Blueprints are small. */
    public void sync(MinecraftServer server, ServerPlayer player) {
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        for (Blueprint b : all.values()) list.add(b.forClient());
        t.put("blueprints", list);
        BlueprintsPayload payload = new BlueprintsPayload(t);
        if (player != null) PacketDistributor.sendToPlayer(player, payload);
        else PacketDistributor.sendToAllPlayers(payload);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Blueprint b : all.values()) list.add(b.save());
        tag.put("blueprints", list);
        tag.putInt("nextId", nextId);
        return tag;
    }

    static Blueprints load(CompoundTag tag, HolderLookup.Provider registries) {
        Blueprints b = new Blueprints();
        for (Tag e : tag.getList("blueprints", Tag.TAG_COMPOUND)) {
            Blueprint bp = Blueprint.load((CompoundTag) e);
            b.all.put(bp.id(), bp);
        }
        b.nextId = Math.max(1, tag.getInt("nextId"));
        return b;
    }
}
