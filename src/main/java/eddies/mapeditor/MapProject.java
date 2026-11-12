package eddies.mapeditor;

import games.strategy.triplea.EngineImageLoader;
import games.strategy.triplea.image.ImageTransformer;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.ui.mapdata.PlayerColors;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.generic.xml.reader.exceptions.XmlParsingException;
import org.triplea.io.FileUtils;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Info;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapFiles.Marker;
import eddies.mapeditor.MapFiles.NationUnit;
import eddies.mapeditor.MapFiles.Redrawn;
import eddies.mapeditor.MapFiles.UnitPicture;
import eddies.mapeditor.MapPicture.DetectedMap;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapPicture.Neighbours;

/**
 * A map open in the map maker: the game and its map files as last saved, as they are now, and the
 * steps between them to undo and redo. Each step is a whole game with its files, as {@link MapXml}
 * changes make new ones. A new map has no file until it is saved the first time.
 */
final class MapProject {
  private static final String UNTITLED = "Untitled map";
  // distinct colors for new nations, before any made up
  private static final List<Color> NATION_COLORS =
      List.of(
          new Color(0xC0392B),
          new Color(0x2E86C1),
          new Color(0x28B463),
          new Color(0xD68910),
          new Color(0x8E44AD),
          new Color(0x17A589),
          new Color(0x7F8C8D),
          new Color(0xF1C40F));

  // 600 MB: about 60 lines drawn into a 2000x1200 picture, a few into a huge one
  private static final long UNDO_PICTURE_BYTES = 600_000_000L;
  private final Deque<State> undos = new ArrayDeque<>();
  // a picture with lines drawn into it: the picture it came from, so a save writes changed tiles;
  // that first one stays in memory while a later one is used, outside the undo pictures' limit
  private final Map<BufferedImage, BufferedImage> drawnOn = new WeakHashMap<>();
  private final Deque<State> redos = new ArrayDeque<>();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private @Nullable Path xmlFile;
  private @Nullable Path folder;
  // the name of a map made here, written into its game on every save
  private @Nullable String mapName;
  private State saved;
  private State current;
  // the quick problems of a step, worked out once for the overview and the map's markers
  private @Nullable State problemsOf;
  private List<Problem> problems = List.of();
  // how many territories changed since the save, worked out once per step and save for the legend
  private @Nullable State changedOf;
  private @Nullable State changedSince;
  private long changedCount;

  /**
   * One step: the game, the map's files, which change together when a territory is renamed, and
   * the texts beside them.
   */
  private record State(Game game, MapFiles files, MapTexts texts) {}

  MapProject(final Path xmlFile, final Game game, final MapFiles files) {
    this.xmlFile = xmlFile;
    folder = files.folder().orElseGet(() -> mapFolderOf(xmlFile));
    saved = new State(game, files, MapTexts.read(folder));
    current = saved;
  }

  private MapProject(final Game game) {
    saved = new State(game, MapFiles.none(), MapTexts.none());
    current = saved;
  }

  /** A step with this game and these files, the texts as they are. */
  private State state(final Game game, final MapFiles files) {
    return new State(game, files, current.texts());
  }

  /** The folder of the map the game XML belongs to: above its "games" folder, else its own. */
  private static Path mapFolderOf(final Path xmlFile) {
    final Path parent = xmlFile.toAbsolutePath().getParent();
    return parent.getFileName() != null
            && "games".equals(parent.getFileName().toString())
            && parent.getParent() != null
        ? parent.getParent()
        : parent;
  }

  /** Opens the game; notes in a file of their own beside it count, as the game shows those. */
  static MapProject open(final Path xmlFile) throws IOException, XmlParsingException {
    try (InputStream xml = Files.newInputStream(xmlFile)) {
      final Game game = new XmlMapper(xml).mapXmlToObject(Game.class);
      final Path notes = notesFile(xmlFile);
      return new MapProject(
          xmlFile,
          Files.exists(notes) ? MapXml.withNotes(game, Files.readString(notes)) : game,
          MapFiles.of(xmlFile));
    }
  }

  /**
   * The game's notes file, eg games/my_map.notes.html: the game shows the notes in it, and makes it
   * from the notes in the game file when it is missing.
   */
  static Path notesFile(final Path xmlFile) {
    final String name = xmlFile.getFileName().toString();
    return xmlFile.resolveSibling(name.replaceFirst("\\.xml$", "") + ".notes.html");
  }

  /**
   * A new map, not saved yet: the rules of the map maker's template (classic units, purchases and
   * turn) without nations, territories or picture.
   */
  static MapProject newEmpty() {
    try (InputStream xml = MapProject.class.getResourceAsStream("new-game.xml")) {
      return new MapProject(new XmlMapper(xml).mapXmlToObject(Game.class));
    } catch (final IOException | XmlParsingException e) {
      throw new IllegalStateException("The map maker's template of a new game is missing", e);
    }
  }

  /**
   * A new map as the new-map wizard makes it: the template with the territories found in the
   * picture and these nations, saved as a map of its own in the maps folder and opened from there,
   * so its history starts with the saved map.
   *
   * @param picture the map's picture, null to load one later
   * @throws IOException also for a name without letters or digits or of a map that is there
   */
  static NewMap newMap(
      final String mapName,
      @Nullable final BufferedImage picture,
      final List<String> nations,
      final Path mapsFolder)
      throws IOException, XmlParsingException {
    final MapProject made = newEmpty();
    if (picture != null) {
      made.loadPicture(picture, MapPicture.detect(picture));
    }
    final List<String> refused = new ArrayList<>();
    for (final String line : nations) {
      final String nation = line.strip();
      if (!nation.isEmpty() && !made.addNation(nation)) {
        refused.add(nation);
      }
    }
    made.saveAsNewMap(mapName, mapsFolder);
    final Path xml = made.getXmlFile().orElseThrow();
    final MapProject opened = open(xml);
    // the picture stays held, the same as the tiles just written, so a split can draw into it
    return new NewMap(
        picture == null
            ? opened
            : new MapProject(xml, opened.game(), opened.getFiles().withPicture(picture)),
        List.copyOf(refused));
  }

