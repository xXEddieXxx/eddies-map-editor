package eddies.mapeditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.Serial;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import org.triplea.map.data.elements.Game;

/**
 * The whole map small, on Home: each territory in its owner's color, seas pale, problems outlined
 * red; a click shows the territory. Drawn once into a picture per change and size, not per paint,
 * as a map may have 700 territories.
 */
final class MiniMap extends JComponent {
  @Serial private static final long serialVersionUID = 1L;
  private static final int MAX_HEIGHT = 360;
  private static final Color SEA = new Color(0xC6DAE8);
  // the map's own colors, not the theme's: a strong red on a white halo shows on every owner's
  static final Color PROBLEM = new Color(0xE0150B);
  private static final Color OUTLINE = new Color(0, 0, 0, 70);

  private final transient MapProject project;
  private int width;
  private Set<String> problems = Set.of();
  // the game and files read, compared by identity as each step makes new ones
  private transient @Nullable Game readGame;
  private transient @Nullable MapFiles readFiles;
  // each territory's area to fill, worked out once for the files
  private final transient Map<String, Shape> filled = new HashMap<>();
  private transient @Nullable MapFiles filledOf;
  private transient Map<String, String> owners = Map.of();
  private transient Set<String> seas = Set.of();
  private Rectangle bounds = new Rectangle();
  // land before sea zones, so a click on an island inside a sea zone finds the island
  private transient List<String> landFirst = List.of();
  // what the picture was drawn for
  private transient @Nullable Game drawnGame;
  private transient @Nullable MapFiles drawnFiles;
  private Set<String> drawnProblems = Set.of();
  private @Nullable BufferedImage picture;

