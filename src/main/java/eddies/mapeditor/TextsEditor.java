package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.DocumentListenerBuilder;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapTexts.Entry;
import eddies.mapeditor.MapTexts.Kind;

/**
 * The texts the game reads by key beside the game file: objectives, the messages of triggers and
 * unit tool tips. A file at a time, its keys in a list with what each means, the chosen one's text
 * to change. Typing becomes one step when it pauses or the text is left.
 */
final class TextsEditor implements Editor {
  private static final int PAUSE_MILLIS = 1000;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 8));
  private final JComboBox<Kind> file = new JComboBox<>(Kind.values());
  private final SearchList list;
  private final Form inspector = new Form(360);
  private final XmlTextArea text = new XmlTextArea();
  private final Timer pause = new Timer(PAUSE_MILLIS, e -> commit());
  private @Nullable String selected;
  // the file the text shown belongs to, as the choice of file changes before the text is kept
  private Kind shownKind = Kind.OBJECTIVES;
  private boolean editingHere;
  private final JButton add = new JButton();
  private final JButton remove = new JButton();
  private final Map<String, String> explained = new HashMap<>();
  private @Nullable Game explainedFor;
  private @Nullable Kind explainedKind;

  TextsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search key",
            () -> {
              final List<String> keys = keys();
              return keys.stream().map(k -> shown(k, keys)).toList();
            },
            this::explain,
            this::choose);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.colorAsXml();
    text.setRows(12);
    pause.setRepeats(false);
    new DocumentListenerBuilder(pause::restart).attachTo(text);
    text.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit();
          }
        });
    file.setToolTipText("Which file's texts are listed");
    file.addActionListener(
        e -> {
          commit();
          selected = null;
          list.refresh();
          showInspector();
        });
    add.setText("Add entry...");
    add.setToolTipText("Adds an entry, its key offered from the game");
    add.addActionListener(e -> addEntry());
    remove.setText("Remove");
    remove.setToolTipText("Removes the chosen entry from the file");
    remove.addActionListener(
        e -> Optional.ofNullable(selected).ifPresent(key -> project.removeText(kind(), key)));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(add);
    buttons.add(remove);
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(file, BorderLayout.NORTH);
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(buttons, BorderLayout.SOUTH);
    page.add(
        Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(inspector)),
        BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          if (!editingHere) {
            showInspector();
          }
        });
    showInspector();
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
    return "The texts the game shows by key: objectives, the messages of triggers and unit tool"
        + " tips. Each is saved in its file beside the game.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  /** What the key means; kept per game and file, as the list asks for every row it paints. */
  private String explain(final String key) {
    if (explainedFor != project.game() || explainedKind != kind()) {
      explained.clear();
      explainedFor = project.game();
      explainedKind = kind();
    }
    return explained.computeIfAbsent(
        key, k -> MapTexts.explain(kind(), keyOf(k), project.game()));
  }

  private List<String> keys() {
    return project.texts().entries(kind()).stream().map(Entry::key).toList();
  }

  /**
   * The key as listed: objectives' keys without the game's name they all start with, unless that
   * leaves another key's name.
   */
  private String shown(final String key, final List<String> keys) {
    final String prefix = MapTexts.objectivesPrefix(project.game());
    return kind() == Kind.OBJECTIVES
            && key.startsWith(prefix)
            && !keys.contains(key.substring(prefix.length()))
        ? key.substring(prefix.length())
        : key;
  }

  /** The key of a name in the list. */
  private String keyOf(final String shown) {
    return keys().contains(shown) ? shown : MapTexts.objectivesPrefix(project.game()) + shown;
  }

  private Kind kind() {
    return (Kind) file.getSelectedItem();
  }

  private void choose(final String shown) {
    commit();
    selected = keyOf(shown);
    showInspector();
  }

  /** Offers the keys the game's conditions, triggers or units call for; any other may be typed. */
  private void addEntry() {
    final List<Entry> few = MapTexts.suggestions(kind(), project.game(), project.texts());
    final List<Entry> all = MapTexts.allSuggestions(kind(), project.game(), project.texts());
    final JComboBox<String> key = new JComboBox<>(keysOf(few));
    key.setEditable(true);
    final JPanel ask = new JPanel(new BorderLayout(0, 6));
    ask.add(key, BorderLayout.CENTER);
    // a big map has thousands of conditions: those like objectives first, all on request
    if (all.size() > few.size()) {
      final JCheckBox showAll = new JCheckBox("All conditions and triggers (" + all.size() + ")");
      showAll.addActionListener(
          e -> key.setModel(new DefaultComboBoxModel<>(keysOf(showAll.isSelected() ? all : few))));
      ask.add(showAll, BorderLayout.SOUTH);
    }
    final boolean ok = Panels.ask(page, ask, "Add entry: key");
    final String chosen = String.valueOf(key.getSelectedItem()).strip();
    if (!ok || chosen.isEmpty() || chosen.equals("null")) {
      return;
    }
    if (project.texts().value(kind(), chosen).isEmpty()) {
      final String start =
          Stream.concat(few.stream(), all.stream())
              .filter(e -> e.key().equals(chosen))
              .map(Entry::value)
              .findFirst()
              .orElse("");
      project.setText(kind(), chosen, start);
    }
    selected = chosen;
    list.choose(shown(chosen, keys()));
    showInspector();
  }

  private static String[] keysOf(final List<Entry> entries) {
    return entries.stream().map(Entry::key).toArray(String[]::new);
  }

  private void showInspector() {
    inspector.clear();
    shownKind = kind();
    final boolean readable = !project.texts().unreadable().contains(kind());
    add.setEnabled(readable);
    remove.setEnabled(readable);
    if (!readable) {
      selected = null;
      inspector
          .title(kind().getTitle(), kind().getHelp())
          .note(kind().getFile() + " does not read: fix it in Files.")
          .end();
      return;
    }
    final Optional<String> value =
        Optional.ofNullable(selected).flatMap(key -> project.texts().value(kind(), key));
    if (value.isEmpty()) {
      selected = null;
      final int count = project.texts().entries(kind()).size();
      inspector
          .title(kind().getTitle(), kind().getHelp())
          .note(
              count == 0
                  ? "No entries in " + kind().getFile() + " yet. Add one with Add entry."
                  : count + " entries in " + kind().getFile() + ".")
          .end();
      return;
    }
    final String key = selected;
    inspector.title(key).note(explain(key));
    if (!value.get().equals(text.getText())) {
      text.setText(value.get());
      text.setCaretPosition(0);
    }
    inspector.field("Text", SwingComponents.newJScrollPane(text)).end();
  }

  @Override
  public void commit() {
    pause.stop();
    if (selected == null) {
      return;
    }
    final String key = selected;
    final String typed = text.getText();
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
