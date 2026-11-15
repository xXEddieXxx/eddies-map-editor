package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.ReliefMaker.Touch;
import java.awt.Color;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
            EnumSet.allOf(Touch.class),
            Map.of());

    assertThat(part.getRGB(10, 20))
        .as("the coast and the texture at the same place")
        .isEqualTo(whole.getRGB(40, 70));
    assertThat(part.getRGB(40, 20)).as("inside the land").isEqualTo(whole.getRGB(70, 70));
  }

  @DisplayName("A textured territory has its texture, repeated from the texture's offset on")
  @Test
  void textureFollowsOffset() {
    // given: a texture of a red and a blue pixel side by side, on West only
    final BufferedImage texture = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
    texture.setRGB(0, 0, 0xFF0000);
    texture.setRGB(1, 0, 0x0000FF);

    // when
    final BufferedImage atZero = givenTexturedRelief(texture, new Point());
    final BufferedImage moved = givenTexturedRelief(texture, new Point(1, 0));

    // then
    final Color west = new Color(atZero.getRGB(65, 70), true);
    assertThat(west.getAlpha()).as("the texture covers most of the map below").isGreaterThan(150);
    assertThat(west.getBlue())
        .as("x 65 is the texture's second, blue pixel")
        .isGreaterThan(west.getRed());
    assertThat(alpha(atZero, 115, 70)).as("East, untextured, stays clear").isZero();
    final Color westMoved = new Color(moved.getRGB(65, 70), true);
    assertThat(westMoved.getRed())
        .as("moved by one pixel, its first, red pixel is there")
        .isGreaterThan(westMoved.getBlue());
  }

  @DisplayName("Where a texture is see-through, the relief is as without it")
  @Test
  void clearTextureLeavesRelief() {
    // given: a texture of a see-through and a red pixel side by side, on West only
    final BufferedImage texture = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
    texture.setRGB(1, 0, 0xFFFF0000);

    // when
    final BufferedImage textured = givenTexturedRelief(texture, new Point());
    final BufferedImage plain = givenRelief(EnumSet.noneOf(Touch.class));

    // then
    assertThat(textured.getRGB(64, 70))
        .as("x 64 is the see-through pixel: the relief as it was")
        .isEqualTo(plain.getRGB(64, 70));
    assertThat(textured.getRGB(44, 70))
        .as("near the border, with shading, too")
        .isEqualTo(plain.getRGB(44, 70));
    assertThat(new Color(textured.getRGB(65, 70), true).getRed())
        .as("x 65 is the red pixel")
        .isGreaterThan(200);
  }

  @DisplayName("A relief remade where a border was erased is the one made anew for the new outlines")
  @Test
  void remadeRegionMatchesWholeRelief() {
    // given
    final Rectangle map = new Rectangle(400, 150);
    final Map<String, List<Polygon>> before =
        Map.of(
            "Sea", List.of(rectangle(0, 0, 400, 150)),
            "West", List.of(rectangle(40, 30, 90, 110)),
            "East", List.of(rectangle(90, 30, 140, 110)),
            "Far", List.of(rectangle(300, 30, 360, 110)));
    final Map<String, List<Polygon>> merged =
        Map.of(
            "Sea", List.of(rectangle(0, 0, 400, 150)),
            "West", List.of(rectangle(40, 30, 140, 110)),
            "Far", List.of(rectangle(300, 30, 360, 110)));
    final Set<Touch> touches = EnumSet.allOf(Touch.class);
    final BufferedImage old = ReliefMaker.make(map, before, Set.of("Sea"), 50, touches, Map.of());

    // when
    final BufferedImage remade =
        ReliefMaker.remade(
            old,
            new Rectangle(85, 25, 10, 90),
            merged,
            Set.of("Sea"),
            new ReliefMaker.Look(50, touches),
            Map.of());

    // then
    final BufferedImage anew = ReliefMaker.make(map, merged, Set.of("Sea"), 50, touches, Map.of());
    assertThat(old.getRGB(90, 70))
        .as("the border's shading was there")
        .isNotEqualTo(anew.getRGB(90, 70));
    for (int y = 0; y < map.height; y++) {
      for (int x = 0; x < map.width; x++) {
        assertThat(remade.getRGB(x, y)).as("pixel %d, %d", x, y).isEqualTo(anew.getRGB(x, y));
      }
    }
  }

  @DisplayName("How a relief was made reads back from its text; another text is none")
  @Test
  void lookReadsBack() {
    // given
    final ReliefMaker.Look look = new ReliefMaker.Look(70, EnumSet.of(Touch.WAVES));
    final Properties properties = new Properties();
    properties.setProperty(ReliefMaker.Look.KEY, look.text());
    final Properties byHand = new Properties();
    byHand.setProperty(ReliefMaker.Look.KEY, "strong");

    // when
    final var read = ReliefMaker.Look.of(properties);
    final var none = ReliefMaker.Look.of(new Properties());
    final var unread = ReliefMaker.Look.of(byHand);

    // then
    assertThat(read).as("as written").contains(look);
    assertThat(none).as("a relief made elsewhere").isEmpty();
    assertThat(unread).as("a value that does not read").isEmpty();
  }

  private static BufferedImage givenTexturedRelief(final BufferedImage texture, final Point at) {
    return ReliefMaker.make(
        MAP,
        givenPolygons(),
        Set.of("Sea"),
        50,
        EnumSet.noneOf(Touch.class),
        Map.of("West", new Textures.Fill(texture, at)));
  }

  private static BufferedImage givenRelief(final Set<Touch> touches) {
    return ReliefMaker.make(MAP, givenPolygons(), Set.of("Sea"), 50, touches, Map.of());
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
