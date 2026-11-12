package eddies.mapeditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Optional;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * The map maker's icons, drawn on a grid of 16 at any size: in the text's color for tools and
 * actions, in their own colors where the color is the point, eg the owners' fields.
 */
record MakerIcon(Glyph glyph, int size) implements Icon {
  private static final List<Color> OWNER_COLORS =
      List.of(new Color(0xC0392B), new Color(0x2E86C1), new Color(0x28B463), new Color(0xD68910));
  private static final Color SEA = new Color(0x3E6A8A);
  private static final Color LAND = new Color(0x6E8B3D);
  private static final Color SUN = new Color(0xF1C40F);
  private static final Color GOLD = new Color(0xD4A017);

  /** What an icon shows. */
  enum Glyph {
    OVERVIEW,
    NOTES,
    FILES,
    TERRITORIES,
    CONNECTIONS,
    NATIONS,
    OPTIONS,
    TURN_ORDER,
    UNITS,
    GAME_VIEW,
    OWNERS,
    VALUES,
    NEW_MAP,
    UNDO,
    REDO,
    VALIDATE,
    PLAYTEST,
    SAVE,
    MENU,
    RESOURCES,
    MAP_LOOK,
    TEXTS,
    ATTACHMENTS,
    PRODUCTION,
    POLITICS,
    TECHNOLOGY,
    SOUNDS,
    CANALS,
    EFFECTS,
    ACTIONS,
    TRIGGERS,
    LOCK,
    UNLOCK
  }

  /** The icon at the size of the text around it. */
  static MakerIcon small(final Glyph glyph) {
    return new MakerIcon(glyph, 16);
  }

