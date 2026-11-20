package hollow.harness;

import java.lang.reflect.Method;

/**
 * Target JVM that logs through log4j2 twice a second, so the test can prove
 * that the game's own lines survive while the payload's lines do not.
 *
 * log4j2 is reached reflectively: the fixtures compile without it on the
 * classpath and the test supplies the jars at runtime.
 */
public final class LogHarness {

    public static void main(String[] args) throws Exception {
        Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
        Class<?> loggerType = Class.forName("org.apache.logging.log4j.Logger");
        Object logger = logManager.getMethod("getLogger", String.class).invoke(null, "harness.tick");
        Method info = loggerType.getMethod("info", String.class);

        System.out.println("log harness pid " + ProcessHandle.current().pid());
        System.out.flush();

        for (int tick = 0; tick < 400; tick++) {
            info.invoke(logger, "HARNESS-TICK-" + tick);
            Thread.sleep(150);
        }
    }

    private LogHarness() {
    }
}
