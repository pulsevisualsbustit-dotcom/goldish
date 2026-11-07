package dev.velora.gui;

import dev.velora.Velora;
import dev.velora.gui.widget.VeloraMenuButton;
import dev.velora.mixin.ScreenAccessor;
import dev.velora.render.ColorUtil;
import dev.velora.render.HudStyle;
import dev.velora.render.Render2D;
import dev.velora.render.SystemFont;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Кастомное главное меню Velora: вращающаяся размытая панорама + фирменный
 * тёмный градиент, неоновая марка и вордмарк «VELORA» с посимвольным
 * градиентом акцентов, стеклянные кнопки, сплэш с классическим наклоном и
 * плавное появление из затемнения. Экран подменяет ванильный TitleScreen в
 * {@code dev.velora.mixin.TitleScreenMixin} ещё до создания ванильных
 * виджетов, поэтому ванильный титул не отрисовывается ни на один кадр.
 *
 * <p>Лэйаут адаптивный: на низких окнах (GUI &lt; 320px высоты, например
 * 854×480 при масштабе 2) включается компактный режим — без марки и
 * подзаголовка, кнопки ниже, вордмарк меньше.</p>
 */
public class VeloraTitleScreen extends Screen {

    private static final String[] SPLASHES = {
            "Sloth не палится!", "0 флагов на space-times", "aim как человек",
            "сделано на пасте", "база", "ударь в хитбокс", "ротации на месте",
            "Shard2? не слышал", "легит mode on", "сборка зелёная",
            "быстрее античита", "Velora на связи"
    };

    private static final String WORDMARK = "VELORA";
    private static final int BUTTON_COUNT = 6;
    private static final long FADE_MS = 650L;

    private final List<VeloraMenuButton> menuButtons = new ArrayList<>();
    private final String splash;
    private long openTime = -1L;

    public VeloraTitleScreen() {
        super(Text.literal("Velora"));
        this.splash = SPLASHES[ThreadLocalRandom.current().nextInt(SPLASHES.length)];
    }

    @Override
    protected void init() {
        super.init();
        if (openTime < 0L) {
            openTime = System.currentTimeMillis();
        }
        menuButtons.clear();

        int w = buttonWidth();
        int x = (width - w) / 2;
        int pitch = buttonHeight() + buttonGap();

        menuButtons.add(menuButton(x, buttonTop(), w, "Одиночная игра", false,
                () -> MinecraftClient.getInstance().setScreen(new SelectWorldScreen(this))));
        menuButtons.add(menuButton(x, buttonTop() + pitch, w, "Сетевая игра", false,
                () -> MinecraftClient.getInstance().setScreen(new MultiplayerScreen(this))));
        menuButtons.add(menuButton(x, buttonTop() + 2 * pitch, w, "Альты", false,
                () -> MinecraftClient.getInstance().setScreen(new AltManagerScreen(this))));
        menuButtons.add(menuButton(x, buttonTop() + 3 * pitch, w, "Интерфейс Velora", false,
                () -> MinecraftClient.getInstance().setScreen(new LiteClickGuiScreen())));
        menuButtons.add(menuButton(x, buttonTop() + 4 * pitch, w, "Настройки", false,
                () -> MinecraftClient.getInstance().setScreen(
                        new OptionsScreen(this, MinecraftClient.getInstance().options))));
        menuButtons.add(menuButton(x, buttonTop() + 5 * pitch, w, "Выход", true,
                () -> MinecraftClient.getInstance().scheduleStop()));
    }

