package eddies.mapeditor;

import eddies.mapeditor.MakerIcon.Glyph;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultCellEditor;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.map.data.elements.Production.Rule.Result;
import org.triplea.swing.SwingComponents;

/**
 * What nations can buy and repair, and the resources they pay with. Buying and repairing each have
 * frontiers, lists of rules, with the nations that use them, and the rules with what they give and
 * cost.
 */
final class ProductionEditor implements Editor {
  private final JTabbedPane page = new JTabbedPane();
  private final FrontiersPage buy;
  private final FrontiersPage repair;
  private final ResourcesPage resources;

  ProductionEditor(final MapProject project) {
    buy = new FrontiersPage(project, Trade.BUY);
    repair = new FrontiersPage(project, Trade.REPAIR);
    resources = new ResourcesPage(project);
    page.addTab("Buy", buy.page);
    page.addTab("Repair", repair.page);
    page.addTab("Resources", resources.page);
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
    return "Each nation buys from one frontier: a list of rules like '1 infantry for 3 PUs'."
        + " Pick a frontier, choose who uses it, and tick what it sells.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    buy.commit();
    repair.commit();
    resources.commit();
  }

  /** Buying or repairing: both are rules sold in frontiers, repairs in lists of their own. */
  enum Trade {
    BUY,
    REPAIR;

    Game view(final Game game) {
      return this == BUY ? game : ProductionXml.repairView(game);
    }

    /** The change of the view as a change of the game. */
    UnaryOperator<Game> edit(final UnaryOperator<Game> change) {
      return this == BUY ? change : game -> ProductionXml.withRepairChange(game, change);
    }
  }

  /** PUs first, the resource every game has, then the others in the game's order. */
  private static List<String> resourcesPusFirst(final Game game) {
    final List<String> all = ProductionXml.resources(game);
    return Stream.concat(
            all.stream().filter(MapXml.PUS::equals),
            all.stream().filter(r -> !MapXml.PUS.equals(r)))
        .toList();
  }

  /** "Germans, Russians and Japanese", or the first four and "and 7 more". */
  private static String nationsText(final List<String> nations) {
    if (nations.size() > 4) {
      return String.join(", ", nations.subList(0, 4)) + " and " + (nations.size() - 4) + " more";
    }
    return nations.size() < 2
        ? String.join("", nations)
        : String.join(", ", nations.subList(0, nations.size() - 1)) + " and " + nations.getLast();
  }

  /** The spinner's typed value too, eg when Enter chose the verb before the field lost focus. */
  private static int valueOf(final JSpinner spinner) {
    try {
      spinner.commitEdit();
    } catch (final ParseException e) {
      // the last valid value stays
    }
    return (Integer) spinner.getValue();
  }

  /** The frontiers to buy or repair from, and the details of the chosen one. */
  private static final class FrontiersPage {
    private final MapProject project;
    private final Trade trade;
    private final JPanel page = new JPanel(new BorderLayout());
    private final SearchList list;
    private final JPanel details = new JPanel(new BorderLayout());
    private final Form head = new Form(480);
    private @Nullable String selected;
    private boolean allRules;
    private Optional<JTable> rulesShown = Optional.empty();

