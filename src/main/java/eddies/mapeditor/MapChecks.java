package eddies.mapeditor;

import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.data.gameparser.XmlGameElementMapper;
import games.strategy.triplea.Constants;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.xml.writer.GameXmlWriter;

/** The problems of a map, each with the territory to show for it if there is one. */
@UtilityClass
class MapChecks {

  /**
   * A problem, worded for the map maker, and where it is.
   *
   * @param place the territory to show for it, if it is about one
   * @param fixedIn the title of the tool that fixes it, if the text names one
   */
  record Problem(String text, @Nullable String place, @Nullable String fixedIn) {
    static Problem of(final String text) {
      return new Problem(text, null, null);
    }

    static Problem at(final String territory, final String text) {
      return new Problem(text, territory, null);
    }

    static Problem in(final String tool, final String text) {
      return new Problem(text, null, tool);
    }

    /** The territory to show for the problem, if it is about one. */
    Optional<String> territory() {
      return Optional.ofNullable(place);
    }

    /** The tool that fixes the problem, if the problem names one. */
    Optional<String> tool() {
      return Optional.ofNullable(fixedIn);
    }
  }

  /** The problems the map maker finds itself, quick enough to look for after every change. */
  static List<Problem> quick(final Game game, final MapFiles files) {
    final List<Problem> problems = new ArrayList<>();
    final List<String> territories = MapXml.territories(game);
    final Set<String> known = new HashSet<>(territories);
    final Set<String> nations = new HashSet<>(MapXml.nations(game));
    if (!files.hasOutlines()) {
      problems.add(
          Problem.in(
              "Territories",
              files.picture().isPresent()
                  ? "No territories yet: press Find territories in Territories"
                  : "No picture yet: load one in Territories"));
    }
    if (nations.isEmpty()) {
      problems.add(Problem.in("Nations", "No nations yet: add them in Nations"));
    } else if (!territories.isEmpty()) {
      final Set<String> owning =
          territories.stream()
              .flatMap(t -> MapXml.owner(game, t).stream())
              .collect(Collectors.toSet());
      // only nations that take turns: a map's neutrals' player owns no land on purpose
      MapXml.nations(game).stream()
          .filter(nation -> MapXml.takesTurns(game, nation))
          .filter(nation -> !owning.contains(nation))
          .forEach(
              nation -> problems.add(Problem.in("Territories", nation + " owns no territory")));
    }
    for (final String territory : territories) {
      if (files.hasOutlines() && !files.polygons().containsKey(territory)) {
        problems.add(Problem.at(territory, territory + " has no outline on the map"));
      }
      // an impassable territory, eg marshes, may well stay apart
      if (MapXml.neighbors(game, territory).isEmpty() && !MapXml.isImpassable(game, territory)) {
        problems.add(
            new Problem(
                territory + " is not connected to any territory", territory, "Connections"));
      }
      // the engine's owner of neutral land is a player of every game without being listed
      MapXml.owner(game, territory)
          .filter(owner -> !nations.contains(owner) && !Constants.PLAYER_NAME_NEUTRAL.equals(owner))
          .ifPresent(
              owner ->
                  problems.add(
                      Problem.at(territory, territory + " belongs to " + owner + ", no nation")));
      for (final UnitPlacement units : MapXml.units(game, territory)) {
        if (!MapXml.unitTypes(game).contains(units.getUnitType())) {
          problems.add(
              Problem.at(territory, territory + " has units of no type: " + units.getUnitType()));
        }
      }
    }
    MapXml.connections(game).stream()
        .flatMap(c -> Stream.of(c.getT1(), c.getT2()))
        .filter(t -> !known.contains(t))
        .distinct()
        .forEach(
            t ->
                problems.add(
                    Problem.in("Connections", "A connection leads to " + t + ", no territory")));
    final boolean mapHasCapitals = territories.stream().anyMatch(t -> MapXml.isCapital(game, t));
    if (mapHasCapitals) {
      // a nation without land, eg the neutrals' player, needs none
      final Set<String> landed =
          new HashSet<>(territories.stream().flatMap(t -> MapXml.owner(game, t).stream()).toList());
      landed.remove(Constants.PLAYER_NAME_NEUTRAL);
      for (final String nation : landed) {
        final boolean hasCapital =
            territories.stream()
                .anyMatch(t -> MapXml.capitalOf(game, t).filter(nation::equals).isPresent());
        if (!hasCapital) {
          // shown at one of its territories, where Territories makes a capital
          final String owned =
              territories.stream()
                  .filter(t -> MapXml.owner(game, t).filter(nation::equals).isPresent())
                  .findFirst()
                  .orElseThrow();
          problems.add(Problem.at(owned, nation + " has no capital"));
        }
      }
    }
    // the game refuses a canal without exactly two sea zones or without land
    for (final CanalXml.Canal canal : CanalXml.canals(game)) {
      if (canal.seaZones().size() != 2 || canal.lands().isEmpty()) {
        problems.add(
            new Problem(
                "The canal "
                    + canal.name()
                    + " needs two sea zones and a land territory: change it in Canals",
                canal.seaZones().isEmpty() ? null : canal.seaZones().getFirst(),
                "Canals"));
      }
    }
    return problems;
  }

  /**
   * The problems of {@link #quick} and, if the engine does not load the game, why: the check of
   * Validate, which takes a moment.
   */
  static List<Problem> all(final Game game, final MapFiles files) {
    final List<Problem> problems = new ArrayList<>(quick(game, files));
    engineError(game)
        .map(error -> "The game does not load: " + error)
        .map(Problem::of)
        .ifPresent(problems::add);
    return problems;
  }

  private static Optional<String> engineError(final Game game) {
    try {
      final Path xml = Files.createTempFile("map-maker-check", ".xml");
      try {
        GameXmlWriter.exportXml(game, xml);
        return findError(xml);
      } finally {
        Files.deleteIfExists(xml);
      }
    } catch (final IOException e) {
      return Optional.of("could not write it to check it: " + e.getMessage());
    }
  }

  /**
   * Why TripleA does not load the game XML, eg a unit placed in a territory the map does not have;
   * empty when it loads.
   */
  static Optional<String> findError(final Path xml) {
    // TripleA's public parse only logs the reason; its parser itself throws it
    final Object parser;
    final Method parse;
    try {
      final Constructor<GameParser> constructor =
          GameParser.class.getDeclaredConstructor(
              Path.class, XmlGameElementMapper.class, boolean.class);
      constructor.setAccessible(true);
      parser = constructor.newInstance(xml, new XmlGameElementMapper(), false);
      parse = GameParser.class.getDeclaredMethod("parse", InputStream.class);
      parse.setAccessible(true);
    } catch (final ReflectiveOperationException e) {
      // a TripleA whose parser looks otherwise: whether it loads, without the reason
      return GameParser.parse(xml, false).isPresent()
          ? Optional.empty()
          : Optional.of("TripleA cannot read it, its log says why");
    }
    try (InputStream in = Files.newInputStream(xml)) {
      parse.invoke(parser, in);
      return Optional.empty();
    } catch (final InvocationTargetException e) {
      return Optional.of(message(e.getCause()));
    } catch (final IOException | IllegalAccessException e) {
      return Optional.of(message(e));
    }
  }

  private static String message(final Throwable e) {
    return Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
  }
}
