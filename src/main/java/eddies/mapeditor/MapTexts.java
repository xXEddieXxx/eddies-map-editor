package eddies.mapeditor;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Info;
import org.triplea.util.FileNameUtils;

/**
 * The texts beside the game that the game reads by key: objectives.properties, notifications
 * .properties, tooltips.properties, politicstext.properties and actionstext.properties, each held
 * as its whole text, so a change of one entry keeps every other line, comment and odd entry as it
 * was. The game reads them as ISO-8859-1.
 *
 * @param unreadable the files that are there but could not be read: shown without entries, not
 *     changed and never written
 */
@Slf4j
record MapTexts(Map<Kind, String> texts, Set<Kind> unreadable) {
  private static final String GROUP = "TABLEGROUP.";
  private static final String PANEL_NAME = "Objectives.Panel.Name";
  private static final String SOUNDS = "_sounds";
  private static final String TOOLTIP = "tooltip.unit.";
  private static final String APPEND = ".append";
  private static final String SOUND_FOLDER = "Sound.Default.Folder";
  private static final List<String> ATTACHMENT_WORDS =
      List.of("objectiveAttachment", "triggerAttachment", "conditionAttachment");
  // the game's sound keys, SoundPath, by plain name; a key ending in _ is followed by a message
  private static final Map<String, String> SOUND_NAMES =
      Map.ofEntries(
          Map.entry("chat_message", "Chat message"),
          Map.entry("chat_slap", "Chat slap"),
          Map.entry("chat_join_game", "Someone joins the chat"),
          Map.entry("click_button", "Button clicked"),
          Map.entry("game_start", "Game starts"),
          Map.entry("game_won", "Game won"),
          Map.entry("required_your_turn_series", "Your turn starts"),
          Map.entry("battle_aa_hit", "Anti-air hit"),
          Map.entry("battle_aa_miss", "Anti-air miss"),
          Map.entry("battle_air", "Air battle"),
          Map.entry("battle_air_successful", "Air battle won"),
          Map.entry("battle_bombard", "Bombardment"),
          Map.entry("battle_failure", "Battle lost"),
          Map.entry("battle_land", "Land battle"),
          Map.entry("battle_retreat_air", "Air retreat"),
          Map.entry("battle_retreat_land", "Land retreat"),
          Map.entry("battle_retreat_sea", "Sea retreat"),
          Map.entry("battle_retreat_submerge", "Submarines submerge"),
          Map.entry("battle_sea_normal", "Sea battle"),
          Map.entry("battle_sea_subs", "Submarine battle"),
          Map.entry("battle_sea_successful", "Sea battle won"),
          Map.entry("battle_stalemate", "Battle undecided"),
          Map.entry("bombing_rocket", "Rocket attack"),
          Map.entry("bombing_strategic", "Strategic bombing"),
          Map.entry("phase_battle", "Phase: battle"),
          Map.entry("phase_end_turn", "Phase: end of turn"),
          Map.entry("phase_move_combat", "Phase: combat move"),
          Map.entry("phase_move_noncombat", "Phase: non-combat move"),
          Map.entry("phase_placement", "Phase: placement"),
          Map.entry("phase_politics", "Phase: politics"),
          Map.entry("phase_purchase", "Phase: purchase"),
          Map.entry("phase_technology", "Phase: technology"),
          Map.entry("phase_user_actions", "Phase: actions"),
          Map.entry("placed_air", "Air units placed"),
          Map.entry("placed_infrastructure", "Buildings placed"),
          Map.entry("placed_land", "Land units placed"),
          Map.entry("placed_sea", "Sea units placed"),
          Map.entry("political_action_failure", "Political action failed"),
          Map.entry("political_action_successful", "Political action done"),
          Map.entry("technology_failure", "No technology found"),
          Map.entry("technology_successful", "Technology found"),
          Map.entry("territory_capture_blitz", "Territory taken by blitz"),
          Map.entry("territory_capture_capital", "Capital taken"),
          Map.entry("territory_capture_land", "Territory taken"),
          Map.entry("territory_capture_sea", "Sea zone taken"),
          Map.entry("user_action_failure", "Action failed"),
          Map.entry("user_action_successful", "Action done"),
          Map.entry("notification_", "With a message, its sounds"),
          Map.entry("victory_", "Victory, its sounds"),
          Map.entry("defeat_", "Defeat, its sounds"));

