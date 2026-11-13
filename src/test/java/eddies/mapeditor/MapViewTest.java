package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
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

  @DisplayName("The views start the toolbar above the map, before the tool's own; not in the legend")
  @Test
  void viewsStartTheToolbar() throws Exception {
    // given
    final List<String> toolbar = new ArrayList<>();
    final List<Boolean> inLegend = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenToolbar(1400);
          toolbar.addAll(toolbarTexts(map));
          inLegend.add(
              SwingUtilities.isDescendingFrom(
                  find(map, MapViewsBar.class), find(map, MapLegend.class)));
        });

    // then
    assertThat(toolbar)
        .as("left to right: the views by name, then the tool's own, then the map's")
        .startsWith(
            "Game",
            "Owners",
            "Political",
            "Values",
            "Units",
            "Neighbours",
            "Select",
            "▾",
            "Draw borders",
            "▾");
    assertThat(inLegend).as("the legend has only keys").containsExactly(false);
  }

  @DisplayName("The map starts right under the toolbar's line, no gap between")
  @Test
  void mapRightUnderToolbar() throws Exception {
    // given
    final List<Integer> ys = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenToolbar(1400);
          final Container row = find(map, MapViewsBar.class).getParent();
          ys.add(SwingUtilities.convertPoint(row, 0, row.getHeight(), map).y);
          final MapCanvas canvas = find(map, MapCanvas.class);
          // the scroll pane's top, the map's frame
          ys.add(
              SwingUtilities.convertPoint(canvas.getParent().getParent(), 0, 0, map).y);
        });

    // then
    assertThat(ys.get(1)).as("the map's top at the toolbar's bottom").isEqualTo(ys.get(0));
  }

  @DisplayName("Short of room only the chosen view keeps its name, and nothing is cut")
  @Test
  void narrowToolbarCutsNothing() throws Exception {
    // given
    final List<String> toolbar = new ArrayList<>();
    final List<Boolean> fits = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () ->
            inEditorsTheme(
                () -> {
                  // the map's width at 1280 x 800 beside Territories' inspector
                  final MapView map = givenToolbar(734);
                  toolbar.addAll(toolbarTexts(map));
                  fits.add(cutNothing(map));
                }));

    // then
    assertThat(toolbar)
        .as("the chosen view by name, the others as icons")
        .startsWith("Game", "", "", "", "", "");
    assertThat(fits).as("every control whole, none over another").containsExactly(true);
  }

  @DisplayName("Shorter of room views and the tool's modes show only their glyphs, nothing is cut")
  @Test
  void shortToolbarShowsGlyphs() throws Exception {
    // given
    final List<String> toolbar = new ArrayList<>();
    final List<Object> select = new ArrayList<>();
    final List<Boolean> fits = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () ->
            inEditorsTheme(
                () -> {
                  // the map's width in a 1024 px window
                  final MapView map = givenToolbar(615);
                  toolbar.addAll(toolbarTexts(map));
                  final AbstractButton button = toolbarButton(map, "Select");
                  select.add(button.getIcon());
                  select.add(button.getToolTipText());
                  fits.add(cutNothing(map));
                }));

    // then
    assertThat(toolbar)
        .as("glyphs only, Paint and Places with their arrows")
        .startsWith("", "", "", "", "", "", "", "▾", "", "▾");
    assertThat(select)
        .as("Select as its glyph, named in its tool tip")
        .containsExactly(
            new MakerIcon(MakerIcon.Glyph.SELECT, Theme.MAP_TOOL_ICON),
            "Select: a click on the map chooses the territory");
    assertThat(fits).as("every control whole, none over another").containsExactly(true);
  }

  @DisplayName("On a very narrow map the toolbar wraps: the map's tools stay whole and reachable")
  @Test
  void narrowestToolbarWraps() throws Exception {
    // given
    final List<Boolean> fits = new ArrayList<>();
    final List<Integer> rowHeight = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () ->
            inEditorsTheme(
                () -> {
                  // the narrowest the window's split lets the map be
                  final MapView map = givenToolbar(300);
                  fits.add(cutNothing(map));
                  fits.add(
                      toolbarButtons(map).stream()
                          .anyMatch(b -> "Fit the map (Ctrl+0)".equals(b.getToolTipText())));
                  rowHeight.add(find(map, MapViewsBar.class).getParent().getHeight());
                }));

    // then
    assertThat(fits)
        .as("every control whole, none over another; Fit among them")
        .containsExactly(true, true);
    assertThat(rowHeight.getFirst()).as("more than one line").isGreaterThan(48);
  }

  @DisplayName("A tool without controls of its own leaves no line and no room in the toolbar")
  @Test
  void noEditorToolsNoLine() throws Exception {
    // given
    final List<Boolean> shown = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap();
          map.useFor(MapMode.CONNECTIONS, new JPanel(), territory -> {});
          map.setSize(1400, 600);
          layOut(map);
          final Container row = find(map, MapViewsBar.class).getParent();
          Stream.of(row.getComponents())
              .filter(c -> !(c instanceof MapViewsBar) && c.isVisible())
              .forEach(c -> shown.add(c instanceof JSeparator));
        });

    // then
    assertThat(shown)
        .as("only the views and the map's tools, no separator")
        .containsExactly(false);
  }

  @DisplayName("The toolbar stands out: about 46 px high, its controls 32 px")
  @Test
  void toolbarIsTall() throws Exception {
    // given
    final List<Integer> heights = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenToolbar(1400);
          heights.add(find(map, MapViewsBar.class).getParent().getHeight());
          toolbarButtons(map).forEach(button -> heights.add(button.getHeight()));
        });

    // then
    assertThat(heights.getFirst()).as("the row").isBetween(44, 48);
    assertThat(heights.subList(1, heights.size()))
        .as("its buttons and toggles")
        .isNotEmpty()
        .containsOnly(32);
  }

  /** Runs in the editor's light theme, as sizes depend on it, then back in the one before. */
  private static void inEditorsTheme(final Runnable run) {
    final LookAndFeel before = UIManager.getLookAndFeel();
    try {
      Theme.install();
      UIManager.setLookAndFeel(new FlatLightLaf());
      run.run();
    } catch (final UnsupportedLookAndFeelException e) {
      throw new IllegalStateException(e);
    } finally {
      Theme.uninstall();
      try {
        UIManager.setLookAndFeel(before);
      } catch (final UnsupportedLookAndFeelException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** The map at this width, laid out, with Territories' modes above it. */
  private static MapView givenToolbar(final int width) {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    final MapView map = new MapView(project, () -> {}, () -> {}, () -> {});
    new TerritoriesEditor(project, map).showing();
    map.setSize(width, 600);
    // twice, as the toolbar may change texts and lines in its layout
    layOut(map);
    layOut(map);
    return map;
  }

  /** A button of the toolbar by its accessible name. */
  private static AbstractButton toolbarButton(final MapView map, final String name) {
    return all(map)
        .flatMap(
            c ->
                c instanceof AbstractButton b
                        && name.equals(b.getAccessibleContext().getAccessibleName())
                    ? Stream.of(b)
                    : Stream.empty())
        .findFirst()
        .orElseThrow();
  }

  /** The buttons above the map, left to right. */
  private static List<AbstractButton> toolbarButtons(final MapView map) {
    final MapCanvas canvas = find(map, MapCanvas.class);
    final int mapTop = SwingUtilities.convertPoint(canvas.getParent(), 0, 0, map).y;
    return all(map)
        .filter(c -> c instanceof AbstractButton && shownComponent(map, c))
        .map(AbstractButton.class::cast)
        .filter(b -> SwingUtilities.convertPoint(b, 0, 0, map).y < mapTop)
        .sorted(Comparator.comparingInt(b -> SwingUtilities.convertPoint(b, 0, 0, map).x))
        .toList();
  }

  private static List<String> toolbarTexts(final MapView map) {
    return toolbarButtons(map).stream().map(AbstractButton::getText).toList();
  }

  /** Whether each toolbar control is as wide as it needs, inside the map, none over another. */
  private static boolean cutNothing(final MapView map) {
    final List<AbstractButton> buttons = toolbarButtons(map);
    final List<Rectangle> bounds =
        buttons.stream()
            .map(b -> SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), map))
            .toList();
    for (int i = 0; i < bounds.size(); i++) {
      final Rectangle one = bounds.get(i);
      if (one.x < 0
          || one.x + one.width > map.getWidth()
          || one.width < buttons.get(i).getPreferredSize().width) {
        return false;
      }
      for (int j = i + 1; j < bounds.size(); j++) {
        if (one.intersects(bounds.get(j))) {
          return false;
        }
      }
    }
    return true;
  }

  private static <T> T find(final MapView map, final Class<T> kind) {
    return all(map).filter(kind::isInstance).map(kind::cast).findFirst().orElseThrow();
  }

  @DisplayName("A view chosen above the map shows on the map and in the legend")
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
