package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.UnitSupportAttachment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.WeakHashMap;
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
  private static final String UNIT_KIND = "UnitAttachment";
  static final String USED_UP = "consumesUnits";

  // ---- the unit's options

  /**
   * The options of the unit type's unitAttachment in their order, as TripleA reads them: also of
   * one written once for each element of a variable.
   */
  static List<Option> options(final Game game, final String unit) {
    return read(game, unit).map(Read::made).map(AttachmentsXml::options).orElse(List.of());
  }

  /** The option's value as the game file gives it to the unit type; none when it does not. */
  static Optional<String> option(final Game game, final String unit, final String option) {
    return options(game, unit).stream()
        .filter(o -> AttachmentsXml.isOption(o, option))
        .map(Option::getValue)
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Whether the unit type cannot move during combat move, as TripleA keeps it: isAA and
   * isAAmovement set canNotMoveDuringCombatMove too, so of the three the last written counts.
   */
  static boolean cannotMoveInCombatMove(final Game game, final String unit) {
    return options(game, unit).stream()
        .filter(
            o ->
                Stream.of("isAA", "isAAmovement", "canNotMoveDuringCombatMove")
                    .anyMatch(name -> AttachmentsXml.isOption(o, name)))
        .map(o -> Boolean.parseBoolean(o.getValue()))
        .reduce((first, last) -> last)
        .orElse(false);
  }

  /**
   * The units one of the type uses up when it is placed (consumesUnits), eg "2 Material": to the
   * player a cost beside the rule's, though the game file has it as an option of the unit.
   */
  static List<String> usedUp(final Game game, final String unit) {
    return options(game, unit).stream()
        .flatMap(o -> UsedUp.read(o).stream())
        .map(used -> used.count() + " " + used.unit())
        .toList();
  }

  /**
   * So many units of a type that one of another uses up when placed: one consumesUnits option, of
   * which a unit type may have several.
   */
  record UsedUp(int count, String unit) {
    /** As TripleA reads the option; empty for another option or one that does not read so. */
    static Optional<UsedUp> read(final Option option) {
      if (!AttachmentsXml.isOption(option, USED_UP) || option.getValue() == null) {
        return Optional.empty();
      }
      // "count:unit", the count also given apart
      final String[] parts =
          Optional.ofNullable(option.getCount())
              .filter(count -> !count.isEmpty())
              .map(count -> count + ":" + option.getValue())
              .orElse(option.getValue())
              .split(":");
      try {
        return parts.length == 2 && !parts[1].isBlank()
            ? Optional.of(new UsedUp(Integer.parseInt(parts[0].strip()), parts[1].strip()))
            : Optional.empty();
      } catch (final NumberFormatException e) {
        return Optional.empty();
      }
    }
  }

  /**
   * The game with the unit type's consumesUnits option, the given one of them (0 for the first),
   * saying this, in the form the map wrote it: the count apart or as "count:unit". Unchanged for
   * one that says it already, and for one that does not read, which is changed among the unit's
   * other options.
   */
  static Game withUsedUp(
      final Game game, final String unit, final int occurrence, final UsedUp used) {
    return optionAt(game, unit, USED_UP, occurrence)
        // the same again, eg the unit type chosen once more, is no step
        .filter(had -> UsedUp.read(had).filter(now -> !now.equals(used)).isPresent())
        .map(
            had -> {
              final boolean counted =
                  Optional.ofNullable(had.getCount()).filter(c -> !c.isEmpty()).isPresent();
              return withOptionAt(
                  game,
                  unit,
                  USED_UP,
                  occurrence,
                  Option.builder()
                      .name(had.getName())
                      .count(counted ? String.valueOf(used.count()) : null)
                      .value(counted ? used.unit() : used.count() + ":" + used.unit())
                      .build());
            })
        .orElse(game);
  }

  /**
   * The game with the unit type using up one of one more unit type, the first it does not use up
   * yet, to choose another from: a new option after its others. Unchanged when there is none.
   */
  static Game withNewUsedUp(final Game game, final String unit) {
    final List<String> used =
        options(game, unit).stream()
            .flatMap(o -> UsedUp.read(o).stream())
            .map(UsedUp::unit)
            .toList();
    return MapXml.unitTypes(game).stream()
        .filter(type -> !type.equals(unit) && !used.contains(type))
        .findFirst()
        .map(
            type ->
                withNewOption(
                    game, unit, Option.builder().name(USED_UP).value("1:" + type).build()))
        .orElse(game);
  }

  /**
   * The attachment written once for each element of a variable (foreach) that makes the unit
   * type's unitAttachment, as written: its options are read here but changed in Attachments, at
   * the one written, as one written here for this unit type alone would replace all it gives.
   */
  static Optional<Attachment> loop(final Game game, final String unit) {
    return read(game, unit).flatMap(Read::loop);
  }

  /**
   * The game with the unit type's unitAttachment written for it alone, with all its {@link #loop}
   * gives it and behind it ({@link AttachmentsXml#withOwn}): then changed here like any other, and
   * no longer following the loop. The same game for a unit type no loop makes.
   */
  static Game withOwn(final Game game, final String unit) {
    return loop(game, unit).isPresent() ? AttachmentsXml.withOwn(game, UNIT, unit) : game;
  }

  /** A unit type's unitAttachment as the game reads it, and the loop that makes it, if one does. */
  private record Read(Attachment made, Optional<Attachment> loop) {}

  // by the game, which every change makes anew; kept while it is, as the list's line asks three
  // numbers of every unit type
  private static final Map<Game, Map<String, Read>> READ = new WeakHashMap<>();

  private static Optional<Read> read(final Game game, final String unit) {
    synchronized (READ) {
      return Optional.ofNullable(READ.computeIfAbsent(game, UnitsXml::read).get(unit));
    }
  }

  // of two for a unit type the last, as TripleA keeps one per name, written for it or by a loop
  private static Map<String, Read> read(final Game game) {
    final Map<String, List<String>> variables = AttachmentsXml.variables(game);
    final Map<String, Read> last = new HashMap<>();
    for (final Attachment written : AttachmentsXml.attachments(game)) {
      if (!AttachmentsXml.isForEach(written)) {
        if (AttachmentsXml.isNamed(written, UNIT) && written.getAttachTo() != null) {
          last.put(written.getAttachTo(), new Read(written, Optional.empty()));
        }
        continue;
      }
      // only the loops of its kind: a big map has thousands of others
      if (written.getName() == null || !UNIT_KIND.equals(AttachmentsXml.kind(written))) {
        continue;
      }
      for (final Attachment made : AttachmentsXml.madeBy(written, variables)) {
        if (AttachmentsXml.isNamed(made, UNIT) && made.getAttachTo() != null) {
          last.put(made.getAttachTo(), new Read(made, Optional.of(written)));
        }
      }
    }
    return last;
  }

  private static OptionalInt attachmentIndex(
      final Game game, final String name, final String attachTo) {
    final int at = AttachmentsXml.lastIndexOf(AttachmentsXml.attachments(game), name, attachTo);
    return at < 0 ? OptionalInt.empty() : OptionalInt.of(at);
  }

  /**
   * Whether the option stands at most once and without a count, so it reads as one value; else it
   * is changed among the unit's other options, row by row.
   */
  static boolean isPlain(final Game game, final String unit, final String option) {
    return isPlain(options(game, unit), option);
  }

  /** As {@link #isPlain(Game, String, String)}, among the unit's options read once for many. */
  static boolean isPlain(final List<Option> options, final String option) {
    final List<Option> named =
        options.stream().filter(o -> AttachmentsXml.isOption(o, option)).toList();
    return named.isEmpty() || (named.size() == 1 && named.getFirst().getCount() == null);
  }

  /** The option's value, its default when the game does not give it. */
  static String value(final Game game, final String unit, final UnitOptions.UnitOption option) {
    return option(game, unit, option.name()).orElse(option.defaultValue());
  }

  /** The game with the option set; set to its default or to nothing, it is left out. */
  static Game withValue(
      final Game game,
      final String unit,
      final UnitOptions.UnitOption option,
      final String value) {
    if (loop(game, unit).isPresent()) {
      // not for this unit type alone: see loop
      return game;
    }
    final String typed = value.strip();
    return typed.isEmpty() || typed.equals(option.defaultValue())
        ? MapXml.withoutUnitOption(game, unit, option.name())
        : MapXml.withUnitOption(game, unit, option.name(), typed);
  }

  /** Land, sea or air; empty for a unit that both sails and flies, which no kind describes. */
  static Optional<UnitOptions.UnitKind> kind(final Game game, final String unit) {
    final boolean sea = Boolean.parseBoolean(value(game, unit, UnitOptions.named("isSea")));
    final boolean air = Boolean.parseBoolean(value(game, unit, UnitOptions.named("isAir")));
    if (sea && air) {
      return Optional.empty();
    }
    if (sea) {
      return Optional.of(UnitOptions.UnitKind.SEA);
    }
    return Optional.of(air ? UnitOptions.UnitKind.AIR : UnitOptions.UnitKind.LAND);
  }

  /** The game with the unit of the kind: isSea and isAir set, false ones left out. */
  static Game withKind(final Game game, final String unit, final UnitOptions.UnitKind kind) {
    final Game sea =
        withValue(
            game,
            unit,
            UnitOptions.named("isSea"),
            String.valueOf(kind == UnitOptions.UnitKind.SEA));
    return withValue(
        sea, unit, UnitOptions.named("isAir"), String.valueOf(kind == UnitOptions.UnitKind.AIR));
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
        .filter(o -> AttachmentsXml.isOption(o, name))
        .skip(occurrence)
        .findFirst();
  }

  /**
   * The unit type's limit of this name ({@link RulesXml#STACKING_LIMITS}); empty when it has none,
   * or one the Stacking limits section cannot show: given twice, or not as TripleA reads it.
   */
  static Optional<RulesXml.StackingLimit> stackingLimit(
      final Game game, final String unit, final String name) {
    final List<Option> named =
        options(game, unit).stream().filter(o -> AttachmentsXml.isOption(o, name)).toList();
    return named.size() == 1
        ? RulesXml.StackingLimit.read(named.getFirst()).filter(limit -> limit.units().isEmpty())
        : Optional.empty();
  }

  /** Whether the unit type has an option of this name at all. */
  static boolean hasOption(final Game game, final String unit, final String name) {
    return options(game, unit).stream().anyMatch(o -> AttachmentsXml.isOption(o, name));
  }

  /**
   * The game with the unit type's limit of this name set where it is, under the name it has, else
   * after its other options; none takes it out. Unchanged while the unit type has an option of the
   * name that {@link #stackingLimit} does not read: that one is changed among its other options.
   */
  static Game withStackingLimit(
      final Game game,
      final String unit,
      final String name,
      final Optional<RulesXml.StackingLimit> limit) {
    final Optional<Option> had = optionAt(game, unit, name, 0);
    final Optional<RulesXml.StackingLimit> now = stackingLimit(game, unit, name);
    if (now.equals(limit) || (had.isPresent() && now.isEmpty())) {
      return game;
    }
    if (limit.isEmpty()) {
      return withoutOptionAt(game, unit, name, 0);
    }
    // as the map writes it: many have the number as the option's count, eg count="3" value="allied"
    final boolean counted = had.map(Option::getCount).filter(c -> !c.isEmpty()).isPresent();
    final Option written =
        Option.builder()
            .name(had.map(Option::getName).orElse(name))
            .count(counted ? String.valueOf(limit.get().most()) : null)
            .value(counted ? limit.get().counting() : limit.get().written())
            .build();
    return had.isPresent()
        ? withOptionAt(game, unit, name, 0, written)
        : withNewOption(game, unit, written);
  }

  private static OptionalInt rowOf(
      final Game game, final String unit, final String name, final int occurrence) {
    final List<Option> options = options(game, unit);
    return IntStream.range(0, options.size())
        .filter(row -> AttachmentsXml.isOption(options.get(row), name))
        .skip(occurrence)
        .findFirst();
  }

  private static Game withAttachmentChanged(
      final Game game, final String unit, final UnaryOperator<Attachment> change) {
    if (loop(game, unit).isPresent()) {
      return game;
    }
    final int index = attachmentIndex(game, UNIT, unit).orElseThrow();
    return AttachmentsXml.withAttachment(
        game, index, change.apply(AttachmentsXml.attachments(game).get(index)));
  }

  /**
   * The game with the option added after the unit's others, also when one of the name is there, as
   * some take several, eg createsUnitsList.
   */
  static Game withNewOption(final Game game, final String unit, final Option option) {
    if (loop(game, unit).isPresent()) {
      return game;
    }
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
    return supportAttachments(game, unit).stream().map(Attachment::getName).toList();
  }

  /**
   * The unit's supports as TripleA reads them (UnitSupportAttachment.get): the attachments of the
   * kind on it, named supportAttachment…, also those written once for each unit of a variable.
   */
  private static List<Attachment> supportAttachments(final Game game, final String unit) {
    return AttachmentsXml.expanded(game).stream()
        .filter(a -> unit.equals(a.getAttachTo()) && SUPPORT_KIND.equals(AttachmentsXml.kind(a)))
        .filter(
            a ->
                a.getName() != null
                    && AttachmentsXml.readName(a.getName())
                        .startsWith(Constants.SUPPORT_ATTACHMENT_PREFIX))
        .toList();
  }

  /**
   * The variable the support is written once for each unit of, eg "$Planes$"; empty for one
   * written for the unit itself, which is changed in Units.
   */
  static Optional<String> supportForEach(
      final Game game, final String unit, final String support) {
    return supportAttachments(game, unit).stream()
        .filter(a -> support.equals(a.getName()))
        .reduce((first, last) -> last)
        .map(Attachment::getForeach)
        .filter(each -> !each.isBlank());
  }

  /** The support's option, of the last attachment of the name, as the game takes it. */
  static Optional<String> supportOption(
      final Game game, final String unit, final String support, final String option) {
    return supportAttachments(game, unit).stream()
        .filter(a -> support.equals(a.getName()))
        .reduce((first, last) -> last)
        .stream()
        .flatMap(a -> AttachmentsXml.options(a).stream())
        .filter(o -> AttachmentsXml.isOption(o, option))
        .map(Option::getValue)
        .filter(Objects::nonNull)
        .findFirst();
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
