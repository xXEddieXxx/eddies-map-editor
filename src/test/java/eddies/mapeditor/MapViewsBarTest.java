package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class MapViewsBarTest {

  private static List<JToggleButton> toggles(final MapViewsBar bar) {
    return Stream.of(bar.getComponents())
        .flatMap(c -> c instanceof JToggleButton t ? Stream.of(t) : Stream.<JToggleButton>empty())
        .toList();
  }

  private static List<String> selected(final MapViewsBar bar) {
    return toggles(bar).stream()
        .filter(JToggleButton::isSelected)
        .map(JToggleButton::getText)
        .toList();
  }

  @DisplayName("One toggle per view, with its name and what it shows as tool tip")
  @Test
  void oneTogglePerView() throws Exception {
    // given
    final List<JToggleButton> toggles = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> toggles.addAll(toggles(new MapViewsBar(MapMode.GAME, mode -> {}))));

    // then
    assertThat(toggles.stream().map(JToggleButton::getText))
        .as("short plain names, the views in their order")
        .containsExactly("Game", "Owners", "Values", "Units", "Neighbours");
    assertThat(toggles.stream().map(JToggleButton::getToolTipText))
        .as("each says what it shows, without HTML")
        .containsExactly(
            Stream.of(MapMode.values())
                .map(mode -> mode + ": " + mode.getDescription())
                .toArray(String[]::new));
    assertThat(toggles.stream().filter(JToggleButton::isSelected).map(JToggleButton::getText))
        .as("only the shown view selected")
        .containsExactly("Game");
  }

  @DisplayName("A click shows that view and reports it; showChosen only selects")
  @Test
  void clickReportsShowChosenDoesNot() throws Exception {
    // given
    final List<MapMode> reported = new ArrayList<>();
    final List<List<String>> selected = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapViewsBar bar = new MapViewsBar(MapMode.GAME, reported::add);
          toggles(bar).get(MapMode.VALUES.ordinal()).doClick();
          selected.add(selected(bar));
          bar.showChosen(MapMode.CONNECTIONS);
          selected.add(selected(bar));
        });

    // then
    assertThat(reported).as("only the click reports").containsExactly(MapMode.VALUES);
    assertThat(selected)
        .as("the clicked view, then the one a tool chose")
        .containsExactly(List.of("Values"), List.of("Neighbours"));
  }
}
