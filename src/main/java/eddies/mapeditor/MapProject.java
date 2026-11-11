package eddies.mapeditor;

import games.strategy.triplea.EngineImageLoader;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.Color;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

  private final Deque<State> undos = new ArrayDeque<>();
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
  private static Path notesFile(final Path xmlFile) {
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
    return new NewMap(open(made.getXmlFile().orElseThrow()), List.copyOf(refused));
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
    listeners.forEach(Runnable::run);
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

  void setNationColor(final String nation, final Color color) {
    step(state(game(), current.files().withNationColor(nation, color)));
  }

  /** The picture as the unit type's for every nation from the next save on, as one step. */
  void setUnitPicture(final String unitType, final UnitPicture picture) {
    step(state(game(), current.files().withUnitPicture(unitType, picture)));
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
   * files they were redrawn from are still the map's; a change meanwhile keeps it.
   *
   * @return whether they were taken
   */
  boolean takeRedrawn(final MapFiles from, final Redrawn redrawn) {
    if (getFiles() != from) {
      return false;
    }
    step(state(game(), redrawn.files()));
    return true;
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
      problems = MapChecks.quick(game(), getFiles());
      problemsOf = current;
    }
    return problems;
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
        .or(() -> gamesUnitPicture("Germans", unit));
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
      files.writeNamePlaces(saving.game(), saved.files().places());
    }
    files.writePlaces(saving.game(), saved.files().places());
    if (firstSave || !saving.files().properties().equals(saved.files().properties())) {
      files.writeProperties();
    }
    if (files.picture().isPresent()
        && (firstSave || !files.picture().equals(saved.files().picture()))) {
      MapFolder.writePicture(mapFolder, files.picture().get());
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
