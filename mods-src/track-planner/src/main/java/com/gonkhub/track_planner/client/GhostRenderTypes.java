package com.gonkhub.track_planner.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/** Render types for ghost track. Extends RenderType only to reach its protected render-state pieces. */
public final class GhostRenderTypes extends RenderType {
    /** Translucent, no depth test: the faint copy of the ghost that shows through terrain. */
    public static final RenderType XRAY = create("track_planner_ghost_xray", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
            1 << 16, false, true, CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /** Translucent, depth tested: the bright ghost where nothing is in front of it. */
    public static final RenderType SOLID = create("track_planner_ghost", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
            1 << 16, false, true, CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    private GhostRenderTypes() {
        super("track_planner_unused", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 0, false, false, () -> {}, () -> {});
    }
}
