package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MapGuide.Step;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GoalStripTest {
  @TempDir Path maps;

  /** A new map with territories and the nation Red, which owns no land yet. */
  private MapProject givenNewMapWithRed() throws Exception {
    return MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), maps)
        .project();
  }

  private static String givenLand(final MapProject project) {
    return MapXml.territories(project.game()).stream()
        .filter(t -> !MapXml.isWater(project.game(), t))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("A step shows its place and text; Next shows once an edit has done it")
  @Test
  void nextShowsOnceTheStepIsDone() throws Exception {
    // given
    final MapProject project = givenNewMapWithRed();
    final List<Object> seen = new ArrayList<>();
    final List<Step.Id> followed = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final GoalStrip strip = new GoalStrip(project);
          strip.onFollowStep(followed::add);
          strip.showStep(Step.Id.LAND);
          seen.add(strip.text());
          seen.add(strip.next().isVisible());
          project.edit(g -> MapXml.withOwner(g, givenLand(project), "Red"));
          seen.add(strip.next().isVisible());
          seen.add(strip.next().getText());
          strip.next().doClick();
        });

    // then
    assertThat(seen)
        .as("the step, no Next while Red has no land, then Next to the capitals")
        .containsExactly(
            "Step 3 of 7 · Give every nation its land",
            false,
            true,
            "Next: Mark each nation's capital ›");
    assertThat(followed).as("Next follows the capitals step").containsExactly(Step.Id.CAPITALS);
  }

  @DisplayName("End hides the goal and forgets its step")
  @Test
  void endClearsTheGoal() throws Exception {
    // given
    final MapProject project = givenNewMapWithRed();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final GoalStrip strip = new GoalStrip(project);
          strip.showStep(Step.Id.LAND);
          strip.end();
          seen.add(strip.isVisible());
          seen.add(strip.step().isPresent());
          seen.add(strip.next().isVisible());
        });

    // then
    assertThat(seen).as("hidden, no step, no Next").containsExactly(false, false, false);
  }

  @DisplayName("A goal of a tool's own shows its title and runs its action")
  @Test
  void goalRunsItsAction() throws Exception {
    // given
    final MapProject project = givenNewMapWithRed();
    final List<String> ran = new ArrayList<>();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final GoalStrip strip = new GoalStrip(project);
          strip.showGoal(
              "Terrain effect Forest",
              "3 territories",
              "Back to Terrain effects",
              () -> ran.add("back"));
          seen.add(strip.text());
          seen.add(strip.next().getText());
          strip.next().doClick();
        });

    // then
    assertThat(seen)
        .as("the goal's title and action")
        .containsExactly("Terrain effect Forest", "Back to Terrain effects");
    assertThat(ran).as("the action runs").containsExactly("back");
  }

  @DisplayName("The step turning done is news once: a toast, not again on the next edit")
  @Test
  void toastOnceWhenDone() throws Exception {
    // given: the strip in a window, at the land step
    final MapProject project = givenNewMapWithRed();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final JRootPane window = new JRootPane();
          window.setSize(1100, 700);
          final GoalStrip strip = new GoalStrip(project);
          window.getContentPane().add(strip);
          window.doLayout();
          strip.showStep(Step.Id.LAND);
          final JLayeredPane layers = window.getLayeredPane();
          seen.add(Toast.shown(layers).isPresent());
          project.edit(g -> MapXml.withOwner(g, givenLand(project), "Red"));
          final Toast toast = Toast.shown(layers).orElseThrow();
          seen.add(toast.getAccessibleContext().getAccessibleDescription());
          project.edit(g -> MapXml.withProduction(g, givenLand(project), 3));
          seen.add(Toast.shown(layers).filter(shown -> shown != toast).isPresent());
        });

    // then
    assertThat(seen)
        .as("no toast before, one when Red has land, no new one on the next edit")
        .containsExactly(false, "✓ Give every nation its land", false);
  }
}
