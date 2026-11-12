package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FilesEditorTest {
  @TempDir Path folder;

  @DisplayName("No file open: the map folder's parts, each with the tool that makes it")
  @Test
  void anatomyWithoutFile() throws Exception {
    final List<String> opened = new ArrayList<>();
    final FilesEditor files = givenFilesOfMinimap(opened, () -> true, () -> {});

    final Optional<Component> title = find(files.page(), labelled("What's in a map folder"));
    find(files.page(), labelled("Edit in Territories"))
        .map(AbstractButton.class::cast)
        .ifPresent(AbstractButton::doClick);

    assertThat(title).as("the landing page explains the folder").isPresent();
    assertThat(opened).as("a part's link opens its tool").contains("Territories");
  }

  @DisplayName("A missing part and the relief read Make in their tool, a present part Edit")
  @Test
  void anatomyVerbs() throws Exception {
    Files.createDirectory(folder.resolve("reliefTiles"));
    final FilesEditor files = givenFilesOfMinimap(new ArrayList<>(), () -> true, () -> {});

    final List<String> links =
        Stream.of("Make in Map look", "Make in Territories", "Edit in Territories")
            .filter(text -> find(files.page(), labelled(text)).isPresent())
            .toList();

    assertThat(links)
        .as("relief made again, polygons.txt still to make, the game XML to edit")
        .containsExactly("Make in Map look", "Make in Territories", "Edit in Territories");
    assertThat(find(files.page(), labelled("Edit in Map look")))
        .as("map.properties is missing, the relief is never edited")
        .isEmpty();
  }

  @DisplayName("Revert and Save file show only once the file is changed")
  @Test
  void saveShowsWhenChanged() throws Exception {
    final FilesEditor files = givenFilesOfMinimap(new ArrayList<>(), () -> true, () -> {});
    files.reveal(folder.resolve("notes.txt"));
    final AbstractButton save = button(files, "Save file");
    final boolean shownBefore = save.isVisible();

    find(files.page(), XmlTextArea.class::isInstance)
        .map(XmlTextArea.class::cast)
        .orElseThrow()
        .setText("changed");

    assertThat(shownBefore).as("nothing to save yet").isFalse();
    assertThat(save.isVisible()).as("the typed text can be saved").isTrue();
    assertThat(button(files, "Revert").isVisible()).as("and reverted").isTrue();
  }

  @DisplayName("Wrap lines wraps the text and keeps the line numbers, one per line of the file")
  @Test
  void wrapKeepsLineNumbers() throws Exception {
    final FilesEditor files = givenFilesOfMinimap(new ArrayList<>(), () -> true, () -> {});
    files.reveal(folder.resolve("notes.txt"));
    final XmlTextArea text =
        find(files.page(), XmlTextArea.class::isInstance).map(XmlTextArea.class::cast).orElseThrow();
    final JScrollPane scroll = (JScrollPane) text.getParent().getParent();

    button(files, "Wrap lines").doClick();

    assertThat(text.getLineWrap()).as("the lines wrap").isTrue();
    assertThat(scroll.getRowHeader().getView())
        .as("line numbers while wrapping too")
        .isInstanceOf(LineNumbers.class);
  }

  @DisplayName("A first version revealed, eg by the toast after a save, shows its text read only")
  @Test
  void revealedBackupShowsReadOnly() throws Exception {
    final FilesEditor files = givenFilesOfMinimap(new ArrayList<>(), () -> true, () -> {});
    final Path backup = folder.resolve("minimap.xml.bak");
    Files.writeString(backup, "<game/>");

    files.reveal(backup);

    final XmlTextArea text =
        find(files.page(), XmlTextArea.class::isInstance)
            .map(XmlTextArea.class::cast)
            .orElseThrow();
    assertThat(text.getText()).as("its text, read as the XML file it was").isEqualTo("<game/>");
    assertThat(text.isEditable()).as("a first version is kept as it is").isFalse();
    assertThat(find(files.page(), labelled("Read only: a first version")))
        .as("told why it is read only")
        .isPresent();
    assertThat(treeNames(files)).as("listed in the tree").contains("minimap.xml.bak");
  }

  @DisplayName("The search waits for a pause in typing, then shows what matches and its folders")
  @Test
  void searchAfterPause() throws Exception {
    final FilesEditor files = givenFilesOfMinimap(new ArrayList<>(), () -> true, () -> {});
    Files.createDirectories(folder.resolve("units/Red"));
    Files.writeString(folder.resolve("units/Red/infantry.png"), "");
    Files.createDirectories(folder.resolve("docs/.git"));
    Files.writeString(folder.resolve("docs/.git/infantry.txt"), "");
    final JTextField search =
        find(files.page(), JTextField.class::isInstance).map(JTextField.class::cast).orElseThrow();

    search.setText("infantry");
    final List<String> whileTyping = treeNames(files);
    final List<String> afterPause = awaitTree(files, names -> names.contains("infantry.png"));

    assertThat(whileTyping).as("nothing searched while typing").contains("notes.txt");
    assertThat(afterPause)
        .as("the match and the folders it is in, nothing in hidden folders")
        .containsSubsequence("units", "Red", "infantry.png")
        .doesNotContain("notes.txt", "docs");
  }

  @DisplayName("The open file stays selected in the tree when the tool shows again")
  @Test
  void treeKeepsTheOpenFile() throws Exception {
    final FilesEditor files = givenFilesOfMinimap(new ArrayList<>(), () -> true, () -> {});
    files.reveal(folder.resolve("notes.txt"));

    files.showing();

    final JTree tree =
        find(files.page(), JTree.class::isInstance).map(JTree.class::cast).orElseThrow();
    assertThat(tree.getSelectionPath())
        .as("the tree shows which file is open")
        .isNotNull()
        .extracting(TreePath::getLastPathComponent)
        .isEqualTo(folder.resolve("notes.txt"));
  }

  @DisplayName("The anatomy's marks take one width, so the names after ✓, ! and ○ line up")
  @Test
  void anatomyMarksOneWidth() throws Exception {
    final FilesEditor files = givenFilesOfMinimap(new ArrayList<>(), () -> true, () -> {});

    final List<Integer> widths =
        Stream.of(Theme.Status.DONE, Theme.Status.PROBLEM, Theme.Status.TODO)
            .map(status -> find(files.page(), labelled(status.mark())).orElseThrow())
            .map(mark -> mark.getPreferredSize().width)
            .toList();

    assertThat(widths)
        .as("one mark column for there, missing and optional")
        .containsOnly(widths.getFirst());
  }

  private static List<String> awaitTree(
      final FilesEditor files, final Predicate<List<String>> done) throws Exception {
    final long until = System.currentTimeMillis() + 5_000;
    final AtomicReference<List<String>> names = new AtomicReference<>(List.of());
    do {
      Thread.sleep(50);
      // the search's timer runs on the event thread: read the tree there too
      SwingUtilities.invokeAndWait(() -> names.set(treeNames(files)));
    } while (!done.test(names.get()) && System.currentTimeMillis() < until);
    return names.get();
  }

  private static List<String> treeNames(final FilesEditor files) {
    final JTree tree =
        find(files.page(), JTree.class::isInstance).map(JTree.class::cast).orElseThrow();
    return IntStream.range(0, tree.getRowCount())
        .mapToObj(row -> tree.getPathForRow(row).getLastPathComponent())
        .filter(Path.class::isInstance)
        .map(node -> ((Path) node).getFileName().toString())
        .toList();
  }

  @DisplayName("Save file writes only when the map may be written, then reads the map again")
  @Test
  void saveAsksThenReloads() throws Exception {
    final AtomicBoolean mayWrite = new AtomicBoolean(false);
    final AtomicBoolean reloaded = new AtomicBoolean(false);
    final FilesEditor files =
        givenFilesOfMinimap(new ArrayList<>(), mayWrite::get, () -> reloaded.set(true));
    files.reveal(folder.resolve("notes.txt"));
    find(files.page(), XmlTextArea.class::isInstance)
        .map(XmlTextArea.class::cast)
        .orElseThrow()
        .setText("typed");

    button(files, "Save file").doClick();
    final String refused = Files.readString(folder.resolve("notes.txt"));
    final boolean reloadedRefused = reloaded.get();
    mayWrite.set(true);
    button(files, "Save file").doClick();

    assertThat(refused).as("the map may not be written: the file stays").isEqualTo("x");
    assertThat(reloadedRefused).as("nothing to read again").isFalse();
    assertThat(Files.readString(folder.resolve("notes.txt"))).as("written").isEqualTo("typed");
    assertThat(folder.resolve("notes.txt.bak")).as("its first version kept").hasContent("x");
    assertThat(reloaded.get()).as("the map is read again from its files").isTrue();
  }

  private FilesEditor givenFilesOfMinimap(
      final List<String> opened,
      final BooleanSupplier mayWrite,
      final Runnable reload)
      throws Exception {
    Files.writeString(folder.resolve("map.yml"), "map_name: Test\n");
    Files.writeString(folder.resolve("notes.txt"), "x");
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        FilesEditorTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    final MapProject project = MapProject.open(xml);
    final FilesEditor files = new FilesEditor(() -> project, mayWrite, reload, opened::add);
    files.showing();
    return files;
  }

  private static AbstractButton button(final FilesEditor files, final String text) {
    return find(files.page(), labelled(text)).map(AbstractButton.class::cast).orElseThrow();
  }

  private static Predicate<Component> labelled(final String text) {
    return c ->
        c instanceof AbstractButton button && text.equals(button.getText())
            || c instanceof JLabel label && text.equals(label.getText());
  }

  private static Optional<Component> find(final Container in, final Predicate<Component> wanted) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                wanted.test(c)
                    ? Stream.of(c)
                    : c instanceof Container inner ? find(inner, wanted).stream() : Stream.empty())
        .findFirst();
  }
}
