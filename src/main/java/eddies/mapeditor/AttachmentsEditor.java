package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.Serial;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import lombok.experimental.ExtensionMethod;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * Every attachment of the game as it stands in its XML, for what has no tool of its own, eg
 * triggers and conditions: listed by kind, searchable, the chosen one's options in a table. Each
 * change is one step; options the editor does not know are kept as they are.
 */
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
final class AttachmentsEditor implements Editor {
  private static final String ALL_KINDS = "All kinds";
  private static final String SEPARATOR = " · ";

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 8));
  private final JComboBox<String> kinds = new JComboBox<>();
  private final SearchList list;
  private final Form inspector = new Form(360);
  private final JTextField name = new JTextField();
  private final JComboBox<String> attachTo = new JComboBox<>();
  private final JPanel attachToRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
  private final OptionsTable options = new OptionsTable();
  private final JTable table = new JTable(options);
  private final JScrollPane optionsScroll = SwingComponents.newJScrollPane(table);
  private final JComboBox<String> optionNames = new JComboBox<>();
  private @Nullable String selected;
  private boolean filling;
  private List<String> labels = List.of();
  private Map<String, Integer> indexes = Map.of();
  private @Nullable Game labelsOf;

  AttachmentsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search attachment",
            this::shownLabels,
            label -> index(label).map(i -> describe(attachment(i))).orElse(""),
            this::choose);
    kinds.setToolTipText("Which kind of attachments are listed");
    kinds.addActionListener(
        e -> {
          if (!filling) {
            list.refresh();
          }
        });
    final JButton add = new JButton("Add attachment...");
    add.setToolTipText("Adds an attachment of a kind you choose, eg a trigger, to the game");
    add.addActionListener(e -> addAttachment());
    final JButton remove = new JButton("Remove");
    remove.setToolTipText("Removes the chosen attachment from the game");
    remove.addActionListener(
        e -> chosen().ifPresent(i -> project.edit(g -> AttachmentsXml.withoutAttachment(g, i))));
    final JButton newTrigger = new JButton("New trigger...");
    newTrigger.setToolTipText("Makes a trigger step by step with the trigger assistant");
    newTrigger.addActionListener(e -> TriggerAssistant.openNew(page, project));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(add);
    buttons.add(remove);
    buttons.add(newTrigger);
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(kinds, BorderLayout.NORTH);
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(buttons, BorderLayout.SOUTH);

    name.addActionListener(e -> rename());
    name.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            rename();
          }
        });
    attachTo.setEditable(true);
    attachTo.addActionListener(e -> reattach());
    table.setRowHeight(table.getRowHeight() + 8);
    table.putClientProperty("terminateEditOnFocusLost", true);
    optionNames.setEditable(true);
    table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(optionNames));
    // the value is what is longest, eg a trigger's conditions
    table.getColumnModel().getColumn(0).setPreferredWidth(160);
    table.getColumnModel().getColumn(1).setPreferredWidth(400);
    // the count, short, beside the option's name instead of squeezed at the far right
    table.getColumnModel().getColumn(2).setMinWidth(70);
    table.getColumnModel().getColumn(2).setMaxWidth(70);
    table.moveColumn(2, 1);
    attachTo.setPreferredSize(new Dimension(260, attachTo.getPreferredSize().height));
    attachToRow.add(attachTo);
    optionsScroll.setColumnHeaderView(table.getTableHeader());
    page.add(
        Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(inspector)),
        BorderLayout.CENTER);
    project.onChange(this::refresh);
    refresh();
  }

  @Override
  public String title() {
    return "Attachments";
  }

  @Override
  public Glyph glyph() {
    return Glyph.ATTACHMENTS;
  }

  @Override
  public String hint() {
    return "Every attachment of the game: triggers, conditions, canals, actions and all the"
        + " others, with their options as the game file has them. For what no other tool shows.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    if (table.isEditing()) {
      table.getCellEditor().stopCellEditing();
    }
    if (name.hasFocus()) {
      rename();
    }
  }

  // ---- the list

  /** How the attachment shows in the list: its name and what it is attached to. */
  private static String label(final Attachment attachment) {
    return Objects.toString(attachment.getName(), "")
        + SEPARATOR
        + Objects.toString(attachment.getAttachTo(), "");
  }

  private static String describe(final Attachment attachment) {
    final int count = AttachmentsXml.options(attachment).size();
    return AttachmentsXml.kind(attachment) + ", " + count + (count == 1 ? " option" : " options");
  }

  /** The labels in the game's order, worked out once per change, as every row asks for them. */
  private List<String> labels() {
    if (labelsOf != project.game()) {
      labels = AttachmentsXml.attachments(project.game()).stream()
          .map(AttachmentsEditor::label)
          .toList();
      indexes = new HashMap<>();
      for (int i = labels.size() - 1; i >= 0; i--) {
        indexes.put(labels.get(i), i);
      }
      labelsOf = project.game();
    }
    return labels;
  }

  private List<String> shownLabels() {
    final Object kind = kinds.getSelectedItem();
    final List<Attachment> attachments = AttachmentsXml.attachments(project.game());
    final List<String> all = labels();
    return IntStream.range(0, all.size())
        .filter(
            i ->
                kind == null
                    || ALL_KINDS.equals(kind)
                    || kind.equals(AttachmentsXml.kind(attachments.get(i))))
        .mapToObj(all::get)
        .distinct()
        .toList();
  }

  /**
   * The place of the attachment with the label. shortcut: of two with the same name on the same
   * thing, which the game takes as one, the first; tell them apart if a map needs both.
   */
  private Optional<Integer> index(final String label) {
    labels();
    return Optional.ofNullable(indexes.get(label));
  }

  private Attachment attachment(final int index) {
    return AttachmentsXml.attachments(project.game()).get(index);
  }

  private Optional<Integer> chosen() {
    return Optional.ofNullable(selected).flatMap(this::index);
  }

  private void choose(final String label) {
    commit();
    selected = label;
    // the list stays as it is: only the inspector shows the chosen one
    filling = true;
    try {
      showInspector();
    } finally {
      filling = false;
    }
  }

  // ---- the chosen one

  private void refresh() {
    filling = true;
    try {
      final Object kind = kinds.getSelectedItem();
      kinds.removeAllItems();
      kinds.addItem(ALL_KINDS);
      AttachmentsXml.attachments(project.game()).stream()
          .map(AttachmentsXml::kind)
          .distinct()
          .sorted()
          .forEach(kinds::addItem);
      kinds.setSelectedItem(Optional.ofNullable(kind).orElse(ALL_KINDS));
      list.refresh();
      showInspector();
    } finally {
      filling = false;
    }
  }

  private void showInspector() {
    inspector.clear();
    final Optional<Integer> index = chosen();
    if (index.isEmpty()) {
      selected = null;
      inspector
          .title("Attachments")
          .note(
              AttachmentsXml.attachments(project.game()).size()
                  + " attachments. Choose one to see and change its options.")
          .end();
      options.show(List.of());
      return;
    }
    final Attachment attachment = attachment(index.get());
    final Game game = project.game();
    inspector.title(Objects.toString(attachment.getName(), ""));
    if (!name.hasFocus()) {
      name.setText(Objects.toString(attachment.getName(), ""));
    }
    attachTo.removeAllItems();
    AttachmentsXml.targets(game, Objects.toString(attachment.getType(), "unitType"))
        .forEach(attachTo::addItem);
    attachTo.setSelectedItem(attachment.getAttachTo());
    optionNames.removeAllItems();
    AttachmentsXml.knownOptions(game, AttachmentsXml.kind(attachment))
        .forEach(optionNames::addItem);
    final JButton addOption = new JButton("Add option");
    addOption.setToolTipText("Adds an option to the attachment, its name chosen from TripleA's");
    addOption.addActionListener(e -> addOption());
    final JButton removeOption = new JButton("Remove option");
    removeOption.setToolTipText("Removes the option chosen in the table");
    removeOption.addActionListener(e -> removeOption());
    final JPanel optionButtons = new JPanel(new GridLayout(1, 2, 4, 0));
    optionButtons.add(addOption);
    optionButtons.add(removeOption);
    if (TriggerXml.TRIGGER.equals(AttachmentsXml.kind(attachment))) {
      final JButton guided = new JButton("Guided...");
      guided.setToolTipText("Opens the trigger in the trigger assistant");
      guided.addActionListener(e -> TriggerAssistant.open(page, project, index.get()));
      optionButtons.add(guided);
    }
    final JPanel optionButtonsLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    optionButtonsLeft.add(optionButtons);
    table.setPreferredScrollableViewportSize(
        new Dimension(0, table.getRowHeight() * Math.max(6, options.getRowCount() + 1)));
    inspector
        .note(
            AttachmentsXml.kind(attachment)
                + ", attached to a "
                + Objects.toString(attachment.getType(), "unitType")
                + (attachment.getForeach() == null ? "" : ", for each " + attachment.getForeach()))
        .field("Name", name)
        .field("Attached to", attachToRow)
        .section(
            "Options",
            "What the attachment sets, as the game file has them. Double-click to change a name,"
                + " value or count; the names TripleA knows for this kind are offered. Check (F6)"
                + " tells whether the game still loads.")
        .row(optionsScroll)
        .row(optionButtonsLeft)
        .end();
    options.show(AttachmentsXml.options(attachment));
  }

  private void change(final Attachment changed) {
    chosen()
        .ifPresent(
            i -> {
              // eg an option without a name, which the change refused
              if (changed == attachment(i)) {
                return;
              }
              selected = label(changed);
              project.edit(g -> AttachmentsXml.withAttachment(g, i, changed));
            });
  }

  private void rename() {
    final String typed = name.getText().strip();
    chosen()
        .map(this::attachment)
        .filter(a -> !typed.isEmpty() && !typed.equals(a.getName()))
        .filter(
            a -> {
              // the old name back, so leaving the field does not warn again
              final boolean refused = refusedAsTaken(typed, a.getAttachTo());
              if (refused) {
                name.setText(a.getName());
              }
              return !refused;
            })
        .ifPresent(a -> change(a.toBuilder().name(typed).build()));
  }

  /** Whether the thing has an attachment of the name already, telling so. */
  private boolean refusedAsTaken(final String newName, final String target) {
    if (!AttachmentsXml.has(project.game(), newName, target)) {
      return false;
    }
    JOptionPane.showMessageDialog(
        page,
        target + " has an attachment " + newName + " already. Choose another name.",
        "Attachment name",
        JOptionPane.WARNING_MESSAGE);
    return true;
  }

  private void reattach() {
    if (filling) {
      return;
    }
    final String typed = Objects.toString(attachTo.getSelectedItem(), "").strip();
    chosen()
        .map(this::attachment)
        .filter(a -> !typed.isEmpty() && !typed.equals(a.getAttachTo()))
        .ifPresent(a -> change(a.toBuilder().attachTo(typed).build()));
  }

  private void addOption() {
    commit();
    final Optional<Attachment> attachment = chosen().map(this::attachment);
    if (attachment.isEmpty()) {
      return;
    }
    final JComboBox<String> optionName =
        new JComboBox<>(
            AttachmentsXml.knownOptions(project.game(), AttachmentsXml.kind(attachment.get()))
                .toArray(String[]::new));
    optionName.setEditable(true);
    final JTextField value = new JTextField(24);
    final JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
    Stream.of(new JLabel("Option"), optionName, new JLabel("Value"), value).forEach(form::add);
    final boolean ok = Panels.ask(page, form, "Add option");
    final String typedName = Objects.toString(optionName.getSelectedItem(), "").strip();
    if (ok && !typedName.isEmpty()) {
      change(
          AttachmentsXml.withNewOption(
              attachment.get(),
              Option.builder().name(typedName).value(value.getText().strip()).build()));
    }
  }

  private void removeOption() {
    commit();
    final int row = table.getSelectedRow();
    if (row >= 0) {
      chosen()
          .map(this::attachment)
          .ifPresent(a -> change(AttachmentsXml.withoutOption(a, row)));
    }
  }

  /** Offers the kinds TripleA knows and what each may be attached to. */
  private void addAttachment() {
    final JComboBox<String> kind =
        new JComboBox<>(AttachmentsXml.KINDS.keySet().stream().sorted().toArray(String[]::new));
    kind.setSelectedItem("TriggerAttachment");
    final JTextField newName = new JTextField(24);
    final JComboBox<String> target = new JComboBox<>();
    target.setEditable(true);
    final Runnable fillTargets =
        () -> {
          final String chosenKind = (String) kind.getSelectedItem();
          target.removeAllItems();
          AttachmentsXml.targets(project.game(), AttachmentsXml.KINDS.get(chosenKind))
              .forEach(target::addItem);
          newName.setText(AttachmentsXml.newName(project.game(), chosenKind));
        };
    kind.addActionListener(e -> fillTargets.run());
    fillTargets.run();
    final JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
    Stream.of(
            new JLabel("Kind"),
            kind,
            new JLabel("Name"),
            newName,
            new JLabel("Attached to"),
            target)
        .forEach(form::add);
    final boolean ok = Panels.ask(page, form, "Add attachment");
    final String typedName = newName.getText().strip();
    final String typedTarget = Objects.toString(target.getSelectedItem(), "").strip();
    if (!ok || typedName.isEmpty() || typedTarget.isEmpty()) {
      return;
    }
    if (refusedAsTaken(typedName, typedTarget)) {
      return;
    }
    selected = label(Attachment.builder().name(typedName).attachTo(typedTarget).build());
    project.edit(
        g ->
            AttachmentsXml.withNewAttachment(
                g, (String) kind.getSelectedItem(), typedName, typedTarget));
    list.choose(selected);
  }

  /** The chosen attachment's options: name, value and count, each cell a step when changed. */
  private final class OptionsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;

    // kept, as a cell is asked for often and finding the chosen one goes through all
    private transient List<Option> rows = List.of();

    void show(final List<Option> options) {
      rows = options;
      fireTableDataChanged();
    }

    private List<Option> rows() {
      return rows;
    }

    @Override
    public int getRowCount() {
      return rows().size();
    }

    @Override
    public int getColumnCount() {
      return 3;
    }

    @Override
    public String getColumnName(final int column) {
      return List.of("Option", "Value", "Count").get(column);
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final Option option = rows().get(row);
      return Objects.toString(
          switch (column) {
            case 0 -> option.getName();
            case 1 -> option.getValue();
            default -> option.getCount();
          },
          "");
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      return true;
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      final String typed = Objects.toString(value, "").strip();
      if (row >= getRowCount() || typed.equals(getValueAt(row, column))) {
        return;
      }
      final Option option = rows().get(row);
      // an empty count is no count, as the game file mostly has it
      final Option changed =
          switch (column) {
            case 0 -> option.toBuilder().name(typed).build();
            case 1 -> option.toBuilder().value(typed).build();
            default -> option.toBuilder().count(typed.isEmpty() ? null : typed).build();
          };
      chosen()
          .map(AttachmentsEditor.this::attachment)
          .ifPresent(a -> change(AttachmentsXml.withOption(a, row, changed)));
    }
  }
}
