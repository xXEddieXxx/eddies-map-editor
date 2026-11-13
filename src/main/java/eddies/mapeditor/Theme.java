package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.util.UIScale;
import eddies.mapeditor.MakerIcon.Glyph;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.List;
import java.util.Optional;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import lombok.experimental.UtilityClass;

/**
 * The editor's styles on top of the FlatLaf theme TripleA sets up: its colors (in
 * {@code theme/<theme class>.properties}, eg {@code Editor.danger}), the button kinds, titles and
 * section headings, defined here so they look the same with any TripleA version.
 */
@UtilityClass
class Theme {
  static final int GAP_XS = 4;
  static final int GAP_S = 8;
  static final int GAP_M = 12;
  static final int GAP_L = 16;
  static final int GAP_XL = 24;
  static final int GAP_XXL = 32;
  // the map's toolbar: its buttons' height and their icons' size
  static final int MAP_TOOL = 32;
  static final int MAP_TOOL_ICON = 18;
  private static final String MAP_TOOL_KEY = "Editor.mapTool";
  private static final String MAP_TOOL_NAME_KEY = "Editor.mapToolName";
  private static final String MAP_TOOL_GLYPH_KEY = "Editor.mapToolGlyph";

  // FlatLaf reads <theme class>.properties from here on top of its own, eg FlatDarkLaf.properties
  private static final String THEME_PACKAGE = "eddies.mapeditor.theme";

  /** The editor's colors in the FlatLaf themes; call it before the look and feel is set up. */
  static void install() {
    FlatLaf.registerCustomDefaultsSource(THEME_PACKAGE, Theme.class.getClassLoader());
  }

  /**
   * FlatLaf, light or dark like the look TripleA set up, when that is another look, eg Nimbus:
   * the editor's sizes and styles are made for FlatLaf, and other looks cut its buttons to "...".
   * Call it on the Swing thread after TripleA's look is set up.
   */
  static void useFlatLaf() {
    if (UIManager.getLookAndFeel() instanceof FlatLaf) {
      return;
    }
    final Color background =
        Optional.ofNullable(UIManager.getColor("Panel.background")).orElse(Color.WHITE);
    final boolean dark =
        (background.getRed() + background.getGreen() + background.getBlue()) / 3 < 128;
    if (dark) {
      FlatDarkLaf.setup();
    } else {
      FlatLightLaf.setup();
    }
  }

  /** Undoes {@link #install()}, for tests that set up a look and feel. */
  static void uninstall() {
    FlatLaf.unregisterCustomDefaultsSource(THEME_PACKAGE, Theme.class.getClassLoader());
  }

  /** Text that says less, eg a hint below a title. */
  static Color getSecondaryTextColor() {
    return themeColor("Editor.secondaryText", themeColor("Label.disabledForeground"));
  }

  /** An example in an empty field, lighter than secondary text, so it does not read as typed. */
  static Color getPlaceholderColor() {
    return themeColor("TextField.placeholderForeground", getSecondaryTextColor());
  }

  /** Secondary text on a selected row. */
  static Color getSelectionSecondaryTextColor() {
    return themeColor("Editor.selectionSecondaryText", getSecondaryTextColor());
  }

  static Color getAccentColor() {
    return themeColor("Component.accentColor");
  }

  /** Links and tertiary buttons: the accent, lighter on a dark theme, so it reads as text. */
  static Color getAccentTextColor() {
    return themeColor("Editor.accentText", getAccentColor());
  }

  static Color getSeparatorColor() {
    return themeColor("Separator.foreground");
  }

  /** A selected row, also while the focus is elsewhere. */
  static Color getSelectionColor() {
    return themeColor("List.selectionBackground");
  }

  /** A row under the mouse. */
  static Color getHoverColor() {
    return themeColor("Editor.hover", new Color(0xF5F5F5));
  }

  static Color getSuccessColor() {
    return themeColor("Editor.success", new Color(0x1A7F37));
  }

  static Color getWarningColor() {
    return themeColor("Editor.warning", new Color(0x9A6700));
  }

  static Color getDangerColor() {
    return themeColor("Editor.danger", new Color(0xCF222E));
  }

