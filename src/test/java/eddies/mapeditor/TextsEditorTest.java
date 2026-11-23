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
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
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

  @DisplayName("An objective's text says what the objective pays, with the way to Objectives")
  @Test
  void objectiveTextSaysWhatItPays() throws Exception {
    // given: G40 with a text for an objective and one for a group
    final Game game = GameRulesXmlTest.givenG40();
    final String trade = MapTexts.objectiveKey(game, "Germans", TRADE);
    final String group = MapTexts.objectivesPrefix(game) + "TABLEGROUP.01;Germans";
    final MapProject project = givenG40WithTexts(trade + "=Trade\n" + group + "=" + TRADE + "\n");
    final TextsEditor texts = new TextsEditor(project);

    // when
    choose(texts, 0, trade);
    final boolean linkForObjective = showsObjectivesLink(texts);
    choose(texts, 0, group);
    final boolean linkForGroup = showsObjectivesLink(texts);

    // then
    assertThat(linkForObjective).as("an objective's text links to it").isTrue();
    assertThat(linkForGroup).as("a group's text is no objective's").isFalse();
    assertThat(TextsEditor.pays(givenObjective(game, TRADE)))
        .as("its PUs, every time")
        .isEqualTo("Pays +5 PUs · in");
    assertThat(TextsEditor.pays(givenObjective(game, NOVGOROD)))
        .as("a check that counts each territory")
        .isEqualTo("Pays +5 PUs each · in");
    assertThat(TextsEditor.pays(givenPenalty()))
        .as("a penalty, at most once")
        .isEqualTo("Pays −2 PUs · at most 1 time · in");
  }

  private static final String TRADE = "objectiveAttachment_Germans_1_Trade_with_Russia";
  private static final String NOVGOROD =
      "objectiveAttachment_Germans_2_Control_Novgorod_Or_Volgograd_Or_Russia";

  private MapProject givenG40WithTexts(final String objectives) throws Exception {
    final Path xml = folder.resolve("ww2global40.xml");
    try (InputStream original =
        TextsEditorTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    Files.writeString(folder.resolve("objectives.properties"), objectives);
    return MapProject.open(xml);
  }

  private static boolean showsObjectivesLink(final TextsEditor texts) {
    return all(texts.page(), AbstractButton.class).stream()
        .anyMatch(b -> "Objectives ›".equals(b.getText()));
  }

  private static ObjectiveXml.Objective givenObjective(final Game game, final String name) {
    return ObjectiveXml.objectives(game).stream()
        .filter(o -> o.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static ObjectiveXml.Objective givenPenalty() {
    return new ObjectiveXml.Objective(
        0,
        Attachment.builder()
            .name("objectiveAttachment_Germans_9_Penalty")
            .attachTo("Germans")
            .javaClass("games.strategy.triplea.attachments.RulesAttachment")
            .type("player")
            .options(
                List.of(
                    Option.builder().name("objectiveValue").value("-2").build(),
                    Option.builder().name("uses").value("1").build()))
            .build());
  }

  @DisplayName("The political action texts' tab counts actions, each a row of its own")
  @Test
  void countsActions() throws Exception {
    // given: two actions, one with two texts
    final MapProject project = givenMinimapWithTexts();
    Files.writeString(
        folder.resolve("politicstext.properties"),
        "Truce.BUTTON=Truce\nTruce.DESCRIPTION=Make peace\nWar.BUTTON=War\n");
    final TextsEditor texts =
        new TextsEditor(MapProject.open(project.getXmlFile().orElseThrow()));

    // when
    final JTabbedPane tabs = all(texts.page(), JTabbedPane.class).getFirst();
    final List<String> rows = all(texts.page(), SearchList.class).get(3).rows();

    // then
    assertThat(tabs.getTitleAt(3)).as("two actions, not three texts").contains("Political", ">2<");
    assertThat(rows).as("a row per action").containsExactly("▸ Truce (2)", "▸ War (1)");
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

  @DisplayName("A one-line text shows a line at each <br> and <p>, and is written back as one line")
  @Test
  void showsLinesWritesOne() throws Exception {
    // given: a message of one line, as tool tips and messages are in their files
    final MapProject project = givenMinimapWithTexts();
    final String value = "<b>Hero</b><br>* Max 1<BR/><p style=\"color:green;\"> * Duel</p>";
    project.setText(Kind.NOTIFICATIONS, "Welcome", value);
    final TextsEditor texts = new TextsEditor(project);
    all(texts.page(), JTabbedPane.class).getFirst().setSelectedIndex(1);
    choose(texts, 1, "Welcome");
    final CodeArea field = all(texts.page(), CodeArea.class).getFirst();
    final String shown = field.getText();

    // when: a word typed on the second line
    field.setText(shown.replace("* Max 1", "* Max 2"));
    texts.commit();

    // then
    assertThat(shown)
        .as("a line after each <br>, before each <p")
        .isEqualTo(
            "<b>Hero</b><br>\n* Max 1<BR/>\n<p style=\"color:green;\"> * Duel</p>");
    assertThat(project.texts().value(Kind.NOTIFICATIONS, "Welcome"))
        .as("written as one line, only the typed change")
        .contains(value.replace("* Max 1", "* Max 2"));
    assertThat(TextsEditor.oneLine(TextsEditor.withLines("a<br><p>b</p><br/>c")))
        .as("back as it was, also where <br> and <p> meet")
        .isEqualTo("a<br><p>b</p><br/>c");
    assertThat(TextsEditor.withLines("<p>first</p>"))
        .as("no empty first line")
        .isEqualTo("<p>first</p>");
    assertThat(TextsEditor.withLines("a<pre>b</pre><param x>"))
        .as("<pre> and <param> are no <p>")
        .isEqualTo("a<pre>b</pre><param x>");
    assertThat(TextsEditor.oneLine(TextsEditor.withLines("x<P>y")))
        .as("a <P> in capitals too")
        .isEqualTo("x<P>y");
  }

  @DisplayName("A line typed after a <br> stays, also over the next step, and nothing else changes")
  @Test
  void keepsTypedLinesOverSteps() throws Exception {
    // given
    final MapProject project = givenMinimapWithTexts();
    project.setText(Kind.NOTIFICATIONS, "Welcome", "a<br>b");
    final TextsEditor texts = new TextsEditor(project);
    all(texts.page(), JTabbedPane.class).getFirst().setSelectedIndex(1);
    choose(texts, 1, "Welcome");
    final CodeArea field = all(texts.page(), CodeArea.class).getFirst();

    // when: an empty line typed after the <br>, then another step, then the field left again
    field.setText("a<br>\n\nb");
    texts.commit();
    final String typed = project.texts().value(Kind.NOTIFICATIONS, "Welcome").orElseThrow();
    project.setText(Kind.NOTIFICATIONS, "Other", "x");
    texts.commit();

    // then
    assertThat(typed).as("the line typed is kept, the shown one goes").isEqualTo("a<br>\nb");
    assertThat(project.texts().value(Kind.NOTIFICATIONS, "Welcome"))
        .as("unchanged by the next step and commit")
        .contains("a<br>\nb");
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
    final CodeArea raw = all(texts.page(), CodeArea.class).getFirst();

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
