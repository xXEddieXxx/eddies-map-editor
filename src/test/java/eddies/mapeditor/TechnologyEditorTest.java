package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.TechXml.UnitNumber;

final class TechnologyEditorTest {
  private static final String CHART = "Land Advances";

  private static MapProject givenMinimap(final Path folder) {
    return new MapProject(
        folder.resolve("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
  }

  @DisplayName("A game without a list shows TripleA's standard technologies and writes nothing")
  @Test
  void listsTheStandardTechnologies(@TempDir final Path folder) {
    final MapProject project = givenMinimap(folder);

    final TechnologyEditor editor = new TechnologyEditor(project);

    assertThat(editor.names())
        .as("the engine's standard ones")
        .isNotEmpty()
        .isEqualTo(TechXml.techNames(TechXml.withStandardTechs(project.game())));
    assertThat(project.game().getTechnology()).as("looking writes nothing").isNull();
    assertThat(project.canUndo()).as("no step").isFalse();
  }

  @DisplayName("What it gives: a bonus row per unit type, its number a step; costs per nation")
  @Test
  void showsWhatItGives(@TempDir final Path folder) {
    // given: super subs give submarines +2 attack and battleships +1
    final MapProject project = givenMinimap(folder);
    project.edit(
        g ->
            TechXml.withUnitNumberAdded(
                TechXml.withUnitNumberAdded(
                    g, "superSub", new UnitNumber("attackBonus", 2, "submarine")),
                "superSub",
                new UnitNumber("attackBonus", 1, "battleship")));
    final TechnologyEditor editor = new TechnologyEditor(project);
    choose(editor, "superSub");
    final List<JSpinner> numbers = find(editor.page(), JSpinner.class);
    final JTable nations = find(editor.page(), JTable.class).getFirst();

    // when: the first bonus counted up twice in its field, then the Russians' research cost
    numbers.getFirst().setValue(3);
    numbers.getFirst().setValue(4);
    nations.setValueAt(8, 0, nations.getColumnCount() - 1);

    // then
    assertThat(find(editor.page(), JLabel.class).stream().map(JLabel::getText))
        .as("a row per unit type in plain words, the option's name only as its tool tip")
        .contains("submarine · Attack", "battleship · Attack")
        .doesNotContain("attackBonus");
    assertThat(TechXml.abilityOptions(project.game(), "superSub").stream().map(TechXml::read))
        .as("the second change of the same field counts too; the other row stays")
        .containsExactly("4:submarine", "1:battleship");
    assertThat(nations.getColumnName(nations.getColumnCount() - 1))
        .as("the research cost beside each nation's chart")
        .isEqualTo("PUs per roll");
    assertThat(TechXml.techCost(project.game(), MapXml.nations(project.game()).getFirst()))
        .as("written to the nation's techAttachment")
        .isEqualTo(8);
    project.undo();
    project.undo();
    assertThat(TechXml.abilityOptions(project.game(), "superSub").getFirst().getValue())
        .as("each change one step")
        .isEqualTo("3:submarine");
  }

  @DisplayName("A built-in technology with nothing set says what TripleA gives it by itself")
  @Test
  void saysTheStandardEffect(@TempDir final Path folder) {
    final MapProject project = givenMinimap(folder);
    final TechnologyEditor editor = new TechnologyEditor(project);

    choose(editor, "longRangeAir");

    assertThat(texts(editor.page()))
        .as("so a first value is not set by mistake for the standard effect")
        .anyMatch(t -> t.contains("+2 movement for every air unit"));
    assertThat(texts(editor.page()))
        .as("the rarer effects fold into one row saying how many it holds; none is set")
        .anyMatch(
            t -> t.matches("▸ Rockets, war bonds, factories, airborne forces \\(\\d+\\)"));
  }

  private static void choose(final TechnologyEditor editor, final String tech) {
    try {
      final Method choose = TechnologyEditor.class.getDeclaredMethod("choose", String.class);
      choose.setAccessible(true);
      choose.invoke(editor, tech);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> List<T> find(final Component in, final Class<T> type) {
    final List<T> found = new ArrayList<>();
    if (type.isInstance(in)) {
      found.add(type.cast(in));
    }
    if (in instanceof Container container) {
      for (final Component child : container.getComponents()) {
        found.addAll(find(child, type));
      }
    }
    return found;
  }

  /** The texts of the labels, notes and buttons. */
  private static List<String> texts(final Component in) {
    return Stream.of(
            find(in, JLabel.class).stream().map(JLabel::getText),
            find(in, AbstractButton.class).stream().map(AbstractButton::getText))
        .flatMap(s -> s)
        .filter(Objects::nonNull)
        .toList();
  }

  @DisplayName("Standard technologies link to Game options, where WW2V3 Tech Model picks them")
  @Test
  void standardLinksToGameOptions(@TempDir final Path folder) throws Exception {
    // given
    final MapProject project = givenMinimap(folder);
    final List<Boolean> shown = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TechnologyEditor editor = new TechnologyEditor(project);
          shown.add(givenGameOptionsLinkShown(editor));
          project.edit(TechXml::withStandardTechs);
          shown.add(givenGameOptionsLinkShown(editor));
        });

    // then
    assertThat(shown.get(0)).as("which standard ones depends on an option").isTrue();
    assertThat(shown.get(1)).as("an own list needs no option").isFalse();
  }

  private static boolean givenGameOptionsLinkShown(final TechnologyEditor editor) {
    return all(editor.page())
        .filter(c -> c instanceof AbstractButton b && "Game options ›".equals(b.getText()))
        .anyMatch(c -> c.getParent().isVisible());
  }

  private static Stream<Component> all(final Component in) {
    return in instanceof Container container
        ? Stream.concat(
            Stream.of(in),
            Stream.of(container.getComponents()).flatMap(TechnologyEditorTest::all))
        : Stream.of(in);
  }

  @DisplayName("The first chart change writes the standard list and the change as one step")
  @Test
  void firstChangeWritesTheStandardList(@TempDir final Path folder) {
    final MapProject project = givenMinimap(folder);
    final TechnologyEditor editor = new TechnologyEditor(project);
    final String tech = editor.names().getFirst();

    editor.setChart("Russians", tech, CHART);

    assertThat(TechXml.techNames(project.game()))
        .as("the standard list written into the game")
        .isEqualTo(editor.names());
    assertThat(TechXml.categoryOf(project.game(), "Russians", tech))
        .as("the Russians research it in the new chart")
        .contains(CHART);
    assertThat(RulesXmlTest.givenLoaded(project.game(), folder))
        .as("the changed game loads in TripleA")
        .isNotNull();
    project.undo();
    assertThat(project.game().getTechnology()).as("one undo: no list again").isNull();
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @DisplayName("Not researched takes the technology out of the nation's charts")
  @Test
  void notResearchedTakesItOut(@TempDir final Path folder) {
    final MapProject project = givenMinimap(folder);
    final TechnologyEditor editor = new TechnologyEditor(project);
    final String tech = editor.names().getFirst();
    editor.setChart("Russians", tech, CHART);

    editor.setChart("Russians", tech, "Not researched");

    assertThat(TechXml.categoryOf(project.game(), "Russians", tech))
        .as("not researched any more")
        .isEmpty();
  }
}
