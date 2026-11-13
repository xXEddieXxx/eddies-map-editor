package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapFolder.MapDetails;
import eddies.mapeditor.PublishEditor.Id;
import eddies.mapeditor.PublishEditor.Item;
import eddies.mapeditor.Theme.Status;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.yaml.YamlReader;

/** Publishing a map, its flags, and the pictures a map should have. */
final class PublishTest {
  @TempDir Path maps;

  /** A map of two nations with land, a capital and units, saved as "island_wars" and opened. */
  private MapProject givenSavedMap() throws Exception {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    project.addNation("Red");
    project.addNation("Blue");
    project.edit(
        game ->
            MapXml.withUnits(
                MapXml.withOwner(game, "Territory 1", "Red"), "Territory 1", "Red", "infantry", 2));
    project.saveAsNewMap("Island Wars", maps);
    return MapProject.open(maps.resolve("island_wars/games/island_wars.xml"));
  }

  private Path givenRoot() {
    return maps.resolve("island_wars");
  }

  private static BufferedImage givenPicture(final int width, final int height, final Color color) {
    final BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = picture.createGraphics();
    g.setColor(color);
    g.fillRect(0, 0, width, height);
    g.dispose();
    return picture;
  }

  private static Item item(final List<Item> items, final Id id) {
    return items.stream().filter(i -> i.id() == id).findFirst().orElseThrow();
  }

  private static <T> Stream<T> givenAll(final Component component, final Class<T> type) {
    final Stream<T> own =
        type.isInstance(component) ? Stream.of(type.cast(component)) : Stream.empty();
    return component instanceof Container container
        ? Stream.concat(
            own, Arrays.stream(container.getComponents()).flatMap(c -> givenAll(c, type)))
        : own;
  }

  // ---- map.yml

  @DisplayName("The map's name and version are set in map.yml, its other entries and .bak kept")
  @Test
  void setsNameAndVersion() throws Exception {
    givenSavedMap();
    final Path yaml = givenRoot().resolve("map.yml");
    Files.writeString(yaml, Files.readString(yaml) + "author: Somebody\n");
    final String first = Files.readString(yaml);

    MapFolder.setMapName(givenRoot(), " Island Wars II ");
    MapFolder.setMapVersion(givenRoot(), "2");

    assertThat(MapFolder.mapDetails(givenRoot()))
        .as("read back")
        .isEqualTo(new MapDetails("Island Wars II", "2"));
    final Map<String, Object> written = YamlReader.readMap(Files.readString(yaml));
    assertThat(written.get("version")).as("a whole number, as TripleA's example").isEqualTo(2);
    assertThat(written.get("author")).as("a key the editor does not know").isEqualTo("Somebody");
    assertThat(written.keySet())
        .as("the new version before the games")
        .containsSubsequence("map_name", "version", "games");
    assertThat(MapFolder.gamesOf(givenRoot())).as("the game still listed").hasSize(1);
    assertThat(yaml.resolveSibling("map.yml.bak")).as("the first version").hasContent(first);
  }

  @DisplayName("A version emptied leaves map.yml without one, not with an empty one")
  @Test
  void removesVersion() throws Exception {
    givenSavedMap();
    MapFolder.setMapVersion(givenRoot(), "4");

    MapFolder.setMapVersion(givenRoot(), " ");

    assertThat(YamlReader.readMap(Files.readString(givenRoot().resolve("map.yml"))))
        .as("the key is gone, the name stays")
        .doesNotContainKey("version")
        .containsEntry("map_name", "Island Wars");
  }

  @DisplayName("Raise makes the version one higher, 1 for a map without one")
  @Test
  void raisesVersion() {
    assertThat(List.of(MapFolder.raised(""), MapFolder.raised("7"), MapFolder.raised("1.5")))
        .as("none, a whole number, a number with a point")
        .containsExactly("1", "8", "2");
  }

  // ---- the list

