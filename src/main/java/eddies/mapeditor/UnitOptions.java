package eddies.mapeditor;

import static java.util.Map.entry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * The options of a unit type's unitAttachment the Units tool shows in plain words, grouped as a map
 * maker thinks of them. Each has the name TripleA reads (UnitAttachment) and its default there; the
 * other options a map uses stay under "More options".
 */
@UtilityClass
class UnitOptions {
  /** How an option's value is typed. */
  enum Kind {
    /** true or false, false by default */
    FLAG,
    /** a whole number */
    NUMBER,
    /** unit types joined by colons */
    UNITS,
    /** nations joined by colons */
    NATIONS,
    /** territories joined by colons */
    TERRITORIES,
    TEXT
  }

  /**
   * @param name the option's name in the XML
   * @param defaultValue what TripleA takes when the map does not give it; the option is left out
   *     for it
   * @param minimum the least whole number TripleA loads, eg 1 hit point
   * @param help what it means, empty when the label says it
   */
  record UnitOption(
      String name, String label, Kind kind, String defaultValue, int minimum, String help) {}

  record Section(String title, List<UnitOption> options) {}

  static final String COMBAT = "Combat";
  private static final String AIR = "Air and anti-air";

  /**
   * What a section's "?" says about all of it. TripleA's battle round (BattleStep.Order) starts
   * with AA_OFFENSIVE, AA_DEFENSIVE and AA_REMOVE_CASUALTIES, the fire of isAAforCombatOnly units
   * at their targetsAA, then bombardment, then first strike, then everyone else.
   */
  static final Map<String, String> SECTION_HELP =
      Map.of(
          AIR,
          "Anti-air is targeted fire: a unit that \"Fires at planes in battles\" shoots at the"
              + " units of \"Planes it fires at\" (any unit types, not only planes) at the start"
              + " of a battle (its first round, unless set otherwise), before bombardment and before"
              + " first strike. What it hits"
              + " is removed at once and does not fire back.");

