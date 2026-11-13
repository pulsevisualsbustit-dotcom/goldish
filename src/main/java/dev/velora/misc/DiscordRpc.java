package dev.velora.misc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.velora.Velora;

import java.io.EOFException;
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
 * <p>The socket is driven by two daemon threads: the worker connects, sends the
 * handshake and throttled activity updates, while a reader drains the replies.
 * Reading matters even though nothing is displayed from them: Discord answers
 * the handshake with {@code READY}, sends {@code PING} frames that must be
 * echoed as {@code PONG}, and closes the connection when the client goes away.
 * Without the reader the presence would only ever be sent once and a Discord
 * restart would go unnoticed. A closed client, a refused pipe or a malformed
 * frame can never stall the game; it simply retries in the background. Updates
 * are throttled to Discord's own limit of five activity updates per twenty
 * seconds.</p>
 *
 * <p>Windows pipes need special care: while a blocking {@code ReadFile} is
 * pending on a pipe handle, every {@code WriteFile} on that same handle waits
 * for it to finish. A reader parked in {@code readFully} would therefore freeze
 * the first activity update forever. The Windows transport avoids that by
 * asking the pipe how many bytes are queued and only then reading them.</p>
 */
public final class DiscordRpc {

    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;
    private static final int OP_PING = 3;
    private static final int OP_PONG = 4;

    /** Discord allows 5 updates / 20 s; keep a little headroom. */
    private static final long MIN_UPDATE_INTERVAL_MS = 4_200L;
    /** Re-send an unchanged presence so a dead pipe is noticed within a minute. */
    private static final long RESEND_INTERVAL_MS = 30_000L;
    private static final long RECONNECT_DELAY_MS = 5_000L;
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000L;
    /** Discord never sends bigger payloads; reject garbage instead of buffering it. */
    private static final int MAX_FRAME_BYTES = 1 << 20;
    private static final int MAX_PIPE_INDEX = 10;

    private final String clientId;

    private volatile Transport transport;
    private volatile boolean running;
    private volatile boolean handshaked;
    private volatile JsonObject activity;
    private volatile boolean dirty;

    private Thread worker;
    private Thread reader;
    private long connectedAt;
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

    /** Starts the background threads; a no-op when the id is empty or already running. */
    public void start() {
        if (running || clientId.isEmpty()) {
            return;
        }
        running = true;
        connectedAt = 0L;
        lastSentAt = 0L;
        worker = new Thread(this::loop, "Velora-Discord-RPC");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        Transport current = transport;
        transport = null;
        running = false;
        handshaked = false;
        activity = null;
        Thread readerThread = reader;
        reader = null;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        Thread workerThread = worker;
        worker = null;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (current == null) {
            return;
        }
        // Best effort: clear the presence before the socket goes away, otherwise
        // Discord keeps showing the last activity until the client restarts.
        // A pipe write may block, so it happens on its own thread: the caller is
        // usually the game thread and must never wait on the socket.
        Thread closer = new Thread(() -> {
            try {
                writeFrame(current, OP_FRAME, activityPayload(null));
            } catch (Throwable ignored) {
                // Discord already gone: closing the pipe is enough.
            }
            current.close();
        }, "Velora-Discord-RPC-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Replaces the activity; {@code null} removes the presence. */
    public void setActivity(JsonObject value) {
        this.activity = value;
        this.dirty = true;
    }

    // ------------------------------------------------------------------ worker

    private void loop() {
        while (running) {
            Transport current = transport;
            try {
                if (current == null) {
                    current = open();
                    if (current == null) {
                        if (sleep(RECONNECT_DELAY_MS)) {
                            return;
                        }
                        continue;
                    }
                    if (!running) {
                        // stop() ran while the pipe was being opened.
                        current.close();
                        return;
                    }
                    transport = current;
                    if (!running) {
                        // stop() ran between the check above and this assignment.
                        dropTransport(current);
                        return;
                    }
                    connectedAt = System.currentTimeMillis();
                    lastSentAt = 0L;
                    handshaked = false;
                    startReader(current);
                    handshake(current);
                }
                if (handshaked) {
                    long sinceLast = System.currentTimeMillis() - lastSentAt;
                    // Sending an unchanged payload now and then doubles as a
                    // heartbeat: if Discord died, the write fails and the worker
                    // reconnects instead of waiting for the text to change.
                    boolean heartbeat = sinceLast >= RESEND_INTERVAL_MS;
                    if ((dirty || heartbeat) && sinceLast >= MIN_UPDATE_INTERVAL_MS) {
                        JsonObject snapshot = activity;
                        sendActivity(current, snapshot);
                        // If setActivity() won the race, keep the flag so the
                        // newer payload goes out on the next interval.
                        dirty = activity != snapshot;
                        lastSentAt = System.currentTimeMillis();
                    }
                } else if (System.currentTimeMillis() - connectedAt > HANDSHAKE_TIMEOUT_MS) {
                    throw new IOException("Discord did not answer the handshake");
                }
            } catch (Throwable throwable) {
                // Discord closed, the pipe was replaced by an update, or the
                // payload was rejected: drop the socket and try again later.
                Velora.LOGGER.debug("Discord IPC disconnected: {}", throwable.toString());
                handshaked = false;
                dirty = true;
                dropTransport(current);
                if (sleep(RECONNECT_DELAY_MS)) {
                    return;
                }
                continue;
            }
            if (sleep(100L)) {
                return;
            }
        }
    }

    private void handshake(Transport connection) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1);
        payload.addProperty("client_id", clientId);
        writeFrame(connection, OP_HANDSHAKE, payload.toString());
    }

