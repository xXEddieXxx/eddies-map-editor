package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize;
import org.triplea.map.data.elements.Initialize.ResourceInitialize;
import org.triplea.map.data.elements.Initialize.ResourceInitialize.ResourceGiven;
import org.triplea.map.data.elements.Production;
import org.triplea.map.data.elements.Production.PlayerProduction;
import org.triplea.map.data.elements.Production.PlayerRepair;
import org.triplea.map.data.elements.Production.ProductionFrontier;
import org.triplea.map.data.elements.Production.ProductionFrontier.FrontierRules;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.map.data.elements.Production.RepairFrontier;
import org.triplea.map.data.elements.Production.RepairFrontier.RepairRules;
import org.triplea.map.data.elements.Production.RepairRule;
import org.triplea.map.data.elements.Production.Rule.Cost;
import org.triplea.map.data.elements.Production.Rule.Result;
import org.triplea.map.data.elements.ResourceList;
import org.triplea.map.data.elements.ResourceList.Resource;

/**
 * What nations can buy: the production rules (what a purchase costs and gives), the frontiers (the
 * lists of rules a nation buys from) and which frontier each nation has; the same for repairs,
 * through {@link #repairView}; and the resources. Every change returns a new model, as in {@link
 * MapXml}.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class ProductionXml {

  static List<ProductionRule> rules(final Game game) {
    return Optional.ofNullable(game.getProduction())
        .map(Production::getProductionRules)
        .map(List::<ProductionRule>copyOf)
        .orElse(List.of());
  }

  /** The game's resources, eg PUs, which rules cost. */
  static List<String> resources(final Game game) {
    return Optional.ofNullable(game.getResourceList())
        .map(ResourceList::getResources)
        .map(List::<Resource>copyOf)
        .orElse(List.of())
        .stream()
        .map(Resource::getName)
        .toList();
  }

  static List<String> frontiers(final Game game) {
    return frontierElements(game).stream().map(ProductionFrontier::getName).toList();
  }

  private static List<ProductionFrontier> frontierElements(final Game game) {
    return Optional.ofNullable(game.getProduction())
        .map(Production::getProductionFrontiers)
        .map(List::<ProductionFrontier>copyOf)
        .orElse(List.of());
  }

  /** The names of the rules in the frontier, in its order. */
  static List<String> rulesIn(final Game game, final String frontier) {
    return frontierElements(game).stream()
        .filter(f -> frontier.equals(f.getName()))
        .flatMap(f -> frontierRules(f).stream())
        .map(FrontierRules::getName)
        .toList();
  }

  private static List<FrontierRules> frontierRules(final ProductionFrontier frontier) {
    return Optional.ofNullable(frontier.getFrontierRules()).map(List::copyOf).orElse(List.of());
  }

  private static List<PlayerProduction> playerProductions(final Game game) {
    return Optional.ofNullable(game.getProduction())
        .map(Production::getPlayerProductions)
        .map(List::<PlayerProduction>copyOf)
        .orElse(List.of());
  }

  /** The frontier the nation buys from; empty when it buys nothing. */
  static Optional<String> frontierOf(final Game game, final String nation) {
    return playerProductions(game).stream()
        .filter(p -> nation.equals(p.getPlayer()))
        .map(PlayerProduction::getFrontier)
        .findFirst();
  }

  /** The game with the nation buying from the frontier. */
  static Game withFrontierOf(final Game game, final String nation, final String frontier) {
    return gameWithFrontierOf(game, nation, frontier);
  }

  /** The game with the nation buying nothing. */
  static Game withoutFrontierOf(final Game game, final String nation) {
    return gameWithFrontierOf(game, nation, null);
  }

  private static Game gameWithFrontierOf(
      final Game game, final String nation, @Nullable final String frontier) {
    final List<PlayerProduction> productions = new ArrayList<>();
    boolean placed = false;
    for (final PlayerProduction production : playerProductions(game)) {
      if (!nation.equals(production.getPlayer())) {
        productions.add(production);
      } else if (frontier != null && !placed) {
        productions.add(production.toBuilder().frontier(frontier).build());
        placed = true;
      }
    }
    if (frontier != null && !placed) {
      productions.add(PlayerProduction.builder().player(nation).frontier(frontier).build());
    }
    return withProduction(game, p -> p.playerProductions(productions));
  }

  /** The game with a new, empty frontier; nothing changes for a blank or taken name. */
  static Game withFrontier(final Game game, final String frontier) {
    if (frontier.isBlank() || frontiers(game).contains(frontier)) {
      return game;
    }
    return withFrontiers(
        game,
        Stream.concat(
                frontierElements(game).stream(),
                Stream.of(
                    ProductionFrontier.builder().name(frontier).frontierRules(List.of()).build()))
            .toList());
  }

  /** The game without the frontier; the nations that bought from it buy nothing. */
  static Game withoutFrontier(final Game game, final String frontier) {
    final Game without =
        withFrontiers(
            game,
            frontierElements(game).stream().filter(f -> !frontier.equals(f.getName())).toList());
    return withProduction(
        without,
        p ->
            p.playerProductions(
                playerProductions(game).stream()
                    .filter(pp -> !frontier.equals(pp.getFrontier()))
                    .toList()));
  }

  /** The game with the rule sold in the frontier, after the others, or not sold there. */
  static Game withRuleIn(final Game game, final String frontier, final String rule) {
    return withFrontierRules(
        game,
        frontier,
        rules ->
            rules.stream().anyMatch(r -> rule.equals(r.getName()))
                ? rules
                : Stream.concat(
                        rules.stream(), Stream.of(FrontierRules.builder().name(rule).build()))
                    .toList());
  }

  static Game withoutRuleIn(final Game game, final String frontier, final String rule) {
    return withFrontierRules(
        game, frontier, rules -> rules.stream().filter(r -> !rule.equals(r.getName())).toList());
  }

  private static Game withFrontierRules(
      final Game game, final String frontier, final UnaryOperator<List<FrontierRules>> change) {
    return withFrontiers(
        game,
        frontierElements(game).stream()
            .map(
                f ->
                    frontier.equals(f.getName())
                        ? f.toBuilder().frontierRules(change.apply(frontierRules(f))).build()
                        : f)
            .toList());
  }

  /**
   * The game with a new rule that sells one of the unit (or resource) for the cost, in PUs or the
   * game's first resource without them (none without resources), sold in the frontier; its name,
   * eg "buyTank", made free.
   */
  static Game withNewRule(
      final Game game, final String frontier, final String resourceOrUnit, final int cost) {
    return withNewRule(game, frontier, resourceOrUnit, cost, "buy");
  }

  /** As {@link #withNewRule(Game, String, String, int)}, its name starting with the prefix. */
  static Game withNewRule(
      final Game game,
      final String frontier,
      final String resourceOrUnit,
      final int cost,
      final String prefix) {
    final Set<String> taken =
        rules(game).stream().map(ProductionRule::getName).collect(Collectors.toSet());
    final String name =
        RulesXml.freeName(
            taken, prefix + RulesXml.upperFirst(resourceOrUnit.replaceAll("[^A-Za-z0-9]", "")));
    final ProductionRule rule =
        ProductionRule.builder()
            .name(name)
            .costs(
                resources(game).stream()
                    .filter(MapXml.PUS::equals)
                    .findFirst()
                    .or(() -> resources(game).stream().findFirst())
                    .map(r -> List.of(Cost.builder().resource(r).quantity(cost).build()))
                    .orElse(List.of()))
            .results(List.of(Result.builder().resourceOrUnit(resourceOrUnit).quantity(1).build()))
            .build();
    return withRuleIn(
        withRules(game, Stream.concat(rules(game).stream(), Stream.of(rule)).toList()),
        frontier,
        name);
  }

  /** The game without the rule, also out of every frontier. */
  static Game withoutRule(final Game game, final String rule) {
    Game without =
        withRules(game, rules(game).stream().filter(r -> !rule.equals(r.getName())).toList());
    for (final String frontier : frontiers(game)) {
      without = withoutRuleIn(without, frontier, rule);
    }
    return without;
  }

  /** What the rule costs of the resource, 0 when nothing. */
  static int cost(final ProductionRule rule, final String resource) {
    return costs(rule).stream()
        .filter(c -> resource.equals(c.getResource()))
        .map(Cost::getQuantity)
        .filter(q -> q != null)
        .findFirst()
        .orElse(0);
  }

  private static List<Cost> costs(final ProductionRule rule) {
    return Optional.ofNullable(rule.getCosts()).map(List::copyOf).orElse(List.of());
  }

  /** The game with the rule costing this much of the resource; 0 takes the cost out. */
  static Game withCost(
      final Game game, final String rule, final String resource, final int quantity) {
    return withRule(
        game,
        rule,
        r -> {
          final Predicate<Cost> isResource = c -> resource.equals(c.getResource());
          final List<Cost> costs = new ArrayList<>();
          boolean placed = false;
          for (final Cost cost : costs(r)) {
            if (!isResource.test(cost)) {
              costs.add(cost);
            } else if (quantity != 0 && !placed) {
              costs.add(cost.toBuilder().quantity(quantity).build());
              placed = true;
            }
          }
          if (quantity != 0 && !placed) {
            costs.add(Cost.builder().resource(resource).quantity(quantity).build());
          }
          return r.toBuilder().costs(costs).build();
        });
  }

  /** The first thing the rule gives, eg one infantry. */
  static Optional<Result> result(final ProductionRule rule) {
    return Optional.ofNullable(rule.getResults()).orElse(List.of()).stream().findFirst();
  }

  /** The game with the rule's first result replaced; any further results stay. */
  static Game withResult(
      final Game game, final String rule, final String resourceOrUnit, final int quantity) {
    return withRule(
        game,
        rule,
        r -> {
          final List<Result> results =
              new ArrayList<>(Optional.ofNullable(r.getResults()).orElse(List.of()));
          final Result first =
              results.isEmpty()
                  ? Result.builder().resourceOrUnit(resourceOrUnit).quantity(quantity).build()
                  : results.get(0).toBuilder()
                      .resourceOrUnit(resourceOrUnit)
                      .quantity(quantity)
                      .build();
          if (results.isEmpty()) {
            results.add(first);
          } else {
            results.set(0, first);
          }
          return r.toBuilder().results(results).build();
        });
  }

  private static Game withRule(
      final Game game, final String rule, final UnaryOperator<ProductionRule> change) {
    return withRules(
        game,
        rules(game).stream().map(r -> rule.equals(r.getName()) ? change.apply(r) : r).toList());
  }

  private static Game withRules(final Game game, final List<ProductionRule> rules) {
    return withProduction(game, p -> p.productionRules(rules));
  }

  private static Game withFrontiers(final Game game, final List<ProductionFrontier> frontiers) {
    return withProduction(game, p -> p.productionFrontiers(frontiers));
  }

  private static Game withProduction(
      final Game game, final UnaryOperator<Production.ProductionBuilder> change) {
    final Production production =
        Optional.ofNullable(game.getProduction()).orElseGet(() -> Production.builder().build());
    return game.toBuilder().production(change.apply(production.toBuilder()).build()).build();
  }

  // ---- repair: rules, frontiers and nations as for buying, in lists of their own

  /**
   * The game with its repair rules, repair frontiers and the nations' repair frontiers in the
   * places of the production ones, so that the methods above read and change them.
   */
  static Game repairView(final Game game) {
    final Optional<Production> production = Optional.ofNullable(game.getProduction());
    return game.toBuilder()
        .production(
            Production.builder()
                .productionRules(
                    production.map(Production::getRepairRules).orElse(List.of()).stream()
                        .map(ProductionXml::asProductionRule)
                        .toList())
                .productionFrontiers(
                    production.map(Production::getRepairFrontiers).orElse(List.of()).stream()
                        .map(ProductionXml::asProductionFrontier)
                        .toList())
                .playerProductions(
                    production.map(Production::getPlayerRepairs).orElse(List.of()).stream()
                        .map(p -> new PlayerProduction(p.getPlayer(), p.getFrontier()))
                        .toList())
                .build())
        .build();
  }

  private static ProductionRule asProductionRule(final RepairRule rule) {
    return new ProductionRule(rule.getName(), rule.getCosts(), rule.getResults());
  }

  private static ProductionFrontier asProductionFrontier(final RepairFrontier frontier) {
    return new ProductionFrontier(
        frontier.getName(),
        Optional.ofNullable(frontier.getRepairRules()).orElse(List.of()).stream()
            .map(r -> new FrontierRules(r.getName()))
            .toList());
  }

  /**
   * The game changed as the change changes its {@link #repairView}: its repair lists then are the
   * changed view's; the same game when the change changes nothing.
   */
  static Game withRepairChange(final Game game, final UnaryOperator<Game> change) {
    final Game view = repairView(game);
    final Game changed = change.apply(view);
    if (changed == view) {
      return game;
    }
    return withProduction(
        changed.toBuilder().production(game.getProduction()).build(),
        p ->
            p.repairRules(
                    rules(changed).stream()
                        .map(r -> new RepairRule(r.getName(), r.getCosts(), r.getResults()))
                        .toList())
                .repairFrontiers(
                    frontierElements(changed).stream()
                        .map(ProductionXml::asRepairFrontier)
                        .toList())
                .playerRepairs(
                    playerProductions(changed).stream()
                        .map(r -> new PlayerRepair(r.getPlayer(), r.getFrontier()))
                        .toList()));
  }

  private static RepairFrontier asRepairFrontier(final ProductionFrontier frontier) {
    return new RepairFrontier(
        frontier.getName(),
        frontierRules(frontier).stream().map(r -> new RepairRules(r.getName())).toList());
  }

  // ---- resources

  /** The game with a new resource, after the others; nothing changes for a blank or taken name. */
  static Game withResource(final Game game, final String resource) {
    if (resource.isBlank() || resources(game).contains(resource)) {
      return game;
    }
    return withResources(
        game,
        Stream.concat(resourceElements(game).stream(), Stream.of(new Resource(resource, null)))
            .toList());
  }

  private static List<Resource> resourceElements(final Game game) {
    return Optional.ofNullable(game.getResourceList())
        .map(ResourceList::getResources)
        .map(List::<Resource>copyOf)
        .orElse(List.of());
  }

  private static Game withResources(final Game game, final List<Resource> resources) {
    return game.toBuilder()
        .resourceList(ResourceList.builder().resources(resources).build())
        .build();
  }

  /**
   * Why the resource cannot be renamed, eg a trigger that gives it; empty when it can. TripleA
   * itself reads PUs, tech tokens and VPs by their names.
   */
  static Optional<String> whyResourceNotRenamed(final Game game, final String resource) {
    if (Set.of(Constants.PUS, Constants.TECH_TOKENS, Constants.VPS).contains(resource)) {
      return Optional.of("TripleA reads " + resource + " by this name.");
    }
    final List<String> places = RulesXml.whereNamed(game, resource);
    return places.isEmpty()
        ? Optional.empty()
        : Optional.of("It is named in " + String.join(", ", places) + ". Change it there first.");
  }

  /** Why the resource cannot be removed, eg a rule that costs it; empty when it can. */
  static Optional<String> whyResourceStays(final Game game, final String resource) {
    final Optional<String> named = whyResourceNotRenamed(game, resource);
    if (named.isPresent()) {
      return named;
    }
    final List<String> rules =
        Stream.concat(rules(game).stream(), rules(repairView(game)).stream())
            .filter(r -> cost(r, resource) != 0 || gives(r, resource))
            .map(ProductionRule::getName)
            .toList();
    return rules.isEmpty()
        ? Optional.empty()
        : Optional.of("The rules " + String.join(", ", rules) + " cost or give it.");
  }

  private static boolean gives(final ProductionRule rule, final String resource) {
    return Optional.ofNullable(rule.getResults()).orElse(List.of()).stream()
        .anyMatch(r -> resource.equals(r.getResourceOrUnit()));
  }

  /**
   * The game with the resource renamed in its list, the nations' amounts at the start and the
   * costs and results of the rules to buy and repair; nothing changes for a blank or taken name.
   */
  static Game withRenamedResource(final Game game, final String old, final String renamed) {
    if (renamed.isBlank() || resources(game).contains(renamed)) {
      return game;
    }
    final UnaryOperator<String> rename = name -> old.equals(name) ? renamed : name;
    final UnaryOperator<Game> inRules =
        g -> withRules(g, rules(g).stream().map(r -> renamedIn(r, rename)).toList());
    final Game withList =
        withResources(
            game,
            resourceElements(game).stream()
                .map(
                    r ->
                        Resource.builder()
                            .name(rename.apply(r.getName()))
                            .isDisplayedFor(r.getIsDisplayedFor())
                            .build())
                .toList());
    final Game withRules =
        game.getProduction() == null
            ? withList
            : withRepairChange(inRules.apply(withList), inRules);
    return withGiven(
        withRules,
        given(game).stream()
            .map(g -> g.toBuilder().resource(rename.apply(g.getResource())).build())
            .toList());
  }

  private static ProductionRule renamedIn(
      final ProductionRule rule, final UnaryOperator<String> rename) {
    return rule.toBuilder()
        .costs(
            costs(rule).stream()
                .map(c -> c.toBuilder().resource(rename.apply(c.getResource())).build())
                .toList())
        .results(
            Optional.ofNullable(rule.getResults()).orElse(List.of()).stream()
                .map(r -> r.toBuilder().resourceOrUnit(rename.apply(r.getResourceOrUnit())).build())
                .toList())
        .build();
  }

  /** The game without the resource and the nations' amounts of it at the start. */
  static Game withoutResource(final Game game, final String resource) {
    return withGiven(
        withResources(
            game,
            resourceElements(game).stream().filter(r -> !resource.equals(r.getName())).toList()),
        given(game).stream().filter(g -> !resource.equals(g.getResource())).toList());
  }

  private static List<ResourceGiven> given(final Game game) {
    return Optional.ofNullable(game.getInitialize())
        .map(Initialize::getResourceInitialize)
        .map(ResourceInitialize::getResourcesGiven)
        .map(List::<ResourceGiven>copyOf)
        .orElse(List.of());
  }

  private static Game withGiven(final Game game, final List<ResourceGiven> given) {
    if (game.getInitialize() == null) {
      return game;
    }
    final Initialize initialize = game.getInitialize();
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
}
