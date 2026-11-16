package eddies.mapeditor;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;
import javax.imageio.ImageIO;
import lombok.experimental.UtilityClass;

/**
 * Resizes a picture that lies in tiles, tile by tile, never holding the whole picture: each new
 * tile is drawn from the part of the old tiles that covers it, with a margin for the smoothing,
 * so a map of any size can be made bigger or smaller with a few tiles' worth of memory.
 *
 * <p>Every tile is drawn with the one transform of the whole picture, so the tiles meet without a
 * seam. A flat picture (lines and filled areas) takes the colors of the part it was drawn from
 * afterwards, as {@link MapFiles#inOwnColors} does for a picture held whole, so its lines stay
 * one color; a painted one, eg a relief, is smoothed, halved step by step when made much smaller.
 */
@UtilityClass
class TileScaler {
  // source tiles kept while the new tiles that need them are drawn, 256 KB each
  private static final int KEPT_TILES = 64;

  /**
   * Writes the picture at the new size as tiles into the folder, named column_row.png as the game
   * reads them; tiles beyond the picture are not written.
   *
   * @param source the old tile at a column and row, empty where the map has none
   * @param from the old picture's size
   * @param to the new size
   * @param flat whether the picture is lines and filled areas, which keep their own colors
   * @param onTile told each new tile written, with how many there are in all
   * @return the number of tiles written
   */
  static int scale(
      final BiFunction<Integer, Integer, Optional<Path>> source,
      final Dimension from,
      final Dimension to,
      final boolean flat,
      final Path folder,
      final IntConsumer onTile)
      throws IOException {
    final int size = MapFiles.tileSize();
    final double fx = to.width / (double) from.width;
    final double fy = to.height / (double) from.height;
    // halved before drawing when made less than half as big: a smoothing step sees 2 by 2 pixels
    int halvings = 0;
    while (Math.min(fx, fy) * (1 << (halvings + 1)) <= 1) {
      halvings++;
    }
    final int halved = 1 << halvings;
    // the margin around the part drawn from, so the smoothing at a tile's edge sees what it
    // would see in the whole picture; in source pixels, after the halvings
    final int margin = (int) Math.ceil(2.0 * halved / Math.min(fx, fy)) + 2 * halved;
    final Map<Point, Optional<BufferedImage>> kept =
        new LinkedHashMap<>(KEPT_TILES, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(
              final Map.Entry<Point, Optional<BufferedImage>> eldest) {
            return size() > KEPT_TILES;
          }
        };
    Files.createDirectories(folder);
    final int columns = (to.width + size - 1) / size;
    final int rows = (to.height + size - 1) / size;
    final int all = columns * rows;
    int written = 0;
    for (int row = 0; row < rows; row++) {
      for (int column = 0; column < columns; column++) {
        final Rectangle target = new Rectangle(column * size, row * size, size, size);
        // the old pixels the new tile is drawn from, with the margin, within the old picture
        final Rectangle part =
            new Rectangle(
                    (int) Math.floor(target.x / fx) - margin,
                    (int) Math.floor(target.y / fy) - margin,
                    (int) Math.ceil(size / fx) + 2 * margin,
                    (int) Math.ceil(size / fy) + 2 * margin)
                .intersection(new Rectangle(from));
        final BufferedImage drawn =
            new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        if (!part.isEmpty()) {
          final BufferedImage patch = patch(source, part, size, kept);
          BufferedImage smoothed = patch;
          for (int i = 0; i < halvings; i++) {
            smoothed =
                drawnAt(smoothed, Math.max(1, smoothed.getWidth() / 2), Math.max(1, smoothed.getHeight() / 2));
          }
          final Graphics2D g = drawn.createGraphics();
          g.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          // one transform for the whole picture: the tiles meet without a seam
          g.translate(-target.x, -target.y);
          g.scale(fx * halved, fy * halved);
          g.translate(part.x / (double) halved, part.y / (double) halved);
          g.drawImage(smoothed, 0, 0, null);
          g.dispose();
          if (flat) {
            final BufferedImage own = MapFiles.inOwnColors(patch, drawn);
            final Graphics2D over = drawn.createGraphics();
            over.drawImage(own, 0, 0, null);
            over.dispose();
          }
          // beyond the new picture nothing, as the game's own tiles have nothing there
          clearBeyond(drawn, to, target);
        }
        ImageIO.write(drawn, "png", folder.resolve(column + "_" + row + ".png").toFile());
        written++;
        onTile.accept(all);
      }
    }
    return written;
  }

  // the old picture's part, put together from the tiles it lies on; a missing tile stays clear
  private static BufferedImage patch(
      final BiFunction<Integer, Integer, Optional<Path>> source,
      final Rectangle part,
      final int size,
      final Map<Point, Optional<BufferedImage>> kept)
      throws IOException {
    final BufferedImage patch =
        new BufferedImage(part.width, part.height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = patch.createGraphics();
    g.translate(-part.x, -part.y);
    for (int column = part.x / size; column * size < part.x + part.width; column++) {
      for (int row = part.y / size; row * size < part.y + part.height; row++) {
        final Point at = new Point(column, row);
        Optional<BufferedImage> tile = kept.get(at);
        if (tile == null) {
          tile = read(source.apply(column, row));
          kept.put(at, tile);
        }
        final int x = column * size;
        final int y = row * size;
        tile.ifPresent(t -> g.drawImage(t, x, y, null));
      }
    }
    g.dispose();
    return patch;
  }

  private static Optional<BufferedImage> read(final Optional<Path> file) throws IOException {
    if (file.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(ImageIO.read(file.get().toFile()));
  }

  private static BufferedImage drawnAt(
      final BufferedImage image, final int width, final int height) {
    final BufferedImage drawn = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = drawn.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(image, 0, 0, width, height, null);
    g.dispose();
    return drawn;
  }

  // the part of the tile beyond the picture's right and bottom edge made see-through
  private static void clearBeyond(
      final BufferedImage tile, final Dimension picture, final Rectangle target) {
    final int keptWidth = Math.min(tile.getWidth(), picture.width - target.x);
    final int keptHeight = Math.min(tile.getHeight(), picture.height - target.y);
    if (keptWidth >= tile.getWidth() && keptHeight >= tile.getHeight()) {
      return;
    }
    final int[] clear = new int[tile.getWidth()];
    for (int y = 0; y < tile.getHeight(); y++) {
      if (y >= keptHeight) {
        tile.setRGB(0, y, tile.getWidth(), 1, clear, 0, tile.getWidth());
      } else if (keptWidth < tile.getWidth()) {
        tile.setRGB(keptWidth, y, tile.getWidth() - keptWidth, 1, clear, 0, tile.getWidth());
      }
    }
  }
}