  static final List<Section> SECTIONS =
      List.of(
          new Section(
              COMBAT,
              List.of(
                  number("attack", "Attack", 0, "Hits on a die roll of this or less."),
                  number("defense", "Defense", 0, "Hits when defending on this or less."),
                  number("hitPoints", "Hit points", 1, 1, "Hits it takes to destroy, eg 2."),
                  number("attackRolls", "Dice when attacking", 1, ""),
                  number("defenseRolls", "Dice when defending", 1, ""),
                  flag("chooseBestRoll", "Takes its best die instead of rolling each", ""),
                  flag("isFirstStrike", "Fires first: its hits take no shot back", ""),
                  flag("canEvade", "Can leave a battle before it fires", ""),
                  flag("artillery", "Artillery: supports infantry", ""),
                  flag("artillerySupportable", "Gets the support of artillery", ""),
                  number(
                      "unitSupportCount",
                      "Units it supports",
                      -1,
                      "How many units its support reaches; empty for as many as the support says."),
                  number(
                      "isMarine",
                      "Bonus when landing from sea",
                      0,
                      "Added to its attack in a landing from a transport, eg 1."),
                  units(
                      "canNotTarget",
                      "Cannot hit",
                      "Unit types its hits never go to, also with a destroyer beside it."),
                  units(
                      "canNotBeTargetedBy",
                      "Cannot be hit by",
                      "Unit types whose hits miss it, eg planes for a submarine, unless their side"
                          + " has a destroyer in the battle."),
                  flag("isSuicideOnAttack", "Dies after it attacks", ""),
                  flag("isSuicideOnDefense", "Dies after it defends", ""),
                  flag("isSuicideOnHit", "Dies when it hits", ""))),
          new Section(
              "Movement",
              List.of(
                  number("movement", "Movement", 0, "Territories it can move per turn."),
                  flag("canBlitz", "Can blitz through empty territories", ""),
                  flag("canMoveThroughEnemies", "Can move through enemy units", ""),
                  flag("canBeMovedThroughByEnemies", "Enemies can move through it", ""),
                  flag("canNotMoveDuringCombatMove", "Moves only after combat", ""),
                  flag(
                      "isKamikaze",
                      "Kamikaze",
                      "A plane that need not keep moves to land: it may fly to its end."))),
          new Section(
              "Transport and carrier",
              List.of(
                  number(
                      "transportCapacity",
                      "Transport capacity",
                      -1,
                      "Room it has for land units."),
                  number(
                      "transportCost",
                      "Transport cost",
                      -1,
                      "Room it takes on a transport."),
                  flag("isCombatTransport", "Fights as well as transports", ""),
                  number(
                      "carrierCapacity",
                      "Carrier capacity",
                      -1,
                      "Room it has for planes."),
                  number(
                      "carrierCost",
                      "Carrier cost",
                      -1,
                      "Room it takes on a carrier."),
                  flag("isAirTransport", "Carries paratroopers", ""),
                  flag("isAirTransportable", "Can be carried by planes", ""),
                  flag("isLandTransport", "Carries land units on land", ""),
                  flag("isLandTransportable", "Can be carried on land", ""))),
          new Section(
              "Sea",
              List.of(
                  flag("isSea", "Sails", ""),
                  flag(
                      "isDestroyer",
                      "Destroyer: cancels the first strike of subs",
                      "With one in the battle its side also hits units that 'cannot be hit by'"
                          + " them, eg planes hit submarines."),
                  flag("canBombard", "Can bombard a shore", ""),
                  number(
                      "bombard",
                      "Bombard strength",
                      -1,
                      "Hits on this or less when it bombards; empty for its attack."),
                  number(
                      "blockade",
                      "Blockade",
                      0,
                      "PUs an enemy loses when it is in a sea zone beside its territory."))),
          new Section(
              AIR,
              List.of(
                  flag("isAir", "Flies", ""),
                  flag("canIntercept", "Can intercept bombers", ""),
                  flag("requiresAirbaseToIntercept", "Needs an air base to intercept", ""),
                  flag("canEscort", "Can escort bombers", ""),
                  flag("canAirBattle", "Fights in air battles", ""),
                  number("airAttack", "Air battle attack", 0, ""),
                  number("airDefense", "Air battle defense", 0, ""),
                  flag("canScramble", "Can scramble to sea battles", ""),
                  flag("isAirBase", "Air base: planes may scramble from it", ""),
                  number("maxScrambleDistance", "Scramble distance", -1, ""),
                  number("maxScrambleCount", "Planes it lets scramble", -1, ""),
                  number("maxInterceptCount", "Planes it lets intercept", -1, ""),
                  flag("isAAforCombatOnly", "Fires at planes in battles", ""),
                  flag("isAAforBombingThisUnitOnly", "Fires at planes bombing it", ""),
                  flag("isAAforFlyOverOnly", "Fires at planes flying over", ""),
                  number("attackAA", "Anti-air defense", 1, "Hits planes on this or less."),
                  number(
                      "offensiveAttackAA", "Anti-air attack", 0, "Hits planes on this or less."),
                  number(
                      "maxAAattacks",
                      "Anti-air shots",
                      -1,
                      "Shots per round; empty for one at each plane."),
                  number(
                      "maxRoundsAA", "Anti-air rounds", 1, -1, "Rounds it fires; empty for all."),
                  text(
                      "typeAA",
                      "Kind of anti-air",
                      "AA",
                      "A name; anti-air of the same kind fires together."),
                  units("targetsAA", "Planes it fires at", "Empty: all planes."),
                  flag("mayOverStackAA", "May fire more shots than there are planes", ""),
                  flag("damageableAA", "Its anti-air can be damaged", ""),
                  flag("isRocket", "Rocket: fires at factories", ""))),
          new Section(
              "Production and infrastructure",
              List.of(
                  flag(
                      "isInfrastructure",
                      "Infrastructure, eg a factory",
                      "Taken by the conqueror, never destroyed in battle."),
                  flag("canProduceUnits", "Produces units", ""),
                  number(
                      "canProduceXUnits",
                      "Units it produces",
                      -1,
                      "Per turn; empty for the territory's production."),
                  flag("canBeDamaged", "Can be damaged by bombing", ""),
                  number("maxDamage", "Most damage", 2, "Damage it takes at most."),
                  number(
                      "maxOperationalDamage",
                      "Damage until it stops working",
                      -1,
                      "With more damage it is disabled (produces nothing, no air base or repair);"
                          + " empty: never disabled."),
                  flag("canDieFromReachingMaxDamage", "Is destroyed at its most damage", ""),
                  flag("isConstruction", "Construction: placed like a factory", ""),
                  text(
                      "constructionType",
                      "Kind of construction",
                      "none",
                      "A name; constructions of a kind count together."),
                  number("constructionsPerTerrPerTypePerTurn", "Constructions per turn", -1, ""),
                  number("maxConstructionsPerTypePerTerr", "Constructions per territory", -1, ""),
                  number("maxBuiltPerPlayer", "Most a nation may have", -1, ""),
                  number(
                      "canOnlyBePlacedInTerritoryValuedAtX",
                      "Placed only in territories worth",
                      -1,
                      "The least production a territory needs."),
                  territories(
                      "unitPlacementRestrictions",
                      "Never placed in",
                      "Territories where it may not be placed."),
                  territories(
                      "unitPlacementOnlyAllowedIn",
                      "Placed only in",
                      "Territories where it may be placed, empty for all."))),
          new Section(
              "Bombing",
              List.of(
                  flag("isStrategicBomber", "Strategic bomber", ""),
                  number(
                      "bombingMaxDieSides",
                      "Bombing die",
                      -1,
                      "Sides of its bombing die."),
                  number("bombingBonus", "Bombing bonus", 0, "Added to each bombing roll."),
                  units("bombingTargets", "Bombs", "Unit types it may bomb, empty for all."))),
          new Section(
              "Special",
              List.of(
                  number(
                      "tuv",
                      "Value",
                      -1,
                      "What it is worth for the battle calculator."),
                  number(
                      "whenCapturedSustainsDamage",
                      "Damage when captured",
                      0,
                      "Damage it takes when captured."),
                  nations(
                      "canBeCapturedOnEnteringBy",
                      "Captured by entering",
                      "Nations that capture it by moving in."),
                  nations(
                      "canBeGivenByTerritoryTo",
                      "Given with its territory to",
                      "Nations it goes to with its territory."))));

