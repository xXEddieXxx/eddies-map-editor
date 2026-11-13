package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** "Show all options": every fold row starts open, and can still be closed. */
final class FoldsTest {
  private final Preferences preferences =
      Preferences.userRoot().node("eddies-map-editor-test-" + UUID.randomUUID());

  @BeforeEach
  void givenOwnPreferences() {
    Folds.keepIn(preferences);
  }

  @AfterEach
  void removePreferences() throws BackingStoreException {
    preferences.removeNode();
    Folds.keepIn(Preferences.userNodeForPackage(Folds.class));
  }

  private static Stream<Component> all(final Component in) {
    return in instanceof Container container
        ? Stream.concat(Stream.of(in), Stream.of(container.getComponents()).flatMap(FoldsTest::all))
        : Stream.of(in);
  }

  private static boolean shows(final Component c, final Component in) {
    return c == in || c == null || (c.isVisible() && shows(c.getParent(), in));
  }

  private static List<String> folds(final Component in) {
    return all(in)
        .filter(AbstractButton.class::isInstance)
        .map(c -> ((AbstractButton) c).getText())
        .filter(text -> text != null && (text.startsWith("▸ ") || text.startsWith("▾ ")))
        .toList();
  }

  private static Form givenFormWithMore(final String id) {
    final Form form = new Form(300);
    form.more(id, 1, more -> more.field("Rare", new JTextField()));
    form.end();
    return form;
  }

  @DisplayName("A fold row starts closed, and open with Show all options; a click still closes it")
  @Test
  void foldRowsFollowTheSwitch() {
    // given
    final String id = "test." + UUID.randomUUID();
    final List<String> seen = new ArrayList<>();

    // when
    seen.addAll(folds(givenFormWithMore(id)));
    Folds.toggleShowAll();
    final Form open = givenFormWithMore(id);
    seen.addAll(folds(open));
    all(open)
        .filter(c -> c instanceof AbstractButton b && b.getText().startsWith("▾ "))
        .map(AbstractButton.class::cast)
        .findFirst()
        .orElseThrow()
        .doClick();
    // a form built anew, as the tools do with every pick
    seen.addAll(folds(givenFormWithMore(id)));
    seen.addAll(folds(givenFormWithMore(id + ".other")));
    Folds.toggleShowAll();
    seen.addAll(folds(givenFormWithMore(id + ".other")));

    // then
    assertThat(seen)
        .as("closed; open with the switch; closed by the click, another row still open; closed"
            + " again without the switch")
        .containsExactly(
            "▸ More options (1)",
            "▾ More options (1)",
            "▸ More options (1)",
            "▾ More options (1)",
            "▸ More options (1)");
  }

  @DisplayName("The switch is kept in the preferences, for the next time")
  @Test
  void switchIsKept() {
    // given
    final boolean before = Folds.showsAll();

    // when
    Folds.toggleShowAll();

    // then
    assertThat(before).as("off for a new map maker").isFalse();
    assertThat(preferences.getBoolean("showAllOptions", false)).as("kept").isTrue();
  }

  @DisplayName("With Show all options Units shows every section, Home every step and tool")
  @Test
  void toolsStartOpen() throws Exception {
    // given
    final MapProject units =
        new MapProject(Path.of("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
    final MapProject home =
        new MapProject(Path.of("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
    final List<List<String>> seen = new ArrayList<>();
    final List<List<String>> labels = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          for (int i = 0; i < 2; i++) {
            final Component unitsPage = new UnitsEditor(units).page();
            seen.add(folds(unitsPage));
            labels.add(
                all(unitsPage)
                    .filter(JLabel.class::isInstance)
                    // a closed fold keeps its fields, hidden
                    .filter(c -> shows(c, unitsPage))
                    .map(c -> ((JLabel) c).getText())
                    .toList());
            seen.add(
                folds(
                    new HomeEditor(home, t -> {}, t -> {}, s -> {}, () -> {}, () -> {}, () -> {})
                        .page()));
            Folds.toggleShowAll();
          }
        });

    // then
    assertThat(seen.get(0)).as("Units as before: its folds closed").allMatch(t -> t.startsWith("▸ "));
    assertThat(labels.get(0)).as("the sections of other kinds folded away").doesNotContain("Bombing");
    assertThat(seen.get(1)).as("Home as before: More tools closed").anyMatch(t -> t.startsWith("▸ More tools"));
    assertThat(seen.get(2))
        .as("Units with the switch: every fold open, the sections of other kinds too")
        .isNotEmpty()
        .allMatch(t -> t.startsWith("▾ "))
        .anyMatch(t -> t.startsWith("▾ Not for"));
    assertThat(labels.get(1)).as("so every section shows").contains("Sea", "Bombing");
    assertThat(seen.get(3))
        .as("Home with the switch: More tools open")
        .anyMatch(t -> t.startsWith("▾ More tools"));
  }
}
