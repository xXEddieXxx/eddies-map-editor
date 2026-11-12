package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class MapViewTest {
  private static final String TEXT = "Paint owner: click land to give it to Germans.";

  @DisplayName("The zoom shows its widest value, 300 %, without cutting it")
  @Test
  void zoomFitsWidestValue() {
    final JButton zoom = givenZoomButton(givenMap());

    final Insets insets = zoom.getInsets();
    final int needed =
        zoom.getFontMetrics(zoom.getFont()).stringWidth("300 %") + insets.left + insets.right;

    assertThat(zoom.getPreferredSize().width)
        .as("wide enough for the most it zooms in, not \"50...\"")
        .isGreaterThanOrEqualTo(needed);
  }

  private static JButton givenZoomButton(final MapView map) {
    return all(map)
        .flatMap(
            c ->
                c instanceof JButton b && b.getText().endsWith(" %")
                    ? Stream.of(b)
                    : Stream.empty())
        .findFirst()
        .orElseThrow();
  }

  private static MapView givenMap() {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    return new MapView(project, () -> {}, () -> {}, () -> {});
  }

  /** A new map: no picture, no territories. */
  private static MapView givenNewMap() {
    return new MapView(MapProject.newEmpty(), () -> {}, () -> {}, () -> {});
  }

  /** The minimap: territories, but no picture. */
  private static MapView givenMinimap() {
    return new MapView(
        new MapProject(Path.of("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none()),
        () -> {},
        () -> {},
        () -> {});
  }

  /** A picture loaded, no territories found in it yet; finding them is counted. */
  private static MapView givenPictureOnly(final List<String> found) {
    final MapProject project = MapProject.newEmpty();
    project.loadPicture(MapPictureTest.givenPicture());
    return new MapView(project, () -> {}, () -> found.add("found"), () -> {});
  }

  private static List<String> titles(final MapView map) {
    return all(map)
        .flatMap(c -> c instanceof JLabel label ? Stream.of(label.getText()) : Stream.empty())
        .toList();
  }

  /** Whether the component and every panel up to the map is visible. */
  private static boolean shown(final MapView map, final Class<?> kind) {
    return shownComponent(map, all(map).filter(kind::isInstance).findFirst().orElseThrow());
  }

  private static Optional<JButton> button(final MapView map, final String text) {
    return all(map)
        .flatMap(
            c -> c instanceof JButton b && text.equals(b.getText()) ? Stream.of(b) : Stream.empty())
        .findFirst();
  }

  private static Stream<Component> all(final Component component) {
    return component instanceof Container container
        ? Stream.concat(
            Stream.of(component), Stream.of(container.getComponents()).flatMap(MapViewTest::all))
        : Stream.of(component);
  }

  /** Whether the banner's text shows: it and every panel up to the map visible. */
  private static boolean bannerShown(final MapView map) {
    final Optional<Component> text =
        all(map)
            .filter(c -> c instanceof JLabel label && TEXT.equals(label.getText()))
            .findFirst();
    for (Component c = text.orElse(null); c != null && c != map; c = c.getParent()) {
      if (!c.isVisible()) {
        return false;
      }
    }
    return text.isPresent();
  }

  private static void pressEscape(final MapView map) {
    final MapCanvas canvas =
        all(map)
            .flatMap(c -> c instanceof MapCanvas m ? Stream.of(m) : Stream.empty())
            .findFirst()
            .orElseThrow();
    final KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
    final Object action = canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
    SwingUtilities.notifyAction(
        canvas.getActionMap().get(action),
        escape,
        new KeyEvent(
            canvas, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED),
        canvas,
        0);
  }

  @DisplayName("Without outlines the map says what to do next, without toolbar and legend")
  @Test
  void emptyStates() throws Exception {
    final List<List<String>> titles = new ArrayList<>();
    final List<Boolean> toolbarOrLegend = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          for (final MapView map :
              List.of(givenNewMap(), givenMinimap(), givenPictureOnly(new ArrayList<>()))) {
            titles.add(titles(map));
            toolbarOrLegend.add(shown(map, MapViewsButton.class) || shown(map, MapLegend.class));
          }
        });

    assertThat(titles.get(0)).as("a new map").contains("No map picture yet");
    assertThat(titles.get(1))
        .as("the minimap has 20 territories")
        .contains("20 territories, but no map picture");
    assertThat(titles.get(2)).as("a picture to find territories in").contains("Picture loaded");
    assertThat(toolbarOrLegend).as("hidden in every empty state").containsExactly(false, false, false);
  }

  @DisplayName("With outlines the map shows its toolbar and legend, and no \"?\"")
  @Test
  void mapShowsToolbarAndLegend() throws Exception {
    final List<Boolean> shown = new ArrayList<>();
    final List<Boolean> help = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          shown.add(shown(map, MapViewsButton.class));
          shown.add(shown(map, MapLegend.class));
          help.add(button(map, "?").isPresent());
        });

    assertThat(shown).as("toolbar and legend").containsExactly(true, true);
    assertThat(help).as("explanations live in tool tips and the banner").containsExactly(false);
  }

  @DisplayName("Find territories on a loaded picture finds them")
  @Test
  void findTerritoriesOverThePicture() throws Exception {
    final List<String> found = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> button(givenPictureOnly(found), "Find territories").orElseThrow().doClick());

    assertThat(found).as("the empty state's one action").containsExactly("found");
  }

  @DisplayName("Find territories again needs a picture loaded here")
  @Test
  void findAgainNeedsAPicture() throws Exception {
    final List<Boolean> enabled = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          for (final MapView map : List.of(givenMinimap(), givenPictureOnly(new ArrayList<>()))) {
            enabled.add(findAgain(map).isEnabled());
          }
        });

    assertThat(enabled).as("disabled without picture, enabled with").containsExactly(false, true);
  }

  private static JMenuItem findAgain(final MapView map) {
    final JButton picture =
        all(map)
            .flatMap(
                c ->
                    c instanceof JButton b && "Map picture".equals(b.getToolTipText())
                        ? Stream.of(b)
                        : Stream.empty())
            .findFirst()
            .orElseThrow();
    return all(picture.getComponentPopupMenu())
        .flatMap(
            c ->
                c instanceof JMenuItem item && "Find territories again…".equals(item.getText())
                    ? Stream.of(item)
                    : Stream.empty())
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("A banner with something to choose and its own verb, its text replaced on a call")
  @Test
  void bannerWithExtraAndVerb() throws Exception {
    final List<Object> seen = new ArrayList<>();
    final List<String> done = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          final JComboBox<String> pictures = new JComboBox<>(new String[] {"tree.png"});
          map.showBanner("Click to place.", Optional.of(pictures), "Cancel", () -> done.add("x"));
          seen.add(shownComponent(map, pictures));
          map.showBanner(TEXT, Optional.empty(), "Cancel", () -> done.add("cancelled"));
          seen.add(map.bannerText().orElseThrow());
          seen.add(shownComponent(map, pictures));
          button(map, "Cancel").orElseThrow().doClick();
          seen.add(map.bannerText().isPresent());
        });

    assertThat(seen)
        .as("extra shown, then replaced with the text; Cancel ends the mode")
        .containsExactly(true, TEXT, false, false);
    assertThat(done).as("only the last done runs").containsExactly("cancelled");
  }

  private static boolean shownComponent(final MapView map, final Component component) {
    if (all(map).noneMatch(c -> c == component)) {
      return false;
    }
    for (Component c = component; c != null && c != map; c = c.getParent()) {
      if (!c.isVisible()) {
        return false;
      }
    }
    return true;
  }

  @DisplayName("A mode's banner shows until another tool takes the map")
  @Test
  void useForHidesTheBanner() throws Exception {
    final List<Boolean> shown = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          map.showBanner(TEXT, () -> {});
          shown.add(bannerShown(map));
          map.useFor(MapMode.GAME, new JPanel(), territory -> {});
          shown.add(bannerShown(map));
        });

    assertThat(shown).as("shown, then hidden by useFor").containsExactly(true, false);
  }

  @DisplayName("Esc on the map ends the mode: done runs and the banner hides")
  @Test
  void escapeIsDone() throws Exception {
    final List<String> done = new ArrayList<>();
    final List<Boolean> shown = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          map.showBanner(TEXT, () -> done.add("done"));
          pressEscape(map);
          shown.add(bannerShown(map));
          pressEscape(map);
        });

    assertThat(done)
        .as("Esc runs done once; a second Esc without banner does nothing")
        .containsExactly("done");
    assertThat(shown).as("the banner is gone after Esc").containsExactly(false);
  }
}
