package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
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
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.swing.AbstractButton;
import javax.swing.JFormattedTextField;
import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;

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

  @DisplayName("The list shows a row for each unit, its name as the row's title, its picture before")
  @Test
  void listShowsEveryUnit() throws Exception {
    final MapProject project = givenG40();
    final List<String> titles = new ArrayList<>();
    final List<Integer> iconHeights = new ArrayList<>();

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
            collect(row, JLabel.class).stream()
                .filter(label -> label.getIcon() != null)
                .findFirst()
                .ifPresent(label -> iconHeights.add(label.getIcon().getIconHeight()));
          }
        });

    assertThat(iconHeights)
        .as("each row has its picture, or a blank of the size, at most 32 px high")
        .hasSize(19)
        .allMatch(height -> height > 0 && height <= 32);

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
  @DisplayName("A support written for each unit of a variable shows for each, read-only")
  @Test
  void supportForEachUnit() throws Exception {
    // given: as 1941 Global Command Decision gives every fighter a support
    final MapProject project = givenG40();
    project.edit(g -> GameRulesXml.withVariable(g, "Planes", List.of("fighter", "bomber")));
    project.edit(
        g ->
            AttachmentsXml.withNewAttachment(
                g, "UnitSupportAttachment", "supportAttachment_@Planes@_Escort", "@Planes@"));
    project.edit(
        g -> {
          final int last = AttachmentsXml.attachments(g).size() - 1;
          return AttachmentsXml.withAttachment(
              g,
              last,
              Builders.toBuilder(AttachmentsXml.attachments(g).get(last))
                  .foreach("$Planes$")
                  .options(
                      List.of(
                          Option.builder().name("unitType").value("infantry").build(),
                          Option.builder().name("side").value("offence").build(),
                          Option.builder().name("faction").value("allied").build(),
                          Option.builder().name("bonus").value("2").build(),
                          Option.builder().name("number").value("1").build(),
                          Option.builder().name("dice").value("strength").build()))
                  .build());
        });
    final List<String> labels = new ArrayList<>();
    final List<String> buttons = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "bomber", "none").page();
            labels.addAll(givenLabels(page));
            buttons.addAll(givenButtonTexts(page));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    // then
    assertThat(UnitsXml.supports(project.game(), "bomber"))
        .as("the bomber's support, as the game reads it")
        .containsExactly("supportAttachment_bomber_Escort");
    assertThat(labels)
        .as("its sentence, and where it is changed")
        .anyMatch(
            text -> text != null && text.contains("+2 attack to 1 allied infantry when attacking"))
        .anyMatch(text -> text != null && text.contains("Written for each unit of $Planes$ in"));
    assertThat(buttons).as("not edited or removed here").doesNotContain("Edit", "Close");
  }

  @DisplayName("Value (tuv) sits in the top row beside Cost, blank for its cost")
  @Test
  void valueBesideCost() throws Exception {
    final MapProject project = givenG40();
    final List<String> labels = new ArrayList<>();
    final List<String> shown = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "fighter", "none").page();
            labels.addAll(givenLabels(page));
            final JLabel value =
                collect(page, JLabel.class).stream()
                    .filter(l -> "Value".equals(l.getText()))
                    .findFirst()
                    .orElseThrow();
            final JTextField text =
                ((JSpinner.DefaultEditor) ((JSpinner) value.getLabelFor()).getEditor())
                    .getTextField();
            shown.add(text.getText());
            shown.add(
                String.valueOf(text.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT)));
            ((JSpinner) value.getLabelFor()).setValue(12);
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(labels.indexOf("Value"))
        .as("right after Cost")
        .isEqualTo(labels.indexOf("Cost") + 1);
    assertThat(shown).as("empty, with the word for -1").containsExactly("", "its cost");
    assertThat(MapXml.option(project.game(), "unitAttachment", "fighter", "tuv"))
        .as("typed, the unit's tuv")
        .contains("12");
  }

  private static Optional<String> givenResources(final MapProject project) {
    return MapXml.option(project.game(), "unitAttachment", "factory_major", "createsResourcesList");
  }

  @DisplayName("Resources it makes each turn: a count and a resource under Production, 0 for none")
  @Test
  void resourcesItMakes() throws Exception {
    final MapProject project = givenG40();
    project.edit(
        g ->
            UnitsXml.withNewOption(
                g,
                "factory_major",
                Option.builder().name("createsResourcesList").value("-1:PUs").build()));
    final List<String> labels = new ArrayList<>();
    final List<Object> shown = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "factory_major", "none").page();
            labels.addAll(givenLabels(page));
            final JLabel label =
                collect(page, JLabel.class).stream()
                    .filter(l -> "Resources it makes each turn".equals(l.getText()))
                    .findFirst()
                    .orElseThrow();
            final JSpinner count = collect(label.getLabelFor(), JSpinner.class).getFirst();
            final JComboBox<?> resource = collect(label.getLabelFor(), JComboBox.class).getFirst();
            shown.add(count.getValue());
            shown.add(resource.getSelectedItem());
            count.setValue(2);
            shown.add(givenResources(project));
            count.setValue(0);
            shown.add(givenResources(project));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(labels)
        .as("the stacking limits under Miscellaneous")
        .contains("Miscellaneous", "Stacking limits");
    assertThat(shown)
        .as("-1 PUs of upkeep; 2 PUs typed; 0 leaves the option out")
        .containsExactly(-1, "PUs", Optional.of("2:PUs"), Optional.empty());
  }

  @DisplayName("A unit whose options are written for each unit of a variable shows them, locked")
  @Test
  void loopMadeUnitIsShownButNotChanged() throws Exception {
    // given
    final MapProject project = givenG40();
    project.edit(UnitsXmlTest::givenWalkersLoop);
    final List<JSpinner> numbers = new ArrayList<>();
    final List<String> buttons = new ArrayList<>();
    final List<JLabel> labels = new ArrayList<>();
    final List<String> enabled = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "artillery", "none").page();
            numbers.addAll(collect(page, JSpinner.class));
            buttons.addAll(givenButtonTexts(page));
            labels.addAll(collect(page, JLabel.class));
            collect(page, AbstractButton.class).stream()
                .filter(AbstractButton::isEnabled)
                .map(AbstractButton::getText)
                .forEach(enabled::add);
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    // then
    assertThat(numbers)
        .as("the loop's attack shows")
        .anyMatch(number -> Integer.valueOf(4).equals(number.getValue()));
    assertThat(numbers)
        .as("only the cost, which a production rule gives, is changed here")
        .filteredOn(JSpinner::isEnabled)
        .extracting(JSpinner::getToolTipText)
        .containsExactly("PUs to buy one");
    assertThat(numbers)
        .as("a field that is off says why")
        .filteredOn(number -> !number.isEnabled())
        .allMatch(number -> number.getToolTipText().startsWith("Can't be changed here"));
    assertThat(labels)
        .as("the label of a field that is off says why too")
        .filteredOn(label -> "Attack".equals(label.getText()))
        .allMatch(label -> label.getToolTipText().startsWith("Can't be changed here"));
    assertThat(buttons).as("the way to where the loop is changed").contains("Attachments ›");
    assertThat(buttons).as("the buttons that are off are there").contains("+ Add option…", "Land");
    assertThat(enabled)
        .as("its support is an attachment of its own and stays to change; no option is added")
        .contains("+ Add support")
        .doesNotContain("+ Add option…", "Land", "Sea", "Air");
  }

  @DisplayName("A change made while another tool shows reaches Units when it shows again")
  @Test
  void hiddenUnitsWaitWithShowingAChange() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "the window needs a screen");
    // given: Units as a card of a window that shows another card, as while Production shows
    final MapProject project = givenG40();
    final List<Boolean> showsSeven = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          // far off the screen, and never focused
          final JWindow window = new JWindow();
          window.setFocusableWindowState(false);
          window.setBounds(-20000, -20000, 900, 700);
          try {
            final UnitsEditor editor = givenEditor(project, "artillery", "none");
            final CardLayout cards = new CardLayout();
            final JPanel workspace = new JPanel(cards);
            workspace.add(new JPanel(), "Production");
            workspace.add(editor.page(), "Units");
            window.add(workspace);
            window.setVisible(true);
            project.edit(g -> MapXml.withUnitOption(g, "artillery", "attack", "7"));
            showsSeven.add(numbersOf(editor).contains(7));
            cards.show(workspace, "Units");
            showsSeven.add(numbersOf(editor).contains(7));
            project.edit(g -> MapXml.withUnitOption(g, "artillery", "attack", "8"));
            showsSeven.add(numbersOf(editor).contains(7));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(showsSeven)
        .as("not built anew while hidden, which would slow every step; anew when it shows; at"
            + " once while it shows")
        .containsExactly(false, true, false);
  }

  private static List<Object> numbersOf(final UnitsEditor editor) {
    return collect(editor.page(), JSpinner.class).stream().map(JSpinner::getValue).toList();
  }

  @DisplayName("A unit that costs other resources shows them under its name and in the tool tip")
  @Test
  void costInOtherResources() throws Exception {
    // given: artillery for 4 PUs and 5 Fame, then for 5 Fame alone
    final MapProject project = givenG40();
    final List<String> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            project.edit(g -> ProductionXml.withCost(g, "buyArtillery", "Fame", 5));
            final Component both = givenEditor(project, "artillery", "none").page();
            seen.add(labelEndingIn(both, "Fame"));
            seen.add(
                collect(both, JSpinner.class).stream()
                    .map(JSpinner::getToolTipText)
                    .filter(tip -> tip != null && tip.startsWith("PUs to buy one"))
                    .findFirst()
                    .orElse(""));
            project.edit(g -> ProductionXml.withCost(g, "buyArtillery", MapXml.PUS, 0));
            final Component fameOnly = givenEditor(project, "artillery", "none").page();
            seen.add(labelEndingIn(fameOnly, "Fame"));
            seen.add(
                collect(fameOnly, JLabel.class).stream()
                    .filter(label -> "–".equals(label.getText()))
                    .map(JLabel::getToolTipText)
                    .findFirst()
                    .orElse(""));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    // then
    assertThat(seen)
        .as("the line under the name and the Cost field's tool tip, for both")
        .containsExactly(
            "Land unit · 2-2-1 · 4 PUs + 5 Fame",
            "PUs to buy one; also 5 Fame, changed in Production",
            "Land unit · 2-2-1 · 5 Fame",
            "Costs no PUs: 5 Fame, changed in Production");
  }

  private static String labelEndingIn(final Component in, final String end) {
    return givenLabels(in).stream()
        .filter(text -> text != null && text.endsWith(end) && text.contains(" · "))
        .findFirst()
        .orElse("");
  }
}