    FrontiersPage(final MapProject project, final Trade trade) {
      this.project = project;
      this.trade = trade;
      list =
          new SearchList(
                  "frontier",
                  "frontiers",
                  () -> ProductionXml.frontiers(view()),
                  f -> f,
                  this::detail,
                  this::choose)
              .stripSharedPrefix()
              .withAdd("Add", "Add a frontier", this::addFrontier);
      // room below the tabs; at the left the list starts where the other tools' lists do
      list.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, 0, 0, Theme.GAP_S));
      page.add(Panels.sideBySide(list, details), BorderLayout.CENTER);
      project.onChange(
          () -> {
            list.refresh();
            if (selected != null && ProductionXml.frontiers(view()).contains(selected)) {
              update();
            } else {
              showDetails();
            }
          });
      showDetails();
    }

    private Game view() {
      return trade.view(project.game());
    }

    private void edit(final UnaryOperator<Game> change) {
      project.edit(trade.edit(change));
    }

    void commit() {
      rulesShown.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
    }

    private List<String> users(final Game game, final String frontier) {
      return MapXml.nations(game).stream()
          .filter(n -> ProductionXml.frontierOf(game, n).filter(frontier::equals).isPresent())
          .toList();
    }

    private String detail(final String frontier) {
      final Game game = view();
      final int rules = ProductionXml.rulesIn(game, frontier).size();
      final int nations = users(game, frontier).size();
      return rules
          + (rules == 1 ? " rule · " : " rules · ")
          + (nations == 0 ? "no nation" : nations + (nations == 1 ? " nation" : " nations"));
    }

    private void choose(final String frontier) {
      selected = frontier;
      showDetails();
    }

    private void addFrontier() {
      final List<String> taken = ProductionXml.frontiers(view());
      Dialogs.askName(
              page,
              "Add frontier",
              "Name",
              "",
              "Add",
              name -> taken.contains(name) ? Optional.of("Already a frontier") : Optional.empty())
          .ifPresent(
              frontier -> {
                edit(g -> ProductionXml.withFrontier(g, frontier));
                list.choose(frontier);
                choose(frontier);
              });
    }

    /** The chosen frontier, else the first, built anew; the empty state without frontiers. */
    private void showDetails() {
      details.removeAll();
      rulesShown = Optional.empty();
      final List<String> frontiers = ProductionXml.frontiers(view());
      if (frontiers.isEmpty()) {
        selected = null;
        details.add(
            Panels.empty(
                Glyph.PRODUCTION,
                "No frontiers yet",
                trade == Trade.BUY
                    ? "Without one, nations can buy nothing."
                    : "Without one, nations can repair nothing.",
                "Add frontier",
                this::addFrontier),
            BorderLayout.CENTER);
        details.revalidate();
        details.repaint();
        return;
      }
      if (selected == null || !frontiers.contains(selected)) {
        selected = frontiers.getFirst();
        list.choose(selected);
      }
      final String frontier = selected;
      final RulesTable model = new RulesTable(project, trade, frontier);
      final JTable table = newRulesTable(model);
      rulesShown = Optional.of(table);
      showHead(frontier, model);
      details.add(head, BorderLayout.NORTH);
      final JScrollPane rules = new JScrollPane(table);
      rules.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.getSeparatorColor()));
      final JPanel middle = new JPanel(new BorderLayout());
      middle.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_L, 0, Theme.GAP_L));
      middle.add(rules, BorderLayout.CENTER);
      details.add(middle, BorderLayout.CENTER);
      details.add(newRemoveRow(frontier, model, table), BorderLayout.SOUTH);
      details.revalidate();
      details.repaint();
    }

    /** After a change of the game: the head anew, the table's rows kept unless they changed. */
    private void update() {
      rulesShown.ifPresent(
          table -> {
            final RulesTable model = (RulesTable) table.getModel();
            final int row = table.getSelectedRow();
            final Optional<String> chosen =
                row >= 0 && row < model.getRowCount()
                    ? Optional.of(model.ruleName(row))
                    : Optional.empty();
            if (model.changed()) {
              setUpColumns(table);
            }
            chosen
                .map(model::rowOf)
                .filter(r -> r >= 0)
                .ifPresent(r -> table.setRowSelectionInterval(r, r));
            showHead(model.frontier, model);
          });
    }

    private void showHead(final String frontier, final RulesTable model) {
      final Game game = view();
      head.clear();
      head.title(frontier);
      final List<String> users = users(game, frontier);
      final JLabel usedBy =
          new JLabel(users.isEmpty() ? "Used by no nation" : "Used by " + nationsText(users));
      usedBy.setToolTipText(users.isEmpty() ? null : String.join(", ", users));
      final JButton chooseNations = new JButton("Choose nations…");
      Theme.setTertiaryButtonStyle(chooseNations);
      chooseNations.addActionListener(e -> chooseNations(frontier));
      final JPanel usedLine = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
      usedLine.add(usedBy);
      usedLine.add(chooseNations);
      head.row(usedLine);
      head.section(trade == Trade.BUY ? "What it sells" : "What it repairs");
      if (trade == Trade.REPAIR) {
        head.note(
            "A rule repairs an amount of damage on a unit for its cost, eg 1 damage for 1 PU.");
      }
      final JRadioButton here =
          new JRadioButton(
              (trade == Trade.BUY ? "Sold here (" : "Used here (")
                  + ProductionXml.rulesIn(game, frontier).size()
                  + ")",
              !allRules);
      final JRadioButton all =
          new JRadioButton("All rules (" + ProductionXml.rules(game).size() + ")", allRules);
      final ButtonGroup group = new ButtonGroup();
      group.add(here);
      group.add(all);
      here.addActionListener(
          e -> {
            allRules = false;
            model.showSoldHere();
          });
      all.addActionListener(
          e -> {
            allRules = true;
            model.showAllRules();
          });
      final JButton addRule = new JButton("+ Add rule…");
      Theme.setTertiaryButtonStyle(addRule);
      addRule.addActionListener(e -> addRule(frontier));
      final JPanel toggle = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
      toggle.add(new JLabel("Show"));
      toggle.add(here);
      toggle.add(all);
      final JPanel showRow = new JPanel(new BorderLayout());
      showRow.add(toggle, BorderLayout.WEST);
      showRow.add(addRule, BorderLayout.EAST);
      head.row(showRow);
      head.revalidate();
      head.repaint();
    }

    private void chooseNations(final String frontier) {
      final Game game = view();
      final List<String> users = users(game, frontier);
      Dialogs.pickMany(
              page,
              (trade == Trade.BUY ? "Nations that buy from " : "Nations that repair with ")
                  + frontier,
              MapXml.nations(game),
              users,
              "Choose")
          .filter(chosen -> !new HashSet<>(chosen).equals(new HashSet<>(users)))
          .ifPresent(chosen -> edit(g -> ProductionXml.withFrontierUsers(g, frontier, chosen)));
    }

    private JTable newRulesTable(final RulesTable model) {
      final JTable table =
          new JTable(model) {
            @Serial private static final long serialVersionUID = 1L;

            // the names take the room left; too narrow for every column's least, it scrolls
            @Override
            public boolean getScrollableTracksViewportWidth() {
              return getParent() instanceof JViewport viewport
                  && viewport.getWidth() >= leastWidth(this);
            }
          };
      table.setTableHeader(
          new JTableHeader(table.getColumnModel()) {
            @Serial private static final long serialVersionUID = 1L;

            @Override
            public @Nullable String getToolTipText(final MouseEvent e) {
              final int column = columnAtPoint(e.getPoint());
              return model.isCostColumn(column)
                  ? "What it costs in " + table.getColumnName(column)
                  : null;
            }
          });
      table.setRowHeight(table.getRowHeight() + 8);
      table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
      table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      table.putClientProperty("terminateEditOnFocusLost", true);
      // numbers open on one click, as the tick does
      ((DefaultCellEditor) table.getDefaultEditor(Integer.class)).setClickCountToStart(1);
      setUpColumns(table);
      return table;
    }

    /** The widths, the combo of what a rule gives and the rule's name in the secondary color. */
    private void setUpColumns(final JTable table) {
      final FontMetrics header = table.getFontMetrics(table.getTableHeader().getFont());
      final int last = table.getColumnCount() - 1;
      for (int i = 0; i <= last; i++) {
        final TableColumn column = table.getColumnModel().getColumn(i);
        if (i == 1 || i == last) {
          column.setMinWidth(120);
          column.setPreferredWidth(200);
        } else {
          final int width =
              Math.max(i == 0 ? 50 : 60, header.stringWidth(table.getColumnName(i)) + 24);
          column.setMinWidth(width);
          column.setPreferredWidth(width);
          column.setMaxWidth(width + 30);
        }
      }
      final JComboBox<String> gives = new JComboBox<>();
      things(view()).forEach(gives::addItem);
      Details.comboColumn(table, 1, gives);
      final DefaultTableCellRenderer ruleName = new DefaultTableCellRenderer();
      ruleName.setForeground(Theme.getSecondaryTextColor());
      table.getColumnModel().getColumn(last).setCellRenderer(ruleName);
    }

    /** The width of the table's columns at their least. */
    private static int leastWidth(final JTable table) {
      int width = 0;
      for (int i = 0; i < table.getColumnCount(); i++) {
        width += table.getColumnModel().getColumn(i).getMinWidth();
      }
      return width;
    }

    /** "Remove rule" while a row is chosen; "Remove frontier" below it, apart. */
    private JComponent newRemoveRow(
        final String frontier, final RulesTable model, final JTable table) {
      final JButton removeRule = new JButton("Remove rule");
      Theme.setDestructiveButtonStyle(removeRule);
      removeRule.addActionListener(
          e -> {
            if (table.getSelectedRow() >= 0) {
              removeRule(model.ruleName(table.getSelectedRow()));
            }
          });
      removeRule.setVisible(false);
      table
          .getSelectionModel()
          .addListSelectionListener(e -> removeRule.setVisible(table.getSelectedRow() >= 0));
      final JButton removeFrontier = new JButton("Remove frontier");
      Theme.setDestructiveButtonStyle(removeFrontier);
      removeFrontier.addActionListener(e -> removeFrontier(frontier));
      // one row, its height that of Remove frontier: the table keeps its size while Remove rule
      // hides, and fills the height above
      final JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      south.setBorder(
          BorderFactory.createEmptyBorder(
              Theme.GAP_M,
              Theme.GAP_L - removeFrontier.getInsets().left,
              Theme.GAP_L,
              Theme.GAP_L));
      // its text starts where the table does, as Form.remove's
      south.add(removeFrontier);
      south.add(Box.createHorizontalStrut(Theme.GAP_L));
      south.add(removeRule);
      return south;
    }

    /** What a rule may give: units and resources to buy, units to repair. */
    private Stream<String> things(final Game game) {
      return trade == Trade.BUY
          ? Stream.concat(
              MapXml.unitTypes(game).stream(), ProductionXml.resources(game).stream())
          : MapXml.unitTypes(game).stream();
    }

    /** Asks what the new rule gives, how many and for what, and adds it to the frontier. */
    private void addRule(final String frontier) {
      final Game game = view();
      final List<String> choices = things(game).toList();
      if (choices.isEmpty()) {
        Dialogs.warn(
            page,
            "Add rule",
            trade == Trade.BUY
                ? "The game has no units or resources yet."
                : "The game has no units yet.");
        return;
      }
      final JComboBox<String> thing = new JComboBox<>(choices.toArray(String[]::new));
      final JSpinner amount = Form.newNumber(1, 1, 9999, n -> {});
      final JSpinner cost = Form.newNumber(costOf(game, choices.getFirst()), 99_999, n -> {});
      thing.addActionListener(e -> cost.setValue(costOf(game, (String) thing.getSelectedItem())));
      final Form content = new Form(300);
      content.setBorder(BorderFactory.createEmptyBorder());
      content
          .field(trade == Trade.BUY ? "Sells" : "Repairs", thing)
          .number(trade == Trade.BUY ? "Amount" : "Damage repaired", amount);
      ProductionXml.costResource(game)
          .ifPresent(
              resource -> {
                final JLabel label = new JLabel("Costs");
                label.setLabelFor(cost);
                label.setPreferredSize(new Dimension(140, label.getPreferredSize().height));
                cost.setPreferredSize(new Dimension(96, cost.getPreferredSize().height));
                final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
                line.setOpaque(false);
                line.add(label);
                line.add(cost);
                line.add(new JLabel(resource));
                content.row(line);
              });
      if (Dialogs.ask(page, "Add rule", content, "Add rule")) {
        final String chosen = (String) thing.getSelectedItem();
        final int amountChosen = valueOf(amount);
        final int costChosen = valueOf(cost);
        edit(
            g ->
                ProductionXml.withNewRule(
                    g,
                    frontier,
                    chosen,
                    amountChosen,
                    costChosen,
                    trade == Trade.BUY ? "buy" : "repair"));
      }
    }

    /** A unit's cost to buy it; a repair, or a resource, costs 1 at first. */
    private int costOf(final Game game, final String thing) {
      return trade == Trade.BUY ? MapXml.unitCost(game, thing).orElse(1) : 1;
    }

    private void removeRule(final String rule) {
      final Game game = view();
      if (RulesXml.isNamedInAttachments(project.game(), rule)) {
        Dialogs.warn(
            page, "Remove rule", rule + " is named in an attachment, eg a trigger; it stays.");
        return;
      }
      final long frontiers =
          ProductionXml.frontiers(game).stream()
              .filter(f -> ProductionXml.rulesIn(game, f).contains(rule))
              .count();
      if (Dialogs.confirmRemove(
          page,
          "Remove rule",
          "Remove "
              + rule
              + "?\nIt is taken out of every frontier that uses it ("
              + frontiers
              + ").",
          List.of(),
          "Remove")) {
        edit(g -> ProductionXml.withoutRule(g, rule));
      }
    }

    private void removeFrontier(final String frontier) {
      if (RulesXml.isNamedInAttachments(project.game(), frontier)) {
        Dialogs.warn(
            page,
            "Remove frontier",
            frontier + " is named in an attachment, eg a trigger; it stays.");
        return;
      }
      final List<String> users = users(view(), frontier);
      final String after =
          users.isEmpty()
              ? "No nation uses it."
              : String.join(", ", users)
                  + (trade == Trade.BUY ? " then buy nothing." : " then repair nothing.");
      if (Dialogs.confirmRemove(
          page, "Remove frontier", "Remove " + frontier + "?\n" + after, List.of(), "Remove")) {
        edit(g -> ProductionXml.withoutFrontier(g, frontier));
      }
    }
  }

  /**
   * The rules of a frontier, or every rule of the game: sold there, what it gives, a cost per
   * resource and its name. The rows are an index of rule names, set by {@link #refilter}, so a rule
   * unticked under "Sold here" stays until the frontier, the filter or the rules change.
   */
  static final class RulesTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private static final int FIXED = 3;
    private final transient MapProject project;
    private final Trade trade;
    private final String frontier;
    private boolean all;
    private List<String> shown = List.of();
    private List<String> allRules = List.of();
    private List<String> costColumns;

    RulesTable(final MapProject project, final Trade trade, final String frontier) {
      this.project = project;
      this.trade = trade;
      this.frontier = frontier;
      costColumns = costColumns();
      refilter();
    }

    private Game view() {
      return trade.view(project.game());
    }

    private void edit(final UnaryOperator<Game> change) {
      project.edit(trade.edit(change));
    }

    void showSoldHere() {
      all = false;
      refilter();
    }

    void showAllRules() {
      all = true;
      refilter();
    }

    /** The rows anew: the rules sold here, in the frontier's order, or every rule. */
    void refilter() {
      final Game game = view();
      allRules = ruleNames(game);
      shown =
          all
              ? allRules
              : ProductionXml.rulesIn(game, frontier).stream()
                  .filter(allRules::contains)
                  .toList();
      fireTableDataChanged();
    }

    private static List<String> ruleNames(final Game game) {
      return ProductionXml.rules(game).stream().map(ProductionRule::getName).toList();
    }

    /**
     * After a change of the game: the rows anew when the rules changed or one sold here is not
     * shown (eg after an undo); whether the columns changed.
     */
    boolean changed() {
      final Game game = view();
      final List<String> columns = costColumns();
      final boolean structure = !columns.equals(costColumns);
      costColumns = columns;
      if (!ruleNames(game).equals(allRules)
          || !shown.containsAll(ProductionXml.rulesIn(game, frontier))) {
        refilter();
      }
      if (structure) {
        fireTableStructureChanged();
      } else {
        fireTableDataChanged();
      }
      return structure;
    }

    String ruleName(final int row) {
      return shown.get(row);
    }

    int rowOf(final String rule) {
      return shown.indexOf(rule);
    }

    ProductionRule rule(final int row) {
      final String name = ruleName(row);
      return ProductionXml.rules(view()).stream()
          .filter(r -> name.equals(r.getName()))
          .findFirst()
          .orElseThrow();
    }

    boolean isCostColumn(final int column) {
      return column >= FIXED && column < FIXED + costColumns.size();
    }

    /** The resources rules cost, and PUs: the cost columns, few even in a game with many. */
    private List<String> costColumns() {
      final Game game = view();
      final List<ProductionRule> rules = ProductionXml.rules(game);
      return resourcesPusFirst(game).stream()
          .filter(
              r ->
                  MapXml.PUS.equals(r)
                      || rules.stream().anyMatch(rule -> ProductionXml.cost(rule, r) != 0))
          .toList();
    }

    @Override
    public int getRowCount() {
      return shown.size();
    }

    @Override
    public int getColumnCount() {
      return FIXED + costColumns.size() + 1;
    }

    @Override
    public String getColumnName(final int column) {
      if (isCostColumn(column)) {
        return costColumns.get(column - FIXED);
      }
      return column < FIXED
          ? List.of(
                  trade == Trade.BUY ? "Sold" : "Used",
                  trade == Trade.BUY ? "Unit or resource" : "Repairs",
                  trade == Trade.BUY ? "Amount" : "Damage repaired")
              .get(column)
          : "Rule";
    }

    @Override
    public Class<?> getColumnClass(final int column) {
      if (isCostColumn(column)) {
        return Integer.class;
      }
      return switch (column) {
        case 0 -> Boolean.class;
        case 2 -> Integer.class;
        default -> String.class;
      };
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      // what a rule without a result gives is not in the box: it would take the last one chosen
      return column != getColumnCount() - 1
          && (column != 1 || ProductionXml.result(rule(row)).isPresent());
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final ProductionRule rule = rule(row);
      if (isCostColumn(column)) {
        return ProductionXml.cost(rule, costColumns.get(column - FIXED));
      }
      final Optional<Result> result = ProductionXml.result(rule);
      return switch (column) {
        case 0 -> ProductionXml.rulesIn(view(), frontier).contains(rule.getName());
        case 1 -> result.map(Result::getResourceOrUnit).orElse("");
        case 2 -> result.map(Result::getQuantity).orElse(0);
        default -> rule.getName();
      };
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      if (value == null || value.equals(getValueAt(row, column))) {
        return;
      }
      final ProductionRule rule = rule(row);
      final String name = rule.getName();
      if (isCostColumn(column)) {
        final String resource = costColumns.get(column - FIXED);
        edit(g -> ProductionXml.withCost(g, name, resource, Math.max(0, (Integer) value)));
        return;
      }
      final Optional<Result> result = ProductionXml.result(rule);
      switch (column) {
        case 0 ->
            edit(
                g ->
                    (Boolean) value
                        ? ProductionXml.withRuleIn(g, frontier, name)
                        : ProductionXml.withoutRuleIn(g, frontier, name));
        case 1 ->
            result.ifPresent(
                r -> edit(g -> ProductionXml.withResult(g, name, (String) value, r.getQuantity())));
        case 2 ->
            result.ifPresent(
                r ->
                    edit(
                        g ->
                            ProductionXml.withResult(
                                g, name, r.getResourceOrUnit(), Math.max(1, (Integer) value))));
        default -> {
          // the rule's name is not edited here
        }
      }
    }
  }

  /** The resources with their icons, and what each nation has of them at the start. */
  private static final class ResourcesPage {
    private static final int ICON_SIZE = 20;
    private static final int MAX_WIDTH = 760;
    private static final int NATION_WIDTH = 160;
    private static final int NUMBER_WIDTH = 120;
    private final MapProject project;
    private final Form form = new Form(420);
    private final JComponent page = SwingComponents.newJScrollPane(form);
    private Optional<JTable> startShown = Optional.empty();

    ResourcesPage(final MapProject project) {
      this.project = project;
      form.addComponentListener(
          new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
              // card, line and table one width, at most 760 px, at the left
              form.setBorder(
                  BorderFactory.createEmptyBorder(
                      Theme.GAP_XS,
                      Theme.GAP_L,
                      Theme.GAP_L,
                      Math.max(Theme.GAP_L, form.getWidth() - Theme.GAP_L - MAX_WIDTH)));
            }
          });
      project.onChange(this::show);
      show();
    }

    void commit() {
      startShown.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
    }

    private void show() {
      form.clear();
      final Game game = project.game();
      final JPanel top = new JPanel(new BorderLayout());
      top.add(Theme.newAddButton("Add", "Add a resource", this::add), BorderLayout.EAST);
      form.row(top);
      final JPanel card = new JPanel();
      card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
      card.setBorder(
          BorderFactory.createEmptyBorder(Theme.GAP_XS, Theme.GAP_M, Theme.GAP_XS, Theme.GAP_XS));
      Theme.setCardStyle(card);
      for (final String resource : resourcesPusFirst(game)) {
        card.add(newResourceRow(resource));
      }
      form.row(card);

      form.section("At the start");
      final JTable start = newStartTable();
      startShown = Optional.of(start);
      final JScrollPane table = new JScrollPane(start);
      final Dimension size = start.getPreferredSize();
      table.setPreferredSize(
          new Dimension(
              size.width + 2, size.height + start.getTableHeader().getPreferredSize().height + 2));
      form.row(table);
      form.end();
    }

    private JComponent newResourceRow(final String resource) {
      final JLabel name = new JLabel(resource, icon(resource), SwingConstants.LEADING);
      name.setIconTextGap(Theme.GAP_S);
      final JButton rename = new JButton("Rename");
      Theme.setTertiaryButtonStyle(rename);
      rename.addActionListener(e -> rename(resource));
      final JButton icon = new JButton("Change icon…");
      Theme.setTertiaryButtonStyle(icon);
      icon.setToolTipText("A picture for " + resource + ", written into resources/");
      icon.setEnabled(project.getFiles().folder().isPresent());
      icon.addActionListener(e -> chooseIcon(resource));
      final JButton remove =
          Theme.newIconButton("×", "Remove " + resource, () -> remove(resource));
      final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
      actions.setOpaque(false);
      actions.add(rename);
      actions.add(icon);
      final JPanel left = new JPanel(new BorderLayout(Theme.GAP_L, 0));
      left.setOpaque(false);
      name.setPreferredSize(new Dimension(220, ICON_SIZE + 8));
      left.add(name, BorderLayout.WEST);
      left.add(actions, BorderLayout.CENTER);
      final JPanel row = new JPanel(new BorderLayout());
      row.setOpaque(false);
      row.add(left, BorderLayout.CENTER);
      row.add(remove, BorderLayout.EAST);
      row.setAlignmentX(Component.LEFT_ALIGNMENT);
      row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
      return row;
    }

    private JTable newStartTable() {
      final StartTable model = new StartTable(project);
      final JTable start =
          new JTable(model) {
            @Serial private static final long serialVersionUID = 1L;

            // as wide as the card above while its columns fit; else they keep their width and
            // the table scrolls
            @Override
            public boolean getScrollableTracksViewportWidth() {
              return getParent() != null
                  && getParent().getWidth()
                      >= NATION_WIDTH + NUMBER_WIDTH * (getColumnCount() - 1);
            }
          };
      start.setTableHeader(
          new JTableHeader(start.getColumnModel()) {
            @Serial private static final long serialVersionUID = 1L;

            @Override
            public @Nullable String getToolTipText(final MouseEvent e) {
              final int column = columnAtPoint(e.getPoint());
              return column > 0 && MapXml.PUS.equals(start.getColumnName(column))
                  ? "Also set in Nations"
                  : null;
            }
          });
      start.setRowHeight(start.getRowHeight() + 8);
      start.putClientProperty("terminateEditOnFocusLost", true);
      ((DefaultCellEditor) start.getDefaultEditor(Integer.class)).setClickCountToStart(1);
      // numbers right-aligned under right-aligned headers
      final TableCellRenderer header = start.getTableHeader().getDefaultRenderer();
      final TableCellRenderer aligned =
          (table, value, isSelected, hasFocus, row, column) -> {
            final Component cell =
                header.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (cell instanceof JLabel label) {
              label.setHorizontalAlignment(
                  column == 0 ? SwingConstants.LEADING : SwingConstants.RIGHT);
            }
            return cell;
          };
      for (int i = 0; i < start.getColumnCount(); i++) {
        final TableColumn column = start.getColumnModel().getColumn(i);
        column.setPreferredWidth(i == 0 ? NATION_WIDTH : NUMBER_WIDTH);
        column.setHeaderRenderer(aligned);
      }
      start.setPreferredScrollableViewportSize(start.getPreferredSize());
      return start;
    }

    /** The resource's icon in the map's folder at the size the game shows it, else its letter. */
    private ImageIcon icon(final String resource) {
      final BufferedImage icon =
          new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
      final Graphics2D g = icon.createGraphics();
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      final Optional<BufferedImage> picture =
          project
              .getFiles()
              .folder()
              .map(f -> f.resolve("resources").resolve(resource + ".png"))
              .filter(Files::isRegularFile)
              .flatMap(ResourcesPage::read);
      if (picture.isPresent()) {
        final BufferedImage p = picture.get();
        final double scale = ICON_SIZE / (double) Math.max(p.getWidth(), p.getHeight());
        final int width = (int) Math.round(p.getWidth() * scale);
        final int height = (int) Math.round(p.getHeight() * scale);
        g.drawImage(p, (ICON_SIZE - width) / 2, (ICON_SIZE - height) / 2, width, height, null);
      } else {
        // the game draws a resource without an icon as its first letters
        final Color text = Theme.getSecondaryTextColor();
        g.setColor(new Color(text.getRed(), text.getGreen(), text.getBlue(), 60));
        g.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE, 6, 6);
        g.setColor(text);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        final String letter = resource.isEmpty() ? "?" : resource.substring(0, 1);
        final FontMetrics metrics = g.getFontMetrics();
        g.drawString(
            letter,
            (ICON_SIZE - metrics.stringWidth(letter)) / 2,
            (ICON_SIZE - metrics.getHeight()) / 2 + metrics.getAscent());
      }
      g.dispose();
      return new ImageIcon(icon);
    }

    private static Optional<BufferedImage> read(final Path file) {
      try {
        return Optional.ofNullable(ImageIO.read(file.toFile()));
      } catch (final IOException e) {
        return Optional.empty();
      }
    }

    /** Why the name cannot be a resource's: another name in the game, or a resource's. */
    private Optional<String> problem(final String name) {
      return project
          .nameProblem(name)
          .or(
              () ->
                  ProductionXml.resources(project.game()).contains(name)
                      ? Optional.of("Already a resource")
                      : Optional.empty());
    }

    private void add() {
      Dialogs.askName(page, "Add resource", "Name", "", "Add", this::problem)
          .ifPresent(resource -> project.edit(g -> ProductionXml.withResource(g, resource)));
    }

    private void rename(final String resource) {
      final Optional<String> why = ProductionXml.whyResourceNotRenamed(project.game(), resource);
      if (why.isPresent()) {
        Dialogs.warn(page, "Rename " + resource, why.get());
        return;
      }
      Dialogs.askName(
              page,
              "Rename " + resource,
              "New name",
              resource,
              "Rename",
              name -> resource.equals(name) ? Optional.empty() : problem(name))
          .filter(renamed -> !renamed.equals(resource))
          .ifPresent(
              renamed ->
                  project.edit(g -> ProductionXml.withRenamedResource(g, resource, renamed)));
    }

    private void remove(final String resource) {
      final Optional<String> why = ProductionXml.whyResourceStays(project.game(), resource);
      if (why.isPresent()) {
        Dialogs.warn(page, "Remove " + resource, why.get());
      } else {
        project.edit(g -> ProductionXml.withoutResource(g, resource));
      }
    }

    private void chooseIcon(final String resource) {
      final Optional<Path> folder = project.getFiles().folder();
      final JFileChooser chooser = new JFileChooser();
      chooser.setFileFilter(new FileNameExtensionFilter("Pictures", "png", "gif", "jpg", "jpeg"));
      if (folder.isEmpty() || chooser.showOpenDialog(page) != JFileChooser.APPROVE_OPTION) {
        return;
      }
      final Optional<BufferedImage> picture = read(chooser.getSelectedFile().toPath());
      if (picture.isEmpty()) {
        Dialogs.warn(page, "Icon of " + resource, "That file is no picture.");
        return;
      }
      try {
        MapFolder.writeResourceIcon(folder.get(), resource, picture.get());
      } catch (final IOException e) {
        Dialogs.warn(page, "Icon of " + resource, "Could not write the icon: " + e.getMessage());
      }
      show();
    }
  }

  /** The nations, each with what it has of every resource at the start, PUs first. */
  private static final class StartTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;

    StartTable(final MapProject project) {
      this.project = project;
    }

    private List<String> resources() {
      return resourcesPusFirst(project.game());
    }

    @Override
    public int getRowCount() {
      return MapXml.nations(project.game()).size();
    }

    @Override
    public int getColumnCount() {
      return 1 + resources().size();
    }

    @Override
    public String getColumnName(final int column) {
      return column == 0 ? "Nation" : resources().get(column - 1);
    }

    @Override
    public Class<?> getColumnClass(final int column) {
      return column == 0 ? String.class : Integer.class;
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      return column > 0;
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final String nation = MapXml.nations(project.game()).get(row);
      return column == 0
          ? nation
          : MapXml.startingResource(project.game(), nation, resources().get(column - 1));
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      if (value == null || value.equals(getValueAt(row, column))) {
        return;
      }
      final String nation = MapXml.nations(project.game()).get(row);
      final String resource = resources().get(column - 1);
      project.edit(
          g -> MapXml.withStartingResource(g, nation, resource, Math.max(0, (Integer) value)));
    }
  }
}
