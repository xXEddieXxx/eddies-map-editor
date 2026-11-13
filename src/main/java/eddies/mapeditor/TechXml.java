package eddies.mapeditor;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PropertyList.Property.Value;
import org.triplea.map.data.elements.Technology;
import org.triplea.map.data.elements.Technology.PlayerTech;
import org.triplea.map.data.elements.Technology.PlayerTech.Category;
import org.triplea.map.data.elements.Technology.PlayerTech.Category.Tech;
import org.triplea.map.data.elements.Technology.Technologies;
import org.triplea.map.data.elements.Technology.Technologies.TechName;
import eddies.mapeditor.MapXml.AttachmentKey;

/**
 * The technologies: those of the game, the categories each nation researches them in, and the
 * built-in ones a nation has at the start (its techAttachment). Every change returns a new model,
 * as in {@link MapXml}.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class TechXml {
  /** The engine's own technologies by the option that gives them, eg "jetPower". */
  static final List<String> BUILT_IN =
      Arrays.stream(TechAdvance.class.getFields())
          .filter(f -> Modifier.isStatic(f.getModifiers()))
          .filter(f -> f.getName().startsWith("TECH_PROPERTY_"))
          .map(TechXml::constant)
          .toList();

  /** A unit gaining this with a technology may blitz; the other ability it may gain is bombard. */
  static final String CAN_BLITZ = TechAbilityAttachment.ABILITY_CAN_BLITZ;

  static final String CAN_BOMBARD = TechAbilityAttachment.ABILITY_CAN_BOMBARD;
  static final String AIRBORNE_FORCES = "airborneForces";
  static final String AIRBORNE_TYPES = "airborneTypes";
  static final String AIRBORNE_BASES = "airborneBases";
  private static final String GAINED = "unitAbilitiesGained";
  private static final String SHOT_AT_BY = "airborneTargettedByAA";
  private static final String TECH_COST = "techCost";
  private static final int STANDARD_TECH_COST = 5;

  /**
   * The options of a techAbilityAttachment that give a unit type a number, "n:unit", an entry per
   * unit type, each with its plain words, in the order shown.
   */
  static final Map<String, String> UNIT_NUMBERS = unitNumbers();

  private static Map<String, String> unitNumbers() {
    final Map<String, String> words = new LinkedHashMap<>();
    words.put("attackBonus", "Attack");
    words.put("defenseBonus", "Defence");
    words.put("movementBonus", "Movement");
    words.put("attackRollsBonus", "Attack rolls");
    words.put("defenseRollsBonus", "Defence rolls");
    words.put("airAttackBonus", "Air battle attack");
    words.put("airDefenseBonus", "Air battle defence");
    words.put("radarBonus", "Anti-air attack (radar)");
    words.put("bombingBonus", "Bombing damage");
    words.put("productionBonus", "Units it can produce");
    words.put("rocketDiceNumber", "Rocket dice");
    words.put("airborneCapacity", "Airborne units it launches");
    return Collections.unmodifiableMap(words);
  }

  /**
   * A whole number of a techAbilityAttachment.
   *
   * @param unset the number TripleA has when the option is not set; -1 shows as the blank word
   * @param max the most TripleA takes; it refuses a game with more
   * @param blank what an unset -1 means, eg "none"; empty for a number that is never -1
   */
  record AbilityNumber(String option, String label, int unset, int max, String blank) {}

  static final AbilityNumber ROCKET_DISTANCE =
      new AbilityNumber("rocketDistance", "Rockets reach this many territories", 0, 100, "");
  static final AbilityNumber ROCKETS_PER_TERRITORY =
      new AbilityNumber(
          "rocketNumberPerTerritory", "Rocket attacks from one territory, at most", 1, 200, "");
  static final AbilityNumber WAR_BOND_DICE =
      new AbilityNumber("warBondDiceNumber", "Dice rolled for PUs each turn", 0, 100, "");
  static final AbilityNumber WAR_BOND_SIDES =
      new AbilityNumber("warBondDiceSides", "Sides of each die", -1, 200, "none");
  static final AbilityNumber MIN_TERRITORY_VALUE =
      new AbilityNumber(
          "minimumTerritoryValueForProductionBonus",
          "More units produced only in territories worth at least",
          -1,
          10_000,
          "any");
  static final AbilityNumber REPAIR_DISCOUNT =
      new AbilityNumber("repairDiscount", "Repairs cost less, in %", -1, 100, "none");
  static final AbilityNumber AIRBORNE_DISTANCE =
      new AbilityNumber(
          "airborneDistance", "Airborne units reach this many territories", 0, 100, "");

  /** Every {@link AbilityNumber}: what a technology gives that is no number for a unit type. */
  static final List<AbilityNumber> ABILITY_NUMBERS =
      List.of(
          ROCKET_DISTANCE,
          ROCKETS_PER_TERRITORY,
          WAR_BOND_DICE,
          WAR_BOND_SIDES,
          MIN_TERRITORY_VALUE,
          REPAIR_DISCOUNT,
          AIRBORNE_DISTANCE);

  /**
   * The options the editor has no words for, or whose value it does not read, eg one a newer
   * TripleA knows: they stay as they are and are edited in Attachments.
   */
  static List<Option> otherAbilityOptions(final Game game, final String tech) {
    return abilityOptions(game, tech).stream()
        .filter(o -> unitNumber(o).isEmpty())
        .filter(
            o ->
                Stream.concat(
                        ABILITY_NUMBERS.stream().map(AbilityNumber::option),
                        Stream.of(
                            AIRBORNE_FORCES, AIRBORNE_TYPES, AIRBORNE_BASES, GAINED, SHOT_AT_BY))
                    .noneMatch(name -> AttachmentsXml.isOption(o, name)))
        .toList();
  }

  // a built-in technology's name in the game by its option, eg "Super subs" by "superSub":
  // TripleA finds a technology to attach to by either, and maps write both
  private static final Map<String, String> SHOWN_NAMES =
      Arrays.stream(TechAdvance.class.getFields())
          .filter(f -> Modifier.isStatic(f.getModifiers()))
          .filter(f -> f.getName().startsWith("TECH_PROPERTY_"))
          .filter(f -> shownName(f).isPresent())
          .collect(
              Collectors.toUnmodifiableMap(TechXml::constant, f -> shownName(f).orElseThrow()));

  private static Optional<String> shownName(final Field property) {
    try {
      return Optional.of(
          constant(
              TechAdvance.class.getField(
                  property.getName().replace("TECH_PROPERTY_", "TECH_NAME_"))));
    } catch (final NoSuchFieldException e) {
      return Optional.empty();
    }
  }

  private static String constant(final Field field) {
    try {
      return (String) field.get(null);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The game's technologies; none when it has no list and the engine makes its standard ones. */
  static List<TechName> techs(final Game game) {
    return Optional.ofNullable(game.getTechnology())
        .map(Technology::getTechnologies)
        .map(Technologies::getTechNames)
        .map(List::<TechName>copyOf)
        .orElse(List.of());
  }

  static List<String> techNames(final Game game) {
    return techs(game).stream().map(TechName::getName).toList();
  }

  /** The built-in technology it is, eg "jetPower"; empty for one whose effects attachments give. */
  static Optional<String> builtIn(final TechName tech) {
    return Stream.of(tech.getTech(), tech.getName())
        .filter(BUILT_IN::contains)
        .findFirst();
  }

  /**
   * The game with a new technology, built in or not; nothing changes for a blank or taken name. A
   * game without a list gets TripleA's standard technologies first, which it had till now.
   */
  static Game withTech(final Game given, final String name, @Nullable final String builtIn) {
    final Game game = withStandardTechs(given);
    if (name.isBlank() || techNames(game).contains(name)) {
      return given;
    }
    final List<TechName> techs =
        Stream.concat(
                techs(game).stream(),
                Stream.of(
                    TechName.builder()
                        .name(name)
                        .tech(builtIn == null || builtIn.equals(name) ? null : builtIn)
                        .build()))
            .toList();
    return withTechnology(game, techs, playerTechs(game));
  }

  /**
   * The game with the technologies and categories the engine makes for a game without a list,
   * written out; a game with a list stays as it is.
   */
  static Game withStandardTechs(final Game game) {
    if (game.getTechnology() != null) {
      return game;
    }
    // the engine's own choice, which goes by the game's WW2V2 and WW2V3 options
    final GameData data = new GameData();
    MapXml.properties(game).stream()
        .filter(p -> Constants.WW2V2.equals(p.getName()) || Constants.WW2V3.equals(p.getName()))
        .forEach(
            p ->
                data.getProperties()
                    .set(
                        p.getName(),
                        Boolean.parseBoolean(
                            Optional.ofNullable(p.getValue())
                                .or(
                                    () ->
                                        Optional.ofNullable(p.getValueProperty())
                                            .map(Value::getData))
                                .orElse("")
                                .strip())));
    final GamePlayer anyone = new GamePlayer("anyone", data);
    data.getPlayerList().addPlayerId(anyone);
    TechAdvance.createDefaultTechAdvances(data);
    final List<TechName> techs =
        data.getTechnologyFrontier().getTechs().stream()
            .map(t -> TechName.builder().name(t.getProperty()).build())
            .toList();
    final List<Category> categories =
        anyone.getTechnologyFrontierList().getFrontiers().stream()
            .map(
                f ->
                    Category.builder()
                        .name(f.getName())
                        .techs(
                            f.getTechs().stream()
                                .map(t -> Tech.builder().name(t.getProperty()).build())
                                .toList())
                        .build())
            .toList();
    return withTechnology(
        game,
        techs,
        MapXml.nations(game).stream()
            .map(n -> PlayerTech.builder().player(n).categories(categories).build())
            .toList());
  }

  /** The game without the technology, also out of each nation's categories. */
  static Game withoutTech(final Game game, final String name) {
    final Optional<TechName> removed =
        techs(game).stream().filter(t -> name.equals(t.getName())).findFirst();
    if (removed.isEmpty()) {
      return game;
    }
    return withTechnology(
        game,
        techs(game).stream().filter(t -> t != removed.get()).toList(),
        playerTechs(game).stream()
            .map(p -> withoutTechIn(p, techs(game), removed.get()))
            .toList());
  }

  private static PlayerTech withoutTechIn(
      final PlayerTech nation, final List<TechName> all, final TechName tech) {
    return nation.toBuilder()
        .categories(
            categories(nation).stream()
                .map(
                    c ->
                        c.toBuilder()
                            .techs(techsOf(c).stream().filter(t -> !isTech(all, t, tech)).toList())
                            .build())
                .toList())
        .build();
  }

  /**
   * Whether the tech of a category is the technology, found as the engine does: the first
   * technology that is built in as it, eg "jetPower", else the one of its name.
   */
  private static boolean isTech(
      final List<TechName> all, final Tech tech, final TechName technology) {
    final String name = tech.getName();
    return all.stream()
            .filter(t -> builtIn(t).orElse(t.getName()).equals(name))
            .findFirst()
            .or(() -> all.stream().filter(t -> t.getName().equals(name)).findFirst())
            .filter(t -> t == technology)
            .isPresent();
  }

  private static List<PlayerTech> playerTechs(final Game game) {
    return Optional.ofNullable(game.getTechnology())
        .map(Technology::getPlayerTechs)
        .map(List::<PlayerTech>copyOf)
        .orElse(List.of());
  }

  private static List<Category> categories(final PlayerTech nation) {
    return Optional.ofNullable(nation.getCategories()).map(List::copyOf).orElse(List.of());
  }

  private static List<Tech> techsOf(final Category category) {
    return Optional.ofNullable(category.getTechs()).map(List::copyOf).orElse(List.of());
  }

  /** The nation's categories, eg "Air and Naval Advances", its research charts. */
  static List<String> categories(final Game game, final String nation) {
    return playerTechs(game).stream()
        .filter(p -> nation.equals(p.getPlayer()))
        .flatMap(p -> categories(p).stream())
        .map(Category::getName)
        .distinct()
        .toList();
  }

  /** The category the nation researches the technology in; empty when it cannot research it. */
  static Optional<String> categoryOf(final Game game, final String nation, final String tech) {
    final Optional<TechName> technology =
        techs(game).stream().filter(t -> tech.equals(t.getName())).findFirst();
    return technology.flatMap(
        t ->
            playerTechs(game).stream()
                .filter(p -> nation.equals(p.getPlayer()))
                .flatMap(p -> categories(p).stream())
                .filter(c -> techsOf(c).stream().anyMatch(x -> isTech(techs(game), x, t)))
                .map(Category::getName)
                .findFirst());
  }

  /**
   * The game with the nation researching the technology in the category, a new one if the nation
   * has none of this name.
   */
  static Game withTechIn(
      final Game game, final String nation, final String tech, final String category) {
    return gameWithTechIn(game, nation, tech, category);
  }

  /** The game with the nation not researching the technology. */
  static Game withoutTechIn(final Game game, final String nation, final String tech) {
    return gameWithTechIn(game, nation, tech, null);
  }

  private static Game gameWithTechIn(
      final Game game, final String nation, final String tech, @Nullable final String category) {
    final Optional<TechName> technology =
        techs(game).stream().filter(t -> tech.equals(t.getName())).findFirst();
    if (technology.isEmpty()) {
      return game;
    }
    final List<PlayerTech> nations = new ArrayList<>(playerTechs(game));
    int at = -1;
    for (int i = 0; i < nations.size(); i++) {
      if (nation.equals(nations.get(i).getPlayer())) {
        nations.set(i, withoutTechIn(nations.get(i), techs(game), technology.get()));
        at = at < 0 ? i : at;
      }
    }
    if (category != null) {
      if (at < 0) {
        nations.add(PlayerTech.builder().player(nation).categories(List.of()).build());
        at = nations.size() - 1;
      }
      nations.set(at, withTechAdded(nations.get(at), category, tech));
    }
    return withTechnology(game, techs(game), nations);
  }

  private static PlayerTech withTechAdded(
      final PlayerTech nation, final String category, final String tech) {
    final List<Category> categories = new ArrayList<>(categories(nation));
    final Tech added = Tech.builder().name(tech).build();
    final int at =
        categories.stream().map(Category::getName).toList().indexOf(category);
    if (at < 0) {
      categories.add(Category.builder().name(category).techs(List.of(added)).build());
    } else {
      final Category was = categories.get(at);
      categories.set(
          at,
          was.toBuilder()
              .techs(Stream.concat(techsOf(was).stream(), Stream.of(added)).toList())
              .build());
    }
    return nation.toBuilder().categories(categories).build();
  }

  /** The game without the nation's categories, as the engine refuses those of no nation. */
  static Game withoutNation(final Game game, final String nation) {
    return game.getTechnology() == null
        ? game
        : withTechnology(
            game,
            techs(game),
            playerTechs(game).stream().filter(p -> !nation.equals(p.getPlayer())).toList());
  }

  /** Whether the nation has the built-in technology at the start, eg "jetPower". */
  static boolean hasAtStart(final Game game, final String nation, final String builtIn) {
    return MapXml.option(game, Constants.TECH_ATTACHMENT_NAME, nation, builtIn)
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  static Game withAtStart(final Game game, final String nation, final String builtIn) {
    return MapXml.withOption(game, techKey(nation), builtIn, "true");
  }

  static Game withoutAtStart(final Game game, final String nation, final String builtIn) {
    return MapXml.withOption(game, techKey(nation), builtIn, null);
  }

  private static AttachmentKey techKey(final String nation) {
    return new AttachmentKey(
        Constants.TECH_ATTACHMENT_NAME, TechAttachment.class.getName(), "player", nation);
  }

  /** The PUs one research roll costs the nation, for every technology; TripleA's 5 when not set. */
  static int techCost(final Game game, final String nation) {
    return MapXml.option(game, Constants.TECH_ATTACHMENT_NAME, nation, TECH_COST)
        .flatMap(TechXml::parseInt)
        .orElse(STANDARD_TECH_COST);
  }

  /** The game with the nation's research cost; the same game when it pays that already. */
  static Game withTechCost(final Game game, final String nation, final int cost) {
    return cost == techCost(game, nation)
        ? game
        : MapXml.withOption(game, techKey(nation), TECH_COST, String.valueOf(cost));
  }

  private static Optional<Integer> parseInt(final String value) {
    try {
      return Optional.of(Integer.parseInt(value.strip()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  // ---- what a technology gives: its techAbilityAttachment

  /**
   * The technology's techAbilityAttachment and its place among the game's attachments; empty when
   * it has none. A built-in technology without one gets TripleA's standard effects
   * (TechAbilityAttachment.setDefaultTechnologyAttachments); with one, only what it says. As in
   * TripleA, one attached to a built-in technology's name in the game, eg "Super subs", is the
   * one of superSub.
   */
  static Optional<Map.Entry<Integer, Attachment>> ability(final Game game, final String tech) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    final List<String> names =
        Stream.concat(Stream.of(tech), Optional.ofNullable(SHOWN_NAMES.get(tech)).stream())
            .toList();
    return IntStream.range(0, attachments.size())
        .filter(i -> names.contains(attachments.get(i).getAttachTo()))
        .filter(i -> "TechAbilityAttachment".equals(AttachmentsXml.kind(attachments.get(i))))
        .filter(
            i -> AttachmentsXml.isNamed(attachments.get(i), Constants.TECH_ABILITY_ATTACHMENT_NAME))
        .mapToObj(i -> Map.entry(i, attachments.get(i)))
        .findFirst();
  }

  /** The options of the technology's techAbilityAttachment, in the XML's order. */
  static List<Option> abilityOptions(final Game game, final String tech) {
    return ability(game, tech).map(a -> AttachmentsXml.options(a.getValue())).orElse(List.of());
  }

  /** An option's value as TripleA reads it: its count, if it has one, before the value. */
  static String read(final Option option) {
    final String value = Objects.toString(option.getValue(), "");
    return option.getCount() == null || option.getCount().isEmpty()
        ? value
        : option.getCount() + ":" + value;
  }

  /** The number an option gives a unit type, eg attackBonus "1:fighter". */
  record UnitNumber(String option, int number, String unit) {}

  /** The option as a number for a unit type; empty for another option or a value not so written. */
  static Optional<UnitNumber> unitNumber(final Option option) {
    final String[] parts = read(option).split(":");
    return UNIT_NUMBERS.keySet().stream()
        .filter(name -> AttachmentsXml.isOption(option, name))
        .findFirst()
        .filter(name -> parts.length == 2)
        .flatMap(name -> parseInt(parts[0]).map(n -> new UnitNumber(name, n, parts[1])));
  }

  /**
   * The game with the technology's options changed, its attachment made with the first option,
   * under the name TripleA reads it by; the same game when nothing changes. An attachment left
   * without options stays: without one a built-in technology would get its standard effects back.
   * A game without a list of technologies stays so: TripleA attaches to its standard ones too.
   */
  static Game withAbilityOptions(
      final Game game, final String tech, final UnaryOperator<List<Option>> change) {
    final Optional<Map.Entry<Integer, Attachment>> ability = ability(game, tech);
    final List<Option> before = abilityOptions(game, tech);
    final List<Option> after = List.copyOf(change.apply(new ArrayList<>(before)));
    if (after.equals(before)) {
      return game;
    }
    if (ability.isPresent()) {
      return AttachmentsXml.withAttachment(
          game,
          ability.get().getKey(),
          ability.get().getValue().toBuilder().options(after).build());
    }
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
    attachments.add(
        AttachmentsXml.newAttachment(
                "TechAbilityAttachment", Constants.TECH_ABILITY_ATTACHMENT_NAME, tech)
            .toBuilder()
            .options(after)
            .build());
    return AttachmentsXml.withAttachments(game, attachments);
  }

  /** The game without the technology's attachment: a built-in one has its standard effects. */
  static Game withoutAbility(final Game game, final String tech) {
    return ability(game, tech)
        .map(a -> AttachmentsXml.withoutAttachment(game, a.getKey()))
        .orElse(game);
  }

  /** The game with one more number for a unit type, eg +1 attack for fighters, after the others. */
  static Game withUnitNumberAdded(final Game game, final String tech, final UnitNumber added) {
    return withAbilityOptions(
        game,
        tech,
        options -> {
          options.add(written(added));
          return options;
        });
  }

  /**
   * The game with the number of the option at this row of {@link #abilityOptions}; unchanged when
   * the row is not that option for that unit type, eg after an undo.
   */
  static Game withUnitNumberAt(
      final Game game, final String tech, final int row, final UnitNumber changed) {
    return withAbilityOptions(
        game,
        tech,
        options -> {
          if (isRowOf(options, row, changed)
              && unitNumber(options.get(row)).orElseThrow().number() != changed.number()) {
            options.set(row, written(changed));
          }
          return options;
        });
  }

  /** The game without the option at this row, if it is this number for this unit type. */
  static Game withoutUnitNumberAt(
      final Game game, final String tech, final int row, final UnitNumber removed) {
    return withAbilityOptions(
        game,
        tech,
        options -> {
          if (isRowOf(options, row, removed)) {
            options.remove(row);
          }
          return options;
        });
  }

  private static boolean isRowOf(final List<Option> options, final int row, final UnitNumber of) {
    return row < options.size()
        && unitNumber(options.get(row))
            .filter(n -> n.option().equals(of.option()) && n.unit().equals(of.unit()))
            .isPresent();
  }

  private static Option written(final UnitNumber number) {
    return Option.builder()
        .name(number.option())
        .value(number.number() + ":" + number.unit())
        .build();
  }

  /** The number the technology's option has, TripleA's own when it is not set. */
  static int abilityNumber(final Game game, final String tech, final AbilityNumber number) {
    return abilityOptions(game, tech).stream()
        .filter(o -> AttachmentsXml.isOption(o, number.option()))
        .map(TechXml::read)
        .flatMap(v -> parseInt(v).stream())
        .findFirst()
        .orElse(number.unset());
  }

  /**
   * The game with the number set, or its option taken out for the number TripleA has anyway. A
   * number TripleA refuses, below 0 or above the option's most (getIntInRange), is not written:
   * the game would not load.
   */
  static Game withAbilityNumber(
      final Game game, final String tech, final AbilityNumber number, final int value) {
    final boolean refused = value != number.unset() && (value < 0 || value > number.max());
    return refused || value == abilityNumber(game, tech, number)
        ? game
        : withAbilityValues(
            game,
            tech,
            number.option(),
            value == number.unset() ? List.of() : List.of(String.valueOf(value)));
  }

  /** Whether the technology's option says true, eg airborneForces. */
  static boolean abilityFlag(final Game game, final String tech, final String option) {
    return abilityOptions(game, tech).stream()
        .filter(o -> AttachmentsXml.isOption(o, option))
        .map(o -> Boolean.parseBoolean(read(o).strip()))
        .findFirst()
        .orElse(false);
  }

  /** The game with the option true; {@link #withoutAbilityFlag} takes it out. */
  static Game withAbilityFlag(final Game game, final String tech, final String option) {
    return abilityFlag(game, tech, option)
        ? game
        : withAbilityValues(game, tech, option, List.of("true"));
  }

  static Game withoutAbilityFlag(final Game game, final String tech, final String option) {
    return abilityFlag(game, tech, option)
        ? withAbilityValues(game, tech, option, List.of())
        : game;
  }

  /** The unit types the technology's option lists, over all its entries, eg airborneTypes. */
  static List<String> abilityUnits(final Game game, final String tech, final String option) {
    return abilityOptions(game, tech).stream()
        .filter(o -> AttachmentsXml.isOption(o, option))
        .flatMap(o -> Stream.of(read(o).split(":")))
        .filter(unit -> !unit.isEmpty())
        .distinct()
        .toList();
  }

  /** The game with the option listing these unit types, an entry each; unchanged when it does. */
  static Game withAbilityUnits(
      final Game game, final String tech, final String option, final List<String> units) {
    return units.equals(abilityUnits(game, tech, option))
        ? game
        : withAbilityValues(game, tech, option, units);
  }

  /** The unit types that gain the ability with the technology: {@link #CAN_BLITZ} or bombard. */
  static List<String> gaining(final Game game, final String tech, final String ability) {
    return gained(game, tech).entrySet().stream()
        .filter(e -> e.getValue().contains(ability))
        .map(Map.Entry::getKey)
        .toList();
  }

  /** The game with these unit types gaining the ability; what they gain besides stays. */
  static Game withGaining(
      final Game game, final String tech, final String ability, final List<String> units) {
    if (units.equals(gaining(game, tech, ability))) {
      return game;
    }
    final Map<String, List<String>> gained = new LinkedHashMap<>(gained(game, tech));
    units.forEach(unit -> gained.putIfAbsent(unit, List.of()));
    gained.replaceAll(
        (unit, abilities) ->
            Stream.concat(
                    abilities.stream().filter(a -> !a.equals(ability)),
                    units.contains(unit) ? Stream.of(ability) : Stream.empty())
                .sorted()
                .toList());
    return withAbilityValues(
        game,
        tech,
        GAINED,
        gained.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty())
            .map(e -> e.getKey() + ":" + String.join(":", e.getValue()))
            .toList());
  }

  // unit type -> the abilities it gains, "unit:canBlitz:canBombard" an entry
  private static Map<String, List<String>> gained(final Game game, final String tech) {
    final Map<String, List<String>> gained = new LinkedHashMap<>();
    abilityOptions(game, tech).stream()
        .filter(o -> AttachmentsXml.isOption(o, GAINED))
        .map(o -> List.of(read(o).split(":")))
        .filter(parts -> parts.size() > 1)
        .forEach(
            parts ->
                gained.merge(
                    parts.getFirst(),
                    parts.subList(1, parts.size()),
                    (a, b) -> Stream.concat(a.stream(), b.stream()).distinct().toList()));
    return gained;
  }

  /**
   * The unit types each kind of anti-air shoots at when they drop as airborne forces, by the
   * kind's name (a unit's typeAA, "AA" when not set): airborneTargettedByAA "AA:infantry:elite".
   * As in TripleA, a kind's last entry counts.
   */
  static Map<String, List<String>> shotAtBy(final Game game, final String tech) {
    final Map<String, List<String>> shot = new LinkedHashMap<>();
    abilityOptions(game, tech).stream()
        .filter(o -> AttachmentsXml.isOption(o, SHOT_AT_BY))
        .map(o -> List.of(read(o).split(":")))
        .filter(parts -> parts.size() > 1)
        .forEach(parts -> shot.put(parts.getFirst(), parts.subList(1, parts.size())));
    return shot;
  }

  /** The game with the kind of anti-air shooting at these unit types; none takes the kind out. */
  static Game withShotAtBy(
      final Game game, final String tech, final String aaType, final List<String> units) {
    final Map<String, List<String>> shot = new LinkedHashMap<>(shotAtBy(game, tech));
    if (units.equals(shot.getOrDefault(aaType, List.of()))) {
      return game;
    }
    if (units.isEmpty()) {
      shot.remove(aaType);
    } else {
      shot.put(aaType, units);
    }
    return withAbilityValues(
        game,
        tech,
        SHOT_AT_BY,
        shot.entrySet().stream()
            .map(e -> e.getKey() + ":" + String.join(":", e.getValue()))
            .toList());
  }

  /**
   * The game with the option's entries replaced by these values, the first where the first one
   * was, the others right after it; new ones after the other options.
   */
  private static Game withAbilityValues(
      final Game game, final String tech, final String option, final List<String> values) {
    return withAbilityOptions(
        game,
        tech,
        options -> {
          int at = options.size();
          for (int i = options.size() - 1; i >= 0; i--) {
            if (AttachmentsXml.isOption(options.get(i), option)) {
              options.remove(i);
              at = i;
            }
          }
          options.addAll(
              Math.min(at, options.size()),
              values.stream().map(v -> Option.builder().name(option).value(v).build()).toList());
          return options;
        });
  }

  private static Game withTechnology(
      final Game game, final List<TechName> techs, final List<PlayerTech> nations) {
    final Technology technology =
        Optional.ofNullable(game.getTechnology()).orElseGet(() -> Technology.builder().build());
    final Technologies names =
        Optional.ofNullable(technology.getTechnologies())
            .orElseGet(() -> Technologies.builder().build());
    return game.toBuilder()
        .technology(
            technology.toBuilder()
                .technologies(names.toBuilder().techNames(techs).build())
                .playerTechs(nations)
                .build())
        .build();
  }
}
