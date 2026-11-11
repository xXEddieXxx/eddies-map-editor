package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import lombok.extern.slf4j.Slf4j;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.TriggerXml.Problems;
import eddies.mapeditor.TriggerXml.Trigger;

/**
 * The game's triggers, each with what it does in plain words; the trigger assistant makes new ones
 * and changes them. The list is worked out in the background while the tool shows, as big maps
 * have tens of thousands of triggers.
 */
@Slf4j
final class TriggersEditor implements Editor {
  private static final String SEPARATOR = " · ";

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 8));
  private final SearchList list;
  private final Form inspector = new Form(360);
  private @Nullable String selected;
  private Listing listing = new Listing(Map.of(), Map.of());
  private @Nullable Game listed;
  private @Nullable Game loading;

  /** The triggers by their label in the list, and what each does. */
  private record Listing(Map<String, Trigger> triggers, Map<String, String> sentences) {}

  TriggersEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search trigger",
            () -> List.copyOf(listing.triggers().keySet()),
            label -> listing.sentences().getOrDefault(label, ""),
            this::choose);
    final JButton add = new JButton("New trigger...");
    add.setToolTipText("Makes a trigger step by step");
    add.addActionListener(e -> TriggerAssistant.openNew(page, project));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(add);
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(buttons, BorderLayout.SOUTH);
    page.add(
        Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(inspector)),
        BorderLayout.CENTER);
    project.onChange(this::refresh);
    showInspector();
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
    return "The game's triggers in plain words: what each does, when and how often. The"
        + " assistant makes new ones and changes them step by step.";
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
        list.refresh();
        showInspector();
      }
    }.execute();
  }

  /**
   * The triggers by a short label: the name without "triggerAttachment", the nation added only
   * where two share it. Of two with the same name on a nation the game keeps the last, so does the
   * list.
   */
  private static Listing listing(final Game game) {
    final Map<String, Trigger> byKey = new LinkedHashMap<>();
    for (final Trigger trigger : TriggerXml.triggers(game)) {
      byKey.put(trigger.name() + SEPARATOR + trigger.nation(), trigger);
    }
    final Map<String, Trigger> byLabel = new LinkedHashMap<>();
    final Map<String, String> sentences = new HashMap<>();
    for (final Trigger trigger : byKey.values()) {
      // the full name and nation, which name and nation make unique, when shorter ones are taken
      final String label = Stream.of(shortName(trigger.name()),
              shortName(trigger.name()) + SEPARATOR + trigger.nation(),
              trigger.name() + SEPARATOR + trigger.nation())
          .filter(l -> !byLabel.containsKey(l))
          .findFirst()
          .orElse(trigger.name() + SEPARATOR + trigger.nation());
      byLabel.put(label, trigger);
      sentences.put(label, TriggerXml.sentence(game, trigger));
    }
    return new Listing(byLabel, sentences);
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

  private static String shortName(final String name) {
    final String shortened =
        name.replaceFirst("^(?i)trigger(Attachment|Attatchment)_?", "").strip();
    return shortened.isEmpty() ? name : shortened;
  }

  private void choose(final String label) {
    selected = label;
    showInspector();
  }

  private void showInspector() {
    inspector.clear();
    final Optional<Trigger> chosen =
        Optional.ofNullable(selected).map(listing.triggers()::get);
    if (chosen.isEmpty()) {
      selected = null;
      inspector
          .title("Triggers")
          .note(listed == null ? "Reading the triggers..."
              : listing.triggers().size() + " triggers. Choose one to see what it does, or make"
                  + " a new one.")
          .end();
      return;
    }
    final Trigger trigger = chosen.get();
    final Attachment attachment = trigger.attachment();
    final JButton change = new JButton("Change...");
    change.setToolTipText("Opens the trigger in the assistant");
    // looked up again, as the list may be older than the game
    change.addActionListener(e -> current(trigger)
        .ifPresent(t -> TriggerAssistant.open(page, project, t.index())));
    change.setEnabled(listed == project.game());
    final JPanel changeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    changeRow.add(change);
    inspector
        .title(selected)
        .note(SearchList.escape(trigger.name() + " of " + trigger.nation()))
        .row(changeRow)
        .section("What it does")
        .note(SearchList.escape(listing.sentences().getOrDefault(selected, "")));
    final List<String> names = trigger.conditionNames();
    if (!names.isEmpty()) {
      inspector.section("Conditions");
      for (final String name : names) {
        final String says = trigger.conditions().stream()
            .filter(c -> name.equals(c.getName()))
            .findFirst()
            .map(TriggerXml::conditionSentence)
            .orElse("not in the game file, eg made by a foreach");
        inspector.note("<b>" + SearchList.escape(name) + "</b>: " + SearchList.escape(says));
      }
    }
    final String when = TriggerXml.when(attachment);
    inspector.section("When and how often")
        .note(SearchList.escape((when.isEmpty() ? "" : when + ". ") + TriggerXml.often(attachment)));
    final Problems problems = listed == project.game()
        ? TriggerXml.problems(project.game(), project.texts(), trigger)
        : new Problems(List.of(), List.of());
    if (!problems.errors().isEmpty() || !problems.warnings().isEmpty()) {
      inspector.section("Problems");
      problems.errors().forEach(p -> inspector.note("Must fix: " + SearchList.escape(p)));
      problems.warnings().forEach(p -> inspector.note("Check: " + SearchList.escape(p)));
    }
    inspector.end();
  }
}
