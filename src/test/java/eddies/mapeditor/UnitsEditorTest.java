package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.swing.AbstractButton;
import javax.swing.JFormattedTextField;
import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UnitsEditorTest {
  @TempDir Path folder;

  private MapProject givenG40() throws Exception {
    final Path xml = folder.resolve("ww2global40.xml");
    try (InputStream original =
        UnitsEditorTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  /**
   * The editor showing the unit, of its folds only the section's "More … options" open: folds
   * stay as they are for the session, so each test sets them.
   */
  private static UnitsEditor givenEditor(
      final MapProject project, final String unit, final String section) throws Exception {
    final UnitsEditor editor = new UnitsEditor(project);
    final Method choose = UnitsEditor.class.getDeclaredMethod("choose", String.class);
    choose.setAccessible(true);
    choose.invoke(editor, unit);
    final String wanted = "More " + section.toLowerCase(Locale.ROOT) + " options";
    for (final AbstractButton fold : collect(editor.page(), AbstractButton.class)) {
      final String text = fold.getText();
      if ((text.startsWith("▸ ") || text.startsWith("▾ "))
          && text.startsWith("▾ ") != text.contains(wanted)) {
        fold.doClick();
      }
    }
    return editor;
  }

  private static JTextField givenFieldShowing(final Component in, final String text) {
    return collect(in, JTextField.class).stream()
        .filter(f -> text.equals(f.getText()))
        .findFirst()
        .orElseThrow();
  }

  private static <T> List<T> collect(final Component in, final Class<T> type) {
    final List<T> found = new ArrayList<>();
    collect(in, type, found);
    return found;
  }

  private static <T> void collect(
      final Component component, final Class<T> type, final List<T> found) {
    // what a closed fold holds is in the form, but not shown
    if (!component.isVisible()) {
      return;
    }
    if (type.isInstance(component)) {
      found.add(type.cast(component));
    }
    if (component instanceof Container container) {
      for (final Component child : container.getComponents()) {
        collect(child, type, found);
      }
    }
  }

  private static List<String> givenButtonTexts(final Component in) {
    return collect(in, AbstractButton.class).stream().map(AbstractButton::getText).toList();
  }

  @DisplayName("A field typed in twice takes the second text, also when it is the first value")
  @Test
  void fieldTakesEachText() throws Exception {
    final MapProject project = givenG40();
    final List<String> afterFirst = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final JTextField kind =
                givenFieldShowing(
                    givenEditor(project, "fighter", "Air and anti-air").page(), "AA");
            kind.setText("flak");
            kind.postActionEvent();
            afterFirst.add(MapXml.option(project.game(), "unitAttachment", "fighter", "typeAA")
                .orElse(""));
            kind.setText("AA");
            kind.postActionEvent();
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(afterFirst).as("the first text taken").containsExactly("flak");
    assertThat(MapXml.option(project.game(), "unitAttachment", "fighter", "typeAA"))
        .as("back to TripleA's default AA, the option is left out again")
        .isEmpty();
  }

  @DisplayName("An emptied number with a blank word is -1, TripleA's default, so it is left out")
  @Test
  void emptiedNumberIsLeftOut() throws Exception {
    final MapProject project = givenG40();
    final List<Object> shown = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final JSpinner capacity =
                (JSpinner)
                    collect(givenEditor(project, "transport", "Sea").page(), JLabel.class).stream()
                        .filter(l -> "Transport capacity".equals(l.getText()))
                        .findFirst()
                        .orElseThrow()
                        .getLabelFor();
            final JFormattedTextField text =
                ((JSpinner.DefaultEditor) capacity.getEditor()).getTextField();
            shown.add(text.getText());
            text.setText("");
            text.commitEdit();
            shown.add(capacity.getValue());
            shown.add(text.getText());
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(shown)
        .as("5 at first; emptied it is -1, shown empty")
        .containsExactly("5", -1, "");
    assertThat(MapXml.option(project.game(), "unitAttachment", "transport", "transportCapacity"))
        .as("-1 is TripleA's default: the option is left out")
        .isEmpty();
  }

  @DisplayName("A support reads as one sentence")
  @Test
  void supportReadsAsSentence() throws Exception {
    final MapProject project = givenG40();

    final String sentence =
        UnitsEditor.supportSentence(project.game(), "fighter", "supportAttachmentFighter");

    assertThat(sentence)
        .as("G40's fighter: bonus 1, strength, number 1, allied tactical_bomber, offence")
        .isEqualTo("+1 attack to 1 allied tactical_bomber when attacking");
  }

  @DisplayName("A support to allied units reads green, one to enemy units red, one to both plain")
  @Test
  void supportReadsInItsSidesColor() throws Exception {
    // given G40's fighter, which supports allied tactical bombers
    final MapProject project = givenG40();
    final String support = "supportAttachmentFighter";
    final UnaryOperator<String> faction =
        value ->
            UnitsEditor.supportColor(
                    UnitsXml.withSupportOption(
                        project.game(), "fighter", support, "faction", value),
                    "fighter",
                    support)
                .map(color -> color.equals(Theme.getSuccessColor()) ? "green" : "red")
                .orElse("plain");

    // when, then
    assertThat(List.of(faction.apply("allied"), faction.apply("enemy"), faction.apply("allied:enemy")))
        .as("the theme's success and danger colors; the sentence keeps the words")
        .containsExactly("green", "red", "plain");
    assertThat(Theme.getSuccessColor()).as("two colors").isNotEqualTo(Theme.getDangerColor());
  }

  @DisplayName("The numbers and the kind sit beside the picture's name; Gives support follows"
      + " Combat; Air and anti-air says when targeted fire comes")
  @Test
  void topRowAndSectionOrder() throws Exception {
    // given
    final MapProject project = givenG40();
    final List<String> labels = new ArrayList<>();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "fighter", "none").page();
            labels.addAll(givenLabels(page));
            final JLabel name =
                collect(page, JLabel.class).stream()
                    .filter(l -> "fighter".equals(l.getText()))
                    .findFirst()
                    .orElseThrow();
            final JLabel cost =
                collect(page, JLabel.class).stream()
                    .filter(l -> "Cost".equals(l.getText()))
                    .findFirst()
                    .orElseThrow();
            // the header's text column holds the name, and the numbers and kind below it
            seen.add(SwingUtilities.isDescendingFrom(cost, name.getParent()));
            seen.add(
                collect(name.getParent(), JRadioButton.class).stream()
                    .map(AbstractButton::getText)
                    .toList());
            seen.add(
                collect(page, AbstractButton.class).stream()
                    .filter(b -> "?".equals(b.getText()))
                    .map(b -> b.getAccessibleContext().getAccessibleName())
                    .anyMatch(spoken -> spoken != null && spoken.contains("Air and anti-air")));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    // then
    assertThat(seen)
        .as("Cost and the kind in the header beside the picture; a \"?\" on Air and anti-air")
        .containsExactly(true, List.of("Land", "Sea", "Air"), true);
    assertThat(labels.indexOf("Gives support"))
        .as("Gives support comes right after Combat, before Movement")
        // the last Movement: the first is the number's label at the top
        .isBetween(labels.indexOf("Combat"), labels.lastIndexOf("Movement"));
    assertThat(UnitOptions.SECTION_HELP.get("Air and anti-air"))
        .as("what the \"?\" says")
        .contains("before bombardment and before first strike");
  }

  @DisplayName("Combat shows its common options and those set; the rest behind a fold row")
  @Test
  void combatShowsCommonUntilShowAll() throws Exception {
    final MapProject project = givenG40();
    final List<List<String>> buttons = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            buttons.add(givenButtonTexts(givenEditor(project, "infantry", "none").page()));
            buttons.add(givenButtonTexts(givenEditor(project, "infantry", "Combat").page()));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(buttons.getFirst())
        .as("infantry: a common option, one set, the fold with the rest, not a rare option")
        .contains(
            "Fires first: its hits take no shot back",
            "Gets the support of artillery",
            "▸ More combat options (9)")
        .doesNotContain("Dies when it hits");
    assertThat(buttons.get(1))
        .as("the fold open: every combat option")
        .contains("Dies when it hits", "▾ More combat options (9)");
  }

  private static List<String> givenLabels(final Component in) {
    return collect(in, JLabel.class).stream().map(JLabel::getText).toList();
  }

  @DisplayName("The sections not for a land unit fold into one row; the add actions are buttons")
  @Test
  void otherKindsFoldIntoOneRow() throws Exception {
    final MapProject project = givenG40();
    final List<String> buttons = new ArrayList<>();
    final List<String> labels = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "infantry", "none").page();
            buttons.addAll(givenButtonTexts(page));
            labels.addAll(givenLabels(page));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(buttons)
        .as("one fold for the sections of other kinds, not a row of links")
        .filteredOn(text -> text.startsWith("▸ Not for"))
        .hasSize(1)
        .allMatch(text -> text.matches("▸ Not for land units \\(\\d+\\)"));
    assertThat(labels)
        .as("its sections are folded away")
        .contains("Combat", "Movement")
        .doesNotContain("Sea", "Bombing");
    assertThat(buttons)
        .as("every add action is a '+ Add …' button, '…' where a dialog follows; the list's too")
        .contains("+ Add support", "+ Add option…", "+ Add unit…")
        .noneMatch(text -> text.startsWith("Show all"));
  }

  @DisplayName("Search options shows only the options it finds, by label or TripleA's name,"
      + " under their sections, also from folds")
  @Test
  void searchFindsOptionsInFolds() throws Exception {
    final MapProject project = givenG40();
    final List<List<String>> found = new ArrayList<>();
    final List<List<String>> sections = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "infantry", "none").page();
            final JTextField search =
                collect(page, JTextField.class).stream()
                    .filter(
                        f -> "Search options".equals(f.getAccessibleContext().getAccessibleName()))
                    .findFirst()
                    .orElseThrow();
            for (final String typed : List.of("bombard", "isSuicideOnHit", "no such option", "")) {
              search.setText(typed);
              found.add(givenButtonTexts(page));
              sections.add(givenLabels(page));
            }
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(found.get(0))
        .as("by its label, from the folded Sea section of a land unit")
        .contains("Can bombard a shore")
        .doesNotContain("Fires first: its hits take no shot back")
        .noneMatch(text -> text.startsWith("▸"));
    assertThat(sections.get(0)).as("under its section's title").contains("Sea", "Bombard strength");
    assertThat(found.get(1))
        .as("by TripleA's name, from Combat's fold")
        .contains("Dies when it hits")
        .doesNotContain("Can bombard a shore");
    assertThat(sections.get(2)).as("nothing found says so").contains("<html>No option matches.</html>");
    assertThat(found.get(3))
        .as("emptied, the sections and folds are back")
        .contains("Fires first: its hits take no shot back")
        .anyMatch(text -> text.startsWith("▸ Not for land units"));
  }

  @DisplayName("A number whose -1 means none or all shows a grey word, never -1")
  @Test
  void minusOneShowsAsWord() throws Exception {
    final MapProject project = givenG40();
    final Map<String, List<String>> shown = new HashMap<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "infantry", "none").page();
            collect(page, JTextField.class).stream()
                .filter(f -> "Search options".equals(f.getAccessibleContext().getAccessibleName()))
                .findFirst()
                .orElseThrow()
                .setText("a");
            for (final JLabel label : collect(page, JLabel.class)) {
              if (label.getLabelFor() instanceof JSpinner spinner) {
                final JTextField text = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
                shown.put(
                    label.getText(),
                    List.of(
                        text.getText(),
                        String.valueOf(
                            text.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT))));
              }
            }
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(shown.values()).as("no field shows a raw -1").noneMatch(v -> v.getFirst().equals("-1"));
    assertThat(shown)
        .as("empty with the word for what TripleA takes -1 as")
        .containsEntry("Planes it lets intercept", List.of("", "no limit"))
        .containsEntry("Anti-air shots", List.of("", "no limit"))
        .containsEntry("Damage until it stops working", List.of("", "never"));
    assertThat(UnitOptions.blankWord(UnitOptions.named("maxScrambleCount")))
        .as("the word of another number TripleA starts at -1")
        .contains("no limit");
  }

  @DisplayName("The list shows a row for each unit, its name as the row's title")
  @Test
  void listShowsEveryUnit() throws Exception {
    final MapProject project = givenG40();
    final List<String> titles = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final Component page = new UnitsEditor(project).page();
          page.setSize(1240, 840);
          givenLaidOut(page);
          @SuppressWarnings("unchecked")
          final JList<String> list = collect(page, JList.class).getFirst();
          for (int i = 0; i < list.getModel().getSize(); i++) {
            final Component row =
                list.getCellRenderer()
                    .getListCellRendererComponent(
                        list, list.getModel().getElementAt(i), i, false, false);
            titles.add(collect(row, JLabel.class).getFirst().getText());
          }
        });

    assertThat(titles)
        .as("every G40 unit, by name, in the game's order")
        .hasSize(19)
        .containsExactlyElementsOf(MapXml.unitTypes(project.game()));
  }

  @DisplayName("Draw it on the map, unticked, lists the unit type in dont_draw_units, one step")
  @Test
  void hidesUnitOnMap() throws Exception {
    final MapProject project = givenG40();
    project.setMapProperty(MapFiles.UNDRAWN_UNITS, "factory");

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            collect(givenEditor(project, "infantry", "").page(), JCheckBox.class).stream()
                .filter(box -> "Draw it on the map".equals(box.getText()))
                .findFirst()
                .orElseThrow()
                .doClick();
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(project.getFiles().properties().getProperty(MapFiles.UNDRAWN_UNITS))
        .as("after the one there, at commas without spaces, as the game splits them")
        .isEqualTo("factory,infantry");
    project.undo();
    assertThat(project.getFiles().listed(MapFiles.UNDRAWN_UNITS))
        .as("one undo draws it again")
        .containsExactly("factory");
  }

  @DisplayName("Under In each nation's color, a link to Nations, where the colors are set")
  @Test
  void nationTintLinksToNations() throws Exception {
    final MapProject project = givenG40();
    final List<List<String>> texts = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            texts.add(givenButtonTexts(givenEditor(project, "infantry", "").page()));
            project.tintUnits(List.of("Germans"));
            texts.add(givenButtonTexts(givenEditor(project, "infantry", "").page()));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(texts.get(0)).as("no nation's units tinted yet").doesNotContain("Nations ›");
    assertThat(texts.get(1))
        .as("the tint follows the nation's color")
        .contains("In each nation's color", "Nations ›");
  }

  private static void givenLaidOut(final Component component) {
    if (component instanceof Container container) {
      container.doLayout();
      for (final Component child : container.getComponents()) {
        givenLaidOut(child);
      }
    }
  }
}