  /** With the cost, the numbers in the row at the top, out of their sections. */
  static final List<String> ESSENTIALS = List.of("attack", "defense", "movement", "hitPoints");

  /** What a unit is: TripleA's isSea and isAir, both false for land. */
  enum UnitKind {
    LAND,
    SEA,
    AIR
  }

  /** The options the kind sets; in their sections only for a unit that both sails and flies. */
  static final Set<String> KIND_FLAGS = Set.of("isSea", "isAir");

  /** Shown in their section for every unit the section is for, set or not. */
  static final Set<String> COMMON =
      Set.of(
          "attackRolls",
          "defenseRolls",
          "isFirstStrike",
          "artillery",
          "artillerySupportable",
          "canBlitz",
          "transportCapacity",
          "transportCost",
          "carrierCapacity",
          "carrierCost",
          "isDestroyer",
          "canBombard",
          "canIntercept",
          "canEscort",
          "canScramble",
          "isAirBase",
          "isInfrastructure",
          "canProduceUnits",
          "canBeDamaged",
          "maxDamage",
          "isStrategicBomber",
          "tuv");

  /**
   * The word an empty number field shows instead of -1, which TripleA takes as "none" or "all";
   * "default" for a number that starts at -1 and has no word here.
   */
  static Optional<String> blankWord(final UnitOption option) {
    return Optional.ofNullable(BLANK_WORDS.get(option.name()))
        .or(() -> Optional.of("default").filter(word -> "-1".equals(option.defaultValue())));
  }

  /** Whether a search finds the option: by its label or by TripleA's name for it. */
  static boolean matches(final UnitOption option, final String lowerCaseSearch) {
    return option.label().toLowerCase(Locale.ROOT).contains(lowerCaseSearch)
        || option.name().toLowerCase(Locale.ROOT).contains(lowerCaseSearch);
  }

  /** The word an empty number field shows for -1, TripleA's default "none" or "all". */
  static final Map<String, String> BLANK_WORDS =
      Map.ofEntries(
          entry("unitSupportCount", "all"),
          entry("transportCapacity", "none"),
          entry("transportCost", "none"),
          entry("carrierCapacity", "none"),
          entry("carrierCost", "none"),
          entry("maxScrambleDistance", "none"),
          entry("maxScrambleCount", "no limit"),
          entry("maxInterceptCount", "no limit"),
          entry("maxAAattacks", "no limit"),
          entry("maxRoundsAA", "all rounds"),
          entry("maxOperationalDamage", "never"),
          entry("maxBuiltPerPlayer", "no limit"),
          entry("constructionsPerTerrPerTypePerTurn", "no limit"),
          entry("maxConstructionsPerTypePerTerr", "no limit"),
          entry("bombingMaxDieSides", "game's dice"),
          entry("tuv", "its cost"),
          entry("bombard", "its attack"),
          entry("canProduceXUnits", "territory's"),
          entry("canOnlyBePlacedInTerritoryValuedAtX", "any"));

  /**
   * @param phrase what the option is, in plain words
   * @param format how its value is written, as UnitAttachment's setter reads it
   */
  record Hint(String phrase, String format) {}