  @DisplayName("A new map still needs a check, a version and a description to be uploaded")
  @Test
  void listsWhatIsMissing() throws Exception {
    givenSavedMap();

    final List<Item> items = PublishEditor.checklist(givenRoot(), Optional.empty());

    assertThat(items)
        .as("what is not done yet")
        .filteredOn(i -> !i.done())
        .extracting(Item::id)
        .containsExactly(Id.CHECK, Id.VERSION, Id.DESCRIPTION);
    assertThat(item(items, Id.NAME).text())
        .as("the name map.yml has")
        .isEqualTo("Name: Island Wars");
    assertThat(item(items, Id.PREVIEW).done()).as("a save writes preview.png").isTrue();
    assertThat(item(items, Id.FOLDER).done()).as("island_wars: lower case, underscores").isTrue();
  }

  @DisplayName("The list is done once the map is checked and has version and description")
  @Test
  void listsAllDone() throws Exception {
    givenSavedMap();
    MapFolder.setMapVersion(givenRoot(), "1");
    Files.writeString(givenRoot().resolve("description.html"), "<p>Two islands at war.</p>");
    // setting the version left the first map.yml as .bak
    final List<Item> withBackup = PublishEditor.checklist(givenRoot(), Optional.of(0));
    Files.delete(givenRoot().resolve("map.yml.bak"));

    final List<Item> items = PublishEditor.checklist(givenRoot(), Optional.of(0));

    assertThat(item(withBackup, Id.LEFTOVERS))
        .as("the .bak is no part of the map")
        .returns(Status.TODO, Item::status)
        .returns("1 backup file in the folder", Item::text);
    assertThat(items).as("nothing left to do").allMatch(Item::done);
  }

  @DisplayName("Problems of Check, a description too long and a folder name with capitals show")
  @Test
  void listsProblems() throws Exception {
    final Path renamed = Files.createDirectories(maps.resolve("Island Wars"));
    Files.writeString(renamed.resolve("map.yml"), "map_name: Island Wars\ngames: []\n");
    Files.writeString(renamed.resolve("description.html"), "x".repeat(3001));

    final List<Item> items = PublishEditor.checklist(renamed, Optional.of(2));

    assertThat(item(items, Id.CHECK))
        .as("Check's problems")
        .returns(Status.PROBLEM, Item::status)
        .returns("Check found 2 problems", Item::text);
    assertThat(item(items, Id.DESCRIPTION).status())
        .as("over the map server's 3000 characters")
        .isEqualTo(Status.PROBLEM);
    assertThat(item(items, Id.PREVIEW).status()).as("no preview.png").isEqualTo(Status.TODO);
    assertThat(item(items, Id.FOLDER))
        .as("the folder is not renamed, the name to give it is said")
        .returns(Status.WARNING, Item::status)
        .extracting(Item::hint)
        .asString()
        .contains("<b>island_wars</b>");
    assertThat(renamed).as("the folder stays").exists();
  }

  @DisplayName("A map without map.yml is told so instead of name and version")
  @Test
  void listsMissingYaml() throws Exception {
    givenSavedMap();
    Files.delete(givenRoot().resolve("map.yml"));

    final List<Item> items = PublishEditor.checklist(givenRoot(), Optional.of(0));

    assertThat(items)
        .as("no name or version to tell of")
        .extracting(Item::id)
        .contains(Id.MAP_YML)
        .doesNotContain(Id.NAME, Id.VERSION);
  }

  @DisplayName("The zip leaves out what the list calls backup files")
  @Test
  void leftoversStayOutOfTheZip() throws Exception {
    givenSavedMap();
    MapFolder.setMapVersion(givenRoot(), "1");
    Files.writeString(givenRoot().resolve("games/island_wars.xml.saving"), "half");
    final long files;
    try (Stream<Path> walk = Files.walk(givenRoot())) {
      files = walk.filter(Files::isRegularFile).count();
    }

    final List<Path> leftovers = MapFolder.leftovers(givenRoot());
    final int zipped = MapFolder.zip(givenRoot(), maps.resolve("island_wars.zip"));

    assertThat(leftovers)
        .as("the first map.yml and what a failed save left")
        .extracting(file -> file.getFileName().toString())
        .containsExactlyInAnyOrder("map.yml.bak", "island_wars.xml.saving");
    assertThat((long) zipped).as("every file but those two").isEqualTo(files - 2);
  }

  // ---- the tool

