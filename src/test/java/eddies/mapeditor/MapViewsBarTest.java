package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class MapViewsBarTest {

  @DisplayName("The views are one button, \"View: Game ▾\", saying what the view shows")
  @Test
  void oneButtonNamesTheView() throws Exception {
    // given
    final List<AbstractButton> buttons = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> buttons.addAll(shownButtons(new MapViewsBar(MapMode.GAME, mode -> {}))));

    // then
    assertThat(buttons.stream().map(AbstractButton::getText))
        .as("one button, no toggle per view")
        .containsExactly("View: Game ▾");
    assertThat(buttons.getFirst().getToolTipText())
        .as("what the view shows and that there are others")
        .isEqualTo(
            "View: Game. " + MapMode.GAME.getDescription() + ". Click for the other views");
    assertThat(buttons.getFirst().getAccessibleContext().getAccessibleName())
        .as("named without the arrow")
        .isEqualTo("View: Game");
  }

  @DisplayName("The menu lists Game, Owners, Political and Values, not the Connections tool's view")
  @Test
  void menuListsTheViewsToChoose() throws Exception {
    // given
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final List<JMenuItem> entries = entries(new MapViewsBar(MapMode.OWNERS, mode -> {}));
          seen.add(
              entries.stream().map(e -> e.getAccessibleContext().getAccessibleName()).toList());
          seen.add(entries.stream().allMatch(e -> e.getIcon() != null));
          seen.add(entries.stream().filter(JMenuItem::isSelected).map(JMenuItem::getText).count());
          seen.add(entries.get(1).isSelected());
        });

    // then
    assertThat(seen)
        .as("an entry per view to choose, with icon, name and what it shows, the shown one marked")
        .containsExactly(
            Stream.of(MapMode.GAME, MapMode.OWNERS, MapMode.POLITICAL, MapMode.VALUES)
                .map(mode -> mode + ": " + mode.getDescription())
                .toList(),
            true,
            1L,
            true);
  }

  @DisplayName("The menu's choice shows on the button and reports; showChosen only shows")
  @Test
  void choiceReportsShowChosenDoesNot() throws Exception {
    // given
    final List<MapMode> reported = new ArrayList<>();
    final List<String> shown = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapViewsBar bar = new MapViewsBar(MapMode.GAME, reported::add);
          entries(bar).get(MapMode.CHOICES.indexOf(MapMode.VALUES)).doClick();
          shown.add(shownButtons(bar).getFirst().getText());
          bar.showChosen(MapMode.OWNERS);
          shown.add(shownButtons(bar).getFirst().getText());
        });

    // then
    assertThat(reported).as("only the map maker's choice reports").containsExactly(MapMode.VALUES);
    assertThat(shown)
        .as("the chosen view, then the one a tool chose")
        .containsExactly("View: Values ▾", "View: Owners ▾");
  }

  private static List<JMenuItem> entries(final MapViewsBar bar) {
    return Stream.of(bar.viewsMenu().getComponents()).map(JMenuItem.class::cast).toList();
  }

  private static List<AbstractButton> shownButtons(final MapViewsBar bar) {
    return Stream.of(bar.getComponents())
        .filter(Component::isVisible)
        .map(AbstractButton.class::cast)
        .toList();
  }
}
