package eddies.mapeditor;

import eddies.mapeditor.MapPicture.DetectedMap;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapPicture.Neighbours;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A grid of territories laid over a picture that has none: squares, hexagons or offset squares,
 * every cell a land territory named by its row and column (A1, A2, B1), with its outline, its
 * center and its neighbours. Cells at the picture's edge are cut to it, so the cells tile the
 * picture whole. The borders are drawn into the picture as black lines, as Draw borders draws
 * them, so the saved tiles show the grid.
 */
final class TerritoryGrid {
  /** The least a cell may be wide, so that a border and a unit fit into it. */
  static final int MIN_CELL_WIDTH = 8;

  /** The kinds of grid, each with how its cells are connected. */
  enum Pattern {
    SQUARES("Squares", "Each cell has 4 neighbours: left, right, above and below"),
    SQUARES_DIAGONAL(
        "Squares with diagonals", "Each cell has 8 neighbours: the corners count too"),
    HEX_ROWS("Hexagons in straight rows", "Pointed tops; each cell has 6 neighbours"),
    HEX_COLUMNS("Hexagons in straight columns", "Flat tops; each cell has 6 neighbours"),
    OFFSET_ROWS(
        "Offset squares in straight rows",
        "Every second row shifted by half a cell, like bricks; each cell has 6 neighbours"),
    OFFSET_COLUMNS(
        "Offset squares in straight columns",
        "Every second column shifted by half a cell; each cell has 6 neighbours");

    final String label;
    final String neighbours;

    Pattern(final String label, final String neighbours) {
      this.label = label;
      this.neighbours = neighbours;
    }
  }

  /** A cell of the lattice: its row and column there, and its outline cut to the picture. */
  private record Cell(int row, int column, Polygon outline) {}

  private TerritoryGrid() {}

  /**
   * The grid over a picture of the size: the cells as territories, all land, with their outlines,
   * centers and neighbours.
   *
   * @param cellWidth how wide a cell is, in pixels; a hexagon's is its width from side to side
   */
  static DetectedMap grid(final Dimension size, final Pattern pattern, final int cellWidth) {
    final List<Cell> cells = cells(size, pattern, Math.max(MIN_CELL_WIDTH, cellWidth));
    final int firstRow = cells.stream().mapToInt(Cell::row).min().orElse(0);
    final int firstColumn = cells.stream().mapToInt(Cell::column).min().orElse(0);
    final Map<Cell, String> names = new LinkedHashMap<>();
    for (final Cell cell : cells) {
      names.put(cell, rowName(cell.row() - firstRow) + (cell.column() - firstColumn + 1));
    }
    final List<DetectedTerritory> territories = new ArrayList<>();
    for (final Cell cell : cells) {
      territories.add(
          new DetectedTerritory(
              names.get(cell), false, List.of(cell.outline()), centerOf(cell.outline())));
    }
    return new DetectedMap(new Dimension(size), territories, connections(cells, names, pattern));
  }

  /** The picture with the grid's borders drawn in as black lines, one pixel wide. */
  static BufferedImage withBorders(final BufferedImage picture, final DetectedMap grid) {
    final BufferedImage drawn =
        new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = drawn.createGraphics();
    g.drawImage(picture, 0, 0, null);
    g.setColor(Color.BLACK);
    g.setStroke(new BasicStroke(1));
    for (final DetectedTerritory territory : grid.territories()) {
      territory.outlines().forEach(g::drawPolygon);
    }
    g.dispose();
    return drawn;
  }

  /** How many cells the grid has on a picture of the size. */
  static int count(final Dimension size, final Pattern pattern, final int cellWidth) {
    return cells(size, pattern, Math.max(MIN_CELL_WIDTH, cellWidth)).size();
  }

  /** How many columns of cells the grid has on a picture of the size. */
  static int columns(final Dimension size, final Pattern pattern, final int cellWidth) {
    return (int)
        cells(size, pattern, Math.max(MIN_CELL_WIDTH, cellWidth)).stream()
            .mapToInt(Cell::column)
            .distinct()
            .count();
  }

  /** How many rows of cells the grid has on a picture of the size. */
  static int rows(final Dimension size, final Pattern pattern, final int cellWidth) {
    return (int)
        cells(size, pattern, Math.max(MIN_CELL_WIDTH, cellWidth)).stream()
            .mapToInt(Cell::row)
            .distinct()
            .count();
  }

