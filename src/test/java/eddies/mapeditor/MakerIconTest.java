package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import eddies.mapeditor.MakerIcon.Glyph;

final class MakerIconTest {

  private static BufferedImage givenPicture() {
    return new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
  }

  @DisplayName("Each icon draws something, also without a component around it")
  @ParameterizedTest
  @EnumSource(Glyph.class)
  void drawsTheGlyph(final Glyph glyph) {
    final BufferedImage picture = givenPicture();
    final Graphics2D g = picture.createGraphics();

    new MakerIcon(glyph, 32).paintIcon(null, g, 0, 0);
    g.dispose();

    assertThat(
            Arrays.stream(picture.getRGB(0, 0, 32, 32, null, 0, 32))
                .anyMatch(pixel -> pixel >>> 24 != 0))
        .as("%s has a drawing", glyph)
        .isTrue();
  }
}
