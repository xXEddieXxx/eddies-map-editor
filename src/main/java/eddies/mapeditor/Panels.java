package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import eddies.mapeditor.MakerIcon.Glyph;
import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import lombok.experimental.UtilityClass;

/** How the tools lay out their panel: beside the map, or alone with the whole room. */
@UtilityClass
class Panels {
  private static final int LIST_WIDTH = 320;
  private static final int LIST_MAX_WIDTH = 420;
  private static final int LIST_MIN_HEIGHT = 220;
  private static final int EMPTY_GLYPH = 32;
  private static final int EMPTY_LINE_WIDTH = 260;

  /** A list above the inspector of the chosen entry, the line between them movable. */
  static JSplitPane stacked(final JComponent list, final JComponent inspector) {
    // a long inspector scrolls instead of squashing the list to a row
    list.setMinimumSize(new Dimension(0, LIST_MIN_HEIGHT));
    inspector.setMinimumSize(new Dimension(0, 0));
    final JSplitPane stacked = new JSplitPane(JSplitPane.VERTICAL_SPLIT, list, inspector);
    stacked.setResizeWeight(0.35);
    stacked.setBorder(null);
    return stacked;
  }

  /**
   * The list left of the inspector of the chosen entry, for a tool that has the whole room; the
   * line between them movable.
   */
  static JSplitPane sideBySide(final JComponent list, final JComponent inspector) {
    final JSplitPane sideBySide = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, inspector);
    // a list of long names, eg SearchList, may ask for more room
    sideBySide.setDividerLocation(
        Math.clamp(list.getPreferredSize().width, LIST_WIDTH, LIST_MAX_WIDTH));
    sideBySide.setBorder(null);
    return sideBySide;
  }

  /** A text left of how it shows, in halves of the room; the line between them movable. */
  static JSplitPane textAndPreview(final JComponent text, final JComponent preview) {
    // the same small wish for both, as a wide HTML table would take nearly all the room
    text.setPreferredSize(new Dimension(100, 0));
    preview.setPreferredSize(new Dimension(100, 0));
    final JSplitPane halves = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, text, preview);
    halves.setResizeWeight(0.5);
    halves.setBorder(null);
    return halves;
  }

  /** Shows the HTML in the preview where the reader was in it, eg after a pause in typing. */
  static void showKeepingPlace(final GameNotesView preview, final String html) {
    final Optional<JViewport> viewport =
        Optional.ofNullable(
            (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, preview));
    final Point at = viewport.map(JViewport::getViewPosition).orElseGet(Point::new);
    preview.setText(html);
    // the view scrolls to its top after a new text, later: back to where the reader was
    SwingUtilities.invokeLater(() -> viewport.ifPresent(v -> v.setViewPosition(at)));
  }

  /** Nothing there yet: glyph, title, one line and the tool's main action. */
  static JComponent empty(
      final Glyph glyph,
      final String title,
      final String line,
      final String verb,
      final Runnable action) {
    final JButton button = new JButton(verb);
    Theme.setPrimaryButtonStyle(button);
    button.addActionListener(e -> action.run());
    return empty(glyph, title, line, List.of(button));
  }

  /** Nothing chosen: glyph, title and one line that points to the list, no button. */
  static JComponent empty(final Glyph glyph, final String title, final String line) {
    return empty(glyph, title, line, List.of());
  }

  // shortcut: always centred; top-align it if a tool shows it in a strip under 240 px high
  private static JComponent empty(
      final Glyph glyph, final String title, final String line, final List<JButton> buttons) {
    final JLabel picture = new JLabel(new SecondaryIcon(new MakerIcon(glyph, EMPTY_GLYPH)));
    final JLabel heading = new JLabel(title);
    heading.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");
    final JLabel text = new JLabel("<html><center>" + SearchList.escape(line) + "</center></html>");
    if (text.getPreferredSize().width > EMPTY_LINE_WIDTH) {
      text.setText(
          "<html><div style='width:"
              + Theme.htmlWidth(EMPTY_LINE_WIDTH)
              + ";text-align:center'>"
              + SearchList.escape(line)
              + "</div></html>");
    }
    text.setHorizontalAlignment(SwingConstants.CENTER);
    text.setForeground(Theme.getSecondaryTextColor());
    final JPanel block = new JPanel();
    block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
    block.setOpaque(false);
    block.add(picture);
    block.add(Box.createVerticalStrut(Theme.GAP_M));
    block.add(heading);
    block.add(Box.createVerticalStrut(Theme.GAP_XS));
    block.add(text);
    for (final JButton button : buttons) {
      block.add(Box.createVerticalStrut(Theme.GAP_L));
      block.add(button);
    }
    for (final Component part : block.getComponents()) {
      ((JComponent) part).setAlignmentX(Component.CENTER_ALIGNMENT);
    }
    final JPanel centred = new JPanel(new GridBagLayout());
    centred.setOpaque(false);
    centred.setBorder(
        BorderFactory.createEmptyBorder(
            Theme.GAP_XXL, Theme.GAP_L, Theme.GAP_XXL, Theme.GAP_L));
    centred.add(block);
    return centred;
  }

  /**
   * The icon's text-colored lines in the secondary text color, as the glyph only decorates; its own
   * colors, eg the map picture's, stay. A disabled label would paint nothing, as Swing greys only
   * image icons.
   */
  private record SecondaryIcon(Icon icon) implements Icon {
    @Override
    public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
      // drawn at the screen's scale, so it stays sharp on a HiDPI screen
      final double scale = ((Graphics2D) g).getTransform().getScaleX();
      final int width = (int) Math.ceil(getIconWidth() * scale);
      final int height = (int) Math.ceil(getIconHeight() * scale);
      final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      final Graphics2D painter = image.createGraphics();
      painter.scale(scale, scale);
      icon.paintIcon(c, painter, 0, 0);
      painter.dispose();
      // the color MakerIcon draws lines in
      final int text =
          Optional.ofNullable(UIManager.getColor("Label.foreground"))
                  .orElseGet(c::getForeground)
                  .getRGB()
              & 0xFFFFFF;
      final int secondary = Theme.getSecondaryTextColor().getRGB() & 0xFFFFFF;
      for (int py = 0; py < height; py++) {
        for (int px = 0; px < width; px++) {
          final int argb = image.getRGB(px, py);
          if ((argb & 0xFFFFFF) == text) {
            image.setRGB(px, py, argb & 0xFF000000 | secondary);
          }
        }
      }
      g.drawImage(image, x, y, getIconWidth(), getIconHeight(), null);
    }

    @Override
    public int getIconWidth() {
      return icon.getIconWidth();
    }

    @Override
    public int getIconHeight() {
      return icon.getIconHeight();
    }
  }
}