  static Color getWarningBackground() {
    return themeColor("Editor.warningBackground", new Color(0xFFF8E5));
  }

  static Color getDangerBackground() {
    return themeColor("Editor.dangerBackground", new Color(0xFBEDEE));
  }

  static Color getToastBackground() {
    return themeColor("Editor.toastBackground", new Color(0x1F2328));
  }

  /** The map's toolbar: a step off the panels around it, so its controls read as one bar. */
  static Color getMapToolbarColor() {
    return themeColor("Editor.mapToolbar", new Color(0xDFE2E7));
  }

  /** The line under the map's toolbar. */
  static Color getMapToolbarLineColor() {
    return themeColor("Editor.mapToolbarLine", getSeparatorColor());
  }

  /** A chosen view or mode's text and glyph in the map's toolbar, on Editor.mapToolSelected. */
  static Color getMapToolSelectedTextColor() {
    return themeColor("Editor.mapToolSelectedText", new Color(0x174EA6));
  }

  static Color getToastTextColor() {
    return themeColor("Editor.toastText", Color.WHITE);
  }

  /**
   * The 1 px outline of a color swatch: light around a dark color, dark around a light one, so a
   * near-black nation shows on the dark theme and a near-white one on the light theme.
   */
  static Color getSwatchOutlineColor(final Color fill) {
    final double luminance =
        (0.299 * fill.getRed() + 0.587 * fill.getGreen() + 0.114 * fill.getBlue()) / 255;
    return luminance < 0.5 ? new Color(0xB0B0B0) : new Color(0x505050);
  }

  /** An XML file's tags, attribute names, values and comments, for a light or dark theme. */
  static List<Color> getXmlColors() {
    return FlatLaf.isLafDark()
        ? List.of(
            new Color(0x79b8ff), new Color(0xffab70), new Color(0x85e89d), new Color(0x6a737d))
        : List.of(
            new Color(0x0550ae), new Color(0x953800), new Color(0x116329), new Color(0x6e7781));
  }

  private static Color themeColor(final String key) {
    return themeColor(key, Color.GRAY);
  }

  // a ColorUIResource, so a theme change gives the component the new theme's color; the fallback,
  // as TripleA may run with a look and feel that is not FlatLaf and lacks the key
  private static Color themeColor(final String key, final Color fallback) {
    return new ColorUIResource(Optional.ofNullable(UIManager.getColor(key)).orElse(fallback));
  }

  /** A screen's title. */
  static JLabel newTitle(final String text) {
    final JLabel label = new JLabel(text);
    label.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
    return label;
  }

  /** A small line that says less, eg below a title. */
  static JLabel newSecondaryLabel(final String text) {
    final JLabel label = new JLabel(text);
    label.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
    label.setForeground(getSecondaryTextColor());
    return label;
  }

  /** A section's heading, as written. */
  static JLabel newSectionLabel(final String text) {
    final JLabel label = new JLabel(text);
    label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
    return label;
  }

  /** How far a step or a check is: each with its own mark, so it never shows by color alone. */
  enum Status {
    DONE("✓", "Editor.success"),
    TODO("○", "Editor.secondaryText"),
    PROBLEM("!", "Editor.danger"),
    WARNING("!", "Editor.warning");

    private final String mark;
    // the theme key of color(), for FlatLaf styles
    private final String key;

    Status(final String mark, final String key) {
      this.mark = mark;
      this.key = key;
    }

    String mark() {
      return mark;
    }

    Color color() {
      return switch (this) {
        case DONE -> getSuccessColor();
        case TODO -> getSecondaryTextColor();
        case PROBLEM -> getDangerColor();
        case WARNING -> getWarningColor();
      };
    }
  }

  /** The status's mark and the words, in the status's color. */
  static JLabel newStatusLabel(final Status status, final String text) {
    final JLabel label = new JLabel(status.mark() + " " + text);
    label.setForeground(status.color());
    return label;
  }

  // filled in the accent with white text, not FlatLaf's default button: the light themes only
  // outline that one
  private static final String PRIMARY_STYLE =
      "background: $Component.accentColor;"
          + " foreground: #FFFFFF;"
          + " hoverBackground: darken($Component.accentColor,5%);"
          + " pressedBackground: darken($Component.accentColor,10%);"
          + " focusedBackground: $Component.accentColor;"
          + " borderColor: $Component.accentColor;"
          + " hoverBorderColor: darken($Component.accentColor,5%);"
          + " focusedBorderColor: $Component.accentColor;"
          + " font: $semibold.font";

