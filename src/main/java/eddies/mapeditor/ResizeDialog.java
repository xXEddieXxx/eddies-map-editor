package eddies.mapeditor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Resize map: by how much the whole map gets bigger or smaller, with the size that gives, and why
 * not when {@link MapFiles#whyNotResized} refuses it.
 */
final class ResizeDialog {
  static final String TEXT =
      "Make the whole map bigger or smaller\n"
          + "The picture, the borders and every place are resized with it. Units keep their size, so a"
          + " bigger map gives them more room.";
  private static final int MIN_PERCENT = 25;
  private static final int MAX_PERCENT = 400;
  private static final List<Integer> USUAL = List.of(50, 75, 150, 200);

  private final MapFiles files;
  private final JPanel content = new JPanel();
  private final JSpinner percent =
      new JSpinner(new SpinnerNumberModel(150, MIN_PERCENT, MAX_PERCENT, 5));
  private final JLabel result = new JLabel(" ");
  private final JLabel note = Theme.newSecondaryLabel(" ");

  ResizeDialog(final MapFiles files) {
    this.files = files;
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    final Dimension size = files.size();
    final JPanel choice = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    choice.setOpaque(false);
    // no gap before the first, so it lines up with the lines around it
    choice.setBorder(BorderFactory.createEmptyBorder(0, -Theme.GAP_S, 0, 0));
    for (final int usual : USUAL) {
      final JButton button = new JButton(usual + " %");
      Theme.setSecondaryButtonStyle(button);
      button.addActionListener(e -> percent.setValue(usual));
      choice.add(button);
    }
    choice.add(Box.createHorizontalStrut(Theme.GAP_S));
    final JLabel own = new JLabel("or");
    own.setLabelFor(percent);
    choice.add(own);
    percent.setEditor(new JSpinner.NumberEditor(percent, "#"));
    percent.getAccessibleContext().setAccessibleName("Size in percent");
    choice.add(percent);
    choice.add(new JLabel("%"));
    percent.addChangeListener(e -> show());
    for (final JComponent part :
        List.of(
            Theme.newSecondaryLabel("Now " + size.width + " × " + size.height + " pixels"),
            choice,
            result,
            note)) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      content.add(part);
      content.add(Box.createVerticalStrut(Theme.GAP_S));
    }
    show();
  }

  /** Asks by how much; the factor, eg 1.5 for 150 %, empty when cancelled. */
  static Optional<Double> ask(final Component parent, final MapFiles files) {
    final ResizeDialog dialog = new ResizeDialog(files);
    return Dialogs.ask(parent, "Resize map", TEXT, dialog.content, "Resize map", dialog::isValid)
        ? Optional.of(dialog.factor())
        : Optional.empty();
  }

  /** The fields under {@link #TEXT}, which the dialog's pane shows above them. */
  JComponent content() {
    return content;
  }

  void setPercent(final int value) {
    percent.setValue(value);
  }

  double factor() {
    return (Integer) percent.getValue() / 100.0;
  }

  boolean isValid() {
    return factor() != 1 && files.whyNotResized(factor()).isEmpty();
  }

  /** What the dialog says about the size chosen, for tests. */
  String resultText() {
    return result.getText();
  }

  private void show() {
    final Dimension size = files.size();
    final Optional<String> refused = files.whyNotResized(factor());
    result.setForeground(
        refused.isPresent() ? Theme.getDangerColor() : new JLabel().getForeground());
    result.setText(
        refused.orElseGet(
            () ->
                factor() == 1
                    ? "The size it has now."
                    : "New size: "
                        + Math.round(size.width * factor())
                        + " × "
                        + Math.round(size.height * factor())
                        + " pixels"));
    note.setText(
        factor() < 1
            ? "Made smaller, thin border lines get softer. Undo takes it back."
            : "Undo takes it back. Save writes the new picture.");
  }
}
