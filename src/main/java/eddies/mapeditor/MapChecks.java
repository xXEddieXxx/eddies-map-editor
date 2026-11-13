package eddies.mapeditor;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.data.gameparser.XmlGameElementMapper;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.ui.mapdata.MapData;
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
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.Initialize.UnitInitialize.HeldUnits;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.xml.writer.GameXmlWriter;
import org.triplea.util.Tuple;
import eddies.mapeditor.MapFiles.NationUnit;

/** The problems of a map, each with the territory to show for it if there is one. */
@UtilityClass
class MapChecks {

  /**
   * A problem, worded for the map maker, and where it is.
   *
   * @param place the territory to show for it, if it is about one
   * @param fixedIn the title of the tool that fixes it, if the text names one
   */
  record Problem(
      String text, @Nullable String place, @Nullable String fixedIn, @Nullable String key) {
    Problem(final String text, @Nullable final String place, @Nullable final String fixedIn) {
      this(text, place, fixedIn, null);
    }

    /**
     * The problem as one the map maker may mark "Fine as it is": no problem that keeps TripleA
     * from loading or starting the game. Known again by its kind and what it is about, not by
     * its text, so a mark outlives a reworded text and follows a rename.
     *
     * @param kind what is wrong in one word without a dot, eg noOutline
     * @param about the territory or nation it is about, empty for the whole map
     */
    Problem markable(final String kind, final String about) {
      return new Problem(text, place, fixedIn, about.isEmpty() ? kind : kind + "." + about);
    }

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
   * without land or capital) is none: finished maps have such nations on purpose. Nor is what
   * the map maker marked as fine ({@link MapFiles#isFine}).
   */
  static List<Problem> quick(final Game game, final MapFiles files) {
    return quickWithFine(game, files).stream().filter(p -> !files.isFine(p)).toList();
  }

  /** As {@link #quick}, with the problems marked as fine. */
  static List<Problem> quickWithFine(final Game game, final MapFiles files) {
    final List<Problem> problems = new ArrayList<>();
    final List<String> territories = MapXml.territories(game);
    final Set<String> known = new HashSet<>(territories);
    final Set<String> nations = new HashSet<>(MapXml.nations(game));
    for (final String territory : territories) {
      if (files.hasOutlines() && !files.polygons().containsKey(territory)) {
        problems.add(
            Problem.at(territory, territory + " has no outline on the map")
                .markable("noOutline", territory));
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
    problems.addAll(ofNoNation(game, nations));
    classicWithoutCapital(game)
        .forEach(
            nation ->
                problems.add(
                    Problem.in(
                        "Nations",
                        nation
                            + " needs a capital: a game of just Russians, Germans, British,"
                            + " Japanese and Americans stops after the first round without"
                            + " one (mark it in Territories, or turn on WW2V2 in Game"
                            + " options)")));
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
   * What names a nation the game does not have, or lacks one it needs: TripleA's parser takes
   * these and fails later, while playing ("gamePlayer is null"): a unit at the start, a capital,
   * a step of a nation that is none, and a step without a nation whose delegate asks for one.
   * Held units too, which the parser refuses.
   */
  private static List<Problem> ofNoNation(final Game game, final Set<String> nations) {
    final List<Problem> problems = new ArrayList<>();
    final Predicate<String> unknown =
        name ->
            name != null
                && !name.isBlank()
                && !nations.contains(name)
                && !Constants.PLAYER_NAME_NEUTRAL.equals(name);
    MapXml.placements(game).stream()
        .filter(units -> unknown.test(units.getOwner()))
        .map(units -> Tuple.of(units.getTerritory(), units.getOwner()))
        .distinct()
        .forEach(
            units ->
                problems.add(
                    Problem.at(
                        units.getFirst(),
                        "Units in "
                            + units.getFirst()
                            + " belong to "
                            + units.getSecond()
                            + ", no nation of the game: give them an owner in Territories")));
    MapXml.heldUnits(game).stream()
        .map(HeldUnits::getPlayer)
        .filter(unknown)
        .distinct()
        .forEach(
            nation ->
                problems.add(
                    Problem.in("Nations", "Held units belong to " + nation + ", no nation")));
    for (final Attachment attachment : AttachmentsXml.attachments(game)) {
      // one made by foreach names its nation as a variable, eg capital="@Player@"
      if (AttachmentsXml.isNamed(attachment, Constants.TERRITORY_ATTACHMENT_NAME)
          && attachment.getForeach() == null
          && !String.valueOf(attachment.getAttachTo()).contains("@")) {
        AttachmentsXml.options(attachment).stream()
            .filter(
                o ->
                    AttachmentsXml.isOption(o, "capital")
                        && unknown.test(o.getValue())
                        && !o.getValue().contains("@"))
            .forEach(
                o ->
                    problems.add(
                        Problem.at(
                            attachment.getAttachTo(),
                            attachment.getAttachTo()
                                + " is the capital of "
                                + o.getValue()
                                + ", which is no nation: untick it in Territories")));
      }
    }
    for (final Step step : RulesXml.steps(game)) {
      if (unknown.test(step.getPlayer())) {
        problems.add(
            Problem.in(
                "Turn order",
                "Step " + step.getName() + " is of " + step.getPlayer() + ", no nation"));
      } else if (GameRulesXml.lacksNation(game, step)) {
        problems.add(
            Problem.in(
                "Turn order",
                "Step "
                    + step.getName()
                    + " needs a nation: choose one in Turn order, or TripleA stops there"));
      }
    }
    return problems;
  }


  /**
   * Of the five nations of the classic game, those without a capital, where TripleA looks for
   * them: its EndRoundDelegate asks each of them for its capital at the end of every round, and
   * throws without one, in a game that has all five, no more than five players and neither WW2V2
   * nor WW2V3. "Has no capital" in Nations does not help there.
   */
  static List<String> classicWithoutCapital(final Game game) {
    final List<String> classic =
        List.of(
            Constants.PLAYER_NAME_RUSSIANS,
            Constants.PLAYER_NAME_GERMANS,
            Constants.PLAYER_NAME_BRITISH,
            Constants.PLAYER_NAME_JAPANESE,
            Constants.PLAYER_NAME_AMERICANS);
    final List<String> nations = MapXml.nations(game);
    if (nations.size() > classic.size()
        || !nations.containsAll(classic)
        || MapXml.properties(game).stream()
            .anyMatch(
                p ->
                    ("WW2V2".equals(p.getName()) || "WW2V3".equals(p.getName()))
                        && Boolean.parseBoolean(GameRulesXml.value(p)))) {
      return List.of();
    }
    final Set<String> withCapital =
        MapXml.nationsWithCapital(game, Set.copyOf(MapXml.territories(game)));
    return classic.stream().filter(n -> !withCapital.contains(n)).toList();
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
                        territory + " is not connected to any territory", territory, "Connections")
                    .markable("notConnected", territory))
        .toList();
  }

  /**
   * Worth a look: the units are big for the map, when half of its land territories cannot hold
   * even one unit wholly inside at the units' size now ({@link MapFiles#unitRoom}); smaller units
   * or a bigger map, both in Map look, give them room. Finished maps with tight land, eg Pacific
   * or Domination, hold one or two in most and get no note. Looks at every land outline, so it is
   * asked once per change of the files, not for each paint.
   */
  static Optional<Problem> unitRoomNote(final Game game, final MapFiles files) {
    final Set<String> seas = MapXml.seaZones(game);
    final List<Integer> room =
        files
            .unitRoom(MapXml.territories(game).stream().filter(t -> !seas.contains(t)).toList())
            .values()
            .stream()
            .toList();
    if (room.isEmpty()) {
      return Optional.empty();
    }
    final long tooSmall = room.stream().filter(r -> r == 0).count();
    return 2 * tooSmall <= room.size()
        ? Optional.empty()
        : Optional.of(
            Problem.in(
                "Map look",
                "Units are big for this map: "
                    + tooSmall
                    + " of "
                    + room.size()
                    + " land territories are too small for even one. Make the units smaller or"
                    + " the map bigger in Map look.")
                .markable("unitRoom", ""));
  }

  /**
   * The unit types a nation can have: the ones it can buy (its purchase list) and the ones it
   * starts with on the map, in the game's order; not what only triggers give it.
   */
  static List<String> unitsOf(final Game game, final String nation) {
    final Set<String> rules =
        ProductionXml.frontierOf(game, nation)
            .map(frontier -> Set.copyOf(ProductionXml.rulesIn(game, frontier)))
            .orElse(Set.of());
    final Set<String> its = new HashSet<>();
    ProductionXml.rules(game).stream()
        .filter(rule -> rules.contains(rule.getName()))
        .flatMap(rule -> Optional.ofNullable(rule.getResults()).orElse(List.of()).stream())
        .forEach(result -> its.add(result.getResourceOrUnit()));
    MapXml.placements(game).stream()
        .filter(units -> nation.equals(units.getOwner()))
        .forEach(units -> its.add(units.getUnitType()));
    return MapXml.unitTypes(game).stream().filter(its::contains).toList();
  }

  /**
   * Pictures the game would miss, worth a look as it then draws a placeholder (the map still
   * loads): a nation's unit types without a picture, one note per nation, and its flags. Only of
   * nations and unit types the saved game has: a save writes plain ones for new ones. Reads the
   * map's folder, so not for every change.
   */
  static List<Problem> pictureNotes(final Game game, final Game saved, final MapFiles files) {
    final Optional<Path> folder = files.folder();
    if (folder.isEmpty()) {
      return List.of();
    }
    final Set<String> forAll = MapFolder.names(folder.get().resolve("units"));
    final Set<String> savedUnits = Set.copyOf(MapXml.unitTypes(saved));
    final Set<String> savedNations = Set.copyOf(MapXml.nations(saved));
    final List<String> flags = new ArrayList<>(List.of("", "_small", "_large"));
    // the faded flag marks kamikaze zones, the convoy flag convoy zones on a map that asks for it
    if (AttachmentsXml.attachments(game).stream()
        .flatMap(a -> Optional.ofNullable(a.getOptions()).orElse(List.of()).stream())
        .anyMatch(o -> "kamikazeZone".equals(o.getName()) && "true".equals(o.getValue()))) {
      flags.add("_fade");
    }
    if (Boolean.parseBoolean(
        files.properties().getProperty(MapData.PROPERTY_MAP_USENATION_CONVOYFLAGS))) {
      flags.add("_convoy");
    }
    final List<Problem> notes = new ArrayList<>();
    for (final String nation : MapXml.nations(game)) {
      if (!savedNations.contains(nation)) {
        continue;
      }
      final List<String> units =
          withoutPicture(
              files,
              forAll,
              nation,
              unitsOf(game, nation).stream().filter(savedUnits::contains).toList());
      if (!units.isEmpty()) {
        notes.add(
            Problem.in(
                "Units",
                nation
                    + " has no picture for "
                    + (units.size() == 1 ? "" : units.size() + " unit types: ")
                    + String.join(", ", units.subList(0, Math.min(3, units.size())))
                    + (units.size() > 3 ? ", …" : ""))
                .markable("noUnitPictures", nation));
      }
      final List<String> missing =
          flags.stream()
              .filter(ending -> MapFolder.flag(folder.get(), nation, ending).isEmpty())
              .map(ending -> nation + ending + ".png")
              .toList();
      if (!missing.isEmpty()) {
        notes.add(
            Problem.in("Nations", nation + " has no flag picture: " + String.join(", ", missing))
                .markable("noFlag", nation));
      }
    }
    return notes;
  }

  /**
   * The nations that can have the unit type ({@link #unitsOf}) and have no picture of it; none for
   * a map not saved yet, nor for a unit type or of nations the saved game does not have, as a
   * save writes plain pictures for those. Reads the map's folder, so not for every change.
   */
  static List<String> nationsWithoutPicture(
      final Game game, final Game saved, final MapFiles files, final String unit) {
    if (files.folder().isEmpty() || !MapXml.unitTypes(saved).contains(unit)) {
      return List.of();
    }
    final Set<String> forAll = MapFolder.names(files.folder().get().resolve("units"));
    final Set<String> savedNations = Set.copyOf(MapXml.nations(saved));
    return MapXml.nations(game).stream()
        .filter(savedNations::contains)
        .filter(nation -> unitsOf(game, nation).contains(unit))
        .filter(nation -> !withoutPicture(files, forAll, nation, List.of(unit)).isEmpty())
        .toList();
  }

  /**
   * @param forAll the names of the pictures in the map's units folder, listed once for all nations
   */
  private static List<String> withoutPicture(
      final MapFiles files,
      final Set<String> forAll,
      final String nation,
      final List<String> units) {
    return files
        .folder()
        .map(
            folder ->
                MapFolder.unitsWithoutPicture(
                    folder,
                    forAll,
                    nation,
                    units.stream()
                        // uploaded or imported here: the next save writes it
                        .filter(unit -> !files.unitPictures().containsKey(unit))
                        .filter(
                            unit ->
                                !files
                                    .nationUnitPictures()
                                    .containsKey(new NationUnit(nation, unit)))
                        .toList()))
        .orElse(List.of());
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
      return whyNotStarted(parse.invoke(parser, in));
    } catch (final InvocationTargetException e) {
      return Optional.of(message(e.getCause()));
    } catch (final IOException | IllegalAccessException e) {
      return Optional.of(message(e));
    }
  }

  /**
   * What stops the loaded game when TripleA starts it, though its parser took it: a land territory,
   * or a sea zone with an owner, without a territory attachment.
   */
  private static Optional<String> whyNotStarted(final Object parsed) {
    final Optional<?> loaded =
        parsed instanceof Optional<?> optional ? optional : Optional.of(parsed);
    return loaded
        .filter(GameData.class::isInstance)
        .map(GameData.class::cast)
        .flatMap(
            data ->
                landWithoutAttachment(data)
                    .or(() -> unitOfNoNation(data))
                    .or(() -> stepWithoutNation(data))
                    .or(() -> capitalOfNoNation(data)));
  }

  /**
   * A unit whose owner the parser did not find among the players: it takes the unit without an
   * owner, and TripleA then fails where it draws it ("gamePlayer is null").
   */
  private static Optional<String> unitOfNoNation(final GameData data) {
    for (final Territory territory : data.getMap().getTerritories()) {
      for (final Unit unit : territory.getUnitCollection()) {
        if (unit.getOwner() == null) {
          return Optional.of(
              "A unit ("
                  + unit.getType().getName()
                  + ") in "
                  + territory.getName()
                  + " belongs to a nation the game does not have");
        }
      }
    }
    return Optional.empty();
  }

  /**
   * A step without a nation whose delegate asks for its nation: all but the game's start, the
   * round's end and the random start do, and TripleA fails when the step begins.
   */
  private static Optional<String> stepWithoutNation(final GameData data) {
    for (final GameStep step : data.getSequence()) {
      if (step.getPlayerId() == null
          && step.getDelegateOptional()
              .filter(d -> !GameRulesXml.ROUND_CLASSES.contains(d.getClass().getSimpleName()))
              .isPresent()) {
        return Optional.of("Step " + step.getName() + " needs a nation");
      }
    }
    return Optional.empty();
  }

  /** A capital of something that is no nation: TripleA fails where it draws the capital's flag. */
  private static Optional<String> capitalOfNoNation(final GameData data) {
    for (final Territory territory : data.getMap().getTerritories()) {
      final Optional<String> capital;
      try {
        // an Optional in today's TripleA, the name itself in older ones
        final Object named =
            TerritoryAttachment.class
                .getMethod("getCapital")
                .invoke(TerritoryAttachment.get(territory));
        capital =
            named instanceof Optional<?> optional
                ? optional.map(String::valueOf)
                : Optional.ofNullable(named).map(String::valueOf);
      } catch (final ReflectiveOperationException | RuntimeException e) {
        // no attachment, or a TripleA that names its capitals otherwise: nothing to say here
        continue;
      }
      if (capital.filter(nation -> data.getPlayerList().getPlayerId(nation) == null).isPresent()) {
        return Optional.of(
            territory.getName() + " is the capital of " + capital.get() + ", which is no nation");
      }
    }
    return Optional.empty();
  }

  private static Optional<String> landWithoutAttachment(final GameData data) {
    for (final Territory territory : data.getMap().getTerritories()) {
      try {
        TerritoryAttachment.get(territory);
        // as the game's start does for every owned territory, water too
        if (!territory.getOwner().isNull()) {
          TerritoryAttachment.getOrThrow(territory);
        }
      } catch (final IllegalStateException e) {
        return Optional.of(message(e));
      }
    }
    return Optional.empty();
  }

  private static String message(final Throwable e) {
    return Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
  }
}
