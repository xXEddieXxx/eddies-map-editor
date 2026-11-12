package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class DetailsTest {

  @Test
  @DisplayName("A combo column opens on one click and shows the value with ▾ beside it")
  void comboColumnOpensOnOneClick() {
    // given
    final JTable table = givenTable();

    // when
    Details.comboColumn(table, 1, new JComboBox<>(new String[] {"Allied", "War"}));

    // then
    assertThat(((DefaultCellEditor) table.getCellEditor(0, 1)).getClickCountToStart())
        .as("one click opens the list")
        .isEqualTo(1);
    final Component cell = table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1);
    assertThat(labels(cell))
        .as("the value and the arrow")
        .containsExactly("Allied", "▾");
  }

  @Test
  @DisplayName("A header shows its visual, name, line and links")
  void headerShowsAllParts() {
    // given
    final JLabel swatch = new JLabel("swatch");

    // when
    final JComponent header =
        Details.header(
            Optional.of(swatch),
            "Russians",
            new JLabel("Allies · 24 PUs"),
            List.of(new JLabel("Change color")));

    // then
    assertThat(labels(header))
        .as("everything in reading order")
        .containsExactly("swatch", "Russians", "Allies · 24 PUs", "Change color");
  }

  private static JTable givenTable() {
    return new JTable(new Object[][] {{"Germans", "Allied"}}, new Object[] {"Nation", "Relation"});
  }

  private static List<String> labels(final Component component) {
    return Stream.concat(
            component instanceof JLabel label ? Stream.of(label.getText()) : Stream.empty(),
            component instanceof Container container
                ? Stream.of(container.getComponents()).flatMap(c -> labels(c).stream())
                : Stream.empty())
        .toList();
  }
}
