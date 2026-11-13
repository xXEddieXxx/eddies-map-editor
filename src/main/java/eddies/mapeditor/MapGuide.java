package eddies.mapeditor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapGuide.Next.Kind;
import eddies.mapeditor.MapGuide.Step.Id;
import eddies.mapeditor.MapGuide.Step.State;

/**
 * How a map is made, at a glance: the parts of its folder, what each is for, whether the map has it
 * and which tool makes it; and the steps to a playable map, which are done.
 */
@UtilityClass
class MapGuide {

  /**
   * A part of the map's folder.
   *
   * @param depth how far it is indented below the map's folder
   * @param required whether the game needs it; the others make the map nicer
   * @param tool the map maker's tool that makes it, null when only Files changes it
   * @param file the file or folder, to open in Files
   */
  record Part(
      int depth,
      String name,
      String purpose,
      boolean required,
      boolean present,
      @Nullable String tool,
      Path file) {}

  /** The title of the last step's "tool": Check, run from Home. */
  static final String CHECK = "Check";

  /**
   * A step to a playable map, how far it is, the tool to do it in and why it matters.
   *
   * @param tool the title of the tool, or {@link #CHECK}
   * @param progress how far it is in numbers, eg "3 of 5 nations have land"; empty for Check
   */
  record Step(Id id, String text, State state, String tool, String why, String progress) {
    enum Id {
      TERRITORIES,
      NATIONS,
      LAND,
      CAPITALS,
      UNITS,
      CONNECTIONS,
      CHECK
    }

    /** Done; not done yet; or not done although it should be by now, eg land once nations exist. */
    enum State {
      DONE,
      TODO,
      FAILING
    }

    boolean done() {
      return state == State.DONE;
    }
  }

  /**
   * What to do next, the one main action of Home.
   *
   * @param tool the tool to go to, {@link #CHECK} to check, or the tool of the first problem
   * @param step the step to follow into its tool, for {@link Kind#GO_TO_TOOL}
   */
  record Next(Kind kind, String title, String why, String tool, Optional<Step.Id> step) {
    enum Kind {
      GO_TO_TOOL,
      FIX_PROBLEMS,
      CHECK,
      SAVE,
      PLAY
    }
  }

  /** The parts of the open map's folder, top down; for a map not saved yet, none. */
  static List<Part> anatomy(final MapProject project) {
    final Optional<Path> content = project.getFolder();
    final Optional<Path> xml = project.getXmlFile();
    if (content.isEmpty() || xml.isEmpty()) {
      return List.of();
    }
    final Path root = MapFolder.rootOf(content.get());
    final List<Part> parts = new ArrayList<>();
    parts.add(
        part(
            0,
            root,
            "map.yml",
            "The map's name and games: how the game finds the map",
            true,
            null));
    parts.add(
        part(0, root, "description.html", "What the map browser says about the map", false, null));
    parts.add(part(0, root, "preview.png", "The map browser's picture of the map", false, null));
    final int indent = root.equals(content.get()) ? 0 : 1;
    if (indent == 1) {
      parts.add(
          new Part(
              0,
              root.relativize(content.get()) + "/",
              "The map's own files",
              true,
              true,
              null,
              content.get()));
    }
    final Path games = xml.get().getParent();
    parts.add(new Part(indent, "games/", "The games of the map", true, true, null, games));
    final String gameFile = xml.get().getFileName().toString();
    parts.add(
        part(
            indent + 1,
            games,
            gameFile,
            "The rules: territories, neighbours, nations, units, costs, turn order, the start",
            true,
            "Territories"));
    parts.add(
        part(
            indent + 1,
            games,
            gameFile.replaceFirst("\\.xml$", "") + ".notes.html",
            "What players read about the game",
            false,
            "Game notes"));
    final Path folder = content.get();
    parts.add(
        part(indent, folder, "polygons.txt", "The territories' outlines", true, "Territories"));
    parts.add(
        part(indent, folder, "centers.txt", "The middle of each territory", true, "Territories"));
    parts.add(
        part(
            indent,
            folder,
            "place.txt",
            "Where the units of each territory stand",
            true,
            "Territories"));
    parts.add(
        part(
            indent,
            folder,
            "capitols.txt",
            "Where capitals show their flag",
            false,
            "Territories"));
    parts.add(
        part(
            indent,
            folder,
            "vc.txt",
            "Where victory cities show their marker",
            false,
            "Territories"));
    parts.add(
        part(
            indent,
            folder,
            "name_place.txt",
            "Where the territories' names stand",
            false,
            "Territories"));
    parts.add(
        part(
            indent,
            folder,
            "pu_place.txt",
            "Where the territories' values stand",
            false,
            "Territories"));
    parts.add(
        part(
            indent,
            folder,
            "map.properties",
            "Nation colors, unit size, the map's size",
            true,
            "Map look"));
    parts.add(
        part(indent, folder, "baseTiles", "The map's picture in tiles of 256 pixels", true, null));
    parts.add(
        part(indent, folder, "reliefTiles", "Relief drawn over the picture", false, "Map look"));
    final Optional<Path> smallMap = smallMap(folder);
    parts.add(
        new Part(
            indent,
            "smallMap",
            "The small map in the corner of the game",
            false,
            smallMap.isPresent(),
            null,
            smallMap.orElse(folder)));
    parts.add(part(indent, folder, "flags", "Each nation's flag", false, "Nations"));
    parts.add(
        part(indent, folder, "units", "The unit pictures, a folder per nation", false, "Units"));
    parts.add(
        part(indent, folder, "resources", "Pictures of PUs and other resources", false, null));
    parts.add(part(indent, folder, "tooltips.properties", "Texts shown over units", false, null));
    parts.add(
        part(
            indent,
            folder,
            "objectives.properties",
            "The national objectives' texts",
            false,
            null));
    parts.add(
        part(
            indent,
            folder,
            "notifications.properties",
            "Messages shown during a game",
            false,
            null));
    return parts;
  }

