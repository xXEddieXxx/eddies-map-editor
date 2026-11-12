package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import lombok.experimental.ExtensionMethod;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MapTexts.Entry;
import eddies.mapeditor.TriggerXml.Clauses;
import eddies.mapeditor.TriggerXml.Group;
import eddies.mapeditor.TriggerXml.Part;
import eddies.mapeditor.TriggerXml.Piece;
import eddies.mapeditor.TriggerXml.Problems;
import eddies.mapeditor.TriggerXml.Shape;
import eddies.mapeditor.TriggerXml.Spec;
import eddies.mapeditor.TriggerXml.Trigger;

/**
 * Makes or changes a trigger step by step, a page per step: Start (a new trigger only, from an
 * example), If, Then, When and how often, Review. The rail on the left shows each step in one line
 * and jumps to it; the footer shows the first problem. Its verb writes the trigger, its new
 * conditions and its messages as one step; options and condition names it does not know are kept
 * as they are.
 */
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
final class TriggerAssistant {
  private static final String ALL = "all of them";
  private static final String ANY = "any of them";
  // a number as conditionType means exactly that many (AbstractConditionsAttachment)
  private static final String EXACTLY = "exactly";
  private static final String FROM_TO = "from ... to";
  private static final String RELATIONSHIP_CHANGE = "relationshipChange";
  // a piece left empty, eg "Condition c: Owns territories: Territories is missing."
  private static final java.util.regex.Pattern MISSING =
      java.util.regex.Pattern.compile(".*: (.+) is missing\\.");
  private static final int TEXT_WIDTH = 560;
  private static final int LABEL_WIDTH = 170;
  private static final int RAIL_WIDTH = 200;
  private static final int RAIL_SUMMARY = 26;
  private static final String CURRENT_MARK = "●";
  private static final int TYPING_PAUSE_MS = 150;
  private static final String HELP =
      "A trigger changes the game when its conditions hold, eg gives PUs or units, changes"
          + " owners, relationships or what can be bought. It fires in its nation's turn; the"
          + " nations it acts for get what it gives. Without steps each effect happens at its"
          + " own moment. A condition may be shared with other triggers: a change then changes"
          + " them too. A use is taken at the end of a round in which it fired; 0 uses: it"
          + " fires only when another trigger fires it.";
  /** The effects of the "Add what happens" menu by group; the others go under Advanced. */
  private static final List<Map.Entry<String, List<String>>> EFFECT_GROUPS = List.of(
      Map.entry("Money & units", List.of("resource", "purchase", "placement", "removeUnits")),
      Map.entry("Land & politics", List.of("changeOwnership", RELATIONSHIP_CHANGE)),
      Map.entry("Production & tech",
          List.of("frontier", "productionRule", "tech", "availableTech", "support")),
      Map.entry("Messages & victory", List.of("notification", "victory")));

  /** The assistant's pages, in their order. */
  enum Step {
    START("Start"),
    IF("If"),
    THEN("Then"),
    WHEN("When and how often"),
    REVIEW("Review");

    private final String label;

    Step(final String label) {
      this.label = label;
    }
  }

  /** The examples a new trigger may start from; each fills If, Then and When. */
  enum Pattern {
    GIVE_PUS("Give PUs when a nation holds territories", "hold territories"),
    PLACE_UNITS("Place units when a nation holds territories", "get units"),
    DECLARE_WAR("Change relationships (declare war) in a round", "declare war"),
    MESSAGE("Show a message in a round", "message"),
    VICTORY("Win the game when a side holds territories", "win"),
    EMPTY("Empty trigger", "");

    private final String label;
    private final String words;

    Pattern(final String label, final String words) {
      this.label = label;
      this.words = words;
    }

    /** The short name a trigger from it gets, eg "Germans hold territories"; empty: none. */
    String name(final String nation) {
      return words.isEmpty() ? "" : nation + " " + words;
    }

    /**
     * The example as a trigger of the nation: one new condition, the effects, in 1 round; the
     * places the map maker must choose, eg the territories, left empty.
     */
    Trigger draft(final Game game, final String nation) {
      final Trigger empty = TriggerXml.newTrigger("", nation);
      if (this == EMPTY) {
        return empty;
      }
      final String key = name(nation).replaceAll("\\W+", "_");
      final Option check = switch (this) {
        case GIVE_PUS, PLACE_UNITS -> TriggerXml.option("directOwnershipTerritories", "");
        case DECLARE_WAR -> TriggerXml.option("rounds", "3");
        case MESSAGE -> TriggerXml.option("rounds", "1");
        default -> TriggerXml.option("alliedOwnershipTerritories", "");
      };
      final List<String> resources = Part.RESOURCE.names(game);
      final List<Option> effects = switch (this) {
        case GIVE_PUS -> TriggerXml.resourceOptions("5", resources.contains("PUs") ? "PUs"
            : resources.stream().findFirst().orElse("PUs"));
        case PLACE_UNITS -> List.of(TriggerXml.option("placement", ""));
        case DECLARE_WAR -> List.of(TriggerXml.option(RELATIONSHIP_CHANGE, String.join(":",
            nation,
            MapXml.nations(game).stream().filter(n -> !n.equals(nation)).findFirst().orElse(""),
            "any", TriggerXml.warType(game))));
        case MESSAGE -> List.of(TriggerXml.option("notification", key));
        default -> List.of(TriggerXml.option("victory", key));
      };
      final Attachment condition = TriggerXml.withOptions(
          AttachmentsXml.newAttachment(TriggerXml.CONDITION,
              TriggerXml.newName(game, TriggerXml.CONDITION, "_" + key, Set.of()), nation),
          List.of(check));
      return new Trigger(-1, TriggerXml.withOptions(empty.attachment(), Stream.concat(
              Stream.of(TriggerXml.option("conditions", condition.getName()),
                  TriggerXml.option("uses", "1")),
              effects.stream()).toList()),
          List.of(condition));
    }
  }