  @DisplayName("The Publish tool writes name, version and description at once, with .bak")
  @Test
  void writesFromTheTool() throws Exception {
    final PublishEditor publish = new PublishEditor(givenSavedMap(), () -> {}, () -> {});
    publish.showing();
    final JTextField name =
        givenAll(publish.page(), JTextField.class)
            .filter(field -> field.getText().equals("Island Wars"))
            .findFirst()
            .orElseThrow();
    final JTextArea description =
        givenAll(publish.page(), JTextArea.class).findFirst().orElseThrow();
    final JButton setVersion =
        givenAll(publish.page(), JButton.class)
            .filter(button -> button.getText().equals("Set version 1"))
            .findFirst()
            .orElseThrow();

    name.setText("Island Wars Reborn");
    name.postActionEvent();
    setVersion.doClick();
    description.setText("<p>Two islands at war.</p>");
    publish.commit();
    description.setText("<p>Two islands at war, again.</p>");
    publish.commit();

    assertThat(MapFolder.mapDetails(givenRoot()))
        .as("map.yml")
        .isEqualTo(new MapDetails("Island Wars Reborn", "1"));
    assertThat(givenRoot().resolve("description.html"))
        .as("the description as typed last")
        .hasContent("<p>Two islands at war, again.</p>");
    assertThat(givenRoot().resolve("description.html.bak"))
        .as("its first version")
        .hasContent("<p>Two islands at war.</p>");
    assertThat(setVersion.isVisible()).as("the version's line is done: no button").isFalse();
    assertThat(publish.items())
        .as("Check, and the first map.yml the editor kept as .bak")
        .filteredOn(i -> !i.done())
        .extracting(Item::id)
        .containsExactly(Id.CHECK, Id.LEFTOVERS);
  }

  @DisplayName("Check's result shows in the Publish list until the map changes; a save keeps it")
  @Test
  void showsCheckResult() throws Exception {
    final MapProject project = givenSavedMap();
    final PublishEditor publish = new PublishEditor(project, () -> {}, () -> {});
    project.addNation("Green");

    publish.setChecked(List.of());
    project.save();
    publish.showing();
    final Status checkedAndSaved = item(publish.items(), Id.CHECK).status();
    project.addNation("Yellow");
    publish.showing();

    assertThat(checkedAndSaved)
        .as("no problems found, and the save changed nothing of the map")
        .isEqualTo(Status.DONE);
    assertThat(item(publish.items(), Id.CHECK).status())
        .as("the check was of the map before the change")
        .isEqualTo(Status.TODO);
  }

  @DisplayName("The description previews as the list of maps shows it: name, picture, text")
  @Test
  void previewsAsListed() throws Exception {
    givenSavedMap();

    final String html = PublishEditor.listedHead(givenRoot()) + "<p>Two islands.</p>";

    assertThat(html)
        .as("as DownloadMapsWindowModel.toHtmlString puts it together")
        .startsWith("<h1>Island Wars</h1>\n<img src='")
        .contains("preview.png")
        .endsWith("<p>Two islands.</p>");
  }

  @DisplayName("What the game file says of version and TripleA version is told, not changed")
  @Test
  void tellsGameFileVersions() {
    final String xml =
        "<game><info name=\"Old\" version=\"1.9\"/><triplea minimumVersion=\"1.8\"/></game>";

    assertThat(PublishEditor.gameFileLine(xml))
        .as("both values")
        .hasValueSatisfying(
            line -> assertThat(line).contains("version 1.9", "for TripleA 1.8 or later"));
    assertThat(PublishEditor.gameFileLine("<game><info name=\"New\"/></game>"))
        .as("a game file of today has neither")
        .isEmpty();
  }

  // ---- flags

