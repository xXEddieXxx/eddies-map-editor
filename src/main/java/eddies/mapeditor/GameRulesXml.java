package eddies.mapeditor;

import static java.util.Map.entry;

import games.strategy.triplea.Constants;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
   * and the ending of its steps' names, by which TripleA tells eg combat from non-combat move; what
   * happens in it in plain words, and whether maps commonly have it.
   */
  record DelegateKind(
      String name,
      String javaClass,
      String display,
      String what,
      String ending,
      boolean common) {
    @Override
    public String toString() {
      return what;
    }

    /** Whether its step is of the whole round, of no nation, eg the round's end. */
    boolean isOfRound() {
      return ROUND_ENDINGS.contains(ending);
    }
  }

  private static final Set<String> ROUND_ENDINGS =
      Set.of("InitDelegate", "EndRoundStep", "RandomStartDelegate");

  // the name TripleA looks its technology delegate up by (GameData.getTechDelegate)
  static final String TECH_DELEGATE = "tech";

  /** TripleA's delegates, as maps name them and their steps. */
  static final List<DelegateKind> DELEGATE_KINDS =
      List.of(
          kind("initDelegate", "InitializationDelegate", "Initializing Delegates",
              "Prepare the game", "InitDelegate"),
          kind("bid", "BidPurchaseDelegate", "Bid Purchase", "Buy with a bid", "Bid"),
          kind("placeBid", "BidPlaceDelegate", "Bid Placement", "Place what a bid bought",
              "BidPlace"),
          kind("tech", "TechnologyDelegate", "Research Technology", "Roll for technology",
              "Tech"),
          kind("tech_activation", "TechActivationDelegate", "Activate Technology",
              "Technology takes effect", "TechActivation"),
          kind("purchase", "PurchaseDelegate", "Purchase Units", "Buy units", "Purchase"),
          rareKind("purchaseNoPU", "NoPUPurchaseDelegate", "Purchase Units",
              "Buy units by territory", "Purchase"),
          kind("move", "MoveDelegate", "Combat Move", "Move to attack", "CombatMove"),
          kind("move", "MoveDelegate", "Non Combat Move", "Move after battles", "NonCombatMove"),
          kind("specialCombatMove", "SpecialMoveDelegate", "Airborne Attack Move",
              "Airborne attack", "AirborneCombatMove"),
          kind("battle", "BattleDelegate", "Combat", "Fight battles", "Battle"),
          kind("place", "PlaceDelegate", "Place Units", "Place bought units", "Place"),
          rareKind("placeNoAirCheck", "NoAirCheckPlaceDelegate", "Place Units",
              "Place, air may not land", "Place"),
          kind("politics", "PoliticsDelegate", "Politics", "War and peace", "Politics"),
          kind("userActions", "UserActionDelegate", "Actions", "Actions", "UserActions"),
          kind("endTurn", "EndTurnDelegate", "Turn Complete", "Collect income", "EndTurn"),
          rareKind("endTurnNoPU", "NoPUEndTurnDelegate", "Turn Complete", "No income",
              "EndTurn"),
          kind("endRound", "EndRoundDelegate", "Round Complete", "End the round",
              "EndRoundStep"),
          rareKind("randomStartDelegate", "RandomStartDelegate", "Pregame Nation Placements",
              "Pregame nation placements", "RandomStartDelegate"));

  private static DelegateKind kind(
      final String name,
      final String javaClass,
      final String display,
      final String what,
      final String ending) {
    return new DelegateKind(name, DELEGATES + javaClass, display, what, ending, true);
  }

  private static DelegateKind rareKind(
      final String name,
      final String javaClass,
      final String display,
      final String what,
      final String ending) {
    return new DelegateKind(name, DELEGATES + javaClass, display, what, ending, false);
  }

  /** What the option does in one line, for those of TripleA the table below knows. */
  static Optional<String> optionHelp(final String option) {
    return Optional.ofNullable(OPTION_HELP.get(option));
  }

  /** What every "<nation> bid" option is. */
  static final String BID_HELP =
      "PUs a nation gets before the game starts, to buy and place units with.";

  /**
   * What the game options TripleA reads do, by their names in its Constants, one line each: only
   * those whose effect is plain from TripleA's code and rule books; the others get no "?".
   */
  private static final Map<String, String> OPTION_HELP =
      Map.ofEntries(
          entry("Low Luck", "Strength is added up: each full die of it is a sure hit, only the"
              + " rest is rolled."),
          entry("Low Luck for AntiAircraft", "Anti-air fire uses low luck too."),
          entry("Low Luck for Technology", "Research rolls use low luck too."),
          entry("Low Luck for Bombing and Territory Damage",
              "Bombing damage uses low luck: less of it is chance."),
          entry("Use Triggers", "Triggers fire. Off, none does."),
          entry("Use Politics", "Nations can change their relationships with political actions."),
          entry("National Objectives", "Objectives pay their PUs. Off, none counts."),
          entry("Triggered Victory", "Triggers can end the game with a winner."),
          entry("Tech Development", "Nations can research technologies."),
          entry("Selectable Tech Roll", "A nation chooses the technology it researches."),
          entry("WW2V3 Tech Model", "Research buys tokens that roll each turn until a technology"
              + " is found."),
          entry("WW2V2", "The rules of the second edition (Revised)."),
          entry("WW2V3", "The rules of the third edition (Anniversary)."),
          entry("Two hit battleship", "Battleships take two hits to sink."),
          entry("Units Repair Hits End Turn",
              "Damaged units are repaired at the end of their owner's turn."),
          entry("Units Repair Hits Start Turn",
              "Damaged units are repaired at the start of their owner's turn."),
          entry("Neutrals Are Impassable", "No unit may enter a neutral territory."),
          entry("Neutrals Are Blitzable", "Units that blitz may pass through neutral territories."),
          entry("Neutral Flyover Allowed", "Planes may fly over neutral territories."),
          entry("Random AA Casualties", "Planes hit by anti-air fire are picked at random."),
          entry("Choose AA Casualties", "The attacker chooses the planes anti-air fire takes."),
          entry("Roll AA Individually", "Anti-air fire rolls for each plane on its own."),
          entry("Always on AA", "Anti-air guns also fire at planes flying over in non-combat"
              + " moves."),
          entry("Multiple AA Per Territory", "A territory may hold more than one anti-air gun."),
          entry("Placement Restricted By Factory",
              "A factory places at most as many units as its territory is worth."),
          entry("Place in Any Territory", "Nations with the rule to place in any land they own"
              + " (Nations) may place without a factory."),
          entry("Unit Placement In Enemy Seas",
              "New ships may be placed in sea zones with enemy units."),
          entry("Unit Placement Restrictions", "Units keep to where their unit type may be"
              + " placed (Units)."),
          entry("Unit Placement Per Territory Restricted", "A nation's rule may stop it placing"
              + " where it has many units already (Nations)."),
          entry("Unlimited Constructions", "No limit on constructions per territory."),
          entry("Submersible Subs", "Submarines may submerge instead of retreating."),
          entry("Sub Retreat Before Battle", "Submarines may leave before a battle starts."),
          entry("Defending Subs Sneak Attack", "Defending submarines strike first too."),
          entry("Air Attack Sub Restricted",
              "Planes hit submarines only with a destroyer of their side in the battle."),
          entry("Ignore Sub In Movement",
              "Ships may move through sea zones that hold only enemy submarines."),
          entry("Ignore Transport In Movement",
              "Ships may move through sea zones that hold only enemy transports."),
          entry("Transport Casualties Restricted",
              "Transports do not fight and are taken as the last casualties."),
          entry("Partial Amphibious Retreat",
              "In a landing, units that came over land may retreat."),
          entry("Naval Bombard Casualties Return Fire",
              "Units hit by a shore bombardment still fire back."),
          entry("Shore Bombard Per Ground Unit Restricted",
              "One ship bombards for each land unit that lands."),
          entry("Kamikaze Airplanes", "Planes may attack without the moves to land again."),
          entry("Scramble Rules In Effect",
              "Planes at an air base may scramble to defend a territory under attack nearby."),
          entry("Scramble To Sea Only", "Planes scramble only into sea zones."),
          entry("Scramble From Island Only", "Only planes on islands scramble."),
          entry("Scrambled Units Return To Base",
              "Scrambled planes must land where they came from."),
          entry("Multiply PUs", "Every income is multiplied by this number."),
          entry("Relationships Last Extra Rounds",
              "Extra rounds a relationship lasts before it may change again."),
          entry("Alliances Can Chain Together", "The allies of an ally count as allies."),
          entry("Air Battle Rounds", "Rounds an air battle lasts at most; -1 for no limit."),
          entry("Sea Battle Rounds", "Rounds a sea battle lasts at most; -1 for no limit."),
          entry("Land Battle Rounds", "Rounds a land battle lasts at most; -1 for no limit."),
          entry("Raids May Be Preceeded By Air Battles",
              "Interceptors and escorts fight an air battle before a bombing raid."),
          entry("Use Fuel Cost", "Moving a unit costs the fuel its unit type names."),
          entry("Damage From Bombing Done To Units Instead Of Territories",
              "Bombing damages the unit bombed, eg a factory, not its territory."),
          entry("Limit SBR Damage To Factory Production",
              "Bombing damage is at most what the territory is worth."),
          entry("All Rockets Attack", "Every rocket launcher may fire each turn, not only one."),
          entry("Heavy Bomber Dice Rolls", "Dice a heavy bomber rolls."),
          entry("LHTR Heavy Bombers", "Heavy bombers roll their dice and take the best one."),
          entry("Contested Territories Produce No Income",
              "A territory with enemy units in it gives no income."),
          entry("Territories Are Assigned Randomly",
              "Territories are dealt to the nations at random when the game starts."),
          entry("Retreating Units Remain In Place",
              "Units that retreat stay in the territory instead of moving out."),
          entry("Units Can Load In Hostile Sea Zones",
              "Transports may load in sea zones with enemy units."),
          entry("Subs Can End NonCombat Move With Enemies",
              "Submarines may end a non-combat move in a sea zone with enemy ships."),
          entry("Paratroopers Can Move During Non Combat",
              "Paratroopers may also be flown in non-combat moves."),
          entry("Airborne Attacks Only In Existing Battles",
              "Airborne units may only join battles that other units started."),
          entry("Airborne Attacks Only In Enemy Territories",
              "Airborne units may only attack enemy territories."),
          entry("Disabled Players Assets Deleted",
              "The units and land of a nation switched off before the game are removed."),
          entry("Convoy Blockades Roll Dice For Cost",
              "Blockading ships roll dice for the PUs they cut."),
          entry("Selectable Zero Movement Units", "Units that cannot move can still be selected."),
          entry("Units May Give Bonus Movement",
              "Units may give others extra movement, eg an air base to planes."));

  /**
   * The kind of the step: of its delegate's class, and of several (combat and non-combat move) the
   * one whose ending its name has, as TripleA tells them by the name; empty for a delegate of a
   * class TripleA's maps do not commonly name, or a step whose delegate is missing.
   */
  static Optional<DelegateKind> kindOf(final Game game, final Step step) {
    final List<DelegateKind> ofClass =
        delegate(game, step.getDelegate())
            .map(d -> DELEGATE_KINDS.stream().filter(k -> isClass(d, k)).toList())
            .orElse(List.of());
    return ofClass.stream()
        .filter(k -> Optional.ofNullable(step.getName()).orElse("").endsWith(k.ending()))
        .max(Comparator.comparingInt(k -> k.ending().length()))
        .or(() -> ofClass.stream().findFirst());
  }

  // the delegates TripleA runs for no nation; every other asks for its step's nation and fails
  // without one ("gamePlayer is null")
  static final Set<String> ROUND_CLASSES =
      Set.of("InitializationDelegate", "EndRoundDelegate", "RandomStartDelegate");

  /** Whether the step's delegate, one the game has, asks for the step's nation. */
  static boolean needsNation(final Game game, final Step step) {
    return delegate(game, step.getDelegate())
        .map(Delegate::getJavaClass)
        .filter(java.util.Objects::nonNull)
        .filter(name -> !ROUND_CLASSES.contains(name.substring(name.lastIndexOf('.') + 1)))
        .isPresent();
  }

  /** Whether the step has no nation though its delegate asks for one. */
  static boolean lacksNation(final Game game, final Step step) {
    return (step.getPlayer() == null || step.getPlayer().isBlank()) && needsNation(game, step);
  }

  private static Optional<Delegate> delegate(final Game game, @Nullable final String name) {
    return Optional.ofNullable(game.getGamePlay())
        .map(GamePlay::getDelegates)
        .orElse(List.of())
        .stream()
        .filter(d -> d.getName() != null && d.getName().equals(name))
        .findFirst();
  }

  /** The step's name in the game: its own display, else its delegate's, else its name. */
  static String shownName(final Game game, final Step step) {
    return Optional.ofNullable(step.getDisplay())
        .or(() -> delegate(game, step.getDelegate()).map(Delegate::getDisplay))
        .orElse(step.getName());
  }

  /**
   * The game with the step at the index of the kind: the game's delegate of that class (added if
   * missing, as by {@link #withDelegateStep}), and a name with the kind's ending, as TripleA tells
   * by it what a step does, eg "germansPurchase" becomes "germansBattle". A display that was the
   * old kind's goes, one of the map's own stays. A step of the round made a nation's gets a nation
   * ({@link #playerFor}). Unchanged when the step is of the kind already.
   */
  static Game withStepKind(final Game game, final int index, final DelegateKind kind) {
    final Step step = RulesXml.steps(game).get(index);
    final Optional<DelegateKind> old = kindOf(game, step);
    if (old.filter(kind::equals).isPresent()) {
      return game;
    }
    final DelegateOf delegate = withDelegate(game, kind);
    final String name = Optional.ofNullable(step.getName()).orElse("");
    final String oldEnding =
        DELEGATE_KINDS.stream()
            .map(DelegateKind::ending)
            .filter(name::endsWith)
            .max(Comparator.comparingInt(String::length))
            .orElse("");
    final Set<String> taken =
        RulesXml.steps(game).stream()
            .map(Step::getName)
            .filter(n -> !name.equals(n))
            .collect(Collectors.toSet());
    final boolean ownDisplay =
        step.getDisplay() != null
            && !old.map(DelegateKind::display).filter(step.getDisplay()::equals).isPresent();
    return RulesXml.withStep(
        delegate.game(),
        index,
        step.toBuilder()
            .delegate(delegate.name())
            .player(kind.isOfRound() ? null : playerFor(game, index))
            .name(
                oldEnding.equals(kind.ending())
                    ? name
                    : freeNameEnding(
                        taken, name.substring(0, name.length() - oldEnding.length()), kind.ending()))
            .display(
                ownDisplay
                    ? step.getDisplay()
                    : delegate.shownAsKind() ? null : kind.display())
            .build());
  }

  /**
   * The step's nation; for a step of the round, which has none, the nation of the band it sits in
   * (the nation step before it), else the first nation, as TripleA cannot play a nation's step
   * without one.
   */
  @Nullable
  private static String playerFor(final Game game, final int index) {
    final List<Step> steps = RulesXml.steps(game);
    for (int i = index; i >= 0; i--) {
      if (steps.get(i).getPlayer() != null) {
        return steps.get(i).getPlayer();
      }
    }
    return MapXml.nations(game).stream().findFirst().orElse(null);
  }

  /**
   * The game with a step of the kind at the index, for the nation unless it is a step of the round.
   * Its delegate is the game's of that class, as TripleA finds some by name, eg "battle"; only
   * without one a delegate is added, under its usual name. The step's name ends as TripleA needs,
   * eg "russiansCombatMove".
   */
  static Game withDelegateStep(
      final Game game, final int index, final DelegateKind kind, @Nullable final String nation) {
    final DelegateOf delegate = withDelegate(game, kind);
    final String player = kind.isOfRound() ? null : nation;
    final Game added = RulesXml.withStepAdded(delegate.game(), index, delegate.name(), player);
    final Set<String> stepNames =
        RulesXml.steps(delegate.game()).stream().map(Step::getName).collect(Collectors.toSet());
    final String prefix =
        player == null ? "game" : lowerFirst(player.replaceAll("[^A-Za-z0-9]", ""));
    final Step step = RulesXml.steps(added).get(index);
    return RulesXml.withStep(
        added,
        index,
        step.toBuilder()
            // a number after the ending would hide it from TripleA: a number before it instead
            .name(freeNameEnding(stepNames, prefix, kind.ending()))
            .display(delegate.shownAsKind() ? step.getDisplay() : kind.display())
            .build());
  }

  /** The game with a delegate of the kind, its name, and whether it shows the kind's display. */
  private record DelegateOf(Game game, String name, boolean shownAsKind) {}

  /**
   * The game with a delegate named "tech": TripleA's technology activation asks the delegate of
   * that very name what the nation researched, and stops the game where there is none.
   */
  private static Game withTechDelegate(final Game game) {
    final List<Delegate> delegates =
        new ArrayList<>(
            Optional.ofNullable(game.getGamePlay())
                .map(GamePlay::getDelegates)
                .orElse(List.of()));
    if (delegates.stream().anyMatch(d -> TECH_DELEGATE.equals(d.getName()))) {
      return game;
    }
    final DelegateKind tech =
        DELEGATE_KINDS.stream()
            .filter(k -> TECH_DELEGATE.equals(k.name()))
            .findFirst()
            .orElseThrow();
    delegates.add(
        Delegate.builder()
            .name(TECH_DELEGATE)
            .javaClass(tech.javaClass())
            .display(tech.display())
            .build());
    final GamePlay gamePlay =
        Optional.ofNullable(game.getGamePlay()).orElseGet(() -> GamePlay.builder().build());
    return game.toBuilder()
        .gamePlay(gamePlay.toBuilder().delegates(List.copyOf(delegates)).build())
        .build();
  }

  private static DelegateOf withDelegate(final Game given, final DelegateKind kind) {
    final Game game =
        kind.javaClass().endsWith("TechActivationDelegate") ? withTechDelegate(given) : given;
    final List<Delegate> delegates =
        new ArrayList<>(
            Optional.ofNullable(game.getGamePlay())
                .map(GamePlay::getDelegates)
                .orElse(List.of()));
    final Optional<Delegate> existing =
        delegates.stream()
            .filter(d -> isClass(d, kind))
            .min(Comparator.comparing((Delegate d) -> !kind.name().equals(d.getName())));
    if (existing.isPresent()) {
      return new DelegateOf(
          game, existing.get().getName(), kind.display().equals(existing.get().getDisplay()));
    }
    final String name =
        RulesXml.freeName(
            delegates.stream().map(Delegate::getName).collect(Collectors.toSet()), kind.name());
    delegates.add(
        Delegate.builder().name(name).javaClass(kind.javaClass()).display(kind.display()).build());
    final GamePlay gamePlay =
        Optional.ofNullable(game.getGamePlay()).orElseGet(() -> GamePlay.builder().build());
    return new DelegateOf(
        game.toBuilder()
            .gamePlay(gamePlay.toBuilder().delegates(List.copyOf(delegates)).build())
            .build(),
        name,
        true);
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
