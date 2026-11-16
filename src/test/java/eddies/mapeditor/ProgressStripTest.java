package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ProgressStripTest {
  @DisplayName("The strip shows its text moving, then how far the work is, and hides when it ends")
  @Test
  void showsHowFar() throws Exception {
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final ProgressStrip strip = new ProgressStrip();
          seen.add(strip.text());
          strip.start("Resizing the map tile by tile…");
          seen.add(strip.text());
          seen.add(strip.fraction());
          strip.setDone(3, 6);
          seen.add(strip.fraction());
          strip.end();
          seen.add(strip.text());
          seen.add(strip.isVisible());
        });

    assertThat(seen)
        .as("hidden; the text, moving; half way; hidden again")
        .containsExactly("", "Resizing the map tile by tile…", -1.0, 0.5, "", false);
  }

  @DisplayName("The map view shows the strip under its toolbar")
  @Test
  void mapViewShowsIt() throws Exception {
    final MapProject project = MapProject.newEmpty();
    final List<String> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapView view = new MapView(project, () -> {}, () -> {}, () -> {});
          view.showProgress("Loading…");
          seen.add(view.progressText());
          view.setProgress(1, 2);
          seen.add(view.progressText());
          view.endProgress();
          seen.add(view.progressText());
        });

    assertThat(seen).containsExactly("Loading…", "Loading…", "");
  }
}