  /** The cell width that gives about this many columns across the picture. */
  static int cellWidthFor(final Dimension size, final Pattern pattern, final int columns) {
    final double across = Math.max(1, columns);
    final double width =
        switch (pattern) {
          // flat-topped hexagons overlap by a quarter of their width
          case HEX_COLUMNS -> size.width / (0.75 * across + 0.25);
          default -> size.width / across;
        };
    return Math.max(MIN_CELL_WIDTH, (int) Math.round(width));
  }

  /** The row's name: A to Z, then AA, AB and so on. */
  static String rowName(final int row) {
    final StringBuilder name = new StringBuilder();
    int left = row;
    do {
      name.insert(0, (char) ('A' + left % 26));
      left = left / 26 - 1;
    } while (left >= 0);
    return name.toString();
  }

  /**
   * The cells of the lattice that have a part inside the picture, each cut to it. The lattice is
   * laid out generously around the picture and the cells outside drop out, so the picture's edge
   * is covered whole, also where a shifted row or a hexagon's point would leave a gap.
   */
  private static List<Cell> cells(final Dimension size, final Pattern pattern, final int width) {
    final List<Cell> cells = new ArrayList<>();
    final double w = width;
    switch (pattern) {
      case SQUARES, SQUARES_DIAGONAL -> {
        final int columns = (int) Math.ceil(size.width / w);
        final int rows = (int) Math.ceil(size.height / w);
        for (int r = 0; r < rows; r++) {
          for (int c = 0; c < columns; c++) {
            add(cells, size, r, c, box(c * w, r * w, w, w));
          }
        }
      }
      case OFFSET_ROWS -> {
        final int columns = (int) Math.ceil(size.width / w) + 1;
        final int rows = (int) Math.ceil(size.height / w);
        for (int r = 0; r < rows; r++) {
          final double shift = r % 2 == 1 ? w / 2 : 0;
          for (int c = 0; c < columns; c++) {
            add(cells, size, r, c, box(c * w - shift, r * w, w, w));
          }
        }
      }
      case OFFSET_COLUMNS -> {
        final int columns = (int) Math.ceil(size.width / w);
        final int rows = (int) Math.ceil(size.height / w) + 1;
        for (int c = 0; c < columns; c++) {
          final double shift = c % 2 == 1 ? w / 2 : 0;
          for (int r = 0; r < rows; r++) {
            add(cells, size, r, c, box(c * w, r * w - shift, w, w));
          }
        }
      }
      case HEX_ROWS -> {
        // pointed tops: the height from point to point, rows three quarters of it apart, every
        // second row shifted right by half a cell; a row above and a column before fill the gaps
        final double h = w * 2 / Math.sqrt(3);
        final int rows = (int) Math.ceil(size.height / (0.75 * h)) + 1;
        final int columns = (int) Math.ceil(size.width / w) + 1;
        for (int r = -1; r < rows; r++) {
          for (int c = -1; c < columns; c++) {
            final double cx = c * w + (Math.floorMod(r, 2) == 1 ? w : w / 2);
            final double cy = h / 2 + r * 0.75 * h;
            add(
                cells,
                size,
                r,
                c,
                List.of(
                    new Point2D.Double(cx, cy - h / 2),
                    new Point2D.Double(cx + w / 2, cy - h / 4),
                    new Point2D.Double(cx + w / 2, cy + h / 4),
                    new Point2D.Double(cx, cy + h / 2),
                    new Point2D.Double(cx - w / 2, cy + h / 4),
                    new Point2D.Double(cx - w / 2, cy - h / 4)));
          }
        }
      }
      case HEX_COLUMNS -> {
        // flat tops: the width is from point to point, columns three quarters of it apart, every
        // second column shifted down by half a cell
        final double h = w * Math.sqrt(3) / 2;
        final int columns = (int) Math.ceil(size.width / (0.75 * w)) + 1;
        final int rows = (int) Math.ceil(size.height / h) + 1;
        for (int c = -1; c < columns; c++) {
          for (int r = -1; r < rows; r++) {
            final double cx = w / 2 + c * 0.75 * w;
            final double cy = r * h + (Math.floorMod(c, 2) == 1 ? h : h / 2);
            add(
                cells,
                size,
                r,
                c,
                List.of(
                    new Point2D.Double(cx - w / 2, cy),
                    new Point2D.Double(cx - w / 4, cy - h / 2),
                    new Point2D.Double(cx + w / 4, cy - h / 2),
                    new Point2D.Double(cx + w / 2, cy),
                    new Point2D.Double(cx + w / 4, cy + h / 2),
                    new Point2D.Double(cx - w / 4, cy + h / 2)));
          }
        }
      }
    }
    return cells;
  }

