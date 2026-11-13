package eddies.mapeditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Finds the textures a map has painted into its picture or relief, eg forest and open sea: each
 * territory's inner pixels are compared by their middle color and how much pixels close by differ
 * (the median color and the mean without the highest tenth, so a name written across it hardly
 * counts), alike territories make one texture, and a tile is cut from one of the biggest, where it
 * has the fewest pixels unlike the texture: one period of the pattern where it repeats, else a
 * square whose edges are faded into each other, so it is seamless either way.
 */
final class TextureFinder {
  static final int MAX_TILE = 256;
  // pixels left out along a territory's border, so border and coast lines do not count
  private static final int ERODE = 4;
  private static final int MAX_SAMPLES = 4000;
  private static final int MIN_SAMPLES = 60;
  // a see-through relief pixel shows the picture below, so it is none of the territory's
  private static final int MIN_ALPHA = 200;
  // how far apart the pixels compared for detail are: wide bands of a pattern count too
  private static final int REACH = 2;
  // below this a territory is a flat color, no texture
  private static final double MIN_DETAIL = 1.5;
  // alike: the channels' differences summed at most this, the detail within this share
  private static final int ALIKE_COLOR = 36;
  private static final double ALIKE_DETAIL = 0.4;
  private static final int MIN_PERIOD = 8;
  // a smaller square is a marker or a picture, eg a ring's, rather than a textured land
  private static final int MIN_SQUARE = 48;
  // a baked tile repeats nearly exactly: this share of the pixels a period apart differ at most
  // this much in brightness, where a pattern only alike, eg curved waves, differs more
  private static final double PERIOD_SAME = 0.85;
  private static final int SAME = 3;
  // the tile is cut from the one of the biggest few with the fewest pixels unlike the texture,
  // eg of a name written across it
  private static final int TILE_CANDIDATES = 3;
  private static final int UNLIKE = 3 * ALIKE_COLOR;

  private TextureFinder() {}

  /**
   * A texture found in the map.
   *
   * @param tile seamless, repeated over the map
   * @param offset where the tile was taken, so it lines up with the map there
   * @param territories those that have it, the one the tile was taken from first
   */
  record Found(BufferedImage tile, Point offset, boolean water, List<String> territories) {}

  /** A territory's look, from its inner pixels. */
  private record Look(String territory, boolean water, int[] color, double detail, int samples) {}

  /** The textures in the picture, those of the most territories first; flat ones have none. */
  static List<Found> find(
      final BufferedImage picture,
      final Map<String, List<Polygon>> polygons,
      final Set<String> water) {
    final List<Look> looks = new ArrayList<>();
    polygons.forEach(
        (territory, outlines) ->
            look(picture, territory, outlines, water.contains(territory))
                .filter(look -> look.detail() >= MIN_DETAIL)
                .ifPresent(looks::add));
    looks.sort(Comparator.comparingInt(Look::samples).reversed());
    final List<List<Look>> groups = new ArrayList<>();
    for (final Look look : looks) {
      groups.stream()
          .filter(group -> alike(group.getFirst(), look))
          .findFirst()
          .ifPresentOrElse(
              group -> group.add(look), () -> groups.add(new ArrayList<>(List.of(look))));
    }
    final List<Found> found = new ArrayList<>();
    for (final List<Look> group : groups) {
      group.stream()
          .limit(TILE_CANDIDATES)
          .flatMap(look -> tileOf(picture, polygons.get(look.territory()), look.color()).stream())
          .min(Comparator.comparingDouble(Tile::unlike))
          .ifPresent(
              tile ->
                  found.add(
                      new Found(
                          tile.image(),
                          tile.at(),
                          group.getFirst().water(),
                          group.stream().map(Look::territory).toList())));
    }
    found.sort(Comparator.comparingInt((Found f) -> f.territories().size()).reversed());
    return List.copyOf(found);
  }

