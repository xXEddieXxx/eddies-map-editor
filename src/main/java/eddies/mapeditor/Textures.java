package eddies.mapeditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
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
  private static final Color INK = new Color(55, 45, 35, 150);
  private static final Color SEA_INK = new Color(25, 45, 65, 130);
  private static final Color SNOW_INK = new Color(70, 85, 105, 140);
  // the icon shows a part of the texture this big on paper, so its strokes stay visible
  private static final int ICON_PART = 48;
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
    return picture(files, name).map(p -> ICONS.computeIfAbsent(p, Textures::small));
  }

  private static BufferedImage small(final BufferedImage picture) {
    final BufferedImage icon = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = icon.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.setColor(PAPER);
    g.fillRect(0, 0, ICON, ICON);
    final int part = Math.min(ICON_PART, Math.min(picture.getWidth(), picture.getHeight()));
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
   * Draws the premade texture: sparse thin strokes, eg tree symbols or wave lines, on a see-through
   * ground, as they only hint at the terrain; the owner's color shows between them.
   */
  static BufferedImage make(final String name) {
    final Random random = new Random(name.hashCode());
    final int size = name.equals("Mountains") ? MOUNTAINS_SIZE : SIZE;
    final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    switch (name) {
      case "Open sea" -> scatter(g, random, 48, size, (m, r) -> wave(m, r, 14));
      case "Coastal sea" -> scatter(g, random, 26, size, (m, r) -> wave(m, r, 10));
      case "Grass" -> scatter(g, random, 32, size, Textures::tuft);
      case "Forest" -> scatter(g, random, 24, size, Textures::tree);
      case "Hills" -> scatter(g, random, 44, size, Textures::hill);
      case "Mountains" -> scatter(g, random, 64, size, Textures::peak);
      case "Desert" -> scatter(g, random, 30, size, Textures::sand);
      case "Swamp" -> scatter(g, random, 30, size, Textures::marsh);
      case "Snow" -> scatter(g, random, 30, size, Textures::flake);
      default -> throw new IllegalArgumentException("No premade texture " + name);
    }
    g.dispose();
    return image;
  }

  /**
   * A mark in each square of a grid, at a random place in it and every other row shifted half a
   * square, so they spread evenly with empty space between; each is drawn also a picture's width
   * and height away, so one cut by an edge goes on at the other: the texture is seamless.
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
        final double x =
            (column + (row % 2) * 0.5) * side + random.nextDouble() * side * 0.5 + side * 0.25;
        final double y = row * side + random.nextDouble() * side * 0.5 + side * 0.25;
        final long markSeed = random.nextLong();
        for (int dx = -size; dx <= size; dx += size) {
          for (int dy = -size; dy <= size; dy += size) {
            final Graphics2D at = (Graphics2D) g.create();
            at.translate(x + dx, y + dy);
            mark.accept(at, new Random(markSeed));
            at.dispose();
          }
        }
      }
    }
  }

  /** A wave: two small curves side by side, like a tilde. */
  private static void wave(final Graphics2D g, final Random r, final int length) {
    final double w = length * (0.8 + r.nextDouble() * 0.4);
    final Path2D wave = new Path2D.Double();
    wave.moveTo(-w / 2, 0);
    wave.quadTo(-w / 4, -w / 5, 0, 0);
    wave.quadTo(w / 4, w / 5, w / 2, 0);
    g.setColor(SEA_INK);
    g.draw(wave);
  }

  /** Three short blades from one root. */
  private static void tuft(final Graphics2D g, final Random r) {
    g.setColor(INK);
    final double high = 4 + r.nextDouble() * 2;
    g.draw(new Line2D.Double(0, 0, -2.5, -high * 0.8));
    g.draw(new Line2D.Double(0, 0, 0, -high));
    g.draw(new Line2D.Double(0, 0, 2.5, -high * 0.8));
  }

  /** A round crown on a trunk, or a conifer. */
  private static void tree(final Graphics2D g, final Random r) {
    g.setColor(INK);
    final double size = 6 + r.nextDouble() * 3;
    if (r.nextBoolean()) {
      g.draw(new Ellipse2D.Double(-size / 2, -size, size, size));
    } else {
      final Path2D conifer = new Path2D.Double();
      conifer.moveTo(-size / 2, 0);
      conifer.lineTo(0, -size * 1.2);
      conifer.lineTo(size / 2, 0);
      conifer.closePath();
      g.draw(conifer);
    }
    g.draw(new Line2D.Double(0, 0, 0, size / 3));
  }

  /** A low arc, a short stroke under its right side as its shade. */
  private static void hill(final Graphics2D g, final Random r) {
    g.setColor(INK);
    final double w = 14 + r.nextDouble() * 8;
    g.draw(new Arc2D.Double(-w / 2, -w / 3, w, w * 2 / 3, 10, 160, Arc2D.OPEN));
    g.draw(new Line2D.Double(w * 0.1, -w * 0.05, w * 0.35, -w * 0.02));
  }

  /** A peak outline, hatched on its shade side. */
  private static void peak(final Graphics2D g, final Random r) {
    g.setColor(INK);
    final double half = 8 + r.nextDouble() * 7;
    final double high = half * (1.2 + r.nextDouble() * 0.4);
    final Path2D outline = new Path2D.Double();
    outline.moveTo(-half, 0);
    outline.lineTo(0, -high);
    outline.lineTo(half, 0);
    g.draw(outline);
    for (int i = 1; i <= 3; i++) {
      final double t = i / 4.0;
      // from the right slope down towards the foot
      g.draw(new Line2D.Double(half * t, -high * (1 - t), half * t, -high * (1 - t) * 0.4));
    }
  }

  /** A few dots, or a dune's curve. */
  private static void sand(final Graphics2D g, final Random r) {
    g.setColor(INK);
    if (r.nextInt(3) == 0) {
      final double w = 12 + r.nextDouble() * 6;
      g.draw(new Arc2D.Double(-w / 2, 0, w, w / 3, 20, 140, Arc2D.OPEN));
    } else {
      for (int i = 0; i < 3; i++) {
        g.fill(new Ellipse2D.Double(r.nextDouble() * 6 - 3, r.nextDouble() * 6 - 3, 1.4, 1.4));
      }
    }
  }

  /** Reeds over two short water dashes. */
  private static void marsh(final Graphics2D g, final Random r) {
    g.setColor(INK);
    for (int i = -1; i <= 1; i++) {
      g.draw(new Line2D.Double(i * 2, 0, i * 2.5, -4 - r.nextDouble() * 3));
    }
    g.setColor(SEA_INK);
    g.draw(new Line2D.Double(-6, 3, -1, 3));
    g.draw(new Line2D.Double(1, 5, 6, 5));
  }

  /** A tiny flake: three crossing strokes. */
  private static void flake(final Graphics2D g, final Random r) {
    g.setColor(SNOW_INK);
    final double arm = 2 + r.nextDouble();
    for (int i = 0; i < 3; i++) {
      final double angle = Math.PI / 3 * i;
      g.draw(
          new Line2D.Double(
              -arm * Math.cos(angle),
              -arm * Math.sin(angle),
              arm * Math.cos(angle),
              arm * Math.sin(angle)));
    }
  }
}
