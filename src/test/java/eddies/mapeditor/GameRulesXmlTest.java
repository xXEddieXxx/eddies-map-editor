package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameStep;
import games.strategy.triplea.attachments.RulesAttachment;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.map.data.elements.AttachmentList;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.map.data.elements.PropertyList.Property.Value;
import eddies.mapeditor.GameRulesXml.DelegateKind;
import eddies.mapeditor.GameRulesXml.Kind;

@ExtensionMethod(Builders.class)
final class GameRulesXmlTest {
  private static final String VCS = "Axis Honorable Victory VCs";
  private static final String NATIONS = "Nations";

  /** TripleA's Global 1940, a big game with repair rules, number bounds and several resources. */
  static Game givenG40() {
    try (InputStream xml =
        GameRulesXmlTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      return new XmlMapper(xml).mapXmlToObject(Game.class);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Game> givenGames() {
    return List.of(MapXmlTest.givenMinimap(), givenG40());
  }

  private static Property option(final Game game, final String name) {
    return GameRulesXml.options(game).stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("Options are added of a kind, changed, made fixed and removed, and still load")
  @Test
  void changesTypedOptions(@TempDir final Path folder) {
    for (final Game game : givenGames()) {
      final Game added = GameRulesXml.withNewOption(game, "Kamikaze Airplanes", Kind.YES_NO);
      final Game number = GameRulesXml.withNewOption(added, "Air Battle Rounds", Kind.NUMBER);
      final Game changed =
          GameRulesXml.withRange(
              GameRulesXml.withOptionValue(number, "Air Battle Rounds", "3"),
              "Air Battle Rounds",
              Optional.of(1),
              Optional.of(5));
      final Game fixed = GameRulesXml.withFixed(changed, "Kamikaze Airplanes");

      assertThat(GameRulesXml.kind(option(fixed, "Kamikaze Airplanes")))
          .as("a new yes or no option")
          .isEqualTo(Kind.YES_NO);
      assertThat(option(fixed, "Kamikaze Airplanes").getEditable())
          .as("players may no longer change it")
          .isFalse();
      assertThat(GameRulesXml.max(option(fixed, "Air Battle Rounds"))).as("its most").contains(5);
      assertThat(GameRulesXml.withNewOption(fixed, "Air Battle Rounds", Kind.TEXT))
          .as("a taken name adds nothing")
          .isSameAs(fixed);
      assertThat(GameRulesXml.options(GameRulesXml.withoutOption(fixed, "Air Battle Rounds")))
          .as("removed")
          .extracting(Property::getName)
          .doesNotContain("Air Battle Rounds");
      final GameData data = RulesXmlTest.givenLoaded(fixed, folder.resolve(game.hashCode() + ""));
      assertThat(data.getProperties().get("Air Battle Rounds", 0))
          .as("the engine reads the changed number")
          .isEqualTo(3);
      assertThat(data.getProperties().get("Kamikaze Airplanes", true))
          .as("and the new option")
          .isEqualTo(false);
    }
  }

  @DisplayName("A value in a value child is changed there; old number bounds become attributes")
  @Test
  void keepsTheFormOfAnOption() {
    final Game g40 = givenG40();
    final Game withChild = GameRulesXml.withNewOption(g40, "Map Credits", Kind.TEXT);
    final List<Property> properties = new ArrayList<>(MapXml.properties(withChild));
    properties.replaceAll(
        p ->
            "Map Credits".equals(p.getName())
                ? p.toBuilder().value(null).valueProperty(new Value("by Eddie")).build()
                : p);
    final Game given =
        withChild.toBuilder()
            .propertyList(withChild.getPropertyList().toBuilder().properties(properties).build())
            .build();

    final Game changed = GameRulesXml.withOptionValue(given, "Map Credits", "by the community");
    final Game bounded =
        GameRulesXml.withRange(g40, VCS, Optional.of(12), GameRulesXml.max(option(g40, VCS)));

    assertThat(option(changed, "Map Credits").getValueProperty().getData())
        .as("the value child, which TripleA reads first, has the new text")
        .isEqualTo("by the community");
    assertThat(option(changed, "Map Credits").getValue()).as("no attribute beside it").isNull();
    assertThat(GameRulesXml.min(option(g40, VCS))).as("read from the number child").contains(11);
    assertThat(option(bounded, VCS).getNumberProperty()).as("replaced").isNull();
    assertThat(List.of(option(bounded, VCS).getMin(), option(bounded, VCS).getMax()))
        .as("the new least, the most kept")
        .containsExactly(12, 19);
  }

  @DisplayName("The dice get another number of sides, which the engine rolls")
  @Test
  void changesDiceSides(@TempDir final Path folder) {
    for (final Game game : givenGames()) {
      assertThat(GameRulesXml.diceSides(game)).as("as the game has it, or 6").isEqualTo(6);
      final Game twelve = GameRulesXml.withDiceSides(game, 12);
      assertThat(
              RulesXmlTest.givenLoaded(twelve, folder.resolve(game.hashCode() + ""))
                  .getDiceSides())
          .as("the engine's dice")
          .isEqualTo(12);
    }
  }

  @DisplayName("A variable is added, changed and found where attachments use it")
  @Test
  void changesVariables(@TempDir final Path folder) {
    for (final Game game : givenGames()) {
      final Game added =
          GameRulesXml.withVariable(game, NATIONS, List.of("Italians"));
      final Game changed =
          GameRulesXml.withVariable(added, NATIONS, List.of("Italians", "Russians"));
      final Game used = givenForEach(changed);

      assertThat(GameRulesXml.elements(changed, NATIONS))
          .as("its names changed in place")
          .containsExactly("Italians", "Russians");
      assertThat(GameRulesXml.whereUsed(changed, NATIONS)).as("used nowhere yet").isEmpty();
      assertThat(GameRulesXml.whereUsed(used, NATIONS))
          .as("the attachment made for each")
          .containsExactly("conditionAttachment@Nations@ of @Nations@");
      assertThat(GameRulesXml.variableNames(GameRulesXml.withoutVariable(changed, NATIONS)))
          .as("removed")
          .doesNotContain(NATIONS);
      final GameData data = RulesXmlTest.givenLoaded(used, folder.resolve(game.hashCode() + ""));
      assertThat(
              data.getPlayerList()
                  .getPlayerId("Russians")
                  .getAttachment("conditionAttachmentRussians"))
          .as("the engine makes the attachment for each name")
          .isNotNull();
    }
  }

  /** The game with a condition for each of the nations in the variable. */
  private static Game givenForEach(final Game game) {
    final List<Attachment> attachments =
        new ArrayList<>(game.getAttachmentList().getAttachments());
    attachments.add(
        Attachment.builder()
            .name("conditionAttachment@" + NATIONS + "@")
            .attachTo("@" + NATIONS + "@")
            .javaClass(RulesAttachment.class.getName())
            .type("player")
            .foreach("$" + NATIONS + "$")
            .options(List.of(Option.builder().name("objectiveValue").value("5").build()))
            .build());
    return game.toBuilder()
        .attachmentList(AttachmentList.builder().attachments(attachments).build())
        .build();
  }

  private static DelegateKind kind(final String ending) {
    return GameRulesXml.DELEGATE_KINDS.stream()
        .filter(k -> ending.equals(k.ending()))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("Steps of TripleA's kinds use the game's delegates and end their names as it needs")
  @Test
  void addsStepsOfKinds(@TempDir final Path folder) {
    for (final Game game : givenGames()) {
      final Game added =
          GameRulesXml.withDelegateStep(
              GameRulesXml.withDelegateStep(game, 1, kind("CombatMove"), "Russians"),
              2,
              kind("Battle"),
              "Russians");
      final Game round = GameRulesXml.withDelegateStep(game, 1, kind("EndRoundStep"), "Russians");

      assertThat(RulesXml.delegates(added))
          .as("the game's move and battle delegates, none new")
          .isEqualTo(RulesXml.delegates(game));
      assertThat(RulesXml.steps(round).get(1).getPlayer())
          .as("a step of the whole round has no nation")
          .isNull();
      final GameData data = RulesXmlTest.givenLoaded(added, folder.resolve(game.hashCode() + ""));
      final GameStep move = data.getSequence().getStep(1);
      final GameStep battle = data.getSequence().getStep(2);
      assertThat(GameStep.isCombatMoveStepName(move.getName()))
          .as("TripleA knows the move as a combat move by its name: " + move.getName())
          .isTrue();
      assertThat(move.getDelegate()).as("the game's move").isSameAs(data.getMoveDelegate());
      assertThat(battle.getDelegate())
          .as("the battle delegate TripleA fights with")
          .isSameAs(data.getBattleDelegate());
    }
  }

  @DisplayName("A kind the game lacks brings its delegate under TripleA's name")
  @Test
  void addsDelegateTheGameLacks(@TempDir final Path folder) {
    final Game minimap = MapXmlTest.givenMinimap();

    final Game added = GameRulesXml.withDelegateStep(minimap, 1, kind("Politics"), "Russians");

    assertThat(RulesXml.delegates(added)).as("TripleA's name for it").contains("politics");
    assertThat(RulesXml.steps(added).get(1).getName())
        .as("a politics step by its name")
        .isEqualTo("russiansPolitics");
    assertThat(RulesXmlTest.givenLoaded(added, folder).getPoliticsDelegate())
        .as("which the engine finds")
        .isNotNull();
  }

  @DisplayName("Undoing a change of the dice leaves it to redo: the spinner set back is no step")
  @Test
  void undoesDiceSides(@TempDir final Path folder) {
    final MapProject project =
        new MapProject(folder.resolve("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
    RulesEditors.newGameOptions(project);

    project.edit(g -> GameRulesXml.withDiceSides(g, 12));
    project.undo();

    assertThat(project.canRedo()).as("the change can be made again").isTrue();
    project.redo();
    assertThat(GameRulesXml.diceSides(project.game())).as("redone").isEqualTo(12);
    assertThat(GameRulesXml.withDiceSides(project.game(), 12))
        .as("the same sides are no change")
        .isSameAs(project.game());
  }

  @DisplayName("Bounds TripleA would refuse are not set; the map's name is no option to remove")
  @Test
  void refusesBadBounds(@TempDir final Path folder) {
    final Game g40 = givenG40();

    assertThat(GameRulesXml.withRange(g40, VCS, Optional.of(15), Optional.of(12)))
        .as("the least above the most")
        .isSameAs(g40);
    assertThat(GameRulesXml.withRange(g40, VCS, Optional.of(15), Optional.of(19)))
        .as("its value 14 below the least")
        .isSameAs(g40);
    final Game bounded = GameRulesXml.withRange(g40, VCS, Optional.of(14), Optional.of(14));
    assertThat(option(bounded, VCS).getMax()).as("a value at both bounds is fine").isEqualTo(14);
    RulesXmlTest.givenLoaded(bounded, folder);
    assertThat(GameRulesXml.options(g40))
        .as("mapName, by which TripleA finds the pictures")
        .extracting(Property::getName)
        .doesNotContain("mapName");
  }
}
