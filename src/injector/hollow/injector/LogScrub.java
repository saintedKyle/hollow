package hollow.injector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes the JVM's dynamic-agent warning from the game's log.
 *
 * JDK 21 (JEP 451) prints four lines to the process's stderr the first time an
 * agent is loaded dynamically. They are not log4j events, so they usually land
 * in the launcher console rather than latest.log, but some launchers mirror
 * stderr into the log file. This strips them where they are found and reports
 * honestly when the file is held open by the running game.
 *
 * To stop the warning at the source, launch the game with
 * -XX:+EnableDynamicAgentLoading.
 */
final class LogScrub {

    private static final String[] WARNING_FRAGMENTS = {
            "A Java agent has been loaded dynamically",
            "If a serviceability tool is in use",
            "If a serviceability tool is not in use",
            "Dynamic loading of agents will be disallowed",
            "WARNING: A Java agent has been loaded dynamically",
    };

    record Result(Path file, int removed, String note) {
        String describe() {
            if (file == null) return "no log file found";
            if (removed == 0) return "log clean";
            return "stripped " + removed + " warning line" + (removed == 1 ? "" : "s")
                    + " from " + file.getFileName();
        }
    }

    static Result scrub(Path gameDir) {
        if (gameDir == null) return new Result(null, 0, "unknown game directory");
        Path log = gameDir.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log)) return new Result(null, 0, "no latest.log");

        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            List<String> kept = new ArrayList<>(lines.size());
            int removed = 0;
            for (String line : lines) {
                if (isWarning(line)) {
                    removed++;
                    continue;
                }
                kept.add(line);
            }
            if (removed == 0) return new Result(log, 0, "clean");

            Files.write(log, kept, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return new Result(log, removed, "ok");
        } catch (IOException locked) {
            return new Result(log, 0, "log is held open, skipped");
        }
    }

    private static boolean isWarning(String line) {
        for (String fragment : WARNING_FRAGMENTS) {
            if (line.contains(fragment)) return true;
        }
        return false;
    }

    private LogScrub() {
    }
}
