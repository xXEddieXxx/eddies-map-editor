package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.AttachmentList.Attachment;

@ExtensionMethod(Builders.class)
final class AttachmentsEditorTest {
  /** World War II Global 1940, with its triggers and conditions, without its map's files. */
  private static MapProject givenG40() {
    return new MapProject(Path.of("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
  }

  private static String givenFirstTrigger(final MapProject project) {
    return AttachmentsXml.attachments(project.game()).stream()
        .filter(a -> TriggerXml.TRIGGER.equals(AttachmentsXml.kind(a)))
        .map(Attachment::getName)
        .findFirst()
        .orElseThrow();
  }

  /** G40 with a copy of its first trigger, without options, at the end: the same name twice. */
  private static MapProject givenTwiceNamedTrigger() {
    final MapProject project = givenG40();
    final String trigger = givenFirstTrigger(project);
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(project.game()));
    attachments.add(
        attachments.stream()
            .filter(a -> trigger.equals(a.getName()))
            .findFirst()
            .orElseThrow()
            .toBuilder()
            .options(List.of())
            .build());
    return new MapProject(
        Path.of("g40.xml"),
        AttachmentsXml.withAttachments(project.game(), attachments),
        MapFiles.none());
  }

  private static Stream<Component> all(final Container in) {
    return Stream.of(in.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }

  private static <T> Stream<T> all(final AttachmentsEditor editor, final Class<T> type) {
    return all((Container) editor.page()).filter(type::isInstance).map(type::cast);
  }

  private static JComboBox<?> kinds(final AttachmentsEditor editor) {
    return all(editor, JComboBox.class)
        .filter(c -> c.getItemCount() > 0 && c.getItemAt(0).toString().startsWith("All kinds"))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> items(final JComboBox<?> combo) {
    return IntStream.range(0, combo.getItemCount())
        .mapToObj(i -> combo.getItemAt(i).toString())
        .toList();
  }

  private static JList<?> list(final AttachmentsEditor editor) {
    return all(editor, JList.class).findFirst().orElseThrow();
  }

  @DisplayName("A row is titled by what it changes or by the name without its kind's start")
  @Test
  void titlesRowsPlainly() {
    final Attachment unit =
        AttachmentsXml.newAttachment("UnitAttachment", "unitAttachment", "infantry");
    final Attachment trigger =
        AttachmentsXml.newAttachment(
            "TriggerAttachment", "triggerAttachment_Americans_War", "Americans");
    final Attachment numbered =
        AttachmentsXml.newAttachment("TriggerAttachment", "triggerAttachment3", "Germans");

    assertThat(AttachmentsEditor.rowTitle(unit))
        .as("the usual name says nothing: the unit type it is on")
        .isEqualTo("infantry");
    assertThat(AttachmentsEditor.rowTitle(trigger))
        .as("the name of its own, in words")
        .isEqualTo("Americans War");
    assertThat(AttachmentsEditor.rowTitle(numbered))
        .as("a number alone gets its kind")
        .isEqualTo("Trigger 3");
  }

  @DisplayName("The kind filter names kinds in plain plurals with counts and lists only that kind")
  @Test
  void filtersByKind() throws Exception {
    final MapProject project = givenG40();
    final long triggerCount =
        AttachmentsXml.attachments(project.game()).stream()
            .filter(a -> TriggerXml.TRIGGER.equals(AttachmentsXml.kind(a)))
            .map(a -> a.getName() + " · " + a.getAttachTo())
            .distinct()
            .count();
    final List<List<String>> seen = new ArrayList<>();
    final List<Integer> rows = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final AttachmentsEditor editor = new AttachmentsEditor(project);
          final JComboBox<?> kinds = kinds(editor);
          seen.add(items(kinds));
          kinds.setSelectedIndex(
              items(kinds).indexOf(
                  items(kinds).stream()
                      .filter(i -> i.startsWith("Triggers ("))
                      .findFirst()
                      .orElseThrow()));
          rows.add(list(editor).getModel().getSize());
        });

    assertThat(seen.getFirst())
        .as("plain plural names with counts, not TripleA's classes")
        .anyMatch(i -> i.startsWith("Triggers ("))
        .anyMatch(i -> i.startsWith("Conditions ("))
        .noneMatch(i -> i.contains("Attachment"));
    assertThat(rows.getFirst()).as("only triggers listed").isEqualTo((int) triggerCount);
  }

  @DisplayName("show(name) chooses the attachment of that name, also when another kind is listed")
  @Test
  void showsAttachmentByName() throws Exception {
    final MapProject project = givenG40();
    final String trigger = givenFirstTrigger(project);
    final List<Object> seen = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final AttachmentsEditor editor = new AttachmentsEditor(project);
          final JComboBox<?> kinds = kinds(editor);
          kinds.setSelectedIndex(
              items(kinds).indexOf(
                  items(kinds).stream()
                      .filter(i -> i.startsWith("Conditions ("))
                      .findFirst()
                      .orElseThrow()));
          editor.show(trigger);
          seen.add(
              all(editor, JTextField.class).anyMatch(f -> trigger.equals(f.getText())));
          seen.add(String.valueOf(list(editor).getSelectedValue()));
        });

