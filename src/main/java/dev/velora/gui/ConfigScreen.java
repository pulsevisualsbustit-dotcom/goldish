package dev.velora.gui;

import dev.velora.Velora;
import dev.velora.config.ShareCode;
import dev.velora.render.ColorUtil;
import dev.velora.render.Render2D;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Configs and sharing: named profile files on the left, the installation share
 * key and the clipboard export/import on the right.
 *
 * <p>Profiles are plain JSON files in {@code config/velora/configs}, so they can
 * be copied between installations or edited by hand.</p>
 */
public class ConfigScreen extends Screen {

    private static final float DESIGN_WIDTH = 500;
    private static final float DESIGN_HEIGHT = 320;
    private static final float PADDING = 16;
    private static final float PROFILE_ROW = 21;
    private static final int MAX_PROFILES = 4;

    private final Screen parent;

    private float uiScale = 1f;
    private float offsetX;
    private float offsetY;

    private String status = "";
    private int statusColor = 0xFFB9B9C4;

    private List<String> profiles = List.of();

    public ConfigScreen(Screen parent) {
        super(Text.literal("Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        uiScale = Math.min(1f, Math.min((width - 12f) / DESIGN_WIDTH, (height - 12f) / DESIGN_HEIGHT));
        offsetX = (width - DESIGN_WIDTH * uiScale) / 2f;
        offsetY = (height - DESIGN_HEIGHT * uiScale) / 2f;
        refreshProfiles();
    }

    private void refreshProfiles() {
        profiles = Velora.get().getConfigManager().listProfiles();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        Render2D.ensureInit();
        var config = Velora.get().getConfigManager();
        int accent = config.getAccentColor();
        int accentSecondary = config.getAccentSecondary();
        int muted = config.getMutedTextColor();

        double localMouseX = (mouseX - offsetX) / uiScale;
        double localMouseY = (mouseY - offsetY) / uiScale;

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(offsetX, offsetY);
        matrices.scale(uiScale, uiScale);

        Render2D.shadow(context, 0, 0, DESIGN_WIDTH, DESIGN_HEIGHT, 12, 0x60000000);
        Render2D.roundRect(context, 0, 0, DESIGN_WIDTH, DESIGN_HEIGHT, 10,
                ColorUtil.withAlpha(config.getBackgroundColor(), 245));
        Render2D.roundRect(context, 0, 0, DESIGN_WIDTH, 2.5f, 1.25f, accent);

        String title = "CONFIGS";
        float titleWidth = Render2D.textWidth(title);
        Render2D.gradientText(context, title, (DESIGN_WIDTH - titleWidth) / 2f, 11,
                accent, accentSecondary, true);
        Render2D.logo(context, 12, 7, 20, accent);
        Render2D.text(context, "Profiles are files in config/velora/configs", PADDING, 27, muted, false);

        // ------------------------------------------------------------ share key
        float keyY = 48;
        Render2D.text(context, "SHARE KEY", PADDING, keyY + 5, muted, false);
        float fieldX = 90;
        float fieldWidth = 220;
        Render2D.roundRect(context, fieldX, keyY, fieldWidth, 20, 5, 0xCC121218);
        Render2D.text(context, config.getShareKey(), fieldX + 8, keyY + 5, accent, true);

        float copyX = DESIGN_WIDTH - PADDING - 62;
        float newX = copyX - 60;
        drawButton(context, newX, keyY, 54, 20, "NEW", localMouseX, localMouseY, accent, 0xFFE9E9EF);
        drawButton(context, copyX, keyY, 62, 20, "COPY", localMouseX, localMouseY, accent, 0xFFE9E9EF);

        drawButton(context, PADDING, keyY + 27, 148, 22, "EXPORT TO CLIPBOARD",
                localMouseX, localMouseY, accent, 0xFFE9E9EF);
        drawButton(context, PADDING + 156, keyY + 27, 148, 22, "IMPORT FROM CLIPBOARD",
                localMouseX, localMouseY, accent, 0xFFE9E9EF);

        // -------------------------------------------------------------- profiles
        float sectionY = 112;
        Render2D.text(context, "PROFILES", PADDING, sectionY, muted, false);
        drawButton(context, DESIGN_WIDTH - PADDING - 104, sectionY - 5, 104, 18,
                "SAVE CURRENT", localMouseX, localMouseY, accent, 0xFFE9E9EF);

        float rowY = sectionY + 20;
        if (profiles.isEmpty()) {
            Render2D.text(context, "No saved profiles yet — click SAVE CURRENT.",
                    PADDING, rowY + 5, ColorUtil.withAlpha(muted, 200), false);
        }
        for (int i = 0; i < Math.min(profiles.size(), MAX_PROFILES); i++) {
            String name = profiles.get(i);
            float y = rowY + i * (PROFILE_ROW + 4);
            boolean hovered = localMouseX >= PADDING && localMouseX <= DESIGN_WIDTH - PADDING
                    && localMouseY >= y && localMouseY <= y + PROFILE_ROW;
            Render2D.roundRect(context, PADDING, y, DESIGN_WIDTH - PADDING * 2, PROFILE_ROW, 5,
                    hovered ? ColorUtil.withAlpha(accent, 45) : 0xFF14141A);
            Render2D.smallText(context, name, PADDING + 9, y + 6,
                    hovered ? 0xFFF2F2F5 : 0xFFB9B9C4);
            Render2D.smallRight(context, "LMB load · RMB delete", DESIGN_WIDTH - PADDING - 9, y + 6,
                    ColorUtil.withAlpha(muted, 170));
        }
        if (profiles.size() > MAX_PROFILES) {
            Render2D.smallText(context, "+" + (profiles.size() - MAX_PROFILES) + " more (use .velora config list)",
                    PADDING, rowY + MAX_PROFILES * (PROFILE_ROW + 4), ColorUtil.withAlpha(muted, 190));
        }

        if (!status.isEmpty()) {
            Render2D.text(context, status, PADDING, DESIGN_HEIGHT - 56, statusColor, false);
        }

        float backX = DESIGN_WIDTH - PADDING - 64;
        float backY = DESIGN_HEIGHT - 30;
        drawButton(context, backX, backY, 64, 20, "BACK", localMouseX, localMouseY, accent, 0xFFE9E9EF);

        matrices.popMatrix();
    }

    private void drawButton(DrawContext context, float x, float y, float width, float height,
                            String label, double mouseX, double mouseY, int accent, int textColor) {
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        Render2D.panel(context, x, y, width, height, 5,
                hovered ? ColorUtil.withAlpha(accent, 210) : 0xFF1A1A21,
                ColorUtil.withAlpha(accent, hovered ? 240 : 100), 1);
        float textWidth = Render2D.textWidth(label);
        Render2D.text(context, label, x + (width - textWidth) / 2f,
                y + (height - Render2D.textHeight()) / 2f + 1,
                hovered ? 0xFF1A1206 : textColor, true);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = (click.x() - offsetX) / uiScale;
        double mouseY = (click.y() - offsetY) / uiScale;
        int button = click.button();

        var config = Velora.get().getConfigManager();
        float keyY = 48;
        float copyX = DESIGN_WIDTH - PADDING - 62;
        float newX = copyX - 60;

        if (inside(mouseX, mouseY, copyX, keyY, 62, 20)) {
            client.keyboard.setClipboard(config.getShareKey());
            setStatus("Share key copied", 0xFF4ADE80);
            return true;
        }
        if (inside(mouseX, mouseY, newX, keyY, 54, 20)) {
            config.setShareKey(ShareCode.generateKey());
            config.save();
            setStatus("New share key generated", 0xFF4ADE80);
            return true;
        }
        if (inside(mouseX, mouseY, PADDING, keyY + 27, 148, 22)) {
            String code = config.exportShareCode();
            client.keyboard.setClipboard(code);
            setStatus("Copied share code (" + code.length() + " characters)", 0xFF4ADE80);
            return true;
        }
        if (inside(mouseX, mouseY, PADDING + 156, keyY + 27, 148, 22)) {
            String clipboard = client.keyboard.getClipboard();
            if (clipboard == null || clipboard.isBlank()) {
                setStatus("Clipboard is empty", 0xFFFF6B6B);
                return true;
            }
            String error = config.importShareCode(clipboard);
            if (error == null) {
                setStatus("Imported config from " + ShareCode.keyOf(clipboard), 0xFF4ADE80);
            } else {
                setStatus("Import failed: " + error, 0xFFFF6B6B);
            }
            return true;
        }

        float sectionY = 112;
        if (inside(mouseX, mouseY, DESIGN_WIDTH - PADDING - 104, sectionY - 5, 104, 18)) {
            String name = "Profile " + (profiles.size() + 1);
            if (config.saveProfile(name)) {
                refreshProfiles();
                setStatus("Saved " + name, 0xFF4ADE80);
            } else {
                setStatus("Could not save the profile", 0xFFFF6B6B);
            }
            return true;
        }

        float rowY = sectionY + 20;
        for (int i = 0; i < Math.min(profiles.size(), MAX_PROFILES); i++) {
            float y = rowY + i * (PROFILE_ROW + 4);
            if (!inside(mouseX, mouseY, PADDING, y, DESIGN_WIDTH - PADDING * 2, PROFILE_ROW)) {
                continue;
            }
            String name = profiles.get(i);
            if (button == 0) {
                if (config.loadProfile(name)) {
                    refreshProfiles();
                    setStatus("Loaded " + name, 0xFF4ADE80);
                } else {
                    setStatus("Could not load " + name, 0xFFFF6B6B);
                }
            } else if (button == 1) {
                if (config.deleteProfile(name)) {
                    refreshProfiles();
                    setStatus("Deleted " + name, 0xFF4ADE80);
                }
            }
            return true;
        }

        float backX = DESIGN_WIDTH - PADDING - 64;
        float backY = DESIGN_HEIGHT - 30;
        if (inside(mouseX, mouseY, backX, backY, 64, 20)) {
            if (button == 0) {
                close();
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private static boolean inside(double mouseX, double mouseY,
                                  float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private void setStatus(String message, int color) {
        status = message;
        statusColor = color;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
