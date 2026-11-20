package hollow.injector;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * One control channel to one game process.
 *
 * The first call attaches hollow-core. Later calls reuse the channel recorded
 * in the temp handshake file, so ejecting from a fresh run of the injector
 * does not re-attach anything.
 */
final class Session implements AutoCloseable {

    private final long pid;
    private final Remote remote;

    private Session(long pid, Remote remote) {
        this.pid = pid;
        this.remote = remote;
    }

    static Session ensure(long pid, Path coreJar) throws Exception {
        Path state = stateFile(pid);

        Session reused = tryExisting(pid, state);
        if (reused != null) return reused;

        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int port = freePort();
            String token = newToken();
            Files.deleteIfExists(state);
            Attach.loadAgent(coreJar, pid, "port=" + port + ";token=" + token);

            for (int probe = 0; probe < 25; probe++) {
                try {
                    return new Session(pid, Remote.open(port, token));
                } catch (IOException notYet) {
                    last = notYet;
                    Thread.sleep(80);
                }
            }
        }
        throw new IOException("could not open a control channel to pid " + pid
                + (last == null ? "" : ": " + last.getMessage()));
    }

    /** Connects only if hollow-core is already resident. Never attaches. */
    static Session existing(long pid) {
        return tryExisting(pid, stateFile(pid));
    }

    private static Session tryExisting(long pid, Path state) {
        if (!Files.isRegularFile(state)) return null;
        try {
            String body = Files.readString(state, StandardCharsets.UTF_8);
            int port = intField(body, "port");
            String token = textField(body, "token");
            if (port <= 0 || token == null) return null;
            Remote remote = Remote.open(port, token);
            if (remote.remotePid() != pid) {
                remote.close();
                return null;
            }
            return new Session(pid, remote);
        } catch (Exception stale) {
            return null;
        }
    }

    Remote remote() {
        return remote;
    }

    String inject(Path jar, String entryClass, String agentArgs) throws IOException {
        String reply = remote.call("LOAD " + encode(jar.toString())
                + " " + (entryClass == null || entryClass.isBlank() ? "-" : encode(entryClass))
                + " " + (agentArgs == null || agentArgs.isEmpty() ? "-" : encode(agentArgs)));
        if (!reply.startsWith("OK ")) throw new IOException(strip(reply));
        return reply.substring(3).trim();
    }

    int ejectAll() throws IOException {
        int count = 0;
        for (String row : remote.rows()) {
            String id = row.split(" ", 2)[0];
            String reply = remote.call("UNLOAD " + id);
            if (reply.startsWith("OK ")) count++;
        }
        return count;
    }

    @Override
    public void close() {
        remote.close();
    }

    static Path stateFile(long pid) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "hollow", pid + ".json");
    }

    private static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String strip(String reply) {
        return reply.startsWith("ERR ") ? reply.substring(4) : reply;
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static int intField(String json, String key) {
        String needle = "\"" + key + "\":";
        int at = json.indexOf(needle);
        if (at < 0) return -1;
        int start = at + needle.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Integer.parseInt(json.substring(start, end));
    }

    private static String textField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int start = at + needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
}
