package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.ProductionTabs.Tab;

final class ProductionTabsTest {
  // as real maps write the file, eg Civil War: comments, a line per key, CRLF
  private static final String FILE =
      """
      # use the default tabs of tripleA which are: All, Land, Air, Sea, Construction\r
      production_tabs.use_default_tabs=false\r
      \r
      # how many tabs should there be\r
      production_tabs.number_of_tabs=2\r
      production_tabs.rows=2\r
      production_tabs.columns=5\r
      \r
      # Tab 1: Land\r
      production_tabs.tab_name.1=Land\r
      production_tabs.tab_units.1=infantry:armour:\r
      # Tab 2: Sea, with a letter beyond ASCII\r
      production_tabs.tab_name.2=Sea \\u00FC\r
      production_tabs.tab_units.2=transport:battleship\r
      future.key=kept\r
      """;

  @TempDir Path folder;

  private static <T> List<T> find(final Component in, final Class<T> type) {
    final List<T> found = new ArrayList<>();
    if (type.isInstance(in)) {
      found.add(type.cast(in));
    }
    if (in instanceof Container container) {
      for (final Component child : container.getComponents()) {
        found.addAll(find(child, type));
      }
    }
    return found;
  }

  /** The minimap in a folder of its own, with the file above as its tabs for all nations. */
  private MapProject givenMapWithTabs() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (var original = ProductionTabsTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    Files.writeString(folder.resolve(ProductionTabs.FILE), FILE, StandardCharsets.ISO_8859_1);
    return MapProject.open(xml);
  }

  @DisplayName("A production_tabs file reads as tabs with names and what they sell, rows and columns")
  @Test
  void readsTheFile() {
    final ProductionTabs tabs = ProductionTabs.read(FILE);

    assertThat(tabs.standard()).as("the map's own tabs").isFalse();
    assertThat(List.of(tabs.rows(), tabs.columns())).as("rows x columns").containsExactly(2, 5);
    assertThat(tabs.tabs())
        .as("as many tabs as number_of_tabs says; an empty entry at the end is none")
        .containsExactly(
            new Tab("Land", List.of("infantry", "armour")),
            new Tab("Sea \u00FC", List.of("transport", "battleship")));
    assertThat(ProductionTabs.read(""))
        .as("no file: TripleA's standard tabs, its own rows and columns")
        .isEqualTo(new ProductionTabs(true, 0, 0, List.of()));
  }

  @DisplayName("A changed tab changes its line only; comments, other keys and line ends stay")
  @Test
  void writesOnlyWhatChanged() {
    final ProductionTabs tabs = ProductionTabs.read(FILE);
    final List<Tab> renamed = List.of(new Tab("Army", tabs.tabs().get(0).units()), tabs.tabs().get(1));

    final String same = tabs.writeInto(FILE);
    final String written = tabs.withTabs(renamed).writeInto(FILE);
    final String one = tabs.withTabs(renamed.subList(0, 1)).writeInto(FILE);
    final String fresh =
        new ProductionTabs(false, 0, 0, List.of(new Tab("All", List.of("infantry", "PUs"))))
            .writeInto("");

    assertThat(same).as("nothing changed: the text as it was, also the units' odd end").isEqualTo(FILE);
    assertThat(written)
        .as("only the name's line differs")
        .isEqualTo(FILE.replace("tab_name.1=Land", "tab_name.1=Army"));
    assertThat(one)
        .as("the tab beyond the last goes with both its lines, its comment stays")
        .doesNotContain("tab_name.2", "tab_units.2")
        .contains("production_tabs.number_of_tabs=1\r\n", "# Tab 2: Sea", "future.key=kept");
    assertThat(ProductionTabs.read(fresh))
        .as("a new file reads back the same")
        .isEqualTo(
            new ProductionTabs(false, 0, 0, List.of(new Tab("All", List.of("infantry", "PUs")))));
    assertThat(fresh)
        .as("rows and columns that TripleA works out are not written")
        .doesNotContain("rows", "columns");
  }

