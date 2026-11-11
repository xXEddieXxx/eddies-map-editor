package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
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
