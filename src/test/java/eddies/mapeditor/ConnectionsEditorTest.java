package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ConnectionsEditorTest {
  private static final String TOP_LEFT = "Territory 1";
  private static final String TOP_RIGHT = "Territory 2";
  private static final String BOTTOM_RIGHT = "Territory 4";

  /** The four land areas and the sea of {@link MapPictureTest#givenPicture()}, found. */
  private static MapProject givenFoundMap() {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    return project;
  }

  private static MapView givenView(final MapProject project) {
    return new MapView(project, () -> {}, () -> {}, () -> {});
  }

  private static Stream<Component> all(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }

  private static List<String> labels(final Container in) {
    return all(in)
        .flatMap(c -> c instanceof JLabel l ? Stream.of(l.getText()) : Stream.empty())
        .toList();
  }

  @DisplayName("Two clicks connect two territories in one step and the banner says so")
  @Test
  void connectsWithTwoClicks() throws Exception {
    final MapProject project = givenFoundMap();
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenView(project);
          final ConnectionsEditor editor = new ConnectionsEditor(project, map);
          editor.click(TOP_LEFT);
          seen.add(map.bannerText().orElse(""));
          editor.click(BOTTOM_RIGHT);
          seen.add(MapXml.neighbors(project.game(), TOP_LEFT).contains(BOTTOM_RIGHT));
          seen.add(map.bannerText().orElse(""));
          project.undo();
          seen.add(MapXml.neighbors(project.game(), TOP_LEFT).contains(BOTTOM_RIGHT));
        });

    assertThat(seen.get(0))
        .as("the first click chooses")
        .isEqualTo("Connecting Territory 1: click a territory to connect it or part it.");
    assertThat(seen.get(1)).as("the second connects").isEqualTo(true);
    assertThat(seen.get(2))
        .as("the news in the banner")
        .isEqualTo("Connected Territory 1 and Territory 4.");
    assertThat(seen.get(3)).as("one undo takes it back").isEqualTo(false);
  }

  @DisplayName("An impassable territory says it may stay alone, with a link to Territories")
  @Test
  void impassableLinksToTerritories() throws Exception {
    // given
    final MapProject project = givenFoundMap();
    final List<List<String>> buttons = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          buttons.add(givenButtonsOfChosen(project, TOP_LEFT));
          project.edit(g -> TerritoryXml.withFlag(g, TOP_LEFT, TerritoryXml.IMPASSABLE));
          buttons.add(givenButtonsOfChosen(project, TOP_LEFT));
        });

    // then
    assertThat(buttons.get(0))
        .as("a passable territory needs neighbours")
        .doesNotContain("Territories ›");
    assertThat(buttons.get(1)).as("where impassable is set").contains("Territories ›");
  }

  private static List<String> givenButtonsOfChosen(
      final MapProject project, final String territory) {
    final ConnectionsEditor editor = new ConnectionsEditor(project, givenView(project));
    editor.click(territory);
    return all((Container) editor.page())
        .flatMap(c -> c instanceof AbstractButton b ? Stream.of(b.getText()) : Stream.empty())
        .toList();
  }

  @DisplayName("The × of a neighbour parts the two")
  @Test
  void partsWithTheCross() throws Exception {
    final MapProject project = givenFoundMap();
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final ConnectionsEditor editor = new ConnectionsEditor(project, givenView(project));
          editor.click(TOP_LEFT);
          all((Container) editor.page())
              .flatMap(c -> c instanceof AbstractButton b ? Stream.of(b) : Stream.empty())
              .filter(b -> "Part Territory 1 and Territory 2".equals(b.getToolTipText()))
              .findFirst()
              .orElseThrow()
              .doClick();
          seen.add(MapXml.neighbors(project.game(), TOP_LEFT).contains(TOP_RIGHT));
        });

    assertThat(seen).as("no longer neighbours").containsExactly(false);
  }

  @DisplayName("Nothing chosen says to click a territory; without outlines, to load the picture")
  @Test
  void showsWhatToDo() throws Exception {
    final List<List<String>> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          for (final MapProject project : List.of(givenFoundMap(), MapProject.newEmpty())) {
            seen.add(labels((Container) new ConnectionsEditor(project, givenView(project)).page()));
          }
        });

    assertThat(seen.get(0)).as("a found map").contains("Click a territory");
    assertThat(seen.get(1))
        .as("a new map")
        .contains("Connections need the map")
        .doesNotContain("Click a territory");
  }
}
