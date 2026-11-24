// Desire Paths for NeoForge - port of Desire Paths by Matjojo (https://github.com/matjojo/desire-paths)
// Copyright (C) 2019 Matjojo; NeoForge port (C) 2026 gonkhub
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version 3
// of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY.
// See the LICENSE file or <https://www.gnu.org/licenses/>.

package com.gonkhub.desire_paths;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class DPConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue WEAR_PER_BLOCK = B
            .comment("Average number of wear stages a block gains each time something walks one block across it.",
                     "Every ground type has 7 steps to its next type (for example grass -> trampled grass x6 -> dirt),",
                     "so at 0.15 a patch of grass needs roughly 47 crossings to become dirt, and dirt another 47 to",
                     "become coarse dirt. The original Fabric mod was about 0.55.")
            .defineInRange("wearPerBlockWalked", 0.15, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue PATH_MULTIPLIER = B
            .comment("Multiplier on the wear rate for the last step, coarse dirt -> dirt path.",
                     "At 0.25 with the default wear rate that step alone takes roughly 190 crossings.")
            .defineInRange("pathStageMultiplier", 0.25, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue REGROW_CHANCE = B
            .comment("Chance per random tick that a partly worn block grows back one stage.",
                     "At the default random tick speed a block gets about one random tick a minute, so 0.115 is",
                     "roughly one stage every 10 minutes. Fully worn dirt is regrown by ordinary grass spread;",
                     "coarse dirt and dirt paths never grow back. 0 turns regrowth off.")
            .defineInRange("regrowChance", 0.115, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue MIN_STEP = B
            .comment("Minimum blocks moved in one tick for a step to count. Filters out standing and shuffling.",
                     "Sneaking players never wear paths regardless of this.")
            .defineInRange("minStepPerTick", 0.03, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue MOUNTS = B
            .comment("If true, an animal ridden by something in the desire_paths:wears_paths entity tag wears paths",
                     "too (players on horses, caravan porters on their horses). The rider itself never does.")
            .define("mountsWearPaths", true);

    public static final ModConfigSpec SPEC = B.build();

    private DPConfig() {}
}
