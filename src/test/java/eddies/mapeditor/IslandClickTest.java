package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Polygon;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A click on any island of a group hits the group, not the sea zone drawn around it. */
final class IslandClickTest {
  private static final String SEA = "Sea Zone";
  private static final String ISLANDS = "Marianas";

  private static Polygon box(final int x, final int y, final int side) {
    return new Polygon(
        new int[] {x, x + side, x + side, x}, new int[] {y, y, y + side, y + side}, 4);
  }

  /** A sea zone of 200 px, listed first, around a territory of two islands. */
  private static MapFiles givenIslandsInASea() {
    return MapFiles.none()
        .withOutlines(SEA, List.of(box(0, 0, 200)))
        .withOutlines(ISLANDS, List.of(box(20, 20, 30), box(120, 130, 40)));
  }

  private static MapProject givenProject() {
    final MapProject project = MapProject.newEmpty();
    project.editFiles(files -> givenIslandsInASea());
    return project;
  }

  @DisplayName("A point in any outline of a territory hits it, the smallest outline first")
  @Test
  void hitsEveryIslandOfAGroup() {
    // given
    final MapFiles files = givenIslandsInASea();

    // when, then
    assertThat(files.territoryAt(30, 30)).as("the first island").contains(ISLANDS);
    assertThat(files.territoryAt(140, 150)).as("the second island").contains(ISLANDS);
    assertThat(files.territoryAt(90, 90)).as("the water between them").contains(SEA);
    assertThat(files.territoryAt(300, 300)).as("off the map").isEmpty();
  }

  @DisplayName("The map's tool tip and the small map's click use the same test")
  @Test
  void canvasAndMiniMapHitTheIslands() throws Exception {
    // given
    final MapProject project = givenProject();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = new MapCanvas(project);
          canvas.setSize(canvas.getPreferredSize());
          final double zoom = canvas.getPreferredSize().getWidth() / project.getFiles().size().width;
          for (final int[] at : new int[][] {{140, 150}, {90, 90}}) {
            seen.add(
                canvas.getToolTipText(
                    new MouseEvent(
                        canvas,
                        MouseEvent.MOUSE_MOVED,
                        0,
                        0,
                        (int) (at[0] * zoom),
                        (int) (at[1] * zoom),
                        0,
                        false)));
          }
          final MiniMap miniMap = new MiniMap(project, territory -> {}, 200);
          miniMap.setSize(200, 200);
          seen.add(miniMap.territoryAt(140, 150));
          seen.add(miniMap.territoryAt(90, 90));
        });

    // then
    assertThat(seen)
        .as("the second island and the sea, on the map and on the small map")
        .containsExactly(ISLANDS, SEA, Optional.of(ISLANDS), Optional.of(SEA));
  }
}
