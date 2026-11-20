package hollow.injector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Works out how a jar can enter a running JVM.
 *
 * Order of preference:
 *   1. Manifest Agent-Class    - the jar declares itself a java agent.
 *   2. Manifest Premain-Class  - premain works too; hollow calls it reflectively.
 *   3. A scanned class with a public static agentmain/premain.
 *   4. Nothing               - the jar is appended to the game's system classpath.
 */
final class JarScan {

    enum Kind {
        AGENT("agent", "agentmain via manifest"),
        PREMAIN("premain", "premain via manifest"),
        SCANNED("agent", "agentmain found by scan"),
        CLASSPATH("classpath", "no entry point, classpath only");

        final String shortName;
        final String description;

        Kind(String shortName, String description) {
            this.shortName = shortName;
            this.description = description;
        }
    }

    final Path jar;
    final Kind kind;
    final String entryClass;
    final String note;
    final int classCount;
    final long sizeBytes;

    private JarScan(Path jar, Kind kind, String entryClass, String note, int classCount, long sizeBytes) {
        this.jar = jar;
        this.kind = kind;
        this.entryClass = entryClass;
        this.note = note;
        this.classCount = classCount;
        this.sizeBytes = sizeBytes;
    }

    boolean hasEntryPoint() {
        return kind != Kind.CLASSPATH;
    }

    String summary() {
        if (entryClass == null) {
            return "classpath only, " + classCount + " classes";
        }
        return entryClass + "  (" + kind.description + ")";
    }

    static JarScan scan(Path jar) throws IOException {
        long size = java.nio.file.Files.size(jar);
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            Attributes attrs = manifest == null ? new Attributes() : manifest.getMainAttributes();

            String agent = trim(attrs.getValue("Agent-Class"));
            String premain = trim(attrs.getValue("Premain-Class"));

            int classes = countClasses(jf);

            if (agent != null && findClass(jf, agent)) {
                return new JarScan(jar, Kind.AGENT, agent, "manifest Agent-Class", classes, size);
            }
            if (premain != null && findClass(jf, premain)) {
                return new JarScan(jar, Kind.PREMAIN, premain, "manifest Premain-Class", classes, size);
            }

            List<String> hits = new ArrayList<>();
            for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                String name = entry.getName();
                if (!isPlainClass(name)) continue;
                try (InputStream in = jf.getInputStream(entry)) {
                    ClassFile cf = ClassFile.parse(in);
                    if (cf.hasAgentMain() || cf.hasPremain()) hits.add(cf.binaryName());
                } catch (IOException malformed) {
                    // a single unreadable class does not disqualify the jar
                }
            }

            if (hits.size() == 1) {
                return new JarScan(jar, Kind.SCANNED, hits.get(0), "scanned", classes, size);
            }
            if (hits.size() > 1) {
                Collections.sort(hits);
                return new JarScan(jar, Kind.SCANNED, hits.get(0),
                        "scanned, " + hits.size() + " candidates: " + String.join(", ", hits), classes, size);
            }
            return new JarScan(jar, Kind.CLASSPATH, null, "no agent entry point", classes, size);
        }
    }

    private static boolean isPlainClass(String name) {
        return name.endsWith(".class")
                && !name.startsWith("META-INF/")
                && !name.equals("module-info.class");
    }

    private static int countClasses(JarFile jf) {
        int n = 0;
        for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
            if (isPlainClass(e.nextElement().getName())) n++;
        }
        return n;
    }

    private static boolean findClass(JarFile jf, String binaryName) {
        return jf.getJarEntry(binaryName.replace('.', '/') + ".class") != null;
    }

    private static String trim(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
