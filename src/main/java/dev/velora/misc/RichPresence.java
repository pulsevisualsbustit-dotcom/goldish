package dev.velora.misc;

import com.google.gson.JsonObject;
import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import net.minecraft.client.network.ServerInfo;

/**
 * Discord Rich Presence.
 *
 * <p>Shows "Velora Client" on the Discord profile with the current server, the
 * number of enabled modules and a running session timer.</p>
 *
 * <p>The presence uses the public "Testing RPC" application that ships with the
 * official discord-rpc examples, so it works out of the box and nothing has to
 * be registered by the user. Only text is displayed: Discord takes the activity
 * title and art assets from the application itself, and a dedicated application
 * would be required for a custom logo. To use one, replace
 * {@link #APPLICATION_ID} with its id and add the {@code assets} block back to
 * the activity in {@link #push()}.</p>
 */
public class RichPresence extends Module {

    /** Public discord-rpc example application; usable without registering anything. */
    private static final String APPLICATION_ID = "345229890980937739";

    private final BooleanSetting showServer = register(new BooleanSetting(
            "Show server", "Показывать сервер, на котором вы играете", true));
    private final BooleanSetting showModules = register(new BooleanSetting(
            "Show modules", "Показывать число включённых модулей", true));

    private DiscordRpc rpc;
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

    /** The presence must also update in the main menu, not only in a world. */
    @Override
    public boolean ticksOutsideWorld() {
        return true;
    }

    @Override
    public void onTick() {
        if (rpc == null) {
            connect();
        }
        if (rpc == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBuildAt < 1_000L) {
            return;
        }
        lastBuildAt = now;
        push();
    }

    private void connect() {
        rpc = new DiscordRpc(APPLICATION_ID);
        rpc.start();
        lastBuildAt = 0L;
    }

    private void disconnect() {
        if (rpc != null) {
            // stop() clears the presence on Discord before closing the socket.
            rpc.stop();
            rpc = null;
        }
        lastPayload = "";
    }

    private void push() {
        JsonObject activity = new JsonObject();
        activity.addProperty("details", details());
        activity.addProperty("state", state());

        JsonObject timestamps = new JsonObject();
        // Discord expects unix seconds, not milliseconds.
        timestamps.addProperty("start", Velora.START_TIME / 1000L);
        activity.add("timestamps", timestamps);

        String payload = activity.toString();
        if (payload.equals(lastPayload)) {
            return;
        }
        lastPayload = payload;
        rpc.setActivity(activity);
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
