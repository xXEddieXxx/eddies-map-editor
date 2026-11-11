package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.CanalAttachment;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.CanalXml.Canal;

final class CanalXmlTest {
  private static final String SUEZ = "Suez Canal";
  private static final Canal NEW =
      new Canal(
          "Bering Strait",
          List.of("1 Sea Zone", "2 Sea Zone"),
          List.of("Alaska"),
          List.of("submarine"));
  private final Game global = givenGlobal();

  /** TripleA's World War II Global 1940, with its canals, eg Suez. */
  static Game givenGlobal() {
    try (InputStream xml =
        CanalXmlTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      return new XmlMapper(xml).mapXmlToObject(Game.class);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Canal givenSuez() {
    return CanalXml.canals(global).stream()
        .filter(c -> SUEZ.equals(c.name()))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("A game's canals are read by their name, with sea zones and land territories")
  @Test
  void readsCanals() {
    final Canal suez = givenSuez();

    assertThat(suez.seaZones()).as("both").containsExactly("81 Sea Zone", "98 Sea Zone");
    assertThat(suez.lands()).as("its banks").containsExactly("Egypt", "Trans-Jordan");
    assertThat(suez.excludedUnits()).as("the game's default").isEmpty();
    assertThat(CanalXml.passFreely(global, suez))
        .as("the air units by default")
        .contains("fighter", "bomber")
        .doesNotContain("submarine");
  }

  @DisplayName("A canal of two sea zones and a land territory is written on both sea zones")
  @Test
  void writesANewCanal() {
    final Game changed = CanalXml.withCanal(global, NEW.name(), NEW);

    assertThat(CanalXml.canals(changed)).as("read back as written").contains(NEW);
    assertThat(
            AttachmentsXml.attachments(changed).stream()
                .filter(a -> "CanalAttachment".equals(AttachmentsXml.kind(a)))
                .filter(a -> NEW.seaZones().contains(a.getAttachTo()))
                .map(Attachment::getName)
                .distinct())
        .as("one new name for both, as maps have it")
        .hasSize(1)
        .allMatch(name -> name.startsWith("canalAttachment"));
    assertThat(CanalXml.canals(global)).as("the game it started from stays").doesNotContain(NEW);
  }

  @DisplayName("A canal is renamed, moved to another sea zone and removed")
  @Test
  void changesACanal() {
    final Canal suez = givenSuez();
    final Canal moved =
        new Canal(
            "Suez", List.of("81 Sea Zone", "80 Sea Zone"), suez.lands(), List.of("NONE"));

    final Game changed = CanalXml.withCanal(global, SUEZ, moved);
    final Game removed = CanalXml.withoutCanal(changed, "Suez");

    assertThat(CanalXml.canals(changed)).as("renamed and moved").contains(moved);
    assertThat(CanalXml.canals(changed)).extracting(Canal::name).doesNotContain(SUEZ);
    assertThat(CanalXml.canals(removed)).extracting(Canal::name).doesNotContain("Suez");
    assertThat(CanalXml.canals(removed)).as("the others stay").hasSize(4);
    assertThat(CanalXml.withCanal(changed, "Suez", moved)).as("no change").isSameAs(changed);
  }

  @DisplayName("What passes freely stays as the map has it, eg ALL, until it is chosen anew")
  @Test
  void keepsWhatPassesFreely() {
    final Canal all = new Canal(NEW.name(), NEW.seaZones(), NEW.lands(), List.of("ALL"));
    final Game withAll = CanalXml.withCanal(global, NEW.name(), all);
    final Canal moreLand =
        new Canal(all.name(), all.seaZones(), List.of("Alaska", "Yukon Territory"), List.of("ALL"));

    final Game changed = CanalXml.withCanal(withAll, all.name(), moreLand);

    assertThat(CanalXml.canals(changed)).as("ALL stays ALL").contains(moreLand);
    assertThat(CanalXml.passFreely(changed, moreLand))
        .as("all unit types")
        .isEqualTo(MapXml.unitTypes(changed));
    assertThat(CanalXml.excludedUnits(global, CanalXml.passFreely(global, givenSuez())))
        .as("the air units chosen: the default, no option")
        .isEmpty();
    assertThat(CanalXml.excludedUnits(global, List.of())).as("none").containsExactly("NONE");
  }

  @DisplayName("A canal the game would refuse, eg of one sea zone without land, is a problem")
  @Test
  void unfinishedCanalIsAProblem() {
    final Canal half = new Canal("Half", List.of("1 Sea Zone"), List.of(), List.of());

    final Game changed = CanalXml.withCanal(global, half.name(), half);

    assertThat(MapChecks.quick(changed, MapFiles.none()))
        .as("shown at its sea zone")
        .anyMatch(
            p -> p.text().contains("Half") && p.territory().equals(Optional.of("1 Sea Zone")));
    assertThat(MapChecks.quick(global, MapFiles.none()))
        .as("G40's canals are whole")
        .noneMatch(p -> p.text().contains("canal"));
  }

  @DisplayName("A game with a new canal still loads in TripleA, the canal on both sea zones")
  @Test
  void newCanalLoads(@TempDir final Path folder) {
    final GameData data =
        RulesXmlTest.givenLoaded(CanalXml.withCanal(global, NEW.name(), NEW), folder);

    for (final String seaZone : NEW.seaZones()) {
      final CanalAttachment canal =
          CanalAttachment.get(data.getMap().getTerritoryOrNull(seaZone)).stream()
              .filter(c -> NEW.name().equals(c.getCanalName()))
              .findFirst()
              .orElseThrow();
      assertThat(canal.getLandTerritories())
          .extracting(Territory::getName)
          .as("its land")
          .containsExactly("Alaska");
      assertThat(canal.getExcludedUnits())
          .extracting(UnitType::getName)
          .as("only submarines pass freely")
          .containsExactly("submarine");
    }
  }
}
