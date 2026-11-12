package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JSplitPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Game notes: the insert bar, the template for blank notes, the views and the line numbers. */
final class NotesEditorTest {

  @DisplayName("Bold wraps the selection in <b> and is one step to undo, the rest kept as typed")
  @Test
  void boldWrapsTheSelection() {
    final MapProject project = givenMinimap("<p>Hello  world</p>");
    final NotesEditor notes = new NotesEditor(project);
    final XmlTextArea text = find(notes.page(), XmlTextArea.class).orElseThrow();

    text.select(10, 15);
    button(notes, b -> "Bold (Ctrl+B)".equals(b.getToolTipText())).doClick();
    notes.commit();
    final String bold = MapXml.notes(project.game());
    project.undo();

    assertThat(bold)
        .as("the selection in <b>, nothing else changed")
        .isEqualTo("<p>Hello  <b>world</b></p>");
    assertThat(MapXml.notes(project.game()))
        .as("one undo takes the bold back")
        .isEqualTo("<p>Hello  world</p>");
  }

  @DisplayName("Long notes open at their start, also when made on Swing's thread")
  @Test
  void opensAtTheTop() throws Exception {
    final MapProject project = givenMinimap("<p>line</p>\n".repeat(300));
    final NotesEditor[] notes = new NotesEditor[1];

    SwingUtilities.invokeAndWait(() -> notes[0] = new NotesEditor(project));
    final XmlTextArea text = find(notes[0].page(), XmlTextArea.class).orElseThrow();

    assertThat(text.getCaretPosition())
        .as("the caret, and so the view, at the first line, not the last")
        .isZero();
  }

  @DisplayName("Text typed just before is taken by commit, and the template hides at once")
  @Test
  void commitTakesTextTypedJustBefore() {
    final MapProject project = givenMinimap("");
    final NotesEditor notes = new NotesEditor(project);
    final XmlTextArea text = find(notes.page(), XmlTextArea.class).orElseThrow();

    text.setText("<p>Our story</p>");
    final boolean templateShown =
        buttons(notes.page())
            .filter(b -> "Start from a template".equals(b.getText()))
            .anyMatch(b -> isShowing(b, notes.page()));
    notes.commit();

    assertThat(templateShown).as("the empty page goes as soon as text is typed").isFalse();
    assertThat(MapXml.notes(project.game()))
        .as("commit, as before undo or a save, keeps what was just typed")
        .isEqualTo("<p>Our story</p>");
  }

  @DisplayName("Blank notes offer a template with the game's name, written as one step")
  @Test
  void templateForBlankNotes() {
    final MapProject project = givenMinimap("");
    final NotesEditor notes = new NotesEditor(project);

    button(notes, b -> "Start from a template".equals(b.getText())).doClick();
    final String written = MapXml.notes(project.game());
    project.undo();

    assertThat(written)
        .as("the template, with the game's name as the heading")
        .startsWith("<h2>Minimap</h2>")
        .contains("<h3>How to win</h3>");
    assertThat(MapXml.notes(project.game())).as("one undo takes it back").isBlank();
  }

  @DisplayName("Notes with text show no template button")
  @Test
  void noTemplateForNotesWithText() {
    final NotesEditor notes = new NotesEditor(givenMinimap("<p>Our story</p>"));

    final boolean shown =
        buttons(notes.page())
            .filter(b -> "Start from a template".equals(b.getText()))
            .anyMatch(b -> isShowing(b, notes.page()));

    assertThat(shown).as("the template only for blank notes").isFalse();
  }

  @DisplayName("Text hides the preview, Split shows both")
  @Test
  void viewSwitch() {
    final NotesEditor notes = new NotesEditor(givenMinimap("<p>Our story</p>"));
    final JSplitPane split = find(notes.page(), JSplitPane.class).orElseThrow();

    notes.show(NotesEditor.View.TEXT);
    final List<Boolean> text =
        List.of(split.getLeftComponent().isVisible(), split.getRightComponent().isVisible());
    notes.show(NotesEditor.View.SPLIT);
    final List<Boolean> both =
        List.of(split.getLeftComponent().isVisible(), split.getRightComponent().isVisible());

    assertThat(text).as("Text: the HTML only").containsExactly(true, false);
    assertThat(both).as("Split: the HTML and the preview").containsExactly(true, true);
  }

