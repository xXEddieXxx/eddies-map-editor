package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
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

  @DisplayName("The names not drawn show under Territory names; Show all removes the key, one step")
  @Test
  void showsAllNames() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    project.setMapProperty(MapFiles.HIDDEN_NAMES, "Territory 1,Unit List");
    final MapLookEditor look = new MapLookEditor(project);

    button(look.page(), JButton.class, "Show all").doClick();

    assertThat(project.getFiles().properties().containsKey(MapFiles.HIDDEN_NAMES))
        .as("no name is hidden, and no empty key stays")
        .isFalse();
    assertThat(components(look.page()).filter(JButton.class::isInstance).map(JButton.class::cast))
        .as("nothing left to show: the line is gone")
        .noneMatch(b -> "Show all".equals(b.getText()));
    project.undo();
    assertThat(project.getFiles().listed(MapFiles.HIDDEN_NAMES))
        .as("one undo hides both again")
        .containsExactly("Territory 1", "Unit List");
  }

  @DisplayName("A changed setting changes its line of map.properties only; comments, order stay")
  @Test
  void savesMapPropertiesKeyByKey() throws Exception {
    // given: a map.properties as map makers write it, with comments, odd spacing and old keys
    final MapProject made = givenSavedMapWithRed();
    final Path file = made.getFolder().orElseThrow().resolve("map.properties");
    final String byHand =
        "# My map, by me\r\n"
            + "\r\n"
            + "color.Red = FF0000\r\n"
            + "   units.scale=1\r\n"
            + "#map.mapBlends=false   forces the switch off\r\n"
            + "map.mapBlends=true\r\n"
            + "dont_draw_units=factory\r\n"
            + "odd key\\ of mine : kept   \r\n"
            + Files.readString(file, StandardCharsets.ISO_8859_1)
                .lines()
                .filter(line -> line.startsWith("map.width") || line.startsWith("map.height"))
                .collect(Collectors.joining("\r\n"))
            + "\r\n";
    Files.writeString(file, byHand, StandardCharsets.ISO_8859_1);
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());

    // when: one setting changed, one list emptied, one key new
    project.setMapProperty("units.scale", "0.75");
    project.editFiles(f -> f.withoutListed(MapFiles.UNDRAWN_UNITS, "factory"));
    project.setMapProperty("map.showSeaZoneNames", "true");
    project.save();

    // then
    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1))
        .as("the changed line, the removed key's line gone, the new key at the end; all else as"
            + " written, byte for byte")
        .isEqualTo(
            byHand
                    .replace("   units.scale=1\r\n", "units.scale=0.75\r\n")
                    .replace("dont_draw_units=factory\r\n", "")
                + "map.showSeaZoneNames=true\r\n");
    assertThat(savedProperties(project).getProperty("map.mapBlends"))
        .as("a key the editor has no control for stays")
        .isEqualTo("true");
  }

  /** The saved map opened with this text as its map.properties, the map's size kept. */
  private MapProject givenMapWithProperties(final String byHand) throws Exception {
    final MapProject made = givenSavedMapWithRed();
    Files.writeString(givenFile(made), givenSize(made) + byHand, StandardCharsets.ISO_8859_1);
    return MapProject.open(made.getXmlFile().orElseThrow());
  }

  private static Path givenFile(final MapProject project) {
    return project.getFolder().orElseThrow().resolve("map.properties");
  }

  private static String givenSize(final MapProject project) throws Exception {
    return Files.readString(givenFile(project), StandardCharsets.ISO_8859_1)
            .lines()
            .filter(line -> line.startsWith("map.width") || line.startsWith("map.height"))
            .collect(Collectors.joining("\n"))
        + "\n";
  }

  @DisplayName("A new map's first save writes every entry of its map.properties")
  @Test
  void firstSaveWritesAllProperties() throws Exception {
    final MapProject project = givenSavedMapWithRed();

    assertThat(savedProperties(project))
        .as("the file reads as the map holds it")
        .isEqualTo(project.getFiles().properties())
        .containsKey("color.Red");
  }

  @DisplayName("A key changed by hand in the file since opening stays; one changed here wins")
  @Test
  void keepsHandChangesOfOtherKeys() throws Exception {
    // given: opened, then units.scale and map.showComments changed in the file by hand
    final MapProject project = givenMapWithProperties("units.scale=1\nmap.showComments=true\n");
    final String size = givenSize(project);
    Files.writeString(
        givenFile(project),
        size + "# by hand\nunits.scale=2\nmap.showComments=false\nmine=1\n",
        StandardCharsets.ISO_8859_1);

    // when: the editor changes one of the two
    project.setMapProperty("map.showComments", "true");
    project.setMapProperty("map.showComments", "maybe");
    project.save();

    // then
    assertThat(Files.readString(givenFile(project), StandardCharsets.ISO_8859_1))
        .as("the scale and the key set by hand stay; the key changed here has the editor's value")
        .isEqualTo(size + "# by hand\nunits.scale=2\nmap.showComments=maybe\nmine=1\n");
  }

  @DisplayName("A key with blanks and letters beyond ASCII is written as the game reads it")
  @Test
  void writesOddKeys() throws Exception {
    final MapProject project = givenMapWithProperties("units.scale=1\n");

    project.setMapProperty("relief.texture.Sea Zone 1", "Open sea");
    project.setMapProperty("relief.texture.S\u00fcd: See", "W\u00e4lder \u4e2d");
    project.save();

    assertThat(savedProperties(project))
        .as("read back by Properties, key and value whole")
        .containsEntry("relief.texture.Sea Zone 1", "Open sea")
        .containsEntry("relief.texture.S\u00fcd: See", "W\u00e4lder \u4e2d")
        .containsEntry("units.scale", "1");
    assertThat(Files.readString(givenFile(project), StandardCharsets.ISO_8859_1))
        .as("the lines there stay, the new ones follow")
        .startsWith(givenSize(project) + "units.scale=1\n")
        .contains("relief.texture.Sea\\ Zone\\ 1=Open sea\n");
  }

  @DisplayName("Of a key given twice the last is changed; a continued line stays until changed")
  @Test
  void writesRepeatedAndContinuedKeys() throws Exception {
    final String byHand =
        "units.scale=1\nunits.scale=2\ndont_draw_units=factory,\\\n    bunker\n# end\n";
    final MapProject project = givenMapWithProperties(byHand);
    final String size = givenSize(project);

    project.setMapProperty("units.scale", "3");
    project.save();
    final String scaled = Files.readString(givenFile(project), StandardCharsets.ISO_8859_1);
    project.editFiles(f -> f.withoutListed(MapFiles.UNDRAWN_UNITS, "factory"));
    project.save();

    assertThat(project.getFiles().listed(MapFiles.UNDRAWN_UNITS))
        .as("the continued line read as the game reads it, before its change")
        .containsExactly("bunker");
    assertThat(scaled)
        .as("the entry the game reads, the last, changed; the continued line byte for byte")
        .isEqualTo(size + byHand.replace("units.scale=2", "units.scale=3"));
    assertThat(Files.readString(givenFile(project), StandardCharsets.ISO_8859_1))
        .as("changed, the continued entry is one line")
        .isEqualTo(
            size + "units.scale=1\nunits.scale=3\ndont_draw_units=bunker\n# end\n");
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

  @DisplayName("A scrolling choice writes both directions as one step")
  @Test
  void scrollingIsOneStep() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapLookEditor look = new MapLookEditor(project);

    button(look.page(), JRadioButton.class, "Neither").doClick();

    final Properties properties = project.getFiles().properties();
    assertThat(List.of("map.scrollWrapX", "map.scrollWrapY"))
        .as("neither direction scrolls around")
        .map(properties::getProperty)
        .containsExactly("false", "false");
    project.undo();
    assertThat(project.getFiles().properties().getProperty("map.scrollWrapX"))
        .as("one undo takes both back")
        .isNull();
  }

  @DisplayName("The four scrolling choices sit in one grid, two by two")
  @Test
  void scrollingInOneGrid() throws Exception {
    final MapLookEditor look = new MapLookEditor(givenSavedMapWithRed());

    final Container grid =
        button(look.page(), JRadioButton.class, "Around left and right").getParent();

    assertThat(grid.getLayout()).as("one layout for all four").isInstanceOf(GridLayout.class);
    assertThat(Stream.of(grid.getComponents()).map(c -> ((JRadioButton) c).getText()))
        .as("in reading order")
        .containsExactly("Around left and right", "Around top and bottom", "Both", "Neither");
  }

  private static <T extends AbstractButton> T button(
      final Container in, final Class<T> kind, final String text) {
    return components(in)
        .filter(kind::isInstance)
        .map(kind::cast)
        .filter(b -> b.getText().equals(text))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No button " + text));
  }

  @DisplayName("The unit size shows as a percent and is written as the game's scale")
  @Test
  @SuppressWarnings("unchecked")
  void unitSizeAsPercent() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapLookEditor look = new MapLookEditor(project);
    final JComboBox<String> size = inner(fieldOf(look.page(), "Size"), JComboBox.class);

    size.setSelectedItem("0.875");

    final JLabel shown =
        (JLabel)
            size.getRenderer()
                .getListCellRendererComponent(new JList<>(), "0.875", 0, false, false);
    assertThat(shown.getText()).as("a maker reads a percent").isEqualTo("88 %");
    assertThat(project.getFiles().properties().getProperty("units.scale"))
        .as("the game reads the scale")
        .isEqualTo("0.875");
  }

  @DisplayName("Unticking a nation's tint writes no tint; ticking writes nothing yet")
  @Test
  void tintIsTickedOff() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    project.setMapProperty("units.transform.color.Red", "FF0000");
    final MapLookEditor look = new MapLookEditor(project);
    final JCheckBox tint = checkBox(look.page(), "Tint the pictures");

    tint.doClick();
    final String unticked = project.getFiles().properties().getProperty("units.transform.color.Red");
    tint.doClick();

    assertThat(unticked).as("no tint, as the game reads it").isNullOrEmpty();
    assertThat(project.getFiles().properties().getProperty("units.transform.color.Red"))
        .as("ticked again, nothing is written until a colour is chosen")
        .isNullOrEmpty();
  }

  @DisplayName("A nation without a tint shows None, not a grey colour it does not have; with one,"
      + " its colour")
  @Test
  void noTintSaysNone() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapLookEditor look = new MapLookEditor(project);
    final List<String> without = labelsIn(fieldOf(look.page(), "Tint"));

    project.setMapProperty("units.transform.color.Red", "FF0000");
    final List<String> with = labelsIn(fieldOf(look.page(), "Tint"));

    assertThat(without).as("no tint is set").contains("None").doesNotContain("#808080");
    assertThat(with).as("the tint the map sets").contains("#FF0000").doesNotContain("None");
  }

  private static List<String> labelsIn(final Component field) {
    return components((Container) field)
        .filter(JLabel.class::isInstance)
        .filter(Component::isVisible)
        .map(c -> ((JLabel) c).getText())
        .toList();
  }

  @DisplayName("The page is at most 1040 px wide, the intro left to the window")
  @Test
  void pageIsCapped() {
    final MapLookEditor look = new MapLookEditor(MapProject.newEmpty());
    final Container page = (Container) look.page();

    page.setSize(1600, 900);
    page.doLayout();
    ((Container) page.getComponent(0)).doLayout();

    assertThat(((Container) page.getComponent(0)).getComponent(0).getWidth())
        .as("the cards do not stretch over a wide window")
        .isEqualTo(1040);
    assertThat(components(page).filter(JLabel.class::isInstance)
            .map(c -> ((JLabel) c).getText()))
        .as("the window already shows the tool's intro")
        .noneMatch(t -> t != null && t.startsWith("How the game draws your map"));
  }

  @DisplayName("Without outlines the relief buttons are off, say why and lead to Territories")
  @Test
  void reliefNeedsOutlines() {
    final MapProject project = MapProject.newEmpty();
    final AtomicBoolean wentToTerritories = new AtomicBoolean();
    final MapLookEditor look = new MapLookEditor(project, () -> wentToTerritories.set(true));

    button(look.page(), JButton.class, "Go to Territories").doClick();

    assertThat(button(look.page(), JButton.class, "Create relief…").isEnabled())
        .as("a relief is made from the outlines")
        .isFalse();
    assertThat(button(look.page(), JButton.class, "Load picture…").isEnabled())
        .as("a loaded relief is cut along them too")
        .isFalse();
    assertThat(components(look.page()).filter(JLabel.class::isInstance).map(JLabel.class::cast))
        .as("the reason is shown, not only a disabled button")
        .anyMatch(l -> l.getText().contains("Needs the territories' outlines first."));
    assertThat(wentToTerritories).as("the link leads to Territories").isTrue();
  }

  @DisplayName("Show the relief is off and unticked while the map has no relief, writing nothing")
  @Test
  void showReliefNeedsRelief() throws Exception {
    final MapProject project = givenSavedMapWithRed();

    final JCheckBox without = checkBox(new MapLookEditor(project).page(), "Show the relief");
    Files.createDirectories(project.getFolder().orElseThrow().resolve("reliefTiles"));
    final JCheckBox with = checkBox(new MapLookEditor(project).page(), "Show the relief");

    assertThat(without.isSelected()).as("nothing is shown").isFalse();
    assertThat(without.isEnabled()).as("nothing to switch on").isFalse();
    assertThat(project.canUndo()).as("the file's setting stays").isFalse();
    assertThat(with.isSelected() && with.isEnabled()).as("with relief tiles, as set").isTrue();
  }

  @DisplayName("Size and Stack up to share one field column")
  @Test
  void unitFieldsInOneColumn() throws Exception {
    final MapLookEditor look = new MapLookEditor(givenSavedMapWithRed());
    final Container page = (Container) look.page();
    page.setSize(1200, 900);
    layOut(page);

    final Component size = inner(fieldOf(page, "Size"), JComboBox.class);
    final Component stack = fieldOf(page, "Stack up to");

    assertThat(SwingUtilities.convertPoint(stack.getParent(), stack.getLocation(), page).x)
        .as("the spinner starts where the combo does")
        .isEqualTo(SwingUtilities.convertPoint(size.getParent(), size.getLocation(), page).x);
  }

  private static void layOut(final Component component) {
    component.doLayout();
    if (component instanceof Container container) {
      for (final Component inner : container.getComponents()) {
        layOut(inner);
      }
    }
  }

  @DisplayName("Only Stack up to, Blending and the small map have a \"?\"")
  @Test
  void threeHelpButtons() throws Exception {
    final MapLookEditor look = new MapLookEditor(givenSavedMapWithRed());

    final long helps =
        components(look.page())
            .filter(JButton.class::isInstance)
            .map(JButton.class::cast)
            .filter(b -> b.getText().equals("?"))
            .count();

    assertThat(helps).as("the other explanations are tool tips").isEqualTo(3);
  }

  @DisplayName("The relief section leads to the texture brushes, and Create relief counts them")
  @Test
  void reliefLeadsToTextures() throws Exception {
    // given a map with one textured territory
    final MapProject project = givenSavedMapWithRed();
    project.editFiles(files -> files.withTexture("Territory 1", Optional.of("Forest")));
    final List<String> opened = new ArrayList<>();
    final MapLookEditor look =
        new MapLookEditor(project, () -> opened.add("outlines"), () -> opened.add("textures"));

    // when
    components(look.page())
        .filter(JButton.class::isInstance)
        .map(JButton.class::cast)
        .filter(b -> "Territories ›".equals(b.getText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No link to Territories"))
        .doClick();
    final MapLookEditor.ReliefOptions options =
        new MapLookEditor.ReliefOptions(project.getFiles(), Set.of());

    // then
    assertThat(opened).as("the link opens Territories' texture brush").containsExactly("textures");
    assertThat(labelsIn(look.page()))
        .as("the line before the link")
        .contains("Textures for the relief are painted in");
    assertThat(String.join(" ", labelsIn(options.form)))
        .as("the dialog says the painted textures are in, and how many territories have one")
        .contains("Textures painted in Territories are included: 1 territory has one.");
  }

  @DisplayName("The relief's strength shows its value as the slider moves")
  @Test
  void reliefStrengthShowsValue() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapLookEditor.ReliefOptions options =
        new MapLookEditor.ReliefOptions(project.getFiles(), Set.of());

    options.strength.setValue(70);

    assertThat(components(options.form).filter(JLabel.class::isInstance).map(JLabel.class::cast))
        .as("the value right of the slider, between its ends")
        .extracting(JLabel::getText)
        .contains("Light", "Strong", "70 %");
  }
}
