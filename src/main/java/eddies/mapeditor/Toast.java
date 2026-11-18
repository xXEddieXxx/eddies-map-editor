package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * News that needs no answer, eg "Saved", at the bottom of the window above everything for a few
 * seconds; one at a time, a new one replaces the old. It never takes the focus. An offer that
 * wants an answer, eg a new version to install, stays until its action or its × is clicked.
 */
final class Toast extends JPanel {
  private static final int SHORT_MILLIS = 4000;
  private static final int LONG_MILLIS = 8000;
  // no time: it stays until it is closed
  private static final int STAYS = 0;
  private static final int MAX_WIDTH = 480;
  // on the layered pane: the toast it shows
  private static final String SHOWN = "Toast.shown";

  private final transient Timer timer;

  private Toast(final String text, final List<JButton> actions, final int millis) {
    super(new FlowLayout(FlowLayout.LEADING, Theme.GAP_S, 0));
    putClientProperty(FlatClientProperties.STYLE, "arc: 8");
    setBackground(Theme.getToastBackground());
    setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, Theme.GAP_L, Theme.GAP_M, Theme.GAP_S));
    setFocusable(false);
    getAccessibleContext().setAccessibleDescription(text);
    final JLabel label = new JLabel(text);
    label.setForeground(Theme.getToastTextColor());
    add(label);
    actions.forEach(this::add);
    final JButton close = newButton("×", this::close);
    close.setToolTipText("Close");
    close.getAccessibleContext().setAccessibleName("Close");
    add(close);
    // a long text is cut in its middle, so the buttons stay in sight beside it
    final int room = MAX_WIDTH - (getPreferredSize().width - label.getPreferredSize().width);
    final String shown = SearchList.cutMiddle(text, label.getFontMetrics(label.getFont()), room);
    label.setText(shown);
    if (!shown.equals(text)) {
      label.setToolTipText(text);
    }
    timer = new Timer(millis, e -> close());
    timer.setRepeats(false);
    // the mouse on it pauses the time, so a slow reader keeps it
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(final MouseEvent e) {
            timer.stop();
          }

          @Override
          public void mouseExited(final MouseEvent e) {
            // entering one of its buttons leaves the panel too
            if (!contains(e.getPoint()) && millis != STAYS) {
              timer.restart();
            }
          }
        });
  }

  static void show(final Component anyInWindow, final String text) {
    show(anyInWindow, text, List.of(), SHORT_MILLIS);
  }

  static void show(
      final Component anyInWindow,
      final String text,
      final String action,
      final Runnable onAction) {
    final JButton button = newButton(action, onAction);
    button.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
    show(anyInWindow, text, List.of(button), LONG_MILLIS);
  }

  /** As show with an action, but it stays until the action or × is clicked. */
  static void showUntilClosed(
      final Component anyInWindow,
      final String text,
      final String action,
      final Runnable onAction) {
    final JButton button = newButton(action, onAction);
    button.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
    show(anyInWindow, text, List.of(button), STAYS);
  }

  /** Whether it closes after its time; an offer shown until closed does not. */
  boolean closesByItself() {
    return timer.isRunning();
  }

  private static void show(
      final Component anyInWindow,
      final String text,
      final List<JButton> actions,
      final int millis) {
    // a component not (yet) in a window has nowhere to show it
    final Optional<JRootPane> root = Optional.ofNullable(SwingUtilities.getRootPane(anyInWindow));
    root.ifPresent(
        rootPane -> {
          final JLayeredPane layers = rootPane.getLayeredPane();
          shown(layers).ifPresent(Toast::close);
          final Toast toast = new Toast(text, actions, millis);
          // an action closes the toast before it runs
          actions.forEach(button -> button.addActionListener(e -> toast.close()));
          final Dimension size = toast.getPreferredSize();
          final int width = Math.min(size.width, MAX_WIDTH);
          toast.setBounds(
              (layers.getWidth() - width) / 2,
              layers.getHeight() - size.height - Theme.GAP_L,
              width,
              size.height);
          layers.add(toast, JLayeredPane.POPUP_LAYER);
          layers.putClientProperty(SHOWN, toast);
          // screen readers read the news without the focus moving
          rootPane.getAccessibleContext().setAccessibleDescription(text);
          layers.repaint(toast.getBounds());
          if (millis != STAYS) {
            toast.timer.start();
          }
        });
  }

  /** The toast the layered pane shows, if any. */
  static Optional<Toast> shown(final JLayeredPane layers) {
    return Optional.ofNullable(layers.getClientProperty(SHOWN))
        .map(Toast.class::cast)
        .filter(toast -> toast.getParent() == layers);
  }

  private void close() {
    timer.stop();
    Optional.ofNullable(getParent())
        .ifPresent(
            layers -> {
              layers.remove(this);
              layers.repaint(getX(), getY(), getWidth(), getHeight());
            });
  }

  private static JButton newButton(final String text, final Runnable action) {
    final JButton button = new JButton(text);
    Theme.setBorderlessButtonStyle(button);
    button.setFocusable(false);
    button.setForeground(Theme.getToastTextColor());
    button.addActionListener(e -> action.run());
    return button;
  }
}