  /** The files, by what they hold. */
  @AllArgsConstructor
  @Getter
  enum Kind {
    OBJECTIVES(
        "objectives.properties",
        "Objectives",
        "The objectives tab in the game: groups of conditions, each shown with its text and"
            + " whether it is met. A key names the game, the nation and the condition."),
    NOTIFICATIONS(
        "notifications.properties",
        "Messages",
        "The messages triggers show: a trigger's \"notification\" or \"victory\" option names the"
            + " message by its key. The text is HTML."),
    TOOLTIPS(
        "tooltips.properties",
        "Unit tool tips",
        "The tool tip of a unit type, in HTML: it replaces the one the game makes, or with"
            + " .append is added below it; a nation's name after the unit's makes it that"
            + " nation's."),
    POLITICS(
        "politicstext.properties",
        "Political actions",
        "The texts of political actions, eg their button and the messages when they succeed or"
            + " fail. A key is an action's \"text\" and the part, eg Axis_To_War.BUTTON."),
    ACTIONS(
        "actionstext.properties",
        "User actions",
        "The texts of user actions, eg their button and the messages when they succeed or fail."
            + " A key is an action's \"text\" and the part, eg Build_Fort.BUTTON."),
    SOUNDS(
        "sounds.properties",
        "Sounds",
        "The sounds the game plays, eg for a land battle: mp3 files or folders of them, separated"
            + " by ;, below the map's sounds folder or the game's. A nation's name after the key"
            + " makes it that nation's.");

    private final String file;
    private final String title;
    private final String help;

    @Override
    public String toString() {
      return title;
    }
  }

  /** An entry: its key and its text, as the game reads it. */
  record Entry(String key, String value) {}

  MapTexts {
    texts = Map.copyOf(texts);
    unreadable = Set.copyOf(unreadable);
  }

  MapTexts(final Map<Kind, String> texts) {
    this(texts, Set.of());
  }

  static MapTexts none() {
    return new MapTexts(Map.of());
  }

  /** The files in the map's folder; a missing one has no entries, nor one that does not read. */
  static MapTexts read(final Path folder) {
    final Map<Kind, String> texts = new EnumMap<>(Kind.class);
    final Set<Kind> unreadable = EnumSet.noneOf(Kind.class);
    for (final Kind kind : Kind.values()) {
      final Path file = folder.resolve(kind.getFile());
      if (!Files.exists(file)) {
        continue;
      }
      try {
        texts.put(kind, MapFolder.readText(file));
      } catch (final IOException e) {
        // the map opens without it; Files shows why
        log.warn("Could not read {}", file, e);
        unreadable.add(kind);
      }
    }
    return new MapTexts(texts, unreadable);
  }

  String text(final Kind kind) {
    return texts.getOrDefault(kind, "");
  }

  /** The entries in the order of the file; a key given twice once, with the text the game reads. */
  List<Entry> entries(final Kind kind) {
    final Map<String, String> entries = new LinkedHashMap<>();
    for (final String chunk : logicalLines(text(kind))) {
      parse(chunk).ifPresent(e -> entries.put(e.key(), e.value()));
    }
    return entries.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList();
  }

  Optional<String> value(final Kind kind, final String key) {
    return entries(kind).stream().filter(e -> e.key().equals(key)).map(Entry::value).findFirst();
  }