  private static List<Point2D.Double> box(
      final double x, final double y, final double w, final double h) {
    return List.of(
        new Point2D.Double(x, y),
        new Point2D.Double(x + w, y),
        new Point2D.Double(x + w, y + h),
        new Point2D.Double(x, y + h));
  }

  /** Adds the cell cut to the picture, unless nothing of it is inside. */
  private static void add(
      final List<Cell> cells,
      final Dimension size,
      final int row,
      final int column,
      final List<Point2D.Double> corners) {
    // the corners on whole pixels first, the same for every cell that shares them: a shared
    // border then has the same points on both sides, so the cells neither gap nor overlap
    final List<Point2D.Double> rounded =
        corners.stream().map(p -> new Point2D.Double(Math.rint(p.x), Math.rint(p.y))).toList();
    final Polygon outline = polygon(clipped(rounded, size));
    if (outline.npoints >= 3 && area(outline) > 0) {
      cells.add(new Cell(row, column, outline));
    }
  }

  /** The convex polygon cut to the picture's rectangle (Sutherland-Hodgman, one edge at a time). */
  private static List<Point2D.Double> clipped(final List<Point2D.Double> corners, final Dimension size) {
    List<Point2D.Double> result = corners;
    result = clipped(result, p -> p.x >= 0, (p, q) -> crossingX(p, q, 0));
    result = clipped(result, p -> p.x <= size.width, (p, q) -> crossingX(p, q, size.width));
    result = clipped(result, p -> p.y >= 0, (p, q) -> crossingY(p, q, 0));
    result = clipped(result, p -> p.y <= size.height, (p, q) -> crossingY(p, q, size.height));
    return result;
  }

  private interface Inside {
    boolean test(Point2D.Double p);
  }

  private interface Crossing {
    Point2D.Double of(Point2D.Double p, Point2D.Double q);
  }

  private static List<Point2D.Double> clipped(
      final List<Point2D.Double> corners, final Inside inside, final Crossing crossing) {
    final List<Point2D.Double> result = new ArrayList<>();
    for (int i = 0; i < corners.size(); i++) {
      final Point2D.Double from = corners.get(i);
      final Point2D.Double to = corners.get((i + 1) % corners.size());
      if (inside.test(from)) {
        result.add(from);
        if (!inside.test(to)) {
          result.add(crossing.of(from, to));
        }
      } else if (inside.test(to)) {
        result.add(crossing.of(from, to));
      }
    }
    return result;
  }

  /**
   * Where the edge crosses the vertical line, computed from its lower-left end whichever way the
   * edge runs, so that the two cells sharing the edge get the same point.
   */
  private static Point2D.Double crossingX(
      final Point2D.Double p, final Point2D.Double q, final double x) {
    final boolean pFirst = p.x < q.x || (p.x == q.x && p.y <= q.y);
    final Point2D.Double a = pFirst ? p : q;
    final Point2D.Double b = pFirst ? q : p;
    final double t = (x - a.x) / (b.x - a.x);
    return new Point2D.Double(x, Math.rint(a.y + t * (b.y - a.y)));
  }

  private static Point2D.Double crossingY(
      final Point2D.Double p, final Point2D.Double q, final double y) {
    final boolean pFirst = p.y < q.y || (p.y == q.y && p.x <= q.x);
    final Point2D.Double a = pFirst ? p : q;
    final Point2D.Double b = pFirst ? q : p;
    final double t = (y - a.y) / (b.y - a.y);
    return new Point2D.Double(Math.rint(a.x + t * (b.x - a.x)), y);
  }

  /** The points as a polygon on whole pixels, a point repeated left out. */
  private static Polygon polygon(final List<Point2D.Double> points) {
    final Polygon polygon = new Polygon();
    for (final Point2D.Double point : points) {
      final int x = (int) Math.rint(point.x);
      final int y = (int) Math.rint(point.y);
      final boolean sameAsLast =
          polygon.npoints > 0
              && polygon.xpoints[polygon.npoints - 1] == x
              && polygon.ypoints[polygon.npoints - 1] == y;
      if (!sameAsLast) {
        polygon.addPoint(x, y);
      }
    }
    if (polygon.npoints > 1
        && polygon.xpoints[0] == polygon.xpoints[polygon.npoints - 1]
        && polygon.ypoints[0] == polygon.ypoints[polygon.npoints - 1]) {
      polygon.npoints--;
    }
    return polygon;
  }

  private static double area(final Polygon polygon) {
    double twice = 0;
    for (int i = 0; i < polygon.npoints; i++) {
      final int j = (i + 1) % polygon.npoints;
      twice += (double) polygon.xpoints[i] * polygon.ypoints[j];
      twice -= (double) polygon.xpoints[j] * polygon.ypoints[i];
    }
    return Math.abs(twice) / 2;
  }

