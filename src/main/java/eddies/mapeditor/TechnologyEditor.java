package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.io.Serial;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Technology.Technologies.TechName;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The technologies: adding and removing them, the research chart each nation researches one in,
 * and the built-in ones a nation has at the start. A game without a list shows TripleA's standard
 * technologies; the first change writes them into the game with it, as one step.
 */
final class TechnologyEditor implements Editor {
  private static final String NOT_RESEARCHED = "Not researched";
  private static final String AT_START_TIP =
      "Only TripleA's built-in technologies can be given at the start.";
  private static final int TABLE_WIDTH = 620;
  private static final int DETAILS_WIDTH = 760;

  private final MapProject project;
  private final Consumer<String> openTool;
  private final JPanel page = new JPanel(new BorderLayout(Theme.GAP_S, 0));
  private final SearchList list;
  private final JLabel standardNote =
      Theme.newSecondaryLabel(
          "<html><div style='width:" + Theme.htmlWidth(260) + "'>This game uses TripleA's standard technologies."
              + " Changing one writes the list into the game.</div></html>");
  private final JComponent standardLink =
      Help.link("Which ones: WW2V3 Tech Model in", "Game options");
  private final Form details = new Form(420);
  private @Nullable String selected;
  private Optional<JTable> nationsShown = Optional.empty();
  // withStandardTechs builds a GameData: once per game, not per row
  private @Nullable Game listedFor;
  private @Nullable Game listed;

  TechnologyEditor(final MapProject project) {
    this(project, tool -> {});
  }

