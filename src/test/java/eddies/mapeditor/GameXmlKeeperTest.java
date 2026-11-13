package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

final class GameXmlKeeperTest {
  private static final String NORTH = "red north 1";
  private static final Path DOWNLOADED_MAPS =
      Path.of(System.getProperty("user.home"), "triplea", "downloadedMaps");

  @TempDir Path folder;

  /** The minimap with what TripleA's model drops: DOCTYPE, comments, loader, versions. */
  private Path givenMinimap() throws IOException {
    return givenMinimap("<map>", "<map>");
  }

  private Path givenMinimap(final String original, final String replacement) throws IOException {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream in =
        GameXmlKeeperTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Files.writeString(
          xml,
          text.replace("<info name=\"Minimap\"/>", "<info name=\"Minimap\" version=\"1.7\"/>")
              .replace(original, replacement));
    }
    return xml;
  }

  @DisplayName("A save keeps the DOCTYPE, comments, info version, loader and minimum version")
  @Test
  void keepsWhatTheModelDrops() throws Exception {
    final Path xml = givenMinimap();
    final int comments = counts(xml).getOrDefault("#comment", 0);
    final MapProject project = MapProject.open(xml);
    project.edit(game -> MapXml.withOwner(game, NORTH, "Italians"));

    project.save();

    final String saved = Files.readString(xml);
    assertThat(saved)
        .as("what the model has no place for is still there")
        .contains("<!DOCTYPE game SYSTEM \"game.dtd\">")
        .contains("version=\"1.7\"")
        .contains("<loader javaClass=\"games.strategy.triplea.TripleA\"/>")
        .contains("<triplea minimumVersion=\"1.5\"/>")
        .contains("<!-- Territory Definitions -->");
    assertThat(counts(xml).get("#comment")).as("no comment lost").isEqualTo(comments);
    assertThat(saved.indexOf("<!-- Territory Connections -->"))
        .as("a comment stays before the element it was before")
        .isLessThan(saved.indexOf("<connection t1=\"red capitol\" t2=\"red north 1\""))
        .isGreaterThan(saved.lastIndexOf("<territory "));
    assertThat(MapXml.owner(MapProject.open(xml).game(), NORTH))
        .as("the change made in the editor is saved")
        .contains("Italians");
    assertThat(MapChecks.findError(xml)).as("the game still loads in TripleA").isEmpty();
  }

  @DisplayName("The comment before an element removed in the editor stays")
  @Test
  void keepsTheCommentOfARemovedElement() throws Exception {
    final String connection = "<connection t1=\"red capitol\" t2=\"red north 1\"/>";
    final Path xml = givenMinimap(connection, "<!-- the capitol's road -->" + connection);
    final MapProject project = MapProject.open(xml);
    project.edit(game -> MapXml.withoutConnection(game, "red capitol", NORTH));

    project.save();

    final String saved = Files.readString(xml);
    assertThat(saved).as("the connection is gone").doesNotContain(connection);
    assertThat(saved).as("its comment is not").contains("<!-- the capitol's road -->");
    assertThat(MapChecks.findError(xml)).as("the game still loads in TripleA").isEmpty();
  }

  @DisplayName("A step moved in the turn order is saved where it was moved to")
  @Test
  void savesMovedElementsInTheirNewOrder() throws Exception {
    final MapProject project = MapProject.open(givenMinimap());
    project.edit(game -> RulesXml.withStepMoved(game, 1, 4));
    final List<String> moved = stepNames(project.game());

    project.save();

    assertThat(stepNames(MapProject.open(project.getXmlFile().orElseThrow()).game()))
        .as("opened again, the steps are in the order the editor gave them")
        .isEqualTo(moved);
  }

  @DisplayName("A new trigger is saved after the condition it names, though another was removed")
  @Test
  void savesANewElementInTheModelsPlace() throws Exception {
    final Path xml =
        givenMinimap(
            "    </attachmentList>",
            """
                <attachment name="conditionAttachmentHeld" attachTo="Russians" \
            javaClass="games.strategy.triplea.attachments.RulesAttachment" type="player">
                    <option name="directOwnershipTerritories" value="red capitol"/>
                </attachment>
                </attachmentList>""");
    final MapProject project = MapProject.open(xml);
    final Attachment trigger =
        Attachment.builder()
            .name("triggerAttachmentHeld")
            .attachTo("Italians")
            .javaClass("games.strategy.triplea.attachments.TriggerAttachment")
            .type("player")
            .options(
                List.of(
                    Option.builder().name("conditions").value("conditionAttachmentHeld").build()))
            .build();
    project.edit(
        game -> {
          final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
          attachments.removeFirst();
          attachments.add(trigger);
          return AttachmentsXml.withAttachments(game, attachments);
        });

    project.save();

    final String saved = Files.readString(xml);
    assertThat(saved.indexOf("\"triggerAttachmentHeld\""))
        .as("the trigger comes after the condition it names, as the model has it")
        .isGreaterThan(saved.indexOf("\"conditionAttachmentHeld\""));
    assertThat(MapChecks.findError(xml)).as("the game still loads in TripleA").isEmpty();
  }

  @DisplayName("Steps inside a step that was not closed are saved once, as TripleA reads them")
  @Test
  void savesNestedStepsOnce() throws Exception {
    // given a map maker's slip: a step left open, closed after the steps that follow it
    final Path xml =
        givenMinimap(
            "<step name=\"russianPlace\" delegate=\"place\" player=\"Russians\"/>",
            "<step name=\"russianPlace\" delegate=\"place\" player=\"Russians\">");
    Files.writeString(
        xml, Files.readString(xml).replace("</sequence>", "</step></sequence>"));
    final MapProject project = MapProject.open(xml);
    final List<String> steps = stepNames(project.game());
    project.edit(game -> MapXml.withOwner(game, NORTH, "Italians"));

    project.save();

    assertThat(steps)
        .as("TripleA skips the first step inside and reads the others one after the other")
        .contains("russianPlace", "italianPurchase", "endRoundStep")
        .doesNotContain("russianEndTurn");
    assertThat(counts(xml).get("step"))
        .as("each step once in the saved file, with the skipped one")
        .isEqualTo(steps.size() + 1);
    assertThat(stepNames(MapProject.open(xml).game()))
        .as("the saved file reads as the file before")
        .isEqualTo(steps);
    assertThat(MapChecks.findError(xml)).as("the game still loads in TripleA").isEmpty();
  }

  private static List<String> stepNames(final Game game) {
    return RulesXml.steps(game).stream().map(Step::getName).toList();
  }

  @DisplayName("Every downloaded map's game, opened and saved, loads and keeps what it had")
  @Test
  void roundTripsDownloadedMaps() throws Exception {
    assumeTrue(Files.isDirectory(DOWNLOADED_MAPS), "no downloaded maps on this machine");
    final List<Path> games = givenDownloadedGames();
    final List<String> failed = new ArrayList<>();

    for (final Path original : games) {
      final Path copy = Files.createTempDirectory(folder, "map").resolve("game.xml");
      Files.copy(original, copy);
      try {
        MapProject.open(copy).save();
      } catch (final Exception e) {
        failed.add(original + ": " + e);
        continue;
      }
      failed.addAll(lossesOf(original, copy).stream().map(loss -> original + ": " + loss).toList());
    }

    assertThat(failed).as("maps that lost something or no longer load").isEmpty();
  }

  private static List<Path> givenDownloadedGames() throws IOException {
    try (Stream<Path> files = Files.walk(DOWNLOADED_MAPS)) {
      return files
          .filter(file -> file.getFileName().toString().endsWith(".xml"))
          .filter(file -> file.getParent().getFileName().toString().equals("games"))
          .filter(file -> MapChecks.findError(file).isEmpty())
          .toList();
    }
  }

  private static List<String> lossesOf(final Path original, final Path saved) throws Exception {
    final List<String> losses = new ArrayList<>();
    MapChecks.findError(saved).ifPresent(error -> losses.add("does not load: " + error));
    final Map<String, Integer> before = counts(original);
    final Map<String, Integer> after = counts(saved);
    before.forEach(
        (what, count) -> {
          if (after.getOrDefault(what, 0) < count) {
            losses.add(what + " " + count + " -> " + after.getOrDefault(what, 0));
          }
        });
    after.forEach(
        (what, count) -> {
          if (what.indexOf('@') < 0 && count > before.getOrDefault(what, 0)) {
            losses.add("more " + what + " " + before.getOrDefault(what, 0) + " -> " + count);
          }
        });
    if (Files.readString(original).contains("<!DOCTYPE")
        && !Files.readString(saved).contains("<!DOCTYPE")) {
      losses.add("DOCTYPE");
    }
    return losses;
  }

  /** A name as TripleA reads it: written in any case, old maps' "attatch" for "attach". */
  private static String asRead(final String name) {
    return name.toLowerCase(Locale.ROOT).replace("attatch", "attach");
  }

  /** How many comments, elements of each name and attributes of each element name it has. */
  private static Map<String, Integer> counts(final Path xml) throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    final Document document = factory.newDocumentBuilder().parse(xml.toFile());
    final Map<String, Integer> counts = new HashMap<>();
    count(document, counts);
    return counts;
  }

  private static void count(final Node node, final Map<String, Integer> counts) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() == Node.COMMENT_NODE) {
        counts.merge("#comment", 1, Integer::sum);
      } else if (child.getNodeType() == Node.ELEMENT_NODE) {
        final String name = asRead(child.getNodeName());
        counts.merge(name, 1, Integer::sum);
        final NamedNodeMap attributes = child.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          counts.merge(name + "@" + asRead(attributes.item(i).getNodeName()), 1, Integer::sum);
        }
        count(child, counts);
      }
    }
  }
}
