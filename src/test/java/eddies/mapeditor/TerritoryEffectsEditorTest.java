package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.Game;

final class TerritoryEffectsEditorTest {
  private static final String FOREST = "forest";

  /** The minimap with a forest on two territories: armour -1 in attack, a move more, def +1. */
  private static MapProject givenForest() {
    Game game = TerritoryEffectsXml.withEffect(MapXmlTest.givenMinimap(), FOREST);
    game = TerritoryXml.withEffect(game, "red north 1", FOREST);
    game = TerritoryXml.withEffect(game, "red north 2", FOREST);
    game =
        TerritoryEffectsXml.withUnitValues(
            game, FOREST, TerritoryEffectsXml.OFFENSE, Map.of("armour", "-1"));
    game =
        TerritoryEffectsXml.withUnitValues(
            game, FOREST, TerritoryEffectsXml.DEFENSE, Map.of("armour", "1"));
    game =
        TerritoryEffectsXml.withUnitValues(
            game, FOREST, TerritoryEffectsXml.MOVEMENT_COST, Map.of("armour", "1"));
    return new MapProject(Path.of("minimap.xml"), game, MapFiles.none());
  }

  /** The components the page shows: those in a hidden card or section left out. */
  private static Stream<Component> visible(final Container in) {
    return Stream.of(in.getComponents())
        .filter(Component::isVisible)
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), visible(inner))
                    : Stream.of(c));
  }

  private static <T> Stream<T> visible(final TerritoryEffectsEditor editor, final Class<T> type) {
    return visible((Container) editor.page()).filter(type::isInstance).map(type::cast);
  }

  private static JSpinner spinner(final TerritoryEffectsEditor editor, final String name) {
    return visible(editor, JSpinner.class)
        .filter(s -> name.equals(s.getAccessibleContext().getAccessibleName()))
        .findFirst()
        .orElseThrow();
  }

  private static String shown(final JSpinner spinner) {
    return ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().getText();
  }

  private static List<String> labels(final TerritoryEffectsEditor editor) {
    return visible(editor, JLabel.class).map(JLabel::getText).toList();
  }

  @DisplayName("× clears attack, defence and moves to enter of a unit type in one undo step")
  @Test
  void clearsAUnitInOneStep() throws Exception {
    final MapProject project = givenForest();
    final List<Map<String, String>> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoryEffectsEditor editor = new TerritoryEffectsEditor(project);
          visible(editor, AbstractButton.class)
              .filter(b -> "Clear armour".equals(b.getToolTipText()))
              .findFirst()
              .orElseThrow()
              .doClick();
          seen.add(TerritoryEffectsXml.unitValues(project.game(), FOREST, "combatOffenseEffect"));
          seen.add(TerritoryEffectsXml.unitValues(project.game(), FOREST, "combatDefenseEffect"));
          seen.add(
              TerritoryEffectsXml.unitValues(project.game(), FOREST, "movementCostModifier"));
          project.undo();
          seen.add(TerritoryEffectsXml.unitValues(project.game(), FOREST, "combatOffenseEffect"));
          seen.add(
              TerritoryEffectsXml.unitValues(project.game(), FOREST, "movementCostModifier"));
        });

    assertThat(seen.subList(0, 3)).as("all three cleared").allMatch(Map::isEmpty);
    assertThat(seen.get(3)).as("one undo brings attack back").containsEntry("armour", "-1");
    assertThat(seen.get(4)).as("and the move").containsEntry("armour", "1");
  }

  @DisplayName("Spinners show the sign, write plain decimals and take a number typed without sign")
  @Test
  void spinnersShowTheSign() throws Exception {
    final MapProject project = givenForest();
    final List<String> shown = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoryEffectsEditor editor = new TerritoryEffectsEditor(project);
          final JSpinner move = spinner(editor, "Moves to enter of armour");
          final JSpinner attack = spinner(editor, "Attack of armour");
          shown.add(shown(attack));
          move.setValue(0.5);
          shown.add(shown(move));
          shown.add(
              TerritoryEffectsXml.unitValues(project.game(), FOREST, "movementCostModifier")
                  .get("armour"));
          final JFormattedTextField typed = ((JSpinner.DefaultEditor) attack.getEditor()).getTextField();
          typed.setText("2");
          try {
            typed.commitEdit();
          } catch (final ParseException e) {
            throw new IllegalStateException(e);
          }
          shown.add(shown(attack));
          shown.add(
              TerritoryEffectsXml.unitValues(project.game(), FOREST, "combatOffenseEffect")
                  .get("armour"));
        });

    assertThat(shown)
        .as("signed in English digits, written as the game reads them")
        .containsExactly("-1", "+0.5", "0.5", "+2", "2");
  }

  @DisplayName("A game without effects shows only the empty state with its one primary action")
  @Test
  void noEffectsShowsOnlyTheEmptyState() throws Exception {
    final MapProject project =
        new MapProject(Path.of("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
    final List<String> primaries = new ArrayList<>();
    final List<Long> lists = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoryEffectsEditor editor = new TerritoryEffectsEditor(project);
          visible(editor, AbstractButton.class)
              .filter(Theme::isPrimary)
              .forEach(b -> primaries.add(b.getText()));
          lists.add(visible(editor, JList.class).count());
        });

    assertThat(primaries).as("one main action").containsExactly("Add effect…");
    assertThat(lists).as("no list beside it").containsExactly(0L);
  }

  @DisplayName("The details tell which territories use the effect")
  @Test
  void showsWhereUsed() throws Exception {
    final MapProject project = givenForest();
    final List<String> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> seen.addAll(labels(new TerritoryEffectsEditor(project))));

    assertThat(seen)
        .as("count and names")
        .contains("Used by 2 territories: red north 1, red north 2");
    assertThat(TerritoryEffectsEditor.usedBy(List.of("a", "b", "c", "d")))
        .as("the first three, then …")
        .isEqualTo("Used by 4 territories: a, b, c, …");
  }

  @DisplayName("An effect no territory has yet still offers Show on map, to paint it there")
  @Test
  void showOnMapWithoutTerritories() throws Exception {
    final MapProject project =
        new MapProject(
            Path.of("minimap.xml"),
            TerritoryEffectsXml.withEffect(MapXmlTest.givenMinimap(), FOREST),
            MapFiles.none());
    final List<String> shown = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () ->
            visible(new TerritoryEffectsEditor(project, shown::add), AbstractButton.class)
                .filter(b -> "Show on map".equals(b.getText()))
                .findFirst()
                .orElseThrow()
                .doClick());

    assertThat(shown).as("the map opens to paint the effect").containsExactly(FOREST);
  }

  @DisplayName("Enter on a taken name keeps the effect and the typed text; a free name renames")
  @Test
  void renamesOnEnter() throws Exception {
    final MapProject project = givenForest();
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final TerritoryEffectsEditor editor = new TerritoryEffectsEditor(project);
          final JTextField name =
              visible(editor, JTextField.class)
                  .filter(
                      f ->
                          "Name of the effect"
                              .equals(f.getAccessibleContext().getAccessibleName()))
                  .findFirst()
                  .orElseThrow();
          name.setText("red north 1");
          name.postActionEvent();
          seen.add(TerritoryEffectsXml.effects(project.game()));
          seen.add(name.getText());
          seen.add(
              labels(editor)
                  .contains(
                      "! Used by another effect, territory, nation or unit, or holds : < ("));
          name.setText("woods");
          name.postActionEvent();
          seen.add(TerritoryEffectsXml.effects(project.game()));
          project.undo();
          seen.add(TerritoryEffectsXml.effects(project.game()));
        });

    assertThat(seen)
        .as("refused and kept typed, then renamed, one undo back")
        .containsExactly(
            List.of(FOREST), "red north 1", true, List.of("woods"), List.of(FOREST));
  }
}
