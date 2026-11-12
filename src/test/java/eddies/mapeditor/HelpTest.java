package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class HelpTest {
  private final Preferences hidden =
      Preferences.userRoot().node("eddies-map-editor-test-" + UUID.randomUUID());

  @AfterEach
  void removePreferences() throws BackingStoreException {
    hidden.removeNode();
  }

  @DisplayName("A link says where a setting is made and names its button for screen readers")
  @Test
  void linkNamesTheTool() {
    // given
    final JComponent link = Help.link("The color is set per nation in", "Nations");

    // when
    final JButton open = givenButton(link, "Nations ›");

    // then
    assertThat(
            descendants(link)
                .filter(
                    c ->
                        c instanceof JLabel l
                            && "The color is set per nation in".equals(l.getText())))
        .as("the context before the link")
        .hasSize(1);
    assertThat(open.getAccessibleContext().getAccessibleName())
        .as("read as what it does")
        .isEqualTo("Open Nations");
    assertThatCode(open::doClick)
        .as("outside the editor's window a click does nothing")
        .doesNotThrowAnyException();
  }

  @DisplayName("A tool's intro line stays hidden once hidden, other tools still show theirs")
  @Test
  void introHiddenAfterHide() {
    final JComponent intro = Help.intro("Sounds", "The sounds the game plays.", hidden);
    final boolean shownAtFirst = intro.isVisible();

    givenButton(intro, "Hide").doClick();

    assertThat(shownAtFirst).as("the first visit shows the hint").isTrue();
    assertThat(intro.isVisible()).as("Hide hides it at once").isFalse();
    assertThat(Help.intro("Sounds", "The sounds the game plays.", hidden).isVisible())
        .as("the next visit remembers it")
        .isFalse();
    assertThat(Help.intro("Texts", "The game's texts.", hidden).isVisible())
        .as("hidden per tool")
        .isTrue();
  }

  @DisplayName("An intro longer than its width wraps on the first layout instead of being cut")
  @Test
  void introWrapsAtItsWidth() {
    final JComponent shortIntro = givenIntroLaidOut("Each territory.");
    final JComponent longIntro =
        givenIntroLaidOut(
            "Each territory with its owner, value, capital and the units it starts with, "
                + "on the map and in the list.");

    final int shortHeight = shortIntro.getPreferredSize().height;
    final int longHeight = longIntro.getPreferredSize().height;

    assertThat(longHeight).as("the long text takes more lines").isGreaterThan(shortHeight);
    assertThat(longIntro.getPreferredSize().width)
        .as("it asks for no more than the width it has, so nothing is cut")
        .isLessThanOrEqualTo(300);
  }

  @DisplayName("An intro laid out once in a 300 px column already has all its wrapped lines")
  @Test
  void introWrapsOnTheFirstLayout() {
    final JComponent intro =
        Help.intro(
            "Canals",
            "Canals and straits, eg Suez: ships pass between its two sea zones only while their"
                + " side holds its land territories. Choose a canal or make a new one.",
            hidden);
    final JPanel column = givenColumnLaidOutOnce(intro, 300);

    final JTextArea line = (JTextArea) ((Container) intro).getComponent(0);

    assertThat(line.getHeight())
        .as("tall enough for every line at the width it got, not cut to one")
        .isEqualTo(line.getPreferredSize().height)
        .isGreaterThan(2 * line.getFontMetrics(line.getFont()).getHeight());
    assertThat(column.getWidth()).as("the column it was laid out in").isEqualTo(300);
  }

  @DisplayName("Hide follows a short intro on its line instead of the far end of a wide column")
  @Test
  void hideFollowsAShortIntro() {
    final JComponent intro = Help.intro("Home", "Your map at a glance.", hidden);
    givenColumnLaidOutOnce(intro, 1200);

    final JTextArea line = (JTextArea) ((Container) intro).getComponent(0);
    final JButton hide = givenButton(intro, "Hide");
    final int hideX = SwingUtilities.convertPoint(hide, 0, 0, intro).x;

    assertThat(hideX)
        .as("a small gap after the text")
        .isGreaterThanOrEqualTo(line.getWidth())
        .isLessThanOrEqualTo(line.getWidth() + 2 * Theme.GAP_S);
    assertThat(line.getWidth())
        .as("the text keeps its one line's width")
        .isLessThan(line.getFontMetrics(line.getFont()).stringWidth("Your map at a glance.") + 8);
  }

  /** The intro at the top of a column with a border, as a tool shows it, laid out once. */
  private static JPanel givenColumnLaidOutOnce(final JComponent intro, final int width) {
    final JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    intro.setAlignmentX(Component.LEFT_ALIGNMENT);
    top.add(intro);
    final JPanel column = new JPanel(new BorderLayout());
    column.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    column.add(top, BorderLayout.NORTH);
    column.setSize(width, 600);
    layOutOnce(column);
    return column;
  }

  /** One pass from the top down, as a window's validate does (it needs a screen here). */
  private static void layOutOnce(final Container container) {
    container.doLayout();
    for (final Component child : container.getComponents()) {
      if (child instanceof Container inner) {
        layOutOnce(inner);
      }
    }
  }

  private JComponent givenIntroLaidOut(final String hint) {
    final JComponent intro = Help.intro("Territories", hint, hidden);
    intro.setSize(300, 40);
    intro.doLayout();
    return intro;
  }

  @DisplayName("The \"?\" tool tip does not repeat the explanation its bubble shows")
  @Test
  void buttonToolTipIsShort() {
    final JButton help = Help.button("Sounds", "The sounds the game plays.", "sounds.properties");

    assertThat(help.getToolTipText()).as("the text shows once, in the bubble").isEqualTo("Help");
    assertThat(help.getAccessibleContext().getAccessibleName())
        .as("screen readers tell several apart")
        .isEqualTo("Help: Sounds");
  }

  private static JButton givenButton(final Container container, final String text) {
    return descendants(container)
        .filter(JButton.class::isInstance)
        .map(JButton.class::cast)
        .filter(button -> text.equals(button.getText()))
        .findFirst()
        .orElseThrow();
  }

  private static Stream<Component> descendants(final Container container) {
    return Stream.of(container.getComponents())
        .flatMap(
            child ->
                Stream.concat(
                    Stream.of(child),
                    child instanceof Container inner ? descendants(inner) : Stream.empty()));
  }
}
