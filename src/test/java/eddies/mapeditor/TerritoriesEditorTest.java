package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TerritoriesEditorTest {
  private static final String NORTH = "red north 1";
  @TempDir Path folder;

  private MapProject givenMinimap() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        TerritoriesEditorTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  /** The editor showing red north 1 with its more options open. */
  private static TerritoriesEditor givenMoreOptions(final MapProject project) {
    final TerritoriesEditor editor =
        new TerritoriesEditor(project, new MapView(project, () -> {}, () -> {}, () -> {}));
    editor.show(NORTH);
    find(editor.page(), AbstractButton.class, b -> b.getText().startsWith("More options"))
        .doClick();
    return editor;
  }

  private static <T extends Component> T find(
      final Container in, final Class<T> kind, final Predicate<T> wanted) {
    return all(in)
        .filter(kind::isInstance)
        .map(kind::cast)
        .filter(wanted)
        .findFirst()
        .orElseThrow();
  }

  private static Stream<Component> all(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }

  @DisplayName("A text typed, committed and then cleared clears the option too")
  @Test
  void clearedTextClearsTheOption() throws Exception {
    final MapProject project = givenMinimap();
    final List<List<String>> after = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = givenMoreOptions(project);
          final JTextField resources =
              (JTextField)
                  find(editor.page(), JLabel.class, l -> "Resources per turn".equals(l.getText()))
                      .getLabelFor();
          resources.setText("2:Ore");
          resources.postActionEvent();
          after.add(TerritoryXml.values(project.game(), NORTH, "resources"));
          resources.setText("");
          resources.postActionEvent();
          after.add(TerritoryXml.values(project.game(), NORTH, "resources"));
        });

    assertThat(after)
        .as("committed, then cleared again")
        .containsExactly(List.of("2:Ore"), List.of());
  }

  @DisplayName("A territory chosen in the list is scrolled into view on the map")
  @Test
  void listChoiceScrollsTheMap() throws Exception {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    final List<Point> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = new MapView(project, () -> {}, () -> {}, () -> {});
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          final JFrame frame = new JFrame();
          frame.setContentPane(map);
          frame.setSize(400, 400);
          frame.addNotify();
          frame.validate();
          // bigger than the view, so there is room to scroll
          find(map, MapCanvas.class, c -> true).zoomBy(4);
          frame.validate();
          final JViewport viewport = find(map, JViewport.class, v -> v.getView() != null);
          seen.add(viewport.getViewPosition());
          find(editor.page(), JList.class, l -> true)
              .setSelectedValue(MapXml.territories(project.game()).getLast(), false);
          seen.add(viewport.getViewPosition());
          frame.dispose();
        });

    assertThat(seen.getLast()).as("moved to the territory").isNotEqualTo(seen.getFirst());
  }
}