  /** A map the new-map wizard made, and the nation names it refused, eg twice the same. */
  record NewMap(MapProject project, List<String> refusedNations) {}

  /** The game XML, empty for a new map not saved yet. */
  Optional<Path> getXmlFile() {
    return Optional.ofNullable(xmlFile);
  }

  /** The map's folder, empty for a new map not saved yet. */
  Optional<Path> getFolder() {
    return Optional.ofNullable(folder);
  }

  /**
   * The name to show: a map's made here, else the opened game's or its file's, else "Untitled map".
   */
  String name() {
    return Optional.ofNullable(mapName)
        .or(
            () ->
                getXmlFile()
                    .map(
                        file ->
                            Optional.ofNullable(game().getInfo())
                                .map(Info::getName)
                                .orElse(file.getFileName().toString())))
        .orElse(UNTITLED);
  }

  Game game() {
    return current.game();
  }

  /** The map's outlines, centers, colors and picture as they are now. */
  MapFiles getFiles() {
    return current.files();
  }

  /** Called after every change, undo and save. */
  void onChange(final Runnable listener) {
    listeners.add(listener);
  }

  /** Applies a change; one that changes nothing is no step to undo. */
  void edit(final UnaryOperator<Game> change) {
    final Game changed = change.apply(current.game());
    if (changed != current.game()) {
      step(state(changed, current.files()));
    }
  }

  /**
   * Changes the game and the texts beside it together, eg a trigger and its message, as one step;
   * one that changes neither is no step.
   */
  void edit(final UnaryOperator<Game> change, final UnaryOperator<MapTexts> textChange) {
    final Game changed = change.apply(current.game());
    final MapTexts changedTexts = textChange.apply(current.texts());
    if (changed != current.game() || changedTexts != current.texts()) {
      step(new State(changed, current.files(), changedTexts));
    }
  }

  private void step(final State next) {
    undos.push(current);
    redos.clear();
    current = next;
    forgetOldPictures();
    listeners.forEach(Runnable::run);
  }

  /**
   * Drops the oldest undo steps once the pictures they hold, eg one per line drawn into it, take
   * more than {@value #UNDO_PICTURE_BYTES} bytes; they can no longer be undone then.
   */
  private void forgetOldPictures() {
    final int kept =
        keptSteps(
            undos.stream().map(state -> state.files().picture()).toList(),
            current.files().picture(),
            UNDO_PICTURE_BYTES);
    while (undos.size() > kept) {
      undos.removeLast();
    }
  }

  /**
   * How many of the undo steps, newest first, to keep so the distinct pictures they hold, besides
   * the current one, take at most this many bytes.
   */
  static int keptSteps(
      final List<Optional<BufferedImage>> pictures,
      final Optional<BufferedImage> current,
      final long limitBytes) {
    final Set<BufferedImage> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    current.ifPresent(seen::add);
    long bytes = 0;
    int kept = 0;
    for (final Optional<BufferedImage> picture : pictures) {
      if (picture.isPresent() && seen.add(picture.get())) {
        bytes += 4L * picture.get().getWidth() * picture.get().getHeight();
        if (bytes > limitBytes) {
          break;
        }
      }
      kept++;
    }
    return kept;
  }

  /** Takes the picture as the map's, as one step; the territories stay until they are found. */
  void loadPicture(final BufferedImage picture) {
    step(state(game(), current.files().withPicture(picture)));
  }

  /**
   * Takes the picture as the map's: its territories and connections replace the game's, its
   * outlines and centers the files', as one step.
   */
  void loadPicture(final BufferedImage picture, final DetectedMap detected) {
    step(
        state(
            MapXml.withWorld(game(), detected), current.files().withPicture(picture, detected)));
  }

  /**
   * Renames the territory in the game and in the map's outlines and centers, as one step.
   *
   * @return false, changing nothing, for a name {@link #isFreeName} refuses, or when the old name
   *     is also a nation's or a unit's: the options name both alike, so renaming would rename them
   *     too
   */
  boolean rename(final String old, final String renamed) {
    if (whyNotRenamed(game(), old, renamed).isPresent()) {
      return false;
    }
    step(
        state(
            MapXml.withRenamedTerritory(game(), old, renamed),
            current.files().withRenamed(old, renamed)));
    return true;
  }

  /** Why the territory may not take the name, empty if it may. */
  private static Optional<String> whyNotRenamed(
      final Game game, final String old, final String renamed) {
    if (!MapXml.territories(game).contains(old)) {
      return Optional.of("no territory is called " + old);
    }
    if (MapXml.nations(game).contains(old) || MapXml.unitTypes(game).contains(old)) {
      return Optional.of("named like a nation or a unit, so it keeps its name");
    }
    return isFreeName(game, renamed)
        ? Optional.empty()
        : Optional.of("the new name is taken, or holds : < or (");
  }

  /** How many territories {@link #renameAll} renamed, and the lines it refused with the reason. */
  record Renamed(int count, List<String> refused) {}

  /**
   * Renames territories by lines "old name;new name" ("," or "=" may stand for ";"), all as one
   * step, each as {@link #rename} does. Blank lines, lines starting with '#' and lines without a
   * new name, as {@link #nameList} writes them, are skipped.
   */
  Renamed renameAll(final List<String> lines) {
    Game game = game();
    MapFiles files = getFiles();
    int count = 0;
    final List<String> refused = new ArrayList<>();
    for (final String raw : lines) {
      // a spreadsheet's UTF-8 file may start with a byte order mark
      final String line = raw.replace("﻿", "").strip();
      if (line.isEmpty() || line.charAt(0) == '#') {
        continue;
      }
      final int separator = separator(line);
      if (separator < 0) {
        refused.add(line + ": no ; , or = between the old and the new name");
        continue;
      }
      final String old = line.substring(0, separator).strip();
      final String renamed = line.substring(separator + 1).strip();
      if (renamed.isEmpty() || renamed.equals(old)) {
        continue;
      }
      final Optional<String> why = whyNotRenamed(game, old, renamed);
      if (why.isPresent()) {
        refused.add(line + ": " + why.get());
        continue;
      }
      game = MapXml.withRenamedTerritory(game, old, renamed);
      files = files.withRenamed(old, renamed);
      count++;
    }
    if (count > 0) {
      step(state(game, files));
    }
    return new Renamed(count, List.copyOf(refused));
  }