  /** Options the sections do not show, explained when adding one (as UnitAttachment reads them). */
  static final Map<String, Hint> EXTRA_HINTS =
      Map.ofEntries(
          entry(
              "consumesUnits",
              new Hint("units it uses up when built", "Count and unit, eg 1:factory")),
          entry(
              "createsUnitsList",
              new Hint("units it creates each turn", "Count and unit, eg 1:infantry")),
          entry(
              "createsResourcesList",
              new Hint("resources it makes each turn", "Count and resource, eg 2:PUs")),
          entry("fuelCost", new Hint("resources each move costs", "Count and resource, eg 1:Fuel")),
          entry(
              "fuelFlatCost",
              new Hint(
                  "resources it costs once a turn when it moves", "Count and resource, eg 1:Fuel")),
          entry(
              "givesMovement",
              new Hint("movement it gives other units", "Count and units, eg 1:infantry:armour")),
          entry("requiresUnits", new Hint("units needed to place it", "Units, eg factory")),
          entry(
              "requiresUnitsToMove",
              new Hint("units needed with it to move", "Units, eg airfield")),
          entry("repairsUnits", new Hint("units it repairs", "Units, eg battleship")),
          entry(
              "canInvadeOnlyFrom",
              new Hint("what it may land from", "all, none or units, eg transport")),
          entry(
              "special",
              new Hint("special rules", "none or canOnlyPlaceInOriginalTerritories")),
          entry(
              "destroyedWhenCapturedBy",
              new Hint(
                  "nations whose capture destroys it", "Nations, eg Russians, or FROM:Russians")),
          entry("placementLimit", limit("placing")),
          entry("movementLimit", limit("moving")),
          entry("attackingLimit", limit("attacking")));

  /** The names of the options shown in the sections. */
  static final Set<String> NAMES =
      SECTIONS.stream()
          .flatMap(s -> s.options().stream())
          .map(UnitOption::name)
          .collect(Collectors.toUnmodifiableSet());

  /** The option of the sections with the name. */
  static UnitOption named(final String name) {
    return SECTIONS.stream()
        .flatMap(s -> s.options().stream())
        .filter(o -> name.equals(o.name()))
        .findFirst()
        .orElseThrow();
  }

  /**
   * Old option names TripleA still reads, with the options each sets (UnitAttachment's setters,
   * LegacyPropertyMapper): while a unit has one, those show what it sets and are changed there.
   */
  static final Map<String, Set<String>> OLD_NAMES =
      Map.of(
          "isSub",
          Set.of(
              "canEvade",
              "isFirstStrike",
              "canMoveThroughEnemies",
              "canNotTarget",
              "canNotBeTargetedBy"),
          "isSuicide",
          Set.of("isFirstStrike", "isSuicideOnAttack", "isSuicideOnDefense", "canNotTarget"),
          "isTwoHit",
          Set.of("hitPoints"),
          "isFactory",
          Set.of(
              "canBeDamaged",
              "isInfrastructure",
              "canProduceUnits",
              "isConstruction",
              "constructionType",
              "maxConstructionsPerTypePerTerr",
              "constructionsPerTerrPerTypePerTurn"),
          "isAA",
          Set.of(
              "isAAforCombatOnly",
              "isAAforBombingThisUnitOnly",
              "isAAforFlyOverOnly",
              "isRocket",
              "isInfrastructure"),
          "isInfantry",
          Set.of("isLandTransportable"),
          "isMechanized",
          Set.of("isLandTransportable"),
          "isParatroop",
          Set.of("isAirTransportable"));

  private static Hint limit(final String when) {
    return new Hint(
        "most of it per territory when " + when,
        "Count and whose, eg 2:owned (owned, allied or total)");
  }

  private static UnitOption flag(final String name, final String label, final String help) {
    return new UnitOption(name, label, Kind.FLAG, "false", 0, help);
  }

  private static UnitOption number(
      final String name, final String label, final int defaultValue, final String help) {
    // -1 where it is the default, eg "none" or "no limit"; TripleA refuses it elsewhere
    return number(name, label, defaultValue, Math.min(0, defaultValue), help);
  }

  private static UnitOption number(
      final String name,
      final String label,
      final int defaultValue,
      final int minimum,
      final String help) {
    return new UnitOption(name, label, Kind.NUMBER, String.valueOf(defaultValue), minimum, help);
  }

  private static UnitOption text(
      final String name, final String label, final String defaultValue, final String help) {
    return new UnitOption(name, label, Kind.TEXT, defaultValue, 0, help);
  }

  private static UnitOption units(final String name, final String label, final String help) {
    return new UnitOption(name, label, Kind.UNITS, "", 0, help);
  }

  private static UnitOption nations(final String name, final String label, final String help) {
    return new UnitOption(name, label, Kind.NATIONS, "", 0, help);
  }

  private static UnitOption territories(final String name, final String label, final String help) {
    return new UnitOption(name, label, Kind.TERRITORIES, "", 0, help);
  }
}
