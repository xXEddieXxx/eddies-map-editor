package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.Serial;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class FormTest {

  @DisplayName("A number field shows its digits as the files have them, without grouping")
  @Test
  void numbersWithoutGrouping() {
    final Locale before = Locale.getDefault();
    Locale.setDefault(Locale.GERMANY);
    try {
      final JSpinner number = Form.newNumber(2300, 10_000, value -> {});

      final String shown = ((JSpinner.DefaultEditor) number.getEditor()).getTextField().getText();

      assertThat(shown).as("no '2.300' on a German system").isEqualTo("2300");
    } finally {
      Locale.setDefault(before);
    }
  }

  @DisplayName("A dense form, eg a unit's many options, is clearly shorter")
  @Test
  void denseFormIsShorter() {
    // given the same rows in a usual and a dense form
    final Form usual = new Form(300);
    final Form dense = new Form(300).dense();
    for (final Form form : java.util.List.of(usual, dense)) {
      form.section("Combat");
      for (int i = 0; i < 10; i++) {
        form.number("Number " + i, new JSpinner());
        form.row(new javax.swing.JCheckBox("Flag " + i));
      }
      form.section("Movement").field("Name", new JTextField());
      form.end();
    }

    // when
    final int usualHeight = usual.getPreferredSize().height;
    final int denseHeight = dense.getPreferredSize().height;

    // then
    assertThat(denseHeight)
        .as("at least a sixth shorter than %s px", usualHeight)
        .isLessThan(usualHeight * 5 / 6);
  }

  @DisplayName("More options starts its text where the labels of the fields do")
  @Test
  void moreInLineWithFields() {
    final Form form = new Form(300);
    form.setBorder(null);
    form.field("Name", new JTextField()).more(givenId(), 1, f -> f.field("B", new JTextField()));
    form.end();
    form.setSize(400, 600);
    layOut(form);

    final AbstractButton toggle = buttonIn(form, "More options");
    final int text =
        SwingUtilities.convertPoint(toggle.getParent(), toggle.getLocation(), form).x
            + toggle.getInsets().left;

    assertThat(text).as("the text of the toggle at the form's edge, as the labels").isZero();
  }

  @DisplayName("A text field shows the start of a long text, not its end")
  @Test
  void textFieldShowsItsStart() {
    final JTextField field = new JTextField("You have declared war on the True Neutrals!");

    new Form(300).field("Message", field);

    assertThat(field.getCaretPosition()).as("the caret, and so the view, at the start").isZero();
  }

  @DisplayName("More options tells how many it holds, and stays open for the next form of its id")
  @Test
  void moreKeepsItsOpenState() {
    final String id = givenId();
    final Form first = givenFormWithMore(id);
    final AbstractButton toggle = buttonIn(first, "More options");

    toggle.doClick();
    final Form next = givenFormWithMore(id);

    assertThat(buttonIn(next, "More options").getText())
        .as("open, with the count")
        .isEqualTo("▾ More options (2)");
    assertThat(fieldLabelled(next, "Repairs").getParent().isVisible())
        .as("its fields show")
        .isTrue();
    assertThat(buttonIn(givenFormWithMore(givenId()), "More options").getText())
        .as("another id starts closed")
        .isEqualTo("▸ More options (2)");
  }

  @DisplayName("A closed More options keeps its fields in the form, only hidden")
  @Test
  void closedMoreKeepsFields() {
    final Form form = givenFormWithMore(givenId());

    final Component field = fieldLabelled(form, "Repairs");

    assertThat(field.getParent().isVisible()).as("found by its label, in a hidden part").isFalse();
  }

  @DisplayName("A fold of nothing is not shown; one counting its fields says how many are set")
  @Test
  void foldHiddenWhenEmpty() {
    final Form form = new Form(300);

    form.more(givenId(), 0, inner -> {});
    form.fold(givenId(), "Rules", 0, inner -> {});
    form.fold(
        givenId(),
        "Terrain",
        2,
        inner -> inner.field("Forest", new JTextField()).row(new JLabel("Hills")).number(
            "Moves", Form.newNumber(1, 9, value -> {})));
    form.fold(givenId(), "Sea zone", 0, inner -> inner.field("Blockade", new JTextField()));

    assertThat(
            Stream.of(form.getComponents())
                .flatMap(c -> c instanceof Container inner ? Stream.of(inner.getComponents()) : Stream.empty())
                .filter(AbstractButton.class::isInstance)
                .map(c -> ((AbstractButton) c).getText()))
        .as("no '▸ More options (0)' and no '▸ Rules (0)'; the count, and what is set, behind it")
        .containsExactly("▸ Terrain (3) · 2 set", "▸ Sea zone (1)");
  }

  @DisplayName("More options with a label of its own shows that label")
  @Test
  void moreWithLabel() {
    final Form form = new Form(300);

    form.more(givenId(), "Sea zone", inner -> inner.field("Blockade", new JTextField()));

    assertThat(buttonIn(form, "▸").getText()).as("the label, closed").isEqualTo("▸ Sea zone");
  }

  @DisplayName("Sections after columns() go two in a row on a wide form, one below another on a narrow")
  @Test
  void columnsByWidth() {
    final Form wide = givenTwoSections();
    final Form narrow = givenTwoSections();

    wide.setSize(900, 2000);
    layOut(wide);
    narrow.setSize(700, 2000);
    layOut(narrow);

    assertThat(secondSection(wide).getY()).as("side by side at 900 px").isEqualTo(0);
    assertThat(secondSection(narrow).getY()).as("below the first at 700 px").isGreaterThan(0);
  }

  @DisplayName("A form that grows past 820 px asks its scroll pane for a new height afterwards")
  @Test
  void columnsRevalidateWhenWidened() throws Exception {
    final AtomicInteger invalidated = new AtomicInteger();
    final Form form = givenTwoSectionsIn(givenParentCounting(invalidated));
    // a scroll pane sizes its form while laying it out, which drops this first invalidation;
    // both on the Swing thread, so the form's later one cannot run before the count is reset
    SwingUtilities.invokeAndWait(
        () -> {
          form.setSize(900, 2000);
          invalidated.set(0);
        });

    SwingUtilities.invokeAndWait(() -> {});

    assertThat(invalidated)
        .as("laid out again after the resize, for the height of two columns")
        .hasPositiveValue();
  }

  /** A parent like a laid out scroll pane: valid, and counting what invalidates it. */
  private static JPanel givenParentCounting(final AtomicInteger invalidated) {
    return new JPanel() {
      @Serial private static final long serialVersionUID = 1L;

      @Override
      public boolean isValid() {
        return true;
      }

      @Override
      public void invalidate() {
        invalidated.incrementAndGet();
      }
    };
  }

  private static Form givenTwoSectionsIn(final JPanel parent) {
    final Form form = givenTwoSections();
    parent.add(form);
    return form;
  }

  @DisplayName("A number field is 96 px wide")
  @Test
  void numbersAreNarrow() {
    final JSpinner spinner = Form.newNumber(5, 99, value -> {});

    new Form(300).number("PUs per turn", spinner);

    assertThat(spinner.getPreferredSize().width).as("the rule's width").isEqualTo(96);
  }

  private static String givenId() {
    return "test-" + UUID.randomUUID();
  }

  private static Form givenFormWithMore(final String id) {
    final Form form = new Form(300);
    form.field("Name", new JTextField())
        .more(
            id,
            2,
            inner ->
                inner
                    .field("Repairs", new JTextField())
                    .number("Bombing", Form.newNumber(0, 9, v -> {})))
        .end();
    return form;
  }

  private static Form givenTwoSections() {
    final Form form = new Form(300);
    form.title("Germany").columns().section("Land").field("A", new JTextField());
    return form.section("Sea").field("B", new JTextField()).end();
  }

  private static Component secondSection(final Form form) {
    return fieldLabelled(form, "B").getParent();
  }

  private static void layOut(final Container container) {
    container.doLayout();
    Stream.of(container.getComponents())
        .filter(Container.class::isInstance)
        .forEach(c -> layOut((Container) c));
  }

  private static AbstractButton buttonIn(final Container container, final String start) {
    return components(container)
        .filter(AbstractButton.class::isInstance)
        .map(AbstractButton.class::cast)
        .filter(b -> b.getText().contains(start))
        .findFirst()
        .orElseThrow();
  }

  private static Component fieldLabelled(final Container container, final String label) {
    return components(container)
        .filter(JLabel.class::isInstance)
        .map(JLabel.class::cast)
        .filter(l -> label.equals(l.getText()))
        .findFirst()
        .orElseThrow()
        .getLabelFor();
  }

  private static Stream<Component> components(final Container container) {
    return Stream.of(container.getComponents())
        .flatMap(
            c ->
                Stream.concat(
                    Stream.of(c),
                    c instanceof Container inner ? components(inner) : Stream.empty()));
  }

  @DisplayName("A row wider than the form keeps the fields at their height and the form's width;"
      + " a note wraps to the form")
  @Test
  void wideRowKeepsFields() {
    final JScrollPane text = new JScrollPane(new JTextArea(5, 20));
    final Form form = new Form(600);
    form.subtitle("a subtitle far wider than the form ".repeat(10))
        .field("Text", text)
        .note("a note far wider than the form ".repeat(10))
        .end();

    form.setSize(300, 2000);
    layOut(form);
    final JLabel note = labelStarting(form, "<html>a note");

    final int inside = 300 - form.getInsets().left - form.getInsets().right;
    assertThat(text.getHeight()).as("not collapsed to its minimum height")
        .isEqualTo(text.getPreferredSize().height);
    assertThat(text.getWidth()).as("as wide as the form").isEqualTo(inside);
    assertThat(note.getWidth()).as("the note fits the form").isLessThanOrEqualTo(inside);
    assertThat(note.getHeight()).as("wrapped to several lines")
        .isGreaterThan(3 * note.getFontMetrics(note.getFont()).getHeight());
  }

  @DisplayName("After end() the form is no taller than its rows and its bottom margin")
  @Test
  void endAddsNoHeight() {
    final Form form = new Form(300);
    form.field("Name", new JTextField());
    final int before = form.getPreferredSize().height;

    form.end();

    assertThat(form.getPreferredSize().height)
        .as("what follows the form starts right below it").isEqualTo(before);
  }

  @DisplayName("The remove button's text starts where the fields start")
  @Test
  void removeLinesUpWithFields() {
    final JTextField name = new JTextField();
    final Form form = new Form(300);
    form.field("Name", name).remove("Remove nation", () -> {}).end();

    form.setSize(300, 600);
    layOut(form);
    final AbstractButton remove = buttonIn(form, "Remove nation");
    final int textX =
        SwingUtilities.convertPoint(remove, remove.getInsets().left, 0, form).x;

    assertThat(textX).as("in line with the field, not indented by the button's margin")
        .isEqualTo(SwingUtilities.convertPoint(name, 0, 0, form).x);
  }

  private static JLabel labelStarting(final Container container, final String start) {
    return components(container)
        .filter(JLabel.class::isInstance)
        .map(JLabel.class::cast)
        .filter(l -> l.getText().startsWith(start))
        .findFirst()
        .orElseThrow();
  }
}