  /**
   * @param width how wide it is; as high as the map's shape asks, up to a limit
   */
  MiniMap(final MapProject project, final Consumer<String> showTerritory, final int width) {
    this.project = project;
    this.width = width;
    // registers the tool tip, which getToolTipText says per territory
    setToolTipText("");
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            territoryAt(e.getX(), e.getY()).ifPresent(showTerritory);
          }
        });
  }

  /** How wide it is, eg as the room on Home grows. */
  void setPreferredWidth(final int width) {
    this.width = width;
    revalidate();
  }

  /** The territories to outline red. */
  void setProblems(final Set<String> territories) {
    problems = Set.copyOf(territories);
    repaint();
  }

  @Override
  public Dimension getPreferredSize() {
    read();
    final int height =
        bounds.width <= 0 ? 0 : (int) Math.round((double) width * bounds.height / bounds.width);
    return new Dimension(width, Math.min(MAX_HEIGHT, height));
  }

  @Override
  public @Nullable String getToolTipText(final MouseEvent event) {
    return territoryAt(event.getX(), event.getY())
        .map(t -> Optional.ofNullable(owners.get(t)).map(o -> t + " · " + o).orElse(t))
        .orElse(null);
  }

  @Override
  public AccessibleContext getAccessibleContext() {
    if (accessibleContext == null) {
      accessibleContext =
          new AccessibleJComponent() {
            @Serial private static final long serialVersionUID = 1L;

            @Override
            public AccessibleRole getAccessibleRole() {
              return AccessibleRole.CANVAS;
            }
          };
      accessibleContext.setAccessibleName("Map overview");
      accessibleContext.setAccessibleDescription(
          "The territories in their owners' colors; Territories lists them");
    }
    return accessibleContext;
  }

  @Override
  protected void paintComponent(final Graphics g) {
    if (getWidth() <= 0 || getHeight() <= 0 || !project.getFiles().hasOutlines()) {
      return;
    }
    read();
    final double screen = ((Graphics2D) g).getTransform().getScaleX();
    final int pixelWidth = (int) Math.ceil(getWidth() * screen);
    final int pixelHeight = (int) Math.ceil(getHeight() * screen);
    if (picture == null
        || drawnGame != readGame
        || drawnFiles != readFiles
        || !drawnProblems.equals(problems)
        || picture.getWidth() != pixelWidth
        || picture.getHeight() != pixelHeight) {
      picture = draw(pixelWidth, pixelHeight, screen);
    }
    g.drawImage(picture, 0, 0, getWidth(), getHeight(), null);
  }

  /** The picture as last drawn, for tests: a new one only after a change. */
  @Nullable BufferedImage picture() {
    return picture;
  }

  /** Owners, seas, bounds and the order to find territories in, once per change. */
  private void read() {
    final Game game = project.game();
    final MapFiles files = project.getFiles();
    if (game == readGame && files == readFiles) {
      return;
    }
    readGame = game;
    readFiles = files;
    owners = MapXml.ownersByTerritory(game);
    seas = MapXml.seaZones(game);
    bounds =
        files.polygons().values().stream()
            .flatMap(List::stream)
            .map(Polygon::getBounds)
            .reduce(Rectangle::union)
            .orElseGet(Rectangle::new);
    landFirst =
        files.polygons().keySet().stream()
            .sorted(Comparator.comparing(seas::contains))
            .toList();
  }

  private BufferedImage draw(final int pixelWidth, final int pixelHeight, final double screen) {
    drawnGame = readGame;
    drawnFiles = readFiles;
    drawnProblems = problems;
    final BufferedImage image =
        new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.scale(screen, screen);
    final AffineTransform toScreen = toScreen();
    g.transform(toScreen);
    final Map<String, List<Polygon>> polygons = project.getFiles().polygons();
    final float line = (float) (1 / toScreen.getScaleX());
    g.setStroke(new BasicStroke(line));
    // seas first, so land drawn over a sea zone's outline stays whole
    landFirst.reversed().forEach(t -> fill(g, t, polygons.get(t)));
    final List<Polygon> marked =
        problems.stream().flatMap(t -> polygons.getOrDefault(t, List.of()).stream()).toList();
    g.setColor(Color.WHITE);
    g.setStroke(new BasicStroke(4 * line));
    marked.forEach(g::draw);
    g.setColor(PROBLEM);
    g.setStroke(new BasicStroke(2 * line));
    marked.forEach(g::draw);
    g.dispose();
    return image;
  }

  private void fill(final Graphics2D g, final String territory, final List<Polygon> outlines) {
    final Color color =
        seas.contains(territory)
            ? SEA
            : project.isImpassable(territory)
                ? project.impassableColor()
                : Optional.ofNullable(owners.get(territory))
                    .map(project::nationColor)
                    .orElseGet(project::neutralColor);
    // without the territories inside it, eg a lake in land, which the fill would cover
    g.setColor(color);
    if (filledOf != readFiles) {
      filledOf = readFiles;
      filled.clear();
    }
    g.fill(filled.computeIfAbsent(territory, project.getFiles()::filled));
    g.setColor(OUTLINE);
    outlines.forEach(g::draw);
  }

  /** Map pixels to this component's: the map fitted in, centred. */
  private AffineTransform toScreen() {
    final double scale =
        Math.min((double) getWidth() / bounds.width, (double) getHeight() / bounds.height);
    final AffineTransform transform =
        AffineTransform.getTranslateInstance(
            (getWidth() - bounds.width * scale) / 2, (getHeight() - bounds.height * scale) / 2);
    transform.scale(scale, scale);
    transform.translate(-bounds.x, -bounds.y);
    return transform;
  }

  /** The territory at this point of the component; land before the sea zone around it. */
  Optional<String> territoryAt(final int x, final int y) {
    read();
    if (bounds.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
      return Optional.empty();
    }
    final Point2D at;
    try {
      at = toScreen().inverseTransform(new Point2D.Double(x, y), null);
    } catch (final NoninvertibleTransformException e) {
      return Optional.empty();
    }
    // the map's own test, so a click here hits what a click on the big map hits
    return project.getFiles().territoryAt(at.getX(), at.getY());
  }
}
