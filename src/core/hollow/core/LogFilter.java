package hollow.core;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Keeps hollow and everything it loads out of the game's log.
 *
 * Minecraft logs through log4j2. The root LoggerConfig receives every event
 * before any appender writes it, so a DENY filter installed there removes the
 * payload's output from latest.log and from the launcher console at the same
 * time, without touching the config file on disk.
 *
 * If log4j2 is not on the classpath the install is a no-op and the stream
 * swap in Bootstrap is the only suppression in play.
 */
final class LogFilter {

    private static final Set<String> DENIED_PREFIXES = new CopyOnWriteArraySet<>();
    private static volatile boolean installed;

    static {
        DENIED_PREFIXES.add("hollow.");
    }

    static void install() {
        if (installed) return;
        try {
            Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
            Object context = logManager.getMethod("getContext", boolean.class).invoke(null, false);
            Object configuration = context.getClass().getMethod("getConfiguration").invoke(context);
            Object rootConfig = configuration.getClass().getMethod("getRootLogger").invoke(configuration);

            Class<?> filterType = Class.forName("org.apache.logging.log4j.core.Filter");
            Class<?> resultType = Class.forName("org.apache.logging.log4j.core.Filter$Result");
            Class<?> stateType = Class.forName("org.apache.logging.log4j.core.LifeCycle$State");
            Class<?> eventType = Class.forName("org.apache.logging.log4j.core.LogEvent");

            Object deny = enumConstant(resultType, "DENY");
            Object neutral = enumConstant(resultType, "NEUTRAL");
            Object started = enumConstant(stateType, "STARTED");

            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "filter" -> {
                        if (args == null || args.length != 1 || !eventType.isInstance(args[0])) return neutral;
                        Object name = eventType.getMethod("getLoggerName").invoke(args[0]);
                        if (name instanceof String s && isDenied(s)) return deny;
                        return neutral;
                    }
                    case "getState" -> {
                        return started;
                    }
                    case "getOnMatch", "getOnMismatch" -> {
                        return neutral;
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "toString" -> {
                        return "hollow.LogFilter";
                    }
                    default -> {
                        return null;
                    }
                }
            };

            Object filter = Proxy.newProxyInstance(filterType.getClassLoader(),
                    new Class<?>[]{filterType}, handler);
            rootConfig.getClass().getMethod("addFilter", filterType).invoke(rootConfig, filter);
            installed = true;
        } catch (Throwable ignored) {
            // no log4j2 here, or a shape we do not recognise; stream silence still holds
        }
    }

    static void deny(String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isEmpty()) return;
        DENIED_PREFIXES.add(packagePrefix.endsWith(".") ? packagePrefix : packagePrefix + ".");
    }

    static boolean isDenied(String loggerName) {
        for (String prefix : DENIED_PREFIXES) {
            if (loggerName.startsWith(prefix)) return true;
        }
        return false;
    }

    static PrintStream silentStream() {
        return new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
    }

    private static Object enumConstant(Class<?> type, String name) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object value = Enum.valueOf((Class) type, name);
        return value;
    }

    private LogFilter() {
    }
}
