package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PoliticsEditorTest {

  private static MapProject givenG40(final Path folder) {
    return new MapProject(
        folder.resolve("ww2global40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
  }

  @DisplayName("Set all to By alliance takes out every pair of the nation as one step")
  @Test
  void setsAllByAlliance(@TempDir final Path folder) {
    final MapProject project = givenG40(folder);
    final PoliticsEditor editor = new PoliticsEditor(project);

    editor.setAllByAlliance("Germans");

    assertThat(MapXml.nations(project.game()))
        .as("no pair of the Germans is left")
        .allSatisfy(
            n ->
                assertThat(RulesXml.relationship(project.game(), "Germans", n))
                    .as("Germans go by alliance with " + n)
                    .isEmpty());
    assertThat(RulesXml.relationship(project.game(), "Russians", "Japanese"))
        .as("pairs of other nations stay")
        .contains("Neutrality");
    project.undo();
    assertThat(RulesXml.relationship(project.game(), "Germans", "Russians"))
        .as("one undo brings them back")
        .contains("Neutrality");
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @DisplayName("Under the tabs both lists keep room above their count line")
  @Test
  void listsHaveRoomBelowTabs(@TempDir final Path folder) {
    final JTabbedPane page = (JTabbedPane) new PoliticsEditor(givenG40(folder)).page();

    final List<Integer> tops =
        IntStream.range(0, page.getTabCount())
            .mapToObj(
                i -> ((JComponent) ((JSplitPane) page.getComponentAt(i)).getLeftComponent()))
            .map(list -> list.getInsets().top)
            .toList();

    assertThat(tops).as("the count not squeezed under the tab line").containsOnly(Theme.GAP_M);
  }
}
