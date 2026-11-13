package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapGuide.Step;

/** Capitals: the brush that moves one, a nation without one on purpose, and who needs one. */
final class CapitalsTest {
  private static final String RED_CAPITAL = "red capitol";
  private static final String NORTH = "red north 1";
  private static final String BLUE_CAPITAL = "blue capitol";

  @TempDir Path folder;

  private MapProject givenMinimap() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        CapitalsTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private static TerritoriesEditor givenCapitalBrush(final MapProject project, final MapView map) {
    final TerritoriesEditor editor = new TerritoriesEditor(project, map);
    find(editor.paintMenu(), AbstractButton.class, i -> "Capital".equals(i.getText())).doClick();
    return editor;
  }

  private static MapView givenMapView(final MapProject project) {
    return new MapView(project, () -> {}, () -> {}, () -> {});
  }

  private static <T extends Component> T find(
      final Container in, final Class<T> kind, final Predicate<T> wanted) {
    return all(in).filter(kind::isInstance).map(kind::cast).filter(wanted).findFirst().orElseThrow();
  }

  private static Stream<Component> all(final Container in) {
    return Stream.of(in instanceof JMenu menu ? menu.getMenuComponents() : in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }

  private static Step capitalsStep(final MapProject project) {
    return MapGuide.steps(project).stream()
        .filter(s -> s.id() == Step.Id.CAPITALS)
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("The Capital brush moves a nation's capital: the old one is none, as one step")
  @Test
  void brushMovesTheCapital() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMapView(project);
          final TerritoriesEditor editor = givenCapitalBrush(project, map);
          seen.add(map.bannerText().orElse(""));
          editor.clickOnMap(NORTH);
          seen.add(MapXml.capitalsOf(project.game(), "Russians"));
          seen.add(map.bannerText().isPresent());
          project.undo();
          seen.add(MapXml.capitalsOf(project.game(), "Russians"));
        });

