package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A sea zone's outline has no hole for its islands: its fill leaves them out all the same. */
final class IslandsOnTopTest {
  private static final String SEA = "Tasman Sea";
  private static final String ISLAND = "Tasmania";
  private static final String LAKE = "Great Lake";

  private static Polygon box(final int x, final int y, final int side) {
    return new Polygon(
        new int[] {x, x + side, x + side, x}, new int[] {y, y, y + side, y + side}, 4);
  }

  /** An island with a lake, listed before the sea zone around it, which is so drawn after it. */
  private static MapProject givenIslandInASea() {
    final MapProject project = MapProject.newEmpty();
    project.edit(
        game ->
            MapXml.withWater(
                MapXml.withWater(
                    MapXml.withTerritory(
                        MapXml.withTerritory(
                            MapXml.withTerritory(game, ISLAND, List.of()), SEA, List.of()),
                        LAKE,
                        List.of()),
                    SEA),
                LAKE));
    project.editFiles(
        files ->
            files
                .withOutlines(ISLAND, List.of(box(60, 60, 80)))
                .withOutlines(SEA, List.of(box(0, 0, 200)))
                .withOutlines(LAKE, List.of(box(90, 90, 20))));
    return project;
  }

  private static int painted(final javax.swing.JComponent component, final int x, final int y) {
    final BufferedImage image =
        new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    component.paint(g);
    g.dispose();
    return image.getRGB(x, y) & 0xFFFFFF;
  }

  @DisplayName("A territory's fill leaves out the territories inside it, but not theirs in turn")
  @Test
  void fillHasHolesForTerritoriesInside() {
    // given
    final MapFiles files = givenIslandInASea().getFiles();

    // when
    final Shape sea = files.filled(SEA);
    final Shape island = files.filled(ISLAND);

    // then
    assertThat(sea.contains(20, 20)).as("the sea's own water").isTrue();
    assertThat(sea.contains(70, 70)).as("the island is a hole in the sea").isFalse();
    assertThat(sea.contains(100, 100)).as("and so is the lake on it").isFalse();
    assertThat(island.contains(70, 70)).as("the island's land").isTrue();
    assertThat(island.contains(100, 100)).as("without its lake").isFalse();
    assertThat(files.filled(LAKE).contains(100, 100)).as("the lake itself").isTrue();
  }

  @DisplayName("An island shows in the land's color on the map and the small map, the lake as sea")
  @Test
  void islandIsNotCoveredByItsSea() throws Exception {
    // given
    final MapProject project = givenIslandInASea();
    final List<Integer> pixels = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = new MapCanvas(project);
          canvas.setMode(MapMode.OWNERS);
          canvas.setSize(canvas.getPreferredSize());
          final double zoom = canvas.getPreferredSize().getWidth() / project.getFiles().size().width;
          pixels.add(painted(canvas, (int) (70 * zoom), (int) (70 * zoom)));
          pixels.add(painted(canvas, (int) (100 * zoom), (int) (100 * zoom)));
          final MiniMap miniMap = new MiniMap(project, territory -> {}, 200);
          miniMap.setSize(200, 200);
          pixels.add(painted(miniMap, 70, 70));
          pixels.add(painted(miniMap, 100, 100));
        });

    // then
    final int land = MapCanvas.NEUTRAL.getRGB() & 0xFFFFFF;
    final int sea = MapCanvas.SEA.getRGB() & 0xFFFFFF;
    assertThat(pixels.subList(0, 3))
        .as("on the map the island as land, its lake as sea; on the small map the island as land")
        .containsExactly(land, sea, land);
    assertThat(pixels.get(3)).as("and its lake not").isNotEqualTo(land);
  }
}
