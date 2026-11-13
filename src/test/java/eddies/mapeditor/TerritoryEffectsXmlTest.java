package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.TerritoryEffectAttachment;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;

final class TerritoryEffectsXmlTest {
  private static final String FOREST = "forest";
  private static final String NORTH = "red north 1";
  private final Game minimap = MapXmlTest.givenMinimap();

  /**
   * The minimap with a forest on red north 1: armour -1 in attack, infantry +1 in defence, no
   * blitz for armour, no bombers, a move into it costs armour one more.
   */
  private Game givenForest() {
    final Game forest =
        TerritoryXml.withEffect(TerritoryEffectsXml.withEffect(minimap, FOREST), NORTH, FOREST);
    final Game attack =
        TerritoryEffectsXml.withUnitValues(
            forest, FOREST, TerritoryEffectsXml.OFFENSE, Map.of("armour", "-1"));
    final Game defence =
        TerritoryEffectsXml.withUnitValues(
            attack, FOREST, TerritoryEffectsXml.DEFENSE, Map.of("infantry", "1"));
    final Game movement =
        TerritoryEffectsXml.withUnitValues(
            defence, FOREST, TerritoryEffectsXml.MOVEMENT_COST, Map.of("armour", "1"));
    final Game noBlitz =
        TerritoryEffectsXml.withUnits(
            movement, FOREST, TerritoryEffectsXml.NO_BLITZ, List.of("armour"));
    return TerritoryEffectsXml.withUnits(
        noBlitz, FOREST, TerritoryEffectsXml.NOT_ALLOWED, List.of("bomber"));
  }

  /** The forest named in a trigger besides its own attachment and the territory having it. */
  private Game givenNamedInTrigger() {
    final Game trigger =
        AttachmentsXml.withNewAttachment(
            givenForest(), "TriggerAttachment", "triggerAttachmentForest", "Russians");
    final int index = AttachmentsXml.attachments(trigger).size() - 1;
    return AttachmentsXml.withAttachment(
        trigger,
        index,
        AttachmentsXml.withNewOption(
            AttachmentsXml.attachments(trigger).get(index),
            Option.builder()
                .name("territoryProperty")
                .value(FOREST)
                .build()));
  }

  @DisplayName("An effect is added, renamed and removed, with the territories that have it")
  @Test
  void addsRenamesAndRemoves() {
    final Game forest = givenForest();

    final Game renamed = TerritoryEffectsXml.withRenamedEffect(forest, FOREST, "woods");
    final Game removed = TerritoryEffectsXml.withoutEffect(renamed, "woods");

    assertThat(TerritoryEffectsXml.effects(forest)).as("added").containsExactly(FOREST);
    assertThat(TerritoryEffectsXml.territoriesWith(forest))
        .as("one territory has it")
        .containsExactly(Map.entry(FOREST, List.of(NORTH)));
    assertThat(TerritoryEffectsXml.effects(renamed)).as("renamed").containsExactly("woods");
    assertThat(MapXml.territoryEffects(renamed, NORTH)).as("on the territory").contains("woods");
    assertThat(TerritoryEffectsXml.unitValues(renamed, "woods", TerritoryEffectsXml.OFFENSE))
        .as("its attachment follows")
        .containsEntry("armour", "-1");
    assertThat(TerritoryEffectsXml.effects(removed)).as("removed").isEmpty();
    assertThat(MapXml.territoryEffects(removed, NORTH)).as("off the territory").isEmpty();
    assertThat(TerritoryEffectsXml.withEffect(forest, FOREST)).as("taken").isSameAs(forest);
    assertThat(TerritoryEffectsXml.withRenamedEffect(forest, FOREST, "a:b"))
        .as("not a name")
        .isSameAs(forest);
  }

  @DisplayName("An effect named elsewhere, eg in a trigger, tells where; its own uses do not count")
  @Test
  void tellsWhereNamed() {
    assertThat(TerritoryEffectsXml.whereNamed(givenForest(), FOREST))
        .as("only its attachment and the territory")
        .isEmpty();
    assertThat(TerritoryEffectsXml.whereNamed(givenNamedInTrigger(), FOREST))
        .as("the trigger")
        .singleElement()
        .asString()
        .contains("triggerAttachmentForest");
  }

