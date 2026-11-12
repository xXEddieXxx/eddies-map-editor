package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MiniMapTest {
  @TempDir Path maps;
  private final List<String> shown = new ArrayList<>();

  /** A map made from the test picture, its first land owned by Red. */
  private MapProject givenOwnedMap() throws Exception {
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), maps)
            .project();
    project.edit(game -> MapXml.withOwner(game, givenFirstLand(project), "Red"));
    return project;
  }

  private static String givenFirstLand(final MapProject project) {
    return MapXml.territories(project.game()).stream()
        .filter(t -> !MapXml.isWater(project.game(), t))
        .findFirst()
        .orElseThrow();
  }

  private MiniMap givenMiniMap(final MapProject project) {
    final MiniMap miniMap = new MiniMap(project, shown::add, 400);
    miniMap.setSize(miniMap.getPreferredSize());
    return miniMap;
  }

  private static BufferedImage givenPainted(final MiniMap miniMap) {
    final BufferedImage image =
        new BufferedImage(miniMap.getWidth(), miniMap.getHeight(), BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    miniMap.paint(g);
    g.dispose();
    return image;
  }

  private static Optional<Point> givenPixelOf(final BufferedImage image, final Color color) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) & 0xFFFFFF) == (color.getRGB() & 0xFFFFFF)) {
          return Optional.of(new Point(x, y));
        }
      }
    }
    return Optional.empty();
  }

  @DisplayName("A territory shows in its owner's color")
  @Test
  void paintsOwnersColor() throws Exception {
    // given
    final MapProject project = givenOwnedMap();
    final MiniMap miniMap = givenMiniMap(project);

    // when
    final BufferedImage painted = givenPainted(miniMap);

    // then
    assertThat(givenPixelOf(painted, project.nationColor("Red")))
        .as("Red's land is filled in Red's color")
        .isPresent();
  }

  @DisplayName("A territory with a problem is outlined red")
  @Test
  void outlinesProblemsRed() throws Exception {
    // given
    final MapProject project = givenOwnedMap();
    final MiniMap miniMap = givenMiniMap(project);
    final Color danger = MiniMap.PROBLEM;
    final long before = givenRedPixels(givenPainted(miniMap), danger);

    // when
    // the sea zone, not Red's land, whose color may be a red too
    miniMap.setProblems(MapXml.seaZones(project.game()));
    final long after = givenRedPixels(givenPainted(miniMap), danger);

    // then
    assertThat(after).as("the problem's outline in the danger color").isGreaterThan(before + 20);
  }

  private static long givenRedPixels(final BufferedImage image, final Color danger) {
    long count = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        final Color pixel = new Color(image.getRGB(x, y));
        if (Math.abs(pixel.getRed() - danger.getRed())
                + Math.abs(pixel.getGreen() - danger.getGreen())
                + Math.abs(pixel.getBlue() - danger.getBlue())
            < 40) {
          count++;
        }
      }
    }
    return count;
  }

  @DisplayName("The picture is drawn again only after a change")
  @Test
  void drawsOncePerChange() throws Exception {
    // given
    final MapProject project = givenOwnedMap();
    final MiniMap miniMap = givenMiniMap(project);
    givenPainted(miniMap);
    final BufferedImage first = miniMap.picture();

    // when
    givenPainted(miniMap);
    final BufferedImage again = miniMap.picture();
    project.edit(game -> MapXml.withOwner(game, givenFirstLand(project), null));
    givenPainted(miniMap);

    // then
    assertThat(again).as("nothing changed: the same picture").isSameAs(first);
    assertThat(miniMap.picture()).as("an owner changed: drawn anew").isNotSameAs(first);
  }

  @DisplayName("Hovering a territory tells its name and owner")
  @Test
  void toolTipNamesTerritoryAndOwner() throws Exception {
    // given
    final MapProject project = givenOwnedMap();
    final MiniMap miniMap = givenMiniMap(project);
    final Point red = givenPixelOf(givenPainted(miniMap), project.nationColor("Red")).orElseThrow();

    // when
    final String tip =
        miniMap.getToolTipText(
            new MouseEvent(
                miniMap,
                MouseEvent.MOUSE_MOVED,
                0,
                0,
                red.x,
                red.y,
                0,
                false,
                MouseEvent.NOBUTTON));

    // then
    assertThat(tip).as("name · owner").isEqualTo(givenFirstLand(project) + " · Red");
  }

  @DisplayName("Screen readers name the map overview")
  @Test
  void hasAccessibleName() throws Exception {
    // given
    final MiniMap miniMap = givenMiniMap(givenOwnedMap());

    // when
    final String name = miniMap.getAccessibleContext().getAccessibleName();

    // then
    assertThat(name).as("a canvas without a text of its own").isEqualTo("Map overview");
  }

  @DisplayName("A click on a territory shows it in Territories")
  @Test
  void clickShowsTerritory() throws Exception {
    // given
    final MapProject project = givenOwnedMap();
    final MiniMap miniMap = givenMiniMap(project);
    final Point red = givenPixelOf(givenPainted(miniMap), project.nationColor("Red")).orElseThrow();

    // when
    miniMap.dispatchEvent(
        new MouseEvent(
            miniMap, MouseEvent.MOUSE_CLICKED, 0, 0, red.x, red.y, 1, false, MouseEvent.BUTTON1));

    // then
    assertThat(shown).as("the land owned by Red").containsExactly(givenFirstLand(project));
  }
}
