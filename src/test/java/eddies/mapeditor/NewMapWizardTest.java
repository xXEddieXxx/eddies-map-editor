package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  @DisplayName("The nations step starts with three real nations, which the map gets")
  @Test
  void startsWithNations() throws Exception {
    final NewMapWizard wizard = givenWizard();
    wizard.name.setText("Island");

    final MapProject made = wizard.making().call().project();

    assertThat(wizard.nations.getText().lines())
        .as("typed text, not an example in grey that the count below does not count")
        .containsExactly("Germans", "Russians", "British");
    assertThat(MapXml.nations(made.game()))
        .as("what the step shows is what the map gets")
        .containsExactly("Germans", "Russians", "British");
  }

  @DisplayName("The picture step says what was found and shows the outlines over the preview")
  @Test
  void showsWhatWasFound() {
    // given
    final BufferedImage picture = MapPictureTest.givenPicture();
    final MapPicture.DetectedMap found = MapPicture.detect(picture);

    // when
    final String line = NewMapWizard.foundLine(found);
    final BufferedImage preview = NewMapWizard.smaller(picture, found);

    // then
    assertThat(line).as("before Create").isEqualTo("Found 4 territories and 1 sea zone");
    assertThat(countOf(preview, MapCanvas.MARKED))
        .as("the outlines drawn over the picture, which has no such color")
        .isGreaterThan(200);
    assertThat(NewMapWizard.foundLine(MapPicture.detect(blank())))
        .as("a picture without lines says why nothing was found")
        .startsWith("No borders found");
  }

  private static BufferedImage blank() {
    final BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 200, 100);
    g.dispose();
    return image;
  }

  private static long countOf(final BufferedImage image, final Color color) {
    long count = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        // smoothed lines are blended with the picture: near the color counts
        final Color pixel = new Color(image.getRGB(x, y));
        if (Math.abs(pixel.getRed() - color.getRed())
                + Math.abs(pixel.getGreen() - color.getGreen())
                + Math.abs(pixel.getBlue() - color.getBlue())
            < 90) {
          count++;
        }
      }
    }
    return count;
  }

  /** A picture as the wizard's background read hands it on, with what it found. */
  private static NewMapWizard.ReadPicture givenRead(
      final BufferedImage picture, final MapPicture.DetectedMap found) {
    return new NewMapWizard.ReadPicture(picture, found, NewMapWizard.smaller(picture, found));
  }

  @DisplayName("Of two pictures being read, the one chosen last counts, also when read first")
  @Test
  void olderReadFinishingLastIsDropped() throws Exception {
    // given: a big picture chosen, then a small one, whose read ends first
    final NewMapWizard wizard = givenWizard();
    wizard.name.setText("Island");
    final BufferedImage chosenLast = MapPictureTest.givenPicture();
    final int first = wizard.startRead("big.png");
    final int second = wizard.startRead("small.png");

    // when
    wizard.finishRead(second, "small.png", givenRead(chosenLast, MapPicture.detect(chosenLast)));
    wizard.finishRead(first, "big.png", givenRead(blank(), MapPicture.detect(blank())));
    final MapProject made = wizard.making().call().project();

    // then
    assertThat(MapXml.territories(made.game()))
        .as("the picture chosen last, not the one whose read ended last")
        .hasSize(5);
    assertThat(wizard.next.isEnabled()).as("no longer waiting").isTrue();
  }

  @DisplayName("Remove while a picture is read leaves the wizard without one")
  @Test
  void removeCancelsTheRead() throws Exception {
    // given
    final NewMapWizard wizard = givenWizard();
    wizard.name.setText("Island");
    final BufferedImage picture = MapPictureTest.givenPicture();
    final int read = wizard.startRead("map.png");

    // when
    wizard.removePicture();
    wizard.finishRead(read, "map.png", givenRead(picture, MapPicture.detect(picture)));
    final MapProject made = wizard.making().call().project();

    // then
    assertThat(made.getFiles().picture()).as("the picture does not come back").isEmpty();
    assertThat(MapXml.territories(made.game())).as("nor its territories").isEmpty();
  }

  @DisplayName("Create takes what the picture step found, without searching the picture again")
  @Test
  void createReusesWhatWasFound() throws Exception {
    // given: what was found differs from what a second search would find, to tell them apart
    final NewMapWizard wizard = givenWizard();
    wizard.name.setText("Island");
    final BufferedImage picture = MapPictureTest.givenPicture();
    final MapPicture.DetectedMap all = MapPicture.detect(picture);
    final MapPicture.DetectedMap found =
        new MapPicture.DetectedMap(all.size(), all.territories().subList(0, 1), List.of());

    // when
    wizard.finishRead(wizard.startRead("map.png"), "map.png", givenRead(picture, found));
    final MapProject made = wizard.making().call().project();

    // then
    assertThat(MapXml.territories(made.game()))
        .as("the territories of what was found and shown")
        .containsExactly(found.territories().getFirst().name());
  }
}
