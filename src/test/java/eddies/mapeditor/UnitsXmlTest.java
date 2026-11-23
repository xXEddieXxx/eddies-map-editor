package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.DynamicallyModifiable;
import games.strategy.engine.data.gameparser.XmlGameElementMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.xml.writer.GameXmlWriter;

final class UnitsXmlTest {
  @DisplayName("A unit's tool tip is the game's: TripleA's stats, the map's own text, or both")
  @Test
  void gameToolTips(@TempDir final Path folder) throws Exception {
    // given: an own text for the armour, an appended one for the artillery
    Files.writeString(
        folder.resolve("tooltips.properties"),
        "tooltip.unit.armour=<b>Tanks</b>\n"
            + "tooltip.unit.artillery.append=<br>* Supports infantry\n");
    final Game game = MapXmlTest.givenMinimap();

    // when
    final java.util.Map<String, String> tips =
        UnitsXml.gameToolTips(game, MapTexts.read(folder), java.util.Optional.empty());

    // then
    assertThat(tips.get("infantry"))
        .as("TripleA's stats: kind and attack, defense, movement")
        .contains("Land")
        .contains("1 | 2 | 1");
    assertThat(tips.get("armour")).as("the map's own text instead").isEqualTo("<b>Tanks</b>");
    assertThat(tips.get("artillery"))
        .as("an appended text after the stats")
        .contains("Land")
        .endsWith("<br>* Supports infantry");
  }

  @TempDir Path folder;

