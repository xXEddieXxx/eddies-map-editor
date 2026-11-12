package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.PropertyList.Property;

final class RulesEditorsTest {
  private static final String LOW_LUCK = "Low Luck";

  private static MapProject givenG40Project(final Path folder) {
    return new MapProject(folder.resolve("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
  }

  private static <T> Stream<T> givenAll(final Component component, final Class<T> type) {
    final Stream<T> self =
        type.isInstance(component) ? Stream.of(type.cast(component)) : Stream.empty();
    return component instanceof final Container container
        ? Stream.concat(
            self, Stream.of(container.getComponents()).flatMap(child -> givenAll(child, type)))
        : self;
  }

  private static JCheckBox givenBox(final Editor editor, final String text) {
    return givenAll(editor.page(), JCheckBox.class)
        .filter(b -> text.equals(b.getText()))
        .findFirst()
        .orElseThrow();
  }

  private static JPanel givenRow(final Editor editor, final String option) {
    return givenAll(editor.page(), JPanel.class)
        .filter(p -> option.equals(p.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> givenRowNames(final Editor editor, final MapProject project) {
    final List<String> options =
        GameRulesXml.options(project.game()).stream().map(Property::getName).toList();
    return givenAll(editor.page(), JPanel.class)
        .map(Component::getName)
        .filter(options::contains)
        .toList();
  }

  private static Property givenOption(final MapProject project, final String name) {
    return GameRulesXml.options(project.game()).stream()
        .filter(o -> name.equals(o.getName()))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("Options go into sections by their name; the rest under More options")
  @Test
  void groupsOptions() {
    assertThat(RulesEditors.group("Germans bid")).as("a nation's bid").isEqualTo("Bids");
    assertThat(RulesEditors.group(LOW_LUCK)).as("the luck family").isEqualTo("Luck");
    assertThat(RulesEditors.group("Heavy Bomber Dice Rolls")).as("dice rolls").isEqualTo("Luck");
    assertThat(RulesEditors.group("Axis Honorable Victory VCs"))
        .as("what wins")
        .isEqualTo("Victory");
    assertThat(RulesEditors.group("Use Triggers"))
        .as("triggers")
        .isEqualTo("Politics & triggers");
    assertThat(RulesEditors.group("maxFactoriesPerTerritory"))
        .as("an option TripleA does not group")
        .isEmpty();
  }

  @DisplayName("Ticking a yes/no option is one undo step, and the page keeps its box")
  @Test
  void ticksInPlace(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final Editor editor = RulesEditors.newGameOptions(project);
    final JCheckBox box = givenBox(editor, LOW_LUCK);

    box.doClick();

    assertThat(GameRulesXml.value(givenOption(project, LOW_LUCK)))
        .as("the game starts with low luck")
        .isEqualTo("true");
    assertThat(givenBox(editor, LOW_LUCK))
        .as("the same box: the focus stays where it was")
        .isSameAs(box);
    project.undo();
    assertThat(GameRulesXml.value(givenOption(project, LOW_LUCK)))
        .as("undone in one step")
        .isEqualTo("false");
    assertThat(project.canUndo()).as("no other step was made").isFalse();
  }

  @DisplayName("The lock fixes an option and opens it again; the range only for open numbers")
  @Test
  void locksOptions(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final Editor editor = RulesEditors.newGameOptions(project);
    final JToggleButton lock =
        givenAll(givenRow(editor, LOW_LUCK), JToggleButton.class)
            .filter(b -> !(b instanceof JCheckBox))
            .findFirst()
            .orElseThrow();

    lock.doClick();
    final Boolean fixed = givenOption(project, LOW_LUCK).getEditable();
    lock.doClick();

    assertThat(fixed).as("closed: fixed by the map").isFalse();
    assertThat(givenOption(project, LOW_LUCK).getEditable())
        .as("open: players may change it")
        .isTrue();
    assertThat(lock.getToolTipText())
        .as("the lock says what it means")
        .isEqualTo("Players may change it before a game");
    assertThat(
            givenAll(givenRow(editor, "Axis Honorable Victory VCs"), JButton.class)
                .anyMatch(b -> b.isVisible() && "11–19".equals(b.getText())))
        .as("a number players may change shows its range, from the old number child")
        .isTrue();
    assertThat(
            givenAll(givenRow(editor, "Heavy Bomber Dice Rolls"), JButton.class)
                .anyMatch(b -> b.isVisible() && !b.getText().isEmpty()))
        .as("a fixed number has none in TripleA")
        .isFalse();
  }

  @DisplayName("Open variables without any take only a line below the options")
  @Test
  void emptyVariablesStaySmall(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final Editor editor = RulesEditors.newGameOptions(project);
    final JButton toggle =
        givenAll(editor.page(), JButton.class)
            .filter(b -> b.getText().startsWith("▸ Variables"))
            .findFirst()
            .orElseThrow();

    toggle.doClick();

    final Component bar =
        ((BorderLayout) editor.page().getLayout()).getLayoutComponent(BorderLayout.SOUTH);
    assertThat(toggle.getText()).as("open").startsWith("▾ Variables (0)");
    assertThat(bar.getPreferredSize().height)
        .as("the bar and one line: the options above keep the height")
        .isLessThan(100);
  }

  @DisplayName("The lock is a bare icon, closed as open")
  @Test
  void lockLooksAlike(@TempDir final Path folder) {
    final Editor editor = RulesEditors.newGameOptions(givenG40Project(folder));

    final List<JToggleButton> locks =
        givenAll(editor.page(), JToggleButton.class).filter(b -> !(b instanceof JCheckBox)).toList();

    assertThat(locks).as("every option has a lock").isNotEmpty();
    assertThat(locks)
        .as("no filled background for a closed lock")
        .noneMatch(JToggleButton::isContentAreaFilled);
  }

  @DisplayName("A search shows only the options whose name has it")
  @Test
  void searchesOptions(@TempDir final Path folder) {
    final MapProject project = givenG40Project(folder);
    final Editor editor = RulesEditors.newGameOptions(project);
    final JTextField search =
        givenAll(editor.page(), JTextField.class)
            .filter(
                f -> "Search options".equals(f.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT)))
            .findFirst()
            .orElseThrow();

    search.setText("luck");

    final List<String> shown = givenRowNames(editor, project);
    assertThat(shown).as("the low luck options").contains(LOW_LUCK, "Low Luck for Technology");
    assertThat(shown)
        .as("nothing else")
        .allSatisfy(name -> assertThat(name).as("a luck option").containsIgnoringCase("luck"));
  }
}
