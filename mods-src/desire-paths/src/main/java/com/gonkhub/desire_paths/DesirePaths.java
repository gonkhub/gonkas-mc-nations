// Desire Paths for NeoForge - port of Desire Paths by Matjojo (https://github.com/matjojo/desire-paths)
// Copyright (C) 2019 Matjojo; NeoForge port (C) 2026 gonkhub
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version 3
// of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY.
// See the LICENSE file or <https://www.gnu.org/licenses/>.

package com.gonkhub.desire_paths;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

@Mod(DesirePaths.MOD_ID)
public final class DesirePaths {
    public static final String MOD_ID = "desire_paths";

    public DesirePaths(IEventBus modBus, ModContainer container) {
        DPBlocks.BLOCKS.register(modBus);
        // SERVER config: one file per world, and minecraft/defaultconfigs/ seeds new worlds.
        container.registerConfig(ModConfig.Type.SERVER, DPConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(Wear::onEntityTick);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