  private MapProject givenGame(final String resource) throws Exception {
    final Path xml = folder.resolve(resource);
    try (InputStream original = UnitsXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private static UnitOptions.UnitOption givenOption(final String name) {
    return UnitOptions.SECTIONS.stream()
        .flatMap(s -> s.options().stream())
        .filter(o -> name.equals(o.name()))
        .findFirst()
        .orElseThrow();
  }

  private Game saveAndReopen(final MapProject project, final String name) throws Exception {
    final Path xml = folder.resolve(name);
    GameXmlWriter.exportXml(project.game(), xml);
    assertThat(MapChecks.findError(xml)).as("TripleA loads the saved game").isEmpty();
    return MapProject.open(xml).game();
  }

  @DisplayName("Every option the Units tool shows is one TripleA's unitAttachment reads")
  @Test
  void showsOnlyOptionsTripleaKnows() {
    final DynamicallyModifiable unit =
        (DynamicallyModifiable)
            new XmlGameElementMapper()
                .newAttachment("UnitAttachment", "probe", null, null)
                .orElseThrow();

    final List<String> unknown =
        UnitOptions.NAMES.stream().filter(name -> unit.getProperty(name).isEmpty()).toList();

    assertThat(unknown).as("shown options TripleA would refuse").isEmpty();
  }

  @DisplayName("A changed option keeps the others, their order and counts, and still loads")
  @Test
  void changedOptionKeepsTheRest() throws Exception {
    final MapProject project = givenGame("ww2global40.xml");
    final List<Option> before = UnitsXml.options(project.game(), "carrier");

    project.edit(g -> UnitsXml.withValue(g, "carrier", givenOption("defense"), "3"));
    project.edit(g -> UnitsXml.withValue(g, "carrier", givenOption("isAirBase"), "true"));
    final List<Option> after = UnitsXml.options(saveAndReopen(project, "changed.xml"), "carrier");

    assertThat(after)
        .as("the same options in their order, defense changed, the new one last")
        .extracting(Option::getName)
        .containsExactlyElementsOf(
            Stream.concat(
                    before.stream().map(Option::getName),
                    Stream.of("isAirBase"))
                .toList());
    assertThat(after)
        .as("the options the tool does not show keep their counts")
        .filteredOn(o -> "whenCombatDamaged".equals(o.getName()))
        .extracting(Option::getCount)
        .containsExactly("1:2", "1:2");
    assertThat(after)
        .as("the changed value")
        .filteredOn(o -> "defense".equals(o.getName()))
        .extracting(Option::getValue)
        .containsExactly("3");
    assertThat(UnitsXml.isPlain(project.game(), "carrier", "whenCombatDamaged"))
        .as("an option given twice is changed row by row, not as one value")
        .isFalse();
  }

  @DisplayName("Every number option at the least its field allows still loads in TripleA")
  @Test
  void leastNumbersLoad() throws Exception {
    final MapProject project = givenGame("minimap.xml");
    final List<UnitOptions.UnitOption> numbers =
        UnitOptions.SECTIONS.stream()
            .flatMap(s -> s.options().stream())
            .filter(o -> o.kind() == UnitOptions.Kind.NUMBER)
            .toList();

    for (final UnitOptions.UnitOption option : numbers) {
      project.edit(
          g -> UnitsXml.withValue(g, "infantry", option, String.valueOf(option.minimum())));
    }

    saveAndReopen(project, "least.xml");
  }

  @DisplayName("An option set to its default is left out, so TripleA's default applies")
  @Test
  void defaultIsLeftOut() throws Exception {
    final Game game = givenGame("ww2global40.xml").game();

    final Game changed = UnitsXml.withValue(game, "carrier", givenOption("hitPoints"), "1");

    assertThat(UnitsXml.options(changed, "carrier"))
        .as("hit points 1 is TripleA's own")
        .extracting(Option::getName)
        .doesNotContain("hitPoints");
  }

  @DisplayName("An option among More options is found by its name after options before it went")
  @Test
  void moreOptionFoundAfterRowsMoved() throws Exception {
    final Game game = givenGame("ww2global40.xml").game();
    final Game withoutMovement = MapXml.withoutUnitOption(game, "carrier", "movement");

    final Game changed =
        UnitsXml.withOptionAt(
            withoutMovement,
            "carrier",
            "whenCombatDamaged",
            1,
            Option.builder()
                .name("whenCombatDamaged")
                .value("unitsMayNotLandOnCarrier")
                .count("1:3")
                .build());
    final Game removed =
        UnitsXml.withoutOptionAt(withoutMovement, "carrier", "whenCombatDamaged", 0);

    assertThat(UnitsXml.options(changed, "carrier"))
        .as("the second whenCombatDamaged changed, nothing else")
        .filteredOn(o -> "whenCombatDamaged".equals(o.getName()))
        .extracting(Option::getCount)
        .containsExactly("1:2", "1:3");
    assertThat(UnitsXml.options(changed, "carrier"))
        .as("the option before it, defense, is kept")
        .extracting(Option::getName)
        .contains("defense", "canBeGivenByTerritoryTo");
    assertThat(UnitsXml.options(removed, "carrier"))
        .as("only the first whenCombatDamaged removed")
        .filteredOn(o -> "whenCombatDamaged".equals(o.getName()))
        .extracting(Option::getValue)
        .containsExactly("unitsMayNotLeaveAlliedCarrier");
  }

  @DisplayName("An old option, eg isTwoHit, is told as what sets the new ones")
  @Test
  void oldOptionSetsNewOnes() throws Exception {
    final Game game =
        UnitsXml.withNewOption(
            givenGame("ww2global40.xml").game(),
            "infantry",
            Option.builder().name("isTwoHit").value("true").build());

    assertThat(UnitsXml.setBy(game, "infantry", "hitPoints"))
        .as("hit points come from isTwoHit")
        .contains("isTwoHit");
    assertThat(UnitsXml.setBy(game, "infantry", "attack")).as("attack does not").isEmpty();
  }

  @DisplayName("TripleA's unit option names spelt otherwise than their setters are offered")
  @Test
  void knownOptionsHaveAntiAirNames() throws Exception {
    final Game game = givenGame("minimap.xml").game();

    assertThat(AttachmentsXml.knownOptions(game, "UnitAttachment"))
        .as("anti-air names")
        .contains("attackAA", "isAAforCombatOnly", "typeAA");
  }

  @DisplayName("A new support and a changed one still load, each one step, on minimap and G40")
  @Test
  void supportStillLoads() throws Exception {
    for (final String resource : List.of("minimap.xml", "ww2global40.xml")) {
      final MapProject project = givenGame(resource);
      final String name = AttachmentsXml.newName(project.game(), UnitsXml.SUPPORT_KIND);

      project.edit(g -> UnitsXml.withNewSupport(g, "armour", name));
      project.edit(
          g -> UnitsXml.withSupportOption(g, "armour", name, "side", "offence:defence"));
      project.undo();
      final Game reopened = saveAndReopen(project, "support-" + resource);

      assertThat(UnitsXml.supports(reopened, "armour"))
          .as(resource + ": the new support")
          .contains(name);
      assertThat(UnitsXml.supportOption(reopened, "armour", name, "side"))
          .as(resource + ": undo took back only the change of side")
          .contains("offence");
      assertThat(UnitsXml.supports(UnitsXml.withoutSupport(reopened, "armour", name), "armour"))
          .as(resource + ": removed again")
          .doesNotContain(name);
    }
  }

  @DisplayName("A new support's name is one no attachment of the game has")
  @Test
  void supportNamesAreUnique() throws Exception {
    final Game game = givenGame("ww2global40.xml").game();
    final String first = AttachmentsXml.newName(game, UnitsXml.SUPPORT_KIND);

    final Game withFirst = UnitsXml.withNewSupport(game, "armour", first);
    final String second = AttachmentsXml.newName(withFirst, UnitsXml.SUPPORT_KIND);

    assertThat(second)
        .as("the second new support")
        .isNotEqualTo(first)
        .startsWith("supportAttachment");
  }

  @DisplayName("A kind sets only isSea and isAir, leaving out what is false")
  @Test
  void kindSetsOnlySeaAndAir() throws Exception {
    final Game game = givenGame("ww2global40.xml").game();
    final List<String> before = givenRows(game, "infantry");

    final Game sea = UnitsXml.withKind(game, "infantry", UnitOptions.UnitKind.SEA);
    final Game land = UnitsXml.withKind(sea, "infantry", UnitOptions.UnitKind.LAND);

    assertThat(givenRows(sea, "infantry"))
        .as("the options in their order, isSea added last, no isAir")
        .containsExactlyElementsOf(
            Stream.concat(before.stream(), Stream.of("isSea=true×null")).toList());
    assertThat(UnitsXml.kind(sea, "infantry"))
        .as("a sea unit now")
        .contains(UnitOptions.UnitKind.SEA);
    assertThat(givenRows(land, "infantry"))
        .as("land again: both left out, the rest as before")
        .containsExactlyElementsOf(before);
  }

  /** The unit's options as name=value, each with its count. */
  private static List<String> givenRows(final Game game, final String unit) {
    return UnitsXml.options(game, unit).stream()
        .map(o -> o.getName() + "=" + o.getValue() + "×" + o.getCount())
        .toList();
  }

  @DisplayName("A unit that both sails and flies has no kind")
  @Test
  void bothSeaAndAirIsNoKind() throws Exception {
    final Game game = givenGame("ww2global40.xml").game();

    final Game both = UnitsXml.withValue(game, "fighter", givenOption("isSea"), "true");

    assertThat(UnitsXml.kind(both, "fighter")).as("no kind to choose").isEmpty();
    assertThat(UnitsXml.kind(game, "fighter"))
        .as("the fighter as given")
        .contains(UnitOptions.UnitKind.AIR);
  }

  @DisplayName("The lists of the Units tool name only options its sections have")
  @Test
  void listsNameSectionOptions() {
    assertThat(UnitOptions.BLANK_WORDS.keySet())
        .as("blank words only for numbers that may be -1: by default, or as their least")
        .allSatisfy(
            name ->
                assertThat(givenOption(name))
                    .as(name)
                    .matches(
                        o ->
                            o.kind() == UnitOptions.Kind.NUMBER
                                && ("-1".equals(o.defaultValue()) || o.minimum() == -1)));
    assertThat(UnitOptions.NAMES)
        .as("the common options, the essentials and the kind flags are section options")
        .containsAll(UnitOptions.COMMON)
        .containsAll(UnitOptions.ESSENTIALS)
        .containsAll(UnitOptions.KIND_FLAGS);
    assertThat(UnitOptions.EXTRA_HINTS.keySet())
        .as("hints only for options the sections do not show")
        .doesNotContainAnyElementsOf(UnitOptions.NAMES);
  }

  @DisplayName("A unit type whose attachment is written for each element of a variable reads it")
  @Test
  void readsOptionsWrittenForEach() throws Exception {
    // given
    final Game g40 = givenGame("ww2global40.xml").game();

    // when
    final Game game = givenWalkersLoop(g40);

    // then
    assertThat(UnitsXml.value(game, "artillery", givenOption("attack")))
        .as("the attack the loop gives it, not its default 0")
        .isEqualTo("4");
    assertThat(MapXml.unitStat(game, "infantry", "hitPoints", 1))
        .as("the list's line reads the same")
        .isEqualTo(2);
    assertThat(UnitsXml.options(game, "infantry"))
        .as("the loop's attachment replaces the one written before it, as in TripleA")
        .extracting(Option::getName)
        .containsExactly("attack", "hitPoints");
    assertThat(UnitsXml.loop(game, "infantry"))
        .as("changed at the loop as written")
        .map(loop -> loop.getAttachTo())
        .contains("@Walkers@");
    assertThat(UnitsXml.loop(game, "fighter")).as("one of its own").isEmpty();
  }

  @DisplayName("A change for one unit type of a loop is not taken: it would replace the loop's")
  @Test
  void loopMadeUnitIsNotChangedAlone() throws Exception {
    // given
    final Game game = givenWalkersLoop(givenGame("ww2global40.xml").game());

    // when
    final Game changed =
        UnitsXml.withNewOption(
            UnitsXml.withoutOptionAt(
                UnitsXml.withValue(game, "infantry", givenOption("attack"), "9"),
                "infantry",
                "hitPoints",
                0),
            "infantry",
            Option.builder().name("isAir").value("true").build());

    // then
    assertThat(changed).as("the game as it was").isSameAs(game);
  }

  @DisplayName("A loop's unit type gets its own attachment with all the loop gives it, and loads")
  @Test
  void loopMadeUnitGetsItsOwnAttachment() throws Exception {
    // given
    final MapProject project = givenGame("ww2global40.xml");
    project.edit(UnitsXmlTest::givenWalkersLoop);
    final Game loop = project.game();

    // when
    project.edit(g -> UnitsXml.withOwn(g, "infantry"));
    final Game own = project.game();
    project.edit(g -> UnitsXml.withValue(g, "infantry", givenOption("attack"), "9"));
    final Game saved = saveAndReopen(project, "own.xml");

    // then
    assertThat(UnitsXml.loop(own, "infantry")).as("its own now, changed here").isEmpty();
    assertThat(UnitsXml.options(own, "infantry"))
        .as("all the loop gave it, nothing lost")
        .extracting(Option::getName, Option::getValue)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("attack", "4"),
            org.assertj.core.groups.Tuple.tuple("hitPoints", "2"));
    assertThat(AttachmentsXml.attachments(own))
        .as("the loop as written stays, one attachment more")
        .containsAll(AttachmentsXml.attachments(loop))
        .hasSize(AttachmentsXml.attachments(loop).size() + 1);
    assertThat(UnitsXml.value(saved, "infantry", givenOption("attack")))
        .as("changed like any unit, as TripleA reads the saved game")
        .isEqualTo("9");
    assertThat(UnitsXml.value(saved, "infantry", givenOption("hitPoints")))
        .as("the loop's other value stays")
        .isEqualTo("2");
    assertThat(UnitsXml.loop(saved, "artillery")).as("the loop's other unit follows it").isPresent();
    assertThat(UnitsXml.value(saved, "artillery", givenOption("attack")))
        .as("with the loop's value")
        .isEqualTo("4");
    assertThat(UnitsXml.withOwn(own, "fighter")).as("nothing for a unit no loop makes").isSameAs(own);
  }

  @DisplayName("A loop's unit type is not removed while the loop's variable names it")
  @Test
  void loopMadeUnitIsStillNamedByItsVariable() throws Exception {
    // given
    final Game game = givenWalkersLoop(givenGame("ww2global40.xml").game());

    // when
    final List<String> named =
        RulesXml.whereNamed(MapXml.withoutUnitType(game, "artillery"), "artillery");

    // then
    assertThat(named).as("what Remove unit refuses with").contains("variable Walkers");
  }

  /** G40 with a unitAttachment written for each of infantry and artillery, after their own. */
  static Game givenWalkersLoop(final Game g40) {
    return givenLoop(
        GameRulesXml.withVariable(g40, "Walkers", List.of("infantry", "artillery")),
        "$Walkers$",
        "@Walkers@",
        "4");
  }

  private static Game givenLoop(
      final Game game, final String foreach, final String attachTo, final String attack) {
    final Game added =
        AttachmentsXml.withNewAttachment(game, "UnitAttachment", "unitAttachment", attachTo);
    final int last = AttachmentsXml.attachments(added).size() - 1;
    return AttachmentsXml.withAttachment(
        added,
        last,
        Builders.toBuilder(AttachmentsXml.attachments(added).get(last))
            .foreach(foreach)
            .options(
                List.of(
                    Option.builder().name("attack").value(attack).build(),
                    Option.builder().name("hitPoints").value("2").build()))
            .build());
  }

  @DisplayName("An attachment written for the unit type after the loop counts, not the loop's")
  @Test
  void ownAttachmentAfterTheLoopCounts() throws Exception {
    // given
    final Game loop = givenWalkersLoop(givenGame("ww2global40.xml").game());
    final Game own =
        AttachmentsXml.withNewAttachment(loop, "UnitAttachment", "unitAttachment", "infantry");
    final int last = AttachmentsXml.attachments(own).size() - 1;

    // when
    final Game game =
        AttachmentsXml.withAttachment(
            own,
            last,
            Builders.toBuilder(AttachmentsXml.attachments(own).get(last))
                .options(List.of(Option.builder().name("attack").value("1").build()))
                .build());

    // then
    assertThat(UnitsXml.value(game, "infantry", givenOption("attack")))
        .as("the own one, written last, counts")
        .isEqualTo("1");
    assertThat(UnitsXml.loop(game, "infantry")).as("its own, changed here").isEmpty();
  }

  @DisplayName("Two variables side by side in a loop go element by element: $A$:$B$")
  @Test
  void readsVariablesSideBySide() throws Exception {
    // given: each walker with the attack of the same place in another variable
    final Game variables =
        GameRulesXml.withVariable(
            GameRulesXml.withVariable(
                givenGame("ww2global40.xml").game(), "Walkers", List.of("infantry", "artillery")),
            "Attacks",
            List.of("5", "6"));

    // when
    final Game game = givenLoop(variables, "$Walkers$:$Attacks$", "@Walkers@", "@Attacks@");

    // then
    assertThat(
            List.of(
                UnitsXml.value(game, "infantry", givenOption("attack")),
                UnitsXml.value(game, "artillery", givenOption("attack"))))
        .as("one attachment per place, not per pair")
        .containsExactly("5", "6");
  }

  @DisplayName("Units used up are changed in the form the map wrote, added and removed, and load")
  @Test
  void usedUpUnitsKeepTheirForm() throws Exception {
    // given: G40's factory_upgrade uses up "1:factory_minor"; artillery gets one, its count apart
    final MapProject project = givenGame("ww2global40.xml");
    project.edit(
        g ->
            UnitsXml.withNewOption(
                g,
                "artillery",
                Option.builder().name("consumesUnits").count("2").value("infantry").build()));

    // when
    project.edit(
        g -> UnitsXml.withUsedUp(g, "factory_upgrade", 0, new UnitsXml.UsedUp(2, "factory_minor")));
    project.edit(g -> UnitsXml.withNewUsedUp(g, "factory_upgrade"));
    project.edit(g -> UnitsXml.withUsedUp(g, "artillery", 0, new UnitsXml.UsedUp(3, "armour")));
    final Game saved = saveAndReopen(project, "usedUp.xml");
    final Game removed = UnitsXml.withoutOptionAt(saved, "factory_upgrade", "consumesUnits", 0);

    // then
    assertThat(UnitsXml.options(saved, "factory_upgrade"))
        .as("count:unit stays count:unit; the new one, of the first unit type not used yet, last")
        .filteredOn(o -> "consumesUnits".equals(o.getName()))
        .extracting(Option::getCount, Option::getValue)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(null, "2:factory_minor"),
            org.assertj.core.groups.Tuple.tuple(null, "1:infantry"));
    assertThat(UnitsXml.options(saved, "factory_upgrade"))
        .as("the changed one keeps its place among the options")
        .extracting(Option::getName)
        .containsSubsequence("maxDamage", "consumesUnits", "whenCapturedChangesInto");
    assertThat(UnitsXml.options(saved, "artillery"))
        .as("a count written apart stays apart")
        .filteredOn(o -> "consumesUnits".equals(o.getName()))
        .extracting(Option::getCount, Option::getValue)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("3", "armour"));
    assertThat(UnitsXml.usedUp(saved, "artillery")).as("read either way").containsExactly("3 armour");
    assertThat(UnitsXml.usedUp(removed, "factory_upgrade"))
        .as("the first removed, the second stays")
        .containsExactly("1 infantry");
  }

  @DisplayName("A consumesUnits option that does not read as a count and a unit is left as it is")
  @Test
  void unreadUsedUpStays() throws Exception {
    // given
    final Game game =
        UnitsXml.withNewOption(
            givenGame("ww2global40.xml").game(),
            "artillery",
            Option.builder().name("consumesUnits").value("some:infantry").build());

    // when
    final Game changed = UnitsXml.withUsedUp(game, "artillery", 0, new UnitsXml.UsedUp(2, "armour"));

    // then
    assertThat(changed).as("changed among the raw options, not here").isSameAs(game);
    final Game read = UnitsXml.withNewUsedUp(game, "infantry");
    assertThat(
            UnitsXml.withUsedUp(
                read, "infantry", 0, UnitsXml.UsedUp.read(UnitsXml.options(read, "infantry").getLast()).orElseThrow()))
        .as("what it says already: no step")
        .isSameAs(read);
    assertThat(UnitsXml.usedUp(game, "artillery")).as("not told as a cost").isEmpty();
    assertThat(UnitsEditor.moreOptions(game, "artillery"))
        .as("still a raw option under More options")
        .extracting(Option::getName)
        .contains("consumesUnits");
  }
}
