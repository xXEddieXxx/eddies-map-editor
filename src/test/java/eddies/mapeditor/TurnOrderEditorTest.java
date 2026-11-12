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
import javax.swing.JComponent;
import javax.swing.JRadioButton;
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

  @DisplayName("A band can't be chosen: choosing it chooses the step after it")
  @Test
  void skipsBands(@TempDir final Path folder) {
    final TurnOrderEditor editor = new TurnOrderEditor(givenG40Project(folder));
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();

    table.changeSelection(0, 0, false, false);

    assertThat(table.getSelectedRow()).as("the first step, below the first band").isEqualTo(1);
  }

  @DisplayName("A band's line is above it, parting it from the group before; none above the first")
  @Test
  void bandLineAbove(@TempDir final Path folder) {
    final TurnOrderEditor editor = new TurnOrderEditor(givenG40Project(folder));
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();
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
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();
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
    final JTable table = givenAll(editor.page(), JTable.class).findFirst().orElseThrow();
    final int row =
        IntStream.range(0, table.getRowCount())
            .filter(r -> Integer.valueOf(step + 1).equals(table.getValueAt(r, 0)))
            .findFirst()
            .orElseThrow();
    table.changeSelection(row, 0, false, false);
  }
}
