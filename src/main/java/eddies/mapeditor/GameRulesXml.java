package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
import org.triplea.map.data.elements.DiceSides;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay;
import org.triplea.map.data.elements.GamePlay.Delegate;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.PropertyList;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.map.data.elements.VariableList;
import org.triplea.map.data.elements.VariableList.Variable;
import org.triplea.map.data.elements.VariableList.Variable.Element;

/**
 * The game's rules beyond the map: its options with their types and bounds, the sides of its dice,
 * its variables (lists of names for attachments made "for each" of them) and its delegates. Every
 * change returns a new model, as in {@link MapXml}.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class GameRulesXml {
  private static final String NOTES = "notes";
  private static final String DELEGATES = "games.strategy.triplea.delegate.";

  // ---- options

  /** How TripleA reads an option's value: true or false, a whole number, else text. */
  enum Kind {
    YES_NO,
    NUMBER,
    TEXT
  }

  /**
   * The names of TripleA's options, eg "Low Luck", to offer for a new one: its constants that are
   * words with spaces, as option names are.
   */
  static final List<String> COMMON_OPTIONS =
      Arrays.stream(Constants.class.getFields())
          .filter(f -> f.getType() == String.class && Modifier.isStatic(f.getModifiers()))
          .map(GameRulesXml::constant)
          .filter(name -> name.contains(" "))
          .sorted()
          .toList();

  private static String constant(final Field field) {
    try {
      return (String) field.get(null);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The options players see, eg "Low Luck"; the notes have a tool of their own, and the map's name,
   * by which TripleA finds its pictures, is not to be removed.
   */
  static List<Property> options(final Game game) {
    return MapXml.properties(game).stream()
        .filter(p -> p.getName() != null)
        .filter(p -> !NOTES.equalsIgnoreCase(p.getName()))
        .filter(p -> !Constants.MAP_NAME.equals(p.getName()))
        .toList();
  }

  private static Optional<Property> option(final Game game, final String name) {
    return options(game).stream().filter(p -> name.equals(p.getName())).findFirst();
  }

  /** The value as TripleA reads it: of a "value" child first, else the attribute; "" for none. */
  static String value(final Property option) {
    return Optional.ofNullable(option.getValueProperty())
        .map(Property.Value::getData)
        .or(() -> Optional.ofNullable(option.getValue()))
        .orElse("");
  }

  static Kind kind(final Property option) {
    final String value = value(option).strip();
    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
      return Kind.YES_NO;
    }
    return parseInt(value).isPresent() ? Kind.NUMBER : Kind.TEXT;
  }

  private static Optional<Integer> parseInt(final String value) {
    try {
      return Optional.of(Integer.parseInt(value.strip()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** The least a number option may be: the attribute, else the old "number" child's. */
  static Optional<Integer> min(final Property option) {
    return Optional.ofNullable(option.getMin())
        .or(
            () ->
                Optional.ofNullable(option.getNumberProperty())
                    .map(PropertyList.Property.XmlNumberTag::getMin));
  }

  static Optional<Integer> max(final Property option) {
    return Optional.ofNullable(option.getMax())
        .or(
            () ->
                Optional.ofNullable(option.getNumberProperty())
                    .map(PropertyList.Property.XmlNumberTag::getMax));
  }

  /**
   * The game with the option's value, where the map has it: in its "value" child when it has one,
   * as TripleA reads that first, else in the attribute.
   */
  static Game withOptionValue(final Game game, final String name, final String value) {
    final Optional<Property> option = option(game, name);
    if (option.isPresent() && option.get().getValueProperty() != null) {
      return withOption(
          game,
          name,
          p -> p.toBuilder().valueProperty(Property.Value.builder().data(value).build()).build());
    }
    return MapXml.withPropertyValue(game, name, value);
  }

  /** The game with a new option players may change, of the kind's plain value; none if taken. */
  static Game withNewOption(final Game game, final String name, final Kind kind) {
    if (name.isBlank() || option(game, name).isPresent()) {
      return game;
    }
    final List<Property> options = new ArrayList<>(MapXml.properties(game));
    options.add(
        Property.builder()
            .name(name)
            .value(
                switch (kind) {
                  case YES_NO -> "false";
                  case NUMBER -> "0";
                  case TEXT -> "";
                })
            .editable(true)
            .build());
    return withProperties(game, options);
  }

  static Game withoutOption(final Game game, final String name) {
    return withProperties(
        game, MapXml.properties(game).stream().filter(p -> !name.equals(p.getName())).toList());
  }

  /** The game with players able to change the option on the setup screen. */
  static Game withEditable(final Game game, final String name) {
    return withOption(game, name, p -> p.toBuilder().editable(true).build());
  }

  /** The game with the option fixed to its value. */
  static Game withFixed(final Game game, final String name) {
    return withOption(game, name, p -> p.toBuilder().editable(false).build());
  }

  /**
   * The game with the bounds players may set a number option within; empty for none. Both go into
   * attributes, which TripleA reads first, so an old "number" child is replaced, its bounds kept.
   * Bounds TripleA would not load, the least above the most or the value outside, change nothing.
   */
  static Game withRange(
      final Game game,
      final String name,
      final Optional<Integer> least,
      final Optional<Integer> most) {
    final Optional<Integer> value =
        option(game, name).map(GameRulesXml::value).flatMap(GameRulesXml::parseInt);
    final int low = least.orElse(Integer.MIN_VALUE);
    final int high = most.orElse(Integer.MAX_VALUE);
    if (low > high || value.filter(v -> v < low || v > high).isPresent()) {
      return game;
    }
    return withOption(
        game,
        name,
        p ->
            p.toBuilder()
                .min(least.orElse(null))
                .max(most.orElse(null))
                .numberProperty(null)
                .build());
  }

  private static Game withOption(
      final Game game, final String name, final UnaryOperator<Property> change) {
    return withProperties(
        game,
        MapXml.properties(game).stream()
            .map(p -> name.equals(p.getName()) ? change.apply(p) : p)
            .toList());
  }

  private static Game withProperties(final Game game, final List<Property> properties) {
    return game.toBuilder()
        .propertyList(
            Optional.ofNullable(game.getPropertyList())
                .orElseGet(() -> PropertyList.builder().build())
                .toBuilder()
                .properties(List.copyOf(properties))
                .build())
        .build();
  }

  // ---- dice

  /** The sides of the game's dice, 6 when it says nothing, as TripleA takes it. */
  static int diceSides(final Game game) {
    return Optional.ofNullable(game.getDiceSides()).map(DiceSides::getValue).orElse(6);
  }

  /** The game with dice of so many sides; the same game when they have them already. */
  static Game withDiceSides(final Game game, final int sides) {
    if (Optional.ofNullable(game.getDiceSides())
        .map(DiceSides::getValue)
        .filter(v -> v == sides)
        .isPresent()) {
      return game;
    }
    return game.toBuilder().diceSides(DiceSides.builder().value(sides).build()).build();
  }

  // ---- variables

  private static List<Variable> variables(final Game game) {
    return Optional.ofNullable(game.getVariableList())
        .map(VariableList::getVariables)
        .map(List::<Variable>copyOf)
        .orElse(List.of());
  }

  static List<String> variableNames(final Game game) {
    return variables(game).stream().map(Variable::getName).toList();
  }

  /** The names in the variable, eg territories, in their order. */
  static List<String> elements(final Game game, final String variable) {
    return variables(game).stream()
        .filter(v -> variable.equals(v.getName()))
        .flatMap(v -> Optional.ofNullable(v.getElements()).orElse(List.of()).stream())
        .map(Element::getName)
        .toList();
  }

  /** The game with the variable holding the names, a new one after the others. */
  static Game withVariable(final Game game, final String variable, final List<String> names) {
    if (variable.isBlank()) {
      return game;
    }
    final Variable changed =
        Variable.builder()
            .name(variable)
            .elements(names.stream().map(n -> Element.builder().name(n).build()).toList())
            .build();
    final List<Variable> variables = new ArrayList<>();
    boolean placed = false;
    for (final Variable v : variables(game)) {
      if (!variable.equals(v.getName())) {
        variables.add(v);
      } else if (!placed) {
        variables.add(changed);
        placed = true;
      }
    }
    if (!placed) {
      variables.add(changed);
    }
    return withVariables(game, variables);
  }

  static Game withoutVariable(final Game game, final String variable) {
    return withVariables(
        game, variables(game).stream().filter(v -> !variable.equals(v.getName())).toList());
  }

  private static Game withVariables(final Game game, final List<Variable> variables) {
    return game.toBuilder()
        .variableList(VariableList.builder().variables(List.copyOf(variables)).build())
        .build();
  }

  /**
   * Where the variable is used, eg "triggerAttachment@Nation@ of @Nation@: foreach": as $name$ in
   * attachments or other variables, or as @name@ in attachments made for each of it. Removing a
   * used one would break the game.
   */
  static List<String> whereUsed(final Game game, final String variable) {
    final List<String> uses = List.of("$" + variable + "$", "@" + variable + "@");
    final List<String> places = new ArrayList<>();
    for (final Attachment a :
        Optional.ofNullable(game.getAttachmentList())
            .map(AttachmentList::getAttachments)
            .map(List::<Attachment>copyOf)
            .orElse(List.of())) {
      final String attachment = a.getName() + " of " + a.getAttachTo();
      final Stream<String> texts =
          Stream.concat(
              Stream.of(a.getName(), a.getAttachTo(), a.getForeach()),
              Optional.ofNullable(a.getOptions()).orElse(List.of()).stream()
                  .flatMap(o -> Stream.of(o.getValue(), o.getCount())));
      if (texts.anyMatch(t -> t != null && uses.stream().anyMatch(t::contains))) {
        places.add(attachment);
      }
    }
    variables(game).stream()
        .filter(v -> !variable.equals(v.getName()))
        .filter(v -> elements(game, v.getName()).stream().anyMatch(uses.get(0)::equals))
        .forEach(v -> places.add("variable " + v.getName()));
    return places;
  }

  // ---- delegates

  /**
   * A kind of step TripleA has, eg politics: the delegate's usual name, class and what players see,
   * and the ending of its steps' names, by which TripleA tells eg combat from non-combat move.
   */
  record DelegateKind(
      String name, String javaClass, String display, String what, String ending) {
    @Override
    public String toString() {
      return display + " · " + what;
    }

    /** Whether its step is of the whole round, of no nation, eg the round's end. */
    boolean isOfRound() {
      return ROUND_ENDINGS.contains(ending);
    }
  }

  private static final Set<String> ROUND_ENDINGS =
      Set.of("InitDelegate", "EndRoundStep", "RandomStartDelegate");

  /** TripleA's delegates, as maps name them and their steps. */
  static final List<DelegateKind> DELEGATE_KINDS =
      List.of(
          kind("initDelegate", "InitializationDelegate", "Initializing Delegates", "game start",
              "InitDelegate"),
          kind("bid", "BidPurchaseDelegate", "Bid Purchase", "buy with a bid", "Bid"),
          kind("placeBid", "BidPlaceDelegate", "Bid Placement", "place what a bid bought",
              "BidPlace"),
          kind("tech", "TechnologyDelegate", "Research Technology", "roll for techs", "Tech"),
          kind("tech_activation", "TechActivationDelegate", "Activate Technology", "techs work",
              "TechActivation"),
          kind("purchase", "PurchaseDelegate", "Purchase Units", "buy units", "Purchase"),
          kind("purchaseNoPU", "NoPUPurchaseDelegate", "Purchase Units", "units by territory",
              "Purchase"),
          kind("move", "MoveDelegate", "Combat Move", "move to attack", "CombatMove"),
          kind("move", "MoveDelegate", "Non Combat Move", "move after battles", "NonCombatMove"),
          kind("specialCombatMove", "SpecialMoveDelegate", "Airborne Attack Move", "airborne",
              "AirborneCombatMove"),
          kind("battle", "BattleDelegate", "Combat", "fight battles", "Battle"),
          kind("place", "PlaceDelegate", "Place Units", "place bought units", "Place"),
          kind("placeNoAirCheck", "NoAirCheckPlaceDelegate", "Place Units", "air may not land",
              "Place"),
          kind("politics", "PoliticsDelegate", "Politics", "war and peace", "Politics"),
          kind("userActions", "UserActionDelegate", "Actions", "the nation's actions",
              "UserActions"),
          kind("endTurn", "EndTurnDelegate", "Turn Complete", "income", "EndTurn"),
          kind("endTurnNoPU", "NoPUEndTurnDelegate", "Turn Complete", "no income", "EndTurn"),
          kind("endRound", "EndRoundDelegate", "Round Complete", "victory checks",
              "EndRoundStep"),
          kind("randomStartDelegate", "RandomStartDelegate", "Pregame Nation Placements",
              "territories dealt out", "RandomStartDelegate"));

  private static DelegateKind kind(
      final String name,
      final String javaClass,
      final String display,
      final String what,
      final String ending) {
    return new DelegateKind(name, DELEGATES + javaClass, display, what, ending);
  }

  /**
   * The game with a step of the kind at the index, for the nation unless it is a step of the round.
   * Its delegate is the game's of that class, as TripleA finds some by name, eg "battle"; only
   * without one a delegate is added, under its usual name. The step's name ends as TripleA needs,
   * eg "russiansCombatMove".
   */
  static Game withDelegateStep(
      final Game game, final int index, final DelegateKind kind, @Nullable final String nation) {
    final List<Delegate> delegates =
        new ArrayList<>(
            Optional.ofNullable(game.getGamePlay())
                .map(GamePlay::getDelegates)
                .orElse(List.of()));
    final Optional<Delegate> existing =
        delegates.stream()
            .filter(d -> isClass(d, kind))
            .min(Comparator.comparing((Delegate d) -> !kind.name().equals(d.getName())));
    final Game withDelegate;
    final String name;
    if (existing.isPresent()) {
      withDelegate = game;
      name = existing.get().getName();
    } else {
      name =
          RulesXml.freeName(
              delegates.stream().map(Delegate::getName).collect(Collectors.toSet()), kind.name());
      delegates.add(
          Delegate.builder()
              .name(name)
              .javaClass(kind.javaClass())
              .display(kind.display())
              .build());
      final GamePlay gamePlay =
          Optional.ofNullable(game.getGamePlay()).orElseGet(() -> GamePlay.builder().build());
      withDelegate =
          game.toBuilder()
              .gamePlay(gamePlay.toBuilder().delegates(List.copyOf(delegates)).build())
              .build();
    }
    final String player = kind.isOfRound() ? null : nation;
    final Game added = RulesXml.withStepAdded(withDelegate, index, name, player);
    final Set<String> stepNames =
        RulesXml.steps(withDelegate).stream().map(Step::getName).collect(Collectors.toSet());
    final String prefix =
        player == null ? "game" : lowerFirst(player.replaceAll("[^A-Za-z0-9]", ""));
    final Step step = RulesXml.steps(added).get(index);
    final boolean shownAsDelegate =
        existing.map(d -> kind.display().equals(d.getDisplay())).orElse(true);
    return RulesXml.withStep(
        added,
        index,
        step.toBuilder()
            // a number after the ending would hide it from TripleA: a number before it instead
            .name(freeNameEnding(stepNames, prefix, kind.ending()))
            .display(shownAsDelegate ? step.getDisplay() : kind.display())
            .build());
  }

  private static boolean isClass(final Delegate delegate, final DelegateKind kind) {
    final String javaClass = Optional.ofNullable(delegate.getJavaClass()).orElse("");
    return javaClass
        .substring(javaClass.lastIndexOf('.') + 1)
        .equalsIgnoreCase(kind.javaClass().substring(kind.javaClass().lastIndexOf('.') + 1));
  }

  /** The prefix and ending, eg "russiansBattle", else with a number between, "russians2Battle". */
  private static String freeNameEnding(
      final Set<String> taken, final String prefix, final String ending) {
    String free = prefix + ending;
    for (int i = 2; taken.contains(free); i++) {
      free = prefix + i + ending;
    }
    return free;
  }

  private static String lowerFirst(final String text) {
    return text.isEmpty()
        ? text
        : text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
  }
}
