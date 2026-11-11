package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UnitsEditorTest {
  @TempDir Path folder;

  private MapProject givenG40() throws Exception {
    final Path xml = folder.resolve("ww2global40.xml");
    try (InputStream original =
        UnitsEditorTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  /** The editor showing the unit with the section open. */
  @SuppressWarnings("unchecked")
  private static UnitsEditor givenEditor(
      final MapProject project, final String unit, final String section) throws Exception {
    final UnitsEditor editor = new UnitsEditor(project);
    final Field open = UnitsEditor.class.getDeclaredField("open");
    open.setAccessible(true);
    ((Set<String>) open.get(editor)).add(section);
    final Method choose = UnitsEditor.class.getDeclaredMethod("choose", String.class);
    choose.setAccessible(true);
    choose.invoke(editor, unit);
    return editor;
  }

  private static JTextField givenFieldShowing(final Component in, final String text) {
    final List<JTextField> found = new ArrayList<>();
    collect(in, found);
    return found.stream().filter(f -> text.equals(f.getText())).findFirst().orElseThrow();
  }

  private static void collect(final Component component, final List<JTextField> found) {
    if (component instanceof JTextField field) {
      found.add(field);
    }
    if (component instanceof Container container) {
      for (final Component child : container.getComponents()) {
        collect(child, found);
      }
    }
  }

  @DisplayName("A field typed in twice takes the second text, also when it is the first value")
  @Test
  void fieldTakesEachText() throws Exception {
    final MapProject project = givenG40();
    final List<String> afterFirst = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final JTextField kind =
                givenFieldShowing(
                    givenEditor(project, "fighter", "Air and anti-air").page(), "AA");
            kind.setText("flak");
            kind.postActionEvent();
            afterFirst.add(MapXml.option(project.game(), "unitAttachment", "fighter", "typeAA")
                .orElse(""));
            kind.setText("AA");
            kind.postActionEvent();
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(afterFirst).as("the first text taken").containsExactly("flak");
    assertThat(MapXml.option(project.game(), "unitAttachment", "fighter", "typeAA"))
        .as("back to TripleA's default AA, the option is left out again")
        .isEmpty();
  }
}
