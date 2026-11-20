package hollow.probe;

import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Headless payload used by the test. Writes a marker on load and removes it on
 * unload, so the test can prove both directions without a display.
 */
public final class Probe {

    private static Path marker;

    public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
        marker = Paths.get(System.getProperty("java.io.tmpdir"), "hollow-probe.txt");
        String body = "pid=" + ProcessHandle.current().pid()
                + "\nargs=" + (args == null ? "" : args)
                + "\nlog4j=" + hasLog4j()
                + "\n";
        Files.write(marker, body.getBytes(StandardCharsets.UTF_8));
    }

    public static void unload() throws Exception {
        if (marker != null) Files.deleteIfExists(marker);
    }

    private static boolean hasLog4j() {
        try {
            Class.forName("org.apache.logging.log4j.LogManager");
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    private Probe() {
    }
}
