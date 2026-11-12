package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.triplea.map.data.elements.Info;
import org.triplea.map.data.elements.Initialize;
import org.triplea.map.data.elements.Initialize.InitializeBuilder;
import org.triplea.map.data.elements.Initialize.OwnerInitialize;
import org.triplea.map.data.elements.Initialize.OwnerInitialize.TerritoryOwner;
import org.triplea.map.data.elements.Initialize.ResourceInitialize;
import org.triplea.map.data.elements.Initialize.ResourceInitialize.ResourceGiven;
import org.triplea.map.data.elements.Initialize.UnitInitialize;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.data.elements.Map;
import org.triplea.map.data.elements.Map.Connection;
import org.triplea.map.data.elements.Map.Territory;
import org.triplea.map.data.elements.PlayerList;
import org.triplea.map.data.elements.PlayerList.Alliance;
import org.triplea.map.data.elements.PlayerList.Player;
import org.triplea.map.data.elements.Production;
import org.triplea.map.data.elements.Production.PlayerProduction;
import org.triplea.map.data.elements.Production.ProductionFrontier;
import org.triplea.map.data.elements.Production.ProductionFrontier.FrontierRules;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.map.data.elements.Production.Rule.Cost;
import org.triplea.map.data.elements.Production.Rule.Result;
import org.triplea.map.data.elements.PropertyList;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.map.data.elements.PropertyList.Property.Value;
import org.triplea.map.data.elements.ResourceList;
import org.triplea.map.data.elements.ResourceList.Resource;
import org.triplea.map.data.elements.UnitList;
import org.triplea.map.data.elements.UnitList.Unit;
import eddies.mapeditor.MapPicture.DetectedMap;

