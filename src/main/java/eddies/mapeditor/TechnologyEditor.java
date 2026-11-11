package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Technology.Technologies.TechName;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The technologies: adding and removing them, the category each nation researches one in, and the
 * built-in ones a nation has at the start.
 */
final class TechnologyEditor implements Editor {
  private static final String OWN = "none: attachments give its effects";
  private static final String NOT_RESEARCHED = "";

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(280);
  private @Nullable String selected;
  private Optional<JTable> nationsShown = Optional.empty();

  TechnologyEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search technology",
            () -> TechXml.techNames(project.game()),
            name ->
                techOf(name)
                    .flatMap(TechXml::builtIn)
                    .map(b -> b.equals(name) ? "built in" : "built in: " + b)
                    .orElse("own"),
            this::choose);
    final JButton add = new JButton("Add technology...");
    add.setToolTipText("A new technology, your own or one of TripleA's built-in ones");
    add.addActionListener(e -> addTech());
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    final JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    addRow.add(add);
    listColumn.add(addRow, BorderLayout.SOUTH);
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
    return "Technology";
  }

  @Override
  public Glyph glyph() {
    return Glyph.TECHNOLOGY;
  }

  @Override
  public String hint() {
    return "The technologies nations can research, in which category each nation has them, and"
        + " which built-in ones a nation has at the start.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    nationsShown.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
  }

  private Optional<TechName> techOf(final String name) {
    return TechXml.techs(project.game()).stream()
        .filter(t -> name.equals(t.getName()))
        .findFirst();
  }

  private void choose(final String tech) {
    selected = tech;
    showInspector();
  }

  /** Asks the name and whether it is one of the engine's own technologies. */
  private void addTech() {
    final JTextField name = new JTextField(20);
    final JComboBox<String> builtIn = new JComboBox<>();
    builtIn.addItem(OWN);
    TechXml.BUILT_IN.forEach(builtIn::addItem);
    final JPanel ask = new JPanel(new GridLayout(0, 1, 0, 4));
    ask.add(new JLabel("Name of the new technology:"));
    ask.add(name);
    ask.add(new JLabel("Built-in technology it is:"));
    ask.add(builtIn);
    builtIn.addActionListener(
        e -> {
          if (name.getText().isBlank() && builtIn.getSelectedIndex() > 0) {
            name.setText((String) builtIn.getSelectedItem());
          }
        });
    if (JOptionPane.showConfirmDialog(
            page, ask, "Add technology", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        != JOptionPane.OK_OPTION) {
      return;
    }
    final String tech = name.getText().strip();
    final String engineTech =
        builtIn.getSelectedIndex() > 0 ? (String) builtIn.getSelectedItem() : null;
    final Game game = project.game();
    if (TechXml.withTech(game, tech, engineTech) == game) {
      JOptionPane.showMessageDialog(
          page,
          "The name must not be empty or another technology's.",
          "Add technology",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    project.edit(g -> TechXml.withTech(g, tech, engineTech));
    choose(tech);
    list.choose(tech);
  }

  private void showInspector() {
    inspector.clear();
    nationsShown = Optional.empty();
    final Game game = project.game();
    final Optional<TechName> tech = Optional.ofNullable(selected).flatMap(this::techOf);
    if (tech.isEmpty()) {
      inspector
          .note(
              game.getTechnology() == null
                  ? "This game has no list of technologies, so it has TripleA's standard ones."
                      + " Adding a technology writes them into the game first, so they stay."
                  : TechXml.techNames(game).size() + " technologies. Choose one to change it.")
          .end();
      return;
    }
    final String name = tech.get().getName();
    final Optional<String> builtIn = TechXml.builtIn(tech.get());
    inspector.title(name);
    inspector.note(
        builtIn
            .map(b -> "TripleA's built-in technology " + b + ".")
            .orElse(
                "Its own technology: attachments, eg techAbilityAttachment, give its effects."));
    inspector.section(
        "Nations",
        "The category, a research chart, each nation researches it in; empty for not at all. Type a"
            + " name for a new category."
            + (builtIn.isPresent() ? " Tick those that have it at the start." : ""));
    final NationsTable model = new NationsTable(project, name, builtIn);
    final JTable table = new JTable(model);
    table.setRowHeight(table.getRowHeight() + 8);
    table.putClientProperty("terminateEditOnFocusLost", true);
    final JComboBox<String> categories = new JComboBox<>();
    categories.setEditable(true);
    categories.addItem(NOT_RESEARCHED);
    MapXml.nations(game).stream()
        .flatMap(n -> TechXml.categories(game, n).stream())
        .distinct()
        .forEach(categories::addItem);
    table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(categories));
    if (builtIn.isPresent()) {
      // a tick needs little room, the category more
      table.getColumnModel().getColumn(2).setPreferredWidth(120);
      table.getColumnModel().getColumn(2).setMaxWidth(150);
    }
    nationsShown = Optional.of(table);
    final JPanel nations = new JPanel(new BorderLayout());
    nations.add(table.getTableHeader(), BorderLayout.NORTH);
    nations.add(table, BorderLayout.CENTER);
    inspector.row(nations);

    final JButton remove = new JButton("Remove technology");
    remove.setToolTipText("Removes it, also from each nation's categories. Undo takes it back");
    remove.addActionListener(e -> removeTech(name));
    final JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    removeRow.add(remove);
    inspector.row(removeRow);
    inspector.end();
  }

  private void removeTech(final String tech) {
    if (RulesXml.isNamedInAttachments(project.game(), tech)) {
      JOptionPane.showMessageDialog(
          page,
          tech + " is named in an attachment, eg its techAbilityAttachment; it stays.",
          "Remove technology",
          JOptionPane.WARNING_MESSAGE);
    } else {
      project.edit(g -> TechXml.withoutTech(g, tech));
    }
  }

  /** A row per nation: the category it researches the technology in, and whether it has it. */
  private static final class NationsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;
    private final String tech;
    private final transient Optional<String> builtIn;

    NationsTable(final MapProject project, final String tech, final Optional<String> builtIn) {
      this.project = project;
      this.tech = tech;
      this.builtIn = builtIn;
    }

    private List<String> nations() {
      return MapXml.nations(project.game());
    }

    @Override
    public int getRowCount() {
      return nations().size();
    }

    @Override
    public int getColumnCount() {
      return builtIn.isPresent() ? 3 : 2;
    }

    @Override
    public String getColumnName(final int column) {
      return List.of("Nation", "Researches it in", "Has it at the start").get(column);
    }

    @Override
    public Class<?> getColumnClass(final int column) {
      return column == 2 ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      return column > 0;
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final String nation = nations().get(row);
      final Game game = project.game();
      return switch (column) {
        case 0 -> nation;
        case 1 -> TechXml.categoryOf(game, nation, tech).orElse(NOT_RESEARCHED);
        default -> TechXml.hasAtStart(game, nation, builtIn.orElseThrow());
      };
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      if (value == null || value.equals(getValueAt(row, column))) {
        return;
      }
      final String nation = nations().get(row);
      if (column == 1) {
        final String category = String.valueOf(value).strip();
        project.edit(
            g ->
                category.isEmpty()
                    ? TechXml.withoutTechIn(g, nation, tech)
                    : TechXml.withTechIn(g, nation, tech, category));
      } else {
        final String engineTech = builtIn.orElseThrow();
        project.edit(
            g ->
                (Boolean) value
                    ? TechXml.withAtStart(g, nation, engineTech)
                    : TechXml.withoutAtStart(g, nation, engineTech));
      }
    }
  }
}
