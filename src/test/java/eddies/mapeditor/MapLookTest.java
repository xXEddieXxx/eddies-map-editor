package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
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
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSpinner;
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
    assertThat(project.getFiles().reliefTile(0, 0)).as("the map shows its tile").isPresent();
    assertThat(project.getFiles().showsRelief()).as("shown unless told not to").isTrue();
    project.setMapProperty("map.hasRelief", "false");
    assertThat(project.getFiles().showsRelief()).as("hidden when switched off").isFalse();
    assertThat(tiles.resolve("0_0.png")).as("the first tile written").exists();
    assertThat(tiles.resolve("9_9.png")).as("a tile beyond the relief removed").doesNotExist();
    assertThat(tiles.resolve("9_9.png.bak")).as("its first version kept").hasContent("an old tile");
  }

  @DisplayName("A new relief on a map told not to show one is shown, in the same step")
  @Test
  void newReliefIsShown() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    project.setMapProperty("map.hasRelief", "false");
    final Dimension size = project.getFiles().size();

    project.setRelief(new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB));

    assertThat(project.getFiles().showsRelief()).as("switched on with the relief").isTrue();
    project.undo();
    assertThat(project.getFiles().relief()).as("one undo takes the relief back").isEmpty();
    assertThat(project.getFiles().showsRelief()).as("and hides it again").isFalse();
  }

  @DisplayName("Each field of the further sections sets its map.properties entry as one step")
  @Test
  void furtherFieldsSetProperties() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapLookEditor look = new MapLookEditor(project);
    final List<Change> changes =
        List.of(
            new Change("Comment markers", null, "map.showComments", "false"),
            new Change("Each nation's own convoy flags", null, "map.useNation_convoyFlags", "true"),
            new Change(
                "Names from their place's top left", null, "map.drawNamesFromTopLeft", "true"),
            new Change("With a title", null, "screenshot.title.enabled", "false"),
            new Change("Facing the other way", null, "units.transform.flip.Red", "true"),
            new Change("Number left", 20, "units.counter.offset.width", "20"),
            new Change("Number down", 30, "units.counter.offset.height", "30"),
            new Change("Brightness", -20, "units.transform.brightness.Red", "-20"),
            new Change("Strength in %", 30, "map.mapBlendAlpha", "0.3"),
            new Change("Territory colors in %", 80, "smallMap.territory.saturation", "0.8"),
            new Change("Unit dots", 6, "smallMap.unit.size", "6"),
            new Change("Inside the frame, cover in %", 25, "smallMap.viewer.fillAlpha", "0.25"),
            new Change("Point left", 5, "map.cursor.hotspot.x", "5"),
            new Change("Point down", 7, "map.cursor.hotspot.y", "7"),
            new Change("Mode", "OVERLAY", "map.mapBlendMode", "OVERLAY"));

    for (final Change change : changes) {
      change.make(look.page());
      assertThat(project.getFiles().properties().getProperty(change.key()))
          .as("%s sets %s", change.label(), change.key())
          .isEqualTo(change.written());
      project.undo();
      assertThat(project.getFiles().properties().getProperty(change.key()))
          .as("one undo takes %s back", change.key())
          .isNull();
    }
  }

  /** A field's change: a check box clicked, or a number or choice set. */
  private record Change(String label, Object value, String key, String written) {
    void make(final Container in) {
      if (value == null) {
        checkBox(in, label).doClick();
        return;
      }
      final Component field = fieldOf(in, label);
      if (value instanceof Integer) {
        inner(field, JSpinner.class).setValue(value);
      } else {
        inner(field, JComboBox.class).setSelectedItem(value);
      }
    }
  }

  private static Component fieldOf(final Container in, final String label) {
    return components(in)
        .filter(JLabel.class::isInstance)
        .map(JLabel.class::cast)
        .filter(l -> label.equals(l.getText()) && l.getLabelFor() != null)
        .map(JLabel::getLabelFor)
        .findFirst()
        .orElseThrow(() -> new AssertionError("No field " + label));
  }

  private static <T> T inner(final Component field, final Class<T> kind) {
    return Stream.concat(
            Stream.of(field),
            field instanceof Container c ? components(c) : Stream.<Component>empty())
        .filter(kind::isInstance)
        .map(kind::cast)
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("The title of the map picture is written with its place and size, all or none")
  @Test
  void titlePlaceIsWrittenWhole() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapLookEditor look = new MapLookEditor(project);

    inner(fieldOf(look.page(), "Title left"), JSpinner.class).setValue(40);

    final Properties properties = project.getFiles().properties();
    assertThat(List.of("screenshot.title.x", "screenshot.title.y", "screenshot.title.font.size"))
        .as("the game reads them only all three, else takes 15 for each")
        .map(properties::getProperty)
        .containsExactly("40", "15", "15");
    project.undo();
    assertThat(project.getFiles().properties().getProperty("screenshot.title.y"))
        .as("one undo takes all three back")
        .isNull();
  }

  @DisplayName("A color is written as the game reads it, six hex digits; others are not read")
  @Test
  void colorsAsTheGameReadsThem() {
    final Color color = new Color(0x12, 0xAB, 0xEF);

    final String written = MapLookEditor.hex(color);

    assertThat(written).as("six digits, no #").isEqualTo("12ABEF");
    assertThat(MapLookEditor.colorOf(written)).as("read back").contains(color);
    assertThat(MapLookEditor.colorOf("#12ABEF")).as("a value the game cannot read").isEmpty();
  }
}
