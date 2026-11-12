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
import javax.swing.AbstractButton;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSpinner;
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
    return collect(in, JTextField.class).stream()
        .filter(f -> text.equals(f.getText()))
        .findFirst()
        .orElseThrow();
  }

  private static <T> List<T> collect(final Component in, final Class<T> type) {
    final List<T> found = new ArrayList<>();
    collect(in, type, found);
    return found;
  }

  private static <T> void collect(
      final Component component, final Class<T> type, final List<T> found) {
    if (type.isInstance(component)) {
      found.add(type.cast(component));
    }
    if (component instanceof Container container) {
      for (final Component child : container.getComponents()) {
        collect(child, type, found);
      }
    }
  }

  private static List<String> givenButtonTexts(final Component in) {
    return collect(in, AbstractButton.class).stream().map(AbstractButton::getText).toList();
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

  @DisplayName("An emptied number with a blank word is -1, TripleA's default, so it is left out")
  @Test
  void emptiedNumberIsLeftOut() throws Exception {
    final MapProject project = givenG40();
    final List<Object> shown = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final JSpinner capacity =
                (JSpinner)
                    collect(givenEditor(project, "transport", "Sea").page(), JLabel.class).stream()
                        .filter(l -> "Transport capacity".equals(l.getText()))
                        .findFirst()
                        .orElseThrow()
                        .getLabelFor();
            final JFormattedTextField text =
                ((JSpinner.DefaultEditor) capacity.getEditor()).getTextField();
            shown.add(text.getText());
            text.setText("");
            text.commitEdit();
            shown.add(capacity.getValue());
            shown.add(text.getText());
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(shown)
        .as("5 at first; emptied it is -1, shown empty")
        .containsExactly("5", -1, "");
    assertThat(MapXml.option(project.game(), "unitAttachment", "transport", "transportCapacity"))
        .as("-1 is TripleA's default: the option is left out")
        .isEmpty();
  }

  @DisplayName("A support reads as one sentence")
  @Test
  void supportReadsAsSentence() throws Exception {
    final MapProject project = givenG40();

    final String sentence =
        UnitsEditor.supportSentence(project.game(), "fighter", "supportAttachmentFighter");

    assertThat(sentence)
        .as("G40's fighter: bonus 1, strength, number 1, allied tactical_bomber, offence")
        .isEqualTo("+1 attack to 1 allied tactical_bomber when attacking");
  }

  @DisplayName("Combat shows its common options and those set; the rest after Show all")
  @Test
  void combatShowsCommonUntilShowAll() throws Exception {
    final MapProject project = givenG40();
    final List<List<String>> buttons = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            buttons.add(givenButtonTexts(givenEditor(project, "infantry", "none").page()));
            buttons.add(givenButtonTexts(givenEditor(project, "infantry", "Combat").page()));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(buttons.getFirst())
        .as("infantry: a common option, one set, the link, not a rare option")
        .contains(
            "Fires first: its hits take no shot back",
            "Gets the support of artillery",
            "Show all 14 combat options ›")
        .doesNotContain("Dies when it hits");
    assertThat(buttons.get(1))
        .as("after Show all: every combat option, and the link back")
        .contains("Dies when it hits", "Show fewer ‹");
  }

  @DisplayName("The list shows a row for each unit, its name as the row's title")
  @Test
  void listShowsEveryUnit() throws Exception {
    final MapProject project = givenG40();
    final List<String> titles = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final Component page = new UnitsEditor(project).page();
          page.setSize(1240, 840);
          givenLaidOut(page);
          @SuppressWarnings("unchecked")
          final JList<String> list = collect(page, JList.class).getFirst();
          for (int i = 0; i < list.getModel().getSize(); i++) {
            final Component row =
                list.getCellRenderer()
                    .getListCellRendererComponent(
                        list, list.getModel().getElementAt(i), i, false, false);
            titles.add(collect(row, JLabel.class).getFirst().getText());
          }
        });

    assertThat(titles)
        .as("every G40 unit, by name, in the game's order")
        .hasSize(19)
        .containsExactlyElementsOf(MapXml.unitTypes(project.game()));
  }

  @DisplayName("Under In each nation's color, a link to Nations, where the colors are set")
  @Test
  void nationTintLinksToNations() throws Exception {
    final MapProject project = givenG40();
    final List<List<String>> texts = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            texts.add(givenButtonTexts(givenEditor(project, "infantry", "").page()));
            project.tintUnits(List.of("Germans"));
            texts.add(givenButtonTexts(givenEditor(project, "infantry", "").page()));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });

    assertThat(texts.get(0)).as("no nation's units tinted yet").doesNotContain("Nations ›");
    assertThat(texts.get(1))
        .as("the tint follows the nation's color")
        .contains("In each nation's color", "Nations ›");
  }

  private static void givenLaidOut(final Component component) {
    if (component instanceof Container container) {
      container.doLayout();
      for (final Component child : container.getComponents()) {
        givenLaidOut(child);
      }
    }
  }
}
