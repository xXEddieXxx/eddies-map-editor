package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.Serial;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The politics: for the chosen nation how it starts with each other nation, and the relationship
 * types, eg war, allied or neutral. Alliances are set in Nations.
 */
final class PoliticsEditor implements Editor {
  private static final String BY_ALLIANCE = "By alliance";
  private static final List<String> ARCHETYPES =
      List.of(
          Constants.RELATIONSHIP_ARCHETYPE_WAR,
          Constants.RELATIONSHIP_ARCHETYPE_ALLIED,
          Constants.RELATIONSHIP_ARCHETYPE_NEUTRAL);

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final TypesTable types;
  private final JTable typesTable;
  private final StartTable start;
  private final JTable startTable;
  private final JComboBox<String> startChoices = new JComboBox<>();
  private final JLabel startTitle = new JLabel();
  private List<String> typesShown = List.of();

  PoliticsEditor(final MapProject project) {
    this.project = project;
    types = new TypesTable(project);
    typesTable = newTable(types);
    typesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    typesTable
        .getColumnModel()
        .getColumn(1)
        .setCellEditor(new DefaultCellEditor(new JComboBox<>(ARCHETYPES.toArray(String[]::new))));
    final JButton add = new JButton("Add type...");
    add.setToolTipText("A new relationship type, at heart war");
    add.addActionListener(e -> addType());
    final JButton remove = new JButton("Remove type");
    remove.setToolTipText("Removes the chosen type; its nations go by alliance");
    remove.addActionListener(e -> removeType());
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    buttons.add(add);
    buttons.add(remove);
    start = new StartTable(project);
    startTable = newTable(start);
    // the cell's value is always one of the choices, so the box shows it, not the last one chosen
    final DefaultCellEditor startEditor = new DefaultCellEditor(startChoices);
    startEditor.setClickCountToStart(2);
    startTable.getColumnModel().getColumn(1).setCellEditor(startEditor);
    startTable
        .getColumnModel()
        .getColumn(1)
        .setCellRenderer(new ByAllianceRenderer(project, start));

    final Form form = new Form(280);
    form.section(
        "At the start",
        "How the chosen nation starts with each other nation. By alliance: allied when they share"
            + " an alliance, else at war; alliances are set in Nations. Double-click to choose a"
            + " type.");
    startTitle.setFont(startTitle.getFont().deriveFont(Font.BOLD));
    form.row(startTitle);
    form.row(withHeader(startTable));
    form.section(
        "Relationship types",
        "The kinds of relationship nations can be in. Each is at heart war, allied or neutral; its"
            + " other settings stay as the game has them.");
    form.row(withHeader(typesTable));
    form.row(buttons);
    form.end();
    final SearchList nations =
        new SearchList(
            "Search nation",
            () -> MapXml.nations(project.game()),
            nation -> String.join(", ", MapXml.alliances(project.game(), nation)),
            nation -> {
              start.nation = nation;
              refresh();
            });
    // the first nation's, so the table is not empty before one is chosen
    start.chosen().ifPresent(nations::choose);
    page.add(
        Panels.sideBySide(nations, SwingComponents.newJScrollPane(form)), BorderLayout.CENTER);
    project.onChange(nations::refresh);
    project.onChange(this::refresh);
    refresh();
  }

  /** The table with its header above it, as it sits in the form, not in a scroll pane. */
  private static JPanel withHeader(final JTable table) {
    final JPanel panel = new JPanel(new BorderLayout());
    panel.add(table.getTableHeader(), BorderLayout.NORTH);
    panel.add(table, BorderLayout.CENTER);
    return panel;
  }

  private static JTable newTable(final AbstractTableModel model) {
    final JTable table = new JTable(model);
    table.setRowHeight(table.getRowHeight() + 8);
    table.putClientProperty("terminateEditOnFocusLost", true);
    return table;
  }

  /**
   * Shows the changed game. The choices are made again only when the types changed: this runs
   * while a cell's edit is being taken, which a new editor would end.
   */
  private void refresh() {
    final Game game = project.game();
    types.fireTableDataChanged();
    if (!RulesXml.relationshipTypes(game).equals(typesShown)) {
      typesShown = RulesXml.relationshipTypes(game);
      startChoices.removeAllItems();
      startChoices.addItem(BY_ALLIANCE);
      typesShown.forEach(startChoices::addItem);
    }
    start.fireTableDataChanged();
    startTitle.setText(start.chosen().map(n -> n + " starts with").orElse("No nations yet."));
  }

  @Override
  public String title() {
    return "Politics";
  }

  @Override
  public Glyph glyph() {
    return Glyph.POLITICS;
  }

