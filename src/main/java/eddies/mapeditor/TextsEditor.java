package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapTexts.Entry;
import eddies.mapeditor.MapTexts.Kind;

/**
 * The texts the game reads by key beside the game file: objectives, the messages of triggers, unit
 * tool tips and the texts of actions. A tab per file, its keys in plain words, the chosen one's
 * text to change with a preview where it is HTML. Typing becomes one step when it pauses or the
 * text is left.
 */
final class TextsEditor implements Editor {
  private static final int PAUSE_MILLIS = 1000;
  private static final int PREVIEW_HEIGHT = 160;
  private static final int NOTE_WIDTH = 400;
  private static final String UNREADABLE = "unreadable";
  private static final String GROUP = "TABLEGROUP.";
  // sounds have a tool of their own
  static final List<Kind> KINDS = Stream.of(Kind.values()).filter(k -> k != Kind.SOUNDS).toList();

  private final MapProject project;
  private final Consumer<String> openTool;
  private final JPanel page = new JPanel(new BorderLayout(0, Theme.GAP_S));
  private final JTabbedPane tabs = new JTabbedPane();
  private final CardLayout cards = new CardLayout();
  private final JPanel listCards = new JPanel(cards);
  private final Map<Kind, SearchList> lists = new EnumMap<>(Kind.class);
  private final JPanel banner = new JPanel();
  private final JLabel bannerText = Theme.newSecondaryLabel("");
  // narrower than the design's 680: a row wider than the column shrinks every field to its least
  private final Form inspector = new Form(NOTE_WIDTH);
  private final XmlTextArea text = new XmlTextArea();
  private final GameNotesView preview = new GameNotesView();
  private final Timer pause = new Timer(PAUSE_MILLIS, e -> commit());
  private @Nullable String selected;
  // the file the text shown belongs to, as the tab changes before the text is kept
  private Kind shownKind = Kind.OBJECTIVES;
  private boolean editingHere;
  private final Map<Kind, Map<String, String>> explained = new EnumMap<>(Kind.class);
  private @Nullable Game explainedFor;

  TextsEditor(final MapProject project) {
    this(project, tool -> {});
  }

