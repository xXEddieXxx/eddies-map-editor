package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapTexts.Entry;
import eddies.mapeditor.MapTexts.Kind;

final class MapTextsTest {
  @DisplayName("An internal name reads in plain words: no prefix of its kind, words apart")
  @Test
  void plainNames() {
    assertThat(
            Stream.of(
                    "conditionAttachment_Japanese_hold_territories",
                    "triggerAttachmentGermansWin",
                    "productionChinese",
                    "production",
                    "productions",
                    "GermanAttachmentBonus",
                    "Germans")
                .map(MapTexts::plainName))
        .as("underscores and camelCase as spaces; a name that is only the prefix stays")
        .containsExactly(
            "Japanese hold territories",
            "Germans Win",
            "Chinese",
            "production",
            "productions",
            "German Attachment Bonus",
            "Germans");
  }
  @DisplayName("Lines end as Properties ends them: a lone CR too; other keys stay in their lines")
  @Test
  void editsTextsWithLoneCarriageReturns() {
    final String text = "aa=1\rbb=2\rcc=3\r";

    assertThat(MapTexts.withEntry(text, "aa", "X"))
        .as("only aa's line changes, its line end kept")
        .isEqualTo("aa=X\rbb=2\rcc=3\r");
    assertThat(MapTexts.withoutEntry(text, "bb")).as("only bb's line goes").isEqualTo("aa=1\rcc=3\r");
    assertThat(MapTexts.withEntry(text, "dd", "4"))
        .as("a new key is added with the text's line end")
        .isEqualTo("aa=1\rbb=2\rcc=3\rdd=4\r");
  }

  @DisplayName("A value ending in an escaped space does not take the next line with it")
  @Test
  void escapedSpaceEndsItsLine() {
    final String text = "aa=b\\ \ncc=d\n";

    assertThat(MapTexts.entries(text))
        .as("two entries, as Properties reads them")
        .containsExactly(new Entry("aa", "b "), new Entry("cc", "d"));
    assertThat(MapTexts.withEntry(text, "aa", "z")).as("cc stays").isEqualTo("aa=z\ncc=d\n");
    assertThat(MapTexts.withoutEntry(text, "aa")).as("cc stays").isEqualTo("cc=d\n");
  }

  @DisplayName("A continued line is one entry; of a key given twice the last is the one changed")
  @Test
  void continuedAndRepeatedKeys() {
    final String text = "aa=one \\\r\n   two\r\nbb=1\r\nbb=2\r\n# end\r\n";

    assertThat(MapTexts.withEntry(text, "bb", "9"))
        .as("the one the game reads; the continued line untouched")
        .isEqualTo("aa=one \\\r\n   two\r\nbb=1\r\nbb=9\r\n# end\r\n");
    assertThat(MapTexts.withEntry(text, "aa", "x"))
        .as("both lines of the continued entry make way for the one")
        .isEqualTo("aa=x\r\nbb=1\r\nbb=2\r\n# end\r\n");
    assertThat(MapTexts.withoutEntry(text, "bb"))
        .as("removed wherever it is given")
        .isEqualTo("aa=one \\\r\n   two\r\n# end\r\n");
    assertThat(MapTexts.withEntries(text, Map.of("bb", "9", "new key", "\u00e4"), List.of("aa")))
        .as("several changes in one go: changed, removed, added with its escapes")
        .isEqualTo("bb=1\r\nbb=9\r\n# end\r\nnew\\ key=\\u00E4\r\n");
  }

  @DisplayName("An edit that would lose an entry is not written: the entries are written anew")
  @Test
  void rewritesRatherThanLose() throws Exception {
    final String before = "# mine\naa=1\nbb=2\n";

    final String kept =
        MapTexts.orRewritten(before, "# mine\naa=X\nbb=2\n", Map.of("aa", "X"), List.of());
    final String saved = MapTexts.orRewritten(before, "aa=X\n", Map.of("aa", "X"), List.of());

    final Properties read = new Properties();
    read.load(new StringReader(saved));
    assertThat(kept).as("an edit that reads as meant stays as it is").isEqualTo("# mine\naa=X\nbb=2\n");
    assertThat(read)
        .as("an edit that lost bb is replaced by all the entries meant")
        .containsOnly(Map.entry("aa", "X"), Map.entry("bb", "2"));
  }

  private static final String MESSAGES =
      """
      # messages of the triggers
      Welcome=<body>Hello</body>
      Long=first \\
        second
      Twice=old
      Twice=read
      """;

