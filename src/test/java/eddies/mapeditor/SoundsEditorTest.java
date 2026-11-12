package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MapTexts.Kind;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sounds: the list in groups, the filter, the default folder and the file rows. */
final class SoundsEditorTest {
  @TempDir Path folder;

  @DisplayName("Removing a path keeps every other entry, also unknown ones, in its order")
  @Test
  void withoutKeepsTheRest() {
    final String value = "a.mp3; odd|entry ;b/;a.mp3x";

    final String rest = SoundsEditor.without(value, "a.mp3");

    assertThat(rest).as("only that path gone").isEqualTo("odd|entry;b/;a.mp3x");
  }

  @DisplayName("Sounds are grouped: Battles, Turns and phases, Messages, Other")
  @Test
  void groups() {
    final List<String> groups =
        Stream.of("bombing_rocket", "phase_purchase", "victory_fanfare", "game_won")
            .map(SoundsEditor::group)
            .toList();

    assertThat(groups)
        .as("each key's group by its start")
        .containsExactly("Battles", "Turns and phases", "Messages", "Other");
  }

  @DisplayName("The list has the default folder first, then the sounds in group order")
  @Test
  void listOrder() throws Exception {
    final SoundsEditor sounds = new SoundsEditor(givenMinimap(""));

    final List<String> groups =
        sounds.shownKeys().stream().skip(1).map(SoundsEditor::group).toList();

    assertThat(sounds.shownKeys().getFirst())
        .as("the default folder pinned first")
        .isEqualTo("Sound.Default.Folder");
    assertThat(
            IntStream.range(1, groups.size())
                .allMatch(i -> order(groups.get(i - 1)) <= order(groups.get(i))))
        .as("groups never go back, in " + groups)
        .isTrue();
  }

  @DisplayName("A set sound shows its plain name, and its group, own and file count below")
  @Test
  void setSoundRow() throws Exception {
    final SoundsEditor sounds = new SoundsEditor(givenMinimap("battle_land=a.mp3;b.mp3\n"));

    assertThat(sounds.title("battle_land")).as("the plain name").isEqualTo("Land battle");
    assertThat(sounds.detail("battle_land"))
        .as("group, the map's own, how many files")
        .isEqualTo("Battles · Your own · 2 files");
  }

  @DisplayName("A map that sets sounds opens on Your own, showing only those")
  @Test
  void yourOwnByDefault() throws Exception {
    final SoundsEditor sounds = new SoundsEditor(givenMinimap("battle_land=a.mp3\n"));

    final AbstractButton own = button(sounds, b -> b.getText().startsWith("Your own"));

    assertThat(own.isSelected()).as("Your own chosen").isTrue();
    assertThat(own.getText()).as("its count").isEqualTo("Your own 1");
    assertThat(sounds.shownKeys()).as("only the map's own").containsExactly("battle_land");
  }

  @DisplayName("A default folder the list does not know is kept as an item, chosen")
  @Test
  void unknownDefaultFolderKept() throws Exception {
    final SoundsEditor sounds = new SoundsEditor(givenMinimap("Sound.Default.Folder=mymusic\n"));

    final JComboBox<?> combo = find(sounds.page(), JComboBox.class).getFirst();

    assertThat(String.valueOf(combo.getSelectedItem()))
        .as("the file's value, not replaced by ww2")
        .isEqualTo("mymusic");
  }

  @DisplayName("× removes one file from the sound as one step, the rest kept")
  @Test
  void removeOneFile() throws Exception {
    final MapProject project = givenMinimap("battle_land=a.mp3;keep|me\n");
    final SoundsEditor sounds = new SoundsEditor(project);

    button(sounds, b -> "Remove from this sound".equals(b.getToolTipText())).doClick();
    final String after = project.texts().value(Kind.SOUNDS, "battle_land").orElseThrow();
    project.undo();

    assertThat(after).as("the other entry kept as written").isEqualTo("keep|me");
    assertThat(project.texts().value(Kind.SOUNDS, "battle_land"))
        .as("one undo brings the file back")
        .contains("a.mp3;keep|me");
  }

  @DisplayName("A file the game would not find is marked not found")
  @Test
  void missingFileMarked() throws Exception {
    final SoundsEditor sounds = new SoundsEditor(givenMinimap("battle_land=nowhere/gone.mp3\n"));

    final boolean marked =
        find(sounds.page(), JLabel.class).stream()
            .anyMatch(l -> "not found".equals(l.getText()));

    assertThat(marked).as("the row says the file is missing").isTrue();
  }

  private static int order(final String group) {
    return List.of("Battles", "Turns and phases", "Messages", "Other").indexOf(group);
  }

  private MapProject givenMinimap(final String soundsProperties) throws Exception {
    if (!soundsProperties.isEmpty()) {
      Files.writeString(
          folder.resolve("sounds.properties"), soundsProperties, StandardCharsets.ISO_8859_1);
    }
    return new MapProject(
        folder.resolve("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
  }

  private static AbstractButton button(
      final SoundsEditor sounds, final Predicate<AbstractButton> which) {
    return find(sounds.page(), AbstractButton.class).stream()
        .filter(which)
        .findFirst()
        .orElseThrow();
  }

  private static <T> List<T> find(final Container in, final Class<T> type) {
    return Stream.of(in.getComponents())
        .flatMap(
            (Component c) ->
                Stream.concat(
                    type.isInstance(c) ? Stream.of(type.cast(c)) : Stream.empty(),
                    c instanceof Container inner ? find(inner, type).stream() : Stream.empty()))
        .toList();
  }
}
