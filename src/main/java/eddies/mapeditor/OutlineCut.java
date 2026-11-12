package eddies.mapeditor;

import java.awt.BasicStroke;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/** Cuts a territory's outlines in two along a line drawn on the map, for a split territory. */
@UtilityClass
class OutlineCut {
  // found outlines are 4 px apart across a 3 px border line: 5 px on each side reaches over it
  private static final Stroke NEAR = new BasicStroke(10);
  private static final double SLIVER = 4;

  /** The outlines the territory keeps, the larger part and those the line missed, and the cut off. */
  record Cut(List<Polygon> kept, List<Polygon> split) {}

  /**
   * The outlines cut along the line, when it crosses one outline once from outside to outside;
   * empty when it cuts nothing, a line ending inside only makes a slit, or it cuts into more parts.
   */
  static Optional<Cut> cut(final List<Polygon> outlines, final List<Point> line) {
    final Path2D path = new Path2D.Double();
    path.moveTo(line.get(0).x, line.get(0).y);
    line.stream().skip(1).forEach(p -> path.lineTo(p.x, p.y));
    final Area blade =
        new Area(
            new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL)
                .createStrokedShape(path));
    final List<Polygon> kept = new ArrayList<>();
    Optional<Polygon> split = Optional.empty();
    for (final Polygon outline : outlines) {
      final Area rest = new Area(outline);
      rest.subtract(blade);
      final List<Polygon> parts = parts(rest);
      if (parts.size() <= 1) {
        kept.add(outline);
      } else if (parts.size() > 2 || split.isPresent()) {
        return Optional.empty();
      } else {
        final boolean firstLarger = area(parts.get(0)) >= area(parts.get(1));
        kept.add(parts.get(firstLarger ? 0 : 1));
        split = Optional.of(parts.get(firstLarger ? 1 : 0));
      }
    }
    return split.map(s -> new Cut(List.copyOf(kept), List.of(s)));
  }

  /** The area's outer rings as whole-pixel polygons, without holes and slivers. */
  private static List<Polygon> parts(final Area area) {
    final List<double[]> rings = new ArrayList<>();
    final List<Double> ring = new ArrayList<>();
    final double[] at = new double[6];
    for (final PathIterator it = area.getPathIterator(null, 0.5); !it.isDone(); it.next()) {
      final int kind = it.currentSegment(at);
      if (kind == PathIterator.SEG_MOVETO) {
        ring.clear();
      }
      if (kind == PathIterator.SEG_CLOSE) {
        rings.add(ring.stream().mapToDouble(Double::doubleValue).toArray());
        ring.clear();
      } else {
        ring.add(at[0]);
        ring.add(at[1]);
      }
    }
    final double outer =
        Math.signum(
            rings.stream()
                .map(OutlineCut::signedArea)
                .max(Comparator.comparingDouble(Math::abs))
                .orElse(0.0));
    return rings.stream()
        .filter(r -> Math.signum(signedArea(r)) == outer && Math.abs(signedArea(r)) >= SLIVER)
        .map(OutlineCut::rounded)
        .filter(p -> p.npoints >= 3)
        .toList();
  }

  private static double signedArea(final double[] xy) {
    double sum = 0;
    for (int i = 0; i < xy.length; i += 2) {
      final int next = (i + 2) % xy.length;
      sum += xy[i] * xy[next + 1] - xy[next] * xy[i + 1];
    }
    return sum / 2;
  }

  private static double area(final Polygon polygon) {
    final double[] xy = new double[polygon.npoints * 2];
    for (int i = 0; i < polygon.npoints; i++) {
      xy[2 * i] = polygon.xpoints[i];
      xy[2 * i + 1] = polygon.ypoints[i];
    }
    return Math.abs(signedArea(xy));
  }

  private static Polygon rounded(final double[] xy) {
    final Polygon polygon = new Polygon();
    for (int i = 0; i < xy.length; i += 2) {
      final int x = (int) Math.round(xy[i]);
      final int y = (int) Math.round(xy[i + 1]);
      final int last = polygon.npoints - 1;
      if (last < 0 || polygon.xpoints[last] != x || polygon.ypoints[last] != y) {
        polygon.addPoint(x, y);
      }
    }
    final int last = polygon.npoints - 1;
    if (last > 0
        && polygon.xpoints[last] == polygon.xpoints[0]
        && polygon.ypoints[last] == polygon.ypoints[0]) {
      polygon.npoints--;
    }
    return polygon;
  }

  /** Whether the outlines lie at or a border line's width from the others. */
  static boolean touches(final List<Polygon> outlines, final List<Polygon> others) {
    for (final Polygon outline : outlines) {
      final Rectangle near = outline.getBounds();
      near.grow(5, 5);
      Optional<Area> grown = Optional.empty();
      for (final Polygon other : others) {
        if (near.intersects(other.getBounds())) {
          if (grown.isEmpty()) {
            final Area area = new Area(NEAR.createStrokedShape(outline));
            area.add(new Area(outline));
            grown = Optional.of(area);
          }
          final Area overlap = new Area(other);
          overlap.intersect(grown.get());
          if (!overlap.isEmpty()) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
