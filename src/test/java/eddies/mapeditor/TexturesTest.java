package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntBinaryOperator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TexturesTest {
  @TempDir Path folder;

  @DisplayName("Each premade texture is seamless: across its edges it goes on as inside")
  @Test
  void premadeAreSeamless() {
    for (final String name : Textures.PREMADE) {
      // given
      final BufferedImage texture = Textures.make(name);

      // when
      final IntBinaryOperator columns = (j, at) -> texture.getRGB(at, j);
      final IntBinaryOperator rows = (j, at) -> texture.getRGB(j, at);

      // then
      final int last = texture.getWidth() - 1;
      assertThat(between(columns, last, 0, texture.getWidth()))
          .as(name + ": the right edge goes on at the left one as columns inside do")
          .isLessThanOrEqualTo(mostInside(columns, texture.getWidth()));
      assertThat(between(rows, last, 0, texture.getWidth()))
          .as(name + ": the bottom edge goes on at the top one as rows inside do")
          .isLessThanOrEqualTo(mostInside(rows, texture.getWidth()));
    }
  }

  @DisplayName("A premade texture only hints: sparse strokes, at least 70 % see-through")
  @Test
  void premadeAreMostlySeeThrough() {
    for (final String name : Textures.PREMADE) {
      // given
      final BufferedImage texture = Textures.make(name);

      // when
      final long clear =
          java.util.Arrays.stream(pixels(texture)).filter(argb -> argb >>> 24 == 0).count();

      // then
      assertThat(clear)
          .as(name + ": the owner's color shows between the strokes")
          .isGreaterThanOrEqualTo((long) (0.7 * texture.getWidth() * texture.getHeight()));
    }
  }

  @DisplayName("A premade texture is the same each time it is made")
  @Test
  void premadeAreTheSameEachTime() {
    for (final String name : Textures.PREMADE) {
      // given
      final BufferedImage first = Textures.make(name);

      // when
      final BufferedImage second = Textures.make(name);

      // then
      assertThat(pixels(second))
          .as(name + ": made again, every pixel alike")
          .isEqualTo(pixels(first));
    }
  }

  @DisplayName("The map's own textures follow the premade ones, by file name")
  @Test
  void ownAfterPremade() throws Exception {
    // given
    final Path textures = Files.createDirectories(folder.resolve(Textures.FOLDER));
    final BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    ImageIO.write(picture, "png", textures.resolve("lava.png").toFile());
    ImageIO.write(picture, "png", textures.resolve("ash.png").toFile());
    Files.writeString(textures.resolve("notes.txt"), "no picture");

    // when
    final List<String> names = Textures.names(MapFiles.none().withFolder(folder));

    // then
    assertThat(names.subList(0, Textures.PREMADE.size()))
        .as("the premade ones first")
        .isEqualTo(Textures.PREMADE);
    assertThat(names.subList(Textures.PREMADE.size(), names.size()))
        .as("then the pictures in textures, by name")
        .containsExactly("ash.png", "lava.png");
  }

  /** How much two columns or rows differ, on average per pixel. */
  private static double between(
      final IntBinaryOperator pixel, final int a, final int b, final int size) {
    double sum = 0;
    for (int j = 0; j < size; j++) {
      sum += difference(pixel.applyAsInt(j, a), pixel.applyAsInt(j, b));
    }
    return sum / size;
  }

  /** The most two neighbouring columns or rows inside differ. */
  private static double mostInside(final IntBinaryOperator pixel, final int size) {
    double most = 0;
    for (int at = 0; at < size - 1; at++) {
      most = Math.max(most, between(pixel, at, at + 1, size));
    }
    return most;
  }

  private static int difference(final int a, final int b) {
    return Math.abs((a >>> 24) - (b >>> 24))
        + Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF))
        + Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF))
        + Math.abs((a & 0xFF) - (b & 0xFF));
  }

  private static int[] pixels(final BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }
}
