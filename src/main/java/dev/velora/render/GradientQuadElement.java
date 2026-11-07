package dev.velora.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * A quad with an independent colour per corner, submitted to the GUI batch the
 * same way vanilla {@code ColoredQuadGuiElementRenderState} does. Used for
 * horizontal / diagonal gradients that vanilla {@code fillGradient} cannot do.
 */
public record GradientQuadElement(
        RenderPipeline pipeline,
        Matrix3x2fc pose,
        int x0, int y0, int x1, int y1,
        int colorTopLeft, int colorTopRight,
        int colorBottomRight, int colorBottomLeft,
        @Nullable ScreenRect scissorArea,
        @Nullable ScreenRect bounds
) implements SimpleGuiElementRenderState {

    public GradientQuadElement(RenderPipeline pipeline, Matrix3x2fc pose, int x0, int y0, int x1, int y1,
                               int colorTopLeft, int colorTopRight, int colorBottomRight, int colorBottomLeft,
                               @Nullable ScreenRect scissorArea) {
        this(pipeline, pose, x0, y0, x1, y1, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft,
                scissorArea, createBounds(x0, y0, x1, y1, pose, scissorArea));
    }

    @Override
    public void setupVertices(VertexConsumer vertices) {
        vertices.vertex(pose, x0, y0).color(colorTopLeft);
        vertices.vertex(pose, x0, y1).color(colorBottomLeft);
        vertices.vertex(pose, x1, y1).color(colorBottomRight);
        vertices.vertex(pose, x1, y0).color(colorTopRight);
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.empty();
    }

    private static @Nullable ScreenRect createBounds(int x0, int y0, int x1, int y1, Matrix3x2fc pose, @Nullable ScreenRect scissor) {
        ScreenRect rect = new ScreenRect(x0, y0, x1 - x0, y1 - y0).transformEachVertex(pose);
        return scissor != null ? scissor.intersection(rect) : rect;
    }
}