  /** The files with each texture's territories and offset, by the name it is taken as. */
  static MapFiles withFound(final MapFiles files, final Map<String, Found> taken) {
    MapFiles textured = files;
    for (final Map.Entry<String, Found> texture : taken.entrySet()) {
      final Point at = texture.getValue().offset();
      // 0,0 needs no entry, as MapProject.setTextureOffset writes it
      textured =
          textured.withProperty(
              MapFiles.TEXTURE_OFFSET + texture.getKey(),
              at.x == 0 && at.y == 0 ? "" : at.x + "," + at.y);
      for (final String territory : texture.getValue().territories()) {
        textured = textured.withTexture(territory, Optional.of(texture.getKey()));
      }
    }
    return textured;
  }

  /** Writes each tile into the map's textures folder under its name, as imports are written. */
  static void write(final Path mapFolder, final Map<String, Found> taken) throws IOException {
    for (final Map.Entry<String, Found> texture : taken.entrySet()) {
      final Path target = mapFolder.resolve(Textures.FOLDER).resolve(texture.getKey());
      Files.createDirectories(target.getParent());
      MapFolder.replace(
          target, to -> ImageIO.write(texture.getValue().tile(), "png", to.toFile()));
    }
  }

  private static boolean alike(final Look a, final Look b) {
    final int color =
        Math.abs(a.color()[0] - b.color()[0])
            + Math.abs(a.color()[1] - b.color()[1])
            + Math.abs(a.color()[2] - b.color()[2]);
    return a.water() == b.water()
        && color <= ALIKE_COLOR
        && Math.abs(a.detail() - b.detail())
            <= Math.max(1, ALIKE_DETAIL * Math.max(a.detail(), b.detail()));
  }

  /** The territory's inner pixels and their bounds in the picture. */
  private record Inner(Rectangle bounds, byte[] mask) {
    boolean has(final int x, final int y) {
      return bounds.contains(x, y) && mask[(y - bounds.y) * bounds.width + x - bounds.x] != 0;
    }
  }

