package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import lombok.experimental.UtilityClass;

/**
 * The small "?" next to a feature: what it does, as its tool tip and, on a click, as a hint that
 * stays beside it, so the map maker needs no long texts beside its controls.
 */
@UtilityClass
class Help {

  /** A "?" that explains; Tab reaches it, Enter or a click keeps the explanation open. */
  static JButton button(final String explanation) {
    return button("", explanation);
  }

  /**
   * A "?" that explains the subject, eg a field's label; screen readers call it "Help: subject", so
   * several in a row stay apart.
   */
  static JButton button(final String subject, final String explanation) {
    final JButton help = new JButton("?");
    help.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
    help.putClientProperty(FlatClientProperties.STYLE, "margin: 0,5,0,5");
    help.setFont(help.getFont().deriveFont(Font.BOLD, help.getFont().getSize2D() * 0.85f));
    final String text =
        "<html><div style='width:300px'>" + SearchList.escape(explanation) + "</div></html>";
    help.setToolTipText(text);
    help.getAccessibleContext().setAccessibleName(subject.isEmpty() ? "Help" : "Help: " + subject);
    help.getAccessibleContext().setAccessibleDescription(explanation);
    // a click keeps the hint open beside the "?" until a click elsewhere, like a pinned tool tip
    final JLabel hint = new JLabel(text);
    hint.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    final JPopupMenu bubble = new JPopupMenu();
    bubble.add(hint);
    help.addActionListener(e -> bubble.show(help, help.getWidth() + 4, 0));
    return help;
  }

  /** The component with its "?" right after it, named after the component's text. */
  static JComponent with(final JComponent component, final String explanation) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    row.setOpaque(false);
    row.add(component);
    final String subject =
        component instanceof JLabel label
            ? label.getText()
            : component instanceof AbstractButton button ? button.getText() : "";
    row.add(button(subject, explanation));
    return row;
  }
}
