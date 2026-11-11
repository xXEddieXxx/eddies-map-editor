package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.Constants;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.ActionsXml.Action;
import eddies.mapeditor.MapTexts.Kind;

final class ActionsXmlTest {
  private static final String TRUCE = "Truce";

  @TempDir Path folder;

  /** The minimap with a neutral relationship type, as it has none. */
  private static Game givenMinimapWithTruce() {
    return RulesXml.withRelationshipType(
        MapXmlTest.givenMinimap(), TRUCE, Constants.RELATIONSHIP_ARCHETYPE_NEUTRAL);
  }

  /** The game with a new political action of the Russians that makes a truce with Italy. */
  private static Game givenPoliticalAction(final Game game, final String name) {
    return ActionsXml.withAction(
        game,
        ActionsXml.POLITICAL,
        name,
        "Russians",
        "Truce_With_Italy",
        List.of("Russians:Italians:" + TRUCE));
  }

  static Game givenGlobal() {
    try (InputStream xml =
        ActionsXmlTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      return new XmlMapper(xml).mapXmlToObject(Game.class);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private MapProject givenMinimapProject() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        ActionsXmlTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    final MapProject project = MapProject.open(xml);
    project.edit(g -> RulesXml.withRelationshipType(g, TRUCE, "neutral"));
    return project;
  }

  @DisplayName("A new political action with cost, chance and acceptance loads on the minimap")
  @Test
  void newPoliticalActionLoads() {
    final Game minimap = givenMinimapWithTruce();
    final String name = ActionsXml.newName(minimap, ActionsXml.POLITICAL, "Truce with Italy");
    final Action action = new Action(name, "Russians");
    final Game added = givenPoliticalAction(minimap, name);
    final Game changed =
        ActionsXml.withNames(
            ActionsXml.withValues(
                ActionsXml.withValues(
                    ActionsXml.withValues(
                        added, action, ActionsXml.COST_RESOURCES, List.of("5:PUs")),
                    action,
                    ActionsXml.CHANCE,
                    List.of("1:2")),
                action,
                ActionsXml.ATTEMPTS_PER_TURN,
                List.of("2")),
            action,
            ActionsXml.ACTION_ACCEPT,
            List.of("Italians"));

    final GameData data = RulesXmlTest.givenLoaded(changed, folder.resolve("minimap"));

    assertThat(name)
        .as("named with the prefix the engine finds political actions by")
        .isEqualTo("politicalActionAttachment_Truce_with_Italy");
    assertThat(data.getPlayerList().getPlayerId("Russians").getAttachment(name))
        .as("the engine has the action")
        .isNotNull();
    assertThat(ActionsXml.actions(changed)).as("listed as an action").containsExactly(action);
    assertThat(ActionsXml.names(changed, action, ActionsXml.ACTION_ACCEPT))
        .as("who must accept it")
        .containsExactly("Italians");
    assertThat(ActionsXml.actions(minimap))
        .as("the game it started from stays, for undo")
        .isEmpty();
  }

  @DisplayName("New political and user actions naming a condition and a trigger load on Global 40")
  @Test
  void newActionsLoadOnGlobal() {
    final Game global = givenGlobal();
    final String political = ActionsXml.newName(global, ActionsXml.POLITICAL, "Pact");
    final Game withPolitical =
        ActionsXml.withAction(
            global,
            ActionsXml.POLITICAL,
            political,
            "Germans",
            "Pact",
            List.of("Germans:Russians:Neutrality"));
    final Action pact = new Action(political, "Germans");
    final String condition = ActionsXml.conditionsBefore(withPolitical, pact).getFirst();
    final Game withCondition =
        ActionsXml.withNames(withPolitical, pact, ActionsXml.CONDITIONS, List.of(condition));
    final String user = ActionsXml.newName(withCondition, ActionsXml.USER, "Build fort");
    final Action fort = new Action(user, "Germans");
    final Game withUser =
        ActionsXml.withAction(
            withCondition, ActionsXml.USER, user, "Germans", "Build_fort", List.of());
    final String trigger = ActionsXml.triggersBefore(withUser, fort).getFirst();
    final Game changed =
        ActionsXml.withValues(
            withUser,
            fort,
            ActionsXml.ACTIVATE_TRIGGER,
            List.of(trigger + ":1:false:false:false:false"));

    final GameData data = RulesXmlTest.givenLoaded(changed, folder.resolve("global"));

    assertThat(data.getPlayerList().getPlayerId("Germans").getAttachment(political))
        .as("the political action with its condition")
        .isNotNull();
    assertThat(data.getPlayerList().getPlayerId("Germans").getAttachment(user))
        .as("the user action firing a trigger")
        .isNotNull();
    assertThat(user).as("a user action's prefix").startsWith("userActionAttachment");
  }

  @DisplayName("A new action and its button text are one step: one undo takes both back")
  @Test
  void oneUndoRemovesActionAndTexts() throws Exception {
    final MapProject project = givenMinimapProject();
    final String name = ActionsXml.newName(project.game(), ActionsXml.POLITICAL, "Truce");
    final String button = "Truce_With_Italy.BUTTON";

    project.edit(
        g -> givenPoliticalAction(g, name),
        texts -> texts.with(Kind.POLITICS, button, "Make a truce"));
    final String explained = MapTexts.explain(Kind.POLITICS, button, project.game());
    project.undo();

    assertThat(explained).as("the key is the action's button").contains(name);
    assertThat(ActionsXml.actions(project.game())).as("the action is gone").isEmpty();
    assertThat(project.texts().value(Kind.POLITICS, button)).as("and its text").isEmpty();
    assertThat(RulesXml.relationshipTypes(project.game()))
        .as("the step before stays")
        .contains(TRUCE);
  }
}
