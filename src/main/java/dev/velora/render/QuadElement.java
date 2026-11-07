package dev.velora.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * An arbitrary four-vertex quad submitted to the vanilla GUI batch.
 *
 * <p>{@code DrawContext#fill} only draws axis-aligned rectangles, which is not
 * enough for the projected 3D boxes. This element takes four independent points,
 * so ESP can draw perspective-correct edges and faces while still using the
 * normal HUD pipeline (scissor + batching).</p>
 */
public record QuadElement(
        RenderPipeline pipeline,
        Matrix3x2fc pose,
        float x0, float y0,
        float x1, float y1,
        float x2, float y2,
        float x3, float y3,
        int color,
        @Nullable ScreenRect scissorArea,
        @Nullable ScreenRect bounds
) implements SimpleGuiElementRenderState {

    public QuadElement(RenderPipeline pipeline, Matrix3x2fc pose,
                       float x0, float y0, float x1, float y1,
                       float x2, float y2, float x3, float y3,
                       int color, @Nullable ScreenRect scissorArea) {
        this(pipeline, pose, x0, y0, x1, y1, x2, y2, x3, y3, color, scissorArea,
                createBounds(x0, y0, x1, y1, x2, y2, x3, y3, pose, scissorArea));
    }

    @Override
    public void setupVertices(VertexConsumer vertices) {
        vertices.vertex(pose, x0, y0).color(color);
        vertices.vertex(pose, x1, y1).color(color);
        vertices.vertex(pose, x2, y2).color(color);
        vertices.vertex(pose, x3, y3).color(color);
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.empty();
    }

    private static @Nullable ScreenRect createBounds(float x0, float y0, float x1, float y1,
                                                     float x2, float y2, float x3, float y3,
                                                     Matrix3x2fc pose, @Nullable ScreenRect scissor) {
        float minX = Math.min(Math.min(x0, x1), Math.min(x2, x3));
        float maxX = Math.max(Math.max(x0, x1), Math.max(x2, x3));
        float minY = Math.min(Math.min(y0, y1), Math.min(y2, y3));
        float maxY = Math.max(Math.max(y0, y1), Math.max(y2, y3));
        ScreenRect rect = new ScreenRect(Math.round(minX), Math.round(minY),
                Math.max(1, Math.round(maxX - minX)), Math.max(1, Math.round(maxY - minY)))
                .transformEachVertex(pose);
        return scissor != null ? scissor.intersection(rect) : rect;
    }
}
