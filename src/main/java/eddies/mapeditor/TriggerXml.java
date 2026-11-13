package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.map.data.elements.PropertyList.Property;

/**
 * Triggers and their conditions as the game XML has them, read and written in plain parts for the
 * trigger assistant. TripleA joins an option's count and value as "count:value", so the editor works
 * on that joined text and writes it back as map makers do, eg placement count="2"
 * value="Germany:infantry". An option that did not change keeps its form; options the editor does
 * not manage are kept in their order.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class TriggerXml {
  static final String TRIGGER = "TriggerAttachment";
  static final String CONDITION = "RulesAttachment";
  // how much of a message's text says which message it is
  private static final int MESSAGE_LENGTH = 40;
  static final String USE_TRIGGERS = Constants.USE_TRIGGERS;
  static final String TRIGGER_PREFIX = Constants.TRIGGER_ATTACHMENT_PREFIX;
  static final String CONDITION_PREFIX = Constants.RULES_CONDITION_PREFIX;
  private static final List<String> CONDITION_PREFIXES =
      List.of(
          Constants.RULES_CONDITION_PREFIX,
          Constants.RULES_ATTACHMENT_NAME,
          Constants.RULES_OBJECTIVE_PREFIX);
  // the old name of "conditions", which many maps still use
  static final String OLD_CONDITIONS = "trigger";
  private static final Pattern CONDITION_TYPE = Pattern.compile("AND|OR|\\d+|(\\d+)-(\\d+)");
  private static final Pattern INTEGER = Pattern.compile("-?\\d+");
  private static final String TERRITORY_WORDS =
      "original originalNoWater controlled controlledNoWater all map enemy";

  /** What a part of an option's value names, and the names the game has for it. */
  enum Part {
    TERRITORY(MapXml::territories),
    NATION(MapXml::nations),
    UNIT(MapXml::unitTypes),
    STEP(g -> RulesXml.steps(g).stream().map(Step::getName).toList()),
    RELATION(RulesXml::relationshipTypes),
    TECH(g -> Stream.concat(TechXml.techNames(g).stream(), TechXml.BUILT_IN.stream()).toList()),
    CATEGORY(
        g ->
            MapXml.nations(g).stream()
                .flatMap(n -> TechXml.categories(g, n).stream())
                .distinct()
                .toList()),
    FRONTIER(ProductionXml::frontiers),
    RULE(g -> ProductionXml.rules(g).stream().map(ProductionRule::getName).toList()),
    RESOURCE(ProductionXml::resources),
    SUPPORT(g -> namesOf(g, "UnitSupportAttachment")),
    TRIGGER(g -> namesOf(g, TriggerXml.TRIGGER)),
    TERRITORY_EFFECT(g -> AttachmentsXml.targets(g, "territoryEffect")),
    /** An option of the attachment kind the piece's word names, eg "attack" of UnitAttachment. */
    PROPERTY(g -> List.of()),
    /** The key of a message in notifications.properties. */
    TEXT_KEY(g -> List.of()),
    NUMBER(g -> List.of()),
    BOOL(g -> List.of("true", "false")),
    WORD(g -> List.of()),
    TEXT(g -> List.of());

    private final Function<Game, List<String>> names;

    Part(final Function<Game, List<String>> names) {
      this.names = names;
    }

    /** The names the game has for the part, for a chooser; none for free text or a number. */
    List<String> names(final Game game) {
      return names.apply(game);
    }
  }

  /** One, a list of, or maybe one of the part; a list takes all colons it is given. */
  enum Shape {
    ONE,
    MANY,
    OPTIONAL
  }

  /**
   * A piece of an option's value.
   *
   * @param words what may stand in place of a name, eg "all" for every territory; for {@link
   *     Part#WORD} the choices, for {@link Part#PROPERTY} the attachment kind
   */
  record Piece(Part part, String label, Shape shape, List<String> words) {}

  /** Where an option belongs: the trigger itself, what it does, or a check of a condition. */
  enum Group {
    CORE,
    EFFECT,
    CHECK
  }

  /**
   * An option TripleA's triggers or conditions take.
   *
   * @param words what it means in plain words: {n} is the n-th piece, [..] is left out when a piece
   *     in it is empty
   * @param inCount how many pieces from the start map makers write as the option's count
   * @param moment when the effect happens without a "when", in the trigger nation's turn
   */
  record Spec(
      String option,
      Group group,
      String label,
      String words,
      int inCount,
      String moment,
      List<Piece> pieces) {}

  private static final String PURCHASE = "at the start of the purchase";
  private static final String TECH_ACTIVATION = "when technology is activated";
  private static final String TECH_STEP = "at the technology step";
  private static final String POLITICS = "at the politics step";
  private static final String COMBAT_MOVE = "at the start of the combat move";
  private static final String END_TURN = "at the end of the turn";
  private static final String END_ROUND = "at the end of the round";
  private static final String GIVE_RESOURCE_LABEL = "Give PUs or a resource";
  private static final String HOW_MANY = "How many";
  private static final String EACH = "each";
  private static final Set<String> NONE_IF_EMPTY = Set.of("atWarPlayers", "techs");

  static final List<Spec> SPECS =
      List.of(
          // the trigger itself
          core("conditions", "", many(Part.TEXT, "Conditions")),
          core(OLD_CONDITIONS, "", many(Part.TEXT, "Conditions")),
          core("conditionType", "", one(Part.TEXT, "Combine")),
          core("invert", "", one(Part.BOOL, "Not")),
          core("uses", "", one(Part.NUMBER, "Uses")),
          core("chance", "", one(Part.NUMBER, "Chance"), one(Part.NUMBER, "Dice sides")),
          core("players", "", many(Part.NATION, "Nations")),
          core("when", "", one(Part.WORD, "Before or after", "before", "after"),
              one(Part.STEP, "Step")),
          // what a trigger does
          effect("placement", "Place units", "place [{0} × ]{2} in {1}", 1, COMBAT_MOVE,
              optional(Part.NUMBER, "How many"), one(Part.TERRITORY, "Territory"),
              many(Part.UNIT, "Units")),
          effect("removeUnits", "Remove units", "remove [{0} × ]{2} from {1}", 1, COMBAT_MOVE,
              optional(Part.NUMBER, "How many"), one(Part.TERRITORY, "Territory", "all"),
              many(Part.UNIT, "Units", "all")),
          effect("purchase", "Give units to place", "give [{0} × ]{1} to place", 1, PURCHASE,
              optional(Part.NUMBER, "How many"), many(Part.UNIT, "Units")),
          effect("resource", GIVE_RESOURCE_LABEL, "give {0}", 0, END_TURN,
              one(Part.RESOURCE, "Resource")),
          effect("resourceCount", "Amount of a resource (alone)", "amount {0}", 0, "",
              one(Part.NUMBER, "Amount")),
          effect("changeOwnership", "Change a territory's owner",
              "give {0} owned by {1} to {2} (captured: {3})", 0, COMBAT_MOVE,
              one(Part.TERRITORY, "Territory", "all"), one(Part.NATION, "Old owner", "any"),
              one(Part.NATION, "New owner"), one(Part.BOOL, "As if captured")),
          effect("relationshipChange", "Change a relationship",
              "{0} and {1} become {3}[ (if {2} now)]",
              0, POLITICS, one(Part.NATION, "Nation"), one(Part.NATION, "Other nation"),
              one(Part.RELATION, "Was", "any", "anyAllied", "anyNeutral", "anyWar"),
              one(Part.RELATION, "Becomes")),
          effect("productionRule", "Change a purchase list", "in {0}: {1}", 0, PURCHASE,
              one(Part.FRONTIER, "Purchase list"), one(Part.RULE, "Rule (- to take away)")),
          effect("frontier", "Switch purchase list", "buy from {0}", 0, PURCHASE,
              one(Part.FRONTIER, "Purchase list")),
          effect("tech", "Give technology", "give technology {0}", 0, TECH_ACTIVATION,
              many(Part.TECH, "Technologies")),
          effect("availableTech", "Change researchable technology", "research in {0}: {1}", 0,
              TECH_STEP, one(Part.CATEGORY, "Category"),
              many(Part.TECH, "Technologies (- to take away)")),
          effect("support", "Change unit support", "support {0}", 0, TECH_ACTIVATION,
              many(Part.SUPPORT, "Supports (- to take away)")),
          effect("victory", "Win the game", "win with message {0}", 0, END_ROUND,
              one(Part.TEXT_KEY, "Message")),
          effect("notification", "Show a message", "show message {0}", 0, COMBAT_MOVE,
              one(Part.TEXT_KEY, "Message")),
          effect("activateTrigger", "Fire another trigger", "fire {0} {1} times", 0, END_ROUND,
              one(Part.TRIGGER, "Trigger"), one(Part.NUMBER, "Times"),
              one(Part.BOOL, "Counts its uses"), one(Part.BOOL, "Only if it has uses"),
              one(Part.BOOL, "Only if its conditions hold"), one(Part.BOOL, "Rolls its chance")),
          effect("unitType", "For unit types", "for {0}", 0, "", many(Part.UNIT, "Unit types")),
          effect("unitAttachmentName", "In unit attachment", "in {1} {0}", 1, "",
              one(Part.TEXT, "Attachment name"),
              one(Part.WORD, "Kind", "UnitAttachment", "UnitSupportAttachment")),
          effect("unitProperty", "Set a unit property", "set {1} to {0}", 1, TECH_ACTIVATION,
              many(Part.TEXT, "New value (-reset- first to clear)"),
              one(Part.PROPERTY, "Property", "UnitAttachment")),
          effect("territories", "For territories", "for {0}", 0, "",
              many(Part.TERRITORY, "Territories")),
          effect("territoryAttachmentName", "In territory attachment", "in {1} {0}", 1, "",
              one(Part.TEXT, "Attachment name"),
              one(Part.WORD, "Kind", "TerritoryAttachment", "CanalAttachment")),
          effect("territoryProperty", "Set a territory property", "set {1} to {0}", 1,
              COMBAT_MOVE, many(Part.TEXT, "New value (-reset- first to clear)"),
              one(Part.PROPERTY, "Property", "TerritoryAttachment")),
          effect("playerAttachmentName", "In nation attachment", "in {1} {0}", 1, "",
              one(Part.TEXT, "Attachment name"),
              one(Part.WORD, "Kind", "PlayerAttachment", "RulesAttachment", "TriggerAttachment",
                  "TechAttachment", "PoliticalActionAttachment", "UserActionAttachment")),
          effect("playerProperty", "Set a nation property", "set {1} to {0}", 1, COMBAT_MOVE,
              many(Part.TEXT, "New value (-reset- first to clear)"),
              one(Part.PROPERTY, "Property", "PlayerAttachment")),
          effect("relationshipTypes", "For relationship types", "for {0}", 0, "",
              many(Part.RELATION, "Relationship types")),
          effect("relationshipTypeProperty", "Set a relationship property", "set {1} to {0}", 1,
              COMBAT_MOVE, many(Part.TEXT, "New value"),
              one(Part.PROPERTY, "Property", "RelationshipTypeAttachment")),
          effect("territoryEffects", "For territory effects", "for {0}", 0, "",
              many(Part.TERRITORY_EFFECT, "Territory effects")),
          effect("territoryEffectProperty", "Set a territory effect property", "set {1} to {0}",
              1, COMBAT_MOVE, many(Part.TEXT, "New value"),
              one(Part.PROPERTY, "Property", "TerritoryEffectAttachment")),
          // what a condition checks
          territories("directOwnershipTerritories", "Owns territories", "own"),
          territories("alliedOwnershipTerritories", "Allies own territories", "own with allies"),
          territories("directExclusionTerritories", "Has no units in", "have no units in"),
          territories("alliedExclusionTerritories", "Allies have no units in",
              "have no own or allied units in"),
          territories("enemyExclusionTerritories", "Enemies have no units in",
              "see no enemy units in"),
          territories("enemySurfaceExclusionTerritories", "Enemies have no ships in",
              "see no enemy surface ships in"),
          territories("directPresenceTerritories", "Has units in", "have units in"),
          territories("alliedPresenceTerritories", "Allies have units in",
              "have own or allied units in"),
          territories("enemyPresenceTerritories", "Enemies have units in",
              "see enemy units in"),
          check("unitPresence", "Counting only units", "counting [{0} × ]{1}", 1,
              one(Part.NUMBER, "How many"), many(Part.UNIT, "Units", "any")),
          check("atWarPlayers", "At war with", "are at war with {0} of {1}", 1,
              optional(Part.NUMBER, HOW_MANY), many(Part.NATION, "Nations")),
          check("relationship", "A relationship is", "{0} and {1} {2}[ for {3} rounds or more]", 0,
              one(Part.NATION, "Nation"), one(Part.NATION, "Other nation"),
              one(Part.RELATION, "Relationship", "anyAllied", "anyNeutral", "anyWar"),
              optional(Part.NUMBER, "For rounds")),
          check("techs", "Has technology", "have {0} of {1}", 1,
              optional(Part.NUMBER, HOW_MANY), many(Part.TECH, "Technologies")),
          check("rounds", "In rounds", "in round {0}", 0, many(Part.TEXT, "Rounds")),
          check("switch", "Switched on", "are switched {0}", 0, one(Part.BOOL, "On")),
          check("gameProperty", "A game option is on", "play with {0}", 0,
              one(Part.TEXT, "Game option")),
          check("destroyedTUV", "Destroyed units worth", "destroyed units worth {0} ({1})", 1,
              one(Part.NUMBER, "Worth"), one(Part.WORD, "When", "currentRound", "allRounds")),
          check("battle", "A battle happened", "see {0} attack {1} in {4} ({2}, round {3})", 0,
              one(Part.NATION, "Attacker", "any"), one(Part.NATION, "Defender", "any"),
              one(Part.WORD, "Result", "any", "won", "lost", "draw", "noBattle"),
              one(Part.TEXT, "Rounds, eg 2-4"), many(Part.TERRITORY, "Territories")),
          check("isAI", "Played by the AI", "are played by the AI: {0}", 0,
              one(Part.BOOL, "AI")));

  private static final Map<String, Spec> BY_OPTION =
      SPECS.stream().collect(Collectors.toMap(Spec::option, s -> s));

  /**
   * "resource" and "resourceCount" as one effect, as the game gives nothing without an amount; not
   * in {@link #SPECS}, the file keeps the two options.
   */
  static final Spec GIVE_RESOURCE =
      new Spec("resource", Group.EFFECT, GIVE_RESOURCE_LABEL, "give [{0} ]{1}", 0, END_TURN,
          List.of(optional(Part.NUMBER, "Amount"), one(Part.RESOURCE, "Resource")));

  /** The options the assistant manages on a trigger; others it keeps as they are. */
  private static final Set<String> TRIGGER_CORE =
      Set.of("conditions", OLD_CONDITIONS, "conditionType", "invert", "uses", "chance", "players",
          "when");

  /** The options the assistant manages on a condition besides its checks. */
  private static final Set<String> CONDITION_CORE = Set.of("players", "invert");

  private static Spec core(final String option, final String words, final Piece... pieces) {
    return new Spec(option, Group.CORE, option, words, 0, "", List.of(pieces));
  }

  private static Spec effect(
      final String option,
      final String label,
      final String words,
      final int inCount,
      final String moment,
      final Piece... pieces) {
    return new Spec(option, Group.EFFECT, label, words, inCount, moment, List.of(pieces));
  }

  private static Spec check(
      final String option,
      final String label,
      final String words,
      final int inCount,
      final Piece... pieces) {
    return new Spec(option, Group.CHECK, label, words, inCount, "", List.of(pieces));
  }

  /** A check of territories: a count, or "each", then a list or a word like "controlled". */
  private static Spec territories(final String option, final String label, final String verb) {
    return check(option, label, verb + " [{0} of ]{1}", 1,
        optional(Part.NUMBER, HOW_MANY, EACH),
        many(Part.TERRITORY, "Territories", TERRITORY_WORDS.split(" ")));
  }

  private static Piece one(final Part part, final String label, final String... words) {
    return new Piece(part, label, Shape.ONE, List.of(words));
  }

  private static Piece many(final Part part, final String label, final String... words) {
    return new Piece(part, label, Shape.MANY, List.of(words));
  }

  private static Piece optional(final Part part, final String label, final String... words) {
    return new Piece(part, label, Shape.OPTIONAL, List.of(words));
  }

  /** The spec of the option, by its name as TripleA reads it, eg "RemoveUnits" or "turns". */
  static Optional<Spec> spec(final String option) {
    return Optional.ofNullable(option).map(o -> BY_OPTION.get(AttachmentsXml.optionName(o)));
  }

  static List<Spec> specs(final Group group) {
    return SPECS.stream().filter(s -> s.group() == group).toList();
  }

  /** Whether the assistant shows the option of a trigger with fields; else it keeps it as is. */
  static boolean isManagedOnTrigger(final String option) {
    return TRIGGER_CORE.contains(AttachmentsXml.optionName(option))
        || spec(option).filter(s -> s.group() == Group.EFFECT).isPresent();
  }

  static boolean isManagedOnCondition(final String option) {
    return CONDITION_CORE.contains(AttachmentsXml.optionName(option))
        || spec(option).filter(s -> s.group() == Group.CHECK).isPresent();
  }

  /** The options of {@link #GIVE_RESOURCE}: the amount only when there is one. */
  static List<Option> resourceOptions(final String amount, final String resource) {
    return amount.isEmpty()
        ? List.of(option("resource", resource))
        : List.of(option("resource", resource), option("resourceCount", amount));
  }

  /** Whether the spec's count is a how-many in words, eg "none" or "at least 2". */
  static boolean countsInWords(final Spec spec) {
    return NONE_IF_EMPTY.contains(spec.option()) || isTerritoryCheck(spec);
  }

  private static boolean isTerritoryCheck(final Spec spec) {
    return spec.group() == Group.CHECK && spec.pieces().getFirst().words().contains(EACH);
  }

  /**
   * The count in words: atWarPlayers and techs without a number, or 0, need none of them to hold
   * (RulesAttachment.checkAtWar, checkTechs); territory checks without one need all.
   */
  static String countWord(final Spec spec, final String count) {
    if (NONE_IF_EMPTY.contains(spec.option()) && (count.isEmpty() || count.equals("0"))) {
      return "none";
    } else if (isTerritoryCheck(spec) && count.isEmpty()) {
      return "all";
    }
    return INTEGER.matcher(count).matches() && countsInWords(spec) ? "at least " + count : count;
  }

  /** A relation word of TripleA in plain words, eg anyNeutral as "any neutral"; others as is. */
  static String plain(final String relation) {
    return switch (relation) {
      case "any" -> "anything";
      case "anyAllied" -> "any allied";
      case "anyNeutral" -> "any neutral";
      case "anyWar" -> "any war";
      default -> relation;
    };
  }

  /**
   * A relationship condition's kind in words, eg "are at war": an any word matches each type of
   * that kind, a type only itself (RulesAttachment.checkRelationships).
   */
  private static String relationPhrase(final String relation) {
    return switch (relation) {
      case "anyAllied" -> "are allied";
      case "anyNeutral" -> "are neutral";
      case "anyWar" -> "are at war";
      default -> "have the relationship " + relation;
    };
  }

  /** The name without triggerAttachment before it, eg "Germans_4_Iron_Ore". */
  static String shortName(final String name) {
    final String shortened =
        name.replaceFirst("^(?i)trigger(Attachment|Attatchment)_?", "").strip();
    return shortened.isEmpty() ? name : shortened;
  }

  /** The first relationship type of the war kind, else the first, eg for a declaration of war. */
  static String warType(final Game game) {
    final List<String> types = RulesXml.relationshipTypes(game);
    return types.stream()
        .filter(t -> MapXml.option(game, Constants.RELATIONSHIPTYPE_ATTACHMENT_NAME, t,
                "archeType")
            .filter(Constants.RELATIONSHIP_ARCHETYPE_WAR::equalsIgnoreCase)
            .isPresent())
        .findFirst()
        .or(() -> types.stream().findFirst())
        .orElse("");
  }

  // ---- count and value

  /** The option as TripleA reads it: its count, a colon and its value; just the value without. */
  static String joined(final Option option) {
    final String value = Objects.toString(option.getValue(), "");
    final String count = option.getCount();
    return count == null || count.isEmpty() ? value : count + ":" + value;
  }

  /**
   * The pieces of the joined text, one per piece of the spec: a list takes as many colons as the
   * pieces after it leave, a maybe-piece is empty when the text does not have it. Left-over text
   * stays in the last piece, so nothing is lost.
   */
  static List<String> split(final Spec spec, final String joined) {
    final List<String> tokens =
        joined.isEmpty() ? List.of() : List.of(joined.split(":", -1));
    final List<Piece> pieces = spec.pieces();
    final boolean[] present = new boolean[pieces.size()];
    final int required =
        (int) pieces.stream().filter(p -> p.shape() != Shape.OPTIONAL).count();
    int spare = tokens.size() - required;
    for (int i = 0; i < pieces.size(); i++) {
      final Piece piece = pieces.get(i);
      if (piece.shape() != Shape.OPTIONAL) {
        present[i] = true;
      } else if (spare > 0) {
        // a leading count is there only if it reads as one, eg "2" or "each"
        final String token = tokens.get(i == 0 ? 0 : tokens.size() - 1);
        present[i] = i > 0 || INTEGER.matcher(token).matches() || piece.words().contains(token);
        spare -= present[i] ? 1 : 0;
      }
    }
    final List<Integer> shown =
        IntStream.range(0, pieces.size()).filter(i -> present[i]).boxed().toList();
    final String[] result = new String[pieces.size()];
    Arrays.fill(result, "");
    final int many =
        IntStream.range(0, shown.size())
            .filter(k -> pieces.get(shown.get(k)).shape() == Shape.MANY)
            .findFirst()
            .orElse(-1);
    int front = 0;
    int back = tokens.size();
    final int before = many < 0 ? shown.size() : many;
    for (int k = 0; k < before && front < back; k++) {
      result[shown.get(k)] = tokens.get(front++);
    }
    // the pieces after a list take the last colons, so the list may be empty, eg a property's
    for (int k = shown.size() - 1; many >= 0 && k > many && back > front; k--) {
      result[shown.get(k)] = tokens.get(--back);
    }
    if (many >= 0) {
      result[shown.get(many)] = String.join(":", tokens.subList(front, back));
    } else if (front < back && !shown.isEmpty()) {
      // more parts than the option takes: kept, so the problem shows instead of vanishing
      result[shown.getLast()] += ":" + String.join(":", tokens.subList(front, back));
    }
    return List.of(result);
  }

  /**
   * The pieces joined as TripleA reads them; an empty maybe-piece is left out, as is an empty list
   * at the start, eg the new value of a property that is cleared.
   */
  static String join(final Spec spec, final List<String> pieces) {
    return join(spec, pieces, 0, pieces.size());
  }

  private static String join(
      final Spec spec, final List<String> pieces, final int from, final int to) {
    return IntStream.range(from, Math.min(to, spec.pieces().size()))
        .filter(i -> !(pieces.get(i).isEmpty()
            && (spec.pieces().get(i).shape() == Shape.OPTIONAL
                || (i == 0 && spec.pieces().get(i).shape() == Shape.MANY))))
        .mapToObj(pieces::get)
        .collect(Collectors.joining(":"));
  }

  /**
   * The option as map makers write it: the spec's first pieces as its count, the rest as its value,
   * eg placement count="2" value="Germany:infantry".
   */
  static Option communityForm(final String name, final String joined) {
    final Optional<Spec> spec = spec(name).filter(s -> s.inCount() > 0);
    if (spec.isEmpty()) {
      return option(name, joined);
    }
    final List<String> pieces = split(spec.get(), joined);
    final int inCount = spec.get().inCount();
    final String count = join(spec.get(), pieces, 0, inCount);
    return Option.builder()
        .name(name)
        .value(join(spec.get(), pieces, inCount, pieces.size()))
        .count(count.isEmpty() ? null : count)
        .build();
  }

  /** An option in the joined form the assistant works on. */
  static Option option(final String name, final String joined) {
    return Option.builder().name(name).value(joined).build();
  }

  /**
   * The attachment with these options, in the joined form: an option that is there with the same
   * text keeps its form and place, a changed one takes the place of the first of its name, new ones
   * come last. The same attachment when nothing changed.
   */
  static Attachment withOptions(final Attachment attachment, final List<Option> wanted) {
    final List<Option> before = AttachmentsXml.options(attachment);
    final boolean[] used = new boolean[wanted.size()];
    final Option[] kept = new Option[before.size()];
    for (int b = 0; b < before.size(); b++) {
      final Option original = before.get(b);
      for (int w = 0; w < wanted.size(); w++) {
        if (!used[w]
            && AttachmentsXml.isOption(original, wanted.get(w).getName())
            && joined(original).equals(joined(wanted.get(w)))) {
          used[w] = true;
          kept[b] = original;
          break;
        }
      }
    }
    final List<Option> options = new ArrayList<>();
    for (int b = 0; b < before.size(); b++) {
      if (kept[b] != null) {
        options.add(kept[b]);
        continue;
      }
      final String name = before.get(b).getName();
      for (int w = 0; w < wanted.size(); w++) {
        if (!used[w] && AttachmentsXml.isOption(before.get(b), wanted.get(w).getName())) {
          used[w] = true;
          options.add(communityForm(name, joined(wanted.get(w))));
          break;
        }
      }
    }
    for (int w = 0; w < wanted.size(); w++) {
      if (!used[w]) {
        options.add(communityForm(wanted.get(w).getName(), joined(wanted.get(w))));
      }
    }
    final boolean same =
        options.size() == before.size()
            && IntStream.range(0, before.size()).allMatch(i -> options.get(i) == before.get(i));
    return same ? attachment : attachment.toBuilder().options(List.copyOf(options)).build();
  }

  /** The values of the option in the attachment, in the joined form, eg each of its "when". */
  static List<String> values(final Attachment attachment, final String name) {
    return AttachmentsXml.options(attachment).stream()
        .filter(o -> AttachmentsXml.isOption(o, name))
        .map(TriggerXml::joined)
        .toList();
  }

  static Optional<String> value(final Attachment attachment, final String name) {
    return values(attachment, name).stream().findFirst();
  }

  // ---- triggers

  /**
   * A trigger and the conditions it names.
   *
   * @param index its place in the game's attachments, -1 for a new one
   * @param conditions those it names that the game has, and new ones it brings
   */
  record Trigger(int index, Attachment attachment, List<Attachment> conditions) {
    Trigger {
      conditions = List.copyOf(conditions);
    }

    String name() {
      return Objects.toString(attachment.getName(), "");
    }

    String nation() {
      return Objects.toString(attachment.getAttachTo(), "");
    }

    /** The names of its conditions, in its order. */
    List<String> conditionNames() {
      return conditionNamesOf(attachment);
    }
  }

  private static List<String> conditionNamesOf(final Attachment attachment) {
    return Stream.of("conditions", OLD_CONDITIONS)
        .flatMap(o -> values(attachment, o).stream())
        .flatMap(v -> Stream.of(v.split(":")))
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  /** The game's triggers; the conditions are looked up once, as big maps have thousands. */
  static List<Trigger> triggers(final Game game) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    final Map<String, Attachment> conditions = conditionsByName(game);
    return IntStream.range(0, attachments.size())
        .filter(i -> TRIGGER.equals(AttachmentsXml.kind(attachments.get(i))))
        .mapToObj(i -> trigger(attachments.get(i), i, conditions))
        .toList();
  }

  /** The trigger at this place in the game's attachments. */
  static Trigger trigger(final Game game, final int index) {
    return trigger(AttachmentsXml.attachments(game).get(index), index, conditionsByName(game));
  }

  /**
   * The trigger with the conditions it names that the game has; a name the game has not, eg one a
   * foreach makes, stays in its option only.
   */
  private static Trigger trigger(
      final Attachment attachment, final int index, final Map<String, Attachment> conditions) {
    return new Trigger(
        index,
        attachment,
        conditionNamesOf(attachment).stream()
            .map(conditions::get)
            .filter(Objects::nonNull)
            .distinct()
            .toList());
  }

  /** The game's conditions by name, the first of a name, as the game finds it. */
  static Map<String, Attachment> conditionsByName(final Game game) {
    final Map<String, Attachment> byName = new HashMap<>();
    conditions(game).forEach(c -> byName.putIfAbsent(c.getName(), c));
    return byName;
  }

  /** The condition of the name, on whichever nation, as the game finds it. */
  static Optional<Attachment> condition(final Game game, final String name) {
    return conditions(game).stream().filter(a -> name.equals(a.getName())).findFirst();
  }

  /** The game's conditions, which a trigger may name. */
  static List<Attachment> conditions(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> CONDITION.equals(AttachmentsXml.kind(a)))
        .toList();
  }

  /** A new trigger of the nation, without options yet. */
  static Trigger newTrigger(final String name, final String nation) {
    return new Trigger(-1, AttachmentsXml.newAttachment(TRIGGER, name, nation), List.of());
  }

  /**
   * A free name for a new trigger or condition: the start TripleA knows them by and the map maker's
   * short name, eg triggerAttachmentGermanOil, else with the first free number.
   *
   * @param alsoTaken names not in the game yet, eg of conditions made in the same assistant
   */
  static String newName(
      final Game game, final String kind, final String shortName, final Set<String> alsoTaken) {
    final String start = AttachmentsXml.NAMES.getOrDefault(kind, "attachment");
    final Set<String> taken = new HashSet<>(alsoTaken);
    AttachmentsXml.attachments(game).stream().map(Attachment::getName).forEach(taken::add);
    final String wanted = start + shortName.strip().replaceAll("[^\\w-]+", "_");
    if (!shortName.isBlank() && !taken.contains(wanted)) {
      return wanted;
    }
    final String numbered = shortName.isBlank() ? start : wanted;
    return IntStream.iterate(1, i -> i + 1)
        .mapToObj(i -> numbered + i)
        .filter(n -> !taken.contains(n))
        .findFirst()
        .orElseThrow();
  }

  /**
   * The game with the trigger and its conditions: a condition the game has is replaced where it is,
   * a new one goes right before the trigger, as the game finds what a trigger names only among the
   * attachments before it; a new trigger goes last. The same game when nothing changed.
   */
  static Game withTrigger(final Game game, final Trigger trigger) {
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
    int at = trigger.index() >= 0 ? trigger.index() : attachments.size();
    boolean changed = false;
    for (final Attachment condition : trigger.conditions()) {
      final int found = indexOf(attachments, condition);
      if (found >= 0) {
        if (attachments.get(found) != condition) {
          attachments.set(found, condition);
          changed = true;
        }
      } else {
        attachments.add(at++, condition);
        changed = true;
      }
    }
    if (trigger.index() < 0) {
      attachments.add(trigger.attachment());
      changed = true;
    } else if (attachments.get(at) != trigger.attachment()) {
      attachments.set(at, trigger.attachment());
      changed = true;
    }
    return changed ? AttachmentsXml.withAttachments(game, attachments) : game;
  }

  private static int indexOf(final List<Attachment> attachments, final Attachment condition) {
    return IntStream.range(0, attachments.size())
        .filter(i -> CONDITION.equals(AttachmentsXml.kind(attachments.get(i))))
        .filter(i -> Objects.equals(condition.getName(), attachments.get(i).getName()))
        .filter(i -> Objects.equals(condition.getAttachTo(), attachments.get(i).getAttachTo()))
        .findFirst()
        .orElse(-1);
  }

  /** Whether the game option "Use Triggers" is on, without which no trigger fires. */
  static boolean usesTriggers(final Game game) {
    return MapXml.properties(game).stream()
        .filter(p -> USE_TRIGGERS.equals(p.getName()))
        .map(Property::getValue)
        .anyMatch("true"::equalsIgnoreCase);
  }

  static Game withTriggersOn(final Game game) {
    return MapXml.withPropertyValue(game, USE_TRIGGERS, "true");
  }

  // ---- problems

  /** What keeps the trigger from loading or working, and what may be a slip. */
  record Problems(List<String> errors, List<String> warnings) {
    Problems {
      errors = List.copyOf(errors);
      warnings = List.copyOf(warnings);
    }
  }

  /** The trigger's problems in the game as it would be with it. */
  static Problems problems(final Game game, final MapTexts texts, final Trigger trigger) {
    final Game after = withTrigger(game, trigger);
    final List<Attachment> attachments = AttachmentsXml.attachments(after);
    final int at = indexOfSame(attachments, trigger.attachment());
    final Set<String> errors = new LinkedHashSet<>();
    final Set<String> warnings = new LinkedHashSet<>();
    final String name = trigger.name();
    // TripleA fires a trigger of any name; other attachments find triggers by the start
    if (trigger.index() < 0 && !hasStart(name, TRIGGER_PREFIX)) {
      warnings.add("A trigger's name usually starts with " + TRIGGER_PREFIX
          + ", so conditions and objectives can name it.");
    }
    final List<Integer> namesakes = IntStream.range(0, attachments.size())
        .filter(i -> TRIGGER.equals(AttachmentsXml.kind(attachments.get(i))))
        .filter(i -> name.equals(attachments.get(i).getName())
            && trigger.nation().equals(attachments.get(i).getAttachTo()))
        .boxed()
        .toList();
    // the game keeps one attachment per name on a nation: the last one read
    if (!namesakes.isEmpty() && namesakes.getLast() != at) {
      warnings.add("A later trigger " + name + " of " + trigger.nation()
          + " replaces this one: the game ignores this one.");
    } else if (namesakes.size() > 1) {
      warnings.add("This trigger replaces an earlier one of " + trigger.nation()
          + " with the same name.");
    }
    if (!MapXml.nations(after).contains(trigger.nation())) {
      errors.add("No nation is called " + trigger.nation() + ".");
    }
    if (!usesTriggers(after)) {
      errors.add("The game option " + USE_TRIGGERS + " is off: no trigger fires.");
    }
    final Map<String, Integer> places = new HashMap<>();
    for (int i = attachments.size() - 1; i >= 0; i--) {
      places.put(attachments.get(i).getName(), i);
    }
    for (final Option option : AttachmentsXml.options(trigger.attachment())) {
      final String optionName =
          AttachmentsXml.optionName(Objects.toString(option.getName(), ""));
      final String joined = joined(option);
      if (joined.contains("@")) {
        // a foreach variable, which the game fills in
        continue;
      }
      switch (optionName) {
        case "conditions", OLD_CONDITIONS -> {
          for (final String condition : joined.split(":")) {
            if (condition.isBlank()) {
              continue;
            }
            final Optional<Attachment> found = condition(after, condition);
            if (found.isEmpty()) {
              errors.add("The game has no condition " + condition + ".");
            } else if (attachments.indexOf(found.get()) > at) {
              errors.add("Condition " + condition + " comes after the trigger in the game file,"
                  + " so the game does not find it: it must come before (Files shows the XML).");
            }
          }
        }
        case "conditionType" -> {
          final Matcher matcher = CONDITION_TYPE.matcher(joined.toUpperCase(Locale.ROOT));
          if (!matcher.matches()
              || (matcher.group(1) != null
                  && Integer.parseInt(matcher.group(2)) <= Integer.parseInt(matcher.group(1)))) {
            errors.add("Combine conditions as AND, OR, a number or a range like 2-3, not "
                + joined + ".");
          }
        }
        case "chance" -> {
          final String[] parts = joined.split(":", -1);
          final boolean valid = parts.length == 2
              && INTEGER.matcher(parts[0]).matches() && INTEGER.matcher(parts[1]).matches()
              && Integer.parseInt(parts[0]) >= 0
              && Integer.parseInt(parts[0]) <= Integer.parseInt(parts[1])
              && Integer.parseInt(parts[1]) <= 120;
          if (!valid) {
            errors.add("A chance is x in y, with 0 ≤ x ≤ y ≤ 120, not " + joined + ".");
          }
        }
        case "activateTrigger" -> {
          final String target = joined.split(":", -1)[0];
          final Integer place = places.get(target);
          if (target.equals(name)) {
            errors.add("A trigger cannot fire itself.");
          } else if (place != null && place > at) {
            errors.add("Trigger " + target + " comes after this one in the game file, so the"
                + " game does not find it: it must come before (Files shows the XML).");
          }
        }
        default -> { }
      }
      spec(optionName)
          .filter(s -> !s.option().equals("conditions") && !s.option().equals(OLD_CONDITIONS))
          .flatMap(s -> pieceProblem(after, s, joined))
          .ifPresent(errors::add);
      if (spec(optionName).map(s -> s.pieces().getFirst().part() == Part.TEXT_KEY).orElse(false)
          && !joined.isBlank()
          && texts.value(MapTexts.Kind.NOTIFICATIONS, joined).filter(t -> !t.isBlank())
              .isEmpty()) {
        warnings.add("Message " + joined + " has no text yet.");
      }
    }
    if (AttachmentsXml.options(trigger.attachment()).stream()
        .noneMatch(o -> spec(o.getName()).filter(s -> s.group() == Group.EFFECT).isPresent())) {
      warnings.add("The trigger does nothing yet: add an effect.");
    }
    // the game gives a resource only with an amount other than 0 (TriggerAttachment)
    if (value(trigger.attachment(), "resource").isPresent()
        && value(trigger.attachment(), "resourceCount").filter(c -> !c.equals("0")).isEmpty()) {
      warnings.add(GIVE_RESOURCE_LABEL + ": no amount yet, so it gives nothing.");
    }
    final List<Attachment> before = AttachmentsXml.attachments(game);
    for (final Attachment condition : trigger.conditions()) {
      final String conditionName = Objects.toString(condition.getName(), "");
      // a map's own condition may have any name and a namesake: only new ones must be clean
      final boolean isNew = indexOf(before, condition) < 0;
      if (isNew && CONDITION_PREFIXES.stream().noneMatch(p -> hasStart(conditionName, p))) {
        warnings.add("Condition " + conditionName + ": a condition's name usually starts with "
            + CONDITION_PREFIX + ", so objectives can show it.");
      }
      if (isNew
          && conditions(after).stream().filter(a -> conditionName.equals(a.getName())).count()
              > 1) {
        errors.add("Another condition is called " + conditionName
            + ": the game would take either.");
      }
      if (!MapXml.nations(after).contains(Objects.toString(condition.getAttachTo(), ""))) {
        errors.add("Condition " + conditionName + " belongs to no nation.");
      }
      for (final Option option : AttachmentsXml.options(condition)) {
        final String joined = joined(option);
        if (!joined.contains("@")) {
          spec(Objects.toString(option.getName(), ""))
              .flatMap(s -> pieceProblem(after, s, joined))
              .map(p -> "Condition " + conditionName + ": " + p)
              .ifPresent(errors::add);
        }
      }
      if (AttachmentsXml.options(condition).stream()
          .noneMatch(o -> spec(o.getName()).filter(s -> s.group() == Group.CHECK).isPresent())) {
        warnings.add("The condition " + conditionName + " uses checks the assistant does not"
            + " show. It stays as it is; change it in Attachments.");
      }
    }
    return new Problems(List.copyOf(errors), List.copyOf(warnings));
  }

  /**
   * Why the game would not read a check of the condition, eg a territory the map does not have;
   * the first found. A foreach variable, which the game fills in, is none.
   */
  static Optional<String> checkProblem(final Game game, final Attachment condition) {
    return AttachmentsXml.options(condition).stream()
        .flatMap(o -> checkProblem(game, o).stream())
        .findFirst();
  }

  /** Why the game would not read the option if it is a check; a foreach variable is no problem. */
  static Optional<String> checkProblem(final Game game, final Option check) {
    return joined(check).contains("@")
        ? Optional.empty()
        : spec(check.getName())
            .filter(s -> s.group() == Group.CHECK)
            .flatMap(s -> pieceProblem(game, s, joined(check)));
  }

  /** Whether the name starts so, also with the common misspelling "Attatchment". */
  private static boolean hasStart(final String name, final String start) {
    return AttachmentsXml.readName(name).startsWith(start);
  }

  private static int indexOfSame(final List<Attachment> attachments, final Attachment wanted) {
    return IntStream.range(0, attachments.size())
        .filter(i -> attachments.get(i) == wanted)
        .findFirst()
        .orElse(attachments.size());
  }

  /** Why a piece of the option's text is wrong, eg a territory the map does not have. */
  private static Optional<String> pieceProblem(
      final Game game, final Spec spec, final String joined) {
    final List<String> pieces = split(spec, joined);
    for (int i = 0; i < pieces.size(); i++) {
      final Piece piece = spec.pieces().get(i);
      final String text = pieces.get(i).strip();
      if (text.isEmpty()) {
        if (piece.shape() != Shape.OPTIONAL) {
          return Optional.of(spec.label() + ": " + piece.label() + " is missing.");
        }
        continue;
      }
      final List<String> values =
          piece.shape() == Shape.MANY ? List.of(text.split(":")) : List.of(text);
      final List<String> names = piece.part().names(game);
      for (final String raw : values) {
        final String value =
            raw.startsWith("-") && List.of(Part.RULE, Part.TECH, Part.SUPPORT)
                .contains(piece.part()) ? raw.substring(1) : raw;
        // TripleA takes words like "any" in any case
        final boolean isWord = piece.words().stream().anyMatch(value::equalsIgnoreCase);
        final boolean valid = switch (piece.part()) {
          case NUMBER -> INTEGER.matcher(value).matches() || isWord;
          case BOOL -> names.contains(value.toLowerCase(Locale.ROOT));
          case WORD -> piece.words().isEmpty() || isWord;
          case PROPERTY, TEXT_KEY, TEXT -> true;
          default -> names.contains(value) || isWord;
        };
        if (!valid) {
          return Optional.of(spec.label() + ": " + piece.label() + " " + value
              + (piece.part() == Part.NUMBER ? " is no number." : " is not in the game."));
        }
      }
    }
    return Optional.empty();
  }

  // ---- plain words

  /** What the trigger does, in plain words, eg "Germans: if Germans own France, give 5 PUs ...". */
  static String sentence(final Game game, final Trigger trigger) {
    final Clauses clauses = clauses(game, trigger);
    final StringBuilder sentence = new StringBuilder(clauses.nation()).append(": ");
    if (!clauses.conditions().isEmpty()) {
      sentence.append(clauses.conditions()).append(", ");
    }
    sentence.append(clauses.effects().isEmpty() ? "nothing" : String.join(", ", clauses.effects()));
    value(trigger.attachment(), "players").ifPresent(p -> sentence.append(" for ")
        .append(p.replace(":", ", ")));
    if (!clauses.when().isEmpty()) {
      sentence.append(", ").append(clauses.when());
    }
    return sentence.append(". ").append(clauses.often()).toString();
  }

  /**
   * The parts of what a trigger does, in plain words.
   *
   * @param conditions "if ..." or "unless ...", empty without conditions
   * @param effects what happens, one phrase each
   * @param when its steps, else its effects' moments; may be empty
   * @param often in how many rounds, and its chance
   * @param thenIds what the effects show in plain words as the map's files name it, eg the keys
   *     of messages and the ids of purchase lists, for a tool tip
   * @param whenSteps its steps as the game's XML names them, for a tool tip
   */
  record Clauses(
      String nation,
      String conditions,
      List<String> effects,
      String when,
      String often,
      String thenIds,
      String whenSteps) {
    Clauses {
      effects = List.copyOf(effects);
    }

    /** Clauses that show no internal names in plain words, so have none for a tool tip. */
    Clauses(
        final String nation,
        final String conditions,
        final List<String> effects,
        final String when,
        final String often) {
      this(nation, conditions, effects, when, often, "", "");
    }
  }

  static Clauses clauses(final Game game, final Trigger trigger) {
    return clauses(game, trigger, names(game));
  }

  /**
   * What a trigger's internal names mean, looked up once for many triggers: a message's text by
   * its key in notifications.properties, a step in plain words by its name.
   */
  record Names(
      Map<String, String> messages,
      Map<String, String> steps,
      Map<String, String> lists,
      Map<String, String> rules) {}

  private static Names names(
      final Game game, final Map<String, String> messages, final Map<String, String> steps) {
    final Map<String, String> rules = new HashMap<>();
    ProductionXml.rules(game)
        .forEach(
            rule ->
                ProductionXml.result(rule)
                    .ifPresent(r -> rules.put(rule.getName(), r.getResourceOrUnit())));
    return new Names(messages, steps, ProductionXml.listTitles(game), rules);
  }

  /** The steps in plain words; messages stay their keys. */
  static Names names(final Game game) {
    return names(game, Map.of(), stepWords(game));
  }

  static Names names(final Game game, final MapTexts texts) {
    final Map<String, String> messages = new HashMap<>();
    texts.entries(MapTexts.Kind.NOTIFICATIONS).forEach(e -> messages.put(e.key(), e.value()));
    return names(game, messages, stepWords(game));
  }

  /** The steps by what happens in them only, eg "Roll for technology", whoever's they are. */
  static Names stepKinds(final Game game) {
    return names(game, Map.of(), stepWords(game, step -> ""));
  }

  /** Each step by its name as Turn order shows it, eg "Roll for technology · Chinese". */
  private static Map<String, String> stepWords(final Game game) {
    return stepWords(
        game, step -> Optional.ofNullable(step.getPlayer()).map(p -> " · " + p).orElse(""));
  }

  private static Map<String, String> stepWords(
      final Game game, final Function<Step, String> whose) {
    final Map<String, String> words = new HashMap<>();
    for (final Step step : RulesXml.steps(game)) {
      GameRulesXml.kindOf(game, step)
          .ifPresent(
              kind ->
                  words.put(
                      Objects.toString(step.getName(), ""), kind.what() + whose.apply(step)));
    }
    return words;
  }

  /** As {@link #clauses(Game, Trigger)}, a message shown by its text instead of its key. */
  static Clauses clauses(final Game game, final Trigger trigger, final Names names) {
    final Attachment attachment = trigger.attachment();
    final List<String> conditionNames = trigger.conditionNames();
    final List<String> conditions =
        conditionNames.stream()
            .map(n -> trigger.conditions().stream().filter(c -> n.equals(c.getName()))
                .findFirst()
                .map(TriggerXml::conditionSentence)
                .orElse(n))
            .toList();
    final String type = value(attachment, "conditionType").orElse("AND").toUpperCase(Locale.ROOT);
    // a number means exactly that many hold (AbstractConditionsAttachment.areConditionsMet)
    final String combined =
        switch (type) {
          case "AND" -> String.join(" and ", conditions);
          case "OR" -> String.join(" or ", conditions);
          case "0" -> "none of: " + String.join("; ", conditions);
          default -> (type.contains("-") ? "from " + type.replace("-", " to ") : "exactly " + type)
              + " of: " + String.join("; ", conditions);
        };
    final boolean inverted = value(attachment, "invert").filter("true"::equalsIgnoreCase)
        .isPresent();
    return new Clauses(
        trigger.nation(),
        conditions.isEmpty() ? "" : (inverted ? "unless " : "if ") + combined,
        phrases(attachment, Group.EFFECT, names),
        when(attachment, names),
        often(attachment),
        Stream.concat(
                values(attachment, "notification").stream()
                    .filter(key -> messageText(names, key).isPresent()),
                Stream.of("productionRule", "frontier")
                    .flatMap(option -> values(attachment, option).stream())
                    .filter(value -> !plainProduction(names, value).equals(value)))
            .collect(Collectors.joining(", ")),
        String.join(", ", values(attachment, "when")));
  }

  /**
   * "Japanese:nighthawk" for productionJapanese:buynighthawk: the purchase list as Production
   * calls it, each rule by what it sells; a "-" that takes a rule away stays.
   */
  private static String plainProduction(final Names names, final String joined) {
    final String[] pieces = joined.split(":", -1);
    pieces[0] = names.lists().getOrDefault(pieces[0], pieces[0]);
    for (int i = 1; i < pieces.length; i++) {
      final String rule = pieces[i].startsWith("-") ? pieces[i].substring(1) : pieces[i];
      pieces[i] =
          (pieces[i].startsWith("-") ? "-" : "") + names.rules().getOrDefault(rule, rule);
    }
    return String.join(":", pieces);
  }

  /** The start of the message's text without its HTML, eg for "show message “…”". */
  private static Optional<String> messageText(final Names names, final String key) {
    return Optional.ofNullable(names.messages().get(key))
        .map(text -> text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip())
        .filter(text -> !text.isEmpty())
        .map(
            text ->
                text.length() <= MESSAGE_LENGTH
                    ? text
                    : text.substring(0, MESSAGE_LENGTH).strip() + "…");
  }

  /**
   * When the trigger fires: its steps in plain words, eg "before Roll for technology · Chinese",
   * else the moments of its effects.
   */
  static String when(final Attachment attachment, final Names names) {
    final List<String> when = values(attachment, "when");
    return String.join(" and ", when.isEmpty()
        ? AttachmentsXml.options(attachment).stream()
            .map(o -> spec(o.getName()).map(Spec::moment).orElse(""))
            .filter(m -> !m.isEmpty())
            .distinct()
            .toList()
        : when.stream()
            .map(
                w -> {
                  final String[] parts = w.split(":", 2);
                  return parts.length < 2
                      ? w
                      : parts[0] + " " + names.steps().getOrDefault(parts[1], parts[1]);
                })
            .toList());
  }

  /**
   * In how many rounds the trigger fires, and its chance, eg "In 1 round. Chance 1 in 6.": uses
   * count rounds, one taken at the end of each round it fired in (AbstractTriggerAttachment).
   */
  static String often(final Attachment attachment) {
    final int uses = value(attachment, "uses").filter(u -> INTEGER.matcher(u).matches())
        .map(Integer::parseInt).orElse(-1);
    final String times = uses < 0 ? "Every round."
        : uses == 0 ? "Used up: fires only when another trigger fires it."
        : uses == 1 ? "In 1 round." : "In " + uses + " rounds.";
    return times + value(attachment, "chance").map(c -> c.split(":"))
        .filter(c -> c.length == 2)
        .map(c -> " Chance " + c[0] + " in " + c[1] + ".")
        .orElse("");
  }

  /** What the condition checks, eg "Germans own France and have units in Egypt". */
  static String conditionSentence(final Attachment condition) {
    final List<Option> checks = AttachmentsXml.options(condition).stream()
        .filter(o -> spec(o.getName()).filter(s -> s.group() == Group.CHECK).isPresent())
        .toList();
    if (checks.isEmpty()) {
      return Objects.toString(condition.getName(), "");
    }
    final String who = value(condition, "players").map(p -> p.replace(":", ", "))
        .orElse(Objects.toString(condition.getAttachTo(), ""));
    final boolean inverted = value(condition, "invert").filter("true"::equalsIgnoreCase)
        .isPresent();
    final List<String> phrases = new ArrayList<>();
    boolean whoSaid = false;
    for (final Option check : checks) {
      final String phrase = phrase(spec(check.getName()).orElseThrow(), joined(check));
      // a relationship names its own two nations, whoever the condition is for
      if (whoSaid || AttachmentsXml.isOption(check, "relationship")) {
        phrases.add(phrase);
      } else {
        whoSaid = true;
        phrases.add(who + " " + phrase);
      }
    }
    return (inverted ? "not: " : "") + String.join(" and ", phrases);
  }

  /** The options in plain words; a resource and its amount as one, at the first of the two. */
  private static List<String> phrases(
      final Attachment attachment, final Group group, final Names names) {
    final Optional<String> resource = value(attachment, "resource");
    final Optional<String> amount = value(attachment, "resourceCount");
    final boolean paired = group == Group.EFFECT && resource.isPresent() && amount.isPresent();
    final List<String> phrases = new ArrayList<>();
    final Set<String> pair = new HashSet<>(paired ? Set.of("resource", "resourceCount") : Set.of());
    boolean pairShown = false;
    for (final Option option : AttachmentsXml.options(attachment)) {
      final Optional<Spec> spec = spec(option.getName()).filter(s -> s.group() == group);
      if (spec.isEmpty()) {
        continue;
      }
      if (pair.remove(AttachmentsXml.optionName(option.getName()))) {
        if (!pairShown) {
          pairShown = true;
          phrases.add(givePhrase(amount.get(), resource.get()));
        }
        continue;
      }
      phrases.add(
          Optional.of(option)
              .filter(o -> AttachmentsXml.isOption(o, "notification"))
              .flatMap(o -> messageText(names, joined(o)))
              .map(text -> "show message “" + text + "”")
              .orElseGet(
                  () ->
                      phrase(
                          spec.get(),
                          Stream.of("productionRule", "frontier")
                                  .anyMatch(name -> AttachmentsXml.isOption(option, name))
                              ? plainProduction(names, joined(option))
                              : joined(option))));
    }
    return phrases;
  }

  /** Eg "give 10 PUs", or "take 3 PUs" for -3. */
  private static String givePhrase(final String amount, final String resource) {
    return amount.startsWith("-") && INTEGER.matcher(amount).matches()
        ? "take " + amount.substring(1) + " " + resource
        : "give " + amount + " " + resource;
  }

  /** The option in plain words, eg "place 2 × infantry in Germany". */
  static String phrase(final Spec spec, final String joined) {
    final List<String> pieces = new ArrayList<>(split(spec, joined));
    for (int i = 0; i < pieces.size(); i++) {
      final Piece piece = spec.pieces().get(i);
      if (pieces.get(i).isBlank() && piece.shape() != Shape.OPTIONAL) {
        pieces.set(i, "… (choose " + piece.label().toLowerCase(Locale.ROOT) + ")");
      }
    }
    switch (spec.option()) {
      case "atWarPlayers", "techs" -> pieces.set(0, countWord(spec, pieces.getFirst()));
      // "any" was is no condition, so it is left out
      case "relationshipChange" ->
          pieces.set(2, pieces.get(2).equalsIgnoreCase("any") ? "" : plain(pieces.get(2)));
      case "relationship" -> {
        pieces.set(2, relationPhrase(pieces.get(2)));
        // -1 is TripleA's "however long" (RulesAttachment.relationshipExistsLongEnough)
        pieces.set(3, pieces.get(3).equals("-1") ? "" : pieces.get(3));
      }
      case "rounds" -> pieces.set(0, pieces.getFirst().replace("-+", " and later")
          .replace("-", " to ").replace(":", ", "));
      default -> { }
    }
    final Function<String, String> fill =
        text -> {
          final Matcher matcher = Pattern.compile("\\{(\\d)}").matcher(text);
          final StringBuilder filled = new StringBuilder();
          while (matcher.find()) {
            final int i = Integer.parseInt(matcher.group(1));
            final String piece = i < pieces.size() ? pieces.get(i).replace(":", ", ") : "";
            matcher.appendReplacement(filled, Matcher.quoteReplacement(piece));
          }
          return matcher.appendTail(filled).toString();
        };
    final Matcher optional = Pattern.compile("\\[([^]]*)]").matcher(spec.words());
    final StringBuilder words = new StringBuilder();
    while (optional.find()) {
      final Matcher used = Pattern.compile("\\{(\\d)}").matcher(optional.group(1));
      boolean empty = false;
      while (used.find()) {
        final int i = Integer.parseInt(used.group(1));
        empty |= i >= pieces.size() || pieces.get(i).isEmpty();
      }
      optional.appendReplacement(words, Matcher.quoteReplacement(empty ? "" : optional.group(1)));
    }
    return fill.apply(optional.appendTail(words).toString());
  }

  private static List<String> namesOf(final Game game, final String kind) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> kind.equals(AttachmentsXml.kind(a)))
        .map(Attachment::getName)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  /** Where else the condition is named, eg by another trigger, besides the trigger. */
  static List<String> otherUsers(
      final Game game, final String condition, final Collection<String> besides) {
    return RulesXml.whereNamed(game, condition).stream()
        .filter(place -> besides.stream().noneMatch(b -> place.startsWith(b + " of ")))
        .toList();
  }
}
