package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;

final class NationsEditorTest {
  @TempDir Path folder;

  private MapProject givenG40() throws Exception {
    final Path xml = folder.resolve("ww2global40.xml");
    try (InputStream original =
        NationsEditorTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private MapProject givenMinimap() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        NationsEditorTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  /** The Alliance box of the nation shown: the editable one. */
  @SuppressWarnings("unchecked")
  private static JComboBox<String> givenAllianceBox(final NationsEditor editor) {
    return (JComboBox<String>) find(editor.page(), JComboBox.class)
        .filter(JComboBox::isEditable)
        .findFirst()
        .orElseThrow();
  }

  private static <T> Stream<T> find(final Container in, final Class<T> type) {
    return Stream.of(in.getComponents())
        .flatMap(c -> Stream.concat(
            type.isInstance(c) ? Stream.of(type.cast(c)) : Stream.empty(),
            c instanceof Container inner ? find(inner, type) : Stream.empty()));
  }

  @DisplayName("A nation alone in an alliance of its name shows No alliance, written as before")
  @Test
  void ownAllianceShowsAsNone() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final NationsEditor editor = new NationsEditor(project);
    final String nation = (String) givenChosen(editor);
    final JComboBox<String> box = givenAllianceBox(editor);
    final Game before = project.game();

    // when
    final Object shown = box.getSelectedItem();
    final List<String> items = IntStream.range(0, box.getItemCount())
        .mapToObj(box::getItemAt).toList();
    box.setSelectedItem("No alliance");
    final Game unchanged = project.game();
    box.setSelectedItem("Axis");
    final List<String> joined = MapXml.alliances(project.game(), nation);
    givenAllianceBox(editor).setSelectedItem("No alliance");

    // then
    assertThat(MapXml.alliances(before, nation))
        .as("the minimap gives %s an alliance of its own name", nation)
        .containsExactly(nation);
    assertThat(shown).as("the box says what the list says").isEqualTo("No alliance");
    assertThat(items).as("its own alliance is not offered twice").doesNotContain(nation);
    assertThat(unchanged).as("choosing what it has writes nothing").isSameAs(before);
    assertThat(joined).as("a typed alliance is written").containsExactly("Axis");
    assertThat(MapXml.alliances(project.game(), nation))
        .as("No alliance is written as the map had it: an alliance of its own name")
        .containsExactly(nation);
  }

  private static Object givenChosen(final NationsEditor editor) throws Exception {
    final Field selected = NationsEditor.class.getDeclaredField("selected");
    selected.setAccessible(true);
    return selected.get(editor);
  }

  @DisplayName("A click on the map chooses the owner and keeps a rule value typed, not taken yet")
  @Test
  void clickOnMapKeepsTyping() throws Exception {
    // given
    final MapProject project = givenG40();
    final NationsEditor editor = new NationsEditor(project);
    editor.click("Japan");
    final String value =
        RulesXml.playerOptions(project.game(), "Japanese").getFirst().getValue();
    final JTextField field =
        find(editor.page(), JTextField.class)
            .filter(f -> value.equals(f.getText()))
            .findFirst()
            .orElseThrow();

    // when
    field.setText("Germans:PUs");
    editor.click("Germany");

    // then
    assertThat(givenChosen(editor)).as("the owner of the land clicked").isEqualTo("Germans");
    assertThat(RulesXml.playerOptions(project.game(), "Japanese").getFirst().getValue())
        .as("the typed value was taken before the form was built anew")
        .isEqualTo("Germans:PUs");
  }

  @DisplayName("A unit picture's menu keeps the unit out of the nation colors, and back")
  @Test
  void unitMenuKeepsItsColors() throws Exception {
    // given
    final MapProject project = givenMinimap();
    final NationsEditor editor = new NationsEditor(project);

    // when
    final JCheckBoxMenuItem first =
        (JCheckBoxMenuItem) editor.tintMenu("infantry").getComponent(0);
    first.doClick();
    final List<String> kept = project.getFiles().untintedUnits();
    final JCheckBoxMenuItem again =
        (JCheckBoxMenuItem) editor.tintMenu("infantry").getComponent(0);
    again.doClick();

    // then
    assertThat(first.getText()).as("the choice").isEqualTo("In each nation's color");
    assertThat(kept).as("unticked: in its own colors").containsExactly("infantry");
    assertThat(again.isSelected()).as("ticked again").isTrue();
    assertThat(project.getFiles().untintedUnits()).as("in the nation colors again").isEmpty();
  }

  @DisplayName("A unit limit is written as TripleA reads it: count, how it counts, the units")
  @Test
  void unitLimitIsWrittenInTripleAsFormat() {
    final List<String> units = List.of("infantry", "artillery");

    final String some = NationsEditor.unitLimit(2, "owned", units);
    final String all = NationsEditor.unitLimit(2, "total", List.of("All units", "infantry"));

    assertThat(some)
        .as("count:owned|allied|total:unit:unit, not the old 2:territory:infantry")
        .isEqualTo("2:owned:infantry:artillery");
    assertThat(all).as("All units ticked wins and writes all").isEqualTo("2:total:all");
  }

  @DisplayName("A new Nations tool shows the first nation, and More options counts its rules")
  @Test
  void firstNationIsChosenAndRulesCounted() throws Exception {
    final MapProject project = givenG40();
    final Game game = project.game();

    final NationsEditor editor = new NationsEditor(project);
    final Game withLimit =
        RulesXml.withPlayerOptionAdded(game, "Japanese", "placementLimit", "1:owned:all");

    assertThat(givenChosen(editor))
        .as("no empty details on open")
        .isEqualTo(MapXml.nations(game).getFirst());
    assertThat(NationsEditor.moreCount(game, "Germans")).as("Germans set none").isZero();
    assertThat(NationsEditor.moreCount(withLimit, "Japanese"))
        .as("suicideAttackResources and the new placementLimit")
        .isEqualTo(2);
  }

  @DisplayName("A list of nations reads short, all of them up to three")
  @Test
  void nationListReadsShort() {
    final List<String> five = List.of("Russians", "British", "Chinese", "ANZAC", "French");

    final String shown = NationsEditor.shortList(five);

    assertThat(shown).as("two and how many more").isEqualTo("Russians, British and 3 more");
    assertThat(NationsEditor.shortList(five.subList(0, 3)))
        .as("three are named")
        .isEqualTo("Russians, British and Chinese");
    assertThat(NationsEditor.shortList(List.of())).as("none").isEqualTo("None");
  }

  @DisplayName("Mark one in Territories follows the capitals step")
  @Test
  void markCapitalFollowsTheStep() throws Exception {
    // given: the Russians without their capital
    final MapProject project = givenMinimap();
    project.edit(g -> MapXml.withCapital(g, "red capitol", null));
    final List<MapGuide.Step.Id> followed = new ArrayList<>();
    final MapView map = new MapView(project, () -> {}, () -> {}, () -> {});
    map.onFollowStep(followed::add);
    final NationsEditor editor = new NationsEditor(project, map);
    editor.click("red north 1");

    // when
    find(editor.page(), JButton.class)
        .filter(b -> "Mark one in Territories".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();

    // then
    assertThat(followed).as("the capitals step").containsExactly(MapGuide.Step.Id.CAPITALS);
  }

  @DisplayName("The line under a nation's name wraps in a narrow inspector instead of being cut")
  @Test
  void headerLineWraps() throws Exception {
    // given: the Germans' line, longer than a narrow inspector is wide
    final NationsEditor editor = new NationsEditor(givenG40());
    final JComponent page = editor.page();
    page.setSize(320, 900);

    // when: laid out, and again once the line knows its width, as the editor revalidates it
    layOut(page);
    invalidateAll(page);
    layOut(page);

    // then
    final JLabel start =
        find(page, JLabel.class)
            .filter(l -> l.getText().contains("PUs at the start"))
            .findFirst()
            .orElseThrow();
    final Container line = start.getParent();
    final Container form = (Container) SwingUtilities.getAncestorOfClass(Form.class, line);
    assertThat(SwingUtilities.convertRectangle(line.getParent(), line.getBounds(), form).getMaxX())
        .as("the line within the inspector")
        .isLessThanOrEqualTo(form.getWidth());
    assertThat(line.getComponents())
        .as("each part within the line, wrapped where it does not fit")
        .allSatisfy(
            part ->
                assertThat(line.getBounds().contains(
                        SwingUtilities.convertRectangle(line, part.getBounds(), line.getParent())))
                    .as("%s seen whole", ((JLabel) part).getText())
                    .isTrue());
  }

  private static void layOut(final Component component) {
    component.doLayout();
    if (component instanceof Container container) {
      Stream.of(container.getComponents()).forEach(NationsEditorTest::layOut);
    }
  }

  private static void invalidateAll(final Component component) {
    component.invalidate();
    if (component instanceof Container container) {
      Stream.of(container.getComponents()).forEach(NationsEditorTest::invalidateAll);
    }
  }

  @DisplayName("A nation's placing and moving rules show in plain words and are written by a tick")
  @Test
  void showsAndWritesNationRules() throws Exception {
    // given China, which may move only into its own territories
    final MapProject project = givenG40();
    final NationsEditor editor = new NationsEditor(project);
    editor.click("Szechwan");
    final Field selected = NationsEditor.class.getDeclaredField("selected");
    selected.setAccessible(true);
    assertThat(selected.get(editor)).as("a click on its land chooses China").isEqualTo("Chinese");
    final JCheckBox captured =
        find(editor.page(), JCheckBox.class)
            .filter(box -> "May place in land it took this turn".equals(box.getText()))
            .findFirst()
            .orElseThrow();
    final JCheckBox capital =
        find(editor.page(), JCheckBox.class)
            .filter(box -> "May place only in its capital".equals(box.getText()))
            .findFirst()
            .orElseThrow();

    // when
    capital.doClick();

    // then
    assertThat(captured.isSelected()).as("placementCapturedTerritory of the map shows").isTrue();
    assertThat(find(editor.page(), JComboBox.class).map(JComboBox::getSelectedItem))
        .as("movementRestrictionType allowed in plain words")
        .contains("May move only into");
    assertThat(RulesXml.nationRule(project.game(), "Chinese", "placementInCapitalRestricted"))
        .as("the tick is written to its rulesAttachment")
        .contains("true");
    assertThat(NationsEditor.moreCount(project.game(), "Chinese"))
        .as("More options counts the rules of the rulesAttachment too")
        .isEqualTo(7);
  }
}