  /** Where the old name ends: at ';', else '=', else ',' as names hold commas more often. */
  private static int separator(final String line) {
    return IntStream.of(line.indexOf(';'), line.indexOf('='), line.indexOf(','))
        .filter(i -> i >= 0)
        .findFirst()
        .orElse(-1);
  }

  /** The territories as lines "name;" for {@link #renameAll}, to fill in the new names. */
  List<String> nameList() {
    return MapXml.territories(game()).stream().map(t -> t + ";").toList();
  }

  /**
   * Whether a territory, nation or unit type may take the name: not blank, no spaces around it,
   * none of ':' '<' '(' that the game's and map's files cannot hold, and not the name of a
   * territory, nation or unit type already, as options name them alike.
   */
  boolean isFreeName(final String name) {
    return isFreeName(game(), name);
  }

  /**
   * Why a new territory, nation or unit type may not take the name, as {@link #isFreeName} judges
   * it; empty when it may, and for a blank name (the caller's button is off then).
   */
  Optional<String> nameProblem(final String name) {
    if (name.isBlank() || isFreeName(name)) {
      return Optional.empty();
    }
    if (name.chars().anyMatch(c -> ":<(".indexOf(c) >= 0)) {
      return Optional.of("Must not contain : < or (");
    }
    return name.strip().equals(name)
        ? Optional.of("Already used by a territory, nation or unit")
        : Optional.of("No spaces at the start or end");
  }

  /**
   * The first of base, "base 2", "base 3", ... that {@link #isFreeName} takes and alsoTaken has not.
   */
  String freeName(final String base, final Collection<String> alsoTaken) {
    return IntStream.iterate(1, i -> i + 1)
        .mapToObj(i -> i == 1 ? base : base + " " + i)
        .filter(name -> isFreeName(name) && !alsoTaken.contains(name))
        .findFirst()
        .orElseThrow();
  }

  private static boolean isFreeName(final Game game, final String name) {
    return !name.isBlank()
        && name.strip().equals(name)
        && name.chars().noneMatch(c -> ":<(".indexOf(c) >= 0)
        && Stream.of(MapXml.territories(game), MapXml.nations(game), MapXml.unitTypes(game))
            .noneMatch(names -> names.contains(name));
  }

  /**
   * Adds a nation with its turn and a color of its own, as one step.
   *
   * @return false, changing nothing, for a name {@link #isFreeName} refuses
   */
  boolean addNation(final String nation) {
    if (!isFreeName(nation)) {
      return false;
    }
    step(
        state(
            MapXml.withNation(game(), nation),
            current.files().withNationColor(nation, newNationColor())));
    return true;
  }

  /** The first of the colors no nation has, else one made up. */
  private Color newNationColor() {
    final Set<Color> used =
        MapXml.nations(game()).stream()
            .flatMap(n -> getFiles().nationColor(n).stream())
            .collect(Collectors.toSet());
    return NATION_COLORS.stream()
        .filter(c -> !used.contains(c))
        .findFirst()
        .orElseGet(() -> Color.getHSBColor((used.size() * 0.618f) % 1, 0.6f, 0.75f));
  }

  /**
   * The nation's color: from map.properties, else one of the new nations' colors by its place among
   * the nations, so the map and the unit pictures agree.
   */
  Color nationColor(final String nation) {
    return getFiles()
        .nationColor(nation)
        .orElseGet(
            () ->
                NATION_COLORS.get(
                    Math.floorMod(MapXml.nations(game()).indexOf(nation), NATION_COLORS.size())));
  }

  /**
   * Removes the nation, all that names it and its color, as one step; unless attachments, eg
   * triggers, still name it, as the game would not load then.
   *
   * @return where it is still named, empty when it was removed
   */
  List<String> removeNation(final String nation) {
    final Game without = MapXml.withoutNation(game(), nation);
    final List<String> named = RulesXml.whereNamed(without, nation);
    if (named.isEmpty()) {
      step(state(without, current.files().withoutNationColor(nation)));
    }
    return named;
  }

  /**
   * Removes the territory, all that belongs to it, its outline and places, as one step; unless
   * attachments, eg triggers, still name it, as the game would not load then.
   *
   * @return where it is still named, empty when it was removed
   */
  List<String> removeTerritory(final String territory) {
    final Game without = MapXml.withoutTerritory(game(), territory);
    final List<String> named = RulesXml.whereNamed(without, territory);
    if (named.isEmpty()) {
      step(state(without, current.files().withoutTerritory(territory)));
    }
    return named;
  }

  /**
   * Splits the territory along the cut, as one step: it keeps the kept outlines with all that
   * belongs to it; the split ones become a new territory, land or sea as it is, with its owner,
   * connected to it and to the neighbours it touches. A neighbour only the new part touches is no
   * longer the old one's; neighbours without an outline stay the old one's.
   *
   * @return false, changing nothing, for a name {@link #isFreeName} refuses
   */
  boolean splitTerritory(final String territory, final OutlineCut.Cut cut, final String newName) {
    return splitTerritory(territory, cut, newName, Optional.empty());
  }

