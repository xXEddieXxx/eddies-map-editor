package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.List;
import java.util.prefs.Preferences;
import javax.annotation.Nullable;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import lombok.experimental.UtilityClass;

/**
 * The small "?" next to a feature: on a click, a bubble that explains it and stays open, so the map
 * maker needs no long texts beside its controls; and a screen's first-visit line.
 */
@UtilityClass
class Help {
  private static final int BUBBLE_WIDTH = 320;

  /** A "?" that explains; Tab reaches it, Enter or a click keeps the explanation open. */
  static JButton button(final String explanation) {
    return button("", explanation);
  }

  /**
   * A "?" that explains the subject, eg a field's label; screen readers call it "Help: subject", so
   * several in a row stay apart.
   */
  static JButton button(final String subject, final String explanation) {
    return button(subject, explanation, List.of());
  }

  /** A "?" whose bubble also says where the map keeps it, eg "Saved in sounds.properties". */
  static JButton button(final String subject, final String explanation, final String where) {
    return button(subject, explanation, List.of(Theme.newSecondaryLabel("Saved in " + where)));
  }

  private static JButton button(
      final String subject, final String explanation, final List<JLabel> below) {
    final JButton help = new JButton("?");
    help.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    help.putClientProperty(
        FlatClientProperties.STYLE,
        "arc: 999; margin: 0,0,0,0; foreground: $Editor.secondaryText; font: $semibold.font");
    help.setPreferredSize(new Dimension(UIScale.scale(24), UIScale.scale(24)));
    // short, so the tool tip does not show the bubble's text a second time
    help.setToolTipText("Help");
    help.getAccessibleContext().setAccessibleName(subject.isEmpty() ? "Help" : "Help: " + subject);
    help.getAccessibleContext().setAccessibleDescription(explanation);
    final JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    content.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_M, Theme.GAP_M, Theme.GAP_M, Theme.GAP_M));
    if (!subject.isEmpty()) {
      content.add(Theme.newSectionLabel(subject));
      content.add(Box.createVerticalStrut(Theme.GAP_XS));
    }
    content.add(wrapping(explanation));
    for (final JLabel label : below) {
      content.add(Box.createVerticalStrut(Theme.GAP_S));
      content.add(label);
    }
    // a click keeps the bubble open below the "?" until a click elsewhere or Esc
    final JPopupMenu bubble = new JPopupMenu();
    bubble.add(content);
    help.addActionListener(e -> bubble.show(help, 0, help.getHeight() + Theme.GAP_XS));
    return help;
  }

  // a short text on one line, a long one wrapped at the bubble's width
  private static JLabel wrapping(final String text) {
    final JLabel label = new JLabel("<html>" + SearchList.escape(text) + "</html>");
    if (label.getPreferredSize().width > BUBBLE_WIDTH) {
      label.setText(
          "<html><div style='width:"
              + Theme.htmlWidth(BUBBLE_WIDTH)
              + "'>"
              + SearchList.escape(text)
              + "</div></html>");
    }
    return label;
  }

  /**
   * A line saying that a setting comes from another tool, with a link there, eg "The color is set
   * per nation in" [Nations ›]: a setting here depends on it.
   */
  static JComponent link(final String text, final String tool) {
    return link(text, tool, null);
  }

  /** As link, the tool showing the item, eg a territory. */
  static JComponent link(final String text, final String tool, @Nullable final String item) {
    // no gap at the left, so the text lines up with the labels above and below
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    row.setOpaque(false);
    row.add(Theme.newSecondaryLabel(text));
    row.add(Box.createHorizontalStrut(Theme.GAP_XS));
    final JButton open = new JButton(tool + " ›");
    Theme.setTertiaryButtonStyle(open);
    open.getAccessibleContext().setAccessibleName("Open " + tool);
    open.addActionListener(e -> MapMakerWindow.openTool(open, tool, item));
    row.add(open);
    return row;
  }

  /** The component with its "?" right after it, named after the component's text. */
  static JComponent with(final JComponent component, final String explanation) {
    // no gap before the label, so it lines up with labels without a "?"
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setOpaque(false);
    row.add(component);
    row.add(Box.createHorizontalStrut(4));
    final String subject =
        component instanceof JLabel label
            ? label.getText()
            : component instanceof AbstractButton button ? button.getText() : "";
    row.add(button(subject, explanation));
    return row;
  }

  /**
   * The tool's hint as a line below its title, until the map maker hides it; then only the
   * title's "?" has it.
   */
  static JComponent intro(final String toolTitle, final String hint) {
    return intro(toolTitle, hint, Preferences.userNodeForPackage(Help.class).node("hiddenIntros"));
  }

  static JComponent intro(final String toolTitle, final String hint, final Preferences hidden) {
    final JTextArea line = wrappingLine(hint);
    final JPanel right = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    final JPanel intro =
        new JPanel(new BorderLayout(Theme.GAP_S, 0)) {
          // a layout asks for the height before it gives the width: wrap at the width to come,
          // so the first layout already has every line (a text area wraps only once it has a
          // width and a height); a short line keeps its own width, so Hide follows it
          @Override
          public Dimension getPreferredSize() {
            final int room = widthToCome(this) - right.getPreferredSize().width - Theme.GAP_S;
            if (room > 0) {
              final int oneLine = line.getFontMetrics(line.getFont()).stringWidth(hint) + 1;
              final int width = Math.min(oneLine, room);
              if (line.getWidth() != width) {
                line.setSize(width, Math.max(1, line.getHeight()));
              }
              line.setPreferredSize(null);
              line.setPreferredSize(new Dimension(width, line.getPreferredSize().height));
            }
            return super.getPreferredSize();
          }
        };
    intro.setOpaque(false);
    intro.add(line, BorderLayout.WEST);
    final JButton hide = new JButton("Hide");
    Theme.setTertiaryButtonStyle(hide);
    hide.setToolTipText("Hide this line; the \"?\" beside the title still has it");
    hide.addActionListener(
        e -> {
          hidden.putBoolean(toolTitle, true);
          intro.setVisible(false);
          if (intro.getParent() != null) {
            intro.getParent().revalidate();
          }
        });
    right.setOpaque(false);
    right.add(hide);
    intro.add(right, BorderLayout.CENTER);
    intro.setVisible(!hidden.getBoolean(toolTitle, false));
    return intro;
  }

  /**
   * The component's width, or before its first layout the width of the first laid out container
   * above it less the insets between: the intro fills the width of the column it is in.
   */
  private static int widthToCome(final Component component) {
    int insets = 0;
    for (Component at = component; at != null; at = at.getParent()) {
      if (at.getWidth() > 0) {
        return at.getWidth() - insets;
      }
      if (at.getParent() != null) {
        final Insets around = at.getParent().getInsets();
        insets += around.left + around.right;
      }
    }
    return 0;
  }

  // a text area wraps at the width it gets, already on the first layout, and asks for no width of
  // its own; an HTML label would ask for the whole line and be cut
  private static JTextArea wrappingLine(final String text) {
    final JTextArea line = new JTextArea(text);
    line.setLineWrap(true);
    line.setWrapStyleWord(true);
    line.setEditable(false);
    line.setFocusable(false);
    line.setOpaque(false);
    line.setBorder(BorderFactory.createEmptyBorder());
    line.putClientProperty(
        FlatClientProperties.STYLE, "font: $Label.font; foreground: $Editor.secondaryText");
    return line;
  }
}