  private final MapProject project;
  private final Game game;
  private final Trigger original;
  private final List<Step> steps;
  private final JComponent content;
  private @Nullable JDialog dialog;
  private final CardLayout cards = new CardLayout();
  private final JPanel pages = new JPanel(cards);
  private final Map<Step, JComponent> pageOf = new EnumMap<>(Step.class);
  private final Map<Step, JToggleButton> railOf = new EnumMap<>(Step.class);
  private final Set<Step> seen = EnumSet.noneOf(Step.class);
  private Step shown = Step.IF;
  private final JButton footerProblem = new JButton();
  private String firstProblem = "";
  private final JButton back = new JButton("Back");
  private final JButton next = new JButton("Next");
  private final JPanel reviewCard = new JPanel(new BorderLayout());
  private final JPanel reviewProblems = column();
  private final Map<Pattern, JRadioButton> patternRadios = new EnumMap<>(Pattern.class);
  private final ButtonGroup patternGroup = new ButtonGroup();
  private Optional<Pattern> chosen = Optional.empty();
  // what If, Then and When said when the example was applied, to ask before replacing changes
  private List<Object> applied = List.of();
  private final JButton verb;
  private final JCheckBox triggersOn = new JCheckBox("Turn on the game option Use Triggers");
  private final JComboBox<String> nation;
  private final ListField players;
  private final JTextField shortName = new JTextField();
  private final JRadioButton usual = new JRadioButton("At each effect's usual moment");
  private final JRadioButton atSteps = new JRadioButton("Right before or after steps");
  private final JLabel usualMoment = new JLabel();
  private final JPanel whenRows = column();
  private final JButton addStep = Theme.newAddButton("Add step",
      "Adds a step the trigger happens before or after", () -> addWhen("after:"));
  private final List<ConditionBox> boxes = new ArrayList<>();
  private final JButton addCheck =
      Theme.newAddButton("Add a check", "Adds something that must be true", this::addCheck);
  private final JButton addEffect = Theme.newAddButton("Add what happens",
      "Adds something the trigger does", this::showEffectMenu);
  // the condition boxes and the names kept as they are, in the trigger's order
  private final JPanel conditionsPanel = column();
  private final JComboBox<String> combine =
      new JComboBox<>(new String[] {ALL, ANY, EXACTLY, FROM_TO});
  private final JSpinner low = Form.newNumber(1, 0, 99, n -> changed());
  private final JSpinner high = Form.newNumber(2, 1, 99, n -> changed());
  private final JLabel to = new JLabel("to");
  private final JCheckBox not = new JCheckBox("Fire when the conditions are not met");
  // effect rows and the relationship table, in the trigger's order
  private final JPanel effectRows = column();
  private final JRadioButton once = new JRadioButton("In 1 round");
  private final JRadioButton times = new JRadioButton("In");
  private final JLabel rounds = new JLabel("rounds");
  private final JRadioButton always = new JRadioButton("Every round");
  private final JSpinner timesCount = Form.newNumber(2, 0, 999, n -> changed());
  private final JCheckBox chanceOn = new JCheckBox("Only by chance");
  private final JSpinner chanceHit = Form.newNumber(1, 0, 120, n -> changed());
  private final JLabel chanceIn = new JLabel("in");
  private final JSpinner chanceSides = Form.newNumber(6, 1, 120, n -> changed());
  private boolean filling = true;
  // the problems are checked over the whole game, too slow for each key on a big map
  private final Timer typingPause = new Timer(TYPING_PAUSE_MS, e -> changed());

  /** The assistant for the trigger, not shown yet; {@link #show} opens it. */
  TriggerAssistant(final MapProject project, final Trigger trigger) {
    this.project = project;
    this.game = project.game();
    this.original = trigger;
    verb = new JButton(isNew() ? "Add trigger" : "Save trigger");
    nation = named(chooser(MapXml.nations(game), trigger.nation()), "Whose turn it fires in");
    nation.setEditable(false);
    players = new ListField("Nations", Part.NATION.names(game), "");
    steps = isNew() ? List.of(Step.values())
        : List.of(Step.IF, Step.THEN, Step.WHEN, Step.REVIEW);
    if (!isNew()) {
      seen.addAll(steps);
    }
    fill(trigger);
    triggersOn.setSelected(true);
    triggersOn.setVisible(!TriggerXml.usesTriggers(game));
    filling = false;
    content = newContent();
    applied = state();
    go(steps.getFirst() == Step.START ? Step.START : Step.REVIEW);
  }

  /** Opens the assistant for a new trigger of the first nation. */
  static void openNew(final Component parent, final MapProject project) {
    openNew(parent, project, "");
  }

  /**
   * Opens the assistant for a new trigger of the nation, from the first example; empty: the first
   * nation.
   */
  static void openNew(final Component parent, final MapProject project, final String nation) {
    final Game game = project.game();
    final String owner =
        nation.isEmpty() ? MapXml.nations(game).stream().findFirst().orElse("") : nation;
    final TriggerAssistant assistant = new TriggerAssistant(project,
        TriggerXml.newTrigger(TriggerXml.newName(game, TriggerXml.TRIGGER, "", Set.of()), owner));
    assistant.apply(Pattern.GIVE_PUS, owner);
    assistant.show(parent);
  }

  /** Opens the assistant for the trigger at this place in the game's attachments. */
  static void open(final Component parent, final MapProject project, final int index) {
    new TriggerAssistant(project, TriggerXml.trigger(project.game(), index)).show(parent);
  }

  /** As big as fits into the window it opens over, so the buttons always show. */
  private void show(final Component parent) {
    final Window owner = SwingUtilities.getWindowAncestor(parent);
    final JDialog shown = new JDialog(owner, title(), JDialog.ModalityType.APPLICATION_MODAL);
    dialog = shown;
    shown.setContentPane(content);
    final Dimension room =
        owner == null ? Toolkit.getDefaultToolkit().getScreenSize() : owner.getSize();
    shown.setMinimumSize(new Dimension(720, 560));
    shown.setSize(Math.min(900, room.width - 80), Math.min(680, room.height - 40));
    shown.setResizable(true);
    shown.setLocationRelativeTo(parent);
    shown.setVisible(true);
  }

  private void close() {
    Optional.ofNullable(dialog).ifPresent(JDialog::dispose);
  }

  private boolean isNew() {
    return original.index() < 0;
  }

  String title() {
    return isNew() ? "New trigger"
        : "Trigger: " + TriggerXml.shortName(original.name()).replace('_', ' ');
  }

  JButton verb() {
    return verb;
  }

  /** The assistant's inside, for tests. */
  JComponent content() {
    return content;
  }

  /** The step's page, for tests. */
  JComponent page(final Step step) {
    return pageOf.get(step);
  }

  /** The page shown. */
  Step shown() {
    return shown;
  }

  /** The problem the footer shows, empty when there is none. */
  String footerProblem() {
    return firstProblem;
  }

  /** The footer's button to the problem's step, for tests. */
  JButton footerProblemButton() {
    return footerProblem;
  }

  JButton next() {
    return next;
  }

  // ---- filling in what the trigger has

  /** Shows what the trigger has, in place of what was shown. */
  private void fill(final Trigger from) {
    boxes.clear();
    conditionsPanel.removeAll();
    effectRows.removeAll();
    whenRows.removeAll();
    final Attachment attachment = from.attachment();
    final List<String> when = TriggerXml.values(attachment, "when");
    (when.isEmpty() ? usual : atSteps).setSelected(true);
    when.forEach(this::addWhen);
    for (final String name : new LinkedHashSet<>(from.conditionNames())) {
      from.conditions().stream()
          .filter(c -> name.equals(c.getName()))
          .findFirst()
          .ifPresentOrElse(
              c -> addBox(new ConditionBox(
                  TriggerXml.condition(game, name).isPresent() ? Optional.of(c) : Optional.empty(),
                  c)),
              () -> conditionsPanel.add(new KeptName(name)));
    }
    players.setValue(TriggerXml.value(attachment, "players").orElse(""));
    combine.setSelectedItem(ALL);
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
    fillEffects(attachment);
    final int uses = TriggerXml.value(attachment, "uses").filter(u -> u.matches("-?\\d+"))
        .map(Integer::parseInt).orElse(-1);
    (uses < 0 ? always : uses == 1 ? once : times).setSelected(true);
    // 0, which a used-up trigger may have, stays 0
    timesCount.setValue(uses == 0 || uses > 1 ? uses : 2);
    chanceOn.setSelected(false);
    TriggerXml.value(attachment, "chance").map(c -> c.split(":")).filter(c -> c.length == 2)
        .filter(c -> c[0].matches("\\d+") && c[1].matches("\\d+"))
        .ifPresent(c -> {
          chanceOn.setSelected(true);
          chanceHit.setValue(Integer.parseInt(c[0]));
          chanceSides.setValue(Integer.parseInt(c[1]));
        });
  }

  /**
   * Starts the trigger from the example, for the nation: replaces If, Then and When, and the name.
   */
  void apply(final Pattern pattern, final String nationName) {
    filling = true;
    nation.setSelectedItem(nationName);
    shortName.setText(pattern.name(nationName));
    fill(pattern.draft(game, nationName));
    patternRadios.get(pattern).setSelected(true);
    chosen = Optional.of(pattern);
    filling = false;
    applied = state();
    changed();
  }

