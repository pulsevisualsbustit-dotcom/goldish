package dev.velora.render;

import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.setting.Setting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole client HUD in one module: watermark, potion effects, module list
 * and the target hud.
 *
 * <p>Each part is a {@link HudWidget} with its own on/off toggle and a matched
 * block of settings that is only visible while that part is on. A shared
 * "HUD scale" multiplies the drawn size of every widget around its top-left
 * corner, and each widget can still be dragged individually in the HUD
 * editor (chat open).</p>
 *
 * <p>The corner stack: widgets flagged as {@link HudWidget#topRow()} and still
 * sitting on their default spot (the watermark and the module list) are laid
 * out one under another. The badge keeps the top-right corner and the list
 * moves below it, so the two never cover each other; a dragged widget keeps
 * exactly the position the user picked.</p>
 */
public class Hud extends Module {

    private final NumberSetting scale = register(new NumberSetting("HUD scale", 1.0, 0.5, 2.0, 0.05));
    private final BooleanSetting animations = register(new BooleanSetting("HUD Animation", true));
    private final BooleanSetting watermark = register(new BooleanSetting("Watermark", true));
    private final BooleanSetting potions = register(new BooleanSetting("Potions", true));
    private final BooleanSetting moduleList = register(new BooleanSetting("Module list", false));
    private final BooleanSetting targetHud = register(new BooleanSetting("Target HUD", true));
    private final BooleanSetting cooldowns = register(new BooleanSetting("Cooldowns", true));
    private final BooleanSetting cooldownsTarget = register(new BooleanSetting("Target Cooldowns", true));
    private final BooleanSetting crosshair = register(new BooleanSetting("Crosshair", false));
    private final BooleanSetting playerInfo = register(new BooleanSetting("Player Info", true));
    private final BooleanSetting mediaPlayer = register(new BooleanSetting("Media Player", true));
    private final BooleanSetting keybinds = register(new BooleanSetting("Keybinds", true));
    private final BooleanSetting notifications = register(new BooleanSetting("Notifications", true));

    private final List<HudWidget> widgets = new ArrayList<>();

    public Hud() {
        super("Hud", "The whole Velora HUD: watermark, potions, module list and the target hud", Category.RENDER);
        // The left column stacks top-down, the right column stacks below the
        // media player, so the default layout matches the reference design.
        // The badge is laid out first: everything below it follows.
        addWidget(new WatermarkWidget(), watermark);
        addWidget(new NotificationsWidget(), notifications);
        addWidget(new KeybindsWidget(), keybinds);
        addWidget(new PotionsWidget(), potions);
        addWidget(new ModuleListWidget(), moduleList);
        addWidget(new MediaPlayerWidget(), mediaPlayer);
        addWidget(new CooldownsWidget(), cooldowns);
        addWidget(new CooldownsTargetWidget(), cooldownsTarget);
        addWidget(new TargetHudWidget(), targetHud);
        addWidget(new CrosshairWidget(), crosshair);
        addWidget(new PlayerInfoWidget(), playerInfo);
        startEnabled();
    }

    private void addWidget(HudWidget widget, BooleanSetting toggle) {
        widget.setVisible(toggle::get);
        for (Setting<?> setting : widget.getSettings()) {
            setting.visibleWhen(toggle::get);
            register(setting);
        }
        widgets.add(widget);
    }

    public List<HudWidget> getWidgets() {
        return widgets;
    }

    public HudWidget getWidget(String id) {
        for (HudWidget widget : widgets) {
            if (widget.getId().equals(id)) {
                return widget;
            }
        }
        return null;
    }

    /** Whether rows may fade and slide (the "HUD Animation" switch). */
    public static boolean animationsEnabled() {
        return HudWidget.hudToggle("HUD Animation");
    }

    /** The HUD scale every widget is multiplied by (1.0 = normal size). */
    public static float getScale() {
        Velora client = Velora.get();
        Hud hud = client == null ? null : client.getModuleManager().get(Hud.class);
        return hud == null ? 1f : hud.scale.getFloat();
    }

    // ----------------------------------------------------------------- toast

    /**
     * Shows the small top-centre pill that confirms a module toggle. Called from
     * {@link Module#setEnabled(boolean)}, so every toggle path (binds, ClickGUI,
     * commands) gets it without extra wiring.
     */
    public static void toast(String moduleName, boolean enabled) {
        NotificationsWidget.notify(moduleName, enabled);
    }

    /** Shows a neutral pill, used for feedback that is not a module toggle. */
    public static void message(String text) {
        NotificationsWidget.message(text);
    }

    @Override
    public void onRender2D(DrawContext context, float tickDelta) {
        // The dedicated editor screen draws the widgets itself in editor mode;
        // drawing here as well would paint every card twice.
        if (Velora.mc().currentScreen instanceof HudEditorScreen) {
            return;
        }
        renderWidgets(context, tickDelta, Velora.mc().currentScreen instanceof ChatScreen);
    }

    void renderWidgets(DrawContext context, float tickDelta, boolean editor) {
        float s = getScale();
        // Last top-edge widget drawn, so the next one can avoid overlapping it.
        HudWidget previous = null;
        for (HudWidget widget : widgets) {
            // In the HUD editor the widgets that are switched on are drawn even
            // when the Hud module itself is off, so their previews stay visible
            // and can be positioned. Switched-off widgets stay hidden: they would
            // cover the previews of the cards the user actually sees.
            boolean visible = editor ? widget.isEnabled()
                    : (isEnabled() && widget.isEnabled());
            if (!visible) {
                previous = null;
                continue;
            }

            // Only widgets that still follow their default spot take part in the
            // corner stack; one the user moved stays exactly where it was put.
            // The editor uses the same layout so dragging starts from the design.
            boolean inStack = widget.isTopRow() && !widget.hasCustomPosition()
                    && widget.hudWidth() > 0f && widget.hudHeight() > 0f;
            if (inStack && previous != null && previous.sharesTopCorner(widget)) {
                // Two widgets claim the same top corner: the later one waits below.
                widget.setStackedTop(previous.hudY() + previous.hudHeight() + 4f * s);
            } else {
                widget.clearStackedTop();
            }

            widget.prepareFrame();
            float anchorX = widget.hudX();
            float anchorY = widget.hudY();
            boolean scaled = s != 1f && !Float.isNaN(anchorX);
            if (scaled) {
                // Scale around the corner; the card itself is not moved.
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(anchorX, anchorY);
                context.getMatrices().scale(s, s);
                context.getMatrices().translate(-anchorX, -anchorY);
            }

            widget.render(context, tickDelta);

            if (scaled) {
                context.getMatrices().popMatrix();
            }
            previous = inStack ? widget : null;
        }
    }
}
