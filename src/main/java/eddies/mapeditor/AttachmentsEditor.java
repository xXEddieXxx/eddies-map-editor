package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;
import lombok.experimental.ExtensionMethod;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * Every attachment of the game as it stands in its XML, for what has no tool of its own: listed by
 * kind in plain words, searchable, the chosen one's options in a table. Each change is one step;
 * options the editor does not know are kept as they are.
 */
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
final class AttachmentsEditor implements Editor {
  private static final String ALL_KINDS = "";
  private static final String SEPARATOR = " · ";
  private static final String EMPTY = "empty";
  private static final String DETAILS = "details";

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout());
  private final JComboBox<String> kinds = new JComboBox<>();
  // the raw kind of each item of kinds, ALL_KINDS first
  private List<String> kindKeys = List.of(ALL_KINDS);
  private String shownKind = ALL_KINDS;
  private final SearchList list;
  private final JPanel detailsCard = new JPanel(new CardLayout());
  private final Form inspector = new Form(360);
  private final JTextField name = new JTextField();
  private final JComboBox<String> attachTo = new JComboBox<>();
  private final JPanel attachToRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
  private final JLabel optionsTitle = Theme.newSectionLabel("");
  private final OptionsTable options = new OptionsTable();
  private final JTable table = new JTable(options);
  private final JComboBox<String> optionNames = new JComboBox<>();
  private @Nullable String selected;
  private boolean filling;
  // what each row shows, worked out once per change, as a big map has 35,000 attachments
  private Map<String, Entry> entries = Map.of();
  private Map<String, Integer> counts = Map.of();
  private @Nullable Game indexedOf;

  /** A row of the list: the attachment's place in the game, its kind and its two lines. */
  private record Entry(int index, String kind, String title, String detail) {}

  AttachmentsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
                "attachment",
                "attachments",
                this::shownKeys,
                this::titleOf,
                this::detailOf,
                this::choose)
            .withAdd(
                "Add attachment…", "Adds an attachment of a kind you choose", this::addAttachment);
    kinds.setToolTipText("Which kind of attachments are listed");
    kinds.setRenderer(new KindRenderer());
    kinds.addActionListener(
        e -> {
          if (!filling && kinds.getSelectedIndex() >= 0) {
            shownKind = kindKeys.get(kinds.getSelectedIndex());
            list.refresh();
          }
        });
    final JPanel listColumn = new JPanel(new BorderLayout(0, Theme.GAP_S));
    // in line with the search field, which keeps a gap to the divider
    final JPanel kindsRow = new JPanel(new BorderLayout());
    kindsRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Theme.GAP_S));
    kindsRow.add(kinds, BorderLayout.CENTER);
    listColumn.add(kindsRow, BorderLayout.NORTH);
    listColumn.add(list, BorderLayout.CENTER);

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
    attachTo.setPreferredSize(new Dimension(260, attachTo.getPreferredSize().height));
    attachToRow.add(attachTo);
    detailsCard.add(
        Panels.empty(
            Glyph.ATTACHMENTS, "Choose an attachment", "Choose an attachment to see its options."),
        EMPTY);
    detailsCard.add(newDetails(), DETAILS);
    page.add(Panels.sideBySide(listColumn, detailsCard), BorderLayout.CENTER);
    project.onChange(this::refresh);
    refresh();
  }

  /** The chosen one's fields above, its options filling the rest, Remove at the bottom. */
  private JComponent newDetails() {
    table.setRowHeight(table.getRowHeight() + 8);
    table.putClientProperty("terminateEditOnFocusLost", true);
    optionNames.setEditable(true);
    table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(optionNames));
    table.getColumnModel().getColumn(0).setPreferredWidth(220);
    table.getColumnModel().getColumn(1).setPreferredWidth(480);
    table.getColumnModel().getColumn(2).setMinWidth(60);
    table.getColumnModel().getColumn(2).setMaxWidth(60);
    table.getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeOption");
    table.getActionMap()
        .put(
            "removeOption",
            new AbstractAction() {
              @Override
              public void actionPerformed(final ActionEvent e) {
                removeOption();
              }
            });
    final JScrollPane optionsScroll = SwingComponents.newJScrollPane(table);
    optionsScroll.setColumnHeaderView(table.getTableHeader());
    final JPanel optionsHeader = new JPanel(new BorderLayout());
    optionsHeader.add(optionsTitle, BorderLayout.WEST);
    optionsHeader.add(
        Theme.newAddButton(
            "Add option…",
            "Adds an option to the attachment, its name chosen from TripleA's",
            this::addOption),
        BorderLayout.EAST);
    final JPanel optionsPart = new JPanel(new BorderLayout(0, Theme.GAP_S));
    optionsPart.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_L, 0, Theme.GAP_L));
    optionsPart.add(optionsHeader, BorderLayout.NORTH);
    optionsPart.add(optionsScroll, BorderLayout.CENTER);

    final JButton remove = new JButton("Remove attachment");
    Theme.setDestructiveButtonStyle(remove);
    remove.addActionListener(e -> removeAttachment());
    final JPanel footer = new JPanel(new BorderLayout());
    footer.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_S, Theme.GAP_L, Theme.GAP_L, Theme.GAP_L));
    footer.add(
        Theme.newSecondaryLabel("Select a row and press Delete to remove it."),
        BorderLayout.NORTH);
    // bottom left as in every tool (Form.remove), its text where the text above starts
    final JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    removeRow.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_XL, -remove.getInsets().left, 0, 0));
    removeRow.add(remove);
    footer.add(removeRow, BorderLayout.SOUTH);

    final JPanel details = new JPanel(new BorderLayout(0, Theme.GAP_S));
    details.add(inspector, BorderLayout.NORTH);
    details.add(optionsPart, BorderLayout.CENTER);
    details.add(footer, BorderLayout.SOUTH);
    return details;
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

  /**
   * Chooses the attachment of this name, the first if several have it, eg from another tool; or the
   * one of a {@link RulesXml#whereNamed} line, "name of attachTo: option", as names like
   * unitAttachment repeat.
   */
  @Override
  public void show(final String attachmentName) {
    // matched whole, not parsed: names and targets may hold " of " or ": " themselves
    AttachmentsXml.attachments(project.game()).stream()
        .filter(
            a -> {
              final String of = a.getName() + " of " + a.getAttachTo();
              return attachmentName.equals(a.getName())
                  || attachmentName.equals(of)
                  || attachmentName.startsWith(of + ": ");
            })
        .findFirst()
        .ifPresent(
            a -> {
              commit();
              selected = key(a);
              if (!shownKind.equals(ALL_KINDS)
                  && !shownKind.equals(AttachmentsXml.kind(a))) {
                shownKind = ALL_KINDS;
              }
              refresh();
            });
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

  private static String key(final Attachment attachment) {
    return Objects.toString(attachment.getName(), "")
        + SEPARATOR
        + Objects.toString(attachment.getAttachTo(), "");
  }

  /**
   * The first line of the attachment's row: what it is attached to when it has its kind's usual
   * name, eg the unit type of a unitAttachment, else its name without what every one of the kind
   * starts with, eg "Americans War" for triggerAttachment_Americans_War.
   */
  static String rowTitle(final Attachment attachment) {
    final String target = Objects.toString(attachment.getAttachTo(), "");
    final String fullName = Objects.toString(attachment.getName(), "");
    if (AttachmentsXml.hasDefaultName(attachment)) {
      return target;
    }
    final String kind = AttachmentsXml.kind(attachment);
    final String start = AttachmentsXml.NAMES.getOrDefault(kind, "");
    final String rest =
        (fullName.startsWith(start) ? fullName.substring(start.length()) : fullName)
            .replace('_', ' ')
            .strip();
    if (rest.isEmpty()) {
      return fullName;
    }
    // eg triggerAttachment3: a number alone says nothing
    return rest.chars().allMatch(Character::isDigit)
        ? AttachmentsXml.kindName(kind).one() + " " + rest
        : rest;
  }

  private static String detail(final Attachment attachment) {
    final int count = AttachmentsXml.options(attachment).size();
    return (AttachmentsXml.hasDefaultName(attachment)
            ? ""
            : Objects.toString(attachment.getAttachTo(), "") + SEPARATOR)
        + count
        + (count == 1 ? " option" : " options");
  }

  private Map<String, Entry> entries() {
    if (indexedOf != project.game()) {
      final Map<String, Entry> made = new LinkedHashMap<>();
      final Map<String, Integer> perKind = new HashMap<>();
      final List<Attachment> all = AttachmentsXml.attachments(project.game());
      for (int i = 0; i < all.size(); i++) {
        final Attachment attachment = all.get(i);
        final String kind = AttachmentsXml.kind(attachment);
        perKind.merge(kind, 1, Integer::sum);
        // of two with the same name on the same thing the game takes the last, as Triggers does
        made.put(
            key(attachment), new Entry(i, kind, rowTitle(attachment), detail(attachment)));
      }
      entries = made;
      counts = perKind;
      indexedOf = project.game();
    }
    return entries;
  }

  private List<String> shownKeys() {
    return entries().entrySet().stream()
        .filter(e -> shownKind.equals(ALL_KINDS) || shownKind.equals(e.getValue().kind()))
        .map(Map.Entry::getKey)
        .toList();
  }

  private String titleOf(final String key) {
    return Optional.ofNullable(entries().get(key)).map(Entry::title).orElse(key);
  }

  private String detailOf(final String key) {
    return Optional.ofNullable(entries().get(key))
        .map(
            e ->
                (shownKind.equals(ALL_KINDS)
                        ? AttachmentsXml.kindName(e.kind()).one() + SEPARATOR
                        : "")
                    + e.detail())
        .orElse("");
  }

  private Optional<Entry> chosen() {
    return Optional.ofNullable(selected).map(entries()::get);
  }

  private Attachment attachment(final int index) {
    return AttachmentsXml.attachments(project.game()).get(index);
  }

  private void choose(final String key) {
    commit();
    selected = key;
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
      entries();
      final List<String> kindsHad = new ArrayList<>(List.of(ALL_KINDS));
      counts.keySet().stream()
          .sorted(Comparator.comparing(k -> AttachmentsXml.kindName(k).many()))
          .forEach(kindsHad::add);
      if (!kindsHad.contains(shownKind)) {
        shownKind = ALL_KINDS;
      }
      kindKeys = List.copyOf(kindsHad);
      kinds.removeAllItems();
      kinds.addItem("All kinds (" + entries.size() + ")");
      kindKeys.stream()
          .skip(1)
          .forEach(
              k -> kinds.addItem(AttachmentsXml.kindName(k).many() + " (" + counts.get(k) + ")"));
      kinds.setSelectedIndex(kindKeys.indexOf(shownKind));
      list.refresh();
      chosen()
          .filter(e -> shownKind.equals(ALL_KINDS) || shownKind.equals(e.kind()))
          .ifPresent(e -> list.choose(selected));
      showInspector();
    } finally {
      filling = false;
    }
  }

  private void showInspector() {
    final Optional<Entry> entry = chosen();
    final CardLayout cards = (CardLayout) detailsCard.getLayout();
    if (entry.isEmpty()) {
      selected = null;
      options.show(List.of());
      cards.show(detailsCard, EMPTY);
      return;
    }
    final int index = entry.get().index();
    final Attachment attachment = attachment(index);
    final Game game = project.game();
    final String kind = AttachmentsXml.kind(attachment);
    inspector.clear();
    inspector
        .title(entry.get().title())
        .subtitle(
            AttachmentsXml.kindName(kind).one()
                + " attachment"
                + SEPARATOR
                + Objects.toString(attachment.getName(), "")
                + (attachment.getForeach() == null ? "" : ", for each " + attachment.getForeach()));
    if (TriggerXml.TRIGGER.equals(kind)) {
      inspector.row(
          easierIn(
              "This is a trigger. It is easier to change in the trigger assistant.",
              "Open in assistant",
              "Opens the trigger in the trigger assistant",
              () -> TriggerAssistant.open(page, project, index)));
    } else if (ObjectiveXml.isObjective(attachment)) {
      final String objective = Objects.toString(attachment.getName(), "");
      inspector.row(
          easierIn(
              "This is a national objective. It is easier to change in Objectives.",
              "Open in Objectives",
              "Shows the objective in Objectives, with its text and checks in plain words",
              () -> MapMakerWindow.openTool(page, ObjectivesEditor.TITLE, objective)));
    }
    if (!name.hasFocus()) {
      name.setText(Objects.toString(attachment.getName(), ""));
    }
    attachTo.removeAllItems();
    AttachmentsXml.targets(game, Objects.toString(attachment.getType(), "unitType"))
        .forEach(attachTo::addItem);
    attachTo.setSelectedItem(attachment.getAttachTo());
    optionNames.removeAllItems();
    AttachmentsXml.knownOptions(game, kind).forEach(optionNames::addItem);
    inspector.field("Name", name).field("Attached to", attachToRow).end();
    final List<Option> shown = AttachmentsXml.options(attachment);
    optionsTitle.setText("Options (" + shown.size() + ")");
    options.show(shown);
    cards.show(detailsCard, DETAILS);
    detailsCard.revalidate();
    detailsCard.repaint();
  }

  /** A line saying which tool changes the attachment more easily, and the button that opens it. */
  private static JComponent easierIn(
      final String text, final String verb, final String toolTip, final Runnable open) {
    final JButton button = new JButton(verb);
    Theme.setTertiaryButtonStyle(button);
    button.setToolTipText(toolTip);
    button.addActionListener(e -> open.run());
    final JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    info.add(new JLabel(text));
    info.add(Box.createHorizontalStrut(Theme.GAP_S));
    info.add(button);
    return info;
  }

  private void change(final Attachment changed) {
    chosen()
        .ifPresent(
            e -> {
              // eg an option without a name, which the change refused
              if (changed == attachment(e.index())) {
                return;
              }
              selected = key(changed);
              project.edit(g -> AttachmentsXml.withAttachment(g, e.index(), changed));
            });
  }

  private void rename() {
    final String typed = name.getText().strip();
    chosen()
        .map(e -> attachment(e.index()))
        .filter(a -> !typed.isEmpty() && !typed.equals(a.getName()))
        .filter(
            a -> {
              if (!AttachmentsXml.has(project.game(), typed, a.getAttachTo())) {
                return true;
              }
              // the old name back, so leaving the field does not warn again
              name.setText(a.getName());
              Dialogs.warn(
                  page,
                  "Attachment name",
                  a.getAttachTo() + " has an attachment " + typed + " already. Choose another"
                      + " name.");
              return false;
            })
        .ifPresent(a -> change(a.toBuilder().name(typed).build()));
  }

  private void reattach() {
    if (filling) {
      return;
    }
    final String typed = Objects.toString(attachTo.getSelectedItem(), "").strip();
    chosen()
        .map(e -> attachment(e.index()))
        .filter(a -> !typed.isEmpty() && !typed.equals(a.getAttachTo()))
        .ifPresent(a -> change(a.toBuilder().attachTo(typed).build()));
  }

  private void removeAttachment() {
    commit();
    chosen()
        .ifPresent(
            e -> {
              final Attachment attachment = attachment(e.index());
              final String one = AttachmentsXml.kindName(e.kind()).one().toLowerCase(Locale.ROOT);
              final String what =
                  AttachmentsXml.hasDefaultName(attachment)
                      ? "the " + one + " attachment of " + e.title()
                      : one + " “" + e.title() + "”";
              final List<String> namedBy =
                  RulesXml.whereNamed(
                      project.game(), Objects.toString(attachment.getName(), ""));
              if (Dialogs.confirmRemove(
                  page,
                  "Remove attachment",
                  "Remove " + what + "?"
                      + (namedBy.isEmpty()
                          ? ""
                          : "\nThese still name it; the game may not load until you change them."),
                  namedBy,
                  "Remove")) {
                selected = null;
                project.edit(g -> AttachmentsXml.withoutAttachment(g, e.index()));
              }
            });
  }

  private void addOption() {
    commit();
    final Optional<Attachment> attachment = chosen().map(e -> attachment(e.index()));
    if (attachment.isEmpty()) {
      return;
    }
    final JComboBox<String> optionName =
        new JComboBox<>(
            AttachmentsXml.knownOptions(project.game(), AttachmentsXml.kind(attachment.get()))
                .toArray(String[]::new));
    optionName.setEditable(true);
    optionName.setSelectedItem("");
    final JTextField value = new JTextField(24);
    final Form form = new Form(320);
    form.setBorder(BorderFactory.createEmptyBorder());
    form.field("Option", optionName).field("Value", value).end();
    if (Dialogs.ask(
        page, "Add option", form, "Add option", () -> !typed(optionName).isEmpty())) {
      change(
          AttachmentsXml.withNewOption(
              attachment.get(),
              Option.builder().name(typed(optionName)).value(value.getText().strip()).build()));
    }
  }

  // what is typed in an editable combo box, also before Enter
  private static String typed(final JComboBox<String> combo) {
    return Objects.toString(combo.getEditor().getItem(), "").strip();
  }

  private void removeOption() {
    final int row = table.getSelectedRow();
    if (row >= 0 && !table.isEditing()) {
      chosen()
          .map(e -> attachment(e.index()))
          .ifPresent(a -> change(AttachmentsXml.withoutOption(a, row)));
    }
  }

  /** Offers the kinds TripleA knows in plain words, and what each may be attached to. */
  private void addAttachment() {
    commit();
    final Game game = project.game();
    final JComboBox<String> kind =
        new JComboBox<>(
            AttachmentsXml.KINDS.keySet().stream()
                .sorted(Comparator.comparing(k -> AttachmentsXml.kindName(k).one()))
                .toArray(String[]::new));
    kind.setRenderer(new KindRenderer());
    kind.setSelectedItem(TriggerXml.TRIGGER);
    final JLabel easier =
        Theme.newSecondaryLabel("Triggers are easier to make in Triggers with New trigger.");
    final JComboBox<String> target = new JComboBox<>();
    target.setEditable(true);
    final JTextField newName = new JTextField(24);
    final Form form = new Form(320);
    form.setBorder(BorderFactory.createEmptyBorder());
    form.field("Kind", kind).row(easier).field("Attached to", target).field("Name", newName);
    final FieldError taken = form.error(newName);
    form.end();
    final Runnable check =
        () ->
            taken.setProblem(
                AttachmentsXml.has(game, newName.getText().strip(), typed(target))
                    ? Optional.of(typed(target) + " has an attachment of that name already.")
                    : Optional.empty());
    final Runnable fill =
        () -> {
          final String chosenKind = (String) kind.getSelectedItem();
          easier.setVisible(
              TriggerXml.TRIGGER.equals(chosenKind) || "RulesAttachment".equals(chosenKind));
          target.removeAllItems();
          AttachmentsXml.targets(game, AttachmentsXml.KINDS.get(chosenKind))
              .forEach(target::addItem);
          newName.setText(AttachmentsXml.newName(game, chosenKind));
          check.run();
        };
    kind.addActionListener(e -> fill.run());
    target.addActionListener(e -> check.run());
    OnTyping.attach(newName, check);
    OnTyping.attach((JTextField) target.getEditor().getEditorComponent(), check);
    fill.run();
    if (!Dialogs.ask(
        page,
        "Add attachment",
        form,
        "Add attachment",
        () ->
            !newName.getText().isBlank() && !typed(target).isEmpty() && !taken.hasProblem())) {
      return;
    }
    final String chosenKind = (String) kind.getSelectedItem();
    final String typedName = newName.getText().strip();
    final String typedTarget = typed(target);
    selected = key(Attachment.builder().name(typedName).attachTo(typedTarget).build());
    if (!shownKind.equals(ALL_KINDS) && !shownKind.equals(chosenKind)) {
      shownKind = chosenKind;
    }
    project.edit(g -> AttachmentsXml.withNewAttachment(g, chosenKind, typedName, typedTarget));
  }

  /**
   * A kind in plain words; its class, as TripleA names it, in the tool tip and, in the open list,
   * smaller beside it.
   */
  private final class KindRenderer extends DefaultListCellRenderer {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public Component getListCellRendererComponent(
        final JList<?> owner,
        final Object value,
        final int index,
        final boolean isSelected,
        final boolean cellHasFocus) {
      super.getListCellRendererComponent(owner, value, index, isSelected, cellHasFocus);
      final String text = Objects.toString(value, "");
      if (AttachmentsXml.KINDS.containsKey(text)) {
        // the add dialog's items are the raw kinds; the closed box shows the plain name only
        setText(
            index < 0
                ? AttachmentsXml.kindName(text).one()
                : "<html>"
                    + AttachmentsXml.kindName(text).one()
                    + " <font size='-2' color='"
                    + Theme.convertColorToHex(Theme.getSecondaryTextColor())
                    + "'>"
                    + text
                    + "</font></html>");
        setToolTipText(text);
      } else {
        final int at = index >= 0 ? index : kinds.getSelectedIndex();
        setToolTipText(
            at > 0 && at < kindKeys.size() ? kindKeys.get(at) : "Attachments of every kind");
      }
      return this;
    }
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

    @Override
    public int getRowCount() {
      return rows.size();
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
      final Option option = rows.get(row);
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
      final Option option = rows.get(row);
      // an empty count is no count, as the game file mostly has it
      final Option changed =
          switch (column) {
            case 0 -> option.toBuilder().name(typed).build();
            case 1 -> option.toBuilder().value(typed).build();
            default -> option.toBuilder().count(typed.isEmpty() ? null : typed).build();
          };
      chosen()
          .map(e -> attachment(e.index()))
          .ifPresent(a -> change(AttachmentsXml.withOption(a, row, changed)));
    }
  }
}
