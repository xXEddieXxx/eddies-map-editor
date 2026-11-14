package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.MapProject.NewMap;

final class HomeEditorTest {
  @TempDir Path maps;
  private final List<String> opened = new ArrayList<>();
  private final List<MapGuide.Step.Id> followed = new ArrayList<>();

  private HomeEditor givenHome(final MapProject project) {
    return new HomeEditor(
        project, t -> {}, opened::add, followed::add, () -> {}, () -> {}, () -> {});
  }

  /** The minimap without a picture: making its territories is the guide's first step. */
  private static MapProject givenMinimapWithoutPicture() {
    return new MapProject(Path.of("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
  }

  @DisplayName("Home has one main action, the next step's")
  @Test
  void hasOnePrimary() {
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());

    final List<AbstractButton> primaries =
        givenAll(home.page(), AbstractButton.class).filter(Theme::isPrimary).toList();

    assertThat(primaries).as("one button in the primary style").hasSize(1);
  }

  @DisplayName("A problem without a territory opens the tool it names")
  @Test
  void problemOpensItsTool() {
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());
    home.setValidated(List.of(MapChecks.Problem.in("Territories", "A picture does not read")));
    final JButton open =
        givenAll(home.page(), JButton.class)
            .filter(b -> b.getText().equals("Open Territories"))
            .findFirst()
            .orElseThrow();

    open.doClick();

    assertThat(opened).as("the problem names Territories").containsExactly("Territories");
  }