  @DisplayName("Line numbers stand where lines start, so a wrapped line keeps one number")
  @Test
  void lineNumbersFollowWrapping() {
    final XmlTextArea text = new XmlTextArea();
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setText("word ".repeat(200) + "\nsecond line");
    text.setSize(400, 400);
    final LineNumbers numbers = new LineNumbers(text);
    final int lineHeight = text.getFontMetrics(text.getFont()).getHeight();

    final List<Integer> tops = numbers.lineTops();

    assertThat(tops).as("one top per line").hasSize(2);
    assertThat(tops.get(1) - tops.get(0))
        .as("the long first line wraps over several rows")
        .isGreaterThan(lineHeight);
  }

  @DisplayName("The preview finds a picture as the game does, in doc/images; the notes keep it")
  @Test
  void previewFindsPicturesInDocImages(@TempDir final Path folder) throws Exception {
    // given: a map folder with its outlines file, so it is the map's content folder
    Files.writeString(folder.resolve("polygons.txt"), "");
    final MapProject project =
        new MapProject(
            folder.resolve("games/minimap.xml"),
            MapXml.withNotes(MapXmlTest.givenMinimap(), "<img src=\"preview.png\"/>"),
            MapFiles.of(folder.resolve("games/minimap.xml")));

    // when
    final String shown = new NotesEditor(project).asShown(MapXml.notes(project.game()));

    // then
    assertThat(shown)
        .as("the link the game opens")
        .contains(folder.resolve("doc/images/preview.png").toUri().toURL().toString());
    assertThat(MapXml.notes(project.game()))
        .as("saved as typed")
        .isEqualTo("<img src=\"preview.png\"/>");
  }

  @DisplayName("A picture from elsewhere is copied into doc/images, a taken name not overwritten")
  @Test
  void pictureCopiedUnderFreeName(@TempDir final Path folder) throws Exception {
    // given: doc/images with another preview.png, and a preview.png elsewhere
    final Path images = Files.createDirectories(folder.resolve("doc/images"));
    Files.writeString(images.resolve("preview.png"), "the map's own");
    final Path chosen = Files.writeString(folder.resolve("preview.png"), "a new one");

    // when
    final String first = NotesEditor.pictureIn(images, chosen);
    final String again = NotesEditor.pictureIn(images, chosen);
    final String own = NotesEditor.pictureIn(images, images.resolve("preview.png"));

    // then
    assertThat(first).as("a free name").isEqualTo("preview-2.png");
    assertThat(Files.readString(images.resolve("preview.png")))
        .as("the map's own kept")
        .isEqualTo("the map's own");
    assertThat(again).as("the same picture once").isEqualTo("preview-2.png");
    assertThat(own).as("one there already, as it is").isEqualTo("preview.png");
  }

  private static MapProject givenMinimap(final String notes) {
    return new MapProject(
        Path.of("minimap.xml"),
        MapXml.withNotes(MapXmlTest.givenMinimap(), notes),
        MapFiles.none());
  }

  private static AbstractButton button(
      final NotesEditor notes, final Predicate<AbstractButton> which) {
    return buttons(notes.page()).filter(which).findFirst().orElseThrow();
  }

  /** Visible up to the page, as a card behind another is not. */
  private static boolean isShowing(final Component part, final Container page) {
    for (Component c = part; c != page; c = c.getParent()) {
      if (!c.isVisible()) {
        return false;
      }
    }
    return true;
  }

  private static Stream<AbstractButton> buttons(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                Stream.concat(
                    c instanceof AbstractButton b ? Stream.of(b) : Stream.empty(),
                    c instanceof Container inner ? buttons(inner) : Stream.empty()));
  }

  private static <T> Optional<T> find(final Container in, final Class<T> type) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                type.isInstance(c)
                    ? Stream.of(type.cast(c))
                    : c instanceof Container inner ? find(inner, type).stream() : Stream.empty())
        .findFirst();
  }

  @DisplayName("A picture in a map not saved yet asks to save first and opens no chooser")
  @Test
  void pictureNeedsASavedMap() throws Exception {
    // given: notes of a map without a folder, in a window
    final NotesEditor notes = new NotesEditor(givenMinimap("<p>Our story</p>"));
    final JRootPane window = new JRootPane();
    window.setSize(1100, 700);
    window.getContentPane().add(notes.page());
    window.doLayout();

    // when: it returns, as no chooser waits for a choice
    button(
            notes,
            b -> b.getToolTipText() != null && b.getToolTipText().startsWith("Picture…"))
        .doClick();

    // then
    assertThat(Toast.shown(window.getLayeredPane()))
        .as("the toast says why")
        .map(toast -> toast.getAccessibleContext().getAccessibleDescription())
        .contains("! Save the map first: its pictures go into its folder");
  }
}
