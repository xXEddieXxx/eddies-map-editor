package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import lombok.experimental.ExtensionMethod;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.DocumentListenerBuilder;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MapTexts.Entry;
import eddies.mapeditor.TriggerXml.Group;
import eddies.mapeditor.TriggerXml.Part;
import eddies.mapeditor.TriggerXml.Piece;
import eddies.mapeditor.TriggerXml.Problems;
import eddies.mapeditor.TriggerXml.Shape;
import eddies.mapeditor.TriggerXml.Spec;
import eddies.mapeditor.TriggerXml.Trigger;

/**
 * Makes or changes a trigger step by step: who, when, on which conditions, what happens and how
 * often, with what it does in plain words on top and its problems below. Finish writes the trigger,
 * its new conditions and its messages as one step; options and condition names it does not know
 * are kept as they are.
 */
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
final class TriggerAssistant {
  private static final String ALL = "all of them";
  private static final String ANY = "any of them";
  // a number as conditionType means exactly that many (AbstractConditionsAttachment)
  private static final String EXACTLY = "exactly";
  private static final String FROM_TO = "from ... to";
  private static final int TEXT_WIDTH = 560;
  private static final int LABEL_WIDTH = 170;

  private final MapProject project;
  private final Game game;
  private final Trigger original;
  private final JComponent content;
  private @Nullable JDialog dialog;
  private final JTextArea summary = new JTextArea();
  private final JLabel problemsLabel = new JLabel();
  private final JButton finish = new JButton("Finish");
  private final JCheckBox triggersOn = new JCheckBox("Turn on the game option Use Triggers");
  private final JComboBox<String> nation;
  private final ListField players;
  private final JTextField shortName = new JTextField();
  private final JRadioButton usual = new JRadioButton("At its usual moment");
  private final JRadioButton atSteps = new JRadioButton("Before or after steps");
  private final JLabel usualMoment = new JLabel();
  private final JPanel whenRows = column();
  private final JButton addStep = button("Add step",
      "Adds a step the trigger happens before or after", () -> addWhen("after:"));
  private final List<ConditionBox> boxes = new ArrayList<>();
  // the condition boxes and the names kept as they are, in the trigger's order
  private final JPanel conditionsPanel = column();
  private final JComboBox<String> combine =
      new JComboBox<>(new String[] {ALL, ANY, EXACTLY, FROM_TO});
  private final JSpinner low = Form.newNumber(1, 0, 99, n -> changed());
  private final JSpinner high = Form.newNumber(2, 1, 99, n -> changed());
  private final JLabel to = new JLabel("to");
  private final JCheckBox not = new JCheckBox("Not: fire when the conditions do not hold");
  private final JPanel effectRows = column();
  private final JRadioButton once = new JRadioButton("In 1 round");
  private final JRadioButton times = new JRadioButton("In rounds");
  private final JRadioButton always = new JRadioButton("Every round");
  private final JSpinner timesCount = Form.newNumber(2, 0, 999, n -> changed());
  private final JCheckBox chanceOn = new JCheckBox("Only by chance");
  private final JSpinner chanceHit = Form.newNumber(1, 0, 120, n -> changed());
  private final JSpinner chanceSides = Form.newNumber(6, 1, 120, n -> changed());
  private boolean filling = true;

  /** The assistant for the trigger, not shown yet; {@link #show} opens it. */
  TriggerAssistant(final MapProject project, final Trigger trigger) {
    this.project = project;
    this.game = project.game();
    this.original = trigger;
    nation = chooser(MapXml.nations(game), trigger.nation());
    nation.setEditable(false);
    players = new ListField("Nations", Part.NATION.names(game),
        TriggerXml.value(trigger.attachment(), "players").orElse(""));
    fill();
    filling = false;
    content = content();
    changed();
  }

  /** Opens the assistant for a new trigger. */
  static void openNew(final Component parent, final MapProject project) {
    final Game game = project.game();
    final String nation = MapXml.nations(game).stream().findFirst().orElse("");
    new TriggerAssistant(project,
        TriggerXml.newTrigger(TriggerXml.newName(game, TriggerXml.TRIGGER, "", Set.of()), nation))
        .show(parent);
  }

  /** Opens the assistant for the trigger at this place in the game's attachments. */
  static void open(final Component parent, final MapProject project, final int index) {
    new TriggerAssistant(project, TriggerXml.trigger(project.game(), index)).show(parent);
  }

  private void show(final Component parent) {
    final JDialog shown = new JDialog(SwingUtilities.getWindowAncestor(parent), title(),
        JDialog.ModalityType.APPLICATION_MODAL);
    dialog = shown;
    shown.setContentPane(content);
    shown.setSize(780, 860);
    shown.setLocationRelativeTo(parent);
    shown.setVisible(true);
  }