  @DisplayName("A save keeps Check's result, so Check is not asked for again; a change drops it")
  @Test
  void saveKeepsTheCheck() throws Exception {
    // given a map checked with a change not saved yet
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), maps)
            .project();
    final HomeEditor home = givenHome(project);
    project.edit(game -> MapXml.withNotes(game, "A note"));
    home.setValidated(List.of());

    // when
    project.save();
    final boolean afterSave = home.checked();
    project.edit(game -> MapXml.withNotes(game, "Another note"));
    final boolean afterChange = home.checked();
    project.undo();

    // then
    assertThat(afterSave).as("the saved map is the one Check loaded").isTrue();
    assertThat(afterChange).as("a changed map is not").isFalse();
    assertThat(home.checked()).as("back at the checked map by undo").isTrue();
  }

  @DisplayName("A change of the texts alone drops Check's result, and shows its problem")
  @Test
  void textsChangeDropsTheCheck() {
    // given a map checked without problems
    final MapProject project = givenMinimapWithoutPicture();
    final HomeEditor home = givenHome(project);
    home.setValidated(List.of());

    // when an objective text of another game is added: neither game nor files change
    project.setText(MapTexts.Kind.OBJECTIVES, "Other_Game.Russians;x", "X");

    // then
    assertThat(home.checked()).as("Check ran on other texts").isFalse();
    assertThat(home.problems().stream().map(MapChecks.Problem::text))
        .as("the problem of the new text shows")
        .anyMatch(text -> text.contains("for another game"));
  }

  @DisplayName("After the wizard, Home tells which nations it left out")
  @Test
  void welcomeShowsRefusedNations() throws Exception {
    final NewMap made =
        MapProject.newMap(
            "Island Wars", MapPictureTest.givenPicture(), List.of("Red", "Territory 1"), maps);
    final HomeEditor home = givenHome(made.project());

    home.welcome(made);

    assertThat(givenAll(home.page(), JLabel.class).map(JLabel::getText))
        .as("the name of a territory is no nation's")
        .anyMatch(text -> text.contains("Left out: Territory 1."))
        .anyMatch(text -> text.contains("Found "));
  }

  @DisplayName("Go to follows the next step into its tool, not just opens the tool")
  @Test
  void goToFollowsTheStep() throws Exception {
    // given
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), maps)
            .project();
    final HomeEditor home = givenHome(project);
    final AbstractButton goTo =
        givenAll(home.page(), AbstractButton.class)
            .filter(Theme::isPrimary)
            .findFirst()
            .orElseThrow();

    // when
    goTo.doClick();

    // then
    assertThat(followed).as("Red has no land yet").containsExactly(MapGuide.Step.Id.LAND);
  }

  private static Optional<JButton> givenTile(final HomeEditor home, final String tool) {
    // a tile has its tool's glyph, a step's button has none
    return givenAll(home.page(), JButton.class)
        .filter(b -> b.getIcon() instanceof MakerIcon)
        .filter(b -> b.getAccessibleContext().getAccessibleName().startsWith(tool + ": "))
        .findFirst();
  }

  @DisplayName("A tile shows its tool's number and problems, and a click opens the tool")
  @Test
  void tileShowsNumberAndProblems() {
    // given
    final MapProject project = givenMinimapWithoutPicture();
    final HomeEditor home = givenHome(project);
    home.setValidated(List.of(MapChecks.Problem.in("Territories", "A picture does not read")));
    final long territoryProblems =
        home.problems().stream()
            .filter(p -> p.territory().isPresent() || p.tool().equals(Optional.of("Territories")))
            .count();
    final JButton territories = givenTile(home, "Territories").orElseThrow();
    final JButton turnOrder = givenTile(home, "Turn order").orElseThrow();

    // when
    turnOrder.doClick();

    // then
    assertThat(territories.getAccessibleContext().getAccessibleName())
        .as("the count of territories and of their problems")
        .startsWith("Territories: " + MapXml.territories(project.game()).size() + " ")
        .contains(", " + territoryProblems + " problem");
    assertThat(territoryProblems).as("the one of the picture").isPositive();
    assertThat(territories.getText()).as("the red badge").contains("! " + territoryProblems);
    assertThat(opened).as("Turn order has no step to follow").containsExactly("Turn order");
  }

  @DisplayName("A problem of a territory counts for Territories' tile")
  @Test
  void territoryProblemCountsForTerritories() {
    // given
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());

    // when
    home.setValidated(List.of(MapChecks.Problem.at("Rome", "Rome has no owner")));

    // then
    assertThat(givenTile(home, "Territories").orElseThrow().getText())
        .as("the red badge of the one problem")
        .contains("! 1");
  }

  @DisplayName("The problems of folded tiles show on More tools")
  @Test
  void foldedProblemsShowOnMore() {
    // given
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());

    // when
    home.setValidated(List.of(MapChecks.Problem.in("Canals", "A canal without sea zones")));

    // then
    assertThat(givenAll(home.page(), JButton.class).map(JButton::getText))
        .as("Canals is folded, its problem counted on the toggle")
        .anyMatch(text -> text.matches("▸ More tools \\(\\d+\\) · ! 1"));
    assertThat(givenAll(home.page(), JButton.class))
        .as("one problem, not one problems")
        .anyMatch(
            b ->
                b.getAccessibleContext()
                    .getAccessibleName()
                    .matches("More tools \\(\\d+\\) · ! 1, collapsed, 1 problem"));
  }

  @DisplayName("A tile's number is read with its noun")
  @Test
  void tileNumberHasNoun() {
    // given
    final MapProject project = givenMinimapWithoutPicture();

    // when
    final HomeEditor home = givenHome(project);

    // then
    assertThat(givenTile(home, "Units").orElseThrow().getAccessibleContext().getAccessibleName())
        .as("the kinds of units, then the line")
        .startsWith("Units: " + MapXml.unitTypes(project.game()).size() + " kinds, ")
        .endsWith(" with units");
  }

  @DisplayName("A new width refits the overview without counting the tiles again")
  @Test
  void widthChangeDoesNotRecount() throws Exception {
    // given
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());
    final int counted = home.countings();
    final JButton before = givenTile(home, "Territories").orElseThrow();

    // when
    for (final int width : new int[] {1050, 1400, 1100}) {
      home.page().setSize(width, 800);
      givenLaidOut(home.page());
      SwingUtilities.invokeAndWait(() -> {});
      givenLaidOut(home.page());
    }

    // then
    assertThat(home.countings()).as("the map did not change").isEqualTo(counted);
    assertThat(givenTile(home, "Territories"))
        .as("the same tile, so its focus stays")
        .containsSame(before);
  }

  @DisplayName("At 1050 px the overview stands beside the guide")
  @Test
  void columnsSideBySideAt1050() throws Exception {
    // given
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());
    home.page().setSize(1050, 800);

    // when
    // the overview fitted to the room is made again on the Swing thread, a scroll bar may follow
    for (int i = 0; i < 3; i++) {
      givenLaidOut(home.page());
      SwingUtilities.invokeAndWait(() -> {});
    }

    // then
    final JButton territories = givenTile(home, "Territories").orElseThrow();
    final Point at = SwingUtilities.convertPoint(territories, 0, 0, home.page());
    assertThat(at.x).as("right of the 540 px guide").isGreaterThan(540);
    assertThat(at.y).as("not below the guide").isLessThan(400);
  }

  private static void givenLaidOut(final Container container) {
    container.doLayout();
    for (final Component child : container.getComponents()) {
      if (child instanceof Container inner) {
        givenLaidOut(inner);
      }
    }
  }

  @DisplayName("Each tile's line fits in one line at the tile's width")
  @Test
  void tileLinesFitOneLine() {
    // given
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());
    givenAll(home.page(), JButton.class)
        .filter(b -> b.getText().startsWith("▸ More tools"))
        .findFirst()
        .orElseThrow()
        .doClick();

    // when
    final List<JButton> tiles =
        givenAll(home.page(), JButton.class).filter(b -> b.getIcon() instanceof MakerIcon).toList();

    // then
    assertThat(tiles).as("every tool's tile").hasSizeGreaterThan(15);
    assumeTrue(
        System.getProperty("os.name").startsWith("Windows"),
        "the tile width is tuned to Windows' fonts; others, eg on Linux, are wider");
    assertThat(tiles)
        .as("the line, its tool tip, no wider than the tile's text")
        .allMatch(
            tile ->
                tile.getFontMetrics(tile.getFont()).stringWidth(tile.getToolTipText())
                    <= HomeEditor.TILE_TEXT_WIDTH,
            "fits");
  }

  @DisplayName("A tile whose tool has a step not done follows that step")
  @Test
  void tileFollowsItsStep() throws Exception {
    // given
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), maps)
            .project();
    final HomeEditor home = givenHome(project);

    // when
    givenTile(home, "Territories").orElseThrow().doClick();

    // then
    assertThat(followed).as("Red has no land yet").containsExactly(MapGuide.Step.Id.LAND);
    assertThat(opened).as("followed, not just opened").isEmpty();
  }

  @DisplayName("More tools unfolds the tiles of the other tools")
  @Test
  void moreUnfoldsTiles() {
    // given
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());
    final boolean canalsBefore = givenTile(home, "Canals").isPresent();

    // when
    givenAll(home.page(), JButton.class)
        .filter(b -> b.getText().startsWith("▸ More tools"))
        .findFirst()
        .orElseThrow()
        .doClick();

    // then
    assertThat(canalsBefore).as("Canals is folded away at first").isFalse();
    assertThat(givenTile(home, "Canals")).as("unfolded").isPresent();
    assertThat(givenTile(home, "Files")).as("the last tool too").isPresent();
  }

  @DisplayName("Without outlines, the overview leads to making territories")
  @Test
  void noOutlinesLeadsToTerritories() {
    // given
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());

    // when
    givenAll(home.page(), JButton.class)
        .filter(b -> b.getText().equals("Make territories ›"))
        .findFirst()
        .orElseThrow()
        .doClick();

    // then
    assertThat(givenAll(home.page(), MiniMap.class)).as("no map to show").isEmpty();
    assertThat(followed).as("the territories step").containsExactly(MapGuide.Step.Id.TERRITORIES);
  }

  @DisplayName("With outlines but no nations, the map says nations show who owns what")
  @Test
  void noNationsSaysSo() throws Exception {
    // given
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of(), maps)
            .project();

    // when
    final HomeEditor home = givenHome(project);

    // then
    assertThat(givenAll(home.page(), MiniMap.class)).as("the map small").hasSize(1);
    assertThat(givenAll(home.page(), JLabel.class).map(JLabel::getText))
        .as("a line instead of the nations' colors")
        .anyMatch(text -> text.contains("Add nations to see who owns what."));
  }

  private static <T> Stream<T> givenAll(final Component component, final Class<T> type) {
    final Stream<T> self =
        type.isInstance(component) ? Stream.of(type.cast(component)) : Stream.of();
    if (component instanceof Container container) {
      return Stream.concat(
          self, Stream.of(container.getComponents()).flatMap(child -> givenAll(child, type)));
    }
    return self;
  }

  @DisplayName("A new map greets calmly: its steps, no problems, only the tiles of its steps")
  @Test
  void newMapShowsStepsNotProblems() throws Exception {
    // given
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), maps)
            .project();

    // when
    final HomeEditor home = givenHome(project);

    // then
    assertThat(home.problems())
        .as("Red without land is the next step, not also a problem")
        .isEmpty();
    assertThat(MapMakerWindow.pill(home.problems().size(), MapMakerWindow.Check.NOT_YET))
        .as("the header shows no pill")
        .isEmpty();
    assertThat(
            Stream.of("Territories", "Nations", "Connections", "Turn order", "Production")
                .filter(tool -> givenTile(home, tool).isPresent()))
        .as("no numbers the map maker never made, eg the template's 32 turn order steps")
        .containsExactly("Territories", "Nations", "Connections");
  }

  /** A map with its six building steps done: Red owns land with its capital and a unit. */
  private MapProject givenBuiltMap() throws Exception {
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), maps)
            .project();
    final String land =
        MapXml.territories(project.game()).stream()
            .filter(t -> !MapXml.isWater(project.game(), t))
            .findFirst()
            .orElseThrow();
    project.edit(g -> MapXml.withOwner(g, land, "Red"));
    project.edit(g -> MapXml.withCapital(g, land, "Red"));
    project.edit(g -> MapXml.withUnits(g, land, "Red", MapXml.unitTypes(g).getFirst(), 1));
    return project;
  }

  @DisplayName("On a built map the done steps fold into one row, Check still shows; every tool"
      + " has its tile and More tools is gone")
  @Test
  void builtMapFoldsItsSteps() throws Exception {
    // given
    final HomeEditor home = givenHome(givenBuiltMap());
    final List<String> folded = givenAll(home.page(), JLabel.class).map(JLabel::getText).toList();
    final JButton fold =
        givenAll(home.page(), JButton.class)
            .filter(b -> b.getText().startsWith("▸ Steps to a playable map"))
            .findFirst()
            .orElseThrow();

    // when
    fold.doClick();

    // then
    assertThat(fold.getText())
        .as("the six building steps are done, Check is not")
        .isEqualTo("▸ Steps to a playable map · 6 of 7 done");
    assertThat(folded)
        .as("the done steps are folded away, the open one always shows")
        .contains("Check the map and play it")
        .doesNotContain("Add the nations that play");
    assertThat(givenAll(home.page(), JLabel.class).map(JLabel::getText))
        .as("a click shows them again")
        .contains("Add the nations that play", "Check the map and play it");
    assertThat(givenAll(home.page(), JButton.class).map(JButton::getText))
        .as("open, as every fold row")
        .contains("▾ Steps to a playable map · 6 of 7 done")
        .noneMatch(text -> text.contains("More tools"));
    assertThat(Stream.of("Territories", "Canals", "Files").filter(t -> givenTile(home, t).isPresent()))
        .as("every tool's tile, also the rarer ones")
        .containsExactly("Territories", "Canals", "Files");
  }

  @DisplayName("From about 1500 px the tiles stand in a third column right of the mini map")
  @Test
  void tilesInThirdColumnWhenWide() throws Exception {
    // given
    final HomeEditor home = givenHome(givenBuiltMap());
    final List<Point> at = new ArrayList<>();

    // when
    for (final int width : List.of(1200, 1700)) {
      home.page().setSize(width, 900);
      for (int i = 0; i < 3; i++) {
        givenLaidOut(home.page());
        SwingUtilities.invokeAndWait(() -> {});
      }
      at.add(
          SwingUtilities.convertPoint(
              givenTile(home, "Territories").orElseThrow(), 0, 0, home.page()));
    }

    // then
    assertThat(at.get(0).x).as("at 1200 px below the mini map, in its column").isLessThan(700);
    assertThat(at.get(0).y).as("below the mini map").isGreaterThan(200);
    assertThat(at.get(1).x).as("at 1700 px right of the 540 px guide and the mini map").isGreaterThan(1100);
    assertThat(at.get(1).y).as("at the top, beside the mini map").isLessThan(120);
  }

  @DisplayName("A territory without neighbours is worth a look: listed apart, not counted")
  @Test
  void notesAreListedApart() {
    // given: a finished map with a territory off the board
    final MapProject project =
        new MapProject(
            Path.of("minimap.xml"),
            MapXml.withTerritory(MapXmlTest.givenMinimap(), "Atlantis", List.of()),
            MapFiles.none());

    // when
    final HomeEditor home = givenHome(project);

    // then
    assertThat(home.problems()).as("no problem, so not in the pill").isEmpty();
    assertThat(home.notes())
        .as("the note")
        .extracting(MapChecks.Problem::text)
        .containsExactly("Atlantis is not connected to any territory");
    assertThat(givenAll(home.page(), JLabel.class).map(JLabel::getText))
        .as("under a heading of its own")
        .contains("Worth a look")
        .anyMatch(text -> text.contains("Atlantis is not connected to any territory"));
    assertThat(givenTile(home, "Territories").orElseThrow().getText())
        .as("no red count on the tile")
        .doesNotContain("! ");
  }
}
