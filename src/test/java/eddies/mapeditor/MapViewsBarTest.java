package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
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

  @DisplayName("Compact, the chosen view keeps its name and the others only their icons")
  @Test
  void compactKeepsTheChosenName() throws Exception {
    // given
    final List<List<String>> texts = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapViewsBar bar = new MapViewsBar(MapMode.GAME, mode -> {});
          bar.show(MapViewsBar.Names.CHOSEN);
          texts.add(toggles(bar).stream().map(JToggleButton::getText).toList());
          toggles(bar).get(MapMode.VALUES.ordinal()).doClick();
          texts.add(toggles(bar).stream().map(JToggleButton::getText).toList());
          bar.show(MapViewsBar.Names.ALL);
          texts.add(toggles(bar).stream().map(JToggleButton::getText).toList());
        });

    // then
    assertThat(texts.get(0))
        .as("compact: only the chosen view by name")
        .containsExactly("Game", "", "", "", "", "");
    assertThat(texts.get(1))
        .as("the name follows the choice")
        .containsExactly("", "", "", "Values", "", "");
    assertThat(texts.get(2))
        .as("with room every name again")
        .containsExactly("Game", "Owners", "Political", "Values", "Units", "Neighbours");
  }

  @DisplayName("A chosen view's glyph shows in the chosen text color, also as an icon alone")
  @Test
  void chosenGlyphInChosenColor() throws Exception {
    // given
    final List<Long> chosenPixels = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapViewsBar bar = new MapViewsBar(MapMode.UNITS, mode -> {});
          bar.show(MapViewsBar.Names.NONE);
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
