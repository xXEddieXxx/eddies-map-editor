package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
