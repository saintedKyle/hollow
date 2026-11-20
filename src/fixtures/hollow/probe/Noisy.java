package hollow.probe;

import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Logging payload. Writes the same line through System.out and through log4j2
 * under the hollow.probe logger, then touches a marker so the test knows the
 * payload actually ran.
 */
public final class Noisy {

    public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
        System.out.println("HOLLOW-SYS-OUT-SHOULD-NOT-APPEAR");
        System.err.println("HOLLOW-SYS-ERR-SHOULD-NOT-APPEAR");

        try {
            Class<?> manager = Class.forName("org.apache.logging.log4j.LogManager");
            Object logger = manager.getMethod("getLogger", String.class).invoke(null, "hollow.probe.Noisy");
            Class<?> loggerType = Class.forName("org.apache.logging.log4j.Logger");
            loggerType.getMethod("warn", String.class)
                    .invoke(logger, "HOLLOW-LOG4J-SHOULD-NOT-APPEAR");
        } catch (ClassNotFoundException absent) {
            // no log4j2 on the classpath; the stream check still runs
        }

        Path marker = Paths.get(System.getProperty("java.io.tmpdir"), "hollow-noisy.txt");
        Files.write(marker, "ran".getBytes(StandardCharsets.UTF_8));
    }

    private Noisy() {
    }
}
