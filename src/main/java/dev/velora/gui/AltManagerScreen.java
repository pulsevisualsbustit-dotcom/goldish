package dev.velora.gui;

import dev.velora.Velora;
import dev.velora.render.ColorUtil;
import dev.velora.render.Render2D;
import dev.velora.util.Alt;
import dev.velora.util.AltManager;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Alt manager: stores offline accounts, lets the player add/remove them and
 * applies one by swapping the live client session.
 *
 * <p>Styled with the Velora 2D stack instead of vanilla widgets; the only
 * vanilla child is the nickname text field.</p>
 */
public class AltManagerScreen extends Screen {

    private static final float PANEL_WIDTH = 300;
    private static final float HEADER_HEIGHT = 28;
    private static final float ROW_HEIGHT = 22;
    private static final float PADDING = 12;
    private static final float BUTTON_HEIGHT = 22;
    private static final float INPUT_HEIGHT = 24;
    private static final float ADD_BUTTON_WIDTH = 54;

    private final Screen parent;

    private TextFieldWidget nameField;
    private String selected;
    private String status = "";
    private int statusColor = 0xFF9A9AA5;
    private float scroll;

    /** Last known cursor position, used by the custom button/row hit tests. */
    private float cursorX;
    private float cursorY;

    public AltManagerScreen(Screen parent) {
        super(Text.literal("Alt Manager"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ layout

    private float panelWidth() {
        return Math.min(PANEL_WIDTH, width - 24f);
    }

    private float panelHeight() {
        return Math.min(220f, height - 20f);
    }

    private float panelX() {
        return (width - panelWidth()) / 2f;
    }

    private float panelY() {
        return (height - panelHeight()) / 2f;
    }

    private float buttonsY() {
        return panelY() + panelHeight() - PADDING - BUTTON_HEIGHT;
    }

    private float inputY() {
        return buttonsY() - 8 - INPUT_HEIGHT;
    }

    private float statusY() {
        return inputY() - 13;
    }

    private float listTop() {
        return panelY() + HEADER_HEIGHT;
    }

    private float listHeight() {
        return Math.max(ROW_HEIGHT, statusY() - 2 - listTop());
    }

    private float rowX() {
        return panelX() + PADDING;
    }

    private float rowWidth() {
        return panelWidth() - PADDING * 2;
    }

    private float maxScroll() {
        return Math.max(0, Velora.get().getAltManager().getAlts().size() * ROW_HEIGHT - listHeight());
    }

    private float addButtonX() {
        return panelX() + panelWidth() - PADDING - ADD_BUTTON_WIDTH;
    }

    private float buttonWidth() {
        return (panelWidth() - PADDING * 2 - 16) / 3f;
    }

    // -------------------------------------------------------------------- init

    @Override
    protected void init() {
        super.init();

        float boxWidth = panelWidth() - PADDING * 2 - ADD_BUTTON_WIDTH - 8;
        nameField = new TextFieldWidget(textRenderer,
                Math.round(panelX() + PADDING + 6),
                Math.round(inputY() + (INPUT_HEIGHT - 9) / 2f),
                Math.round(boxWidth - 12), 10,
                Text.literal("Nickname"));
        nameField.setMaxLength(16);
        nameField.setDrawsBackground(false);
        nameField.setEditableColor(0xFFF2F2F5);
        nameField.setTextPredicate(value -> value.matches("[A-Za-z0-9_]{0,16}"));
        nameField.setPlaceholder(Text.literal("Nickname").styled(style -> style.withColor(0x6F6F7C)));
        addDrawableChild(nameField);
    }

    @Override
    protected void setInitialFocus() {
        if (nameField != null) {
            setInitialFocus(nameField);
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        Render2D.ensureInit();
        cursorX = mouseX;
        cursorY = mouseY;

        AltManager manager = Velora.get().getAltManager();
        int accent = Velora.get().getConfigManager().getAccentColor();
        int background = Velora.get().getConfigManager().getBackgroundColor();

        float x = panelX();
        float y = panelY();
        float panelWidth = panelWidth();
        float panelHeight = panelHeight();

        Render2D.shadow(context, x, y, panelWidth, panelHeight, 10, 0x66000000);
        Render2D.roundRect(context, x, y, panelWidth, panelHeight, 9, background);

        // Header strip: rounded on top, squared where the list starts.
        int strip = ColorUtil.withAlpha(ColorUtil.scale(accent, 0.12f), 220);
        Render2D.roundRect(context, x, y, panelWidth, HEADER_HEIGHT, 9, strip);
        Render2D.rect(context, x, y + HEADER_HEIGHT / 2f, panelWidth, HEADER_HEIGHT / 2f, strip);
        Render2D.rect(context, x, y + HEADER_HEIGHT - 1, panelWidth, 1, ColorUtil.withAlpha(accent, 190));

        Render2D.text(context, "ALT MANAGER", x + PADDING, y + 7, 0xFFFFFFFF, true);
        int count = manager.getAlts().size();
        Render2D.text(context, count + (count == 1 ? " account" : " accounts"),
                x + PADDING, y + 17, 0xFF9A9AA5, false);

        String currentLabel = "CURRENT " + manager.getCurrentUsername();
        float chipWidth = Render2D.textWidth(currentLabel) + 12;
        Render2D.roundRect(context, x + panelWidth - PADDING - chipWidth, y + 8, chipWidth, 13, 6.5f,
                ColorUtil.withAlpha(accent, 36));
        Render2D.text(context, currentLabel, x + panelWidth - PADDING - chipWidth + 6, y + 10.5f, accent, false);

        renderList(context, mouseX, mouseY, manager, accent);
        renderScrollbar(context, manager, accent);

        String hint = status.isEmpty() ? "Double-click an account to log in" : status;
        String trimmed = textRenderer.trimToWidth(hint, Math.max(20, Math.round(panelWidth - PADDING * 2)));
        Render2D.text(context, trimmed, x + PADDING, statusY(),
                status.isEmpty() ? 0xFF7B7B88 : statusColor, false);

        renderInput(context, accent);
        renderButtons(context, accent);

        // Vanilla child: the nickname field.
        super.render(context, mouseX, mouseY, deltaTicks);

        Alt hovered = altAt(mouseX, mouseY);
        if (hovered != null) {
            renderTooltip(context, hovered, mouseX, mouseY, accent);
        }
    }

    private void renderList(DrawContext context, int mouseX, int mouseY, AltManager manager, int accent) {
        List<Alt> alts = manager.getAlts();
        float listTop = listTop();
        float listHeight = listHeight();
        float rowX = rowX();
        float rowWidth = rowWidth();

        Render2D.roundRect(context, rowX, listTop + 1, rowWidth, listHeight - 2, 6, 0x0CFFFFFF);
        Render2D.scissor(context, rowX - 2, listTop, rowWidth + 4, listHeight);

        int hoveredIndex = rowIndexAt(mouseX, mouseY);
        float rowY = listTop - scroll;
        for (int i = 0; i < alts.size(); i++) {
            Alt alt = alts.get(i);
            if (rowY + ROW_HEIGHT > listTop && rowY < listTop + listHeight) {
                boolean isSelected = selected != null && selected.equalsIgnoreCase(alt.name());
                boolean isCurrent = manager.isCurrent(alt);
                boolean isHovered = i == hoveredIndex;

                if (isSelected) {
                    Render2D.roundRect(context, rowX, rowY, rowWidth, ROW_HEIGHT - 2, 5,
                            ColorUtil.withAlpha(accent, 34));
                    Render2D.roundRect(context, rowX + 1, rowY + 4, 2, ROW_HEIGHT - 10, 1,
                            ColorUtil.withAlpha(accent, 220));
                } else if (isHovered) {
                    Render2D.roundRect(context, rowX, rowY, rowWidth, ROW_HEIGHT - 2, 5, 0x22FFFFFF);
                }

                int avatarColor = ColorUtil.rainbow((alt.name().hashCode() & 0x7FFFFFFF) % 360 / 360f,
                        0.55f, 0.9f, 255);
                Render2D.circle(context, rowX + 13, rowY + (ROW_HEIGHT - 2) / 2f, 14, avatarColor);
                Render2D.centeredText(context, alt.name().substring(0, 1).toUpperCase(),
                        rowX + 13, rowY + (ROW_HEIGHT - 2 - Render2D.textHeight()) / 2f + 1, 0xFF101014, true);

                Render2D.text(context, alt.name(), rowX + 26, rowY + (ROW_HEIGHT - 2 - Render2D.textHeight()) / 2f + 1,
                        isCurrent ? accent : 0xFFF2F2F5, true);

                String right = isCurrent ? "ACTIVE" : alt.shortUuid();
                int rightColor = isCurrent ? accent : 0xFF62626F;
                Render2D.text(context, right, rowX + rowWidth - 8 - Render2D.textWidth(right),
                        rowY + (ROW_HEIGHT - 2 - Render2D.textHeight()) / 2f + 1, rightColor, false);
            }
            rowY += ROW_HEIGHT;
        }

        if (alts.isEmpty()) {
            Render2D.centeredText(context, "No accounts yet — type a nickname below",
                    rowX + rowWidth / 2f, listTop + listHeight / 2f - 4, 0xFF6F6F7C, false);
        }

        Render2D.unScissor(context);
    }

    private void renderScrollbar(DrawContext context, AltManager manager, int accent) {
        float maxScroll = maxScroll();
        if (maxScroll <= 0) {
            return;
        }
        float trackX = panelX() + panelWidth() - 6;
        float trackY = listTop() + 2;
        float trackHeight = listHeight() - 4;
        float thumbHeight = Math.max(16f, trackHeight * (listHeight() / (manager.getAlts().size() * ROW_HEIGHT)));
        float thumbY = trackY + (trackHeight - thumbHeight) * (scroll / maxScroll);
        Render2D.roundRect(context, trackX, trackY, 2, trackHeight, 1, 0x22FFFFFF);
        Render2D.roundRect(context, trackX, thumbY, 2, thumbHeight, 1, ColorUtil.withAlpha(accent, 150));
    }

    private void renderInput(DrawContext context, int accent) {
        float boxX = panelX() + PADDING;
        float boxWidth = panelWidth() - PADDING * 2 - ADD_BUTTON_WIDTH - 8;
        boolean focused = nameField != null && nameField.isFocused();

        Render2D.panel(context, boxX, inputY(), boxWidth, INPUT_HEIGHT, 6,
                0xFF17171C, focused ? ColorUtil.withAlpha(accent, 200) : 0xFF2B2B34, 1);

        if (nameField != null && nameField.getText().isEmpty()) {
            String hint = "3-16 chars";
            Render2D.text(context, hint, boxX + boxWidth - 6 - Render2D.textWidth(hint),
                    inputY() + (INPUT_HEIGHT - Render2D.textHeight()) / 2f, 0xFF55555F, false);
        }

        boolean addHovered = insideButton(addButtonX(), inputY(), ADD_BUTTON_WIDTH, INPUT_HEIGHT);
        drawButton(context, addButtonX(), inputY(), ADD_BUTTON_WIDTH, INPUT_HEIGHT, "ADD",
                true, addHovered, accent);
    }

    private void renderButtons(DrawContext context, int accent) {
        float width = buttonWidth();
        float y = buttonsY();
        float x = panelX() + PADDING;

        boolean loginHovered = insideButton(x, y, width, BUTTON_HEIGHT);
        boolean deleteHovered = insideButton(x + width + 8, y, width, BUTTON_HEIGHT);
        boolean backHovered = insideButton(x + (width + 8) * 2, y, width, BUTTON_HEIGHT);

        drawButton(context, x, y, width, BUTTON_HEIGHT, "LOGIN", true, loginHovered, accent);
        drawButton(context, x + width + 8, y, width, BUTTON_HEIGHT, "DELETE", false, deleteHovered, accent);
        drawButton(context, x + (width + 8) * 2, y, width, BUTTON_HEIGHT, "BACK", false, backHovered, accent);
    }

    private void drawButton(DrawContext context, float x, float y, float width, float height,
                            String label, boolean primary, boolean hovered, int accent) {
        if (primary) {
            int top = ColorUtil.withAlpha(accent, hovered ? 255 : 235);
            int bottom = ColorUtil.withAlpha(ColorUtil.scale(accent, 0.80f), hovered ? 255 : 235);
            Render2D.roundRectGradient(context, x, y, width, height, 6, top, bottom);
            Render2D.centeredText(context, label, x + width / 2f, y + (height - 9) / 2f + 1, 0xFF1A1206, true);
        } else {
            Render2D.roundRect(context, x, y, width, height, 6, hovered ? 0xFF26262F : 0xFF1B1B22);
            Render2D.centeredText(context, label, x + width / 2f, y + (height - 9) / 2f + 1,
                    hovered ? 0xFFFFFFFF : 0xFFCFCFDA, true);
        }
    }

    private void renderTooltip(DrawContext context, Alt alt, int mouseX, int mouseY, int accent) {
        String uuid = alt.uuid().toString();
        float boxWidth = Math.max(Render2D.textWidth(alt.name()), Render2D.textWidth(uuid)) + 12;
        float boxX = MathHelper.clamp(mouseX + 10, 2, Math.max(2, width - boxWidth - 2));
        float boxY = MathHelper.clamp(mouseY + 12, 2, Math.max(2, height - 36));

        Render2D.roundRect(context, boxX, boxY, boxWidth, 32, 4, 0xF0101014);
        Render2D.text(context, alt.name(), boxX + 6, boxY + 4, accent, true);
        Render2D.text(context, uuid, boxX + 6, boxY + 15, 0xFF9A9AA5, false);
    }

    // ------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        cursorX = (float) mouseX;
        cursorY = (float) mouseY;
        if (click.button() == 0) {
            int index = rowIndexAt(mouseX, mouseY);
            if (index >= 0) {
                List<Alt> alts = Velora.get().getAltManager().getAlts();
                if (index < alts.size()) {
                    Alt alt = alts.get(index);
                    selected = alt.name();
                    if (doubled) {
                        login(alt);
                    }
                }
                return true;
            }

            float width = buttonWidth();
            float x = panelX() + PADDING;
            float y = buttonsY();

            if (inside(addButtonX(), inputY(), ADD_BUTTON_WIDTH, INPUT_HEIGHT)) {
                addFromField();
                return true;
            }
            if (inside(x, y, width, BUTTON_HEIGHT)) {
                loginSelected();
                return true;
            }
            if (inside(x + width + 8, y, width, BUTTON_HEIGHT)) {
                deleteSelected();
                return true;
            }
            if (inside(x + (width + 8) * 2, y, width, BUTTON_HEIGHT)) {
                close();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (inside(rowX() - 2, listTop(), rowWidth() + 4, listHeight())) {
            float maxScroll = maxScroll();
            if (maxScroll > 0) {
                scroll = MathHelper.clamp(scroll - (float) verticalAmount * ROW_HEIGHT, 0, maxScroll);
                return true;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (nameField != null && !nameField.getText().trim().isEmpty()) {
                addFromField();
            } else {
                loginSelected();
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        Velora.get().getConfigManager().save();
        if (parent == null) {
            client.setScreen(null);
            return;
        }
        client.setScreen(parent);
        // Vanilla screens rebuild their widgets in init(), so the ALTS button must be re-attached.
        Velora.attachAltButton(client, parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---------------------------------------------------------------- actions

    private void addFromField() {
        if (nameField == null) {
            return;
        }
        String name = nameField.getText().trim();
        AltManager manager = Velora.get().getAltManager();
        switch (manager.add(name)) {
            case ADDED -> {
                selected = name;
                nameField.setText("");
                scroll = maxScroll();
                setStatus("Added " + name, Velora.get().getConfigManager().getAccentColor());
            }
            case INVALID_NAME -> setStatus("Use 3-16 characters: a-z, 0-9, _", 0xFFFF6B6B);
            case ALREADY_EXISTS -> {
                selected = name;
                setStatus(name + " is already in the list", 0xFFFFC64A);
            }
        }
    }

    private void loginSelected() {
        AltManager manager = Velora.get().getAltManager();
        String name = selected;
        if (nameField != null && !nameField.getText().trim().isEmpty()) {
            name = nameField.getText().trim();
        }
        if (name == null || name.isEmpty()) {
            setStatus("Select an account or type a nickname", 0xFFFFC64A);
            return;
        }

        Alt alt = manager.find(name);
        if (alt == null) {
            if (manager.add(name) == AltManager.AddResult.INVALID_NAME) {
                setStatus("Use 3-16 characters: a-z, 0-9, _", 0xFFFF6B6B);
                return;
            }
            alt = manager.find(name);
            if (alt == null) {
                return;
            }
            nameField.setText("");
            scroll = maxScroll();
        }

        selected = alt.name();
        manager.login(alt);
        boolean inWorld = client != null && client.world != null;
        setStatus("Logged in as " + alt.name() + (inWorld ? " — reconnect to apply" : ""),
                Velora.get().getConfigManager().getAccentColor());
    }

    private void login(Alt alt) {
        selected = alt.name();
        Velora.get().getAltManager().login(alt);
        boolean inWorld = client != null && client.world != null;
        setStatus("Logged in as " + alt.name() + (inWorld ? " — reconnect to apply" : ""),
                Velora.get().getConfigManager().getAccentColor());
    }

    private void deleteSelected() {
        String name = selected;
        if (nameField != null && !nameField.getText().trim().isEmpty()) {
            name = nameField.getText().trim();
        }
        if (name == null || name.isEmpty()) {
            setStatus("Nothing selected", 0xFFFFC64A);
            return;
        }
        if (Velora.get().getAltManager().remove(name)) {
            if (name.equalsIgnoreCase(selected)) {
                selected = null;
            }
            if (nameField != null && nameField.getText().trim().equalsIgnoreCase(name)) {
                nameField.setText("");
            }
            scroll = Math.min(scroll, maxScroll());
            setStatus("Removed " + name, 0xFFFFC64A);
        } else {
            setStatus(name + " is not in the list", 0xFFFFC64A);
        }
    }

    private void setStatus(String message, int color) {
        status = message;
        statusColor = color;
    }

    // ---------------------------------------------------------------- helpers

    private int rowIndexAt(double mouseX, double mouseY) {
        if (!inside(rowX() - 2, listTop(), rowWidth() + 4, listHeight())) {
            return -1;
        }
        int index = (int) ((mouseY - listTop() + scroll) / ROW_HEIGHT);
        return index >= 0 ? index : -1;
    }

    private Alt altAt(double mouseX, double mouseY) {
        int index = rowIndexAt(mouseX, mouseY);
        List<Alt> alts = Velora.get().getAltManager().getAlts();
        return index >= 0 && index < alts.size() ? alts.get(index) : null;
    }

    private boolean insideButton(float x, float y, float width, float height) {
        return inside(x, y, width, height);
    }

    private boolean inside(float x, float y, float width, float height) {
        return cursorX >= x && cursorX <= x + width && cursorY >= y && cursorY <= y + height;
    }
}
