package eddies.mapeditor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/**
 * A relief made from the territories' outlines, for a map maker without a paint program: a dark
 * line and a soft shadow along the coasts, wave lines in the sea beside them, and land raised a
 * little at its borders with light from the top left; see-through everywhere else, as the game
 * draws the relief over the map. A territory with a texture ({@link Textures}) has it under all
 * that, so its borders stay, at the texture's own alpha: its see-through parts add nothing.
 */
final class ReliefMaker {
  /** What the relief has besides the coasts and the raised land. */
  enum Touch {
    WAVES,
    TEXTURE
  }

  private static final byte NONE = 0;
  private static final byte WATER = 1;
  private static final byte LAND = 2;
  // distances in fifths of a pixel (chamfer 5-7-11), at most 51 pixels in a byte
  private static final int UNIT = 5;
  private static final int FAR = 255;
  // the neighbours already passed going forward, with their distance; going back mirrored
  private static final int[][] STEPS = {
    {-1, 0, 5}, {-1, -1, 7}, {0, -1, 5}, {1, -1, 7},
    {-2, -1, 11}, {-1, -2, 11}, {1, -2, 11}, {2, -1, 11}
  };
  // a coast's line may lie in a gap between the outlines up to this wide
  private static final float GAP = 8;
  private static final float BEVEL = 6;
  private static final float SHALLOWS = 12;
  private static final float SHADOW = 10;
  private static final float[] WAVES = {5, 9.5f, 15};
  private static final float[] WAVE_ALPHAS = {0.3f, 0.2f, 0.12f};
  // a texture covers nearly all of the map below, as a painted one would; the map shows it so too
  static final float TEXTURE_ALPHA = 0.9f;
  private static final int MAX_FILLS = 255;

  private ReliefMaker() {}

  /**
   * The relief of the area of the map; for the whole map the area is its size.
   *
   * @param strength how strong the relief is drawn, 50 as meant, up to 100
   * @param fills the textured territories' fills, by territory
   */
  static BufferedImage make(
      final Rectangle area,
      final Map<String, List<Polygon>> polygons,
      final Set<String> water,
      final int strength,
      final Set<Touch> touches,
      final Map<String, Textures.Fill> fills) {
    final int width = area.width;
    final int height = area.height;
    final byte[] kind = kinds(area, polygons, water);
    final List<String> textured =
        polygons.keySet().stream()
            .filter(fills::containsKey)
            // land over sea, as kinds draws them
            .sorted(Comparator.comparing(t -> !water.contains(t)))
            .toList();
    // territories of one texture share its fill, so its pixels are taken once
    // shortcut: at most 255 different fills, the rest left out; a short index if maps need more
    final List<Textures.Fill> distinct =
        textured.stream().map(fills::get).distinct().limit(MAX_FILLS).toList();
    // without textures no index: on a big map it would take as much memory as the kinds
    final byte[] fillOf =
        distinct.isEmpty()
            ? new byte[0]
            : fillIndex(area, polygons, textured, t -> distinct.indexOf(fills.get(t)));
    final int[][] texels = distinct.stream().map(f -> pixels(f.picture())).toArray(int[][]::new);
    final byte[] toWater = distances(kind, width, height, WATER);
    final byte[] toLand = distances(kind, width, height, LAND);
    final byte[] toBorder = distancesFromOther(kind, width, height);
    final BufferedImage relief = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    final int[] out = ((DataBufferInt) relief.getRaster().getDataBuffer()).getData();
    final float scale = strength / 50f;
    IntStream.range(0, height)
        .parallel()
        .forEach(
            y -> {
              for (int x = 0; x < width; x++) {
                final int i = y * width + x;
                final Pixel pixel = new Pixel(scale);
                final int f = fillOf.length == 0 ? 0 : fillOf[i] & 0xFF;
                if (f > 0) {
                  final Textures.Fill fill = distinct.get(f - 1);
                  final int w = fill.picture().getWidth();
                  final int h = fill.picture().getHeight();
                  final int tx = Math.floorMod(x + area.x - fill.offset().x, w);
                  final int ty = Math.floorMod(y + area.y - fill.offset().y, h);
                  final int texel = texels[f - 1][ty * w + tx];
                  // a see-through texture pixel leaves the relief as it is
                  pixel.overColor(texel, TEXTURE_ALPHA * (texel >>> 24) / 255f);
                }
                shade(pixel, i, x, y, width, height, kind, toWater, toLand, toBorder);
                if (touches.contains(Touch.WAVES) && kind[i] == WATER) {
                  waves(pixel, (toLand[i] & 0xFF) / (float) UNIT);
                }
                if (touches.contains(Touch.TEXTURE) && kind[i] == LAND) {
                  final float n = noise(x + area.x, y + area.y);
                  pixel.over(n > 0 ? 1 : 0, 0.08f * Math.abs(n));
                }
                coastLine(pixel, i, kind, toWater, toLand);
                out[i] = pixel.argb();
              }
            });
    return relief;
  }

