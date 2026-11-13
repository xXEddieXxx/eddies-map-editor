package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MapTexts.Kind;
import eddies.mapeditor.ObjectiveXml.Objective;
import eddies.mapeditor.ObjectivesEditor.Listing;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;

final class ObjectivesEditorTest {
  private static final String TRADE = "objectiveAttachment_Germans_1_Trade_with_Russia";
  private static final String TRADE_KEY =
      "World_War_II_Global_1940_Original.Germans;" + TRADE;

  /** World War II Global 1940, with its nine objectives, without its map's files. */
  private static MapProject givenG40() {
    return new MapProject(Path.of("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
  }

  private static <T> Stream<T> givenAll(final Component component, final Class<T> type) {
    final Stream<T> self =
        type.isInstance(component) ? Stream.of(type.cast(component)) : Stream.of();
    if (component instanceof Container container) {
      return Stream.concat(
          self, Stream.of(container.getComponents()).flatMap(child -> givenAll(child, type)));
    }
    return self;
  }

  private static Attachment givenTrade(final Game game) {
    return ObjectiveXml.objectives(game).stream()
        .filter(o -> o.name().equals(TRADE))
        .map(Objective::attachment)
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("G40's objectives are listed nation by nation, by their text or in plain words")
  void listsByNation() {
    // given
    final Game game = GameRulesXmlTest.givenG40();
    final MapTexts texts =
        new MapTexts(Map.of(Kind.OBJECTIVES, TRADE_KEY + "=<b>5 PUs</b> for trade with Russia\n"));

    // when
    final Listing listing = ObjectivesEditor.listing(game, texts);

    // then
    final List<String> keys = List.copyOf(listing.objectives().keySet());
    assertThat(keys).as("every objective of the game").hasSize(9);
    assertThat(listing.objectives().values().stream().map(Objective::nation).distinct())
        .as("nation by nation in the game's order of nations, not the file's")
        .containsExactly("Germans", "Japanese", "ANZAC", "Italians", "Americans", "Chinese")
        .isEqualTo(MapXml.nations(game).stream().filter(listing.nations()::contains).toList());
    final String trade = TRADE + " · Germans";
    assertThat(ObjectivesEditor.title(listing, trade))
        .as("the text the players read, without its HTML")
        .isEqualTo("5 PUs for trade with Russia");
    assertThat(ObjectivesEditor.line(listing, trade))
        .as("whose it is and what it pays")
        .isEqualTo("Germans · +5 PUs");
    assertThat(
            ObjectivesEditor.title(
                listing, "objectiveAttachment_Italians_1_Control_Greece_And_Egypt_And_"
                    + "Southern_France · Italians"))
        .as("without a text, what it checks in plain words")
        .isEqualTo("Italians own with allies 3 of Greece, Egypt, Southern France");
    assertThat(listing.nations())
        .as("the filter offers the nations that have objectives")
        .hasSize(6)
        .doesNotContain("Russians");
  }

  @Test
  @DisplayName("PUs changed in the inspector are one step that changes only that objective")
  void changesPusAsOneStep() {
    // given
    final MapProject project = givenG40();
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    editor.show(TRADE);
    final JSpinner pus =
        givenAll(editor.page(), JSpinner.class)
            .filter(s -> Integer.valueOf(5).equals(s.getValue()))
            .findFirst()
            .orElseThrow();

    // when
    pus.setValue(8);

    // then
    assertThat(ObjectiveXml.value(givenTrade(project.game()))).as("the new PUs").isEqualTo(8);
    assertThat(givenAll(editor.page(), JLabel.class).map(JLabel::getText))
        .as("the line under the title follows, the internal name after a grey id")
        .contains("Germans · +8 PUs · id " + TRADE);
    project.undo();
    assertThat(ObjectiveXml.value(givenTrade(project.game()))).as("one undo step").isEqualTo(5);
    assertThat(project.canUndo()).as("and no other").isFalse();
  }

  @Test
  @DisplayName("A typed text is kept before a save, under the key and in a group the game reads")
  void keepsTypedText() {
    // given
    final MapProject project = givenG40();
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    editor.show(TRADE);
    final JTextArea text = givenAll(editor.page(), JTextArea.class)
        .filter(JTextArea::isEditable)
        .findFirst()
        .orElseThrow();

    // when
    text.setText("5 PUs while not at war\nwith Russia");
    editor.commit();

    // then
    assertThat(project.texts().value(Kind.OBJECTIVES, TRADE_KEY))
        .as("written with TripleA's key, on one line")
        .contains("5 PUs while not at war with Russia");
    assertThat(project.texts().text(Kind.OBJECTIVES))
        .as("and listed in a group, without which the game shows no text")
        .contains("TABLEGROUP.01;Germans=" + TRADE);
  }

  @Test
  @DisplayName("National Objectives off is told with a button that turns it on")
  void offersToTurnObjectivesOn() {
    // given
    final MapProject project =
        new MapProject(
            Path.of("g40.xml"),
            MapXml.withPropertyValue(
                GameRulesXmlTest.givenG40(), ObjectiveXml.GAME_OPTION, "false"),
            MapFiles.none());
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    final AbstractButton turnOn =
        givenAll(editor.page(), AbstractButton.class)
            .filter(b -> "Turn on".equals(b.getText()))
            .findFirst()
            .orElseThrow();
    final boolean toldWhileOff = turnOn.getParent().isVisible();

    // when
    turnOn.doClick();

    // then
    assertThat(toldWhileOff).as("told while no objective pays").isTrue();
    assertThat(ObjectiveXml.isOn(project.game())).as("turned on").isTrue();
    assertThat(turnOn.getParent().isVisible()).as("nothing to tell any more").isFalse();
  }

  @Test
  @DisplayName("The nation chosen in the inspector moves the objective and its text to it")
  void movesToAnotherNation() {
    // given
    final MapProject project = givenG40();
    project.setText(Kind.OBJECTIVES, TRADE_KEY, "Trade");
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    editor.show(TRADE);
    final JComboBox<?> nation =
        givenAll(editor.page(), JComboBox.class)
            .filter(box -> "Germans".equals(box.getSelectedItem()))
            .findFirst()
            .orElseThrow();

    // when
    nation.setSelectedItem("Italians");

    // then
    assertThat(givenTrade(project.game()).getAttachTo()).as("now Italy's").isEqualTo("Italians");
    assertThat(project.texts().value(Kind.OBJECTIVES, TRADE_KEY.replace("Germans;", "Italians;")))
        .as("its text under the key of its new nation")
        .contains("Trade");
    assertThat(project.texts().value(Kind.OBJECTIVES, TRADE_KEY))
        .as("and no longer under the old one")
        .isEmpty();
  }

  @Test
  @DisplayName("PUs changed in the fields stay when the nation is changed after them")
  void nationChangeKeepsWhatWasChangedBefore() {
    // given PUs changed, which leaves the fields as they are
    final MapProject project = givenG40();
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    editor.show(TRADE);
    givenAll(editor.page(), JSpinner.class)
        .filter(s -> Integer.valueOf(5).equals(s.getValue()))
        .findFirst()
        .orElseThrow()
        .setValue(8);
    final JComboBox<?> nation =
        givenAll(editor.page(), JComboBox.class)
            .filter(box -> "Germans".equals(box.getSelectedItem()))
            .findFirst()
            .orElseThrow();

    // when
    nation.setSelectedItem("Italians");

    // then
    final Attachment trade = givenTrade(project.game());
    assertThat(trade.getAttachTo()).as("moved").isEqualTo("Italians");
    assertThat(ObjectiveXml.value(trade))
        .as("the objective as it was after the PUs changed, not as the fields were built")
        .isEqualTo(8);
  }

  @Test
  @DisplayName("An added check is held back until it reads, then written as a step")
  void holdsCheckUntilItReads() {
    // given
    final MapProject project = givenG40();
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    editor.show(TRADE);
    final int before = AttachmentsXml.options(givenTrade(project.game())).size();

    // when a check is added, which has no territories yet
    givenAll(editor.page(), AbstractButton.class)
        .filter(b -> "+ Add check".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();
    final List<String> heldLines =
        givenAll(editor.page(), JLabel.class)
            .filter(JLabel::isVisible)
            .map(JLabel::getText)
            .filter(text -> text != null && text.contains("Not taken yet"))
            .toList();
    final boolean writtenWhileHeld = project.canUndo();
    final int held = editor.heldChecks().size();
    editor.commit();
    final boolean writtenByCommit = project.canUndo();
    // and then made one that needs nothing more: "Switched on"
    givenAll(editor.page(), JComboBox.class)
        .filter(box -> box.getSelectedItem() instanceof TriggerXml.Spec)
        .reduce((first, last) -> last)
        .orElseThrow()
        .setSelectedItem(TriggerXml.spec("switch").orElseThrow());

    // then
    assertThat(heldLines).as("told in red while the game could not read it").hasSize(1);
    assertThat(held).as("one check held back").isEqualTo(1);
    assertThat(writtenWhileHeld).as("nothing unreadable is written").isFalse();
    assertThat(writtenByCommit).as("nor by a save, which keeps it in its fields").isFalse();
    assertThat(AttachmentsXml.options(givenTrade(project.game())))
        .as("written once it reads")
        .hasSize(before + 1);
    assertThat(TriggerXml.value(givenTrade(project.game()), "switch")).contains("false");
    assertThat(editor.heldChecks()).as("nothing held any more").isEmpty();
    assertThat(
            givenAll(editor.page(), JLabel.class)
                .filter(JLabel::isVisible)
                .map(JLabel::getText)
                .filter(text -> text != null && text.contains("Not taken yet")))
        .as("the red line is gone")
        .isEmpty();
  }

  @Test
  @DisplayName("A check the map has that does not read keeps nothing else from being written")
  void mapsOwnProblemDoesNotBlock() {
    // given an objective at war with a nation the game does not have
    final Game g40 = GameRulesXmlTest.givenG40();
    final Objective trade =
        ObjectiveXml.objectives(g40).stream()
            .filter(o -> o.name().equals(TRADE))
            .findFirst()
            .orElseThrow();
    final MapProject project =
        new MapProject(
            Path.of("g40.xml"),
            AttachmentsXml.withAttachment(
                g40,
                trade.index(),
                ObjectiveXml.with(trade.attachment(), "atWarPlayers", "0:Martians")),
            MapFiles.none());
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    editor.show(TRADE);

    // when
    givenAll(editor.page(), AbstractButton.class)
        .filter(b -> "Pays while its checks do not hold".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();

    // then
    assertThat(TriggerXml.value(givenTrade(project.game()), "invert"))
        .as("written, though the map's own check names no nation of the game")
        .contains("true");
    assertThat(TriggerXml.value(givenTrade(project.game()), "atWarPlayers"))
        .as("the map's check as it was")
        .contains("0:Martians");
  }

  @Test
  @DisplayName("A new objective, its text and the game option are one step to undo")
  void addsAsOneStep() {
    // given a game that plays without national objectives
    final MapProject project =
        new MapProject(
            Path.of("g40.xml"),
            MapXml.withPropertyValue(
                GameRulesXmlTest.givenG40(), ObjectiveXml.GAME_OPTION, "false"),
            MapFiles.none());
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    final String key =
        "World_War_II_Global_1940_Original.Russians;objectiveAttachment_Russians_1";

    // when
    editor.addNew("Russians", 4, "4 PUs for Moscow");

    // then
    final Objective added = ObjectiveXml.objectives(project.game()).getLast();
    assertThat(List.of(added.name(), added.nation()))
        .as("named as the game pays it")
        .containsExactly("objectiveAttachment_Russians_1", "Russians");
    assertThat(ObjectiveXml.value(added.attachment())).as("its PUs").isEqualTo(4);
    assertThat(project.texts().value(Kind.OBJECTIVES, key))
        .as("its text under the game's key")
        .contains("4 PUs for Moscow");
    assertThat(ObjectiveXml.isOn(project.game())).as("the game option turned on").isTrue();
    assertThat(givenAll(editor.page(), JLabel.class).map(JLabel::getText))
        .as("the new one is chosen")
        .contains("Russians · +4 PUs · id objectiveAttachment_Russians_1");
    project.undo();
    assertThat(ObjectiveXml.objectives(project.game())).as("one undo: no objective").hasSize(9);
    assertThat(project.texts().value(Kind.OBJECTIVES, key)).as("no text").isEmpty();
    assertThat(ObjectiveXml.isOn(project.game())).as("and the option off again").isFalse();
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @Test
  @DisplayName("Duplicate copies the chosen objective with its text, as one step")
  void duplicatesWithText() {
    // given
    final MapProject project = givenG40();
    project.setText(Kind.OBJECTIVES, TRADE_KEY, "Trade");
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    editor.show(TRADE);

    // when
    editor.duplicate();

    // then
    final Objective copy = ObjectiveXml.objectives(project.game()).getLast();
    assertThat(copy.name()).as("the copy's name").isEqualTo(TRADE + "_copy");
    assertThat(project.texts().value(Kind.OBJECTIVES, TRADE_KEY + "_copy"))
        .as("with the text of the original")
        .contains("Trade");
    assertThat(project.texts().text(Kind.OBJECTIVES))
        .as("and in the nation's group, so the game shows it")
        .contains("TABLEGROUP.01;Germans=" + TRADE + "_copy");
    project.undo();
    assertThat(ObjectiveXml.objectives(project.game())).as("one undo: no copy").hasSize(9);
    assertThat(project.texts().value(Kind.OBJECTIVES, TRADE_KEY + "_copy"))
        .as("nor its text")
        .isEmpty();
  }

  @Test
  @DisplayName("Remove takes the text and the group entry too; an objective still named stays")
  void removesWithTextUnlessNamed() {
    // given
    final MapProject project = givenG40();
    final ObjectivesEditor editor = new ObjectivesEditor(project);
    editor.show(TRADE);
    final JTextArea text =
        givenAll(editor.page(), JTextArea.class)
            .filter(JTextArea::isEditable)
            .findFirst()
            .orElseThrow();
    text.setText("Trade");
    editor.commit();

    // when
    final boolean removed = editor.removeChosen();
    editor.show("objectiveAttachment_Chinese_1_Burma_Road");
    final boolean removedNamed = editor.removeChosen();

    // then
    assertThat(removed).as("nothing names the trade objective").isTrue();
    assertThat(ObjectiveXml.objectives(project.game()))
        .as("gone from the game")
        .hasSize(8)
        .noneMatch(o -> o.name().equals(TRADE));
    assertThat(project.texts().text(Kind.OBJECTIVES))
        .as("its text and its group, which listed nothing else, are gone")
        .doesNotContain(TRADE);
    assertThat(removedNamed).as("a trigger names the Burma Road objective").isFalse();
    assertThat(ObjectiveXml.objectives(project.game())).as("which stays").hasSize(8);
  }
}