  /** The main action of a screen: filled in the accent, white semibold text, also when focused. */
  static void setPrimaryButtonStyle(final AbstractButton button) {
    button.putClientProperty(FlatClientProperties.STYLE, PRIMARY_STYLE);
  }

  /** Whether the button has the primary style, eg for a screen's one main action. */
  static boolean isPrimary(final AbstractButton button) {
    return PRIMARY_STYLE.equals(button.getClientProperty(FlatClientProperties.STYLE));
  }

  /** Any other action with a frame: the theme's button, never narrower than 72 px. */
  static void setSecondaryButtonStyle(final AbstractButton button) {
    button.putClientProperty(FlatClientProperties.STYLE, "minimumWidth: 72");
  }

  /** A small action in a row or list, eg Show or Rename: text in the accent color. */
  static void setTertiaryButtonStyle(final AbstractButton button) {
    setBorderlessButtonStyle(button);
    button.putClientProperty(
        FlatClientProperties.STYLE, "foreground: $Editor.accentText; margin: 2,4,2,4");
  }

  /** An action that removes, outside dialogs: text in the danger color. */
  static void setDestructiveButtonStyle(final AbstractButton button) {
    setBorderlessButtonStyle(button);
    button.putClientProperty(FlatClientProperties.STYLE, "foreground: $Editor.danger");
  }

  /**
   * A dialog's verb that removes: filled in the danger color, its own one, as the dark theme's
   * danger text color is too light under white text.
   */
  static void setDangerButtonStyle(final AbstractButton button) {
    button.putClientProperty(
        FlatClientProperties.STYLE,
        "background: $Editor.dangerButton;"
            + " foreground: #FFFFFF;"
            + " hoverBackground: $Editor.dangerButton;"
            + " pressedBackground: $Editor.dangerButton;"
            + " focusedBackground: $Editor.dangerButton;"
            + " borderColor: $Editor.dangerButton;"
            + " font: $semibold.font");
  }

  /** A rounded button that shows a status, eg the header's problems. */
  static void setPillStyle(final AbstractButton button, final Status status) {
    final String background =
        switch (status) {
          case PROBLEM -> " background: $Editor.dangerBackground;";
          case WARNING -> " background: $Editor.warningBackground;";
          case DONE, TODO -> "";
        };
    button.putClientProperty(
        FlatClientProperties.STYLE,
        "arc: 999; margin: 2,10,2,10;" + background + " foreground: $" + status.key);
  }

  /** A button like a link; a click does not focus it, so no focus ring shows unless Tab does. */
  static void setBorderlessButtonStyle(final AbstractButton button) {
    button.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    button.setRequestFocusEnabled(false);
  }

  /** A 28 px button with a glyph; the tool tip says what it does, also to screen readers. */
  static JButton newIconButton(final Glyph glyph, final String toolTip, final Runnable action) {
    return newIconButton(MakerIcon.small(glyph), "", toolTip, action);
  }

  /** A 28 px button with a symbol, eg "+" or "×"; the tool tip says what it does. */
  static JButton newIconButton(final String symbol, final String toolTip, final Runnable action) {
    return newIconButton(null, symbol, toolTip, action);
  }

  private static JButton newIconButton(
      final Icon icon, final String symbol, final String toolTip, final Runnable action) {
    final JButton button = new JButton(symbol, icon);
    button.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    // a symbol in the secondary text color, as quiet as a glyph; a style outlives a theme change
    button.putClientProperty(FlatClientProperties.STYLE, "foreground: $Editor.secondaryText");
    button.setPreferredSize(new Dimension(UIScale.scale(28), UIScale.scale(28)));
    button.setToolTipText(toolTip);
    button.getAccessibleContext().setAccessibleName(toolTip);
    button.addActionListener(e -> action.run());
    return button;
  }

