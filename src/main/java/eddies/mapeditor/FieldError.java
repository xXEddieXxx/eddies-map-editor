package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JLabel;

/** The small line below a field that says what is wrong with it, and the field's red outline. */
final class FieldError extends JLabel {
  private final JComponent field;

  /** Hidden until the field has a problem. */
  FieldError(final JComponent field) {
    this.field = field;
    putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
    setVisible(false);
  }

  void setProblem(final Optional<String> problem) {
    setText(problem.map(text -> "! " + text).orElse(""));
    // a narrow column cuts a long reason: the tool tip has all of it
    setToolTipText(problem.orElse(null));
    // the theme's color now, as the theme may have changed since the field was made
    setForeground(Theme.getDangerColor());
    setVisible(problem.isPresent());
    field.putClientProperty(
        FlatClientProperties.OUTLINE,
        problem.isPresent() ? FlatClientProperties.OUTLINE_ERROR : null);
  }

  boolean hasProblem() {
    return isVisible();
  }
}