  private void close() {
    Optional.ofNullable(dialog).ifPresent(JDialog::dispose);
  }

  private boolean isNew() {
    return original.index() < 0;
  }

  private String title() {
    return isNew() ? "New trigger" : "Trigger " + original.name();
  }

  // ---- filling in what the trigger has

  private void fill() {
    final Attachment attachment = original.attachment();
    final List<String> when = TriggerXml.values(attachment, "when");
    (when.isEmpty() ? usual : atSteps).setSelected(true);
    when.forEach(this::addWhen);
    for (final String name : new LinkedHashSet<>(original.conditionNames())) {
      original.conditions().stream()
          .filter(c -> name.equals(c.getName()))
          .findFirst()
          .ifPresentOrElse(
              c -> addBox(new ConditionBox(Optional.of(c), name)),
              () -> conditionsPanel.add(new KeptName(name)));
    }
    final String type =
        TriggerXml.value(attachment, "conditionType").orElse("AND").toUpperCase(Locale.ROOT);
    if (type.equals("OR")) {
      combine.setSelectedItem(ANY);
    } else if (type.matches("\\d+")) {
      combine.setSelectedItem(EXACTLY);
      low.setValue(Integer.parseInt(type));
    } else if (type.matches("\\d+-\\d+")) {
      combine.setSelectedItem(FROM_TO);
      low.setValue(Integer.parseInt(type.split("-")[0]));
      high.setValue(Integer.parseInt(type.split("-")[1]));
    }
    not.setSelected(isTrue(attachment, "invert"));
    for (final Option option : AttachmentsXml.options(attachment)) {
      TriggerXml.spec(option.getName())
          .filter(s -> s.group() == Group.EFFECT)
          .ifPresent(s -> effectRows.add(
              new OptionRow(TriggerXml.specs(Group.EFFECT), s, TriggerXml.joined(option))));
    }
    final int uses = TriggerXml.value(attachment, "uses").filter(u -> u.matches("-?\\d+"))
        .map(Integer::parseInt).orElse(-1);
    (uses < 0 ? always : uses == 1 ? once : times).setSelected(true);
    // 0, which a used-up trigger may have, stays 0
    timesCount.setValue(uses == 0 || uses > 1 ? uses : 2);
    TriggerXml.value(attachment, "chance").map(c -> c.split(":")).filter(c -> c.length == 2)
        .filter(c -> c[0].matches("\\d+") && c[1].matches("\\d+"))
        .ifPresent(c -> {
          chanceOn.setSelected(true);
          chanceHit.setValue(Integer.parseInt(c[0]));
          chanceSides.setValue(Integer.parseInt(c[1]));
        });
    triggersOn.setSelected(true);
    triggersOn.setVisible(!TriggerXml.usesTriggers(game));
  }

  private static boolean isTrue(final Attachment attachment, final String option) {
    return TriggerXml.value(attachment, option).filter("true"::equalsIgnoreCase).isPresent();
  }

  // ---- the dialog

