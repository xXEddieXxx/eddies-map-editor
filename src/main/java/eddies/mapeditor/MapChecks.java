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

  /**
   * The problems the map maker finds itself, quick enough to look for after every change. What the
   * steps to a playable map say ({@link MapGuide#steps}: no territories, no nations, a nation
   * without land or capital) is none: finished maps have such nations on purpose.
   */
  static List<Problem> quick(final Game game, final MapFiles files) {
    final List<Problem> problems = new ArrayList<>();
    final List<String> territories = MapXml.territories(game);
    final Set<String> known = new HashSet<>(territories);
    final Set<String> nations = new HashSet<>(MapXml.nations(game));
    for (final String territory : territories) {
      if (files.hasOutlines() && !files.polygons().containsKey(territory)) {
        problems.add(Problem.at(territory, territory + " has no outline on the map"));
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
   * Worth a look, but no problem: territories without a neighbour, which many finished maps have on
   * purpose, eg a box for units off the board.
   */
  static List<Problem> notes(final Game game) {
    final Set<String> namedByRules = namedByRules(game);
    // an impassable territory, eg marshes, may well stay apart
    // so may one the rules name, eg a dial whose owner triggers change
    return MapXml.territories(game).stream()
        .filter(
            territory ->
                MapXml.neighbors(game, territory).isEmpty()
                    && !MapXml.isImpassable(game, territory)
                    && !namedByRules.contains(territory))
        .map(
            territory ->
                new Problem(
                    territory + " is not connected to any territory", territory, "Connections"))
        .toList();
  }

  /**
   * The names that attachments' options (other than of the attachments to that name itself) and
   * variables list, in one pass, as maps have up to 35,000 attachments.
   */
  private static Set<String> namedByRules(final Game game) {
    final Set<String> names = new HashSet<>();
    for (final var attachment : AttachmentsXml.attachments(game)) {
      for (final var option : Optional.ofNullable(attachment.getOptions()).orElse(List.of())) {
        if (option.getValue() == null) {
          continue;
        }
        // values list names joined by colons, "-" before one taken away, as in RulesXml.whereNamed
        Stream.of(option.getValue().split(":"))
            .map(part -> part.startsWith("-") ? part.substring(1) : part)
            .filter(part -> !part.equals(attachment.getAttachTo()))
            .forEach(names::add);
      }
    }
    Optional.ofNullable(game.getVariableList()).stream()
        .flatMap(list -> Optional.ofNullable(list.getVariables()).orElse(List.of()).stream())
        .flatMap(v -> Optional.ofNullable(v.getElements()).orElse(List.of()).stream())
        .forEach(element -> names.add(element.getName()));
    return names;
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
