package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.Serial;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ToolTipManager;
import javax.swing.border.Border;
import org.triplea.swing.SwingComponents;

/**
 * The list column of an editor: a count, a search field and the names, each shown as a title with
 * a smaller line below it, eg the owner of a territory. Choosing one tells the editor its name.
 */
final class SearchList extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  // narrow lists cut long names: the column asks for room for the longest, up to a limit
  private static final int MIN_WIDTH = 240;
  private static final int MAX_WIDTH = 360;
  private static final int TWO_LINE_HEIGHT = 44;
  private static final int ONE_LINE_HEIGHT = 28;
  private static final int MIN_PREFIX = 8;
  private static final String LIST = "list";
  private static final String NO_MATCH = "noMatch";
  private static final String EMPTY = "empty";

  private final JTextField search = new JTextField();
  private DefaultListModel<String> shown = new DefaultListModel<>();
  private final JList<String> list;
  private final transient Supplier<List<String>> names;
  private final transient Function<String, String> title;
  private final transient Optional<Function<String, String>> detail;
  private final Optional<String> noun;
  private final Optional<String> nounPlural;
  private final JLabel count = Theme.newSecondaryLabel("");
  private final JPanel header = new JPanel(new BorderLayout(Theme.GAP_S, 0));
  private final JLabel prefixLine = Theme.newSecondaryLabel("");
  private final JPanel middle = new JPanel(new CardLayout());
  private final JLabel noMatch = new JLabel();
  private final JPanel emptyCard = new JPanel(new BorderLayout());
  private boolean stripping;
  private Optional<String> prefix = Optional.empty();
  private boolean hasEmpty;
  private boolean refilling;
  private int wanted = MIN_WIDTH;
  // the names as read by the last refresh; typing searches these
  private List<String> all = List.of();
  // each name's lines in lower case, made once per refresh, not on every key typed
  private final transient Map<String, String> searchTexts = new HashMap<>();

  /**
   * Rows of two lines.
   *
   * @param noun eg "sound", for the count and the search
   * @param names the keys, asked again on every {@link #refresh}; the editor is told the key
   * @param title the first line of a key's row, in plain words
   * @param detail the line below it
   */
  SearchList(
      final String noun,
      final String nounPlural,
      final Supplier<List<String>> names,
      final Function<String, String> title,
      final Function<String, String> detail,
      final Consumer<String> onChoose) {
    this(Optional.of(noun), Optional.of(nounPlural), names, title, Optional.of(detail), onChoose);
  }

  /** Rows of one line. */
  SearchList(
      final String noun,
      final String nounPlural,
      final Supplier<List<String>> names,
      final Function<String, String> title,
      final Consumer<String> onChoose) {
    this(Optional.of(noun), Optional.of(nounPlural), names, title, Optional.empty(), onChoose);
  }

  private SearchList(
      final Optional<String> noun,
      final Optional<String> nounPlural,
      final Supplier<List<String>> names,
      final Function<String, String> title,
      final Optional<Function<String, String>> detail,
      final Consumer<String> onChoose) {
    super(new BorderLayout(0, Theme.GAP_S));
    this.noun = noun;
    this.nounPlural = nounPlural;
    this.names = names;
    this.title = title;
    this.detail = detail;
    list = new WideList();
    ToolTipManager.sharedInstance().registerComponent(list);
    setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Theme.GAP_S));
    nounPlural.ifPresent(
        plural ->
            search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search " + plural));
    search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    OnTyping.attach(search, this::refill);
    list.setModel(shown);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new Row());
    list.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && !refilling && list.getSelectedValue() != null) {
            onChoose.accept(list.getSelectedValue());
          }
        });
    final HoverTracker hover = new HoverTracker();
    list.addMouseListener(hover);
    list.addMouseMotionListener(hover);

    header.add(count, BorderLayout.WEST);
    header.setVisible(noun.isPresent());
    final JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    for (final JComponent part : List.of(header, search, prefixLine)) {
      part.setAlignmentX(LEFT_ALIGNMENT);
      top.add(part);
    }
    header.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_S, 0));
    prefixLine.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_XS, 0, 0, 0));
    prefixLine.setVisible(false);

    middle.add(SwingComponents.newJScrollPane(list), LIST);
    middle.add(newNoMatchCard(), NO_MATCH);
    middle.add(emptyCard, EMPTY);
    add(top, BorderLayout.NORTH);
    add(middle, BorderLayout.CENTER);
    refresh();
  }

  private JComponent newNoMatchCard() {
    final JButton clear = new JButton("Clear search");
    Theme.setTertiaryButtonStyle(clear);
    clear.addActionListener(e -> search.setText(""));
    final JPanel lines = new JPanel();
    lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
    noMatch.setAlignmentX(CENTER_ALIGNMENT);
    clear.setAlignmentX(CENTER_ALIGNMENT);
    lines.add(noMatch);
    lines.add(clear);
    final JPanel card = new JPanel(new GridBagLayout());
    card.add(lines);
    return card;
  }

  /**
   * Shows the start all names share once above the list instead of in each row, eg a game's name
   * before each objective's key. It is cut from whichever line of a row starts with it.
   */
  SearchList stripSharedPrefix() {
    stripping = true;
    refresh();
    return this;
  }

  /** "+ label" at the right of the count. */
  SearchList withAdd(final String label, final String toolTip, final Runnable add) {
    header.add(Theme.newAddButton(label, toolTip, add), BorderLayout.EAST);
    header.setVisible(true);
    return this;
  }

  /** What shows instead of the list while there are no names, eg how to add the first. */
  SearchList withEmpty(final JComponent emptyState) {
    emptyCard.removeAll();
    emptyCard.add(emptyState, BorderLayout.CENTER);
    hasEmpty = true;
    refresh();
    return this;
  }

  /** The start cut from the rows, if {@link #stripSharedPrefix} found one. */
  Optional<String> strippedPrefix() {
    return prefix;
  }

  /**
   * Reads the names again, keeping the search and the chosen one. A list of thousands, eg a big
   * map's triggers, stays quick: the names come in one new model, not one event each, and rows are
   * of a fixed height instead of each being measured.
   */
  void refresh() {
    searchTexts.clear();
    all = names.get();
    // measured here, not on every key typed: each asks every row's title
    prefix = stripping ? sharedPrefix(all) : Optional.empty();
    fitWidthTo(all);
    prefixLine.setVisible(prefix.isPresent());
    prefix.ifPresent(
        p -> {
          prefixLine.setText(
              cutMiddle(
                  "Keys start with " + p,
                  prefixLine.getFontMetrics(prefixLine.getFont()),
                  wanted - Theme.GAP_XL));
          prefixLine.setToolTipText(p);
        });
    refill();
  }

  private void refill() {
    final String filter = search.getText().strip().toLowerCase(Locale.ROOT);
    final List<String> filtered =
        filter.isEmpty() ? all : all.stream().filter(name -> matches(name, filter)).toList();
    showCount(filtered.size(), all.size());
    showCard(filter, all.isEmpty(), filtered.isEmpty());
    if (filtered.equals(Collections.list(shown.elements()))) {
      // the names are the same, but their lines may not be
      list.repaint();
      return;
    }
    final String chosen = list.getSelectedValue();
    final DefaultListModel<String> refilled = new DefaultListModel<>();
    refilled.addAll(filtered);
    refilling = true;
    if (!filtered.isEmpty()) {
      list.setPrototypeCellValue(filtered.getFirst());
    }
    list.setFixedCellHeight(
        UIScale.scale(detail.isPresent() ? TWO_LINE_HEIGHT : ONE_LINE_HEIGHT));
    shown = refilled;
    list.setModel(refilled);
    list.setSelectedValue(chosen, false);
    refilling = false;
  }

  private boolean matches(final String name, final String filter) {
    if (name.toLowerCase(Locale.ROOT).contains(filter)) {
      return true;
    }
    return searchTexts.computeIfAbsent(name, this::searchText).contains(filter);
  }

  private String searchText(final String name) {
    // a line break, which no search has, so a search never spans both lines
    return (title.apply(name) + "\n" + detail.map(d -> d.apply(name)).orElse(""))
        .toLowerCase(Locale.ROOT);
  }

  private void showCount(final int shownCount, final int allCount) {
    noun.ifPresent(
        n -> count.setText(SearchList.count(shownCount, allCount, n, nounPlural.orElseThrow())));
  }

  private void showCard(final String filter, final boolean none, final boolean noneShown) {
    final String card;
    if (none && hasEmpty) {
      card = EMPTY;
    } else if (noneShown && !filter.isEmpty()) {
      noMatch.setText(
          noun.map(n -> "No " + n + " matches").orElse("Nothing matches")
              + " “"
              + search.getText().strip()
              + "”");
      card = NO_MATCH;
    } else {
      card = LIST;
    }
    ((CardLayout) middle.getLayout()).show(middle, card);
  }

  private void fitWidthTo(final List<String> all) {
    final FontMetrics bold = list.getFontMetrics(list.getFont().deriveFont(Font.BOLD));
    // the row's padding, the column's border and the scroll bar
    final int longest =
        all.stream().mapToInt(name -> bold.stringWidth(lineOf(title, name))).max().orElse(0) + 48;
    final int width = Math.clamp(longest, MIN_WIDTH, MAX_WIDTH);
    if (width != wanted) {
      wanted = width;
      revalidate();
    }
  }

  // the line without the shared start, whichever line of the row shows the key
  private String lineOf(final Function<String, String> line, final String name) {
    final String text = line.apply(name);
    return prefix.filter(text::startsWith).map(p -> text.substring(p.length())).orElse(text);
  }

  @Override
  public Dimension getPreferredSize() {
    // a size the tool set, eg for a dialog, wins
    return isPreferredSizeSet() ? super.getPreferredSize() : new Dimension(wanted, 0);
  }

  /** Marks the name as chosen without telling the editor, eg after a click on the map. */
  void choose(@Nullable final String name) {
    refilling = true;
    if (name != null && !shown.contains(name)) {
      search.setText("");
    }
    list.setSelectedValue(name, true);
    refilling = false;
  }

  /**
   * The start all names share, cut back to its last ".", ";" or "_"; only when it is long enough
   * to be worth showing once, there are two names or more, and no name is only the start.
   */
  static Optional<String> sharedPrefix(final List<String> names) {
    if (names.size() < 2) {
      return Optional.empty();
    }
    final String first = names.getFirst();
    int length = first.length();
    for (final String name : names) {
      length = Math.min(length, name.length());
      int same = 0;
      while (same < length && first.charAt(same) == name.charAt(same)) {
        same++;
      }
      length = same;
    }
    int cut = length;
    while (cut > 0 && ".;_".indexOf(first.charAt(cut - 1)) < 0) {
      cut--;
    }
    final String prefix = first.substring(0, cut);
    // names that all share it stay different without it; only a name that is just it would not
    final boolean leavesEmpty = names.stream().anyMatch(prefix::equals);
    return cut < MIN_PREFIX || leavesEmpty ? Optional.empty() : Optional.of(prefix);
  }

  /**
   * The text cut in the middle to fit the width: the first 40 % and the last 60 % of what fits,
   * as names differ at their ends.
   */
  static String cutMiddle(final String text, final FontMetrics metrics, final int width) {
    if (metrics.stringWidth(text) <= width) {
      return text;
    }
    int low = 0;
    int high = text.length() - 1;
    // the most characters kept that still fit
    while (low < high) {
      final int kept = (low + high + 1) / 2;
      if (metrics.stringWidth(cut(text, kept)) <= width) {
        low = kept;
      } else {
        high = kept - 1;
      }
    }
    return cut(text, low);
  }

  private static String cut(final String text, final int kept) {
    final int head = kept * 2 / 5;
    return text.substring(0, head) + "…" + text.substring(text.length() - (kept - head));
  }

  /** "1 sound", "64 sounds", or while searching "12 of 64". */
  static String count(final int shown, final int all, final String noun, final String nounPlural) {
    if (shown != all) {
      return shown + " of " + all;
    }
    return all + " " + (all == 1 ? noun : nounPlural);
  }

  /**
   * A list as wide as its column, so long names are cut instead of scrolled sideways; the tool tip
   * of a row tells it whole.
   */
  private final class WideList extends JList<String> {
    @Serial private static final long serialVersionUID = 1L;
    private int hovered = -1;

    @Override
    public @Nullable String getToolTipText(final MouseEvent event) {
      final int index = locationToIndex(event.getPoint());
      if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) {
        return null;
      }
      final String name = getModel().getElementAt(index);
      final String first = title.apply(name);
      final String line = detail.map(d -> d.apply(name)).orElse("");
      return "<html><b>"
          + escape(first)
          + "</b>"
          + (line.isEmpty() ? "" : "<br>" + escape(line))
          + (first.equals(name) || line.equals(name) ? "" : "<br>" + escape(name))
          + "</html>";
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }
  }

  private final class HoverTracker extends MouseAdapter {
    @Override
    public void mouseMoved(final MouseEvent e) {
      hover(list.locationToIndex(e.getPoint()));
    }

    @Override
    public void mouseExited(final MouseEvent e) {
      hover(-1);
    }

    private void hover(final int index) {
      final WideList wide = (WideList) list;
      if (wide.hovered != index) {
        repaintRow(wide.hovered);
        wide.hovered = index;
        repaintRow(index);
      }
    }

    private void repaintRow(final int index) {
      Optional.ofNullable(index < 0 ? null : list.getCellBounds(index, index))
          .ifPresent(list::repaint);
    }
  }

  /** A row: the title, the line below it smaller; the chosen one with a bar in the accent color. */
  private final class Row extends JPanel implements ListCellRenderer<String> {
    @Serial private static final long serialVersionUID = 1L;
    private static final int PAD_Y = 6;
    private static final int PAD_X = 12;
    private static final int BAR = 3;
    // cut lines by text, font and width, as 35,000 rows repaint while scrolling
    private final transient Map<Cut, String> cuts = new HashMap<>();
    private final JLabel first = new JLabel();
    private final JLabel second = new JLabel();
    // made once, as the renderer runs for every row painted
    private final Border plain = BorderFactory.createEmptyBorder(PAD_Y, PAD_X, PAD_Y, PAD_X);
    // the bar takes the place of padding, so the text stays where it is
    private final Border chosen =
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, BAR, 0, 0, Theme.getAccentColor()),
            BorderFactory.createEmptyBorder(PAD_Y, PAD_X - BAR, PAD_Y, PAD_X));

    private record Cut(String text, Font font, int width) {}

    Row() {
      setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
      first.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
      second.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
      add(first);
      add(second);
    }

    @Override
    public Component getListCellRendererComponent(
        final JList<? extends String> owner,
        final String name,
        final int index,
        final boolean isSelected,
        final boolean cellHasFocus) {
      final int room = owner.getWidth() - 2 * PAD_X;
      first.setText(fitted(lineOf(title, name), first.getFont(), room));
      second.setVisible(detail.isPresent());
      detail.ifPresent(d -> second.setText(fitted(lineOf(d, name), second.getFont(), room)));
      final boolean hovered = ((WideList) owner).hovered == index;
      setBackground(
          isSelected
              ? Theme.getSelectionColor()
              : hovered ? Theme.getHoverColor() : owner.getBackground());
      first.setForeground(isSelected ? owner.getSelectionForeground() : owner.getForeground());
      second.setForeground(
          isSelected ? Theme.getSelectionSecondaryTextColor() : Theme.getSecondaryTextColor());
      setBorder(isSelected ? chosen : plain);
      return this;
    }

    private String fitted(final String text, final Font font, final int room) {
      if (room <= 0) {
        return text;
      }
      if (cuts.size() > 100_000) {
        cuts.clear();
      }
      return cuts.computeIfAbsent(
          new Cut(text, font, room), c -> cutMiddle(text, getFontMetrics(font), room));
    }
  }

  /** The text safe to put into a label's html. */
  static String escape(final String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
