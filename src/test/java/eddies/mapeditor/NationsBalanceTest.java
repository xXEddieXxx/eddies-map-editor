package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.stats.ProductionStat;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.java.collections.IntegerMap;
import org.triplea.map.data.elements.Game;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import eddies.mapeditor.NationsBalance.Row;

/** Compare nations: what each starts with, counted from the game as TripleA counts it. */
final class NationsBalanceTest {
  @TempDir Path folder;

  private static Row rowOf(final List<Row> rows, final String name) {
    return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
  }

  /** The G40 file itself, read as XML, to count in it without the editor's model. */
  private static Document givenG40Xml() throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    try (InputStream xml =
        NationsBalanceTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      return factory.newDocumentBuilder().parse(xml);
    }
  }

  private static List<Element> elements(final Document xml, final String name) {
    final NodeList nodes = xml.getElementsByTagName(name);
    return java.util.stream.IntStream.range(0, nodes.getLength())
        .mapToObj(i -> (Element) nodes.item(i))
        .toList();
  }

  /** Each territory's production, from its territoryAttachment in the file. */
  private static Map<String, Integer> givenProduction(final Document xml) {
    final Map<String, Integer> production = new HashMap<>();
    for (final Element attachment : elements(xml, "attachment")) {
      if ("territory".equals(attachment.getAttribute("type"))) {
        final NodeList options = attachment.getElementsByTagName("option");
        for (int i = 0; i < options.getLength(); i++) {
          final Element option = (Element) options.item(i);
          if ("production".equals(option.getAttribute("name"))) {
            production.put(
                attachment.getAttribute("attachTo"), Integer.parseInt(option.getAttribute("value")));
          }
        }
      }
    }
    return production;
  }

  /** TripleA's own unit costs for the nation, if this TripleA has its calculator. */
  private IntegerMap<UnitType> givenTripleaCosts(final GameData data, final String nation)
      throws Exception {
    final Class<?> calculator;
    try {
      calculator = Class.forName("games.strategy.triplea.util.TuvCostsCalculator");
    } catch (final ClassNotFoundException e) {
      assumeTrue(false, "this TripleA has no TuvCostsCalculator");
      throw e;
    }
    final Method costs = calculator.getMethod("getCostsForTuv", GamePlayer.class);
    @SuppressWarnings("unchecked")
    final IntegerMap<UnitType> result =
        (IntegerMap<UnitType>)
            costs.invoke(
                calculator.getDeclaredConstructor().newInstance(),
                data.getPlayerList().getPlayerId(nation));
    return result;
  }

  private GameData givenG40Loaded() throws Exception {
    final Path xml = folder.resolve("ww2global40.xml");
    try (InputStream original =
        NationsBalanceTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    return GameParser.parse(xml, false).orElseThrow();
  }

  @DisplayName("A nation's territories, income, PUs and units are those the game file has")
  @Test
  void countsWhatTheFileHas() throws Exception {
    // given
    final Document xml = givenG40Xml();
    final Map<String, Integer> production = givenProduction(xml);

    // when
    final NationsBalance balance = NationsBalance.of(GameRulesXmlTest.givenG40());

    // then
    assertThat(balance.nations().stream().map(Row::name).toList())
        .as("a row per nation, in the game's order")
        .isEqualTo(MapXml.nations(GameRulesXmlTest.givenG40()));
    for (final String nation : List.of("Germans", "Russians")) {
      // each territory once: the file gives Russia to the Russians twice
      final List<String> owned =
          elements(xml, "territoryOwner").stream()
              .filter(o -> nation.equals(o.getAttribute("owner")))
              .map(o -> o.getAttribute("territory"))
              .distinct()
              .toList();
      final int units =
          elements(xml, "unitPlacement").stream()
                  .filter(u -> nation.equals(u.getAttribute("owner")))
                  .mapToInt(u -> Integer.parseInt(u.getAttribute("quantity")))
                  .sum()
              + elements(xml, "heldUnits").stream()
                  .filter(u -> nation.equals(u.getAttribute("player")))
                  .mapToInt(u -> Integer.parseInt(u.getAttribute("quantity")))
                  .sum();
      final int pus =
          elements(xml, "resourceGiven").stream()
              .filter(r -> nation.equals(r.getAttribute("player")))
              .filter(r -> "PUs".equals(r.getAttribute("resource")))
              .mapToInt(r -> Integer.parseInt(r.getAttribute("quantity")))
              .sum();
      final Row row = rowOf(balance.nations(), nation);
      assertThat(owned).as("%s starts with land", nation).isNotEmpty();
      assertThat(row.territories()).as("%s: territories", nation).isEqualTo(owned.size());
      assertThat(row.income())
          .as("%s: the PUs of its territories", nation)
          .isEqualTo(
              owned.stream()
                  .mapToInt(territory -> production.getOrDefault(territory, 0))
                  .sum())
          .isPositive();
      assertThat(row.units()).as("%s: units at the start", nation).isEqualTo(units).isPositive();
      assertThat(row.resources().getFirst()).as("%s: starting PUs", nation).isEqualTo(pus);
    }
    assertThat(balance.resources()).as("PUs first").startsWith("PUs");
  }

  @DisplayName("Income is TripleA's Production at the start; contested land gives none if so set")
  @Test
  void incomeIsWhatTripleaCollects() throws Exception {
    // given
    final GameData data = givenG40Loaded();
    final Game game = GameRulesXmlTest.givenG40();
    final Game contested =
        GameRulesXml.withOptionValue(
            MapXml.withUnits(game, "Poland", "British", "infantry", 1),
            "Contested Territories Produce No Income",
            "true");

    // when
    final NationsBalance balance = NationsBalance.of(game);
    final int germans = rowOf(NationsBalance.of(contested).nations(), "Germans").income();

    // then
    for (final Row row : balance.nations()) {
      assertThat(row.income())
          .as("%s: as TripleA's Players tab says", row.name())
          .isEqualTo(
              (int)
                  new ProductionStat()
                      .getValue(data.getPlayerList().getPlayerId(row.name()), data, null));
    }
    assertThat(germans)
        .as("Poland, with a British unit in it, gives the Germans nothing")
        .isEqualTo(
            rowOf(balance.nations(), "Germans").income() - MapXml.production(game, "Poland"));
  }

  @DisplayName("Income leaves out sea zones first owned by others and cut convoys, times Multiply")
  @Test
  void incomeOfSeaZonesAndConvoys() throws Exception {
    // given: on the minimap, Russians own two sea zones, one first the Italians'; red north 1
    // lies on a convoy route of an Italian zone, red north 2 on one of a Russian zone
    final String route =
        "attachTo=\"red north %d\" javaClass=\"games.strategy.triplea.attachments.TerritoryAttachment\""
            + " type=\"territory\">";
    final String zone =
        "<attachment name=\"territoryAttachment\" attachTo=\"sea zone %d\""
            + " javaClass=\"games.strategy.triplea.attachments.TerritoryAttachment\" type=\"territory\">"
            + "<option name=\"production\" value=\"%d\"/>%s</attachment>";
    final String xml;
    try (InputStream minimap =
        NationsBalanceTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      xml =
          new String(minimap.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .replace(
                  route.formatted(1),
                  route.formatted(1)
                      + "<option name=\"convoyRoute\" value=\"true\"/>"
                      + "<option name=\"convoyAttached\" value=\"sea zone 3\"/>")
              .replace(
                  route.formatted(2),
                  route.formatted(2)
                      + "<option name=\"convoyRoute\" value=\"true\"/>"
                      + "<option name=\"convoyAttached\" value=\"sea zone 2\"/>")
              .replace(
                  "</attachmentList>",
                  zone.formatted(1, 3, "<option name=\"originalOwner\" value=\"Italians\"/>")
                      + zone.formatted(2, 4, "<option name=\"convoyRoute\" value=\"true\"/>")
                      + zone.formatted(3, 0, "<option name=\"convoyRoute\" value=\"true\"/>")
                      + "</attachmentList>")
              .replace(
                  "<territoryOwner territory=\"red capitol\" owner=\"Russians\"/>",
                  "<territoryOwner territory=\"red capitol\" owner=\"Russians\"/>"
                      + "<territoryOwner territory=\"sea zone 1\" owner=\"Russians\"/>"
                      + "<territoryOwner territory=\"sea zone 2\" owner=\"Russians\"/>"
                      + "<territoryOwner territory=\"sea zone 3\" owner=\"Italians\"/>")
              .replace(
                  "<propertyList>",
                  "<propertyList><property name=\"Multiply PUs\" value=\"2\" editable=\"false\">"
                      + "<number min=\"1\" max=\"10\"/></property>");
    }
    final Path file = folder.resolve("convoys.xml");
    Files.writeString(file, xml);
    final GameData data = GameParser.parse(file, false).orElseThrow();
    final Game game;
    try (InputStream read = Files.newInputStream(file)) {
      game = new org.triplea.generic.xml.reader.XmlMapper(read).mapXmlToObject(Game.class);
    }

    // when
    final NationsBalance balance = NationsBalance.of(game);

    // then
    for (final String nation : List.of("Russians", "Italians")) {
      assertThat(rowOf(balance.nations(), nation).income())
          .as("%s: as TripleA's Players tab says", nation)
          .isEqualTo(
              (int)
                  new ProductionStat()
                      .getValue(data.getPlayerList().getPlayerId(nation), data, null));
    }
    assertThat(rowOf(balance.nations(), "Russians").income())
        .as("given: the zones and routes make a difference")
        .isNotEqualTo(
            2
                * MapXml.ownersByTerritory(game).entrySet().stream()
                    .filter(e -> "Russians".equals(e.getValue()))
                    .mapToInt(e -> MapXml.production(game, e.getKey()))
                    .sum());
  }

  @DisplayName("Units are worth what TripleA's own calculator says, so the TUV is TripleA's")
  @Test
  void valuesUnitsAsTripleaDoes() throws Exception {
    // given
    final GameData data = givenG40Loaded();
    final Document xml = givenG40Xml();
    final Game game = GameRulesXmlTest.givenG40();

    // when
    final NationsBalance balance = NationsBalance.of(game);

    // then
    for (final String nation : List.of("Germans", "Russians", "Chinese")) {
      final IntegerMap<UnitType> costs = givenTripleaCosts(data, nation);
      for (final String unit : List.of("infantry", "armour", "fighter", "battleship", "factory_major")) {
        assertThat(NationsBalance.unitCost(game, nation, unit))
            .as("%s: what one %s is worth", nation, unit)
            .contains(costs.getInt(data.getUnitTypeList().getUnitTypeOrThrow(unit)));
      }
      final int tuv =
          elements(xml, "unitPlacement").stream()
                  .filter(u -> nation.equals(u.getAttribute("owner")))
                  .mapToInt(
                      u ->
                          Integer.parseInt(u.getAttribute("quantity"))
                              * costs.getInt(
                                  data.getUnitTypeList()
                                      .getUnitTypeOrThrow(u.getAttribute("unitType"))))
                  .sum()
              + elements(xml, "heldUnits").stream()
                  .filter(u -> nation.equals(u.getAttribute("player")))
                  .mapToInt(
                      u ->
                          Integer.parseInt(u.getAttribute("quantity"))
                              * costs.getInt(
                                  data.getUnitTypeList()
                                      .getUnitTypeOrThrow(u.getAttribute("unitType"))))
                  .sum();
      assertThat(rowOf(balance.nations(), nation).tuv())
          .as("%s: the value of its units at the start", nation)
          .isEqualTo(tuv)
          .isPositive();
    }
    assertThat(balance.withoutCost()).as("every unit of G40 has a cost").isEmpty();
  }

  @DisplayName("An alliance's row is the sum of its nations")
  @Test
  void sumsAlliances() {
    // given
    final Game game = GameRulesXmlTest.givenG40();

    // when
    final NationsBalance balance = NationsBalance.of(game);

    // then
    assertThat(balance.alliances().stream().map(Row::name).toList())
        .as("a row per alliance")
        .isEqualTo(MapXml.allAlliances(game));
    for (final Row alliance : balance.alliances()) {
      final List<Row> members =
          balance.nations().stream().filter(r -> r.alliances().contains(alliance.name())).toList();
      assertThat(alliance.alliances())
          .as("%s: its nations", alliance.name())
          .isEqualTo(members.stream().map(Row::name).toList())
          .isNotEmpty();
      assertThat(List.of(alliance.territories(), alliance.income(), alliance.units(), alliance.tuv()))
          .as("%s: territories, income, units and TUV summed", alliance.name())
          .containsExactly(
              members.stream().mapToInt(Row::territories).sum(),
              members.stream().mapToInt(Row::income).sum(),
              members.stream().mapToInt(Row::units).sum(),
              members.stream().mapToInt(Row::tuv).sum());
    }
  }

  @DisplayName("A unit type no rule sells counts 0 and is told")
  @Test
  void tellsUnitsWithoutCost() {
    // given the minimap without a rule that sells infantry
    final Game minimap = MapXmlTest.givenMinimap();
    final Game unsold =
        Builders.toBuilder(minimap)
            .production(
                Builders.toBuilder(minimap.getProduction())
                    .productionRules(
                        minimap.getProduction().getProductionRules().stream()
                            .filter(
                                rule ->
                                    rule.getResults().stream()
                                        .noneMatch(r -> "infantry".equals(r.getResourceOrUnit())))
                            .toList())
                    .build())
            .build();

    // when
    final NationsBalance with = NationsBalance.of(minimap);
    final NationsBalance without = NationsBalance.of(unsold);

    // then
    assertThat(with.withoutCost()).as("sold, infantry has its cost").isEmpty();
    assertThat(without.withoutCost()).as("not sold, it is told").containsExactly("infantry");
    assertThat(rowOf(without.nations(), "Russians").tuv())
        .as("and counts nothing")
        .isLessThan(rowOf(with.nations(), "Russians").tuv());
    assertThat(rowOf(without.nations(), "Russians").units())
        .as("but is still counted as a unit")
        .isEqualTo(rowOf(with.nations(), "Russians").units());
  }
}