    private void sendActivity(Transport connection, JsonObject value) throws IOException {
        writeFrame(connection, OP_FRAME, activityPayload(value));
    }

    private String activityPayload(JsonObject value) {
        JsonObject payload = new JsonObject();
        payload.addProperty("cmd", "SET_ACTIVITY");

        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        args.add("activity", value == null ? JsonNull.INSTANCE : value);

        payload.add("args", args);
        payload.addProperty("nonce", UUID.randomUUID().toString());
        return payload.toString();
    }

    // ------------------------------------------------------------------ reader

    private void startReader(Transport connection) {
        Thread thread = new Thread(() -> readLoop(connection), "Velora-Discord-RPC-reader");
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    /**
     * Drains replies for one connection. Runs until the socket closes, then lets
     * the worker reconnect; the activity is marked dirty so it is re-sent.
     */
    private void readLoop(Transport connection) {
        try {
            while (running && transport == connection) {
                int opcode = connection.readInt();
                int length = connection.readInt();
                if (opcode < 0 || length < 0 || length > MAX_FRAME_BYTES) {
                    throw new IOException("Malformed Discord IPC frame");
                }
                String json = new String(connection.readFully(length), StandardCharsets.UTF_8);
                handleFrame(connection, opcode, json);
            }
        } catch (Throwable throwable) {
            if (running && transport == connection) {
                Velora.LOGGER.debug("Discord IPC read failed: {}", throwable.toString());
                dirty = true;
                dropTransport(connection);
            }
        }
    }

    private void handleFrame(Transport connection, int opcode, String json) throws IOException {
        switch (opcode) {
            case OP_PING -> writeFrame(connection, OP_PONG, json);
            case OP_CLOSE -> throw new IOException("Discord closed the IPC connection");
            case OP_FRAME -> handleDispatch(json);
            default -> {
                // Unknown opcode: ignore, the connection stays usable.
            }
        }
    }

    private void handleDispatch(String json) {
        JsonObject frame;
        try {
            frame = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException malformed) {
            return;
        }
        String event = string(frame, "evt");
        if ("READY".equals(event)) {
            handshaked = true;
            // A reconnect starts from a clean slate: push the presence again
            // even if RichPresence considers it unchanged.
            dirty = true;
            Velora.LOGGER.info("Discord RPC connected for application {}", clientId);
        } else if ("ERROR".equals(event)) {
            Velora.LOGGER.warn("Discord RPC rejected a payload: {}", json);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    // ------------------------------------------------------------- transports

    private void writeFrame(Transport connection, int opcode, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[8 + body.length];
        putIntLittleEndian(frame, 0, opcode);
        putIntLittleEndian(frame, 4, body.length);
        System.arraycopy(body, 0, frame, 8, body.length);
        // PONGs come from the reader, activities from the worker: never let two
        // frames interleave on the same pipe.
        synchronized (connection) {
            connection.write(frame);
        }
    }

    private static void putIntLittleEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static int getIntLittleEndian(byte[] source) {
        return (source[0] & 0xFF)
                | ((source[1] & 0xFF) << 8)
                | ((source[2] & 0xFF) << 16)
                | ((source[3] & 0xFF) << 24);
    }

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
        // Flatpak and Snap put the socket one directory deeper.
        String[] roots = {
                runtime,
                runtime == null ? null : runtime + "/app/com.discordapp.Discord",
                runtime == null ? null : runtime + "/app/com.discordapp.DiscordCanary",
                runtime == null ? null : runtime + "/snap.discord",
                tmp,
                "/tmp"
        };
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

    /** Closes the socket if {@code expected} is still the active one. */
    private void dropTransport(Transport expected) {
        if (expected == null) {
            return;
        }
        if (transport == expected) {
            transport = null;
            handshaked = false;
        }
        // Closing the handle belongs to the connection that failed, even when a
        // newer one has already taken its place.
        expected.close();
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

        int readInt() throws IOException;

        byte[] readFully(int length) throws IOException;

        void close();
    }

    private record FileTransport(RandomAccessFile file) implements Transport {

        /**
         * A blocking read on a Windows pipe stalls writes on the same handle
         * until it returns, so the reader polls instead of waiting inside the
         * kernel. 25 ms is well below a frame and costs nothing else.
         */
        private static final long READ_POLL_MS = 25L;

        @Override
        public void write(byte[] bytes) throws IOException {
            file.write(bytes);
        }

        @Override
        public int readInt() throws IOException {
            return getIntLittleEndian(readFully(4));
        }

        @Override
        public byte[] readFully(int length) throws IOException {
            // Only read bytes the pipe reports as queued: never issue a read
            // that could stay pending while the worker tries to write.
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                long available = file.length();
                if (available <= 0) {
                    try {
                        Thread.sleep(READ_POLL_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Discord IPC read interrupted");
                    }
                    continue;
                }
                int chunk = (int) Math.min(available, (long) (length - offset));
                file.readFully(bytes, offset, chunk);
                offset += chunk;
            }
            return bytes;
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
        public int readInt() throws IOException {
            return getIntLittleEndian(readFully(4));
        }

        @Override
        public byte[] readFully(int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new EOFException("Discord IPC closed");
                }
            }
            return buffer.array();
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
