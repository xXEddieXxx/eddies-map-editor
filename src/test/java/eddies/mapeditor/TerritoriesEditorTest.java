package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRootPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;

final class TerritoriesEditorTest {
  private static final String NORTH = "red north 1";
  private static final String SEA = "sea zone 1";
  private static final String BADGE = "Edits the picture";
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

  @DisplayName("Draw borders is an edit mode whose badge says it edits the map's picture")
  @Test
  void drawBordersSaysItEditsThePicture() throws Exception {
    // given
    final MapProject project = givenPictureMap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          seen.add(all(map).anyMatch(c -> c instanceof JLabel l && BADGE.equals(l.getText())));
          final AbstractButton draw =
              find(map, AbstractButton.class, b -> "Draw borders".equals(b.getText()));
          seen.add(draw.getIcon());
          seen.add(draw.getToolTipText().contains("changes the map's picture"));
          draw.doClick();
          seen.add(all(map).anyMatch(c -> c instanceof JLabel l && BADGE.equals(l.getText())));
        });

    // then
    assertThat(seen)
        .as("no badge before; the pencil beside the name and the warning in its tool tip; the"
            + " badge once drawing")
        .containsExactly(
            false, new MakerIcon(MakerIcon.Glyph.PENCIL, Theme.MAP_TOOL_ICON), true, true);
  }

  @DisplayName("From Map look, Territories opens with the first texture's brush")
  @Test
  void paintsTexturesFromMapLook() throws Exception {
    // given
    final MapProject project = givenPictureMap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          editor.paintTextures();
          seen.add(map.bannerText().orElse(""));
        });

    // then
    assertThat((String) seen.getFirst())
        .as("the banner of the texture brush")
        .startsWith("Painting " + Textures.names(project.getFiles()).getFirst());
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
        .isEqualTo("Drag a line.");
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

  @DisplayName("Show its name on the map, unticked, lists the territory as not drawn, one step")
  @Test
  void hidesNameOnMap() throws Exception {
    final MapProject project = givenMinimap();
    final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
    editor.show(NORTH);

    find(editor.page(), JCheckBox.class, b -> "Show its name on the map".equals(b.getText()))
        .doClick();

    assertThat(project.getFiles().listed(MapFiles.HIDDEN_NAMES))
        .as("the game draws no name on it")
        .containsExactly(NORTH);
    assertThat(
            find(editor.page(), JCheckBox.class, b -> "Show its name on the map".equals(b.getText()))
                .isSelected())
        .as("the inspector shows it")
        .isFalse();
    project.undo();
    assertThat(project.getFiles().properties().containsKey(MapFiles.HIDDEN_NAMES))
        .as("one undo, and no empty key")
        .isFalse();
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

  /** Everything in it, also what a menu in it opens. */
  private static Stream<Component> all(final Container in) {
    return Stream.of(in instanceof JMenu menu ? menu.getMenuComponents() : in.getComponents())
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

  /** The Paint menu's entry of the text, in a menu of it too; a texture's tile by its name. */
  private static AbstractButton menuItem(final TerritoriesEditor editor, final String text) {
    return find(
        editor.paintMenu(),
        AbstractButton.class,
        i -> text.equals(i.getText()) || text.equals(i.getToolTipText()));
  }

  @DisplayName("The Paint menu is short: a menu per kind of brush, Capital at the top level")
  @Test
  void paintMenuCascades() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<String> top = new ArrayList<>();
    final Map<String, List<String>> inside = new LinkedHashMap<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          for (final Component c : editor.paintMenu().getComponents()) {
            final JMenuItem item = (JMenuItem) c;
            top.add(item.getText() + (item instanceof JMenu ? " ›" : ""));
            if (item instanceof JMenu menu) {
              inside.put(
                  menu.getText(),
                  Stream.of(menu.getMenuComponents())
                      .filter(JMenuItem.class::isInstance)
                      .map(i -> ((JMenuItem) i).getText())
                      // the textures' tiles, listed below, have no text
                      .filter(text -> !text.isEmpty())
                      .toList());
            }
          }
          inside.put(
              "tiles",
              all(editor.paintMenu())
                  .filter(c -> c instanceof AbstractButton b && b.getIcon() != null)
                  .filter(c -> ((AbstractButton) c).getIcon().getIconWidth() == 48)
                  .map(c -> ((AbstractButton) c).getToolTipText())
                  .toList());
        });

    // then
    assertThat(top)
        .as("seven rows, so the menu fits a small window")
        .containsExactly(
            "Owner ›",
            "Capital",
            "PUs per turn ›",
            "Units ›",
            "Passable ›",
            "Terrain effects ›",
            "Textures ›");
    assertThat(inside.get("Owner"))
        .as("the nations, then No owner")
        .containsExactly("Russians", "Italians", "No owner");
    assertThat(inside.get("PUs per turn"))
        .as("the usual values, then any other")
        .containsExactly(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "12", "15", "20", "Other…");
    assertThat(inside.get("Units"))
        .as("copy and clear")
        .containsExactly("Copy units of the chosen one", "Clear units");
    assertThat(inside.get("Passable"))
        .as("both ways")
        .containsExactly("Impassable", "Passable");
    assertThat(inside.get("Textures"))
        .as("below the tiles")
        .containsExactly("No texture", "Import texture…", "Find textures in the map…");
    assertThat(inside.get("tiles"))
        .as("a 48 px tile per texture, named in its tool tip")
        .containsExactlyElementsOf(Textures.PREMADE);
  }

  @DisplayName("A PU brush paints the value typed into the banner's field, each stroke one step")
  @Test
  void puBrushPaintsTheBannersValue() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          menuItem(editor, "7").doClick();
          seen.add(map.bannerText().orElseThrow());
          final JSpinner value = find(map, JSpinner.class, s -> s.getValue().equals(7));
          editor.paintStroke(List.of(NORTH));
          seen.add(MapXml.production(project.game(), NORTH));
          // typed, without Enter: a press on the map takes no focus from the field
          ((JSpinner.DefaultEditor) value.getEditor()).getTextField().setText("13");
          final Game before = project.game();
          editor.paintStroke(List.of(NORTH, SEA));
          seen.add(MapXml.production(project.game(), NORTH));
          seen.add(MapXml.production(project.game(), SEA));
          project.undo();
          seen.add(project.game() == before);
        });

    // then
    assertThat(seen)
        .as("the menu's 7, then the typed 13 on the whole stroke, which one undo takes back")
        .containsExactly(
            "Painting PUs per turn: click or drag across territories to give them",
            7,
            13,
            13,
            true);
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

  @DisplayName("Paint is a brush with its name and ▾, found by its name, also with a brush chosen")
  @Test
  void paintIsAnIcon() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<AbstractButton> paint = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          menuItem(editor, "Italians").doClick();
          paint.add(
              find(
                  map,
                  AbstractButton.class,
                  b -> "Paint".equals(b.getAccessibleContext().getAccessibleName())));
        });

    // then
    final AbstractButton button = paint.getFirst();
    assertThat(button.getText()).as("its name and the arrow beside the icon").isEqualTo("Paint ▾");
    assertThat(button.getIcon())
        .as("a brush")
        .isEqualTo(new MakerIcon(MakerIcon.Glyph.BRUSH, Theme.MAP_TOOL_ICON));
    assertThat(button.isSelected()).as("on while painting").isTrue();
    assertThat(button.getToolTipText())
        .as("says what it does")
        .isEqualTo(
            "Paint: give many territories an owner, a capital, PUs, units, rules, effects or"
                + " textures");
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

  @DisplayName("A stroke of the owner brush paints all the land it passed as one step")
  @Test
  void strokePaintsAsOneStep() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<String> land = List.of(NORTH, "red north 2", "red middle 1");
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          editor.showing();
          menuItem(editor, "Italians").doClick();
          editor.paintStroke(List.of(NORTH, "red north 2", SEA, "red middle 1"));
          land.forEach(t -> seen.add(MapXml.owner(project.game(), t)));
          seen.add(MapXml.owner(project.game(), SEA));
          project.undo();
          seen.add(MapXml.owner(project.game(), NORTH));
        });

    // then
    assertThat(seen.subList(0, 3))
        .as("each land territory passed is Italian")
        .containsOnly(Optional.of("Italians"));
    assertThat(seen.get(3)).as("the sea zone stays as it is").isEqualTo(Optional.empty());
    assertThat(seen.get(4))
        .as("one undo takes back the whole stroke")
        .isNotEqualTo(Optional.of("Italians"));
  }

  @DisplayName("A texture stroke paints land and sea as one step; the banner moves the texture")
  @Test
  void texturePaintsAndMoves() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          menuItem(editor, "Coastal sea").doClick();
          editor.paintStroke(List.of(NORTH, SEA));
          seen.add(project.getFiles().textures());
          find(map, JSpinner.class, s -> s.getToolTipText().contains("right")).setValue(7);
          seen.add(project.getFiles().textureOffset("Coastal sea"));
          project.undo();
          project.undo();
          seen.add(project.getFiles().textures());
        });

    // then
    assertThat(seen.get(0))
        .as("each territory passed has the texture")
        .isEqualTo(Map.of(NORTH, "Coastal sea", SEA, "Coastal sea"));
    assertThat(seen.get(1)).as("X moves the texture").isEqualTo(new Point(7, 0));
    assertThat(seen.get(2)).as("the offset and the whole stroke are one step each").isEqualTo(Map.of());
  }

  @DisplayName("The map shows textures while a texture brush or No texture is chosen, else not")
  @Test
  void texturesShowWithTheirBrushes() throws Exception {
    // given: Territory 1 in forest, on screen within (0, 0) to (100, 55)
    final MapProject project = givenPictureMap();
    project.editFiles(files -> files.withTexture("Territory 1", Optional.of("Forest")));
    final List<Integer> changed = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          final MapCanvas canvas = find(map, MapCanvas.class, c -> true);
          canvas.setSize(400, 300);
          // the picture would be made smaller in the background
          canvas.hidePicture();
          final BufferedImage before = shown(canvas);
          menuItem(editor, "Forest").doClick();
          changed.add(changedPixels(before, shown(canvas)));
          menuItem(editor, "Impassable").doClick();
          changed.add(changedPixels(before, shown(canvas)));
          menuItem(editor, "No texture").doClick();
          changed.add(changedPixels(before, shown(canvas)));
          find(map, AbstractButton.class, b -> "Done".equals(b.getText())).doClick();
          changed.add(changedPixels(before, shown(canvas)));
        });

    // then: of Territory 1's 5,500 pixels on screen
    assertThat(changed.get(0)).as("the forest brush shows the trees").isGreaterThan(50);
    assertThat(changed.get(0))
        .as("as sparse strokes: the owner's color stays between them")
        .isLessThan(5500 / 2);
    assertThat(changed.get(1)).as("another brush does not").isZero();
    assertThat(changed.get(2)).as("No texture shows what it clears").isGreaterThan(50);
    assertThat(changed.get(3)).as("gone once painting is done").isZero();
  }

  private static BufferedImage shown(final MapCanvas canvas) {
    final BufferedImage shown = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
    final java.awt.Graphics2D g = shown.createGraphics();
    canvas.paint(g);
    g.dispose();
    return shown;
  }

  /** How many pixels within Territory 1 on screen differ. */
  private static int changedPixels(final BufferedImage before, final BufferedImage after) {
    int changed = 0;
    for (int y = 0; y < 55; y++) {
      for (int x = 0; x < 100; x++) {
        changed += before.getRGB(x, y) != after.getRGB(x, y) ? 1 : 0;
      }
    }
    return changed;
  }

  @DisplayName("The Circle draws a closed ring into the picture, one step; it makes a new area")
  @Test
  void circleDrawsARing() throws Exception {
    // given: Territory 4 is the grey quarter at the bottom right, (200, 110) to (400, 220)
    final MapProject project = givenPictureMap();
    final BufferedImage before = project.getFiles().picture().orElseThrow();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          find(map, AbstractButton.class, b -> "Draw borders".equals(b.getText())).doClick();
          brush(map, "Circle:").doClick();
          seen.add(map.bannerText().orElseThrow());
          editor.borderDrawn(MapCanvas.ellipse(new Point(240, 130), new Point(360, 200)));
        });
    final BufferedImage after = project.getFiles().picture().orElseThrow();
    final int areasBefore = MapPicture.detect(before).territories().size();
    final int areasAfter = MapPicture.detect(after).territories().size();
    project.undo();

    // then
    assertThat(seen)
        .as("the circle's banner")
        .containsExactly("Drag a circle.");
    assertThat(after.getRGB(240, 165) & 0xFFFFFF).as("the ring in the lines' color").isZero();
    assertThat(after.getRGB(300, 165) & 0xFFFFFF).as("its inside as it was").isNotZero();
    assertThat(areasAfter).as("Make territories finds the inside").isEqualTo(areasBefore + 1);
    assertThat(project.getFiles().picture())
        .as("one undo, the picture as before")
        .containsSame(before);
  }

  @DisplayName("Territory picture files: safe names, each once in an export, ignoring case")
  @Test
  void pictureFileNames() {
    // given
    final List<String> territories =
        List.of("Sea Zone 1", "A/B\\C:D?", "A_B_C_D_", "north", "North", "CON", "Cape.");

    // when
    final Map<String, String> files = TerritoriesEditor.pictureFiles(territories);

    // then
    assertThat(files)
        .as("plain names stay; others made safe; a clash numbered")
        .containsExactly(
            Map.entry("Sea Zone 1", "Sea Zone 1.png"),
            Map.entry("A/B\\C:D?", "A_B_C_D_.png"),
            Map.entry("A_B_C_D_", "A_B_C_D_ (2).png"),
            Map.entry("north", "north.png"),
            Map.entry("North", "North (2).png"),
            Map.entry("CON", "CON_.png"),
            Map.entry("Cape.", "Cape_.png"));
  }

  @DisplayName("Exporting again keeps the positions of territories exported before")
  @Test
  void picturePositionsMerge() {
    // given
    final List<String> before = List.of("Alpha;Alpha.png;1;2", "Beta;Beta.png;3;4");
    final Map<String, String> now = Map.of("Beta", "Beta;Beta.png;5;6");

    // when
    final List<String> merged = TerritoriesEditor.withPositions(before, now);

    // then
    assertThat(merged)
        .as("Alpha kept, Beta's line replaced")
        .containsExactly("Alpha;Alpha.png;1;2", "Beta;Beta.png;5;6");
  }

  @DisplayName("For the units step a land territory without units is chosen, its Add units shown")
  @Test
  void choosesATerritoryForUnits() throws Exception {
    // given: a map from a picture, no units anywhere
    final MapProject project = givenPictureMap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.showing();
          editor.paintCapitals();
          editor.select();
          seen.add(map.bannerText().isPresent());
          editor.chooseForUnits();
          seen.add(
              all(editor.page())
                  .anyMatch(
                      c -> c instanceof AbstractButton b && "+ Add units".equals(b.getText())));
        });

    // then
    assertThat(seen)
        .as("the capital brush is gone; a land territory is open with its Add units")
        .containsExactly(false, true);
  }

  @DisplayName("The Places menu: kinds by name, the sea zones' last, (none) where none shows")
  @Test
  void placesMenuInPlainWords() throws Exception {
    // given: a map from a picture, no place set yet
    final MapProject project = givenPictureMap();
    final List<String> items = new ArrayList<>();
    final List<String> tips = new ArrayList<>();
    final List<String> layOut = new ArrayList<>();
    final List<String> create = new ArrayList<>();
    final List<String> disabled = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          for (final Component c : editor.placesMenu().getComponents()) {
            if (c instanceof JMenu menu) {
              items.add(menu.getText());
              final List<String> texts = menu.getText().startsWith("Lay out") ? layOut : create;
              Stream.of(menu.getMenuComponents())
                  .filter(JMenuItem.class::isInstance)
                  .map(JMenuItem.class::cast)
                  .forEach(
                      item -> {
                        texts.add(item.getText());
                        if (!item.isEnabled()) {
                          disabled.add(item.getText());
                        }
                      });
            } else if (c instanceof JMenuItem item) {
              // the grey of "(none)" is HTML
              items.add(item.getText().replaceAll("<[^>]+>", ""));
              tips.add(item.getToolTipText());
            } else {
              items.add("—");
            }
          }
        });

    // then
    assertThat(items)
        .as("units, the kinds by name, the sea zones' own, the two menus, the reload; (none) only"
            + " for kinds no territory shows, found places count")
        .containsExactly(
            "Units",
            "Capitals (none)",
            "Centers",
            "Comments (none)",
            "Decorations (none)",
            "Names",
            "PUs (none)",
            "Terrain effects (none)",
            "Victory cities (none)",
            "—",
            "Blockades (none)",
            "Convoys (none)",
            "Kamikaze (none)",
            "—",
            "Lay out unit places",
            "Create missing places",
            "Reload places from the files");
    assertThat(tips.get(0)).as("the file only in the tool tip").endsWith("place.txt");
    assertThat(tips.get(5)).as("the names' file").endsWith("name_place.txt");
    assertThat(layOut)
        .as("no territory chosen: each layout for all")
        .containsExactly(
            "Round the center — in all territories",
            "In rows below the center — in all territories");
    assertThat(create)
        .as("how many are missing per kind: 5 territories, none with a value or capital")
        .startsWith("Units — 5 missing", "Names — 5 missing", "PUs — none missing")
        .endsWith("All kinds — 10 missing");
    assertThat(disabled)
        .as("nothing to create: off")
        .contains("PUs — none missing", "Capitals — none missing");
  }

  @DisplayName("Create missing places gives every territory its place as one step")
  @Test
  void createsMissingPlaces() throws Exception {
    // given
    final MapProject project = givenPictureMap();
    final MapFiles files = project.getFiles();

    // when
    SwingUtilities.invokeAndWait(
        () ->
            new TerritoriesEditor(project, givenMap(project))
                .createMissingPlaces(List.of(MapCanvas.PlaceKind.UNITS, MapFiles.Marker.NAME)));

    // then
    assertThat(project.getFiles().places().units()).as("unit places for all 5").hasSize(5);
    assertThat(project.getFiles().places().markers(MapFiles.Marker.NAME))
        .as("and names")
        .hasSize(5);
    project.undo();
    assertThat(project.getFiles()).as("one undo takes all back").isSameAs(files);
  }

  @DisplayName("Merge into this: each click on the map merges that territory, the keeper stays")
  @Test
  void mergeModeMergesClicked() throws Exception {
    // given
    final MapProject project = givenPictureMap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMap(project);
          final TerritoriesEditor editor = new TerritoriesEditor(project, map);
          editor.show("Territory 1");
          find(editor.page(), AbstractButton.class, b -> "Merge into this…".equals(b.getText()))
              .doClick();
          seen.add(map.bannerText());
          editor.clickOnMap("Territory 2");
          editor.clickOnMap("Sea Zone 1");
          seen.add(map.bannerText().isPresent());
          seen.add(
              all(editor.page())
                  .anyMatch(c -> c instanceof JTextField f && "Territory 1".equals(f.getText())));
        });

    // then
    assertThat(seen)
        .as("the banner; still merging after the clicks; the keeper still shown")
        .containsExactly(
            Optional.of(
                "Merging into Territory 1: click each territory that belongs to it."
                    + " Esc or Done ends."),
            true,
            true);
    assertThat(MapXml.territories(project.game()))
        .as("the land clicked is part of it now; the sea zone, refused, stays")
        .doesNotContain("Territory 2")
        .contains("Territory 1", "Sea Zone 1");
    assertThat(project.getFiles().polygons().get("Territory 1")).as("two outlines").hasSize(2);
    project.undo();
    assertThat(MapXml.territories(project.game())).as("one step per click").contains("Territory 2");
  }

  @DisplayName("Remove territories lists the smallest first, each with its area")
  @Test
  void removeListSmallestFirst() {
    // given
    final MapFiles files =
        new MapFiles(
            Optional.empty(),
            Map.of(
                "Big",
                List.of(givenSquare(0, 100)),
                "Islands",
                List.of(givenSquare(200, 10), givenSquare(300, 40))),
            Map.of(),
            new Properties(),
            Optional.empty(),
            Map.of(),
            Map.of(),
            Optional.empty(),
            MapFiles.Places.none());

    // when
    final List<String> lines =
        TerritoriesEditor.smallestFirst(List.of("Big", "Islands", "Ghost"), files).stream()
            .map(t -> TerritoriesEditor.areaLine(t, files))
            .toList();

    // then
    assertThat(lines)
        .as("one without an outline, then by the area of all its outlines")
        .containsExactly("Ghost · not on the map", "Islands · 1,700 px²", "Big · 10,000 px²");
  }

  private static Polygon givenSquare(final int at, final int side) {
    return new Polygon(
        new int[] {at, at + side, at + side, at}, new int[] {0, 0, side, side}, 4);
  }

  @DisplayName("What a merge drops is told: the other's owner, units and value")
  @Test
  void mergeLossesAreTold() throws Exception {
    // given
    final MapProject project = givenMinimap();

    // when
    final List<String> lost = TerritoriesEditor.mergeLosses(project.game(), "blue north 1", NORTH);
    final List<String> ofSea = TerritoriesEditor.mergeLosses(project.game(), "sea zone 2", SEA);

    // then
    assertThat(lost)
        .as("another owner's land with units and a value")
        .contains("Its owner, Russians.", "Its starting units.");
    assertThat(lost).as("its value").anyMatch(line -> line.endsWith("per turn."));
    assertThat(ofSea).as("an empty sea zone has nothing to lose: no question asked").isEmpty();
  }

  @DisplayName("Draw borders' eraser takes a line out of the picture, one step")
  @Test
  void eraserErasesBorder() throws Exception {
    // given: a map whose picture is held, a black line 3 px wide down x 199 to 201
    final MapProject project = givenPictureMap();
    final BufferedImage before = project.getFiles().picture().orElseThrow();

    // when
    SwingUtilities.invokeAndWait(
        () ->
            new TerritoriesEditor(project, givenMap(project))
                .bordersErased(List.of(new Point(200, 20), new Point(200, 90))));

    // then
    assertThat(MapPicture.isBorder(before.getRGB(200, 50))).as("a line before").isTrue();
    assertThat(MapPicture.isBorder(project.getFiles().picture().orElseThrow().getRGB(200, 50)))
        .as("erased with the default width, three times the line's")
        .isFalse();
    project.undo();
    assertThat(project.getFiles().picture()).as("one step").containsSame(before);
  }

  @DisplayName("A sea zone's canal is told once in a merge, not also as its settings")
  @Test
  void mergeLossesCountOnlyTerritorySettings() throws Exception {
    // given: a canal on two sea zones, which puts a canalAttachment on each
    final MapProject project = givenMinimap();
    final Game game =
        CanalXml.withCanal(
            project.game(),
            "",
            new CanalXml.Canal("Strait", List.of(SEA, "sea zone 2"), List.of(NORTH), List.of()));

    // when
    final List<String> lost = TerritoriesEditor.mergeLosses(game, "sea zone 3", SEA);

    // then
    assertThat(lost)
        .as("the canal that goes with it; the canal's own options are no settings of the zone")
        .containsExactly("The canal Strait goes with it.");
  }

  @DisplayName("Create missing places is off for a place file that does not read, and says why")
  @Test
  void createMissingOffForUnreadFile() throws Exception {
    // given: a saved map whose place.txt names a territory twice
    final MapProject made =
        MapProject.newMap(
                "Island Wars",
                MapPictureTest.givenPicture(),
                List.of("Red"),
                folder.resolve("maps"))
            .project();
    Files.writeString(
        made.getFolder().orElseThrow().resolve("place.txt"),
        "Sea Zone 1 (1,2)\r\nSea Zone 1 (3,4)\r\n");
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMap(project));
          seen.add(
              ((JMenuItem) editor.placesMenu().getComponent(0))
                  .getText()
                  .replaceAll("<[^>]+>", ""));
          Stream.of(editor.placesMenu().getComponents())
              .filter(c -> c instanceof JMenu menu && menu.getText().startsWith("Create"))
              .map(c -> (JMenuItem) ((JMenu) c).getMenuComponent(0))
              .forEach(
                  units -> {
                    seen.add(units.getText());
                    seen.add(units.isEnabled());
                    seen.add(units.getToolTipText());
                  });
        });

    // then
    assertThat(seen)
        .as("the units' kind says so; no places to create over lines that are not known")
        .containsExactly(
            "Units (not read)",
            "Units — file does not read",
            false,
            "place.txt does not read, eg for a name twice: open it in Files to put it right");
  }

  @DisplayName("Lay out unit places moves centers only when asked, as one step with the places")
  @Test
  void layOutMovesCentersWhenAsked() throws Exception {
    // given
    final MapProject project = givenPictureMap();
    final MapFiles before = project.getFiles();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor =
              new TerritoriesEditor(project, givenMap(project), (tool, item) -> {}, picture -> {});
          final JMenu layOut =
              find(editor.placesMenu(), JMenu.class, menu -> menu.getText().startsWith("Lay out"));
          final JCheckBox move =
              find(layOut, JCheckBox.class, box -> box.getText().startsWith("Move centers"));
          seen.add(move.isSelected());
          seen.add(find(layOut, JSpinner.class, spinner -> spinner.getParent() == move.getParent())
              .getValue());
          final AbstractButton all =
              find(
                  layOut,
                  AbstractButton.class,
                  item -> "In rows below the center — in all territories".equals(item.getText()));
          all.doClick();
          seen.add(project.getFiles().centers() == before.centers());
          project.undo();
          move.setSelected(true);
          all.doClick();
        });
    // laid out in the background, then taken on the Swing thread
    final MapFiles moved = givenFilesOtherThan(project, before);
    project.undo();

    // then
    assertThat(seen)
        .as("off at first, up to 27 px; without it the layout leaves the centers")
        .containsExactly(false, 27, true);
    assertThat(moved.centers())
        .as("ticked: a center moved where a second row of units fits")
        .isNotEqualTo(before.centers());
    assertThat(moved.centers().keySet()).as("no center lost").isEqualTo(before.centers().keySet());
    assertThat(project.getFiles()).as("one undo takes centers and places back").isSameAs(before);
  }

  @DisplayName("Lay out unit places done in the background is refused when the map changed")
  @Test
  void layOutRefusedAfterAChange() throws Exception {
    // given: the editor in a window, centers to be moved
    final MapProject project = givenPictureMap();
    final JRootPane window = new JRootPane();
    final List<MapFiles> changed = new ArrayList<>();

    // when: the files change before the layout's result comes back
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor =
              new TerritoriesEditor(project, givenMap(project), (tool, item) -> {}, picture -> {});
          window.getContentPane().add(editor.page());
          final JMenu layOut =
              find(editor.placesMenu(), JMenu.class, menu -> menu.getText().startsWith("Lay out"));
          find(layOut, JCheckBox.class, box -> box.getText().startsWith("Move centers"))
              .setSelected(true);
          find(
                  layOut,
                  AbstractButton.class,
                  item -> "In rows below the center — in all territories".equals(item.getText()))
              .doClick();
          project.editFiles(files -> files.withProperty("units.scale", "0.75"));
          changed.add(project.getFiles());
        });
    final List<String> told = new ArrayList<>(List.of(""));
    for (int i = 0; i < 500 && !told.getFirst().startsWith("!"); i++) {
      Thread.sleep(20);
      SwingUtilities.invokeAndWait(
          () ->
              told.set(
                  0,
                  Toast.shown(window.getLayeredPane())
                      .map(toast -> toast.getAccessibleContext().getAccessibleDescription())
                      .orElse("")));
    }

    // then
    assertThat(told.getFirst())
        .as("the map maker is told to lay them out again")
        .startsWith("! The map changed");
    assertThat(project.getFiles()).as("the layout is not taken").isSameAs(changed.getFirst());
  }

  @DisplayName("Lay out unit places says the size units are drawn at, with the way to Map look")
  @Test
  void layOutSaysTheUnitSize() throws Exception {
    // given
    final MapProject project = givenPictureMap();
    final String standard = TerritoriesEditor.unitSizeLine(project.getFiles());
    project.editFiles(
        files ->
            files
                .withProperty("units.width", "54")
                .withProperty("units.height", "60")
                .withProperty("units.scale", "0.75"));
    final List<String> shown = new ArrayList<>();
    final List<String> opened = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor =
              new TerritoriesEditor(
                  project, givenMap(project), (tool, item) -> opened.add(tool), picture -> {});
          final JMenu layOut =
              find(editor.placesMenu(), JMenu.class, menu -> menu.getText().startsWith("Lay out"));
          shown.add(
              find(layOut, JLabel.class, label -> label.getText().startsWith("Units")).getText());
          find(layOut, AbstractButton.class, button -> "Map look ›".equals(button.getText()))
              .doClick();
        });

    // then
    assertThat(standard).as("TripleA's own size").isEqualTo("Units are drawn 48 × 48 px at 100 %");
    assertThat(shown)
        .as("as map.properties says")
        .containsExactly("Units are drawn 54 × 60 px at 75 %");
    assertThat(opened).as("the link leads to where it is set").containsExactly("Map look");
  }

  /** The project's files once they are no longer these, as background work changed them. */
  private static MapFiles givenFilesOtherThan(final MapProject project, final MapFiles these)
      throws Exception {
    final List<MapFiles> now = new ArrayList<>(List.of(these));
    for (int i = 0; i < 500 && now.getFirst() == these; i++) {
      Thread.sleep(20);
      SwingUtilities.invokeAndWait(() -> now.set(0, project.getFiles()));
    }
    return now.getFirst();
  }
}
