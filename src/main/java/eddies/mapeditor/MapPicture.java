package eddies.mapeditor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Finds the territories in a picture of a map: areas separated by dark or thin grey border lines,
 * seas in blue.
 * Each area becomes a territory with its outline and center, areas next to each other across a line
 * become neighbours. Small areas, eg the letters of a name, are left out.
 */
final class MapPicture {
  private static final int DARK = 64;
  // a pixel this much darker than a pixel on both sides within LINE_REACH is part of a thin line
  private static final int LINE_CONTRAST = 40;
  private static final int LINE_REACH = 3;
  // across, down and both diagonals
  private static final int[] LINE_DX = {1, 0, 1, 1};
  private static final int[] LINE_DY = {0, 1, 1, -1};
  // a line pixel's color channels differ less than this: it is grey
  private static final int GREY = 30;
  private static final int REACH = 4;
  private static final int MIN_SUPPORT = 3;
  private static final double EPSILON = 1.0;
  // a drawn line's end is cut back to a border it crosses this much farther than the nearest
  private static final double OVERSHOOT_SLACK = 3;
  // every 4th pixel of a drawn line keeps its shape and hides the stairs of rounding
  private static final int SMOOTH_STEP = 4;
  // how far, in pixels, areas grow into the dark line around them, so outlines lie on the line
  private static final int GROW = 2;
  // the 8 neighbours of a pixel, clockwise on the screen, starting west
  private static final int[] DX = {-1, -1, 0, 1, 1, 1, 0, -1};
  private static final int[] DY = {0, -1, -1, -1, 0, 1, 1, 1};

  /** A territory found in the picture. */
  record DetectedTerritory(String name, boolean water, List<Polygon> outlines, Point center) {}

  /** Two territories next to each other. */
  record Neighbours(String t1, String t2) {}

  /** What the picture shows: its size, the territories and which of them touch. */
  record DetectedMap(
      Dimension size, List<DetectedTerritory> territories, List<Neighbours> connections) {}

  /** An area of the picture: its label and what is summed over its pixels. */
  private record Region(
      int label, int area, long sumX, long sumY, long sumR, long sumG, long sumB, int first) {}

  private final int width;
  private final int height;
  private final int[] rgb;
  // -1 for a border pixel, else the label of its area; after dropping small areas 0 for those
  private final int[] labels;
  // smaller areas are dropped, eg gaps where a line nearly meets another
  private final int minArea;

  private MapPicture(final BufferedImage image, final int minArea) {
    width = image.getWidth();
    height = image.getHeight();
    rgb = image.getRGB(0, 0, width, height, null, 0, width);
    labels = new int[width * height];
    this.minArea = minArea;
  }

  private static int minArea(final BufferedImage image) {
    return Math.max(30, image.getWidth() * image.getHeight() / 20_000);
  }

  /** Reads a picture of a map; an IOException also for a file that is no picture. */
  static BufferedImage read(final Path file) throws IOException {
    final BufferedImage image = ImageIO.read(file.toFile());
    if (image == null) {
      throw new IOException(file.getFileName() + " is no picture the map maker can read");
    }
    return image;
  }

  /** How the picture's border lines are drawn: their width in pixels and their color. */
  record LineStyle(int width, Color color) {}

  /**
   * The width and color most of the picture's border lines have: the commonest run of line pixels
   * across rows, up to 12 pixels, of its dark lines or of its thin grey ones (not the smoothed edge
   * of a dark one), whichever has more pixels; 2 pixels black for a picture without lines.
   */
  static LineStyle lineStyle(final BufferedImage image) {
    // the dark lines without their smoothed edges, or the thin grey ones, whichever has more
    final Optional<Measured> dark = styleOf(image, (x, y) -> isOpaqueDark(image.getRGB(x, y)));
    final Optional<Measured> grey =
        styleOf(
            image,
            (x, y) ->
                !isOpaqueDark(image.getRGB(x, y))
                    && isThinLineAt(image, x, y)
                    && !nearDark(image, x, y));
    return Stream.of(dark, grey)
        .flatMap(Optional::stream)
        .max(Comparator.comparingInt(Measured::pixels))
        .map(Measured::style)
        .orElse(new LineStyle(2, Color.BLACK));
  }

