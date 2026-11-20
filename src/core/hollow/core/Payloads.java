package hollow.core;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * The loaded-payload registry.
 *
 * A payload enters the JVM in one of two ways:
 *
 *   agent     - the jar declares an Agent-Class, or a class with a public
 *               static agentmain(String, Instrumentation) / premain(...).
 *               hollow appends the jar to the system class loader and invokes
 *               the entry point reflectively. This is exactly what the JVM
 *               does for -javaagent, minus the command line.
 *
 *   classpath - the jar declares nothing. hollow appends it to the system
 *               class loader so anything that later looks the classes up
 *               finds them.
 *
 * Ejection is reflective and opt-in. A payload that defines a public static
 * void unload() (or onUnload()) gets called. Classes cannot be removed from a
 * loaded class loader, so a payload with no hook stays resident until the game
 * exits; hollow reports that instead of pretending otherwise.
 */
final class Payloads {

    static final class Payload {
        final String id;
        final String className;
        final Path jar;
        final Class<?> type;

        Payload(String id, String className, Path jar, Class<?> type) {
            this.id = id;
            this.className = className;
            this.jar = jar;
            this.type = type;
        }
    }

    private static final Map<String, Payload> LOADED = new LinkedHashMap<>();
    private static final Set<String> APPENDED = ConcurrentHashMap.newKeySet();
    private static Instrumentation instrumentation;
    private static int counter;

    static void bind(Instrumentation inst) {
        instrumentation = inst;
    }

    static synchronized String load(Path jar, String className, String agentArgs) throws Exception {
        if (instrumentation == null) throw new IllegalStateException("instrumentation not bound");

        String key = jar.toAbsolutePath().normalize().toString();
        if (APPENDED.add(key)) {
            instrumentation.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));
        }

        String id = "p" + (++counter);
        if (className == null || className.isBlank()) {
            LOADED.put(id, new Payload(id, null, jar, null));
            return id;
        }

        Class<?> type = Class.forName(className, true, ClassLoader.getSystemClassLoader());
        Method entry = findEntry(type);
        if (entry == null) {
            LOADED.put(id, new Payload(id, className, jar, type));
            return id;
        }

        LogFilter.deny(packageOf(type));
        registerJarPackages(jar);

        // The payload's own startup output is the loudest thing it does.
        // Swap the streams for the duration of the entry call and put them
        // back the moment it returns. Anything it prints later from its own
        // threads is caught by the log4j2 filter if it goes through a logger.
        PrintStream out = System.out;
        PrintStream err = System.err;
        System.setOut(LogFilter.silentStream());
        System.setErr(LogFilter.silentStream());
        try {
            entry.setAccessible(true);
            if (entry.getParameterCount() == 2) {
                entry.invoke(null, agentArgs, instrumentation);
            } else {
                entry.invoke(null, agentArgs);
            }
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        LOADED.put(id, new Payload(id, className, jar, type));
        return id;
    }

    static synchronized String unload(String id) {
        Payload payload = LOADED.get(id);
        if (payload == null) throw new IllegalStateException("no payload " + id);
        if (payload.type == null) {
            LOADED.remove(id);
            return "detached, jar stays on the classpath";
        }
        Method hook = findUnload(payload.type);
        if (hook == null) {
            LOADED.remove(id);
            return "no unload hook, classes stay loaded until the game exits";
        }
        try {
            hook.setAccessible(true);
            hook.invoke(null);
            LOADED.remove(id);
            return "unloaded";
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            throw new IllegalStateException("unload hook failed: " + cause);
        }
    }

    static synchronized List<String> list() {
        List<String> rows = new ArrayList<>(LOADED.size());
        for (Payload p : LOADED.values()) {
            rows.add(p.id + " " + (p.className == null ? "-" : p.className) + " " + p.jar.getFileName());
        }
        return rows;
    }

    static synchronized void clear() {
        LOADED.clear();
    }

    private static Method findEntry(Class<?> type) {
        for (String name : new String[]{"agentmain", "premain"}) {
            for (Method m : type.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())) continue;
                if (m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class
                        && m.getParameterTypes()[1] == Instrumentation.class) {
                    return m;
                }
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Method findUnload(Class<?> type) {
        for (String name : new String[]{"unload", "onUnload"}) {
            try {
                Method m = type.getDeclaredMethod(name);
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // try the next name
            }
        }
        return null;
    }

    private static String packageOf(Class<?> type) {
        Package pkg = type.getPackage();
        if (pkg != null) return pkg.getName();
        String name = type.getName();
        int cut = name.lastIndexOf('.');
        return cut < 0 ? "" : name.substring(0, cut);
    }

    /**
     * A payload usually ships more than one package and logs from whichever
     * one it likes. Register every package the jar carries, so the filter
     * covers the payload rather than just its entry class.
     */
    private static void registerJarPackages(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Set<String> packages = new java.util.LinkedHashSet<>();
            for (var e = jf.entries(); e.hasMoreElements() && packages.size() < 64; ) {
                String name = e.nextElement().getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                if (name.equals("module-info.class")) continue;
                int cut = name.lastIndexOf('/');
                if (cut <= 0) continue;
                packages.add(name.substring(0, cut).replace('/', '.'));
            }
            for (String pkg : packages) LogFilter.deny(pkg);
        } catch (Exception unreadable) {
            // the jar was already opened once; a second failure changes nothing
        }
    }

    private Payloads() {
    }
}
