package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import eddies.mapeditor.MapMakerWindow.Check;
import eddies.mapeditor.MapMakerWindow.Pill;
import eddies.mapeditor.MapMakerWindow.Primary;
import eddies.mapeditor.Theme.Status;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.Game;

final class MapMakerWindowTest {

  @DisplayName("The pill shows only with content: the problems known, or that Check found none")
  @Test
  void pillTellsTheProblems() {
    final List<Optional<Pill>> pills =
        List.of(
            MapMakerWindow.pill(0, Check.NOT_YET),
            MapMakerWindow.pill(0, Check.RUNNING),
            MapMakerWindow.pill(1, Check.NOT_YET),
            MapMakerWindow.pill(4, Check.DONE),
            MapMakerWindow.pill(0, Check.DONE));

    assertThat(pills)
        .as("no pill before Check; words and marks, never color alone")
        .containsExactly(
            Optional.empty(),
            Optional.empty(),
            Optional.of(new Pill("! 1 problem", Status.PROBLEM)),
            Optional.of(new Pill("! 4 problems", Status.PROBLEM)),
            Optional.of(new Pill("✓ No problems", Status.DONE)));
  }

  @DisplayName("Beside the save state: not checked, checking, or nothing once the pill says it")
  @Test
  void checkNoteBesideTheSaveState() {
    final List<String> notes =
        List.of(
            MapMakerWindow.checkNote(0, Check.NOT_YET),
            MapMakerWindow.checkNote(2, Check.NOT_YET),
            MapMakerWindow.checkNote(2, Check.RUNNING),
            MapMakerWindow.checkNote(0, Check.DONE));

    assertThat(notes)
        .as("problems known already show in the pill")
        .containsExactly("not checked", "", "checking…", "");
  }

  @DisplayName("One primary in the header: Save with changes, then Check, then Play")
  @Test
  void primaryIsWhatComesNext() {
    // given
    final MapProject saved = MapProject.newEmpty();
    final MapProject changed = MapProject.newEmpty();
    givenChange(changed);

    // when
    final List<Primary> primaries =
        List.of(
            MapMakerWindow.primary(changed, 0, Check.DONE),
            MapMakerWindow.primary(saved, 0, Check.NOT_YET),
            MapMakerWindow.primary(saved, 0, Check.RUNNING),
            MapMakerWindow.primary(saved, 3, Check.DONE),
            MapMakerWindow.primary(saved, 0, Check.DONE));

    // then
    assertThat(primaries)
        .as("changes first; Check until it ran and found nothing; only then Play")
        .containsExactly(
            Primary.SAVE, Primary.CHECK, Primary.CHECK, Primary.CHECK, Primary.PLAY);
  }

