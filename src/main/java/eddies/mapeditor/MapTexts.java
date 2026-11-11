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
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
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
 * .properties and tooltips.properties, each held as its whole text, so a change of one entry keeps
 * every other line, comment and odd entry as it was. The game reads them as ISO-8859-1.
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

  /** The three files, by what they hold. */
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
            + " nation's.");

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

  /** What the key means to the game, in plain words. */
  static String explain(final Kind kind, final String key, final Game game) {
    return switch (kind) {
      case OBJECTIVES -> explainObjective(key, game);
      case NOTIFICATIONS -> explainMessage(key, game);
      case TOOLTIPS -> explainTooltip(key, game);
    };
  }

  private static String explainObjective(final String key, final Game game) {
    if (key.equals(PANEL_NAME)) {
      return "The name of the objectives tab";
    }
    final String prefix = objectivesPrefix(game);
    if (!key.startsWith(prefix)) {
      return "For another game, not this one";
    }
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
        ? attachment.getName().startsWith("objectiveAttachment")
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
          case TOOLTIPS ->
              MapXml.unitTypes(game).stream()
                  .flatMap(u -> Stream.of(TOOLTIP + u, TOOLTIP + u + APPEND))
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
        .filter(o -> name.equals(o.getName()))
        .map(o -> o.getValue() == null ? "" : o.getValue().strip())
        .filter(v -> !v.isEmpty())
        .findFirst();
  }
}
