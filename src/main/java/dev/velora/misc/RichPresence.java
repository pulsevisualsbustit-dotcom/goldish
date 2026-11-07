package dev.velora.misc;

import com.google.gson.JsonObject;
import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.setting.TextSetting;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.math.MathHelper;

/**
 * Discord Rich Presence.
 *
 * <p>Shows "Velora Client" on the Discord profile with the server, the number of
 * enabled modules and a running session timer. The logo can be animated by
 * cycling art assets: Discord itself only accepts still images, so the client
 * swaps between numbered frames ({@code velora}, {@code velora_1}, ...) using
 * the slowest interval the Rich Presence rate limit allows.</p>
 *
 * <p>Requires a Discord application: create one at
 * {@code discord.com/developers/applications}, paste its id into the App ID
 * setting, then upload the logo frames as Art Assets with the matching keys.</p>
 */
public class RichPresence extends Module {

    private final TextSetting appId = register(new TextSetting(
            "App ID", "Discord application id from discord.com/developers", "", 24, true));
    private final TextSetting logo = register(new TextSetting(
            "Logo asset", "Art asset key of the first logo frame", "velora", 32, false));
    private final BooleanSetting animated = register(new BooleanSetting(
            "Animated logo", "Переключать <logo>, <logo>_1 ... как кадры", true));
    private final NumberSetting frames = register(new NumberSetting(
            "Logo frames", "Сколько кадров загружено для анимации", 8, 2, 8, 1)
            .visibleWhen(animated::get));
    private final NumberSetting frameTime = register(new NumberSetting(
            "Frame time", "Секунд на кадр (Discord ~1 обновление в 4 с)", 6.0, 4.5, 30.0, 0.5)
            .visibleWhen(animated::get));
    private final BooleanSetting showServer = register(new BooleanSetting(
            "Show server", "Показывать сервер, на котором вы играете", true));
    private final BooleanSetting showModules = register(new BooleanSetting(
            "Show modules", "Показывать число включённых модулей", true));

    private DiscordRpc rpc;
    private String connectedId = "";
    private String lastPayload = "";
    private long lastBuildAt;

    public RichPresence() {
        super("Discord RPC", "Shows the client in your Discord profile", Category.MISC);
    }

    @Override
    public void onEnable() {
        lastPayload = "";
        connect();
    }

    @Override
    public void onDisable() {
        disconnect();
    }

    @Override
    public void onTick() {
        String id = appId.getValue().trim();
        if (id.isEmpty()) {
            if (rpc != null) {
                disconnect();
            }
            return;
        }
        if (rpc == null || !id.equals(connectedId)) {
            disconnect();
            connect();
            if (rpc == null) {
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastBuildAt < 1_000L) {
            return;
        }
        lastBuildAt = now;
        push(now);
    }

    private void connect() {
        String id = appId.getValue().trim();
        if (id.isEmpty()) {
            Velora.LOGGER.info("Discord RPC is enabled but has no App ID; set one in the ClickGUI");
            return;
        }
        rpc = new DiscordRpc(id);
        rpc.start();
        connectedId = id;
        lastBuildAt = 0L;
    }

    private void disconnect() {
        if (rpc != null) {
            rpc.setActivity(null);
            rpc.stop();
            rpc = null;
        }
        connectedId = "";
        lastPayload = "";
    }

    private void push(long now) {
        JsonObject activity = new JsonObject();
        activity.addProperty("details", details());
        activity.addProperty("state", state());

        JsonObject timestamps = new JsonObject();
        // Discord expects unix seconds, not milliseconds.
        timestamps.addProperty("start", Velora.START_TIME / 1000L);
        activity.add("timestamps", timestamps);

        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", frameKey(now));
        assets.addProperty("large_text", Velora.NAME + " Client v" + Velora.VERSION);
        activity.add("assets", assets);

        String payload = activity.toString();
        if (payload.equals(lastPayload)) {
            return;
        }
        lastPayload = payload;
        rpc.setActivity(activity);
    }

    /** {@code <logo>} for the first frame, then {@code <logo>_1} ... {@code <logo>_(n-1)}. */
    private String frameKey(long now) {
        String base = logo.getValue().trim();
        if (base.isEmpty()) {
            base = "velora";
        }
        if (!animated.get()) {
            return base;
        }
        int count = MathHelper.clamp(Math.round(frames.getFloat()), 2, 8);
        long frameMillis = Math.max(4_500L, Math.round(frameTime.get() * 1_000.0));
        int frame = (int) ((now / frameMillis) % count);
        return frame == 0 ? base : base + "_" + frame;
    }

    private String details() {
        if (mc.world == null) {
            return "In the menus";
        }
        ServerInfo server = mc.getCurrentServerEntry();
        if (server == null) {
            return "Singleplayer";
        }
        if (!showServer.get()) {
            return "Multiplayer";
        }
        String address = server.address == null ? "" : server.address.trim();
        return address.isEmpty() ? "Multiplayer" : "Playing on " + address;
    }

    private String state() {
        String version = "v" + Velora.VERSION;
        if (!showModules.get()) {
            return version;
        }
        int enabled = Velora.get() == null ? 0
                : Velora.get().getModuleManager().getEnabledModules().size();
        return version + " · " + enabled + (enabled == 1 ? " module" : " modules");
    }

    @Override
    public String getSuffix() {
        return rpc != null && rpc.isConnected() ? "Connected" : "Offline";
    }
}