  @Override
  public String hint() {
    return "How nations stand to each other at the start: at war, allied or neutral, by their"
        + " alliance or by a relationship type.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    for (final JTable table : List.of(typesTable, startTable)) {
      if (table.isEditing()) {
        table.getCellEditor().stopCellEditing();
      }
    }
  }

  private void addType() {
    final String name =
        JOptionPane.showInputDialog(
            page,
            "Name of the new relationship type (at heart war; change it in the list):",
            "Add type",
            JOptionPane.PLAIN_MESSAGE);
    if (name == null) {
      return;
    }
    final String type = name.strip();
    final Game game = project.game();
    if (RulesXml.withRelationshipType(game, type, Constants.RELATIONSHIP_ARCHETYPE_WAR) == game) {
      warn("The name must not be empty or another type's.", "Add type");
      return;
    }
    project.edit(g -> RulesXml.withRelationshipType(g, type, Constants.RELATIONSHIP_ARCHETYPE_WAR));
  }

  private void removeType() {
    final int row = typesTable.getSelectedRow();
    if (row < 0) {
      return;
    }
    final String type = RulesXml.relationshipTypes(project.game()).get(row);
    // its own attachment names it too; others, eg political actions, keep it
    if (RulesXml.isNamedInAttachments(
        RulesXml.withoutRelationshipType(project.game(), type), type)) {
      warn(type + " is named in an attachment, eg a political action; it stays.", "Remove type");
    } else {
      project.edit(g -> RulesXml.withoutRelationshipType(g, type));
    }
  }

  private void warn(final String message, final String title) {
    JOptionPane.showMessageDialog(page, message, title, JOptionPane.WARNING_MESSAGE);
  }

  private static final class TypesTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;

    TypesTable(final MapProject project) {
      this.project = project;
    }

    @Override
    public int getRowCount() {
      return RulesXml.relationshipTypes(project.game()).size();
    }

    @Override
    public int getColumnCount() {
      return 2;
    }

    @Override
    public String getColumnName(final int column) {
      return List.of("Type", "Kind").get(column);
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      return column == 1;
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final String type = RulesXml.relationshipTypes(project.game()).get(row);
      return column == 0 ? type : RulesXml.archetype(project.game(), type);
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      final String type = RulesXml.relationshipTypes(project.game()).get(row);
      if (value != null && !value.equals(RulesXml.archetype(project.game(), type))) {
        project.edit(g -> RulesXml.withArchetype(g, type, (String) value));
      }
    }
  }

  /** A row per other nation: how the chosen nation starts with it. */
  private static final class StartTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;
    private @Nullable String nation;

    StartTable(final MapProject project) {
      this.project = project;
    }

    /** The chosen nation; the first while none is chosen or the chosen one is gone. */
    Optional<String> chosen() {
      final List<String> nations = MapXml.nations(project.game());
      return Optional.ofNullable(nation)
          .filter(nations::contains)
          .or(() -> nations.stream().findFirst());
    }

    String nation() {
      return chosen().orElseThrow();
    }

    List<String> others() {
      return chosen()
          .map(c -> MapXml.nations(project.game()).stream().filter(n -> !n.equals(c)).toList())
          .orElse(List.of());
    }

    @Override
    public int getRowCount() {
      return others().size();
    }

    @Override
    public int getColumnCount() {
      return 2;
    }

    @Override
    public String getColumnName(final int column) {
      return column == 0 ? "With" : "Starts as";
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      return column == 1;
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final String other = others().get(row);
      return column == 0
          ? other
          : RulesXml.relationship(project.game(), nation(), other).orElse(BY_ALLIANCE);
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      final String chosen = nation();
      final String other = others().get(row);
      if (value == null || value.equals(getValueAt(row, column))) {
        return;
      }
      project.edit(
          g ->
              BY_ALLIANCE.equals(value)
                  ? RulesXml.withoutRelationship(g, chosen, other)
                  : RulesXml.withRelationship(g, chosen, other, (String) value));
    }
  }

  /** Shows what "By alliance" comes to for the two nations: allied or war. */
  private static final class ByAllianceRenderer extends DefaultTableCellRenderer {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;
    private final StartTable start;

    ByAllianceRenderer(final MapProject project, final StartTable start) {
      this.project = project;
      this.start = start;
    }

    @Override
    public Component getTableCellRendererComponent(
        final JTable table,
        final Object value,
        final boolean selected,
        final boolean focused,
        final int row,
        final int column) {
      final Object shown;
      if (BY_ALLIANCE.equals(value)) {
        final Game game = project.game();
        final boolean allied =
            MapXml.alliances(game, start.nation()).stream()
                .anyMatch(MapXml.alliances(game, start.others().get(row))::contains);
        shown = BY_ALLIANCE + (allied ? ": allied" : ": war");
      } else {
        shown = value;
      }
      return super.getTableCellRendererComponent(table, shown, selected, focused, row, column);
    }
  }
}