  /** Which pixels are water, land or neither; land borders are left out, so they are edges. */
  private static byte[] kinds(
      final Rectangle area, final Map<String, List<Polygon>> polygons, final Set<String> water) {
    final byte[] gray = {0, (byte) 255, (byte) 128};
    final IndexColorModel colors = new IndexColorModel(8, 3, gray, gray, gray);
    final BufferedImage kinds =
        new BufferedImage(
            area.width, area.height, BufferedImage.TYPE_BYTE_INDEXED, colors);
    final Graphics2D g = kinds.createGraphics();
    g.translate(-area.x, -area.y);
    g.setColor(new Color(255, 255, 255));
    polygons.forEach((t, shapes) -> fillIf(g, shapes, water.contains(t)));
    g.setColor(new Color(128, 128, 128));
    polygons.forEach((t, shapes) -> fillIf(g, shapes, !water.contains(t)));
    g.setColor(Color.BLACK);
    polygons.entrySet().stream()
        .filter(e -> !water.contains(e.getKey()))
        .flatMap(e -> e.getValue().stream())
        .forEach(g::drawPolygon);
    g.dispose();
    return ((DataBufferByte) kinds.getRaster().getDataBuffer()).getData();
  }

  /** Which fill each pixel has, its index counted from 1; 0 for none. */
  private static byte[] fillIndex(
      final Rectangle area,
      final Map<String, List<Polygon>> polygons,
      final List<String> textured,
      final ToIntFunction<String> fillOf) {
    // a palette whose entry i is the color (i, 0, 0), so a fill in that color writes i
    final byte[] reds = new byte[256];
    for (int i = 0; i < reds.length; i++) {
      reds[i] = (byte) i;
    }
    final byte[] none = new byte[256];
    final BufferedImage index =
        new BufferedImage(
            area.width,
            area.height,
            BufferedImage.TYPE_BYTE_INDEXED,
            new IndexColorModel(8, 256, reds, none, none));
    final Graphics2D g = index.createGraphics();
    g.translate(-area.x, -area.y);
    for (final String territory : textured) {
      final int fill = fillOf.applyAsInt(territory);
      if (fill >= 0) {
        g.setColor(new Color(fill + 1, 0, 0));
        polygons.get(territory).forEach(g::fill);
      }
    }
    g.dispose();
    return ((DataBufferByte) index.getRaster().getDataBuffer()).getData();
  }

  private static int[] pixels(final BufferedImage picture) {
    final int width = picture.getWidth();
    return picture.getRGB(0, 0, width, picture.getHeight(), null, 0, width);
  }

  private static void fillIf(final Graphics2D g, final List<Polygon> shapes, final boolean fill) {
    if (fill) {
      shapes.forEach(g::fill);
    }
  }

  /** How far each pixel is from the nearest pixel of the kind. */
  private static byte[] distances(
      final byte[] kind, final int width, final int height, final byte from) {
    final byte[] distance = new byte[kind.length];
    for (int i = 0; i < kind.length; i++) {
      distance[i] = (byte) (kind[i] == from ? 0 : FAR);
    }
    return chamfer(distance, width, height);
  }

  /** How far each land pixel is from the nearest pixel that is no land: its territory's edge. */
  private static byte[] distancesFromOther(final byte[] kind, final int width, final int height) {
    final byte[] distance = new byte[kind.length];
    for (int i = 0; i < kind.length; i++) {
      distance[i] = (byte) (kind[i] == LAND ? FAR : 0);
    }
    return chamfer(distance, width, height);
  }

