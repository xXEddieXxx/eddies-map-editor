package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JMenuItem;
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
        .containsExactly("Game", "Owners", "Political", "Values", "Units", "Neighbours");
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

  @DisplayName("Short of room the views are one button, \"View: Game ▾\"; its menu chooses a view")
  @Test
  void dropdownNamesTheViewAndChooses() throws Exception {
    // given
    final List<MapMode> reported = new ArrayList<>();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapViewsBar bar = new MapViewsBar(MapMode.GAME, reported::add);
          bar.show(MapViewsBar.Shape.DROPDOWN);
          seen.add(shownTexts(bar));
          final List<JMenuItem> entries =
              Stream.of(bar.viewsMenu().getComponents()).map(JMenuItem.class::cast).toList();
          seen.add(
              entries.stream().map(e -> e.getAccessibleContext().getAccessibleName()).toList());
          seen.add(entries.stream().allMatch(e -> e.getIcon() != null));
          seen.add(entries.stream().filter(JMenuItem::isSelected).count());
          entries.get(MapMode.VALUES.ordinal()).doClick();
          seen.add(shownTexts(bar));
          seen.add(selected(bar));
          bar.show(MapViewsBar.Shape.TOGGLES);
          seen.add(shownTexts(bar));
        });

    // then
    assertThat(seen)
        .as("one button naming the view; an entry per view with icon, name and what it shows, the"
            + " shown one marked; the choice shows on the button and, with room, in the toggles")
        .containsExactly(
            List.of("View: Game ▾"),
            Stream.of(MapMode.values()).map(mode -> mode + ": " + mode.getDescription()).toList(),
            true,
            1L,
            List.of("View: Values ▾"),
            List.of("Values"),
            List.of("Game", "Owners", "Political", "Values", "Units", "Neighbours"));
    assertThat(reported).as("the menu's choice reports").containsExactly(MapMode.VALUES);
  }

  private static List<String> shownTexts(final MapViewsBar bar) {
    return Stream.of(bar.getComponents())
        .filter(Component::isVisible)
        .map(c -> ((AbstractButton) c).getText())
        .toList();
  }

  @DisplayName("A chosen view's glyph shows in the chosen text color")
  @Test
  void chosenGlyphInChosenColor() throws Exception {
    // given
    final List<Long> chosenPixels = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapViewsBar bar = new MapViewsBar(MapMode.UNITS, mode -> {});
          for (final MapMode mode : List.of(MapMode.UNITS, MapMode.CONNECTIONS)) {
            final JToggleButton toggle = toggles(bar).get(mode.ordinal());
            final BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            toggle.getIcon().paintIcon(toggle, image.getGraphics(), 0, 0);
            final int chosen = Theme.getMapToolSelectedTextColor().getRGB();
            long count = 0;
            for (int x = 0; x < 20; x++) {
              for (int y = 0; y < 20; y++) {
                count += image.getRGB(x, y) == chosen ? 1 : 0;
              }
            }
            chosenPixels.add(count);
          }
        });

    // then
    assertThat(chosenPixels.get(0)).as("the chosen Units in the chosen color").isPositive();
    assertThat(chosenPixels.get(1)).as("Neighbours, not chosen, in the text's color").isZero();
  }
}
