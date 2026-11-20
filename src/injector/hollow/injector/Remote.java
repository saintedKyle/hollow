package hollow.injector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Client side of the loopback control protocol. */
final class Remote implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final String banner;

    private Remote(Socket socket, BufferedReader in, PrintWriter out, String banner) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.banner = banner;
    }

    static Remote open(int port, String token) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3000);
            socket.setSoTimeout(20_000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            out.println("HELLO " + token);
            String reply = in.readLine();
            if (reply == null || !reply.startsWith("OK ")) {
                socket.close();
                throw new IOException(reply == null ? "no handshake reply" : reply);
            }
            return new Remote(socket, in, out, reply.substring(3));
        } catch (IOException failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closing
            }
            throw failure;
        }
    }

    /** pid, java.version, user.dir */
    String banner() {
        return banner;
    }

    long remotePid() {
        String[] parts = banner.split(" ", 3);
        try {
            return Long.parseLong(parts[0]);
        } catch (RuntimeException notANumber) {
            return -1;
        }
    }

    String userDir() {
        String[] parts = banner.split(" ", 3);
        return parts.length < 3 ? null : parts[2];
    }

    String call(String line) throws IOException {
        out.println(line);
        String reply = in.readLine();
        if (reply == null) throw new IOException("control channel closed");
        return reply;
    }

    List<String> rows() throws IOException {
        out.println("LIST");
        List<String> rows = new ArrayList<>();
        for (int guard = 0; guard < 4096; guard++) {
            String line = in.readLine();
            if (line == null) throw new IOException("control channel closed");
            if (line.equals(".")) return rows;
            rows.add(line);
        }
        throw new IOException("LIST did not terminate");
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing left to do
        }
    }
}
