package eddies.mapeditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * The textures a territory can be filled with in the relief: premade ones, drawn here so they are
 * free to share, seamless and the same on every run, and the map's own pictures in its textures
 * folder. TripleA has no textures; the relief the editor makes has them baked in.
 */
final class Textures {
  static final String FOLDER = "textures";
  static final List<String> PREMADE =
      List.of(
          "Open sea",
          "Coastal sea",
          "Grass",
          "Forest",
          "Hills",
          "Mountains",
          "Desert",
          "Swamp",
          "Snow");
  static final int SIZE = 256;
  // mountains are big marks: a bigger tile repeats less visibly
  private static final int MOUNTAINS_SIZE = 512;
  private static final List<String> KINDS = List.of("png", "gif", "jpg");
  private static final Map<String, BufferedImage> MADE = new ConcurrentHashMap<>();
  // the open map's own pictures, read again only when changed (guarded by the class)
  private static final Map<Path, Read> READ = new HashMap<>();
  private static Optional<Path> readFrom = Optional.empty();
  // a menu's icon per picture, kept as long as the picture is
  private static final Map<BufferedImage, BufferedImage> ICONS =
      Collections.synchronizedMap(new WeakHashMap<>());
  private static final int ICON = 16;
  // the strokes: dark and nearly without color, as color shows the owner
  private static final Color INK = new Color(50, 44, 38, 140);
  private static final Color WATER_INK = new Color(40, 62, 88, 130);
  // hachures and shade ticks: lighter, so a mark's outline leads
  private static final Color HATCH = new Color(50, 44, 38, 90);
  // a crown's faint fill
  private static final Color SHADE = new Color(50, 44, 38, 35);
  // how far a mark may stand off its square's middle, in squares
  private static final double JITTER = 0.45;
  // the icon shows a part of the texture this big on paper, so its strokes stay visible
  private static final int ICON_PART = 48;
  // a premade one's marks are small: a smaller part keeps them readable
  private static final int PREMADE_ICON_PART = 24;
  private static final Color PAPER = new Color(0xF4, 0xF1, 0xEA);

  private Textures() {}

  /** A texture on a territory: its picture, repeated over the map from the offset on. */
  record Fill(BufferedImage picture, Point offset) {
    TexturePaint paint() {
      return new TexturePaint(
          picture, new Rectangle(offset.x, offset.y, picture.getWidth(), picture.getHeight()));
    }
  }

  /** The premade textures, then the map's own by file name. */
  static List<String> names(final MapFiles files) {
    return Stream.concat(PREMADE.stream(), own(files).stream()).toList();
  }

  /** The pictures in the map's textures folder, by file name, eg "lava.png". */
  static List<String> own(final MapFiles files) {
    final Optional<Path> folder = files.folder().map(f -> f.resolve(FOLDER));
    if (folder.isEmpty() || !Files.isDirectory(folder.get())) {
      return List.of();
    }
    try (Stream<Path> listed = Files.list(folder.get())) {
      return listed
          .map(p -> p.getFileName().toString())
          .filter(name -> KINDS.contains(kind(name)))
          .sorted()
          .toList();
    } catch (final IOException e) {
      return List.of();
    }
  }

  private static String kind(final String fileName) {
    return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
  }

  /** The texture's picture; empty for an own one that is gone or does not read. */
  static Optional<BufferedImage> picture(final MapFiles files, final String name) {
    if (PREMADE.contains(name)) {
      return Optional.of(premade(name));
    }
    return files
        .folder()
        .map(f -> f.resolve(FOLDER).resolve(name))
        .filter(Files::isRegularFile)
        .flatMap(Textures::read);
  }

  /** The texture's picture made small for a menu, eg the Paint menu's, made once per picture. */
  static Optional<BufferedImage> icon(final MapFiles files, final String name) {
    return picture(files, name)
        .map(
            p ->
                ICONS.computeIfAbsent(
                    p, q -> small(q, PREMADE.contains(name) ? PREMADE_ICON_PART : ICON_PART)));
  }

  private static BufferedImage small(final BufferedImage picture, final int most) {
    final BufferedImage icon = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = icon.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.setColor(PAPER);
    g.fillRect(0, 0, ICON, ICON);
    final int part = Math.min(most, Math.min(picture.getWidth(), picture.getHeight()));
    g.drawImage(picture, 0, 0, ICON, ICON, 0, 0, part, part, null);
    g.dispose();
    return icon;
  }