  @DisplayName("A flag picture becomes the nation's flags in the game's sizes, the old as .bak")
  @Test
  void importsFlag() throws Exception {
    final MapProject project = givenSavedMap();
    final Path flags = givenRoot().resolve("flags");
    final long plain = Files.size(flags.resolve("Red.png"));

    MapFolder.importFlag(givenRoot(), "Red", givenPicture(300, 200, Color.GREEN));

    final BufferedImage flag = ImageIO.read(flags.resolve("Red.png").toFile());
    final BufferedImage small = ImageIO.read(flags.resolve("Red_small.png").toFile());
    final BufferedImage large = ImageIO.read(flags.resolve("Red_large.png").toFile());
    final BufferedImage faded = ImageIO.read(flags.resolve("Red_fade.png").toFile());
    assertThat(List.of(flag.getWidth(), flag.getHeight()))
        .as("32 px high as the game's flags, its shape kept")
        .containsExactly(48, 32);
    assertThat(small.getHeight()).as("the small flag").isEqualTo(12);
    assertThat(large.getHeight()).as("the flag on the capital").isEqualTo(100);
    assertThat(flag.getRGB(20, 16)).as("the picture's color").isEqualTo(Color.GREEN.getRGB());
    assertThat(faded.getRGB(20, 16) >>> 24)
        .as("the faded flag of kamikaze zones lets the map through")
        .isBetween(90, 115);
    assertThat(Files.size(flags.resolve("Red.png.bak")))
        .as("the plain flag the save made stays as .bak")
        .isEqualTo(plain);
    assertThat(new NationsEditor(project).flagIcon("Red"))
        .as("Nations shows the new flag")
        .hasValueSatisfying(icon -> assertThat(icon.getIconHeight()).isEqualTo(32));
  }

  @DisplayName("A flag import takes the nation's gif flags away, as the game takes a gif first")
  @Test
  void importedFlagReplacesGif() throws Exception {
    givenSavedMap();
    final Path flags = givenRoot().resolve("flags");
    ImageIO.write(givenPicture(32, 32, Color.RED), "gif", flags.resolve("Red.gif").toFile());
    ImageIO.write(givenPicture(12, 12, Color.RED), "gif", flags.resolve("Red_small.gif").toFile());
    final Optional<URL> before = MapFolder.flag(givenRoot(), "Red", "");

    MapFolder.importFlag(givenRoot(), "Red", givenPicture(300, 200, Color.GREEN));

    assertThat(before.map(URL::toString).orElse(""))
        .as("the gif before the png the save made, as FlagIconImageFactory asks")
        .endsWith("Red.gif");
    assertThat(MapFolder.flag(givenRoot(), "Red", "").map(URL::toString).orElse(""))
        .as("the game now finds the imported flag")
        .endsWith("Red.png");
    assertThat(flags.resolve("Red_small.gif")).as("nor the small gif").doesNotExist();
    assertThat(flags.resolve("Red.gif.bak")).as("the gif stays as .bak").exists();
  }

  @DisplayName("A small flag picture is not blown up")
  @Test
  void keepsSmallFlag() throws Exception {
    givenSavedMap();

    MapFolder.importFlag(givenRoot(), "Red", givenPicture(30, 20, Color.GREEN));

    assertThat(ImageIO.read(givenRoot().resolve("flags/Red_large.png").toFile()).getHeight())
        .as("as high as the picture")
        .isEqualTo(20);
  }

  // ---- missing pictures

  @DisplayName("A saved new map has every picture: nothing worth a look")
  @Test
  void newMapHasItsPictures() throws Exception {
    final MapProject project = givenSavedMap();

    assertThat(MapChecks.pictureNotes(project.game(), project.savedGame(), project.getFiles()))
        .as("a save writes flags and unit pictures for new nations")
        .isEmpty();
    final Game unsaved = MapProject.newEmpty().game();
    assertThat(MapChecks.pictureNotes(unsaved, unsaved, MapFiles.none()))
        .as("a map not saved has no folder to look in")
        .isEmpty();
  }

  @DisplayName("A unit a nation has and a flag without picture are worth a look, with their tool")
  @Test
  void notesMissingPictures() throws Exception {
    final MapProject project = givenSavedMap();
    project.addUnitType("Ranger", "infantry");
    project.edit(game -> MapXml.withUnits(game, "Territory 1", "Red", "Ranger", 1));
    project.save();
    Files.delete(givenRoot().resolve("units/Red/Ranger.png"));
    Files.delete(givenRoot().resolve("flags/Blue_small.png"));

    final List<Problem> notes =
        MapChecks.pictureNotes(project.game(), project.savedGame(), project.getFiles());

    assertThat(MapChecks.unitsOf(project.game(), "Red"))
        .as("what Red starts with")
        .contains("infantry", "Ranger");
    assertThat(notes)
        .as("one note per nation and kind, each with the tool that fixes it")
        .containsExactly(
            Problem.in("Units", "Red has no picture for Ranger")
                .markable("noUnitPictures", "Red"),
            Problem.in("Nations", "Blue has no flag picture: Blue_small.png")
                .markable("noFlag", "Blue"));
    assertThat(new UnitsEditor(project).nationsWithoutPicture(project.game(), "Ranger"))
        .as("Units says which nations lack the chosen unit's picture")
        .containsExactly("Red");
  }

