package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

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

  /** The minimap without a picture: its problem "No picture yet" is fixed in Territories. */
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
    final JButton open =
        givenAll(home.page(), JButton.class)
            .filter(b -> b.getText().equals("Open Territories"))
            .findFirst()
            .orElseThrow();

    open.doClick();

    assertThat(opened).as("the picture is loaded in Territories").containsExactly("Territories");
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
    assertThat(territoryProblems).as("no picture yet, at least").isPositive();
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
        .contains("More tools ▾ · ! 1");
    assertThat(givenAll(home.page(), JButton.class))
        .as("one problem, not one problems")
        .anyMatch(
            b ->
                b.getAccessibleContext()
                    .getAccessibleName()
                    .equals("More tools, collapsed, 1 problem"));
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
        .filter(b -> b.getText().startsWith("More tools ▾"))
        .findFirst()
        .orElseThrow()
        .doClick();

    // when
    final List<JButton> tiles =
        givenAll(home.page(), JButton.class).filter(b -> b.getIcon() instanceof MakerIcon).toList();

    // then
    assertThat(tiles).as("every tool's tile").hasSizeGreaterThan(15);
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
        .filter(b -> b.getText().startsWith("More tools ▾"))
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
}