  private static boolean isOpaqueDark(final int argb) {
    return isBorder(argb) && (argb >>> 24) >= 128;
  }

  /** A dark line's pixel within LINE_REACH: a grey one there is its smoothed edge, no line. */
  private static boolean nearDark(final BufferedImage image, final int x, final int y) {
    final int bottom = Math.min(image.getHeight() - 1, y + LINE_REACH);
    final int right = Math.min(image.getWidth() - 1, x + LINE_REACH);
    for (int py = Math.max(0, y - LINE_REACH); py <= bottom; py++) {
      for (int px = Math.max(0, x - LINE_REACH); px <= right; px++) {
        if (isOpaqueDark(image.getRGB(px, py))) {
          return true;
        }
      }
    }
    return false;
  }

  /** A line style and how many of the sampled pixels have it. */
  private record Measured(LineStyle style, int pixels) {}

  /** The commonest run of line pixels across every 4th row, and their commonest color. */
  private static Optional<Measured> styleOf(final BufferedImage image, final LinePixel line) {
    final int[] widths = new int[13];
    final Map<Integer, Integer> colors = new HashMap<>();
    // every 4th row is plenty to find the commonest
    for (int y = 0; y < image.getHeight(); y += 4) {
      int start = -1;
      for (int x = 0; x < image.getWidth(); x++) {
        if (line.at(x, y)) {
          if (start < 0) {
            start = x;
          }
          colors.merge(image.getRGB(x, y) | 0xFF000000, 1, Integer::sum);
        } else {
          if (start > 0 && x - start < widths.length) {
            widths[x - start]++;
          }
          start = -1;
        }
      }
    }
    if (colors.isEmpty()) {
      return Optional.empty();
    }
    int common = 2;
    for (int w = 1; w < widths.length; w++) {
      if (widths[w] > widths[common]) {
        common = w;
      }
    }
    final int color =
        colors.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    return Optional.of(
        new Measured(
            new LineStyle(common, new Color(color)),
            colors.values().stream().mapToInt(Integer::intValue).sum()));
  }

  @FunctionalInterface
  private interface LinePixel {
    boolean at(int x, int y);
  }

  /**
   * The line with each end brought to the border line nearest it, so the areas it cuts close: an
   * end short of a border line is drawn on to it, one just past it is cut back to it, each within
   * reach pixels; the picture's edge counts as a border. A line closed already (its last point its
   * first), or else one whose end comes back within reach of its start, is a loop, closed and kept
   * as it is: it encloses an area on its own, eg an island. Empty when an end is farther from any,
   * or when nothing is left off the border lines.
   */
  static Optional<List<Point>> snapToBorders(
      final BufferedImage image, final List<Point> line, final int reach) {
    final Optional<List<Point>> loop = closed(line, reach);
    if (line.getFirst().equals(line.getLast()) && loop.isPresent()) {
      return loop;
    }
    return snapStart(image, everyPixel(line), reach)
        .flatMap(snapped -> snapStart(image, snapped.reversed(), reach))
        .map(List::reversed)
        .filter(snapped -> snapped.stream().anyMatch(p -> !isBorderAt(image, p)))
        .map(MapPicture::smoothed)
        .or(() -> loop);
  }

  /**
   * The line closed, its end joined to its start, when they are within reach; empty for a line
   * too short around to enclose more than a dot.
   */
  private static Optional<List<Point>> closed(final List<Point> line, final int reach) {
    if (line.getFirst().distance(line.getLast()) > reach) {
      return Optional.empty();
    }
    final List<Point> loop = new ArrayList<>(line);
    if (!loop.getLast().equals(loop.getFirst())) {
      loop.add(loop.getFirst());
    }
    final List<Point> path = everyPixel(loop);
    return path.size() > 4 * reach ? Optional.of(smoothed(path)) : Optional.empty();
  }

