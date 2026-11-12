package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import com.formdev.flatlaf.FlatDarkLaf;
import games.strategy.triplea.Constants;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PoliticsEditorTest {

  private static MapProject givenG40(final Path folder) {
    return new MapProject(
        folder.resolve("ww2global40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
  }

  @DisplayName("Set all to By alliance takes out every pair of the nation as one step")
  @Test
  void setsAllByAlliance(@TempDir final Path folder) {
    final MapProject project = givenG40(folder);
    final PoliticsEditor editor = new PoliticsEditor(project);

    editor.setAllByAlliance("Germans");

    assertThat(MapXml.nations(project.game()))
        .as("no pair of the Germans is left")
        .allSatisfy(
            n ->
                assertThat(RulesXml.relationship(project.game(), "Germans", n))
                    .as("Germans go by alliance with " + n)
                    .isEmpty());
    assertThat(RulesXml.relationship(project.game(), "Russians", "Japanese"))
        .as("pairs of other nations stay")
        .contains("Neutrality");
    project.undo();
    assertThat(RulesXml.relationship(project.game(), "Germans", "Russians"))
        .as("one undo brings them back")
        .contains("Neutrality");
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @DisplayName("Under the tabs the types list keeps room above its count line")
  @Test
  void listHasRoomBelowTabs(@TempDir final Path folder) {
    final JTabbedPane page = (JTabbedPane) new PoliticsEditor(givenG40(folder)).page();

    final int top =
        ((JComponent) ((JSplitPane) page.getComponentAt(1)).getLeftComponent()).getInsets().top;

    assertThat(top).as("the count not squeezed under the tab line").isEqualTo(Theme.GAP_M);
  }

  @DisplayName("A matrix cell shows how two start; a type chosen in its menu is mutual, one step")
  @Test
  void matrixCellChangesPair(@TempDir final Path folder) {
    // given: in G40 Germans and Japanese start as Allied
    final MapProject project = givenG40(folder);
    final PoliticsEditor editor = new PoliticsEditor(project);
    final Optional<String> before = RulesXml.relationship(project.game(), "Germans", "Japanese");
    final String shown = editor.cell(project.game(), "Germans", "Japanese").getText();

    // when
    final JPopupMenu menu = editor.startMenu("Germans", "Japanese", before);
    Stream.of(menu.getComponents())
        .filter(c -> c instanceof JMenuItem item && "Neutrality".equals(item.getText()))
        .map(JMenuItem.class::cast)
        .findFirst()
        .orElseThrow()
        .doClick();

    // then
    assertThat(shown).as("the type they start as").isEqualTo(before.orElseThrow());
    assertThat(editor.cell(project.game(), "Japanese", "Germans").getText())
        .as("mutual: the other way round too")
        .isEqualTo("Neutrality");
    project.undo();
    assertThat(RulesXml.relationship(project.game(), "Germans", "Japanese"))
        .as("one undo: as before")
        .isEqualTo(before);
  }

  @DisplayName("A cell is strong where a type is set, light where the pair goes by alliance")
  @Test
  void cellShowsWhetherSet(@TempDir final Path folder) {
    // given: Germans and Japanese start as Allied, Germans and Italians by alliance
    final MapProject project = givenG40(folder);
    final PoliticsEditor editor = new PoliticsEditor(project);
    project.edit(g -> RulesXml.withoutRelationship(g, "Germans", "Italians"));

    // when
    final JButton set = editor.cell(project.game(), "Germans", "Japanese");
    final JButton byAlliance = editor.cell(project.game(), "Germans", "Italians");
    final JButton itself = editor.cell(project.game(), "Germans", "Germans");

    // then
    assertThat(byAlliance.getText()).as("its kind by alliance").isEqualTo("Allied");
    assertThat(set.getBackground())
        .as("set: another, stronger shade")
        .isNotEqualTo(byAlliance.getBackground());
    assertThat(set.getAccessibleContext().getAccessibleName())
        .as("read out with both nations")
        .isEqualTo("Germans and Japanese: Allied");
    assertThat(itself.isEnabled()).as("a nation with itself: nothing to choose").isFalse();
  }

  @DisplayName("In a game without types a cell offers the kinds; one chosen makes its type, loads")
  @Test
  void cellMakesMissingType(@TempDir final Path folder) throws Exception {
    // given: a new map of two nations, no relationship types
    final MapProject project =
        MapProject.newMap(
                "Two", MapPictureTest.givenPicture(), List.of("Red", "Blue"), folder.resolve("maps"))
            .project();
    final PoliticsEditor editor = new PoliticsEditor(project);

    // when
    final JPopupMenu menu = editor.startMenu("Red", "Blue", Optional.empty());
    final List<String> offered =
        Stream.of(menu.getComponents())
            .filter(c -> c instanceof JMenuItem)
            .map(c -> ((JMenuItem) c).getText())
            .toList();
    Stream.of(menu.getComponents())
        .filter(c -> c instanceof JMenuItem item && item.getText().startsWith("Neutral"))
        .map(JMenuItem.class::cast)
        .findFirst()
        .orElseThrow()
        .doClick();
    final List<String> typesMade = RulesXml.relationshipTypes(project.game());
    project.undo();
    final List<String> typesUndone = RulesXml.relationshipTypes(project.game());
    final Optional<String> pairUndone = RulesXml.relationship(project.game(), "Red", "Blue");
    project.redo();
    project.save();

    // then
    assertThat(typesMade).as("the type made").containsExactly("Neutral");
    assertThat(typesUndone).as("one undo takes out the type").isEmpty();
    assertThat(pairUndone).as("and the pair").isEmpty();
    assertThat(offered)
        .as("by alliance and the three kinds, each with its new type")
        .containsExactly(
            "By alliance",
            "At war (new type War)",
            "Allied (new type Allied)",
            "Neutral (new type Neutral)");
    assertThat(RulesXml.relationship(project.game(), "Blue", "Red"))
        .as("both start neutral")
        .contains("Neutral");
    assertThat(MapChecks.findError(project.getXmlFile().orElseThrow()))
        .as("the game loads in TripleA")
        .isEmpty();
  }

  @DisplayName("A cell offers only the kinds the game has no type of, below its types")
  @Test
  void cellOffersOnlyMissingKinds(@TempDir final Path folder) throws Exception {
    // given: a game with only a war type
    final MapProject project =
        MapProject.newMap(
                "Two", MapPictureTest.givenPicture(), List.of("Red", "Blue"), folder.resolve("maps"))
            .project();
    project.edit(
        g -> RulesXml.withRelationshipType(g, "Hostile", Constants.RELATIONSHIP_ARCHETYPE_WAR));
    final PoliticsEditor editor = new PoliticsEditor(project);

    // when
    final List<String> offered =
        Stream.of(editor.startMenu("Red", "Blue", Optional.empty()).getComponents())
            .filter(c -> c instanceof JMenuItem)
            .map(c -> ((JMenuItem) c).getText())
            .toList();

    // then
    assertThat(offered)
        .as("the war kind has its type: not offered again")
        .containsExactly(
            "By alliance", "Hostile", "Allied (new type Allied)", "Neutral (new type Neutral)");
  }

  @DisplayName("The matrix of 14 nations fits a big screen at the text's size, the legend apart")
  @Test
  void matrixFitsAndLegendApart(@TempDir final Path folder) throws Exception {
    // given: G40's 14 nations, a pair starting with a long type's name, the editor's font
    final LookAndFeel before = UIManager.getLookAndFeel();
    Theme.install();
    UIManager.setLookAndFeel(new FlatDarkLaf());
    try {
      final MapProject project = givenG40(folder);
      project.edit(
          g ->
              RulesXml.withRelationship(
                  RulesXml.withRelationshipType(
                      g, "Custodianship", Constants.RELATIONSHIP_ARCHETYPE_NEUTRAL),
                  "Germans",
                  "Russians",
                  "Custodianship"));
      final PoliticsEditor editor = new PoliticsEditor(project);
      final JComponent start = (JComponent) ((JTabbedPane) editor.page()).getComponentAt(0);

      // when: the page of a 1920 px window
      editor.showing();
      start.setSize(1680, 900);
      layOut(start);

      // then
      final JScrollPane matrix =
          all(start).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast)
              .findFirst().orElseThrow();
      final JButton cell =
          all(matrix).filter(c -> c instanceof JButton b && "Custodianship".equals(b.getText()))
              .map(JButton.class::cast).findFirst().orElseThrow();
      final JLabel legend =
          all(start).filter(c -> c instanceof JLabel l && l.getText().endsWith(" at heart"))
              .map(JLabel.class::cast).findFirst().orElseThrow();
      final int matrixBottom = matrix.getY() + matrix.getHeight();
      assertThat(matrix.getPreferredSize().width)
          .as("all 14 columns without scrolling")
          .isLessThanOrEqualTo(1680);
      assertThat(cell.getFont().getSize())
          .as("cells at the text's size")
          .isEqualTo(UIManager.getFont("Label.font").getSize());
      assertThat(cell.getPreferredSize().width - cell.getFontMetrics(cell.getFont()).stringWidth("Custodianship"))
          .as("room around the longest name")
          .isGreaterThanOrEqualTo(2 * Theme.GAP_S);
      assertThat(SwingUtilities.convertPoint(legend.getParent(), legend.getLocation(),
              matrix.getParent()).y - matrixBottom)
          .as("the legend apart from the last row")
          .isGreaterThanOrEqualTo(Theme.GAP_M);
    } finally {
      Theme.uninstall();
      UIManager.setLookAndFeel(before);
    }
  }

  private static Stream<Component> all(final Component in) {
    return in instanceof Container container
        ? Stream.concat(
            Stream.of(in), Stream.of(container.getComponents()).flatMap(PoliticsEditorTest::all))
        : Stream.of(in);
  }

  private static void layOut(final Component component) {
    component.doLayout();
    if (component instanceof Container container) {
      Stream.of(container.getComponents()).forEach(PoliticsEditorTest::layOut);
    }
  }
}
