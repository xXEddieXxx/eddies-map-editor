package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.swing.JComboBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;

/** A nation that buys what another buys, and a purchase list copied from another. */
final class CopyPurchaseListTest {
  @TempDir Path folder;

  private MapProject givenG40Project() throws Exception {
    final Path xml = folder.resolve("ww2global40.xml");
    try (InputStream original =
        CopyPurchaseListTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  @DisplayName("A nation added to buy what the Chinese buy has a list of its own with their rules")
  @Test
  void addsANationBuyingWhatAnotherBuys() throws Exception {
    // given: the Chinese buy from a list of their own, the Germans repair from one
    final MapProject project = givenG40Project();
    final Game before = project.game();
    final String chinese = ProductionXml.frontierOf(before, "Chinese").orElseThrow();
    final Game repairsBefore = ProductionXml.repairView(before);

    // when
    final boolean added = project.addNation("Tibetans", Optional.of("Chinese"));

    // then
    final Game game = project.game();
    final String own = ProductionXml.frontierOf(game, "Tibetans").orElseThrow();
    assertThat(added).as("added").isTrue();
    assertThat(own)
        .as("a list of its own, named after it")
        .isEqualTo("productionTibetans")
        .isNotEqualTo(chinese);
    assertThat(ProductionXml.rulesIn(game, own))
        .as("with the Chinese list's rules, in its order")
        .isEqualTo(ProductionXml.rulesIn(before, chinese))
        .isNotEmpty();
    assertThat(ProductionXml.frontierOf(ProductionXml.repairView(game), "Tibetans").isPresent())
        .as("a repair list only if the Chinese have one")
        .isEqualTo(ProductionXml.frontierOf(repairsBefore, "Chinese").isPresent());

    // when the copy gets a rule more
    final String extra =
        ProductionXml.rules(game).stream()
            .map(rule -> rule.getName())
            .filter(rule -> !ProductionXml.rulesIn(game, own).contains(rule))
            .findFirst()
            .orElseThrow();
    project.edit(g -> ProductionXml.withRuleIn(g, own, extra));

    // then
    assertThat(ProductionXml.rulesIn(project.game(), chinese))
        .as("the Chinese list stays as it was: the copy is a list of its own")
        .isEqualTo(ProductionXml.rulesIn(before, chinese));
    project.save();
    assertThat(MapChecks.findError(project.getXmlFile().orElseThrow()))
        .as("TripleA loads the game with the new nation")
        .isEmpty();

    // when
    project.undo();
    project.undo();

    // then
    assertThat(project.game()).as("the nation with its list was one step").isSameAs(before);
  }

  @DisplayName("A nation added to buy what one with a repair list buys repairs like it too")
  @Test
  void copiesTheRepairList() throws Exception {
    // given
    final MapProject project = givenG40Project();
    final Game repairs = ProductionXml.repairView(project.game());
    final String like =
        MapXml.nations(project.game()).stream()
            .filter(n -> ProductionXml.frontierOf(repairs, n).isPresent())
            .findFirst()
            .orElseThrow();

    // when
    project.addNation("Tibetans", Optional.of(like));

    // then
    final Game now = ProductionXml.repairView(project.game());
    assertThat(ProductionXml.frontierOf(now, "Tibetans"))
        .as("a repair list of its own")
        .contains("repairTibetans");
    assertThat(ProductionXml.rulesIn(now, "repairTibetans"))
        .as("with the same repair rules")
        .isEqualTo(
            ProductionXml.rulesIn(repairs, ProductionXml.frontierOf(repairs, like).orElseThrow()))
        .isNotEmpty();
    assertThat(ProductionXml.frontierOf(project.game(), "Tibetans"))
        .as("and its purchase list")
        .contains("productionTibetans");
  }

  @DisplayName("Without a nation to buy like, a new nation shares the game's list, as before")
  @Test
  void addsANationAsBefore() throws Exception {
    // given
    final MapProject project = givenG40Project();
    final List<String> lists = ProductionXml.frontiers(project.game());
    final JComboBox<String> box = NationsEditor.newBuysLikeBox(project.game());

    // when
    project.addNation("Tibetans", NationsEditor.buysLikeChosen(box));

    // then
    assertThat(box.getItemAt(0)).as("the box starts with no nation").isEqualTo("—");
    assertThat(box.getItemCount()).as("then the nations that buy").isGreaterThan(5);
    assertThat(ProductionXml.frontiers(project.game())).as("no new list").isEqualTo(lists);
    assertThat(ProductionXml.frontierOf(project.game(), "Tibetans"))
        .as("it buys from the shared list")
        .contains("production");
  }

  @DisplayName("Copy from replaces a list's rules with another's, as one step")
  @Test
  void copiesRulesFromAnotherList() throws Exception {
    // given
    final MapProject project = givenG40Project();
    final Game before = project.game();
    final String chinese = ProductionXml.frontierOf(before, "Chinese").orElseThrow();
    final List<String> shared = ProductionXml.rulesIn(before, "production");

    // when
    project.edit(g -> ProductionXml.withRulesCopied(g, "production", chinese));

    // then
    assertThat(ProductionXml.rulesIn(before, chinese)).as("the lists differed").isNotEqualTo(shared);
    assertThat(ProductionXml.rulesIn(project.game(), chinese))
        .as("the Chinese list sells what the shared list sells")
        .isEqualTo(shared);
    assertThat(ProductionXml.rulesIn(project.game(), "production"))
        .as("the list copied from is unchanged")
        .isEqualTo(shared);
    assertThat(ProductionXml.rules(project.game()))
        .as("no rule is lost from the game")
        .hasSameSizeAs(ProductionXml.rules(before));
    assertThat(ProductionXml.withRulesCopied(project.game(), "production", chinese))
        .as("copied again nothing changes, so there is no step")
        .isSameAs(project.game());

    // when
    project.undo();

    // then
    assertThat(project.game()).as("one undo").isSameAs(before);
  }
}