  private static Part part(
      final int depth,
      final Path folder,
      final String name,
      final String purpose,
      final boolean required,
      @Nullable final String tool) {
    final Path file = folder.resolve(name);
    return new Part(
        depth,
        Files.isDirectory(file) ? name + "/" : name,
        purpose,
        required,
        Files.exists(file),
        tool,
        file);
  }

  private static Optional<Path> smallMap(final Path content) {
    return List.of("smallMap.png", "smallMap.jpeg", "smallMap.jpg", "smallMap.gif").stream()
        .map(content::resolve)
        .filter(Files::exists)
        .findFirst();
  }

  /** The steps to a playable map in their order, how far each is; not checked yet. */
  static List<Step> steps(final MapProject project) {
    return steps(project, false);
  }

  /** The steps after a Check that found no problem, the game engine's included. */
  static List<Step> stepsAfterCleanCheck(final MapProject project) {
    return steps(project, true);
  }

  /** What to do next with these quick problems, before a Check. */
  static Next nextStep(final MapProject project, final List<Problem> problems) {
    return next(project, problems, false);
  }

  /** What to do next after a Check that found these problems, the engine's included. */
  static Next nextStepAfterCheck(final MapProject project, final List<Problem> problems) {
    return next(project, problems, true);
  }

  private static Next next(
      final MapProject project, final List<Problem> problems, final boolean checked) {
    final Optional<Step> todo = firstOpen(steps(project)).filter(s -> !s.tool().equals(CHECK));
    if (todo.isPresent()) {
      return new Next(
          Kind.GO_TO_TOOL,
          todo.get().text(),
          todo.get().why(),
          todo.get().tool(),
          Optional.of(todo.get().id()));
    }
    if (!problems.isEmpty()) {
      final Problem first = problems.getFirst();
      return new Next(
          Kind.FIX_PROBLEMS,
          problems.size() == 1 ? "Fix 1 problem" : "Fix " + problems.size() + " problems",
          "They keep the map from being played. The first: " + first.text(),
          first.territory().map(t -> "Territories").or(first::tool).orElse(""),
          Optional.empty());
    }
    if (!checked) {
      return new Next(
          Kind.CHECK,
          "Check your map",
          "Check loads the game as TripleA does and finds what still keeps it from being played.",
          CHECK,
          Optional.empty());
    }
    if (project.hasUnsavedChanges()) {
      return new Next(
          Kind.SAVE,
          "Save your map",
          "TripleA plays the map as it is saved.",
          "",
          Optional.empty());
    }
    return new Next(
        Kind.PLAY,
        "Your map is ready to play",
        "Try it in TripleA: play a few turns.",
        "",
        Optional.empty());
  }

  /**
   * The next step: the first not done in the list's order, the same on Home and on a tool's goal,
   * so a step done ahead of others, eg connections found in the picture, is never offered.
   */
  static Optional<Step> firstOpen(final List<Step> steps) {
    return steps.stream().filter(s -> !s.done()).findFirst();
  }

