package dev.lectern.sync;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * A simple Swing dialog that shows sync progress to the player.
 * Displays status messages, progress info, and any errors before launching.
 *
 * If the system is headless (no display), all methods are no-ops.
 */
public class SyncDialog {

    private JDialog dialog;
    private JLabel statusLabel;
    private JLabel detailLabel;
    private JTextArea logArea;
    private JButton launchButton;
    private JPanel errorPanel;
    private JLabel errorLabel;
    private boolean hasErrors = false;
    private boolean headless = false;
    private volatile boolean closed = false;

    public SyncDialog(String serverName) {
        if (GraphicsEnvironment.isHeadless()) {
            headless = true;
            return;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                buildUI(serverName);
            }
        });

        // Wait briefly for the UI to initialize
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {
        }
    }

    private void buildUI(String serverName) {
        dialog = new JDialog();
        dialog.setTitle("Lectern Sync");
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setResizable(false);
        dialog.setAlwaysOnTop(true);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        root.setBackground(new Color(30, 30, 30));

        // Title
        JLabel titleLabel = new JLabel("Lectern Sync");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        titleLabel.setForeground(new Color(76, 175, 80));
        titleLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        root.add(titleLabel);

        // Server name
        JLabel serverLabel = new JLabel(serverName);
        serverLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        serverLabel.setForeground(new Color(180, 180, 180));
        serverLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        root.add(serverLabel);

        root.add(Box.createVerticalStrut(12));

        // Status
        statusLabel = new JLabel("Connecting to server...");
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        root.add(statusLabel);

        root.add(Box.createVerticalStrut(4));

        // Detail line
        detailLabel = new JLabel(" ");
        detailLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        detailLabel.setForeground(new Color(160, 160, 160));
        detailLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        root.add(detailLabel);

        root.add(Box.createVerticalStrut(10));

        // Log area (scrollable)
        logArea = new JTextArea(6, 40);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBackground(new Color(20, 20, 20));
        logArea.setForeground(new Color(200, 200, 200));
        logArea.setCaretColor(new Color(200, 200, 200));
        logArea.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setAlignmentX(JScrollPane.LEFT_ALIGNMENT);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60)));
        scrollPane.setPreferredSize(new Dimension(380, 120));
        scrollPane.setMaximumSize(new Dimension(380, 120));
        root.add(scrollPane);

        root.add(Box.createVerticalStrut(10));

        // Error panel (hidden initially)
        errorPanel = new JPanel(new BorderLayout());
        errorPanel.setBackground(new Color(60, 20, 20));
        errorPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(180, 60, 60)),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        errorPanel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        errorPanel.setMaximumSize(new Dimension(380, 60));

        errorLabel = new JLabel(" ");
        errorLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        errorLabel.setForeground(new Color(255, 150, 150));
        errorPanel.add(errorLabel, BorderLayout.CENTER);
        errorPanel.setVisible(false);
        root.add(errorPanel);

        root.add(Box.createVerticalStrut(10));

        // Launch button (hidden until sync complete or error)
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttonPanel.setBackground(new Color(30, 30, 30));
        buttonPanel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        buttonPanel.setMaximumSize(new Dimension(380, 36));

        launchButton = new JButton("Launch Minecraft");
        launchButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        launchButton.setForeground(Color.WHITE);
        launchButton.setBackground(new Color(76, 175, 80));
        launchButton.setFocusPainted(false);
        launchButton.setBorderPainted(false);
        launchButton.setOpaque(true);
        launchButton.setVisible(false);
        launchButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                closed = true;
                dialog.dispose();
            }
        });
        buttonPanel.add(launchButton);
        root.add(buttonPanel);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    /** Update the main status line (bold white text). */
    public void setStatus(final String text) {
        if (headless) {
            System.out.println("[Lectern] " + text);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (statusLabel != null) statusLabel.setText(text);
            }
        });
    }

    /** Update the detail/subtitle line (grey text). */
    public void setDetail(final String text) {
        if (headless) return;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (detailLabel != null) detailLabel.setText(text);
            }
        });
    }

    /** Append a line to the log area. */
    public void log(final String text) {
        if (headless) {
            System.out.println("[Lectern] " + text);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (logArea != null) {
                    logArea.append(text + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            }
        });
    }

    /** Show an error in the error panel and enable the launch button. */
    public void showError(final String message) {
        hasErrors = true;
        if (headless) {
            System.err.println("[Lectern] ERROR: " + message);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (errorPanel != null) {
                    errorLabel.setText("<html>" + message.replace("\n", "<br>") + "</html>");
                    errorPanel.setVisible(true);
                    dialog.pack();
                }
            }
        });
    }

    /** Show the launch button and wait for the user to click it (or auto-close). */
    public void showLaunchButton() {
        if (headless) return;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (launchButton != null) {
                    launchButton.setVisible(true);
                    if (hasErrors) {
                        launchButton.setText("Launch Anyway");
                        launchButton.setBackground(new Color(200, 120, 50));
                    }
                    dialog.pack();
                    // Allow window close now
                    dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                    dialog.addWindowListener(new WindowAdapter() {
                        public void windowClosing(WindowEvent e) {
                            closed = true;
                        }
                    });
                }
            }
        });
    }

    /** Wait for the user to dismiss the dialog. Returns immediately if headless. */
    public void waitForDismiss() {
        if (headless) return;
        while (!closed) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    /** Close the dialog without user interaction (used for clean auto-close). */
    public void close() {
        if (headless) return;
        closed = true;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (dialog != null) dialog.dispose();
            }
        });
    }

    /** Auto-close after a brief delay (no errors case). */
    public void autoCloseAfter(final int millis) {
        if (headless) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException ignored) {
                }
                if (!hasErrors) {
                    close();
                }
            }
        }).start();
    }

    public boolean hasErrors() {
        return hasErrors;
    }
}