  @DisplayName("A new unit type or nation misses no picture before the save that writes them")
  @Test
  void newOnesMissNoPictureYet() throws Exception {
    final MapProject project = givenSavedMap();
    project.addUnitType("Ranger", "infantry");
    project.addNation("Green");
    project.edit(
        game ->
            MapXml.withUnits(
                MapXml.withUnits(game, "Territory 1", "Red", "Ranger", 1),
                "Territory 1",
                "Green",
                "infantry",
                1));
    final UnitsEditor units = new UnitsEditor(project);

    final List<String> beforeSave = units.nationsWithoutPicture(project.game(), "Ranger");
    final List<Problem> notes =
        MapChecks.pictureNotes(project.game(), project.savedGame(), project.getFiles());
    final List<String> again = units.nationsWithoutPicture(project.game(), "Ranger");
    project.save();
    Files.delete(givenRoot().resolve("units/Red/Ranger.png"));

    assertThat(beforeSave).as("the save writes a plain picture of the new unit type").isEmpty();
    assertThat(notes).as("nor of the new nation's flags and units").isEmpty();
    assertThat(again).as("kept for the same map and unit, not read again").isSameAs(beforeSave);
    assertThat(units.nationsWithoutPicture(project.game(), "Ranger"))
        .as("after the save the unit type counts, and its picture is gone")
        .containsExactly("Red");
  }

  @DisplayName("A picture for all nations in units/ and the game's own pictures count")
  @Test
  void findsPicturesAsTheGameDoes() throws Exception {
    givenSavedMap();
    final Path units = givenRoot().resolve("units");
    ImageIO.write(givenPicture(48, 48, Color.RED), "png", units.resolve("dragon.png").toFile());

    final List<String> missing =
        MapFolder.unitsWithoutPicture(
            givenRoot(),
            MapFolder.names(units),
            "Germans",
            List.of("infantry", "dragon", "wizard"));

    assertThat(missing)
        .as("the game has the Germans' infantry, units/dragon.png is for every nation")
        .containsExactly("wizard");
    assertThat(MapFolder.flag(givenRoot(), "Germans", "_small"))
        .as("the game's own flag of the Germans")
        .isPresent();
    assertThat(MapFolder.flag(givenRoot(), "Elves", "")).as("a nation without a flag").isEmpty();
  }

  @DisplayName("Home shows missing pictures under Worth a look once the map is saved")
  @Test
  void homeNotesMissingPictures() throws Exception {
    final MapProject project = givenSavedMap();
    final HomeEditor home =
        new HomeEditor(project, t -> {}, t -> {}, s -> {}, () -> {}, () -> {}, () -> {});
    Files.delete(givenRoot().resolve("flags/Red_large.png"));

    home.showing();

    assertThat(home.notes())
        .as("found when Home shows again, as an import changes no step")
        .contains(
            Problem.in("Nations", "Red has no flag picture: Red_large.png")
                .markable("noFlag", "Red"));
  }

  @DisplayName("Home finds a flag imported meanwhile also while the map has unsaved changes")
  @Test
  void homeFindsPicturesWithUnsavedChanges() throws Exception {
    final MapProject project = givenSavedMap();
    Files.delete(givenRoot().resolve("flags/Red_large.png"));
    final HomeEditor home =
        new HomeEditor(project, t -> {}, t -> {}, s -> {}, () -> {}, () -> {}, () -> {});
    project.addNation("Green");
    final List<Problem> missing =
        home.notes().stream().filter(note -> note.text().contains("picture")).toList();

    MapFolder.importFlag(givenRoot(), "Red", givenPicture(300, 200, Color.GREEN));
    home.showing();

    assertThat(missing)
        .as("Red's flag is missing; Green is new, a save writes its flags")
        .containsExactly(
            Problem.in("Nations", "Red has no flag picture: Red_large.png")
                .markable("noFlag", "Red"));
    assertThat(home.notes())
        .as("the import is seen though the map is not saved")
        .noneMatch(note -> note.text().contains("picture"));
  }

