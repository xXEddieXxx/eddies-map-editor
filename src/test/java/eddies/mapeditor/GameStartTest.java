package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PlayerList.Player;
import org.triplea.map.xml.writer.GameXmlWriter;
import eddies.mapeditor.MapChecks.Problem;

/** The start check: a game is started in memory as TripleA does and plays its first round. */
final class GameStartTest {
  @TempDir Path folder;

  /** TripleA's steps read its settings, as the editor sets them up; in tests kept in memory. */
  @BeforeAll
  static void givenTripleASettings() {
    ClientSetting.initialize();
  }

  /** A map made as the wizard does, with the nations it proposes. */
  private MapProject givenNewMap() throws Exception {
    return MapProject.newMap(
            "Island Wars",
            MapPictureTest.givenPicture(),
            List.of("Germans", "Russians", "British"),
            folder.resolve("maps"))
        .project();
  }

  /** The minimap whose Italians lost their capital, as a file TripleA loads. */
  private Path givenGameWithoutItaliansCapital() throws Exception {
    final Game game = MapXml.withCapital(MapXmlTest.givenMinimap(), "blue capitol", null);
    final Path xml = folder.resolve("without-capital.xml");
    GameXmlWriter.exportXml(game, xml);
    return xml;
  }

  private static Optional<Boolean> optional(final Game game, final String nation) {
    return game.getPlayerList().getPlayers().stream()
        .filter(p -> nation.equals(p.getName()))
        .map(Player::getOptional)
        .findFirst();
  }

  @DisplayName("A round that does not end in time is a reason, with the step it hangs in")
  @Test
  void roundRunningOnIsToldNotPassed() throws Exception {
    // given: a step that waits for ever, eg for a player's answer
    final CountDownLatch interrupted = new CountDownLatch(1);

    // when
    final Optional<String> why =
        GameStart.within(
            50,
            at -> {
              at.set("step russianCombatMove");
              try {
                Thread.sleep(60_000);
              } catch (final InterruptedException e) {
                interrupted.countDown();
              }
              return Optional.empty();
            });

    // then
    assertThat(why)
        .as("nothing was proved: Check must not say No problems, nor Play start")
        .hasValueSatisfying(
            text -> assertThat(text).contains("did not finish step russianCombatMove"));
    assertThat(interrupted.await(5, TimeUnit.SECONDS))
        .as("the thread is told to stop")
        .isTrue();
  }

  @DisplayName("A new map with the wizard's nations plays its first round before it has capitals")
  @Test
  void newMapStartsWithoutCapitals() throws Exception {
    // given, when
    final MapProject project = givenNewMap();

    // then
    assertThat(MapChecks.playError(project.getXmlFile().orElseThrow()))
        .as("TripleA shows the map and runs every nation's turn")
        .isEmpty();
    assertThat(optional(project.game(), "Germans"))
        .as("without a capital TripleA needs the nation optional, or stops at its turn")
        .contains(true);
  }

  @DisplayName("A nation with steps and no capital is marked optional, others stay as they are")
  @Test
  void nationWithoutCapitalBecomesOptional() {
    // given
    final Game minimap = MapXmlTest.givenMinimap();
    final Game withoutCapital = MapXml.withCapital(minimap, "blue capitol", null);

    // when
    final Game whole = MapXml.withoutCapitalOptional(withoutCapital);

    // then
    assertThat(optional(whole, "Italians")).as("no capital: optional").contains(true);
    assertThat(optional(whole, "Russians")).as("has its capital: as the map says").contains(false);
    assertThat(MapXml.withoutCapitalOptional(minimap))
        .as("every nation has a capital: the same game, no step")
        .isSameAs(minimap);
    assertThat(MapXml.withoutCapitalOptional(whole)).as("done once").isSameAs(whole);
  }

  @DisplayName("TripleA stops at the turn of a nation without capital that is not optional")
  @Test
  void startCheckTellsWhereTheGameStops() throws Exception {
    // given
    final Path xml = givenGameWithoutItaliansCapital();

    // when
    final Optional<String> whyNot = MapChecks.startError(xml, null);

    // then
    assertThat(MapChecks.findError(xml)).as("TripleA's parser takes the game").isEmpty();
    assertThat(whyNot)
        .as("the step it stops at and TripleA's reason")
        .hasValueSatisfying(
            why -> assertThat(why).contains("TripleA stops at step").contains("Capital not found"));
  }

  @DisplayName("Check says that the game does not start, and the editor's own game starts")
  @Test
  void checkPlaysTheGame() throws Exception {
    // given
    final Game stops = MapXml.withCapital(MapXmlTest.givenMinimap(), "blue capitol", null);
    final Game mended = MapXml.withoutCapitalOptional(stops);

    // when
    final List<String> ofStopping =
        MapChecks.all(stops, MapFiles.none()).stream().map(Problem::text).toList();
    final List<String> ofMended =
        MapChecks.all(mended, MapFiles.none()).stream().map(Problem::text).toList();

    // then
    assertThat(ofStopping)
        .as("Check plays the first round and tells where it stops")
        .anyMatch(text -> text.startsWith("The game does not start: TripleA stops at step"));
    assertThat(ofMended).as("as the editor keeps a game: it starts").isEmpty();
  }

  @DisplayName("A big game plays its first round")
  @Test
  void bigGameStarts() throws Exception {
    // given
    final Path xml = folder.resolve("g40.xml");
    GameXmlWriter.exportXml(GameRulesXmlTest.givenG40(), xml);

    // when, then
    assertThat(MapChecks.startError(xml, null)).as("Global 1940 starts").isEmpty();
  }

  @DisplayName("Play's check tells a territory the saved files have no unit places for")
  @Test
  void playTellsWhatTheMapsFilesLack() throws Exception {
    // given: place.txt without the line of one territory
    final MapProject project = givenNewMap();
    final Path xml = project.getXmlFile().orElseThrow();
    final String territory = MapXml.territories(project.game()).getFirst();
    final Path places = project.getFiles().folder().orElseThrow().resolve("place.txt");
    Files.write(
        places,
        Files.readAllLines(places).stream()
            .filter(line -> !line.startsWith(territory + " "))
            .toList());

    // when
    final Optional<String> whyNot = MapChecks.playError(xml);

    // then
    assertThat(whyNot)
        .as("TripleA's game window would not open: said before TripleA is started")
        .hasValueSatisfying(
            why -> assertThat(why).contains("TripleA cannot show the map").contains(territory));
  }

  @DisplayName("Play makes the game TripleA's last chosen one, which TripleA loads beside it")
  @Test
  void playChoosesTheGameAsTripleAsLast() {
    // given
    final Path xml = folder.resolve("maps/island/games/island.xml");

    // when
    PlayWith.chooseAsLastGame(xml);

    // then
    assertThat(ClientSetting.defaultGameUri.getValue())
        .as("TripleA then loads this game twice instead of mixing it with the last one played")
        .contains(xml.toAbsolutePath().toUri().toString());
  }
}