  /**
   * A button or toggle in the map's toolbar, {@value #MAP_TOOL} px high; a chosen toggle's text and
   * glyph in their own color on a stronger selection, so the mode or view on shows at a glance.
   */
  static void setMapToolStyle(final AbstractButton button) {
    button.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    button.putClientProperty(
        FlatClientProperties.STYLE,
        "toolbar.selectedBackground: $Editor.mapToolSelected;"
            + " toolbar.selectedForeground: $Editor.mapToolSelectedText");
    button.putClientProperty(MAP_TOOL_KEY, Boolean.TRUE);
    fitMapTool(button);
  }

  /**
   * Like {@link #setMapToolStyle} for a tool shown by its name, which short of room the map's
   * toolbar shows as this glyph; the name stays its accessible name.
   */
  static void setNamedMapToolStyle(final AbstractButton button, final Glyph glyph) {
    button.putClientProperty(MAP_TOOL_GLYPH_KEY, new MakerIcon(glyph, MAP_TOOL_ICON));
    button.putClientProperty(MAP_TOOL_NAME_KEY, button.getText());
    button.getAccessibleContext().setAccessibleName(button.getText());
    setMapToolStyle(button);
  }

  static boolean isMapTool(final Component component) {
    return component instanceof JComponent c && c.getClientProperty(MAP_TOOL_KEY) != null;
  }

  /** The name of a tool made with {@link #setNamedMapToolStyle}. */
  static Optional<String> mapToolName(final AbstractButton button) {
    return Optional.ofNullable(button.getClientProperty(MAP_TOOL_NAME_KEY)).map(String.class::cast);
  }

  /** The glyph of a tool made with {@link #setNamedMapToolStyle}, shown instead of its name. */
  static Optional<Icon> mapToolGlyph(final AbstractButton button) {
    return Optional.ofNullable(button.getClientProperty(MAP_TOOL_GLYPH_KEY)).map(Icon.class::cast);
  }

  /** Sizes a map toolbar's button to its text: {@value #MAP_TOOL} px high, at least square. */
  static void fitMapTool(final AbstractButton button) {
    button.setPreferredSize(null);
    final int size = UIScale.scale(MAP_TOOL);
    button.setPreferredSize(new Dimension(Math.max(button.getPreferredSize().width, size), size));
  }

  /**
   * How wide {@link #fitMapTool} makes the button with this icon and text, measured without
   * changing it, as the look lays out a button: its insets around icon, gap and text.
   */
  static int mapToolWidth(
      final AbstractButton button, final Optional<Icon> icon, final String text) {
    final Insets insets = button.getInsets();
    final int iconWidth = icon.map(Icon::getIconWidth).orElse(0);
    final int textWidth =
        text.isEmpty() ? 0 : button.getFontMetrics(button.getFont()).stringWidth(text);
    final int gap = icon.isPresent() && !text.isEmpty() ? button.getIconTextGap() : 0;
    return Math.max(
        insets.left + insets.right + iconWidth + gap + textWidth, UIScale.scale(MAP_TOOL));
  }

  /** "+ label", eg at the end of a list's header. */
  static JButton newAddButton(final String label, final String toolTip, final Runnable action) {
    final JButton button = new JButton("+ " + label);
    button.putClientProperty(FlatClientProperties.STYLE, "margin: 2,8,2,8");
    button.setToolTipText(toolTip);
    button.addActionListener(e -> action.run());
    return button;
  }

  /** A raised, rounded area that groups what belongs together. */
  static void setCardStyle(final JComponent panel) {
    panel.putClientProperty(
        FlatClientProperties.STYLE, "arc: 16; background: $TextField.background");
  }

  /** The line above a window's buttons. */
  static Border newFooterBorder() {
    return BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(1, 0, 0, 0, getSeparatorColor()),
        BorderFactory.createEmptyBorder(GAP_M, GAP_L, GAP_M, GAP_L));
  }

  /**
   * The CSS width for {@code px} screen pixels in a Swing HTML text, eg {@code "246px"} for 320:
   * Swing's CSS draws a px 1.3 times as wide.
   */
  static String htmlWidth(final int px) {
    return Math.round(UIScale.scale(px) / 1.3f) + "px";
  }

  static String convertColorToHex(final Color color) {
    return "#" + Integer.toHexString(color.getRGB()).substring(2);
  }
}