    private VeloraMenuButton menuButton(int x, int y, int width, String label, boolean danger, Runnable action) {
        VeloraMenuButton button = new VeloraMenuButton(x, y, width, buttonHeight(), label, danger, action);
        ((ScreenAccessor) (Object) this).velora$addDrawableChild(button);
        return button;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Панорама + блюр из ванильного Screen, но вместо текстуры-затемнения —
        // фирменный тёмный градиент и виньетка мягкими тенями.
        renderPanoramaBackground(context, delta);
        applyBlur(context);
        Render2D.ensureInit();
        Render2D.gradient(context, 0, 0, width, height, 0xD2060612, 0xEE07070F);
        Render2D.shadow(context, -width * 0.08f, -height * 0.12f, width * 0.5f, height * 0.4f, 60, 0xA0000000);
        Render2D.shadow(context, width * 0.58f, height * 0.72f, width * 0.5f, height * 0.4f, 60, 0xA0000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        Render2D.ensureInit();
        float fade = fade();
        float slide = (1.0f - fade) * 14.0f;

        int accent = HudStyle.accent();
        int accent2 = HudStyle.accentSecondary();
        float time = HudStyle.time();
        int centerX = width / 2;
        int mark = markSize();
        float scale = wordScale();
        float wordH = SystemFont.TITLE.height(scale);
        float wordW = SystemFont.TITLE.width(WORDMARK, scale);
        float top = contentTop() + slide;

        // --- Марка клиента (неоновый логотип). ---
        if (mark > 0) {
            int markTint = HudStyle.breathing(accent, 1.1f, 200, 255);
            if (!Render2D.logo(context, centerX - mark / 2.0f, top, mark, markTint)) {
                Render2D.ringGlow(context, centerX, top + mark / 2.0f, mark / 2.0f - 2.0f,
                        ColorUtil.withAlpha(accent, 100));
                Render2D.circle(context, centerX, top + mark / 2.0f, mark * 0.4f, accent);
            }
        }

        // --- Вордмарк с посимвольным градиентом акцентов. ---
        float wordX = centerX - wordW / 2.0f;
        float wordY = top + mark + (compact() ? 8.0f : 14.0f);

        Render2D.shadow(context, wordX - 24.0f, wordY + wordH / 2.0f - 16.0f, wordW + 48.0f, 32.0f, 16.0f,
                HudStyle.breathing(accent, 1.3f, 40, 90));

        float cursor = wordX;
        for (int i = 0; i < WORDMARK.length(); i++) {
            String ch = String.valueOf(WORDMARK.charAt(i));
            float frac = wordW <= 0.0f ? 0.0f : (cursor - wordX) / wordW;
            SystemFont.TITLE.draw(context, ch, cursor, wordY, ColorUtil.lerp(accent, accent2, frac), scale);
            cursor += SystemFont.TITLE.width(ch, scale);
        }

        // Тонкая линия под вордмарком, затухающая к краям.
        float lineY = wordY + wordH + 8.0f;
        float lineW = Math.max(60.0f, wordW * 0.9f);
        Render2D.gradientHorizontal(context, centerX - lineW / 2.0f, lineY, lineW / 2.0f, 1.5f,
                ColorUtil.withAlpha(accent, 0), ColorUtil.withAlpha(accent, 180));
        Render2D.gradientHorizontal(context, centerX, lineY, lineW / 2.0f, 1.5f,
                ColorUtil.withAlpha(accent, 180), ColorUtil.withAlpha(accent, 0));

        if (!compact()) {
            Render2D.smallCentered(context, "by PastaCode", centerX, lineY + 9.0f, HudStyle.textFaint());
        }

        // --- Сплэш: классический наклон и пульсация. ---
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(wordX + wordW - 6.0f, wordY + 4.0f);
        matrices.rotate((float) Math.toRadians(-18.0f));
        float pulse = 1.0f + 0.06f * MathHelper.sin(time * 2.8f);
        matrices.scale(pulse, pulse);
        Render2D.text(context, splash, 2.0f, -Render2D.textHeight() + 3.0f, accent2, true);
        matrices.popMatrix();

        // --- Кнопки (плавный выезд при появлении). ---
        int bx = (width - buttonWidth()) / 2;
        int by = buttonTop() + Math.round(slide);
        for (VeloraMenuButton button : menuButtons) {
            button.setX(bx);
            button.setY(by);
            by += buttonHeight() + buttonGap();
        }
        super.render(context, mouseX, mouseY, delta);

        // --- Футер. ---
        float footerY = height - Render2D.smallHeight() - 5.0f;
        Render2D.smallText(context, "Velora " + Velora.VERSION + " · MC 1.21.11", 6.0f, footerY, HudStyle.textFaint());
        if (!compact()) {
            Render2D.smallCentered(context, "mc.space-times.ru", centerX, footerY, HudStyle.textDim());
        }
        String account = MinecraftClient.getInstance().getSession().getUsername();
        Render2D.smallRight(context, "Игрок: " + account, width - 6.0f, footerY, HudStyle.textDim());

        // --- Плавное появление из затемнения. ---
        if (fade < 1.0f) {
            int alpha = Math.round(0xF0 * (1.0f - fade));
            Render2D.rect(context, 0, 0, width, height, ColorUtil.withAlpha(0x05050C, alpha));
        }
    }

    // ---------------------------------------------------------------- лэйаут

    private boolean compact() {
        return height < 320 || width < 400;
    }

    private int markSize() {
        return compact() ? 0 : 52;
    }

    private float wordScale() {
        float scale = compact() ? 3.0f : 6.0f;
        float w = SystemFont.TITLE.width(WORDMARK, scale);
        if (w > width * 0.62f) {
            scale *= (width * 0.62f) / Math.max(1.0f, w);
        }
        return Math.max(1.5f, scale);
    }

    private int buttonWidth() {
        return Math.min(260, Math.max(200, width / 5));
    }

    private int buttonHeight() {
        return compact() ? 21 : 30;
    }

    private int buttonGap() {
        return compact() ? 3 : 7;
    }

    private int buttonTop() {
        return Math.round(contentTop() + markSize() + (compact() ? 8.0f : 14.0f)
                + SystemFont.TITLE.height(wordScale())
                + (compact() ? 8.0f + 6.0f : 8.0f + 9.0f + 16.0f));
    }

    private float contentHeight() {
        return markSize() + (compact() ? 8.0f : 14.0f)
                + SystemFont.TITLE.height(wordScale())
                + (compact() ? 8.0f + 6.0f : 8.0f + 9.0f + 16.0f)
                + (BUTTON_COUNT * (buttonHeight() + buttonGap()) - buttonGap());
    }

    private int contentTop() {
        return Math.max(6, Math.min((int) (height * 0.16f), (int) ((height - contentHeight()) / 2.0f)));
    }

    private float fade() {
        if (openTime < 0L) {
            return 1.0f;
        }
        return MathHelper.clamp((System.currentTimeMillis() - openTime) / (float) FADE_MS, 0.0f, 1.0f);
    }

    @Override
    protected boolean allowRotatingPanorama() {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // ESC не должен «закрывать» титульный экран в пустоту.
        return false;
    }
}
