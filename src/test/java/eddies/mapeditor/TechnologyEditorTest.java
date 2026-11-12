package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
