package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import eddies.mapeditor.MapChecks.Problem;

/**
 * What TripleA's parser takes but fails on while playing ("gamePlayer is null"): a unit, a step
 * or a capital of a nation the game does not have, and a nation's step without its nation. Check
 * tells them, and no change in the editor makes one.
 */
final class NoNationTest {
  private static final String CAPITAL = "red capitol";

  @TempDir Path folder;

  private static String givenMinimapText() throws Exception {
    try (InputStream original =
        NoNationTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      return new String(original.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private Path givenGame(final String name, final String text) throws Exception {
    final Path xml = folder.resolve(name);
    Files.writeString(xml, text);
    return xml;
  }

  private MapProject givenMinimap() throws Exception {
    return MapProject.open(givenGame("minimap.xml", givenMinimapText()));
  }

  private static String replacedOnce(final String text, final String old, final String with) {
    assertThat(text).as("the minimap has %s", old).contains(old);
    return text.replaceFirst(java.util.regex.Pattern.quote(old), with);
  }

  private static List<String> problems(final Game game) {
    return MapChecks.quick(game, MapFiles.none()).stream().map(Problem::text).toList();
  }

  /** The problems about nations and steps, of all a game has. */
  private static List<String> nationProblems(final Game game) {
    return problems(game).stream()
        .filter(p -> p.contains("no nation") || p.contains("needs a nation"))
        .toList();
  }

  private void thenStartsInTriplea(final MapProject project, final String after) throws Exception {
    assertThat(nationProblems(project.game())).as("after %s: no problem of a nation", after).isEmpty();
    final Path xml = folder.resolve("checked" + after.hashCode() + ".xml");
    GameXmlKeeper.export(project.game(), project.getXmlFile().orElseThrow(), xml);
    assertThat(MapChecks.findError(xml)).as("after %s: TripleA loads and starts it", after).isEmpty();
  }

  @DisplayName("A unit of a nation the game does not have is told, quickly and by Check")
  @Test
  void tellsUnitOfNoNation() throws Exception {
    // given
    final Path xml =
        givenGame(
            "unit.xml",
            replacedOnce(
                givenMinimapText(),
                "territory=\"red capitol\" quantity=\"7\" owner=\"Russians\"",
                "territory=\"red capitol\" quantity=\"7\" owner=\"Nobody\""));

    // when
    final Optional<String> check = MapChecks.findError(xml);
    final List<String> quick = problems(MapProject.open(xml).game());
    final List<String> all =
        MapChecks.all(MapProject.open(xml).game(), MapFiles.none()).stream()
            .map(Problem::text)
            .toList();

    // then
    assertThat(all).as("Check tells it too").anyMatch(p -> p.startsWith("Units in red capitol"));
    assertThat(check.filter(error -> !error.contains("belongs to a nation the game does not have")))
        .as("the parser takes it: today's TripleA makes it a neutral unit, older ones fail where"
            + " they draw it, which the start check then says")
        .isEmpty();
    assertThat(quick)
        .as("and the editor says so after every change")
        .contains(
            "Units in red capitol belong to Nobody, no nation of the game: give them an owner in"
                + " Territories");
  }

  @DisplayName("A nation's step without its nation is told; the round's own steps need none")
  @Test
  void tellsStepWithoutNation() throws Exception {
    // given
    final Path xml =
        givenGame(
            "step.xml",
            replacedOnce(
                givenMinimapText(),
                "<step name=\"russianPurchase\" delegate=\"purchase\" player=\"Russians\"/>",
                "<step name=\"russianPurchase\" delegate=\"purchase\"/>"));

    // when
    final Optional<String> check = MapChecks.findError(xml);
    final List<Problem> quick = MapChecks.quick(MapProject.open(xml).game(), MapFiles.none());

    // then
    assertThat(check).as("the start check").contains("Step russianPurchase needs a nation");
    assertThat(quick.stream().filter(p -> p.text().contains("needs a nation")).toList())
        .as("one problem, for that step, leading to Turn order; not for gameInitDelegate or"
            + " endRoundStep")
        .hasSize(1)
        .allMatch(p -> p.text().startsWith("Step russianPurchase needs a nation"))
        .allMatch(p -> p.tool().equals(Optional.of("Turn order")));
    assertThat(MapChecks.findError(givenGame("plain.xml", givenMinimapText())))
        .as("the minimap itself starts")
        .isEmpty();
  }

  @DisplayName("A capital of something that is no nation is told")
  @Test
  void tellsCapitalOfNoNation() throws Exception {
    // given
    final Path xml =
        givenGame(
            "capital.xml",
            replacedOnce(
                givenMinimapText(),
                "<option name=\"capital\" value=\"Russians\"/>",
                "<option name=\"capital\" value=\"Nobody\"/>"));

    // when
    final Optional<String> check = MapChecks.findError(xml);
    final List<String> quick = problems(MapProject.open(xml).game());

    // then
    assertThat(check.orElse(""))
        .as("today's TripleA refuses it when it loads the game; an older one is told by the"
            + " start check")
        .contains("Nobody");
    assertThat(quick)
        .as("the quick problem, on the territory")
        .contains(
            "red capitol is the capital of Nobody, which is no nation: untick it in Territories");
  }

  @DisplayName("A capital that foreach fills in, eg capital=\"@Player@\", is not told")
  @Test
  void skipsCapitalsMadeByForeach() throws Exception {
    // given a territory attachment made for each nation
    final Path xml =
        givenGame(
            "foreach.xml",
            givenMinimapText()
                .replace(
                    "<attachmentList>",
                    "<attachmentList><attachment foreach=\"$Player$\" name=\"territoryAttachment\""
                        + " attachTo=\"red capitol\" javaClass=\"TerritoryAttachment\""
                        + " type=\"territory\"><option name=\"capital\" value=\"@Player@\"/>"
                        + "</attachment>")
                .replace(
                    "</game>",
                    "<variableList><variable name=\"Player\"><element name=\"Russians\"/>"
                        + "</variable></variableList></game>"));

    // when
    final List<String> quick = nationProblems(MapProject.open(xml).game());

    // then
    assertThat(quick).as("a variable is no missing nation").isEmpty();
  }

  @DisplayName("Removing, renaming and repainting leave no unit, step or capital of no nation")
  @Test
  void changesLeaveNoDanglingNation() throws Exception {
    // given: the Italians hold units, have a capital and units on the map
    final MapProject project = givenMinimap();
    project.edit(g -> MapXml.withHeldUnitsAdded(g, "Italians", "infantry", 2));
    project.edit(g -> TerritoryXml.withValues(g, "blue north 1", TerritoryXml.ORIGINAL_OWNER, List.of("Italians")));
    thenStartsInTriplea(project, "the setup");

    // when, then: each change on its own, undone again
    project.rename("blue capitol", "Rome");
    thenStartsInTriplea(project, "renaming a territory with units and a capital");
    project.renameNation("Italians", "Romans");
    thenStartsInTriplea(project, "renaming a nation");
    project.undo();
    project.undo();

    project.edit(g -> MapXml.withOwner(g, CAPITAL, null));
    thenStartsInTriplea(project, "taking the owner of a capital with units away");
    project.edit(g -> MapXml.withOwner(g, CAPITAL, "Italians"));
    thenStartsInTriplea(project, "giving a capital with units to another nation");
    project.edit(g -> MapXml.withUnitsCopied(g, CAPITAL, "blue north 1"));
    project.edit(g -> MapXml.withUnitsCopied(g, "blue capitol", "sea zone 1"));
    thenStartsInTriplea(project, "copying units to another nation's land and to a sea zone");
    project.undo();
    project.undo();
    project.undo();
    project.undo();

    assertThat(project.mergeTerritories("red north 1", CAPITAL)).as("merged").isEmpty();
    thenStartsInTriplea(project, "merging a capital into its neighbour");
    project.undo();
    assertThat(project.removeTerritory("blue capitol")).as("removed").isEmpty();
    thenStartsInTriplea(project, "removing a capital with units");
    project.undo();

    final List<String> stillNamed = project.removeNation("Italians");
    assertThat(stillNamed)
        .as("a nation that is a territory's original owner stays, told where it is named")
        .isNotEmpty();
    thenStartsInTriplea(project, "a refused removal");
    project.edit(g -> TerritoryXml.withValues(g, "blue north 1", TerritoryXml.ORIGINAL_OWNER, List.of()));
    assertThat(project.removeNation("Italians")).as("then it is removed").isEmpty();
    assertThat(MapXml.heldUnits(project.game())).as("with its held units").isEmpty();
    assertThat(MapXml.placements(project.game()))
        .as("and its units on the map")
        .noneMatch(p -> "Italians".equals(p.getOwner()));
    thenStartsInTriplea(project, "removing a nation with held units, units, land and a capital");

    project.addNation("Greeks", Optional.empty());
    thenStartsInTriplea(project, "adding a nation, with its turn");
  }

  @DisplayName("Turn order gives every nation's step a nation: none is offered only to the round's")
  @Test
  void turnOrderOffersNoNationOnlyToRoundSteps() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final Game game = project.game();
    final List<Step> steps = RulesXml.steps(game);
    final int purchase =
        IntStream.range(0, steps.size())
            .filter(i -> "russianPurchase".equals(steps.get(i).getName()))
            .findFirst()
            .orElseThrow();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TurnOrderEditor editor = new TurnOrderEditor(project);
          for (final int index : new int[] {purchase, steps.size() - 1}) {
            editor.choose(index);
            seen.add(
                nationBox(editor.page()).map(NoNationTest::items).orElse(List.of()));
          }
        });

    // then
    assertThat(GameRulesXml.needsNation(game, steps.get(purchase))).as("a purchase").isTrue();
    assertThat(GameRulesXml.needsNation(game, steps.getLast())).as("the round's end").isFalse();
    assertThat(GameRulesXml.needsNation(game, steps.getFirst())).as("the game's start").isFalse();
    assertThat(seen)
        .as("the nations only for a purchase; (none) too for the round's end")
        .containsExactly(
            List.of("Russians", "Italians"), List.of("(none)", "Russians", "Italians"));

    // when a kind of the round becomes a nation's, and a step is added after the round's end
    final GameRulesXml.DelegateKind buying =
        GameRulesXml.DELEGATE_KINDS.stream()
            .filter(k -> k.name().equals("purchase"))
            .findFirst()
            .orElseThrow();
    project.edit(g -> GameRulesXml.withStepKind(g, steps.size() - 1, buying));
    project.edit(g -> GameRulesXml.withDelegateStep(g, steps.size(), buying, "Italians"));

    // then
    assertThat(nationProblems(project.game())).as("both have a nation").isEmpty();
    assertThat(RulesXml.steps(project.game()).get(steps.size() - 1).getPlayer())
        .as("the changed step took the nation of the steps before it")
        .isEqualTo("Italians");
  }

  @SuppressWarnings("unchecked")
  private static Optional<JComboBox<String>> nationBox(final Container in) {
    return all(in)
        .filter(JComboBox.class::isInstance)
        .map(c -> (JComboBox<String>) c)
        // not the list's filter, which starts with "All nations"
        .filter(box -> items(box).contains("Russians") && !items(box).contains("All nations"))
        .findFirst();
  }

  private static List<String> items(final JComboBox<String> box) {
    return IntStream.range(0, box.getItemCount()).mapToObj(i -> String.valueOf(box.getItemAt(i))).toList();
  }

  private static Stream<Component> all(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }
}