    // then
    assertThat(seen)
        .as("the banner says what a click does; the capital moved, the brush stays for a nation"
            + " that had one; one undo brings the old capital back")
        .containsExactly(
            "Click a territory to make it its owner's capital. One per nation: another click"
                + " moves it.",
            List.of(NORTH),
            true,
            List.of(RED_CAPITAL));
  }

  @DisplayName("A drag with the Capital brush paints nothing: capitals are clicked")
  @Test
  void dragPaintsNoCapitals() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final Game before = project.game();

    // when
    SwingUtilities.invokeAndWait(
        () ->
            givenCapitalBrush(project, givenMapView(project))
                .paintStroke(List.of(NORTH, "red north 2", "blue north 1")));

    // then
    assertThat(project.game()).as("no territory became a capital").isSameAs(before);
  }

  @DisplayName("The Capital brush ends by itself with the last capital missing")
  @Test
  void brushEndsWithTheLastCapital() throws Exception {
    // given the Italians without a capital
    final MapProject project = givenMinimap();
    project.edit(g -> MapXml.withCapital(g, BLUE_CAPITAL, null));
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapView map = givenMapView(project);
          final TerritoriesEditor editor = givenCapitalBrush(project, map);
          editor.clickOnMap("blue north 1");
          seen.add(MapXml.capitalsOf(project.game(), "Italians"));
          seen.add(map.bannerText());
          editor.clickOnMap("blue north 2");
          seen.add(MapXml.capitalsOf(project.game(), "Italians"));
        });

    // then
    assertThat(seen)
        .as("the capital is set, the brush is gone, and the next click only selects")
        .containsExactly(List.of("blue north 1"), Optional.empty(), List.of("blue north 1"));
  }

  @DisplayName("In Select mode, unticking a territory's Capital box sticks; ticking adds a second")
  @Test
  void capitalBoxSticks() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoriesEditor editor = new TerritoriesEditor(project, givenMapView(project));
          editor.clickOnMap(RED_CAPITAL);
          find(editor.page(), JCheckBox.class, b -> "Capital of Russians".equals(b.getText()))
              .doClick();
          seen.add(MapXml.capitalsOf(project.game(), "Russians"));
          editor.clickOnMap(NORTH);
          editor.clickOnMap(RED_CAPITAL);
          seen.add(
              find(editor.page(), JCheckBox.class, b -> "Capital of Russians".equals(b.getText()))
                  .isSelected());
          project.undo();
          editor.clickOnMap(NORTH);
          find(editor.page(), JCheckBox.class, b -> "Capital of Russians".equals(b.getText()))
              .doClick();
          seen.add(MapXml.capitalsOf(project.game(), "Russians"));
        });

    // then
    assertThat(seen)
        .as("unticked it is no capital, also after clicks on other territories; a second one"
            + " ticked in the panel stays beside the first")
        .containsExactly(List.of(), false, List.of(RED_CAPITAL, NORTH));
  }

  @DisplayName("A nation marked as having no capital is not asked for one by the guide")
  @Test
  void guideLeavesOutNationsWithoutCapitalOnPurpose() throws Exception {
    // given the Italians without a capital
    final MapProject project = givenMinimap();
    project.edit(g -> MapXml.withCapital(g, BLUE_CAPITAL, null));
    final Step asking = capitalsStep(project);

    // when
    project.editFiles(files -> files.withNoCapital("Italians"));

    // then
    assertThat(asking.done()).as("first the step asks for the Italians' capital").isFalse();
    assertThat(asking.progress())
        .as("and says so")
        .isEqualTo("1 of 2 nations with land have a capital · without: Italians");
    assertThat(capitalsStep(project).done()).as("marked, the step is done").isTrue();
    assertThat(capitalsStep(project).progress())
        .as("and counts only those that want one")
        .isEqualTo("1 of 1 nation with land has a capital · 1 need none");
    assertThat(project.getFiles().properties().getProperty("guide.noCapital"))
        .as("kept in map.properties under a key the game does not read")
        .isEqualTo("Italians");

    // when
    project.editFiles(files -> files.withCapitalWanted("Italians"));

    // then
    assertThat(project.getFiles().properties().containsKey("guide.noCapital"))
        .as("an emptied list removes the key")
        .isFalse();

    // when the nation is removed while marked
    project.editFiles(files -> files.withNoCapital("Italians"));
    final List<String> stillNamed = project.removeNation("Italians");

    // then
    assertThat(stillNamed).as("removed").isEmpty();
    assertThat(project.getFiles().nationsWithoutCapital()).as("its mark goes with it").isEmpty();
  }

  @DisplayName("Nations shows the name to rename and Has no capital, off while there is a capital")
  @Test
  void nationsInspectorRenamesAndMarksNoCapital() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final NationsEditor editor = new NationsEditor(project);
          editor.choose("Italians");
          final JCheckBox none =
              find(editor.page(), JCheckBox.class, b -> "Has no capital".equals(b.getText()));
          seen.add(none.isEnabled());
          project.edit(g -> MapXml.withCapital(g, BLUE_CAPITAL, null));
          find(editor.page(), JCheckBox.class, b -> "Has no capital".equals(b.getText()))
              .doClick();
          seen.add(project.getFiles().nationsWithoutCapital());
          final JTextField name =
              find(editor.page(), JTextField.class, f -> "Italians".equals(f.getText()));
          name.setText("Russians");
          seen.add(
              find(editor.page(), FieldError.class, e -> e.isVisible()).getText());
          name.postActionEvent();
          seen.add(MapXml.nations(project.game()));
          seen.add(name.getText());
          name.setText("Romans");
          name.postActionEvent();
          seen.add(MapXml.nations(project.game()));
          seen.add(project.getFiles().nationsWithoutCapital());
        });

    // then
    assertThat(seen)
        .as("off while it has a capital; marked; a taken name is told as typed and refused, the"
            + " typed text kept; a free one renames, the mark following")
        .containsExactly(
            false,
            java.util.Set.of("Italians"),
            "! Already used by a territory, nation or unit.",
            List.of("Russians", "Italians"),
            "Russians",
            List.of("Russians", "Romans"),
            java.util.Set.of("Romans"));
  }

  @DisplayName("The classic five nations each need a capital, whatever Nations says")
  @Test
  void classicGameNeedsCapitals() throws Exception {
    // given a game of exactly the classic five, the Germans without a capital
    Game game = givenMinimap().game();
    for (final String nation : List.of("Germans", "British", "Japanese", "Americans")) {
      game = MapXml.withNation(game, nation);
    }
    final Game five = MapXml.withoutNation(game, "Italians");
    final List<String> lands =
        MapXml.territories(five).stream().filter(t -> !MapXml.isWater(five, t)).toList();
    Game withCapitals = five;
    final List<String> others = List.of("British", "Japanese", "Americans");
    for (int i = 0; i < others.size(); i++) {
      final String land = lands.get(lands.size() - 1 - i);
      withCapitals = MapXml.withCapital(MapXml.withOwner(withCapitals, land, others.get(i)), land, others.get(i));
    }
    // the minimap plays by WW2V2, which the classic game does not
    final Game classic = MapXml.withPropertyValue(withCapitals, "WW2V2", "false");

    // when
    final List<String> problems =
        MapChecks.quick(classic, MapFiles.none()).stream().map(Problem::text).toList();

    // then
    assertThat(MapChecks.classicWithoutCapital(classic)).as("who lacks one").containsExactly("Germans");
    assertThat(problems)
        .as("told as a problem, in plain words")
        .anyMatch(p -> p.startsWith("Germans needs a capital"));
    assertThat(MapChecks.classicWithoutCapital(MapXml.withNation(classic, "Chinese")))
        .as("a sixth nation: TripleA does not look")
        .isEmpty();
    assertThat(MapChecks.classicWithoutCapital(withCapitals))
        .as("with WW2V2 neither")
        .isEmpty();
    assertThat(MapChecks.classicWithoutCapital(MapXmlTest.givenMinimap()))
        .as("the minimap has two nations only")
        .isEmpty();
  }
}
