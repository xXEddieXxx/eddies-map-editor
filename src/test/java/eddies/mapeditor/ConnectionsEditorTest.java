package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import eddies.mapeditor.ConnectionsEditor.Choices;
import eddies.mapeditor.ConnectionsEditor.Found;
import eddies.mapeditor.MapPicture.Neighbours;

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

  @DisplayName("Find neighbours again: touching pairs to connect, connections apart to review")
  @Test
  void findsNeighboursAgain() {
    // given: the found map is right; then two that touch are parted and two apart connected
    final MapProject project = givenFoundMap();
    final Found right = ConnectionsEditor.find(project.game(), project.getFiles().polygons());
    project.edit(
        g ->
            MapXml.withConnection(
                MapXml.withoutConnection(g, TOP_LEFT, TOP_RIGHT), TOP_LEFT, BOTTOM_RIGHT));
    // written the other way round too, as some maps do: one connection to review, not two
    project.edit(
        g ->
            Builders.toBuilder(g)
                .map(
                    Builders.toBuilder(g.getMap())
                        .connections(
                            Stream.concat(
                                    MapXml.connections(g).stream(),
                                    Stream.of(
                                        org.triplea.map.data.elements.Map.Connection.builder()
                                            .t1(BOTTOM_RIGHT)
                                            .t2(TOP_LEFT)
                                            .build()))
                                .toList())
                        .build())
                .build());
    final int wrong = MapXml.connections(project.game()).size();

    // when: found, shown, both ticked (the pair by Tick all), taken
    final Found found = ConnectionsEditor.find(project.game(), project.getFiles().polygons());
    final Choices choices = ConnectionsEditor.newChoices(found);
    final List<JCheckBox> boxes =
        all(choices.content())
            .flatMap(c -> c instanceof JCheckBox box ? Stream.of(box) : Stream.empty())
            .toList();
    final List<Boolean> ticked = boxes.stream().map(JCheckBox::isSelected).toList();
    final List<List<Neighbours>> unticked = List.of(choices.connect().get(), choices.part().get());
    final List<AbstractButton> tickAll =
        all(choices.content())
            .flatMap(c -> c instanceof JButton b ? Stream.<AbstractButton>of(b) : Stream.empty())
            // not the scroll bars' arrows
            .filter(b -> !b.getText().isEmpty())
            .toList();
    tickAll.getFirst().doClick();
    boxes.getLast().setSelected(true);
    project.edit(g -> ConnectionsEditor.applied(g, choices.connect().get(), choices.part().get()));

    // then
    assertThat(List.of(right.missing(), right.apart()))
        .as("what the picture gave agrees with the outlines")
        .containsExactly(List.of(), List.of());
    assertThat(found.missing())
        .as("they touch but have no connection")
        .containsExactly(new Neighbours(TOP_LEFT, TOP_RIGHT));
    assertThat(found.apart())
        .as("connected across the middle, without touching")
        .containsExactly(new Neighbours(TOP_LEFT, BOTTOM_RIGHT));
    assertThat(boxes).as("a row each").extracting(JCheckBox::getText).hasSize(2);
    assertThat(ticked)
        .as("nothing ticked: a pair may be apart on purpose, a sea route meant")
        .containsExactly(false, false);
    assertThat(unticked).as("nothing added or removed unless ticked").containsOnly(List.of());
    assertThat(tickAll)
        .as("a list's Tick all, which then unticks them again")
        .extracting(AbstractButton::getText)
        .containsExactly("Untick all", "Tick all");
    assertThat(ConnectionsEditor.find(project.game(), project.getFiles().polygons()))
        .as("taken: connected again, and parted")
        .isEqualTo(right);
    project.undo();
    assertThat(MapXml.connections(project.game()))
        .as("both changes were one step")
        .hasSize(wrong);
  }

  @DisplayName("Touching is found from the outlines alone, each pair once, apart ones left out")
  @Test
  void findsTouchingOutlines() {
    final Map<String, List<Polygon>> outlines = new LinkedHashMap<>();
    outlines.put("West", List.of(new Polygon(new int[] {0, 50, 50, 0}, new int[] {0, 0, 50, 50}, 4)));
    outlines.put("East", List.of(new Polygon(new int[] {53, 100, 100, 53}, new int[] {0, 0, 50, 50}, 4)));
    outlines.put("Far", List.of(new Polygon(new int[] {300, 350, 350, 300}, new int[] {0, 0, 50, 50}, 4)));
    outlines.put("Island", List.of(new Polygon(new int[] {310, 320, 320, 310}, new int[] {10, 10, 20, 20}, 4)));

    assertThat(OutlineCut.touching(outlines))
        .as("across a border line's width, and an island inside its sea")
        .containsExactly(new Neighbours("West", "East"), new Neighbours("Far", "Island"));
  }

  @DisplayName("Connections opens on the overview, no territory chosen, also after one was")
  @Test
  void opensOnTheOverview() throws Exception {
    // given
    final MapProject project = givenFoundMap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenView(project);
          final ConnectionsEditor editor = new ConnectionsEditor(project, map);
          editor.showing();
          editor.click(TOP_LEFT);
          seen.add(map.bannerText().isPresent());
          editor.showing();
          seen.add(map.bannerText().isPresent());
          editor.show(TOP_LEFT);
          seen.add(map.bannerText().isPresent());
        });

    // then
    assertThat(seen)
        .as("chosen by a click; none on opening again; one asked for, eg by a problem, chosen")
        .containsExactly(true, false, true);
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

  @DisplayName("The overview lists territories without neighbours as worth a look, with Show")
  @Test
  void overviewListsLoneTerritories() throws Exception {
    // given: a map from a picture with a territory off the board
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    project.edit(game -> MapXml.withTerritory(game, "Atlantis", List.of()));
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = new MapView(project, () -> {}, () -> {}, () -> {});
          final ConnectionsEditor editor = new ConnectionsEditor(project, map);
          editor.showing();
          seen.add(
              texts(editor.page()).anyMatch(text -> text.contains("Worth a look")));
          seen.add(
              texts(editor.page())
                  .anyMatch(text -> text.contains("Atlantis is not connected to any territory")));
          seen.add(texts(editor.page()).anyMatch(text -> text.equals("Problems")));
        });

    // then
    assertThat(seen)
        .as("named where it is fixed, but not as a problem")
        .containsExactly(true, true, false);
  }

  private static Stream<String> texts(final Component component) {
    final Stream<String> own =
        component instanceof JLabel label && label.getText() != null
            ? Stream.of(label.getText())
            : Stream.empty();
    return component instanceof Container container
        ? Stream.concat(
            own, Stream.of(container.getComponents()).flatMap(ConnectionsEditorTest::texts))
        : own;
  }
}
