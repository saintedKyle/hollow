package hollow.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback control channel.
 *
 * Bound to 127.0.0.1 on a port the injector picked, guarded by a 128-bit token
 * the injector generated. Nothing is discoverable from off the machine and
 * nothing is written to the game's console.
 *
 * Protocol, one UTF-8 line per message:
 *
 *   HELLO &lt;token&gt;                -&gt; OK &lt;pid&gt; &lt;java.version&gt; &lt;user.dir&gt;
 *   LOAD &lt;b64path&gt; &lt;class|-&gt; &lt;b64args|-&gt;  -&gt; OK &lt;id&gt; | ERR &lt;reason&gt;
 *   UNLOAD &lt;id&gt;                  -&gt; OK &lt;note&gt; | ERR &lt;reason&gt;
 *   LIST                         -&gt; one row per payload, then "."
 *   PING                         -&gt; PONG
 *   QUIT                         -&gt; BYE, then the server stops
 */
final class Control {

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private static volatile ServerSocket server;
    private static int boundPort;

    static int start(int requestedPort, String token) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 4);
        server = socket;
        boundPort = socket.getLocalPort();

        Thread acceptor = new Thread(() -> acceptLoop(socket, token), "hollow-control");
        acceptor.setDaemon(true);
        acceptor.start();
        return boundPort;
    }

    static int port() {
        return boundPort;
    }

    static void stop() {
        RUNNING.set(false);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing a dead socket is not an error
        }
    }

    private static void acceptLoop(ServerSocket socket, String token) {
        while (RUNNING.get()) {
            try {
                Socket client = socket.accept();
                Thread worker = new Thread(() -> serve(client, token), "hollow-control-client");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private static void serve(Socket client, String token) {
        try (Socket socket = client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String hello = in.readLine();
            if (hello == null || !hello.equals("HELLO " + token)) {
                out.println("ERR bad token");
                return;
            }
            out.println("OK " + ProcessHandle.current().pid()
                    + " " + System.getProperty("java.version")
                    + " " + System.getProperty("user.dir"));

            String line;
            while ((line = in.readLine()) != null) {
                if (!dispatch(line, out)) return;
            }
        } catch (IOException ignored) {
            // the injector went away; the game keeps running
        }
    }

    private static boolean dispatch(String line, PrintWriter out) {
        try {
            int space = line.indexOf(' ');
            String verb = space < 0 ? line : line.substring(0, space);
            String rest = space < 0 ? "" : line.substring(space + 1);

            switch (verb) {
                case "PING" -> out.println("PONG");
                case "LOAD" -> {
                    String[] parts = rest.split(" ", 3);
                    if (parts.length < 2) {
                        out.println("ERR malformed LOAD");
                        break;
                    }
                    Path jar = Paths.get(new String(B64.decode(parts[0]), StandardCharsets.UTF_8));
                    String className = parts[1].equals("-") ? null
                            : new String(B64.decode(parts[1]), StandardCharsets.UTF_8);
                    String args = parts.length > 2 && !parts[2].equals("-") ? new String(B64.decode(parts[2]), StandardCharsets.UTF_8) : null;
                    String id = Payloads.load(jar, className, args);
                    out.println("OK " + id);
                }
                case "UNLOAD" -> out.println("OK " + Payloads.unload(rest.trim()));
                case "LIST" -> {
                    List<String> rows = Payloads.list();
                    for (String row : rows) out.println(row);
                    out.println(".");
                }
                case "QUIT" -> {
                    out.println("BYE");
                    Payloads.clear();
                    Bootstrap.removeHandshake();
                    stop();
                    return false;
                }
                default -> out.println("ERR unknown verb " + verb);
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            String message = cause.toString().replace('\n', ' ').replace('\r', ' ');
            out.println("ERR " + message);
        }
        return true;
    }

    private Control() {
    }
}