  /**
   * @param openTool switches to the tool of the title, eg "Attachments"
   */
  TechnologyEditor(final MapProject project, final Consumer<String> openTool) {
    this.project = project;
    this.openTool = openTool;
    list =
        new SearchList(
                "technology",
                "technologies",
                this::names,
                t -> t,
                this::rowDetail,
                this::choose)
            .withAdd("Add", "Add a technology", this::addTech)
            .withEmpty(
                Panels.empty(
                    Glyph.TECHNOLOGY,
                    "No technologies",
                    "Nations can research nothing in this game.",
                    "Add technology",
                    this::addTech));
    standardNote.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_S, Theme.GAP_S));
    final JPanel listColumn = new JPanel(new BorderLayout());
    final JPanel standard = new JPanel(new BorderLayout());
    standard.add(standardNote, BorderLayout.CENTER);
    standard.add(standardLink, BorderLayout.SOUTH);
    listColumn.add(standard, BorderLayout.NORTH);
    listColumn.add(list, BorderLayout.CENTER);
    details.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(final ComponentEvent e) {
            // the content at most 760 px wide, at the left: the rest a right margin
            details.setBorder(
                BorderFactory.createEmptyBorder(
                    Theme.GAP_XS,
                    Theme.GAP_L,
                    Theme.GAP_L,
                    Math.max(Theme.GAP_L, details.getWidth() - Theme.GAP_L - DETAILS_WIDTH)));
          }
        });
    page.add(
        Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(details)),
        BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          showDetails();
        });
    // the first technology chosen on open
    showDetails();
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
    return "Technologies nations can research. Give each nation a research chart for it;"
        + " leave Not researched where a nation should not get it.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    nationsShown.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
  }

  /** The game's technologies, or TripleA's standard ones when it has no list; nothing written. */
  private Game listed() {
    final Game game = project.game();
    if (game != listedFor || listed == null) {
      listedFor = game;
      listed = TechXml.withStandardTechs(game);
    }
    return listed;
  }

  List<String> names() {
    return TechXml.techNames(listed());
  }

  private boolean isStandard() {
    return project.game().getTechnology() == null;
  }

  private String rowDetail(final String name) {
    if (isStandard()) {
      return "Standard";
    }
    return techOf(name)
        .flatMap(TechXml::builtIn)
        .map(b -> b.equals(name) ? "Built-in" : "Built-in as " + b)
        .orElse("Own");
  }

  private Optional<TechName> techOf(final String name) {
    return TechXml.techs(listed()).stream().filter(t -> name.equals(t.getName())).findFirst();
  }

  private void choose(final String tech) {
    selected = tech;
    showDetails();
  }

  /**
   * The nation researches the technology in the chart, a new one if the nation has none of the
   * name; not at all for "Not researched" or blank. A game without a list gets the standard one
   * with it, as one step.
   */
  void setChart(final String nation, final String tech, final String chart) {
    final String name = chart.strip();
    project.edit(
        g ->
            name.isEmpty() || NOT_RESEARCHED.equals(name)
                ? TechXml.withoutTechIn(TechXml.withStandardTechs(g), nation, tech)
                : TechXml.withTechIn(TechXml.withStandardTechs(g), nation, tech, name));
  }

  private void addTech() {
    final JRadioButton builtInKind = new JRadioButton("TripleA built-in", true);
    final JRadioButton ownKind = new JRadioButton("Own (its effects come from attachments)");
    final ButtonGroup kinds = new ButtonGroup();
    kinds.add(builtInKind);
    kinds.add(ownKind);
    final JComboBox<String> builtIn = new JComboBox<>(TechXml.BUILT_IN.toArray(String[]::new));
    final JTextField name = new JTextField(20);
    final FieldError error = new FieldError(name);
    final Runnable fill =
        () -> {
          builtIn.setEnabled(builtInKind.isSelected());
          if (name.getText().isBlank() && builtInKind.isSelected()) {
            name.setText((String) builtIn.getSelectedItem());
          }
        };
    builtIn.addActionListener(e -> fill.run());
    builtInKind.addActionListener(e -> fill.run());
    ownKind.addActionListener(e -> fill.run());
    OnTyping.attach(
        name,
        () ->
            error.setProblem(
                names().contains(name.getText().strip())
                    ? Optional.of("Already a technology")
                    : Optional.empty()));
    fill.run();
    final JPanel builtInRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    builtInRow.add(builtInKind);
    builtInRow.add(builtIn);
    final JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    for (final JComponent part :
        List.<JComponent>of(
            new JLabel("Kind"), builtInRow, ownKind, new JLabel("Name"), name, error)) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      content.add(part);
      content.add(Box.createVerticalStrut(Theme.GAP_XS));
    }
    name.setMaximumSize(new Dimension(Integer.MAX_VALUE, name.getPreferredSize().height));
    if (!Dialogs.ask(
        page,
        "Add technology",
        content,
        "Add",
        () -> !name.getText().isBlank() && !names().contains(name.getText().strip()))) {
      return;
    }
    final String tech = name.getText().strip();
    final String engineTech =
        builtInKind.isSelected() ? (String) builtIn.getSelectedItem() : null;
    project.edit(g -> TechXml.withTech(g, tech, engineTech));
    choose(tech);
    list.choose(tech);
  }

  private void showDetails() {
    standardNote.setVisible(isStandard());
    standardLink.setVisible(isStandard());
    details.clear();
    nationsShown = Optional.empty();
    if (selected == null || techOf(selected).isEmpty()) {
      selected = names().stream().findFirst().orElse(null);
      list.choose(selected);
    }
    final Optional<TechName> tech = Optional.ofNullable(selected).flatMap(this::techOf);
    if (tech.isEmpty()) {
      details.end();
      details.revalidate();
      details.repaint();
      return;
    }
    final String name = tech.get().getName();
    final Optional<String> builtIn = TechXml.builtIn(tech.get());
    final List<String> nations = MapXml.nations(project.game());
    final long researching =
        nations.stream().filter(n -> TechXml.categoryOf(listed(), n, name).isPresent()).count();
    details.title(name);
    details.subtitle(
        builtIn
            .map(
                b ->
                    "TripleA built-in · researched by "
                        + researching
                        + " nations · "
                        + nations.stream()
                            .filter(n -> TechXml.hasAtStart(project.game(), n, b))
                            .count()
                        + " have it at the start")
            .orElse("Own technology · researched by " + researching + " nations"));
    details.row(nationsTable(name, builtIn));
    details.section("Its effects");
    details.row(effects(name, builtIn));
    details.remove("Remove technology", () -> removeTech(name));
    details.end();
    details.revalidate();
    details.repaint();
  }

  private JComponent nationsTable(final String tech, final Optional<String> builtIn) {
    final JTable table = new JTable(new NationsTable(this, tech, builtIn));
    table.setTableHeader(new AtStartHeader(table));
    table.setRowHeight(table.getRowHeight() + Theme.GAP_S);
    table.putClientProperty("terminateEditOnFocusLost", true);
    final JComboBox<String> charts = new JComboBox<>();
    charts.setEditable(true);
    charts.setToolTipText("Type a name for a new chart.");
    charts.addItem(NOT_RESEARCHED);
    MapXml.nations(project.game()).stream()
        .flatMap(n -> TechXml.categories(listed(), n).stream())
        .distinct()
        .forEach(charts::addItem);
    Details.comboColumn(table, 1, charts);
    if (builtIn.isPresent()) {
      // a tick needs little room, the chart more
      table.getColumnModel().getColumn(2).setPreferredWidth(120);
      table.getColumnModel().getColumn(2).setMaxWidth(150);
    }
    nationsShown = Optional.of(table);
    final JPanel nations = new JPanel(new BorderLayout());
    nations.add(table.getTableHeader(), BorderLayout.NORTH);
    nations.add(table, BorderLayout.CENTER);
    // at most 620 px at the left, narrower in a narrow window
    final int height = nations.getPreferredSize().height;
    nations.setPreferredSize(new Dimension(TABLE_WIDTH, height));
    nations.setMaximumSize(new Dimension(TABLE_WIDTH, height));
    final JPanel holder = new JPanel();
    holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
    holder.add(nations);
    holder.add(Box.createHorizontalGlue());
    return holder;
  }

  private JComponent effects(final String tech, final Optional<String> builtIn) {
    final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    if (builtIn.isPresent()) {
      line.add(new JLabel("Built into TripleA."));
      return line;
    }
    final List<String> setBy =
        AttachmentsXml.attachments(project.game()).stream()
            .filter(a -> tech.equals(a.getAttachTo()))
            .filter(a -> "TechAbilityAttachment".equals(AttachmentsXml.kind(a)))
            .map(Attachment::getName)
            .toList();
    if (setBy.isEmpty()) {
      line.add(new JLabel("None yet: add a techAbilityAttachment in Attachments."));
      return line;
    }
    line.add(new JLabel("Set by " + String.join(", ", setBy)));
    final JButton open = new JButton("Open in Attachments ›");
    Theme.setTertiaryButtonStyle(open);
    open.addActionListener(e -> openTool.accept("Attachments"));
    line.add(open);
    return line;
  }

  private void removeTech(final String tech) {
    if (RulesXml.isNamedInAttachments(project.game(), tech)) {
      Dialogs.warn(
          page,
          "Remove technology",
          tech + " is named in an attachment, eg its techAbilityAttachment; it stays.");
    } else if (Dialogs.confirmRemove(
        page,
        "Remove technology",
        "Remove " + tech + "?\nIt is also taken out of every nation's research chart.",
        List.of(),
        "Remove")) {
      project.edit(g -> TechXml.withoutTech(TechXml.withStandardTechs(g), tech));
    }
  }

  /** The header, its "Has it at start" column telling why only built-in ones have it. */
  private static final class AtStartHeader extends JTableHeader {
    @Serial private static final long serialVersionUID = 1L;

    AtStartHeader(final JTable table) {
      super(table.getColumnModel());
      ToolTipManager.sharedInstance().registerComponent(this);
    }

    @Override
    public String getToolTipText(final MouseEvent event) {
      return columnAtPoint(event.getPoint()) == 2 ? AT_START_TIP : null;
    }
  }

  /** A row per nation: the chart it researches the technology in, and whether it has it. */
  private static final class NationsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient TechnologyEditor editor;
    private final String tech;
    private final transient Optional<String> builtIn;

    NationsTable(
        final TechnologyEditor editor, final String tech, final Optional<String> builtIn) {
      this.editor = editor;
      this.tech = tech;
      this.builtIn = builtIn;
    }

    private List<String> nations() {
      return MapXml.nations(editor.project.game());
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
      return List.of("Nation", "Research chart", "Has it at start").get(column);
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
      return switch (column) {
        case 0 -> nation;
        case 1 -> TechXml.categoryOf(editor.listed(), nation, tech).orElse(NOT_RESEARCHED);
        default -> TechXml.hasAtStart(editor.project.game(), nation, builtIn.orElseThrow());
      };
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      if (value == null || value.equals(getValueAt(row, column))) {
        return;
      }
      final String nation = nations().get(row);
      if (column == 1) {
        editor.setChart(nation, tech, String.valueOf(value));
      } else {
        final String engineTech = builtIn.orElseThrow();
        editor.project.edit(
            g ->
                (Boolean) value
                    ? TechXml.withAtStart(g, nation, engineTech)
                    : TechXml.withoutAtStart(g, nation, engineTech));
      }
    }
  }
}
