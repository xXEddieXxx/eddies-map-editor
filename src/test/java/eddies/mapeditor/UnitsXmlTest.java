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
}
