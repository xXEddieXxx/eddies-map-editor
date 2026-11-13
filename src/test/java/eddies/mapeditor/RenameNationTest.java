package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapTexts.Kind;

final class RenameNationTest {
  private static final String OLD = "Russians";
  private static final String NEW = "Soviets";

  @TempDir Path folder;

  /** A map folder with the minimap as games/minimap.xml and pictures of the Russians. */
  private MapProject givenMap() throws Exception {
    final Path xml = Files.createDirectories(folder.resolve("games")).resolve("minimap.xml");
    try (InputStream original =
        RenameNationTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    // the outlines tell the editor where the map's files are
    Files.writeString(
        folder.resolve("polygons.txt"), "red north 1  <  (0,0) (10,0) (10,10) (0,10) >\n");
    Files.createDirectories(folder.resolve("flags"));
    for (final String flag :
        List.of("Russians.png", "Russians_small.gif", "Russians_large.png", "Italians.png")) {
      Files.writeString(folder.resolve("flags").resolve(flag), flag);
    }
    Files.createDirectories(folder.resolve("units").resolve(OLD));
    Files.writeString(folder.resolve("units").resolve(OLD).resolve("infantry.png"), "infantry");
    Files.writeString(
        folder.resolve("map.properties"),
        "color.Russians=AA0000\nunits.transform.color.Russians=AA0000\ncolor.Italians=00AA00\n"
            + "guide.noCapital=Italians,Russians\n");
    Files.writeString(
        folder.resolve("objectives.properties"),
        "Minimap.TABLEGROUP.01;Russians=objectiveAttachmentRussians1\n"
            + "Minimap.Russians;objectiveAttachmentRussians1=Hold the Russians' land\n"
            + "Other_Game.Russians;objectiveAttachmentRussians1=another game's\n");
    Files.writeString(
        folder.resolve("tooltips.properties"),
        "tooltip.unit.infantry.Russians=Riflemen\ntooltip.unit.infantry.Russians.append=more\n");
    Files.writeString(
        folder.resolve("sounds.properties"),
        "phase_purchase_Russians=anthem.mp3\nnotification_Russians=stays.mp3\n");
    Files.writeString(folder.resolve("production_tabs.Russians.properties"), "production_tabs.rows=2\n");
    return MapProject.open(xml);
  }

  private void givenOtherGameWithRussians() throws Exception {
    try (InputStream original =
        RenameNationTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, folder.resolve("games").resolve("other.xml"));
    }
  }

  private static List<String> namings(final Game game, final String nation) {
    final List<String> found = new ArrayList<>();
    for (final Attachment a : AttachmentsXml.attachments(game)) {
      if ("player".equals(a.getType()) && nation.equals(a.getAttachTo())) {
        found.add("attachTo of " + a.getName());
      }
      for (final Option o : Optional.ofNullable(a.getOptions()).orElse(List.of())) {
        if (!List.of("notification", "victory", "text").contains(o.getName())
            && List.of((o.getCount() + ":" + o.getValue()).split(":")).contains(nation)) {
          found.add(o.getName() + " of " + a.getName());
        }
      }
    }
    return found;
  }

  @DisplayName("A nation of a big game is renamed everywhere it is named, and TripleA loads it")
  @Test
  void renamesEverywhereInABigGame() throws Exception {
    // given
    final Game g40 = GameRulesXmlTest.givenG40();
    final int named = namings(g40, OLD).size();

    // when
    final Game renamed = MapXml.withRenamedNation(g40, OLD, NEW);

    // then
    final Path xml = folder.resolve("g40.xml");
    GameXmlKeeper.export(renamed, xml, xml);
    assertThat(MapChecks.findError(xml)).as("TripleA loads the renamed game").isEmpty();
    assertThat(MapXml.nations(renamed))
        .as("the player list, in its place")
        .contains(NEW)
        .doesNotContain(OLD)
        .hasSameSizeAs(MapXml.nations(g40));
    assertThat(MapXml.nations(renamed).indexOf(NEW))
        .as("its place in the list")
        .isEqualTo(MapXml.nations(g40).indexOf(OLD));
    assertThat(named).as("G40's attachments name the Russians often").isGreaterThan(50);
    assertThat(namings(renamed, OLD)).as("no attachment or option still names Russians").isEmpty();
    assertThat(namings(renamed, NEW)).as("they all name Soviets").hasSize(named);
    final String text = Files.readString(xml);
    assertThat(text)
        .as("owners, units, production, steps, alliances, relationships, the bid option")
        .doesNotContain("owner=\"Russians\"")
        .doesNotContain("player=\"Russians\"")
        .doesNotContain("player1=\"Russians\"")
        .doesNotContain("player2=\"Russians\"")
        .doesNotContain("\"Russians bid\"")
        .contains("owner=\"Soviets\"")
        .contains("\"Soviets bid\"");
    assertThat(MapXml.alliances(renamed, NEW)).as("its alliance").containsExactly("Allies");
    assertThat(text)
        .as("names that only hold the word stay: a territory, a step, an attachment, a text's key")
        .contains("<territory name=\"Russia\"")
        .contains("name=\"russianBid\"")
        .contains("conditionAttachment_Russians_1_Lend_Lease")
        .contains("<option name=\"text\" value=\"Russians_War_Japanese\"");
  }

