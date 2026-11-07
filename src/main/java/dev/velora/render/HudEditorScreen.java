package dev.velora.render;

import dev.velora.Velora;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Full-screen HUD editor, opened with {@code H}.
 *
 * <p>Unlike the chat-based editor this draws every widget in editor mode over
 * the paused game, so all cards stay visible and draggable no matter which ones
 * are toggled on. Widgets can be moved with the mouse and the theme swatches at
 * the bottom retune the whole HUD instantly.</p>
 */
public class HudEditorScreen extends Screen {

    public HudEditorScreen() {
        super(Text.literal("HUD Editor"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        Hud hud = Velora.get().getModuleManager().get(Hud.class);
        if (hud != null) {
            hud.renderWidgets(context, deltaTicks, true);
        }
        Velora.get().renderHudEditor(context, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (Velora.get().hudMouseClicked(click.x(), click.y(), click.button())) {
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (Velora.get().hudMouseDragged(click.x(), click.y())) {
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (Velora.get().hudMouseReleased()) {
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_H) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        client.setScreen(null);
    }

    @Override
    public void removed() {
        super.removed();
        Velora.get().cancelHudDrag();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
