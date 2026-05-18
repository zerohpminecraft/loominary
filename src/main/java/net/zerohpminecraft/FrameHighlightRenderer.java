package net.zerohpminecraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders translucent wireframe boxes around item frames during steal-grid
 * discovery to show the user which frames will be stolen together.
 *
 * Green  = frame belongs to the target cluster (will be stolen)
 * Orange = frame detected but belongs to a different cluster
 */
public class FrameHighlightRenderer {

    private record FrameHighlight(ItemFrameEntity frame, float r, float g, float b) {}

    private static final List<FrameHighlight> highlights = new ArrayList<>();
    private static long highlightSetAt = 0;
    private static final long HIGHLIGHT_TTL_MS = 2000;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(FrameHighlightRenderer::render);
    }

    /**
     * Sets frames to highlight. {@code targetFrames} will be green; frames in
     * {@code otherFrames} that are NOT in {@code targetFrames} will be orange.
     * Highlights auto-clear after 2 seconds.
     */
    public static void setHighlights(List<ItemFrameEntity> targetFrames,
                                     List<ItemFrameEntity> otherFrames) {
        highlights.clear();
        Set<ItemFrameEntity> targets = Set.copyOf(targetFrames);
        for (ItemFrameEntity frame : otherFrames) {
            if (targets.contains(frame)) {
                highlights.add(new FrameHighlight(frame, 0.2f, 0.9f, 0.2f));  // green
            } else {
                highlights.add(new FrameHighlight(frame, 1.0f, 0.5f, 0.1f)); // orange
            }
        }
        highlightSetAt = System.currentTimeMillis();
    }

    public static void clearHighlights() {
        highlights.clear();
    }

    private static void render(WorldRenderContext context) {
        if (highlights.isEmpty()) return;
        if (System.currentTimeMillis() - highlightSetAt > HIGHLIGHT_TTL_MS) {
            highlights.clear();
            return;
        }
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;
        MatrixStack matrices = context.matrixStack();
        Vec3d camPos = context.camera().getPos();
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

        for (FrameHighlight h : highlights) {
            Box bb = h.frame().getBoundingBox();
            VertexRendering.drawBox(matrices, lines,
                    bb.minX - camPos.x, bb.minY - camPos.y, bb.minZ - camPos.z,
                    bb.maxX - camPos.x, bb.maxY - camPos.y, bb.maxZ - camPos.z,
                    h.r(), h.g(), h.b(), 1.0f);
        }
    }
}
