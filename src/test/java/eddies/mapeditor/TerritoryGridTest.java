package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MapPicture.DetectedMap;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.TerritoryGrid.Pattern;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The grid of territories laid over a picture without any. */
final class TerritoryGridTest {
  private static final Dimension SIZE = new Dimension(100, 60);

  @DisplayName("Squares: a cell per 20 px, named by row and column, 4 or 8 neighbours")
  @Test
  void squares() {
    final DetectedMap plain = TerritoryGrid.grid(SIZE, Pattern.SQUARES, 20);
    final DetectedMap diagonal = TerritoryGrid.grid(SIZE, Pattern.SQUARES_DIAGONAL, 20);

    assertThat(names(plain))
        .as("5 columns of 3 rows, rows lettered, columns numbered")
        .hasSize(15)
        .startsWith("A1", "A2", "A3", "A4", "A5", "B1")
        .endsWith("C5");
    assertThat(plain.territories()).as("all land").noneMatch(DetectedTerritory::water);
    assertThat(plain.territories().get(6).center())
        .as("B2's center in its middle")
        .isEqualTo(new java.awt.Point(30, 30));
    assertThat(neighboursOf(plain, "B3"))
        .as("left, right, above, below")
        .containsExactlyInAnyOrder("B2", "B4", "A3", "C3");
    assertThat(neighboursOf(diagonal, "B3"))
        .as("the corners too")
        .containsExactlyInAnyOrder("B2", "B4", "A3", "C3", "A2", "A4", "C2", "C4");
    assertThat(neighboursOf(plain, "A1")).as("a corner cell").containsExactlyInAnyOrder("A2", "B1");
  }

  @DisplayName("Hexagons and offset squares: 6 neighbours inside, rows or columns straight")
  @Test
  void hexagonsAndBricks() {
    final Dimension size = new Dimension(200, 160);
    for (final Pattern pattern :
        List.of(
            Pattern.HEX_ROWS, Pattern.HEX_COLUMNS, Pattern.OFFSET_ROWS, Pattern.OFFSET_COLUMNS)) {
      final DetectedMap grid = TerritoryGrid.grid(size, pattern, 24);
      final String inside = insideCell(grid, size);
      assertThat(neighboursOf(grid, inside)).as(pattern + " at " + inside).hasSize(6);
    }
    final DetectedMap rows = TerritoryGrid.grid(size, Pattern.HEX_ROWS, 24);
    final DetectedMap columns = TerritoryGrid.grid(size, Pattern.HEX_COLUMNS, 24);
    assertThat(centersOfRow(rows, "C").stream().map(p -> p.y).distinct())
        .as("a row of pointed hexagons is straight")
        .hasSize(1);
    assertThat(
            columns.territories().stream()
                .filter(t -> t.name().endsWith("3") && t.name().length() == 2)
                .map(t -> t.center().x)
                .distinct())
        .as("a column of flat hexagons is straight")
        .hasSize(1);
  }

  @DisplayName("Every pattern tiles the picture: each pixel in exactly one cell")
  @ParameterizedTest
  @EnumSource(Pattern.class)
  void tilesThePicture(final Pattern pattern) {
    final Dimension size = new Dimension(97, 71);
    final DetectedMap grid = TerritoryGrid.grid(size, pattern, 15);
    final List<Polygon> outlines =
        grid.territories().stream().flatMap(t -> t.outlines().stream()).toList();

    int uncovered = 0;
    int overlapped = 0;
    for (int y = 0; y < size.height; y++) {
      for (int x = 0; x < size.width; x++) {
        final double px = x + 0.5;
        final double py = y + 0.5;
        final long in = outlines.stream().filter(o -> o.contains(px, py)).count();
        uncovered += in == 0 ? 1 : 0;
        overlapped += in > 1 ? 1 : 0;
      }
    }
    assertThat(uncovered).as("pixels in no cell").isZero();
    assertThat(overlapped).as("pixels in two cells").isZero();
    assertThat(grid.territories())
        .as("every cell's center inside its outline")
        .allMatch(t -> t.outlines().getFirst().contains(t.center()));
    assertThat(TerritoryGrid.count(size, pattern, 15)).isEqualTo(grid.territories().size());
  }

