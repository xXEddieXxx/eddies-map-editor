package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.io.Serial;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.map.data.elements.Production.Rule.Result;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * What nations can buy: the frontiers, each a list of production rules, with the nations that buy
 * from it, and the rules with what they give and cost.
 */
final class ProductionEditor implements Editor {
  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(280);
  private @Nullable String selected;
  private Optional<JTable> rulesShown = Optional.empty();

  ProductionEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search frontier",
            () -> ProductionXml.frontiers(project.game()),
            this::detail,
            this::choose);
    final JButton add = new JButton("Add frontier...");
    add.addActionListener(e -> addFrontier());
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(
        Help.with(add, "A new, empty list of what a nation can buy, eg for a nation's techs."),
        BorderLayout.SOUTH);
    page.add(
        Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(inspector)),
        BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          showInspector();
        });
    showInspector();
  }

  @Override
  public String title() {
    return "Production";
  }

  @Override
  public Glyph glyph() {
    return Glyph.PRODUCTION;
  }

  @Override
  public String hint() {
    return "What nations can buy. Each nation buys from one frontier, a list of rules; a rule"
        + " gives units or resources for a cost, eg 1 infantry for 3 PUs.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    rulesShown.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
  }

  private String detail(final String frontier) {
    final Game game = project.game();
    final List<String> nations =
        MapXml.nations(game).stream()
            .filter(n -> ProductionXml.frontierOf(game, n).filter(frontier::equals).isPresent())
            .toList();
    final int rules = ProductionXml.rulesIn(game, frontier).size();
    return rules
        + (rules == 1 ? " rule · " : " rules · ")
        + (nations.isEmpty() ? "no nation" : String.join(", ", nations));
  }

  private void choose(final String frontier) {
    selected = frontier;
    showInspector();
  }

  private void addFrontier() {
    final String name =
        JOptionPane.showInputDialog(
            page, "Name of the new frontier:", "Add frontier", JOptionPane.PLAIN_MESSAGE);
    if (name == null) {
      return;
    }
    final String frontier = name.strip();
    final Game game = project.game();
    final Game added = ProductionXml.withFrontier(game, frontier);
    if (added == game) {
      warn("The name must not be empty or another frontier's.", "Add frontier");
      return;
    }
    project.edit(g -> ProductionXml.withFrontier(g, frontier));
    choose(frontier);
    list.choose(frontier);
  }

  private void warn(final String message, final String title) {
    JOptionPane.showMessageDialog(page, message, title, JOptionPane.WARNING_MESSAGE);
  }

  private void showInspector() {
    inspector.clear();
    rulesShown = Optional.empty();
    final Game game = project.game();
    if (selected == null || !ProductionXml.frontiers(game).contains(selected)) {
      inspector
          .note(
              ProductionXml.frontiers(game).size()
                  + " frontiers and "
                  + ProductionXml.rules(game).size()
                  + " rules. Choose a frontier to see what it sells.")
          .end();
      return;
    }
    final String frontier = selected;
    inspector.title(frontier);
    inspector.section(
        "Nations", "Who buys from this frontier. A nation buys from one; untick for none.");
    for (final String nation : MapXml.nations(game)) {
      final Optional<String> now = ProductionXml.frontierOf(game, nation);
      final JCheckBox box =
          new JCheckBox(
              nation
                  + now.filter(f -> !f.equals(frontier))
                      .map(f -> " (buys from " + f + ")")
                      .orElse(""),
              now.filter(frontier::equals).isPresent());
      box.addActionListener(
          e ->
              project.edit(
                  g ->
                      box.isSelected()
                          ? ProductionXml.withFrontierOf(g, nation, frontier)
                          : ProductionXml.withoutFrontierOf(g, nation)));
      inspector.row(box);
    }
    inspector.section(
        "What it sells",
        "Every rule of the game; tick those sold here. A rule gives an amount of a unit or"
            + " resource for its costs. Double-click a value to change it; rules are shared by all"
            + " frontiers.");
    final RulesTable model = new RulesTable(project, frontier);
    final JTable table = new JTable(model);
    table.setTableHeader(
        new JTableHeader(table.getColumnModel()) {
          @Serial private static final long serialVersionUID = 1L;

          @Override
          public String getToolTipText(final MouseEvent e) {
            final int column = columnAtPoint(e.getPoint());
            return column >= RulesTable.FIXED
                ? "What it costs in " + table.getColumnName(column)
                : null;
          }
        });
    table.setRowHeight(table.getRowHeight() + 8);
    // names wide enough to read, the numbers narrow, as a game may have many resources
    final List<Integer> widths = List.of(70, 220, 160, 60);
    for (int i = 0; i < table.getColumnCount(); i++) {
      final TableColumn column = table.getColumnModel().getColumn(i);
      final int width = i < widths.size() ? widths.get(i) : 70;
      column.setPreferredWidth(width);
      if (i == 0 || i >= RulesTable.FIXED - 1) {
        column.setMaxWidth(width + 30);
      }
    }
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.putClientProperty("terminateEditOnFocusLost", true);
    final JComboBox<String> gives = new JComboBox<>();
    Stream.concat(MapXml.unitTypes(game).stream(), ProductionXml.resources(game).stream())
        .forEach(gives::addItem);
    final DefaultCellEditor givesEditor = new DefaultCellEditor(gives);
    givesEditor.setClickCountToStart(2);
    table.getColumnModel().getColumn(2).setCellEditor(givesEditor);
    rulesShown = Optional.of(table);
    final JPanel rules = new JPanel(new BorderLayout());
    rules.add(table.getTableHeader(), BorderLayout.NORTH);
    rules.add(table, BorderLayout.CENTER);
    inspector.row(rules);

    final JButton addRule = new JButton("Add rule...");
    addRule.setToolTipText("A new rule sold here, for one unit or resource you choose");
    addRule.addActionListener(e -> addRule(frontier));
    final JButton removeRule = new JButton("Remove rule");
    removeRule.addActionListener(
        e -> {
          if (table.getSelectedRow() >= 0) {
            removeRule(model.rule(table.getSelectedRow()).getName());
          }
        });
    final JButton remove = new JButton("Remove frontier");
    remove.setToolTipText("Removes this frontier; its nations then buy nothing");
    remove.addActionListener(e -> removeFrontier(frontier));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    buttons.add(addRule);
    buttons.add(Help.with(removeRule, "Removes the chosen rule from the game and every frontier."));
    buttons.add(remove);
    inspector.row(buttons);
    inspector.end();
  }

  /** Asks what the new rule sells, and adds it to the frontier at the unit's cost. */
  private void addRule(final String frontier) {
    final Game game = project.game();
    final Object[] choices =
        Stream.concat(MapXml.unitTypes(game).stream(), ProductionXml.resources(game).stream())
            .toArray();
    if (choices.length == 0) {
      return;
    }
    final Object chosen =
        JOptionPane.showInputDialog(
            page,
            "What the new rule sells:",
            "Add rule",
            JOptionPane.PLAIN_MESSAGE,
            null,
            choices,
            choices[0]);
    if (chosen != null) {
      final String thing = (String) chosen;
      final int cost = MapXml.unitCost(game, thing).orElse(1);
      project.edit(g -> ProductionXml.withNewRule(g, frontier, thing, cost));
    }
  }

  private void removeRule(final String rule) {
    if (RulesXml.isNamedInAttachments(project.game(), rule)) {
      warn(rule + " is named in an attachment, eg a trigger; it stays.", "Remove rule");
    } else {
      project.edit(g -> ProductionXml.withoutRule(g, rule));
    }
  }

  private void removeFrontier(final String frontier) {
    if (RulesXml.isNamedInAttachments(project.game(), frontier)) {
      warn(frontier + " is named in an attachment, eg a trigger; it stays.", "Remove frontier");
    } else {
      project.edit(g -> ProductionXml.withoutFrontier(g, frontier));
    }
  }

  /** Every rule of the game: sold in the frontier, what it gives, and a cost per resource. */
  private static final class RulesTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    static final int FIXED = 4;
    private final transient MapProject project;
    private final String frontier;

    RulesTable(final MapProject project, final String frontier) {
      this.project = project;
      this.frontier = frontier;
    }

    ProductionRule rule(final int row) {
      return ProductionXml.rules(project.game()).get(row);
    }

    /** The resources rules cost, and PUs: the cost columns, few even in a game with many. */
    private List<String> resources() {
      final List<ProductionRule> rules = ProductionXml.rules(project.game());
      return ProductionXml.resources(project.game()).stream()
          .filter(
              r ->
                  MapXml.PUS.equals(r)
                      || rules.stream().anyMatch(rule -> ProductionXml.cost(rule, r) != 0))
          .toList();
    }

    @Override
    public int getRowCount() {
      return ProductionXml.rules(project.game()).size();
    }

    @Override
    public int getColumnCount() {
      return FIXED + resources().size();
    }

    @Override
    public String getColumnName(final int column) {
      return column < FIXED
          ? List.of("Sold here", "Rule", "Gives", "Amount").get(column)
          : resources().get(column - FIXED);
    }

    @Override
    public Class<?> getColumnClass(final int column) {
      return switch (column) {
        case 0 -> Boolean.class;
        case 1, 2 -> String.class;
        default -> Integer.class;
      };
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      // what a rule without a result gives is not in the box: it would take the last one chosen
      return column != 1 && (column != 2 || ProductionXml.result(rule(row)).isPresent());
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final ProductionRule rule = rule(row);
      final Optional<Result> result = ProductionXml.result(rule);
      return switch (column) {
        case 0 -> ProductionXml.rulesIn(project.game(), frontier).contains(rule.getName());
        case 1 -> rule.getName();
        case 2 -> result.map(Result::getResourceOrUnit).orElse("");
        case 3 -> result.map(Result::getQuantity).orElse(0);
        default -> ProductionXml.cost(rule, resources().get(column - FIXED));
      };
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      if (value == null || value.equals(getValueAt(row, column))) {
        return;
      }
      final ProductionRule rule = rule(row);
      final String name = rule.getName();
      final Optional<Result> result = ProductionXml.result(rule);
      switch (column) {
        case 0 ->
            project.edit(
                g ->
                    (Boolean) value
                        ? ProductionXml.withRuleIn(g, frontier, name)
                        : ProductionXml.withoutRuleIn(g, frontier, name));
        case 2 ->
            result.ifPresent(
                r ->
                    project.edit(
                        g -> ProductionXml.withResult(g, name, (String) value, r.getQuantity())));
        case 3 ->
            result.ifPresent(
                r ->
                    project.edit(
                        g ->
                            ProductionXml.withResult(
                                g, name, r.getResourceOrUnit(), Math.max(1, (Integer) value))));
        default -> {
          final String resource = resources().get(column - FIXED);
          project.edit(
              g -> ProductionXml.withCost(g, name, resource, Math.max(0, (Integer) value)));
        }
      }
    }
  }
}
