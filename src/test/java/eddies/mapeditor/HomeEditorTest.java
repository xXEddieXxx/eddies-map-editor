package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.MapProject.NewMap;

final class HomeEditorTest {
  @TempDir Path maps;
  private final List<String> opened = new ArrayList<>();

  private HomeEditor givenHome(final MapProject project) {
    return new HomeEditor(project, t -> {}, opened::add, () -> {}, () -> {}, () -> {});
  }

  /** The minimap without a picture: its problem "No picture yet" is fixed in Territories. */
  private static MapProject givenMinimapWithoutPicture() {
    return new MapProject(Path.of("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
  }

  @DisplayName("Home has one main action, the next step's")
  @Test
  void hasOnePrimary() {
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());

    final List<AbstractButton> primaries =
        givenAll(home.page(), AbstractButton.class).filter(Theme::isPrimary).toList();

    assertThat(primaries).as("one button in the primary style").hasSize(1);
  }

  @DisplayName("A problem without a territory opens the tool it names")
  @Test
  void problemOpensItsTool() {
    final HomeEditor home = givenHome(givenMinimapWithoutPicture());
    final JButton open =
        givenAll(home.page(), JButton.class)
            .filter(b -> b.getText().equals("Open Territories"))
            .findFirst()
            .orElseThrow();

    open.doClick();

    assertThat(opened).as("the picture is loaded in Territories").containsExactly("Territories");
  }

  @DisplayName("After the wizard, Home tells which nations it left out")
  @Test
  void welcomeShowsRefusedNations() throws Exception {
    final NewMap made =
        MapProject.newMap(
            "Island Wars", MapPictureTest.givenPicture(), List.of("Red", "Territory 1"), maps);
    final HomeEditor home = givenHome(made.project());

    home.welcome(made);

    assertThat(givenAll(home.page(), JLabel.class).map(JLabel::getText))
        .as("the name of a territory is no nation's")
        .anyMatch(text -> text.contains("Left out: Territory 1."))
        .anyMatch(text -> text.contains("Found "));
  }

  private static <T> Stream<T> givenAll(final Component component, final Class<T> type) {
    final Stream<T> self =
        type.isInstance(component) ? Stream.of(type.cast(component)) : Stream.of();
    if (component instanceof Container container) {
      return Stream.concat(
          self, Stream.of(container.getComponents()).flatMap(child -> givenAll(child, type)));
    }
    return self;
  }
}
