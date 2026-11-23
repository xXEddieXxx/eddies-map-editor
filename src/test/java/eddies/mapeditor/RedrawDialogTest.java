package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import eddies.mapeditor.MapProject.BorderLine;
import eddies.mapeditor.MapProject.RedrawChoices;

final class RedrawDialogTest {
  @TempDir Path folder;

  @DisplayName("The dialog first says what it takes and that Cancel changes nothing")
  @Test
  void summaryFirst() throws Exception {
    // given
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithNewBorder());

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(texts(content.root()).findFirst())
        .as("the summary sentence on top")
        .contains("Takes 1 change (1 new territory). Cancel changes nothing.");
    assertThat(texts(content.root()))
        .as("from a new picture every change starts taken")
        .noneMatch(t -> t.contains("Not touched by your lines"));
    assertThat(texts(content.root()))
        .as("the changes under a heading with their count, each saying what Take does")
        .contains("Changes (1)", "Territory 4 splits in two.");
    assertThat(buttons(content.root()).filter(b -> b.getText().equals("All")))
        .as("All is a small action, not the dialog's verb")
        .singleElement()
        .matches(b -> !Theme.isPrimary(b), "not primary");
  }

  @DisplayName("A ticked new area's name that is taken is shown at its field and refuses the verb")
  @Test
  void refusesTakenName() throws Exception {
    // given
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithNewBorder());
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);
    final JTextField name = only(content.root(), JTextField.class);

    // when
    name.setText("Territory 1");

    // then
    assertThat(content.problem().get()).as("the name is taken").contains("Territory 1");
    assertThat(errors(content.root()))
        .as("said at the field")
        .containsExactly("! Already used, used twice, or holds : < (.");
    content.takeNone().run();
    assertThat(content.problem().get()).as("a change not taken needs no free name").isEmpty();
    assertThat(errors(content.root())).as("nor shows its error").isEmpty();
    content.takeAll().run();
    assertThat(content.problem().get()).as("taken again, refused again").isPresent();
  }

  @DisplayName("The choices hold each ticked area with the name and kind typed")
  @Test
  void choicesAsTyped() throws Exception {
    // given
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithNewBorder());
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // when
    only(content.root(), JTextField.class).setText("East");
    only(content.root(), JComboBox.class).setSelectedItem("Sea zone");
    final RedrawChoices choices = content.choices().get();

    // then
    assertThat(choices.added().values())
        .as("the area added as named, a sea zone")
        .singleElement()
        .matches(a -> a.name().equals("East") && a.water(), "East, water");
    assertThat(choices.added().keySet())
        .as("keyed by the area found")
        .containsExactly(redrawn.added().getFirst());
    assertThat(choices.asItWas()).as("the change taken").isEmpty();
  }

  @DisplayName("From a new picture a merge starts taken, merged into the territory chosen")
  @Test
  void mergedKeeper() throws Exception {
    // given
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithBorderAt(500));
    final DetectedTerritory area = redrawn.merged().keySet().iterator().next();
    final List<String> members = redrawn.merged().get(area);
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);
    final boolean takenAtFirst = take(content).isSelected();

    // when
    only(content.root(), JComboBox.class).setSelectedItem(members.getLast());

    // then
    assertThat(takenAtFirst).as("an erased border is meant").isTrue();
    assertThat(texts(content.root()))
        .as("which territories become one, and what happens to the other")
        .anyMatch(
            t -> t.contains(members.getFirst() + " and " + members.getLast() + " become one"));
    assertThat(content.choices().get().keepers())
        .as("the territory chosen keeps the area")
        .containsEntry(area, members.getLast());
  }

  @DisplayName("A moved border is one change for both its sides; not taken, both stay")
  @Test
  void movedBorderNotTaken() throws Exception {
    // given
    final MapProject project = givenMap();
    final Redrawn redrawn = givenRedrawn(project, MapFilesTest.givenPictureWithBorderAt(299));
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);
    final boolean takenAtFirst = take(content).isSelected();

    // when
    take(content).setSelected(false);

    // then
    assertThat(takenAtFirst).as("taken at first").isTrue();
    assertThat(texts(content.root()))
        .as("what Take does")
        .contains("New borders for Territory 1 and Territory 2.");
    assertThat(content.choices().get().asItWas())
        .as("both sides as they were")
        .singleElement()
        .matches(g -> g.territories().containsAll(List.of("Territory 1", "Territory 2")), "both");
  }

  @DisplayName("After the eraser crossed a border, the merge it made starts taken")
  @Test
  void erasedBorderStartsTaken() throws Exception {
    // given: the border between the top two territories erased along its length
    final MapProject project = givenMap();
    project.eraseBorders(List.of(new Point(200, 0), new Point(200, 109)), 9);
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(redrawn.merged()).as("the two are one area").hasSize(1);
    assertThat(take(content).isSelected()).as("the map maker erased it").isTrue();
    assertThat(texts(content.root()))
        .as("the lines are kept with Cancel")
        .anyMatch(t -> t.contains("Cancel keeps your lines"));
  }

  @DisplayName("A pencil line ending on a border with a gap leaves the gap's merge off")
  @Test
  void pencilOnGappedBorderLeavesMergeOff() throws Exception {
    // given: a gap in the top border, and a pencil line from the top left area onto that border
    final MapProject project = givenMap();
    project.loadPicture(MapFilesTest.givenPictureWithGap());
    project.drawBorder(
        new BorderLine(List.of(new Point(150, 90), new Point(198, 90)), 3, Color.BLACK));
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(redrawn.merged()).as("the gap runs the two together").hasSize(1);
    assertThat(takeBeside(content, "become one").isSelected())
        .as("only the eraser means a merge")
        .isFalse();
  }

  @DisplayName("A pencil split ending at a border with a gap: the land through the gap starts off")
  @Test
  void leakThroughGapStartsOff() throws Exception {
    // given: a gap in the top border, and a pencil line across the top left territory below it
    final MapProject project = givenMap();
    project.loadPicture(MapFilesTest.givenPictureWithGap());
    project.drawBorder(
        new BorderLine(List.of(new Point(0, 70), new Point(198, 70)), 3, Color.BLACK));
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(redrawn.groups())
        .as("land runs from the top left territory through the gap")
        .anyMatch(group -> !group.moves().isEmpty() || !group.merged().isEmpty());
    assertThat(texts(content.root()))
        .as("said on its card")
        .anyMatch(t -> t.endsWith("through a gap in the line."));
    assertThat(takeBeside(content, "through a gap in the line.").isSelected())
        .as("no erased stroke lets it")
        .isFalse();
    assertThat(texts(content.root()).findFirst())
        .as("Cancel to close the gap")
        .hasValueSatisfying(
            t ->
                assertThat(t)
                    .as("the last sentence")
                    .endsWith(
                        "Cancel keeps your lines: close a gap in Draw borders and make"
                            + " territories again."));
  }

  @DisplayName("An erased merge beside a separate gap is two changes: the erased one taken")
  @Test
  void erasedMergeBesideGapIsTwoChanges() throws Exception {
    // given: a gap in the bottom border between land, then the top border erased
    final MapProject project = givenMap();
    final BufferedImage gapBelow = MapPictureTest.givenPicture();
    final Graphics2D g = gapBelow.createGraphics();
    g.setColor(new Color(240, 230, 150));
    g.fillRect(199, 150, 3, 20);
    g.dispose();
    project.loadPicture(gapBelow);
    project.eraseBorders(List.of(new Point(200, 5), new Point(200, 100)), 9);
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(redrawn.groups()).as("two merges, apart").hasSize(2);
    assertThat(takeBeside(content, "Territory 1 and Territory 2 become one").isSelected())
        .as("the erased one")
        .isTrue();
    assertThat(takeBeside(content, "Territory 3 and Territory 4 become one").isSelected())
        .as("the gap")
        .isFalse();
  }

  @DisplayName("A third territory joining an erased merge through a gap starts off, said so")
  @Test
  void thirdThroughGapStartsOff() throws Exception {
    // given: a gap in the line below the top left territory, then the top border erased
    final MapProject project = givenMap();
    final BufferedImage gapBelow = MapPictureTest.givenPicture();
    final Graphics2D g = gapBelow.createGraphics();
    g.setColor(new Color(230, 180, 180));
    g.fillRect(50, 109, 20, 3);
    g.dispose();
    project.loadPicture(gapBelow);
    project.eraseBorders(List.of(new Point(200, 5), new Point(200, 100)), 9);
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(redrawn.merged().values())
        .as("three run together")
        .singleElement()
        .matches(members -> members.size() == 3, "three");
    assertThat(texts(content.root()))
        .as("the one through the gap told")
        .contains("Territory 3 would join Territory 1 and Territory 2 through a gap in the line.");
    assertThat(take(content).isSelected()).as("only one border erased").isFalse();
  }

  @DisplayName("An erased merge taking part of a third through a gap starts off, said so")
  @Test
  void spillThroughGapStartsOff() throws Exception {
    // given: the top border erased; through a gap below the top left territory the merged area
    // takes the top of the bottom left one, cut off by a line across it
    final MapProject project = givenMap();
    project.loadPicture(givenPictureWithSpill());
    project.eraseBorders(List.of(new Point(200, 5), new Point(200, 100)), 9);
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(texts(content.root()))
        .as("the spill told with its names")
        .contains(
            "Territory 1 and Territory 2 would take part of Territory 3 through a gap in the"
                + " line.");
    assertThat(take(content).isSelected()).as("no erased stroke reaches the third").isFalse();
  }

  @DisplayName("An erased merge taking part of a third where the eraser reached it starts taken")
  @Test
  void spillErasedStartsTaken() throws Exception {
    // given: as above, the eraser also across the line between the top and bottom left ones
    final MapProject project = givenMap();
    project.loadPicture(givenPictureWithSpill());
    project.eraseBorders(List.of(new Point(200, 5), new Point(200, 100)), 9);
    project.eraseBorders(List.of(new Point(40, 110), new Point(80, 110)), 9);
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(take(content).isSelected()).as("the map maker erased both lines").isTrue();
    assertThat(texts(content.root()))
        .as("nothing runs through a gap")
        .noneMatch(t -> t.contains("through a gap in the line"));
  }

  /** A gap below the top left territory and a line across the bottom left one at y 140. */
  private static BufferedImage givenPictureWithSpill() {
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Graphics2D g = picture.createGraphics();
    g.setColor(new Color(230, 180, 180));
    g.fillRect(50, 109, 20, 3);
    g.setColor(Color.BLACK);
    g.fillRect(0, 140, 199, 3);
    g.dispose();
    return picture;
  }

  @DisplayName("After a whole border erased with a click, the merge it made starts taken")
  @Test
  void erasedWholeBorderStartsTaken() throws Exception {
    // given
    final MapProject project = givenMap();
    project.eraseBorder(project.getFiles().sharedBorderAt(new Point(200, 50), 3).orElseThrow());
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(redrawn.merged()).as("the two are one area").hasSize(1);
    assertThat(take(content).isSelected()).as("the map maker erased it").isTrue();
    assertThat(texts(content.root()).findFirst())
        .as("nothing to close: the lines just stay")
        .hasValueSatisfying(
            t -> assertThat(t).as("the last sentence").endsWith("Cancel keeps your lines."));
  }

  @DisplayName("An area outside the old map, eg a frame, is a change of its own and starts off")
  @Test
  void outsideAreaStartsOff() throws Exception {
    // given: the old map without the sea, so the sea area found lies outside it
    final MapProject project = givenMap();
    final MapFiles from = project.getFiles().withoutTerritory("Sea Zone 1");
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Redrawn redrawn = from.withBordersFrom(picture, MapPicture.detect(picture));

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, from, redrawn);

    // then
    assertThat(redrawn.groups())
        .as("a group of its own")
        .singleElement()
        .matches(group -> group.territories().isEmpty() && group.added().size() == 1, "alone");
    assertThat(take(content).isSelected()).as("not taken unless chosen").isFalse();
    assertThat(content.choices().get().added()).as("nothing added").isEmpty();
    assertThat(texts(content.root()))
        .as("said why")
        .contains("Outside the old map, eg a frame or a legend box.");
  }

  @DisplayName("A gap no line drawn crosses starts off; the split a pencil line made, taken")
  @Test
  void gapUntouchedSplitTaken() throws Exception {
    // given: a picture with a gap in the top border, then a line drawn across the sea
    final MapProject project = givenMap();
    project.loadPicture(MapFilesTest.givenPictureWithGap());
    project.drawBorder(seaLine());
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(redrawn.groups()).as("the merge and the split apart").hasSize(2);
    assertThat(takeBeside(content, "become one").isSelected()).as("the gap's merge").isFalse();
    assertThat(takeBeside(content, "Sea Zone 1").isSelected()).as("the split drawn").isTrue();
    assertThat(texts(content.root()))
        .as("why the merge is off")
        .contains("Not touched by your lines: off unless you tick it.");
  }

  @DisplayName("A border moved in the picture but crossed by no line drawn starts off")
  @Test
  void movedBorderUntouched() throws Exception {
    // given: a picture with the top border moved, then a line drawn across the sea
    final MapProject project = givenMap();
    project.loadPicture(MapFilesTest.givenPictureWithBorderAt(299));
    project.drawBorder(seaLine());
    final Redrawn redrawn = givenRedrawnInPlace(project);

    // when
    final RedrawDialog.Content content = RedrawDialog.content(project, project.getFiles(), redrawn);

    // then
    assertThat(takeBeside(content, "Territory 1 and Territory 2").isSelected())
        .as("the moved border")
        .isFalse();
  }

  @DisplayName("The news after the dialog: a toast counting it, a warning only with its own news")
  @Test
  void newsAfterDialog() throws Exception {
    // given: an erased border taken, and a gap kept as it was
    final MapProject project = givenMap();
    final MapFiles from = project.getFiles();
    final List<String> before = MapXml.territories(project.game());
    final Redrawn gap = givenRedrawn(project, MapFilesTest.givenPictureWithGap());
    final Redrawn erased = givenRedrawn(project, MapFilesTest.givenPictureWithBorderAt(500));
    project.takeRedrawn(from, erased, RedrawChoices.none());
    final List<String> after = MapXml.territories(project.game());

    // when
    final RedrawDialog.News taken =
        RedrawDialog.news(erased, RedrawChoices.none(), after, List.of(), List.of());
    final RedrawDialog.News kept =
        RedrawDialog.news(
            gap,
            new RedrawChoices(Map.of(), Map.of(), Set.copyOf(gap.groups())),
            before,
            List.of(),
            List.of());
    final RedrawDialog.News named =
        RedrawDialog.news(
            erased, RedrawChoices.none(), after, List.of("Territory 2: a trigger"), List.of());
    final String report = RedrawDialog.report(erased, after, List.of(), List.of());

    // then
    assertThat(taken.toast())
        .as("what was taken, counted")
        .isEqualTo("Territories made: 1 territory changed, 1 merged into another");
    assertThat(taken.warning()).as("nothing the dialog did not show").isEmpty();
    assertThat(kept.toast())
        .as("what was kept, counted")
        .isEqualTo("Borders as they were; 1 change kept as it was");
    assertThat(named.warning())
        .as("a territory still named is news of its own")
        .hasValueSatisfying(
            t ->
                assertThat(t)
                    .as("the warning's first line")
                    .startsWith("Kept their old border, still named"));
    assertThat(report).as("without the dialog the whole report").contains("Borders changed");
  }

  /** A line drawn across the sea, which splits it. */
  private static BorderLine seaLine() {
    return new BorderLine(List.of(new Point(100, 223), new Point(100, 299)), 3, Color.BLACK);
  }

  private static Redrawn givenRedrawnInPlace(final MapProject project) {
    final BufferedImage picture = project.getFiles().picture().orElseThrow();
    return project.getFiles().withBordersFrom(picture, MapPicture.detect(picture));
  }

  /** The Take tick of the card whose text holds this. */
  private static AbstractButton takeBeside(final RedrawDialog.Content content, final String text) {
    final Component label =
        all(content.root())
            .filter(c -> c instanceof JLabel l && l.getText() != null && l.getText().contains(text))
            .findFirst()
            .orElseThrow();
    return buttons(label.getParent())
        .filter(b -> b.getText().equals("Take"))
        .findFirst()
        .orElseThrow();
  }

  private static AbstractButton take(final RedrawDialog.Content content) {
    return buttons(content.root())
        .filter(b -> b.getText().equals("Take"))
        .findFirst()
        .orElseThrow();
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