  /** The example chosen on Start; asks first when what it would replace was changed. */
  private void choose(final Pattern pattern) {
    if (state().equals(applied) || Dialogs.confirm(content, "Start over",
        "Start from this example?\nWhat you set in steps 2 to 4 is replaced.", "Start over")) {
      apply(pattern, text(nation));
    } else {
      chosen.map(patternRadios::get).ifPresentOrElse(
          r -> r.setSelected(true), patternGroup::clearSelection);
    }
  }

  /** An example not changed since is applied again for the other nation. */
  private void nationChanged() {
    if (filling) {
      return;
    }
    if (isNew() && chosen.isPresent() && state().equals(applied)) {
      apply(chosen.get(), text(nation));
    } else {
      changed();
    }
  }

  /** What If, Then and When say, without the nation. */
  private List<Object> state() {
    final Clauses clauses = TriggerXml.clauses(game, trigger());
    return List.of(clauses.conditions(), clauses.effects(), clauses.when(), clauses.often());
  }

  /**
   * One row per effect, except: the first resource and its amount are one "Give PUs" row (a lone
   * amount keeps its own row), and all relationship changes are one table, at the first of them.
   */
  private void fillEffects(final Attachment attachment) {
    final Optional<String> resource = TriggerXml.value(attachment, "resource");
    final Set<String> paired = new HashSet<>(
        resource.isPresent() ? Set.of("resource", "resourceCount") : Set.of());
    boolean pairShown = false;
    boolean tableShown = false;
    for (final Option option : AttachmentsXml.options(attachment)) {
      final Optional<Spec> spec =
          TriggerXml.spec(option.getName()).filter(s -> s.group() == Group.EFFECT);
      if (spec.isEmpty()) {
        continue;
      }
      if (paired.remove(option.getName())) {
        if (!pairShown) {
          pairShown = true;
          effectRows.add(new OptionRow(effectChoices(), TriggerXml.GIVE_RESOURCE, List.of(
              TriggerXml.value(attachment, "resourceCount").orElse(""), resource.orElseThrow())));
        }
      } else if (spec.get().option().equals(RELATIONSHIP_CHANGE)) {
        if (!tableShown) {
          tableShown = true;
          effectRows.add(new RelationshipTable(TriggerXml.values(attachment, RELATIONSHIP_CHANGE)
              .stream().map(j -> TriggerXml.split(spec.get(), j)).toList()));
        }
      } else {
        effectRows.add(new OptionRow(effectChoices(), spec.get(), TriggerXml.joined(option)));
      }
    }
  }

  /** The effects to choose from: Give PUs instead of a resource and its amount apart. */
  private static List<Spec> effectChoices() {
    return TriggerXml.specs(Group.EFFECT).stream()
        .filter(s -> !s.option().equals("resourceCount"))
        .map(s -> s.option().equals("resource") ? TriggerXml.GIVE_RESOURCE : s)
        .toList();
  }

  private static boolean isTrue(final Attachment attachment, final String option) {
    return TriggerXml.value(attachment, option).filter("true"::equalsIgnoreCase).isPresent();
  }

  // ---- the dialog

