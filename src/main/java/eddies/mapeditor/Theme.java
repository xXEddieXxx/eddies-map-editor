package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Font;
import java.util.Locale;
import java.util.Optional;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import lombok.experimental.UtilityClass;

/**
 * The editor's styles on top of the FlatLaf theme TripleA sets up: colors from the theme and the
 * look of primary buttons, cards and section headings, defined here so they look the same with
 * any TripleA version.
 */
@UtilityClass
class Theme {

  /** Text that says less, eg a hint below a title. */
  static Color getSecondaryTextColor() {
    return themeColor("Label.disabledForeground");
  }

  static Color getAccentColor() {
    return themeColor("Component.accentColor");
  }

  static Color getSeparatorColor() {
    return themeColor("Separator.foreground");
  }

  // a ColorUIResource, so a theme change gives the component the new theme's color
  private static Color themeColor(final String key) {
    return new ColorUIResource(Optional.ofNullable(UIManager.getColor(key)).orElse(Color.GRAY));
  }

  /** The main action of a screen: the theme's default button, bold, also when focused. */
  static void setPrimaryButtonStyle(final AbstractButton button) {
    button.putClientProperty(
        FlatClientProperties.STYLE,
        "background: $Button.default.background;"
            + " foreground: $Button.default.foreground;"
            + " hoverBackground: $Button.default.hoverBackground;"
            + " pressedBackground: $Button.default.pressedBackground;"
            + " borderColor: $Button.default.borderColor;"
            + " hoverBorderColor: $Button.default.hoverBorderColor;"
            + " focusedBorderColor: $Button.default.focusedBorderColor;"
            + " focusedBackground: $Button.default.background;"
            + " font: bold");
  }

  /** A button like a link; a click does not focus it, so no focus ring shows unless Tab does. */
  static void setBorderlessButtonStyle(final AbstractButton button) {
    button.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    button.setRequestFocusEnabled(false);
  }

  /** A raised, rounded area that groups what belongs together. */
  static void setCardStyle(final JComponent panel) {
    panel.putClientProperty(
        FlatClientProperties.STYLE, "arc: 16; background: $TextField.background");
  }

  /** A small heading in capitals. */
  static JLabel newSectionLabel(final String text) {
    final JLabel label = new JLabel(text.toUpperCase(Locale.ROOT));
    label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() - 1));
    label.setForeground(getSecondaryTextColor());
    return label;
  }

  /** The line above a window's buttons. */
  static Border newFooterBorder() {
    return BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(1, 0, 0, 0, getSeparatorColor()),
        BorderFactory.createEmptyBorder(10, 12, 10, 12));
  }

  static String convertColorToHex(final Color color) {
    return "#" + Integer.toHexString(color.getRGB()).substring(2);
  }
}