    assertThat(seen.get(0)).as("its name in the Name field").isEqualTo(true);
    assertThat((String) seen.get(1))
        .as("chosen in the list, which shows it again")
        .startsWith(trigger + " · ");
  }

  @DisplayName("show of a whereNamed line chooses the attachment of that name on that target")
  @Test
  void showsWhereNamedLine() throws Exception {
    final MapProject project = givenG40();
    final Attachment last = givenLastOfRepeatedName(project);
    final List<Object> chosenTargets = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final AttachmentsEditor editor = new AttachmentsEditor(project);
          editor.show(last.getName() + " of " + last.getAttachTo() + ": movement");
          all(editor, JComboBox.class)
              .map(JComboBox::getSelectedItem)
              .filter(last.getAttachTo()::equals)
              .forEach(chosenTargets::add);
        });

    assertThat(chosenTargets)
        .as("the one attached to " + last.getAttachTo() + ", not the first of its name")
        .containsExactly(last.getAttachTo());
  }

  @DisplayName("A where-named line finds an attachment whose name has \" of \" in it")
  @Test
  void showsWhereNamedLineOfNameWithOf() throws Exception {
    // given
    final MapProject project = givenG40();
    project.edit(
        g ->
            AttachmentsXml.withNewAttachment(
                g, "RulesAttachment", "conditionAttachment_Capture of Paris", "Germans"));
    final List<Object> chosenTargets = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final AttachmentsEditor editor = new AttachmentsEditor(project);
          editor.show("conditionAttachment_Capture of Paris of Germans: conditions");
          all(editor, JComboBox.class)
              .map(JComboBox::getSelectedItem)
              .filter("Germans"::equals)
              .forEach(chosenTargets::add);
        });

    // then
    assertThat(chosenTargets).as("chosen, attached to Germans").containsExactly("Germans");
  }

  /** The last attachment whose name, eg unitAttachment, others have too. */
  private static Attachment givenLastOfRepeatedName(final MapProject project) {
    final List<Attachment> attachments = AttachmentsXml.attachments(project.game());
    return attachments.reversed().stream()
        .filter(
            a ->
                attachments.stream()
                        .filter(other -> a.getName().equals(other.getName()))
                        .count()
                    > 1)
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("Of two attachments with the same name on the same thing the last is shown")
  @Test
  void showsTheLastOfTheSameName() throws Exception {
    final MapProject project = givenTwiceNamedTrigger();
    final String trigger = givenFirstTrigger(project);
    final List<String> titles = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final AttachmentsEditor editor = new AttachmentsEditor(project);
          editor.show(trigger);
          all(editor, JLabel.class)
              .map(JLabel::getText)
              .filter(t -> t != null && t.startsWith("Options ("))
              .forEach(titles::add);
        });

    assertThat(titles)
        .as("the copy without options, as TripleA takes the last one")
        .containsExactly("Options (0)");
  }

  @DisplayName("A kind shows its plain name when chosen, its TripleA class only in the open list")
  @Test
  void kindReadsPlainWhenChosen() {
    // given
    final AttachmentsEditor editor = new AttachmentsEditor(givenG40());
    @SuppressWarnings("unchecked")
    final ListCellRenderer<Object> renderer =
        (ListCellRenderer<Object>) kinds(editor).getRenderer();

    // when
    final String chosen = ((JLabel) renderer.getListCellRendererComponent(
        new JList<>(), TriggerXml.TRIGGER, -1, false, false)).getText();
    final String listed = ((JLabel) renderer.getListCellRendererComponent(
        new JList<>(), TriggerXml.TRIGGER, 0, false, false)).getText();

    // then
    assertThat(chosen).as("the closed box does not repeat the kind").isEqualTo("Trigger");
    assertThat(listed).as("the open list names the class, for TripleA's docs")
        .contains("Trigger").contains(TriggerXml.TRIGGER);
  }

  @DisplayName("Remove attachment sits bottom left, as Remove does in every tool")
  @Test
  void removeSitsBottomLeft() {
    // given
    final MapProject project = givenG40();
    final AttachmentsEditor editor = new AttachmentsEditor(project);
    editor.show(givenFirstTrigger(project));
    final JComponent page = editor.page();
    page.setSize(1200, 800);

    // when
    layOut(page);
    final JButton remove = all(editor, JButton.class)
        .filter(b -> "Remove attachment".equals(b.getText()))
        .findFirst()
        .orElseThrow();
    final Container details = remove.getParent().getParent().getParent();
    final int x = SwingUtilities.convertPoint(remove, 0, 0, details).x;

    // then
    assertThat(x).as("at the left of the details, not the right").isLessThan(details.getWidth() / 4);
  }

  private static void layOut(final Component component) {
    component.doLayout();
    if (component instanceof Container container) {
      Stream.of(container.getComponents()).forEach(AttachmentsEditorTest::layOut);
    }
  }
}
