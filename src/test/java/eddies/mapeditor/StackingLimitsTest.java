package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.attachments.PlayerAttachment;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.RulesXml.StackingLimit;

final class StackingLimitsTest {
  private final Game minimap = MapXmlTest.givenMinimap();

  private static Option givenOption(final String name, final String count, final String value) {
    return Option.builder().name(name).count(count).value(value).build();
  }

  private static List<String> givenUnitOptions(final Game game, final String unit) {
    return UnitsXml.options(game, unit).stream()
        .filter(o -> o.getName().endsWith("Limit"))
        .map(o -> o.getName() + "=" + o.getValue())
        .toList();
  }

  private static <T> Stream<T> givenAll(final Component component, final Class<T> type) {
    final Stream<T> self =
        type.isInstance(component) ? Stream.of(type.cast(component)) : Stream.empty();
    return component instanceof Container container
        ? Stream.concat(
            self, Stream.of(container.getComponents()).flatMap(c -> givenAll(c, type)))
        : self;
  }

  @DisplayName("A limit reads as TripleA does: count, whose units, the group; other forms do not")
  @Test
  void readsLimits() {
    assertThat(StackingLimit.read(givenOption("movementLimit", null, "3:allied")))
        .as("a unit type's limit")
        .contains(new StackingLimit(3, "allied", List.of()));
    assertThat(StackingLimit.read(givenOption("placementLimit", null, "2:owned:infantry:armour")))
        .as("a nation's limit for a group")
        .contains(new StackingLimit(2, "owned", List.of("infantry", "armour")));
    assertThat(StackingLimit.read(givenOption("placementLimit", "1", "total:all")))
        .as("the count in its own attribute, joined as TripleA joins it")
        .contains(new StackingLimit(1, "total", List.of("all")));
    assertThat(
            Stream.of("2", "2:territory", "-1:owned", "two:owned", "")
                .map(value -> StackingLimit.read(givenOption("attackingLimit", null, value))))
        .as("what TripleA refuses is no limit to show")
        .allMatch(Optional::isEmpty);
    assertThat(new StackingLimit(2, "owned", List.of("infantry", "armour")).written())
        .as("written back in the same form")
        .isEqualTo("2:owned:infantry:armour");
  }

  @DisplayName("A unit type's three limits are set, changed and taken out; TripleA reads them")
  @Test
  void setsUnitLimits(@TempDir final Path folder) {
    // when
    final Game placing =
        UnitsXml.withStackingLimit(
            minimap, "factory", "placementLimit", Optional.of(new StackingLimit(1, "total", List.of())));
    final Game all =
        UnitsXml.withStackingLimit(
            UnitsXml.withStackingLimit(
                placing, "factory", "movementLimit", Optional.of(new StackingLimit(2, "owned", List.of()))),
            "factory",
            "attackingLimit",
            Optional.of(new StackingLimit(3, "allied", List.of())));
    final Game changed =
        UnitsXml.withStackingLimit(
            all, "factory", "placementLimit", Optional.of(new StackingLimit(4, "allied", List.of())));
    final Game without =
        UnitsXml.withStackingLimit(changed, "factory", "movementLimit", Optional.empty());

    // then
    assertThat(givenUnitOptions(all, "factory"))
        .as("each as count:whose")
        .containsExactly("placementLimit=1:total", "movementLimit=2:owned", "attackingLimit=3:allied");
    assertThat(givenUnitOptions(without, "factory"))
        .as("one changed where it is, one taken out")
        .containsExactly("placementLimit=4:allied", "attackingLimit=3:allied");
    assertThat(UnitsXml.withStackingLimit(all, "factory", "movementLimit", UnitsXml.stackingLimit(all, "factory", "movementLimit")))
        .as("the same limit is no change")
        .isSameAs(all);
    final GameData loaded = RulesXmlTest.givenLoaded(all, folder);
    final var factory =
        loaded.getUnitTypeList().getUnitTypeOrThrow("factory").getUnitAttachment();
    assertThat(factory.getPlacementLimit().orElseThrow().getFirst()).as("placing").isEqualTo(1);
    assertThat(factory.getMovementLimit().orElseThrow().getSecond()).as("moving").isEqualTo("owned");
    assertThat(factory.getAttackingLimit().orElseThrow().getFirst()).as("attacking").isEqualTo(3);
  }

  @DisplayName("A limit the section cannot show stays as written, in More options")
  @Test
  void keepsUnreadLimit() {
    // given: a limit as only a foreach variable could make it read
    final Game odd =
        UnitsXml.withNewOption(
            minimap, "factory", givenOption("placementLimit", null, "$Count$:owned"));
    final Game read =
        UnitsXml.withNewOption(minimap, "factory", givenOption("placementLimit", null, "2:owned"));

    // then
    assertThat(UnitsXml.stackingLimit(odd, "factory", "placementLimit"))
        .as("not shown as a limit")
        .isEmpty();
    assertThat(
            UnitsXml.withStackingLimit(
                odd, "factory", "placementLimit", Optional.of(new StackingLimit(1, "owned", List.of()))))
        .as("nor written over")
        .isSameAs(odd);
    assertThat(UnitsEditor.moreOptions(odd, "factory"))
        .as("it stays under More options")
        .extracting(Option::getValue)
        .contains("$Count$:owned");
    assertThat(UnitsEditor.moreOptions(read, "factory"))
        .as("one that reads shows under Stacking limits only")
        .extracting(Option::getName)
        .doesNotContain("placementLimit");
  }