  /**
   * The picture, read again only when the file changed, as the map asks after every change; the
   * pictures of another map's folder are forgotten.
   */
  private static synchronized Optional<BufferedImage> read(final Path file) {
    final Optional<Path> folder = Optional.ofNullable(file.getParent());
    if (!folder.equals(readFrom)) {
      READ.clear();
      readFrom = folder;
    }
    try {
      final FileTime modified = Files.getLastModifiedTime(file);
      final Read seen = READ.get(file);
      if (seen != null && seen.modified().equals(modified)) {
        return seen.picture();
      }
      final Optional<BufferedImage> picture = Optional.ofNullable(ImageIO.read(file.toFile()));
      READ.put(file, new Read(modified, picture));
      return picture;
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  private record Read(FileTime modified, Optional<BufferedImage> picture) {}

  /** Each textured territory's fill, by territory; one whose picture is missing has none. */
  static Map<String, Fill> fills(final MapFiles files) {
    final Map<String, Optional<Fill>> byTexture = new HashMap<>();
    final Map<String, Fill> fills = new HashMap<>();
    files
        .textures()
        .forEach(
            (territory, texture) ->
                byTexture
                    .computeIfAbsent(
                        texture,
                        t -> picture(files, t).map(p -> new Fill(p, files.textureOffset(t))))
                    .ifPresent(fill -> fills.put(territory, fill)));
    return Map.copyOf(fills);
  }

  /** The premade texture, made once; it must not be drawn into. */
  static BufferedImage premade(final String name) {
    return MADE.computeIfAbsent(name, Textures::make);
  }

  /**
   * Draws the premade texture: small fine symbols as on old printed maps, eg trees, hatched peaks
   * or marsh signs, on a see-through ground, as they only hint at the terrain; the owner's color
   * shows between them.
   */
  static BufferedImage make(final String name) {
    final Random random = new Random(name.hashCode());
    final int size = name.equals("Mountains") ? MOUNTAINS_SIZE : SIZE;
    final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.setColor(name.contains("sea") || name.equals("Snow") ? WATER_INK : INK);
    switch (name) {
      case "Open sea" -> scatter(g, random, 28, size, (m, r) -> wave(m, r, 8));
      case "Coastal sea" -> scatter(g, random, 16, size, (m, r) -> wave(m, r, 5));
      case "Grass" -> scatter(g, random, 17, size, Textures::tuft);
      case "Forest" -> scatter(g, random, 18, size, Textures::tree);
      case "Hills" -> scatter(g, random, 30, size, Textures::hill);
      case "Mountains" -> scatter(g, random, 32, size, Textures::peak);
      case "Desert" -> {
        scatter(g, random, 9, size, Textures::grains);
        scatter(g, random, 42, size, Textures::dune);
      }
      case "Swamp" -> scatter(g, random, 22, size, Textures::marsh);
      case "Snow" -> scatter(g, random, 19, size, Textures::flake);
      default -> throw new IllegalArgumentException("No premade texture " + name);
    }
    g.dispose();
    return image;
  }

  /**
   * A mark in each square of a grid, a little off its middle and every other row shifted half a
   * square, so they keep an even rhythm without standing in line; each is drawn also a picture's
   * width and height away, so one cut by an edge goes on at the other: the texture is seamless.
   */
  private static void scatter(
      final Graphics2D g,
      final Random random,
      final int cell,
      final int size,
      final BiConsumer<Graphics2D, Random> mark) {
    // an even count of squares that fills the picture, so the rows go on alike across its edges
    final int cells = Math.max(2, 2 * Math.round(size / (2f * cell)));
    final double side = size / (double) cells;
    for (int row = 0; row < cells; row++) {
      for (int column = 0; column < cells; column++) {
        final double x = (column + (row % 2) * 0.5 + 0.5 + jitter(random)) * side;
        final double y = (row + 0.5 + jitter(random)) * side;
        final long markSeed = random.nextLong();
        for (int dx = -size; dx <= size; dx += size) {
          for (int dy = -size; dy <= size; dy += size) {
            final Graphics2D at = (Graphics2D) g.create();
            // on a pixel's middle, so a mark's upright and level strokes stay 1 px sharp
            at.translate(Math.floor(x) + dx + 0.5, Math.floor(y) + dy + 0.5);
            mark.accept(at, new Random(markSeed));
            at.dispose();
          }
        }
      }
    }
  }

  private static double jitter(final Random random) {
    return (random.nextDouble() - 0.5) * JITTER;
  }

  /** A short wave mark, like a tilde. */
  private static void wave(final Graphics2D g, final Random r, final int length) {
    final double w = length + r.nextInt(3);
    final Path2D wave = new Path2D.Double();
    wave.moveTo(-w / 2, 0);
    wave.quadTo(-w / 4, -2.4, 0, 0);
    wave.quadTo(w / 4, 2.4, w / 2, 0);
    g.draw(wave);
  }

  /** A tuft: two or three short blades side by side. */
  private static void tuft(final Graphics2D g, final Random r) {
    final int high = 3 + r.nextInt(2);
    if (r.nextInt(3) == 0) {
      g.draw(new Line2D.Double(-1, 0, -2, -high));
      g.draw(new Line2D.Double(1, 0, 2, -high));
      return;
    }
    g.draw(new Line2D.Double(-2, 0, -3, -high + 1));
    g.draw(new Line2D.Double(0, 0, 0, -high));
    g.draw(new Line2D.Double(2, 0, 3, -high + 1));
  }

  /** A small conifer, now and then a round broadleaf crown, each with a tick of shade. */
  private static void tree(final Graphics2D g, final Random r) {
    if (r.nextInt(4) == 0) {
      final Shape crown = new Ellipse2D.Double(-3, -9, 6, 6);
      g.draw(crown);
      g.draw(new Line2D.Double(0, -3, 0, 0));
      g.setColor(SHADE);
      g.fill(crown);
    } else {
      final int high = 8 + r.nextInt(2);
      final Path2D conifer = new Path2D.Double();
      conifer.moveTo(0, 0);
      conifer.lineTo(0, -high);
      conifer.moveTo(-3, -2);
      conifer.lineTo(0, -5);
      conifer.lineTo(3, -2);
      conifer.moveTo(-2, -high + 3);
      conifer.lineTo(0, -high);
      conifer.lineTo(2, -high + 3);
      g.draw(conifer);
    }
    g.setColor(HATCH);
    g.draw(new Line2D.Double(1, 0, 3, 0));
  }

  /** Two low humps, the nearer one hatched on its shade side. */
  private static void hill(final Graphics2D g, final Random r) {
    final int w = 5 + r.nextInt(2);
    final int h = 3 + r.nextInt(2);
    final Path2D humps = new Path2D.Double();
    humps.moveTo(-w, 0);
    humps.quadTo(0, -2 * h, w, 0);
    humps.moveTo(-2 * w + 3, 0);
    humps.quadTo(-w - 1, -h - 0.5, -w + 2, -h * 0.6);
    g.draw(humps);
    g.setColor(HATCH);
    for (int x = 1; x < w - 1; x += 2) {
      final double y = -h * (1 - (double) x * x / (w * w));
      g.draw(new Line2D.Double(x, y + 1.5, x + 1, Math.min(0, y + 3.5)));
    }
  }

  /** A peak, hatched down its shade side. */
  private static void peak(final Graphics2D g, final Random r) {
    final int half = 4 + r.nextInt(5);
    final double high = Math.round(half * 1.3) + 1;
    final Path2D outline = new Path2D.Double();
    outline.moveTo(-half, 0);
    outline.lineTo(0, -high);
    outline.lineTo(half + 1, 0);
    g.draw(outline);
    g.setColor(HATCH);
    for (int x = 0; x < half; x += 2) {
      final double y = -high * (1 - x / (half + 1.0));
      final double length = -y * 0.6;
      g.draw(new Line2D.Double(x, y + 1, x - length * 0.3, y + 1 + length));
    }
  }

  /** One or two grains of sand. */
  private static void grains(final Graphics2D g, final Random r) {
    g.fill(new Rectangle2D.Double(-0.5, -0.5, 1, 1));
    if (r.nextInt(3) == 0) {
      g.fill(new Rectangle2D.Double(1.5 + r.nextInt(2), 0.5 + r.nextInt(2), 1, 1));
    }
  }

  /** A dune's short curve. */
  private static void dune(final Graphics2D g, final Random r) {
    final int w = 9 + r.nextInt(4);
    g.draw(new Arc2D.Double(-w / 2.0, -2, w, 5, 25, 130, Arc2D.OPEN));
  }

  /** The marsh sign: three reeds on a line of water, now and then a second dash below. */
  private static void marsh(final Graphics2D g, final Random r) {
    g.draw(new Line2D.Double(-2, -2, -3, -4));
    g.draw(new Line2D.Double(0, -2, 0, -5));
    g.draw(new Line2D.Double(2, -2, 3, -4));
    g.setColor(WATER_INK);
    g.draw(new Line2D.Double(-4, 0, 4, 0));
    if (r.nextInt(5) < 2) {
      final int x = r.nextBoolean() ? 1 : -6;
      g.draw(new Line2D.Double(x, 2, x + 5, 2));
    }
  }

  /** A dot of snow, now and then a tiny asterisk. */
  private static void flake(final Graphics2D g, final Random r) {
    if (r.nextInt(4) > 0) {
      g.fill(new Ellipse2D.Double(-1, -1, 2, 2));
      return;
    }
    g.draw(new Line2D.Double(-2, 0, 2, 0));
    g.draw(new Line2D.Double(-1, -2, 1, 2));
    g.draw(new Line2D.Double(1, -2, -1, 2));
  }
}
