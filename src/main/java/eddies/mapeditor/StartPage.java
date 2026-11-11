package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.Serial;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The page the map maker opens with: a card each to open a map's folder, to create an empty map
 * where the player wants it, and to create a map from a picture with the wizard.
 */
final class StartPage extends JPanel {
  @Serial private static final long serialVersionUID = 1L;

  private static final int ICON_SIZE = 44;
  private static final int CARD_WIDTH = 250;

  StartPage(final Runnable open, final Runnable createEmpty, final Runnable createFromPicture) {
    super(new GridBagLayout());
    final JPanel column = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridwidth = 3;
    c.insets = new Insets(0, 0, 6, 0);
    final JLabel heading = new JLabel("Eddie's Map Editor");
    heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() * 2f));
    column.add(heading, c);
    final JLabel intro = new JLabel("Make a map of your own for TripleA, or change one you have.");
    intro.setForeground(Theme.getSecondaryTextColor());
    c.insets = new Insets(0, 0, 32, 0);
    column.add(intro, c);
    c.gridwidth = 1;
    c.gridy = 2;
    c.gridx = GridBagConstraints.RELATIVE;
    c.insets = new Insets(0, 8, 0, 8);
    c.fill = GridBagConstraints.BOTH;
    column.add(
        newCard(Glyph.FILES, "Open a map", "A map's folder, eg one in your maps folder", open), c);
    column.add(
        newCard(
            Glyph.NEW_MAP,
            "Create an empty map",
            "Choose where and name it: all its files are made",
            createEmpty),
        c);
    column.add(
        newCard(
            Glyph.GAME_VIEW,
            "New map from a picture",
            "Its territories are found in your picture",
            createFromPicture),
        c);
    add(column);
  }

  /** A card that acts like a button: its icon, title and what it does; the border lights up. */
  private static JPanel newCard(
      final Glyph glyph, final String title, final String description, final Runnable action) {
    final JPanel card = new JPanel(new BorderLayout(0, 10));
    Theme.setCardStyle(card);
    final Border quiet = cardBorder(Theme.getSeparatorColor());
    final Border lit = cardBorder(Theme.getAccentColor());
    card.setBorder(quiet);
    card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    // icon and title on top, the description below them from the top: titles stay in one line
    final JPanel top = new JPanel(new BorderLayout(0, 10));
    top.setOpaque(false);
    top.add(new JLabel(new MakerIcon(glyph, ICON_SIZE)), BorderLayout.NORTH);
    final JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
    titleLabel.setFont(
        titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() * 1.15f));
    top.add(titleLabel, BorderLayout.CENTER);
    card.add(top, BorderLayout.NORTH);
    final JLabel descriptionLabel =
        new JLabel(
            "<html><div style='text-align:center;width:"
                + (CARD_WIDTH - 40)
                + "px'>"
                + SearchList.escape(description)
                + "</div></html>",
            SwingConstants.CENTER);
    descriptionLabel.setForeground(Theme.getSecondaryTextColor());
    descriptionLabel.setVerticalAlignment(SwingConstants.TOP);
    card.add(descriptionLabel, BorderLayout.CENTER);
    card.getAccessibleContext().setAccessibleName(title);
    card.getAccessibleContext().setAccessibleDescription(description);
    // lit while the mouse is over it or it has the keyboard's focus
    final Runnable light =
        () -> card.setBorder(card.hasFocus() || card.getMousePosition() != null ? lit : quiet);
    card.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(final MouseEvent e) {
            light.run();
          }

          @Override
          public void mouseExited(final MouseEvent e) {
            light.run();
          }

          @Override
          public void mouseClicked(final MouseEvent e) {
            action.run();
            light.run();
          }
        });
    // reachable with Tab, Enter or Space opens it
    card.setFocusable(true);
    card.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(final FocusEvent e) {
            light.run();
          }

          @Override
          public void focusLost(final FocusEvent e) {
            light.run();
          }
        });
    card.addKeyListener(
        new KeyAdapter() {
          @Override
          public void keyPressed(final KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
              action.run();
            }
          }
        });
    return card;
  }

  private static Border cardBorder(final Color line) {
    return BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(line, 2, true),
        BorderFactory.createEmptyBorder(22, 18, 20, 18));
  }
}
