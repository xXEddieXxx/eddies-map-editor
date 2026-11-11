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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapTexts.Entry;
import eddies.mapeditor.MapTexts.Kind;

final class MapTextsTest {
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
}