  /**
   * Holds the picture, the map's put together from its tiles ({@link MapFiles#tilesPicture}, in
   * the background), in every state that holds none, so lines can be drawn into it and undo and
   * save see the same; no step, nobody told, as it is the picture as it is.
   */
  void holdPicture(final BufferedImage whole) {
    if (current.files().picture().isPresent()) {
      return;
    }
    // a state is the same state after it, so current == saved stays
    final Map<State, State> holding = new IdentityHashMap<>();
    final UnaryOperator<State> hold =
        state ->
            holding.computeIfAbsent(
                state,
                s ->
                    s.files().picture().isEmpty()
                        ? new State(s.game(), s.files().withPicture(whole), s.texts())
                        : s);
    final List<State> undone = undos.stream().map(hold).toList();
    final List<State> redone = redos.stream().map(hold).toList();
    undos.clear();
    undos.addAll(undone);
    redos.clear();
    redos.addAll(redone);
    saved = hold.apply(saved);
    current = hold.apply(current);
  }

  /**
   * The line drawn into the map's picture, as one step; false without a picture held to draw into
   * ({@link #holdPicture}).
   */
  boolean drawBorder(final BorderLine border) {
    final MapFiles files = current.files();
    if (files.picture().isEmpty()) {
      return false;
    }
    step(
        state(
            game(),
            files.withPicture(drawn(files.picture().orElseThrow(), border, Optional.empty()))));
    return true;
  }

  /** A border line to draw into the map's picture: where, how wide in pixels, in which color. */
  record BorderLine(List<Point> line, int width, Color color) {}

  /**
   * As {@link #splitTerritory(String, OutlineCut.Cut, String)}, the line also drawn into the map's
   * picture held ({@link #holdPicture}), within the territory and up to its border, in the same
   * step; without one only the outlines change.
   */
  boolean splitTerritory(
      final String territory,
      final OutlineCut.Cut cut,
      final String newName,
      final Optional<BorderLine> drawn) {
    if (!isFreeName(newName)) {
      return false;
    }
    final MapFiles files = current.files();
    Game changed = game();
    final List<String> newNeighbours = new ArrayList<>(List.of(territory));
    for (final String neighbour : MapXml.neighbors(changed, territory)) {
      final List<Polygon> outlines = files.polygons().get(neighbour);
      if (outlines != null && OutlineCut.touches(cut.split(), outlines)) {
        newNeighbours.add(neighbour);
        if (!OutlineCut.touches(cut.kept(), outlines)) {
          changed = MapXml.withoutConnection(changed, territory, neighbour);
        }
      }
    }
    changed = MapXml.withTerritory(changed, newName, newNeighbours);
    if (MapXml.isWater(game(), territory)) {
      changed = MapXml.withWater(changed, newName);
    }
    final Optional<String> owner = MapXml.owner(game(), territory);
    if (owner.isPresent()) {
      changed = MapXml.withOwner(changed, newName, owner.get());
    }
    final MapFiles pictured =
        drawn
            .flatMap(
                border ->
                    files
                        .picture()
                        .map(
                            picture ->
                                files.withPicture(
                                    drawn(
                                        picture,
                                        border,
                                        Optional.of(
                                            files
                                                .polygons()
                                                .getOrDefault(territory, List.of()))))))
            .orElse(files);
    step(
        state(
            changed,
            pictured.withOutlines(territory, cut.kept()).withOutlines(newName, cut.split())));
    return true;
  }

  /** {@link #withLine}, the copy remembered as drawn on the picture it came from. */
  private BufferedImage drawn(
      final BufferedImage picture, final BorderLine border, final Optional<List<Polygon>> within) {
    final BufferedImage copy = withLine(picture, border, within);
    drawnOn.put(copy, firstOf(picture));
    return copy;
  }

  /** The picture lines were drawn into to make this one, else itself. */
  private BufferedImage firstOf(final BufferedImage picture) {
    return drawnOn.getOrDefault(picture, picture);
  }

