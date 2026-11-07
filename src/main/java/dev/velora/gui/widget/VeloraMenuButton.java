package dev.velora.gui.widget;

import dev.velora.render.ColorUtil;
import dev.velora.render.HudStyle;
import dev.velora.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * Кнопка главного меню Velora: тёмная стеклянная карточка со скруглением,
 * анимированная полоса акцента слева, плавный ховер, вспышка при нажатии и
 * ванильный звук клика. {@code danger}-кнопки (например, «Выход») тонированы
 * красным. Ховер сглаживается экспоненциально по реальному времени, а не по
 * тикам, чтобы анимация была одинаковой при любом FPS.
 */
public class VeloraMenuButton extends ClickableWidget {

    private final Runnable action;
    private final boolean danger;

    private float hoverProgress;
    private long pressTime;
    private long lastFrame;

    public VeloraMenuButton(int x, int y, int width, int height, String label, boolean danger, Runnable action) {
        super(x, y, width, height, Text.literal(label));
        this.action = action;
        this.danger = danger;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        Render2D.ensureInit();

        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 0.05f : Math.min(0.1f, (now - lastFrame) / 1000.0f);
        lastFrame = now;

        float target = (isHovered() || isFocused()) ? 1.0f : 0.0f;
        hoverProgress += (target - hoverProgress) * Math.min(1.0f, dt * 14.0f);
        hoverProgress = MathHelper.clamp(hoverProgress, 0.0f, 1.0f);

        float flash = Math.max(0.0f, 1.0f - (now - pressTime) / 180.0f);
        float emphasis = MathHelper.clamp(hoverProgress + flash, 0.0f, 1.0f);

        int accent = danger ? HudStyle.RED : HudStyle.accent();
        int accent2 = danger ? accent : HudStyle.accentSecondary();

        float x = getX();
        float y = getY();
        float w = getWidth();
        float h = getHeight();

        // Мягкое свечение под карточкой при ховере/нажатии.
        if (emphasis > 0.02f) {
            Render2D.shadow(context, x + 6.0f, y + h - 2.0f, w - 12.0f, 5.0f, 10.0f,
                    ColorUtil.withAlpha(accent, Math.round(90.0f * emphasis)));
        }

        int fillRest = danger ? 0xD2141012 : 0xD20E0E14;
        int fillHover = ColorUtil.withAlpha(ColorUtil.scale(accent, 0.30f), 0xE0);
        int fill = ColorUtil.lerp(fillRest, fillHover, hoverProgress);
        if (flash > 0.0f) {
            fill = ColorUtil.lerp(fill, ColorUtil.withAlpha(ColorUtil.scale(accent, 0.50f), 0xEE), flash * 0.7f);
        }

        int border = ColorUtil.withAlpha(accent, Math.round(70.0f + 120.0f * hoverProgress + 80.0f * flash));
        Render2D.panel(context, x, y, w, h, 7.0f, fill, border, 1.0f);

        // Полоса акцента: растёт от короткой до почти во всю высоту при ховере.
        float barHeight = MathHelper.lerp(10.0f, h - 10.0f, hoverProgress);
        int barColor = ColorUtil.lerp(ColorUtil.withAlpha(accent, 120), accent, hoverProgress);
        Render2D.roundRect(context, x + 5.0f, y + (h - barHeight) / 2.0f, 3.0f, barHeight, 1.5f, barColor);

        int textRest = danger ? 0xFFE2AFA6 : HudStyle.text();
        int textHover = danger ? 0xFFFFCFC6 : ColorUtil.lerp(HudStyle.text(), accent2, 0.55f);
        float textY = y + (h - Render2D.textHeight()) / 2.0f + 1.0f;
        Render2D.centeredText(context, getMessage().getString(), x + w / 2.0f, textY,
                ColorUtil.lerp(textRest, textHover, emphasis), true);
    }

    @Override
    public void onClick(Click click, boolean doubled) {
        pressTime = System.currentTimeMillis();
        playClickSound();
        action.run();
    }

    private static void playClickSound() {
        try {
            MinecraftClient.getInstance().getSoundManager()
                    .play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.35f));
        } catch (Exception ignored) {
            // Звук — косметика: не даём ему ломать клик.
        }
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, this.getMessage());
    }
}
