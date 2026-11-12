package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The new-map wizard's steps, as a panel without its dialog. */
final class NewMapWizardTest {
  @TempDir Path maps;

  private NewMapWizard givenWizard() {
    return new NewMapWizard(maps, made -> {});
  }

  @DisplayName("Next waits for a name")
  @Test
  void waitsForName() {
    final NewMapWizard wizard = givenWizard();
    final boolean enabledWithoutName = wizard.next.isEnabled();

    wizard.name.setText("Pacific 1942");
    final boolean enabledWithName = wizard.next.isEnabled();
    wizard.name.setText("   ");

    assertThat(enabledWithoutName).as("no name yet, no Next").isFalse();
    assertThat(enabledWithName).as("with a name, Next").isTrue();
    assertThat(wizard.next.isEnabled()).as("only blanks are no name").isFalse();
    assertThat(wizard.nameError.hasProblem()).as("blank is not an error to show").isFalse();
  }

  @DisplayName("A name taken in the folder shows below the field and stops Next")
  @Test
  void showsTakenName() throws IOException {
    Files.createDirectory(maps.resolve("pacific_1942"));
    final NewMapWizard wizard = givenWizard();

    wizard.name.setText("Pacific 1942");

    assertThat(wizard.nameError.hasProblem()).as("the name is taken").isTrue();
    assertThat(wizard.nameError.getText())
        .as("says why, under the field")
        .contains("pacific_1942");
    assertThat(wizard.next.isEnabled()).as("no Next with a taken name").isFalse();
  }

  @DisplayName("The picture step offers Skip and waits for a picture before Next")
  @Test
  void offersSkipWithoutPicture() {
    final NewMapWizard wizard = givenWizard();
    wizard.name.setText("Island");

    wizard.next.doClick();

    assertThat(wizard.skip.isVisible()).as("no picture yet, Skip").isTrue();
    assertThat(wizard.next.isEnabled()).as("Next only with a picture").isFalse();
  }

  @DisplayName("The nations example is drawn in the light placeholder color, not like typed text")
  @Test
  void nationsExampleIsPlaceholder() throws UnsupportedLookAndFeelException {
    final LookAndFeel before = UIManager.getLookAndFeel();
    Theme.install();
    UIManager.setLookAndFeel(new FlatLightLaf());
    try {
      final NewMapWizard wizard = givenWizard();

      final int darkest = darkestPixel(wizard);

      assertThat(darkest)
          .as("no stroke darker than the placeholder color, eg secondary text")
          .isGreaterThanOrEqualTo(brightness(Theme.getPlaceholderColor()) - 2);
    } finally {
      Theme.uninstall();
      UIManager.setLookAndFeel(before);
    }
  }

  private static int darkestPixel(final NewMapWizard wizard) {
    wizard.nations.setSize(300, 150);
    final BufferedImage image = new BufferedImage(300, 150, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    wizard.nations.paint(g);
    g.dispose();
    int darkest = 255;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        darkest = Math.min(darkest, brightness(new Color(image.getRGB(x, y))));
      }
    }
    return darkest;
  }

  private static int brightness(final Color color) {
    return (color.getRed() + color.getGreen() + color.getBlue()) / 3;
  }
}
