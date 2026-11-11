package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import javax.swing.JCheckBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MapLookTest {
  @TempDir Path folder;

  @DisplayName("A ticked box sets its map.properties entry as one step, saved in the file")
  @Test
  void checkBoxSetsProperty() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapLookEditor look = new MapLookEditor(project);

    checkBox(look.page(), "Sea zone names").doClick();
    project.save();

    assertThat(savedProperties(project).getProperty("map.showSeaZoneNames"))
        .as("written to map.properties")
        .isEqualTo("true");
    project.undo();
    assertThat(project.getFiles().properties().getProperty("map.showSeaZoneNames"))
        .as("one undo takes it back")
        .isNull();
  }

  private MapProject givenSavedMapWithRed() throws Exception {
    return MapProject.newMap(
            "Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder.resolve("maps"))
        .project();
  }

  private static JCheckBox checkBox(final Container in, final String label) {
    return components(in)
        .filter(JCheckBox.class::isInstance)
        .map(JCheckBox.class::cast)
        .filter(box -> box.getText().equals(label))
        .findFirst()
        .orElseThrow();
  }

  private static Stream<Component> components(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), components(inner))
                    : Stream.of(c));
  }

  private static Properties savedProperties(final MapProject project) throws Exception {
    final Properties saved = new Properties();
    try (InputStream in =
        Files.newInputStream(project.getFolder().orElseThrow().resolve("map.properties"))) {
      saved.load(in);
    }
    return saved;
  }

  @DisplayName("A relief picture is cut into relief tiles on saving; tiles before keep a .bak")
  @Test
  void reliefIsCutIntoTiles() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final Path tiles = project.getFolder().orElseThrow().resolve("reliefTiles");
    Files.createDirectories(tiles);
    Files.writeString(tiles.resolve("9_9.png"), "an old tile");
    final Dimension size = project.getFiles().size();

    project.setRelief(new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB));
    project.save();

    assertThat(project.hasUnsavedRelief()).as("saved, so no longer to be written").isFalse();
    assertThat(tiles.resolve("0_0.png")).as("the first tile written").exists();
    assertThat(tiles.resolve("9_9.png")).as("a tile beyond the relief removed").doesNotExist();
    assertThat(tiles.resolve("9_9.png.bak")).as("its first version kept").hasContent("an old tile");
  }
}
