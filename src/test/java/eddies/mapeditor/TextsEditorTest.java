package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.MapTexts.Kind;

/** Texts: a tab per file, the banner for another game's objectives, the preview of HTML. */
final class TextsEditorTest {
  private static final String FOREIGN = "World_War_II_Global_1940_Alpha_3.";

  @TempDir Path folder;

  private MapProject givenMinimapWithTexts() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        TextsEditorTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    Files.writeString(
        folder.resolve("objectives.properties"),
        FOREIGN + "Russians;objectiveAttachment_Russians_1_Hold_Moscow=Hold Moscow\n"
            + FOREIGN + "TABLEGROUP.01;Russians=objectiveAttachment_Russians_1_Hold_Moscow\n");
    Files.writeString(folder.resolve("notifications.properties"), "Welcome=<b>Hello</b>\n");
    return MapProject.open(xml);
  }

  @DisplayName("A tab per file but sounds, in their order, each titled with its count")
  @Test
  void tabsWithCounts() throws Exception {
    final TextsEditor texts = new TextsEditor(givenMinimapWithTexts());

    final JTabbedPane tabs = all(texts.page(), JTabbedPane.class).getFirst();
    final List<String> titles =
        IntStream.range(0, tabs.getTabCount()).mapToObj(tabs::getTitleAt).toList();

    assertThat(titles).as("five tabs, sounds have their own tool").hasSize(5);
    assertThat(titles.get(0)).as("objectives first, with its count").contains("Objectives", ">2<");
    assertThat(titles.get(1)).as("then messages").contains("Messages", ">1<");
    assertThat(titles.get(4)).as("user actions last").contains("User actions", ">0<");
  }

  @DisplayName("The banner shows for another game's objectives and renames them in one step")
  @Test
  void bannerRenamesForeignKeys() throws Exception {
    final MapProject project = givenMinimapWithTexts();
    final TextsEditor texts = new TextsEditor(project);
    final AbstractButton use = button(texts, "Use them for this game");
    final boolean shownBefore = use.getParent().isVisible();

    use.doClick();
    final List<String> renamed =
        project.texts().entries(Kind.OBJECTIVES).stream().map(MapTexts.Entry::key).toList();
    final boolean shownAfter = use.getParent().isVisible();
    project.undo();

    assertThat(shownBefore).as("the banner tells the keys are another game's").isTrue();
    assertThat(renamed)
        .as("every key now starts with this game's name")
        .allMatch(k -> k.startsWith("Minimap."));
    assertThat(shownAfter).as("then the banner goes").isFalse();
    assertThat(project.texts().entries(Kind.OBJECTIVES))
        .as("one undo brings all keys back")
        .allMatch(e -> e.key().startsWith(FOREIGN));
  }

  @DisplayName("A chosen message shows a preview of its HTML, a chosen objective none")
  @Test
  void previewForHtmlOnly() throws Exception {
    final TextsEditor texts = new TextsEditor(givenMinimapWithTexts());
    final JTabbedPane tabs = all(texts.page(), JTabbedPane.class).getFirst();

    choose(texts, 0, FOREIGN + "Russians;objectiveAttachment_Russians_1_Hold_Moscow");
    final boolean objectivePreview = !all(texts.page(), GameNotesView.class).isEmpty();
    tabs.setSelectedIndex(1);
    choose(texts, 1, "Welcome");
    final boolean messagePreview = !all(texts.page(), GameNotesView.class).isEmpty();

    assertThat(objectivePreview).as("objectives are plain text: no preview").isFalse();
    assertThat(messagePreview).as("a message is HTML: previewed").isTrue();
  }

  @DisplayName("A group shows its conditions in words, its raw list of names only on request")
  @Test
  void groupReadsInWords() throws Exception {
    // given
    final TextsEditor texts = new TextsEditor(givenMinimapWithTexts());
    choose(texts, 0, FOREIGN + "TABLEGROUP.01;Russians");
    final XmlTextArea raw = all(texts.page(), XmlTextArea.class).getFirst();

    // when
    final boolean rawAtFirst = isShowing(raw, texts.page());
    final List<Object> listed = all(texts.page(), JList.class).stream()
        .flatMap(l -> IntStream.range(0, l.getModel().getSize())
            .mapToObj(i -> l.getModel().getElementAt(i)))
        .toList();
    button(texts, "▸ Edit the list as text").doClick();

    // then
    assertThat(rawAtFirst).as("the names separated by ; are not the first thing shown").isFalse();
    assertThat(listed).as("the group's condition in words")
        .contains("Russians · Hold Moscow");
    assertThat(isShowing(raw, texts.page())).as("still editable as text").isTrue();
    assertThat(raw.getText()).as("the value as the file has it")
        .isEqualTo("objectiveAttachment_Russians_1_Hold_Moscow");
  }

  /** Visible up to the page, as a closed More is not. */
  private static boolean isShowing(final Component part, final Container page) {
    for (Component c = part; c != page; c = c.getParent()) {
      if (!c.isVisible()) {
        return false;
      }
    }
    return true;
  }

  @DisplayName("A tab without entries shows one empty state, the list's, not a second beside it")
  @Test
  void oneEmptyState() throws Exception {
    final TextsEditor texts = new TextsEditor(givenMinimapWithTexts());
    final JTabbedPane tabs = all(texts.page(), JTabbedPane.class).getFirst();

    tabs.setSelectedIndex(4);

    assertThat(all(texts.page(), JLabel.class).stream().map(JLabel::getText))
        .as("the list says there are none and offers Add; the right side says nothing")
        .contains("No user action texts yet")
        .doesNotContain(Kind.ACTIONS.getTitle());
  }

  @DisplayName("A file that does not read offers Open in Files, where it is fixed")
  @Test
  void unreadableOpensFiles() throws Exception {
    givenMinimapWithTexts();
    Files.delete(folder.resolve("objectives.properties"));
    // a folder of the file's name: there, but it does not read
    Files.createDirectory(folder.resolve("objectives.properties"));
    final List<String> opened = new ArrayList<>();
    final TextsEditor texts =
        new TextsEditor(MapProject.open(folder.resolve("minimap.xml")), opened::add);

    button(texts, "Open in Files").doClick();

    assertThat(opened).as("the shell shows Files").containsExactly("Files");
  }

  @SuppressWarnings("unchecked")
  private static void choose(final TextsEditor texts, final int list, final String key) {
    final SearchList searchList = all(texts.page(), SearchList.class).get(list);
    final JList<String> rows = all(searchList, JList.class).getFirst();
    rows.setSelectedValue(key, false);
  }

  private static AbstractButton button(final TextsEditor texts, final String text) {
    return all(texts.page(), AbstractButton.class).stream()
        .filter(b -> text.equals(b.getText()))
        .findFirst()
        .orElseThrow();
  }

  private static <T> List<T> all(final Component root, final Class<T> type) {
    return Stream.concat(
            Stream.of(root).filter(type::isInstance).map(type::cast),
            root instanceof Container container
                ? Stream.of(container.getComponents()).flatMap(c -> all(c, type).stream())
                : Stream.empty())
        .toList();
  }
}
