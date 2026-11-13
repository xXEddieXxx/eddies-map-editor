package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.attachments.RelationshipTypeAttachment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay;
import org.triplea.map.data.elements.GamePlay.Delegate;
import org.triplea.map.data.elements.GamePlay.Sequence;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.GamePlay.Sequence.Step.StepProperty;
import org.triplea.map.data.elements.Initialize;
import org.triplea.map.data.elements.Initialize.RelationshipInitialize;
import org.triplea.map.data.elements.Initialize.RelationshipInitialize.Relationship;
import org.triplea.map.data.elements.PlayerList;
import org.triplea.map.data.elements.PlayerList.Player;
import org.triplea.map.data.elements.RelationshipTypes;
import org.triplea.map.data.elements.RelationshipTypes.RelationshipType;
import org.triplea.map.data.elements.VariableList;
import org.triplea.map.data.elements.VariableList.Variable;
import eddies.mapeditor.MapXml.AttachmentKey;

/**
 * What the rules tools read from and change in a game's XML model: the turn order, the politics
 * (relationship types and how nations start), and whether attachments name a thing. Every change
 * returns a new model, as in {@link MapXml}.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class RulesXml {
  private static final String ARCHETYPE = "archeType";

  // ---- turn order

  /** The steps of a round in their order, eg "germansPurchase". */
  static List<Step> steps(final Game game) {
    return Optional.ofNullable(game.getGamePlay())
        .map(GamePlay::getSequence)
        .map(Sequence::getSteps)
        .map(List::<Step>copyOf)
        .orElse(List.of());
  }

  /** The names of the game's delegates, the kinds of step, eg "purchase". */
  static List<String> delegates(final Game game) {
    return Optional.ofNullable(game.getGamePlay())
        .map(GamePlay::getDelegates)
        .map(List::<Delegate>copyOf)
        .orElse(List.of())
        .stream()
        .map(Delegate::getName)
        .toList();
  }

  /** The game with a new step at the index, its name made from the nation and the delegate. */
  static Game withStepAdded(
      final Game game, final int index, final String delegate, @Nullable final String nation) {
    final List<Step> steps = new ArrayList<>(steps(game));
    steps.add(
        index,
        Step.builder()
            .name(newStepName(steps, delegate, nation))
            .delegate(delegate)
            .player(nation)
            .stepProperties(List.of())
            .build());
    return withSteps(game, steps);
  }

  /** A name no other step has, eg "germansPurchase" or "germansPurchase2". */
  private static String newStepName(
      final List<Step> steps, final String delegate, @Nullable final String nation) {
    final Set<String> taken = steps.stream().map(Step::getName).collect(Collectors.toSet());
    final String base =
        (nation == null ? "" : lowerFirst(nation.replaceAll("[^A-Za-z0-9]", "")))
            + upperFirst(delegate.replaceAll("[^A-Za-z0-9]", ""));
    return freeName(taken, base.isEmpty() ? "step" : lowerFirst(base));
  }

  /** The name, or with a number added if it is taken, eg "buyTank2". */
  static String freeName(final Set<String> taken, final String name) {
    String free = name;
    for (int i = 2; taken.contains(free); i++) {
      free = name + i;
    }
    return free;
  }

  private static String lowerFirst(final String text) {
    return text.isEmpty()
        ? text
        : text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
  }

  static String upperFirst(final String text) {
    return text.isEmpty()
        ? text
        : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
  }

  static Game withoutStep(final Game game, final int index) {
    final List<Step> steps = new ArrayList<>(steps(game));
    steps.remove(index);
    return withSteps(game, steps);
  }

  /** The game with the step at another place in the round. */
  static Game withStepMoved(final Game game, final int from, final int to) {
    final List<Step> steps = new ArrayList<>(steps(game));
    steps.add(to, steps.remove(from));
    return withSteps(game, steps);
  }

  /**
   * The game with count steps from the index moved together, eg a nation's whole turn.
   *
   * @param to where the first of them goes, as an index among the other steps
   */
  static Game withStepsMoved(final Game game, final int from, final int count, final int to) {
    final List<Step> steps = new ArrayList<>(steps(game));
    final List<Step> moved = List.copyOf(steps.subList(from, from + count));
    steps.subList(from, from + count).clear();
    steps.addAll(to, moved);
    return withSteps(game, steps);
  }

  /** The game with the step at the index replaced, eg by one with another nation. */
  static Game withStep(final Game game, final int index, final Step step) {
    final List<Step> steps = new ArrayList<>(steps(game));
    steps.set(index, step);
    return withSteps(game, steps);
  }

  /** The step with the property set, eg "turnSummaryPlayers"; it keeps its place. */
  static Step withStepProperty(final Step step, final String name, final String value) {
    return stepWithProperty(step, name, value);
  }

  static Step withoutStepProperty(final Step step, final String name) {
    return stepWithProperty(step, name, null);
  }

  private static Step stepWithProperty(
      final Step step, final String name, @Nullable final String value) {
    final List<StepProperty> properties = new ArrayList<>();
    boolean placed = false;
    for (final StepProperty property : stepProperties(step)) {
      if (!name.equals(property.getName())) {
        properties.add(property);
      } else if (value != null && !placed) {
        properties.add(StepProperty.builder().name(name).value(value).build());
        placed = true;
      }
    }
    if (value != null && !placed) {
      properties.add(StepProperty.builder().name(name).value(value).build());
    }
    return step.toBuilder().stepProperties(properties).build();
  }

  static List<StepProperty> stepProperties(final Step step) {
    return Optional.ofNullable(step.getStepProperties()).map(List::copyOf).orElse(List.of());
  }

  // ---- politics

  /** The relationship types of the game, eg "War" or "Neutrality". */
  static List<String> relationshipTypes(final Game game) {
    return Optional.ofNullable(game.getRelationshipTypes())
        .map(RelationshipTypes::getRelationshipTypes)
        .map(List::<RelationshipType>copyOf)
        .orElse(List.of())
        .stream()
        .map(RelationshipType::getName)
        .toList();
  }

  /** What the relationship type is at heart: war, allied or neutral; war when not given. */
  static String archetype(final Game game, final String type) {
    return MapXml.option(game, Constants.RELATIONSHIPTYPE_ATTACHMENT_NAME, type, ARCHETYPE)
        .orElse(Constants.RELATIONSHIP_ARCHETYPE_WAR);
  }

  static Game withArchetype(final Game game, final String type, final String archetype) {
    return MapXml.withOption(game, relationshipKey(type), ARCHETYPE, archetype);
  }

  private static AttachmentKey relationshipKey(final String type) {
    return new AttachmentKey(
        Constants.RELATIONSHIPTYPE_ATTACHMENT_NAME,
        RelationshipTypeAttachment.class.getName(),
        "relationship",
        type);
  }

  /**
   * The game with a new relationship type and its attachment of the archetype; nothing changes for
   * a blank or taken name.
   */
  static Game withRelationshipType(final Game game, final String type, final String archetype) {
    if (type.isBlank() || relationshipTypes(game).contains(type)) {
      return game;
    }
    final RelationshipTypes types =
        Optional.ofNullable(game.getRelationshipTypes())
            .orElseGet(() -> RelationshipTypes.builder().relationshipTypes(List.of()).build());
    final List<RelationshipType> added =
        Stream.concat(
                types.getRelationshipTypes().stream(),
                Stream.of(RelationshipType.builder().name(type).build()))
            .toList();
    return withArchetype(
        game.toBuilder()
            .relationshipTypes(types.toBuilder().relationshipTypes(added).build())
            .build(),
        type,
        archetype);
  }

  /**
   * The game without the relationship type: its attachment, and the relationships of it, which go
   * by alliance then.
   */
  static Game withoutRelationshipType(final Game game, final String type) {
    final Game withoutType =
        game.toBuilder()
            .relationshipTypes(
                game.getRelationshipTypes().toBuilder()
                    .relationshipTypes(
                        game.getRelationshipTypes().getRelationshipTypes().stream()
                            .filter(t -> !type.equals(t.getName()))
                            .toList())
                    .build())
            .attachmentList(
                game.getAttachmentList() == null
                    ? null
                    : game.getAttachmentList().toBuilder()
                        .attachments(
                            game.getAttachmentList().getAttachments().stream()
                                .filter(a -> !relationshipKey(type).matches(a))
                                .toList())
                        .build())
            .build();
    return withRelationships(
        withoutType, relationships(game).stream().filter(r -> !type.equals(r.getType())).toList());
  }

  private static List<Relationship> relationships(final Game game) {
    return Optional.ofNullable(game.getInitialize())
        .map(Initialize::getRelationshipInitialize)
        .map(RelationshipInitialize::getRelationships)
        .map(List::<Relationship>copyOf)
        .orElse(List.of());
  }

  /** The type the two nations start in, either way round; empty when it goes by alliance. */
  static Optional<String> relationship(final Game game, final String nation, final String other) {
    return relationships(game).stream()
        .filter(r -> isPair(r, nation, other))
        .map(Relationship::getType)
        .reduce((first, last) -> last);
  }

  private static boolean isPair(final Relationship r, final String nation, final String other) {
    return (nation.equals(r.getPlayer1()) && other.equals(r.getPlayer2()))
        || (nation.equals(r.getPlayer2()) && other.equals(r.getPlayer1()));
  }

  /** The game with the two nations starting in the relationship type. */
  static Game withRelationship(
      final Game game, final String nation, final String other, final String type) {
    return gameWithRelationship(game, nation, other, type);
  }

  /** The game with the two nations going by alliance: allied in one alliance, else at war. */
  static Game withoutRelationship(final Game game, final String nation, final String other) {
    return gameWithRelationship(game, nation, other, null);
  }

  private static Game gameWithRelationship(
      final Game game, final String nation, final String other, @Nullable final String type) {
    final List<Relationship> relationships = new ArrayList<>();
    boolean placed = false;
    for (final Relationship relationship : relationships(game)) {
      if (!isPair(relationship, nation, other)) {
        relationships.add(relationship);
      } else if (type != null && !placed) {
        relationships.add(relationship.toBuilder().type(type).build());
        placed = true;
      }
    }
    if (type != null && !placed) {
      relationships.add(
          Relationship.builder().player1(nation).player2(other).type(type).roundValue(1).build());
    }
    return withRelationships(game, relationships);
  }

  private static Game withRelationships(final Game game, final List<Relationship> relationships) {
    final Initialize initialize =
        Optional.ofNullable(game.getInitialize()).orElseGet(() -> Initialize.builder().build());
    return game.toBuilder()
        .initialize(
            initialize.toBuilder()
                .relationshipInitialize(
                    Optional.ofNullable(initialize.getRelationshipInitialize())
                        .orElseGet(() -> RelationshipInitialize.builder().build())
                        .toBuilder()
                        .relationships(relationships)
                        .build())
                .build())
        .build();
  }

  /**
   * The relationship type's option, eg "canMoveThroughCanals": "true", "false" or "default", which
   * goes by its archetype; "default" also when not given.
   */
  static String relationshipOption(final Game game, final String type, final String option) {
    return MapXml.option(game, Constants.RELATIONSHIPTYPE_ATTACHMENT_NAME, type, option)
        .orElse(Constants.RELATIONSHIP_PROPERTY_DEFAULT);
  }

  /** The game with the type's option set; "default" takes it out, so the archetype decides. */
  static Game withRelationshipOption(
      final Game game, final String type, final String option, final String value) {
    if (value.equals(relationshipOption(game, type, option))) {
      return game;
    }
    return MapXml.withOption(
        game,
        relationshipKey(type),
        option,
        Constants.RELATIONSHIP_PROPERTY_DEFAULT.equals(value) ? null : value);
  }

  /** How many pairs of nations start in the type; a pair given twice, either way round, once. */
  static int pairsOf(final Game game, final String type) {
    return (int)
        relationships(game).stream()
            .filter(r -> type.equals(r.getType()))
            .map(r -> Stream.of(r.getPlayer1(), r.getPlayer2()).sorted().toList())
            .distinct()
            .count();
  }

  /** All options of the type's relationshipTypeAttachment in their order, its archetype too. */
  static List<Option> relationshipOptions(final Game game, final String type) {
    return AttachmentsXml.attachments(game).stream()
        .filter(relationshipKey(type)::matches)
        .findFirst()
        .map(AttachmentsXml::options)
        .orElse(List.of());
  }

  // ---- nations: how they play and their own rules

  /** The nation as the player list gives it: optional, can be disabled, played by, hidden. */
  static Player player(final Game game, final String nation) {
    return Optional.ofNullable(game.getPlayerList())
        .map(PlayerList::getPlayers)
        .orElse(List.of())
        .stream()
        .filter(p -> nation.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No nation " + nation));
  }

  /** The game with the nation's entry in the player list changed; it keeps its place. */
  static Game withPlayer(
      final Game game, final String nation, final UnaryOperator<Player.PlayerBuilder> change) {
    final Player before = player(game, nation);
    final Player changed =
        change
            .apply(
                Player.builder()
                    .name(before.getName())
                    .optional(before.getOptional())
                    .canBeDisabled(before.getCanBeDisabled())
                    .defaultType(before.getDefaultType())
                    .isHidden(before.getIsHidden()))
            .build();
    return game.toBuilder()
        .playerList(
            game.getPlayerList().toBuilder()
                .players(
                    game.getPlayerList().getPlayers().stream()
                        .map(p -> p == before ? changed : p)
                        .toList())
                .build())
        .build();
  }

  /** The option of the nation's playerAttachment, eg "retainCapitalNumber". */
  static Optional<String> playerOption(final Game game, final String nation, final String option) {
    return MapXml.option(game, Constants.PLAYER_ATTACHMENT_NAME, nation, option);
  }

  /** The game with the nation's option set; its playerAttachment is added when missing. */
  static Game withPlayerOption(
      final Game game, final String nation, final String option, final String value) {
    return playerOption(game, nation, option).filter(value::equals).isPresent()
        ? game
        : MapXml.withOption(game, playerKey(nation), option, value);
  }

  /** The game without the nation's option, so the game's default applies. */
  static Game withoutPlayerOption(final Game game, final String nation, final String option) {
    return playerOption(game, nation, option).isEmpty()
        ? game
        : MapXml.withOption(game, playerKey(nation), option, null);
  }

  /** All options of the nation's playerAttachment in their order. */
  static List<Option> playerOptions(final Game game, final String nation) {
    return AttachmentsXml.attachments(game).stream()
        .filter(playerKey(nation)::matches)
        .findFirst()
        .map(AttachmentsXml::options)
        .orElse(List.of());
  }

  /**
   * The nations a list option of the nation names, eg shareTechnology; TripleA adds up an option
   * given more than once, so all are read.
   */
  static List<String> playerNames(final Game game, final String nation, final String option) {
    return playerOptions(game, nation).stream()
        .filter(o -> AttachmentsXml.isOption(o, option) && o.getValue() != null)
        .flatMap(o -> Arrays.stream(o.getValue().split(":")))
        .map(String::strip)
        .filter(n -> !n.isEmpty())
        .toList();
  }

  /**
   * The game with the list option naming these nations in one value at the place of its first;
   * none takes it out.
   */
  static Game withPlayerNames(
      final Game game, final String nation, final String option, final List<String> names) {
    if (playerNames(game, nation, option).equals(names)) {
      return game;
    }
    final Game without = MapXml.withOption(game, playerKey(nation), option, null);
    if (names.isEmpty()) {
      return without;
    }
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    for (int i = 0; i < attachments.size(); i++) {
      if (playerKey(nation).matches(attachments.get(i))) {
        final List<Option> options = new ArrayList<>();
        boolean placed = false;
        for (final Option o : AttachmentsXml.options(attachments.get(i))) {
          if (!AttachmentsXml.isOption(o, option)) {
            options.add(o);
          } else if (!placed) {
            options.add(
                Option.builder().name(o.getName()).value(String.join(":", names)).build());
            placed = true;
          }
        }
        if (!placed) {
          options.add(Option.builder().name(option).value(String.join(":", names)).build());
        }
        return AttachmentsXml.withAttachment(
            game, i, attachments.get(i).toBuilder().options(List.copyOf(options)).build());
      }
    }
    return MapXml.withOption(without, playerKey(nation), option, String.join(":", names));
  }

  /** The game with one more option of the nation, also one it has, eg a second placementLimit. */
  static Game withPlayerOptionAdded(
      final Game game, final String nation, final String option, final String value) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    for (int i = 0; i < attachments.size(); i++) {
      if (playerKey(nation).matches(attachments.get(i))) {
        return AttachmentsXml.withAttachment(
            game,
            i,
            AttachmentsXml.withNewOption(
                attachments.get(i), Option.builder().name(option).value(value).build()));
      }
    }
    return MapXml.withOption(game, playerKey(nation), option, value);
  }

  private static AttachmentKey playerKey(final String nation) {
    return new AttachmentKey(
        Constants.PLAYER_ATTACHMENT_NAME, PlayerAttachment.class.getName(), "player", nation);
  }

  /**
   * The game with the nation's option at this row of {@link #playerOptions} given the value, eg
   * one of two placementLimits; its count stays.
   */
  static Game withPlayerOptionAt(
      final Game game, final String nation, final int row, final String value) {
    return withPlayerAttachmentAt(
        game,
        nation,
        row,
        a ->
            AttachmentsXml.withOption(
                a, row, AttachmentsXml.options(a).get(row).toBuilder().value(value).build()));
  }

  /** The game without the nation's option at this row of {@link #playerOptions}. */
  static Game withoutPlayerOptionAt(final Game game, final String nation, final int row) {
    return withPlayerAttachmentAt(game, nation, row, a -> AttachmentsXml.withoutOption(a, row));
  }

  // ---- stacking limits, of a unit type or of a nation's group of unit types

  /** TripleA's limits of units per territory: when placing, when moving in, when attacking. */
  static final List<String> STACKING_LIMITS =
      List.of("placementLimit", "movementLimit", "attackingLimit");

  /** Whose units a limit counts, as TripleA reads it: the mover's, its allies' too, or all. */
  static final List<String> STACKING_COUNTS = List.of("owned", "allied", "total");

  /**
   * At most so many units in a territory, as TripleA reads it (UnitAttachment.parseStackingLimit,
   * PlayerAttachment.parseUnitLimit): "count:owned|allied|total" for a unit type, followed by
   * ":unit:unit…" or ":all" for a nation's group of unit types, which count together.
   *
   * @param units the group's unit types, or "all"; none for a unit type's own limit
   */
  record StackingLimit(int most, String counting, List<String> units) {

    /** The limit the option gives, its count and value joined; empty when TripleA would not read it. */
    static Optional<StackingLimit> read(final Option option) {
      final String[] parts = TriggerXml.joined(option).strip().split(":");
      if (parts.length < 2 || !STACKING_COUNTS.contains(parts[1])) {
        return Optional.empty();
      }
      try {
        final int most = Integer.parseInt(parts[0]);
        return most < 0
            ? Optional.empty()
            : Optional.of(
                new StackingLimit(
                    most, parts[1], List.of(parts).subList(2, parts.length)));
      } catch (final NumberFormatException e) {
        return Optional.empty();
      }
    }

    String written() {
      return most + ":" + counting + (units.isEmpty() ? "" : ":" + String.join(":", units));
    }
  }

  /** Whether the nation's option is a limit for a group of unit types that reads. */
  static boolean isGroupLimit(final Option option) {
    return STACKING_LIMITS.stream().anyMatch(name -> AttachmentsXml.isOption(option, name))
        && StackingLimit.read(option).filter(limit -> !limit.units().isEmpty()).isPresent();
  }

  /**
   * The game with the nation's option at this row of {@link #playerOptions} replaced by this name
   * and value, eg a placementLimit made a movementLimit; a count it had goes into the value.
   */
  static Game withPlayerOptionAt(
      final Game game, final String nation, final int row, final String name, final String value) {
    return withPlayerAttachmentAt(
        game,
        nation,
        row,
        a -> AttachmentsXml.withOption(a, row, Option.builder().name(name).value(value).build()));
  }

  // the game unchanged when the nation has no option at the row
  private static Game withPlayerAttachmentAt(
      final Game game,
      final String nation,
      final int row,
      final UnaryOperator<Attachment> change) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    for (int i = 0; i < attachments.size(); i++) {
      if (playerKey(nation).matches(attachments.get(i))) {
        return row >= 0 && row < AttachmentsXml.options(attachments.get(i)).size()
            ? AttachmentsXml.withAttachment(game, i, change.apply(attachments.get(i)))
            : game;
      }
    }
    return game;
  }

  // ---- a nation's rules for placing and moving, its rulesAttachment

  /** All options of the nation's rulesAttachment, where the game looks for its rules. */
  static List<Option> nationRules(final Game game, final String nation) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    final int at = nationRulesAt(attachments, nation);
    return at < 0 ? List.of() : AttachmentsXml.options(attachments.get(at));
  }

  /** The rule as the game reads it, its count and value joined, eg "4:Peasant"; the first. */
  static Optional<String> nationRule(final Game game, final String nation, final String option) {
    return nationRules(game, nation).stream()
        .filter(o -> AttachmentsXml.isOption(o, option))
        .map(TriggerXml::joined)
        .findFirst();
  }

  /**
   * The game with the nation's rule set where it is, else after its other rules; the nation's
   * rulesAttachment is added when missing. What else the attachment has stays.
   */
  static Game withNationRule(
      final Game game, final String nation, final String option, final String value) {
    return withNationRule(game, nation, option, "", value);
  }

  /** As withNationRule, with the count map makers write beside the value, eg count="4". */
  static Game withNationRule(
      final Game game,
      final String nation,
      final String option,
      final String count,
      final String value) {
    final String joined = count.isEmpty() ? value : count + ":" + value;
    if (nationRule(game, nation, option).filter(joined::equals).isPresent()) {
      return game;
    }
    final Option rule =
        Option.builder().name(option).value(value).count(count.isEmpty() ? null : count).build();
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    final int at = nationRulesAt(attachments, nation);
    if (at < 0) {
      final List<Attachment> added = new ArrayList<>(attachments);
      added.add(
          AttachmentsXml.withNewOption(
              AttachmentsXml.newAttachment(
                  TriggerXml.CONDITION, Constants.RULES_ATTACHMENT_NAME, nation),
              rule));
      return AttachmentsXml.withAttachments(game, added);
    }
    final Attachment rules = attachments.get(at);
    final List<Option> options = AttachmentsXml.options(rules);
    for (int row = 0; row < options.size(); row++) {
      if (AttachmentsXml.isOption(options.get(row), option)) {
        // the map's spelling of the name stays, eg an old capital first letter
        return AttachmentsXml.withAttachment(
            game,
            at,
            AttachmentsXml.withOption(
                rules, row, rule.toBuilder().name(options.get(row).getName()).build()));
      }
    }
    return AttachmentsXml.withAttachment(game, at, AttachmentsXml.withNewOption(rules, rule));
  }

  /** The game without the nation's rule, so the game's default applies. */
  static Game withoutNationRule(final Game game, final String nation, final String option) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    final int at = nationRulesAt(attachments, nation);
    if (at < 0 || nationRule(game, nation, option).isEmpty()) {
      return game;
    }
    final Attachment rules = attachments.get(at);
    return AttachmentsXml.withAttachment(
        game,
        at,
        rules.toBuilder()
            .options(
                AttachmentsXml.options(rules).stream()
                    .filter(o -> !AttachmentsXml.isOption(o, option))
                    .toList())
            .build());
  }

  // of two on a nation the game keeps the last read
  private static int nationRulesAt(final List<Attachment> attachments, final String nation) {
    return AttachmentsXml.lastIndexOf(attachments, Constants.RULES_ATTACHMENT_NAME, nation);
  }

  // ---- names

  /**
   * Whether an attachment is attached to the name or one of its options names it, eg a trigger that
   * changes a nation's production frontier: removing such a thing would break the game.
   */
  static boolean isNamedInAttachments(final Game game, final String name) {
    return !whereNamed(game, name).isEmpty();
  }

  /**
   * Where attachments or variables name it, eg "triggerAttachment1 of Germans: players", for the
   * map maker to change there first.
   */
  static List<String> whereNamed(final Game game, final String name) {
    final List<String> places = new ArrayList<>();
    for (final Attachment a :
        Optional.ofNullable(game.getAttachmentList())
            .map(AttachmentList::getAttachments)
            .map(List::<Attachment>copyOf)
            .orElse(List.of())) {
      final String attachment = a.getName() + " of " + a.getAttachTo();
      if (name.equals(a.getAttachTo())) {
        places.add(attachment);
      }
      Optional.ofNullable(a.getOptions()).orElse(List.of()).stream()
          .filter(o -> o.getValue() != null)
          // values list names joined by colons, eg "production:buyTank", "-" before one taken away
          .filter(
              o ->
                  Arrays.stream(o.getValue().split(":"))
                      .map(part -> part.startsWith("-") ? part.substring(1) : part)
                      .anyMatch(name::equals))
          .forEach(o -> places.add(attachment + ": " + o.getName()));
    }
    Optional.ofNullable(game.getVariableList())
        .map(VariableList::getVariables)
        .map(List::<Variable>copyOf)
        .orElse(List.of())
        .stream()
        .filter(
            v ->
                Optional.ofNullable(v.getElements()).orElse(List.of()).stream()
                    .anyMatch(e -> name.equals(e.getName())))
        .forEach(v -> places.add("variable " + v.getName()));
    return places;
  }

  /**
   * The game without the name in the steps' lists of nations, eg turnSummaryPlayers; a list left
   * empty goes. The engine reads those lists only while playing, so they are safe to shorten.
   */
  static Game withoutNameInStepProperties(final Game game, final String name) {
    if (game.getGamePlay() == null) {
      return game;
    }
    return withSteps(
        game,
        steps(game).stream()
            .map(
                step -> {
                  Step changed = step;
                  for (final StepProperty property : stepProperties(step)) {
                    final List<String> names =
                        List.of(Objects.requireNonNullElse(property.getValue(), "").split(":"));
                    if (names.contains(name)) {
                      final String rest =
                          String.join(":", names.stream().filter(n -> !name.equals(n)).toList());
                      changed =
                          rest.isEmpty()
                              ? withoutStepProperty(changed, property.getName())
                              : withStepProperty(changed, property.getName(), rest);
                    }
                  }
                  return changed;
                })
            .toList());
  }

  /**
   * What the game adds to its rounds' numbers, gamePlay's offset: with 65 the first round is round
   * 66, in the history and for conditions that name rounds (GameSequence.getRound).
   */
  static int roundOffset(final Game game) {
    return Optional.ofNullable(game.getGamePlay())
        .map(GamePlay::getOffset)
        .map(GamePlay.Offset::getRound)
        .orElse(0);
  }

  /** The game with the offset; the same game when it counts its rounds so already. */
  static Game withRoundOffset(final Game game, final int offset) {
    if (offset == roundOffset(game)) {
      return game;
    }
    final GamePlay gamePlay =
        Optional.ofNullable(game.getGamePlay()).orElseGet(() -> GamePlay.builder().build());
    return game.toBuilder()
        .gamePlay(
            gamePlay.toBuilder().offset(GamePlay.Offset.builder().round(offset).build()).build())
        .build();
  }

  private static Game withSteps(final Game game, final List<Step> steps) {
    final GamePlay gamePlay =
        Optional.ofNullable(game.getGamePlay()).orElseGet(() -> GamePlay.builder().build());
    final Sequence sequence =
        Optional.ofNullable(gamePlay.getSequence()).orElseGet(() -> Sequence.builder().build());
    return game.toBuilder()
        .gamePlay(
            gamePlay.toBuilder().sequence(sequence.toBuilder().steps(List.copyOf(steps)).build())
                .build())
        .build();
  }
}
