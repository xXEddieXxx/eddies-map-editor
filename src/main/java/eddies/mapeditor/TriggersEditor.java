package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import lombok.experimental.ExtensionMethod;
import lombok.extern.slf4j.Slf4j;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.Theme.Status;
import eddies.mapeditor.TriggerXml.Problems;
import eddies.mapeditor.TriggerXml.Trigger;

/**
 * The game's triggers, each with what it does in plain words; the trigger assistant makes new ones
 * and changes them. The list is worked out in the background while the tool shows, as big maps
 * have tens of thousands of triggers.
 */
@Slf4j
@ExtensionMethod(Builders.class)
final class TriggersEditor implements Editor {
  private static final String SEPARATOR = " · ";
  private static final String EMPTY = "empty";
  private static final String READING = "reading";
  private static final String LIST = "list";
  private static final int MAX_SHARING = 5;
  private static final int CARD_WIDTH = 680;
  // shortcut: about what a list row's second line fits at its usual width; SearchList cuts by
  // width in the middle, a cut here keeps the start readable
  private static final int SUBTITLE_LENGTH = 52;
  // the words TriggerXml.clauses starts the conditions with, eg "unless none of"
  private static final Pattern COMBINING =
      Pattern.compile("(if|unless)( none of| exactly \\S+ of| from \\S+ to \\S+ of)?");

  private final MapProject project;
  private final JPanel page = new JPanel(new CardLayout());
  private final JPanel emptyCard = new JPanel(new BorderLayout());
  private final JLabel reading = new JLabel();
  private final JComboBox<String> nationFilter = new JComboBox<>();
  private final SearchList list;
  private final JPanel details = new JPanel(new CardLayout());
  private final Form inspector = new Form(560);
  private @Nullable String selected;
  private Listing listing = new Listing(Map.of(), Map.of(), Map.of());
  private @Nullable Game listed;
  private @Nullable Game loading;
  // a trigger to choose once it is listed, eg a copy just made
  private Optional<Attachment> wanted = Optional.empty();
  private boolean filling;

  /**
   * The triggers by their key in the list, what each does without its nation before it, and how
   * many each nation has, in the game's order of nations.
   */
  record Listing(
      Map<String, Trigger> triggers, Map<String, String> details, Map<String, Integer> nations) {}

  TriggersEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
                "trigger",
                "triggers",
                this::shownKeys,
                key -> title(listing.triggers().get(key).name()),
                key -> listing.details().getOrDefault(key, ""),
                this::choose)
            .withAdd("New...", "Make a trigger step by step", this::openNew);
    nationFilter.setRenderer(new NationRenderer());
    nationFilter.addActionListener(e -> {
      if (!filling) {
        list.refresh();
        if (!shownKeys().contains(selected)) {
          choose(null);
        }
      }
    });
    final JPanel listColumn = new JPanel(new BorderLayout(0, Theme.GAP_S));
    listColumn.add(nationFilter, BorderLayout.NORTH);
    listColumn.add(list, BorderLayout.CENTER);
    page.add(emptyCard, EMPTY);
    page.add(newReadingCard(), READING);
    details.add(Panels.empty(Glyph.TRIGGERS, "Choose a trigger",
        "Choose a trigger to see what it does."), EMPTY);
    details.add(SwingComponents.newJScrollPane(inspector), LIST);
    page.add(Panels.sideBySide(listColumn, details), LIST);
    project.onChange(this::refresh);
    showCard();
    showInspector();
  }

  private JComponent newReadingCard() {
    final JProgressBar bar = new JProgressBar();
    bar.setIndeterminate(true);
    final JPanel lines = new JPanel();
    lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
    for (final JComponent part : List.of(bar, (JComponent) Box.createVerticalStrut(Theme.GAP_S),
        reading)) {
      part.setAlignmentX(Component.CENTER_ALIGNMENT);
      lines.add(part);
    }
    final JPanel centred = new JPanel(new GridBagLayout());
    centred.add(lines);
    return centred;
  }

  @Override
  public String title() {
    return "Triggers";
  }

  @Override
  public Glyph glyph() {
    return Glyph.TRIGGERS;
  }

  @Override
  public String hint() {
    return "The game's triggers in plain words: what each does, when and how often. Change one or"
        + " make a new one step by step in the trigger assistant.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    // called before the tool's card shows
    SwingUtilities.invokeLater(this::refresh);
  }

  /** "Germans 4 Iron Ore" for triggerAttachment_Germans_4_Iron_Ore. */
  static String title(final String name) {
    return TriggerXml.shortName(name).replace('_', ' ');
  }

  private String nation() {
    return Optional.ofNullable((String) nationFilter.getSelectedItem()).orElse("");
  }

  private List<String> shownKeys() {
    final String nation = nation();
    return listing.triggers().entrySet().stream()
        .filter(e -> nation.isEmpty() || nation.equals(e.getValue().nation()))
        .map(Map.Entry::getKey)
        .toList();
  }

  private void openNew() {
    TriggerAssistant.openNew(page, project, nation());
  }

  /** Lists the game's triggers again, in the background, only while the tool shows. */
  private void refresh() {
    final Game game = project.game();
    if (!page.isShowing() || game == listed || game == loading) {
      return;
    }
    loading = game;
    new SwingWorker<Listing, Void>() {
      @Override
      protected Listing doInBackground() {
        return listing(game);
      }

      @Override
      protected void done() {
        if (loading == game) {
          loading = null;
        }
        // a change meanwhile lists again; this one is stale
        if (project.game() != game) {
          refresh();
          return;
        }
        try {
          listing = get();
          listed = game;
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (final ExecutionException e) {
          log.warn("Could not list the triggers", e);
          return;
        }
        fillNations();
        list.refresh();
        wanted.ifPresent(w -> {
          wanted = Optional.empty();
          listing.triggers().entrySet().stream()
              .filter(e -> e.getValue().name().equals(w.getName())
                  && e.getValue().nation().equals(w.getAttachTo()))
              .findFirst()
              .ifPresent(e -> reveal(e.getKey()));
        });
        showCard();
        showInspector();
      }
    }.execute();
  }

  /**
   * The triggers by a key: the short name, the nation added only where two share it. Of two with
   * the same name on a nation the game keeps the last, so does the list.
   */
  static Listing listing(final Game game) {
    final Map<String, Trigger> byName = new LinkedHashMap<>();
    for (final Trigger trigger : TriggerXml.triggers(game)) {
      byName.put(trigger.name() + SEPARATOR + trigger.nation(), trigger);
    }
    final Map<String, Trigger> byKey = new LinkedHashMap<>();
    final Map<String, String> details = new HashMap<>();
    final Map<String, Integer> counts = new HashMap<>();
    for (final Trigger trigger : byName.values()) {
      // the full name and nation, which name and nation make unique, when shorter ones are taken
      final String key = Stream.of(TriggerXml.shortName(trigger.name()),
              TriggerXml.shortName(trigger.name()) + SEPARATOR + trigger.nation(),
              trigger.name() + SEPARATOR + trigger.nation())
          .filter(k -> !byKey.containsKey(k))
          .findFirst()
          .orElse(trigger.name() + SEPARATOR + trigger.nation());
      byKey.put(key, trigger);
      details.put(key, subtitle(game, trigger));
      counts.merge(trigger.nation(), 1, Integer::sum);
    }
    final Map<String, Integer> nations = new LinkedHashMap<>();
    Stream.concat(MapXml.nations(game).stream(), counts.keySet().stream().sorted())
        .filter(counts::containsKey)
        .forEach(n -> nations.putIfAbsent(n, counts.get(n)));
    return new Listing(byKey, details, nations);
  }

  /**
   * What the trigger does first, then the names of its conditions, eg "Give 5 PUs · if Germans 4
   * Control Norway": short, for a list row; the inspector tells it whole.
   */
  static String subtitle(final Game game, final Trigger trigger) {
    final TriggerXml.Clauses clauses = TriggerXml.clauses(game, trigger);
    final List<String> effects = clauses.effects();
    final String does = effects.isEmpty() ? "nothing"
        : effects.getFirst() + (effects.size() > 1 ? " and " + (effects.size() - 1) + " more" : "");
    final List<String> conditions = trigger.conditionNames().stream().distinct()
        .map(TriggersEditor::conditionTitle).toList();
    // how the conditions combine: "if", "unless", or up to the colon, eg "if none of"
    final String sentence = clauses.conditions();
    final Matcher combining = COMBINING.matcher(sentence);
    final String lead = combining.lookingAt() ? combining.group() : "if";
    final String text = RulesXml.upperFirst(does) + (conditions.isEmpty() ? ""
        : SEPARATOR + lead + " " + String.join(", ", conditions));
    return text.length() <= SUBTITLE_LENGTH ? text
        : text.substring(0, SUBTITLE_LENGTH - 1).strip() + "…";
  }

  /** "Germans 4 Iron Ore" for conditionAttachment_Germans_4_Iron_Ore. */
  static String conditionTitle(final String name) {
    final String shortened = name.replaceFirst("^(?i)[a-z]*attach?ment_?", "").replace('_', ' ')
        .strip();
    return shortened.isEmpty() ? name : shortened;
  }

  private void fillNations() {
    final String chosen = nation();
    final List<String> items = new ArrayList<>(List.of(""));
    items.addAll(listing.nations().keySet());
    filling = true;
    nationFilter.setModel(new DefaultComboBoxModel<>(items.toArray(String[]::new)));
    nationFilter.setSelectedItem(items.contains(chosen) ? chosen : "");
    filling = false;
  }

  private void showCard() {
    final String card;
    final long count = listed == null ? triggerCount(project.game()) : 0;
    // a game without triggers needs no reading screen
    if (count > 0) {
      reading.setText("Reading " + String.format("%,d", count)
          + " triggers...");
      card = READING;
    } else if (listed == null || listing.triggers().isEmpty()) {
      emptyCard.removeAll();
      emptyCard.add(Panels.empty(Glyph.TRIGGERS, "No triggers yet",
          "A trigger changes the game when something happens, eg gives PUs when a nation holds a"
              + " territory."
              + (TriggerXml.usesTriggers(project.game()) ? ""
                  : " Triggers also need the game option Use triggers; the assistant turns it"
                      + " on."),
          "New trigger...", this::openNew), BorderLayout.CENTER);
      card = EMPTY;
    } else {
      card = LIST;
    }
    ((CardLayout) page.getLayout()).show(page, card);
  }

  private static long triggerCount(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> TriggerXml.TRIGGER.equals(AttachmentsXml.kind(a)))
        .count();
  }

  /** The trigger of the same name and nation in the game as it is now, the last as the game. */
  private Optional<Trigger> current(final Trigger listedTrigger) {
    final List<Attachment> attachments = AttachmentsXml.attachments(project.game());
    for (int i = attachments.size() - 1; i >= 0; i--) {
      final Attachment a = attachments.get(i);
      if (TriggerXml.TRIGGER.equals(AttachmentsXml.kind(a))
          && listedTrigger.name().equals(a.getName())
          && listedTrigger.nation().equals(a.getAttachTo())) {
        return Optional.of(TriggerXml.trigger(project.game(), i));
      }
    }
    return Optional.empty();
  }

  private void choose(@Nullable final String key) {
    selected = key;
    showInspector();
  }

  /** Chooses the trigger in the list too, showing all nations if the filter hides it. */
  private void reveal(final String key) {
    if (!shownKeys().contains(key)) {
      nationFilter.setSelectedItem("");
    }
    list.choose(key);
    choose(key);
  }

  private void showInspector() {
    inspector.clear();
    final Optional<Trigger> chosen = Optional.ofNullable(selected).map(listing.triggers()::get);
    ((CardLayout) details.getLayout()).show(details, chosen.isEmpty() ? EMPTY : LIST);
    if (chosen.isEmpty()) {
      selected = null;
      return;
    }
    final Trigger trigger = chosen.get();
    final boolean upToDate = listed == project.game();
    final JButton change = new JButton("Change...");
    Theme.setPrimaryButtonStyle(change);
    change.setToolTipText("Opens the trigger in the assistant");
    // looked up again, as the list may be older than the game
    change.addActionListener(e -> current(trigger)
        .ifPresent(t -> TriggerAssistant.open(page, project, t.index())));
    final JButton duplicate = new JButton("Duplicate");
    Theme.setSecondaryButtonStyle(duplicate);
    duplicate.setToolTipText("Makes a copy that uses the same conditions");
    duplicate.addActionListener(e -> duplicate(trigger));
    change.setEnabled(upToDate);
    duplicate.setEnabled(upToDate);
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
    buttons.setOpaque(false);
    buttons.add(duplicate);
    buttons.add(change);
    final JPanel header = new JPanel(new BorderLayout(Theme.GAP_M, 0));
    header.setOpaque(false);
    header.add(Theme.newTitle(title(trigger.name())), BorderLayout.CENTER);
    header.add(buttons, BorderLayout.EAST);
    inspector
        .row(header)
        .subtitle(trigger.nation() + SEPARATOR + trigger.name())
        .row(capped(TriggerAssistant.clauseCard(TriggerXml.clauses(project.game(), trigger), 5)));
    final Problems problems = upToDate
        ? TriggerXml.problems(project.game(), project.texts(), trigger)
        : new Problems(List.of(), List.of());
    final int count = problems.errors().size() + problems.warnings().size();
    if (count > 0) {
      final JLabel status = Theme.newStatusLabel(
          problems.errors().isEmpty() ? Status.WARNING : Status.PROBLEM,
          count == 1 ? "1 thing to check" : count + " things to check");
      status.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_L, 0, 0, 0));
      inspector.row(status);
      Stream.concat(problems.errors().stream(), problems.warnings().stream())
          .forEach(p -> inspector.note(SearchList.escape(p)));
    }
    final List<String> sharing = sharingConditions(trigger);
    sharing.stream().limit(MAX_SHARING).forEach(key -> inspector.row(sharingRow(key)));
    if (sharing.size() > MAX_SHARING) {
      inspector.note("and " + (sharing.size() - MAX_SHARING) + " more");
    }
    inspector.remove("Remove trigger", () -> remove(trigger)).end();
  }

  /** The card as wide as the room up to 680 px, at the left. */
  private static JComponent capped(final JComponent card) {
    // the card's text wraps, so it asks for no width of its own
    final JPanel width = new JPanel(new BorderLayout()) {
      @Serial private static final long serialVersionUID = 1L;

      @Override
      public Dimension getPreferredSize() {
        return new Dimension(CARD_WIDTH, super.getPreferredSize().height);
      }

      @Override
      public Dimension getMinimumSize() {
        return new Dimension(0, super.getPreferredSize().height);
      }

      @Override
      public Dimension getMaximumSize() {
        return getPreferredSize();
      }
    };
    width.setOpaque(false);
    width.add(card, BorderLayout.CENTER);
    final JPanel row = new JPanel();
    row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
    row.setOpaque(false);
    row.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_S, 0, 0, 0));
    row.add(width);
    row.add(Box.createHorizontalGlue());
    return row;
  }

  /** The keys of the other listed triggers that name one of its conditions. */
  private List<String> sharingConditions(final Trigger trigger) {
    final Set<String> names = Set.copyOf(trigger.conditionNames());
    if (names.isEmpty()) {
      return List.of();
    }
    return listing.triggers().entrySet().stream()
        .filter(e -> e.getValue() != trigger
            && e.getValue().conditionNames().stream().anyMatch(names::contains))
        .map(Map.Entry::getKey)
        .toList();
  }

  private JComponent sharingRow(final String key) {
    final JButton show = new JButton("Show");
    Theme.setTertiaryButtonStyle(show);
    show.setToolTipText("Chooses that trigger in the list");
    show.addActionListener(e -> reveal(key));
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    row.setOpaque(false);
    row.add(new JLabel("Shares its condition with: "
        + title(listing.triggers().get(key).name())));
    row.add(show);
    return row;
  }

  private void duplicate(final Trigger listedTrigger) {
    current(listedTrigger).ifPresent(trigger -> {
      final Game copied = withDuplicate(project.game(), trigger);
      wanted = Optional.of(AttachmentsXml.attachments(copied).getLast());
      project.edit(g -> copied);
    });
  }

  /** The game with a copy of the trigger last, of the same nation and with the same conditions. */
  static Game withDuplicate(final Game game, final Trigger trigger) {
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
    attachments.add(trigger.attachment().toBuilder()
        .name(TriggerXml.newName(game, TriggerXml.TRIGGER,
            "_" + TriggerXml.shortName(trigger.name()) + "_copy", Set.of()))
        .build());
    return AttachmentsXml.withAttachments(game, attachments);
  }

  private void remove(final Trigger listedTrigger) {
    final Optional<Trigger> found = current(listedTrigger);
    if (found.isEmpty()) {
      return;
    }
    final Trigger trigger = found.get();
    final String title = title(trigger.name());
    final Optional<Game> without = withoutTrigger(project.game(), trigger);
    if (without.isEmpty()) {
      Dialogs.warn(page, "Remove trigger", title + " stays.\nThese still name it, so the game"
          + " would not load without it: "
          + String.join(", ", RulesXml.whereNamed(project.game(), trigger.name())));
      return;
    }
    if (Dialogs.confirmRemove(page, "Remove trigger", "Remove trigger “" + title
        + "”?\nIts conditions stay, as other triggers may use them.", List.of(), "Remove")) {
      selected = null;
      project.edit(g -> without.get());
    }
  }

  /** The game without the trigger; empty while others name it, as the game would not load. */
  static Optional<Game> withoutTrigger(final Game game, final Trigger trigger) {
    return RulesXml.whereNamed(game, trigger.name()).isEmpty()
        ? Optional.of(AttachmentsXml.withoutAttachment(game, trigger.index()))
        : Optional.empty();
  }

  /** "All nations (n)", else "Germans (n)". */
  private final class NationRenderer extends DefaultListCellRenderer {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public Component getListCellRendererComponent(
        final JList<?> owner,
        final Object value,
        final int index,
        final boolean isSelected,
        final boolean cellHasFocus) {
      final String nation = Optional.ofNullable((String) value).orElse("");
      final String text = nation.isEmpty()
          ? "All nations (" + listing.triggers().size() + ")"
          : nation + " (" + listing.nations().getOrDefault(nation, 0) + ")";
      return super.getListCellRendererComponent(owner, text, index, isSelected, cellHasFocus);
    }
  }
}
