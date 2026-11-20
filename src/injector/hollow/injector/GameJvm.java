package hollow.injector;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the JVMs worth pointing at.
 *
 * ProcessHandle.commandLine() is unavailable on some Windows configurations
 * even for same-user processes, so the primary source is the attach API's own
 * JVM list — the same one jps reads. The main class or jar name comes back with
 * it, which is enough to tell Minecraft from a build server.
 *
 * ProcessHandle is still consulted, best effort, because a real command line
 * carries the game version and profile folder.
 */
record GameJvm(long pid, String label, String commandLine) {

    private static final String[] GAME_MARKERS = {
            "minecraft",
            "fabric",
            "forge",
            "neoforge",
            "quilt",
            "knotclient",
            "bootstraplauncher",
            "modlauncher",
    };

    /** Class names that carry no information once the loader is named. */
    private static final java.util.Set<String> GENERIC = java.util.Set.of(
            "main", "client", "server", "knotclient", "bootstraplauncher",
            "launch", "launcher", "knot", "start");

    static List<GameJvm> discover(boolean includeEverything) {
        Map<Long, String> commandLines = commandLines();
        List<GameJvm> found = new ArrayList<>();

        try {
            for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
                long pid = parsePid(descriptor.id());
                if (pid <= 0 || pid == ProcessHandle.current().pid()) continue;

                String display = descriptor.displayName() == null ? "" : descriptor.displayName();
                String commandLine = commandLines.getOrDefault(pid, "");

                if (!includeEverything && !looksLikeGame(display + " " + commandLine)) continue;
                found.add(new GameJvm(pid, describe(display, commandLine), commandLine));
            }
        } catch (Throwable attachUnavailable) {
            return byProcessHandle(includeEverything, commandLines);
        }

        if (found.isEmpty() && includeEverything) {
            return byProcessHandle(true, commandLines);
        }

        found.sort(Comparator.comparingLong(GameJvm::pid));
        return found;
    }

    private static List<GameJvm> byProcessHandle(boolean includeEverything, Map<Long, String> commandLines) {
        List<GameJvm> found = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(handle -> {
            ProcessHandle.Info info = handle.info();
            String executable = info.command().orElse("").toLowerCase(Locale.ROOT);
            if (!executable.endsWith("java.exe") && !executable.endsWith("javaw.exe")) return;

            String commandLine = info.commandLine().orElse(commandLines.getOrDefault(handle.pid(), ""));
            if (!includeEverything && !looksLikeGame(commandLine)) return;
            found.add(new GameJvm(handle.pid(), describe("", commandLine), commandLine));
        });
        found.sort(Comparator.comparingLong(GameJvm::pid));
        return found;
    }

    private static Map<Long, String> commandLines() {
        Map<Long, String> lines = new HashMap<>();
        ProcessHandle.allProcesses().forEach(handle -> {
            String line = handle.info().commandLine().orElse("");
            if (!line.isEmpty()) lines.put(handle.pid(), line);
        });
        return lines;
    }

    private static boolean looksLikeGame(String haystack) {
        String lower = haystack.toLowerCase(Locale.ROOT);
        for (String marker : GAME_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static String describe(String display, String commandLine) {
        String version = argument(commandLine, "--version");
        String gameDir = argument(commandLine, "--gameDir");

        String loader = loaderName(display, commandLine);
        String subject = version != null ? version : informativeName(display, commandLine);

        StringBuilder sb = new StringBuilder();
        if (loader != null) sb.append(loader);
        if (!subject.isEmpty()) sb.append(sb.length() == 0 ? "" : "  ").append(subject);
        if (gameDir != null) {
            String folder = lastSegment(gameDir);
            if (!folder.isEmpty()) sb.append(sb.length() == 0 ? "" : "  ").append(folder);
        }
        return sb.length() == 0 ? "java" : sb.toString();
    }

    private static String informativeName(String display, String commandLine) {
        String candidate = display.isEmpty() ? mainClass(commandLine) : display;
        if (candidate.isEmpty()) return "";
        String shortName = shortName(candidate);
        if (GENERIC.contains(shortName.toLowerCase(Locale.ROOT))) return "";
        return shortName;
    }

    private static String loaderName(String display, String commandLine) {
        String lower = (display + " " + commandLine).toLowerCase(Locale.ROOT);
        if (lower.contains("knotclient") || lower.contains("net.fabricmc")) return "fabric";
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("bootstraplauncher") || lower.contains("cpw.mods")) return "forge";
        if (lower.contains("quilt")) return "quilt";
        if (lower.contains("minecraft")) return "vanilla";
        return null;
    }

    private static String shortName(String display) {
        String cleaned = display.trim();
        if (cleaned.endsWith(".jar")) return lastSegment(cleaned);
        int cut = cleaned.lastIndexOf('.');
        String tail = cut < 0 ? cleaned : cleaned.substring(cut + 1);
        return tail.isEmpty() ? cleaned : tail;
    }

    private static String mainClass(String commandLine) {
        String[] parts = commandLine.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals("-cp") || part.equals("-classpath") || part.equals("--class-path")) {
                i++;
                continue;
            }
            if (part.startsWith("-")) continue;
            if (part.endsWith(".jar") || part.endsWith(".exe")) continue;
            if (part.contains(".") && !part.contains("\\") && !part.contains("/")) return part;
        }
        return "";
    }

    private static String argument(String commandLine, String flag) {
        int at = commandLine.indexOf(flag);
        while (at >= 0) {
            boolean leftBoundary = at == 0 || Character.isWhitespace(commandLine.charAt(at - 1));
            int after = at + flag.length();
            boolean rightBoundary = after >= commandLine.length()
                    || commandLine.charAt(after) == ' '
                    || commandLine.charAt(after) == '=';
            if (leftBoundary && rightBoundary) {
                int start = after;
                while (start < commandLine.length()
                        && (commandLine.charAt(start) == '=' || commandLine.charAt(start) == ' ')) {
                    start++;
                }
                if (start >= commandLine.length()) return null;
                if (commandLine.charAt(start) == '"') {
                    int end = commandLine.indexOf('"', start + 1);
                    return end < 0 ? null : commandLine.substring(start + 1, end);
                }
                int end = start;
                while (end < commandLine.length() && !Character.isWhitespace(commandLine.charAt(end))) end++;
                return commandLine.substring(start, end);
            }
            at = commandLine.indexOf(flag, at + 1);
        }
        return null;
    }

    private static String lastSegment(String path) {
        String cleaned = path.replace('\\', '/');
        int cut = cleaned.lastIndexOf('/');
        String tail = cut < 0 ? cleaned : cleaned.substring(cut + 1);
        return tail.endsWith(":") ? "" : tail;
    }

    private static long parsePid(String id) {
        try {
            return Long.parseLong(id.trim());
        } catch (RuntimeException notANumber) {
            return -1;
        }
    }
}
