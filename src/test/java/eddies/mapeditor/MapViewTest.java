package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Point;
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
import javax.swing.JToggleButton;
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

  @DisplayName("A line drawn into the picture keeps the view where it is, no fit")
  @Test
  void drawnLineKeepsTheView() throws Exception {
    // given: the map at 100 %, more than fits its small view
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    final MapView[] map = new MapView[1];
    final List<String> zoom = new ArrayList<>();
    SwingUtilities.invokeAndWait(
        () -> {
          map[0] = new MapView(project, () -> {}, () -> {}, () -> {});
          map[0].setSize(300, 200);
          layOut(map[0]);
        });
    // the first fit, queued when the map showed, has run
    SwingUtilities.invokeAndWait(
        () -> {
          layOut(map[0]);
          givenZoomButton(map[0]).doClick();
          zoom.add(givenZoomButton(map[0]).getText());
        });

    // when
    SwingUtilities.invokeAndWait(
        () ->
            project.drawBorder(
                new MapProject.BorderLine(
                    List.of(new Point(10, 10), new Point(90, 90)), 2, Color.BLACK)));
    // a fit the line queued would run here
    SwingUtilities.invokeAndWait(() -> layOut(map[0]));
    SwingUtilities.invokeAndWait(() -> zoom.add(givenZoomButton(map[0]).getText()));

    // then
    assertThat(zoom.get(0)).as("zoomed to 100 %").isEqualTo("100 %");
    assertThat(zoom.get(1)).as("still 100 % after the line").isEqualTo("100 %");
  }

  /** Lays out every container down from the component, as a window on screen would. */
  private static void layOut(final Component component) {
    if (component instanceof Container container) {
      container.doLayout();
      for (final Component child : container.getComponents()) {
        layOut(child);
      }
    }
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
            toolbarOrLegend.add(shown(map, MapViewsBar.class) || shown(map, MapLegend.class));
          }
        });

    assertThat(titles.get(0)).as("a new map").contains("No map picture yet");
    assertThat(titles.get(1))
        .as("the minimap has 20 territories")
        .contains("20 territories, but no map picture");
    assertThat(titles.get(2)).as("a picture to make territories from").contains("Picture loaded");
    assertThat(toolbarOrLegend)
        .as("hidden in every empty state")
        .containsExactly(false, false, false);
  }

  @DisplayName("With outlines the map shows its toolbar and legend, and no \"?\"")
  @Test
  void mapShowsToolbarAndLegend() throws Exception {
    final List<Boolean> shown = new ArrayList<>();
    final List<Boolean> help = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          shown.add(shown(map, MapViewsBar.class));
          shown.add(shown(map, MapLegend.class));
          help.add(button(map, "?").isPresent());
        });

    assertThat(shown).as("toolbar and legend").containsExactly(true, true);
    assertThat(help).as("explanations live in tool tips and the banner").containsExactly(false);
  }

  @DisplayName("A view chosen below the map shows on the map and in the legend")
  @Test
  void viewChosenInLegendShows() throws Exception {
    // given
    final List<Boolean> shown = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          all(map)
              .flatMap(
                  c ->
                      c instanceof JToggleButton t && "Values".equals(t.getText())
                          ? Stream.of(t)
                          : Stream.empty())
              .findFirst()
              .orElseThrow()
              .doClick();
          shown.add(titles(map).stream().anyMatch(text -> text.startsWith("Badge: PUs")));
        });

    // then
    assertThat(shown).as("the legend explains the values view").containsExactly(true);
  }

  @DisplayName("Make territories on a loaded picture finds them")
  @Test
  void findTerritoriesOverThePicture() throws Exception {
    final List<String> found = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> button(givenPictureOnly(found), "Make territories").orElseThrow().doClick());

    assertThat(found).as("the empty state's one action").containsExactly("found");
  }

  @DisplayName("Make territories again needs a picture loaded here")
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
                c instanceof JMenuItem item && "Make territories again…".equals(item.getText())
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

  @DisplayName("A step shows above the banner, 56 px high, kept by a new banner, ended by hiding")
  @Test
  void stepShowsAboveTheBanner() throws Exception {
    // given
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          map.showStep(MapGuide.Step.Id.NATIONS);
          seen.add(bannerHeight(map));
          seen.add(map.bannerText().orElseThrow());
          map.showBanner(TEXT, () -> {});
          seen.add(map.goalStep().orElseThrow());
          map.hideBanner();
          seen.add(map.goalText().isPresent());
          seen.add(bannerHeight(map));
          map.showStep(MapGuide.Step.Id.NATIONS);
          map.useFor(MapMode.GAME, new JPanel(), territory -> {});
          seen.add(map.goalStep().isPresent());
        });

    // then
    assertThat(seen)
        .as("56 px with how to add a nation; a mode's banner keeps it; hiding and useFor end it")
        .containsExactly(
            56,
            MapGuide.howTo(MapGuide.Step.Id.NATIONS),
            MapGuide.Step.Id.NATIONS,
            false,
            32,
            false);
  }

  @DisplayName("An edit mode's banner is taller and says Edit mode; a plain banner after it is not")
  @Test
  void editBannerSaysSo() throws Exception {
    // given
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          map.showEditBanner(TEXT, () -> {});
          seen.add(bannerHeight(map));
          seen.add(editBadgeShown(map));
          map.showBanner(TEXT, () -> {});
          seen.add(bannerHeight(map));
          seen.add(editBadgeShown(map));
        });

    // then
    assertThat(seen)
        .as("40 px with the badge, then 32 px without")
        .containsExactly(40, true, 32, false);
  }

  private static boolean editBadgeShown(final MapView map) {
    return labels(button(map, "Done").orElseThrow().getParent().getParent())
        .anyMatch(l -> "Edit mode".equals(l.getText()));
  }

  private static Stream<JLabel> labels(final Component in) {
    final Stream<JLabel> self = in instanceof JLabel l ? Stream.of(l) : Stream.empty();
    return in instanceof Container c
        ? Stream.concat(self, Stream.of(c.getComponents()).flatMap(MapViewTest::labels))
        : self;
  }

  private static int bannerHeight(final MapView map) {
    return button(map, "Done").orElseThrow().getParent().getParent().getPreferredSize().height;
  }

  @DisplayName("Short of room the banner cuts its text, never the control after it")
  @Test
  void narrowBannerKeepsItsControls() throws Exception {
    // given
    final List<Integer> widths = new ArrayList<>();
    final JComboBox<String> pictures = new JComboBox<>(new String[] {"a_long_picture_name.png"});

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          map.showBanner(TEXT.repeat(5), Optional.of(pictures), "Done", () -> {});
          map.setSize(500, 400);
          layOut(map);
          widths.add(pictures.getWidth());
        });

    // then
    assertThat(widths)
        .as("the picture box keeps its width")
        .containsExactly(pictures.getPreferredSize().width);
  }
}