  /** The line's points one pixel apart, so a step cannot jump over a thin line. */
  private static List<Point> everyPixel(final List<Point> line) {
    final List<Point> path = new ArrayList<>(List.of(line.getFirst()));
    for (int i = 1; i < line.size(); i++) {
      final Point from = line.get(i - 1);
      final Point to = line.get(i);
      final int steps = Math.max(Math.abs(to.x - from.x), Math.abs(to.y - from.y));
      for (int step = 1; step <= steps; step++) {
        path.add(
            new Point(
                from.x + Math.round((to.x - from.x) * (float) step / steps),
                from.y + Math.round((to.y - from.y) * (float) step / steps)));
      }
    }
    return path;
  }

  /** Every few of the pixel steps and both ends, so a wide line is not drawn in stairs. */
  private static List<Point> smoothed(final List<Point> path) {
    final List<Point> kept = new ArrayList<>();
    for (int i = 0; i < path.size(); i += SMOOTH_STEP) {
      kept.add(path.get(i));
    }
    if (!kept.getLast().equals(path.getLast())) {
      kept.add(path.getLast());
    }
    return kept;
  }

  /**
   * The path from its start's border line: cut back to a border it crosses about as near as the
   * nearest one, so only an end drawn past a line is cut; else on from the nearest border.
   */
  private static Optional<List<Point>> snapStart(
      final BufferedImage image, final List<Point> path, final int reach) {
    final Optional<Point> nearest = nearestBorder(image, path.getFirst(), reach);
    if (nearest.isEmpty()) {
      return Optional.empty();
    }
    final double cutWithin = path.getFirst().distance(nearest.get()) + OVERSHOOT_SLACK;
    double walked = 0;
    for (int i = 0; i < path.size() && walked <= cutWithin; i++) {
      if (i > 0) {
        walked += path.get(i - 1).distance(path.get(i));
      }
      if (walked <= cutWithin && isBorderAt(image, path.get(i))) {
        return Optional.of(path.subList(i, path.size()));
      }
    }
    final List<Point> extended = new ArrayList<>(List.of(nearest.get()));
    extended.addAll(path);
    return Optional.of(extended);
  }

  /** The border pixel nearest the point within reach, the picture's edge counting as one. */
  private static Optional<Point> nearestBorder(
      final BufferedImage image, final Point at, final int reach) {
    Optional<Point> nearest = Optional.empty();
    double best = reach + 0.5;
    for (int y = at.y - reach; y <= at.y + reach; y++) {
      for (int x = at.x - reach; x <= at.x + reach; x++) {
        final Point candidate = new Point(x, y);
        final double distance = at.distance(candidate);
        if (distance < best && isBorderAt(image, candidate)) {
          best = distance;
          nearest = Optional.of(candidate);
        }
      }
    }
    return nearest;
  }

  /** A pixel of the picture's lines ({@link #lines}), or one outside it: the edge stops areas too. */
  private static boolean isBorderAt(final BufferedImage image, final Point at) {
    if (at.x < 0 || at.y < 0 || at.x >= image.getWidth() || at.y >= image.getHeight()) {
      return true;
    }
    return isBorder(image.getRGB(at.x, at.y)) || isThinLineAt(image, at.x, at.y);
  }

  /** {@link #isThinLine} of a pixel of the picture. */
  private static boolean isThinLineAt(final BufferedImage image, final int x, final int y) {
    final int argb = image.getRGB(x, y);
    // a pixel lighter than this is never that much darker than both its sides
    return luminance(argb) <= 255 - LINE_CONTRAST
        && isGrey(argb)
        && isThinLine(
            (px, py) ->
                px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()
                    ? -1
                    : luminance(image.getRGB(px, py)),
            x,
            y);
  }

  /** A pixel without much color: borders are drawn grey, rivers and names often in color. */
  private static boolean isGrey(final int argb) {
    final int r = (argb >> 16) & 0xFF;
    final int g = (argb >> 8) & 0xFF;
    final int b = argb & 0xFF;
    return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < GREY;
  }