  private JComponent content() {
    final Form form = new Form(TEXT_WIDTH);
    final ButtonGroup whenGroup = new ButtonGroup();
    whenGroup.add(usual);
    whenGroup.add(atSteps);
    final ButtonGroup often = new ButtonGroup();
    Stream.of(once, times, always).forEach(often::add);
    Stream.of(usual, atSteps, once, times, always, chanceOn, not, triggersOn)
        .forEach(b -> b.addActionListener(e -> changed()));
    combine.addActionListener(e -> changed());
    nation.addActionListener(e -> changed());
    new DocumentListenerBuilder(this::changed).attachTo(shortName);
    usualMoment.setForeground(Theme.getSecondaryTextColor());
    combine.setToolTipText("How many conditions must hold for the trigger to fire");
    low.setToolTipText("How many conditions must hold, or the fewest");
    high.setToolTipText("The most conditions that may hold");
    timesCount.setToolTipText("In how many rounds the trigger may fire; 0 is used up");
    chanceHit.setToolTipText("The roll it needs: this or less");
    chanceSides.setToolTipText("The sides of the die rolled");
    Stream.of(combine, low, high, timesCount, chanceHit, chanceSides)
        .forEach(c -> c.getAccessibleContext().setAccessibleName(c.getToolTipText()));

    form.title(title(),
        "A trigger changes the game when its conditions hold, eg gives PUs or units, changes"
            + " owners, relationships or what can be bought. Fill in the steps; the line on top"
            + " says what it does.");
    form.section("1 Who", "The nation the trigger belongs to; it fires in that nation's turn."
            + " The nations it acts for are the ones that get what it gives, else the nation.")
        .field("Nation", nation)
        .field("Acts for nations (optional)", players);
    if (isNew()) {
      shortName.putClientProperty("JTextField.placeholderText", "eg GermanOil");
      form.field("Short name (optional)", "The trigger is called triggerAttachment with this"
          + " after it, its new conditions conditionAttachment with it.", shortName);
    } else {
      form.note("Name: " + SearchList.escape(original.name()));
    }
    final JLabel fireWhen = new JLabel("Fire when");
    fireWhen.setLabelFor(combine);
    form.section("2 When", "Without steps, each effect happens at its own moment in the nation's"
            + " turn, shown below. Steps make every effect happen right before or after them.")
        .row(row(usual, atSteps))
        .row(usualMoment)
        .row(whenRows)
        .row(row(addStep));
    form.section("3 Conditions", "What must hold for the trigger to fire. Each condition checks"
            + " one or more things; all its checks must hold. A condition may be shared with other"
            + " triggers: a change then changes them too. \"Exactly 0\" fires when none holds.")
        .row(conditionsPanel)
        .row(row(
            button("New condition", "Adds a condition of its own for this trigger",
                () -> addBox(new ConditionBox(Optional.empty(), newConditionName()))),
            button("Use a condition of the map...", "Adds a condition the map has already",
                this::chooseCondition)))
        .row(row(fireWhen, combine, low, to, high))
        .row(not);
    form.section("4 What happens", "The effects, in the order the game file has them.")
        .row(effectRows)
        .row(row(button("Add effect", "Adds something the trigger does", () -> {
          effectRows.add(new OptionRow(TriggerXml.specs(Group.EFFECT),
              TriggerXml.specs(Group.EFFECT).getFirst(), ""));
          changed();
        })));
    form.section("5 How often", "Uses count rounds: in a round it fires each time its moment"
            + " comes and the conditions hold, and one use goes at the round's end. 0 is used up:"
            + " it fires only when another trigger fires it. A chance of 1 in 6 fires on a roll of 1 with a die"
            + " of 6.")
        .row(row(once, times, timesCount, always))
        .row(row(chanceOn, chanceHit, new JLabel("in"), chanceSides));
    final List<Option> kept = AttachmentsXml.options(original.attachment()).stream()
        .filter(o -> !TriggerXml.isManagedOnTrigger(o.getName()))
        .toList();
    if (!kept.isEmpty()) {
      form.section("Kept as is", "Options the assistant does not change; Attachments shows them.");
      kept.forEach(o -> form.note(SearchList.escape(o.getName() + " = " + TriggerXml.joined(o))));
    }
    form.end();

    // a text area, as a label does not wrap to the room it has
    summary.setEditable(false);
    summary.setFocusable(false);
    summary.setOpaque(false);
    summary.setLineWrap(true);
    summary.setWrapStyleWord(true);
    summary.setFont(new JLabel().getFont().deriveFont(Font.BOLD));
    summary.getAccessibleContext().setAccessibleName("What the trigger does");
    final JPanel top = new JPanel(new BorderLayout());
    top.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
    top.add(summary);
    final JButton cancel = new JButton("Cancel");
    cancel.addActionListener(e -> close());
    finish.addActionListener(e -> finish());
    Theme.setPrimaryButtonStyle(finish);
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.add(cancel);
    buttons.add(finish);
    final JPanel bottom = new JPanel(new BorderLayout(0, 6));
    bottom.setBorder(Theme.newFooterBorder());
    bottom.add(problemsLabel, BorderLayout.NORTH);
    bottom.add(triggersOn, BorderLayout.CENTER);
    bottom.add(buttons, BorderLayout.SOUTH);
    final JPanel panel = new JPanel(new BorderLayout());
    panel.add(top, BorderLayout.NORTH);
    panel.add(SwingComponents.newJScrollPane(form), BorderLayout.CENTER);
    panel.add(bottom, BorderLayout.SOUTH);
    return panel;
  }

  private static JPanel column() {
    final JPanel column = new JPanel();
    column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
    return column;
  }

