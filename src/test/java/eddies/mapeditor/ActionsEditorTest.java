package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.Game;

final class ActionsEditorTest {

  @DisplayName("A NONE text shows empty, other texts as they are")
  @Test
  void showsNoneEmpty() {
    final String none = ActionsEditor.NONE;

    final String shown = ActionsEditor.shown(none);
    final String text = ActionsEditor.shown("You declared war.");

    assertThat(shown).as("the game skips NONE: the field shows no message").isEmpty();
    assertThat(text).as("a real message is shown as it is").isEqualTo("You declared war.");
  }

  @DisplayName("An empty message is stored as NONE, an empty button as empty")
  @Test
  void storesEmptyMessagesAsNone() {
    final String message = "NOTIFICATION_SUCCESS";

    final String cleared = ActionsEditor.toStore(message, "");
    final String question = ActionsEditor.toStore("ACCEPT_QUESTION", "");
    final String button = ActionsEditor.toStore("BUTTON", "");
    final String typed = ActionsEditor.toStore(message, "Peace at last");

    assertThat(cleared).as("TripleA expects NONE for no message").isEqualTo(ActionsEditor.NONE);
    assertThat(question).as("the question is a message too").isEqualTo(ActionsEditor.NONE);
    assertThat(button).as("a button is no message: stays as typed").isEmpty();
    assertThat(typed).as("typed text is stored as typed").isEqualTo("Peace at last");
  }

  @DisplayName("A new relationship change defaults to another nation, never the nation itself")
  @Test
  void otherNationIsNotTheNation() {
    final List<String> nations = MapXml.nations(GameRulesXmlTest.givenG40());
    final String first = nations.getFirst();

    final String other = ActionsEditor.otherNation(nations, first).orElseThrow();

    assertThat(other).as("Germans with Germans is no relationship").isNotEqualTo(first);
    assertThat(nations).as("a nation of the game").contains(other);
    assertThat(ActionsEditor.otherNation(List.of(first), first))
        .as("a game of one nation has no other")
        .isEmpty();
  }

  @DisplayName("The type defaults to a war type when the button speaks of war, else the first")
  @Test
  void typeDefaultsToWarForWarButtons() {
    final Game g40 = GameRulesXmlTest.givenG40();

    final String war = ActionsEditor.defaultType(g40, "Declare WAR on Russia");
    final String peace = ActionsEditor.defaultType(g40, "Sign a truce");

    assertThat(RulesXml.archetype(g40, war)).as("war in the button: a war type").isEqualTo("war");
    assertThat(peace)
        .as("else the first type")
        .isEqualTo(RulesXml.relationshipTypes(g40).getFirst());
  }

  @DisplayName("The example on a new action's button follows the relationship chosen")
  @Test
  void buttonExampleFollowsRelationship() {
    final Game g40 = GameRulesXmlTest.givenG40();

    final String allied = ActionsEditor.buttonExample(g40, "Allied", "Russians");
    final String war = ActionsEditor.buttonExample(g40, "War", "Russians");
    final String neutral = ActionsEditor.buttonExample(g40, "Neutrality", "Russians");

    assertThat(allied).as("an allied type").isEqualTo("Ally with Russians");
    assertThat(war).as("a war type").isEqualTo("Declare war on Russians");
    assertThat(neutral).as("a neutral type").isEqualTo("Make peace with Russians");
  }
}
