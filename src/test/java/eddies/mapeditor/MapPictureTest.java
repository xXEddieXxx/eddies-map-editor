package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import eddies.mapeditor.MapPicture.DetectedMap;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapPicture.Neighbours;

final class MapPictureTest {

  /**
   * A 400x300 map: four land areas in a 2x2 grid split by black lines, a blue sea below them, and
   * in the top left area a small black ring, like a letter, around a few white pixels.
   */
  static BufferedImage givenPicture() {
    final BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(new Color(230, 180, 180));
    g.fillRect(0, 0, 200, 110);
    g.setColor(new Color(180, 230, 180));
    g.fillRect(200, 0, 200, 110);
    g.setColor(new Color(240, 230, 150));
    g.fillRect(0, 110, 200, 110);
    g.setColor(new Color(210, 210, 210));
    g.fillRect(200, 110, 200, 110);
    g.setColor(new Color(60, 110, 200));
    g.fillRect(0, 220, 400, 80);
    g.setColor(Color.BLACK);
    g.fillRect(0, 109, 400, 3);
    g.fillRect(199, 0, 3, 220);
    g.fillRect(0, 219, 400, 3);
    g.fillRect(60, 40, 8, 8);
    g.setColor(Color.WHITE);
    g.fillRect(62, 42, 4, 4);
    g.dispose();
    return image;
  }

  @DisplayName("The areas between the lines become territories, the blue one a sea zone")
  @Test
  void findsTerritories() {
    final DetectedMap map = MapPicture.detect(givenPicture());

    assertThat(map.territories())
        .as("4 land areas and the sea; the white inside the small ring is too small")
        .extracting(DetectedTerritory::name)
        .containsExactly("Territory 1", "Territory 2", "Territory 3", "Territory 4", "Sea Zone 1");
    assertThat(map.territories())
        .as("only the blue area is water")
        .filteredOn(DetectedTerritory::water)
        .extracting(DetectedTerritory::name)
        .containsExactly("Sea Zone 1");
    assertThat(map.territories())
        .as("each center lies inside its outline")
        .allMatch(t -> t.outlines().get(0).contains(t.center()));
    assertThat(map.size().width).as("the picture's size").isEqualTo(400);
  }

  @DisplayName("Outlines of areas across a line meet on the line, not beside it with a gap")
  @Test
  void outlinesMeetOnTheLine() {
    for (final int lineWidth : List.of(1, 3)) {
      final List<DetectedTerritory> territories =
          MapPicture.detect(givenTwoAreas(lineWidth)).territories();

      final Rectangle left = territories.get(0).outlines().getFirst().getBounds();
      final Rectangle right = territories.get(1).outlines().getFirst().getBounds();

      assertThat(left.x + left.width)
          .as("%d px line: the left area reaches into the line at x 100", lineWidth)
          .isGreaterThanOrEqualTo(100);
      assertThat(right.x - (left.x + left.width))
          .as("%d px line: no strip of the line between the two outlines", lineWidth)
          .isLessThanOrEqualTo(1);
    }
  }