  /**
   * @param openTool switches to the tool of this title, eg "Files"
   */
  TextsEditor(final MapProject project, final Consumer<String> openTool) {
    this.project = project;
    this.openTool = openTool;
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.colorAsXml();
    text.setRows(8);
    pause.setRepeats(false);
    OnTyping.attach(text, pause::restart);
    text.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit();
          }
        });
    tabs.putClientProperty("JTabbedPane.tabType", "underlined");
    for (final Kind kind : KINDS) {
      final JPanel strip = new JPanel();
      strip.setPreferredSize(new Dimension(0, 0));
      tabs.addTab(kind.getTitle(), null, strip, kind.getHelp());
      listCards.add(newColumn(kind), kind.name());
      listCards.add(
          Panels.empty(
              Glyph.TEXTS,
              kind.getFile() + " does not read",
              "Fix it in Files.",
              "Open in Files",
              () -> openTool.accept("Files")),
          kind.name() + UNREADABLE);
    }
    tabs.addChangeListener(
        e -> {
          commit();
          lists.get(shownKind).choose(null);
          selected = null;
          showList();
          showInspector();
        });
    page.add(tabs, BorderLayout.NORTH);
    page.add(
        Panels.sideBySide(listCards, SwingComponents.newJScrollPane(inspector)),
        BorderLayout.CENTER);
    project.onChange(
        () -> {
          lists.values().forEach(SearchList::refresh);
          showList();
          if (!editingHere) {
            showInspector();
          }
        });
    showList();
    showInspector();
  }

  /** The kind's list, objectives with the banner that tells when they are another game's. */
  private JComponent newColumn(final Kind kind) {
    final String noun = noun(kind);
    final String plural = noun + "s";
    final String add = addTitle(kind);
    final SearchList list =
        new SearchList(
                noun,
                plural,
                () -> keys(kind),
                key -> MapTexts.title(kind, key, project.game()),
                key -> detail(kind, key),
                this::choose)
            .stripSharedPrefix()
            .withAdd("Add text…", add, () -> addEntry(kind))
            .withEmpty(Panels.empty(Glyph.TEXTS, "No " + plural + " yet", kind.getHelp()));
    lists.put(kind, list);
    final JPanel column = new JPanel(new BorderLayout(0, Theme.GAP_S));
    column.add(list, BorderLayout.CENTER);
    if (kind == Kind.OBJECTIVES) {
      column.add(newBanner(), BorderLayout.NORTH);
    }
    return column;
  }

  private static String noun(final Kind kind) {
    return switch (kind) {
      case OBJECTIVES -> "objective text";
      case NOTIFICATIONS -> "message";
      case TOOLTIPS -> "tool tip";
      case POLITICS -> "political action text";
      case ACTIONS, SOUNDS -> "user action text";
    };
  }

  private static String addTitle(final Kind kind) {
    return switch (kind) {
      case OBJECTIVES -> "Add objective text";
      case NOTIFICATIONS -> "Add message";
      case TOOLTIPS -> "Add unit tool tip";
      case POLITICS, ACTIONS, SOUNDS -> "Add action text";
    };
  }

  private JComponent newBanner() {
    banner.setLayout(new BoxLayout(banner, BoxLayout.Y_AXIS));
    banner.setBackground(Theme.getWarningBackground());
    banner.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.getWarningColor()),
            BorderFactory.createEmptyBorder(
                Theme.GAP_M, Theme.GAP_M, Theme.GAP_M, Theme.GAP_M)));
    final JLabel title = Theme.newStatusLabel(Theme.Status.WARNING, "Not shown in this game");
    title.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
    final JButton use = new JButton("Use them for this game");
    Theme.setTertiaryButtonStyle(use);
    use.addActionListener(e -> useForThisGame());
    for (final JComponent part : List.of(title, bannerText, use)) {
      part.setAlignmentX(JComponent.LEFT_ALIGNMENT);
      banner.add(part);
    }
    return banner;
  }

  /** Renames the keys to this game's start, all in one step: the game then shows them. */
  private void useForThisGame() {
    commit();
    MapTexts.foreignPrefix(project.texts(), project.game())
        .ifPresent(
            old -> {
              final String own = MapTexts.objectivesPrefix(project.game());
              project.edit(g -> g, t -> t.withKeyPrefix(Kind.OBJECTIVES, old, own));
              Dialogs.toast(
                  page, "✓ The objective texts are now this game's", "Undo", project::undo);
            });
  }

  @Override
  public String title() {
    return "Texts";
  }

  @Override
  public Glyph glyph() {
    return Glyph.TEXTS;
  }

  @Override
  public String hint() {
    return "The texts the game shows by key: objectives, the messages of triggers, unit tool tips"
        + " and the texts of actions. Each is saved in its file beside the game.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  private Kind kind() {
    return KINDS.get(tabs.getSelectedIndex());
  }

  private List<String> keys(final Kind kind) {
    return project.texts().entries(kind).stream().map(Entry::key).toList();
  }

  /**
   * The line below a row's title: the key for objectives and tool tips, else what the key means,
   * kept per game as the list asks for every row it paints.
   */
  private String detail(final Kind kind, final String key) {
    if (explainedFor != project.game()) {
      explained.clear();
      explainedFor = project.game();
    }
    final String explanation =
        explained
            .computeIfAbsent(kind, k -> new HashMap<>())
            .computeIfAbsent(key, k -> MapTexts.explain(kind, k, project.game()));
    return switch (kind) {
      // a row for another game says so, unless the banner says it for all
      // without the game's name, as Objectives.Panel.Name keeps the list from finding it shared
      case OBJECTIVES ->
          MapTexts.isForeign(key, project.game()) && !banner.isVisible()
              ? explanation
              : key.substring(MapTexts.prefixOf(key).length());
      case TOOLTIPS -> key;
      default -> explanation;
    };
  }

  /** The kind's list, or that its file does not read; the banner while it has something to say. */
  private void showList() {
    final Kind kind = kind();
    cards.show(
        listCards,
        project.texts().unreadable().contains(kind) ? kind.name() + UNREADABLE : kind.name());
    final Optional<String> foreign = MapTexts.foreignPrefix(project.texts(), project.game());
    banner.setVisible(foreign.isPresent());
    foreign.ifPresent(
        old ->
            bannerText.setText(
                "<html><div style='width:"
                    + Theme.htmlWidth(220)
                    + "'>All "
                    + keys(Kind.OBJECTIVES).stream().filter(k -> k.startsWith(old)).count()
                    + " keys start with “"
                    + SearchList.escape(old)
                    + "”. This game reads “"
                    + SearchList.escape(MapTexts.objectivesPrefix(project.game()))
                    + "”</div></html>"));
    final String hex = Theme.convertColorToHex(Theme.getSecondaryTextColor());
    for (int i = 0; i < KINDS.size(); i++) {
      final Kind each = KINDS.get(i);
      final boolean unreadable = project.texts().unreadable().contains(each);
      tabs.setTitleAt(
          i,
          "<html>"
              + each.getTitle()
              + " <font color="
              + hex
              + ">"
              + (unreadable ? "!" : project.texts().entries(each).size())
              + "</font></html>");
    }
  }

  private void choose(final String key) {
    commit();
    selected = key;
    showInspector();
  }

  /** Offers the keys the game's conditions, triggers or units call for; any other may be typed. */
  private void addEntry(final Kind kind) {
    commit();
    final List<Entry> few = MapTexts.suggestions(kind, project.game(), project.texts());
    final List<Entry> all = MapTexts.allSuggestions(kind, project.game(), project.texts());
    final JComboBox<String> key = new JComboBox<>(keysOf(few));
    key.setEditable(true);
    final Form ask = new Form(360).field("Key", key);
    // a big map has thousands of conditions: those like objectives first, all on request
    if (all.size() > few.size()) {
      final JCheckBox showAll =
          new JCheckBox("Show all conditions and triggers (" + all.size() + ")");
      showAll.addActionListener(
          e -> key.setModel(new DefaultComboBoxModel<>(keysOf(showAll.isSelected() ? all : few))));
      ask.row(showAll);
    }
    final boolean ok = Dialogs.ask(page, addTitle(kind), ask.end(), "Add");
    final String chosen = String.valueOf(key.getEditor().getItem()).strip();
    if (!ok || chosen.isEmpty() || chosen.equals("null")) {
      return;
    }
    if (project.texts().value(kind, chosen).isEmpty()) {
      final String start =
          Stream.concat(few.stream(), all.stream())
              .filter(e -> e.key().equals(chosen))
              .map(Entry::value)
              .findFirst()
              .orElse("");
      project.setText(kind, chosen, start);
    }
    selected = chosen;
    lists.get(kind).choose(chosen);
    showInspector();
  }

  private static String[] keysOf(final List<Entry> entries) {
    return entries.stream().map(Entry::key).toArray(String[]::new);
  }

  private void showInspector() {
    inspector.clear();
    final Kind kind = kind();
    shownKind = kind;
    final Optional<String> value =
        Optional.ofNullable(selected).flatMap(key -> project.texts().value(kind, key));
    if (value.isEmpty()) {
      selected = null;
      // with nothing to choose, the list's own empty state says it once
      if (!keys(kind).isEmpty()) {
        inspector.row(Panels.empty(Glyph.TEXTS, kind.getTitle(), kind.getHelp())).end();
      }
      inspector.revalidate();
      inspector.repaint();
      return;
    }
    final String key = selected;
    final Game game = project.game();
    inspector
        .title(MapTexts.title(kind, key, game))
        .subtitle(
            SearchList.cutMiddle(
                kind.getFile() + " · " + key,
                page.getFontMetrics(Theme.newSecondaryLabel("").getFont()),
                NOTE_WIDTH))
        .note(SearchList.escape(MapTexts.explain(kind, key, game)));
    if (!value.get().equals(text.getText())) {
      text.setText(value.get());
      text.setCaretPosition(0);
    }
    final JComponent typed = SwingComponents.newJScrollPane(text);
    // as wide as the column, not as the longest line
    typed.setPreferredSize(new Dimension(0, typed.getPreferredSize().height));
    if (kind == Kind.OBJECTIVES && key.contains(GROUP)) {
      // a group's text is a list of condition names: shown in words, typed behind More
      showGroup(value.get());
      inspector.more(GROUP, "Edit the list as text", more -> more.field("Text", typed));
    } else {
      inspector.field("Text", typed);
    }
    if (previews(kind, key)) {
      preview.setText(value.get());
      final JComponent shown = SwingComponents.newJScrollPane(preview);
      shown.setPreferredSize(new Dimension(0, PREVIEW_HEIGHT));
      inspector.field("Preview", shown);
    }
    inspector.remove("Remove text…", this::removeChosen).end();
    inspector.revalidate();
    inspector.repaint();
  }

  /** HTML the game shows: messages, tool tips and the texts of actions, not a message's sounds. */
  private static boolean previews(final Kind kind, final String key) {
    return kind != Kind.OBJECTIVES && !key.endsWith("_sounds");
  }

  /** The group's conditions in plain words; a click chooses the one the file has a text for. */
  private void showGroup(final String value) {
    final List<String> names =
        Stream.of(value.split(";")).map(String::strip).filter(n -> !n.isEmpty()).toList();
    final List<String> keys = keys(Kind.OBJECTIVES);
    final List<Optional<String>> found =
        names.stream()
            .map(
                name ->
                    keys.stream()
                        .filter(k -> !k.contains(GROUP) && k.endsWith(";" + name))
                        .findFirst())
            .toList();
    final JList<String> conditions =
        new JList<>(
            Stream.iterate(0, i -> i < names.size(), i -> i + 1)
                .map(
                    i ->
                        found
                            .get(i)
                            .map(k -> MapTexts.title(Kind.OBJECTIVES, k, project.game()))
                            .orElse(names.get(i)))
                .toArray(String[]::new));
    conditions.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && conditions.getSelectedIndex() >= 0) {
            found
                .get(conditions.getSelectedIndex())
                .ifPresent(
                    k -> {
                      lists.get(Kind.OBJECTIVES).choose(k);
                      choose(k);
                    });
          }
        });
    inspector.section("In this group (" + names.size() + ")").row(conditions);
  }

  private void removeChosen() {
    Optional.ofNullable(selected)
        .ifPresent(
            key -> {
              final Kind kind = shownKind;
              if (Dialogs.confirmRemove(
                  page,
                  "Remove text",
                  "Remove the text of " + MapTexts.title(kind, key, project.game()) + "?",
                  List.of("The game then shows nothing for it."),
                  "Remove")) {
                pause.stop();
                project.removeText(kind, key);
              }
            });
  }

  @Override
  public void commit() {
    pause.stop();
    if (selected == null) {
      return;
    }
    final String key = selected;
    final String typed = text.getText();
    if (previews(shownKind, key)) {
      Panels.showKeepingPlace(preview, typed);
    }
    if (project.texts().value(shownKind, key).filter(v -> !v.equals(typed)).isEmpty()) {
      return;
    }
    editingHere = true;
    try {
      project.setText(shownKind, key, typed);
    } finally {
      editingHere = false;
    }
  }
}