  @DisplayName("A limit with its number as the option's count shows once, and keeps that form")
  @Test
  void keepsCountForm() {
    // given: as most maps write it
    final Game counted =
        UnitsXml.withNewOption(minimap, "factory", givenOption("movementLimit", "3", "allied"));

    // when
    final Game changed =
        UnitsXml.withStackingLimit(
            counted,
            "factory",
            "movementLimit",
            Optional.of(new StackingLimit(5, "total", List.of())));

    // then
    assertThat(UnitsXml.stackingLimit(counted, "factory", "movementLimit"))
        .as("read under Stacking limits")
        .contains(new StackingLimit(3, "allied", List.of()));
    assertThat(UnitsEditor.moreOptions(counted, "factory"))
        .as("so not again under More options")
        .extracting(Option::getName)
        .doesNotContain("movementLimit");
    assertThat(UnitsXml.optionAt(changed, "factory", "movementLimit", 0).orElseThrow())
        .as("changed, the number stays the count")
        .extracting(Option::getCount, Option::getValue)
        .containsExactly("5", "total");
  }

  @DisplayName("Units shows the three limits; a number typed there writes the option, one step")
  @Test
  void unitsEditorSetsLimit() {
    // given
    final MapProject project = new MapProject(Path.of("minimap.xml"), minimap, MapFiles.none());
    final UnitsEditor editor = new UnitsEditor(project);
    final String unit = MapXml.unitTypes(minimap).getFirst();
    final JLabel placing =
        givenAll(editor.page(), JLabel.class)
            .filter(l -> "When placing".equals(l.getText()))
            .findFirst()
            .orElseThrow();

    // when
    ((JSpinner) placing.getLabelFor()).setValue(2);

    // then
    assertThat(UnitsXml.stackingLimit(project.game(), unit, "placementLimit"))
        .as("at most 2, counting its owner's, the first choice")
        .contains(new StackingLimit(2, "owned", List.of()));
    project.undo();
    assertThat(UnitsXml.hasOption(project.game(), unit, "placementLimit"))
        .as("one undo takes it out again")
        .isFalse();
  }

  @DisplayName("A nation's limits show as rows of their own, several at once, not as raw rules")
  @Test
  void nationsEditorShowsLimits(@TempDir final Path folder) {
    // given
    final String nation = MapXml.nations(minimap).getFirst();
    final Game limited =
        RulesXml.withPlayerOptionAdded(
            RulesXml.withPlayerOptionAdded(
                RulesXml.withPlayerOptionAdded(
                    minimap, nation, "placementLimit", "1:owned:factory"),
                nation,
                "movementLimit",
                "3:allied:fighter:bomber"),
            nation,
            "attackingLimit",
            "oddly written");
    final MapProject project = new MapProject(Path.of("minimap.xml"), limited, MapFiles.none());

    // when
    final NationsEditor editor = new NationsEditor(project);
    editor.choose(MapXml.nations(project.game()).getFirst());

    // then
    assertThat(givenAll(editor.page(), JLabel.class).map(JLabel::getText))
        .as("the groups of both limits that read")
        .contains("factory", "fighter and bomber");
    assertThat(NationsEditor.moreCount(limited, nation))
        .as("More options counts only the limit it still shows as a raw rule")
        .isEqualTo(1);

    // when: the second limit counts all units
    @SuppressWarnings("unchecked")
    final JComboBox<String> counting =
        givenAll(editor.page(), JComboBox.class)
            .filter(box -> "Counting".equals(box.getAccessibleContext().getAccessibleName()))
            .skip(1)
            .findFirst()
            .orElseThrow();
    counting.setSelectedIndex(2);

    // then
    assertThat(RulesXml.playerOptions(project.game(), nation))
        .as("only that limit changed; the one that does not read is kept as written")
        .extracting(o -> o.getName() + "=" + o.getValue())
        .containsExactly(
            "placementLimit=1:owned:factory",
            "movementLimit=3:total:fighter:bomber",
            "attackingLimit=oddly written");
    final Game loadable = RulesXml.withoutPlayerOptionAt(project.game(), nation, 2);
    final PlayerAttachment loaded =
        PlayerAttachment.get(
            RulesXmlTest.givenLoaded(loadable, folder).getPlayerList().getPlayerId(nation));
    assertThat(loaded.getMovementLimit()).as("TripleA reads the changed limit").hasSize(1);
    assertThat(loaded.getPlacementLimit()).as("and the other one").hasSize(1);
  }

  @DisplayName("A limit made a limit for another moment keeps its place; a name's spelling stays")
  @Test
  void changesWhenALimitHolds() {
    // given
    final String nation = MapXml.nations(minimap).getFirst();
    final Game limited =
        RulesXml.withPlayerOptionAdded(minimap, nation, "placementLimit", "1:owned:factory");
    final int row = RulesXml.playerOptions(limited, nation).size() - 1;

    // when
    final Game moving =
        RulesXml.withPlayerOptionAt(limited, nation, row, "movementLimit", "1:owned:factory");

    // then
    assertThat(RulesXml.playerOptions(moving, nation).get(row))
        .as("the same row, now a movement limit")
        .extracting(o -> o.getName() + "=" + o.getValue())
        .isEqualTo("movementLimit=1:owned:factory");
    assertThat(RulesXml.isGroupLimit(RulesXml.playerOptions(moving, nation).get(row)))
        .as("shown under Stacking limits")
        .isTrue();
  }

  @DisplayName("The Nations tool offers + Add limit, and no limit among the rules to add")
  @Test
  void nationsEditorOffersAddLimit() {
    final NationsEditor editor =
        new NationsEditor(new MapProject(Path.of("minimap.xml"), minimap, MapFiles.none()));
    editor.choose(MapXml.nations(minimap).getFirst());

    assertThat(givenAll(editor.page(), JButton.class).map(JButton::getText))
        .as("the button of the Stacking limits section, shown without opening More options")
        .contains("+ Add limit");
  }
}
