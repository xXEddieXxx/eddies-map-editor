package eddies.mapeditor;

import games.strategy.engine.data.DynamicallyModifiable;
import games.strategy.engine.data.IAttachment;
import games.strategy.engine.data.gameparser.XmlGameElementMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
    return Objects.equals(attachment.getName(), NAMES.get(kind(attachment)));
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

  static List<Attachment> attachments(final Game game) {
    return Optional.ofNullable(game.getAttachmentList())
        .map(AttachmentList::getAttachments)
        .map(List::copyOf)
        .orElse(List.of());
  }

  static List<Option> options(final Attachment attachment) {
    return Optional.ofNullable(attachment.getOptions()).map(List::copyOf).orElse(List.of());
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

  /** Whether the game has an attachment of the name on the thing. */
  static boolean has(final Game game, final String name, final String attachTo) {
    return attachments(game).stream()
        .anyMatch(a -> name.equals(a.getName()) && attachTo.equals(a.getAttachTo()));
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
