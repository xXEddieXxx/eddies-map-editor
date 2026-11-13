package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;

/** Territories' "Units at the start": a row goes with its ×, with 0, or all with Remove all. */
final class StartingUnitsTest {
  private static final String CAPITAL = "red capitol";

  @TempDir Path folder;

  private MapProject givenMinimap() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        StartingUnitsTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private static TerritoriesEditor givenEditorAt(final MapProject project) {
    final TerritoriesEditor editor =
        new TerritoriesEditor(project, new MapView(project, () -> {}, () -> {}, () -> {}));
    editor.clickOnMap(CAPITAL);
    return editor;
  }

  private static Stream<Component> all(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }

  private static List<JButton> removeButtons(final TerritoriesEditor editor) {
    return all(editor.page())
        .filter(JButton.class::isInstance)
        .map(JButton.class::cast)
        .filter(b -> "×".equals(b.getText()))
        .toList();
  }

  private static List<JSpinner> counts(final TerritoriesEditor editor) {
    return all(editor.page())
        .filter(JSpinner.class::isInstance)
        .map(JSpinner.class::cast)
        .filter(s -> "0 removes them".equals(s.getToolTipText()))
        .toList();
  }

  private static List<String> types(final MapProject project) {
    return MapXml.units(project.game(), CAPITAL).stream().map(UnitPlacement::getUnitType).toList();
  }

  @DisplayName("A unit row's × removes it at once, as one step, and the list shows it gone")
  @Test
  void removesARow() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<String> before = types(project);
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = givenEditorAt(project);
          seen.add(removeButtons(editor).size());
          final JButton first = removeButtons(editor).getFirst();
          seen.add(first.getToolTipText());
          first.doClick();
          seen.add(types(project));
          seen.add(removeButtons(editor).size());
          project.undo();
          seen.add(types(project));
        });

    // then
    assertThat(seen)
        .as("a × per row; the first row gone from the game and the list; one undo brings it back")
        .containsExactly(
            before.size(),
            "Remove " + before.getFirst(),
            before.subList(1, before.size()),
            before.size() - 1,
            before);
  }

  @DisplayName("A row whose number is set to 0 disappears at once")
  @Test
  void zeroRemovesTheRowAtOnce() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<String> before = types(project);
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = givenEditorAt(project);
          counts(editor).get(1).setValue(0);
          seen.add(types(project));
          seen.add(counts(editor).size());
          counts(editor).getFirst().setValue(4);
          seen.add(counts(editor).size());
        });

    // then
    final List<String> without = new ArrayList<>(before);
    without.remove(1);
    assertThat(seen)
        .as("the second row is gone from the game and from the list; another number keeps the rows")
        .containsExactly(without, before.size() - 1, before.size() - 1);
  }

  @DisplayName("Remove all takes the territory's units away as one step; without units it is gone")
  @Test
  void removesAll() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<String> before = types(project);
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = givenEditorAt(project);
          all(editor.page())
              .filter(JButton.class::isInstance)
              .map(JButton.class::cast)
              .filter(b -> "Remove all".equals(b.getText()))
              .findFirst()
              .orElseThrow()
              .doClick();
          seen.add(types(project));
          seen.add(
              all(editor.page())
                  .anyMatch(c -> c instanceof JButton b && "Remove all".equals(b.getText())));
          project.undo();
          seen.add(types(project));
        });

    // then
    assertThat(seen)
        .as("no units left, no Remove all to click, and one undo brings all back")
        .containsExactly(List.of(), false, before);
  }
}
