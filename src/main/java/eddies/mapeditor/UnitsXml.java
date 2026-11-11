package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.UnitSupportAttachment;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;

/**
 * A unit type's options and the support it gives, as they stand in the game's XML. Every change
 * returns a new game; options the editor does not show, their counts and their order stay.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class UnitsXml {
  private static final String UNIT = Constants.UNIT_ATTACHMENT_NAME;
  static final String SUPPORT_KIND = "UnitSupportAttachment";

  // ---- the unit's options

  /** The options of the unit type's unitAttachment in their order. */
  static List<Option> options(final Game game, final String unit) {
    return attachmentIndex(game, UNIT, unit)
        .stream()
        .mapToObj(i -> AttachmentsXml.options(AttachmentsXml.attachments(game).get(i)))
        .flatMap(List::stream)
        .toList();
  }

  private static OptionalInt attachmentIndex(
      final Game game, final String name, final String attachTo) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    return IntStream.range(0, attachments.size())
        .filter(
            i ->
                name.equals(attachments.get(i).getName())
                    && attachTo.equals(attachments.get(i).getAttachTo()))
        .findFirst();
  }

  /**
   * Whether the option stands at most once and without a count, so it reads as one value; else it
   * is changed among the unit's other options, row by row.
   */
  static boolean isPlain(final Game game, final String unit, final String option) {
    final List<Option> named =
        options(game, unit).stream().filter(o -> option.equals(o.getName())).toList();
    return named.isEmpty() || (named.size() == 1 && named.getFirst().getCount() == null);
  }

  /** The option's value, its default when the game does not give it. */
  static String value(final Game game, final String unit, final UnitOptions.UnitOption option) {
    return MapXml.option(game, UNIT, unit, option.name()).orElse(option.defaultValue());
  }

  /** The game with the option set; set to its default or to nothing, it is left out. */
  static Game withValue(
      final Game game,
      final String unit,
      final UnitOptions.UnitOption option,
      final String value) {
    final String typed = value.strip();
    return typed.isEmpty() || typed.equals(option.defaultValue())
        ? MapXml.withoutUnitOption(game, unit, option.name())
        : MapXml.withUnitOption(game, unit, option.name(), typed);
  }

  /**
   * The old option, eg isTwoHit, that sets this one for the unit, so it is changed there: TripleA
   * reads it in its place among the options, and leaving the new one out would not undo it.
   */
  static Optional<String> setBy(final Game game, final String unit, final String option) {
    return options(game, unit).stream()
        .map(Option::getName)
        .filter(
            name ->
                UnitOptions.OLD_NAMES.entrySet().stream()
                    .anyMatch(
                        old ->
                            old.getKey().equalsIgnoreCase(name)
                                && old.getValue().contains(option)))
        .findFirst();
  }

  /**
   * The game with the unit's option of this name replaced, the given one of those with the name
   * (0 for the first), as rows move when other options come or go.
   */
  static Game withOptionAt(
      final Game game,
      final String unit,
      final String name,
      final int occurrence,
      final Option changed) {
    return rowOf(game, unit, name, occurrence)
        .stream()
        .mapToObj(
            row ->
                withAttachmentChanged(
                    game, unit, a -> AttachmentsXml.withOption(a, row, changed)))
        .findFirst()
        .orElse(game);
  }

  static Game withoutOptionAt(
      final Game game, final String unit, final String name, final int occurrence) {
    return rowOf(game, unit, name, occurrence)
        .stream()
        .mapToObj(
            row -> withAttachmentChanged(game, unit, a -> AttachmentsXml.withoutOption(a, row)))
        .findFirst()
        .orElse(game);
  }

  /** The unit's option of this name, the given one of those with the name (0 for the first). */
  static Optional<Option> optionAt(
      final Game game, final String unit, final String name, final int occurrence) {
    return options(game, unit).stream()
        .filter(o -> name.equals(o.getName()))
        .skip(occurrence)
        .findFirst();
  }

  private static OptionalInt rowOf(
      final Game game, final String unit, final String name, final int occurrence) {
    final List<Option> options = options(game, unit);
    return IntStream.range(0, options.size())
        .filter(row -> name.equals(options.get(row).getName()))
        .skip(occurrence)
        .findFirst();
  }

  private static Game withAttachmentChanged(
      final Game game, final String unit, final UnaryOperator<Attachment> change) {
    final int index = attachmentIndex(game, UNIT, unit).orElseThrow();
    return AttachmentsXml.withAttachment(
        game, index, change.apply(AttachmentsXml.attachments(game).get(index)));
  }

  /**
   * The game with the option added after the unit's others, also when one of the name is there, as
   * some take several, eg createsUnitsList.
   */
  static Game withNewOption(final Game game, final String unit, final Option option) {
    final OptionalInt index = attachmentIndex(game, UNIT, unit);
    if (index.isEmpty()) {
      return MapXml.withUnitOption(game, unit, option.getName(), option.getValue());
    }
    return AttachmentsXml.withAttachment(
        game,
        index.getAsInt(),
        AttachmentsXml.withNewOption(
            AttachmentsXml.attachments(game).get(index.getAsInt()), option));
  }

  // ---- the support it gives

  /** The names of the unit type's support attachments, which TripleA finds by their start. */
  static List<String> supports(final Game game, final String unit) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> unit.equals(a.getAttachTo()) && SUPPORT_KIND.equals(AttachmentsXml.kind(a)))
        .map(Attachment::getName)
        .filter(name -> name != null && name.startsWith(Constants.SUPPORT_ATTACHMENT_PREFIX))
        .toList();
  }

  static Optional<String> supportOption(
      final Game game, final String unit, final String support, final String option) {
    return MapXml.option(game, support, unit, option);
  }

  /** The game with the support's option set; set to nothing, it is left out. */
  static Game withSupportOption(
      final Game game,
      final String unit,
      final String support,
      final String option,
      final String value) {
    final String typed = value.strip();
    return MapXml.withOption(
        game,
        new MapXml.AttachmentKey(
            support, UnitSupportAttachment.class.getName(), "unitType", unit),
        option,
        typed.isEmpty() ? null : typed);
  }

  /**
   * The game with a new support of the unit type, as artillery gives: +1 attack to one allied
   * infantry (else to one of its own kind) of every nation.
   */
  static Game withNewSupport(final Game game, final String unit, final String support) {
    final List<String> units = MapXml.unitTypes(game);
    final Game added = AttachmentsXml.withNewAttachment(game, SUPPORT_KIND, support, unit);
    final int last = AttachmentsXml.attachments(added).size() - 1;
    final List<Option> options =
        Stream.of(
                option("unitType", units.contains("infantry") ? "infantry" : unit),
                option("side", "offence"),
                option("faction", "allied"),
                option("bonus", "1"),
                option("number", "1"),
                // its own kind, so it adds up with no other support
                option("bonusType", support),
                option("dice", "strength"),
                option("players", String.join(":", MapXml.nations(game))))
            .filter(o -> !o.getValue().isEmpty())
            .toList();
    return AttachmentsXml.withAttachment(
        added,
        last,
        AttachmentsXml.attachments(added).get(last).toBuilder().options(options).build());
  }

  private static Option option(final String name, final String value) {
    return Option.builder().name(name).value(value).build();
  }

  static Game withoutSupport(final Game game, final String unit, final String support) {
    return attachmentIndex(game, support, unit)
        .stream()
        .mapToObj(i -> AttachmentsXml.withoutAttachment(game, i))
        .findFirst()
        .orElse(game);
  }
}
