package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import eddies.mapeditor.MapSearch.Hit;
import eddies.mapeditor.MapTexts.Kind;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.Game;

final class MapSearchTest {
  private static final String TOOLTIP = "tooltip.unit.infantry";

  private static MapTexts givenTexts() {
    return new MapTexts(Map.of(Kind.TOOLTIPS, TOOLTIP + "=Holds the line\n"));
  }

  private static Hit hit(final List<Hit> hits, final String tool, final String name) {
    return hit(hits, h -> h.tool().equals(tool) && h.name().equals(name));
  }

  private static Hit hit(final List<Hit> hits, final Predicate<Hit> wanted) {
    return hits.stream().filter(wanted).findFirst().orElseThrow();
  }

  @DisplayName("Find in the map lists each thing under the tool that edits it, with its kind")
  @Test
  void findsEachThingInItsTool() {
    // given
    final Game game = GameRulesXmlTest.givenG40();

    // when
    final List<Hit> hits = MapSearch.hits(game, givenTexts());

    // then
    assertThat(hit(hits, "Territories", "Germany").kind()).as("land").isEqualTo("territory");
    assertThat(hit(hits, "Territories", "112 Sea Zone").kind()).as("sea").isEqualTo("sea zone");
    assertThat(hit(hits, "Nations", "Germans").item()).as("a nation").isEqualTo("Germans");
    assertThat(hit(hits, "Units", "infantry").kind()).as("a unit type").isEqualTo("unit type");
    assertThat(hit(hits, "Production", "buyInfantry").item())
        .as("a rule opens the list that sells it")
        .isEqualTo("production");
    assertThat(hit(hits, RulesEditors.GAME_OPTIONS, "Low Luck").kind())
        .as("a game option")
        .isEqualTo("option");
    assertThat(hits)
        .as("triggers in Triggers, objectives in Objectives, conditions in Attachments")
        .anyMatch(h -> h.tool().equals(TriggersEditor.TITLE) && h.name().startsWith("trigger"))
        .anyMatch(
            h -> h.tool().equals(ObjectivesEditor.TITLE) && h.name().startsWith("objective"))
        .anyMatch(
            h ->
                h.tool().equals("Attachments")
                    && h.name().startsWith("conditionAttachment")
                    && h.item().equals(h.name() + " of " + h.kind().split(" of ")[1]));
    assertThat(hits)
        .as("a political action in Actions, by its row there")
        .anyMatch(h -> h.tool().equals("Actions") && h.item().endsWith(")"));
    assertThat(hit(hits, h -> h.item().equals(TOOLTIP)).tool())
        .as("a text in Texts")
        .isEqualTo(TextsEditor.TITLE);
  }

  @DisplayName("A search for a number finds what says it, not every row of that place in the list")
  @Test
  void numberFindsOnlyWhatSaysIt() throws Exception {
    // given
    final List<Hit> hits = MapSearch.hits(GameRulesXmlTest.givenG40(), MapTexts.none());
    final List<String> shown = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final Container root = MapSearch.newSearch(hits).pane().root();
          all(root)
              .filter(JTextField.class::isInstance)
              .map(JTextField.class::cast)
              .findFirst()
              .orElseThrow()
              .setText("112");
          final JList<?> list =
              all(root).filter(JList.class::isInstance).map(JList.class::cast).findFirst().get();
          for (int i = 0; i < list.getModel().getSize(); i++) {
            shown.add(String.valueOf(list.getModel().getElementAt(i)));
          }
        });

    // then
    // an item's row ends in its place, in private use characters; the others are the headers
    final List<String> items =
        shown.stream().filter(row -> row.charAt(row.length() - 1) >= (char) 0xE000).toList();
    assertThat(items)
        .as("the sea zone and what is attached to it, nothing else")
        .anyMatch(row -> row.startsWith("112 Sea Zone"))
        .allMatch(row -> row.contains("112"));
  }

  @DisplayName("Open shows the tool with the item chosen, for each kind of thing")
  @Test
  void opensTheItemInItsTool() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "the window needs a screen");
    // given
    final Game game = GameRulesXmlTest.givenG40();
    final List<Hit> hits = MapSearch.hits(game, givenTexts());
    final List<Hit> wanted =
        List.of(
            hit(hits, "Territories", "Germany"),
            hit(hits, "Nations", "Italians"),
            hit(hits, "Units", "armour"),
            hit(hits, RulesEditors.GAME_OPTIONS, "Low Luck"),
            hit(hits, h -> h.kind().equals("purchase list") && h.item().equals("production")),
            hit(hits, h -> h.tool().equals("Actions")),
            hit(hits, h -> h.tool().equals("Attachments")),
            hit(hits, h -> h.tool().equals(ObjectivesEditor.TITLE)),
            hit(hits, h -> h.item().equals(TOOLTIP)));
    final Map<String, Boolean> seen = new LinkedHashMap<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapMakerWindow window = new MapMakerWindow();
          try {
            final MapProject project = MapProject.newEmpty();
            project.edit(empty -> game, none -> givenTexts());
            window.showProject(project);
            for (final Hit hit : wanted) {
              MapMakerWindow.openTool(window.getContentPane(), hit.tool(), hit.item());
              seen.put(hit.tool(), showsText(window.getContentPane(), hit.name()));
            }
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(seen)
        .as("each tool shows the item's name")
        .containsOnlyKeys(wanted.stream().map(Hit::tool).toList())
        .doesNotContainValue(false);
  }

  @DisplayName("A name a tool does not have changes nothing in it, eg a territory in Nations")
  @Test
  void otherNamesChangeNothing() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "the window needs a screen");
    // given
    final Game game = GameRulesXmlTest.givenG40();
    final Map<String, String> chosen =
        Map.of("Nations", "Italians", "Units", "armour", "Production", "production");
    final Map<String, Boolean> seen = new LinkedHashMap<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapMakerWindow window = new MapMakerWindow();
          try {
            final MapProject project = MapProject.newEmpty();
            project.edit(empty -> game);
            window.showProject(project);
            chosen.forEach(
                (tool, item) -> {
                  MapMakerWindow.openTool(window.getContentPane(), tool, item);
                  MapMakerWindow.openTool(window.getContentPane(), tool, "Germany");
                  seen.put(tool, showsText(window.getContentPane(), shownName(tool, item)));
                });
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(seen).as("each keeps what was chosen").doesNotContainValue(false);
  }

  private static String shownName(final String tool, final String item) {
    return Optional.of(tool)
        .filter("Production"::equals)
        .map(
            any ->
                ProductionXml.listTitles(GameRulesXmlTest.givenG40())
                    .getOrDefault(item, item))
        .orElse(item);
  }

  private static boolean showsText(final Container in, final String text) {
    return all(in)
        // the window is not on screen: shown is visible up to it, eg the tool's card
        .filter(c -> visibleUpTo(c, in))
        .anyMatch(
            c ->
                (c instanceof JLabel l && String.valueOf(l.getText()).contains(text))
                    || (c instanceof JTextComponent t && t.getText().contains(text)));
  }

  private static boolean visibleUpTo(final Component component, final Container top) {
    for (Component c = component; c != null && c != top; c = c.getParent()) {
      if (!c.isVisible()) {
        return false;
      }
    }
    return true;
  }

  private static Stream<Component> all(final Container in) {
    final List<Component> all = new ArrayList<>();
    for (final Component c : in.getComponents()) {
      all.add(c);
      if (c instanceof Container inner) {
        all.addAll(all(inner).toList());
      }
    }
    return all.stream();
  }
}
