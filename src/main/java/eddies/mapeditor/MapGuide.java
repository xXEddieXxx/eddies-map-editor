package eddies.mapeditor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapGuide.Next.Kind;
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
   */
  record Step(String text, State state, String tool, String why) {
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
   */
  record Next(Kind kind, String title, String why, String tool) {
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
    final Optional<Step> todo =
        steps(project).stream().filter(s -> !s.done() && !s.tool().equals(CHECK)).findFirst();
    if (todo.isPresent()) {
      return new Next(Kind.GO_TO_TOOL, todo.get().text(), todo.get().why(), todo.get().tool());
    }
    if (!problems.isEmpty()) {
      final Problem first = problems.getFirst();
      return new Next(
          Kind.FIX_PROBLEMS,
          problems.size() == 1 ? "Fix 1 problem" : "Fix " + problems.size() + " problems",
          "They keep the map from being played. The first: " + first.text(),
          first.territory().map(t -> "Territories").or(first::tool).orElse(""));
    }
    if (!checked) {
      return new Next(
          Kind.CHECK,
          "Check your map",
          "Check loads the game as TripleA does and finds what still keeps it from being played.",
          CHECK);
    }
    if (project.hasUnsavedChanges()) {
      return new Next(Kind.SAVE, "Save your map", "TripleA plays the map as it is saved.", "");
    }
    return new Next(
        Kind.PLAY, "Your map is ready to play", "Try it in TripleA: play a few turns.", "");
  }

  private static List<Step> steps(final MapProject project, final boolean checked) {
    final Game game = project.game();
    final List<String> territories = MapXml.territories(game);
    final List<String> nations = MapXml.nations(game);
    final List<String> owners =
        territories.stream().flatMap(t -> MapXml.owner(game, t).stream()).distinct().toList();
    final boolean allOwnLand =
        !nations.isEmpty()
            && nations.stream().filter(n -> MapXml.takesTurns(game, n)).allMatch(owners::contains);
    // each territory's capital read once: a big map has thousands of attachments to look through
    final Set<String> withCapital =
        territories.stream()
            .flatMap(t -> MapXml.capitalOf(game, t).stream())
            .collect(Collectors.toSet());
    final List<String> landed = owners.stream().filter(nations::contains).toList();
    final boolean capitals = !landed.isEmpty() && withCapital.containsAll(landed);
    final boolean nationsAndLand = !nations.isEmpty() && !territories.isEmpty();
    return List.of(
        new Step(
            "Find the territories in a picture of the map",
            state(project.getFiles().hasOutlines()),
            "Territories",
            "The game needs each territory's outline; Territories finds them in a picture."),
        new Step(
            "Add the nations that play",
            state(!nations.isEmpty()),
            "Nations",
            "A game needs the nations that take turns."),
        new Step(
            "Give every nation its land",
            allOwnLand ? State.DONE : nationsAndLand ? State.FAILING : State.TODO,
            "Territories",
            "Each nation that takes turns needs land to start with."),
        new Step(
            "Mark each nation's capital",
            capitals ? State.DONE : landed.isEmpty() ? State.TODO : State.FAILING,
            "Territories",
            "A nation's capital is where it collects its income."),
        new Step(
            "Place the units each territory starts with",
            state(territories.stream().anyMatch(t -> !MapXml.units(game, t).isEmpty())),
            "Territories",
            "The units in the territories are what the players start with."),
        new Step(
            "Check which territories border each other",
            state(!MapXml.connections(game).isEmpty()),
            "Connections",
            "Units move only between territories that border each other."),
        new Step(
            "Check the map and play it",
            state(checked && !project.hasUnsavedChanges()),
            CHECK,
            "Check (F6) loads the game as TripleA does; then play it in TripleA."));
  }

  private static State state(final boolean done) {
    return done ? State.DONE : State.TODO;
  }
}