  /** Two areas of 100x100 beside each other, a black line of this width from x 100 between. */
  private static BufferedImage givenTwoAreas(final int lineWidth) {
    final BufferedImage image =
        new BufferedImage(200 + lineWidth, 100, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(new Color(230, 180, 180));
    g.fillRect(0, 0, image.getWidth(), 100);
    g.setColor(Color.BLACK);
    g.fillRect(100, 0, lineWidth, 100);
    g.dispose();
    return image;
  }

  @DisplayName("A center is inside its area, also when the area's middle is on a line")
  @Test
  void centerOffTheLine() {
    // a U on black: its mean pixel lies in the black between its arms, the row's longest run
    // ends on black pixels the U grows into
    final BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(new Color(230, 180, 180));
    g.fillRect(0, 0, 30, 120);
    g.fillRect(90, 0, 30, 120);
    g.fillRect(0, 70, 120, 50);
    g.dispose();

    final List<DetectedTerritory> territories = MapPicture.detect(image).territories();

    assertThat(territories)
        .as("each center is on a pixel of the area, not on a black line")
        .allMatch(t -> (image.getRGB(t.center().x, t.center().y) & 0xFFFFFF) != 0);
  }

  @DisplayName("The picture's lines are found as wide and in the color most of them have")
  @Test
  void findsLineStyle() {
    final MapPicture.LineStyle thick = MapPicture.lineStyle(givenPicture());
    final MapPicture.LineStyle thin = MapPicture.lineStyle(givenTwoAreas(1));

    assertThat(thick).as("3 px black lines").isEqualTo(new MapPicture.LineStyle(3, Color.BLACK));
    assertThat(thin.width()).as("a 1 px line").isEqualTo(1);
  }

  @DisplayName("A drawn line's ends go to the borders near them; one far from any is refused")
  @Test
  void snapsLineToBorders() {
    // given: the top right area is x 202-399, y 0-108, the line left of it at x 199-201
    final BufferedImage picture = givenPicture();

    // when
    final Optional<List<Point>> shortOfLine =
        MapPicture.snapToBorders(picture, List.of(new Point(210, 50), new Point(390, 50)), 12);
    final Optional<List<Point>> past =
        MapPicture.snapToBorders(picture, List.of(new Point(190, 50), new Point(390, 50)), 12);
    final Optional<List<Point>> far =
        MapPicture.snapToBorders(picture, List.of(new Point(300, 50), new Point(390, 50)), 12);

    // then
    assertThat(shortOfLine.orElseThrow().getFirst())
        .as("a start short of the line goes on to it")
        .isEqualTo(new Point(201, 50));
    assertThat(shortOfLine.orElseThrow().getLast())
        .as("an end near the picture's edge goes to it")
        .matches(end -> end.x >= 399);
    assertThat(past.orElseThrow().getFirst())
        .as("a start past the line is cut back to it")
        .isEqualTo(new Point(199, 50));
    assertThat(far).as("a start far from any border: nothing").isEmpty();
  }

  @DisplayName("A line short of a near border keeps it across a narrow area; the areas split")
  @Test
  void snapKeepsTheNearBorder() {
    // given: the yellow area is x 0-198, y 112-218, between lines at y 109-111 and y 219-221
    final BufferedImage picture = givenPicture();
    final int areasBefore = MapPicture.detect(picture).territories().size();

    // when: from 3 px below the upper line down past the lower one, as at zoom 0.1
    final List<Point> snapped =
        MapPicture.snapToBorders(
                picture, List.of(new Point(100, 115), new Point(100, 290)), 120)
            .orElseThrow();
    final Optional<List<Point>> collapsed =
        MapPicture.snapToBorders(picture, List.of(new Point(150, 111), new Point(150, 101)), 12);
    final BufferedImage drawn = givenDrawn(picture, snapped);

    // then
    assertThat(snapped.getFirst())
        .as("on to the near line, not cut to the far one")
        .isEqualTo(new Point(100, 111));
    assertThat(MapPicture.detect(drawn).territories())
        .as("the yellow area and the sea it goes on through each split in two")
        .hasSize(areasBefore + 2);
    assertThat(collapsed).as("a line left only on the border: nothing").isEmpty();
  }

  /** The picture with the line drawn into it, 3 px black, as Draw borders does. */
  private static BufferedImage givenDrawn(final BufferedImage picture, final List<Point> line) {
    final BufferedImage drawn =
        new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = drawn.createGraphics();
    g.drawImage(picture, 0, 0, null);
    g.setColor(Color.BLACK);
    g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    for (int i = 1; i < line.size(); i++) {
      g.drawLine(line.get(i - 1).x, line.get(i - 1).y, line.get(i).x, line.get(i).y);
    }
    g.dispose();
    return drawn;
  }

  @DisplayName("A thin grey line between white areas is a border; a pale blue area is sea")
  @Test
  void findsGreyLinesAndPaleSea() {
    // given: two white areas split by a 1 px grey line, a pale blue sea below its dark coast, as
    // in a printed map
    final BufferedImage image = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 200, 100);
    g.setColor(new Color(221, 240, 246));
    g.fillRect(0, 100, 200, 50);
    g.setColor(new Color(160, 160, 160));
    g.fillRect(100, 0, 1, 100);
    g.setColor(new Color(60, 60, 60));
    g.fillRect(0, 100, 200, 1);
    g.dispose();

    // when
    final List<DetectedTerritory> found = MapPicture.detect(image).territories();

    // then
    assertThat(found).as("two lands and the sea").hasSize(3);
    assertThat(found).filteredOn(DetectedTerritory::water).as("the pale blue is sea").hasSize(1);
    assertThat(found)
        .filteredOn(t -> !t.water())
        .as("the land reaches the coast line")
        .allMatch(t -> t.outlines().getFirst().getBounds().getMaxY() >= 99);
  }

