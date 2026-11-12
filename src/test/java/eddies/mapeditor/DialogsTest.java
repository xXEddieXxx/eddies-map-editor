package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class DialogsTest {
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

  @DisplayName("A dialog has Cancel left of its verb; Enter chooses the verb, Esc Cancel")
  @Test
  void cancelThenVerb() {
    final Dialogs.Pane pane = Dialogs.newPane("Find again?", List.of(), null, "Find", () -> true);
    final List<String> clicked = givenClicksRecorded(pane);

    press(pane, KeyEvent.VK_ENTER);
    press(pane, KeyEvent.VK_ESCAPE);

    assertThat(pane.buttons().stream().map(JButton::getText))
        .as("the verb at the right, where the eye ends")
        .containsExactly("Cancel", "Find");
    assertThat(pane.verb()).as("the verb is the last button").isSameAs(pane.buttons().getLast());
    assertThat(clicked).as("Enter is the verb, Esc is Cancel").containsExactly("Find", "Cancel");
  }

  @DisplayName("A remove lists at most 6 consequences, then how many more")
  @Test
  void removeListsSixDetails() {
    final List<String> details =
        IntStream.rangeClosed(1, 8).mapToObj(i -> "Connection " + i).toList();

    final Dialogs.Pane pane =
        Dialogs.newRemovePane("Remove Germany?\nIt goes with its units.", details, "Remove");

    assertThat(givenTexts(pane.root()))
        .as("the lead, the body, six details, then the rest counted")
        .containsExactly(
            "<html>Remove Germany?</html>",
            "<html>It goes with its units.</html>",
            "• Connection 1",
            "• Connection 2",
            "• Connection 3",
            "• Connection 4",
            "• Connection 5",
            "• Connection 6",
            "and 2 more");
    assertThat(String.valueOf(pane.verb().getClientProperty("FlatLaf.style")))
        .as("the verb that removes is filled in the danger color")
        .contains("$Editor.dangerButton");
  }

  static Stream<LookAndFeel> themes() {
    return Stream.of(new FlatLightLaf(), new FlatDarkLaf());
  }

  @DisplayName("The danger verb's white text reads on it in the light and the dark theme")
  @ParameterizedTest
  @MethodSource("themes")
  void dangerVerbReads(final LookAndFeel theme) throws UnsupportedLookAndFeelException {
    Theme.install();
    UIManager.setLookAndFeel(theme);

    final JButton verb = Dialogs.newRemovePane("Remove Germany?", List.of(), "Remove").verb();

    assertThat(contrast(verb.getForeground(), verb.getBackground()))
        .as("at least 4.5:1, as for any text (WCAG AA)")
        .isGreaterThanOrEqualTo(4.5);
  }

  @DisplayName("A name's verb waits for a name without problems; the problem shows below it")
  @Test
  void nameVerbWaitsForGoodName() {
    final JTextField field = new JTextField("My game");
    final Dialogs.Pane pane =
        Dialogs.newNamePane(
            "Name",
            field,
            "Create",
            name ->
                name.equals("Taken") ? Optional.of("A game of that name exists") : Optional.empty());
    final boolean enabledAtFirst = pane.verb().isEnabled();

    field.setText("Taken");
    final boolean enabledWhenTaken = pane.verb().isEnabled();
    final List<String> textsWhenTaken = givenTexts(pane.root());
    field.setText("  ");
    final boolean enabledWhenBlank = pane.verb().isEnabled();
    final List<String> textsWhenBlank = givenTexts(pane.root());

    assertThat(enabledAtFirst).as("a good name to start with").isTrue();
    assertThat(enabledWhenTaken).as("a taken name cannot be chosen").isFalse();
    assertThat(textsWhenTaken).as("why, below the field").contains("! A game of that name exists");
    assertThat(enabledWhenBlank).as("no name, nothing to create").isFalse();
    assertThat(textsWhenBlank)
        .as("a blank name is not blamed, the verb just waits")
        .doesNotContain("! A game of that name exists");
  }

  @DisplayName("Unsaved changes: Don't save stays away from Save, Esc is Cancel")
  @Test
  void saveChoiceOrder() {
    final Dialogs.Pane pane = Dialogs.newSavePane("Save the changes to Minimap first?");
    final List<String> clicked = givenClicksRecorded(pane);

    press(pane, KeyEvent.VK_ESCAPE);

    assertThat(pane.buttons().stream().map(JButton::getText))
        .as("Don't save at the left, then Cancel and Save at the right")
        .containsExactly("Don't save", "Cancel", "Save");
    assertThat(pane.verb().getText()).as("Enter saves").isEqualTo("Save");
    assertThat(clicked).as("Esc never drops the changes").containsExactly("Cancel");
  }

  @DisplayName("A refusal for what attachments name offers Show in Attachments; Esc closes it")
  @Test
  void refuseNamedShowsAttachments() {
    final Dialogs.Pane pane =
        Dialogs.newRefusePane(
            "Germany stays.", List.of("variable Axis", "triggerAttachment1 of Germans: players"));
    final List<String> clicked = givenClicksRecorded(pane);

    press(pane, KeyEvent.VK_ESCAPE);

    assertThat(pane.buttons().stream().map(JButton::getText))
        .as("Close, then the way to change what names it")
        .containsExactly("Close", "Show in Attachments");
    assertThat(pane.verb().getText())
        .as("Enter opens Attachments")
        .isEqualTo("Show in Attachments");
    assertThat(clicked).as("Esc only closes").containsExactly("Close");
  }

  @DisplayName("A long verb in its semibold font fits its button, not cut to \"Show in Attachme...\"")
  @Test
  void longVerbFits() throws UnsupportedLookAndFeelException {
    // given
    Theme.install();
    UIManager.setLookAndFeel(new FlatDarkLaf());

    // when
    final JButton verb =
        Dialogs.newRefusePane("Germany stays.", List.of("triggerAttachment1 of Germans: players"))
            .verb();

    // then
    assertThat(verb.getPreferredSize().width)
        .as("wide enough for the text in the font it is drawn in")
        .isGreaterThanOrEqualTo(
            verb.getFontMetrics(verb.getFont()).stringWidth(verb.getText())
                + verb.getInsets().left
                + verb.getInsets().right);
  }

  @DisplayName("A refusal named only by variables has no verb, as Attachments does not show them")
  @Test
  void refuseNamedOnlyVariables() {
    final Dialogs.Pane pane = Dialogs.newRefusePane("Germany stays.", List.of("variable Axis"));

    assertThat(pane.buttons().stream().map(JButton::getText))
        .as("a plain warn")
        .containsExactly("Close");
  }

  @DisplayName("Enter in an editable combo box, which keeps Enter for itself, chooses the verb")
  @Test
  void enterInEditableComboChoosesVerb() {
    final JComboBox<String> combo = new JComboBox<>(new String[] {"Germans", "Russians"});
    combo.setEditable(true);
    final Dialogs.Pane pane = Dialogs.newPane("", List.of(), combo, "Add", () -> true);
    final List<String> clicked = givenClicksRecorded(pane);
    Dialogs.fill(new JRootPane(), pane);

    combo
        .getActionMap()
        .get("enterPressed")
        .actionPerformed(new ActionEvent(combo, ActionEvent.ACTION_PERFORMED, ""));

    assertThat(clicked).as("the root pane's default button gets the Enter").containsExactly("Add");
  }

  @DisplayName("A text is kept readable, content gets the width it needs up to the screen")
  @Test
  void widthByContent() {
    final Dialogs.Pane text = Dialogs.newPane("Find again?", List.of(), null, "Find", () -> true);
    final Dialogs.Pane form = Dialogs.newPane("", List.of(), new JLabel("wide"), "Add", () -> true);

    final int textWidth = Dialogs.width(text, 900, 1440);
    final int formWidth = Dialogs.width(form, 900, 1440);
    final int formOnSmallScreen = Dialogs.width(form, 900, 800);

    assertThat(textWidth).as("a text wraps at 560 px").isEqualTo(560);
    assertThat(formWidth).as("content is not cut").isEqualTo(900);
    assertThat(formOnSmallScreen).as("but stays on the screen").isEqualTo(800);
  }

  @DisplayName("Pick returns the item of the row chosen, also with repeated labels and a search")
  @Test
  void pickMapsRowsToItems() {
    final List<Integer> items = IntStream.range(0, 14).boxed().toList();
    final AtomicReference<Optional<Integer>> picked = new AtomicReference<>(Optional.empty());
    final Dialogs.Pane pane =
        Dialogs.newPickPane(
            "Which?", items, i -> i < 12 ? "Same" : "Other " + i, "Pick", picked::set);
    final JList<?> list = givenComponent(pane.root(), JList.class);

    list.setSelectedIndex(3);
    final Optional<Integer> repeated = picked.get();
    givenComponent(pane.root(), JTextField.class).setText("other");
    list.setSelectedIndex(1);

    assertThat(repeated).as("the fourth of the same labels, not the first").contains(3);
    assertThat(picked.get()).as("the second row of the search's rows").contains(13);
  }

  private static <C extends Component> C givenComponent(
      final Component component, final Class<C> type) {
    return descendants(component).filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
  }

  private static Stream<Component> descendants(final Component component) {
    return Stream.concat(
        Stream.of(component),
        component instanceof Container container
            ? Stream.of(container.getComponents()).flatMap(DialogsTest::descendants)
            : Stream.empty());
  }

  private static List<String> givenClicksRecorded(final Dialogs.Pane pane) {
    final List<String> clicked = new ArrayList<>();
    pane.buttons().forEach(b -> b.addActionListener(e -> clicked.add(b.getText())));
    return clicked;
  }

  private static void press(final Dialogs.Pane pane, final int key) {
    final JComponent root = pane.root();
    final Object name =
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(key, 0));
    root.getActionMap()
        .get(name)
        .actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, ""));
  }

  private static List<String> givenTexts(final Component component) {
    final List<String> texts = new ArrayList<>();
    if (component instanceof JLabel label && label.isVisible() && !label.getText().isEmpty()) {
      texts.add(label.getText());
    }
    if (component instanceof Container container) {
      for (final Component child : container.getComponents()) {
        texts.addAll(givenTexts(child));
      }
    }
    return texts;
  }

  private static double contrast(final Color a, final Color b) {
    final double lighter = Math.max(luminance(a), luminance(b));
    final double darker = Math.min(luminance(a), luminance(b));
    return (lighter + 0.05) / (darker + 0.05);
  }

  private static double luminance(final Color color) {
    final double[] linear =
        Stream.of(color.getRed(), color.getGreen(), color.getBlue())
            .mapToDouble(c -> c / 255.0)
            .map(c -> c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4))
            .toArray();
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
  }
}
