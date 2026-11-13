package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.Action;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SearchListTest {
  private static final int MANY = 35_000;

  private static List<String> givenNames(final int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> "triggerAttachment" + i + " · Russians")
        .toList();
  }

  private static JList<?> listIn(final Container container) {
    return components(container)
        .filter(JList.class::isInstance)
        .map(JList.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static Stream<Component> components(final Container container) {
    return Stream.of(container.getComponents())
        .flatMap(
            c ->
                Stream.concat(
                    Stream.of(c),
                    c instanceof Container inner ? components(inner) : Stream.empty()));
  }

  @DisplayName("A list of thousands refreshes with one chosen in a moment, keeping it")
  @Test
  void refreshesManyQuickly() {
    final List<String> names = new ArrayList<>(givenNames(MANY));
    final List<String> chosen = new ArrayList<>();
    final SearchList searchList =
        new SearchList(
            "attachment",
            "attachments",
            () -> List.copyOf(names),
            n -> n,
            n -> "TriggerAttachment",
            chosen::add);
    searchList.setSize(300, 600);
    searchList.validate();
    final String picked = names.get(MANY / 2);
    searchList.choose(picked);

    final long start = System.nanoTime();
    names.add("triggerAttachmentNew · Russians");
    searchList.refresh();
    searchList.refresh();
    searchList.validate();
    final long millis = (System.nanoTime() - start) / 1_000_000;

    assertThat(millis).as("two refreshes and a layout of %s names", MANY).isLessThan(3_000);
    assertThat(Optional.ofNullable(listIn(searchList).getSelectedValue()))
        .as("the chosen one stays chosen")
        .contains(picked);
    assertThat(listIn(searchList).getModel().getSize())
        .as("the new name is listed")
        .isEqualTo(MANY + 1);
    assertThat(chosen).as("a refresh does not choose for the map maker").isEmpty();
  }

  @DisplayName("Rows are 44 px high with a line below the name, even when the first has none")
  @Test
  void rowsFitDetailsAfterAnEmptyOne() {
    final SearchList searchList =
        new SearchList(
            "nation",
            "nations",
            () -> List.of("Neutrals", "Germans"),
            n -> n,
            n -> n.equals("Germans") ? "Axis" : "",
            n -> {});
    searchList.refresh();

    // the list SearchList makes holds names
    @SuppressWarnings("unchecked")
    final JList<String> list = (JList<String>) listIn(searchList);
    final int withDetail =
        list.getCellRenderer()
            .getListCellRendererComponent(list, "Germans", 1, false, false)
            .getPreferredSize()
            .height;

    assertThat(list.getFixedCellHeight())
        .as("every row the fixed two-line height, room for the line below")
        .isEqualTo(44)
        .isGreaterThanOrEqualTo(withDetail);
  }

  @DisplayName("A font with taller lines makes the rows higher, so the line below is not cut")
  @Test
  void rowsFitTallFonts() {
    // given: labels in a font far taller than the usual
    final Object before = UIManager.get("Label.font");
    UIManager.put("Label.font", new FontUIResource(Font.SANS_SERIF, Font.PLAIN, 24));
    try {
      final SearchList searchList =
          new SearchList("nation", "nations", () -> List.of("Germans"), n -> n, n -> "Axis", n -> {});

      // when
      @SuppressWarnings("unchecked")
      final JList<String> list = (JList<String>) listIn(searchList);
      final int needed =
          list.getCellRenderer()
              .getListCellRendererComponent(list, "Germans", 0, false, false)
              .getPreferredSize()
              .height;

      // then
      assertThat(needed).as("the font asks for more than the usual 44 px").isGreaterThan(44);
      assertThat(list.getFixedCellHeight())
          .as("the fixed row height holds both lines")
          .isGreaterThanOrEqualTo(needed);
    } finally {
      UIManager.put("Label.font", before);
    }
  }

  @DisplayName("Rows of one line are 28 px high")
  @Test
  void oneLineRows() {
    final SearchList searchList =
        new SearchList("sound", "sounds", () -> List.of("a", "b"), n -> n, n -> {});

    assertThat(listIn(searchList).getFixedCellHeight()).as("no room for a second line").isEqualTo(28);
  }

  @DisplayName("A shared start is cut back to its last separator, only when long and all differ")
  @Test
  void sharedPrefixRules() {
    final String g40 = "World_War_II_Global_1940_Alpha_3.";

    final Optional<String> game =
        SearchList.sharedPrefix(List.of(g40 + "Germans;a", g40 + "Italians;b"));
    final Optional<String> separator =
        SearchList.sharedPrefix(List.of("Germans_1;a", "Germans_1;b"));
    final Optional<String> tooShort = SearchList.sharedPrefix(List.of("abc.x", "abc.y"));
    final Optional<String> oneRow = SearchList.sharedPrefix(List.of("Germans_1;a"));
    final Optional<String> leavesEmpty =
        SearchList.sharedPrefix(List.of("Germans_1;", "Germans_1;b"));

    assertThat(game).as("G40's objective keys lose the game's name").contains(g40);
    assertThat(separator).as("cut back to the last separator").contains("Germans_1;");
    assertThat(tooShort).as("too short to be worth it").isEmpty();
    assertThat(oneRow).as("one row keeps its name").isEmpty();
    assertThat(leavesEmpty).as("no row may end up empty").isEmpty();
  }

  @DisplayName("The count reads in words, and as part of all while searching")
  @Test
  void countInWords() {
    final String noun = "sound";
    final String plural = "sounds";

    final String one = SearchList.count(1, 1, noun, plural);
    final String many = SearchList.count(64, 64, noun, plural);
    final String searching = SearchList.count(12, 64, noun, plural);

    assertThat(one).as("one").isEqualTo("1 sound");
    assertThat(many).as("many").isEqualTo("64 sounds");
    assertThat(searching).as("searching").isEqualTo("12 of 64");
  }

  @DisplayName("Typing measures nothing: the width and the shared start come from a refresh")
  @Test
  void typingMeasuresNoTitles() {
    final AtomicInteger asked = new AtomicInteger();
    final SearchList searchList = givenListCountingTitles(asked);
    final JTextField search = textFieldIn(searchList);

    for (final String typed : List.of("t", "tr", "tri", "trig")) {
      search.setText(typed);
    }

    assertThat(asked)
        .as("each title asked for the width once and for the search once, not per key")
        .hasValueLessThan(3 * MANY);
    assertThat(listIn(searchList).getModel().getSize()).as("all found by name").isEqualTo(MANY);
  }

  private static SearchList givenGrouped(final List<String> chosen) {
    final SearchList searchList =
        new SearchList(
                "trigger",
                "triggers",
                () -> List.of("Blitz", "Convoy", "Lend lease", "Winter"),
                n -> n,
                n -> "Gives PUs",
                chosen::add)
            .withIds(n -> "triggerAttachment_" + n.replace(' ', '_'));
    searchList.groupBy(Optional.of(n -> n.startsWith("W") ? "Russians" : "Germans"));
    return searchList;
  }

  private static void click(final JList<?> list, final int row) {
    final Rectangle cell = list.getCellBounds(row, row);
    list.setSelectedIndex(row);
    final MouseEvent click =
        new MouseEvent(
            list, MouseEvent.MOUSE_CLICKED, 0, 0, cell.x + 5, cell.y + 5, 1, false, MouseEvent.BUTTON1);
    Stream.of(list.getMouseListeners()).forEach(listener -> listener.mouseClicked(click));
  }

  @DisplayName("Groups show under header rows, ▾ Group (n); a click folds one, a search opens all")
  @Test
  void groupsUnderFoldableHeaders() {
    final List<String> chosen = new ArrayList<>();
    final SearchList searchList = givenGrouped(chosen);
    searchList.setSize(300, 400);
    final JList<?> list = listIn(searchList);
    list.setSize(300, 400);
    final List<String> open = searchList.rows();

    click(list, 0);
    final List<String> folded = searchList.rows();
    textFieldIn(searchList).setText("convoy");
    final List<String> searched = searchList.rows();
    textFieldIn(searchList).setText("");
    searchList.choose("Blitz");

    assertThat(open)
        .as("each group's names under its header, in the order the groups first come")
        .containsExactly("▾ Germans (3)", "Blitz", "Convoy", "Lend lease", "▾ Russians (1)", "Winter");
    assertThat(folded)
        .as("a click on a header folds its group; the count stays")
        .containsExactly("▸ Germans (3)", "▾ Russians (1)", "Winter");
    assertThat(searched)
        .as("a search shows what it finds, also in a folded group")
        .containsExactly("▾ Germans (1)", "Convoy");
    assertThat(searchList.rows())
        .as("choosing a name of a folded group opens the group")
        .startsWith("▾ Germans (3)", "Blitz");
    assertThat(chosen).as("a header is no name: the editor is told of none").isEmpty();
  }

  @DisplayName("Space folds the header chosen with the keys; it shows as chosen, reads as its"
      + " group, and stays folded when the names are read again")
  @Test
  void foldsWithTheKeyboard() {
    final SearchList searchList = givenGrouped(new ArrayList<>());
    @SuppressWarnings("unchecked")
    final JList<String> list = (JList<String>) listIn(searchList);

    list.setSelectedIndex(0);
    list.getActionMap()
        .get(list.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)))
        .actionPerformed(null);
    searchList.refresh();
    final Component header =
        list.getCellRenderer()
            .getListCellRendererComponent(list, list.getModel().getElementAt(0), 0, true, true);

    assertThat(searchList.rows())
        .as("folded by the key, and still after a refresh")
        .containsExactly("▸ Germans (3)", "▾ Russians (1)", "Winter");
    assertThat(list.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)))
        .as("Enter does the same")
        .isEqualTo(list.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)));
    assertThat(list.getSelectedIndex()).as("the header stays chosen, for the next key").isZero();
    final Action fold =
        list.getActionMap().get(list.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
    assertThat(fold.accept(list)).as("on a header the key folds").isTrue();
    list.setSelectedIndex(2);
    assertThat(fold.accept(list))
        .as("on a name Enter is not taken: it goes on, eg to a dialog's verb")
        .isFalse();
    list.setSelectedIndex(0);
    assertThat(header.getBackground()).as("drawn as chosen").isEqualTo(Theme.getSelectionColor());
    assertThat(header.getAccessibleContext().getAccessibleName())
        .as("read as the group and its state, not the model's value")
        .isEqualTo("Germans, 3, collapsed");
  }

  @DisplayName("A name is found by its id in the map's files, which its tool tip tells")
  @Test
  void foundByIdAndToldInToolTip() {
    final SearchList searchList = givenGrouped(new ArrayList<>());
    searchList.setSize(300, 400);
    final JList<?> list = listIn(searchList);
    list.setSize(300, 400);
    final Rectangle row = list.getCellBounds(3, 3);

    final String tip =
        list.getToolTipText(
            new MouseEvent(list, MouseEvent.MOUSE_MOVED, 0, 0, row.x + 5, row.y + 5, 0, false));
    textFieldIn(searchList).setText("attachment_lend");

    assertThat(tip).as("the plain name, then its id").contains("Lend lease", "id triggerAttachment_Lend_lease");
    assertThat(searchList.rows())
        .as("found by the id, which no line of the row shows")
        .containsExactly("▾ Germans (1)", "Lend lease");
  }

  @DisplayName("Typing asks each row's lines once per refresh, not on every key")
  @Test
  void linesAskedOncePerRefresh() {
    final AtomicInteger asked = new AtomicInteger();
    final SearchList searchList =
        new SearchList(
            "attachment",
            "attachments",
            () -> givenNames(MANY),
            n -> "Trigger",
            n -> {
              asked.incrementAndGet();
              return "Russians";
            },
            n -> {});
    final JTextField search = textFieldIn(searchList);

    for (final String typed : List.of("x", "xy", "xyz", "xyzw")) {
      search.setText(typed);
    }

    assertThat(asked).as("each line about once, for 4 keys typed").hasValueLessThan(2 * MANY);
  }

  private static SearchList givenListCountingTitles(final AtomicInteger asked) {
    return new SearchList(
        "attachment",
        "attachments",
        () -> givenNames(MANY),
        n -> {
          asked.incrementAndGet();
          return n;
        },
        n -> {});
  }

  @DisplayName("A long text is cut in the middle, keeping more of its end")
  @Test
  void cutsInTheMiddle() {
    final FontMetrics metrics = new JLabel().getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    final String text = "Germans;objectiveAttachment_1_Trade_with_Russia";
    final int width = metrics.stringWidth(text) / 2;

    final String cut = SearchList.cutMiddle(text, metrics, width);

    assertThat(metrics.stringWidth(cut)).as("fits").isLessThanOrEqualTo(width);
    assertThat(cut).as("start and end kept, the middle cut").startsWith("Germ").contains("…").endsWith("Russia");
    assertThat(SearchList.cutMiddle("short", metrics, width)).as("what fits stays").isEqualTo("short");
  }

  @DisplayName("The search finds a row by its title, and the chosen key goes to the editor")
  @Test
  void searchMatchesTitle() {
    final List<String> chosen = new ArrayList<>();
    final SearchList searchList =
        new SearchList(
            "objective",
            "objectives",
            () -> List.of("key1", "key2"),
            n -> n.equals("key2") ? "Trade with Russia" : "Hold Paris",
            n -> "detail",
            chosen::add);
    final JList<?> list = listIn(searchList);

    textFieldIn(searchList).setText("russia");
    list.setSelectedIndex(0);

    assertThat(list.getModel().getSize()).as("only the row with that title").isEqualTo(1);
    assertThat(chosen).as("the editor gets the key").containsExactly("key2");
  }

  @DisplayName("A shared start is cut from the line that shows the key, and told to the editor")
  @Test
  void stripsFromTheKeyLine() {
    final String g40 = "World_War_II_Global_1940_Alpha_3.";
    final SearchList searchList =
        new SearchList(
                "objective",
                "objectives",
                () -> List.of(g40 + "Germans;a", g40 + "Italians;b"),
                n -> "Plain words",
                n -> n,
                n -> {})
            .stripSharedPrefix();
    @SuppressWarnings("unchecked")
    final JList<String> list = (JList<String>) listIn(searchList);
    list.setSize(300, 400);

    final Component row =
        list.getCellRenderer().getListCellRendererComponent(list, g40 + "Germans;a", 0, false, false);

    assertThat(searchList.strippedPrefix()).as("the editor may cut it elsewhere").contains(g40);
    assertThat(labelsIn((Container) row))
        .as("the key line without the game's name")
        .contains("Germans;a")
        .doesNotContain(g40 + "Germans;a");
  }

  private static JTextField textFieldIn(final Container container) {
    return components(container)
        .filter(JTextField.class::isInstance)
        .map(JTextField.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> labelsIn(final Container container) {
    return components(container)
        .filter(JLabel.class::isInstance)
        .map(c -> ((JLabel) c).getText())
        .toList();
  }

  @DisplayName("A row's tool tip tells its whole name and line; long names widen the column")
  @Test
  void toolTipsAndWidthForLongNames() {
    final String name = "conditionAttachment_Germans_Hold_Every_Capital_Of_The_Allies";
    final SearchList searchList =
        new SearchList(
            "condition",
            "conditions",
            () -> List.of(name, "short"),
            n -> n,
            n -> "Germans, round 3",
            n -> {});
    final JList<?> list = listIn(searchList);
    list.setSize(200, 400);
    final Rectangle row = list.getCellBounds(0, 0);

    final String tip =
        list.getToolTipText(
            new MouseEvent(list, MouseEvent.MOUSE_MOVED, 0, 0, row.x + 4, row.y + 4, 0, false));

    assertThat(tip).as("the whole name and its line").contains(name).contains("Germans, round 3");
    assertThat(searchList.getPreferredSize().width)
        .as("wider than the usual column for the long name, up to a limit")
        .isGreaterThan(240)
        .isLessThanOrEqualTo(360);
  }
}
