package hollow.example;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Font;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Proof-of-life payload.
 *
 * Drops a small always-on-top badge in the corner when it is loaded, and takes
 * it away when hollow ejects it. No Minecraft classes are touched, so it works
 * on every version and every loader.
 *
 * This is also the reference shape for a real payload:
 *
 *   public static void agentmain(String args, Instrumentation inst)
 *   public static void unload()
 *
 * Both are found reflectively. The agentmain signature can drop the
 * Instrumentation parameter if the payload does not need it.
 */
public final class DemoAgent {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static JFrame frame;

    public static void agentmain(String args, Instrumentation instrumentation) {
        SwingUtilities.invokeLater(() -> show(args));
    }

    public static void unload() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        });
    }

    private static void show(String args) {
        if (frame != null) return;

        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        String pid = runtime.contains("@") ? runtime.substring(0, runtime.indexOf('@')) : runtime;

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(new Color(0x0B0D10));
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xB1162A), 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        JLabel title = new JLabel("HOLLOW  ACTIVE");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        title.setForeground(new Color(0xE6E9EF));

        JLabel detail = new JLabel("pid " + pid + "  ·  " + LocalTime.now().format(CLOCK)
                + (args == null || args.isBlank() ? "" : "  ·  " + args));
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        detail.setForeground(new Color(0x8B949E));

        body.add(title);
        body.add(Box.createVerticalStrut(3));
        body.add(detail);

        frame = new JFrame("hollow");
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setType(java.awt.Window.Type.UTILITY);
        frame.setContentPane(body);
        frame.pack();
        frame.setLocation(16, 16);
        frame.setVisible(true);
    }

    private DemoAgent() {
    }
}