  /**
   * The pixels of the picture's border lines: dark or see-through ones ({@link #isBorder}), and
   * grey ones of a thin line clearly darker than both sides of it, eg between white areas; the
   * edge between a darker and a lighter area is not one, nor a colored line, eg a river.
   */
  private static boolean[] lines(final int[] rgb, final int width, final int height) {
    final int[] luminance = new int[rgb.length];
    for (int i = 0; i < rgb.length; i++) {
      luminance[i] = luminance(rgb[i]);
    }
    final Luminance at =
        (x, y) -> x < 0 || y < 0 || x >= width || y >= height ? -1 : luminance[y * width + x];
    final boolean[] lines = new boolean[rgb.length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int p = y * width + x;
        lines[p] = isBorder(rgb[p]) || isGrey(rgb[p]) && isThinLine(at, x, y);
      }
    }
    return lines;
  }

  /** The luminance of a pixel, -1 outside the picture. */
  @FunctionalInterface
  private interface Luminance {
    int at(int x, int y);
  }

  /**
   * Whether the pixel is at least LINE_CONTRAST darker than a pixel within LINE_REACH on each side
   * of it, across, down or on a diagonal: a line up to 5 pixels wide has such a pixel.
   */
  private static boolean isThinLine(final Luminance luminance, final int x, final int y) {
    final int own = luminance.at(x, y);
    for (int d = 0; d < LINE_DX.length; d++) {
      int before = -1;
      int after = -1;
      for (int k = 1; k <= LINE_REACH; k++) {
        before = Math.max(before, luminance.at(x - LINE_DX[d] * k, y - LINE_DY[d] * k));
        after = Math.max(after, luminance.at(x + LINE_DX[d] * k, y + LINE_DY[d] * k));
      }
      if (Math.min(before, after) >= own + LINE_CONTRAST) {
        return true;
      }
    }
    return false;
  }

  private static int luminance(final int argb) {
    return (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
  }

  static DetectedMap detect(final BufferedImage image) {
    return new MapPicture(image, minArea(image)).detect();
  }

  /**
   * As {@link #detect(BufferedImage)}, only within the box, eg around borders drawn, which is faster
   * on a big picture; areas the box cuts end at its edge. Outlines and centers are the picture's,
   * the areas as small as there are dropped.
   */
  static DetectedMap detect(final BufferedImage image, final Rectangle box) {
    final Rectangle within = box.intersection(new Rectangle(image.getWidth(), image.getHeight()));
    final Dimension size = new Dimension(image.getWidth(), image.getHeight());
    if (within.isEmpty()) {
      return new DetectedMap(size, List.of(), List.of());
    }
    final DetectedMap found =
        new MapPicture(
                image.getSubimage(within.x, within.y, within.width, within.height),
                minArea(image))
            .detect();
    return new DetectedMap(
        size,
        found.territories().stream()
            .map(
                t ->
                    new DetectedTerritory(
                        t.name(),
                        t.water(),
                        t.outlines().stream()
                            .map(
                                o -> {
                                  final Polygon moved = new Polygon(o.xpoints, o.ypoints, o.npoints);
                                  moved.translate(within.x, within.y);
                                  return moved;
                                })
                            .toList(),
                        new Point(t.center().x + within.x, t.center().y + within.y)))
            .toList(),
        found.connections());
  }

  private DetectedMap detect() {
    final List<Region> kept = keptRegions();
    final List<int[]> touching = touching();
    final Map<Integer, Point> centers = new HashMap<>();
    kept.forEach(r -> centers.put(r.label(), center(r)));
    // traced from the first pixel of the grown area in scan order, which has none of it to the west
    final Grown grown = grow();
    final List<Region> ordered =
        kept.stream()
            .sorted(
                Comparator.<Region>comparingInt(r -> centers.get(r.label()).y)
                    .thenComparingInt(r -> centers.get(r.label()).x))
            .toList();
    // a territory's place in the list, to name and order the connections
    final Map<Integer, Integer> rank = new HashMap<>();
    final List<DetectedTerritory> territories = new ArrayList<>();
    int land = 0;
    int sea = 0;
    for (final Region region : ordered) {
      final boolean water = isWater(region);
      rank.put(region.label(), territories.size());
      territories.add(
          new DetectedTerritory(
              water ? "Sea Zone " + ++sea : "Territory " + ++land,
              water,
              List.of(outline(region.label(), grown)),
              centers.get(region.label())));
    }
    final List<Neighbours> connections =
        touching.stream()
            .map(pair -> new int[] {rank.get(pair[0]), rank.get(pair[1])})
            .map(pair -> new int[] {Math.min(pair[0], pair[1]), Math.max(pair[0], pair[1])})
            .sorted(Comparator.<int[]>comparingInt(p -> p[0]).thenComparingInt(p -> p[1]))
            .map(p -> new Neighbours(territories.get(p[0]).name(), territories.get(p[1]).name()))
            .toList();
    return new DetectedMap(new Dimension(width, height), territories, connections);
  }

  /** Labels all areas and keeps those big enough; the others become 0 in the labels. */
  private List<Region> keptRegions() {
    final boolean[] lines = lines(rgb, width, height);
    for (int i = 0; i < rgb.length; i++) {
      labels[i] = lines[i] ? -1 : 0;
    }
    final List<Region> kept = new ArrayList<>();
    final int[] stack = new int[labels.length];
    int next = 1;
    for (int start = 0; start < labels.length; start++) {
      if (labels[start] == 0) {
        final Region region = fill(start, next++, stack);
        if (region.area() >= minArea) {
          kept.add(region);
        }
      }
    }
    final boolean[] isKept = new boolean[next];
    kept.forEach(r -> isKept[r.label()] = true);
    for (int i = 0; i < labels.length; i++) {
      labels[i] = labels[i] > 0 && isKept[labels[i]] ? labels[i] : 0;
    }
    return kept;
  }

  /** A dark or mostly transparent pixel, part of a border line. */
  static boolean isBorder(final int argb) {
    final int alpha = argb >>> 24;
    final int r = (argb >> 16) & 0xFF;
    final int g = (argb >> 8) & 0xFF;
    final int b = argb & 0xFF;
    return alpha < 128 || (r * 299 + g * 587 + b * 114) / 1000 < DARK;
  }

  /** Labels the 4-connected area at the start pixel, with a stack instead of recursion. */
  private Region fill(final int start, final int label, final int[] stack) {
    int top = 0;
    stack[top++] = start;
    labels[start] = label;
    int area = 0;
    long sumX = 0;
    long sumY = 0;
    long sumR = 0;
    long sumG = 0;
    long sumB = 0;
    while (top > 0) {
      final int p = stack[--top];
      final int x = p % width;
      final int y = p / width;
      area++;
      sumX += x;
      sumY += y;
      sumR += (rgb[p] >> 16) & 0xFF;
      sumG += (rgb[p] >> 8) & 0xFF;
      sumB += rgb[p] & 0xFF;
      final int[] around = {
        x > 0 ? p - 1 : -1,
        x < width - 1 ? p + 1 : -1,
        y > 0 ? p - width : -1,
        y < height - 1 ? p + width : -1
      };
      for (final int n : around) {
        if (n >= 0 && labels[n] == 0) {
          labels[n] = label;
          stack[top++] = n;
        }
      }
    }
    return new Region(label, area, sumX, sumY, sumR, sumG, sumB, start);
  }

  private static boolean isWater(final Region region) {
    final long r = region.sumR() / region.area();
    final long g = region.sumG() / region.area();
    final long b = region.sumB() / region.area();
    // pale sea blue too, eg 221 240 246, but not a greenish land
    return b > r + 15 && b > g;
  }

  /**
   * The mean pixel if it is in the area; else the middle of the longest run of the area on the
   * mean's row; else the area's first pixel.
   */
  private Point center(final Region region) {
    final int x = (int) (region.sumX() / region.area());
    final int y = (int) (region.sumY() / region.area());
    if (labels[y * width + x] == region.label()) {
      return new Point(x, y);
    }
    int bestStart = 0;
    int bestLength = 0;
    int runStart = -1;
    for (int column = 0; column <= width; column++) {
      final boolean inside = column < width && labels[y * width + column] == region.label();
      if (inside && runStart < 0) {
        runStart = column;
      } else if (!inside && runStart >= 0) {
        if (column - runStart > bestLength) {
          bestLength = column - runStart;
          bestStart = runStart;
        }
        runStart = -1;
      }
    }
    return bestLength > 0
        ? new Point(bestStart + bestLength / 2, y)
        : new Point(region.first() % width, region.first() / width);
  }

  /**
   * Grows each kept area into the dark line and left out pixels next to it, up to {@value #GROW}
   * pixels, so neighbours' outlines meet on the line instead of beside it.
   *
   * @return each label's first pixel in scan order and its area after growing
   */
  private Grown grow() {
    for (int pass = 0; pass < GROW; pass++) {
      // a pixel taken in this pass is marked -2 - label, so it does not grow on in the same pass
      for (int p = 0; p < labels.length; p++) {
        if (labels[p] <= 0 && labels[p] > -2) {
          final int x = p % width;
          final int y = p / width;
          final int[] around = {
            x > 0 ? labels[p - 1] : 0,
            y > 0 ? labels[p - width] : 0,
            x < width - 1 ? labels[p + 1] : 0,
            y < height - 1 ? labels[p + width] : 0
          };
          for (final int label : around) {
            if (label > 0) {
              labels[p] = -2 - label;
              break;
            }
          }
        }
      }
      for (int p = 0; p < labels.length; p++) {
        if (labels[p] <= -2) {
          labels[p] = -2 - labels[p];
        }
      }
    }
    final int labelCount = Arrays.stream(labels).max().orElse(0) + 1;
    final int[] firsts = new int[labelCount];
    final int[] areas = new int[labelCount];
    for (int p = labels.length - 1; p >= 0; p--) {
      if (labels[p] > 0) {
        firsts[labels[p]] = p;
        areas[labels[p]]++;
      }
    }
    return new Grown(firsts, areas);
  }

  /** By label: the first pixel in scan order and the area, of the grown areas. */
  private record Grown(int[] firsts, int[] areas) {}

  /** The outer contour by Moore-neighbour tracing from the area's first pixel, simplified. */
  private Polygon outline(final int label, final Grown grown) {
    final int startX = grown.firsts()[label] % width;
    final int startY = grown.firsts()[label] / width;
    final List<Point> ring = new ArrayList<>();
    ring.add(new Point(startX, startY));
    int x = startX;
    int y = startY;
    // the first pixel in scan order has nothing of its area to the west
    int backtrack = 0;
    int firstStep = -1;
    final int maxSteps = 8 * grown.areas()[label] + 16;
    for (int step = 0; step < maxSteps; step++) {
      final int found = nextStep(x, y, backtrack, label);
      if (found < 0) {
        break; // a single pixel
      }
      if (x == startX && y == startY) {
        if (found == firstStep) {
          break; // round once, about to go the same way again
        }
        firstStep = firstStep < 0 ? found : firstStep;
      }
      final int beforeX = x + DX[(found + 7) % 8];
      final int beforeY = y + DY[(found + 7) % 8];
      x += DX[found];
      y += DY[found];
      backtrack = direction(beforeX - x, beforeY - y);
      if (x != startX || y != startY) {
        ring.add(new Point(x, y));
      }
    }
    final List<Point> simplified = simplify(ring);
    return new Polygon(
        simplified.stream().mapToInt(p -> p.x).toArray(),
        simplified.stream().mapToInt(p -> p.y).toArray(),
        simplified.size());
  }

  /** The first neighbour in the area, clockwise from the one after the backtrack; -1 for none. */
  private int nextStep(final int x, final int y, final int backtrack, final int label) {
    for (int i = 1; i <= 8; i++) {
      final int d = (backtrack + i) % 8;
      if (labelAt(x + DX[d], y + DY[d]) == label) {
        return d;
      }
    }
    return -1;
  }

  private static int direction(final int dx, final int dy) {
    for (int d = 0; d < 8; d++) {
      if (DX[d] == dx && DY[d] == dy) {
        return d;
      }
    }
    throw new IllegalStateException("Not a neighbour: " + dx + "," + dy);
  }

  /** Douglas–Peucker on the closed ring, with a stack of index pairs instead of recursion. */
  private static List<Point> simplify(final List<Point> ring) {
    if (ring.size() < 4) {
      return ring;
    }
    final List<Point> closed = new ArrayList<>(ring);
    closed.add(ring.get(0));
    final boolean[] keep = new boolean[closed.size()];
    keep[0] = true;
    final List<int[]> stack = new ArrayList<>();
    stack.add(new int[] {0, closed.size() - 1});
    while (!stack.isEmpty()) {
      final int[] span = stack.remove(stack.size() - 1);
      double farthest = 0;
      int index = -1;
      for (int i = span[0] + 1; i < span[1]; i++) {
        final double distance = distance(closed.get(i), closed.get(span[0]), closed.get(span[1]));
        if (distance > farthest) {
          farthest = distance;
          index = i;
        }
      }
      if (index >= 0 && farthest > EPSILON) {
        keep[index] = true;
        stack.add(new int[] {span[0], index});
        stack.add(new int[] {index, span[1]});
      }
    }
    final List<Point> kept = new ArrayList<>();
    // the last point closes the ring, it is the first
    for (int i = 0; i < closed.size() - 1; i++) {
      if (keep[i]) {
        kept.add(closed.get(i));
      }
    }
    return kept;
  }

  /** The distance of the point from the segment's line, or from its start if it has no length. */
  private static double distance(final Point p, final Point a, final Point b) {
    final double dx = b.x - (double) a.x;
    final double dy = b.y - (double) a.y;
    final double length = Math.hypot(dx, dy);
    return length == 0
        ? p.distance(a)
        : Math.abs(dy * p.x - dx * p.y + b.x * (double) a.y - b.y * (double) a.x) / length;
  }

  /**
   * The pairs of area labels that touch: next to each other, or across a line or a left out area
   * with the nearest area at most {@value #REACH} pixels away on both sides, seen from at least
   * {@value #MIN_SUPPORT} pixels.
   */
  private List<int[]> touching() {
    final Map<Long, Integer> support = new HashMap<>();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int p = y * width + x;
        // across the pixel to the right and down: from its own area, or between two others
        for (int across = 0; across < 2; across++) {
          final int dx = 1 - across;
          final int dy = across;
          final long a = labels[p] > 0 ? labels[p] : nearest(x, y, -dx, -dy);
          final long b = labels[p] > 0 ? labelAt(x + dx, y + dy) : nearest(x, y, dx, dy);
          if (a > 0 && b > 0 && a != b) {
            support.merge((Math.min(a, b) << 32) | Math.max(a, b), 1, Integer::sum);
          }
        }
      }
    }
    return support.entrySet().stream()
        .filter(e -> e.getValue() >= MIN_SUPPORT)
        .map(e -> new int[] {(int) (e.getKey() >> 32), (int) (e.getKey() & 0xFFFFFFFFL)})
        .toList();
  }

  /** The label of the nearest kept area in the direction, 0 if none is that close. */
  private int nearest(final int x, final int y, final int dx, final int dy) {
    for (int i = 1; i <= REACH; i++) {
      final int label = labelAt(x + dx * i, y + dy * i);
      if (label > 0) {
        return label;
      }
    }
    return 0;
  }

  /** The label of the pixel, -1 outside the picture. */
  private int labelAt(final int x, final int y) {
    return x < 0 || y < 0 || x >= width || y >= height ? -1 : labels[y * width + x];
  }
}
