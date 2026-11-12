package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.metal.MetalLookAndFeel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class ThemeTest {
  private LookAndFeel before;

  @BeforeEach
  void keepLookAndFeel() {
    before = UIManager.getLookAndFeel();
  }

  @AfterEach
  void restoreLookAndFeel() throws UnsupportedLookAndFeelException {
    Theme.uninstall();
    UIManager.setLookAndFeel(before);
  }

  static Stream<Arguments> themes() {
    return Stream.of(
        Arguments.of(new FlatLightLaf(), "#57606A"),
        Arguments.of(new FlatIntelliJLaf(), "#57606A"),
        Arguments.of(new FlatMacLightLaf(), "#57606A"),
        Arguments.of(new FlatDarkLaf(), "#B4B8BF"),
        Arguments.of(new FlatDarculaLaf(), "#B4B8BF"),
        Arguments.of(new FlatMacDarkLaf(), "#B4B8BF"));
  }

  @DisplayName("Every FlatLaf theme TripleA offers gets the editor's colors")
  @ParameterizedTest
  @MethodSource("themes")
  void tokensInEveryTheme(final LookAndFeel theme, final String secondaryText)
      throws UnsupportedLookAndFeelException {
    Theme.install();

    UIManager.setLookAndFeel(theme);

    assertThat(Theme.convertColorToHex(Theme.getSecondaryTextColor()))
        .as("secondary text has the contrast the theme file sets, not the disabled grey")
        .isEqualToIgnoringCase(secondaryText);
    assertThat(UIManager.getColor("List.selectionInactiveBackground"))
        .as("a list keeps its selection color when the focus is in the panel beside it")
        .isEqualTo(UIManager.getColor("List.selectionBackground"));
    assertThat(UIManager.getColor("Editor.danger"))
        .as("FlatLaf styles can name the editor's colors, eg $Editor.danger")
        .isNotNull();
  }

  @DisplayName("A chosen view or mode's text reads on its background in every theme")
  @ParameterizedTest
  @MethodSource("themes")
  void chosenMapToolReads(final LookAndFeel theme, final String secondaryText)
      throws UnsupportedLookAndFeelException {
    Theme.install();

    UIManager.setLookAndFeel(theme);

    assertThat(
            DialogsTest.contrast(
                Theme.getMapToolSelectedTextColor(), UIManager.getColor("Editor.mapToolSelected")))
        .as("at least 4.5:1, as for any text (WCAG AA)")
        .isGreaterThanOrEqualTo(4.5);
  }

  @DisplayName("Secondary text reads on the background in every theme")
  @ParameterizedTest
  @MethodSource("themes")
  void secondaryTextReads(final LookAndFeel theme, final String secondaryText)
      throws UnsupportedLookAndFeelException {
    Theme.install();

    UIManager.setLookAndFeel(theme);

    assertThat(
            DialogsTest.contrast(
                Theme.getSecondaryTextColor(), UIManager.getColor("Panel.background")))
        .as("at least 4.5:1, as for any text (WCAG AA)")
        .isGreaterThanOrEqualTo(4.5);
  }

  @DisplayName("The Mac themes have the same colors as the light and dark themes")
  @Test
  void macThemesAreCopies() throws IOException {
    final Properties light = givenThemeFile("FlatLightLaf");
    final Properties dark = givenThemeFile("FlatDarkLaf");

    final Properties macLight = givenThemeFile("FlatMacLightLaf");
    final Properties macDark = givenThemeFile("FlatMacDarkLaf");

    assertThat(macLight).as("Mac light is a copy of light").isEqualTo(light);
    assertThat(macDark).as("Mac dark is a copy of dark").isEqualTo(dark);
    assertThat(dark.keySet())
        .as("light and dark set the same keys, so no key is missing in one")
        .isEqualTo(light.keySet());
  }

  @DisplayName("Without FlatLaf every color still has a value")
  @Test
  void colorsWithoutFlatLaf() throws UnsupportedLookAndFeelException {
    UIManager.setLookAndFeel(new MetalLookAndFeel());

    final List<Color> colors =
        List.of(
            Theme.getSecondaryTextColor(),
            Theme.getSelectionSecondaryTextColor(),
            Theme.getPlaceholderColor(),
            Theme.getAccentColor(),
            Theme.getAccentTextColor(),
            Theme.getSeparatorColor(),
            Theme.getSelectionColor(),
            Theme.getHoverColor(),
            Theme.getSuccessColor(),
            Theme.getWarningColor(),
            Theme.getDangerColor(),
            Theme.getWarningBackground(),
            Theme.getDangerBackground(),
            Theme.getToastBackground(),
            Theme.getToastTextColor());

    assertThat(colors).as("TripleA may run with another look and feel").doesNotContainNull();
  }

  @DisplayName("A section heading is written as given, not in capitals")
  @Test
  void sectionLabelKeepsCase() {
    final String heading = "Next steps";

    final String shown = Theme.newSectionLabel(heading).getText();

    assertThat(shown).as("sentence case, as the map maker reads it").isEqualTo(heading);
  }

  @DisplayName("A status label has its mark before the words, so it does not rely on color")
  @Test
  void statusLabelHasMark() {
    final String shown = Theme.newStatusLabel(Theme.Status.DONE, "Nations").getText();

    assertThat(shown).as("the mark tells the status without color").isEqualTo("✓ Nations");
  }

  static Stream<LookAndFeel> lightAndDark() {
    return Stream.of(new FlatLightLaf(), new FlatDarkLaf());
  }

  @DisplayName("The primary button is filled in the accent with white text, light and dark")
  @ParameterizedTest
  @MethodSource("lightAndDark")
  void primaryButtonFilled(final LookAndFeel theme) throws UnsupportedLookAndFeelException {
    Theme.install();
    UIManager.setLookAndFeel(theme);

    final JButton button = givenPrimaryButton();

    assertThat(button.getBackground())
        .as("filled in the accent, not outlined on the button's own background")
        .isEqualTo(UIManager.getColor("Component.accentColor"))
        .isNotEqualTo(UIManager.getColor("Button.background"));
    assertThat(button.getForeground()).as("white text on the accent").isEqualTo(Color.WHITE);
  }

  @DisplayName("An HTML width from htmlWidth wraps the text at that many screen pixels")
  @Test
  void htmlWidthInScreenPixels() {
    final int px = 200;

    final JLabel label =
        new JLabel(
            "<html><div style='width:"
                + Theme.htmlWidth(px)
                + "'>"
                + "word ".repeat(100)
                + "</div></html>");

    assertThat(label.getPreferredSize().width)
        .as("Swing draws a CSS px 1.3 times as wide; the helper makes up for it")
        .isBetween(UIScale.scale(px) - 2, UIScale.scale(px) + 2);
  }

  @DisplayName("A symbol button, eg ×, shows its symbol in the secondary text color, light and dark")
  @ParameterizedTest
  @MethodSource("lightAndDark")
  void symbolInSecondaryText(final LookAndFeel theme) throws UnsupportedLookAndFeelException {
    Theme.install();
    UIManager.setLookAndFeel(theme);

    final JButton remove = Theme.newIconButton("×", "Remove", () -> {});

    assertThat(remove.getForeground())
        .as("quiet like the text around it, no tint of its own")
        .isEqualTo(Theme.getSecondaryTextColor());
  }

  @DisplayName("Small text keeps the text's size, and an example lighter than secondary text")
  @ParameterizedTest
  @MethodSource("lightAndDark")
  void smallTextReadable(final LookAndFeel theme) throws UnsupportedLookAndFeelException {
    Theme.install();
    UIManager.setLookAndFeel(theme);

    final int text = UIManager.getFont("defaultFont").getSize();
    final int small = UIManager.getFont("small.font").getSize();

    assertThat(small)
        .as("secondary lines as big as the text, set apart by color only (accessibility)")
        .isEqualTo(text);
    assertThat(Theme.getPlaceholderColor())
        .as("an example is not mistaken for typed or secondary text")
        .isNotEqualTo(Theme.getSecondaryTextColor());
  }

  private static JButton givenPrimaryButton() {
    final JButton button = new JButton("Go to Territories");
    Theme.setPrimaryButtonStyle(button);
    return button;
  }

  private static Properties givenThemeFile(final String theme) throws IOException {
    final Properties properties = new Properties();
    try (InputStream in =
        ThemeTest.class.getResourceAsStream("/eddies/mapeditor/theme/" + theme + ".properties")) {
      properties.load(in);
    }
    return properties;
  }
}
