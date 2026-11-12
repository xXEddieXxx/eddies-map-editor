package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import eddies.mapeditor.MakerIcon.Glyph;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import org.triplea.swing.SwingComponents;

/**
 * The tools by name in their groups down the left, Advanced folded; below a window 1280 px wide
 * only their icons, unless the map maker pins it open.
 */
final class Sidebar extends JPanel {
  @Serial private static final long serialVersionUID = 1L;

  static final int COLLAPSE_BELOW = 1280;
  private static final int WIDTH = 200;
  private static final int COLLAPSED_WIDTH = 48;
  // every tool shows without scrolling: 1440 x 900 with names, 1100 x 700 with icons only
  private static final int ROW_HEIGHT = 28;
  private static final int RAIL_ROW_HEIGHT = 24;
  private static final int ACCENT_BAR = 3;
  // Preferences: "true" pinned open, "false" pinned to icons, absent: by the window's width
  private static final String OPEN_KEY = "open";
  private static final String ADVANCED_KEY = "advancedOpen";

  /** Tools under a name. */
  record Group(String name, List<Editor> tools) {}

  private final transient Preferences preferences;
  private final Map<Editor, JToggleButton> rows = new LinkedHashMap<>();
  private final List<Editor> advancedTools;
  // what shows only with labels, and what only without them
  private final List<JComponent> labelsOnly = new ArrayList<>();
  private final List<JComponent> iconsOnly = new ArrayList<>();
  private final JButton advanced = new JButton();
  private final JButton guides = new JButton();
  private final JButton pin = new JButton();
  private final JPanel rowsPanel = new JPanel();
  private boolean collapsed;
  private boolean advancedOpen;
  private int windowWidth = Integer.MAX_VALUE;

  Sidebar(
      final List<Group> groups,
      final Group advancedGroup,
      final Consumer<Editor> onChoose,
      final JPopupMenu guidesMenu) {
    this(
        groups,
        advancedGroup,
        onChoose,
        guidesMenu,
        Preferences.userNodeForPackage(Sidebar.class).node("sidebar"));
  }

