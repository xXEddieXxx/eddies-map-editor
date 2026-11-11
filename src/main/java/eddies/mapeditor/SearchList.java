package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.MouseEvent;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.triplea.swing.SwingComponents;

/**
 * The list column of an editor: a search field above the names, each with a line below it, eg the
 * owner of a territory. Choosing one tells the editor.
 */
final class SearchList extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  // narrow lists cut long names: the column asks for room for the longest, up to a limit
  private static final int MIN_WIDTH = 240;
  private static final int MAX_WIDTH = 360;

  private final JTextField search = new JTextField();
  private DefaultListModel<String> shown = new DefaultListModel<>();
  private final JList<String> list;
  private final transient Supplier<List<String>> names;
  private boolean refilling;
  private int wanted = MIN_WIDTH;

  /**
   * @param names the names, asked again on every {@link #refresh}
   * @param detail the line below a name
   */
  SearchList(
      final String placeholder,
      final Supplier<List<String>> names,
      final Function<String, String> detail,
      final Consumer<String> onChoose) {
    super(new BorderLayout(0, 8));
    this.names = names;
    list = new WideList(detail);
    ToolTipManager.sharedInstance().registerComponent(list);
    setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
    search.putClientProperty("JTextField.placeholderText", placeholder);
    search.getDocument().addDocumentListener(new Refill());
    list.setModel(shown);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new TwoLineRenderer(detail));
    list.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && !refilling && list.getSelectedValue() != null) {
            onChoose.accept(list.getSelectedValue());
          }
        });
    add(search, BorderLayout.NORTH);
    add(SwingComponents.newJScrollPane(list), BorderLayout.CENTER);
    refresh();
  }

  /**
   * Reads the names again, keeping the search and the chosen one. A list of thousands, eg a big
   * map's attachments, stays quick: the names come in one new model, not one event each, and rows
   * take the height of the first instead of each being measured.
   */
  void refresh() {
    final String filter = search.getText().strip().toLowerCase(Locale.ROOT);
    final List<String> all = names.get();
    fitWidthTo(all);
    final List<String> filtered =
        all.stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).contains(filter))
            .toList();
    if (filtered.equals(Collections.list(shown.elements()))) {
      // the names are the same, but their lines below may not be
      list.repaint();
      return;
    }
    final String chosen = list.getSelectedValue();
    final DefaultListModel<String> refilled = new DefaultListModel<>();
    refilled.addAll(filtered);
    refilling = true;
    list.setPrototypeCellValue(filtered.isEmpty() ? null : filtered.getFirst());
    shown = refilled;
    list.setModel(refilled);
    list.setSelectedValue(chosen, false);
    refilling = false;
  }

  private void fitWidthTo(final List<String> all) {
    final FontMetrics bold = list.getFontMetrics(list.getFont().deriveFont(Font.BOLD));
    // the cell's and column's borders and the scroll bar
    final int longest = all.stream().mapToInt(bold::stringWidth).max().orElse(0) + 48;
    final int width = Math.clamp(longest, MIN_WIDTH, MAX_WIDTH);
    if (width != wanted) {
      wanted = width;
      revalidate();
    }
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
   * A list as wide as its column, so long names are cut instead of scrolled sideways; the tool tip
   * of a row tells it whole.
   */
  private static final class WideList extends JList<String> {
    @Serial private static final long serialVersionUID = 1L;
    private final transient Function<String, String> detail;

    WideList(final Function<String, String> detail) {
      this.detail = detail;
    }

    @Override
    public @Nullable String getToolTipText(final MouseEvent event) {
      final int index = locationToIndex(event.getPoint());
      if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) {
        return null;
      }
      final String name = getModel().getElementAt(index);
      final String line = detail.apply(name);
      return "<html><b>"
          + escape(name)
          + "</b>"
          + (line.isEmpty() ? "" : "<br>" + escape(line))
          + "</html>";
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }
  }

  private final class Refill implements DocumentListener {
    @Override
    public void insertUpdate(final DocumentEvent e) {
      refresh();
    }

    @Override
    public void removeUpdate(final DocumentEvent e) {
      refresh();
    }

    @Override
    public void changedUpdate(final DocumentEvent e) {
      refresh();
    }
  }

  /** A name in the list's text, the line below it smaller and grey. */
  private static final class TwoLineRenderer extends DefaultListCellRenderer {
    @Serial private static final long serialVersionUID = 1L;
    private final transient Function<String, String> detail;

    TwoLineRenderer(final Function<String, String> detail) {
      this.detail = detail;
    }

    @Override
    public Component getListCellRendererComponent(
        final JList<?> list,
        final Object value,
        final int index,
        final boolean isSelected,
        final boolean cellHasFocus) {
      final String name = Objects.toString(value);
      final String line = detail.apply(name);
      // each line unwrapped, cut at the list's edge, as all rows take the first's height
      final String text =
          "<html><nobr><b>"
              + escape(name)
              + "</b>"
              // always a second line, empty or not, so the first row's height fits every row
              + "<br><font color='"
              + Theme.convertColorToHex(Theme.getSecondaryTextColor())
              + "'>"
              + (line.isEmpty() ? "&nbsp;" : escape(line))
              + "</font></nobr></html>";
      super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
      setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
      return this;
    }
  }

  /** The text safe to put into a label's html. */
  static String escape(final String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
