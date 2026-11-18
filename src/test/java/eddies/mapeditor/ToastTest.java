package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Component;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ToastTest {

  @DisplayName("A toast shows at the bottom centre above everything and never takes the focus")
  @Test
  void showsAboveEverything() {
    final JRootPane window = givenWindow();

    Dialogs.toast(window.getContentPane(), "✓ Saved");

    final JLayeredPane layers = window.getLayeredPane();
    final Toast toast = Toast.shown(layers).orElseThrow();
    assertThat(layers.getLayer(toast)).as("above the tools").isEqualTo(JLayeredPane.POPUP_LAYER);
    assertThat(toast.getAccessibleContext().getAccessibleDescription())
        .as("the words, also for screen readers")
        .isEqualTo("✓ Saved");
    assertThat(toast.isFocusable()).as("typing goes on where it was").isFalse();
    assertThat(List.of(toast.getComponents()))
        .as("its buttons do not take the focus either")
        .filteredOn(JButton.class::isInstance)
        .noneMatch(Component::isFocusable);
    assertThat(toast.getX() + toast.getWidth() / 2)
        .as("centred")
        .isCloseTo(layers.getWidth() / 2, within(1));
    assertThat(toast.getY() + toast.getHeight())
        .as("16 px above the bottom")
        .isEqualTo(layers.getHeight() - 16);
  }

  @DisplayName("A new toast replaces the one showing")
  @Test
  void oneAtATime() {
    final JRootPane window = givenWindow();

    Dialogs.toast(window, "✓ Saved");
    Dialogs.toast(window, "✓ Copied 12 files into sounds/");

    assertThat(List.of(window.getLayeredPane().getComponents()))
        .as("only the newest shows")
        .filteredOn(Toast.class::isInstance)
        .singleElement()
        .extracting(toast -> toast.getAccessibleContext().getAccessibleDescription())
        .isEqualTo("✓ Copied 12 files into sounds/");
  }

  @DisplayName("A toast's action runs once and closes the toast")
  @Test
  void actionClosesToast() {
    final JRootPane window = givenWindow();
    final AtomicInteger shown = new AtomicInteger();
    Dialogs.toast(
        window, "Saved. The old file stays as minimap.xml.bak", "Show", shown::incrementAndGet);
    final Toast toast = Toast.shown(window.getLayeredPane()).orElseThrow();

    List.of(toast.getComponents()).stream()
        .filter(c -> c instanceof JButton button && button.getText().equals("Show"))
        .map(JButton.class::cast)
        .findFirst()
        .orElseThrow()
        .doClick();

    assertThat(shown.get()).as("the action ran").isEqualTo(1);
    assertThat(Toast.shown(window.getLayeredPane())).as("the toast is gone").isEmpty();
  }

  @DisplayName("A long text is cut in its middle, so its action and × stay beside it")
  @Test
  void longTextKeepsButtonsInSight() {
    final JRootPane window = givenWindow();
    final String text =
        "✓ Saved. The old file stays as " + "very_long_game_name_".repeat(10) + ".bak";

    Dialogs.toast(window, text, "Show", () -> {});

    final Toast toast = Toast.shown(window.getLayeredPane()).orElseThrow();
    toast.doLayout();
    final Rectangle inside = new Rectangle(toast.getSize());
    assertThat(List.of(toast.getComponents()))
        .as("every part on the one row, inside the toast")
        .allMatch(part -> inside.contains(part.getBounds()));
    assertThat(List.of(toast.getComponents()))
        .as("the text cut in its middle, the whole of it in the tool tip")
        .filteredOn(JLabel.class::isInstance)
        .map(JLabel.class::cast)
        .singleElement()
        .satisfies(label -> assertThat(label.getText()).contains("…").endsWith(".bak"))
        .satisfies(label -> assertThat(label.getToolTipText()).isEqualTo(text));
  }

  @DisplayName("A component outside any window shows no toast and does not fail")
  @Test
  void nothingWithoutWindow() {
    final JPanel alone = new JPanel();

    Dialogs.toast(alone, "✓ Saved");

    assertThat(alone.getComponentCount()).as("nothing added anywhere").isZero();
  }

  private static JRootPane givenWindow() {
    final JRootPane window = new JRootPane();
    window.setSize(1100, 700);
    window.doLayout();
    return window;
  }
}
