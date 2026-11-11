package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.ReliefMaker.Touch;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ReliefMakerTest {
  private static final Rectangle MAP = new Rectangle(200, 150);

  @DisplayName("The relief is as big as the map and see-through inside the land")
  @Test
  void mapSizedAndClearInside() {
    final BufferedImage relief = givenRelief(EnumSet.noneOf(Touch.class));

    assertThat(relief.getWidth()).as("the map's width").isEqualTo(200);
    assertThat(relief.getHeight()).as("the map's height").isEqualTo(150);
    assertThat(alpha(relief, 65, 70))
        .as("far from every border the map shows through")
        .isZero();
  }

  @DisplayName("A coast gets a dark line")
  @Test
  void darkCoastLine() {
    final BufferedImage relief = givenRelief(EnumSet.noneOf(Touch.class));

    final Color coast = new Color(relief.getRGB(40, 70), true);

    assertThat(coast.getAlpha()).as("the line covers the map").isGreaterThan(150);
    assertThat(coast.getRed()).as("the line is dark").isLessThan(60);
  }

  @DisplayName("Light from the top left: land borders facing it are light, the others dark")
  @Test
  void lightFromTopLeft() {
    final BufferedImage relief = givenRelief(EnumSet.noneOf(Touch.class));

    final Color facing = new Color(relief.getRGB(92, 70), true);
    final Color away = new Color(relief.getRGB(88, 70), true);

    assertThat(facing.getAlpha()).as("the east land's west border is drawn").isPositive();
    assertThat(facing.getRed()).as("and lit").isGreaterThan(200);
    assertThat(away.getAlpha()).as("the west land's east border is drawn").isPositive();
    assertThat(away.getRed()).as("and in shadow").isLessThan(60);
  }

  @DisplayName("Waves follow the coast out in the sea only when asked for")
  @Test
  void wavesWhenAsked() {
    final BufferedImage without = givenRelief(EnumSet.noneOf(Touch.class));
    final BufferedImage with = givenRelief(EnumSet.of(Touch.WAVES));

    assertThat(strongestAlpha(without, 21, 28))
        .as("13 to 19 pixels out at sea nothing is drawn without waves")
        .isZero();
    assertThat(strongestAlpha(with, 21, 28))
        .as("the outer wave lies there")
        .isPositive();
  }

  @DisplayName("A part of the map is drawn as in the whole relief")
  @Test
  void partAsWhole() {
    final BufferedImage whole = givenRelief(EnumSet.allOf(Touch.class));

    final BufferedImage part =
        ReliefMaker.make(
            new Rectangle(30, 50, 80, 40),
            givenPolygons(),
            Set.of("Sea"),
            50,
            EnumSet.allOf(Touch.class));

    assertThat(part.getRGB(10, 20))
        .as("the coast and the texture at the same place")
        .isEqualTo(whole.getRGB(40, 70));
    assertThat(part.getRGB(40, 20)).as("inside the land").isEqualTo(whole.getRGB(70, 70));
  }

  private static BufferedImage givenRelief(final Set<Touch> touches) {
    return ReliefMaker.make(MAP, givenPolygons(), Set.of("Sea"), 50, touches);
  }

  /** Two land territories side by side, from x 40 to 90 and 90 to 140, in a sea. */
  private static Map<String, List<Polygon>> givenPolygons() {
    return Map.of(
        "Sea", List.of(rectangle(0, 0, 200, 150)),
        "West", List.of(rectangle(40, 30, 90, 110)),
        "East", List.of(rectangle(90, 30, 140, 110)));
  }

  private static Polygon rectangle(final int x1, final int y1, final int x2, final int y2) {
    return new Polygon(new int[] {x1, x2, x2, x1}, new int[] {y1, y1, y2, y2}, 4);
  }

  private static int alpha(final BufferedImage image, final int x, final int y) {
    return image.getRGB(x, y) >>> 24;
  }

  /** The strongest alpha on the row through the land's middle between the x. */
  private static int strongestAlpha(final BufferedImage image, final int from, final int to) {
    return IntStream.rangeClosed(from, to).map(x -> alpha(image, x, 70)).max().orElseThrow();
  }
}
