package eddies.mapeditor;

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
import org.triplea.map.data.elements.Production;
import org.triplea.map.data.elements.Production.PlayerProduction;
import org.triplea.map.data.elements.Production.ProductionFrontier;
import org.triplea.map.data.elements.Production.ProductionFrontier.FrontierRules;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.map.data.elements.Production.Rule.Cost;
import org.triplea.map.data.elements.Production.Rule.Result;
import org.triplea.map.data.elements.ResourceList;
import org.triplea.map.data.elements.ResourceList.Resource;

/**
 * What nations can buy: the production rules (what a purchase costs and gives), the frontiers (the
 * lists of rules a nation buys from) and which frontier each nation has. Every change returns a new
 * model, as in {@link MapXml}.
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
    final Set<String> taken =
        rules(game).stream().map(ProductionRule::getName).collect(Collectors.toSet());
    final String name =
        RulesXml.freeName(
            taken, "buy" + RulesXml.upperFirst(resourceOrUnit.replaceAll("[^A-Za-z0-9]", "")));
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
}