  /** Title on top, the rail on the left, the step's page, the footer with the first problem. */
  private JComponent newContent() {
    final ButtonGroup whenGroup = new ButtonGroup();
    whenGroup.add(usual);
    whenGroup.add(atSteps);
    final ButtonGroup often = new ButtonGroup();
    Stream.of(once, times, always).forEach(often::add);
    Stream.of(usual, atSteps, once, times, always, chanceOn, not, triggersOn)
        .forEach(b -> b.addActionListener(e -> changed()));
    combine.addActionListener(e -> changed());
    nation.addActionListener(e -> nationChanged());
    OnTyping.attach(shortName, this::typed);
    usualMoment.setForeground(Theme.getSecondaryTextColor());
    combine.setToolTipText("How many conditions must hold for the trigger to fire");
    low.setToolTipText("How many conditions must hold, or the fewest");
    high.setToolTipText("The most conditions that may hold");
    timesCount.setToolTipText("In how many rounds the trigger may fire; 0 is used up");
    chanceHit.setToolTipText("The roll it needs: this or less");
    chanceSides.setToolTipText("The sides of the die rolled");
    Stream.of(combine, low, high, timesCount, chanceHit, chanceSides)
        .forEach(c -> c.getAccessibleContext().setAccessibleName(c.getToolTipText()));

    if (isNew()) {
      pageOf.put(Step.START, startPage());
    }
    pageOf.put(Step.IF, ifPage());
    pageOf.put(Step.THEN, thenPage());
    pageOf.put(Step.WHEN, whenPage());
    pageOf.put(Step.REVIEW, reviewPage());
    final JPanel rail = column();
    rail.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.getSeparatorColor()),
        BorderFactory.createEmptyBorder(Theme.GAP_S, Theme.GAP_S, Theme.GAP_S, Theme.GAP_S)));
    rail.setPreferredSize(new Dimension(RAIL_WIDTH, 1));
    final ButtonGroup railGroup = new ButtonGroup();
    for (final Step step : steps) {
      pages.add(SwingComponents.newJScrollPane(pageOf.get(step)), step.name());
      final JToggleButton item = new JToggleButton();
      item.putClientProperty(FlatClientProperties.BUTTON_TYPE,
          FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
      item.setHorizontalAlignment(SwingConstants.LEADING);
      item.setAlignmentX(Component.LEFT_ALIGNMENT);
      item.getAccessibleContext().setAccessibleName("Step " + step.label);
      item.addActionListener(e -> go(step));
      railGroup.add(item);
      railOf.put(step, item);
      rail.add(item);
      rail.add(Box.createVerticalStrut(Theme.GAP_XS));
    }

    final JComponent title = Help.with(Theme.newTitle(title()), HELP);
    title.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, Theme.GAP_L, Theme.GAP_M,
        Theme.GAP_L));
    Theme.setBorderlessButtonStyle(footerProblem);
    footerProblem.setHorizontalAlignment(SwingConstants.LEADING);
    footerProblem.addActionListener(e -> go(stepOf(firstProblem)));
    final JButton cancel = new JButton("Cancel");
    cancel.addActionListener(e -> close());
    back.addActionListener(e -> go(steps.get(steps.indexOf(shown) - 1)));
    next.addActionListener(e -> go(steps.get(steps.indexOf(shown) + 1)));
    verb.addActionListener(e -> finish());
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
    Stream.of(cancel, back, next, verb).forEach(buttons::add);
    final JPanel footer = new JPanel(new BorderLayout(Theme.GAP_M, 0));
    footer.setBorder(Theme.newFooterBorder());
    footer.add(footerProblem, BorderLayout.CENTER);
    footer.add(buttons, BorderLayout.EAST);
    final JPanel panel = new JPanel(new BorderLayout());
    panel.add(title, BorderLayout.NORTH);
    panel.add(rail, BorderLayout.WEST);
    panel.add(pages, BorderLayout.CENTER);
    panel.add(footer, BorderLayout.SOUTH);
    return panel;
  }

  private static Form page(final Step step, final String question) {
    return new Form(TEXT_WIDTH).title(step.label).subtitle(question);
  }

  private JComponent startPage() {
    final JPanel radios = column();
    radios.setOpaque(false);
    for (final Pattern pattern : Pattern.values()) {
      final JRadioButton radio = new JRadioButton(pattern.label);
      radio.setAlignmentX(Component.LEFT_ALIGNMENT);
      radio.addActionListener(e -> choose(pattern));
      // without relationship types no war can be declared: TripleA refuses an empty one
      radio.setVisible(pattern != Pattern.DECLARE_WAR || !TriggerXml.warType(game).isEmpty());
      patternGroup.add(radio);
      patternRadios.put(pattern, radio);
      radios.add(radio);
    }
    return page(Step.START, "Whose turn does it fire in, and what should it do?")
        .field("Whose turn does it fire in?", nation)
        .field("Start from", radios)
        .end();
  }

  private JComponent ifPage() {
    final JLabel fireWhen = new JLabel("Fire when");
    fireWhen.setLabelFor(combine);
    return page(Step.IF, "What must be true for the trigger to fire?")
        .row(conditionsPanel)
        .row(row(addCheck))
        .more("trigger-if", 4, more -> more
            .row(row(fireWhen, combine, low, to, high))
            .row(row(
                button("New condition", "Adds a condition of its own for this trigger",
                    () -> addBox(new ConditionBox(Optional.empty(), newCondition()))),
                button("Use a condition of the map...", "Adds a condition the map has already",
                    this::chooseCondition)))
            .row(not))
        .end();
  }

  private JComponent thenPage() {
    return page(Step.THEN, "What happens?")
        .row(effectRows)
        .row(row(addEffect))
        .more("trigger-then", 1,
            more -> more.field("Give it to other nations instead", players))
        .end();
  }

  private JComponent whenPage() {
    return page(Step.WHEN, "When does it happen, and how often?")
        .section("When")
        .row(usual)
        .row(usualMoment)
        .row(atSteps)
        .row(whenRows)
        .row(row(addStep))
        .section("How often")
        .row(row(once, times, timesCount, rounds, always))
        .row(Theme.newSecondaryLabel("A use is taken at the end of a round in which it fired."))
        .row(row(chanceOn, chanceHit, chanceIn, chanceSides))
        .end();
  }

  private JComponent reviewPage() {
    final Form form = page(Step.REVIEW, "Check and save").row(reviewCard);
    reviewCard.setOpaque(false);
    if (isNew()) {
      form.field("Name", shortName)
          .note("Saved as triggerAttachment_ with the name after it.");
    } else {
      form.field("Nation", nation).note("Name: " + SearchList.escape(original.name()));
    }
    final List<Option> kept = AttachmentsXml.options(original.attachment()).stream()
        .filter(o -> !TriggerXml.isManagedOnTrigger(o.getName()))
        .toList();
    if (!kept.isEmpty()) {
      form.section("Kept as is");
      form.note("Options the assistant does not change; Attachments shows them.");
      kept.forEach(o -> form.note(SearchList.escape(o.getName() + " = " + TriggerXml.joined(o))));
    }
    reviewProblems.setOpaque(false);
    return form.section("Problems").row(reviewProblems).row(triggersOn).end();
  }

  /** Shows the step's page, with Next on the way to Review and the verb there. */
  void go(final Step step) {
    shown = step;
    seen.add(step);
    cards.show(pages, step.name());
    railOf.get(step).setSelected(true);
    final boolean last = step == Step.REVIEW;
    back.setVisible(steps.indexOf(step) > 0);
    next.setVisible(!last);
    // an existing trigger may be saved from any step
    verb.setVisible(last || !isNew());
    if (last) {
      Theme.setPrimaryButtonStyle(verb);
      Theme.setSecondaryButtonStyle(next);
    } else {
      Theme.setPrimaryButtonStyle(next);
      Theme.setSecondaryButtonStyle(verb);
    }
    changed();
  }

  /**
   * The step whose page fixes the problem.
   *
   * <p>shortcut: matches the problems' texts; give Problems a step if their wording drifts.
   */
  static Step stepOf(final String problem) {
    if (Stream.of("Condition ", "The condition ", "The game has no condition",
        "Another condition", "Combine", "conditionType:", "invert:").anyMatch(problem::startsWith)) {
      return Step.IF;
    } else if (Stream.concat(
            Stream.of(TriggerXml.GIVE_RESOURCE.label(), "The trigger does nothing", "Message ",
                "Trigger ", "A trigger cannot fire", "players:",
                MapTexts.Kind.NOTIFICATIONS.getFile()),
            TriggerXml.specs(Group.EFFECT).stream().map(Spec::label))
        .anyMatch(problem::startsWith)) {
      return Step.THEN;
    } else if (Stream.of("A chance", "chance:", "uses:", "when:").anyMatch(problem::startsWith)) {
      return Step.WHEN;
    }
    return Step.REVIEW;
  }

  /**
   * The problem of a step not reached yet as what to do there, without the names of new
   * conditions, eg "choose the territories".
   */
  static String nextHint(final String problem) {
    final Matcher missing = MISSING.matcher(problem);
    return missing.matches() ? "choose the " + missing.group(1).toLowerCase(Locale.ROOT)
        : problem.replaceAll("^Condition \\S+: ", "");
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
    return Theme.newIconButton("×", "Remove " + what, () -> {
      boxes.remove(removed);
      Optional.ofNullable(removed.getParent()).ifPresent(p -> {
        p.remove(removed);
        p.revalidate();
      });
      changed();
    });
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

  /** Calls {@link #changed} on every change of the box, after a pause while typing. */
  private JComboBox<String> watched(final JComboBox<String> box) {
    box.addActionListener(e -> changed());
    if (box.isEditable()) {
      OnTyping.attach((JTextComponent) box.getEditor().getEditorComponent(), this::typed);
    }
    return box;
  }

  private static <T extends JComponent> T named(final T component, final String name) {
    component.setToolTipText(name);
    component.getAccessibleContext().setAccessibleName(name);
    return component;
  }

  /** Shows the box's items in words, eg a relation as "any neutral"; the item stays as written. */
  private static void showAs(final JComboBox<String> box, final Function<String, String> words) {
    box.setRenderer(new DefaultListCellRenderer() {
      @Serial private static final long serialVersionUID = 1L;

      @Override
      public Component getListCellRendererComponent(final JList<?> list, final Object value,
          final int index, final boolean selected, final boolean focus) {
        return super.getListCellRendererComponent(list,
            value == null ? "" : words.apply(value.toString()), index, selected, focus);
      }
    });
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

  /** A new condition of the trigger's nation, named after the trigger. */
  private Attachment newCondition() {
    final String name = isNew() ? shortName.getText() : TriggerXml.shortName(original.name());
    return AttachmentsXml.newAttachment(TriggerXml.CONDITION,
        TriggerXml.newName(game, TriggerXml.CONDITION, withUnderscore(name),
            new HashSet<>(conditionNames())),
        text(nation));
  }

  /** "_name", as TripleA's names are written, eg triggerAttachment_Germans_hold; blank: "". */
  private static String withUnderscore(final String name) {
    return name.isBlank() ? "" : "_" + name.strip();
  }

  /** A check in a new condition of the trigger's own, so a newcomer never makes a condition. */
  private void addCheck() {
    final ConditionBox box = new ConditionBox(Optional.empty(), newCondition());
    box.addCheck();
    addBox(box);
  }

  /** The effects to add, in groups, the rare ones under Advanced. */
  private void showEffectMenu() {
    final List<Spec> choices = effectChoices();
    final JPopupMenu menu = new JPopupMenu();
    for (final Map.Entry<String, List<String>> group : EFFECT_GROUPS) {
      if (menu.getComponentCount() > 0) {
        menu.addSeparator();
      }
      final JMenuItem heading = new JMenuItem(group.getKey());
      heading.setEnabled(false);
      menu.add(heading);
      group.getValue().stream()
          .flatMap(o -> choices.stream().filter(s -> s.option().equals(o)))
          .forEach(s -> menu.add(effectItem(s)));
    }
    final Set<String> grouped = EFFECT_GROUPS.stream().flatMap(g -> g.getValue().stream())
        .collect(Collectors.toSet());
    final JMenu advanced = new JMenu("Advanced");
    choices.stream().filter(s -> !grouped.contains(s.option()))
        .forEach(s -> advanced.add(effectItem(s)));
    menu.addSeparator();
    menu.add(advanced);
    menu.show(addEffect, 0, addEffect.getHeight());
  }

  private JMenuItem effectItem(final Spec spec) {
    final JMenuItem item = new JMenuItem(spec.label());
    item.addActionListener(e -> addEffect(spec));
    return item;
  }

  /** Adds the effect, empty; a relationship change as a row of the table. */
  void addEffect(final Spec spec) {
    if (spec.option().equals(RELATIONSHIP_CHANGE)) {
      addRelationshipChange(-1);
    } else {
      effectRows.add(new OptionRow(effectChoices(), spec, ""));
      effectRows.revalidate();
    }
    changed();
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
    final SearchList list = new SearchList("condition", "conditions", () -> names,
        n -> TriggerXml.condition(game, n).map(TriggerXml::conditionSentence).orElse(n),
        n -> n, n -> chosen[0] = n);
    list.setPreferredSize(new Dimension(520, 380));
    if (Dialogs.ask(content, "Use a condition of the map", list, "Use condition",
        () -> chosen[0] != null) && chosen[0] != null) {
      TriggerXml.condition(game, chosen[0])
          .ifPresent(c -> addBox(new ConditionBox(Optional.of(c), c)));
    }
  }

  // ---- the trigger as filled in

  /** The trigger as filled in; the same attachments as the game's for a trigger left alone. */
  Trigger trigger() {
    final Attachment before = original.attachment();
    final String name = isNew()
        ? TriggerXml.newName(game, TriggerXml.TRIGGER, withUnderscore(shortName.getText()),
            Set.of())
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
    effects().forEach(e -> wanted.addAll(e.options()));
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

  private List<Effect> effects() {
    return Stream.of(effectRows.getComponents())
        .filter(Effect.class::isInstance)
        .map(Effect.class::cast)
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

  /** Checks again once typing pauses. */
  private void typed() {
    if (!filling) {
      typingPause.setRepeats(false);
      typingPause.restart();
    }
  }

  private void changed() {
    typingPause.stop();
    if (filling) {
      return;
    }
    final Trigger trigger = trigger();
    final Problems problems = TriggerXml.problems(
        triggersOn.isVisible() && triggersOn.isSelected() ? TriggerXml.withTriggersOn(game) : game,
        withMessages(project.texts()), trigger);
    final List<String> errors = new ArrayList<>(problems.errors());
    if (project.texts().unreadable().contains(MapTexts.Kind.NOTIFICATIONS)
        && !typedMessages().isEmpty()) {
      errors.add(MapTexts.Kind.NOTIFICATIONS.getFile() + " could not be read, so a message"
          + " text cannot be saved: fix the file in Files, or leave the text empty.");
    }
    final List<String> warnings = problems.warnings();
    // the first only, so the footer stays one line, of a step seen first; Review lists them all
    final List<String> all = Stream.concat(errors.stream(), warnings.stream()).toList();
    firstProblem = all.stream().filter(p -> seen.contains(stepOf(p))).findFirst()
        .orElse(all.stream().findFirst().orElse(""));
    // a new trigger's step not reached yet is no problem yet: what to do there instead
    final boolean ahead = !firstProblem.isEmpty() && !seen.contains(stepOf(firstProblem));
    final Theme.Status worst = ahead ? Theme.Status.TODO
        : errors.contains(firstProblem) ? Theme.Status.PROBLEM : Theme.Status.WARNING;
    footerProblem.setText(firstProblem.isEmpty() ? ""
        : ahead ? "Next: " + nextHint(firstProblem) : worst.mark() + " " + firstProblem);
    footerProblem.setForeground(worst.color());
    footerProblem.setVisible(!firstProblem.isEmpty());
    footerProblem.setToolTipText(firstProblem.isEmpty() ? null
        : "<html><div style='width:" + Theme.htmlWidth(300) + "'>"
        + SearchList.escape(firstProblem) + "<br>A click shows where to fix it.</div></html>");
    verb.setEnabled(errors.isEmpty());
    // a box adds checks itself; without one this makes the first
    addCheck.setVisible(boxes.isEmpty());
    showRail(TriggerXml.clauses(game, trigger), errors, warnings);
    if (shown == Step.REVIEW) {
      showReview(trigger, errors, warnings);
    }
    final List<String> moments = effects().stream().map(Effect::moment)
        .filter(m -> !m.isEmpty()).distinct().toList();
    usualMoment.setText(moments.isEmpty() ? "Add an effect to see when it happens."
        : "Usually: " + String.join(", ", moments) + ".");
    usualMoment.setVisible(usual.isSelected());
    whenRows.setVisible(atSteps.isSelected());
    addStep.setVisible(atSteps.isSelected());
    low.setVisible(Stream.of(EXACTLY, FROM_TO).anyMatch(s -> s.equals(combine.getSelectedItem())));
    high.setVisible(FROM_TO.equals(combine.getSelectedItem()));
    to.setVisible(high.isVisible());
    timesCount.setVisible(times.isSelected());
    rounds.setVisible(times.isSelected());
    times.setText(times.isSelected() ? "In" : "In several rounds");
    Stream.of(chanceHit, chanceIn, chanceSides).forEach(c -> c.setVisible(chanceOn.isSelected()));
    Optional.ofNullable(content).ifPresent(c -> {
      c.revalidate();
      c.repaint();
    });
  }

  /** Each step in the rail: its mark, number and name, and what it says in one line. */
  private void showRail(final Clauses clauses, final List<String> errors,
      final List<String> warnings) {
    final int problems = errors.size() + warnings.size();
    for (final Step step : steps) {
      final String summary = switch (step) {
        case START -> text(nation) + chosen.map(p -> " · " + p.label).orElse("");
        case IF -> clauses.conditions().isEmpty() ? "always"
            : clauses.conditions().replaceFirst("^if ", "");
        case THEN -> clauses.effects().isEmpty() ? "nothing yet"
            : String.join(", ", clauses.effects());
        case WHEN -> Stream.of(clauses.when(), clauses.often()).filter(s -> !s.isEmpty())
            .collect(Collectors.joining(", "));
        case REVIEW -> problems == 0 ? "ready"
            : problems + (problems == 1 ? " thing" : " things") + " to check";
      };
      final Theme.Status status = !seen.contains(step) ? Theme.Status.TODO
          : errors.stream().anyMatch(p -> stepOf(p) == step) ? Theme.Status.PROBLEM
          : warnings.stream().anyMatch(p -> stepOf(p) == step) ? Theme.Status.WARNING
          : Theme.Status.DONE;
      final String cut = summary.length() <= RAIL_SUMMARY ? summary
          : summary.substring(0, RAIL_SUMMARY - 1).strip() + "…";
      // the step shown is not done yet; its problems still show
      final boolean current = step == shown && status == Theme.Status.DONE;
      final JToggleButton item = railOf.get(step);
      item.setText("<html><span style='color:" + Theme.convertColorToHex(
          current ? Theme.getAccentColor() : status.color()) + "'>"
          + (current ? CURRENT_MARK : status.mark()) + "</span> <b>" + (steps.indexOf(step) + 1) + " " + step.label
          + "</b><br><span style='color:"
          + Theme.convertColorToHex(Theme.getSecondaryTextColor()) + "'>"
          + SearchList.escape(cut) + "</span></html>");
      item.setToolTipText("<html><div style='width:" + Theme.htmlWidth(300) + "'>"
          + SearchList.escape(summary)
          + "</div></html>");
    }
  }

  /** What the trigger does as a card, and all its problems. */
  private void showReview(final Trigger trigger, final List<String> errors,
      final List<String> warnings) {
    reviewCard.removeAll();
    reviewCard.add(clauseCard(TriggerXml.clauses(game, trigger), 5), BorderLayout.CENTER);
    reviewProblems.removeAll();
    errors.forEach(p -> reviewProblems.add(problemLabel(Theme.Status.PROBLEM, p)));
    warnings.forEach(p -> reviewProblems.add(problemLabel(Theme.Status.WARNING, p)));
    if (errors.isEmpty() && warnings.isEmpty()) {
      reviewProblems.add(problemLabel(Theme.Status.DONE, "No problems."));
    }
  }

  private static JLabel problemLabel(final Theme.Status status, final String problem) {
    final JLabel label = Theme.newStatusLabel(status, "");
    label.setText("<html><div style='width:" + Theme.htmlWidth(TEXT_WIDTH) + "'>"
        + status.mark() + " " + SearchList.escape(problem) + "</div></html>");
    label.setAlignmentX(Component.LEFT_ALIGNMENT);
    label.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_XS, 0));
    return label;
  }

  /** Writes the trigger, its new conditions, its messages and Use Triggers as one step. */
  void finish() {
    effects().forEach(Effect::stopEditing);
    // what was typed last may not be checked yet
    changed();
    if (!verb.isEnabled()) {
      return;
    }
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

  /** What the trigger does, as one or more of its options. */
  private interface Effect {
    List<Option> options();

    /** When it happens without a "when". */
    String moment();

    List<Entry> messages();

    /** Takes what is being typed into a cell, so it is written too. */
    default void stopEditing() {}
  }

  /**
   * A list of names, joined by colons as the file has them: shown short, all in its tool tip,
   * changed with Choose, or typed.
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
      final JButton type = button("Type...", "Types the " + what.toLowerCase(Locale.ROOT)
          + ", eg -name to take one away", this::type);
      type.getAccessibleContext().setAccessibleName("Type " + what);
      final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_XS, 0));
      buttons.setOpaque(false);
      if (!names.isEmpty()) {
        final JButton choose = button("Choose...",
            "Chooses the " + what.toLowerCase(Locale.ROOT), this::choose);
        choose.getAccessibleContext().setAccessibleName("Choose " + what);
        Theme.setTertiaryButtonStyle(type);
        buttons.add(type);
        buttons.add(choose);
      } else {
        buttons.add(type);
      }
      add(shown, BorderLayout.CENTER);
      add(buttons, BorderLayout.EAST);
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
          : "<html><div style='width:" + Theme.htmlWidth(300) + "'>" + SearchList.escape(all)
              + "</div></html>");
      shown.getAccessibleContext().setAccessibleName(what + ": " + (all.isEmpty() ? "none" : all));
    }

    /**
     * The names to tick; values that are no names, eg "-name" or a foreach's "@var@", stay where
     * they are, as do the ticked ones the list had, new ones come after.
     */
    private void choose() {
      final List<String> values = values();
      Dialogs.pickMany(this, what, names, values, "Choose").ifPresent(ticked -> {
        final List<String> result = new ArrayList<>(values.stream()
            .filter(v -> !names.contains(v) || ticked.contains(v))
            .distinct()
            .toList());
        ticked.stream().filter(v -> !result.contains(v)).forEach(result::add);
        setValue(String.join(":", result));
      });
    }

    private void type() {
      Dialogs.askName(this, what, what + ", separated by :", value, "Use", v -> Optional.empty())
          .ifPresent(typed -> setValue(String.join(":", Stream.of(typed.split(":"))
              .map(String::strip).filter(v -> !v.isEmpty()).toList())));
    }

    private void setValue(final String newValue) {
      value = newValue;
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
  private final class OptionRow extends JPanel implements Effect {
    @Serial private static final long serialVersionUID = 1L;
    private final JComboBox<Spec> kind;
    private final JPanel fields = grid();
    private final List<Supplier<String>> values = new ArrayList<>();
    private final List<Supplier<Entry>> messages = new ArrayList<>();
    private Spec spec;

    OptionRow(final List<Spec> choices, final Spec spec, final String joined) {
      this(choices, spec, TriggerXml.split(spec, joined));
    }

    /**
     * @param choices what the row may be; a spec not among them, eg a lone resourceCount, adds
     *     itself
     */
    OptionRow(final List<Spec> choices, final Spec spec, final List<String> pieces) {
      super(new BorderLayout(0, 4));
      this.spec = spec;
      setOpaque(false);
      setAlignmentX(Component.LEFT_ALIGNMENT);
      setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
      final String what = spec.group() == Group.CHECK ? "check" : "effect";
      kind = named(new JComboBox<>(Stream.concat(choices.stream(),
              Stream.of(spec).filter(s -> !choices.contains(s))).toArray(Spec[]::new)),
          "What the " + what + " is");
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
        if (this.spec.option().equals(RELATIONSHIP_CHANGE) && this.spec.group() == Group.EFFECT) {
          toRelationshipTable(this);
          return;
        }
        showFields(TriggerXml.split(this.spec, ""));
        changed();
      });
      final JPanel head = new JPanel(new BorderLayout(6, 0));
      head.setOpaque(false);
      head.add(kind, BorderLayout.CENTER);
      head.add(removeButton(what, this), BorderLayout.EAST);
      add(head, BorderLayout.NORTH);
      add(fields, BorderLayout.CENTER);
      showFields(pieces);
    }

    private void showFields(final List<String> pieces) {
      fields.removeAll();
      values.clear();
      messages.clear();
      for (int i = 0; i < spec.pieces().size(); i++) {
        final Piece piece = spec.pieces().get(i);
        final String value = i < pieces.size() ? pieces.get(i) : "";
        final String label =
            piece.label() + (piece.shape() == Shape.OPTIONAL ? " (optional)" : "");
        if (i == 0 && TriggerXml.countsInWords(spec)) {
          final JComboBox<String> count = watched(countBox(spec, value));
          values.add(() -> text(count));
          addRow(fields, piece.label(), count);
        } else if (piece.shape() == Shape.MANY) {
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
          if (piece.part() == Part.RELATION) {
            showAs(box, TriggerXml::plain);
          }
          values.add(() -> text(box));
          addRow(fields, label, box);
          if (piece.part() == Part.TEXT_KEY) {
            final JTextField message = new JTextField(
                project.texts().value(MapTexts.Kind.NOTIFICATIONS, value).orElse(""));
            message.setToolTipText("Shown to the players; HTML may be used, eg <br> for a new"
                + " line");
            box.addActionListener(e -> project.texts()
                .value(MapTexts.Kind.NOTIFICATIONS, text(box)).ifPresent(message::setText));
            OnTyping.attach(message, TriggerAssistant.this::typed);
            messages.add(() -> new Entry(text(box), message.getText().strip()));
            addRow(fields, "Message text", message);
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

    @Override
    public List<Option> options() {
      final List<String> pieces = values.stream().map(Supplier::get).toList();
      return spec == TriggerXml.GIVE_RESOURCE
          ? TriggerXml.resourceOptions(pieces.get(0), pieces.get(1))
          : List.of(TriggerXml.option(spec.option(), TriggerXml.join(spec, pieces)));
    }

    @Override
    public String moment() {
      return spec.moment();
    }

    @Override
    public List<Entry> messages() {
      return messages.stream().map(Supplier::get).toList();
    }
  }

  /**
   * How many must hold, in words, eg "none of them" or "at least 2"; the count as the file has it,
   * so a "0" stays "0".
   */
  private static JComboBox<String> countBox(final Spec spec, final String count) {
    final List<String> items = new ArrayList<>();
    items.add(count.equals("0") ? "0" : "");
    if (spec.pieces().getFirst().words().contains("each")) {
      items.add("each");
    }
    IntStream.rangeClosed(1, 9).mapToObj(String::valueOf).forEach(items::add);
    if (!items.contains(count)) {
      items.add(count);
    }
    final JComboBox<String> box = named(new JComboBox<>(items.toArray(String[]::new)),
        "How many of them");
    box.setSelectedItem(count);
    showAs(box, c -> {
      final String word = TriggerXml.countWord(spec, c);
      return word.equals("none") || word.equals("all") ? word + " of them" : word;
    });
    return box;
  }

  /** The row becomes a new row of the relationship table, which comes where the row was. */
  private void toRelationshipTable(final OptionRow row) {
    final int at = List.of(effectRows.getComponents()).indexOf(row);
    effectRows.remove(row);
    addRelationshipChange(at);
  }

  /** A new row of the relationship table; a new table goes at this place, -1: last. */
  private void addRelationshipChange(final int at) {
    final RelationshipTable table = effects().stream()
        .filter(RelationshipTable.class::isInstance)
        .map(RelationshipTable.class::cast)
        .findFirst()
        .orElseGet(() -> {
          final RelationshipTable added = new RelationshipTable(List.of());
          effectRows.add(added, at);
          return added;
        });
    table.addNewRow();
    effectRows.revalidate();
  }

  /** All relationship changes of the trigger as one table, one change per row. */
  private final class RelationshipTable extends JPanel implements Effect {
    @Serial private static final long serialVersionUID = 1L;
    private static final int SHOWN_ROWS = 8;
    private static final int REMOVE = 4;
    private final Spec spec = TriggerXml.spec(RELATIONSHIP_CHANGE).orElseThrow();
    private final DefaultTableModel model =
        new DefaultTableModel(new Object[] {"Nation", "Other nation", "Was", "Becomes", ""}, 0) {
          @Serial private static final long serialVersionUID = 1L;

          @Override
          public boolean isCellEditable(final int row, final int column) {
            return column != REMOVE;
          }
        };
    private final JTable table = new JTable(model);
    private final JLabel heading = Theme.newSectionLabel("");
    private final JScrollPane scroll = SwingComponents.newJScrollPane(table);

    RelationshipTable(final List<List<String>> rows) {
      super(new BorderLayout(0, Theme.GAP_S));
      setAlignmentX(Component.LEFT_ALIGNMENT);
      Theme.setCardStyle(this);
      setBorder(BorderFactory.createCompoundBorder(
          BorderFactory.createEmptyBorder(0, 0, Theme.GAP_S, 0),
          BorderFactory.createEmptyBorder(Theme.GAP_S, Theme.GAP_M, Theme.GAP_M, Theme.GAP_M)));
      rows.forEach(this::addRow);
      final List<String> nations = MapXml.nations(game);
      final List<String> types = RulesXml.relationshipTypes(game);
      editWith(0, nations, Function.identity());
      editWith(1, nations, Function.identity());
      editWith(2, Stream.concat(spec.pieces().get(2).words().stream(), types.stream()).toList(),
          TriggerXml::plain);
      editWith(3, types, Function.identity());
      table.putClientProperty("terminateEditOnFocusLost", true);
      table.getAccessibleContext().setAccessibleName("Relationship changes");
      final DefaultTableCellRenderer remove = new DefaultTableCellRenderer();
      remove.setHorizontalAlignment(JLabel.CENTER);
      remove.setForeground(Theme.getSecondaryTextColor());
      remove.setToolTipText("Removes the row (Delete)");
      table.getColumnModel().getColumn(REMOVE).setCellRenderer(remove);
      table.getColumnModel().getColumn(REMOVE).setMaxWidth(32);
      table.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(final MouseEvent e) {
          final int row = table.rowAtPoint(e.getPoint());
          if (row >= 0 && table.columnAtPoint(e.getPoint()) == REMOVE) {
            model.removeRow(row);
          }
        }
      });
      table.getInputMap(JComponent.WHEN_FOCUSED)
          .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeRows");
      table.getActionMap().put("removeRows", new AbstractAction() {
        @Serial private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(final ActionEvent e) {
          final int[] chosen = table.getSelectedRows();
          for (int i = chosen.length - 1; i >= 0 && !table.isEditing(); i--) {
            model.removeRow(chosen[i]);
          }
        }
      });
      model.addTableModelListener(e -> {
        showSize();
        if (model.getRowCount() == 0) {
          Optional.ofNullable(getParent()).ifPresent(p -> {
            p.remove(this);
            p.revalidate();
          });
        }
        changed();
      });
      final JPanel head = new JPanel(new BorderLayout(Theme.GAP_S, 0));
      head.setOpaque(false);
      head.add(heading, BorderLayout.CENTER);
      head.add(Theme.newAddButton("Add row", "Adds a relationship change", this::addNewRow),
          BorderLayout.EAST);
      add(head, BorderLayout.NORTH);
      add(scroll, BorderLayout.CENTER);
      showSize();
    }

    /** A combo per cell, showing the items in words; a value the list lacks is added, not lost. */
    private void editWith(final int column, final List<String> items,
        final Function<String, String> words) {
      final JComboBox<String> box = new JComboBox<>(items.toArray(String[]::new));
      showAs(box, words);
      table.getColumnModel().getColumn(column).setCellEditor(new DefaultCellEditor(box) {
        @Serial private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellEditorComponent(final JTable owner, final Object value,
            final boolean selected, final int row, final int col) {
          if (((DefaultComboBoxModel<String>) box.getModel()).getIndexOf(value) < 0) {
            box.addItem(Objects.toString(value, ""));
          }
          return super.getTableCellEditorComponent(owner, value, selected, row, col);
        }
      });
      table.getColumnModel().getColumn(column).setCellRenderer(new DefaultTableCellRenderer() {
        @Serial private static final long serialVersionUID = 1L;

        @Override
        protected void setValue(final Object value) {
          super.setValue(value == null ? "" : words.apply(value.toString()));
        }
      });
    }

    private void addRow(final List<String> pieces) {
      model.addRow(Stream.concat(pieces.stream(), Stream.of("×")).toArray());
    }

    /** The trigger's nation and another become the war type, from anything. */
    void addNewRow() {
      final String own = text(nation);
      final String other = MapXml.nations(game).stream().filter(n -> !n.equals(own)).findFirst()
          .orElse("");
      addRow(List.of(own, other, "any", TriggerXml.warType(game)));
    }

    private void showSize() {
      final int rows = model.getRowCount();
      heading.setText("Change relationships (" + rows + ")");
      table.setPreferredScrollableViewportSize(new Dimension(TEXT_WIDTH,
          table.getRowHeight() * Math.max(1, Math.min(rows, SHOWN_ROWS))));
      // up to 8 rows the dialog scrolls, not the table
      scroll.setWheelScrollingEnabled(rows > SHOWN_ROWS);
      revalidate();
    }

    @Override
    public List<Option> options() {
      return IntStream.range(0, model.getRowCount())
          .mapToObj(r -> TriggerXml.option(RELATIONSHIP_CHANGE, TriggerXml.join(spec,
              IntStream.range(0, REMOVE)
                  .mapToObj(c -> Objects.toString(model.getValueAt(r, c), "").strip())
                  .toList())))
          .toList();
    }

    @Override
    public String moment() {
      return spec.moment();
    }

    @Override
    public List<Entry> messages() {
      return List.of();
    }

    @Override
    public void stopEditing() {
      if (table.isEditing()) {
        table.getCellEditor().stopCellEditing();
      }
    }
  }

  /** A condition of the trigger: one of the map's, or a new one, with its checks. */
  private final class ConditionBox extends JPanel {
    @Serial private static final long serialVersionUID = 1L;
    private final Optional<Attachment> existing;
    private final String name;
    private final JComboBox<String> owner;
    private final ListField forNations;
    private final JCheckBox inverted = new JCheckBox("Holds when its checks are not met");
    private final JPanel checks = column();

    /**
     * @param existing the map's condition, empty for a new one
     * @param shown what the box shows: the map's condition, or the new one's name, nation and
     *     checks
     */
    ConditionBox(final Optional<Attachment> existing, final Attachment shown) {
      super(new BorderLayout(0, 6));
      this.existing = existing;
      this.name = Objects.toString(shown.getName(), "");
      setAlignmentX(Component.LEFT_ALIGNMENT);
      setBorder(BorderFactory.createCompoundBorder(
          BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0),
              BorderFactory.createLineBorder(Theme.getSeparatorColor())),
          BorderFactory.createEmptyBorder(8, 10, 8, 10)));
      owner = watched(chooser(MapXml.nations(game),
          Objects.toString(shown.getAttachTo(), text(nation))));
      owner.setEditable(false);
      // a condition the map has stays where it is, so other triggers still find it
      owner.setEnabled(existing.isEmpty());
      forNations = new ListField("Nations", MapXml.nations(game),
          TriggerXml.value(shown, "players").orElse(""));
      inverted.setSelected(isTrue(shown, "invert"));
      inverted.addActionListener(e -> changed());
      checks.setOpaque(false);
      AttachmentsXml.options(shown).forEach(o ->
          TriggerXml.spec(o.getName()).filter(s -> s.group() == Group.CHECK)
              .ifPresent(s -> checks.add(new OptionRow(TriggerXml.specs(Group.CHECK), s,
                  TriggerXml.joined(o)))));
      final JPanel head = new JPanel(new BorderLayout(6, 0));
      head.setOpaque(false);
      head.add(Theme.newSecondaryLabel(existing.isPresent() ? name : name + " (new)"),
          BorderLayout.CENTER);
      head.add(removeButton("condition", this), BorderLayout.EAST);
      final JPanel body = grid();
      addRow(body, "Of nation", owner);
      if (existing.isPresent()) {
        addRow(body, "", Theme.newSecondaryLabel("<html><div style='width:"
            + Theme.htmlWidth(TEXT_WIDTH - 220) + "'>Conditions the map has keep their nation,"
            + " so the triggers that name them still find them.</div></html>"));
      }
      addRow(body, "For nations (optional)", forNations);
      addRow(body, "", inverted);
      final JPanel center = new JPanel(new BorderLayout(0, 4));
      center.setOpaque(false);
      center.add(body, BorderLayout.NORTH);
      center.add(checks, BorderLayout.CENTER);
      center.add(row(Theme.newAddButton("Add check", "Adds something the condition checks",
          () -> {
            addCheck();
            changed();
          })), BorderLayout.SOUTH);
      add(head, BorderLayout.NORTH);
      add(center, BorderLayout.CENTER);
      // who else uses it, worked out once, as a change here changes them too
      final List<String> others = existing.isEmpty() ? List.of()
          : TriggerXml.otherUsers(game, name, Set.of(original.name()));
      if (!others.isEmpty()) {
        final JLabel shared = Theme.newSecondaryLabel("Shared with " + others.size()
            + (others.size() == 1 ? " other trigger" : " other triggers")
            + ": a change here changes them too.");
        shared.setToolTipText("<html><div style='width:" + Theme.htmlWidth(300) + "'>"
            + SearchList.escape(String.join(", ", others)) + "</div></html>");
        add(shared, BorderLayout.SOUTH);
      }
    }

    void addCheck() {
      checks.add(new OptionRow(TriggerXml.specs(Group.CHECK),
          TriggerXml.specs(Group.CHECK).getFirst(), ""));
      checks.revalidate();
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
          .flatMap(c -> ((OptionRow) c).options().stream())
          .forEach(wanted::add);
      return TriggerXml.withOptions(before, wanted);
    }
  }

  // ---- the card

  /**
   * What a trigger does as a card: If, Then, When and Often in rows, the effects cut after
   * maxEffects with "and n more", which shows the rest.
   */
  static JComponent clauseCard(final Clauses clauses, final int maxEffects) {
    final JPanel card = new JPanel(new GridBagLayout()) {
      @Serial private static final long serialVersionUID = 1L;

      @Override
      public Dimension getMaximumSize() {
        return new Dimension(680, super.getMaximumSize().height);
      }
    };
    Theme.setCardStyle(card);
    card.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, Theme.GAP_L, Theme.GAP_M,
        Theme.GAP_L));
    if (!clauses.conditions().isEmpty()) {
      // "if ..." or "unless ...": the first word is the row's label
      final String[] conditions = clauses.conditions().split(" ", 2);
      addClause(card, RulesXml.upperFirst(conditions[0]),
          wrapping(conditions.length > 1 ? conditions[1] : ""));
    }
    final List<String> effects = clauses.effects();
    final JTextArea then = wrapping(effects.isEmpty() ? "nothing yet"
        : String.join(", ", effects.subList(0, Math.min(maxEffects, effects.size()))));
    if (effects.size() > maxEffects) {
      final JButton more = new JButton("and " + (effects.size() - maxEffects) + " more");
      Theme.setTertiaryButtonStyle(more);
      more.setHorizontalAlignment(JButton.LEADING);
      more.addActionListener(e -> {
        then.setText(String.join(", ", effects));
        more.setVisible(false);
        card.revalidate();
      });
      final JPanel body = new JPanel(new BorderLayout());
      body.setOpaque(false);
      body.add(then, BorderLayout.CENTER);
      body.add(more, BorderLayout.SOUTH);
      addClause(card, "Then", body);
    } else {
      addClause(card, "Then", then);
    }
    if (!clauses.when().isEmpty()) {
      addClause(card, "When", wrapping(clauses.when()));
    }
    addClause(card, "Often", wrapping(clauses.often()));
    return card;
  }

  private static JTextArea wrapping(final String text) {
    final JTextArea area = new JTextArea(text);
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    // narrow by itself, so it wraps at the card's width instead of widening the card
    area.setColumns(20);
    area.setFont(new JLabel().getFont());
    area.setBorder(BorderFactory.createEmptyBorder());
    return area;
  }

  private static void addClause(final JPanel card, final String label, final JComponent body) {
    final JLabel name = Theme.newSectionLabel(label);
    name.setForeground(Theme.getSecondaryTextColor());
    final GridBagConstraints at = new GridBagConstraints();
    at.gridy = card.getComponentCount() / 2;
    at.anchor = GridBagConstraints.NORTHWEST;
    at.insets = new Insets(0, 0, Theme.GAP_S, Theme.GAP_M);
    card.add(name, at);
    at.gridx = 1;
    at.weightx = 1;
    at.fill = GridBagConstraints.HORIZONTAL;
    at.insets = new Insets(0, 0, Theme.GAP_S, 0);
    card.add(body, at);
  }
}
