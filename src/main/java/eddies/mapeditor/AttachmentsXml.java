package eddies.mapeditor;

import games.strategy.engine.data.DynamicallyModifiable;
import games.strategy.engine.data.IAttachment;
import games.strategy.engine.data.gameparser.XmlGameElementMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;

/**
 * The game's attachments as they stand in its XML, for the tool that shows and changes any of them:
 * by their place in the list, as names repeat (every unit's is "unitAttachment"). Every change
 * returns a new game; what an attachment holds beyond what is changed, eg "foreach", is kept.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class AttachmentsXml {
  private static final String PACKAGE = "games.strategy.triplea.attachments.";

  /** The kinds TripleA makes attachments of, with what each is attached to. */
  static final Map<String, String> KINDS =
      Map.ofEntries(
          Map.entry("TriggerAttachment", "player"),
          Map.entry("RulesAttachment", "player"),
          Map.entry("PlayerAttachment", "player"),
          Map.entry("PoliticalActionAttachment", "player"),
          Map.entry("UserActionAttachment", "player"),
          Map.entry("TechAttachment", "player"),
          Map.entry("TerritoryAttachment", "territory"),
          Map.entry("CanalAttachment", "territory"),
          Map.entry("UnitAttachment", "unitType"),
          Map.entry("UnitSupportAttachment", "unitType"),
          Map.entry("TerritoryEffectAttachment", "territoryEffect"),
          Map.entry("RelationshipTypeAttachment", "relationship"),
          Map.entry("TechAbilityAttachment", "technology"));

  /**
   * The name TripleA reads an attachment of the kind by (Constants), eg "unitAttachment". Kinds in
   * {@link #NUMBERED} are found by its start, so a thing may have several, eg triggerAttachment2.
   */
  static final Map<String, String> NAMES =
      Map.ofEntries(
          Map.entry("TriggerAttachment", "triggerAttachment"),
          Map.entry("RulesAttachment", "conditionAttachment"),
          Map.entry("PlayerAttachment", "playerAttachment"),
          Map.entry("PoliticalActionAttachment", "politicalActionAttachment"),
          Map.entry("UserActionAttachment", "userActionAttachment"),
          Map.entry("TechAttachment", "techAttachment"),
          Map.entry("TerritoryAttachment", "territoryAttachment"),
          Map.entry("CanalAttachment", "canalAttachment"),
          Map.entry("UnitAttachment", "unitAttachment"),
          Map.entry("UnitSupportAttachment", "supportAttachment"),
          Map.entry("TerritoryEffectAttachment", "territoryEffectAttachment"),
          Map.entry("RelationshipTypeAttachment", "relationshipTypeAttachment"),
          Map.entry("TechAbilityAttachment", "techAbilityAttachment"));

  /** A kind in plain words, one and many, eg "Trigger" and "Triggers". */
  record KindName(String one, String many) {}

  /** Each of {@link #KINDS} in plain words, for the map maker who does not know TripleA's classes. */
  static final Map<String, KindName> KIND_NAMES =
      Map.ofEntries(
          Map.entry("TriggerAttachment", new KindName("Trigger", "Triggers")),
          Map.entry("RulesAttachment", new KindName("Condition", "Conditions")),
          Map.entry("PlayerAttachment", new KindName("Nation rules", "Nation rules")),
          Map.entry(
              "PoliticalActionAttachment",
              new KindName("Political action", "Political actions")),
          Map.entry("UserActionAttachment", new KindName("User action", "User actions")),
          Map.entry("TechAttachment", new KindName("Technology", "Technology")),
          Map.entry("TerritoryAttachment", new KindName("Territory", "Territories")),
          Map.entry("CanalAttachment", new KindName("Canal", "Canals")),
          Map.entry("UnitAttachment", new KindName("Unit type", "Unit types")),
          Map.entry("UnitSupportAttachment", new KindName("Unit support", "Unit support")),
          Map.entry(
              "TerritoryEffectAttachment", new KindName("Terrain effect", "Terrain effects")),
          Map.entry(
              "RelationshipTypeAttachment",
              new KindName("Relationship type", "Relationship types")),
          Map.entry(
              "TechAbilityAttachment",
              new KindName("Technology ability", "Technology abilities")));

  /** The kind in plain words; one TripleA's classes have but the editor does not know, as it is. */
  static KindName kindName(final String kind) {
    return KIND_NAMES.getOrDefault(kind, new KindName(kind, kind));
  }

  /** Whether the attachment has the name TripleA reads its kind by, eg "unitAttachment". */
  static boolean hasDefaultName(final Attachment attachment) {
    return NAMES.containsKey(kind(attachment)) && isNamed(attachment, NAMES.get(kind(attachment)));
  }

  private static final Set<String> NUMBERED =
      Set.of(
          "TriggerAttachment",
          "RulesAttachment",
          "PoliticalActionAttachment",
          "UserActionAttachment",
          "CanalAttachment",
          "UnitSupportAttachment");

  private static final Map<String, List<String>> KNOWN_OPTIONS = new ConcurrentHashMap<>();

  /**
   * Where the attachment of this name on the thing is: of two the last, as TripleA keeps one per
   * name and thing, eg G40's second territoryAttachment of Russia; -1 for none. Every read and
   * change of one attachment's options asks this, so a box reads what it writes.
   */
  static int lastIndexOf(
      final List<Attachment> attachments, final String name, final String attachTo) {
    for (int i = attachments.size() - 1; i >= 0; i--) {
      if (isNamed(attachments.get(i), name) && attachTo.equals(attachments.get(i).getAttachTo())) {
        return i;
      }
    }
    return -1;
  }

  static List<Attachment> attachments(final Game game) {
    return Optional.ofNullable(game.getAttachmentList())
        .map(AttachmentList::getAttachments)
        .map(List::copyOf)
        .orElse(List.of());
  }

  static List<Option> options(final Attachment attachment) {
    return Optional.ofNullable(attachment.getOptions()).map(List::copyOf).orElse(List.of());
  }

  /** Whether the attachment is written once for each element of a variable ("foreach"). */
  static boolean isForEach(final Attachment attachment) {
    return attachment.getForeach() != null && !attachment.getForeach().isBlank();
  }

  /**
   * The attachments as TripleA reads them (GameParser): one written for each element of a
   * variable, foreach="$Planes$", stands for one per element, "@Planes@" in its name, in what it
   * attaches to and in its options replaced by the element; two variables joined by "^" give one
   * per pair. "$Planes$" in an option's value is the variable's elements joined by colons. Those
   * made keep their foreach, the mark that they are changed at the one written, in Attachments.
   */
  static List<Attachment> expanded(final Game game) {
    synchronized (EXPANDED) {
      return EXPANDED.computeIfAbsent(game, AttachmentsXml::expand);
    }
  }

  // by the game, which every change makes anew; kept while it is: a map like 1941 Global Command
  // Decision has hundreds, asked for at every support's sentence and every nation's limits
  private static final Map<Game, List<Attachment>> EXPANDED = new WeakHashMap<>();

  private static List<Attachment> expand(final Game game) {
    final Map<String, List<String>> variables = variables(game);
    final List<Attachment> all = new ArrayList<>();
    for (final Attachment attachment : attachments(game)) {
      if (isForEach(attachment)) {
        all.addAll(madeBy(attachment, variables));
      } else {
        all.add(attachment);
      }
    }
    return all;
  }

  /**
   * The attachments the one written for each element makes, with the game's {@link #variables}:
   * "$A$^$B$" one per pair, "$A$:$B$" the two side by side, element by element. None for a loop
   * TripleA refuses: a variable the game does not have, or two side by side of unlike length.
   */
  static List<Attachment> madeBy(
      final Attachment loop, final Map<String, List<String>> variables) {
    List<Map<String, String>> each = List.of(Map.of());
    for (final String part : loop.getForeach().split("\\^")) {
      final List<Map<String, String>> rounds = sideBySide(part, variables);
      final List<Map<String, String>> more = new ArrayList<>();
      for (final Map<String, String> sofar : each) {
        for (final Map<String, String> round : rounds) {
          final Map<String, String> with = new LinkedHashMap<>(sofar);
          with.putAll(round);
          more.add(with);
        }
      }
      each = more;
    }
    return each.stream().map(elements -> made(loop, elements, variables)).toList();
  }

  // "$A$:$B$": a round per index, each variable's element of that index
  private static List<Map<String, String>> sideBySide(
      final String part, final Map<String, List<String>> variables) {
    final List<String> names =
        Stream.of(part.split(":"))
            .map(name -> name.strip().replaceAll("^\\$|\\$$", ""))
            .toList();
    final int length = variables.getOrDefault(names.getFirst(), List.of()).size();
    if (names.stream().anyMatch(name -> variables.getOrDefault(name, List.of()).size() != length)) {
      return List.of();
    }
    final List<Map<String, String>> rounds = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      final Map<String, String> round = new LinkedHashMap<>();
      for (final String name : names) {
        round.put(name, variables.get(name).get(i));
      }
      rounds.add(round);
    }
    return rounds;
  }

  // the attachment for these elements of its variables, as GameParser replaces them
  private static Attachment made(
      final Attachment attachment,
      final Map<String, String> elements,
      final Map<String, List<String>> variables) {
    final UnaryOperator<String> each =
        text -> {
          // most texts name no variable, and a big map's loops make 50,000 attachments
          if (text.indexOf('@') < 0) {
            return text;
          }
          String made = text;
          for (final Map.Entry<String, String> element : elements.entrySet()) {
            made = made.replace("@" + element.getKey() + "@", element.getValue());
          }
          return made;
        };
    final UnaryOperator<String> all =
        text -> {
          String made = each.apply(text);
          if (made.indexOf('$') < 0) {
            return made;
          }
          for (final Map.Entry<String, List<String>> variable : variables.entrySet()) {
            made = made.replace("$" + variable.getKey() + "$", String.join(":", variable.getValue()));
          }
          return made;
        };
    return attachment.toBuilder()
        .name(attachment.getName() == null ? null : each.apply(attachment.getName()))
        .attachTo(attachment.getAttachTo() == null ? null : each.apply(attachment.getAttachTo()))
        .options(
            options(attachment).stream()
                .map(
                    o ->
                        Option.builder()
                            .name(o.getName() == null ? null : each.apply(o.getName()))
                            .value(o.getValue() == null ? null : all.apply(o.getValue()))
                            .count(o.getCount() == null ? null : each.apply(o.getCount()))
                            .build())
                .toList())
        .build();
  }

  /** Each variable's elements; an element naming another variable, "$Land$", is its elements. */
  static Map<String, List<String>> variables(final Game game) {
    final Map<String, List<String>> resolved = new LinkedHashMap<>();
    for (final String name : GameRulesXml.variableNames(game)) {
      final List<String> elements = new ArrayList<>();
      for (final String element : GameRulesXml.elements(game, name)) {
        final String text = element.strip();
        if (text.length() > 2 && text.startsWith("$") && text.endsWith("$")) {
          elements.addAll(resolved.getOrDefault(text.substring(1, text.length() - 1), List.of()));
        } else {
          elements.add(element);
        }
      }
      resolved.put(name, elements);
    }
    return resolved;
  }

  /** The attachment's kind, eg "TriggerAttachment", from its Java class. */
  static String kind(final Attachment attachment) {
    final String javaClass = Objects.toString(attachment.getJavaClass(), "");
    return javaClass.substring(javaClass.lastIndexOf('.') + 1);
  }

  /**
   * The name a new attachment of the kind takes: the one TripleA reads it by, for a kind found by
   * the start of its name with the first number no attachment of the game has, eg
   * triggerAttachment3.
   */
  static String newName(final Game game, final String kind) {
    final String name = NAMES.getOrDefault(kind, "attachment");
    if (!NUMBERED.contains(kind)) {
      return name;
    }
    final Set<String> taken =
        attachments(game).stream().map(Attachment::getName).collect(Collectors.toSet());
    return IntStream.iterate(1, i -> i + 1)
        .mapToObj(i -> name + i)
        .filter(n -> !taken.contains(n))
        .findFirst()
        .orElseThrow();
  }

  /**
   * Where a new attachment of the name goes: behind the last one of that name, at the end
   * without one. TripleA reads the attachments in their order, and one may ask for another when
   * it is read, eg a unit attachment's option for every territory's attachment.
   */
  static int placeForNew(final List<Attachment> attachments, final String name) {
    for (int i = attachments.size() - 1; i >= 0; i--) {
      if (isNamed(attachments.get(i), name)) {
        return i + 1;
      }
    }
    return attachments.size();
  }

  /** Whether TripleA reads the attachment by this name; {@link #readName}. */
  static boolean isNamed(final Attachment attachment, final String name) {
    // the usual case first: this runs for every attachment of a big map
    return name.equals(attachment.getName())
        || (attachment.getName() != null
            && readName(name).equals(readName(attachment.getName())));
  }

  /**
   * An attachment's name as TripleA's GameParser reads it: old maps write "territoryAttatchment".
   * The map's spelling stays in the file.
   */
  static String readName(final String name) {
    return name.contains("ttatchment") ? name.replace("ttatchment", "ttachment") : name;
  }

  /** Whether TripleA reads the option by this name; {@link #optionName}. */
  static boolean isOption(final Option option, final String name) {
    return option.getName() != null && optionName(name).equals(optionName(option.getName()));
  }

  /**
   * An option's name as TripleA's GameParser reads it: the first letter in lower case, eg
   * "ConvoyRoute", and the old names its LegacyPropertyMapper knows as today's; the rest of the
   * name counts as written. The map's spelling stays in the file.
   */
  static String optionName(final String name) {
    final String read =
        name.isEmpty() || !Character.isUpperCase(name.charAt(0))
            ? name
            : name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    return OLD_OPTION_NAMES.getOrDefault(read.toLowerCase(Locale.ROOT), read);
  }

  private static final Map<String, String> OLD_OPTION_NAMES =
      Map.of(
          "isparatroop", "isAirTransportable",
          "isinfantry", "isLandTransportable",
          "ismechanized", "isLandTransportable",
          "occupiedterrof", "originalOwner",
          "isimpassible", "isImpassable",
          "turns", "rounds");

  /** Whether the game has an attachment of the name on the thing. */
  static boolean has(final Game game, final String name, final String attachTo) {
    return attachments(game).stream()
        .anyMatch(a -> isNamed(a, name) && attachTo.equals(a.getAttachTo()));
  }

  /** The game with the attachment at this place replaced. */
  static Game withAttachment(final Game game, final int index, final Attachment changed) {
    final List<Attachment> attachments = new ArrayList<>(attachments(game));
    attachments.set(index, changed);
    return withAttachments(game, attachments);
  }

  /** The game without the attachment at this place. */
  static Game withoutAttachment(final Game game, final int index) {
    final List<Attachment> attachments = new ArrayList<>(attachments(game));
    attachments.remove(index);
    return withAttachments(game, attachments);
  }

  /**
   * The game with a new attachment of the kind after the others, so it may name the ones before
   * it, as the game reads them in order.
   */
  static Game withNewAttachment(
      final Game game, final String kind, final String name, final String attachTo) {
    final List<Attachment> attachments = new ArrayList<>(attachments(game));
    attachments.add(newAttachment(kind, name, attachTo));
    return withAttachments(game, attachments);
  }

  /** A new attachment of the kind, without options, not in a game yet. */
  static Attachment newAttachment(final String kind, final String name, final String attachTo) {
    return Attachment.builder()
        .name(name)
        .attachTo(attachTo)
        .javaClass(PACKAGE + kind)
        .type(KINDS.getOrDefault(kind, "player"))
        .options(List.of())
        .build();
  }

  /** The game with these attachments, in this order. */
  static Game withAttachments(final Game game, final List<Attachment> attachments) {
    final AttachmentList list =
        Optional.ofNullable(game.getAttachmentList())
            .orElseGet(() -> AttachmentList.builder().build());
    return game.toBuilder()
        .attachmentList(list.toBuilder().attachments(List.copyOf(attachments)).build())
        .build();
  }

  /** The attachment with the option at this row replaced. */
  static Attachment withOption(final Attachment attachment, final int row, final Option option) {
    // the game refuses an option without a name
    if (isBlank(option.getName())) {
      return attachment;
    }
    final List<Option> options = new ArrayList<>(options(attachment));
    options.set(row, option);
    return attachment.toBuilder().options(List.copyOf(options)).build();
  }

  /** The attachment with the option added after the others; one without a name is not added. */
  static Attachment withNewOption(final Attachment attachment, final Option option) {
    if (isBlank(option.getName())) {
      return attachment;
    }
    return attachment.toBuilder()
        .options(Stream.concat(options(attachment).stream(), Stream.of(option)).toList())
        .build();
  }

  private static boolean isBlank(final String name) {
    return name == null || name.isBlank();
  }

  static Attachment withoutOption(final Attachment attachment, final int row) {
    final List<Option> options = new ArrayList<>(options(attachment));
    options.remove(row);
    return attachment.toBuilder().options(List.copyOf(options)).build();
  }

  /** The names of what an attachment of the type, eg "player", may be attached to in the game. */
  static List<String> targets(final Game game, final String type) {
    return switch (type) {
      case "player" -> MapXml.nations(game);
      case "territory" -> MapXml.territories(game);
      case "unitType" -> MapXml.unitTypes(game);
      default ->
          attachments(game).stream()
              .filter(a -> type.equals(a.getType()))
              .map(Attachment::getAttachTo)
              .filter(Objects::nonNull)
              .distinct()
              .sorted()
              .toList();
    };
  }

  /**
   * The option names an attachment of this kind takes: those TripleA's attachment class knows,
   * and those the game's attachments of the kind use, sorted.
   */
  static List<String> knownOptions(final Game game, final String kind) {
    final TreeSet<String> names =
        new TreeSet<>(KNOWN_OPTIONS.computeIfAbsent(kind, AttachmentsXml::optionsOfClass));
    if ("UnitAttachment".equals(kind)) {
      // names whose setter is spelt otherwise, eg attackAA by setAttackAa
      names.addAll(UnitOptions.NAMES);
    }
    attachments(game).stream()
        .filter(a -> kind.equals(kind(a)))
        .flatMap(a -> options(a).stream())
        .map(Option::getName)
        .filter(Objects::nonNull)
        .forEach(names::add);
    return List.copyOf(names);
  }

  /**
   * The options TripleA's class of the kind takes: its setters' names that the class also answers
   * as a property. Read by reflection, as TripleA lists them nowhere else; empty for a kind it does
   * not know.
   */
  private static List<String> optionsOfClass(final String kind) {
    final IAttachment probe;
    try {
      final Optional<IAttachment> made =
          new XmlGameElementMapper().newAttachment(kind, "probe", null, null);
      if (made.isEmpty()) {
        return List.of();
      }
      probe = made.get();
    } catch (final RuntimeException | LinkageError e) {
      return List.of();
    }
    final TreeSet<String> names = new TreeSet<>();
    for (Class<?> c = probe.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      for (final Method method : c.getDeclaredMethods()) {
        final String name = method.getName();
        if (name.length() > 3
            && name.startsWith("set")
            && Character.isUpperCase(name.charAt(3))
            && method.getParameterCount() == 1
            && !Modifier.isStatic(method.getModifiers())) {
          names.add(name.substring(3, 4).toLowerCase(Locale.ROOT) + name.substring(4));
        }
      }
    }
    return names.stream().filter(name -> isProperty(probe, name)).toList();
  }

  private static boolean isProperty(final IAttachment probe, final String name) {
    if (!(probe instanceof DynamicallyModifiable modifiable)) {
      return true;
    }
    try {
      return modifiable.getProperty(name).isPresent();
    } catch (final RuntimeException | LinkageError e) {
      // an older TripleA without this lookup: the setter's name is the best guess
      return true;
    }
  }
}
