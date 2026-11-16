package eddies.mapeditor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Resize map: by how much the whole map gets bigger or smaller, with the size that gives, and why
 * not when {@link MapFiles#whyNotResized} refuses it. A map whose units.scale is not 1 is offered
 * the size at which units drawn at their pictures' size fill as much of the map as they do now.
 */
final class ResizeDialog {
  static final String TEXT =
      "Make the whole map bigger or smaller\n"
          + "The picture, the borders and every place are resized with it. Units keep their size, so a"
          + " bigger map gives them more room.\n"
          + "From 25 % to 400 %, each side at least "
          + MapFiles.MIN_RESIZED_SIDE
          + " pixels. A saved map is resized tile by tile, at any size; a picture not saved yet"
          + " is held whole, up to "
          + MapFiles.MAX_HELD_PICTURE / 1_000_000
          + " million pixels.";
  private static final int MIN_PERCENT = 25;
  private static final int MAX_PERCENT = 400;
  private static final int STEP = 5;
  private static final List<Integer> USUAL = List.of(50, 75, 150, 200);

  /** What was asked: the factor, eg 1.5 for 150 %, and whether units.scale is set to 1 with it. */
  record Request(double factor, boolean unitsFullSize) {}

  private final MapFiles files;
  private final JPanel content = new JPanel();
  private final List<JButton> usual = new ArrayList<>();
  private final JSpinner percent =
      new JSpinner(new SpinnerNumberModel(150, MIN_PERCENT, MAX_PERCENT, STEP));
  private final JLabel result = new JLabel(" ");
  private final JLabel note = Theme.newSecondaryLabel(" ");
  private final JCheckBox fullSize = new JCheckBox();

  ResizeDialog(final MapFiles files) {
    this.files = files;
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    final Dimension size = files.size();
    final JPanel choice = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    choice.setOpaque(false);
    // no gap before the first, so it lines up with the lines around it
    choice.setBorder(BorderFactory.createEmptyBorder(0, -Theme.GAP_S, 0, 0));
    for (final int value : USUAL) {
      final JButton button = new JButton(value + " %");
      Theme.setSecondaryButtonStyle(button);
      button.addActionListener(e -> percent.setValue(value));
      button.setEnabled(files.whyNotResized(value / 100.0).isEmpty());
      files.whyNotResized(value / 100.0).ifPresent(button::setToolTipText);
      usual.add(button);
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
    final List<JComponent> parts = new ArrayList<>();
    parts.add(Theme.newSecondaryLabel("Now " + size.width + " × " + size.height + " pixels"));
    parts.add(choice);
    parts.add(result);
    parts.add(note);
    final Optional<Integer> suggested = percentForFullSizeUnits();
    fullSize.setOpaque(false);
    fullSize.setVisible(suggested.isPresent());
    suggested.ifPresent(
        value -> {
          fullSize.setText(
              "Draw units at their pictures' size: units.scale 1 instead of "
                  + files.unitScale()
                  + ". At "
                  + value
                  + " % they take as much of the map as now.");
          fullSize.setToolTipText(
              "The game draws units at units.scale times their pictures; at 1 they are drawn as"
                  + " they are, which looks sharpest");
          fullSize.addActionListener(
              e -> {
                if (fullSize.isSelected()) {
                  percent.setValue(value);
                }
              });
          parts.add(fullSize);
        });
    for (final JComponent part : parts) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      content.add(part);
      content.add(Box.createVerticalStrut(Theme.GAP_S));
    }
    show();
  }

  /** Asks by how much; empty when cancelled. */
  static Optional<Request> ask(final Component parent, final MapFiles files) {
    final ResizeDialog dialog = new ResizeDialog(files);
    return Dialogs.ask(parent, "Resize map", TEXT, dialog.content, "Resize map", dialog::isValid)
        ? Optional.of(dialog.request())
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

  Request request() {
    return new Request(factor(), fullSize.isVisible() && fullSize.isSelected());
  }

  boolean isValid() {
    return factor() != 1 && files.whyNotResized(factor()).isEmpty();
  }

  /** What the dialog says about the size chosen, for tests. */
  String resultText() {
    return result.getText();
  }

  /** Whether each usual size's button can be pressed, in {@link #USUAL}'s order, for tests. */
  List<Boolean> usualEnabled() {
    return usual.stream().map(JButton::isEnabled).toList();
  }

  /** The units.scale check box's text, empty while units are drawn at their size, for tests. */
  String fullSizeText() {
    return fullSize.isVisible() ? fullSize.getText() : "";
  }

  void setFullSize(final boolean selected) {
    fullSize.setSelected(selected);
    if (selected) {
      percentForFullSizeUnits().ifPresent(percent::setValue);
    }
  }

  /**
   * The size at which units drawn at their pictures' size (units.scale 1) take as much of the map
   * as they do now: 100 / units.scale, rounded up to the spinner's step, eg 115 % for 0.875; empty
   * while units.scale is 1 or the size cannot be done.
   */
  Optional<Integer> percentForFullSizeUnits() {
    final double scale = files.unitScale();
    if (scale == 1 || scale <= 0) {
      return Optional.empty();
    }
    final int value = (int) Math.ceil(100 / scale / STEP) * STEP;
    return Optional.of(value)
        .filter(v -> v >= MIN_PERCENT && v <= MAX_PERCENT && v != 100)
        .filter(v -> files.whyNotResized(v / 100.0).isEmpty());
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
