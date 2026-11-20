package hollow.injector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Entry point. No arguments opens the window; anything else is a scriptable
 * one-shot that prints to stdout and exits with a meaningful code.
 */
public final class Main {

    static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        if (args.length == 0) {
            Frame.open(null);
            return;
        }
        if (args[0].equals("--gui")) {
            Frame.open(args.length > 1 ? Paths.get(args[1]) : null);
            return;
        }
        if (args[0].toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            Frame.open(Paths.get(args[0]));
            return;
        }

        try {
            if (args[0].equals("--list")) {
                list(args.length > 1 && args[1].equals("--all"));
                return;
            }
            if (args[0].equals("--inject")) {
                inject(args);
                return;
            }
            if (args[0].equals("--eject")) {
                eject(args);
                return;
            }
            if (args[0].equals("--version") || args[0].equals("-V")) {
                System.out.println("hollow " + VERSION);
                return;
            }
            usage();
            System.exit(2);
        } catch (Exception failure) {
            System.out.println("ERR " + rootMessage(failure));
            System.exit(1);
        }
    }

    private static void list(boolean all) {
        List<GameJvm> jvms = GameJvm.discover(all);
        if (jvms.isEmpty()) {
            System.out.println("no matching JVMs");
            return;
        }
        for (GameJvm jvm : jvms) {
            System.out.printf("%7d  %s%n", jvm.pid(), jvm.label());
        }
    }

    private static void inject(String[] args) throws Exception {
        long pid = Long.parseLong(required(args, "--pid"));
        Path jar = Paths.get(required(args, "--jar")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("no such jar: " + jar);

        JarScan scan = JarScan.scan(jar);
        String entry = optional(args, "--class", scan.entryClass);
        String agentArgs = optional(args, "--args", null);

        System.out.println("jar    " + jar.getFileName() + "  " + scan.summary());

        try (Session session = Session.ensure(pid, coreJar())) {
            String id = session.inject(jar, entry, agentArgs);
            System.out.println("loaded " + id + " into pid " + pid);

            LogScrub.Result scrub = LogScrub.scrub(gameDir(session.remote().userDir()));
            System.out.println("log    " + scrub.describe());
        }
    }

    private static void eject(String[] args) throws Exception {
        long pid = Long.parseLong(required(args, "--pid"));
        try (Session session = Session.ensure(pid, coreJar())) {
            int count = session.ejectAll();
            System.out.println("ejected " + count + " payload" + (count == 1 ? "" : "s") + " from pid " + pid);
        }
    }

    static Path coreJar() {
        String override = System.getProperty("hollow.core");
        if (override != null) return Paths.get(override);
        try {
            Path self = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path sibling = self.resolveSibling("hollow-core.jar");
            if (Files.isRegularFile(sibling)) return sibling;
            return sibling;
        } catch (Exception broken) {
            throw new IllegalStateException("cannot locate hollow-core.jar; pass -Dhollow.core=<path>");
        }
    }

    static Path gameDir(String userDir) {
        if (userDir == null) return null;
        Path candidate = Paths.get(userDir);
        return Files.isDirectory(candidate) ? candidate : null;
    }

    private static String required(String[] args, String name) {
        String value = optional(args, name, null);
        if (value == null) throw new IllegalArgumentException("missing " + name);
        return value;
    }

    private static String optional(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return fallback;
    }

    static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    private static void usage() {
        System.out.println("""
                hollow %s

                  hollow                          open the window
                  hollow --list [--all]           list JVMs (--all skips the game filter)
                  hollow --inject --pid N --jar X [--class C] [--args A]
                  hollow --eject  --pid N
                  hollow --version
                """.formatted(VERSION));
    }

    private Main() {
    }
}
