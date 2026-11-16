package eddies.mapeditor;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Vector;
import java.util.function.BiFunction;
import javax.imageio.ImageIO;

/**
 * A map's picture as one image read from its tiles on demand, for a writer that takes an image
 * row by row, eg ImageIO's png writer: a picture too big to hold in memory is written whole with a
 * row of tiles' worth of memory. A missing tile is see-through.
 */
final class TiledPicture implements RenderedImage {
  private final BiFunction<Integer, Integer, Optional<Path>> tileAt;
  private final int width;
  private final int height;
  private final int size = MapFiles.tileSize();
  private final ColorModel colorModel = ColorModel.getRGBdefault();
  private final SampleModel sampleModel =
      new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getSampleModel().createCompatibleSampleModel(size, size);
  // a row of tiles and a few more, as the writer asks for rows from the top down
  private final Map<Point, Raster> kept;
  private int rowsAskedAtMost;

  TiledPicture(final BiFunction<Integer, Integer, Optional<Path>> tileAt, final Dimension picture) {
    this.tileAt = tileAt;
    this.width = picture.width;
    this.height = picture.height;
    final int perRow = (width + size - 1) / size + 2;
    kept =
        new LinkedHashMap<>(perRow, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<Point, Raster> eldest) {
            return size() > perRow;
          }
        };
  }

  /** The most rows one call asked for: small while the writer streams, for tests. */
  int rowsAskedAtMost() {
    return rowsAskedAtMost;
  }

  @Override
  public Raster getTile(final int tileX, final int tileY) {
    return kept.computeIfAbsent(new Point(tileX, tileY), this::readTile);
  }

  private Raster readTile(final Point at) {
    final WritableRaster raster = colorModel.createCompatibleWritableRaster(size, size);
    final Optional<Path> file = tileAt.apply(at.x, at.y);
    if (file.isPresent()) {
      try {
        final BufferedImage read = ImageIO.read(file.get().toFile());
        if (read != null) {
          final BufferedImage argb = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
          argb.getGraphics().drawImage(read, 0, 0, null);
          raster.setRect(argb.getRaster());
        }
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return raster.createWritableTranslatedChild(at.x * size, at.y * size);
  }

  @Override
  public Raster getData() {
    return getData(new Rectangle(width, height));
  }

  @Override
  public Raster getData(final Rectangle rect) {
    final Rectangle within = rect.intersection(new Rectangle(width, height));
    rowsAskedAtMost = Math.max(rowsAskedAtMost, within.height);
    final WritableRaster data =
        colorModel.createCompatibleWritableRaster(within.width, within.height)
            .createWritableTranslatedChild(within.x, within.y);
    copyData(data);
    return data;
  }

  @Override
  public WritableRaster copyData(final WritableRaster raster) {
    final WritableRaster into =
        raster == null ? colorModel.createCompatibleWritableRaster(width, height) : raster;
    final Rectangle bounds = into.getBounds().intersection(new Rectangle(width, height));
    for (int tileY = bounds.y / size; tileY * size < bounds.y + bounds.height; tileY++) {
      for (int tileX = bounds.x / size; tileX * size < bounds.x + bounds.width; tileX++) {
        final Raster tile = getTile(tileX, tileY);
        final Rectangle part = tile.getBounds().intersection(bounds);
        if (!part.isEmpty()) {
          into.setRect(tile.createChild(part.x, part.y, part.width, part.height, part.x, part.y, null));
        }
      }
    }
    return into;
  }

  @Override
  public Vector<RenderedImage> getSources() {
    return null;
  }

  @Override
  public Object getProperty(final String name) {
    return java.awt.Image.UndefinedProperty;
  }

  @Override
  public String[] getPropertyNames() {
    return null;
  }

  @Override
  public ColorModel getColorModel() {
    return colorModel;
  }

  @Override
  public SampleModel getSampleModel() {
    return sampleModel;
  }

  @Override
  public int getWidth() {
    return width;
  }

  @Override
  public int getHeight() {
    return height;
  }

  @Override
  public int getMinX() {
    return 0;
  }

  @Override
  public int getMinY() {
    return 0;
  }

  @Override
  public int getNumXTiles() {
    return (width + size - 1) / size;
  }

  @Override
  public int getNumYTiles() {
    return (height + size - 1) / size;
  }

  @Override
  public int getMinTileX() {
    return 0;
  }

  @Override
  public int getMinTileY() {
    return 0;
  }

  @Override
  public int getTileWidth() {
    return size;
  }

  @Override
  public int getTileHeight() {
    return size;
  }

  @Override
  public int getTileGridXOffset() {
    return 0;
  }

  @Override
  public int getTileGridYOffset() {
    return 0;
  }
}
