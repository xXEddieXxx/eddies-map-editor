package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class MapLegendTest {

  private static MapProject givenG40() {
    return new MapProject(
        Path.of("ww2global40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
  }

  private static MapProject givenMinimap() {
    return new MapProject(Path.of("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
  }

  /** The legend laid out at this width, as below a map. */
  private static MapLegend givenLegend(final MapProject project, final int width) {
    final MapLegend legend = new MapLegend(project);
    legend.setSize(width, 30);
    legend.doLayout();
    legend.doLayout();
    return legend;
  }

  private static Stream<Component> all(final Component component) {
    return component instanceof Container container
        ? Stream.concat(
            Stream.of(component), Stream.of(container.getComponents()).flatMap(MapLegendTest::all))
        : Stream.of(component);
  }

  private static List<String> shownTexts(final MapLegend legend) {
    return all(legend)
        .filter(Component::isVisible)
        .flatMap(
            c ->
                c instanceof JLabel label
                    ? Stream.of(label.getText())
                    : c instanceof AbstractButton button
                        ? Stream.of(button.getText())
                        : Stream.empty())
        .toList();
  }

  private static AbstractButton button(final MapLegend legend, final String text) {
    return all(legend)
        .flatMap(
            c ->
                c instanceof AbstractButton b && text.equals(b.getText())
                    ? Stream.of(b)
                    : Stream.empty())
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("Nations that do not fit the width are counted in a button for all of them")
  @Test
  void moreNationsThanFit() throws Exception {
    final List<Long> hidden = new ArrayList<>();
    final List<String> texts = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapLegend legend = givenLegend(givenG40(), 500);
          final List<String> nations = MapXml.nations(GameRulesXmlTest.givenG40());
          hidden.add(
              all(legend)
                  .filter(c -> c instanceof JLabel label && nations.contains(label.getText()))
                  .filter(c -> !c.isVisible())
                  .count());
          texts.addAll(shownTexts(legend));
        });

    assertThat(hidden.getFirst()).as("G40's nations do not all fit in 500 px").isPositive();
    assertThat(texts)
        .as("the button counts the hidden ones")
        .contains("+" + hidden.getFirst() + " nations", "No owner");
  }

  @DisplayName("Territories changed since the last save are counted")
  @Test
  void countsChanged() throws Exception {
    final List<List<String>> texts = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapProject project = givenMinimap();
          final MapLegend legend = givenLegend(project, 900);
          texts.add(shownTexts(legend));
          project.edit(game -> MapXml.withOwner(game, "red north 1", null));
          project.edit(game -> MapXml.withOwner(game, "red capitol", null));
          texts.add(shownTexts(legend));
        });

    assertThat(texts.get(0)).as("nothing changed yet").noneMatch(text -> text.startsWith("●"));
    assertThat(texts.get(1)).as("two owners taken").contains("● 2 changed");
  }

  @DisplayName("One problem outlined red reads singular, and a click shows the problem list")
  @Test
  void problemShowsList() throws Exception {
    final List<String> shown = new ArrayList<>();
    final List<String> clicked = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapProject project = givenMinimap();
          project.edit(game -> MapXml.withTerritory(game, "island", List.of()));
          final MapLegend legend = givenLegend(project, 900);
          legend.onShowProblems(() -> clicked.add("problems"));
          shown.addAll(shownTexts(legend));
          button(legend, "■ 1 problem").doClick();
        });

    assertThat(shown).as("a territory without neighbours").contains("■ 1 problem");
    assertThat(clicked).as("the list shows").containsExactly("problems");
  }
}
