package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MakerIcon.Glyph;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SidebarTest {
  private final Preferences preferences =
      Preferences.userRoot().node("eddies-map-editor-test-" + UUID.randomUUID());
  private final List<Editor> chosen = new ArrayList<>();
  private final Editor home = givenTool("Home");
  private final Editor territories = givenTool("Territories");
  private final Editor nations = givenTool("Nations");
  private final Editor attachments = givenTool("Attachments");
  private final Editor files = givenTool("Files");

  @AfterEach
  void removePreferences() throws BackingStoreException {
    preferences.removeNode();
  }

  @DisplayName("The tools show in their groups' order, Advanced folded at the end")
  @Test
  void toolsInOrderAdvancedFolded() {
    final Sidebar sidebar = givenSidebar();

    final List<String> rows = toolRows(sidebar).stream().map(SidebarTest::title).toList();
    final List<String> shown =
        toolRows(sidebar).stream().filter(Component::isVisible).map(SidebarTest::title).toList();

    assertThat(rows)
        .as("Home first, then the groups, Advanced last")
        .containsExactly("Home", "Territories", "Nations", "Attachments", "Files");
    assertThat(shown)
        .as("Advanced starts folded")
        .containsExactly("Home", "Territories", "Nations");
  }

  @DisplayName("Selecting a tool of Advanced opens it and marks the tool, without choosing it")
  @Test
  void selectOpensAdvanced() {
    final Sidebar sidebar = givenSidebar();

    sidebar.select(files);

    final JToggleButton row = row(sidebar, "Files");
    assertThat(row.isVisible()).as("Advanced opened for it").isTrue();
    assertThat(row.isSelected()).as("marked as the tool shown").isTrue();
    assertThat(chosen).as("no tool chosen again: the window shows it already").isEmpty();
  }

  @DisplayName("A click on a tool chooses it")
  @Test
  void clickChooses() {
    final Sidebar sidebar = givenSidebar();

    row(sidebar, "Nations").doClick();

    assertThat(chosen).as("the tool clicked").containsExactly(nations);
  }

  @DisplayName("Below 1280 px only icons show, unless pinned open")
  @Test
  void collapsesBelow1280() {
    final Sidebar sidebar = givenSidebar();

    sidebar.fitTo(1440);
    final boolean collapsedWide = sidebar.isCollapsed();
    sidebar.fitTo(1100);
    final boolean collapsedNarrow = sidebar.isCollapsed();
    final String narrowText = row(sidebar, "Nations").getText();
    button(sidebar, "Show the names").doClick();

    assertThat(collapsedWide).as("wide: names").isFalse();
    assertThat(collapsedNarrow).as("narrow: icons only").isTrue();
    assertThat(narrowText).as("the name is in the tool tip only").isEmpty();
    assertThat(row(sidebar, "Nations").getToolTipText())
        .as("the tool tip names the tool and its key")
        .isEqualTo("Nations (Alt+3)");
    assertThat(sidebar.isCollapsed()).as("pinned open in a narrow window").isFalse();
    assertThat(givenSidebar().isCollapsed())
        .as("the pin is kept for the next map")
        .isFalse();
  }

  @DisplayName("Every tool shows without scrolling: 1440 x 900 with names, 1100 x 700 icons only")
  @Test
  void everyToolFits() {
    final Sidebar sidebar = givenFullSidebar();

    sidebar.fitTo(1440);
    final int namesHeight = height(sidebar);
    sidebar.fitTo(1100);
    final int iconsHeight = height(sidebar);

    // the window's content less the header: 861 - 53 and 661 - 53
    assertThat(namesHeight).as("all groups and Advanced, with names").isLessThanOrEqualTo(808);
    assertThat(iconsHeight).as("all groups and Advanced, icons only").isLessThanOrEqualTo(608);
  }

  /** The window's 20 tools in its groups, Advanced open. */
  private Sidebar givenFullSidebar() {
    preferences.putBoolean("advancedOpen", true);
    final List<Sidebar.Group> groups =
        Stream.of(
                List.of("Home"),
                List.of("Territories", "Connections", "Canals", "Terrain effects", "Map look"),
                List.of("Nations", "Politics", "Technology"),
                List.of("Units", "Production"),
                List.of("Turn order", "Game options", "Actions", "Triggers"),
                List.of("Game notes", "Texts", "Sounds"))
            .map(
                titles ->
                    new Sidebar.Group(
                        titles.getFirst(), titles.stream().map(SidebarTest::givenTool).toList()))
            .toList();
    return new Sidebar(
        groups,
        new Sidebar.Group("Advanced", List.of(attachments, files)),
        chosen::add,
        new JPopupMenu(),
        preferences);
  }

  private static int height(final Sidebar sidebar) {
    final JScrollPane scroll =
        all(sidebar).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast).findFirst()
            .orElseThrow();
    final Insets insets = sidebar.getInsets();
    return scroll.getViewport().getView().getPreferredSize().height
        + sidebar.getComponent(1).getPreferredSize().height
        + insets.top
        + insets.bottom;
  }

  private Sidebar givenSidebar() {
    final Sidebar sidebar =
        new Sidebar(
            List.of(
                new Sidebar.Group("Home", List.of(home)),
                new Sidebar.Group("Map", List.of(territories)),
                new Sidebar.Group("Players", List.of(nations))),
            new Sidebar.Group("Advanced", List.of(attachments, files)),
            chosen::add,
            new JPopupMenu(),
            preferences);
    sidebar.fitTo(1100);
    return sidebar;
  }

  private static Editor givenTool(final String title) {
    final JPanel page = new JPanel();
    return new Editor() {
      @Override
      public String title() {
        return title;
      }

      @Override
      public String hint() {
        return "What " + title + " does.";
      }

      @Override
      public Glyph glyph() {
        return Glyph.FILES;
      }

      @Override
      public JComponent page() {
        return page;
      }
    };
  }

  private static List<JToggleButton> toolRows(final Container sidebar) {
    return all(sidebar)
        .filter(JToggleButton.class::isInstance)
        .map(JToggleButton.class::cast)
        .toList();
  }

  private static JToggleButton row(final Container sidebar, final String title) {
    return toolRows(sidebar).stream().filter(r -> title(r).equals(title)).findFirst().orElseThrow();
  }

  private static AbstractButton button(final Container sidebar, final String name) {
    return all(sidebar)
        .filter(AbstractButton.class::isInstance)
        .map(AbstractButton.class::cast)
        .filter(b -> name.equals(b.getAccessibleContext().getAccessibleName()))
        .findFirst()
        .orElseThrow();
  }

  private static String title(final AbstractButton row) {
    return row.getAccessibleContext().getAccessibleName();
  }

  private static Stream<Component> all(final Container container) {
    return Stream.of(container.getComponents())
        .flatMap(
            c ->
                c instanceof Container inner
                    ? Stream.concat(Stream.of(c), all(inner))
                    : Stream.of(c));
  }
}
