package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.border.CompoundBorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import eddies.mapeditor.TurnOrderEditor.Row;

final class TurnOrderEditorTest {
  private static MapProject givenG40Project(final Path folder) {
    return new MapProject(folder.resolve("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
  }

  private static int givenIndex(final Game game, final String name) {
    final List<String> names = RulesXml.steps(game).stream().map(Step::getName).toList();
    return names.indexOf(name);
  }

  private static <T> Stream<T> givenAll(final Component component, final Class<T> type) {
    final Stream<T> self =
        type.isInstance(component) ? Stream.of(type.cast(component)) : Stream.empty();
    return component instanceof final Container container
        ? Stream.concat(
            self,
            Stream.of(container.getComponents()).flatMap(child -> givenAll(child, type)))
        : self;
  }

  private static JRadioButton givenRadio(final TurnOrderEditor editor, final String text) {
    return givenAll(editor.page(), JRadioButton.class)
        .filter(b -> text.equals(b.getText()))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("The first round's number is the game's round offset plus one, a change one step")
  @Test
  void setsFirstRound(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final TurnOrderEditor editor = new TurnOrderEditor(project);
    final JSpinner firstRound = givenAll(editor.page(), JSpinner.class).findFirst().orElseThrow();

    firstRound.setValue(66);
    final int written = RulesXml.roundOffset(project.game());
    project.undo();

    assertThat(written).as("round 66 first: an offset of 65").isEqualTo(65);
    assertThat(RulesXml.roundOffset(project.game())).as("one step to undo").isZero();
    assertThat(firstRound.getValue()).as("the field follows the undo").isEqualTo(1);
    assertThat(project.canUndo()).as("following it made no step of its own").isFalse();
  }

  @DisplayName("G40's steps go in bands: game start, bids, each nation, end of round")
  @Test
  void bandsTheSteps() {
    final Game g40 = GameRulesXmlTest.givenG40();

    final List<Row> rows = TurnOrderEditor.rows(g40);

    final List<String> bands =
        rows.stream().filter(r -> r.step().isEmpty()).map(Row::band).toList();
    assertThat(bands.getFirst()).as("the step before any nation's").isEqualTo("Game start");
    assertThat(bands.get(1)).as("all nations' bids in one band").isEqualTo("Bids");
    assertThat(bands).as("a nation's turn").contains("Germans");
    assertThat(bands.getLast()).as("the step after the last nation's").isEqualTo("End of round");
    assertThat(rows.stream().map(Row::step).flatMap(Optional::stream).toList())
        .as("every step once, in order; a band is no step")
        .isEqualTo(
            IntStream.range(0, RulesXml.steps(g40).size()).boxed().toList());
  }

  /** Opens every band: a long round, as G40's, starts with its bands folded. */
  private static JTable givenUnfolded(final TurnOrderEditor editor) {
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();
    for (int row = 0; row < table.getRowCount(); row++) {
      if (String.valueOf(table.getValueAt(row, 1)).startsWith("▸ ")) {
        table.changeSelection(row, 1, false, false);
        table.getActionMap().get("fold").actionPerformed(null);
      }
    }
    table.clearSelection();
    return table;
  }

  private static List<String> givenHeaders(final JTable table) {
    return IntStream.range(0, table.getRowCount())
        .filter(row -> "".equals(table.getValueAt(row, 0)))
        .mapToObj(row -> String.valueOf(table.getValueAt(row, 1)))
        .toList();
  }

  @DisplayName("A round of 40 steps or more starts as its bands, folded: ▸ Bids (n); one opens")
  @Test
  void longRoundStartsFolded(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final TurnOrderEditor editor = new TurnOrderEditor(project);
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();
    final int bands = table.getRowCount();
    final List<String> folded = givenHeaders(table);
    final int germans = folded.indexOf("▸ Germans (9)");

    table.changeSelection(germans, 1, false, false);
    table.getActionMap().get("fold").actionPerformed(null);

    assertThat(folded)
        .as("only the bands' headers, each with how many steps it holds")
        .hasSize(bands)
        .allMatch(header -> header.matches("▸ .+ \\(\\d+\\)"))
        .contains("▸ Bids (18)");
    assertThat(germans).as("G40's Germans take 9 steps").isNotNegative();
    assertThat(table.getValueAt(germans, 1)).as("open").isEqualTo("▾ Germans (9)");
    assertThat(table.getRowCount()).as("its 9 steps show below it").isEqualTo(bands + 9);
    assertThat(project.canUndo()).as("folding changes nothing in the map").isFalse();
  }

  @DisplayName("The filters show one nation's steps, or one kind's, of every band, also folded ones")
  @Test
  void filtersSteps(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final TurnOrderEditor editor = new TurnOrderEditor(project);
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();
    final List<JComboBox<?>> filters =
        givenAll(editor.page(), JComboBox.class)
            .filter(c -> List.of("All nations", "All kinds").contains(c.getItemAt(0)))
            .<JComboBox<?>>map(c -> c)
            .toList();

    filters.get(1).setSelectedItem("Buy units");
    final List<Object> buying =
        IntStream.range(0, table.getRowCount())
            .filter(row -> !"".equals(table.getValueAt(row, 0)))
            .mapToObj(row -> table.getValueAt(row, 1))
            .toList();
    filters.get(0).setSelectedItem("Germans");

    assertThat(buying)
        .as("only the steps where units are bought, of each nation's band")
        .isNotEmpty()
        .allMatch(what -> String.valueOf(what).startsWith("Buy units"));
    assertThat(givenHeaders(table)).as("then only the Germans' one").containsExactly("▾ Germans (1)");
    assertThat(givenAll(editor.page(), JButton.class).filter(b -> "▲".equals(b.getText())))
        .as("no moving among filtered steps")
        .allMatch(b -> !b.isEnabled());
  }

  @DisplayName("With a band chosen ▲ moves it before the band above; a new step goes at its end")
  @Test
  void movesBandUpAndAddsAtItsEnd(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final TurnOrderEditor editor = new TurnOrderEditor(project);
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();
    final List<String> before = givenHeaders(table);
    final int germans = before.indexOf("▸ Germans (9)");
    final int first = givenIndex(project.game(), "germansPolitics");

    table.changeSelection(germans, 1, false, false);
    final int addsAfter = editor.stepBeforeNew();
    givenAll(editor.page(), JButton.class)
        .filter(b -> "▲".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();
    final List<String> after = givenHeaders(table);
    final int moved = givenIndex(project.game(), "germansPolitics");
    project.undo();

    assertThat(addsAfter)
        .as("a new step goes after the band's last step, not at the round's end")
        .isEqualTo(first + 8);
    assertThat(after.indexOf("▸ Germans (9)")).as("one band further up, still folded").isEqualTo(germans - 1);
    assertThat(after.get(germans)).as("the band above took its place").isEqualTo(before.get(germans - 1));
    assertThat(moved).as("its steps come earlier in the round").isLessThan(first);
    assertThat(givenIndex(project.game(), "germansPolitics")).as("one undo for the band").isEqualTo(first);
  }

  @DisplayName("With a band chosen ▼ moves all its steps behind the next band, as one undo step")
  @Test
  void movesWholeBand(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final TurnOrderEditor editor = new TurnOrderEditor(project);
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();
    final List<String> before = givenHeaders(table);
    final int germans = before.indexOf("▸ Germans (9)");
    final List<String> names =
        RulesXml.steps(project.game()).stream().map(Step::getName).toList();

    table.changeSelection(germans, 1, false, false);
    givenAll(editor.page(), JButton.class)
        .filter(b -> "▼".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();
    final List<String> after = givenHeaders(table);
    final Game moved = project.game();
    project.undo();

    assertThat(after.indexOf("▸ Germans (9)"))
        .as("the band is one further down, still folded")
        .isEqualTo(germans + 1);
    assertThat(after.get(germans)).as("the band after it took its place").isEqualTo(before.get(germans + 1));
    assertThat(table.getSelectedRow()).as("and stays chosen, for the next move").isEqualTo(germans + 1);
    assertThat(RulesXml.steps(moved).stream().map(Step::getName).toList())
        .as("the same steps in another order")
        .containsExactlyInAnyOrderElementsOf(names)
        .isNotEqualTo(names);
    assertThat(RulesXml.steps(project.game()).stream().map(Step::getName).toList())
        .as("one undo brings the whole band back")
        .isEqualTo(names);
    assertThat(RulesXmlTest.givenLoaded(moved, folder.resolve("moved")).getSequence())
        .as("TripleA loads the game with the band moved")
        .isNotNull();
  }

  @DisplayName("A band's line is above it, parting it from the group before; none above the first")
  @Test
  void bandLineAbove(@TempDir final Path folder) {
    final TurnOrderEditor editor = new TurnOrderEditor(givenG40Project(folder));
    final JTable table = givenUnfolded(editor);
    final List<Row> rows = TurnOrderEditor.rows(GameRulesXmlTest.givenG40());
    final int second =
        IntStream.range(1, rows.size()).filter(i -> rows.get(i).step().isEmpty()).findFirst()
            .orElseThrow();

    final Insets first = givenCellInsets(table, 0);
    final Insets later = givenCellInsets(table, second);

    assertThat(later.top).as("a line above a later band").isEqualTo(1);
    assertThat(later.bottom).as("none below it").isZero();
    assertThat(first.top).as("the first band starts the list, no line").isZero();
  }

  private static Insets givenCellInsets(final JTable table, final int row) {
    final JComponent cell =
        (JComponent) table.prepareRenderer(table.getCellRenderer(row, 1), row, 1);
    return ((CompoundBorder) cell.getBorder()).getOutsideBorder().getBorderInsets(cell);
  }

  @DisplayName("'Only in the first n rounds' writes maxRunCount, 'Every round' removes it")
  @Test
  void setsRuns(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final TurnOrderEditor editor = new TurnOrderEditor(project);
    final int purchase = givenIndex(project.game(), "germansPurchase");
    final JTable table = givenUnfolded(editor);
    final int row =
        IntStream.range(0, table.getRowCount())
            .filter(r -> Integer.valueOf(purchase + 1).equals(table.getValueAt(r, 0)))
            .findFirst()
            .orElseThrow();
    table.changeSelection(row, 0, false, false);

    givenRadio(editor, "Only in the first").doClick();
    final Game once = project.game();
    givenRadio(editor, "Every round").doClick();

    assertThat(RulesXml.steps(once).get(purchase).getMaxRunCount())
        .as("only in the first round")
        .isEqualTo(1);
    assertThat(RulesXml.steps(project.game()).get(purchase).getMaxRunCount())
        .as("every round: no count, as TripleA reads none")
        .isNull();
    assertThat(RulesXmlTest.givenLoaded(once, folder.resolve("once")).getSequence())
        .as("loads with the count")
        .isNotNull();
  }

  @DisplayName("A name typed just before is taken by commit, as before undo, a save or another tool")
  @Test
  void commitTakesTextTypedJustBefore(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final TurnOrderEditor editor = new TurnOrderEditor(project);
    final int purchase = givenIndex(project.game(), "germansPurchase");
    givenChosen(editor, purchase);
    final JTextField shown =
        givenAll(editor.page(), JTextField.class)
            .filter(f -> "Name in game".equals(f.getAccessibleContext().getAccessibleName()))
            .findFirst()
            .orElseThrow();

    shown.setText("German shopping");
    editor.commit();

    assertThat(RulesXml.steps(project.game()).get(purchase).getDisplay())
        .as("the typed name is written at once, not lost to a timer that runs later")
        .isEqualTo("German shopping");
  }

  private static void givenChosen(final TurnOrderEditor editor, final int step) {
    final JTable table = givenUnfolded(editor);
    final int row =
        IntStream.range(0, table.getRowCount())
            .filter(r -> Integer.valueOf(step + 1).equals(table.getValueAt(r, 0)))
            .findFirst()
            .orElseThrow();
    table.changeSelection(row, 0, false, false);
  }
}
