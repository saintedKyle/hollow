package hollow.core;

import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * hollow agent entry point.
 *
 * Loaded into a running JVM through the attach API. Does four things, in order:
 *
 *   1. Swaps System.out/System.err for a null stream so nothing hollow prints
 *      reaches the game's console or log while the payload is being attached.
 *   2. Installs a log4j2 root filter that drops events from hollow and from the
 *      payload's package.
 *   3. Starts the loopback control server on the port the injector asked for.
 *   4. Writes a small handshake file in the user's temp directory so a later
 *      run of the injector can find the channel again without re-attaching.
 *
 * The handshake file is deleted on QUIT. Nothing else is written to disk.
 */
public final class Bootstrap {

    private static final String STATE_DIR = "hollow";

    public static void agentmain(String args, Instrumentation inst) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        System.setOut(LogFilter.silentStream());
        System.setErr(LogFilter.silentStream());
        try {
            Map<String, String> cfg = parseArgs(args);
            int port = Integer.parseInt(cfg.getOrDefault("port", "0"));
            String token = cfg.getOrDefault("token", "");
            if (port <= 0 || token.isEmpty()) return;

            Payloads.bind(inst);
            LogFilter.install();
            int bound = Control.start(port, token);
            writeHandshake(bound, token);
        } catch (Throwable ignored) {
            // the game must not learn that anything happened
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    static Path handshakeFile() {
        return Paths.get(System.getProperty("java.io.tmpdir"), STATE_DIR,
                ProcessHandle.current().pid() + ".json");
    }

    static void removeHandshake() {
        try {
            Files.deleteIfExists(handshakeFile());
        } catch (Exception ignored) {
            // a stale temp file is harmless
        }
    }

    private static void writeHandshake(int port, String token) throws Exception {
        Path file = handshakeFile();
        Files.createDirectories(file.getParent());
        String body = "{\"port\":" + port + ",\"token\":\"" + token + "\"}";
        Files.write(file, body.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
    }

    private static Map<String, String> parseArgs(String args) {
        Map<String, String> cfg = new HashMap<>();
        if (args == null) return cfg;
        for (String pair : args.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) cfg.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return cfg;
    }

    private Bootstrap() {
    }
}
