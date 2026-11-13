package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.TextureFinder.Found;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class TextureFinderTest {
  // land repeats every 20 px across and 16 down, sea every 24 both ways
  private static final int LAND_ACROSS = 20;
  private static final int LAND_DOWN = 16;
  private static final int SEA_PERIOD = 24;

  @DisplayName("Alike territories share a texture, land and sea apart, a flat one has none")
  @Test
  void groupsAlikeTerritories() {
    // when
    final List<Found> found = givenFound();

    // then
    assertThat(found).as("one land and one sea texture").hasSize(2);
    assertThat(land(found).territories())
        .as("the land with the pattern, also the one with a name on it")
        .containsExactlyInAnyOrder("A1", "A2", "A3");
    assertThat(sea(found).territories())
        .as("the sea zones")
        .containsExactlyInAnyOrder("B1", "B2");
  }

  @DisplayName("A repeating pattern gives a tile of one period, lined up with the map")
  @Test
  void tileIsOnePeriod() {
    // given
    final BufferedImage picture = givenPicture();

    // when
    final List<Found> found = givenFound();

    // then
    final Found land = land(found);
    assertThat(land.tile().getWidth()).as("one period across").isEqualTo(LAND_ACROSS);
    assertThat(land.tile().getHeight()).as("one period down").isEqualTo(LAND_DOWN);
    assertThat(sea(found).tile().getWidth()).as("the sea's period").isEqualTo(SEA_PERIOD);
    // repeated from its offset, as the relief does, it is the map's pattern in A2
    final Point at = land.offset();
    for (final Point p : List.of(new Point(250, 50), new Point(333, 111))) {
      final int x = Math.floorMod(p.x - at.x, land.tile().getWidth());
      final int y = Math.floorMod(p.y - at.y, land.tile().getHeight());
      assertThat(land.tile().getRGB(x, y))
          .as("the tile's pixel for " + p + " is the map's")
          .isEqualTo(picture.getRGB(p.x, p.y));
    }
  }

  @DisplayName("Taking found textures sets every territory's texture and offset in one step")
  @Test
  void takingIsOneStep() {
    // given
    final MapProject project = MapProject.newEmpty();
    final List<Found> found = givenFound();
    final Map<String, Found> taken = Map.of("Moss.png", land(found), "Waves.png", sea(found));

    // when
    project.editFiles(files -> TextureFinder.withFound(files, taken));
    final Map<String, String> textures = project.getFiles().textures();
    final Point offset = project.getFiles().textureOffset("Moss.png");
    project.undo();

    // then
    assertThat(textures)
        .as("each territory has its texture")
        .isEqualTo(
            Map.of(
                "A1", "Moss.png", "A2", "Moss.png", "A3", "Moss.png", "B1", "Waves.png", "B2",
                "Waves.png"));
    assertThat(offset).as("where the tile was taken").isEqualTo(land(found).offset());
    assertThat(project.getFiles().textures()).as("one undo takes them all back").isEmpty();
  }

  @DisplayName("A find with a tile under 64 px of a few territories, eg icons, starts unticked")
  @Test
  void smallFindsStartUnticked() throws Exception {
    // given
    final Found big = givenFoundOfTile(100, List.of("A1"));
    final Found small = givenFoundOfTile(40, List.of("Ring"));
    final Found ridges =
        givenFoundOfTile(40, List.of("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8"));
    final List<Map<String, Found>> chosen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () ->
            chosen.add(
                FindTexturesDialog.content(List.of(big, small, ridges), List.of())
                    .chosen()
                    .get()));

    // then
    assertThat(chosen.getFirst().values())
        .as("the big one and the small one of many territories, eg narrow mountains, are taken")
        .containsExactly(big, ridges);
  }

  private static Found givenFoundOfTile(final int size, final List<String> territories) {
    return new Found(
        new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), new Point(), false, territories);
  }

  private static List<Found> givenFound() {
    return TextureFinder.find(givenPicture(), givenPolygons(), Set.of("B1", "B2"));
  }

  private static Found land(final List<Found> found) {
    return found.stream().filter(f -> !f.water()).findFirst().orElseThrow();
  }

  private static Found sea(final List<Found> found) {
    return found.stream().filter(Found::water).findFirst().orElseThrow();
  }

  /**
   * Six territories of 200 x 150: A1, A2, B1 above, A3, B2, C below; A patterned land, A1 with a
   * name across it, B patterned sea, C flat; black borders between.
   */
  private static BufferedImage givenPicture() {
    final BufferedImage picture = new BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 300; y++) {
      for (int x = 0; x < 600; x++) {
        final String territory = territoryAt(x, y);
        final int rgb;
        if (territory.startsWith("A")) {
          final int v = (x % LAND_ACROSS * 7 + y % LAND_DOWN * 11) % 40;
          rgb = new Color(60 + v, 120 + v, 40).getRGB();
        } else if (territory.startsWith("B")) {
          final int v = (x % SEA_PERIOD * 5 + y % SEA_PERIOD * 3) % 50;
          rgb = new Color(30, 60 + v, 150 + v).getRGB();
        } else {
          rgb = new Color(200, 180, 140).getRGB();
        }
        picture.setRGB(x, y, rgb);
      }
    }
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.BLACK);
    g.fillRect(199, 0, 3, 300);
    g.fillRect(399, 0, 3, 300);
    g.fillRect(0, 149, 600, 3);
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
    g.drawString("Silver Moss", 20, 85);
    g.dispose();
    return picture;
  }

  private static String territoryAt(final int x, final int y) {
    return List.of(List.of("A1", "A2", "B1"), List.of("A3", "B2", "C"))
        .get(y / 150)
        .get(x / 200);
  }

  private static Map<String, List<Polygon>> givenPolygons() {
    final Map<String, List<Polygon>> polygons = new LinkedHashMap<>();
    for (int row = 0; row < 2; row++) {
      for (int column = 0; column < 3; column++) {
        final int x = column * 200;
        final int y = row * 150;
        polygons.put(
            territoryAt(x, y),
            List.of(
                new Polygon(
                    new int[] {x, x + 200, x + 200, x}, new int[] {y, y, y + 150, y + 150}, 4)));
      }
    }
    return polygons;
  }
}
