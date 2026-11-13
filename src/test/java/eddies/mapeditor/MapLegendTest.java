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
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PlayerList;

@ExtensionMethod(Builders.class)
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

  @DisplayName("Nations itself leaves out the link to Nations; the next tool shows it again")
  @Test
  void noLinkToNationsInNations() throws Exception {
    // given
    final List<Boolean> linkShown = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapLegend legend = givenLegend(givenMinimap(), 3000);
          legend.hideNationsLink();
          legend.doLayout();
          linkShown.add(linkShown(legend));
          legend.showNationsLink();
          legend.doLayout();
          linkShown.add(linkShown(legend));
          // also on a map without nations yet
          final MapLegend empty = givenLegend(MapProject.newEmpty(), 3000);
          empty.hideNationsLink();
          empty.doLayout();
          linkShown.add(linkShown(empty));
        });

    // then
    assertThat(linkShown)
        .as("hidden in Nations, then back; hidden also without nations")
        .containsExactly(false, true, false);
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
          project.edit(game -> MapXml.withOwner(game, "red north 1", "Nobody"));
          final MapLegend legend = givenLegend(project, 900);
          legend.onShowProblems(() -> clicked.add("problems"));
          shown.addAll(shownTexts(legend));
          button(legend, "■ 1 problem").doClick();
        });

    assertThat(shown).as("land of a nation the game has not").contains("■ 1 problem");
    assertThat(clicked).as("the list shows").containsExactly("problems");
  }

  @DisplayName("The game view links to Nations for the colors while every nation fits beside it")
  @Test
  void linksToNationsForColors() throws Exception {
    // given
    final List<Boolean> linkShown = new ArrayList<>();
    final List<Integer> keysWidth = new ArrayList<>();
    final List<List<String>> narrowTexts = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          linkShown.add(linkShown(givenLegend(givenG40(), 3000)));
          final MapLegend narrow = givenLegend(givenG40(), 600);
          linkShown.add(linkShown(narrow));
          narrowTexts.add(shownTexts(narrow));
          keysWidth.add(narrow.getComponent(0).getPreferredSize().width);
          narrow.explain(MapMode.VALUES);
          narrow.doLayout();
          linkShown.add(linkShown(narrow));
        });

    // then
    assertThat(linkShown)
        .as("shown with room for all nations; the nations first when narrow; none for values")
        .containsExactly(true, false, false);
    assertThat(narrowTexts.getFirst())
        .as("the nations that do not fit behind a button")
        .anyMatch(text -> text.startsWith("+"));
    assertThat(keysWidth.getFirst()).as("the line still fits").isLessThanOrEqualTo(600);
  }

  /** Whether "Nations ›" shows: it and its row visible. */
  private static boolean linkShown(final MapLegend legend) {
    return all(legend)
        .anyMatch(
            c ->
                c instanceof AbstractButton b
                    && "Nations ›".equals(b.getText())
                    && b.isVisible()
                    && b.getParent().isVisible());
  }

  @DisplayName("Political: a chip per alliance with its nations as tool tip, then no alliance")
  @Test
  void politicalListsAlliances() throws Exception {
    // given
    final List<String> texts = new ArrayList<>();
    final List<String> tips = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapLegend legend = givenLegend(givenG40(), 3000);
          legend.explain(MapMode.POLITICAL);
          legend.doLayout();
          texts.addAll(shownTexts(legend));
          all(legend)
              .filter(c -> c instanceof JLabel label && "Axis".equals(label.getText()))
              .findFirst()
              .ifPresent(c -> tips.add(((JLabel) c).getToolTipText()));
        });

    // then
    assertThat(texts)
        .as("the alliances in the game's order, then grey and sea")
        .containsSubsequence("Allies", "Axis", "Dutch", "No alliance", "Sea zone")
        .doesNotContain("Germans");
    assertThat(tips)
        .as("the alliance's nations on hover")
        .containsExactly("Germans, Japanese, Italians");
  }

  @DisplayName("Political: an alliance no nation has first gets no chip")
  @Test
  void politicalLeavesOutEmptyAlliance() throws Exception {
    // given
    final List<String> texts = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapProject project = givenG40();
          project.edit(MapLegendTest::withGermansAlsoNeutral);
          final MapLegend legend = givenLegend(project, 3000);
          legend.explain(MapMode.POLITICAL);
          legend.doLayout();
          texts.addAll(shownTexts(legend));
        });

    // then
    assertThat(texts)
        .as("the Germans' second alliance colors nothing")
        .contains("Axis")
        .doesNotContain("Neutral");
  }

  /** The game with the Germans also in "Neutral", after their Axis. */
  private static Game withGermansAlsoNeutral(final Game game) {
    final List<PlayerList.Alliance> alliances =
        new ArrayList<>(game.getPlayerList().getAlliances());
    alliances.add(PlayerList.Alliance.builder().player("Germans").alliance("Neutral").build());
    return game.toBuilder()
        .playerList(game.getPlayerList().toBuilder().alliances(alliances).build())
        .build();
  }
}