  private static byte[] chamfer(final byte[] distance, final int width, final int height) {
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        relax(distance, width, height, x, y, 1);
      }
    }
    for (int y = height - 1; y >= 0; y--) {
      for (int x = width - 1; x >= 0; x--) {
        relax(distance, width, height, x, y, -1);
      }
    }
    return distance;
  }

  private static void relax(
      final byte[] distance,
      final int width,
      final int height,
      final int x,
      final int y,
      final int direction) {
    final int i = y * width + x;
    int best = distance[i] & 0xFF;
    if (best == 0) {
      return;
    }
    for (final int[] step : STEPS) {
      final int nx = x + step[0] * direction;
      final int ny = y + step[1] * direction;
      if (nx >= 0 && ny >= 0 && nx < width && ny < height) {
        best = Math.min(best, (distance[ny * width + nx] & 0xFF) + step[2]);
      }
    }
    distance[i] = (byte) Math.min(best, FAR);
  }

  /** The shallows, the shadow on the land side of a coast and the raised land borders. */
  private static void shade(
      final Pixel pixel,
      final int i,
      final int x,
      final int y,
      final int width,
      final int height,
      final byte[] kind,
      final byte[] toWater,
      final byte[] toLand,
      final byte[] toBorder) {
    if (kind[i] == WATER) {
      final float d = (toLand[i] & 0xFF) / (float) UNIT;
      if (d < SHALLOWS) {
        final float near = 1 - d / SHALLOWS;
        pixel.over(1, 0.22f * near * near);
      }
      return;
    }
    if (kind[i] != LAND) {
      return;
    }
    final float coast = (toWater[i] & 0xFF) / (float) UNIT;
    if (coast >= 1 && coast < SHADOW + 1) {
      final float near = 1 - (coast - 1) / SHADOW;
      pixel.over(0, 0.4f * near * near);
    }
    final float edge = (toBorder[i] & 0xFF) / (float) UNIT;
    if (edge < BEVEL) {
      final float dx = at(toBorder, width, height, x + 1, y) - at(toBorder, width, height, x - 1, y);
      final float dy = at(toBorder, width, height, x, y + 1) - at(toBorder, width, height, x, y - 1);
      // rising to the bottom right faces the light from the top left
      final float facing = (dx + dy) / (2 * UNIT) / (float) Math.sqrt(2);
      final float lit = Math.max(-1, Math.min(1, facing)) * (1 - edge / BEVEL);
      pixel.over(lit > 0 ? 1 : 0, (lit > 0 ? 0.45f : 0.35f) * Math.abs(lit));
    }
  }

  private static int at(
      final byte[] distance, final int width, final int height, final int x, final int y) {
    return distance[Math.clamp(y, 0, height - 1) * width + Math.clamp(x, 0, width - 1)] & 0xFF;
  }

  /** Thin lines following the coast, fainter further out. */
  private static void waves(final Pixel pixel, final float fromCoast) {
    for (int w = 0; w < WAVES.length; w++) {
      final float on = 1 - Math.abs(fromCoast - WAVES[w]) / 0.75f;
      if (on > 0) {
        pixel.over(0, WAVE_ALPHAS[w] * on);
      }
    }
  }

  /** The dark line where land meets water, in the gap between their outlines if there is one. */
  private static void coastLine(
      final Pixel pixel,
      final int i,
      final byte[] kind,
      final byte[] toWater,
      final byte[] toLand) {
    final float water = (toWater[i] & 0xFF) / (float) UNIT;
    final float land = (toLand[i] & 0xFF) / (float) UNIT;
    final float across =
        switch (kind[i]) {
          case LAND -> water;
          case WATER -> land;
          default -> water + land <= GAP ? water - land : Float.NaN;
        };
    final float on = Math.clamp(1.8f - Math.abs(across), 0, 1);
    if (on > 0) {
      pixel.over(0, 0.85f * on);
    }
  }

  /** Smooth noise in -1 to 1, the same for a pixel however much of the map is made. */
  private static float noise(final int x, final int y) {
    return 0.6f * smooth(x, y, 16) + 0.4f * smooth(x, y, 5);
  }

  private static float smooth(final int x, final int y, final int cell) {
    final int cx = Math.floorDiv(x, cell);
    final int cy = Math.floorDiv(y, cell);
    final float fx = ease(Math.floorMod(x, cell) / (float) cell);
    final float fy = ease(Math.floorMod(y, cell) / (float) cell);
    final float top = hash(cx, cy) + (hash(cx + 1, cy) - hash(cx, cy)) * fx;
    final float bottom = hash(cx, cy + 1) + (hash(cx + 1, cy + 1) - hash(cx, cy + 1)) * fx;
    return top + (bottom - top) * fy;
  }

  private static float ease(final float t) {
    return t * t * (3 - 2 * t);
  }

  private static float hash(final int x, final int y) {
    int h = x * 374_761_393 + y * 668_265_263;
    h = (h ^ (h >>> 13)) * 1_274_126_177;
    return ((h ^ (h >>> 16)) & 0xFFFF) / 32_767.5f - 1;
  }

  /** One pixel of the relief, layers laid over each other: gray ones and textures. */
  private static final class Pixel {
    private final float scale;
    // premultiplied
    private float red;
    private float green;
    private float blue;
    private float alpha;

    Pixel(final float scale) {
      this.scale = scale;
    }

    void over(final float layerGray, final float layerAlpha) {
      over(layerGray, layerGray, layerGray, layerAlpha);
    }

    /** A texture's color at this alpha; the alpha the texture has is the caller's to apply. */
    void overColor(final int rgb, final float layerAlpha) {
      over(
          (rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, layerAlpha);
    }

    private void over(final float r, final float g, final float b, final float layerAlpha) {
      final float a = Math.min(1, layerAlpha * scale);
      red = r * a + red * (1 - a);
      green = g * a + green * (1 - a);
      blue = b * a + blue * (1 - a);
      alpha = a + alpha * (1 - a);
    }

    int argb() {
      if (alpha <= 0) {
        return 0;
      }
      return Math.round(alpha * 255) << 24
          | channel(red) << 16
          | channel(green) << 8
          | channel(blue);
    }

    private int channel(final float premultiplied) {
      return Math.clamp(Math.round(premultiplied / alpha * 255), 0, 255);
    }
  }
}