  /** The polygon's centroid, inside it as every cell is convex. */
  private static Point centerOf(final Polygon polygon) {
    double twice = 0;
    double cx = 0;
    double cy = 0;
    for (int i = 0; i < polygon.npoints; i++) {
      final int j = (i + 1) % polygon.npoints;
      final double cross =
          (double) polygon.xpoints[i] * polygon.ypoints[j]
              - (double) polygon.xpoints[j] * polygon.ypoints[i];
      twice += cross;
      cx += (polygon.xpoints[i] + polygon.xpoints[j]) * cross;
      cy += (polygon.ypoints[i] + polygon.ypoints[j]) * cross;
    }
    final double factor = 1 / (3 * twice);
    return new Point((int) Math.rint(cx * factor), (int) Math.rint(cy * factor));
  }

  /**
   * Which cells are neighbours: cells that share a piece of border, as a brick does with the two
   * bricks under it; with diagonals, a shared corner is enough. Only cells near each other in the
   * lattice are compared.
   */
  private static List<Neighbours> connections(
      final List<Cell> cells, final Map<Cell, String> names, final Pattern pattern) {
    final Map<Long, Cell> byPlace = new HashMap<>();
    cells.forEach(cell -> byPlace.put(place(cell.row(), cell.column()), cell));
    final boolean cornersCount = pattern == Pattern.SQUARES_DIAGONAL;
    final List<Neighbours> connections = new ArrayList<>();
    for (final Cell cell : cells) {
      final Set<Long> corners = corners(cell.outline());
      // each pair once: only the cells after this one in the lattice
      for (int dr = 0; dr <= 1; dr++) {
        for (int dc = -2; dc <= 2; dc++) {
          final Cell other = byPlace.get(place(cell.row() + dr, cell.column() + dc));
          if (other == null || (dr == 0 && dc <= 0)) {
            continue;
          }
          final Set<Long> shared = new HashSet<>(corners);
          shared.retainAll(corners(other.outline()));
          if ((cornersCount && !shared.isEmpty())
              || sharesBorder(cell.outline(), other.outline())) {
            connections.add(new Neighbours(names.get(cell), names.get(other)));
          }
        }
      }
    }
    return connections;
  }

  /** Whether the outlines run along each other somewhere: an edge of each on one line, overlapping. */
  static boolean sharesBorder(final Polygon a, final Polygon b) {
    for (int i = 0; i < a.npoints; i++) {
      final int i2 = (i + 1) % a.npoints;
      for (int j = 0; j < b.npoints; j++) {
        final int j2 = (j + 1) % b.npoints;
        if (overlap(
            new Point(a.xpoints[i], a.ypoints[i]),
            new Point(a.xpoints[i2], a.ypoints[i2]),
            new Point(b.xpoints[j], b.ypoints[j]),
            new Point(b.xpoints[j2], b.ypoints[j2]))) {
          return true;
        }
      }
    }
    return false;
  }

  /** Whether the segments p and q lie on one line and overlap by more than a point. */
  private static boolean overlap(final Point p1, final Point p2, final Point q1, final Point q2) {
    final long px1 = p1.x;
    final long py1 = p1.y;
    final long qx1 = q1.x;
    final long qy1 = q1.y;
    final long qx2 = q2.x;
    final long qy2 = q2.y;
    final long dx = p2.x - px1;
    final long dy = p2.y - py1;
    final long length = dx * dx + dy * dy;
    if (length == 0) {
      return false;
    }
    final boolean collinear =
        dx * (qy1 - py1) - dy * (qx1 - px1) == 0 && dx * (qy2 - py1) - dy * (qx2 - px1) == 0;
    if (!collinear) {
      return false;
    }
    // where q's ends lie along p, in parts of p's length
    final double t1 = (double) (dx * (qx1 - px1) + dy * (qy1 - py1)) / length;
    final double t2 = (double) (dx * (qx2 - px1) + dy * (qy2 - py1)) / length;
    return Math.min(1, Math.max(t1, t2)) - Math.max(0, Math.min(t1, t2)) > 0;
  }

  private static long place(final int row, final int column) {
    return ((long) row << 32) ^ (column & 0xffffffffL);
  }

  private static Set<Long> corners(final Polygon polygon) {
    final Set<Long> corners = new TreeSet<>();
    for (int i = 0; i < polygon.npoints; i++) {
      corners.add(place(polygon.xpoints[i], polygon.ypoints[i]));
    }
    return corners;
  }
}