  /** Its outlines filled, less a band along them, within the picture; empty if nothing is left. */
  private static Optional<Inner> inner(final BufferedImage picture, final List<Polygon> outlines) {
    final Rectangle bounds =
        outlines.stream()
            .map(Polygon::getBounds)
            .reduce(Rectangle::union)
            .orElseGet(Rectangle::new)
            .intersection(new Rectangle(picture.getWidth(), picture.getHeight()));
    if (bounds.isEmpty()) {
      return Optional.empty();
    }
    final BufferedImage mask =
        new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_BYTE_GRAY);
    final Graphics2D g = mask.createGraphics();
    g.translate(-bounds.x, -bounds.y);
    g.setColor(Color.WHITE);
    outlines.forEach(g::fill);
    g.setColor(Color.BLACK);
    g.setStroke(new BasicStroke(2 * ERODE + 1f));
    outlines.forEach(g::draw);
    g.dispose();
    return Optional.of(
        new Inner(bounds, ((DataBufferByte) mask.getRaster().getDataBuffer()).getData()));
  }

  private static Optional<Look> look(
      final BufferedImage picture,
      final String territory,
      final List<Polygon> outlines,
      final boolean water) {
    final Optional<Inner> found = inner(picture, outlines);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    final Inner inner = found.get();
    final Rectangle b = inner.bounds();
    int count = 0;
    for (final byte on : inner.mask()) {
      count += on != 0 ? 1 : 0;
    }
    final int step = Math.max(1, (int) Math.sqrt(count / (double) MAX_SAMPLES));
    final List<int[]> colors = new ArrayList<>();
    final List<Integer> details = new ArrayList<>();
    for (int y = b.y; y < b.y + b.height - REACH; y += step) {
      for (int x = b.x; x < b.x + b.width - REACH; x += step) {
        if (inner.has(x, y) && inner.has(x + REACH, y) && inner.has(x, y + REACH)) {
          final int p = picture.getRGB(x, y);
          final int right = picture.getRGB(x + REACH, y);
          final int below = picture.getRGB(x, y + REACH);
          if (opaque(p) && opaque(right) && opaque(below)) {
            colors.add(new int[] {p >> 16 & 0xFF, p >> 8 & 0xFF, p & 0xFF});
            details.add(
                Math.abs(luminance(p) - luminance(right))
                    + Math.abs(luminance(p) - luminance(below)));
          }
        }
      }
    }
    if (colors.size() < MIN_SAMPLES) {
      return Optional.empty();
    }
    final int[] color = new int[3];
    for (int channel = 0; channel < 3; channel++) {
      final int c = channel;
      color[channel] = median(colors.stream().mapToInt(rgb -> rgb[c]).toArray());
    }
    return Optional.of(
        new Look(
            territory,
            water,
            color,
            trimmedMean(details.stream().mapToInt(Integer::intValue).toArray()),
            colors.size()));
  }

  private static boolean opaque(final int argb) {
    return argb >>> 24 >= MIN_ALPHA;
  }

  private static int luminance(final int rgb) {
    return ((rgb >> 16 & 0xFF) * 3 + (rgb >> 8 & 0xFF) * 6 + (rgb & 0xFF)) / 10;
  }

  /** The mean without the highest tenth, eg of a name's letters. */
  private static double trimmedMean(final int[] values) {
    Arrays.sort(values);
    return Arrays.stream(values, 0, Math.max(1, values.length * 9 / 10)).average().orElse(0);
  }

  private static int median(final int[] values) {
    Arrays.sort(values);
    return values[values.length / 2];
  }

  /** A tile, where it was cut, and the share of the square's pixels unlike the texture. */
  private record Tile(BufferedImage image, Point at, double unlike) {}

  /**
   * A seamless tile from the biggest square wholly inside the territory: one period of the
   * pattern along each way it repeats, the edges faded into each other along the others.
   */
  private static Optional<Tile> tileOf(
      final BufferedImage picture, final List<Polygon> outlines, final int[] color) {
    final Optional<Inner> found = inner(picture, outlines);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    final Inner inner = found.get();
    final Rectangle b = inner.bounds();
    // the biggest square of opaque inner pixels ending at each pixel, at its bottom right
    final int[] sizes = new int[b.width * b.height];
    int best = 0;
    int bestAt = 0;
    for (int y = 0; y < b.height; y++) {
      for (int x = 0; x < b.width; x++) {
        final int i = y * b.width + x;
        if (inner.mask()[i] != 0 && opaque(picture.getRGB(b.x + x, b.y + y))) {
          final int up = y == 0 ? 0 : sizes[i - b.width];
          final int left = x == 0 ? 0 : sizes[i - 1];
          final int upLeft = x == 0 || y == 0 ? 0 : sizes[i - b.width - 1];
          sizes[i] = 1 + Math.min(up, Math.min(left, upLeft));
          if (sizes[i] > best) {
            best = sizes[i];
            bestAt = i;
          }
        }
      }
    }
    if (best < MIN_SQUARE) {
      return Optional.empty();
    }
    // a bit smaller than the biggest, so there are places to choose from, away from names
    final int size = Math.min(MAX_TILE, Math.max(MIN_SQUARE, best * 3 / 4));
    final int stride = Math.max(1, size / 4);
    Point at = new Point();
    int[] square = new int[0];
    int unlike = Integer.MAX_VALUE;
    for (int y = size - 1; y < b.height && unlike > 0; y += stride) {
      for (int x = size - 1; x < b.width && unlike > 0; x += stride) {
        if (sizes[y * b.width + x] >= size) {
          final Point corner = new Point(b.x + x - size + 1, b.y + y - size + 1);
          final int[] pixels = picture.getRGB(corner.x, corner.y, size, size, null, 0, size);
          final int count = unlikeCount(pixels, color);
          if (count < unlike) {
            at = corner;
            square = pixels;
            unlike = count;
          }
        }
      }
    }
    if (square.length == 0) {
      final Point corner =
          new Point(b.x + bestAt % b.width - size + 1, b.y + bestAt / b.width - size + 1);
      at = corner;
      square = picture.getRGB(corner.x, corner.y, size, size, null, 0, size);
      unlike = unlikeCount(square, color);
    }
    final Pixels across = seamless(new Pixels(square, size, size));
    final Pixels down = seamless(across.turned()).turned();
    final BufferedImage tile =
        new BufferedImage(down.width(), down.height(), BufferedImage.TYPE_INT_RGB);
    tile.setRGB(0, 0, down.width(), down.height(), down.argb(), 0, down.width());
    return Optional.of(new Tile(tile, at, unlike / (double) square.length));
  }

  /** How many pixels are far from the texture's color, eg of a name written across it. */
  private static int unlikeCount(final int[] pixels, final int[] color) {
    int unlike = 0;
    for (final int p : pixels) {
      final int difference =
          Math.abs((p >> 16 & 0xFF) - color[0])
              + Math.abs((p >> 8 & 0xFF) - color[1])
              + Math.abs((p & 0xFF) - color[2]);
      unlike += difference > UNLIKE ? 1 : 0;
    }
    return unlike;
  }

  /** Pixels in rows, to work on one way and turn for the other. */
  private record Pixels(int[] argb, int width, int height) {
    int at(final int x, final int y) {
      return argb[y * width + x];
    }

    /** Rows become columns. */
    Pixels turned() {
      final int[] turned = new int[argb.length];
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          turned[x * height + y] = argb[y * width + x];
        }
      }
      return new Pixels(turned, height, width);
    }
  }

  /** Seamless from left to right: one period if it repeats that way, else its ends faded. */
  private static Pixels seamless(final Pixels square) {
    final OptionalInt period = period(square);
    if (period.isPresent()) {
      final int width = period.getAsInt();
      final int[] cut = new int[width * square.height()];
      for (int y = 0; y < square.height(); y++) {
        System.arraycopy(square.argb(), y * square.width(), cut, y * width, width);
      }
      return new Pixels(cut, width, square.height());
    }
    // the first quarter faded into the part beyond the tile's end, which it follows on the map
    final int width = square.width() * 4 / 5;
    final int fade = square.width() - width;
    final int[] faded = new int[width * square.height()];
    for (int y = 0; y < square.height(); y++) {
      for (int x = 0; x < width; x++) {
        faded[y * width + x] =
            x < fade
                ? mix(square.at(x + width, y), square.at(x, y), x / (float) fade)
                : square.at(x, y);
      }
    }
    return new Pixels(faded, width, square.height());
  }

  /**
   * The pattern's period from left to right: the shortest shift at which nearly all pixels are
   * as before, as a baked tile repeats, and that is clearly more alike than a shorter shift, so a
   * smooth texture, alike at any small shift, has none.
   */
  private static OptionalInt period(final Pixels square) {
    final int most = square.width() / 2;
    double mostApart = 0;
    for (int shift = 1; shift <= most; shift++) {
      long difference = 0;
      long same = 0;
      for (int y = 0; y < square.height(); y++) {
        for (int x = 0; x + shift < square.width(); x++) {
          final int d =
              Math.abs(luminance(square.at(x, y)) - luminance(square.at(x + shift, y)));
          difference += d;
          same += d <= SAME ? 1 : 0;
        }
      }
      final double pixels = (square.width() - shift) * (double) square.height();
      final double apart = difference / pixels;
      if (shift >= MIN_PERIOD && same >= PERIOD_SAME * pixels && apart <= mostApart / 2) {
        return OptionalInt.of(shift);
      }
      mostApart = Math.max(mostApart, apart);
    }
    return OptionalInt.empty();
  }

  private static int mix(final int from, final int to, final float share) {
    int mixed = 0xFF000000;
    for (int shift = 0; shift <= 16; shift += 8) {
      final int a = from >> shift & 0xFF;
      final int b = to >> shift & 0xFF;
      mixed |= Math.round(a + (b - a) * share) << shift;
    }
    return mixed;
  }
}