  @DisplayName("Rows are lettered A to Z, then AA; the cell width for a count of columns")
  @Test
  void namesAndSizes() {
    assertThat(
            IntStream.of(0, 1, 25, 26, 27, 51, 52, 701, 702)
                .mapToObj(TerritoryGrid::rowName)
                .toList())
        .containsExactly("A", "B", "Z", "AA", "AB", "AZ", "BA", "ZZ", "AAA");
    assertThat(TerritoryGrid.cellWidthFor(SIZE, Pattern.SQUARES, 5)).isEqualTo(20);
    assertThat(TerritoryGrid.columns(SIZE, Pattern.SQUARES, 20)).isEqualTo(5);
    assertThat(TerritoryGrid.rows(SIZE, Pattern.SQUARES, 20)).isEqualTo(3);
    assertThat(
            TerritoryGrid.columns(
                SIZE, Pattern.HEX_COLUMNS, TerritoryGrid.cellWidthFor(SIZE, Pattern.HEX_COLUMNS, 5)))
        .as("flat hexagons: about as many columns as asked")
        .isBetween(5, 7);
    assertThat(TerritoryGrid.grid(SIZE, Pattern.SQUARES, 2).territories())
        .as("a width below the least is raised to it")
        .hasSize(TerritoryGrid.count(SIZE, Pattern.SQUARES, TerritoryGrid.MIN_CELL_WIDTH));
  }

  @DisplayName("The borders are drawn into the picture as black lines; the rest stays")
  @Test
  void drawsTheBorders() {
    final BufferedImage picture = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 100, 60);
    g.dispose();
    final DetectedMap grid = TerritoryGrid.grid(SIZE, Pattern.SQUARES, 20);

    final BufferedImage drawn = TerritoryGrid.withBorders(picture, grid);

    assertThat(new Color(drawn.getRGB(20, 10))).as("on a border").isEqualTo(Color.BLACK);
    assertThat(new Color(drawn.getRGB(40, 30))).as("on a border").isEqualTo(Color.BLACK);
    assertThat(new Color(drawn.getRGB(10, 10))).as("inside a cell").isEqualTo(Color.WHITE);
    assertThat(new Color(picture.getRGB(20, 10))).as("the picture given stays").isEqualTo(Color.WHITE);
  }

  @DisplayName("The grid replaces the map's territories as one step, which Undo takes back")
  @Test
  void isOneStep() {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    final List<String> found = MapXml.territories(project.game());
    final Dimension size = new Dimension(picture.getWidth(), picture.getHeight());
    final DetectedMap grid = TerritoryGrid.grid(size, Pattern.HEX_ROWS, 50);

    project.loadPicture(TerritoryGrid.withBorders(picture, grid), grid);

    assertThat(MapXml.territories(project.game()))
        .as("the cells are the territories")
        .isEqualTo(names(grid));
    assertThat(project.getFiles().polygons().keySet()).isEqualTo(Set.copyOf(names(grid)));
    assertThat(project.getFiles().centers()).hasSize(grid.territories().size());
    assertThat(MapXml.connections(project.game()))
        .as("the connections too")
        .hasSize(grid.connections().size());
    assertThat(found).as("the picture's own areas were found before").isNotEmpty();
    project.undo();
    assertThat(MapXml.territories(project.game())).as("undone").isEqualTo(found);
  }

  @DisplayName("The dialog counts the cells for a width or for a number of columns, and refuses too few")
  @Test
  void dialogCounts() {
    final GridDialog dialog = new GridDialog(SIZE);

    dialog.setCellWidth(20);
    assertThat(dialog.resultText()).isEqualTo("5 columns × 3 rows, 15 territories, cells 20 pixels wide");
    assertThat(dialog.isValid()).isTrue();
    dialog.setColumns(10);
    assertThat(dialog.request())
        .as("10 columns across 100 px: cells 10 wide")
        .isEqualTo(new GridDialog.Request(Pattern.SQUARES, 10));
    dialog.setPattern(Pattern.HEX_ROWS);
    assertThat(dialog.resultText()).contains("cells 10 pixels wide").doesNotContain("too");
    dialog.setPattern(Pattern.SQUARES);
    dialog.setCellWidth(100);
    assertThat(dialog.isValid()).as("one cell is no map").isFalse();
    assertThat(dialog.resultText()).endsWith("too few. Make the cells smaller.");
  }

  private static List<String> names(final DetectedMap grid) {
    return grid.territories().stream().map(DetectedTerritory::name).toList();
  }

  private static List<String> neighboursOf(final DetectedMap grid, final String name) {
    return grid.connections().stream()
        .filter(c -> c.t1().equals(name) || c.t2().equals(name))
        .map(c -> c.t1().equals(name) ? c.t2() : c.t1())
        .toList();
  }

  private static List<java.awt.Point> centersOfRow(final DetectedMap grid, final String row) {
    return grid.territories().stream()
        .filter(t -> t.name().replaceAll("\\d", "").equals(row))
        .map(DetectedTerritory::center)
        .toList();
  }

  /** A cell well inside the picture: the one whose center is nearest the picture's middle. */
  private static String insideCell(final DetectedMap grid, final Dimension size) {
    return grid.territories().stream()
        .min(
            (a, b) ->
                Double.compare(
                    a.center().distanceSq(size.width / 2.0, size.height / 2.0),
                    b.center().distanceSq(size.width / 2.0, size.height / 2.0)))
        .map(DetectedTerritory::name)
        .orElseThrow();
  }
}
