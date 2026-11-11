package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.Container;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.JSplitPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The text to change on the left, how the game shows it on the right. */
final class TextAndPreviewTest {
  @TempDir Path folder;

  @DisplayName("Notes: the text left of the preview over the whole width; a pause is one step")
  @Test
  void notesTextLeftOfPreview() throws Exception {
    final MapProject project = givenMinimapProject();
    final String before = MapXml.notes(project.game());
    final NotesEditor notes = new NotesEditor(project);
    final XmlTextArea text = find(notes.page(), XmlTextArea.class).orElseThrow();

    text.setText("<p>Hello marker</p>");
    notes.commit();

    final JSplitPane split = find(notes.page(), JSplitPane.class).orElseThrow();
    assertThat(notes.preview()).as("no middle: the tool takes the whole width").isEmpty();
    assertThat(find((Container) split.getLeftComponent(), XmlTextArea.class))
        .as("the text on the left")
        .isPresent();
    assertThat(find((Container) split.getRightComponent(), GameNotesView.class))
        .as("the preview on the right, showing the typed text")
        .hasValueSatisfying(view -> assertThat(view.getText()).contains("Hello marker"));
    project.undo();
    assertThat(MapXml.notes(project.game()))
        .as("one undo takes the typing back")
        .isEqualTo(before);
    assertThat(find((Container) split.getRightComponent(), GameNotesView.class))
        .as("the preview follows the undo")
        .hasValueSatisfying(view -> assertThat(view.getText()).doesNotContain("Hello marker"));
  }

  private MapProject givenMinimapProject() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        TextAndPreviewTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  @DisplayName("Files: an HTML file shows its preview right of its text, a text file none")
  @Test
  void htmlFileTextLeftOfPreview() throws Exception {
    final FilesEditor files = givenFilesOf("description.html", "<p>A map</p>", "notes.txt", "x");

    // one text area serves every file: what each file shows is read before the next opens
    files.reveal(folder.resolve("description.html"));
    final JSplitPane htmlSplit =
        find(files.preview().orElseThrow(), JSplitPane.class).orElseThrow();
    final boolean textLeft =
        find((Container) htmlSplit.getLeftComponent(), XmlTextArea.class).isPresent();
    final String previewRight =
        find((Container) htmlSplit.getRightComponent(), GameNotesView.class)
            .map(GameNotesView::getText)
            .orElse("");
    find(files.preview().orElseThrow(), XmlTextArea.class).orElseThrow().setText("<p>Typed</p>");
    files.previewHtml();
    final String previewTyped =
        find((Container) htmlSplit.getRightComponent(), GameNotesView.class)
            .map(GameNotesView::getText)
            .orElse("");
    // the typing dropped, so the next file opens without asking
    files.forgetText();
    files.reveal(folder.resolve("notes.txt"));
    final Optional<JSplitPane> textSplit = find(files.preview().orElseThrow(), JSplitPane.class);

    assertThat(textLeft).as("the HTML's text on the left").isTrue();
    assertThat(previewRight).as("its preview on the right, showing it").contains("A map");
    assertThat(previewTyped).as("a pause in typing shows the typed text").contains("Typed");
    assertThat(textSplit).as("a text file has no preview").isEmpty();
  }

  private FilesEditor givenFilesOf(final String... namesAndTexts) throws Exception {
    Files.writeString(folder.resolve("map.yml"), "map_name: Test\n");
    for (int i = 0; i < namesAndTexts.length; i += 2) {
      Files.writeString(folder.resolve(namesAndTexts[i]), namesAndTexts[i + 1]);
    }
    final FilesEditor files =
        new FilesEditor(() -> Optional.of(folder), () -> true, () -> {}, () -> {});
    files.showing();
    return files;
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
}