  // ---- pictures

  @DisplayName("A unit picture clearly bigger than the map's units is scaled to their size")
  @Test
  void scalesUnitPictures() {
    final Dimension unit = new Dimension(72, 54);
    final BufferedImage big = givenPicture(288, 108, Color.RED);
    final BufferedImage fine = givenPicture(80, 60, Color.RED);

    final Map<String, BufferedImage> scaled =
        UnitsEditor.scaled(Map.of("tank", big, "infantry", fine), unit);

    assertThat(List.of(scaled.get("tank").getWidth(), scaled.get("tank").getHeight()))
        .as("the map's unit size")
        .containsExactly(72, 54);
    assertThat(scaled.get("tank").getRGB(36, 27))
        .as("the picture in the middle, 72 x 27 to keep its shape")
        .isEqualTo(Color.RED.getRGB());
    assertThat(scaled.get("tank").getRGB(36, 5) >>> 24).as("clear above it").isZero();
    assertThat(scaled.get("infantry")).as("a little bigger stays as it is").isSameAs(fine);
    assertThat(MapFolder.isBiggerThanUnit(fine, unit)).as("within a quarter").isFalse();
    assertThat(MapFolder.isBiggerThanUnit(big, unit)).as("four times as wide").isTrue();
  }

  @DisplayName("The map's unit picture size is map.properties' units.width and height, else 48")
  @Test
  void readsUnitPictureSize() {
    final MapFiles files =
        MapFiles.none().withProperty("units.width", "72").withProperty("units.height", "54");

    assertThat(files.unitPictureSize())
        .as("not scaled by units.scale")
        .isEqualTo(new Dimension(72, 54));
    assertThat(MapFiles.none().unitPictureSize())
        .as("TripleA's own")
        .isEqualTo(new Dimension(48, 48));
  }

  @DisplayName("The map's picture is saved whole from its tiles, as a paint program needs it")
  @Test
  void savesPictureFromTiles() throws Exception {
    final MapProject project = givenSavedMap();
    final BufferedImage first = MapPictureTest.givenPicture();
    final Path file = maps.resolve("picture.png");

    MapFolder.savePicture(project.getFiles(), file);

    final BufferedImage saved = ImageIO.read(file.toFile());
    assertThat(project.getFiles().picture())
        .as("opened: the picture is only in baseTiles")
        .isEmpty();
    assertThat(List.of(saved.getWidth(), saved.getHeight()))
        .as("the whole picture, not a tile's size")
        .containsExactly(first.getWidth(), first.getHeight());
    assertThat(saved.getRGB(300, 150))
        .as("a pixel of the second tile column")
        .isEqualTo(first.getRGB(300, 150));
    assertThatThrownBy(() -> MapFolder.savePicture(MapFiles.none(), maps.resolve("none.png")))
        .as("a map without a picture")
        .isInstanceOf(IOException.class);
  }

  @DisplayName("A save that writes a new relief leaves a small map there, and makes a missing one")
  @Test
  void savedReliefKeepsSmallMap() throws Exception {
    final MapProject project = givenSavedMap();
    final Path smallMap = givenRoot().resolve("smallMap.png");
    final byte[] byHand = Files.readAllBytes(smallMap);
    final BufferedImage first = MapPictureTest.givenPicture();

    project.setRelief(givenPicture(first.getWidth(), first.getHeight(), Color.MAGENTA));
    project.save();
    final byte[] afterSave = Files.readAllBytes(smallMap);
    Files.delete(smallMap);
    project.setRelief(givenPicture(first.getWidth(), first.getHeight(), Color.CYAN));
    project.save();

    assertThat(afterSave).as("maybe made by hand: every byte stays").isEqualTo(byHand);
    assertThat(ImageIO.read(smallMap.toFile()).getRGB(180, 160) & 0xFFFFFF)
        .as("a map without one gets it with the relief, as the game draws the file as it is")
        .isEqualTo(Color.CYAN.getRGB() & 0xFFFFFF);
  }

