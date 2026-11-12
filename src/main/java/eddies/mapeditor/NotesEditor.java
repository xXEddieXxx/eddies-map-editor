package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import eddies.mapeditor.MakerIcon.Glyph;
import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.Optional;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import org.triplea.map.data.elements.Info;
import org.triplea.swing.SwingComponents;

/**
 * The game's notes, the HTML players read about the game before and during it: the text on the
 * left, on the right as the game shows it, over the whole width as notes are often wide. Typing
 * becomes one step to undo when it pauses or the text is left, not one per key.
 */
final class NotesEditor implements Editor {
  private static final int PAUSE_MILLIS = 1000;
  private static final int SPLIT_MIN_WIDTH = 960;
  private static final int TOOLBAR_HEIGHT = 36;
  private static final int CAPTION_HEIGHT = 24;
  private static final int SWITCH_HEIGHT = 28;
  private static final String NOTES_CARD = "notes";
  private static final String EMPTY_CARD = "empty";

  /** Which panes show. */
  enum View {
    TEXT,
    SPLIT,
    PREVIEW
  }

  // kept for the session, as a maker who chose a view wants it on every map
  private static Optional<View> chosenView = Optional.empty();

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout());
  private final XmlTextArea text = new XmlTextArea();
  private final GameNotesView preview = new GameNotesView();
  private final CardLayout previewCards = new CardLayout();
  private final JPanel previewPanel = new JPanel(previewCards);
  private final JLabel savedIn = Theme.newSecondaryLabel("");
  private final JToggleButton[] viewButtons = new JToggleButton[View.values().length];
  private final JSplitPane split;
  private final Timer pause = new Timer(PAUSE_MILLIS, e -> commit());
  private boolean editingHere;

  NotesEditor(final MapProject project) {
    this.project = project;
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.colorAsXml();
    text.setText(MapXml.notes(project.game()));
    // on Swing's thread the caret follows the text to its end, and the view with it
    text.setCaretPosition(0);
    preview.setText(text.getText());
    pause.setRepeats(false);
    OnTyping.attach(
        text,
        () -> {
          pause.restart();
          showEmptyOrNotes();
        });
    text.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit();
          }
        });
    bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK), this::bold);
    bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK), this::italic);
    bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), this::lineBreak);

    final JScrollPane textScroll = SwingComponents.newJScrollPane(text);
    textScroll.setRowHeaderView(new LineNumbers(text));
    previewPanel.add(SwingComponents.newJScrollPane(preview), NOTES_CARD);
    previewPanel.add(
        Panels.empty(
            Glyph.NOTES,
            "No game notes yet",
            "Players read them before and during the game: the story, special rules, how to win.",
            "Start from a template",
            this::startFromTemplate),
        EMPTY_CARD);
    split =
        Panels.textAndPreview(captioned("HTML", textScroll), captioned("Preview", previewPanel));
    page.add(newToolbar(), BorderLayout.NORTH);
    page.add(split, BorderLayout.CENTER);
    showSavedIn();
    showEmptyOrNotes();
    show(chosenView.orElse(View.SPLIT));
    project.onChange(
        () -> {
          showSavedIn();
          final String notes = MapXml.notes(project.game());
          // undo, redo or another map: the text follows; typing here keeps the caret
          if (!editingHere && !notes.equals(text.getText())) {
            text.setText(notes);
            preview.setText(notes);
          }
        });
  }

  private void bindKey(final KeyStroke key, final Runnable action) {
    final String name = "notes " + key;
    text.getInputMap().put(key, name);
    text.getActionMap()
        .put(
            name,
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                action.run();
              }
            });
  }

  private void bold() {
    wrap("<b>", "</b>");
  }

  private void italic() {
    wrap("<i>", "</i>");
  }

  private void lineBreak() {
    wrap("<br>\n", "");
  }

  /** The selection between the tags, or the tags at the caret: nothing else of the HTML changes. */
  private void wrap(final String open, final String close) {
    final String selected = Optional.ofNullable(text.getSelectedText()).orElse("");
    text.replaceSelection(open + selected + close);
    text.requestFocusInWindow();
  }

  private void addLink() {
    Dialogs.askName(page, "Add link", "Address", "https://", "Add", a -> Optional.empty())
        .filter(address -> !address.isBlank())
        .ifPresent(
            address -> {
              final String selected = Optional.ofNullable(text.getSelectedText()).orElse("");
              final String href = address.replace("\"", "&quot;");
              wrap("<a href=\"" + href + "\">" + (selected.isEmpty() ? address : ""), "</a>");
            });
  }

  private JComponent newToolbar() {
    final JPanel tags = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
    tags.setOpaque(false);
    tags.add(Theme.newIconButton("B", "Bold (Ctrl+B)", this::bold));
    tags.add(Theme.newIconButton("I", "Italic (Ctrl+I)", this::italic));
    tags.add(Theme.newIconButton("H", "Heading", () -> wrap("<h3>", "</h3>")));
    tags.add(Theme.newIconButton("•", "List", () -> wrap("<ul>\n  <li>", "</li>\n</ul>")));
    tags.add(Theme.newIconButton("¶", "Line break (Shift+Enter)", this::lineBreak));
    final JButton link = Theme.newIconButton("<a>", "Link…", this::addLink);
    // as wide as its three letters, which 28 px cut off
    final int height = link.getPreferredSize().height;
    link.setPreferredSize(null);
    link.setPreferredSize(new Dimension(link.getPreferredSize().width, height));
    tags.add(link);

    final JPanel views = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    views.setOpaque(false);
    final ButtonGroup group = new ButtonGroup();
    for (final View view : View.values()) {
      final String name = view.name().charAt(0) + view.name().substring(1).toLowerCase();
      final JToggleButton button = new JToggleButton(name);
      button.putClientProperty(
          FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
      button.setPreferredSize(new Dimension(button.getPreferredSize().width, SWITCH_HEIGHT));
      button.addActionListener(
          e -> {
            chosenView = Optional.of(view);
            show(view);
          });
      group.add(button);
      viewButtons[view.ordinal()] = button;
      views.add(button);
    }

    final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    right.setOpaque(false);
    right.add(savedIn);
    right.add(Box.createHorizontalStrut(Theme.GAP_M));
    right.add(views);

    final JPanel toolbar = new JPanel(new BorderLayout());
    toolbar.setOpaque(false);
    toolbar.setPreferredSize(new Dimension(0, TOOLBAR_HEIGHT));
    toolbar.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_S, 0));
    toolbar.add(tags, BorderLayout.WEST);
    toolbar.add(right, BorderLayout.EAST);
    return toolbar;
  }

  private static JComponent captioned(final String caption, final JComponent pane) {
    final JLabel label = Theme.newSecondaryLabel(caption);
    label.setPreferredSize(new Dimension(0, CAPTION_HEIGHT));
    final JPanel captioned = new JPanel(new BorderLayout());
    captioned.setOpaque(false);
    captioned.add(label, BorderLayout.NORTH);
    captioned.add(pane, BorderLayout.CENTER);
    return captioned;
  }

  private void showSavedIn() {
    savedIn.setText(
        project
            .getXmlFile()
            .map(xml -> "Saved in " + MapProject.notesFile(xml).getFileName())
            .orElse("Saved beside the game file"));
  }

  private void showEmptyOrNotes() {
    previewCards.show(previewPanel, text.getText().isBlank() ? EMPTY_CARD : NOTES_CARD);
  }

  void show(final View view) {
    viewButtons[view.ordinal()].setSelected(true);
    split.getLeftComponent().setVisible(view != View.PREVIEW);
    split.getRightComponent().setVisible(view != View.TEXT);
    split.resetToPreferredSizes();
    split.revalidate();
    split.repaint();
  }

  /** A start for blank notes only, as one step: notes with text stay exactly as typed. */
  private void startFromTemplate() {
    commit();
    if (!MapXml.notes(project.game()).isBlank()) {
      return;
    }
    final String name =
        Optional.ofNullable(project.game().getInfo()).map(Info::getName).orElse("");
    text.setText(
        "<h2>"
            + SearchList.escape(name)
            + "</h2>\n<p><b>Made by:</b> your name</p>\n<h3>How to win</h3>\n<p>…</p>\n"
            + "<h3>Special rules</h3>\n<ul>\n  <li>…</li>\n</ul>\n");
    commit();
  }

  @Override
  public String title() {
    return "Game notes";
  }

  @Override
  public Glyph glyph() {
    return Glyph.NOTES;
  }

  @Override
  public String hint() {
    return "What players read about your game: its story, rules and tips, in HTML. The preview"
        + " shows it as the game does.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    if (chosenView.isEmpty()) {
      show(page.getWidth() >= SPLIT_MIN_WIDTH || page.getWidth() == 0 ? View.SPLIT : View.TEXT);
    }
  }

  @Override
  public void commit() {
    pause.stop();
    final String typed = text.getText();
    if (typed.equals(MapXml.notes(project.game()))) {
      return;
    }
    Panels.showKeepingPlace(preview, typed);
    editingHere = true;
    try {
      project.edit(game -> MapXml.withNotes(game, typed));
    } finally {
      editingHere = false;
    }
  }
}