  @DisplayName("A nation's tabs are its own file's, with what the file for all says over them")
  @Test
  void theFileForAllCountsFirst() {
    final MapTexts texts =
        MapTexts.none()
            .withBuyTabs(
                ProductionTabs.fileOf("Russians"),
                "production_tabs.use_default_tabs=false\nproduction_tabs.number_of_tabs=1\n"
                    + "production_tabs.tab_name.1=Mine\nproduction_tabs.tab_units.1=infantry\n"
                    + "production_tabs.rows=4\n");

    final ProductionTabs alone = ProductionTabs.of(texts, "Russians");
    final ProductionTabs both =
        ProductionTabs.of(
            texts.withBuyTabs(ProductionTabs.FILE, "production_tabs.rows=2\n"), "Russians");

    assertThat(alone.tabs()).as("its own tab").containsExactly(new Tab("Mine", List.of("infantry")));
    assertThat(ProductionTabs.of(texts, "Italians").standard())
        .as("another nation has TripleA's tabs")
        .isTrue();
    assertThat(List.of(both.rows(), both.tabs().size()))
        .as("as TripleA reads: the file for all over the nation's, key by key")
        .containsExactly(2, 1);
  }

  @DisplayName("Own tabs that leave out what a nation can buy are a problem of Production")
  @Test
  void tellsWhatIsInNoTab() throws Exception {
    final MapProject project = givenMapWithTabs();
    final Game game = project.game();

    final List<Problem> problems =
        project.problems().stream().filter(p -> "Production".equals(p.fixedIn())).toList();
    final List<String> missing = ProductionTabs.read(FILE).missing(ProductionTabs.sold(game));

    assertThat(missing)
        .as("what the two tabs do not sell")
        .contains("fighter", "factory")
        .doesNotContain("infantry", "battleship");
    assertThat(problems)
        .as("TripleA fails to open the buy window (TabbedProductionPanel.checkLists)")
        .isNotEmpty()
        .allSatisfy(p -> assertThat(p.text()).contains("cannot open the buy window", "in no tab"));
    assertThat(ProductionTabs.problems(MapTexts.none(), game))
        .as("no file: TripleA's own tabs sell everything")
        .isEmpty();
  }

  @DisplayName("A tab renamed in Production is one step, saved into the file, the old as .bak")
  @Test
  void savesTheFile() throws Exception {
    // given
    final MapProject project = givenMapWithTabs();
    final ProductionEditor editor = new ProductionEditor(project);
    final JTextField name =
        find(editor.page(), JTextField.class).stream()
            .filter(f -> "Land".equals(f.getText()))
            .findFirst()
            .orElseThrow();

    // when: typed and taken by the save's commit, not by Enter
    name.setText("Army");
    editor.commit();
    project.save();

    // then
    final Path file = folder.resolve(ProductionTabs.FILE);
    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1))
        .as("the one line changed, in the file TripleA reads")
        .isEqualTo(FILE.replace("tab_name.1=Land", "tab_name.1=Army"));
    assertThat(Files.readString(folder.resolve(ProductionTabs.FILE + ".bak"), StandardCharsets.ISO_8859_1))
        .as("its first version stays")
        .isEqualTo(FILE);
    project.undo();
    assertThat(ProductionTabs.read(project.texts().buyTabs(ProductionTabs.FILE)).tabs().getFirst().name())
        .as("one step to undo")
        .isEqualTo("Land");
  }

  @DisplayName("Own tabs chosen for a map without a file start with one tab that sells everything")
  @Test
  void ownTabsStartComplete() throws Exception {
    // given: the minimap without a production_tabs file
    final MapProject project = givenMapWithTabs();
    project.edit(g -> g, t -> t.withBuyTabs(ProductionTabs.FILE, ""));
    final ProductionEditor editor = new ProductionEditor(project);
    final List<String> standardTexts =
        find(editor.page(), AbstractButton.class).stream().map(AbstractButton::getText).toList();

    // when
    find(editor.page(), JRadioButton.class).stream()
        .filter(b -> "Own tabs".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();
    project.save();

    // then
    final ProductionTabs tabs = ProductionTabs.read(project.texts().buyTabs(ProductionTabs.FILE));
    assertThat(standardTexts)
        .as("without a file it says which tabs TripleA shows")
        .anyMatch(t -> t.startsWith("TripleA's own tabs: All, Land, Air, Sea"));
    assertThat(tabs.tabs())
        .as("the buy window must open: one tab All with everything sold")
        .containsExactly(new Tab("All", ProductionTabs.sold(project.game())));
    assertThat(project.problems()).as("so no problem").noneMatch(p -> "Production".equals(p.fixedIn()));
    assertThat(folder.resolve(ProductionTabs.FILE)).as("the file is back, written on saving").exists();
    assertThat(Map.copyOf(MapTexts.read(folder).buyTabs()))
        .as("and reads back as saved")
        .isEqualTo(Map.copyOf(project.texts().buyTabs()));
  }
}