  private static List<Step> steps(final MapProject project, final boolean checked) {
    final Game game = project.game();
    final List<String> territories = MapXml.territories(game);
    final List<String> nations = MapXml.nations(game);
    final Set<String> known = Set.copyOf(territories);
    final Set<String> owners = MapXml.nationsWithLand(game, known);
    final List<String> playing = nations.stream().filter(n -> MapXml.takesTurns(game, n)).toList();
    final List<String> withoutLand = withoutLand(playing, owners);
    final Set<String> withCapital = MapXml.nationsWithCapital(game, known);
    final List<String> landed = nations.stream().filter(owners::contains).toList();
    final List<String> withoutCapital =
        landed.stream().filter(n -> !withCapital.contains(n)).toList();
    final long capitals = landed.size() - withoutCapital.size();
    final boolean nationsAndLand = !nations.isEmpty() && !territories.isEmpty();
    // the placements read once, not per territory: a big map has hundreds of territories
    final long withUnits =
        MapXml.placements(game).stream()
            .map(UnitPlacement::getTerritory)
            .filter(known::contains)
            .distinct()
            .count();
    final int connections = MapXml.connections(game).size();
    final List<Step> building =
        List.of(
        new Step(
            Id.TERRITORIES,
            "Make the territories from a picture of the map",
            state(project.getFiles().hasOutlines()),
            "Territories",
            "The game needs each territory's outline; Territories makes them from a picture.",
            count(territories.size(), "territory", "territories")),
        new Step(
            Id.NATIONS,
            "Add the nations that play",
            state(!nations.isEmpty()),
            "Nations",
            "A game needs the nations that take turns.",
            count(nations.size(), "nation", "nations")),
        new Step(
            Id.LAND,
            "Give every nation its land",
            !nations.isEmpty() && withoutLand.isEmpty()
                ? State.DONE
                : nationsAndLand ? State.FAILING : State.TODO,
            "Territories",
            "Each nation that takes turns needs land to start with.",
            nations.isEmpty()
                ? "No nations yet: add them first"
                : playing.isEmpty()
                    ? "No nation takes turns yet"
                    : (playing.size() - withoutLand.size())
                        + " of "
                        + playing.size()
                        + (playing.size() == 1 ? " nation has land" : " nations have land")
                        + without(withoutLand)),
        new Step(
            Id.CAPITALS,
            "Mark each nation's capital",
            !landed.isEmpty() && capitals == landed.size()
                ? State.DONE
                : landed.isEmpty() ? State.TODO : State.FAILING,
            "Territories",
            "A nation's capital is where it collects its income.",
            landed.isEmpty()
                ? "No nation has land yet"
                : capitals
                    + " of "
                    + landed.size()
                    + (landed.size() == 1 ? " nation with land has" : " nations with land have")
                    + " a capital"
                    + without(withoutCapital)),
        new Step(
            Id.UNITS,
            "Place the units each territory starts with",
            state(withUnits > 0),
            "Territories",
            "The units in the territories are what the players start with.",
            withUnits == 0
                ? "No territory has units yet"
                : withUnits + (withUnits == 1 ? " territory has units" : " territories have units")),
        new Step(
            Id.CONNECTIONS,
            "Check which territories border each other",
            state(connections > 0),
            "Connections",
            "Units move only between territories that border each other.",
            count(connections, "connection", "connections")));
    final List<Step> steps = new ArrayList<>(building);
    steps.add(
        new Step(
            Id.CHECK,
            "Check the map and play it",
            // not ticked ahead of the steps before it: a game may load with a nation without land
            state(
                checked
                    && !project.hasUnsavedChanges()
                    && building.stream().allMatch(Step::done)),
            CHECK,
            "Check (F6) loads the game as TripleA does; then play it in TripleA.",
            ""));
    return List.copyOf(steps);
  }

  /** "No nations yet" rather than "0 nations": what is missing, not a number nobody made. */
  private static String count(final int count, final String noun, final String nounPlural) {
    return count == 0
        ? "No " + nounPlural + " yet"
        : SearchList.count(count, count, noun, nounPlural);
  }

  /** Who is still without, eg " · without: Germans, Russians and 2 more"; empty for nobody. */
  private static String without(final List<String> nations) {
    if (nations.isEmpty()) {
      return "";
    }
    final int named = Math.min(2, nations.size());
    return " · without: "
        + String.join(", ", nations.subList(0, named))
        + (nations.size() > named ? " and " + (nations.size() - named) + " more" : "");
  }

  /** The nations taking turns that own no land at the start, in their order. */
  static List<String> withoutLand(final Game game) {
    final List<String> playing =
        MapXml.nations(game).stream().filter(n -> MapXml.takesTurns(game, n)).toList();
    return withoutLand(
        playing, MapXml.nationsWithLand(game, Set.copyOf(MapXml.territories(game))));
  }

  private static List<String> withoutLand(final List<String> playing, final Set<String> owners) {
    return playing.stream().filter(n -> !owners.contains(n)).toList();
  }

  /** What to do for a step on the map, shown while its tool has no mode of its own for it. */
  static String howTo(final Step.Id step) {
    return switch (step) {
      case TERRITORIES -> "Load a picture of the map, then make the territories from it.";
      case NATIONS -> "Add a nation with + below the list.";
      case LAND -> "Painting an owner: drag across land to give it to the nation.";
      case CAPITALS -> "Painting capitals: click land to make it its owner's capital.";
      case UNITS -> "Click a territory, then + Add units in the panel on the right.";
      case CONNECTIONS -> "Click a territory, then its neighbours.";
      case CHECK -> "Check (F6) loads the game as TripleA does.";
    };
  }

  private static State state(final boolean done) {
    return done ? State.DONE : State.TODO;
  }
}
