package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TerritoriesEditorTest {
  private static final String NORTH = "red north 1";
  private static final String SEA = "sea zone 1";
  @TempDir Path folder;

  private MapProject givenMinimap() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        TerritoriesEditorTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private static MapProject givenPictureMap() throws Exception {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    return project;
  }

  private static MapView givenMap(final MapProject project) {
    return new MapView(project, () -> {}, () -> {}, () -> {});
  }

  @DisplayName("Draw borders draws a line into the picture, one step; Make territories hands it on")
  @Test
  void drawsBordersAndFindsTerritories() throws Exception {
    // given: a map whose picture is held, its lines black and 3 px wide
    final MapProject project = givenPictureMap();
    final List<BufferedImage> found = new ArrayList<>();
    final BufferedImage[] before = new BufferedImage[1];
    final BufferedImage[] after = new BufferedImage[1];

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor =
              new TerritoriesEditor(project, givenMap(project), (tool, item) -> {}, found::add);
          before[0] = project.getFiles().picture().orElseThrow();
          editor.borderDrawn(List.of(new Point(300, 20), new Point(300, 100)));
          after[0] = project.getFiles().picture().orElseThrow();
          editor.findDrawnTerritories();
        });

    // then
    assertThat(after[0]).as("a new picture, one step").isNotSameAs(before[0]);
    assertThat(after[0].getRGB(300, 60) & 0xFFFFFF)
        .as("in the color of the picture's lines")
        .isZero();
    assertThat(before[0].getRGB(300, 60) & 0xFFFFFF).as("not there before").isNotZero();
    assertThat(project.canUndo()).as("it can be undone").isTrue();
    assertThat(found)
        .as("Make territories gets the picture with the line")
        .containsExactly(after[0]);
  }

  @DisplayName("Draw borders' Line brush, chosen in the banner, stays chosen when drawing again")
  @Test
  void lineBrushIsKept() throws Exception {
    // given
    final MapProject project = givenPictureMap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          final AbstractButton draw =
              find(map, AbstractButton.class, b -> "Draw borders".equals(b.getText()));
          draw.doClick();
          brush(map, "Line:").doClick();
          find(map, AbstractButton.class, b -> "Done".equals(b.getText())).doClick();
          draw.doClick();
          seen.add(map.bannerText().orElseThrow());
          seen.add(brush(map, "Line:").isSelected());
          brush(map, "Freehand:").doClick();
          seen.add(map.bannerText().orElseThrow());
        });

    // then
    assertThat(seen.get(0).toString()).as("the line brush's banner").startsWith("Click points");
    assertThat(seen.get(1)).as("its toggle on").isEqualTo(true);
    assertThat(seen.get(2))
        .as("freehand again")
        .isEqualTo("Drag on the map to draw a border line.");
  }

  @DisplayName("A click on the chosen brush keeps it, and the banner with a half-set line")
  @Test
  void chosenBrushAgainChangesNothing() throws Exception {
    // given
    final MapProject project = givenPictureMap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          new TerritoriesEditor(project, map).showing();
          find(map, AbstractButton.class, b -> "Draw borders".equals(b.getText())).doClick();
          brush(map, "Line:").doClick();
          final AbstractButton line = brush(map, "Line:");
          line.doClick();
          seen.add(line.isSelected());
          seen.add(brush(map, "Line:"));
          seen.add(line);
        });

    // then
    assertThat(seen.get(0)).as("still chosen").isEqualTo(true);
    assertThat(seen.get(1))
        .as("the banner not built again, which would drop the points set")
        .isSameAs(seen.get(2));
  }

  private static AbstractButton brush(final Container in, final String tip) {
    return find(
        in,
        AbstractButton.class,
        b -> b.getToolTipText() != null && b.getToolTipText().startsWith(tip) && shows(b));
  }

  /** The editor showing red north 1 with its more options open. */
  private static TerritoriesEditor givenMoreOptions(final MapProject project) {
    final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
    editor.show(NORTH);
    find(editor.page(), AbstractButton.class, b -> b.getText().contains("More options"))
        .doClick();
    return editor;
  }

  /** Sized and laid out as in the window, without one. */
  private static void givenLaidOut(final Component component, final int width, final int height) {
    component.setSize(width, height);
    layOut(component);
  }

  private static void layOut(final Component component) {
    component.doLayout();
    if (component instanceof Container container) {
      for (final Component inner : container.getComponents()) {
        layOut(inner);
      }
    }
  }

  private static <T extends Component> T find(
      final Container in, final Class<T> kind, final Predicate<T> wanted) {
    return all(in)
        .filter(kind::isInstance)
        .map(kind::cast)
        .filter(wanted)
        .findFirst()
        .orElseThrow();
  }

  private static Stream<Component> all(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }

  private static boolean shows(final Component component) {
    for (Component c = component; c != null; c = c.getParent()) {
      if (!c.isVisible()) {
        return false;
      }
    }
    return true;
  }

  private static JMenuItem menuItem(final TerritoriesEditor editor, final String text) {
    return find(editor.paintMenu(), JMenuItem.class, i -> text.equals(i.getText()));
  }

  @DisplayName("The inspector's link, title and fields start where the tool's title does")
  @Test
  void inspectorStartsAtTheEdge() throws Exception {
    final MapProject project = givenMinimap();
    final List<Integer> lefts = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          editor.show(NORTH);
          givenLaidOut(editor.page(), 320, 800);
          final AbstractButton back =
              find(editor.page(), AbstractButton.class, b -> "← All territories".equals(b.getText()));
          final JLabel name = find(editor.page(), JLabel.class, l -> "Name".equals(l.getText()));
          lefts.add(xIn(editor.page(), back) + back.getInsets().left);
          lefts.add(xIn(editor.page(), name));
        });

    assertThat(lefts).as("the back link's text and the Name label at the page's edge").containsOnly(0);
  }

  private static int xIn(final Component page, final Component part) {
    return SwingUtilities.convertPoint(part.getParent(), part.getLocation(), page).x;
  }

  @DisplayName("A text typed, committed and then cleared clears the option too")
  @Test
  void clearedTextClearsTheOption() throws Exception {
    final MapProject project = givenMinimap();
    final List<List<String>> after = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = givenMoreOptions(project);
          final JTextField resources =
              (JTextField)
                  find(editor.page(), JLabel.class, l -> "Resources per turn".equals(l.getText()))
                      .getLabelFor();
          resources.setText("2:Ore");
          resources.postActionEvent();
          after.add(TerritoryXml.values(project.game(), NORTH, "resources"));
          resources.setText("");
          resources.postActionEvent();
          after.add(TerritoryXml.values(project.game(), NORTH, "resources"));
        });

    assertThat(after)
        .as("committed, then cleared again")
        .containsExactly(List.of("2:Ore"), List.of());
  }

  @DisplayName("Another territory chosen shows its inspector from the top")
  @Test
  void anotherTerritoryScrollsInspectorToTop() throws Exception {
    final MapProject project = givenMinimap();
    final List<Point> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = givenMoreOptions(project);
          givenLaidOut(editor.page(), 340, 300);
          final JViewport inspector =
              find(editor.page(), JViewport.class, v -> v.getView() instanceof Form);
          inspector.setViewPosition(new Point(0, 200));
          seen.add(inspector.getViewPosition());

          editor.show(MapXml.territories(project.game()).getLast());
          layOut(editor.page());
          seen.add(inspector.getViewPosition());
        });

    assertThat(seen.getFirst()).as("scrolled down first").isEqualTo(new Point(0, 200));
    assertThat(seen.getLast()).as("back at the top").isEqualTo(new Point());
  }

  @DisplayName("A territory chosen in the list is scrolled into view on the map")
  @Test
  void listChoiceScrollsTheMap() throws Exception {
    final MapProject project = givenPictureMap();
    final List<JViewport> viewport = new ArrayList<>();
    final List<Point> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          givenLaidOut(map, 400, 400);
          // bigger than the view, so there is room to scroll
          find(map, MapCanvas.class, c -> true).zoomBy(4);
          layOut(map);
          viewport.add(find(map, JViewport.class, v -> v.getView() != null));
          seen.add(viewport.getFirst().getViewPosition());

          find(editor.page(), JList.class, l -> true)
              .setSelectedValue(MapXml.territories(project.game()).getLast(), false);
        });
    // a map not showing scrolls later
    SwingUtilities.invokeAndWait(() -> seen.add(viewport.getFirst().getViewPosition()));

    assertThat(seen.getLast()).as("moved to the territory").isNotEqualTo(seen.getFirst());
  }

  @DisplayName("The owner brush from Paint gives land its owner, leaves sea zones and says so")
  @Test
  void ownerBrushPaintsLandOnly() throws Exception {
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          menuItem(editor, "Italians").doClick();

          editor.clickOnMap(NORTH);
          editor.clickOnMap(SEA);
          seen.add(map.bannerText());
        });

    assertThat(MapXml.owner(project.game(), NORTH))
        .as("land painted")
        .contains("Italians");
    assertThat(MapXml.owner(project.game(), SEA)).as("sea zone left as it is").isEmpty();
    assertThat(seen)
        .as("the banner says what a click does")
        .containsExactly(
            Optional.of(
                "Painting owner Italians: click or drag across land to give it to Italians. Sea"
                    + " zones stay as they are."));
  }

  @DisplayName("Copy units names its source and is off until a territory is chosen")
  @Test
  void copyUnitsNeedsAChosenTerritory() throws Exception {
    final MapProject project = givenMinimap();
    final List<String> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          final JMenuItem before =
              find(editor.paintMenu(), JMenuItem.class, i -> i.getText().startsWith("Copy units"));
          seen.add(before.isEnabled() + " " + before.getToolTipText());

          editor.show(NORTH);
          final JMenuItem after =
              find(editor.paintMenu(), JMenuItem.class, i -> i.getText().startsWith("Copy units"));
          seen.add(after.isEnabled() + " " + after.getText());
        });

    assertThat(seen)
        .as("off with the reason, then on with the source in its label")
        .containsExactly("false Choose a territory first", "true Copy units of red north 1");
  }

  @DisplayName("A taken name on Enter keeps the territory's name and the text typed, with why")
  @Test
  void takenNameIsRefusedInline() throws Exception {
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          editor.show(NORTH);
          final JTextField name =
              (JTextField)
                  find(editor.page(), JLabel.class, l -> "Name".equals(l.getText())).getLabelFor();

          name.setText("red capitol");
          name.postActionEvent();
          seen.add(name.getText());
          seen.add(
              find(editor.page(), FieldError.class, FieldError::hasProblem).getText());
        });

    assertThat(MapXml.territories(project.game())).as("name kept").contains(NORTH);
    assertThat(seen)
        .as("the typed text stays, the error says why")
        .containsExactly(
            "red capitol", "! Already used, empty, or holds : < (. Kept \"red north 1\".");
  }

  @DisplayName("Removing a capital and victory city says what goes with it")
  @Test
  void removeDetailsListCapitalAndVictoryCity() throws Exception {
    final MapProject project = givenMinimap();

    final List<String> details = TerritoriesEditor.removeDetails(project.game(), "red capitol");

    assertThat(details)
        .as("capital and victory city")
        .containsExactly("It is the capital of Russians.", "It is a victory city.");
  }

  @DisplayName("Split with a line shows the split card; Split makes the new territory in one step")
  @Test
  void splitWithALine() throws Exception {
    final MapProject project = givenPictureMap();
    final String territory = givenLand(project);
    final Rectangle bounds = project.getFiles().polygons().get(territory).getFirst().getBounds();
    final int middle = bounds.x + bounds.width / 2;
    final List<Point> across =
        List.of(new Point(middle, bounds.y - 4), new Point(middle, bounds.y + bounds.height + 4));
    final int before = MapXml.territories(project.game()).size();
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.show(territory);
          find(editor.page(), AbstractButton.class, b -> "Split with a line…".equals(b.getText()))
              .doClick();
          seen.add(map.bannerText());
          editor.lineDrawn(across);
          seen.add(shows(find(editor.page(), JLabel.class, l -> l.getText().equals("Split " + territory))));
          find(editor.page(), AbstractButton.class, b -> "Split".equals(b.getText())).doClick();
          seen.add(map.bannerText());
        });
    final int after = MapXml.territories(project.game()).size();
    project.undo();

    assertThat(seen)
        .as("banner, split card, banner gone")
        .containsExactly(
            Optional.of(
                "Splitting "
                    + territory
                    + ": drag a line across it, from outside its border to outside again."),
            true,
            Optional.empty());
    assertThat(after).as("one more territory").isEqualTo(before + 1);
    assertThat(MapXml.territories(project.game())).as("one undo takes it back").hasSize(before);
  }

  @DisplayName("The split card says at once why a typed name is refused; Split is off then")
  @Test
  void splitCardRefusesNames() throws Exception {
    final MapProject project = givenPictureMap();
    final String territory = givenLand(project);
    final Rectangle bounds = project.getFiles().polygons().get(territory).getFirst().getBounds();
    final int middle = bounds.x + bounds.width / 2;
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          editor.show(territory);
          find(editor.page(), AbstractButton.class, b -> "Split with a line…".equals(b.getText()))
              .doClick();
          editor.lineDrawn(
              List.of(
                  new Point(middle, bounds.y - 4),
                  new Point(middle, bounds.y + bounds.height + 4)));
          final JTextField name =
              find(editor.page(), JTextField.class, f -> f.getText().equals(territory + " 2"));
          final AbstractButton split =
              find(editor.page(), AbstractButton.class, b -> "Split".equals(b.getText()));
          name.setText(territory);
          seen.add(split.isEnabled());
          seen.add(problems(editor));
          name.setText(" ");
          seen.add(split.isEnabled());
          seen.add(problems(editor));
        });

    assertThat(seen)
        .as("a taken name: off, with why; a blank one: off, without a message")
        .containsExactly(
            false, List.of("! Already used by a territory, nation or unit"), false, List.of());
  }

  private static List<String> problems(final TerritoriesEditor editor) {
    return all(editor.page())
        .filter(c -> c instanceof FieldError e && e.hasProblem())
        .map(c -> ((FieldError) c).getText())
        .toList();
  }

  @DisplayName("A line that does not cut the territory says so in the banner")
  @Test
  void lineNotCuttingSaysSo() throws Exception {
    final MapProject project = givenPictureMap();
    final String territory = givenLand(project);
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.show(territory);
          find(editor.page(), AbstractButton.class, b -> "Split with a line…".equals(b.getText()))
              .doClick();

          editor.lineDrawn(List.of(new Point(-50, -50), new Point(-10, -50)));
          seen.add(map.bannerText());
        });

    assertThat(seen)
        .as("the banner tells to drag again")
        .containsExactly(
            Optional.of(
                "That line doesn't cut "
                    + territory
                    + " in two. Drag again, from outside to outside."));
  }

  @DisplayName("All territories goes back to the list, the territory still chosen")
  @Test
  void backToTheList() throws Exception {
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          editor.show(NORTH);
          final JList<?> list = find(editor.page(), JList.class, l -> true);
          seen.add(shows(list));

          find(editor.page(), AbstractButton.class, b -> "← All territories".equals(b.getText()))
              .doClick();
          seen.add(shows(list));
          seen.add(list.getSelectedValue());
        });

    assertThat(seen)
        .as("the territory card first, then the list with the territory chosen")
        .containsExactly(false, true, NORTH);
  }

  /** The first land territory found in the picture. */
  private static String givenLand(final MapProject project) {
    return MapXml.territories(project.game()).stream()
        .filter(t -> !MapXml.isWater(project.game(), t))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("The Capital brush makes owned land its owner's capital; sea and unowned land stay")
  @Test
  void capitalBrushMarksOwnedLand() throws Exception {
    // given
    final MapProject project = givenMinimap();
    project.edit(g -> MapXml.withOwner(g, "red north 2", null));
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          menuItem(editor, "Capital").doClick();
          editor.clickOnMap(NORTH);
          seen.add(MapXml.capitalOf(project.game(), NORTH));
          for (final String unchanged : List.of(SEA, "red north 2")) {
            final Object before = project.game();
            editor.clickOnMap(unchanged);
            seen.add(project.game() == before);
          }
        });

    // then
    assertThat(seen)
        .as("Russians' capital; the sea zone and the land without owner unchanged")
        .containsExactly(Optional.of("Russians"), true, true);
  }

  @DisplayName("Painting another owner keeps the land step as the goal, with a box of nations")
  @Test
  void paintOwnerKeepsTheLandGoal() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          map.showStep(MapGuide.Step.Id.LAND);
          editor.paintOwner("Italians");
          seen.add(map.goalStep());
          seen.add(map.bannerText().orElseThrow());
          seen.add(find(map, JComboBox.class, box -> true).getSelectedItem());
        });

    // then
    assertThat(seen)
        .as("still the land step, painting the Italians, chosen in the box")
        .containsExactly(
            Optional.of(MapGuide.Step.Id.LAND),
            "Painting owner Italians: click or drag across land to give it to Italians. Sea zones"
                + " stay as they are.",
            "Italians");
  }

  @DisplayName("A terrain effect painted shows Back to Terrain effects, which opens it there")
  @Test
  void paintEffectLeadsBack() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<String> opened = new ArrayList<>();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor =
              new TerritoriesEditor(
                  project, map, (tool, item) -> opened.add(tool + ": " + item), picture -> {});
          editor.showing();
          editor.paintEffect("Forest");
          seen.add(map.goalText().orElseThrow());
          find(map, AbstractButton.class, b -> "Back to Terrain effects".equals(b.getText()))
              .doClick();
        });

    // then
    assertThat(seen).as("the effect is the goal").containsExactly("Terrain effect Forest");
    assertThat(opened).as("back to the effect").containsExactly("Terrain effects: Forest");
  }
}