  @DisplayName("A new picture saved with a new relief: a missing small map gets the relief")
  @Test
  void newPictureAndReliefMakeSmallMapWithRelief() throws Exception {
    final MapProject project = givenSavedMap();
    final Path smallMap = givenRoot().resolve("smallMap.png");
    final BufferedImage first = MapPictureTest.givenPicture();
    Files.delete(smallMap);

    project.loadPicture(givenPicture(first.getWidth(), first.getHeight(), Color.YELLOW));
    project.setRelief(givenPicture(first.getWidth(), first.getHeight(), Color.CYAN));
    project.save();

    assertThat(ImageIO.read(smallMap.toFile()).getRGB(180, 160) & 0xFFFFFF)
        .as("the new picture's small map would hide that the map had none: the relief is on it")
        .isEqualTo(Color.CYAN.getRGB() & 0xFFFFFF);
  }

  @DisplayName("An imported flag takes the place of the nation's gif flags, but a large gif stays")
  @Test
  void importedFlagKeepsLargeGif() throws Exception {
    givenSavedMap();
    final Path flags = givenRoot().resolve("flags");
    Files.writeString(flags.resolve("Red.gif"), "gif");
    Files.writeString(flags.resolve("Red_large.gif"), "gif");

    MapFolder.importFlag(givenRoot(), "Red", givenPicture(300, 200, Color.GREEN));

    assertThat(flags.resolve("Red.gif")).as("the game would take it before the png").doesNotExist();
    assertThat(flags.resolve("Red_large.gif"))
        .as("the game reads a large flag only as png: the gif hides nothing and stays")
        .exists();
  }

  @DisplayName("Update the small map makes it from the picture with the relief, the old as .bak")
  @Test
  void updatesSmallMapWithRelief() throws Exception {
    final MapProject project = givenSavedMap();
    final Path smallMap = givenRoot().resolve("smallMap.png");
    final BufferedImage first = MapPictureTest.givenPicture();
    project.setRelief(givenPicture(first.getWidth(), first.getHeight(), Color.MAGENTA));
    project.save();

    MapFolder.writeSmallMap(givenRoot(), project.getFiles().shownPicture().orElseThrow());

    assertThat(ImageIO.read(smallMap.toFile()).getRGB(180, 160) & 0xFFFFFF)
        .as("the sea under the relief")
        .isEqualTo(Color.MAGENTA.getRGB() & 0xFFFFFF);
    assertThat(smallMap.resolveSibling("smallMap.png.bak")).as("the small map before").exists();
  }

  @DisplayName("The small map is written into the file the game reads: a jpeg before a png")
  @Test
  void writesTheSmallMapTheGameReads() throws Exception {
    final Path folder = Files.createDirectories(maps.resolve("old_map"));
    ImageIO.write(
        new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB),
        "jpeg",
        folder.resolve("smallMap.jpeg").toFile());

    MapFolder.writeSmallMap(folder, givenPicture(500, 300, Color.BLUE));

    assertThat(ImageIO.read(folder.resolve("smallMap.jpeg").toFile()).getWidth())
        .as("the jpeg the game takes first, 250 px wide")
        .isEqualTo(250);
    assertThat(folder.resolve("smallMap.png")).as("no second small map").doesNotExist();
    assertThat(folder.resolve("smallMap.jpeg.bak")).as("the one before").exists();
  }

  @DisplayName("Units looks for missing pictures only while it shows")
  @Test
  void unitsLooksOnlyWhileShowing() throws Exception {
    final MapProject project = givenSavedMap();
    project.addUnitType("Ranger", "infantry");
    project.edit(game -> MapXml.withUnits(game, "Territory 1", "Red", "Ranger", 1));
    project.save();
    Files.delete(givenRoot().resolve("units/Red/Ranger.png"));
    final UnitsEditor units = new UnitsEditor(project);
    final Method choose = UnitsEditor.class.getDeclaredMethod("choose", String.class);
    choose.setAccessible(true);
    final Field found = UnitsEditor.class.getDeclaredField("withoutPicture");
    found.setAccessible(true);

    choose.invoke(units, "Ranger");
    project.addNation("Green");

    assertThat(found.get(units))
        .as("not on screen: the map's folder is not read for a pick or a change of the map")
        .isNull();
  }
}