  /**
   * With the entry's text, in place of the one the game reads, else added at the end; the same
   * texts when it is so already.
   */
  MapTexts with(final Kind kind, final String key, final String value) {
    if (unreadable.contains(kind) || value(kind, key).filter(value::equals).isPresent()) {
      return this;
    }
    final List<String> chunks = new ArrayList<>(logicalLines(text(kind)));
    final String newline = text(kind).contains("\r\n") ? "\r\n" : "\n";
    final String line = escape(key, true) + "=" + escape(value, false);
    int last = -1;
    for (int i = 0; i < chunks.size(); i++) {
      if (parse(chunks.get(i)).filter(e -> e.key().equals(key)).isPresent()) {
        last = i;
      }
    }
    if (last >= 0) {
      final String old = chunks.get(last);
      chunks.set(last, line + (old.endsWith("\n") ? newline : ""));
    } else {
      if (!chunks.isEmpty() && !chunks.getLast().endsWith("\n")) {
        chunks.add(newline);
      }
      chunks.add(line + newline);
    }
    return withText(kind, String.join("", chunks));
  }

  /** Without the entry, also where the file gives its key more than once. */
  MapTexts without(final Kind kind, final String key) {
    if (unreadable.contains(kind)) {
      return this;
    }
    final List<String> kept =
        logicalLines(text(kind)).stream()
            .filter(c -> parse(c).filter(e -> e.key().equals(key)).isEmpty())
            .toList();
    final String text = String.join("", kept);
    return text.equals(text(kind)) ? this : withText(kind, text);
  }

  /**
   * With each key starting with oldPrefix renamed to start with newPrefix, its text and place
   * kept; a key whose new name the file has already stays as it is.
   */
  MapTexts withKeyPrefix(final Kind kind, final String oldPrefix, final String newPrefix) {
    if (unreadable.contains(kind)) {
      return this;
    }
    final Set<String> present =
        entries(kind).stream().map(Entry::key).collect(Collectors.toSet());
    final StringBuilder renamed = new StringBuilder();
    for (final String chunk : logicalLines(text(kind))) {
      renamed.append(
          parse(chunk)
              .map(Entry::key)
              .filter(k -> k.startsWith(oldPrefix))
              .filter(k -> !present.contains(newPrefix + k.substring(oldPrefix.length())))
              .map(k -> renamedChunk(chunk, k, oldPrefix, newPrefix))
              .orElse(chunk));
    }
    final String text = renamed.toString();
    return text.equals(text(kind)) ? this : withText(kind, text);
  }

  /** The line with the key's start replaced; the rest, eg the text's own form, as it was. */
  private static String renamedChunk(
      final String chunk, final String key, final String oldPrefix, final String newPrefix) {
    final String content = chunk.stripLeading();
    final String indent = chunk.substring(0, chunk.length() - content.length());
    final String escapedOld = escape(oldPrefix, true);
    if (content.startsWith(escapedOld)) {
      return indent + escape(newPrefix, true) + content.substring(escapedOld.length());
    }
    // the key written otherwise, eg with \\u escapes: the line anew, read the same
    final String end = chunk.endsWith("\r\n") ? "\r\n" : chunk.endsWith("\n") ? "\n" : "";
    final String value = parse(chunk).map(Entry::value).orElse("");
    return escape(newPrefix + key.substring(oldPrefix.length()), true)
        + "="
        + escape(value, false)
        + end;
  }

  private MapTexts withText(final Kind kind, final String text) {
    final Map<Kind, String> changed = new EnumMap<>(Kind.class);
    changed.putAll(texts);
    changed.put(kind, text);
    return new MapTexts(changed, unreadable);
  }

  /**
   * Writes the files that differ from the saved ones, each keeping its first version as .bak; a
   * file left without text is removed.
   */
  void writeChanged(final Path folder, final MapTexts saved) throws IOException {
    for (final Kind kind : Kind.values()) {
      final String text = text(kind);
      if (unreadable.contains(kind) || text.equals(saved.text(kind))) {
        continue;
      }
      final Path file = folder.resolve(kind.getFile());
      if (text.isBlank()) {
        MapFolder.remove(file);
      } else {
        MapFolder.replaceText(file, text);
      }
    }
  }

  // ---- the file's lines as the game reads them

