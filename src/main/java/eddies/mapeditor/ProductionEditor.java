package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.DefaultCellEditor;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.map.data.elements.Production.Rule.Result;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

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
    return "What nations can buy and repair. Each nation buys from one frontier, a list of rules; a"
        + " rule gives units or resources for a cost, eg 1 infantry for 3 PUs. Repair works alike,"
        + " for damaged units such as factories. Resources: what nations pay with and start with.";
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
  private enum Trade {
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

  private static void warn(final JComponent parent, final String message, final String title) {
    JOptionPane.showMessageDialog(parent, message, title, JOptionPane.WARNING_MESSAGE);
  }

  /** The frontiers to buy or repair from, and the inspector of the chosen one. */
  private static final class FrontiersPage {
    private final MapProject project;
    private final Trade trade;
    private final JPanel page = new JPanel(new BorderLayout(8, 0));
    private final SearchList list;
    private final Form inspector = new Form(280);
    private @Nullable String selected;
    private Optional<JTable> rulesShown = Optional.empty();

    FrontiersPage(final MapProject project, final Trade trade) {
      this.project = project;
      this.trade = trade;
      list =
          new SearchList(
              "Search frontier",
              () -> ProductionXml.frontiers(view()),
              this::detail,
              this::choose);
      final JButton add = new JButton("Add frontier...");
      add.addActionListener(e -> addFrontier());
      final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
      listColumn.add(list, BorderLayout.CENTER);
      listColumn.add(
          Help.with(
              add,
              trade == Trade.BUY
                  ? "A new, empty list of what a nation can buy, eg for a nation's techs."
                  : "A new, empty list of what a nation can repair, eg cheaper after a tech."),
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

    private Game view() {
      return trade.view(project.game());
    }

    private void edit(final UnaryOperator<Game> change) {
      project.edit(trade.edit(change));
    }

    void commit() {
      rulesShown.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
    }

    private String detail(final String frontier) {
      final Game game = view();
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
      final Game game = view();
      if (ProductionXml.withFrontier(game, frontier) == game) {
        warn(page, "The name must not be empty or another frontier's.", "Add frontier");
        return;
      }
      edit(g -> ProductionXml.withFrontier(g, frontier));
      choose(frontier);
      list.choose(frontier);
    }

    private void showInspector() {
      inspector.clear();
      rulesShown = Optional.empty();
      final Game game = view();
      if (selected == null || !ProductionXml.frontiers(game).contains(selected)) {
        inspector
            .note(
                ProductionXml.frontiers(game).size()
                    + " frontiers and "
                    + ProductionXml.rules(game).size()
                    + " rules. Choose a frontier to see what it "
                    + (trade == Trade.BUY ? "sells." : "repairs."))
            .end();
        return;
      }
      final String frontier = selected;
      inspector.title(frontier);
      inspector.section(
          "Nations",
          (trade == Trade.BUY ? "Who buys from this frontier." : "Who repairs with this frontier.")
              + " A nation uses one; untick for none.");
      for (final String nation : MapXml.nations(game)) {
        final Optional<String> now = ProductionXml.frontierOf(game, nation);
        final JCheckBox box =
            new JCheckBox(
                nation
                    + now.filter(f -> !f.equals(frontier))
                        .map(f -> " (uses " + f + ")")
                        .orElse(""),
                now.filter(frontier::equals).isPresent());
        box.addActionListener(
            e ->
                edit(
                    g ->
                        box.isSelected()
                            ? ProductionXml.withFrontierOf(g, nation, frontier)
                            : ProductionXml.withoutFrontierOf(g, nation)));
        inspector.row(box);
      }
      inspector.section(
          trade == Trade.BUY ? "What it sells" : "What it repairs",
          trade == Trade.BUY
              ? "Every rule of the game; tick those sold here. A rule gives an amount of a unit or"
                  + " resource for its costs. Double-click a value to change it; rules are shared"
                  + " by all frontiers."
              : "Every repair rule of the game; tick those used here. A rule repairs an amount of"
                  + " damage to a unit for its costs, eg 1 for 1 PU. Double-click a value to change"
                  + " it; rules are shared by all frontiers.");
      final RulesTable model = new RulesTable(project, trade, frontier);
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
            public String getToolTipText(final MouseEvent e) {
              final int column = columnAtPoint(e.getPoint());
              return column >= RulesTable.FIXED
                  ? "What it costs in " + table.getColumnName(column)
                  : null;
            }
          });
      table.setRowHeight(table.getRowHeight() + 8);
      // the box and the numbers as wide as their headers, the names take the rest
      final FontMetrics header = table.getFontMetrics(table.getTableHeader().getFont());
      for (int i = 0; i < table.getColumnCount(); i++) {
        final TableColumn column = table.getColumnModel().getColumn(i);
        if (i == 1 || i == 2) {
          column.setMinWidth(120);
          column.setPreferredWidth(200);
        } else {
          final int width = Math.max(50, header.stringWidth(table.getColumnName(i)) + 24);
          column.setMinWidth(width);
          column.setPreferredWidth(width);
          column.setMaxWidth(width + 30);
        }
      }
      table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
      table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      table.putClientProperty("terminateEditOnFocusLost", true);
      final JComboBox<String> gives = new JComboBox<>();
      things(game).forEach(gives::addItem);
      final DefaultCellEditor givesEditor = new DefaultCellEditor(gives);
      givesEditor.setClickCountToStart(2);
      table.getColumnModel().getColumn(2).setCellEditor(givesEditor);
      rulesShown = Optional.of(table);
      table.setPreferredScrollableViewportSize(
          new Dimension(leastWidth(table), table.getPreferredSize().height));
      final JScrollPane rules =
          new JScrollPane(
              table,
              JScrollPane.VERTICAL_SCROLLBAR_NEVER,
              JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      rules.setColumnHeaderView(table.getTableHeader());
      // every row shown, and room for the bar when the columns scroll; GridBag would else shrink it
      final Dimension size =
          new Dimension(
              leastWidth(table),
              table.getPreferredSize().height
                  + table.getTableHeader().getPreferredSize().height
                  + rules.getHorizontalScrollBar().getPreferredSize().height
                  + 2);
      rules.setPreferredSize(size);
      rules.setMinimumSize(new Dimension(100, size.height));
      inspector.row(rules);

      final JButton addRule = new JButton("Add rule...");
      addRule.setToolTipText(
          trade == Trade.BUY
              ? "A new rule sold here, for one unit or resource you choose"
              : "A new rule used here, to repair a unit type you choose");
      addRule.addActionListener(e -> addRule(frontier));
      final JButton removeRule = new JButton("Remove rule");
      removeRule.addActionListener(
          e -> {
            if (table.getSelectedRow() >= 0) {
              removeRule(model.rule(table.getSelectedRow()).getName());
            }
          });
      final JButton remove = new JButton("Remove frontier");
      remove.setToolTipText("Removes this frontier; its nations then use none");
      remove.addActionListener(e -> removeFrontier(frontier));
      final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
      buttons.add(addRule);
      buttons.add(
          Help.with(removeRule, "Removes the chosen rule from the game and every frontier."));
      buttons.add(remove);
      inspector.row(buttons);
      inspector.end();
    }

    /** The width of the table's columns at their least. */
    private static int leastWidth(final JTable table) {
      int width = 0;
      for (int i = 0; i < table.getColumnCount(); i++) {
        width += table.getColumnModel().getColumn(i).getMinWidth();
      }
      return width;
    }

    /** What a rule may give: units and resources to buy, units to repair. */
    private Stream<String> things(final Game game) {
      return trade == Trade.BUY
          ? Stream.concat(
              MapXml.unitTypes(game).stream(), ProductionXml.resources(game).stream())
          : MapXml.unitTypes(game).stream();
    }

    /** Asks what the new rule gives, and adds it to the frontier at the unit's cost. */
    private void addRule(final String frontier) {
      final Game game = view();
      final Object[] choices = things(game).toArray();
      if (choices.length == 0) {
        return;
      }
      final Object chosen =
          JOptionPane.showInputDialog(
              page,
              trade == Trade.BUY ? "What the new rule sells:" : "What the new rule repairs:",
              "Add rule",
              JOptionPane.PLAIN_MESSAGE,
              null,
              choices,
              choices[0]);
      if (chosen != null) {
        final String thing = (String) chosen;
        if (trade == Trade.BUY) {
          final int cost = MapXml.unitCost(game, thing).orElse(1);
          edit(g -> ProductionXml.withNewRule(g, frontier, thing, cost));
        } else {
          edit(g -> ProductionXml.withNewRule(g, frontier, thing, 1, "repair"));
        }
      }
    }

    private void removeRule(final String rule) {
      if (RulesXml.isNamedInAttachments(project.game(), rule)) {
        warn(page, rule + " is named in an attachment, eg a trigger; it stays.", "Remove rule");
      } else {
        edit(g -> ProductionXml.withoutRule(g, rule));
      }
    }

    private void removeFrontier(final String frontier) {
      if (RulesXml.isNamedInAttachments(project.game(), frontier)) {
        warn(
            page,
            frontier + " is named in an attachment, eg a trigger; it stays.",
            "Remove frontier");
      } else {
        edit(g -> ProductionXml.withoutFrontier(g, frontier));
      }
    }
  }

  /** Every rule of the game: used in the frontier, what it gives, and a cost per resource. */
  private static final class RulesTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    static final int FIXED = 4;
    private final transient MapProject project;
    private final Trade trade;
    private final String frontier;

    RulesTable(final MapProject project, final Trade trade, final String frontier) {
      this.project = project;
      this.trade = trade;
      this.frontier = frontier;
    }

    private Game view() {
      return trade.view(project.game());
    }

    private void edit(final UnaryOperator<Game> change) {
      project.edit(trade.edit(change));
    }

    ProductionRule rule(final int row) {
      return ProductionXml.rules(view()).get(row);
    }

    /** The resources rules cost, and PUs: the cost columns, few even in a game with many. */
    private List<String> resources() {
      final Game game = view();
      final List<ProductionRule> rules = ProductionXml.rules(game);
      return ProductionXml.resources(game).stream()
          .filter(
              r ->
                  MapXml.PUS.equals(r)
                      || rules.stream().anyMatch(rule -> ProductionXml.cost(rule, r) != 0))
          .toList();
    }

    @Override
    public int getRowCount() {
      return ProductionXml.rules(view()).size();
    }

    @Override
    public int getColumnCount() {
      return FIXED + resources().size();
    }

    @Override
    public String getColumnName(final int column) {
      return column < FIXED
          ? List.of(
                  trade == Trade.BUY ? "Sold here" : "Used here",
                  "Rule",
                  trade == Trade.BUY ? "Gives" : "Repairs",
                  "Amount")
              .get(column)
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
        case 0 -> ProductionXml.rulesIn(view(), frontier).contains(rule.getName());
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
            edit(
                g ->
                    (Boolean) value
                        ? ProductionXml.withRuleIn(g, frontier, name)
                        : ProductionXml.withoutRuleIn(g, frontier, name));
        case 2 ->
            result.ifPresent(
                r -> edit(g -> ProductionXml.withResult(g, name, (String) value, r.getQuantity())));
        case 3 ->
            result.ifPresent(
                r ->
                    edit(
                        g ->
                            ProductionXml.withResult(
                                g, name, r.getResourceOrUnit(), Math.max(1, (Integer) value))));
        default -> {
          final String resource = resources().get(column - FIXED);
          edit(g -> ProductionXml.withCost(g, name, resource, Math.max(0, (Integer) value)));
        }
      }
    }
  }

  /** The resources with their icons, and what each nation has of them at the start. */
  private static final class ResourcesPage {
    private static final int ICON_SIZE = 20;
    private final MapProject project;
    private final Form form = new Form(420);
    private final JComponent page = SwingComponents.newJScrollPane(form);
    private Optional<JTable> startShown = Optional.empty();

    ResourcesPage(final MapProject project) {
      this.project = project;
      project.onChange(this::show);
      show();
    }

    void commit() {
      startShown.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
    }

    private void show() {
      form.clear();
      final Game game = project.game();
      form.title(
          "Resources",
          "What nations pay with and collect, eg PUs or tech tokens. An icon shows a resource in"
              + " the game; without one the game draws its first letters.");
      final List<String> all = ProductionXml.resources(game);
      final FontMetrics metrics = form.getFontMetrics(form.getFont());
      // the names in one column, their buttons beside them
      final int nameWidth =
          all.stream().mapToInt(metrics::stringWidth).max().orElse(0) + ICON_SIZE + 24;
      for (final String resource : all) {
        final JLabel name = new JLabel(resource, icon(resource), JLabel.LEADING);
        name.setPreferredSize(new Dimension(nameWidth, ICON_SIZE + 4));
        final JButton rename = new JButton("Rename...");
        rename.addActionListener(e -> rename(resource));
        final JButton icon = new JButton("Icon...");
        icon.setToolTipText("A picture for " + resource + ", written into resources/");
        icon.setEnabled(project.getFiles().folder().isPresent());
        icon.addActionListener(e -> chooseIcon(resource));
        final JButton remove = new JButton("×");
        remove.setToolTipText("Remove " + resource);
        remove.addActionListener(e -> remove(resource));
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(name);
        row.add(rename);
        row.add(icon);
        row.add(remove);
        form.row(row);
      }
      final JButton add = new JButton("Add resource...");
      add.addActionListener(e -> add());
      final JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      addRow.add(add);
      form.row(addRow);

      form.section(
          "At the start",
          "What each nation has when the game starts. Double-click a number to change it.");
      final JTable start = new JTable(new StartTable(project));
      start.setRowHeight(start.getRowHeight() + 8);
      start.putClientProperty("terminateEditOnFocusLost", true);
      startShown = Optional.of(start);
      final JPanel table = new JPanel(new BorderLayout());
      table.add(start.getTableHeader(), BorderLayout.NORTH);
      table.add(start, BorderLayout.CENTER);
      form.row(table);
      form.end();
    }

    /** The resource's icon in the map's folder, as the game shows it. */
    /** The resource's icon in the map's folder at the size the game shows it, else a gap. */
    private ImageIcon icon(final String resource) {
      final BufferedImage icon =
          new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
      project
          .getFiles()
          .folder()
          .map(f -> f.resolve("resources").resolve(resource + ".png"))
          .filter(Files::isRegularFile)
          .flatMap(ResourcesPage::read)
          .ifPresent(
              picture -> {
                final Graphics2D g = icon.createGraphics();
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                final double scale =
                    ICON_SIZE / (double) Math.max(picture.getWidth(), picture.getHeight());
                final int width = (int) Math.round(picture.getWidth() * scale);
                final int height = (int) Math.round(picture.getHeight() * scale);
                g.drawImage(
                    picture,
                    (ICON_SIZE - width) / 2,
                    (ICON_SIZE - height) / 2,
                    width,
                    height,
                    null);
                g.dispose();
              });
      return new ImageIcon(icon);
    }

    private static Optional<BufferedImage> read(final Path file) {
      try {
        return Optional.ofNullable(ImageIO.read(file.toFile()));
      } catch (final IOException e) {
        return Optional.empty();
      }
    }

    private void add() {
      final String typed =
          JOptionPane.showInputDialog(
              page, "Name of the new resource:", "Add resource", JOptionPane.PLAIN_MESSAGE);
      if (typed == null) {
        return;
      }
      final String resource = typed.strip();
      if (!project.isFreeName(resource)
          || ProductionXml.withResource(project.game(), resource) == project.game()) {
        warn(page, "The name must be new, without : < or (.", "Add resource");
        return;
      }
      project.edit(g -> ProductionXml.withResource(g, resource));
    }

    private void rename(final String resource) {
      final Optional<String> why = ProductionXml.whyResourceNotRenamed(project.game(), resource);
      if (why.isPresent()) {
        warn(page, why.get(), "Rename " + resource);
        return;
      }
      final Object typed =
          JOptionPane.showInputDialog(
              page,
              "New name of " + resource + ":",
              "Rename " + resource,
              JOptionPane.PLAIN_MESSAGE,
              null,
              null,
              resource);
      if (typed == null || typed.toString().strip().equals(resource)) {
        return;
      }
      final String renamed = typed.toString().strip();
      if (!project.isFreeName(renamed)
          || ProductionXml.withRenamedResource(project.game(), resource, renamed)
              == project.game()) {
        warn(page, "The name must be new, without : < or (.", "Rename " + resource);
        return;
      }
      project.edit(g -> ProductionXml.withRenamedResource(g, resource, renamed));
    }

    private void remove(final String resource) {
      final Optional<String> why = ProductionXml.whyResourceStays(project.game(), resource);
      if (why.isPresent()) {
        warn(page, why.get(), "Remove " + resource);
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
        warn(page, "That file is no picture.", "Icon of " + resource);
        return;
      }
      try {
        MapFolder.writeResourceIcon(folder.get(), resource, picture.get());
      } catch (final IOException e) {
        warn(page, "Could not write the icon: " + e.getMessage(), "Icon of " + resource);
      }
      show();
    }
  }

  /** The nations, each with what it has of every resource at the start. */
  private static final class StartTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;

    StartTable(final MapProject project) {
      this.project = project;
    }

    private List<String> resources() {
      return ProductionXml.resources(project.game());
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
