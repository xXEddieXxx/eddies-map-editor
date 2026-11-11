package eddies.mapeditor;

import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.BorderLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import org.triplea.swing.DocumentListenerBuilder;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The game's notes, the HTML players read about the game before and during it: the text on the
 * left, on the right as the game shows it, over the whole width as notes are often wide. Typing
 * becomes one step to undo when it pauses or the text is left, not one per key.
 */
final class NotesEditor implements Editor {
  private static final int PAUSE_MILLIS = 1000;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout());
  private final XmlTextArea text = new XmlTextArea();
  private final GameNotesView preview = new GameNotesView();
  private final Timer pause = new Timer(PAUSE_MILLIS, e -> commit());
  private boolean editingHere;

  NotesEditor(final MapProject project) {
    this.project = project;
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.colorAsXml();
    text.setText(MapXml.notes(project.game()));
    preview.setText(text.getText());
    pause.setRepeats(false);
    new DocumentListenerBuilder(pause::restart).attachTo(text);
    text.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit();
          }
        });
    page.add(
        Panels.textAndPreview(
            SwingComponents.newJScrollPane(text), SwingComponents.newJScrollPane(preview)),
        BorderLayout.CENTER);
    project.onChange(
        () -> {
          final String notes = MapXml.notes(project.game());
          // undo, redo or another map: the text follows; typing here keeps the caret
          if (!editingHere && !notes.equals(text.getText())) {
            text.setText(notes);
            preview.setText(notes);
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