  /**
   * The text in logical lines as {@link Properties} reads them, each with its line ends, so that
   * joined they give the text again: a line ending in an odd number of backslashes goes on in the
   * next. shortcut: a lone "\r" as line end is taken as part of the line; no map seen uses it.
   */
  private static List<String> logicalLines(final String text) {
    final List<String> chunks = new ArrayList<>();
    final StringBuilder chunk = new StringBuilder();
    boolean continues = false;
    for (final String line : text.split("(?<=\n)")) {
      if (line.isEmpty()) {
        continue;
      }
      chunk.append(line);
      final String content = line.stripTrailing();
      final String start = content.stripLeading();
      final boolean comment = !continues && (start.startsWith("#") || start.startsWith("!"));
      continues = !comment && !start.isEmpty() && endsInOddBackslashes(content);
      if (!continues) {
        chunks.add(chunk.toString());
        chunk.setLength(0);
      }
    }
    if (!chunk.isEmpty()) {
      chunks.add(chunk.toString());
    }
    return chunks;
  }

  private static boolean endsInOddBackslashes(final String line) {
    int count = 0;
    for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
      count++;
    }
    return count % 2 == 1;
  }

  /** The entry of a logical line, empty for a comment or a blank line. */
  private static Optional<Entry> parse(final String chunk) {
    final Properties properties = new Properties();
    try {
      properties.load(new StringReader(chunk));
    } catch (final IOException | IllegalArgumentException e) {
      // eg a broken \\u escape: the game cannot read it either, so it stays as it is
      return Optional.empty();
    }
    return properties.stringPropertyNames().stream()
        .findFirst()
        .map(k -> new Entry(k, properties.getProperty(k)));
  }

  /** The text as the file holds it; all but printable ASCII as unicode escapes. */
  private static String escape(final String text, final boolean isKey) {
    final StringBuilder escaped = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      switch (c) {
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        case '\f' -> escaped.append("\\f");
        case ' ' -> escaped.append(isKey || i == 0 ? "\\ " : " ");
        case '=', ':', '#', '!' -> escaped.append(isKey ? "\\" + c : String.valueOf(c));
        default -> {
          if (c < 0x20 || c > 0x7e) {
            escaped.append(String.format("\\u%04X", (int) c));
          } else {
            escaped.append(c);
          }
        }
      }
    }
    return escaped.toString();
  }

  // ---- what the keys mean to the game

  /** The start of the keys of this game in objectives.properties, eg "Big_World." */
  static String objectivesPrefix(final Game game) {
    final String name = Optional.ofNullable(game.getInfo()).map(Info::getName).orElse("");
    return FileNameUtils.replaceIllegalCharacters(name, '_').replace(" ", "_") + ".";
  }

  /** The key of the text the game shows for a nation's objective or condition. */
  static String objectiveKey(final Game game, final String nation, final String name) {
    return objectivesPrefix(game) + nation + ";" + name;
  }

  /**
   * With the text of a nation's objective, and the objective in a group, as the game shows only
   * those a group lists: the group named after the nation, else a new one after the others.
   */
  MapTexts withObjectiveText(
      final Game game, final String nation, final String name, final String text) {
    final MapTexts written = with(Kind.OBJECTIVES, objectiveKey(game, nation, name), text);
    final String groups = objectivesPrefix(game) + GROUP;
    final List<Entry> listing =
        written.entries(Kind.OBJECTIVES).stream()
            .filter(e -> e.key().startsWith(groups))
            .toList();
    if (listing.stream().anyMatch(e -> List.of(e.value().split(";")).contains(name))) {
      return written;
    }
    final Optional<Entry> own =
        listing.stream().filter(e -> e.key().endsWith(";" + nation)).findFirst();
    if (own.isPresent()) {
      final String listed = own.get().value().strip();
      return written.with(
          Kind.OBJECTIVES, own.get().key(), listed.isEmpty() ? name : listed + ";" + name);
    }
    // two digits, as the game sorts the groups by this text
    final int number =
        listing.stream()
            .map(e -> e.key().substring(groups.length()).split(";")[0])
            .filter(n -> n.matches("\\d{1,8}"))
            .mapToInt(Integer::parseInt)
            .max()
            .orElse(0)
            + 1;
    return written.with(
        Kind.OBJECTIVES, groups + String.format("%02d", number) + ";" + nation, name);
  }

  /** Without the objective's text, and without it in the groups that list it. */
  MapTexts withoutObjectiveText(final Game game, final String nation, final String name) {
    MapTexts changed = without(Kind.OBJECTIVES, objectiveKey(game, nation, name));
    final String groups = objectivesPrefix(game) + GROUP;
    for (final Entry group : changed.entries(Kind.OBJECTIVES)) {
      final List<String> listed = List.of(group.value().split(";"));
      if (group.key().startsWith(groups) && listed.contains(name)) {
        final String rest =
            listed.stream().filter(n -> !n.equals(name)).collect(Collectors.joining(";"));
        // a group that lists nothing shows as an empty heading in the game: it goes too
        changed =
            rest.isEmpty()
                ? changed.without(Kind.OBJECTIVES, group.key())
                : changed.with(Kind.OBJECTIVES, group.key(), rest);
      }
    }
    return changed;
  }

  /** Whether the objective key is for another game, which does not show it. */
  static boolean isForeign(final String key, final Game game) {
    return !key.equals(PANEL_NAME) && !key.startsWith(objectivesPrefix(game));
  }

  /**
   * The start all objective keys have when none has this game's, eg a map's copy of another
   * game's file: the game shows none of them.
   */
  static Optional<String> foreignPrefix(final MapTexts texts, final Game game) {
    final List<String> keys = objectiveKeys(texts);
    final List<String> prefixes = keys.stream().map(MapTexts::prefixOf).distinct().toList();
    final String own = objectivesPrefix(game);
    return keys.stream().anyMatch(k -> k.startsWith(own)) || prefixes.size() != 1
        ? Optional.empty()
        : Optional.of(prefixes.getFirst()).filter(p -> !p.isEmpty());
  }

  /** "37 objective texts are for another game" when {@link #foreignPrefix} finds them. */
  static Optional<String> foreignObjectivesProblem(final MapTexts texts, final Game game) {
    final int count = objectiveKeys(texts).size();
    return foreignPrefix(texts, game)
        .map(
            p ->
                count == 1
                    ? "1 objective text is for another game"
                    : count + " objective texts are for another game");
  }

  private static List<String> objectiveKeys(final MapTexts texts) {
    return texts.entries(Kind.OBJECTIVES).stream()
        .map(Entry::key)
        .filter(k -> !k.equals(PANEL_NAME))
        .toList();
  }

  /**
   * The start of an objectives key before nation and condition or TABLEGROUP, the game's name
   * that the game compares with its own, eg "Big_World."; empty when the key has none.
   */
  static String prefixOf(final String key) {
    final int group = key.indexOf(GROUP);
    if (group >= 0) {
      return key.substring(0, group);
    }
    final int semicolon = key.indexOf(';');
    final int dot = semicolon < 0 ? -1 : key.lastIndexOf('.', semicolon);
    return key.substring(0, dot + 1);
  }

  /** The key in plain words, as the list's row shows it; the key when nothing is left. */
  static String title(final Kind kind, final String key, final Game game) {
    final String title =
        switch (kind) {
          case OBJECTIVES -> titleOfObjective(key);
          case NOTIFICATIONS ->
              key.endsWith(SOUNDS)
                  ? "Sound of " + key.substring(0, key.length() - SOUNDS.length())
                  : key;
          case TOOLTIPS -> titleOfTooltip(key, game);
          case POLITICS, ACTIONS -> {
            final int dot = key.lastIndexOf('.');
            final String part = key.substring(dot + 1).toLowerCase(Locale.ROOT);
            yield dot <= 0 || part.isEmpty()
                ? key
                : key.substring(0, dot) + " · " + part.substring(0, 1).toUpperCase(Locale.ROOT)
                    + part.substring(1);
          }
          case SOUNDS -> explainSound(key, game);
        };
    return title.isBlank() ? key : title;
  }

  private static String titleOfObjective(final String key) {
    if (key.equals(PANEL_NAME)) {
      return "Name of the objectives tab";
    }
    final String[] parts = key.substring(prefixOf(key).length()).split(";", -1);
    if (parts.length != 2) {
      return key;
    }
    if (parts[0].startsWith(GROUP)) {
      final String number = parts[0].substring(GROUP.length()).replaceFirst("^0+(?=\\d)", "");
      return "Group " + number + " · " + parts[1];
    }
    final String condition = plainCondition(parts[1], parts[0]);
    return condition.isEmpty() ? key : parts[0] + " · " + condition;
  }

  /**
   * The condition's name without the kind of attachment, the nation and a leading number, eg
   * "Trade with Russia" for objectiveAttachment_Germans_1_Trade_with_Russia.
   */
  private static String plainCondition(final String name, final String nation) {
    final List<String> words = new ArrayList<>();
    for (final String word : name.split("_")) {
      final String rest =
          ATTACHMENT_WORDS.stream()
              .filter(word::startsWith)
              .findFirst()
              .map(w -> word.substring(w.length()))
              .orElse(word);
      if (!rest.isEmpty() && !rest.equals(nation) && !(words.isEmpty() && rest.matches("\\d+"))) {
        words.add(rest);
      }
    }
    return String.join(" ", words);
  }

  private static String titleOfTooltip(final String key, final Game game) {
    if (!key.startsWith(TOOLTIP)) {
      return key;
    }
    final boolean appended = key.endsWith(APPEND);
    final String rest =
        key.substring(TOOLTIP.length(), key.length() - (appended ? APPEND.length() : 0));
    final String how = appended ? "Added to the tool tip of " : "Tool tip of ";
    final List<String> units = MapXml.unitTypes(game);
    if (units.contains(rest)) {
      return how + rest;
    }
    return MapXml.nations(game).stream()
        .filter(n -> rest.endsWith("." + n))
        .map(n -> rest.substring(0, rest.length() - n.length() - 1))
        .filter(units::contains)
        .findFirst()
        .map(unit -> how + unit + " for " + rest.substring(unit.length() + 1))
        .orElse(key);
  }

  /** What the key means to the game, in plain words. */
  static String explain(final Kind kind, final String key, final Game game) {
    return switch (kind) {
      case OBJECTIVES -> explainObjective(key, game);
      case NOTIFICATIONS -> explainMessage(key, game);
      case TOOLTIPS -> explainTooltip(key, game);
      case POLITICS, ACTIONS -> explainAction(kind, key, game);
      case SOUNDS -> explainSound(key, game);
    };
  }

  private static String explainObjective(final String key, final Game game) {
    if (key.equals(PANEL_NAME)) {
      return "The name of the objectives tab";
    }
    if (isForeign(key, game)) {
      return "For another game";
    }
    final String prefix = objectivesPrefix(game);
    final String[] parts = key.substring(prefix.length()).split(";", -1);
    if (parts.length != 2) {
      return "Not read: a key needs one ; between nation and condition";
    }
    if (parts[0].startsWith(GROUP)) {
      return "Group \"" + parts[1] + "\", " + parts[0].substring(GROUP.length())
          + " in order: lists its conditions, separated by ;";
    }
    return conditions(game).contains(parts[1])
        ? "What " + parts[0] + "'s condition " + parts[1] + " means"
        : "Not shown: the game has no condition " + parts[1];
  }

  private static String explainMessage(final String key, final Game game) {
    if (key.endsWith(SOUNDS)) {
      return "The sounds played with message " + key.substring(0, key.length() - SOUNDS.length());
    }
    final List<String> triggers = triggersShowing(game, key);
    return triggers.isEmpty()
        ? "No trigger shows this message"
        : "Shown by trigger " + String.join(", ", triggers);
  }

  private static String explainAction(final Kind kind, final String key, final Game game) {
    final int dot = key.lastIndexOf('.');
    final String textKey = dot < 0 ? key : key.substring(0, dot);
    final List<String> actions =
        ActionsXml.actions(game).stream()
            .filter(a -> a.textKind() == kind)
            .filter(a -> a.textParts().contains(key.substring(dot + 1)))
            .filter(a -> textKey.equals(ActionsXml.textKey(game, a)))
            .map(a -> a.name() + " of " + a.nation())
            .distinct()
            .toList();
    return actions.isEmpty()
        ? "No action of the game reads it"
        : key.substring(dot + 1) + " of " + String.join(", ", actions);
  }

  /**
   * The sound keys the game plays, eg battle_land, then those of each nation, eg
   * battle_land_Germans, and those its triggers' messages name in notifications.properties; the
   * default folder first.
   */
  static List<String> soundKeys(final Game game, final MapTexts texts) {
    final List<String> fixed =
        SOUND_NAMES.keySet().stream().filter(k -> !k.endsWith("_")).sorted().toList();
    final Stream<String> triggered =
        AttachmentsXml.attachments(game).stream()
            .filter(a -> "TriggerAttachment".equals(AttachmentsXml.kind(a)))
            .flatMap(
                a ->
                    Stream.concat(
                        option(a, "notification")
                            .flatMap(m -> messageSounds(texts, m))
                            .map(s -> "notification_" + s)
                            .stream(),
                        option(a, "victory")
                            .flatMap(m -> messageSounds(texts, m))
                            .stream()
                            .flatMap(s -> Stream.of("victory_" + s, "defeat_" + s))));
    return Stream.of(
            Stream.of(SOUND_FOLDER),
            fixed.stream(),
            MapXml.nations(game).stream()
                .flatMap(n -> fixed.stream().map(k -> k + "_" + n)),
            triggered)
        .flatMap(s -> s)
        .distinct()
        .toList();
  }

  private static Optional<String> messageSounds(final MapTexts texts, final String message) {
    return texts.value(Kind.NOTIFICATIONS, message + SOUNDS).map(String::strip);
  }

  private static String explainSound(final String key, final Game game) {
    if (key.equals(SOUND_FOLDER)) {
      return "Default folder";
    }
    if (SOUND_NAMES.containsKey(key)) {
      return SOUND_NAMES.get(key);
    }
    final Optional<String> nation =
        MapXml.nations(game).stream()
            .filter(n -> key.endsWith("_" + n))
            .filter(n -> SOUND_NAMES.containsKey(key.substring(0, key.length() - n.length() - 1)))
            .findFirst();
    if (nation.isPresent()) {
      return SOUND_NAMES.get(key.substring(0, key.length() - nation.get().length() - 1))
          + " for "
          + nation.get();
    }
    return SOUND_NAMES.entrySet().stream()
        .filter(e -> e.getKey().endsWith("_") && key.startsWith(e.getKey()))
        .findFirst()
        .map(e -> e.getValue() + " " + key.substring(e.getKey().length()))
        .orElse(
            key.startsWith("battle_") && (key.endsWith("_hit") || key.endsWith("_miss"))
                ? "Fire of " + key.substring("battle_".length())
                : "Not played by the game");
  }

  private static String explainTooltip(final String key, final Game game) {
    if (!key.startsWith(TOOLTIP)) {
      return "Not read by the game";
    }
    final boolean appended = key.endsWith(APPEND);
    final String rest =
        key.substring(TOOLTIP.length(), key.length() - (appended ? APPEND.length() : 0));
    final String how = appended ? "Added below the tool tip of " : "The tool tip of ";
    if (MapXml.unitTypes(game).contains(rest)) {
      return how + rest;
    }
    return MapXml.nations(game).stream()
        .filter(n -> rest.endsWith("." + n))
        .map(n -> rest.substring(0, rest.length() - n.length() - 1) + " for " + n)
        .filter(unitAndNation -> MapXml.unitTypes(game).contains(unitAndNation.split(" for ")[0]))
        .findFirst()
        .map(unitAndNation -> how + unitAndNation)
        .orElse("Not shown: the game has no such unit type");
  }

  /**
   * Entries the map may want that the file does not have yet, keys from the game's conditions,
   * triggers, nations and unit types, each with a text to start from. Of the conditions and
   * triggers only those like objectives: national objectives and triggers that pay, win or tell.
   */
  static List<Entry> suggestions(final Kind kind, final Game game, final MapTexts texts) {
    return suggestions(kind, game, texts, MapTexts::isLikeObjective);
  }

  /** As {@link #suggestions}, with every condition and trigger of a nation. */
  static List<Entry> allSuggestions(final Kind kind, final Game game, final MapTexts texts) {
    return suggestions(kind, game, texts, a -> true);
  }

  private static boolean isLikeObjective(final Attachment attachment) {
    return "RulesAttachment".equals(AttachmentsXml.kind(attachment))
        ? AttachmentsXml.readName(attachment.getName()).startsWith("objectiveAttachment")
        : Stream.of("resource", "victory", "notification")
            .anyMatch(o -> option(attachment, o).isPresent());
  }

  private static List<Entry> suggestions(
      final Kind kind, final Game game, final MapTexts texts, final Predicate<Attachment> offered) {
    final List<String> present = texts.entries(kind).stream().map(Entry::key).toList();
    final Stream<Entry> all =
        switch (kind) {
          case OBJECTIVES -> objectiveSuggestions(game, offered);
          case NOTIFICATIONS ->
              AttachmentsXml.attachments(game).stream()
                  .filter(a -> "TriggerAttachment".equals(AttachmentsXml.kind(a)))
                  .flatMap(a -> Stream.of("notification", "victory").map(o -> option(a, o)))
                  .flatMap(Optional::stream)
                  .map(message -> new Entry(message, ""));
          case SOUNDS ->
              soundKeys(game, texts).stream().map(k -> new Entry(k, ""));
          case TOOLTIPS ->
              MapXml.unitTypes(game).stream()
                  .flatMap(u -> Stream.of(TOOLTIP + u, TOOLTIP + u + APPEND))
                  .map(k -> new Entry(k, ""));
          case POLITICS, ACTIONS ->
              ActionsXml.actions(game).stream()
                  .filter(a -> a.textKind() == kind)
                  .flatMap(
                      a ->
                          a.textParts().stream()
                              .map(part -> ActionsXml.textKey(game, a) + "." + part))
                  .map(k -> new Entry(k, ""));
        };
    return all.filter(e -> !present.contains(e.key())).distinct().toList();
  }

  private static Stream<Entry> objectiveSuggestions(
      final Game game, final Predicate<Attachment> offered) {
    final String prefix = objectivesPrefix(game);
    final List<String> nations = MapXml.nations(game);
    final Stream<Entry> groups =
        nations.stream()
            .map(
                n ->
                    new Entry(
                        prefix
                            + GROUP
                            + String.format("%02d", nations.indexOf(n) + 1)
                            + ";"
                            + n,
                        String.join(
                            ";",
                            conditionAttachments(game)
                                .filter(a -> n.equals(a.getAttachTo()))
                                .filter(offered)
                                .map(Attachment::getName)
                                .toList())));
    final Stream<Entry> conditions =
        conditionAttachments(game)
            .filter(offered)
            .map(a -> new Entry(prefix + a.getAttachTo() + ";" + a.getName(), ""));
    return Stream.of(Stream.of(new Entry(PANEL_NAME, "Objectives")), groups, conditions)
        .flatMap(s -> s);
  }

  /** The conditions and triggers the objectives tab can show: those of a nation. */
  private static Stream<Attachment> conditionAttachments(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .filter(
            a -> List.of("RulesAttachment", "TriggerAttachment").contains(AttachmentsXml.kind(a)))
        .filter(a -> a.getName() != null && MapXml.nations(game).contains(a.getAttachTo()));
  }

  private static List<String> conditions(final Game game) {
    return conditionAttachments(game).map(Attachment::getName).toList();
  }

  private static List<String> triggersShowing(final Game game, final String message) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> "TriggerAttachment".equals(AttachmentsXml.kind(a)))
        .filter(
            a ->
                Stream.of("notification", "victory")
                    .anyMatch(o -> option(a, o).filter(message::equals).isPresent()))
        .map(Attachment::getName)
        .distinct()
        .toList();
  }

  private static Optional<String> option(final Attachment attachment, final String name) {
    return AttachmentsXml.options(attachment).stream()
        .filter(o -> AttachmentsXml.isOption(o, name))
        .map(o -> o.getValue() == null ? "" : o.getValue().strip())
        .filter(v -> !v.isEmpty())
        .findFirst();
  }
}