  Sidebar(
      final List<Group> groups,
      final Group advancedGroup,
      final Consumer<Editor> onChoose,
      final JPopupMenu guidesMenu,
      final Preferences preferences) {
    super(new BorderLayout());
    this.preferences = preferences;
    advancedTools = advancedGroup.tools();
    advancedOpen = preferences.getBoolean(ADVANCED_KEY, false);
    setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.getSeparatorColor()));
    rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
    rowsPanel.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_XS, 0, Theme.GAP_XS, 0));
    final ButtonGroup chosen = new ButtonGroup();
    final List<Editor> inOrder = new ArrayList<>();
    groups.forEach(group -> inOrder.addAll(group.tools()));
    inOrder.addAll(advancedTools);
    for (final Group group : groups) {
      // the first group, Home, needs no name above it
      if (group != groups.getFirst()) {
        addGroupStart(group.name());
      }
      group.tools().forEach(tool -> addRow(tool, inOrder.indexOf(tool), chosen, onChoose));
    }
    addGap();
    styleRow(advanced, "Advanced", Optional.empty());
    advanced.addActionListener(e -> setAdvancedOpen(!advancedOpen, true));
    rowsPanel.add(advanced);
    advancedTools.forEach(tool -> addRow(tool, inOrder.indexOf(tool), chosen, onChoose));
    rowsPanel.add(Box.createVerticalGlue());
    final JScrollPane scroll = SwingComponents.newJScrollPane(rowsPanel);
    scroll.setBorder(null);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT);
    add(scroll, BorderLayout.CENTER);

    final JPanel bottom = new JPanel();
    bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
    bottom.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.getSeparatorColor()),
            BorderFactory.createEmptyBorder()));
    styleRow(guides, "Guides & links", Optional.of(Glyph.RESOURCES));
    guides.addActionListener(e -> guidesMenu.show(guides, guides.getWidth(), 0));
    bottom.add(guides);
    styleRow(pin, "", Optional.empty());
    pin.addActionListener(e -> pinOpen(collapsed));
    bottom.add(pin);
    add(bottom, BorderLayout.SOUTH);
    setAdvancedOpen(advancedOpen, false);
    applyCollapsed();
  }

  /** Marks the tool as the one shown, without choosing it again; opens Advanced for its tools. */
  void select(final Editor editor) {
    if (advancedTools.contains(editor) && !advancedOpen) {
      setAdvancedOpen(true, false);
    }
    Optional.ofNullable(rows.get(editor))
        .ifPresent(
            row -> {
              row.setSelected(true);
              // once laid out, eg Files at the end of a low window
              SwingUtilities.invokeLater(
                  () -> row.scrollRectToVisible(new Rectangle(row.getSize())));
            });
  }

  /** Icons only below 1280 px, unless pinned. */
  void fitTo(final int width) {
    windowWidth = width;
    applyCollapsed();
  }

  boolean isCollapsed() {
    return collapsed;
  }

  // the group's name, or between the icons a thin line
  private void addGroupStart(final String name) {
    addGap();
    final JLabel header = new JLabel(name);
    header.putClientProperty(FlatClientProperties.STYLE, "font: -1 $semibold.font");
    header.setForeground(Theme.getSecondaryTextColor());
    header.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_L, 2, 0));
    header.setAlignmentX(Component.LEFT_ALIGNMENT);
    rowsPanel.add(header);
    labelsOnly.add(header);
  }

  // 8 px with names, a line centred in 5 px between the icons
  private void addGap() {
    final JComponent gap = (JComponent) Box.createVerticalStrut(Theme.GAP_S);
    gap.setAlignmentX(Component.LEFT_ALIGNMENT);
    rowsPanel.add(gap);
    labelsOnly.add(gap);
    final JSeparator line = new JSeparator();
    line.setPreferredSize(new Dimension(1, 5));
    line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 5));
    line.setAlignmentX(Component.LEFT_ALIGNMENT);
    rowsPanel.add(line);
    iconsOnly.add(line);
  }

  private void addRow(
      final Editor tool,
      final int index,
      final ButtonGroup chosen,
      final Consumer<Editor> onChoose) {
    final JToggleButton row = new JToggleButton();
    // Alt+1 for the first tool, Alt+2 to 9 for the next ones
    final String key = index < 9 ? " (Alt+" + (index + 1) + ")" : "";
    styleRow(row, tool.title(), Optional.of(tool.glyph()));
    row.setToolTipText(tool.title() + key);
    row.addActionListener(e -> onChoose.accept(tool));
    // the selected row: the selection color and a bar in the accent color at its left
    row.addItemListener(e -> row.setBorder(rowBorder(row.isSelected())));
    chosen.add(row);
    rows.put(tool, row);
    rowsPanel.add(row);
  }

  private void styleRow(final AbstractButton row, final String title, final Optional<Glyph> glyph) {
    row.putClientProperty("Sidebar.title", title);
    glyph.map(MakerIcon::small).ifPresent(row::setIcon);
    row.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    row.putClientProperty(
        FlatClientProperties.STYLE,
        "arc: 0; toolbar.hoverBackground: $Editor.hover"
            + (row instanceof JToggleButton
                ? "; toolbar.selectedBackground: $List.selectionBackground"
                : ""));
    row.getAccessibleContext().setAccessibleName(title);
    row.setIconTextGap(Theme.GAP_S);
    row.setBorder(rowBorder(false));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.setFocusPainted(false);
  }

  private static Border rowBorder(final boolean selected) {
    return BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(
            0,
            ACCENT_BAR,
            0,
            0,
            selected ? Theme.getAccentColor() : new Color(0, 0, 0, 0)),
        BorderFactory.createEmptyBorder(0, Theme.GAP_L - ACCENT_BAR, 0, Theme.GAP_S));
  }

  private void setAdvancedOpen(final boolean open, final boolean remember) {
    advancedOpen = open;
    if (remember) {
      preferences.putBoolean(ADVANCED_KEY, open);
    }
    advancedTools.forEach(tool -> rows.get(tool).setVisible(open));
    applyCollapsed();
  }

  // the map maker's own choice, kept: open at any width, or icons only
  private void pinOpen(final boolean open) {
    preferences.put(OPEN_KEY, String.valueOf(open));
    applyCollapsed();
  }

  private void applyCollapsed() {
    final String pinned = preferences.get(OPEN_KEY, "");
    collapsed = pinned.isEmpty() ? windowWidth < COLLAPSE_BELOW : !Boolean.parseBoolean(pinned);
    labelsOnly.forEach(c -> c.setVisible(!collapsed));
    iconsOnly.forEach(c -> c.setVisible(collapsed));
    final List<AbstractButton> all = new ArrayList<>(rows.values());
    all.addAll(List.of(advanced, guides, pin));
    for (final AbstractButton row : all) {
      final String title = (String) row.getClientProperty("Sidebar.title");
      row.setText(collapsed ? "" : title);
      row.setHorizontalAlignment(collapsed ? SwingConstants.CENTER : SwingConstants.LEADING);
      if (!rows.containsValue(row)) {
        row.setToolTipText(collapsed ? title : null);
      }
    }
    final String fold = advancedOpen ? "▾" : "▸";
    advanced.setText(collapsed ? fold : fold + " Advanced");
    advanced.setToolTipText(advancedOpen ? "Hide Advanced" : "Show Advanced");
    pin.setText(collapsed ? "»" : "«");
    pin.setToolTipText(collapsed ? "Show the names" : "Icons only");
    pin.getAccessibleContext().setAccessibleName(pin.getToolTipText());
    final int width = collapsed ? COLLAPSED_WIDTH : WIDTH;
    final int height = collapsed ? RAIL_ROW_HEIGHT : ROW_HEIGHT;
    for (final AbstractButton row : all) {
      final Dimension size = new Dimension(width, height);
      row.setPreferredSize(size);
      row.setMinimumSize(size);
      row.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }
    setPreferredSize(new Dimension(width + 1, 0));
    revalidate();
    repaint();
  }
}