  private static JPanel row(final JComponent... components) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    row.setOpaque(false);
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    Stream.of(components).forEach(row::add);
    return row;
  }

  /** Labels and fields in two columns, so all fields start at the same place. */
  private static JPanel grid() {
    final JPanel grid = new JPanel(new GridBagLayout());
    grid.setOpaque(false);
    return grid;
  }

  private static void addRow(final JPanel grid, final String text, final JComponent field) {
    final JLabel label = new JLabel(text);
    label.setLabelFor(field);
    label.setToolTipText(text);
    label.setPreferredSize(new Dimension(LABEL_WIDTH, label.getPreferredSize().height));
    final GridBagConstraints at = new GridBagConstraints();
    at.gridy = grid.getComponentCount() / 2;
    at.anchor = GridBagConstraints.WEST;
    at.insets = new Insets(2, 0, 2, 8);
    grid.add(label, at);
    at.gridx = 1;
    at.weightx = 1;
    at.fill = GridBagConstraints.HORIZONTAL;
    at.insets = new Insets(2, 0, 2, 0);
    grid.add(field, at);
  }

  private static JButton button(final String text, final String tip, final Runnable action) {
    final JButton button = new JButton(text);
    button.setToolTipText(tip);
    button.addActionListener(e -> action.run());
    return button;
  }

  /** A remove button, small, for a row or a box. */
  private JButton removeButton(final String what, final JComponent removed) {
    final JButton remove = new JButton("×");
    remove.setToolTipText("Removes the " + what);
    remove.getAccessibleContext().setAccessibleName("Remove " + what);
    remove.addActionListener(e -> {
      boxes.remove(removed);
      Optional.ofNullable(removed.getParent()).ifPresent(p -> {
        p.remove(removed);
        p.revalidate();
      });
      changed();
    });
    return remove;
  }

  /** A box to choose from the names or type, eg a territory. */
  private static JComboBox<String> chooser(final List<String> names, final String value) {
    final JComboBox<String> box = new JComboBox<>(names.toArray(String[]::new));
    box.setEditable(true);
    box.setSelectedItem(value);
    return box;
  }

  private static String text(final JComboBox<String> box) {
    return box.isEditable()
        ? ((JTextComponent) box.getEditor().getEditorComponent()).getText().strip()
        : Objects.toString(box.getSelectedItem(), "");
  }

  /** Calls {@link #changed} on every change of the box, also while typing. */
  private JComboBox<String> watched(final JComboBox<String> box) {
    box.addActionListener(e -> changed());
    if (box.isEditable()) {
      new DocumentListenerBuilder(this::changed)
          .attachTo((JTextComponent) box.getEditor().getEditorComponent());
    }
    return box;
  }

  private static <T extends JComponent> T named(final T component, final String name) {
    component.setToolTipText(name);
    component.getAccessibleContext().setAccessibleName(name);
    return component;
  }

  private void addWhen(final String when) {
    final String[] parts = when.split(":", 2);
    final JComboBox<String> beforeAfter = named(
        watched(new JComboBox<>(new String[] {"before", "after"})), "Before or after the step");
    beforeAfter.setSelectedItem(parts[0]);
    final JComboBox<String> step = named(
        watched(chooser(Part.STEP.names(game), parts.length > 1 ? parts[1] : "")), "Step");
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.add(beforeAfter);
    row.add(step);
    row.add(removeButton("step", row));
    row.putClientProperty(WhenValue.class, (WhenValue) () -> text(beforeAfter) + ":" + text(step));
    whenRows.add(row);
    changed();
  }

  /** The "when" of a row in the steps' list. */
  private interface WhenValue extends Supplier<String> {}

  private void addBox(final ConditionBox box) {
    boxes.add(box);
    conditionsPanel.add(box);
    changed();
  }

  private String newConditionName() {
    final Set<String> taken = new HashSet<>(conditionNames());
    return TriggerXml.newName(game, TriggerXml.CONDITION, shortName.getText(), taken);
  }

  /** The names of the conditions, the boxes' and those kept as they are, in their order. */
  private List<String> conditionNames() {
    return Stream.of(conditionsPanel.getComponents())
        .map(c -> c instanceof ConditionBox box ? box.name
            : c instanceof KeptName kept ? kept.name : "")
        .filter(n -> !n.isEmpty())
        .toList();
  }

  private void chooseCondition() {
    final List<String> inUse = conditionNames();
    final List<String> names = TriggerXml.conditions(game).stream()
        .map(Attachment::getName)
        .filter(Objects::nonNull)
        .filter(n -> !inUse.contains(n))
        .distinct()
        .toList();
    final String[] chosen = {null};
    final SearchList list = new SearchList("Search condition", () -> names,
        n -> TriggerXml.condition(game, n).map(TriggerXml::conditionSentence).orElse(""),
        n -> chosen[0] = n);
    list.setPreferredSize(new Dimension(520, 380));
    if (Panels.ask(content, list, "Use a condition of the map") && chosen[0] != null) {
      TriggerXml.condition(game, chosen[0])
          .ifPresent(c -> addBox(new ConditionBox(Optional.of(c), chosen[0])));
    }
  }

  // ---- the trigger as filled in

  /** The trigger as filled in; the same attachments as the game's for a trigger left alone. */
  Trigger trigger() {
    final Attachment before = original.attachment();
    final String name = isNew()
        ? TriggerXml.newName(game, TriggerXml.TRIGGER, shortName.getText(), Set.of())
        : original.name();
    final String nationName = text(nation);
    final Attachment base = name.equals(before.getName()) && nationName.equals(before.getAttachTo())
        ? before
        : before.toBuilder().name(name).attachTo(nationName).build();
    final List<Option> wanted = new ArrayList<>();
    AttachmentsXml.options(before).stream()
        .filter(o -> !TriggerXml.isManagedOnTrigger(o.getName()))
        .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o)))
        .forEach(wanted::add);
    if (!players.value().isEmpty()) {
      wanted.add(TriggerXml.option("players", players.value()));
    }
    final List<String> names = conditionNames();
    if (names.equals(List.copyOf(new LinkedHashSet<>(original.conditionNames())))) {
      // the same names: the options as they were, eg the old name "trigger" or two of them
      AttachmentsXml.options(before).stream()
          .filter(o -> List.of("conditions", TriggerXml.OLD_CONDITIONS).contains(o.getName()))
          .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o)))
          .forEach(wanted::add);
    } else if (!names.isEmpty()) {
      wanted.add(TriggerXml.option("conditions", String.join(":", names)));
    }
    final String type = switch (Objects.toString(combine.getSelectedItem(), ALL)) {
      case ANY -> "OR";
      case EXACTLY -> String.valueOf(low.getValue());
      case FROM_TO -> low.getValue() + "-" + high.getValue();
      default -> "AND";
    };
    // the case the map wrote it in, eg "and", stays when the meaning does
    addUnlessDefault(wanted, before, "conditionType",
        TriggerXml.value(before, "conditionType").filter(type::equalsIgnoreCase).orElse(type),
        "AND");
    addUnlessDefault(wanted, before, "invert",
        TriggerXml.value(before, "invert")
            .filter(String.valueOf(not.isSelected())::equalsIgnoreCase)
            .orElse(String.valueOf(not.isSelected())),
        "false");
    addUnlessDefault(wanted, before, "uses",
        once.isSelected() ? "1" : times.isSelected() ? String.valueOf(timesCount.getValue()) : "-1",
        "-1");
    if (chanceOn.isSelected()) {
      wanted.add(TriggerXml.option("chance", chanceHit.getValue() + ":" + chanceSides.getValue()));
    }
    if (atSteps.isSelected()) {
      for (final Component row : whenRows.getComponents()) {
        if (((JComponent) row).getClientProperty(WhenValue.class) instanceof WhenValue value) {
          wanted.add(TriggerXml.option("when", value.get()));
        }
      }
    }
    effects().forEach(r -> wanted.add(r.option()));
    return new Trigger(original.index(), TriggerXml.withOptions(base, wanted),
        boxes.stream().map(ConditionBox::attachment).toList());
  }

  /** Adds the option unless it is the game's default and the trigger did not have it. */
  private static void addUnlessDefault(final List<Option> wanted, final Attachment before,
      final String name, final String value, final String fallback) {
    if (!value.equalsIgnoreCase(fallback) || TriggerXml.value(before, name).isPresent()) {
      wanted.add(TriggerXml.option(name, value));
    }
  }

  private List<OptionRow> effects() {
    return Stream.of(effectRows.getComponents())
        .filter(OptionRow.class::isInstance)
        .map(OptionRow.class::cast)
        .toList();
  }

  private List<Entry> typedMessages() {
    return effects().stream()
        .flatMap(r -> r.messages().stream())
        .filter(m -> !m.key().isBlank() && !m.value().isBlank())
        .toList();
  }

  /** The texts with the messages typed for the trigger's notifications and victory. */
  private MapTexts withMessages(final MapTexts texts) {
    MapTexts changed = texts;
    for (final Entry message : typedMessages()) {
      changed = changed.with(MapTexts.Kind.NOTIFICATIONS, message.key(), message.value());
    }
    return changed;
  }

  private void changed() {
    if (filling) {
      return;
    }
    final Trigger trigger = trigger();
    summary.setText(TriggerXml.sentence(game, trigger));
    final Problems problems = TriggerXml.problems(
        triggersOn.isVisible() && triggersOn.isSelected() ? TriggerXml.withTriggersOn(game) : game,
        withMessages(project.texts()), trigger);
    final List<String> errors = new ArrayList<>(problems.errors());
    if (project.texts().unreadable().contains(MapTexts.Kind.NOTIFICATIONS)
        && !typedMessages().isEmpty()) {
      errors.add(MapTexts.Kind.NOTIFICATIONS.getFile() + " could not be read, so a message"
          + " text cannot be saved: fix the file in Files, or leave the text empty.");
    }
    final StringBuilder text = new StringBuilder();
    errors.forEach(p -> text.append("<b>Must fix:</b> ").append(SearchList.escape(p))
        .append("<br>"));
    problems.warnings().forEach(p -> text.append("Check: ").append(SearchList.escape(p))
        .append("<br>"));
    problemsLabel.setText(text.isEmpty() ? "No problems."
        : "<html><div style='width:" + (TEXT_WIDTH + 100) + "px'>" + text + "</div></html>");
    finish.setEnabled(errors.isEmpty());
    final List<String> moments = effects().stream().map(r -> r.spec.moment())
        .filter(m -> !m.isEmpty()).distinct().toList();
    usualMoment.setText(moments.isEmpty() ? "Add an effect to see when it happens."
        : "Usually: " + String.join(", ", moments) + ".");
    usualMoment.setVisible(usual.isSelected());
    whenRows.setVisible(atSteps.isSelected());
    addStep.setVisible(atSteps.isSelected());
    low.setVisible(Stream.of(EXACTLY, FROM_TO).anyMatch(s -> s.equals(combine.getSelectedItem())));
    high.setVisible(FROM_TO.equals(combine.getSelectedItem()));
    to.setVisible(high.isVisible());
    timesCount.setEnabled(times.isSelected());
    chanceHit.setEnabled(chanceOn.isSelected());
    chanceSides.setEnabled(chanceOn.isSelected());
    Optional.ofNullable(content).ifPresent(c -> {
      c.revalidate();
      c.repaint();
    });
  }

  private void finish() {
    final Trigger trigger = trigger();
    final boolean turnOn = triggersOn.isVisible() && triggersOn.isSelected();
    project.edit(
        g -> {
          final Game changed = TriggerXml.withTrigger(g, trigger);
          return turnOn ? TriggerXml.withTriggersOn(changed) : changed;
        },
        this::withMessages);
    close();
  }

  // ---- the parts

  /**
   * A list of names, joined by colons as the file has them: shown short, all in its tool tip,
   * changed with Choose.
   */
  private final class ListField extends JPanel {
    @Serial private static final long serialVersionUID = 1L;
    private final String what;
    private final List<String> names;
    private final JLabel shown = new JLabel();
    private String value;

    ListField(final String what, final List<String> names, final String value) {
      super(new BorderLayout(6, 0));
      setOpaque(false);
      this.what = what;
      this.names = names;
      this.value = value;
      final JButton choose = button("Choose...", "Chooses the " + what.toLowerCase(Locale.ROOT),
          this::choose);
      choose.getAccessibleContext().setAccessibleName("Choose " + what);
      add(shown, BorderLayout.CENTER);
      add(choose, BorderLayout.EAST);
      showValue();
    }

    private List<String> values() {
      return value.isEmpty() ? List.of() : List.of(value.split(":"));
    }

    private void showValue() {
      final List<String> values = values();
      final String all = String.join(", ", values);
      shown.setText(values.isEmpty() ? "None"
          : all.length() <= 40 ? all
          : values.getFirst() + " and " + (values.size() - 1) + " more");
      shown.setForeground(values.isEmpty() ? Theme.getSecondaryTextColor() : null);
      shown.setToolTipText(values.isEmpty() ? null
          : "<html><div style='width:300px'>" + SearchList.escape(all) + "</div></html>");
      shown.getAccessibleContext().setAccessibleName(what + ": " + (all.isEmpty() ? "none" : all));
    }

    /** The names to pick, several with Ctrl, and other values to type, eg "-" ones or rounds. */
    private void choose() {
      final List<String> values = values();
      final JList<String> list = new JList<>(names.toArray(String[]::new));
      list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
      list.setSelectedIndices(values.stream().mapToInt(names::indexOf).filter(i -> i >= 0)
          .toArray());
      final JTextField others = new JTextField(String.join(":",
          values.stream().filter(v -> !names.contains(v)).toList()));
      final JPanel panel = new JPanel(new BorderLayout(0, 6));
      if (!names.isEmpty()) {
        panel.add(new JLabel("Ctrl+click picks several."), BorderLayout.NORTH);
        final var scroll = SwingComponents.newJScrollPane(list);
        scroll.setPreferredSize(new Dimension(360, 320));
        panel.add(scroll, BorderLayout.CENTER);
      }
      final JPanel other = new JPanel(new BorderLayout(0, 4));
      final JLabel otherLabel = new JLabel(names.isEmpty() ? what + ", separated by :"
          : "Others, separated by : (eg -name to take one away)");
      otherLabel.setLabelFor(others);
      other.add(otherLabel, BorderLayout.NORTH);
      other.add(others, BorderLayout.CENTER);
      panel.add(other, BorderLayout.SOUTH);
      if (!Panels.ask(this, panel, what)) {
        return;
      }
      final Set<String> picked = new LinkedHashSet<>(list.getSelectedValuesList());
      Stream.of(others.getText().split(":")).map(String::strip).filter(v -> !v.isEmpty())
          .forEach(picked::add);
      // the order the map had, new ones after
      final List<String> result = new ArrayList<>(values.stream().filter(picked::contains)
          .distinct().toList());
      picked.stream().filter(v -> !result.contains(v)).forEach(result::add);
      value = String.join(":", result);
      showValue();
      changed();
    }

    String value() {
      return value.strip();
    }
  }

  /** A condition name the game does not have, eg one a foreach makes: kept as it is. */
  private final class KeptName extends JPanel {
    @Serial private static final long serialVersionUID = 1L;
    private final String name;

    KeptName(final String name) {
      super(new BorderLayout(6, 0));
      this.name = name;
      setAlignmentX(Component.LEFT_ALIGNMENT);
      setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
      final JLabel label = new JLabel("<html><b>" + SearchList.escape(name) + "</b> (kept as is:"
          + " the game file has no condition of this name, eg one a foreach makes)</html>");
      add(label, BorderLayout.CENTER);
      add(removeButton("condition", this), BorderLayout.EAST);
    }
  }

  /** One option, eg an effect or a check: what it is in plain words, then a field per piece. */
  private final class OptionRow extends JPanel {
    @Serial private static final long serialVersionUID = 1L;
    private final JComboBox<Spec> kind;
    private final JPanel fields = grid();
    private final List<Supplier<String>> values = new ArrayList<>();
    private final List<Supplier<Entry>> messages = new ArrayList<>();
    private Spec spec;

    OptionRow(final List<Spec> choices, final Spec spec, final String joined) {
      super(new BorderLayout(0, 4));
      this.spec = spec;
      setOpaque(false);
      setAlignmentX(Component.LEFT_ALIGNMENT);
      setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
      final String what = spec.group() == Group.CHECK ? "check" : "effect";
      kind = named(new JComboBox<>(choices.toArray(Spec[]::new)), "What the " + what + " is");
      kind.setRenderer(new DefaultListCellRenderer() {
        @Serial private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(final JList<?> list,
            final Object value, final int index, final boolean selected, final boolean focus) {
          return super.getListCellRendererComponent(list,
              value instanceof Spec s ? s.label() : value, index, selected, focus);
        }
      });
      kind.setSelectedItem(spec);
      kind.addActionListener(e -> {
        this.spec = (Spec) kind.getSelectedItem();
        showFields("");
        changed();
      });
      final JPanel head = new JPanel(new BorderLayout(6, 0));
      head.setOpaque(false);
      head.add(kind, BorderLayout.CENTER);
      head.add(removeButton(what, this), BorderLayout.EAST);
      add(head, BorderLayout.NORTH);
      add(fields, BorderLayout.CENTER);
      showFields(joined);
    }

    private void showFields(final String joined) {
      fields.removeAll();
      values.clear();
      messages.clear();
      final List<String> pieces = TriggerXml.split(spec, joined);
      for (int i = 0; i < spec.pieces().size(); i++) {
        final Piece piece = spec.pieces().get(i);
        final String value = pieces.get(i);
        final String label =
            piece.label() + (piece.shape() == Shape.OPTIONAL ? " (optional)" : "");
        if (piece.shape() == Shape.MANY) {
          final ListField list = new ListField(piece.label(), names(piece), value);
          values.add(list::value);
          addRow(fields, label, list);
        } else if (piece.part() == Part.BOOL) {
          final JComboBox<String> bool = watched(new JComboBox<>(new String[] {"true", "false"}));
          bool.setSelectedItem(value.isEmpty() ? "false" : value.toLowerCase(Locale.ROOT));
          values.add(() -> text(bool));
          addRow(fields, label, bool);
        } else {
          final JComboBox<String> box = watched(chooser(names(piece), value));
          values.add(() -> text(box));
          addRow(fields, label, box);
          if (piece.part() == Part.TEXT_KEY) {
            final JTextField message = new JTextField(
                project.texts().value(MapTexts.Kind.NOTIFICATIONS, value).orElse(""));
            box.addActionListener(e -> project.texts()
                .value(MapTexts.Kind.NOTIFICATIONS, text(box)).ifPresent(message::setText));
            new DocumentListenerBuilder(TriggerAssistant.this::changed).attachTo(message);
            messages.add(() -> new Entry(text(box), message.getText().strip()));
            addRow(fields, "Its text (HTML)", message);
          }
        }
      }
      fields.revalidate();
    }

    private List<String> names(final Piece piece) {
      return switch (piece.part()) {
        case PROPERTY -> piece.words().isEmpty() ? List.of()
            : AttachmentsXml.knownOptions(game, piece.words().getFirst());
        case TEXT_KEY -> project.texts().entries(MapTexts.Kind.NOTIFICATIONS).stream()
            .map(Entry::key).toList();
        case WORD -> piece.words();
        default -> Stream.concat(piece.words().stream(), piece.part().names(game).stream())
            .toList();
      };
    }

    Option option() {
      return TriggerXml.option(spec.option(),
          TriggerXml.join(spec, values.stream().map(Supplier::get).toList()));
    }

    List<Entry> messages() {
      return messages.stream().map(Supplier::get).toList();
    }
  }

  /** A condition of the trigger: one of the map's, or a new one, with its checks. */
  private final class ConditionBox extends JPanel {
    @Serial private static final long serialVersionUID = 1L;
    private final Optional<Attachment> existing;
    private final String name;
    private final JComboBox<String> owner;
    private final ListField forNations;
    private final JCheckBox inverted = new JCheckBox("Not: holds when its checks do not");
    private final JPanel checks = column();

    ConditionBox(final Optional<Attachment> existing, final String name) {
      super(new BorderLayout(0, 6));
      this.existing = existing;
      this.name = name;
      setAlignmentX(Component.LEFT_ALIGNMENT);
      setBorder(BorderFactory.createCompoundBorder(
          BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0),
              BorderFactory.createLineBorder(Theme.getSeparatorColor())),
          BorderFactory.createEmptyBorder(8, 10, 8, 10)));
      owner = watched(chooser(MapXml.nations(game),
          existing.map(Attachment::getAttachTo).orElse(text(nation))));
      owner.setEditable(false);
      // a condition the map has stays where it is, so other triggers still find it
      owner.setEnabled(existing.isEmpty());
      forNations = new ListField("Nations", MapXml.nations(game),
          existing.flatMap(c -> TriggerXml.value(c, "players")).orElse(""));
      inverted.setSelected(existing.map(c -> isTrue(c, "invert")).orElse(false));
      inverted.addActionListener(e -> changed());
      checks.setOpaque(false);
      existing.ifPresent(c -> AttachmentsXml.options(c).forEach(o ->
          TriggerXml.spec(o.getName()).filter(s -> s.group() == Group.CHECK)
              .ifPresent(s -> checks.add(new OptionRow(TriggerXml.specs(Group.CHECK), s,
                  TriggerXml.joined(o))))));
      final JPanel head = new JPanel(new BorderLayout(6, 0));
      head.setOpaque(false);
      head.add(new JLabel("<html><b>" + SearchList.escape(name) + "</b>"
          + (existing.isPresent() ? " (the map's)" : " (new)") + "</html>"), BorderLayout.CENTER);
      head.add(removeButton("condition", this), BorderLayout.EAST);
      final JPanel body = grid();
      addRow(body, "Of nation", owner);
      addRow(body, "For nations (optional)", forNations);
      addRow(body, "", inverted);
      final JPanel center = new JPanel(new BorderLayout(0, 4));
      center.setOpaque(false);
      center.add(body, BorderLayout.NORTH);
      center.add(checks, BorderLayout.CENTER);
      center.add(row(button("Add check", "Adds something the condition checks", () -> {
        checks.add(new OptionRow(TriggerXml.specs(Group.CHECK),
            TriggerXml.specs(Group.CHECK).getFirst(), ""));
        changed();
      })), BorderLayout.SOUTH);
      add(head, BorderLayout.NORTH);
      add(center, BorderLayout.CENTER);
      // who else uses it, worked out once, as a change here changes them too
      final List<String> others = existing.isEmpty() ? List.of()
          : TriggerXml.otherUsers(game, name, Set.of(original.name()));
      if (!others.isEmpty()) {
        final JLabel shared = new JLabel("<html><div style='width:" + (TEXT_WIDTH - 40)
            + "px'>Also used by " + SearchList.escape(String.join(", ", others))
            + ": a change here changes them too.</div></html>");
        shared.setForeground(Theme.getSecondaryTextColor());
        add(shared, BorderLayout.SOUTH);
      }
    }

    Attachment attachment() {
      final Attachment before = existing.orElseGet(
          () -> AttachmentsXml.newAttachment(TriggerXml.CONDITION, name, text(owner)));
      final List<Option> wanted = new ArrayList<>();
      AttachmentsXml.options(before).stream()
          .filter(o -> !TriggerXml.isManagedOnCondition(o.getName()))
          .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o)))
          .forEach(wanted::add);
      if (!forNations.value().isEmpty()) {
        wanted.add(TriggerXml.option("players", forNations.value()));
      }
      addUnlessDefault(wanted, before, "invert",
          TriggerXml.value(before, "invert")
              .filter(String.valueOf(inverted.isSelected())::equalsIgnoreCase)
              .orElse(String.valueOf(inverted.isSelected())),
          "false");
      Stream.of(checks.getComponents())
          .filter(OptionRow.class::isInstance)
          .map(c -> ((OptionRow) c).option())
          .forEach(wanted::add);
      return TriggerXml.withOptions(before, wanted);
    }
  }
}