  @DisplayName("A game with the effect loads in the engine with what the effect does")
  @Test
  void effectLoads(@TempDir final Path folder) {
    final GameData data = RulesXmlTest.givenLoaded(givenForest(), folder);

    final TerritoryEffectAttachment forest =
        TerritoryEffectAttachment.get(data.getTerritoryEffectList().get(FOREST));
    final UnitType armour = data.getUnitTypeList().getUnitType("armour").orElseThrow();
    final UnitType infantry = data.getUnitTypeList().getUnitType("infantry").orElseThrow();
    assertThat(forest.getCombatEffect(armour, false)).as("armour attacks worse").isEqualTo(-1);
    assertThat(forest.getCombatEffect(infantry, true)).as("infantry defends better").isEqualTo(1);
    assertThat(forest.getMovementCostModifier()).containsEntry(armour, BigDecimal.ONE);
    assertThat(forest.getNoBlitz()).extracting(UnitType::getName).containsExactly("armour");
    assertThat(forest.getUnitsNotAllowed()).extracting(UnitType::getName).containsExactly("bomber");
    assertThat(
            TerritoryAttachment.get(data.getMap().getTerritoryOrNull(NORTH))
                .orElseThrow()
                .getTerritoryEffect())
        .as("on the territory")
        .hasSize(1);
  }

  /** A forest whose move cost is written as real maps often do: the number as a count. */
  private Game givenCostAsCount() {
    // an option of its own first: the effect's attachment comes with its first option
    final Game forest =
        TerritoryEffectsXml.withUnits(
            TerritoryEffectsXml.withEffect(minimap, FOREST),
            FOREST,
            TerritoryEffectsXml.NOT_ALLOWED,
            List.of("bomber"));
    final List<Attachment> attachments =
        AttachmentsXml.attachments(forest);
    final int index =
        IntStream.range(0, attachments.size())
            .filter(i -> FOREST.equals(attachments.get(i).getAttachTo()))
            .findFirst()
            .orElseThrow();
    return AttachmentsXml.withAttachment(
        forest,
        index,
        AttachmentsXml.withNewOption(
            attachments.get(index),
            Option.builder()
                .name(TerritoryEffectsXml.MOVEMENT_COST)
                .value("armour:infantry")
                .count("1")
                .build()));
  }

  @DisplayName("A value written with its number as a count is read, and written back as a count")
  @Test
  void readsAndKeepsCount(@TempDir final Path folder) {
    // given
    final Game counted = givenCostAsCount();

    // when
    final Map<String, String> read =
        TerritoryEffectsXml.unitValues(counted, FOREST, TerritoryEffectsXml.MOVEMENT_COST);
    final Game changed =
        TerritoryEffectsXml.withUnitValues(
            counted,
            FOREST,
            TerritoryEffectsXml.MOVEMENT_COST,
            Map.of("armour", "1", "infantry", "2"));

    // then
    assertThat(read)
        .as("the count is the number, the value the units")
        .containsOnly(Map.entry("armour", "1"), Map.entry("infantry", "1"));
    assertThat(
            AttachmentsXml.attachments(changed).stream()
                .filter(a -> FOREST.equals(a.getAttachTo()))
                .flatMap(a -> AttachmentsXml.options(a).stream())
                .filter(o -> TerritoryEffectsXml.MOVEMENT_COST.equals(o.getName()))
                .map(o -> o.getCount() + " " + o.getValue()))
        .as("still with the number as a count, one option per number")
        .containsExactlyInAnyOrder("1 armour", "2 infantry");
    final TerritoryEffectAttachment loaded =
        TerritoryEffectAttachment.get(
            RulesXmlTest.givenLoaded(changed, folder).getTerritoryEffectList().get(FOREST));
    assertThat(loaded.getMovementCostModifier().values())
        .as("the game reads both numbers")
        .containsExactlyInAnyOrder(BigDecimal.ONE, BigDecimal.valueOf(2));
  }
}
