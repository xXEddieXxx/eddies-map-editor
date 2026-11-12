package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MakerIcon.Glyph;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSplitPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class PanelsTest {

  /** The first list beside its details in the page. */
  private static JSplitPane givenSplit(final Component page) {
    return givenSplits(page).getFirst();
  }

  private static List<JSplitPane> givenSplits(final Component in) {
    if (in instanceof JSplitPane split) {
      return List.of(split);
    }
    return in instanceof Container container
        ? Stream.of(container.getComponents()).flatMap(c -> givenSplits(c).stream()).toList()
        : List.of();
  }

  @DisplayName("Production and Technology put their list where Units does, as wide")
  @Test
  void listsLineUp() {
    final MapProject project =
        new MapProject(Path.of("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
    final JSplitPane units = givenSplit(new UnitsEditor(project).page());

    for (final Editor editor :
        List.of(
            new ProductionEditor(project), new TechnologyEditor(project))) {
      final JSplitPane split = givenSplit(editor.page());
      assertThat(split.getDividerLocation())
          .as("%s: the list as wide as in Units", editor.title())
          .isEqualTo(units.getDividerLocation());
      assertThat(((JComponent) split.getLeftComponent()).getInsets().left)
          .as("%s: the list starts at the page's edge", editor.title())
          .isZero();
    }
  }

  @DisplayName("An empty state with nothing there yet has its tool's one main action")
  @Test
  void emptyWithVerbHasOnePrimary() {
    final Component empty =
        Panels.empty(
            Glyph.SOUNDS,
            "No sounds of your own",
            "The game plays its own.",
            "Add sound file…",
            () -> {});

    final List<JButton> buttons = givenButtons(empty);

    assertThat(buttons)
        .as("one button, the main action")
        .singleElement()
        .extracting(JButton::getText)
        .isEqualTo("Add sound file…");
    assertThat(Theme.isPrimary(buttons.getFirst())).as("in the primary style").isTrue();
  }

  @DisplayName("An empty state for nothing chosen has no button: the list is the way on")
  @Test
  void emptyWithoutVerbHasNoButton() {
    final Component empty =
        Panels.empty(Glyph.SOUNDS, "Choose a sound", "Hear it, or give it your own mp3 files.");

    assertThat(givenButtons(empty)).as("nothing to click").isEmpty();
  }

  @DisplayName("An empty state's glyph shows, as a disabled label would paint nothing")
  @Test
  void emptyGlyphPainted() {
    final JLabel glyph =
        givenGlyph(Panels.empty(Glyph.SOUNDS, "Choose a sound", "Hear it, or give it mp3s."));

    final BufferedImage painted = givenPainted(glyph);

    assertThat(glyph.getIcon()).as("the label has the glyph").isNotNull();
    assertThat(countOpaquePixels(painted)).as("the glyph is drawn").isPositive();
  }

  private static JLabel givenGlyph(final Component component) {
    return descendants(component)
        .filter(JLabel.class::isInstance)
        .map(JLabel.class::cast)
        .filter(label -> label.getIcon() != null)
        .findFirst()
        .orElseThrow();
  }

  private static Stream<Component> descendants(final Component component) {
    return component instanceof Container container
        ? Stream.concat(
            Stream.of(component),
            Stream.of(container.getComponents()).flatMap(PanelsTest::descendants))
        : Stream.of(component);
  }

  private static BufferedImage givenPainted(final JLabel label) {
    label.setSize(label.getPreferredSize());
    final BufferedImage image =
        new BufferedImage(label.getWidth(), label.getHeight(), BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    label.paint(g);
    g.dispose();
    return image;
  }

  private static long countOpaquePixels(final BufferedImage image) {
    long count = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) >>> 24) != 0) {
          count++;
        }
      }
    }
    return count;
  }

  private static List<JButton> givenButtons(final Component component) {
    final List<JButton> buttons = new ArrayList<>();
    if (component instanceof JButton button) {
      buttons.add(button);
    }
    if (component instanceof Container container) {
      for (final Component child : container.getComponents()) {
        buttons.addAll(givenButtons(child));
      }
    }
    return buttons;
  }
}
