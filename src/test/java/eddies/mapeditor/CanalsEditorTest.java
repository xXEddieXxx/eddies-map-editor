package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import eddies.mapeditor.CanalXml.Canal;

final class CanalsEditorTest {
  private static final String SUEZ = "Suez Canal";

  /** World War II Global 1940 with its canals, without its map's files. */
  private static MapProject givenGlobal() {
    return new MapProject(Path.of("g40.xml"), CanalXmlTest.givenGlobal(), MapFiles.none());
  }

  /** The editor with Suez open. */
  private static CanalsEditor givenSuezOpen(final MapProject project) {
    final CanalsEditor editor =
        new CanalsEditor(project, new MapView(project, () -> {}, () -> {}, () -> {}));
    all((Container) editor.page())
        .flatMap(c -> c instanceof JList<?> l ? Stream.of(l) : Stream.empty())
        .findFirst()
        .orElseThrow()
        .setSelectedValue(SUEZ, false);
    return editor;
  }

  private static Stream<Component> all(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }

  private static List<String> labels(final CanalsEditor editor) {
    return all((Container) editor.page())
        .flatMap(
            c -> c instanceof JLabel l && l.isVisible() ? Stream.of(l.getText()) : Stream.empty())
        .toList();
  }

  private static List<String> canals(final MapProject project) {
    return CanalXml.canals(project.game()).stream().map(Canal::name).toList();
  }

  @DisplayName("The inspector's link and fields start where the tool's title does")
  @Test
  void inspectorStartsAtTheEdge() throws Exception {
    final List<Integer> lefts = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final CanalsEditor editor = givenSuezOpen(givenGlobal());
          final Container page = (Container) editor.page();
          page.setSize(320, 800);
          layOut(page);
          final Component back =
              all(page)
                  .filter(c -> c instanceof AbstractButton b && "← All canals".equals(b.getText()))
                  .findFirst()
                  .orElseThrow();
          final Component name =
              all(page)
                  .filter(c -> c instanceof JLabel l && "Name".equals(l.getText()))
                  .findFirst()
                  .orElseThrow();
          lefts.add(xIn(page, back) + ((AbstractButton) back).getInsets().left);
          lefts.add(xIn(page, name));
        });

    assertThat(lefts).as("the back link's text and the Name label at the page's edge").containsOnly(0);
  }

  private static void layOut(final Component component) {
    component.doLayout();
    if (component instanceof Container container) {
      for (final Component inner : container.getComponents()) {
        layOut(inner);
      }
    }
  }

  private static int xIn(final Component page, final Component part) {
    return SwingUtilities.convertPoint(part.getParent(), part.getLocation(), page).x;
  }

  @DisplayName("The checklist shows how many sea zones the canal has of the two it needs")
  @Test
  void checklistCountsSeaZones() throws Exception {
    final MapProject project = givenGlobal();
    final List<List<String>> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final CanalsEditor editor = givenSuezOpen(project);
          editor.click("81 Sea Zone");
          seen.add(labels(editor));
          editor.click("81 Sea Zone");
          seen.add(labels(editor));
        });

    assertThat(seen.get(0))
        .as("one taken out")
        .contains("○ Two sea zones (1 of 2)")
        .doesNotContain("✓ Two sea zones");
    assertThat(seen.get(1)).as("back in").contains("✓ Two sea zones", "✓ At least one land");
  }

  @DisplayName("Enter on a taken or empty name keeps the canal and says why; a free name renames")
  @Test
  void renamesOnEnter() throws Exception {
    final MapProject project = givenGlobal();
    final String refused = "! Empty or another canal's name. Kept \"Suez Canal\".";
    final List<Boolean> kept = new ArrayList<>();
    final List<List<String>> names = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final CanalsEditor editor = givenSuezOpen(project);
          final JTextField name =
              all((Container) editor.page())
                  .flatMap(c -> c instanceof JTextField f ? Stream.of(f) : Stream.empty())
                  .filter(
                      f -> "Name of the canal".equals(f.getAccessibleContext().getAccessibleName()))
                  .findFirst()
                  .orElseThrow();
          for (final String typed : List.of("Panama Canal", " ")) {
            name.setText(typed);
            name.postActionEvent();
            kept.add(labels(editor).contains(refused));
            kept.add(canals(project).contains(SUEZ));
          }
          name.setText("Suez");
          name.postActionEvent();
          names.add(canals(project));
          project.undo();
          names.add(canals(project));
        });

    assertThat(kept).as("refused, kept").containsExactly(true, true, true, true);
    assertThat(names.get(0)).as("renamed").contains("Suez").doesNotContain(SUEZ);
    assertThat(names.get(1)).as("one undo takes it back").contains(SUEZ);
  }

  @DisplayName("A map without canals shows the empty state with its one primary Add canal")
  @Test
  void noCanalsShowsTheEmptyState() throws Exception {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    final List<String> primaries = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final CanalsEditor editor =
              new CanalsEditor(project, new MapView(project, () -> {}, () -> {}, () -> {}));
          all((Container) editor.page())
              .flatMap(c -> c instanceof AbstractButton b ? Stream.of(b) : Stream.empty())
              .filter(Theme::isPrimary)
              .forEach(b -> primaries.add(b.getText()));
        });

    assertThat(primaries).as("one main action").containsExactly("Add canal…");
  }
}
