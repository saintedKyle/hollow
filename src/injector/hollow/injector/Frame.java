package hollow.injector;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** The window. One jar, one process, two buttons. */
final class Frame extends JFrame {

    private static final Color BG = new Color(0x0B0D10);
    private static final Color PANEL = new Color(0x121519);
    private static final Color LINE = new Color(0x23282F);
    private static final Color TEXT = new Color(0xE6E9EF);
    private static final Color MUTED = new Color(0x8B949E);
    private static final Color ACCENT = new Color(0xB1162A);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DefaultListModel<GameJvm> model = new DefaultListModel<>();
    private final JList<GameJvm> jvmList = new JList<>(model);
    private final JCheckBox showAll = new JCheckBox("show every JVM");
    private final JTextArea log = new JTextArea(8, 40);
    private final JTextField argsField = new JTextField();
    private final JLabel jarName = new JLabel("drop a .jar");
    private final JLabel jarDetail = new JLabel("nothing loaded");
    private final JButton injectButton = new JButton("INJECT");
    private final JButton ejectButton = new JButton("EJECT");
    private final JPanel dropZone = new JPanel(new BorderLayout());
    private JScrollPane jvmScroll;

    private Path jar;
    private JarScan scan;

    private Frame(Path preloaded) {
        super("hollow " + Main.VERSION);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(780, 660));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 16, 18));
        root.add(header(), BorderLayout.NORTH);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);

        wireActions();
        refresh();
        setVisible(true);
        if (preloaded != null) accept(preloaded);
    }

    static void open(Path preloaded) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            UIManager.put("Panel.background", BG);
            UIManager.put("Button.focus", new Color(0, 0, 0, 0));
            UIManager.put("Button.select", PANEL);
            UIManager.put("CheckBox.background", PANEL);
            UIManager.put("CheckBox.foreground", MUTED);
            UIManager.put("List.background", PANEL);
            UIManager.put("List.foreground", TEXT);
            UIManager.put("List.selectionBackground", ACCENT);
            UIManager.put("List.selectionForeground", Color.WHITE);
            UIManager.put("ScrollPane.background", PANEL);
            UIManager.put("Viewport.background", PANEL);
            UIManager.put("ScrollBar.thumb", new Color(0x2C3239));
            UIManager.put("ScrollBar.track", PANEL);
            UIManager.put("TextField.background", PANEL);
            UIManager.put("TextField.foreground", TEXT);
            UIManager.put("TextField.caretForeground", TEXT);
            UIManager.put("TextArea.background", new Color(0x080A0C));
            UIManager.put("TextArea.foreground", MUTED);
            UIManager.put("TextArea.caretForeground", MUTED);
            UIManager.put("Label.foreground", TEXT);
        } catch (Exception ignored) {
            // the hand-painted colours carry the window either way
        }
        SwingUtilities.invokeLater(() -> new Frame(preloaded));
    }

    private JPanel header() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));

        JLabel title = new JLabel("HOLLOW");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        title.setForeground(TEXT);

        JLabel subtitle = new JLabel("load a jar into a running JVM  ·  attach, don't relaunch");
        subtitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        subtitle.setForeground(MUTED);

        header.add(title);
        header.add(Box.createVerticalStrut(3));
        header.add(subtitle);
        return header;
    }

    private JPanel body() {
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 12, 0);
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 0.42;
        body.add(processPanel(), c);

        c.gridy = 1;
        c.weighty = 0.58;
        c.insets = new Insets(0, 0, 0, 0);
        body.add(payloadPanel(), c);
        return body;
    }

    private JPanel processPanel() {
        JPanel panel = card();
        panel.setLayout(new BorderLayout(0, 8));

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        JLabel label = caption("TARGET");
        bar.add(label, BorderLayout.WEST);

        JPanel tools = new JPanel();
        tools.setOpaque(false);
        showAll.setForeground(MUTED);
        showAll.setOpaque(false);
        showAll.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        JButton refresh = ghost("refresh");
        tools.add(showAll);
        tools.add(Box.createHorizontalStrut(6));
        tools.add(refresh);
        bar.add(tools, BorderLayout.EAST);
        panel.add(bar, BorderLayout.NORTH);

        jvmList.setBackground(PANEL);
        jvmList.setForeground(TEXT);
        jvmList.setSelectionBackground(ACCENT);
        jvmList.setSelectionForeground(Color.WHITE);
        jvmList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jvmList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        jvmList.setCellRenderer((list, value, index, selected, focused) -> {
            JLabel row = new JLabel(value == null ? "" : String.format("%7d   %s", value.pid(), value.label()));
            row.setOpaque(true);
            row.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            row.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            row.setBackground(selected ? ACCENT : PANEL);
            row.setForeground(selected ? Color.WHITE : TEXT);
            return row;
        });

        JScrollPane scroll = new JScrollPane(jvmList);
        scroll.setBorder(BorderFactory.createLineBorder(LINE));
        scroll.getViewport().setBackground(PANEL);
        jvmScroll = scroll;
        panel.add(scroll, BorderLayout.CENTER);

        refresh.addActionListener(e -> refresh());
        showAll.addActionListener(e -> refresh());
        return panel;
    }

    private JPanel payloadPanel() {
        JPanel panel = card();
        panel.setLayout(new BorderLayout(0, 10));
        panel.add(caption("PAYLOAD"), BorderLayout.NORTH);

        dropZone.setBackground(PANEL);
        dropZone.setBorder(dashed());
        dropZone.setPreferredSize(new Dimension(0, 96));
        dropZone.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel centre = new JPanel();
        centre.setOpaque(false);
        centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
        jarName.setForeground(TEXT);
        jarName.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        jarName.setAlignmentX(Component.CENTER_ALIGNMENT);
        jarDetail.setForeground(MUTED);
        jarDetail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        jarDetail.setAlignmentX(Component.CENTER_ALIGNMENT);
        centre.add(Box.createVerticalGlue());
        centre.add(jarName);
        centre.add(Box.createVerticalStrut(4));
        centre.add(jarDetail);
        centre.add(Box.createVerticalGlue());
        dropZone.add(centre, BorderLayout.CENTER);
        panel.add(dropZone, BorderLayout.CENTER);

        JPanel lower = new JPanel(new BorderLayout(0, 8));
        lower.setOpaque(false);

        JPanel argsRow = new JPanel(new BorderLayout(8, 0));
        argsRow.setOpaque(false);
        JLabel argsLabel = new JLabel("agent args");
        argsLabel.setForeground(MUTED);
        argsLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        argsField.setBackground(PANEL);
        argsField.setForeground(TEXT);
        argsField.setCaretColor(TEXT);
        argsField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(5, 7, 5, 7)));
        argsRow.add(argsLabel, BorderLayout.WEST);
        argsRow.add(argsField, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new BorderLayout(8, 0));
        buttons.setOpaque(false);
        JButton browse = ghost("browse");
        buttons.add(browse, BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout(8, 0));
        right.setOpaque(false);
        right.add(ejectButton, BorderLayout.WEST);
        right.add(injectButton, BorderLayout.CENTER);
        buttons.add(right, BorderLayout.EAST);

        lower.add(argsRow, BorderLayout.NORTH);
        lower.add(buttons, BorderLayout.CENTER);
        panel.add(lower, BorderLayout.SOUTH);

        browse.addActionListener(e -> browse());
        dropZone.setTransferHandler(new Dropper());
        return panel;
    }

    private JScrollPane footer() {
        log.setEditable(false);
        log.setBackground(new Color(0x080A0C));
        log.setForeground(MUTED);
        log.setCaretColor(MUTED);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        log.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createLineBorder(LINE));
        scroll.setPreferredSize(new Dimension(0, 150));
        return scroll;
    }

    private void wireActions() {
        style(injectButton, ACCENT, Color.WHITE, 9, 22, Font.BOLD, false);
        style(ejectButton, PANEL, TEXT, 8, 16, Font.PLAIN, true);

        injectButton.addActionListener(e -> doInject());
        ejectButton.addActionListener(e -> doEject());
    }

    private static void style(JButton button, Color background, Color foreground,
                              int padY, int padX, int weight, boolean outlined) {
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setFont(new Font(Font.SANS_SERIF, weight, 12));
        if (outlined) {
            button.setBorderPainted(true);
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(LINE),
                    BorderFactory.createEmptyBorder(padY, padX, padY, padX)));
        } else {
            button.setBorderPainted(false);
            button.setBorder(BorderFactory.createEmptyBorder(padY, padX, padY, padX));
        }
    }

    private void refresh() {
        new SwingWorker<List<GameJvm>, Void>() {
            @Override
            protected List<GameJvm> doInBackground() {
                return GameJvm.discover(showAll.isSelected());
            }

            @Override
            protected void done() {
                try {
                    List<GameJvm> found = get();
                    model.clear();
                    for (GameJvm jvm : found) model.addElement(jvm);
                    if (found.isEmpty()) {
                        jvmScroll.setViewportView(hint("no game JVM found — start Minecraft, then refresh"));
                        say("no game JVM found — start Minecraft, then hit refresh");
                    } else {
                        jvmScroll.setViewportView(jvmList);
                        say(found.size() + " target" + (found.size() == 1 ? "" : "s") + " found");
                        if (jvmList.getSelectedIndex() < 0) jvmList.setSelectedIndex(0);
                    }
                } catch (Exception failure) {
                    say("scan failed: " + Main.rootMessage(failure));
                }
            }
        }.execute();
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Java archive (*.jar)", "jar"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            accept(chooser.getSelectedFile().toPath());
        }
    }

    private void accept(Path path) {
        jar = path.toAbsolutePath().normalize();
        jarName.setText(jar.getFileName().toString());
        jarDetail.setText("reading…");
        new SwingWorker<JarScan, Void>() {
            @Override
            protected JarScan doInBackground() throws Exception {
                return JarScan.scan(jar);
            }

            @Override
            protected void done() {
                try {
                    scan = get();
                    jarDetail.setText(scan.summary());
                    say("jar " + jar.getFileName() + " — " + scan.summary());
                } catch (Exception failure) {
                    scan = null;
                    jarDetail.setText("unreadable");
                    say("cannot read jar: " + Main.rootMessage(failure));
                }
            }
        }.execute();
    }

    private void doInject() {
        GameJvm target = jvmList.getSelectedValue();
        if (target == null) {
            say("pick a target first");
            return;
        }
        if (jar == null || scan == null) {
            say("drop a jar first");
            return;
        }

        injectButton.setEnabled(false);
        final Path payload = jar;
        final JarScan info = scan;
        final String agentArgs = argsField.getText().trim();

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                try (Session session = Session.ensure(target.pid(), Main.coreJar())) {
                    String id = session.inject(payload, info.entryClass, agentArgs);
                    LogScrub.Result scrub = LogScrub.scrub(Main.gameDir(session.remote().userDir()));
                    return id + "\u0000" + scrub.describe();
                }
            }

            @Override
            protected void done() {
                injectButton.setEnabled(true);
                try {
                    String[] parts = get().split("\u0000", 2);
                    say("injected " + parts[0] + " into pid " + target.pid());
                    say(parts[1]);
                } catch (Exception failure) {
                    say("inject failed: " + Main.rootMessage(failure));
                }
            }
        }.execute();
    }

    private void doEject() {
        GameJvm target = jvmList.getSelectedValue();
        if (target == null) {
            say("pick a target first");
            return;
        }

        ejectButton.setEnabled(false);
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                Session session = Session.existing(target.pid());
                if (session == null) return -1;
                try (session) {
                    return session.ejectAll();
                }
            }

            @Override
            protected void done() {
                ejectButton.setEnabled(true);
                try {
                    int count = get();
                    if (count < 0) say("nothing attached to pid " + target.pid());
                    else say("ejected " + count + " payload" + (count == 1 ? "" : "s") + " from pid " + target.pid());
                } catch (Exception failure) {
                    say("eject failed: " + Main.rootMessage(failure));
                }
            }
        }.execute();
    }

    private void say(String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(LocalTime.now().format(CLOCK) + "  " + line + System.lineSeparator());
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    private static JPanel card() {
        JPanel panel = new JPanel();
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(12, 14, 14, 14)));
        return panel;
    }

    private static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        return label;
    }

    private static JLabel hint(String text) {
        JLabel label = new JLabel(text, javax.swing.SwingConstants.CENTER);
        label.setForeground(MUTED);
        label.setOpaque(true);
        label.setBackground(PANEL);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return label;
    }

    private static JButton ghost(String text) {
        JButton button = new JButton(text);
        style(button, PANEL, MUTED, 4, 10, Font.PLAIN, true);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return button;
    }

    private static Border dashed() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createDashedBorder(MUTED, 1f, 4f, 3f, false),
                BorderFactory.createEmptyBorder(10, 10, 10, 10));
    }

    private final class Dropper extends javax.swing.TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                Transferable data = support.getTransferable();
                List<File> files = (List<File>) data.getTransferData(DataFlavor.javaFileListFlavor);
                for (File file : files) {
                    if (file.getName().toLowerCase().endsWith(".jar")) {
                        accept(file.toPath());
                        return true;
                    }
                }
                say("that is not a .jar");
            } catch (Exception failure) {
                say("drop failed: " + Main.rootMessage(failure));
            }
            return false;
        }
    }

}
