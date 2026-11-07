package dev.velora.misc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.velora.Velora;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Minimal Discord IPC client.
 *
 * <p>Rich Presence talks to the desktop app over a local socket: on Windows a
 * named pipe ({@code \\.\pipe\discord-ipc-0}), everywhere else a unix socket in
 * {@code $XDG_RUNTIME_DIR}. The wire format is an 8-byte little-endian header
 * (opcode + payload length) followed by JSON. That is small enough to implement
 * without pulling a third-party RPC library into the mod.</p>
 *
 * <p>The connection lives on its own daemon thread: a closed Discord client, a
 * refused pipe or a malformed frame can never stall the game, it simply retries
 * in the background. Updates are throttled to Discord's own limit of five
 * activity updates per twenty seconds.</p>
 */
public final class DiscordRpc {

    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    /** Discord allows 5 updates / 20 s; keep a little headroom. */
    private static final long MIN_UPDATE_INTERVAL_MS = 4_200L;
    private static final long RECONNECT_DELAY_MS = 5_000L;
    private static final int MAX_PIPE_INDEX = 9;

    private final String clientId;

    private volatile Transport transport;
    private volatile boolean running;
    private volatile boolean handshaked;
    private volatile JsonObject activity;
    private volatile boolean dirty;

    private Thread thread;
    private long lastSentAt;

    public DiscordRpc(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return handshaked && transport != null;
    }

    /** Starts the background thread; a no-op when the id is empty or already running. */
    public void start() {
        if (running || clientId.isEmpty()) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "Velora-Discord-RPC");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread worker = thread;
        thread = null;
        if (worker != null) {
            worker.interrupt();
        }
        // Best effort: clear the presence before the socket goes away, otherwise
        // Discord keeps showing the last activity until the client restarts.
        if (handshaked && transport != null) {
            try {
                activity = null;
                sendActivity();
            } catch (Throwable ignored) {
                // Discord already gone: closing the pipe is enough.
            }
        }
        handshaked = false;
        closeTransport();
    }

    /** Replaces the activity; {@code null} removes the presence. */
    public void setActivity(JsonObject value) {
        this.activity = value;
        this.dirty = true;
    }

    // ------------------------------------------------------------------ worker

    private void loop() {
        while (running) {
            try {
                if (transport == null) {
                    Transport opened = open();
                    if (opened == null) {
                        if (sleep(RECONNECT_DELAY_MS)) {
                            return;
                        }
                        continue;
                    }
                    transport = opened;
                    handshaked = false;
                }
                if (!handshaked) {
                    handshake();
                }
                long sinceLast = System.currentTimeMillis() - lastSentAt;
                if (dirty && sinceLast >= MIN_UPDATE_INTERVAL_MS) {
                    sendActivity();
                    dirty = false;
                    lastSentAt = System.currentTimeMillis();
                }
            } catch (Throwable throwable) {
                // Discord closed, the pipe was replaced by an update, or the
                // payload was rejected: drop the socket and try again later.
                Velora.LOGGER.debug("Discord IPC disconnected: {}", throwable.toString());
                handshaked = false;
                closeTransport();
                dirty = true;
                if (sleep(RECONNECT_DELAY_MS)) {
                    return;
                }
                continue;
            }
            if (sleep(150L)) {
                return;
            }
        }
    }

    private void handshake() throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1);
        payload.addProperty("client_id", clientId);
        write(OP_HANDSHAKE, payload.toString());
        handshaked = true;
        Velora.LOGGER.info("Discord RPC handshake sent for application {}", clientId);
    }

    private void sendActivity() throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("cmd", "SET_ACTIVITY");

        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        JsonElement current = activity;
        args.add("activity", current == null ? JsonNull.INSTANCE : current);

        payload.add("args", args);
        payload.addProperty("nonce", UUID.randomUUID().toString());
        write(OP_FRAME, payload.toString());
    }

    private void write(int opcode, String json) throws IOException {
        Transport current = transport;
        if (current == null) {
            throw new IOException("Discord IPC is not connected");
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[8 + body.length];
        putIntLittleEndian(frame, 0, opcode);
        putIntLittleEndian(frame, 4, body.length);
        System.arraycopy(body, 0, frame, 8, body.length);
        current.write(frame);
    }

    private static void putIntLittleEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    // ------------------------------------------------------------- transports

    private Transport open() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            for (int i = 0; i < MAX_PIPE_INDEX; i++) {
                try {
                    RandomAccessFile file = new RandomAccessFile(
                            "\\\\.\\pipe\\discord-ipc-" + i, "rw");
                    return new FileTransport(file);
                } catch (FileNotFoundException ignored) {
                    // Discord is not listening on this index; try the next one.
                }
            }
            return null;
        }

        String runtime = System.getenv("XDG_RUNTIME_DIR");
        String tmp = System.getenv("TMPDIR");
        String[] roots = {runtime, tmp, "/tmp"};
        for (String root : roots) {
            if (root == null || root.isEmpty()) {
                continue;
            }
            for (int i = 0; i < MAX_PIPE_INDEX; i++) {
                Path socket = Path.of(root, "discord-ipc-" + i);
                try {
                    SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                    channel.connect(UnixDomainSocketAddress.of(socket));
                    return new UnixTransport(channel);
                } catch (Throwable ignored) {
                    // Socket missing or already taken; keep scanning.
                }
            }
        }
        return null;
    }

    private void closeTransport() {
        Transport current = transport;
        transport = null;
        if (current != null) {
            current.close();
        }
    }

    /** @return {@code true} when the thread was interrupted and should stop. */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private interface Transport {
        void write(byte[] bytes) throws IOException;

        void close();
    }

    private record FileTransport(RandomAccessFile file) implements Transport {

        @Override
        public void write(byte[] bytes) throws IOException {
            file.write(bytes);
        }

        @Override
        public void close() {
            try {
                file.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }

    private record UnixTransport(SocketChannel channel) implements Transport {

        @Override
        public void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }
}