  @DisplayName("The header of an unchecked map: Check primary, Play a click away, no pill, no Save")
  @Test
  void headerOfAnUncheckedMap() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "the window needs a screen");
    // given
    final MapProject project = givenMinimapWithoutItalianLand();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapMakerWindow window = new MapMakerWindow();
          try {
            window.showProject(project);
            final List<AbstractButton> header = buttons(window.getContentPane()).toList();
            for (final String name : List.of("Check", "Play", "Save")) {
              final AbstractButton button =
                  header.stream().filter(b -> name.equals(b.getText())).findFirst().orElseThrow();
              seen.add(name + " " + button.isVisible() + " " + Theme.isPrimary(button));
            }
            seen.add(header.stream().anyMatch(b -> "File ▾".equals(b.getText())));
            seen.add(
                header.stream()
                    .filter(AbstractButton::isVisible)
                    .anyMatch(b -> String.valueOf(b.getText()).contains("problem")));
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(seen)
        .as("Check is the one primary; Play shows as secondary; Save is hidden; File; no pill")
        .containsExactly("Check true true", "Play true false", "Save false false", true, false);
  }

  @DisplayName("The File menu: open, new, recent, new game, export, publish, folder, close, version")
  @Test
  void fileMenuHoldsTheMapsActions() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "the window needs a screen");
    // given
    final MapProject project = givenMinimapWithoutItalianLand();
    final List<String> items = new ArrayList<>();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapMakerWindow window = new MapMakerWindow();
          try {
            window.showProject(project);
            final List<JMenuItem> menu =
                Stream.of(window.fileMenu().getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .toList();
            menu.forEach(
                item ->
                    items.add(
                        item.getText()
                            + Optional.ofNullable(item.getAccelerator())
                                .map(key -> " " + key.getKeyCode())
                                .orElse("")));
            menu.stream()
                .filter(item -> "Publish…".equals(item.getText()))
                .findFirst()
                .orElseThrow()
                .doClick();
            seen.add(
                buttons(window.getContentPane())
                    .anyMatch(
                        b ->
                            "Publish".equals(b.getAccessibleContext().getAccessibleName())
                                && b.isSelected()));
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(items.stream().map(item -> item.replaceFirst("^Play with.*", "Play with…")))
        .as("each with its key; the folder by the system's name")
        .containsExactly(
            "Open a map… " + KeyEvent.VK_O,
            "New map… " + KeyEvent.VK_N,
            "Recent maps",
            "New game from this one…",
            "Export as zip… " + KeyEvent.VK_F8,
            "Publish…",
            "Play with…",
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("mac")
                ? "Show in Finder"
                : "Show in Explorer",
            "Close map " + KeyEvent.VK_W,
            "Show all options",
            "Version dev");
    assertThat(seen).as("Publish… opens the Publish tool").containsExactly(true);
  }

  @DisplayName("The panel beside the map is 24 % of the window, at least 340 and at most 480 px")
  @Test
  void inspectorWidthFollowsTheWindow() {
    final List<Integer> widths =
        Stream.of(1000, 1280, 1600, 1920, 2560).map(MapMakerWindow::inspectorWidth).toList();

    assertThat(widths).as("clamped at both ends").containsExactly(340, 340, 384, 460, 480);
  }

  @DisplayName("A resize fits the panel beside the map again, unless its divider was dragged")
  @Test
  void draggedDividerIsKept() throws Exception {
    // given: a map and a panel as the window lays them out
    final List<Integer> widths = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final JSplitPane split =
              new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JPanel(), new JPanel());
          split.setBorder(null);
          MapMakerWindow.keepDragged(split);
          split.setSize(1600, 600);
          MapMakerWindow.fitInspector(split, 1280);
          split.doLayout();
          widths.add(split.getRightComponent().getWidth());
          MapMakerWindow.fitInspector(split, 1920);
          split.doLayout();
          widths.add(split.getRightComponent().getWidth());
          // the map maker drags the divider
          final Component divider = ((BasicSplitPaneUI) split.getUI()).getDivider();
          divider.dispatchEvent(
              new MouseEvent(divider, MouseEvent.MOUSE_PRESSED, 0, 0, 2, 2, 1, false));
          split.setDividerLocation(1600 - split.getDividerSize() - 600);
          split.doLayout();
          MapMakerWindow.fitInspector(split, 1280);
          split.doLayout();
          widths.add(split.getRightComponent().getWidth());
        });

    // then
    assertThat(widths)
        .as("fitted at 1280, again at 1920, then the dragged 600 px kept")
        .containsExactly(340, 460, 600);
  }

  @DisplayName("The header names the game only when it is named apart from the map")
  @Test
  void headerSaysTheNameOnce() {
    final String map = "Aggression 1941";

    final String same = MapMakerWindow.headerTitle(map, Optional.of(map));
    final String other = MapMakerWindow.headerTitle(map, Optional.of("Short game"));

    assertThat(same).as("not \"X · X\"").isEqualTo("Aggression 1941");
    assertThat(other).as("another game is told apart").isEqualTo("Aggression 1941 · Short game");
  }

  private static void givenChange(final MapProject project) {
    project.edit(game -> MapXml.withNation(game, "Red"));
  }

  @DisplayName("Following a step opens its tool with the step as the goal and the brush it needs")
  @Test
  void followsAStepIntoItsTool() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "the window needs a screen");
    // given: the minimap with the Italians, its second nation, owning no land
    final MapProject project = givenMinimapWithoutItalianLand();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapMakerWindow window = new MapMakerWindow();
          try {
            window.showProject(project);
            final MapView map = givenMap(window);
            window.followStep(MapGuide.Step.Id.LAND);
            seen.add(map.bannerText().orElseThrow());
            seen.add(map.goalStep());
            window.followStep(MapGuide.Step.Id.CAPITALS);
            seen.add(map.bannerText().orElseThrow());
            seen.add(map.goalStep());
            window.followStep(MapGuide.Step.Id.TERRITORIES);
            seen.add(map.goalText());
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(seen)
        .as("the Italians' owner brush, then the Capital brush, each with its step; no strip for"
            + " the territories")
        .containsExactly(
            "Painting owner Italians: click or drag across land to give it to Italians. Sea zones"
                + " stay as they are.",
            Optional.of(MapGuide.Step.Id.LAND),
            MapGuide.CAPITAL_HOW_TO,
            Optional.of(MapGuide.Step.Id.CAPITALS),
            Optional.empty());
  }

  private static MapProject givenMinimapWithoutItalianLand() {
    final Game minimap = MapXmlTest.givenMinimap();
    final Game without =
        MapXml.territories(minimap).stream()
            .filter(t -> MapXml.owner(minimap, t).equals(Optional.of("Italians")))
            .reduce(minimap, (g, t) -> MapXml.withOwner(g, t, null), (a, b) -> b);
    return new MapProject(Path.of("minimap.xml"), without, MapFiles.none());
  }

  /** The window's map, which it keeps to itself. */
  private static MapView givenMap(final MapMakerWindow window) {
    try {
      final Field map = MapMakerWindow.class.getDeclaredField("map");
      map.setAccessible(true);
      return (MapView) map.get(window);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @DisplayName("The units step, followed after the capitals, says what to do, no old brush")
  @Test
  void unitsStepLeavesTheBrushOfTheStepBefore() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "the window needs a screen");
    // given
    final MapProject project = givenMinimapWithoutItalianLand();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapMakerWindow window = new MapMakerWindow();
          try {
            window.showProject(project);
            final MapView map = givenMap(window);
            window.followStep(MapGuide.Step.Id.CAPITALS);
            window.followStep(MapGuide.Step.Id.UNITS);
            seen.add(map.bannerText().orElseThrow());
            seen.add(map.goalStep());
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(seen)
        .as("not still \"Painting capitals\": one sentence for the units, with their step")
        .containsExactly(
            "Click a territory, then + Add units in the panel on the right.",
            Optional.of(MapGuide.Step.Id.UNITS));
  }

  @DisplayName("Show on a note of Home opens Connections with the territory chosen")
  @Test
  void noteOpensConnections() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "the window needs a screen");
    // given: a map from a picture with a territory off the board
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    project.edit(game -> MapXml.withTerritory(game, "Atlantis", List.of()));
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapMakerWindow window = new MapMakerWindow();
          try {
            window.showProject(project);
            buttons(window.getContentPane())
                .filter(
                    b ->
                        "Show: Atlantis is not connected to any territory"
                            .equals(b.getAccessibleContext().getAccessibleName()))
                .findFirst()
                .orElseThrow()
                .doClick();
            seen.add(givenMap(window).bannerText().orElse(""));
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(seen)
        .as("in Connections, the tool that connects it, ready to click its neighbours")
        .containsExactly("Connecting Atlantis: click a territory to connect it or part it.");
  }

  private static Stream<AbstractButton> buttons(
      final Component component) {
    final Stream<AbstractButton> own =
        component instanceof AbstractButton b
            ? Stream.of(b)
            : Stream.empty();
    return component instanceof Container container
        ? Stream.concat(
            own,
            Stream.of(container.getComponents())
                .flatMap(MapMakerWindowTest::buttons))
        : own;
  }
}
