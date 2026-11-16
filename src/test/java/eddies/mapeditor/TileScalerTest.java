package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TileScalerTest {
  @TempDir Path folder;

  /** The test picture in tiles, as a map keeps it; the tile at a column and row. */
  private BiFunction<Integer, Integer, Optional<Path>> givenTiles(final BufferedImage picture)
      throws Exception {
    final Path map = Files.createDirectories(folder.resolve("map"));
    MapFolder.writeTiles(map, picture);
    return (column, row) ->
        Optional.of(map.resolve("baseTiles").resolve(column + "_" + row + ".png"))
            .filter(Files::exists);
  }

  private static BufferedImage stitched(final Path tiles, final Dimension size) throws Exception {
    final BufferedImage whole =
        new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = whole.createGraphics();
    final int tile = MapFiles.tileSize();
    for (int column = 0; column * tile < size.width; column++) {
      for (int row = 0; row * tile < size.height; row++) {
        final Path file = tiles.resolve(column + "_" + row + ".png");
        assertThat(file).as("every tile of the picture is written").exists();
        g.drawImage(ImageIO.read(file.toFile()), column * tile, row * tile, null);
      }
    }
    g.dispose();
    return whole;
  }

  private static Set<Integer> colors(final BufferedImage image) {
    final Set<Integer> colors = new HashSet<>();
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        colors.add(image.getRGB(x, y));
      }
    }
    return colors;
  }

  @DisplayName("A flat picture made bigger tile by tile keeps its colors and its lines whole,"
      + " with no seam where the tiles meet")
  @Test
  void biggerFromTiles() throws Exception {
    // given: 400 x 300 in 2 by 2 tiles
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Dimension to = new Dimension(600, 450);
    final List<Integer> progress = new ArrayList<>();

    // when: 150 %, 3 by 2 tiles
    final int written =
        TileScaler.scale(
            givenTiles(picture),
            new Dimension(400, 300),
            to,
            true,
            folder.resolve("bigger"),
            progress::add);
    final BufferedImage bigger = stitched(folder.resolve("bigger"), to);

    // then
    assertThat(written).as("3 by 2 tiles").isEqualTo(6);
    assertThat(progress).as("each tile told, with how many there are").hasSize(6).containsOnly(6);
    assertThat(colors(bigger))
        .as("no color between two others: each pixel is one of the picture's")
        .isSubsetOf(colors(picture));
    assertThat(IntStream.range(0, 600))
        .as("the 3 px line at y 110 is dark all along at y 165, across the tiles' seams")
        .allMatch(x -> new Color(bigger.getRGB(x, 165)).getRed() < 120);
    assertThat(IntStream.range(0, 450))
        .as("the line down at x 200 is dark all along at x 300")
        .allMatch(y -> new Color(bigger.getRGB(300, y)).getRed() < 120);
    assertThat(bigger.getRGB(50, 50)).as("a filled area's color").isEqualTo(picture.getRGB(30, 30));
    assertThat(bigger.getRGB(590, 440))
        .as("the bottom right corner, drawn from the last tile's edge")
        .isEqualTo(picture.getRGB(395, 295));
  }

  @DisplayName("Made smaller tile by tile, the lines stay closed and the tiles beyond the picture"
      + " are not written")
  @Test
  void smallerFromTiles() throws Exception {
    // given
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Dimension to = new Dimension(200, 150);

    // when: 50 %, one tile
    final int written =
        TileScaler.scale(
            givenTiles(picture),
            new Dimension(400, 300),
            to,
            true,
            folder.resolve("smaller"),
            all -> {});
    final BufferedImage smaller = stitched(folder.resolve("smaller"), to);

    // then
    assertThat(written).isEqualTo(1);
    assertThat(folder.resolve("smaller").resolve("1_0.png")).doesNotExist();
    assertThat(IntStream.range(0, 200))
        .as("the 3 px line at y 110 is still dark all along at y 55")
        .allMatch(x -> new Color(smaller.getRGB(x, 55)).getRed() < 120);
    assertThat(smaller.getRGB(199, 149) >>> 24).as("the last pixel is drawn").isEqualTo(255);
    final BufferedImage tile =
        ImageIO.read(folder.resolve("smaller").resolve("0_0.png").toFile());
    assertThat(tile.getRGB(200, 10) >>> 24).as("beyond the picture the tile is see-through").isZero();
    assertThat(tile.getRGB(10, 150) >>> 24).isZero();
  }

  @DisplayName("A painted picture made a quarter as big is halved step by step, so thin lines"
      + " are averaged in, not left out")
  @Test
  void quarterFromTiles() throws Exception {
    // given: a painted picture, a gradient with one dark line
    final BufferedImage painted = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 300; y++) {
      for (int x = 0; x < 400; x++) {
        painted.setRGB(x, y, new Color(200, (x * 255) / 400, (y * 255) / 300).getRGB());
      }
    }
    final Graphics2D g = painted.createGraphics();
    g.setColor(Color.BLACK);
    g.fillRect(0, 100, 400, 1);
    g.dispose();
    final Dimension to = new Dimension(100, 75);

    // when: 25 %
    TileScaler.scale(
        givenTiles(painted), new Dimension(400, 300), to, false, folder.resolve("quarter"), all -> {});
    final BufferedImage quarter = stitched(folder.resolve("quarter"), to);

    // then
    assertThat(new Color(quarter.getRGB(50, 25)).getRed())
        .as("the 1 px line darkens its row, which a plain 25 % draw would skip")
        .isLessThan(190);
    assertThat(new Color(quarter.getRGB(50, 10)).getRed()).as("the gradient's red").isEqualTo(200);
  }
}
