package eddies.mapeditor;

import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import org.triplea.swing.DocumentListenerBuilder;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The game's notes, the HTML players read about the game before and during it: the text on the
 * right, in the middle as the game shows it. Typing becomes one step to undo when it pauses or the
 * text is left, not one per key.
 */
final class NotesEditor implements Editor {
  private static final int PAUSE_MILLIS = 1000;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout());
  private final JTextArea text = new JTextArea();
  private final GameNotesView preview = new GameNotesView();
  private final JComponent shown;
  private final Timer pause = new Timer(PAUSE_MILLIS, e -> commit());
  private boolean editingHere;

  NotesEditor(final MapProject project) {
    this.project = project;
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize()));
    text.setText(MapXml.notes(project.game()));
    preview.setText(text.getText());
    pause.setRepeats(false);
    new DocumentListenerBuilder(
            () -> {
              preview.setText(text.getText());
              pause.restart();
            })
        .attachTo(text);
    text.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit();
          }
        });
    page.add(SwingComponents.newJScrollPane(text), BorderLayout.CENTER);
    shown = SwingComponents.newJScrollPane(preview);
    project.onChange(
        () -> {
          final String notes = MapXml.notes(project.game());
          // undo, redo or another map: the text follows; typing here keeps the caret
          if (!editingHere && !notes.equals(text.getText())) {
            text.setText(notes);
          }
        });
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

  /** The notes as the game shows them, in the middle. */
  @Override
  public Optional<JComponent> preview() {
    return Optional.of(shown);
  }

  @Override
  public void commit() {
    pause.stop();
    final String typed = text.getText();
    if (typed.equals(MapXml.notes(project.game()))) {
      return;
    }
    editingHere = true;
    try {
      project.edit(game -> MapXml.withNotes(game, typed));
    } finally {
      editingHere = false;
    }
  }
}
