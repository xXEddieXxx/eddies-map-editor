package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.ProductionEditor.RulesTable;
import eddies.mapeditor.ProductionEditor.Trade;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Production.Rule.Result;

final class ProductionEditorTest {

  private static MapProject givenMinimap(final Path folder) {
    return new MapProject(
        folder.resolve("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
  }

  /** Sized and laid out as in the window, without one. */
  private static void givenLaidOut(final Component component, final int width, final int height) {
    component.setSize(width, height);
    layOut(component);
  }

  private static void layOut(final Component component) {
    component.doLayout();
    if (component instanceof Container container) {
      for (final Component inner : container.getComponents()) {
        layOut(inner);
      }
    }
  }

  // only what shows: the other tabs are hidden
  private static Stream<Component> all(final Component component) {
    if (!component.isVisible()) {
      return Stream.empty();
    }
    return component instanceof Container container
        ? Stream.concat(
            Stream.of(component), Stream.of(container.getComponents()).flatMap(ProductionEditorTest::all))
        : Stream.of(component);
  }

  private static <T extends Component> T find(
      final Component in, final Class<T> kind, final Predicate<T> wanted) {
    return all(in)
        .filter(kind::isInstance)
        .map(kind::cast)
        .filter(wanted)
        .findFirst()
        .orElseThrow();
  }

  private static Rectangle boundsIn(final Component page, final Component part) {
    return SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), page);
  }

  @DisplayName("Resources > At the start: each column as wide as its heading and its widest number,"
      + " a number column at least five figures wide")
  @Test
  void startTableColumnsFitTheirContent(@TempDir final Path folder) {
    final MapProject project = givenMinimap(folder);
    project.edit(g -> ProductionXml.withResource(g, "Production Points Reserve"));
    project.edit(
        g ->
            MapXml.withStartingResource(
                g, MapXml.nations(g).getFirst(), "Production Points Reserve", 99999));
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(project).page();
    page.setSelectedIndex(
        IntStream.range(0, page.getTabCount())
            .filter(i -> "Resources".equals(page.getTitleAt(i)))
            .findFirst()
            .orElseThrow());
    givenLaidOut(page, 1200, 800);

    final JTable start =
        find(page, JTable.class, t -> "Nation".equals(t.getColumnName(0)));
    final java.awt.FontMetrics header =
        start.getFontMetrics(start.getTableHeader().getFont());
    final java.awt.FontMetrics cells = start.getFontMetrics(start.getFont());

    for (int i = 0; i < start.getColumnCount(); i++) {
      final int width = start.getColumnModel().getColumn(i).getWidth();
      assertThat(width)
          .as("column %s wide enough for its heading", start.getColumnName(i))
          .isGreaterThan(header.stringWidth(start.getColumnName(i)));
      if (i > 0) {
        assertThat(width).as("and for five figures").isGreaterThan(cells.stringWidth("99999"));
      }
    }
  }

  @DisplayName("Buy: the rules table reaches down to Remove list, no empty room between")
  @Test
  void rulesTableFillsTheHeight(@TempDir final Path folder) {
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(givenMinimap(folder)).page();
    givenLaidOut(page, 1200, 800);

    final Rectangle table =
        boundsIn(
            page,
            find(page, JScrollPane.class, s -> s.getViewport().getView() instanceof JTable));
    final Rectangle remove =
        boundsIn(page, find(page, JButton.class, b -> "Remove list".equals(b.getText())));

    assertThat(remove.y - (table.y + table.height))
        .as("only a small gap: Remove rule shares the row of Remove list")
        .isLessThan(30);
  }

