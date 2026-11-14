package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.MapProject.BorderLine;

/**
 * A picture too big to copy for every line is drawn into itself: no undo for the picture, which
 * the tool says first, and a save writes the tiles the lines touched.
 */
final class BigPictureTest {
  // from the top edge to the picture's border line at y 109, in the tile 1_0 only
  private static final BorderLine LINE =
      new BorderLine(List.of(new Point(300, 0), new Point(300, 109)), 3, Color.BLACK);

  @TempDir Path folder;

  private long usualLimit = -1;

  @AfterEach
  void usualLimitAgain() {
    if (usualLimit >= 0) {
      MapFiles.setUndoPictureLimit(usualLimit);
    }
  }

  /** A saved map of 400 x 300, opened again and its picture held from its tiles. */
  private MapProject givenMapWithPictureHeld() throws Exception {
    final MapProject made =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder)
            .project();
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());
    project.holdPicture(project.getFiles().tilesPictureToDrawInto().orElseThrow());
    return project;
  }

  private void givenPicturesOverTheLimit() {
    // a lowered limit instead of a picture of 136 million pixels
    usualLimit = MapFiles.setUndoPictureLimit(1000);
  }

  private static List<String> backups(final MapProject project) throws Exception {
    try (Stream<Path> tiles =
        Files.list(project.getFolder().orElseThrow().resolve("baseTiles"))) {
      return tiles
          .map(file -> file.getFileName().toString())
          .filter(name -> name.endsWith(".bak"))
          .sorted()
          .toList();
    }
  }

  private static boolean isBlack(final BufferedImage picture, final int x, final int y) {
    return (picture.getRGB(x, y) & 0xFFFFFF) == 0;
  }

  @DisplayName("A line into a picture over the limit changes the picture itself: no undo step")
  @Test
  void drawsInPlaceWithoutUndo() throws Exception {
    // given
    givenPicturesOverTheLimit();
    final MapProject project = givenMapWithPictureHeld();
    final BufferedImage picture = project.getFiles().picture().orElseThrow();
    final int before = picture.getRGB(300, 60);
    final int edits = project.pictureEdits();

    // when
    final boolean drawn = project.drawBorder(LINE);

    // then
    assertThat(drawn).as("the line is drawn").isTrue();
    assertThat(project.getFiles().picture().orElseThrow())
        .as("into the same picture")
        .isSameAs(picture);
    assertThat(isBlack(picture, 300, 60)).as("black now where the line is").isTrue();
    assertThat(project.canUndo()).as("no step to undo").isFalse();
    assertThat(project.hasUnsavedChanges()).as("but something to save").isTrue();
    assertThat(project.drawnRegion()).as("where Make territories looks").isPresent();
    assertThat(project.pictureEdits())
        .as("told to who keeps a copy of the picture")
        .isEqualTo(edits + 1);

    // when the line is erased again, in place too
    final boolean erased =
        project.eraseBorders(List.of(new Point(300, 30), new Point(300, 80)), 9);

    // then
    assertThat(erased).as("erased").isTrue();
    assertThat(picture.getRGB(300, 60)).as("the land's color again").isEqualTo(before);
    assertThat(project.canUndo()).as("still no step").isFalse();
  }

  @DisplayName("Work in the background on the picture as it was is refused after a line")
  @Test
  void lineChangesTheMapForBackgroundWork() throws Exception {
    // given the files a background job, eg a resize or Make territories, started with
    givenPicturesOverTheLimit();
    final MapProject project = givenMapWithPictureHeld();
    final MapFiles from = project.getFiles();

    // when a line is drawn meanwhile
    project.drawBorder(LINE);

    // then
    assertThat(project.getFiles()).as("the map has other files now").isNotSameAs(from);
    assertThat(project.getFiles().picture()).as("with the same picture").isEqualTo(from.picture());
    assertThat(project.takeResized(from, from, 1))
        .as("so a result made from the picture before the line is not taken")
        .isFalse();
    assertThat(project.canUndo()).as("and no step was made").isFalse();
  }

  @DisplayName("A line marks its region only in the steps that hold the picture drawn into")
  @Test
  void otherPicturesKeepTheirRegion() throws Exception {
    // given the map with another picture loaded, undone again: a redo holds the other one
    givenPicturesOverTheLimit();
    final MapProject project = givenMapWithPictureHeld();
    final BufferedImage other = MapPictureTest.givenPicture();
    project.loadPicture(other, MapPicture.detect(other));
    project.undo();

    // when a line is drawn into the first picture
    project.drawBorder(LINE);
    final boolean here = project.drawnRegion().isPresent();
    project.redo();

    // then
    assertThat(here).as("drawn in the picture it went into").isTrue();
    assertThat(project.getFiles().picture().orElseThrow()).as("the other picture").isSameAs(other);
    assertThat(project.drawnRegion()).as("has no line: nothing drawn there").isEmpty();
    assertThat(isBlack(other, 300, 60)).as("nor in its pixels").isFalse();
  }

  @DisplayName("A save writes only the tiles a line drawn in place touched, each with its .bak")
  @Test
  void savesOnlyTheTilesDrawnInto() throws Exception {
    // given
    givenPicturesOverTheLimit();
    final MapProject project = givenMapWithPictureHeld();
    project.drawBorder(LINE);

    // when
    project.save();

    // then
    assertThat(backups(project))
        .as("only the tile with the line was written")
        .containsExactly("1_0.png.bak");
    assertThat(project.hasUnsavedChanges()).as("all saved").isFalse();
    final BufferedImage tile =
        ImageIO.read(project.getFolder().orElseThrow().resolve("baseTiles/1_0.png").toFile());
    assertThat(isBlack(tile, 300 - 256, 60)).as("the tile has the line").isTrue();

    // when nothing was drawn since
    project.edit(game -> MapXml.withNotes(game, "A note"));
    project.save();

    // then
    assertThat(backups(project)).as("no tile is written again").containsExactly("1_0.png.bak");
  }

  @DisplayName("A map under the limit still undoes a line")
  @Test
  void smallPictureKeepsUndo() throws Exception {
    // given
    final MapProject project = givenMapWithPictureHeld();
    final BufferedImage picture = project.getFiles().picture().orElseThrow();

    // when
    project.drawBorder(LINE);
    final BufferedImage drawn = project.getFiles().picture().orElseThrow();
    project.undo();

    // then
    assertThat(project.drawsInPlace()).as("a picture of usual size").isFalse();
    assertThat(drawn).as("the line went into a copy, one step").isNotSameAs(picture);
    assertThat(isBlack(drawn, 300, 60)).as("with the line").isTrue();
    assertThat(project.getFiles().picture().orElseThrow())
        .as("undone: the picture before")
        .isSameAs(picture);
    assertThat(isBlack(picture, 300, 60)).as("which has no line").isFalse();
  }

  @DisplayName("Before the first line without undo the tool asks, once, and Cancel draws nothing")
  @Test
  void asksBeforeTheFirstLine() throws Exception {
    // given
    givenPicturesOverTheLimit();
    final MapProject project = givenMapWithPictureHeld();
    final BufferedImage picture = project.getFiles().picture().orElseThrow();
    final List<String> asked = new ArrayList<>();
    final List<Object> seen = new ArrayList<>();
    final List<Point> line = List.of(new Point(300, 20), new Point(300, 100));

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor =
              new TerritoriesEditor(
                  project, new MapView(project, () -> {}, () -> {}, () -> {}));
          // Cancel the first time, Draw anyway the second
          editor.askToDrawInPlace = text -> asked.add(text) && asked.size() > 1;
          editor.borderDrawn(line);
          seen.add(isBlack(picture, 300, 60));
          editor.borderDrawn(line);
          seen.add(isBlack(picture, 300, 60));
          editor.borderDrawn(List.of(new Point(100, 20), new Point(100, 100)));
          seen.add(isBlack(picture, 100, 60));
        });

    // then
    assertThat(seen)
        .as("Cancel: no line; Draw anyway: the line; then lines without asking")
        .containsExactly(false, true, true);
    assertThat(asked).as("asked until agreed, then no more").hasSize(2);
    assertThat(asked.getFirst())
        .as("says how big the picture is and what to do first")
        .contains("400 × 300", "cannot be undone", "Save first");
  }

  @DisplayName("A picture is held only while Java has room for it and the map on screen")
  @Test
  void holdsOnlyWithRoom() {
    // given the picture of 1941 Global Command Decision: 16816 x 8085
    final long pixels = 16816L * 8085;

    // when, then
    assertThat(MapFiles.memoryMissing(pixels, 4_000_000_000L)).as("4 GB free: it fits").isZero();
    assertThat(MapFiles.memoryMissing(pixels, 500_000_000L))
        .as("500 MB free: its 544 MB and what the map needs beside it are missing")
        .isBetween(250_000_000L, 350_000_000L);
  }
}
