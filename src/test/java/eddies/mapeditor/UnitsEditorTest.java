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

  @DisplayName("A unit's tool tip is the game's once loaded, and follows a text changed in Texts")
  @Test
  void showsTheGamesToolTip() throws Exception {
    // given
    final MapProject project = givenG40();
    final UnitsEditor editor = givenEditor(project, "infantry", "Combat");
    final Optional<String> atFirst = editor.gameToolTip("infantry");

    // when: loaded in the background, then the unit gets a text of its own in Texts
    final Optional<String> loaded = givenLoaded(editor, "infantry");
    project.setText(MapTexts.Kind.TOOLTIPS, "tooltip.unit.infantry", "<i>Foot soldiers</i>");
    final Optional<String> afterText = editor.gameToolTip("infantry");

    // then
    assertThat(atFirst).as("the list's own line until it is loaded").isEmpty();
    assertThat(loaded)
        .as("the name as the game writes it, TripleA's stats")
        .hasValueSatisfying(
            tip -> assertThat(tip).startsWith("<html><b>Infantry</b><br />").contains(" | "));
    assertThat(afterText)
        .as("the new text at once, as a text step keeps the game")
        .hasValueSatisfying(tip -> assertThat(tip).contains("<i>Foot soldiers</i>"));
    assertThat(collect(editor.page(), JLabel.class).stream().map(JLabel::getText))
        .as("a line to its text in Texts, now its own")
        .contains("Its tool tip has its own text in");
  }

  private static Optional<String> givenLoaded(final UnitsEditor editor, final String unit)
      throws Exception {
    final List<Optional<String>> tip = new ArrayList<>(List.of(Optional.empty()));
    for (int tries = 0; tries < 200 && tip.getFirst().isEmpty(); tries++) {
      Thread.sleep(50);
      SwingUtilities.invokeAndWait(() -> tip.set(0, editor.gameToolTip(unit)));
    }
    return tip.getFirst();
  }

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
    assertThat(buttons)
        .as("the sections of no kind, Production and Special, in a fold of their own")
        .filteredOn(text -> text.startsWith("▸ More sections"))
        .hasSize(1);
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
        .doesNotContain("+ Add option…", "+ Add a unit it uses up", "Land", "Sea", "Air");
    assertThat(buttons).as("what it uses up is shown, off").contains("+ Add a unit it uses up");
  }

  @DisplayName("Change for this unit only gives a loop's unit its own attachment: one step, unlocked")
  @Test
  void loopMadeUnitIsChangedAloneAfterTheButton() throws Exception {
    // given
    final MapProject project = givenG40();
    project.edit(UnitsXmlTest::givenWalkersLoop);
    final org.triplea.map.data.elements.Game loop = project.game();
    final List<JSpinner> numbers = new ArrayList<>();
    final List<String> buttons = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "artillery", "none").page();
            collect(page, AbstractButton.class).stream()
                .filter(button -> "Change for this unit only".equals(button.getText()))
                .findFirst()
                .orElseThrow()
                .doClick();
            numbers.addAll(collect(page, JSpinner.class));
            buttons.addAll(givenButtonTexts(page));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });
    final org.triplea.map.data.elements.Game own = project.game();

    // then
    assertThat(UnitsXml.loop(own, "artillery")).as("its own attachment now").isEmpty();
    assertThat(UnitsXml.options(own, "artillery"))
        .as("with the loop's values")
        .extracting(o -> o.getName() + "=" + o.getValue())
        .containsExactly("attack=4", "hitPoints=2");
    assertThat(UnitsXml.loop(own, "infantry")).as("the loop's other unit follows it").isPresent();
    assertThat(numbers).as("every number is changed here now").allMatch(JSpinner::isEnabled);
    assertThat(buttons)
        .as("no loop, no button")
        .doesNotContain("Change for this unit only", "Attachments ›");
    project.undo();
    assertThat(project.game()).as("one step to undo").isSameAs(loop);
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

  @DisplayName("Cost: a field per resource of the unit's rule, + adds one the game has; 0 takes it out")
  @Test
  void costInOtherResources() throws Exception {
    // given: artillery for 4 PUs, in a game that also has Fame
    final MapProject project = givenG40();
    project.edit(g -> ProductionXml.withResource(g, "Fame"));
    final org.triplea.map.data.elements.Game before = project.game();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final UnitsEditor editor = givenEditor(project, "artillery", "none");
            givenResourcesToAdd(editor).stream()
                .filter(item -> "Fame".equals(item.getText()))
                .findFirst()
                .orElseThrow()
                .doClick();
            seen.add(labelEndingIn(editor.page(), "Fame"));
            project.undo();
            seen.add(project.game() == before);
            project.redo();
            seen.add(
                givenResourcesToAdd(editor).stream().map(AbstractButton::getText).toList());
            givenCostField(editor, "Fame").setValue(5);
            seen.add(labelEndingIn(editor.page(), "Fame"));
            givenCostField(editor, "PUs").setValue(0);
            seen.add(labelEndingIn(editor.page(), "Fame"));
            final UnitsEditor again = givenEditor(project, "artillery", "none");
            seen.add(givenCostField(again, "PUs").getValue());
            seen.add(givenCostField(again, "Fame").getValue());
            givenCostField(again, "Fame").setValue(0);
            seen.add(labelEndingIn(again.page(), "free"));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });
    final Path xml = folder.resolve("costs.xml");
    org.triplea.map.xml.writer.GameXmlWriter.exportXml(project.game(), xml);

    // then
    assertThat(seen)
        .as("+ gives 1 Fame, one step; + then offers the others; Fame typed; PUs at 0 leave"
            + " the cost, their field stays, at 0; Fame at 0 too: free")
        .containsExactly(
            "Land unit · 2-2-1 · 4 PUs + 1 Fame",
            true,
            List.of("techTokens", "SuicideAttackTokens"),
            "Land unit · 2-2-1 · 4 PUs + 5 Fame",
            "Land unit · 2-2-1 · 5 Fame",
            0,
            5,
            "Land unit · 2-2-1 · free");
    assertThat(MapChecks.findError(xml)).as("TripleA loads the game").isEmpty();
  }

  // what the + beside the cost offers
  private static List<javax.swing.JMenuItem> givenResourcesToAdd(final UnitsEditor editor) {
    return List.of(
            ((javax.swing.JComponent) givenButton(editor, "+"))
                .getComponentPopupMenu()
                .getComponents())
        .stream()
        .map(javax.swing.JMenuItem.class::cast)
        .toList();
  }

  private static JSpinner givenCostField(final UnitsEditor editor, final String resource) {
    return collect(editor.page(), JSpinner.class).stream()
        .filter(
            field ->
                String.valueOf(field.getToolTipText()).startsWith(resource + " to buy one"))
        .findFirst()
        .orElseThrow();
  }

  private static String labelEndingIn(final Component in, final String end) {
    return givenLabels(in).stream()
        .filter(text -> text != null && text.endsWith(end) && text.contains(" · "))
        .findFirst()
        .orElse("");
  }

  @DisplayName("Value says it is the TUV and what an empty field means, with a guide behind ?")
  @Test
  void valueSaysWhatItIs() throws Exception {
    // given
    final MapProject project = givenG40();
    final List<String> tips = new ArrayList<>();
    final List<String> helps = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "artillery", "none").page();
            collect(page, JLabel.class).stream()
                .filter(label -> "Value".equals(label.getText()))
                .map(JLabel::getToolTipText)
                .forEach(tips::add);
            collect(page, AbstractButton.class).stream()
                .map(button -> button.getAccessibleContext().getAccessibleName())
                .filter("Help: Value"::equals)
                .forEach(helps::add);
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    // then
    assertThat(tips)
        .as("the label's tool tip names the TUV and the grey word of the empty field")
        .isNotEmpty()
        .allMatch(tip -> tip.startsWith("TUV: ") && tip.contains("\"its cost\""));
    assertThat(helps).as("the guide's ? beside the label").hasSize(1);
    assertThat(UnitOptions.VALUE_GUIDE)
        .as("the rule of thumb")
        .contains("attack + defense + movement");
  }

  @DisplayName("A unit that uses up other units when placed says so under its name")
  @Test
  void unitsUsedUpShowWithTheCost() throws Exception {
    // given: artillery made from 2 Material, as Total World War's units are
    final MapProject project = givenG40();
    project.edit(
        g ->
            UnitsXml.withNewOption(
                g, "artillery", Option.builder().name("consumesUnits").value("2:Material").build()));
    final List<String> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final Component page = givenEditor(project, "artillery", "none").page();
            seen.add(labelEndingIn(page, "Material"));
            seen.add(
                collect(page, JSpinner.class).stream()
                    .map(JSpinner::getToolTipText)
                    .filter(tip -> tip != null && tip.startsWith("PUs to buy one"))
                    .findFirst()
                    .orElse(""));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    // then
    assertThat(seen)
        .as("the line under the name and the Cost field's tool tip")
        .containsExactly(
            "Land unit · 2-2-1 · 4 PUs · uses 2 Material",
            "PUs to buy one. Placing one uses up 2 Material, set under Uses up units");
  }

  @DisplayName("The sections come as battle, movement, the rest")
  @Test
  void sectionsInOrder() throws Exception {
    // given G40's fighter, an air unit: its kind's section shows, Sea is folded away
    final MapProject project = givenG40();
    final List<String> labels = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            labels.addAll(givenLabels(givenEditor(project, "fighter", "none").page()));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    // then
    assertThat(UnitOptions.SECTIONS)
        .as("every section, in the order Units shows them")
        .extracting(UnitOptions.Section::title)
        .containsExactly(
            "Combat",
            "Air and anti-air",
            "Bombing",
            "Sea",
            "Movement",
            "Transport and carrier",
            "Production and infrastructure",
            "Special");
    assertThat(labels)
        .as("on the page: Gives support after Combat, Bombing open for a plane, Miscellaneous"
            + " after the movement")
        .containsSubsequence(
            "Combat",
            "Gives support",
            "Air and anti-air",
            "Bombing",
            "Movement",
            "Transport and carrier",
            "Miscellaneous");
  }

  @DisplayName("Uses up units: a row per unit with count, type and ×, each change one step")
  @Test
  void usedUpUnitsAreRows() throws Exception {
    // given: G40's factory_upgrade, placed on a factory_minor that it uses up
    final MapProject project = givenG40();
    final org.triplea.map.data.elements.Game before = project.game();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final UnitsEditor editor = givenEditor(project, "factory_upgrade", "none");
            seen.add(givenUsedUpField(editor, JSpinner.class).getValue());
            seen.add(givenUsedUpField(editor, JComboBox.class).getSelectedItem());
            seen.add(
                UnitsEditor.moreOptions(project.game(), "factory_upgrade").stream()
                    .map(Option::getName)
                    .anyMatch("consumesUnits"::equals));
            givenUsedUpField(editor, JSpinner.class).setValue(2);
            seen.add(UnitsXml.usedUp(project.game(), "factory_upgrade"));
            project.undo();
            seen.add(project.game() == before);
            givenUsedUpField(editor, JComboBox.class).setSelectedItem("infantry");
            seen.add(UnitsXml.usedUp(project.game(), "factory_upgrade"));
            givenButton(editor, "+ Add a unit it uses up").doClick();
            seen.add(UnitsXml.usedUp(project.game(), "factory_upgrade"));
            givenButton(editor, "×").doClick();
            seen.add(UnitsXml.usedUp(project.game(), "factory_upgrade"));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    // then
    assertThat(seen)
        .as("the row as written and not again under More options; the count, undone in one step;"
            + " the type; a second row of the first unit type not used yet; the first removed")
        .containsExactly(
            1,
            "factory_minor",
            false,
            List.of("2 factory_minor"),
            true,
            List.of("1 infantry"),
            List.of("1 infantry", "1 artillery"),
            List.of("1 artillery"));
  }

  // the first row's field of the type
  private static <T extends javax.swing.JComponent> T givenUsedUpField(
      final UnitsEditor editor, final Class<T> type) {
    return collect(editor.page(), type).stream()
        .filter(
            field ->
                String.valueOf(field.getAccessibleContext().getAccessibleName())
                    .startsWith("Uses up units, "))
        .findFirst()
        .orElseThrow();
  }

  // the first on the page: a used-up unit's × stands above those of More options
  private static AbstractButton givenButton(final UnitsEditor editor, final String text) {
    return collect(editor.page(), AbstractButton.class).stream()
        .filter(button -> text.equals(button.getText()))
        .findFirst()
        .orElseThrow();
  }
}