  @DisplayName("Where a grey area meets a white one without a line, no border is found")
  @Test
  void edgeOfTwoColorsIsNoBorder() {
    // given: a grey half and a white half, nothing drawn between them
    final BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(new Color(150, 150, 150));
    g.fillRect(0, 0, 100, 100);
    g.setColor(Color.WHITE);
    g.fillRect(100, 0, 100, 100);
    g.dispose();

    // when
    final List<DetectedTerritory> found = MapPicture.detect(image).territories();

    // then
    assertThat(found).as("one area: an edge is not a line").hasSize(1);
  }

  @DisplayName("A blue river across a white area is no border")
  @Test
  void riverIsNoBorder() {
    // given: a white area with a 2 px blue river from edge to edge
    final BufferedImage image = givenWhite(200, 100);
    final Graphics2D g = image.createGraphics();
    g.setColor(new Color(70, 130, 200));
    g.fillRect(100, 0, 2, 100);
    g.dispose();

    // when
    final List<DetectedTerritory> found = MapPicture.detect(image).territories();

    // then
    assertThat(found).as("one area: a colored line is not a border").hasSize(1);
  }

  @DisplayName("Grey borders give the line style, and a drawn line's end snaps to them")
  @Test
  void greyLinesStyleAndSnap() {
    // given: two white areas split by a 1 px grey line at x 100
    final BufferedImage image = givenWhite(200, 100);
    final Graphics2D g = image.createGraphics();
    g.setColor(new Color(160, 160, 160));
    g.fillRect(100, 0, 1, 100);
    g.dispose();

    // when
    final MapPicture.LineStyle style = MapPicture.lineStyle(image);
    final Optional<List<Point>> snapped =
        MapPicture.snapToBorders(image, List.of(new Point(106, 50), new Point(190, 50)), 12);

    // then
    assertThat(style)
        .as("1 px, grey")
        .isEqualTo(new MapPicture.LineStyle(1, new Color(160, 160, 160)));
    assertThat(snapped.orElseThrow().getFirst())
        .as("on to the grey line")
        .isEqualTo(new Point(100, 50));
  }

  @DisplayName("The style is the lines most of the picture has: grey borders over a dark coast")
  @Test
  void styleOfTheMostLines() {
    // given: one dark coast line and four longer grey borders
    final BufferedImage image = givenWhite(400, 200);
    final Graphics2D g = image.createGraphics();
    g.setColor(new Color(160, 160, 160));
    for (int x = 80; x <= 320; x += 80) {
      g.fillRect(x, 0, 1, 200);
    }
    g.setColor(new Color(60, 60, 60));
    g.fillRect(0, 100, 40, 1);
    g.dispose();

    // when
    final MapPicture.LineStyle style = MapPicture.lineStyle(image);

    // then
    assertThat(style.color()).as("the grey of the borders").isEqualTo(new Color(160, 160, 160));
  }

  @DisplayName("A smoothed black line measures as wide as it is drawn, not with its fringe")
  @Test
  void smoothedLineKeepsItsWidth() {
    // given: a 1 px black line drawn smoothed across white, slanted so its edges blend
    final BufferedImage image = givenWhite(200, 200);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(Color.BLACK);
    for (int x = 20; x < 200; x += 40) {
      g.drawLine(x, 0, x + 30, 199);
    }
    g.dispose();

    // when
    final MapPicture.LineStyle style = MapPicture.lineStyle(image);

    // then
    assertThat(style.width())
        .as("the dark core, 1 or 2 px of a slanted line")
        .isLessThanOrEqualTo(2);
    assertThat(style.color())
        .as("dark as its core, not the grey of its smoothed edge")
        .matches(c -> c.getRed() + c.getGreen() + c.getBlue() < 3 * 64);
  }

  private static BufferedImage givenWhite(final int width, final int height) {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, width, height);
    g.dispose();
    return image;
  }

  @DisplayName("Areas across a line are neighbours, diagonal ones are not")
  @Test
  void findsNeighbours() {
    final DetectedMap map = MapPicture.detect(givenPicture());

    assertThat(map.connections())
        .as("named by place: 1 top left, 2 top right, 3 bottom left, 4 bottom right")
        .extracting(MapPictureTest::pair)
        .containsExactlyInAnyOrder(
            Set.of("Territory 1", "Territory 2"),
            Set.of("Territory 1", "Territory 3"),
            Set.of("Territory 2", "Territory 4"),
            Set.of("Territory 3", "Territory 4"),
            Set.of("Territory 3", "Sea Zone 1"),
            Set.of("Territory 4", "Sea Zone 1"));
  }

  private static Set<String> pair(final Neighbours neighbours) {
    return Set.copyOf(List.of(neighbours.t1(), neighbours.t2()));
  }
}
