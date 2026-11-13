package eddies.mapeditor;

import eddies.mapeditor.TriggerXml.Clauses;
import eddies.mapeditor.TriggerXml.Group;
import eddies.mapeditor.TriggerXml.Trigger;
import games.strategy.triplea.Constants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PropertyList.Property;

/**
 * National objectives as the game XML has them: a condition (RulesAttachment) of a nation with an
 * objectiveValue, the PUs the nation gets at the end of its turn while the condition holds. Its
 * checks are a condition's, so {@link TriggerXml} reads and writes them; options the editor has no
 * words for, their counts and their order stay.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class ObjectiveXml {
  static final String VALUE = "objectiveValue";
  static final String USES = "uses";
  static final String CONDITIONS = "conditions";
  /** The start of the name by which the game finds the objectives to pay. */
  static final String PREFIX = Constants.RULES_OBJECTIVE_PREFIX;

  /** The game option without which no objective pays. */
  static final String GAME_OPTION = Constants.NATIONAL_OBJECTIVES;

  /** The options the Objectives tool shows with fields of their own, besides the checks. */
  private static final Set<String> OWN =
      Set.of(VALUE, USES, CONDITIONS, TriggerXml.OLD_CONDITIONS, "players", "invert");

  /**
   * An objective.
   *
   * @param index its place in the game's attachments
   */
  record Objective(int index, Attachment attachment) {
    String name() {
      return Objects.toString(attachment.getName(), "");
    }

    String nation() {
      return Objects.toString(attachment.getAttachTo(), "");
    }
  }

  /** Whether the attachment is a condition that pays: one with an objectiveValue. */
  static boolean isObjective(final Attachment attachment) {
    return TriggerXml.CONDITION.equals(AttachmentsXml.kind(attachment))
        && AttachmentsXml.options(attachment).stream()
            .anyMatch(o -> AttachmentsXml.isOption(o, VALUE));
  }

  /** The game's objectives, in the order of the file. */
  static List<Objective> objectives(final Game game) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    return IntStream.range(0, attachments.size())
        .filter(i -> isObjective(attachments.get(i)))
        .mapToObj(i -> new Objective(i, attachments.get(i)))
        .toList();
  }

  /** The PUs it pays; 0 when the file has no number there. */
  static int value(final Attachment objective) {
    return number(objective, VALUE).orElse(0);
  }

  /** How many times it still pays; empty for every time, the game's -1. */
  static Optional<Integer> uses(final Attachment objective) {
    return number(objective, USES).filter(u -> u >= 0);
  }

  private static Optional<Integer> number(final Attachment attachment, final String option) {
    try {
      return TriggerXml.value(attachment, option).map(v -> Integer.parseInt(v.strip()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** Whether a check counts "each", so the game pays the PUs once per territory that counts. */
  static boolean paysEach(final Attachment objective) {
    return AttachmentsXml.options(objective).stream()
        .anyMatch(o -> "each".equalsIgnoreCase(TriggerXml.joined(o).split(":", 2)[0]));
  }

  /** Eg "+5 PUs", "+3 PUs each" or "−2 PUs". */
  static String reward(final Attachment objective) {
    final int value = value(objective);
    return (value < 0 ? "−" + -value : "+" + value)
        + " PUs"
        + (paysEach(objective) ? " each" : "");
  }

  /**
   * What must be true for it to pay, in plain words, eg "Germans own all of Poland, Ukraine":
   * its own checks and the conditions it names; "always" without any.
   *
   * @param conditions the game's conditions by name, {@link TriggerXml#conditionsByName}
   */
  static String summary(
      final Game game, final Attachment objective, final Map<String, Attachment> conditions) {
    final boolean hasChecks =
        AttachmentsXml.options(objective).stream().anyMatch(ObjectiveXml::isCheck);
    final String own = hasChecks ? TriggerXml.conditionSentence(objective) : "";
    final List<Attachment> named =
        conditionNames(objective).stream()
            .map(conditions::get)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    // as a trigger's conditions: joined by its conditionType
    final String others =
        TriggerXml.clauses(game, new Trigger(-1, objective, named))
            .conditions()
            .replaceFirst("^(if|unless) ", "");
    final String all =
        Stream.of(own, others).filter(s -> !s.isEmpty()).collect(Collectors.joining(" and "));
    final boolean inverted =
        TriggerXml.value(objective, "invert").filter("true"::equalsIgnoreCase).isPresent();
    if (all.isEmpty()) {
      return inverted ? "never" : "always";
    }
    // its own sentence says "not" already, for all that follows
    return inverted && own.isEmpty() ? "not: " + all : all;
  }

  private static boolean isCheck(final Option option) {
    return TriggerXml.spec(option.getName()).filter(s -> s.group() == Group.CHECK).isPresent();
  }

  /** The names of the conditions it also needs, in its order. */
  static List<String> conditionNames(final Attachment objective) {
    return Stream.of(CONDITIONS, TriggerXml.OLD_CONDITIONS)
        .flatMap(o -> TriggerXml.values(objective, o).stream())
        .flatMap(v -> Stream.of(v.split(":")))
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  /** What it does as the parts of a card: if, then, when and how often. */
  static Clauses clauses(
      final Game game, final Attachment objective, final Map<String, Attachment> conditions) {
    final int value = value(objective);
    final String summary = summary(game, objective, conditions);
    final String nation = Objects.toString(objective.getAttachTo(), "");
    return new Clauses(
        nation,
        summary.equals("always") ? "" : "if " + summary,
        List.of(
            (value < 0 ? "take " + -value : "give " + value)
                + " PUs"
                + (paysEach(objective) ? " for each territory that counts" : "")),
        "at the end of the turn of " + nation,
        uses(objective)
            .map(
                u ->
                    u == 0
                        ? "Used up: it pays no more."
                        : u == 1 ? "Once." : "At most " + u + " times.")
            .orElse("Every round it holds."));
  }

  /** The options the Objectives tool has no field for, which it shows as they are. */
  static List<Option> otherOptions(final Attachment objective) {
    return AttachmentsXml.options(objective).stream()
        .filter(o -> !isCheck(o))
        .filter(o -> !OWN.contains(AttachmentsXml.optionName(Objects.toString(o.getName(), ""))))
        .toList();
  }

  /** Why the game does not pay it, in plain words; none when it does. */
  static List<String> whyNotPaid(final Game game, final Objective objective) {
    final List<String> reasons = new ArrayList<>();
    if (!AttachmentsXml.readName(objective.name()).startsWith(PREFIX)) {
      reasons.add(
          "The game pays only objectives whose name starts with "
              + PREFIX
              + ": this one never pays. Rename it in Attachments.");
    }
    if (!MapXml.nations(game).contains(objective.nation())) {
      reasons.add("No nation is called " + objective.nation() + ".");
    }
    return reasons;
  }

  // ---- changes

  /** The objective with the option set where it is, else after the others; all else stays. */
  static Attachment with(final Attachment objective, final String option, final String value) {
    return TriggerXml.withOptions(
        objective,
        Stream.concat(joinedExcept(objective, option), Stream.of(TriggerXml.option(option, value)))
            .toList());
  }

  /** The objective without the option, so the game's default applies. */
  static Attachment without(final Attachment objective, final String option) {
    return TriggerXml.withOptions(objective, joinedExcept(objective, option).toList());
  }

  private static Stream<Option> joinedExcept(final Attachment attachment, final String option) {
    return AttachmentsXml.options(attachment).stream()
        .filter(o -> !AttachmentsXml.isOption(o, option))
        .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o)));
  }

  /**
   * The objective with the checks, nations and "invert" of the other, eg as the fields of {@link
   * TriggerAssistant#checksOf} have them; its own PUs, uses and all else stay.
   */
  static Attachment withChecksOf(final Attachment objective, final Attachment from) {
    return TriggerXml.withOptions(
        objective,
        Stream.concat(
                AttachmentsXml.options(objective).stream()
                    .filter(o -> !TriggerXml.isManagedOnCondition(o.getName())),
                AttachmentsXml.options(from).stream()
                    .filter(o -> TriggerXml.isManagedOnCondition(o.getName())))
            .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o)))
            .toList());
  }

  /**
   * The checks among the fields' options that the game could not read, eg one without its
   * territories yet; one the objective has just so already is the map's own and none of these.
   */
  static List<Option> unreadableChecks(
      final Game game, final Attachment objective, final Attachment fields) {
    return AttachmentsXml.options(fields).stream()
        .filter(o -> TriggerXml.checkProblem(game, o).isPresent())
        .filter(o -> AttachmentsXml.options(objective).stream().noneMatch(b -> isSame(b, o)))
        .toList();
  }

  private static boolean isSame(final Option one, final Option other) {
    return AttachmentsXml.isOption(one, Objects.toString(other.getName(), ""))
        && TriggerXml.joined(one).equals(TriggerXml.joined(other));
  }

  /**
   * The fields' attachment without its {@link #unreadableChecks}, so the rest can be written: in
   * place of each the check the objective has at that place among its checks, if it is of the same
   * option and the fields do not have it elsewhere, so a check being changed stays as it was until
   * the change reads. With rows removed above it this may keep another check of that option for
   * the moment; the next change that reads puts it right.
   */
  static Attachment withReadableChecks(
      final Game game, final Attachment objective, final Attachment fields) {
    final List<Option> held = unreadableChecks(game, objective, fields);
    if (held.isEmpty()) {
      return fields;
    }
    final List<Option> before =
        AttachmentsXml.options(objective).stream().filter(ObjectiveXml::isCheck).toList();
    final List<Option> all = AttachmentsXml.options(fields);
    final List<Option> readable = new ArrayList<>();
    int check = 0;
    for (final Option option : all) {
      final int at = isCheck(option) ? check++ : -1;
      if (!held.contains(option)) {
        readable.add(option);
      } else if (at < before.size()
          && AttachmentsXml.isOption(before.get(at), Objects.toString(option.getName(), ""))
          && all.stream().noneMatch(o -> isSame(o, before.get(at)))) {
        readable.add(before.get(at));
      }
    }
    return fields.toBuilder().options(List.copyOf(readable)).build();
  }

  /**
   * The objective naming these conditions, under the option name it uses for them ("conditions",
   * or the old "trigger"); none: without the option.
   */
  static Attachment withConditions(final Attachment objective, final List<String> names) {
    final String option =
        TriggerXml.value(objective, CONDITIONS).isEmpty()
                && TriggerXml.value(objective, TriggerXml.OLD_CONDITIONS).isPresent()
            ? TriggerXml.OLD_CONDITIONS
            : CONDITIONS;
    return names.isEmpty()
        ? without(objective, option)
        : with(objective, option, String.join(":", names));
  }

  /**
   * A name for a new objective of the nation that no attachment has: the start the game pays by,
   * the nation and a number, eg objectiveAttachment_Germans_3.
   */
  static String newName(final Game game, final String nation) {
    final Set<String> taken = names(game);
    final String start = PREFIX + "_" + nation.strip().replaceAll("[^\\w-]+", "_") + "_";
    return IntStream.iterate(1, i -> i + 1)
        .mapToObj(i -> start + i)
        .filter(n -> !taken.contains(n))
        .findFirst()
        .orElseThrow();
  }

  private static Set<String> names(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .map(Attachment::getName)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  /**
   * The game with a new objective of the nation that pays the PUs, after the other attachments, so
   * it may name any condition before it; without checks yet it pays every round.
   */
  static Game withNew(final Game game, final String name, final String nation, final int pus) {
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
    attachments.add(
        AttachmentsXml.withNewOption(
            AttachmentsXml.newAttachment(TriggerXml.CONDITION, name, nation),
            TriggerXml.option(VALUE, String.valueOf(pus))));
    return AttachmentsXml.withAttachments(game, attachments);
  }

  /** The name a copy of the objective takes: its name and "_copy", numbered if taken. */
  static String copyName(final Game game, final Objective objective) {
    return RulesXml.freeName(names(game), objective.name() + "_copy");
  }

  /** The game with a copy of the objective under the name, after the other attachments. */
  static Game withDuplicate(final Game game, final Objective objective, final String name) {
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
    attachments.add(objective.attachment().toBuilder().name(name).build());
    return AttachmentsXml.withAttachments(game, attachments);
  }

  /** The game without the objective; empty while others name it, as the game would not load. */
  static Optional<Game> without(final Game game, final Objective objective) {
    return RulesXml.whereNamed(game, objective.name()).isEmpty()
        ? Optional.of(AttachmentsXml.withoutAttachment(game, objective.index()))
        : Optional.empty();
  }

  /** Whether the game option "National Objectives" is on, without which no objective pays. */
  static boolean isOn(final Game game) {
    return MapXml.properties(game).stream()
        .filter(p -> GAME_OPTION.equals(p.getName()))
        .map(Property::getValue)
        .anyMatch("true"::equalsIgnoreCase);
  }

  static Game withOn(final Game game) {
    return isOn(game) ? game : MapXml.withPropertyValue(game, GAME_OPTION, "true");
  }
}