  @Override
  public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
    final Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.translate(x, y);
    g2.scale(size / 16.0, size / 16.0);
    final Color text = UIManager.getColor("Label.foreground");
    // painted without a component, eg into a picture, the icon is as if enabled
    final boolean enabled = c == null || c.isEnabled();
    final Color foreground = text != null || c == null ? text : c.getForeground();
    g2.setColor(enabled ? Optional.ofNullable(foreground).orElse(Color.DARK_GRAY) : Color.GRAY);
    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    paint(g2);
    g2.dispose();
  }

  private void paint(final Graphics2D g) {
    switch (glyph) {
      case OVERVIEW -> {
        g.drawRoundRect(1, 1, 6, 6, 2, 2);
        g.drawRoundRect(9, 1, 6, 6, 2, 2);
        g.drawRoundRect(1, 9, 6, 6, 2, 2);
        g.drawRoundRect(9, 9, 6, 6, 2, 2);
      }
      case NOTES -> {
        g.drawRoundRect(2, 1, 12, 14, 2, 2);
        g.drawLine(5, 5, 11, 5);
        g.drawLine(5, 8, 11, 8);
        g.drawLine(5, 11, 9, 11);
      }
      case FILES -> {
        g.drawPolygon(
            new Polygon(new int[] {1, 6, 8, 15, 15, 1}, new int[] {3, 3, 5, 5, 14, 14}, 6));
      }
      case TERRITORIES ->
          g.drawPolygon(
              new Polygon(new int[] {2, 8, 14, 13, 9, 3}, new int[] {4, 1, 4, 11, 15, 12}, 6));
      case CONNECTIONS -> {
        g.drawLine(3, 12, 8, 4);
        g.drawLine(8, 4, 13, 12);
        g.drawLine(3, 12, 13, 12);
        g.fillOval(0, 9, 6, 6);
        g.fillOval(5, 1, 6, 6);
        g.fillOval(10, 9, 6, 6);
      }
      case NATIONS -> {
        g.drawLine(3, 1, 3, 15);
        g.fillPolygon(new Polygon(new int[] {4, 14, 11, 14, 4}, new int[] {2, 2, 5, 8, 8}, 5));
      }
      case OPTIONS -> {
        g.drawLine(2, 4, 14, 4);
        g.drawLine(2, 8, 14, 8);
        g.drawLine(2, 12, 14, 12);
        g.fillOval(4, 2, 4, 4);
        g.fillOval(9, 6, 4, 4);
        g.fillOval(5, 10, 4, 4);
      }
      case TURN_ORDER -> {
        g.drawArc(2, 2, 12, 12, 30, 280);
        g.fillPolygon(new Polygon(new int[] {12, 16, 11}, new int[] {1, 5, 6}, 3));
      }
      case UNITS -> {
        g.fillOval(5, 1, 6, 6);
        g.fillPolygon(new Polygon(new int[] {3, 13, 15, 1}, new int[] {8, 8, 15, 15}, 4));
      }
      case GAME_VIEW -> {
        g.setColor(SEA);
        g.fillRoundRect(0, 1, 16, 14, 3, 3);
        g.setColor(LAND);
        g.fillPolygon(
            new Polygon(
                new int[] {0, 5, 9, 12, 16, 16, 0}, new int[] {13, 6, 10, 7, 12, 15, 15}, 7));
        g.setColor(SUN);
        g.fillOval(10, 3, 4, 4);
      }
      case OWNERS -> {
        for (int i = 0; i < OWNER_COLORS.size(); i++) {
          g.setColor(OWNER_COLORS.get(i));
          g.fillRoundRect(1 + (i % 2) * 8, 1 + (i / 2) * 8, 6, 6, 2, 2);
        }
      }
      case VALUES -> {
        g.setColor(GOLD);
        g.fillOval(1, 1, 14, 14);
        g.setColor(GOLD.darker());
        g.drawOval(4, 4, 8, 8);
      }
      case NEW_MAP -> {
        g.drawPolygon(new Polygon(new int[] {3, 10, 13, 13, 3}, new int[] {1, 1, 4, 15, 15}, 5));
        g.drawLine(8, 6, 8, 12);
        g.drawLine(5, 9, 11, 9);
      }
      case UNDO -> {
        g.drawArc(4, 4, 10, 10, 270, 200);
        g.fillPolygon(new Polygon(new int[] {1, 6, 6}, new int[] {7, 3, 11}, 3));
      }
      case REDO -> {
        g.drawArc(2, 4, 10, 10, 70, 200);
        g.fillPolygon(new Polygon(new int[] {15, 10, 10}, new int[] {7, 3, 11}, 3));
      }
      case VALIDATE -> {
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(new int[] {2, 6, 14}, new int[] {8, 12, 3}, 3);
      }
      case PLAYTEST -> g.fillPolygon(new Polygon(new int[] {4, 14, 4}, new int[] {2, 8, 14}, 3));
      case SAVE -> {
        g.drawRoundRect(1, 1, 14, 14, 2, 2);
        g.fillRect(4, 1, 7, 5);
        g.drawRect(4, 9, 8, 6);
      }
      case MENU -> {
        g.drawLine(2, 4, 14, 4);
        g.drawLine(2, 8, 14, 8);
        g.drawLine(2, 12, 14, 12);
      }
      case RESOURCES -> {
        // an open book
        g.drawPolyline(new int[] {8, 4, 1, 1, 4, 8}, new int[] {4, 2, 2, 13, 13, 15}, 6);
        g.drawPolyline(new int[] {8, 12, 15, 15, 12, 8}, new int[] {4, 2, 2, 13, 13, 15}, 6);
        g.drawLine(8, 4, 8, 15);
      }
      case MAP_LOOK -> {
        // a painter's palette
        g.drawOval(1, 2, 14, 12);
        g.fillOval(4, 5, 3, 3);
        g.fillOval(8, 4, 3, 3);
        g.fillOval(10, 8, 3, 3);
      }
      case TEXTS -> {
        // a speech bubble
        g.drawRoundRect(1, 2, 14, 9, 3, 3);
        g.drawPolyline(new int[] {4, 4, 8}, new int[] {11, 15, 11}, 3);
        g.drawLine(4, 6, 12, 6);
      }
      case ATTACHMENTS -> {
        // a paper clip
        g.drawRoundRect(4, 1, 8, 14, 8, 8);
        g.drawLine(8, 5, 8, 11);
      }
      case PRODUCTION -> {
        // a factory
        g.drawPolyline(
            new int[] {1, 1, 6, 6, 11, 11, 15, 15, 1},
            new int[] {15, 7, 10, 7, 10, 7, 10, 15, 15},
            9);
        g.drawLine(13, 9, 13, 2);
      }
      case POLITICS -> {
        // two linked rings
        g.drawOval(1, 4, 8, 8);
        g.drawOval(7, 4, 8, 8);
      }
      case TECHNOLOGY -> {
        // a flask
        g.drawPolygon(
            new Polygon(new int[] {6, 10, 10, 15, 1, 6}, new int[] {1, 1, 6, 15, 15, 6}, 6));
        g.drawLine(4, 10, 12, 10);
      }
      case SOUNDS -> {
        // a speaker with a sound wave
        g.drawPolygon(new Polygon(new int[] {1, 5, 9, 9, 5, 1}, new int[] {6, 6, 2, 14, 10, 10}, 6));
        g.drawArc(8, 4, 6, 8, -60, 120);
      }
      case CANALS -> {
        // two banks with a waterway between them
        g.drawPolyline(new int[] {1, 6, 6, 1}, new int[] {2, 2, 14, 14}, 4);
        g.drawPolyline(new int[] {15, 10, 10, 15}, new int[] {2, 2, 14, 14}, 4);
        g.drawLine(8, 4, 8, 6);
        g.drawLine(8, 10, 8, 12);
      }
      case EFFECTS -> {
        // a tree on a hill
        g.drawArc(1, 10, 14, 10, 0, 180);
        g.fillPolygon(new Polygon(new int[] {8, 12, 4}, new int[] {1, 9, 9}, 3));
      }
      case ACTIONS -> {
        // a hand pressing a button
        g.drawRoundRect(2, 9, 12, 5, 2, 2);
        g.drawLine(8, 2, 8, 9);
        g.drawLine(5, 5, 8, 2);
        g.drawLine(11, 5, 8, 2);
      }
      case TRIGGERS -> {
        // a lightning bolt
        g.drawPolygon(
            new Polygon(new int[] {9, 3, 8, 6, 13, 8}, new int[] {1, 9, 9, 15, 6, 6}, 6));
      }
      case LOCK -> {
        // a padlock, its shackle closed
        g.drawArc(5, 2, 6, 8, 0, 180);
        g.drawLine(5, 6, 5, 8);
        g.drawLine(11, 6, 11, 8);
        g.fillRoundRect(3, 8, 10, 7, 2, 2);
      }
      case UNLOCK -> {
        // a padlock, its shackle open to the right
        g.drawArc(8, 1, 6, 8, 0, 180);
        g.drawLine(8, 5, 8, 8);
        g.drawLine(14, 5, 14, 6);
        g.fillRoundRect(1, 8, 10, 7, 2, 2);
      }
      default -> throw new IllegalStateException("Unknown icon: " + glyph);
    }
  }

  @Override
  public int getIconWidth() {
    return size;
  }

  @Override
  public int getIconHeight() {
    return size;
  }
}