/**
 * What the map maker reads from and changes in a game's XML model: the territories with their
 * owner, value and units, the nations, the units' stats, the options. Every change returns a new
 * model and leaves the given one as it is, so that a model once shown can be gone back to.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class MapXml {
  static final String PUS = Constants.PUS;
  private static final String TERRITORY = Constants.TERRITORY_ATTACHMENT_NAME;
  private static final String UNIT = Constants.UNIT_ATTACHMENT_NAME;
  private static final String END_ROUND = "endRound";
  private static final String PRODUCTION = "production";
  private static final String NOTES = "notes";
  // the engine tells the kind of a step by these endings, eg "NonCombatMove" or "BidPlace"
  private static final List<TurnStep> TURN =
      List.of(
          new TurnStep("Tech", "tech", null),
          new TurnStep("TechActivation", "tech_activation", null),
          new TurnStep("Purchase", "purchase", null),
          new TurnStep("CombatMove", "move", null),
          new TurnStep("Battle", "battle", null),
          new TurnStep("NonCombatMove", "move", "Non Combat Move"),
          new TurnStep("Place", "place", null),
          new TurnStep("EndTurn", "endTurn", null));
  // once at the start, before the first turn: buying and placing with the "<nation> bid" PUs
  private static final List<TurnStep> BIDDING =
      List.of(new TurnStep("Bid", "bid", null), new TurnStep("BidPlace", "placeBid", null));

  /** A step of a nation's turn: the end of its name, the delegate, how it is shown if not so. */
  private record TurnStep(String suffix, String delegate, @Nullable String display) {}

  // ---- territories

  static List<String> territories(final Game game) {
    return territoryElements(game).stream().map(Territory::getName).toList();
  }

  private static List<Territory> territoryElements(final Game game) {
    return Optional.ofNullable(game.getMap())
        .map(Map::getTerritories)
        .map(List::copyOf)
        .orElse(List.of());
  }

  static boolean isWater(final Game game, final String territory) {
    return territoryElements(game).stream()
        .anyMatch(t -> t.getName().equals(territory) && Boolean.TRUE.equals(t.getWater()));
  }

  static Game withWater(final Game game, final String territory) {
    return withWaterValue(game, territory, Boolean.TRUE);
  }

  static Game withLand(final Game game, final String territory) {
    return withWaterValue(game, territory, null);
  }

  private static Game withWaterValue(
      final Game game, final String territory, @Nullable final Boolean water) {
    final List<Territory> territories =
        territoryElements(game).stream()
            .map(t -> t.getName().equals(territory) ? t.toBuilder().water(water).build() : t)
            .toList();
    return game.toBuilder().map(map(game).toBuilder().territories(territories).build()).build();
  }

  private static Map map(final Game game) {
    return Optional.ofNullable(game.getMap()).orElseGet(() -> Map.builder().build());
  }

  /**
   * The game with a new land territory last on the map, connected once to each neighbour; a sea
   * zone is made with {@link #withWater}, an owner with {@link #withOwner}.
   */
  static Game withTerritory(
      final Game game, final String territory, final Collection<String> neighbours) {
    final List<Territory> territories = new ArrayList<>(territoryElements(game));
    territories.add(Territory.builder().name(territory).build());
    Game changed =
        game.toBuilder().map(map(game).toBuilder().territories(territories).build()).build();
    for (final String neighbour : neighbours) {
      changed = withConnection(changed, territory, neighbour);
    }
    return changed;
  }

  /**
   * The game without the territory and all that belongs to it: the canals through it (a canal needs
   * both its sea zones), its owner and units at the start, its connections and its territory
   * attachments. Other attachments that name it stay: {@link RulesXml#whereNamed} tells them.
   */
  static Game withoutTerritory(final Game game, final String territory) {
    Game changed = game;
    for (final CanalXml.Canal canal : CanalXml.canals(game)) {
      if (canal.seaZones().contains(territory) || canal.lands().contains(territory)) {
        changed = CanalXml.withoutCanal(changed, canal.name());
      }
    }
    if (owner(changed, territory).isPresent()) {
      changed = withOwner(changed, territory, null);
    }
    changed = withTerritoryUnits(changed, territory, List.of());
    final Map world =
        map(changed).toBuilder()
            .territories(without(territoryElements(changed), t -> territory.equals(t.getName())))
            .connections(
                without(
                    connections(changed),
                    c -> territory.equals(c.getT1()) || territory.equals(c.getT2())))
            .build();
    final Game withoutWorld = changed.toBuilder().map(world).build();
    return changed.getAttachmentList() == null
        ? withoutWorld
        : withoutWorld.toBuilder()
            .attachmentList(
                changed.getAttachmentList().toBuilder()
                    .attachments(
                        without(
                            attachments(changed),
                            a ->
                                "territory".equals(a.getType())
                                    && territory.equals(a.getAttachTo())))
                    .build())
            .build();
  }

  /** The nation owning the territory at the start, empty for none (neutral). */
  static Optional<String> owner(final Game game, final String territory) {
    return owners(game).stream()
        .filter(owner -> territory.equals(owner.getTerritory()))
        .map(TerritoryOwner::getOwner)
        .findFirst();
  }

  /**
   * The nations owning some of these territories at the start, as {@link #owner} finds them; the
   * owners read once, as a big map has hundreds of territories.
   */
  static Set<String> nationsWithLand(final Game game, final Set<String> territories) {
    final Set<String> seen = new LinkedHashSet<>();
    final Set<String> nations = new LinkedHashSet<>();
    for (final TerritoryOwner owner : owners(game)) {
      if (territories.contains(owner.getTerritory()) && seen.add(owner.getTerritory())) {
        Optional.ofNullable(owner.getOwner()).ifPresent(nations::add);
      }
    }
    return nations;
  }

  /** Each owned territory's owner at the start, as {@link #owner} finds it, read once. */
  static java.util.Map<String, String> ownersByTerritory(final Game game) {
    final java.util.Map<String, String> owners = new HashMap<>();
    for (final TerritoryOwner owner : owners(game)) {
      if (owner.getTerritory() != null && owner.getOwner() != null) {
        owners.putIfAbsent(owner.getTerritory(), owner.getOwner());
      }
    }
    return owners;
  }

  /** The sea zones, read once, as {@link #isWater} reads all territories for each. */
  static Set<String> seaZones(final Game game) {
    return territoryElements(game).stream()
        .filter(t -> Boolean.TRUE.equals(t.getWater()))
        .map(Territory::getName)
        .collect(Collectors.toSet());
  }

  private static List<TerritoryOwner> owners(final Game game) {
    return Optional.ofNullable(game.getInitialize())
        .map(Initialize::getOwnerInitialize)
        .map(OwnerInitialize::getTerritoryOwners)
        .map(List::copyOf)
        .orElse(List.of());
  }

  /**
   * The game with the territory owned by the nation at the start, by none for null; an owner it had
   * keeps its place in the XML.
   */
  static Game withOwner(final Game game, final String territory, @Nullable final String owner) {
    final List<TerritoryOwner> owners = new ArrayList<>();
    boolean placed = false;
    for (final TerritoryOwner current : owners(game)) {
      if (!territory.equals(current.getTerritory())) {
        owners.add(current);
      } else if (owner != null && !placed) {
        owners.add(current.toBuilder().owner(owner).build());
        placed = true;
      }
    }
    if (owner != null && !placed) {
      owners.add(TerritoryOwner.builder().territory(territory).owner(owner).build());
    }
    final Initialize initialize = initialize(game);
    return game.toBuilder()
        .initialize(
            initialize.toBuilder()
                .ownerInitialize(
                    Optional.ofNullable(initialize.getOwnerInitialize())
                        .orElseGet(() -> OwnerInitialize.builder().build())
                        .toBuilder()
                        .territoryOwners(owners)
                        .build())
                .build())
        .build();
  }

  private static Initialize initialize(final Game game) {
    return Optional.ofNullable(game.getInitialize()).orElseGet(() -> Initialize.builder().build());
  }

  /** The PUs the territory gives its owner each turn. */
  static int production(final Game game, final String territory) {
    return intOption(game, TERRITORY, territory, "production");
  }

  static Game withProduction(final Game game, final String territory, final int production) {
    final Game changed =
        withTerritoryOption(
            game, territory, "production", production == 0 ? null : String.valueOf(production));
    // the game's "production" sets the unit production too: an own one must stay after it
    final List<String> units =
        TerritoryXml.values(changed, territory, TerritoryXml.UNIT_PRODUCTION);
    return changed == game || units.isEmpty()
        ? changed
        : TerritoryXml.withValues(
            TerritoryXml.withValues(changed, territory, TerritoryXml.UNIT_PRODUCTION, List.of()),
            territory,
            TerritoryXml.UNIT_PRODUCTION,
            units);
  }

  /** Whether the territory is the capital of its owner. */
  static boolean isCapital(final Game game, final String territory) {
    return capitalOf(game, territory).isPresent();
  }

  /** The nation the territory is the capital of, if it is one. */
  static Optional<String> capitalOf(final Game game, final String territory) {
    return option(game, TERRITORY, territory, "capital");
  }

  /**
   * The nations that have a capital among these territories, as {@link #capitalOf} finds them; the
   * attachments read once, as a big map has thousands.
   */
  static Set<String> nationsWithCapital(final Game game, final Set<String> territories) {
    final Set<String> seen = new LinkedHashSet<>();
    final Set<String> nations = new LinkedHashSet<>();
    for (final Attachment attachment : attachments(game)) {
      if (TERRITORY.equals(attachment.getName())
          && territories.contains(attachment.getAttachTo())) {
        for (final Option option :
            Optional.ofNullable(attachment.getOptions()).orElse(List.of())) {
          if ("capital".equals(option.getName())
              && option.getValue() != null
              && seen.add(attachment.getAttachTo())) {
            nations.add(option.getValue());
          }
        }
      }
    }
    return nations;
  }

  /** Whether no unit may enter the territory, eg marshes. */
  static boolean isImpassable(final Game game, final String territory) {
    return option(game, TERRITORY, territory, "isImpassable")
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  /** The game with the territory the capital of the nation, of none for null. */
  static Game withCapital(final Game game, final String territory, @Nullable final String nation) {
    return withTerritoryOption(game, territory, "capital", nation);
  }

  /**
   * The game with the territory renamed wherever the XML names it: the map, its connections, owners
   * and units at the start, the territory's attachments and the options listing territories (names
   * between ':').
   */
  static Game withRenamedTerritory(final Game game, final String old, final String renamed) {
    final UnaryOperator<String> rename = name -> old.equals(name) ? renamed : name;
    final Map world =
        map(game).toBuilder()
            .territories(
                territoryElements(game).stream()
                    .map(t -> t.toBuilder().name(rename.apply(t.getName())).build())
                    .toList())
            .connections(
                connections(game).stream()
                    .map(
                        c ->
                            c.toBuilder()
                                .t1(rename.apply(c.getT1()))
                                .t2(rename.apply(c.getT2()))
                                .build())
                    .toList())
            .build();
    final List<Attachment> attachments =
        attachments(game).stream()
            .map(
                a ->
                    a.toBuilder()
                        .attachTo(
                            "territory".equals(a.getType())
                                ? rename.apply(a.getAttachTo())
                                : a.getAttachTo())
                        .options(
                            Optional.ofNullable(a.getOptions()).orElse(List.of()).stream()
                                .map(
                                    o ->
                                        o.toBuilder()
                                            .value(renamedInList(o.getValue(), rename))
                                            .build())
                                .toList())
                        .build())
            .toList();
    final Game renamedWorld =
        game.toBuilder()
            .map(world)
            .attachmentList(
                game.getAttachmentList() == null
                    ? null
                    : game.getAttachmentList().toBuilder().attachments(attachments).build())
            .build();
    final Initialize initialize = game.getInitialize();
    if (initialize == null) {
      return renamedWorld;
    }
    final OwnerInitialize owners = initialize.getOwnerInitialize();
    final UnitInitialize units = initialize.getUnitInitialize();
    return renamedWorld.toBuilder()
        .initialize(
            initialize.toBuilder()
                .ownerInitialize(
                    owners == null
                        ? null
                        : owners.toBuilder()
                            .territoryOwners(
                                owners(game).stream()
                                    .map(
                                        o ->
                                            o.toBuilder()
                                                .territory(rename.apply(o.getTerritory()))
                                                .build())
                                    .toList())
                            .build())
                .unitInitialize(
                    units == null
                        ? null
                        : units.toBuilder()
                            .unitPlacements(
                                placements(game).stream()
                                    .map(
                                        p ->
                                            p.toBuilder()
                                                .territory(rename.apply(p.getTerritory()))
                                                .build())
                                    .toList())
                            .build())
                .build())
        .build();
  }

  /** The option's value with each ':' separated name renamed; other values stay as they are. */
  @Nullable
  private static String renamedInList(
      @Nullable final String value, final UnaryOperator<String> rename) {
    return value == null
        ? null
        : Arrays.stream(value.split(":", -1)).map(rename).collect(Collectors.joining(":"));
  }

  /**
   * The game on a new world: the territories and connections found in a picture, no owners or units
   * on them and none of the attachments that name territories; nations, alliances, units,
   * production, turn order, technology and options stay as they are.
   */
  static Game withWorld(final Game rules, final DetectedMap world) {
    final List<Territory> territories =
        world.territories().stream()
            .map(
                t ->
                    Territory.builder()
                        .name(t.name())
                        .water(t.water() ? Boolean.TRUE : null)
                        .build())
            .toList();
    final List<Connection> connections =
        world.connections().stream()
            .map(c -> Connection.builder().t1(c.t1()).t2(c.t2()).build())
            .toList();
    final Initialize initialize = initialize(rules);
    return rules.toBuilder()
        .map(Map.builder().territories(territories).connections(connections).build())
        .initialize(
            initialize.toBuilder()
                .ownerInitialize(OwnerInitialize.builder().territoryOwners(List.of()).build())
                .unitInitialize(
                    Optional.ofNullable(initialize.getUnitInitialize())
                        .orElseGet(() -> UnitInitialize.builder().build())
                        .toBuilder()
                        .unitPlacements(List.of())
                        .build())
                .build())
        .attachmentList(
            AttachmentList.builder()
                .attachments(attachments(rules).stream().filter(MapXml::namesNoTerritory).toList())
                .build())
        .build();
  }

  private static boolean namesNoTerritory(final Attachment attachment) {
    final String javaClass = Objects.toString(attachment.getJavaClass(), "");
    return !"territory".equals(attachment.getType())
        && Stream.of(
                "RulesAttachment",
                "TriggerAttachment",
                "PoliticalActionAttachment",
                "UserActionAttachment",
                "CanalAttachment")
            .noneMatch(javaClass::endsWith);
  }

  static boolean isVictoryCity(final Game game, final String territory) {
    return intOption(game, TERRITORY, territory, "victoryCity") != 0;
  }

  /** Whether the territory's attachment sets the option true, eg kamikazeZone. */
  static boolean hasTerritoryFlag(final Game game, final String territory, final String option) {
    return option(game, TERRITORY, territory, option)
        .map(value -> Boolean.parseBoolean(value.strip()))
        .orElse(false);
  }

  /** The territory's effects, eg Forest, in the order the game draws them. */
  static List<String> territoryEffects(final Game game, final String territory) {
    return attachments(game).stream()
        .filter(a -> TERRITORY.equals(a.getName()) && territory.equals(a.getAttachTo()))
        .flatMap(MapXml::effectsOf)
        .toList();
  }

  /** Each territory's effects, as {@link #territoryEffects(Game, String)}, in one pass. */
  static java.util.Map<String, List<String>> territoryEffects(final Game game) {
    return attachments(game).stream()
        .filter(a -> TERRITORY.equals(a.getName()) && a.getAttachTo() != null)
        .collect(
            Collectors.groupingBy(
                Attachment::getAttachTo,
                Collectors.flatMapping(MapXml::effectsOf, Collectors.toList())));
  }

  private static Stream<String> effectsOf(final Attachment attachment) {
    return Optional.ofNullable(attachment.getOptions()).orElse(List.of()).stream()
        .filter(o -> "territoryEffect".equals(o.getName()) && o.getValue() != null)
        .flatMap(o -> Arrays.stream(o.getValue().split(":")))
        .map(String::strip)
        .filter(effect -> !effect.isEmpty());
  }

  static Game withVictoryCity(final Game game, final String territory) {
    return withTerritoryOption(game, territory, "victoryCity", "1");
  }

  static Game withoutVictoryCity(final Game game, final String territory) {
    return withTerritoryOption(game, territory, "victoryCity", null);
  }

  private static Game withTerritoryOption(
      final Game game, final String territory, final String option, @Nullable final String value) {
    return withOption(
        game,
        new AttachmentKey(TERRITORY, TerritoryAttachment.class.getName(), "territory", territory),
        option,
        value);
  }

  /** The neighbours of the territory, in the order of the connections. */
  static Set<String> neighbors(final Game game, final String territory) {
    final Set<String> neighbors = new LinkedHashSet<>();
    for (final Connection connection : connections(game)) {
      if (territory.equals(connection.getT1())) {
        neighbors.add(connection.getT2());
      } else if (territory.equals(connection.getT2())) {
        neighbors.add(connection.getT1());
      }
    }
    return neighbors;
  }

  static List<Connection> connections(final Game game) {
    return Optional.ofNullable(game.getMap())
        .map(Map::getConnections)
        .map(List::copyOf)
        .orElse(List.of());
  }

  /** The game with the two territories connected once; either direction counts. */
  static Game withConnection(final Game game, final String territory, final String other) {
    final List<Connection> connections =
        new ArrayList<>(connectionsWithout(game, territory, other));
    connections.add(Connection.builder().t1(territory).t2(other).build());
    return withConnections(game, connections);
  }

  /** The game without a connection between the two territories, in either direction. */
  static Game withoutConnection(final Game game, final String territory, final String other) {
    return withConnections(game, connectionsWithout(game, territory, other));
  }

  private static List<Connection> connectionsWithout(
      final Game game, final String territory, final String other) {
    final Predicate<Connection> between =
        c ->
            (territory.equals(c.getT1()) && other.equals(c.getT2()))
                || (territory.equals(c.getT2()) && other.equals(c.getT1()));
    return connections(game).stream().filter(between.negate()).toList();
  }

  private static Game withConnections(final Game game, final List<Connection> connections) {
    return game.toBuilder().map(map(game).toBuilder().connections(connections).build()).build();
  }

  // ---- units at the start

  static List<UnitPlacement> units(final Game game, final String territory) {
    return placements(game).stream().filter(p -> territory.equals(p.getTerritory())).toList();
  }

  /** Every territory's units at the start, in the XML's order. */
  static List<UnitPlacement> placements(final Game game) {
    return Optional.ofNullable(game.getInitialize())
        .map(Initialize::getUnitInitialize)
        .map(UnitInitialize::getUnitPlacements)
        .map(List::copyOf)
        .orElse(List.of());
  }

  /**
   * The game with the starting units of one territory copied to another, replacing its own: units
   * of the first one's owner go to the other one's owner, eg a nation's usual garrison.
   */
  static Game withUnitsCopied(final Game game, final String from, final String to) {
    final Optional<String> fromOwner = owner(game, from);
    final Optional<String> toOwner = owner(game, to);
    final List<UnitPlacement> copied =
        units(game, from).stream()
            .map(
                unit ->
                    fromOwner.isPresent()
                            && toOwner.isPresent()
                            && fromOwner.get().equals(unit.getOwner())
                        ? unit.toBuilder().owner(toOwner.get()).build()
                        : unit)
            .toList();
    return withTerritoryUnits(game, to, copied);
  }

  /**
   * The game with the territory's starting units replaced by these, each placed in the territory;
   * none clears them. The other territories' units keep their order.
   */
  static Game withTerritoryUnits(
      final Game game, final String territory, final List<UnitPlacement> units) {
    if (units.isEmpty() && units(game, territory).isEmpty()) {
      // nothing to clear: no change, so no undo step
      return game;
    }
    final List<UnitPlacement> placements = new ArrayList<>();
    placements(game).stream()
        .filter(p -> !territory.equals(p.getTerritory()))
        .forEach(placements::add);
    units.stream()
        .map(unit -> unit.toBuilder().territory(territory).build())
        .forEach(placements::add);
    return withPlacements(game, placements);
  }

  /**
   * The game with this many units of the type and owner (null for neutral) in the territory at the
   * start, none for 0.
   */
  static Game withUnits(
      final Game game,
      final String territory,
      @Nullable final String owner,
      final String unitType,
      final int quantity) {
    final Predicate<UnitPlacement> same =
        p ->
            territory.equals(p.getTerritory())
                && unitType.equals(p.getUnitType())
                && Objects.equals(owner, p.getOwner());
    final List<UnitPlacement> placements = new ArrayList<>();
    boolean placed = false;
    for (final UnitPlacement current : placements(game)) {
      if (!same.test(current)) {
        placements.add(current);
      } else if (quantity > 0 && !placed) {
        placements.add(current.toBuilder().quantity(quantity).build());
        placed = true;
      }
    }
    if (quantity > 0 && !placed) {
      placements.add(
          UnitPlacement.builder()
              .territory(territory)
              .owner(owner)
              .unitType(unitType)
              .quantity(quantity)
              .build());
    }
    return withPlacements(game, placements);
  }

  private static Game withPlacements(final Game game, final List<UnitPlacement> placements) {
    final Initialize initialize = initialize(game);
    return game.toBuilder()
        .initialize(
            initialize.toBuilder()
                .unitInitialize(
                    Optional.ofNullable(initialize.getUnitInitialize())
                        .orElseGet(() -> UnitInitialize.builder().build())
                        .toBuilder()
                        .unitPlacements(placements)
                        .build())
                .build())
        .build();
  }

  // ---- nations

  /** Whether the nation has steps of its own in the turn, unlike eg a map's neutrals' player. */
  static boolean takesTurns(final Game game, final String nation) {
    return steps(game).stream().anyMatch(step -> nation.equals(step.getPlayer()));
  }

  static List<String> nations(final Game game) {
    return Optional.ofNullable(game.getPlayerList())
        .map(PlayerList::getPlayers)
        .map(List::copyOf)
        .orElse(List.<Player>of())
        .stream()
        .map(Player::getName)
        .toList();
  }

  /** The alliances of the nation, eg "Axis"; most nations have one. */
  static List<String> alliances(final Game game, final String nation) {
    return allianceElements(game).stream()
        .filter(a -> nation.equals(a.getPlayer()))
        .map(Alliance::getAlliance)
        .toList();
  }

  private static List<Alliance> allianceElements(final Game game) {
    return Optional.ofNullable(game.getPlayerList())
        .map(PlayerList::getAlliances)
        .map(List::copyOf)
        .orElse(List.of());
  }

  static List<String> allAlliances(final Game game) {
    return allianceElements(game).stream().map(Alliance::getAlliance).distinct().toList();
  }

  /** The game with the nation in this one alliance only, in none for a blank name. */
  static Game withAlliance(final Game game, final String nation, final String alliance) {
    final List<Alliance> alliances =
        new ArrayList<>(
            allianceElements(game).stream().filter(a -> !nation.equals(a.getPlayer())).toList());
    if (!alliance.isBlank()) {
      alliances.add(Alliance.builder().player(nation).alliance(alliance.strip()).build());
    }
    final PlayerList players =
        Optional.ofNullable(game.getPlayerList()).orElseGet(() -> PlayerList.builder().build());
    return game.toBuilder().playerList(players.toBuilder().alliances(alliances).build()).build();
  }

  /** The amount of the resource the nation starts with, eg its PUs. */
  static int startingResource(final Game game, final String nation, final String resource) {
    return resourcesGiven(game).stream()
        .filter(r -> nation.equals(r.getPlayer()) && resource.equals(r.getResource()))
        .mapToInt(ResourceGiven::getQuantity)
        .sum();
  }

  private static List<ResourceGiven> resourcesGiven(final Game game) {
    return Optional.ofNullable(game.getInitialize())
        .map(Initialize::getResourceInitialize)
        .map(ResourceInitialize::getResourcesGiven)
        .map(List::copyOf)
        .orElse(List.of());
  }

  static Game withStartingResource(
      final Game game, final String nation, final String resource, final int quantity) {
    final List<ResourceGiven> given = new ArrayList<>();
    boolean placed = false;
    for (final ResourceGiven current : resourcesGiven(game)) {
      if (!nation.equals(current.getPlayer()) || !resource.equals(current.getResource())) {
        given.add(current);
      } else if (!placed) {
        given.add(current.toBuilder().quantity(quantity).build());
        placed = true;
      }
    }
    if (!placed) {
      given.add(
          ResourceGiven.builder().player(nation).resource(resource).quantity(quantity).build());
    }
    final Initialize initialize = initialize(game);
    return game.toBuilder()
        .initialize(
            initialize.toBuilder()
                .resourceInitialize(
                    Optional.ofNullable(initialize.getResourceInitialize())
                        .orElseGet(() -> ResourceInitialize.builder().build())
                        .toBuilder()
                        .resourcesGiven(given)
                        .build())
                .build())
        .build();
  }

  /**
   * The game with a new nation: in an alliance of its name, with no PUs at the start, buying from
   * the frontier "production" (or the game's first), and its turn before the end of the round:
   * tech, tech activation, purchase, combat move, battle, non-combat move, place, end turn, of the
   * delegates the game has. With bid delegates also its bid and bid placement once at the start,
   * after the other nations', and the option "&lt;nation&gt; bid" of 0 PUs.
   */
  static Game withNation(final Game game, final String nation) {
    final List<Player> players = new ArrayList<>(playerElements(game));
    players.add(Player.builder().name(nation).optional(false).build());
    final Game withPlayer =
        game.toBuilder()
            .playerList(
                Optional.ofNullable(game.getPlayerList())
                    .orElseGet(() -> PlayerList.builder().build())
                    .toBuilder()
                    .players(players)
                    .build())
            .build();
    final Game withAlliance = withAlliance(withPlayer, nation, nation);
    final Game withPus =
        resourceNames(game).contains(PUS)
            ? withStartingResource(withAlliance, nation, PUS, 0)
            : withAlliance;
    return withTurn(withFrontier(withPus, nation), nation);
  }

  private static List<Player> playerElements(final Game game) {
    return Optional.ofNullable(game.getPlayerList())
        .map(PlayerList::getPlayers)
        .map(List::<Player>copyOf)
        .orElse(List.of());
  }

  private static List<String> resourceNames(final Game game) {
    return Optional.ofNullable(game.getResourceList())
        .map(ResourceList::getResources)
        .map(List::<Resource>copyOf)
        .orElse(List.of())
        .stream()
        .map(Resource::getName)
        .toList();
  }

  private static Game withFrontier(final Game game, final String nation) {
    final List<String> frontiers =
        frontiers(game).stream().map(ProductionFrontier::getName).toList();
    if (frontiers.isEmpty()) {
      return game;
    }
    final String frontier = frontiers.contains(PRODUCTION) ? PRODUCTION : frontiers.get(0);
    final List<PlayerProduction> productions =
        new ArrayList<>(
            Optional.ofNullable(game.getProduction().getPlayerProductions()).orElse(List.of()));
    productions.add(PlayerProduction.builder().player(nation).frontier(frontier).build());
    return game.toBuilder()
        .production(game.getProduction().toBuilder().playerProductions(productions).build())
        .build();
  }

  private static List<ProductionFrontier> frontiers(final Game game) {
    return Optional.ofNullable(game.getProduction())
        .map(Production::getProductionFrontiers)
        .map(List::<ProductionFrontier>copyOf)
        .orElse(List.of());
  }

  private static Game withTurn(final Game game, final String nation) {
    final List<Step> steps = new ArrayList<>(steps(game));
    final Set<String> delegates =
        Optional.ofNullable(game.getGamePlay())
            .map(GamePlay::getDelegates)
            .map(List::<Delegate>copyOf)
            .orElse(List.of())
            .stream()
            .map(Delegate::getName)
            .collect(Collectors.toSet());
    final Set<String> stepNames = steps.stream().map(Step::getName).collect(Collectors.toSet());
    final String prefix = stepPrefix(nation, stepNames);
    final int endRound =
        IntStream.range(0, steps.size())
            .filter(i -> END_ROUND.equals(steps.get(i).getDelegate()))
            .findFirst()
            .orElse(steps.size());
    steps.addAll(endRound, newSteps(TURN, delegates, prefix, nation, null));
    final List<Step> bidding = newSteps(BIDDING, delegates, prefix, nation, 1);
    if (bidding.isEmpty()) {
      return withSteps(game, steps);
    }
    final Set<String> bidDelegates =
        BIDDING.stream().map(TurnStep::delegate).collect(Collectors.toSet());
    final int afterBids =
        IntStream.range(0, steps.size())
            .filter(i -> bidDelegates.contains(steps.get(i).getDelegate()))
            .map(i -> i + 1)
            .max()
            // no bids yet: before the first nation's turn
            .orElseGet(
                () ->
                    IntStream.range(0, steps.size())
                        .filter(i -> steps.get(i).getPlayer() != null)
                        .findFirst()
                        .orElse(endRound));
    steps.addAll(afterBids, bidding);
    final List<Property> properties = new ArrayList<>(properties(game));
    properties.add(
        Property.builder()
            .name(bidOption(nation))
            .value("0")
            .editable(true)
            .min(0)
            .max(1000)
            .build());
    return withProperties(withSteps(game, steps), properties);
  }

  /** The option of the PUs the nation may bid, which the bid delegates read. */
  private static String bidOption(final String nation) {
    return nation + " bid";
  }

  private static List<Step> newSteps(
      final List<TurnStep> kinds,
      final Set<String> delegates,
      final String prefix,
      final String nation,
      @Nullable final Integer maxRunCount) {
    return kinds.stream()
        .filter(s -> delegates.contains(s.delegate()))
        .map(
            s ->
                Step.builder()
                    .name(prefix + s.suffix())
                    .delegate(s.delegate())
                    .player(nation)
                    .display(s.display())
                    .maxRunCount(maxRunCount)
                    .build())
        .toList();
  }

  /** The start of the nation's step names, eg "redArmy", other than those of other nations. */
  private static String stepPrefix(final String nation, final Set<String> stepNames) {
    final String letters = nation.replaceAll("[^A-Za-z0-9]", "");
    final String base =
        letters.isEmpty()
            ? "nation"
            : letters.substring(0, 1).toLowerCase(Locale.ROOT) + letters.substring(1);
    String prefix = base;
    for (int i = 2; isTaken(prefix, stepNames); i++) {
      prefix = base + i;
    }
    return prefix;
  }

  private static boolean isTaken(final String prefix, final Set<String> stepNames) {
    return Stream.concat(TURN.stream(), BIDDING.stream())
        .anyMatch(s -> stepNames.contains(prefix + s.suffix()));
  }

  private static List<Step> steps(final Game game) {
    return Optional.ofNullable(game.getGamePlay())
        .map(GamePlay::getSequence)
        .map(Sequence::getSteps)
        .map(List::<Step>copyOf)
        .orElse(List.of());
  }

  private static Game withSteps(final Game game, final List<Step> steps) {
    final GamePlay gamePlay =
        Optional.ofNullable(game.getGamePlay()).orElseGet(() -> GamePlay.builder().build());
    return game.toBuilder()
        .gamePlay(
            GamePlay.builder()
                .delegates(gamePlay.getDelegates())
                .offset(gamePlay.getOffset())
                .sequence(Sequence.builder().steps(steps).build())
                .build())
        .build();
  }

  /**
   * The game without the nation and all that names it: its alliances, resources, production,
   * technology, turn and its name in the steps' lists, relationships, the territories it owns and
   * its units at the start, its capitals, its bid option, and the attachments to it. Other
   * attachments that name it stay: {@link RulesXml#whereNamed} tells them.
   */
  static Game withoutNation(final Game game, final String nation) {
    final Predicate<String> isNation = nation::equals;
    final PlayerList players =
        Optional.ofNullable(game.getPlayerList()).orElseGet(() -> PlayerList.builder().build());
    final Initialize initialize = initialize(game);
    final InitializeBuilder start = initialize.toBuilder();
    Optional.ofNullable(initialize.getOwnerInitialize())
        .ifPresent(
            o ->
                start.ownerInitialize(
                    o.toBuilder()
                        .territoryOwners(
                            without(owners(game), owner -> isNation.test(owner.getOwner())))
                        .build()));
    Optional.ofNullable(initialize.getUnitInitialize())
        .ifPresent(
            u ->
                start.unitInitialize(
                    u.toBuilder()
                        .unitPlacements(without(placements(game), p -> isNation.test(p.getOwner())))
                        .heldUnits(
                            without(
                                Optional.ofNullable(u.getHeldUnits()).orElse(List.of()),
                                h -> isNation.test(h.getPlayer())))
                        .build()));
    Optional.ofNullable(initialize.getResourceInitialize())
        .ifPresent(
            r ->
                start.resourceInitialize(
                    r.toBuilder()
                        .resourcesGiven(
                            without(resourcesGiven(game), g -> isNation.test(g.getPlayer())))
                        .build()));
    Optional.ofNullable(initialize.getRelationshipInitialize())
        .ifPresent(
            r ->
                start.relationshipInitialize(
                    r.toBuilder()
                        .relationships(
                            without(
                                Optional.ofNullable(r.getRelationships()).orElse(List.of()),
                                rel ->
                                    isNation.test(rel.getPlayer1())
                                        || isNation.test(rel.getPlayer2())))
                        .build()));
    final Game withoutPlayer =
        game.toBuilder()
            .playerList(
                players.toBuilder()
                    .players(without(playerElements(game), p -> isNation.test(p.getName())))
                    .alliances(without(allianceElements(game), a -> isNation.test(a.getPlayer())))
                    .build())
            .initialize(game.getInitialize() == null ? null : start.build())
            .propertyList(
                game.getPropertyList() == null
                    ? null
                    : game.getPropertyList().toBuilder()
                        .properties(
                            without(properties(game), p -> bidOption(nation).equals(p.getName())))
                        .build())
            .attachmentList(
                game.getAttachmentList() == null
                    ? null
                    : game.getAttachmentList().toBuilder()
                        .attachments(
                            attachments(game).stream()
                                .filter(
                                    a ->
                                        !("player".equals(a.getType())
                                            && isNation.test(a.getAttachTo())))
                                .map(a -> withoutOption(a, "capital", nation))
                                .toList())
                        .build())
            .build();
    final Production production = game.getProduction();
    final Game withoutProduction =
        production == null
            ? withoutPlayer
            : withoutPlayer.toBuilder()
                .production(
                    production.toBuilder()
                        .playerProductions(
                            without(
                                Optional.ofNullable(production.getPlayerProductions())
                                    .orElse(List.of()),
                                p -> isNation.test(p.getPlayer())))
                        .playerRepairs(
                            without(
                                Optional.ofNullable(production.getPlayerRepairs())
                                    .orElse(List.of()),
                                p -> isNation.test(p.getPlayer())))
                        .build())
                .build();
    final Game withoutTech =
        RulesXml.withoutNameInStepProperties(
            TechXml.withoutNation(withoutProduction, nation), nation);
    return game.getGamePlay() == null
        ? withoutTech
        : withSteps(withoutTech, without(steps(withoutTech), s -> isNation.test(s.getPlayer())));
  }

  private static <T> List<T> without(final List<T> values, final Predicate<T> removed) {
    return values.stream().filter(removed.negate()).toList();
  }

  /** The attachment without the option when it has this value, eg the capital of a nation. */
  private static Attachment withoutOption(
      final Attachment attachment, final String option, final String value) {
    final List<Option> options = Optional.ofNullable(attachment.getOptions()).orElse(List.of());
    final Predicate<Option> removed = o -> option.equals(o.getName()) && value.equals(o.getValue());
    return options.stream().anyMatch(removed)
        ? attachment.toBuilder().options(without(options, removed)).build()
        : attachment;
  }

  // ---- unit types

  static List<String> unitTypes(final Game game) {
    return Optional.ofNullable(game.getUnitList())
        .map(UnitList::getUnits)
        .map(List::copyOf)
        .orElse(List.of())
        .stream()
        .map(Unit::getName)
        .toList();
  }

  /**
   * A whole number stat of the unit type, eg "attack"; the default when the map does not give it.
   */
  static int unitStat(
      final Game game, final String unitType, final String stat, final int defaultValue) {
    return option(game, UNIT, unitType, stat).flatMap(MapXml::parseInt).orElse(defaultValue);
  }

  /** A yes or no stat of the unit type, eg "isAir". */
  static boolean unitFlag(final Game game, final String unitType, final String flag) {
    return option(game, UNIT, unitType, flag).map(Boolean::parseBoolean).orElse(false);
  }

  /** The game with the unit type's option set to the value. */
  static Game withUnitOption(
      final Game game, final String unitType, final String option, final String value) {
    return withOption(game, unitKey(unitType), option, value);
  }

  /** The game without the unit type's option, so the engine's default applies. */
  static Game withoutUnitOption(final Game game, final String unitType, final String option) {
    return withOption(game, unitKey(unitType), option, null);
  }

  private static AttachmentKey unitKey(final String unitType) {
    return new AttachmentKey(UNIT, UnitAttachment.class.getName(), "unitType", unitType);
  }

  /**
   * The game with a new unit type like the one given: its stats, and a rule "buy" and its name at
   * the same cost in every production frontier.
   */
  static Game withUnitType(final Game game, final String unitType, final String like) {
    final List<Unit> units =
        Stream.concat(unitElements(game).stream(), Stream.of(Unit.builder().name(unitType).build()))
            .toList();
    final List<Attachment> attachments = new ArrayList<>(attachments(game));
    attachments.stream()
        .filter(a -> unitKey(like).matches(a))
        .findFirst()
        .map(a -> a.toBuilder().attachTo(unitType).build())
        .ifPresent(attachments::add);
    final Game withUnit =
        game.toBuilder()
            .unitList(UnitList.builder().units(units).build())
            .attachmentList(
                Optional.ofNullable(game.getAttachmentList())
                    .orElseGet(() -> AttachmentList.builder().build())
                    .toBuilder()
                    .attachments(attachments)
                    .build())
            .build();
    final Optional<Integer> cost = unitCost(game, like);
    if (game.getProduction() == null || cost.isEmpty()) {
      return withUnit;
    }
    final Set<String> ruleNames =
        productionRules(game).stream().map(ProductionRule::getName).collect(Collectors.toSet());
    final String letters = unitType.replaceAll("[^A-Za-z0-9]", "");
    final String base =
        "buy"
            + (letters.isEmpty() ? "Unit" : letters.substring(0, 1).toUpperCase(Locale.ROOT))
            + (letters.length() > 1 ? letters.substring(1) : "");
    String ruleName = base;
    for (int i = 2; ruleNames.contains(ruleName); i++) {
      ruleName = base + i;
    }
    final ProductionRule rule =
        ProductionRule.builder()
            .name(ruleName)
            .costs(List.of(Cost.builder().resource(PUS).quantity(cost.get()).build()))
            .results(List.of(Result.builder().resourceOrUnit(unitType).quantity(1).build()))
            .build();
    final String added = ruleName;
    return withUnit.toBuilder()
        .production(
            game.getProduction().toBuilder()
                .productionRules(
                    Stream.concat(productionRules(game).stream(), Stream.of(rule)).toList())
                .productionFrontiers(
                    frontiers(game).stream()
                        .map(
                            f ->
                                f.toBuilder()
                                    .frontierRules(
                                        Stream.concat(
                                                Optional.ofNullable(f.getFrontierRules())
                                                    .orElse(List.of())
                                                    .stream(),
                                                Stream.of(
                                                    FrontierRules.builder().name(added).build()))
                                            .toList())
                                    .build())
                        .toList())
                .build())
        .build();
  }

  private static List<Unit> unitElements(final Game game) {
    return Optional.ofNullable(game.getUnitList())
        .map(UnitList::getUnits)
        .map(List::<Unit>copyOf)
        .orElse(List.of());
  }

  /**
   * The game without the unit type: its attachments, the rules that make it and their places in the
   * frontiers, and its units at the start.
   */
  static Game withoutUnitType(final Game game, final String unitType) {
    final Predicate<String> isType = unitType::equals;
    final Set<String> rules =
        productionRules(game).stream()
            .filter(
                r ->
                    Optional.ofNullable(r.getResults()).orElse(List.of()).stream()
                        .anyMatch(result -> isType.test(result.getResourceOrUnit())))
            .map(ProductionRule::getName)
            .collect(Collectors.toSet());
    final Game withoutUnit =
        game.toBuilder()
            .unitList(
                UnitList.builder()
                    .units(without(unitElements(game), u -> isType.test(u.getName())))
                    .build())
            .attachmentList(
                game.getAttachmentList() == null
                    ? null
                    : game.getAttachmentList().toBuilder()
                        .attachments(
                            without(
                                attachments(game),
                                a ->
                                    "unitType".equals(a.getType()) && isType.test(a.getAttachTo())))
                        .build())
            .production(
                game.getProduction() == null
                    ? null
                    : game.getProduction().toBuilder()
                        .productionRules(
                            without(productionRules(game), r -> rules.contains(r.getName())))
                        .productionFrontiers(
                            frontiers(game).stream()
                                .map(
                                    f ->
                                        f.toBuilder()
                                            .frontierRules(
                                                without(
                                                    Optional.ofNullable(f.getFrontierRules())
                                                        .orElse(List.of()),
                                                    r -> rules.contains(r.getName())))
                                            .build())
                                .toList())
                        .build())
            .build();
    final Initialize initialize = game.getInitialize();
    if (initialize == null || initialize.getUnitInitialize() == null) {
      return withoutUnit;
    }
    final UnitInitialize units = initialize.getUnitInitialize();
    return withoutUnit.toBuilder()
        .initialize(
            initialize.toBuilder()
                .unitInitialize(
                    units.toBuilder()
                        .unitPlacements(
                            without(placements(game), p -> isType.test(p.getUnitType())))
                        .heldUnits(
                            without(
                                Optional.ofNullable(units.getHeldUnits()).orElse(List.of()),
                                h -> isType.test(h.getUnitType())))
                        .build())
                .build())
        .build();
  }

  /** The PUs a unit of the type costs, empty when no rule buys exactly one. */
  static Optional<Integer> unitCost(final Game game, final String unitType) {
    return costRule(game, unitType)
        .flatMap(
            rule ->
                Optional.ofNullable(rule.getCosts()).orElse(List.of()).stream()
                    .filter(cost -> PUS.equals(cost.getResource()))
                    .map(Cost::getQuantity)
                    .filter(Objects::nonNull)
                    .findFirst());
  }

  private static Optional<ProductionRule> costRule(final Game game, final String unitType) {
    return productionRules(game).stream()
        .filter(
            rule ->
                Optional.ofNullable(rule.getResults()).orElse(List.of()).stream()
                    .anyMatch(
                        r ->
                            unitType.equals(r.getResourceOrUnit())
                                && Integer.valueOf(1).equals(r.getQuantity())))
        .findFirst();
  }

  private static List<ProductionRule> productionRules(final Game game) {
    return Optional.ofNullable(game.getProduction())
        .map(Production::getProductionRules)
        .map(List::copyOf)
        .orElse(List.of());
  }

  /** The game with the unit costing this many PUs in its rule; nothing changes without one. */
  static Game withUnitCost(final Game game, final String unitType, final int pus) {
    final Optional<ProductionRule> changed = costRule(game, unitType);
    if (changed.isEmpty()) {
      return game;
    }
    final List<ProductionRule> rules =
        productionRules(game).stream()
            .map(
                rule ->
                    rule != changed.get()
                        ? rule
                        : rule.toBuilder()
                            .costs(
                                Optional.ofNullable(rule.getCosts()).orElse(List.of()).stream()
                                    .map(
                                        cost ->
                                            PUS.equals(cost.getResource())
                                                ? cost.toBuilder().quantity(pus).build()
                                                : cost)
                                    .toList())
                            .build())
            .toList();
    return game.toBuilder()
        .production(game.getProduction().toBuilder().productionRules(rules).build())
        .build();
  }

  // ---- game options

  static List<Property> properties(final Game game) {
    return Optional.ofNullable(game.getPropertyList())
        .map(PropertyList::getProperties)
        .map(List::copyOf)
        .orElse(List.of());
  }

  /** The game with the option's value; an option the game does not have is added, fixed. */
  static Game withPropertyValue(final Game game, final String name, final String value) {
    final List<Property> properties = new ArrayList<>();
    properties(game).stream()
        .map(p -> name.equals(p.getName()) ? p.toBuilder().value(value).build() : p)
        .forEach(properties::add);
    if (properties.stream().noneMatch(p -> name.equals(p.getName()))) {
      properties.add(Property.builder().name(name).value(value).editable(false).build());
    }
    return withProperties(game, properties);
  }

  private static Game withProperties(final Game game, final List<Property> properties) {
    return game.toBuilder()
        .propertyList(
            Optional.ofNullable(game.getPropertyList())
                .orElseGet(() -> PropertyList.builder().build())
                .toBuilder()
                .properties(properties)
                .build())
        .build();
  }

  /** The game's notes, the HTML the game shows players about it; empty for none. */
  static String notes(final Game game) {
    return properties(game).stream()
        .filter(p -> NOTES.equalsIgnoreCase(p.getName()))
        .findFirst()
        .map(
            p ->
                Optional.ofNullable(p.getValue())
                    .or(() -> Optional.ofNullable(p.getValueProperty()).map(Value::getData))
                    .orElse(""))
        .orElse("");
  }

  /** The game with these notes, in the notes option's &lt;value&gt; as the template has them. */
  static Game withNotes(final Game game, final String notes) {
    final Property written =
        Property.builder().name(NOTES).valueProperty(Value.builder().data(notes).build()).build();
    final List<Property> properties = new ArrayList<>();
    boolean placed = false;
    for (final Property property : properties(game)) {
      if (!NOTES.equalsIgnoreCase(property.getName())) {
        properties.add(property);
      } else if (!placed) {
        properties.add(written);
        placed = true;
      }
    }
    if (!placed) {
      properties.add(written);
    }
    return withProperties(game, properties);
  }

  /** The game named after its map, by which the game finds the map's files. */
  static Game withMapName(final Game game, final String mapName) {
    return withPropertyValue(game, Constants.MAP_NAME, mapName).toBuilder()
        .info(Info.builder().name(mapName).build())
        .build();
  }

  // ---- attachments

  /** Which attachment: its name, class, the kind of thing it is attached to, and that thing. */
  record AttachmentKey(String name, String javaClass, String type, String attachTo) {
    boolean matches(final Attachment attachment) {
      return name.equals(attachment.getName()) && attachTo.equals(attachment.getAttachTo());
    }
  }

  private static List<Attachment> attachments(final Game game) {
    return Optional.ofNullable(game.getAttachmentList())
        .map(AttachmentList::getAttachments)
        .map(List::copyOf)
        .orElse(List.of());
  }

  /** The option's value in the attachment of this name on the thing, eg its "production". */
  static Optional<String> option(
      final Game game, final String attachment, final String attachTo, final String option) {
    return attachments(game).stream()
        .filter(a -> attachment.equals(a.getName()) && attachTo.equals(a.getAttachTo()))
        .flatMap(a -> Optional.ofNullable(a.getOptions()).orElse(List.of()).stream())
        .filter(o -> option.equals(o.getName()))
        .map(Option::getValue)
        .filter(Objects::nonNull)
        .findFirst();
  }

  private static int intOption(
      final Game game, final String attachment, final String attachTo, final String option) {
    return option(game, attachment, attachTo, option).flatMap(MapXml::parseInt).orElse(0);
  }

  private static Optional<Integer> parseInt(final String value) {
    try {
      return Optional.of(Integer.parseInt(value.strip()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * The game with the option set in the attachment, or taken out for null. A missing attachment is
   * added after the others; options keep their place.
   */
  static Game withOption(
      final Game game, final AttachmentKey key, final String option, @Nullable final String value) {
    final List<Attachment> attachments = new ArrayList<>();
    boolean found = false;
    for (final Attachment attachment : attachments(game)) {
      if (!found && key.matches(attachment)) {
        attachments.add(withOption(attachment, option, value));
        found = true;
      } else {
        attachments.add(attachment);
      }
    }
    if (!found && value != null) {
      attachments.add(
          Attachment.builder()
              .name(key.name())
              .attachTo(key.attachTo())
              .javaClass(key.javaClass())
              .type(key.type())
              .options(List.of(Option.builder().name(option).value(value).build()))
              .build());
    }
    final AttachmentList list =
        Optional.ofNullable(game.getAttachmentList())
            .orElseGet(() -> AttachmentList.builder().build());
    return game.toBuilder()
        .attachmentList(list.toBuilder().attachments(attachments).build())
        .build();
  }

  private static Attachment withOption(
      final Attachment attachment, final String option, @Nullable final String value) {
    final List<Option> before = Optional.ofNullable(attachment.getOptions()).orElse(List.of());
    final boolean present = before.stream().anyMatch(o -> option.equals(o.getName()));
    final Stream<Option> kept =
        before.stream()
            .filter(o -> value != null || !option.equals(o.getName()))
            .map(
                o ->
                    option.equals(o.getName())
                        ? o.toBuilder().value(value).count(null).build()
                        : o);
    final List<Option> options =
        present || value == null
            ? kept.toList()
            : Stream.concat(kept, Stream.of(Option.builder().name(option).value(value).build()))
                .toList();
    return attachment.toBuilder().options(options).build();
  }
}
