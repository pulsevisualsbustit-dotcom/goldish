package dev.velora.gui.widget;

import dev.velora.Velora;
import dev.velora.render.ColorUtil;
import dev.velora.render.Render2D;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * A small Velora-styled button that can be dropped into vanilla screens
 * (title screen, multiplayer list) without looking out of place.
 */
public class VeloraButton extends ClickableWidget {

    private final Runnable action;

    public VeloraButton(int x, int y, int width, int height, Text message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        Render2D.ensureInit();
        int accent = Velora.get().getConfigManager().getAccentColor();
        boolean hovered = isHovered() || isFocused();

        Render2D.panel(context, getX(), getY(), getWidth(), getHeight(), 6,
                hovered ? ColorUtil.withAlpha(ColorUtil.scale(accent, 0.9f), 230) : 0xE6101014,
                ColorUtil.withAlpha(accent, hovered ? 230 : 120), 1);
        Render2D.centeredText(context, getMessage().getString(),
                getX() + getWidth() / 2f, getY() + (getHeight() - Render2D.textHeight()) / 2f + 1,
                hovered ? 0xFF1A1206 : accent, true);
    }

    @Override
    public void onClick(Click click, boolean doubled) {
        action.run();
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, getMessage());
    }
}