  @TempDir Path folder;

  private static MapTexts givenMessages() {
    return new MapTexts(Map.of(Kind.NOTIFICATIONS, MESSAGES));
  }

  private MapProject givenMinimapWithMessages() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        MapTextsTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    Files.writeString(folder.resolve("notifications.properties"), MESSAGES);
    return MapProject.open(xml);
  }

  /** The minimap with a trigger of the Russians that shows the message "Hello". */
  static Game givenMinimapWithTrigger(final MapProject project) {
    final Game withTrigger =
        AttachmentsXml.withNewAttachment(
            project.game(), "TriggerAttachment", "triggerAttachmentHello", "Russians");
    final int last = AttachmentsXml.attachments(withTrigger).size() - 1;
    return AttachmentsXml.withAttachment(
        withTrigger,
        last,
        AttachmentsXml.withNewOption(
            AttachmentsXml.attachments(withTrigger).get(last),
            Option.builder().name("notification").value("Hello").build()));
  }

  @DisplayName("Changing an entry keeps the other lines, comments and long entries as they were")
  @Test
  void changesOneEntryInPlace() {
    final MapTexts messages = givenMessages();

    final MapTexts changed = messages.with(Kind.NOTIFICATIONS, "Welcome", "<body>Hi</body>");
    final MapTexts added = messages.with(Kind.NOTIFICATIONS, "New", "line one\nzwei ü");
    final MapTexts removed = messages.without(Kind.NOTIFICATIONS, "Twice");

    assertThat(changed.text(Kind.NOTIFICATIONS))
        .as("only the entry's line changed")
        .isEqualTo(MESSAGES.replace("<body>Hello</body>", "<body>Hi</body>"));
    assertThat(messages.entries(Kind.NOTIFICATIONS))
        .as("a long entry is one, and of a key given twice the game reads the last")
        .contains(new Entry("Long", "first second"), new Entry("Twice", "read"));
    assertThat(added.text(Kind.NOTIFICATIONS))
        .as("a new entry comes at the end, escaped as the game reads the file")
        .startsWith(MESSAGES)
        .endsWith("New=line one\\nzwei \\u00FC\n");
    assertThat(added.value(Kind.NOTIFICATIONS, "New"))
        .as("and reads back as typed")
        .contains("line one\nzwei ü");
    assertThat(removed.text(Kind.NOTIFICATIONS))
        .as("a removed key is gone in every line that gives it, the rest stays")
        .isEqualTo(MESSAGES.replace("Twice=old\n", "").replace("Twice=read\n", ""));
    assertThat(messages.with(Kind.NOTIFICATIONS, "Twice", "read"))
        .as("setting the text it has changes nothing")
        .isSameAs(messages);
  }

  @DisplayName("A changed text is a step to undo, and a save writes it beside the old as .bak")
  @Test
  void textsAreStepsAndSaved() throws Exception {
    final MapProject project = givenMinimapWithMessages();
    final Path file = folder.resolve("notifications.properties");

    project.setText(Kind.NOTIFICATIONS, "Welcome", "Hi");
    project.undo();
    final String afterUndo = project.texts().value(Kind.NOTIFICATIONS, "Welcome").orElseThrow();
    project.redo();
    project.save();

    assertThat(afterUndo).as("undo brings the text back").isEqualTo("<body>Hello</body>");
    final Properties saved = new Properties();
    saved.load(new StringReader(Files.readString(file, StandardCharsets.ISO_8859_1)));
    assertThat(saved.getProperty("Welcome")).as("the save wrote the new text").isEqualTo("Hi");
    assertThat(saved.getProperty("Long")).as("and kept the others").isEqualTo("first second");
    assertThat(Files.readString(folder.resolve("notifications.properties.bak")))
        .as("the first version stays as .bak")
        .isEqualTo(MESSAGES);
  }

  @DisplayName("Keys are explained by the game, and the game's triggers and units are offered")
  @Test
  void explainsAndSuggestsKeys() throws Exception {
    final MapProject project = givenMinimapWithMessages();
    final Game game = givenMinimapWithTrigger(project);

    final String message = MapTexts.explain(Kind.NOTIFICATIONS, "Hello", game);
    final String tooltip = MapTexts.explain(Kind.TOOLTIPS, "tooltip.unit.infantry.append", game);
    final String objective = MapTexts.explain(Kind.OBJECTIVES, "Minimap.Russians;nothing", game);

    assertThat(message).as("which trigger shows it").contains("triggerAttachmentHello");
    assertThat(tooltip)
        .as("what the tool tip key does")
        .isEqualTo("Added below the tool tip of infantry");
    assertThat(objective).as("a condition the game lacks").contains("no condition nothing");
    assertThat(MapTexts.suggestions(Kind.NOTIFICATIONS, game, project.texts()))
        .as("the trigger's message is offered")
        .contains(new Entry("Hello", ""));
    assertThat(MapTexts.suggestions(Kind.OBJECTIVES, game, project.texts()))
        .as("the trigger as an objective of its nation, in the game's keys")
        .contains(new Entry("Minimap.Russians;triggerAttachmentHello", ""));
  }

  @DisplayName("An objective of another game is told by its key, and explained in plain words")
  @Test
  void tellsForeignObjectives() throws Exception {
    final Game game = givenMinimapWithTrigger(givenMinimapWithMessages());

    final String foreign = MapTexts.explain(Kind.OBJECTIVES, "Big_World.Russians;x", game);

    assertThat(MapTexts.isForeign("Big_World.Russians;x", game))
        .as("a key with another game's start")
        .isTrue();
    assertThat(MapTexts.isForeign("Minimap.Russians;x", game))
        .as("a key of this game")
        .isFalse();
    assertThat(MapTexts.isForeign("Objectives.Panel.Name", game))
        .as("the tab's name belongs to every game")
        .isFalse();
    assertThat(foreign).as("plain words, no marker").isEqualTo("For another game");
  }

  @DisplayName("Objectives offer the conditions and triggers like objectives, all on request")
  @Test
  void offersObjectivesFirst() throws Exception {
    final MapProject project = givenMinimapWithMessages();
    final Game game =
        AttachmentsXml.withNewAttachment(
            givenMinimapWithTrigger(project),
            "RulesAttachment",
            "conditionAttachmentHelper",
            "Russians");

    final List<String> few =
        MapTexts.suggestions(Kind.OBJECTIVES, game, project.texts()).stream()
            .map(Entry::key)
            .toList();
    final List<String> all =
        MapTexts.allSuggestions(Kind.OBJECTIVES, game, project.texts()).stream()
            .map(Entry::key)
            .toList();

    assertThat(few)
        .as("a trigger that tells something is offered, a helper condition not")
        .contains("Minimap.Russians;triggerAttachmentHello")
        .doesNotContain("Minimap.Russians;conditionAttachmentHelper");
    assertThat(all)
        .as("all conditions on request")
        .contains("Minimap.Russians;conditionAttachmentHelper");
  }

  @DisplayName("A file left without entries is removed on save, its first version kept as .bak")
  @Test
  void emptiedFileIsRemoved() throws Exception {
    final MapProject project = givenMinimapWithMessages();
    final Path file = folder.resolve("notifications.properties");
    final String original = "Welcome=Hello\nBye=Good bye\n";
    Files.writeString(file, original);
    final MapProject reopened = MapProject.open(project.getXmlFile().orElseThrow());

    reopened.removeText(Kind.NOTIFICATIONS, "Welcome");
    reopened.removeText(Kind.NOTIFICATIONS, "Bye");
    reopened.save();

    assertThat(file).as("the emptied file is gone").doesNotExist();
    assertThat(Files.readString(folder.resolve("notifications.properties.bak")))
        .as("its first version stays as .bak")
        .isEqualTo(original);
  }

  @DisplayName("A texts file that does not read leaves the map opening, unchanged and not written")
  @Test
  void unreadableFileIsLeftAlone() throws Exception {
    final MapProject project = givenMinimapWithMessages();
    final Path file = folder.resolve("tooltips.properties");
    // a folder in the file's place cannot be read as a file
    Files.createDirectory(file);

    final MapProject reopened = MapProject.open(project.getXmlFile().orElseThrow());
    reopened.setText(Kind.TOOLTIPS, "tooltip.unit.infantry", "Walks");
    reopened.save();

    assertThat(reopened.canUndo()).as("no change to a file that did not read").isFalse();
    assertThat(file).as("and the save left it as it was").isDirectory();
    assertThat(reopened.texts().entries(Kind.NOTIFICATIONS))
        .as("the other files read")
        .isNotEmpty();
  }


  @DisplayName("A sound set keeps the file's comments, other lines and ISO-8859-1, with a .bak")
  @Test
  void soundsKeepTheirFile() throws Exception {
    final Path xml = givenMinimapWithMessages().getXmlFile().orElseThrow();
    final Path file = folder.resolve("sounds.properties");
    final String before =
        "# the map's sounds\nSound.Default.Folder=classical\nmy_own=réveil.mp3\n";
    Files.writeString(file, before, StandardCharsets.ISO_8859_1);
    final MapProject project = MapProject.open(xml);

    project.setText(Kind.SOUNDS, "battle_land_Russians", "mine/battle.mp3");
    project.undo();
    final boolean undone = project.texts().value(Kind.SOUNDS, "battle_land_Russians").isEmpty();
    project.redo();
    project.save();

    assertThat(undone).as("one undo takes the sound back").isTrue();
    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1))
        .as("the lines before kept as they were, the new sound added")
        .isEqualTo(before + "battle_land_Russians=mine/battle.mp3\n");
    assertThat(Files.readString(folder.resolve("sounds.properties.bak"), StandardCharsets.ISO_8859_1))
        .as("the first version stays as .bak")
        .isEqualTo(before);
  }

  @DisplayName("Sounds are named plainly, also a nation's, and a trigger's message sounds offered")
  @Test
  void explainsAndOffersSounds() throws Exception {
    final MapProject project = givenMinimapWithMessages();
    final Game game = givenMinimapWithTrigger(project);
    final MapTexts texts =
        new MapTexts(Map.of(Kind.NOTIFICATIONS, "Hello=<body>Hi</body>\nHello_sounds=fanfare\n"));

    final List<String> keys = MapTexts.soundKeys(game, texts);

    assertThat(MapTexts.explain(Kind.SOUNDS, "battle_land_Russians", game))
        .as("the game's sound for a nation")
        .isEqualTo("Land battle for Russians");
    assertThat(MapTexts.explain(Kind.SOUNDS, "notification_fanfare", game))
        .as("a message's sound")
        .isEqualTo("With a message, its sounds fanfare");
    assertThat(keys)
        .as("the game's sounds, a nation's, and the one the trigger's message names")
        .contains("Sound.Default.Folder", "battle_land", "battle_land_Russians", "notification_fanfare");
  }

  private static final String G40_OBJECTIVES =
      """
      # copied from another game
      Objectives.Panel.Name=Objectives
      World_War_II_Global_1940_Alpha_3.TABLEGROUP.01;Russians=objectiveAttachment_Russians_1_Hold_Moscow
      World_War_II_Global_1940_Alpha_3.Russians;objectiveAttachment_Russians_1_Hold_Moscow=Hold \\
        Moscow
      """;

  private MapProject givenMinimapWithForeignObjectives() throws Exception {
    final Path xml = givenMinimapWithMessages().getXmlFile().orElseThrow();
    Files.writeString(folder.resolve("objectives.properties"), G40_OBJECTIVES);
    return MapProject.open(xml);
  }

  @DisplayName("Each kind's keys get a title in plain words, the key when there is none")
  @Test
  void titlesInPlainWords() throws Exception {
    final Game game = givenMinimapWithMessages().game();

    assertThat(MapTexts.title(Kind.OBJECTIVES, "Objectives.Panel.Name", game))
        .as("the tab's name")
        .isEqualTo("Name of the objectives tab");
    assertThat(MapTexts.title(Kind.OBJECTIVES, "Other_Game.TABLEGROUP.01;Germans", game))
        .as("a group, its number without zeros, whatever the game's start")
        .isEqualTo("Group 1 · Germans");
    assertThat(
            MapTexts.title(
                Kind.OBJECTIVES, "A.B.Germans;objectiveAttachment_Germans_1_Trade_with_Russia", game))
        .as("a condition without the kind of attachment, the nation and the number")
        .isEqualTo("Germans · Trade with Russia");
    assertThat(MapTexts.title(Kind.OBJECTIVES, "Minimap.Germans;objectiveAttachment_Germans_1", game))
        .as("nothing left: the key")
        .isEqualTo("Minimap.Germans;objectiveAttachment_Germans_1");
    assertThat(MapTexts.title(Kind.NOTIFICATIONS, "Germans_Win_sounds", game))
        .as("a message's sounds")
        .isEqualTo("Sound of Germans_Win");
    assertThat(MapTexts.title(Kind.NOTIFICATIONS, "Germans_Win", game))
        .as("a message is its own name")
        .isEqualTo("Germans_Win");
    assertThat(MapTexts.title(Kind.TOOLTIPS, "tooltip.unit.infantry", game))
        .as("a unit's tool tip")
        .isEqualTo("Tool tip of infantry");
    assertThat(MapTexts.title(Kind.TOOLTIPS, "tooltip.unit.infantry.append", game))
        .as("added to a unit's tool tip")
        .isEqualTo("Added to the tool tip of infantry");
    assertThat(MapTexts.title(Kind.TOOLTIPS, "tooltip.unit.infantry.Russians", game))
        .as("a nation's tool tip of a unit")
        .isEqualTo("Tool tip of infantry for Russians");
    assertThat(MapTexts.title(Kind.TOOLTIPS, "tooltip.unit.dragon", game))
        .as("no such unit: the key")
        .isEqualTo("tooltip.unit.dragon");
    assertThat(MapTexts.title(Kind.POLITICS, "Axis_To_War.BUTTON", game))
        .as("an action's text and its part")
        .isEqualTo("Axis_To_War · Button");
  }

  @DisplayName("Objective keys all of another game are found, not once one is this game's")
  @Test
  void findsForeignPrefix() throws Exception {
    final Game game = givenMinimapWithMessages().game();
    final MapTexts foreign = new MapTexts(Map.of(Kind.OBJECTIVES, G40_OBJECTIVES));
    final MapTexts groupsOnly =
        new MapTexts(Map.of(Kind.OBJECTIVES, "Old.TABLEGROUP.01;Russians=a\n"));

    final MapTexts oneOwn = foreign.with(Kind.OBJECTIVES, "Minimap.Russians;x", "X");

    assertThat(MapTexts.foreignPrefix(foreign, game))
        .as("the other game's start")
        .contains("World_War_II_Global_1940_Alpha_3.");
    assertThat(MapTexts.foreignPrefix(oneOwn, game))
        .as("a key of this game: the rest is not all another game's")
        .isEmpty();
    assertThat(MapTexts.foreignPrefix(groupsOnly, game))
        .as("groups alone also tell the start")
        .contains("Old.");
    assertThat(MapTexts.foreignObjectivesProblem(foreign, game))
        .as("the problem counts the keys bar the tab's name")
        .contains("2 objective texts are for another game");
  }

  @DisplayName("Renaming the keys' start keeps texts, order, comments and a key already there")
  @Test
  void renamesKeyPrefix() {
    final MapTexts foreign =
        new MapTexts(
            Map.of(
                Kind.OBJECTIVES,
                G40_OBJECTIVES + "Minimap.TABLEGROUP.01;Russians=mine\n"));

    final MapTexts renamed =
        foreign.withKeyPrefix(Kind.OBJECTIVES, "World_War_II_Global_1940_Alpha_3.", "Minimap.");

    assertThat(renamed.text(Kind.OBJECTIVES))
        .as("only the start of the keys changed, the long text's lines kept")
        .isEqualTo(
            G40_OBJECTIVES.replace(
                    "World_War_II_Global_1940_Alpha_3.Russians", "Minimap.Russians")
                + "Minimap.TABLEGROUP.01;Russians=mine\n");
    assertThat(renamed.value(Kind.OBJECTIVES, "Minimap.Russians;objectiveAttachment_Russians_1_Hold_Moscow"))
        .as("the text reads as before")
        .contains("Hold Moscow");
    assertThat(foreign.withKeyPrefix(Kind.OBJECTIVES, "Nothing.", "Minimap."))
        .as("nothing to rename changes nothing")
        .isSameAs(foreign);
  }

  @DisplayName("Using foreign objective texts for this game is one step, and a problem before")
  @Test
  void foreignObjectivesAreAProblemAndOneStep() throws Exception {
    final MapProject project = givenMinimapWithForeignObjectives();
    final List<MapChecks.Problem> before = project.problems();

    project.edit(
        g -> g,
        t -> t.withKeyPrefix(Kind.OBJECTIVES, "World_War_II_Global_1940_Alpha_3.", "Minimap."));
    final List<MapChecks.Problem> after = project.problems();
    project.undo();

    assertThat(before)
        .as("the problem is listed once, for the Texts tool")
        .filteredOn(p -> p.text().equals("2 objective texts are for another game"))
        .singleElement()
        .extracting(MapChecks.Problem::fixedIn)
        .isEqualTo("Texts");
    assertThat(after).as("gone once renamed").noneMatch(p -> p.text().contains("another game"));
    assertThat(MapTexts.foreignPrefix(project.texts(), project.game()))
        .as("one undo brings all keys back")
        .contains("World_War_II_Global_1940_Alpha_3.");
  }
}
