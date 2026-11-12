package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.MapFiles.Redrawn;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapProject.RedrawChoices;

final class RedrawDialogTest {
  @TempDir Path folder;

  @DisplayName("The dialog first says what the new picture has and which borders were taken")
  @Test
  void summaryFirst() throws Exception {
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithNewBorder());

    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    assertThat(texts(content.root()).findFirst())
        .as("the summary sentence on top")
        .contains(
            "The new picture has 1 new area. 1 territory took its new border; its name, owner and"
                + " units stay.");
    assertThat(texts(content.root()))
        .as("the new areas under a heading with their count")
        .contains("New areas (1)");
    assertThat(buttons(content.root()).filter(b -> b.getText().equals("Add all")))
        .as("Add all is a small action, not the dialog's verb")
        .singleElement()
        .matches(b -> !Theme.isPrimary(b), "not primary");
  }

  @DisplayName("A ticked new area's name that is taken is shown at its field and refuses the verb")
  @Test
  void refusesTakenName() throws Exception {
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithNewBorder());
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);
    final JTextField name = only(content.root(), JTextField.class);

    name.setText("Territory 1");

    assertThat(content.problem().get()).as("the name is taken").contains("Territory 1");
    assertThat(errors(content.root()))
        .as("said at the field")
        .containsExactly("! Already used, used twice, or holds : < (.");
    content.addNone().run();
    assertThat(content.problem().get()).as("an area not added needs no free name").isEmpty();
    assertThat(errors(content.root())).as("nor shows its error").isEmpty();
    content.addAll().run();
    assertThat(content.problem().get()).as("ticked again, refused again").isPresent();
  }

  @DisplayName("The choices hold each ticked area with the name and kind typed")
  @Test
  void choicesAsTyped() throws Exception {
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithNewBorder());
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    only(content.root(), JTextField.class).setText("East");
    only(content.root(), JComboBox.class).setSelectedItem("Sea zone");
    final RedrawChoices choices = content.choices().get();

    assertThat(choices.added().values())
        .as("the area added as named, a sea zone")
        .singleElement()
        .matches(a -> a.name().equals("East") && a.water(), "East, water");
    assertThat(choices.added().keySet())
        .as("keyed by the area found")
        .containsExactly(redrawn.added().getFirst());
  }

  @DisplayName("A merged area names its territories and keeps the one chosen")
  @Test
  void mergedKeeper() throws Exception {
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithBorderAt(500));
    final DetectedTerritory area = redrawn.merged().keySet().iterator().next();
    final List<String> members = redrawn.merged().get(area);
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    only(content.root(), JComboBox.class).setSelectedItem(members.getLast());

    assertThat(texts(content.root()))
        .as("which territories are one now, and what happens to the other")
        .anyMatch(t -> t.contains(members.getFirst() + " and " + members.getLast() + " are one"))
        .anyMatch(t -> t.contains("The other is removed with its owner and units"))
        .anyMatch(t -> t.contains("its neighbours go to the one kept; canals through it are removed"))
        .noneMatch(t -> t.contains("canals go"))
        .contains("Merged areas (1)");
    assertThat(content.choices().get().keepers())
        .as("the territory chosen keeps the area")
        .containsEntry(area, members.getLast());
  }

  @DisplayName("The how-to before the picture is not shown after Don't show again")
  @Test
  void howToHidden() throws Exception {
    final Preferences preferences =
        Preferences.userRoot().node("eddies-map-editor-test/" + UUID.randomUUID());
    try {
      preferences.putBoolean("redrawHowToHidden", true);

      final boolean goOn = RedrawDialog.howTo(null, preferences);

      assertThat(goOn).as("straight on to the picture, no dialog").isTrue();
    } finally {
      preferences.removeNode();
    }
  }

  private MapProject givenMap() throws Exception {
    return MapProject.newMap(
            "Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder.resolve("maps"))
        .project();
  }

  private static Redrawn givenRedrawn(final MapProject project, final BufferedImage picture) {
    return project.getFiles().withBordersFrom(picture, MapPicture.detect(picture));
  }

  private static Stream<Component> all(final Component root) {
    return Stream.concat(
        Stream.of(root),
        root instanceof Container c
            ? Stream.of(c.getComponents()).flatMap(RedrawDialogTest::all)
            : Stream.empty());
  }

  private static <T extends Component> T only(final Component root, final Class<T> type) {
    final List<T> found = all(root).filter(type::isInstance).map(type::cast).toList();
    assertThat(found).as("one " + type.getSimpleName()).hasSize(1);
    return found.getFirst();
  }

  private static Stream<String> texts(final Component root) {
    return all(root)
        .filter(c -> c instanceof JLabel l && !(c instanceof FieldError) && l.getText() != null)
        .map(c -> ((JLabel) c).getText().replaceAll("<[^>]*>", ""))
        .filter(t -> !t.isEmpty());
  }

  private static List<String> errors(final Component root) {
    return all(root)
        .filter(c -> c instanceof FieldError e && e.hasProblem())
        .map(c -> ((FieldError) c).getText())
        .toList();
  }

  private static Stream<AbstractButton> buttons(final Component root) {
    return all(root).filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast);
  }
}