  @DisplayName("A rename is one undo step, with the nation's settings and texts")
  @Test
  void renamesAsOneStepWithSettingsAndTexts() throws Exception {
    // given
    final MapProject project = givenMap();
    final Game before = project.game();

    // when
    final boolean renamed = project.renameNation(OLD, NEW);

    // then
    assertThat(renamed).as("renamed").isTrue();
    final Properties properties = project.getFiles().properties();
    assertThat(properties.getProperty("color.Soviets")).as("its color").isEqualTo("AA0000");
    assertThat(properties.getProperty("units.transform.color.Soviets"))
        .as("its units' tint")
        .isEqualTo("AA0000");
    assertThat(properties.getProperty("color.Russians")).as("the old color key").isNull();
    assertThat(project.getFiles().nationsWithoutCapital())
        .as("has no capital follows the name")
        .containsExactly("Italians", NEW);
    final MapTexts texts = project.texts();
    assertThat(texts.text(Kind.OBJECTIVES))
        .as("this game's objective keys and the group named after it; another game's key stays")
        .contains("Minimap.TABLEGROUP.01;Soviets=objectiveAttachmentRussians1")
        .contains("Minimap.Soviets;objectiveAttachmentRussians1=Hold the Russians' land")
        .contains("Other_Game.Russians;objectiveAttachmentRussians1");
    assertThat(texts.text(Kind.TOOLTIPS))
        .as("its unit tool tips")
        .isEqualTo(
            "tooltip.unit.infantry.Soviets=Riflemen\ntooltip.unit.infantry.Soviets.append=more\n");
    assertThat(texts.text(Kind.SOUNDS))
        .as("its sounds; a message's sound of that name stays")
        .isEqualTo("phase_purchase_Soviets=anthem.mp3\nnotification_Russians=stays.mp3\n");
    assertThat(texts.buyTabs("production_tabs.Soviets.properties"))
        .as("its buy window tabs")
        .contains("rows=2");
    assertThat(texts.buyTabs("production_tabs.Russians.properties")).as("the old tabs").isBlank();

    // when
    project.undo();

    // then
    assertThat(project.game()).as("one undo brings the whole game back").isSameAs(before);
    assertThat(project.getFiles().properties().getProperty("color.Russians"))
        .as("and its color")
        .isEqualTo("AA0000");
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @DisplayName("A name that is empty, taken, unfit for files or named like a territory is refused")
  @Test
  void refusesNames() throws Exception {
    // given
    final MapProject project = givenMap();
    final Game before = project.game();
    final String territory = MapXml.territories(before).getFirst();

    // when, then
    assertThat(project.whyNotRenamedNation(OLD, " ")).as("empty").contains("A nation needs a name.");
    assertThat(project.whyNotRenamedNation(OLD, "Italians").orElse(""))
        .as("another nation's")
        .startsWith("Already used");
    assertThat(project.whyNotRenamedNation(OLD, "italians").orElse(""))
        .as("another nation's but for the case, as its flag would be the same file")
        .startsWith("Already used");
    assertThat(project.whyNotRenamedNation(OLD, territory).orElse(""))
        .as("a territory's")
        .startsWith("Already used");
    assertThat(project.whyNotRenamedNation(OLD, "So/viets").orElse(""))
        .as("a character no file name takes")
        .startsWith("No : <");
    assertThat(project.whyNotRenamedNation(OLD, "So:viets").orElse(""))
        .as("the colon parts names in options")
        .startsWith("No : <");
    assertThat(project.whyNotRenamedNation(OLD, NEW)).as("a free name").isEmpty();
    assertThat(project.renameNation(OLD, "Italians")).as("a refused rename").isFalse();
    assertThat(project.game()).as("changes nothing").isSameAs(before);

    // given a nation called like a step, which triggers name too
    project.addNation("russianBid");

    // then
    assertThat(project.whyNotRenamedNation("russianBid", "Free name").orElse(""))
        .as("a nation named like a step keeps its name")
        .contains("also names something else");
    project.undo();

    // given a territory called like the nation: options name both alike
    project.edit(g -> MapXml.withRenamedTerritory(g, territory, "Italians"));

    // then
    assertThat(project.whyNotRenamedNation("Italians", "Free name").orElse(""))
        .as("a nation named like a territory keeps its name")
        .contains("also names something else");
  }

  @DisplayName("A nation that foreach attachments build names from is not renamed")
  @Test
  void refusesNamesBuiltByForeach() throws Exception {
    // given a condition made for each nation, named after it
    final Path xml = folder.resolve("foreach.xml");
    try (InputStream original =
        RenameNationTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.writeString(
          xml,
          new String(original.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .replace(
                  "<attachmentList>",
                  "<attachmentList><attachment foreach=\"$All$\" name=\"conditionAttachment_@All@\""
                      + " attachTo=\"@All@\" javaClass=\"RulesAttachment\" type=\"player\">"
                      + "<option name=\"rounds\" value=\"1\"/></attachment>")
              .replace(
                  "</game>",
                  "<variableList><variable name=\"All\"><element name=\"Russians\"/>"
                      + "<element name=\"Italians\"/></variable></variableList></game>"));
    }
    final MapProject project = MapProject.open(xml);

    // when
    final Optional<String> why = project.whyNotRenamedNation(OLD, NEW);

    // then
    assertThat(MapXml.buildsNamesFrom(project.game(), OLD)).as("names are built from it").isTrue();
    assertThat(why.orElse("")).as("refused, saying why").contains("foreach");
  }

  @DisplayName("The flags and unit pictures keep their name until the save moves them")
  @Test
  void movesPicturesOnSave() throws Exception {
    // given
    final MapProject project = givenMap();
    final Path flags = folder.resolve("flags");

    // when
    project.renameNation(OLD, NEW);

    // then
    assertThat(flags.resolve("Russians.png")).as("nothing moves before the save").exists();
    assertThat(project.nationNameProblem(OLD).orElse(""))
        .as("a new nation may not take the name its pictures still have")
        .startsWith("Save first");
    assertThat(project.nameProblem(OLD)).as("a territory or unit type may").isEmpty();
    assertThat(project.getFiles().nationFilesName(NEW))
        .as("the editor finds the pictures under the saved name")
        .isEqualTo(OLD);
    assertThat(project.unitPicture(NEW, Color.RED, "infantry"))
        .as("no picture is read from a text file, but none is made up either")
        .isNotNull();

    // when
    project.save();

    // then
    assertThat(flags.resolve("Soviets.png")).as("the flag").hasContent("Russians.png");
    assertThat(flags.resolve("Soviets_small.gif")).as("the small gif").hasContent("Russians_small.gif");
    assertThat(flags.resolve("Soviets_large.png")).as("the large one").exists();
    assertThat(flags.resolve("Russians.png")).as("the old flag is gone").doesNotExist();
    assertThat(flags.resolve("Italians.png")).as("another nation's flag stays").exists();
    assertThat(folder.resolve("units").resolve(NEW).resolve("infantry.png"))
        .as("its unit pictures")
        .hasContent("infantry");
    assertThat(folder.resolve("units").resolve(OLD)).as("the old folder").doesNotExist();
    assertThat(project.getFiles().nationFilesName(NEW)).as("found under its own name now").isEqualTo(NEW);
    assertThat(Files.readString(folder.resolve("map.properties")))
        .as("map.properties has the new keys and none of the editor's own")
        .contains("color.Soviets=AA0000")
        .doesNotContain("color.Russians")
        .doesNotContain("editor.");
    assertThat(folder.resolve("production_tabs.Soviets.properties")).as("the tabs file").exists();
    assertThat(folder.resolve("production_tabs.Russians.properties")).as("the old one").doesNotExist();
    assertThat(MapChecks.findError(project.getXmlFile().orElseThrow()))
        .as("the saved game loads")
        .isEmpty();

    // when the rename is undone after the save
    project.undo();

    // then
    assertThat(MapXml.nations(project.game())).as("Russians again").contains(OLD);
    assertThat(project.getFiles().nationFilesName(OLD))
        .as("whose pictures are where the save put them")
        .isEqualTo(NEW);

    // when
    project.save();

    // then
    assertThat(flags.resolve("Russians.png")).as("and move back").hasContent("Russians.png");
    assertThat(flags.resolve("Soviets.png")).as("the other name is free again").doesNotExist();
  }

  @DisplayName("Where another game of the map has the nation, its pictures and settings are copied")
  @Test
  void copiesWhatGamesShare() throws Exception {
    // given
    final MapProject project = givenMap();
    givenOtherGameWithRussians();

    // when
    project.renameNation(OLD, NEW);
    project.save();

    // then
    final Path flags = folder.resolve("flags");
    assertThat(flags.resolve("Soviets.png")).as("the renamed nation's flag").exists();
    assertThat(flags.resolve("Russians.png")).as("the other game's stays").exists();
    assertThat(folder.resolve("units").resolve(OLD).resolve("infantry.png"))
        .as("its unit pictures too")
        .exists();
    assertThat(folder.resolve("units").resolve(NEW).resolve("infantry.png")).as("copied").exists();
    assertThat(Files.readString(folder.resolve("map.properties")))
        .as("both colors")
        .contains("color.Soviets=AA0000")
        .contains("color.Russians=AA0000");
    assertThat(Files.readString(folder.resolve("tooltips.properties")))
        .as("both tool tips")
        .contains("tooltip.unit.infantry.Soviets=Riflemen")
        .contains("tooltip.unit.infantry.Russians=Riflemen");
    assertThat(folder.resolve("production_tabs.Russians.properties")).as("its tabs stay").exists();
  }

  @DisplayName("A trigger's list that starts with -reset- or -clear- is renamed too")
  @Test
  void renamesAfterResetAndClear() {
    // given a trigger that empties a list first, as TripleA reads "-reset-" and "-clear-"
    final Game minimap = MapXmlTest.givenMinimap();
    final Attachment trigger =
        Attachment.builder()
            .name("triggerAttachmentGive")
            .attachTo("Italians")
            .javaClass("games.strategy.triplea.attachments.TriggerAttachment")
            .type("player")
            .options(
                List.of(
                    Option.builder()
                        .name("unitProperty")
                        .value("canBeGivenByTerritoryTo")
                        .count("-reset-Russians:Italians")
                        .build(),
                    Option.builder().name("players").value("-clear-Russians").build(),
                    Option.builder().name("players").value("-Russians:Russians").build(),
                    Option.builder().name("players").value("-reset-Russians2").build()))
            .build();
    final Game game =
        AttachmentsXml.withAttachments(
            minimap,
            java.util.stream.Stream.concat(
                    AttachmentsXml.attachments(minimap).stream(), java.util.stream.Stream.of(trigger))
                .toList());

    // when
    final Game renamed = MapXml.withRenamedNation(game, OLD, NEW);

    // then
    final List<Option> options = AttachmentsXml.attachments(renamed).getLast().getOptions();
    assertThat(options.get(0).getCount())
        .as("after -reset-, in the count the game reads before the value")
        .isEqualTo("-reset-Soviets:Italians");
    assertThat(options.get(1).getValue()).as("after -clear-").isEqualTo("-clear-Soviets");
    assertThat(options.get(2).getValue()).as("taken away, and plain").isEqualTo("-Soviets:Soviets");
    assertThat(options.get(3).getValue())
        .as("another name that only starts alike stays")
        .isEqualTo("-reset-Russians2");
  }

  @DisplayName("A save that fails before the game is written leaves the old pictures in place")
  @Test
  void failedSaveKeepsThePictures() throws Exception {
    // given: the game file cannot be written, as a folder stands where it is written first
    final MapProject project = givenMap();
    final Path xml = project.getXmlFile().orElseThrow();
    final Path blocked = xml.resolveSibling(xml.getFileName() + ".saving");
    Files.createDirectories(blocked.resolve("in the way"));
    final String game = Files.readString(xml);
    project.renameNation(OLD, NEW);

    // when
    final Throwable failure = org.assertj.core.api.Assertions.catchThrowable(project::save);

    // then
    assertThat(failure).as("the save fails").isInstanceOf(java.io.IOException.class);
    assertThat(Files.readString(xml)).as("the game there still names the Russians").isEqualTo(game);
    assertThat(folder.resolve("flags").resolve("Russians.png"))
        .as("and still finds their flag")
        .hasContent("Russians.png");
    assertThat(folder.resolve("units").resolve(OLD).resolve("infantry.png"))
        .as("and their unit pictures")
        .exists();
    assertThat(project.getFiles().nationFilesName(NEW))
        .as("the editor still looks under the old name")
        .isEqualTo(OLD);

    // when the way is free again
    Files.delete(blocked.resolve("in the way"));
    Files.delete(blocked);
    project.save();

    // then
    assertThat(folder.resolve("flags").resolve("Soviets.png")).as("the flag moved").exists();
    assertThat(folder.resolve("flags").resolve("Russians.png")).as("the old one gone").doesNotExist();
    assertThat(folder.resolve("units").resolve(OLD)).as("and the old folder").doesNotExist();
  }
}
