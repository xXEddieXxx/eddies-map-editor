package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.RelationshipTypeAttachment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay;
import org.triplea.map.data.elements.GamePlay.Delegate;
import org.triplea.map.data.elements.GamePlay.Sequence;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.GamePlay.Sequence.Step.StepProperty;
import org.triplea.map.data.elements.Initialize;
import org.triplea.map.data.elements.Initialize.RelationshipInitialize;
import org.triplea.map.data.elements.Initialize.RelationshipInitialize.Relationship;
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