  @DisplayName("A purchase list is called by the nations that use it; its id stays for the tool"
      + " tip, the search and the header")
  @Test
  void listsInPlainWords(@TempDir final Path folder) {
    final MapProject project =
        new MapProject(folder.resolve("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());

    final Map<String, String> titles = ProductionXml.listTitles(project.game());
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(project).page();
    givenLaidOut(page, 1200, 800);
    final List<String> labels =
        all(page).filter(JLabel.class::isInstance).map(c -> ((JLabel) c).getText()).toList();

    assertThat(titles)
        .as("one nation: its name; several: a shared list; none: the id's words")
        .containsEntry("production", "Shared list 1")
        .containsEntry("productionChinese_Burma_Road_Open", "Chinese")
        .containsEntry("productionChinese_Burma_Road_Closed", "Chinese Burma Road Closed")
        .containsEntry("productionShipyards", "Shipyards");
    assertThat(labels)
        .as("the count in plain words, the chosen list's title and its id in grey below")
        .contains("4 purchase lists", "Shared list 1", "id production");
    assertThat(all(page).filter(JButton.class::isInstance).map(c -> ((JButton) c).getText()))
        .as("the add actions as buttons saying what they add")
        .contains("+ Add list…", "+ Add rule…");
  }

  @DisplayName("The rules table has no column for a rule's id: the row's tool tip tells it")
  @Test
  void ruleIdInToolTip(@TempDir final Path folder) {
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(givenMinimap(folder)).page();
    givenLaidOut(page, 1200, 800);
    final JTable table = find(page, JTable.class, t -> true);
    final Rectangle first = table.getCellRect(0, 1, true);

    final String tip =
        table.getToolTipText(
            new MouseEvent(
                table, MouseEvent.MOUSE_MOVED, 0, 0, first.x + 4, first.y + 4, 0, false));

    assertThat(IntStream.range(0, table.getColumnCount()).mapToObj(table::getColumnName))
        .as("what it sells, how many and for what; no Rule column")
        .containsExactly("Sold", "Unit or resource", "Amount", "PUs");
    assertThat(tip).as("the rule's name in the game's XML").isEqualTo("id buyInfantry");
  }

  @DisplayName("Resources: the card of resources and the table at the start are one width")
  @Test
  void resourcesOneWidth(@TempDir final Path folder) {
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(givenMinimap(folder)).page();
    page.setSelectedIndex(2);
    givenLaidOut(page, 1200, 800);

    final Component start =
        find(page, JScrollPane.class, s -> s.getViewport().getView() instanceof JTable);
    final Component card =
        find(page, JButton.class, b -> "Rename".equals(b.getText()))
            .getParent()
            .getParent()
            .getParent()
            .getParent();

    assertThat(card).as("the card of resources").isInstanceOf(JPanel.class);
    assertThat(start.getWidth()).as("the table as wide as the card").isEqualTo(card.getWidth());
  }

  @DisplayName("Sold here: the rows are the frontier's rules, and an edit changes the row's rule")
  @Test
  void editsTheRuleOfTheRow(@TempDir final Path folder) {
    final MapProject project = givenMinimap(folder);
    final RulesTable table = new RulesTable(project, Trade.BUY, "production");

    table.setValueAt(3, 1, 2);

    assertThat(table.rule(0).getName())
        .as("the first rule the frontier sells, not the game's first")
        .isEqualTo("buyInfantry");
    assertThat(table.getRowCount()).as("only what it sells").isEqualTo(6);
    assertThat(
            ProductionXml.rules(project.game()).stream()
                .filter(r -> r.getName().equals("buyArtillery"))
                .findFirst()
                .flatMap(ProductionXml::result)
                .map(Result::getQuantity))
        .as("the Amount of row 1 is the second rule sold")
        .contains(3);
  }

  @DisplayName("An unticked rule stays in its row until the rows are filtered again")
  @Test
  void keepsUntickedRow(@TempDir final Path folder) {
    final MapProject project = givenMinimap(folder);
    final RulesTable table = new RulesTable(project, Trade.BUY, "production");

    table.setValueAt(false, 0, 0);
    table.changed();

    assertThat(table.getRowCount()).as("the row does not jump away").isEqualTo(6);
    assertThat(table.getValueAt(0, 0)).as("shown unticked").isEqualTo(false);
    table.refilter();
    assertThat(table.getRowCount()).as("gone once filtered again").isEqualTo(5);
    project.undo();
    table.changed();
    assertThat(table.getRowCount()).as("back after an undo").isEqualTo(6);
  }

  @DisplayName("A rule moved or the list ordered shows in the rows at once")
  @Test
  void rowsFollowAMoveAndAnOrder(@TempDir final Path folder) {
    // given
    final MapProject project = givenMinimap(folder);
    final RulesTable table = new RulesTable(project, Trade.BUY, "production");
    final String first = table.ruleName(0);
    final String second = table.ruleName(1);

    // when the first rule moves down
    project.edit(g -> ProductionXml.withRuleMovedIn(g, "production", 0, 1));
    table.changed();

    // then
    assertThat(List.of(table.ruleName(0), table.ruleName(1)))
        .as("the two rows changed places")
        .containsExactly(second, first);

    // when the list is ordered A to Z
    project.edit(
        g -> ProductionXml.withRulesOrderedIn(g, "production", ProductionXml.rulesByName(g)));
    table.changed();

    // then
    assertThat(IntStream.range(0, table.getRowCount()).mapToObj(table::ruleName))
        .as("the rows in the list's new order")
        .containsExactlyElementsOf(ProductionXml.rulesIn(project.game(), "production"));
  }

  @DisplayName("The order buttons are off while All rules shows, and on again with Sold here")
  @Test
  void orderButtonsOnlyForSoldHere(@TempDir final Path folder) {
    // given
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(givenMinimap(folder)).page();
    givenLaidOut(page, 1200, 800);
    final List<JButton> ordering =
        Stream.of("▲", "▼", "⇅")
            .map(text -> find(page, JButton.class, b -> text.equals(b.getText())))
            .toList();
    final List<String> seen = new ArrayList<>();

    // when
    find(page, JRadioButton.class, b -> b.getText().startsWith("All rules")).doClick();
    ordering.forEach(b -> seen.add(b.isEnabled() + " " + b.getToolTipText()));
    find(page, JRadioButton.class, b -> b.getText().startsWith("Sold here")).doClick();
    ordering.forEach(b -> seen.add(String.valueOf(b.isEnabled())));

    // then
    assertThat(seen)
        .as("off with the reason while the game's order shows; on with the list's")
        .containsExactly(
            "false Show Sold here to order its rules",
            "false Show Sold here to order its rules",
            "false Show Sold here to order its rules",
            "true",
            "true",
            "true");
  }

  @DisplayName("A row kept after its tick was taken goes when the list is ordered")
  @Test
  void untickedRowGoesWithAnOrder(@TempDir final Path folder) {
    // given
    final MapProject project = givenMinimap(folder);
    final RulesTable table = new RulesTable(project, Trade.BUY, "production");
    table.setValueAt(false, 0, 0);
    table.changed();
    final int kept = table.getRowCount();

    // when
    project.edit(g -> ProductionXml.withRuleMovedIn(g, "production", 0, 1));
    table.changed();

    // then
    assertThat(table.getRowCount()).as("the rows are the list's again").isEqualTo(kept - 1);
  }

  @DisplayName("Buy: a unit's row shows its picture, and the numbers stand in the middle")
  @Test
  void buyRowsWithUnitPictures(@TempDir final Path folder) {
    // given
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(givenMinimap(folder)).page();
    givenLaidOut(page, 1200, 800);
    final JTable table = find(page, JTable.class, t -> true);

    // when
    final JLabel unit =
        find(rendered(table, 0, 1), JLabel.class, l -> "infantry".equals(l.getText()));
    final JLabel amount = (JLabel) rendered(table, 0, 2);

    // then
    assertThat(unit.getIcon()).as("infantry's picture before its name").isNotNull();
    assertThat(unit.getIcon().getIconWidth())
        .as("one width for every row, so the names stay in line")
        .isEqualTo(22);
    assertThat(amount.getHorizontalAlignment())
        .as("a number in the middle of its column")
        .isEqualTo(SwingConstants.CENTER);
  }

  @DisplayName("Resources > At the start: each resource's icon above its name, the number columns"
      + " no wider than needed")
  @Test
  void startTableWithResourceIcons(@TempDir final Path folder) {
    // given
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(givenMinimap(folder)).page();
    page.setSelectedIndex(
        IntStream.range(0, page.getTabCount())
            .filter(i -> "Resources".equals(page.getTitleAt(i)))
            .findFirst()
            .orElseThrow());
    givenLaidOut(page, 1200, 800);
    final JTable start = find(page, JTable.class, t -> "Nation".equals(t.getColumnName(0)));

    // when
    final JLabel pus =
        (JLabel)
            start
                .getColumnModel()
                .getColumn(1)
                .getHeaderRenderer()
                .getTableCellRendererComponent(start, start.getColumnName(1), false, false, -1, 1);

    // then
    assertThat(pus.getIcon()).as("the resource's icon in its heading").isNotNull();
    assertThat(pus.getVerticalTextPosition())
        .as("the name below the icon")
        .isEqualTo(SwingConstants.BOTTOM);
    assertThat(start.getColumnModel().getColumn(1).getMaxWidth())
        .as("room to spare goes to the nations' names, not between the numbers")
        .isEqualTo(start.getColumnModel().getColumn(1).getMinWidth());
    assertThat(((JLabel) rendered(start, 0, 1)).getHorizontalAlignment())
        .as("a number in the middle of its column")
        .isEqualTo(SwingConstants.CENTER);
  }

  @DisplayName("Buy: a unit shows as the nation that buys from the list shows it")
  @Test
  void buyRowsInTheListsNationsPictures(@TempDir final Path folder) {
    // given: the one list, used by the Russians first, then by the Italians alone
    final MapProject project = givenMinimap(folder);
    final JTabbedPane page = (JTabbedPane) new ProductionEditor(project).page();
    givenLaidOut(page, 1200, 800);
    final JTable table = find(page, JTable.class, t -> true);
    final int[] russian = pixels(infantryIcon(table));

    // when
    project.edit(
        g -> ProductionXml.withFrontierOf(g, "Russians", "productionIndustrialTechnology"));
    givenLaidOut(page, 1200, 800);
    final int[] italian = pixels(infantryIcon(find(page, JTable.class, t -> true)));

    // then
    assertThat(italian)
        .as("the Italians' infantry, not the Russians', once only they buy from the list")
        .isNotEqualTo(russian);
  }

  private static Icon infantryIcon(final JTable table) {
    return find(rendered(table, 0, 1), JLabel.class, l -> "infantry".equals(l.getText()))
        .getIcon();
  }

  private static int[] pixels(final Icon icon) {
    final BufferedImage image =
        new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
    icon.paintIcon(null, image.createGraphics(), 0, 0);
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  private static Component rendered(final JTable table, final int row, final int column) {
    return table.prepareRenderer(table.getCellRenderer(row, column), row, column);
  }
}
