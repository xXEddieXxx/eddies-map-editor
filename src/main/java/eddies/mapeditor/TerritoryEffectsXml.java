package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryEffectAttachment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.TerritoryEffectList;
import org.triplea.map.data.elements.TerritoryEffectList.TerritoryEffect;
import eddies.mapeditor.MapXml.AttachmentKey;

/**
 * The game's territory effects, eg forest: the list of them, and what each does in its
 * territoryEffectAttachment. Every change returns a new game, the given one when nothing changes.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class TerritoryEffectsXml {
  static final String OFFENSE = "combatOffenseEffect";
  static final String DEFENSE = "combatDefenseEffect";
  static final String MOVEMENT_COST = "movementCostModifier";
  static final String NOT_ALLOWED = "unitsNotAllowed";
  static final String NO_BLITZ = "noBlitz";
  private static final String TYPE = "territoryEffect";

  static List<String> effects(final Game game) {
    return Optional.ofNullable(game.getTerritoryEffectList())
        .map(TerritoryEffectList::getTerritoryEffects)
        .orElse(List.of())
        .stream()
        .map(TerritoryEffect::getName)
        .toList();
  }

  /** The territories that have each effect, in one pass over the attachments of a big map. */
  static Map<String, List<String>> territoriesWith(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> AttachmentsXml.isNamed(a, Constants.TERRITORY_ATTACHMENT_NAME))
        .flatMap(
            a ->
                AttachmentsXml.options(a).stream()
                    .filter(
                        o ->
                            AttachmentsXml.isOption(o, TerritoryXml.EFFECT)
                                && o.getValue() != null)
                    .flatMap(o -> Arrays.stream(o.getValue().split(":")))
                    .map(String::strip)
                    .distinct()
                    .map(effect -> Map.entry(effect, a.getAttachTo())))
        .collect(
            Collectors.groupingBy(
                Map.Entry::getKey,
                LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
  }

  /** The game with a new effect after the others; none for a name taken or not allowed. */
  static Game withEffect(final Game game, final String effect) {
    if (!isNewName(game, effect)) {
      return game;
    }
    return withEffects(game, Stream.concat(effects(game).stream(), Stream.of(effect)).toList());
  }

  private static boolean isNewName(final Game game, final String name) {
    return !name.isBlank()
        && name.strip().equals(name)
        && !name.contains(":")
        && !effects(game).contains(name);
  }

  private static Game withEffects(final Game game, final List<String> effects) {
    return game.toBuilder()
        .territoryEffectList(
            TerritoryEffectList.builder()
                .territoryEffects(
                    effects.stream().map(e -> TerritoryEffect.builder().name(e).build()).toList())
                .build())
        .build();
  }

  /**
   * The game with the effect renamed in the list, its attachments and the territories that have
   * it; none for a name taken or not allowed.
   */
  static Game withRenamedEffect(final Game game, final String old, final String renamed) {
    if (!effects(game).contains(old) || !isNewName(game, renamed)) {
      return game;
    }
    final UnaryOperator<String> rename = name -> name.equals(old) ? renamed : name;
    final Game attached =
        withAttachments(
            game,
            AttachmentsXml.attachments(game).stream()
                .map(
                    a ->
                        isOf(a, old)
                            ? a.toBuilder().attachTo(renamed).build()
                            : withEffectNames(a, names -> names.map(rename)))
                .toList());
    return withEffects(attached, effects(game).stream().map(rename).toList());
  }

  private static boolean isOf(final Attachment attachment, final String effect) {
    return TYPE.equals(attachment.getType()) && effect.equals(attachment.getAttachTo());
  }

  /** A territory attachment with the effect names its territoryEffect options list changed. */
  private static Attachment withEffectNames(
      final Attachment attachment, final UnaryOperator<Stream<String>> change) {
    if (!AttachmentsXml.isNamed(attachment, Constants.TERRITORY_ATTACHMENT_NAME)) {
      return attachment;
    }
    final List<Option> options = new ArrayList<>();
    for (final Option o : AttachmentsXml.options(attachment)) {
      if (!AttachmentsXml.isOption(o, TerritoryXml.EFFECT) || o.getValue() == null) {
        options.add(o);
        continue;
      }
      final String names =
          change
              .apply(Arrays.stream(o.getValue().split(":")).map(String::strip))
              .collect(Collectors.joining(":"));
      if (!names.isEmpty()) {
        options.add(o.toBuilder().value(names).build());
      }
    }
    return attachment.toBuilder().options(List.copyOf(options)).build();
  }

  private static Game withAttachments(final Game game, final List<Attachment> attachments) {
    final AttachmentList list =
        Optional.ofNullable(game.getAttachmentList())
            .orElseGet(() -> AttachmentList.builder().build());
    return game.toBuilder()
        .attachmentList(list.toBuilder().attachments(attachments).build())
        .build();
  }

  /**
   * Where the effect is named besides its own attachments and the territories that have it, eg in
   * a trigger: there it must be changed first, before the effect is renamed or removed.
   */
  static List<String> whereNamed(final Game game, final String effect) {
    return RulesXml.whereNamed(withoutUses(game, effect), effect);
  }

  /** The game without the effect, its attachments, and the territories without it. */
  static Game withoutEffect(final Game game, final String effect) {
    if (!effects(game).contains(effect)) {
      return game;
    }
    return withEffects(
        withoutUses(game, effect),
        effects(game).stream().filter(e -> !e.equals(effect)).toList());
  }

  private static Game withoutUses(final Game game, final String effect) {
    return withAttachments(
        game,
        AttachmentsXml.attachments(game).stream()
            .filter(a -> !isOf(a, effect))
            .map(a -> withEffectNames(a, names -> names.filter(name -> !name.equals(effect))))
            .toList());
  }

  /** The effect's attachment, "territoryEffectAttachment". */
  private static AttachmentKey key(final String effect) {
    return new AttachmentKey(
        AttachmentsXml.NAMES.get("TerritoryEffectAttachment"),
        TerritoryEffectAttachment.class.getName(),
        TYPE,
        effect);
  }

  /**
   * By unit type the value of an option listing "value:unit:unit", eg -1 for armour of
   * combatOffenseEffect "-1:armour:artillery".
   */
  static Map<String, String> unitValues(
      final Game game, final String effect, final String option) {
    final Map<String, String> values = new LinkedHashMap<>();
    for (final String value : TerritoryXml.values(game, key(effect), option)) {
      final List<String> parts = Arrays.stream(value.split(":")).map(String::strip).toList();
      parts.stream().skip(1).forEach(unit -> values.put(unit, parts.getFirst()));
    }
    return values;
  }

  /** The game with the option's values by unit type these, one option for each value. */
  static Game withUnitValues(
      final Game game, final String effect, final String option, final Map<String, String> units) {
    if (unitValues(game, effect, option).equals(units)) {
      return game;
    }
    final Map<String, List<String>> byValue = new LinkedHashMap<>();
    units.forEach(
        (unit, value) -> byValue.computeIfAbsent(value, v -> new ArrayList<>()).add(unit));
    return TerritoryXml.withValues(
        game,
        key(effect),
        option,
        byValue.entrySet().stream()
            .map(e -> e.getKey() + ":" + String.join(":", e.getValue()))
            .toList());
  }

  /** The unit types an option lists, eg unitsNotAllowed. */
  static List<String> units(final Game game, final String effect, final String option) {
    return TerritoryXml.values(game, key(effect), option).stream()
        .flatMap(value -> Arrays.stream(value.split(":")))
        .map(String::strip)
        .filter(unit -> !unit.isEmpty())
        .toList();
  }

  /** The game with the option listing these unit types; none takes it out. */
  static Game withUnits(
      final Game game, final String effect, final String option, final List<String> units) {
    if (units(game, effect, option).equals(units)) {
      return game;
    }
    final List<String> written = units.isEmpty() ? List.of() : List.of(String.join(":", units));
    return TerritoryXml.withValues(game, key(effect), option, written);
  }
}