  /**
   * A copy of the picture with the line drawn into it, crisp, in one color, like the picture's
   * lines; within outlines, inside them and beyond them only over the picture's dark border line
   * there, so it meets that line and stops.
   */
  private static BufferedImage withLine(
      final BufferedImage picture, final BorderLine border, final Optional<List<Polygon>> within) {
    final BufferedImage copy =
        new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = copy.createGraphics();
    g.drawImage(picture, 0, 0, null);
    final Area inside = new Area();
    final Area reached = new Area();
    final Stroke reach = new BasicStroke(border.width() * 2f + 2);
    within.ifPresent(
        outlines ->
            outlines.forEach(
                outline -> {
                  inside.add(new Area(outline));
                  reached.add(new Area(reach.createStrokedShape(outline)));
                }));
    reached.add(inside);
    within.ifPresent(outlines -> g.setClip(reached));
    g.setColor(border.color());
    g.setStroke(new BasicStroke(border.width(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    final List<Point> line = border.line();
    final Path2D path = new Path2D.Double();
    path.moveTo(line.getFirst().x, line.getFirst().y);
    line.stream().skip(1).forEach(p -> path.lineTo(p.x, p.y));
    g.draw(path);
    g.dispose();
    if (within.isPresent()) {
      // past the outline only where the picture already has its line, not into the neighbour
      final Rectangle drawnIn =
          reached.getBounds().intersection(new Rectangle(0, 0, copy.getWidth(), copy.getHeight()));
      for (int y = drawnIn.y; y < drawnIn.y + drawnIn.height; y++) {
        for (int x = drawnIn.x; x < drawnIn.x + drawnIn.width; x++) {
          final int was = picture.getRGB(x, y);
          if (copy.getRGB(x, y) != was
              && !MapPicture.isBorder(was)
              && !inside.contains(x + 0.5, y + 0.5)) {
            copy.setRGB(x, y, was);
          }
        }
      }
    }
    return copy;
  }

  /**
   * The relief picture, drawn over the map, cut into relief tiles on the next save; one step. A
   * map told not to show its relief shows it again, as a new one is meant to be seen.
   */
  void setRelief(final BufferedImage relief) {
    final MapFiles files = current.files().withRelief(relief);
    step(
        state(
            game(),
            files.showsRelief()
                ? files
                : files.withProperty(MapData.PROPERTY_MAP_HASRELIEF, "")));
  }

  /** Sets a map.properties entry as one step; an empty value removes it. */
  void setMapProperty(final String name, final String value) {
    setMapProperties(Map.of(name, value));
  }

  /**
   * Sets map.properties entries together as one step, eg those the game reads only all together;
   * an empty value removes its entry.
   */
  void setMapProperties(final Map<String, String> values) {
    MapFiles changed = current.files();
    for (final Map.Entry<String, String> value : values.entrySet()) {
      if (!value.getValue().equals(changed.properties().getProperty(value.getKey(), ""))) {
        changed = changed.withProperty(value.getKey(), value.getValue());
      }
    }
    stepFiles(changed);
  }

  /** The nation's color, and its units' tint along with it when it was the old color, one step. */
  void setNationColor(final String nation, final Color color) {
    if (color.equals(nationColor(nation))) {
      return;
    }
    final MapFiles files = current.files();
    final MapFiles colored = files.withNationColor(nation, color);
    step(
        state(
            game(),
            files.unitTint(nation).equals(Optional.of(nationColor(nation)))
                ? colored.withProperty(MapFiles.UNIT_TINT + nation, MapLookEditor.hex(color))
                : colored));
  }

  /** The unit type in each nation's tint again, as the others, as one step. */
  void tintUnitType(final String unit) {
    setMapProperty(
        MapFiles.UNTINTED_UNITS,
        String.join(
            ",", getFiles().untintedUnits().stream().filter(u -> !u.equals(unit)).toList()));
  }

  /** The unit type drawn in its own colors for every nation, never tinted, as one step. */
  void keepUnitTypeColors(final String unit) {
    final List<String> untinted = new ArrayList<>(getFiles().untintedUnits());
    if (!untinted.contains(unit)) {
      untinted.add(unit);
      setMapProperty(MapFiles.UNTINTED_UNITS, String.join(",", untinted));
    }
  }

  /**
   * Has the game tint these nations' unit pictures in their colors, as one step; a nation's color
   * the map does not set yet is written too, so land and units agree in the game.
   */
  void tintUnits(final Collection<String> nations) {
    final Map<String, String> values = new HashMap<>();
    for (final String nation : nations) {
      final String color = MapLookEditor.hex(nationColor(nation));
      values.put(MapFiles.UNIT_TINT + nation, color);
      values.put(PlayerColors.PROPERTY_COLOR_PREFIX + nation, color);
    }
    setMapProperties(values);
  }

  /**
   * The picture as the unit type's for every nation from the next save on, as one step; one the
   * same for all nations is also kept from the game's tint ({@link #keepUnitTypeColors}); one
   * switched from that to colored per nation is tinted again, others stay as the map has them.
   */
  void setUnitPicture(final String unitType, final UnitPicture picture) {
    final MapFiles files = current.files();
    final boolean wasSame =
        Optional.ofNullable(files.unitPictures().get(unitType))
            .filter(before -> before.coloring() == UnitPicture.Coloring.AS_IS)
            .isPresent();
    final List<String> untinted = new ArrayList<>(files.untintedUnits());
    if (picture.coloring() == UnitPicture.Coloring.AS_IS && !untinted.contains(unitType)) {
      untinted.add(unitType);
    } else if (picture.coloring() == UnitPicture.Coloring.PER_NATION && wasSame) {
      untinted.removeIf(unitType::equals);
    }
    final MapFiles pictured = files.withUnitPicture(unitType, picture);
    step(
        state(
            game(),
            untinted.equals(files.untintedUnits())
                ? pictured
                : pictured.withProperty(MapFiles.UNTINTED_UNITS, String.join(",", untinted))));
  }

  /**
   * These pictures as the nation's units, by unit type, from the next save on, as one step; an
   * empty map takes back all imported for the nation.
   */
  void setNationUnitPictures(final String nation, final Map<String, BufferedImage> byUnit) {
    step(state(game(), current.files().withNationUnitPictures(nation, byUnit)));
  }

  /** Places the territory's units here, in this order, as one step; for none, nothing. */
  void setUnitPlaces(final String territory, final List<Point> places) {
    stepFiles(current.files().withUnitPlaces(territory, places));
  }

  /**
   * Places the territory's marker, eg its name, at this point, as one step; the index counts the
   * effects, 0 for the other markers.
   */
  void setMarkerPlace(
      final Marker marker, final String territory, final int index, final Point place) {
    stepFiles(current.files().withMarkerPlace(game(), marker, territory, index, place));
  }

  /** Draws the misc picture at these top left corners, as one step; none takes it off the map. */
  void setDecorations(final String picture, final List<Point> places) {
    stepFiles(current.files().withDecorations(picture, places));
  }

  /** Finds the territory's places again, as one step; nothing if they are the found ones. */
  void findPlacesAgain(final String territory) {
    stepFiles(current.files().withPlacesFoundAgain(territory));
  }

  // a change that changes nothing is no step to undo
  private void stepFiles(final MapFiles changed) {
    if (changed != current.files()) {
      step(state(game(), changed));
    }
  }

  /**
   * Takes borders redrawn from a picture, {@link MapFiles#withBordersFrom}, as one step, if the
   * files they were redrawn from are still the map's; a change meanwhile keeps it. Each merged
   * area goes to its keeper, the others in it are removed, their neighbours going to the keeper;
   * each chosen new area becomes a territory, connected as in the picture.
   *
   * @return empty when not taken, else for each territory to go that rules still name, so it
   *     stays with its old border, "territory: where"
   */
  Optional<List<String>> takeRedrawn(
      final MapFiles from, final Redrawn redrawn, final RedrawChoices choices) {
    if (getFiles() != from) {
      return Optional.empty();
    }
    Game g = game();
    MapFiles f = redrawn.files();
    final List<String> named = new ArrayList<>();
    final Map<String, String> areaOf = new HashMap<>();
    redrawn.takenBy().forEach((area, territory) -> areaOf.put(area.name(), territory));
    for (final Map.Entry<DetectedTerritory, List<String>> merge : redrawn.merged().entrySet()) {
      final String keeper =
          choices.keepers().getOrDefault(merge.getKey(), merge.getValue().getFirst());
      f = f.withOutlines(keeper, merge.getKey().outlines());
      areaOf.put(merge.getKey().name(), keeper);
      for (final String member : merge.getValue()) {
        if (member.equals(keeper)) {
          continue;
        }
        Game without = g;
        for (final String neighbour : MapXml.neighbors(g, member)) {
          if (!neighbour.equals(keeper) && !MapXml.neighbors(without, keeper).contains(neighbour)) {
            without = MapXml.withConnection(without, keeper, neighbour);
          }
        }
        without = MapXml.withoutTerritory(without, member);
        final List<String> where = RulesXml.whereNamed(without, member);
        if (where.isEmpty()) {
          g = without;
          f = f.withoutTerritory(member);
        } else {
          f = f.withOutlines(member, from.polygons().get(member));
          where.forEach(w -> named.add(member + ": " + w));
        }
      }
    }
    choices.added().forEach((area, chosen) -> areaOf.put(area.name(), chosen.name()));
    for (final Map.Entry<DetectedTerritory, DetectedTerritory> add : choices.added().entrySet()) {
      final String area = add.getKey().name();
      final DetectedTerritory chosen = add.getValue();
      final Set<String> neighbours = new LinkedHashSet<>();
      for (final Neighbours pair : redrawn.detected().connections()) {
        final String other =
            pair.t1().equals(area) ? pair.t2() : pair.t2().equals(area) ? pair.t1() : "";
        Optional.ofNullable(areaOf.get(other)).ifPresent(neighbours::add);
      }
      g = MapXml.withTerritory(g, chosen.name(), neighbours);
      if (chosen.water()) {
        g = MapXml.withWater(g, chosen.name());
      }
      f = f.withOutlines(chosen.name(), chosen.outlines());
    }
    step(state(g, f));
    return Optional.of(List.copyOf(named));
  }

  /**
   * What the map maker chose for borders redrawn with new or merged areas.
   *
   * @param added the new areas to add, each as detected and as named in the dialog, its name and
   *     water chosen there
   * @param keepers the territory keeping each merged area; one not given goes to its first
   */
  record RedrawChoices(
      Map<DetectedTerritory, DetectedTerritory> added, Map<DetectedTerritory, String> keepers) {

    static RedrawChoices none() {
      return new RedrawChoices(Map.of(), Map.of());
    }
  }

  /** The unit type as the game draws it again, as one step; nothing without an uploaded one. */
  void removeUnitPicture(final String unitType) {
    if (getFiles().unitPictures().containsKey(unitType)) {
      step(state(game(), current.files().withoutUnitPicture(unitType)));
    }
  }

  /** Objectives, messages and unit tool tips beside the game, as they are now. */
  MapTexts texts() {
    return current.texts();
  }

  /** Sets the entry's text in the file, or adds the entry, as one step. */
  void setText(final MapTexts.Kind kind, final String key, final String value) {
    stepTexts(current.texts().with(kind, key, value));
  }

  void removeText(final MapTexts.Kind kind, final String key) {
    stepTexts(current.texts().without(kind, key));
  }

  // a change that changes nothing is no step to undo
  private void stepTexts(final MapTexts changed) {
    if (changed != current.texts()) {
      step(new State(game(), current.files(), changed));
    }
  }

  /**
   * Adds a unit type with the stats and cost of another, as one step.
   *
   * @return false, changing nothing, for a name {@link #isFreeName} refuses
   */
  boolean addUnitType(final String unitType, final String like) {
    if (!isFreeName(unitType)) {
      return false;
    }
    edit(g -> MapXml.withUnitType(g, unitType, like));
    return true;
  }

  boolean canUndo() {
    return !undos.isEmpty();
  }

  void undo() {
    if (canUndo()) {
      redos.push(current);
      current = undos.pop();
      listeners.forEach(Runnable::run);
    }
  }

  boolean canRedo() {
    return !redos.isEmpty();
  }

  void redo() {
    if (canRedo()) {
      undos.push(current);
      current = redos.pop();
      listeners.forEach(Runnable::run);
    }
  }

  boolean hasUnsavedChanges() {
    return current != saved;
  }

  /** Whether a relief picture was loaded since the last save, so the next one writes it. */
  boolean hasUnsavedRelief() {
    return getFiles().relief().isPresent()
        && !getFiles().relief().equals(saved.files().relief());
  }

  /** The problems {@link MapChecks#quick} finds in the map as it is now. */
  List<Problem> problems() {
    if (problemsOf != current) {
      problems =
          Stream.concat(
                  MapChecks.quick(game(), getFiles()).stream(),
                  MapTexts.foreignObjectivesProblem(texts(), game()).stream()
                      .map(t -> Problem.in("Texts", t)))
              .toList();
      problemsOf = current;
    }
    return problems;
  }

  /** How many territories {@link #isChanged} finds changed. */
  long changedCount() {
    if (changedOf != current || changedSince != saved) {
      changedCount = MapXml.territories(game()).stream().filter(this::isChanged).count();
      changedOf = current;
      changedSince = saved;
    }
    return changedCount;
  }

  /** Whether the territory differs from the last save in owner, value, units or its kind. */
  boolean isChanged(final String territory) {
    // asked for every territory shown after a change: a part of the game a change kept, as each
    // change makes a new game from the parts it does not touch, needs no comparing
    if (current == saved) {
      return false;
    }
    final Game was = saved.game();
    final Game now = current.game();
    return (was.getMap() != now.getMap()
            && !mapSummary(was, territory).equals(mapSummary(now, territory)))
        || (was.getInitialize() != now.getInitialize()
            && !startSummary(was, territory).equals(startSummary(now, territory)))
        || (was.getAttachmentList() != now.getAttachmentList()
            && !valueSummary(was, territory).equals(valueSummary(now, territory)));
  }

  private static List<Object> mapSummary(final Game game, final String territory) {
    return List.of(MapXml.isWater(game, territory), MapXml.neighbors(game, territory));
  }

  private static List<Object> startSummary(final Game game, final String territory) {
    return List.of(
        MapXml.owner(game, territory),
        MapXml.units(game, territory).stream()
            .map(u -> Objects.toString(u.getOwner()) + u.getUnitType() + u.getQuantity())
            .toList());
  }

  private static List<Object> valueSummary(final Game game, final String territory) {
    return List.of(
        MapXml.production(game, territory),
        MapXml.isCapital(game, territory),
        MapXml.isVictoryCity(game, territory));
  }

  /**
   * The unit's picture as the first nation with one of the map's shows it, else as the first nation
   * shows it; before there are nations, an uploaded one as it is or the game's of the Germans.
   */
  Optional<Image> unitPicture(final String unit) {
    final List<String> nations = MapXml.nations(game());
    final Optional<UnitPicture> uploaded = Optional.ofNullable(getFiles().unitPictures().get(unit));
    if (nations.isEmpty() && uploaded.isPresent()) {
      return Optional.of(uploaded.get().image());
    }
    final List<String> showing = nations.isEmpty() ? List.of("Germans") : nations;
    // a map often draws a unit type only for the nations that have it, eg 270BC's archers
    return showing.stream()
        .map(nation -> mapsUnitPicture(nation, nationColor(nation), unit))
        .flatMap(Optional::stream)
        .findFirst()
        .or(() -> gamesUnitPicture(showing.getFirst(), unit))
        .or(() -> gamesUnitPicture("Germans", unit));
  }

  /**
   * The unit's picture as the nation's units show it: one imported for the nation, else an uploaded
   * one in the nation's color, else the map's own (not one the last save wrote from a picture taken
   * back since), else the game's of the nation or of the Germans; empty for a unit type the game
   * has no picture of.
   */
  Optional<Image> unitPicture(final String nation, final Color color, final String unit) {
    return mapsUnitPicture(nation, color, unit)
        .or(() -> gamesUnitPicture(nation, unit))
        .or(() -> gamesUnitPicture("Germans", unit))
        .map(picture -> tinted(nation, unit, picture));
  }

  /** The picture as the game draws it with the map's tint for the nation, if it has one. */
  private Image tinted(final String nation, final String unit, final Image picture) {
    final Properties properties = getFiles().properties();
    final Optional<Color> tint = getFiles().unitTint(nation);
    if (tint.isEmpty() || getFiles().untintedUnits().contains(unit)) {
      return picture;
    }
    int brightness = 0;
    try {
      brightness =
          Math.clamp(
              Integer.parseInt(
                  properties.getProperty("units.transform.brightness." + nation, "0")),
              -100,
              100);
    } catch (final NumberFormatException e) {
      // the game would not start with it; Check says so, the picture shows without it
    }
    final BufferedImage copy =
        new BufferedImage(
            picture.getWidth(null), picture.getHeight(null), BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = copy.createGraphics();
    g.drawImage(picture, 0, 0, null);
    g.dispose();
    ImageTransformer.colorize(tint.get(), brightness, copy);
    return copy;
  }

  /** The nation's picture of the unit that the map has: imported, uploaded or in its folder. */
  private Optional<Image> mapsUnitPicture(
      final String nation, final Color color, final String unit) {
    final Optional<BufferedImage> imported =
        Optional.ofNullable(getFiles().nationUnitPictures().get(new NationUnit(nation, unit)));
    if (imported.isPresent()) {
      return Optional.of(imported.get());
    }
    final Optional<UnitPicture> uploaded = Optional.ofNullable(getFiles().unitPictures().get(unit));
    if (uploaded.isPresent()) {
      return Optional.of(uploaded.get().forNation(color));
    }
    // the last save wrote a picture taken back since: the next save brings the game's back
    final boolean takenBack =
        saved.files().nationUnitPictures().containsKey(new NationUnit(nation, unit))
            || saved.files().unitPictures().containsKey(unit);
    final Optional<Path> own =
        getFolder()
            .filter(f -> !takenBack)
            .map(f -> f.resolve("units").resolve(nation).resolve(unit + ".png"))
            .filter(Files::exists);
    if (own.isPresent()) {
      try {
        final Optional<Image> read = Optional.ofNullable(ImageIO.read(own.get().toFile()));
        if (read.isPresent()) {
          return read;
        }
      } catch (final IOException expected) {
        // a picture of the map's that cannot be read shows as the game's
      }
    }
    return Optional.empty();
  }

  private static Optional<Image> gamesUnitPicture(final String nation, final String unit) {
    try {
      return Optional.of(EngineImageLoader.loadImage("units", nation, unit + ".png"));
    } catch (final IllegalStateException e) {
      // the game has no such picture, eg of a nation or unit type new to it
      return Optional.empty();
    }
  }

  /**
   * Writes what changed since the last save: the outlines and centers, map.properties, the picture
   * in tiles, flags and unit pictures of new nations and unit types, uploaded unit pictures, the
   * notes, the objectives, messages and tool tips ({@link MapTexts}), and the game; the places of
   * units, names, capital flags and victory city markers when
   * they differ from the files, as they follow the outlines and the game. Text files are written as
   * {@link MapFolder#replace} does, so a failed save loses nothing.
   *
   * @throws IllegalStateException for a new map, which {@link #saveAsNewMap} saves first
   */
  void save() throws IOException {
    write(
        getXmlFile().orElseThrow(() -> new IllegalStateException("Not saved yet: " + UNTITLED)),
        Objects.requireNonNull(folder));
  }

  /**
   * Saves a new map the first time: a folder of its name in the maps folder with map.yml, its game,
   * and its outlines, map.properties, picture, flags and unit pictures as far as it has them. A
   * folder that could not be written whole is deleted again.
   *
   * @throws IOException also for a name without letters or digits or of a map that is there
   */
  void saveAsNewMap(final String newMapName, final Path mapsFolder) throws IOException {
    final Path xml = MapFolder.newMapFolder(mapsFolder, newMapName);
    final Path newFolder = xml.getParent().getParent();
    mapName = newMapName.strip();
    try {
      write(xml, newFolder);
    } catch (final IOException | RuntimeException e) {
      mapName = null;
      // half a map would only stand in the way of the next try
      FileUtils.deleteDirectory(newFolder);
      throw e;
    }
    xmlFile = xml;
    folder = newFolder;
    listeners.forEach(Runnable::run);
  }

  private void write(final Path xml, final Path mapFolder) throws IOException {
    final State saving = current;
    final MapFiles files = saving.files().withFolder(mapFolder);
    final boolean firstSave = xmlFile == null;
    // the outlines first: if they fail, the game file still names the territories they hold
    if (firstSave || saving.files().polygons() != saved.files().polygons()) {
      files.writeOutlines();
    }
    // a map made here gets them also when its picture comes after the first save
    if (mapName != null) {
      files.writeNamePlaces(saving.game(), saved.files());
    }
    files.writePlaces(saving.game(), saved.files());
    if (firstSave || !saving.files().properties().equals(saved.files().properties())) {
      files.writeProperties();
    }
    if (files.picture().isPresent()
        && (firstSave || !files.picture().equals(saved.files().picture()))) {
      final BufferedImage picture = files.picture().get();
      final Optional<BufferedImage> before =
          firstSave
              ? Optional.empty()
              : saved.files().picture().filter(b -> firstOf(b) == firstOf(picture));
      // lines drawn into it, or taken back: only the tiles they touch; a new picture: all, with
      // the small map and the preview
      if (before.isPresent()) {
        MapFolder.writeChangedTiles(mapFolder, picture, before.get());
      } else {
        MapFolder.writePicture(mapFolder, picture);
      }
    }
    if (files.relief().isPresent() && !files.relief().equals(saved.files().relief())) {
      MapFolder.writeRelief(mapFolder, files.relief().get());
    }
    final Game before = firstSave ? Game.builder().build() : saved.game();
    writeNationPictures(saving.game(), files, before);
    if (firstSave
        || !files.unitPictures().equals(saved.files().unitPictures())
        || !files.nationUnitPictures().equals(saved.files().nationUnitPictures())
        || !files.properties().equals(saved.files().properties())
        || !MapXml.nations(saving.game()).equals(MapXml.nations(before))) {
      writeUploadedUnitPictures(saving.game(), files, saved.files());
    }
    final String notes = MapXml.notes(saving.game());
    if (firstSave || !notes.equals(MapXml.notes(saved.game()))) {
      writeNotes(notesFile(xml), notes);
    }
    saving.texts().writeChanged(mapFolder, saved.texts());
    final Game game = mapName == null ? saving.game() : MapXml.withMapName(saving.game(), mapName);
    // the file there is the last save: what TripleA's model does not hold is kept from it
    MapFolder.replace(xml, file -> GameXmlKeeper.export(game, xml, file));
    saved = saving;
    current = saved;
    listeners.forEach(Runnable::run);
  }

  /**
   * The uploaded pictures for every nation and the ones imported for a nation, over the pictures
   * there were; a type whose picture was taken back since the last save gets the game's again.
   */
  private static void writeUploadedUnitPictures(
      final Game game, final MapFiles files, final MapFiles before) throws IOException {
    final Path mapFolder = files.folder().orElseThrow();
    final List<String> units = MapXml.unitTypes(game);
    for (final String nation : MapXml.nations(game)) {
      final Color color = files.nationColor(nation).orElse(Color.GRAY);
      for (final String unit : units) {
        final NationUnit nationUnit = new NationUnit(nation, unit);
        final Optional<BufferedImage> imported =
            Optional.ofNullable(files.nationUnitPictures().get(nationUnit));
        final Optional<UnitPicture> uploaded = Optional.ofNullable(files.unitPictures().get(unit));
        if (imported.isPresent()) {
          MapFolder.writeUnitPicture(mapFolder, nation, unit, imported.get());
        } else if (uploaded.isPresent()) {
          MapFolder.writeUnitPicture(mapFolder, nation, unit, uploaded.get().forNation(color));
        } else if (before.unitPictures().containsKey(unit)
            || before.nationUnitPictures().containsKey(nationUnit)) {
          MapFolder.restoreUnitPicture(mapFolder, nation, color, unit);
        }
      }
    }
  }

  /**
   * The notes file the game shows; for no notes none, which the game makes again, empty, from the
   * game file's notes.
   */
  private static void writeNotes(final Path file, final String notes) throws IOException {
    if (notes.isBlank()) {
      MapFolder.remove(file);
    } else {
      MapFolder.replace(file, written -> Files.writeString(written, notes));
    }
  }

  /** Flags and unit pictures for the nations and unit types that the game before did not have. */
  private static void writeNationPictures(final Game game, final MapFiles files, final Game before)
      throws IOException {
    final Path mapFolder = files.folder().orElseThrow();
    final List<String> units = MapXml.unitTypes(game);
    final List<String> newUnits =
        units.stream().filter(u -> !MapXml.unitTypes(before).contains(u)).toList();
    for (final String nation : MapXml.nations(game)) {
      final Color color = files.nationColor(nation).orElse(Color.GRAY);
      final boolean isNew = !MapXml.nations(before).contains(nation);
      if (isNew) {
        MapFolder.writeFlags(mapFolder, nation, color);
      }
      MapFolder.writeUnitPictures(mapFolder, nation, color, isNew ? units : newUnits);
    }
  }
}
