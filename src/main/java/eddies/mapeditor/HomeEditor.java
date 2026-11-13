package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapGuide.Next;
import eddies.mapeditor.MapGuide.Step;
import eddies.mapeditor.MapProject.NewMap;
import eddies.mapeditor.MapTexts.Kind;
import eddies.mapeditor.Theme.Status;

/**
 * The map at a glance: the guide (the next step with its one main action, the steps to a playable
 * map, the problems, each with a way to where it is fixed) and, beside it when there is room, an
 * overview: the map small and a tile per tool with what the map has there.
 */
final class HomeEditor implements Editor {
  private static final int LEFT_WIDTH = 540;
  // the overview beside the guide from about 1000 px, growing with the room up to its widest
  private static final int RIGHT_MIN_WIDTH = 420;
  private static final int RIGHT_MAX_WIDTH = 720;
  private static final int GUTTER = Theme.GAP_XL;
  private static final int TILE_COLUMNS = 3;
  // the tiles shown before "More tools": the ones most maps need
  private static final int MAIN_TILES = 8;
  // a tile's text at the narrowest overview: a third, less the margins and the glyph
  static final int TILE_TEXT_WIDTH = 92;
  private static final int SHOWN_PROBLEMS = 8;
  private static final int SHOWN_NOTES = 3;
  private static final int MARK_WIDTH = 16;

  private final MapProject project;
  private final Consumer<String> showTerritory;
  private final Consumer<String> openTool;
  private final Consumer<Step.Id> followStep;
  private final Runnable check;
  private final Runnable save;
  private final Runnable play;
  private final Form form = new Form(LEFT_WIDTH);
  private final JScrollPane page = SwingComponents.newJScrollPane(form);
  private final Form left = column(LEFT_WIDTH);
  private final Form right = column(RIGHT_MAX_WIDTH);
  private int rightWidth = RIGHT_MIN_WIDTH;
  private final MiniMap miniMap;
  private final List<String> welcome = new ArrayList<>();
  private List<Problem> problems;
  // worth a look, but no problems: never red, not counted
  private List<Problem> notes;
  private List<Problem> pictureNotes = List.of();
  private boolean checked;
  private boolean allProblemsShown;
  private boolean allNotesShown;
  private boolean finishedStepsShown;
  private boolean allTilesShown;
  // the tiles as counted, and the state they were counted for
  private List<Tile> tiles = List.of();
  private List<Object> tilesState = List.of();
  private int countings;
  // the nations' colors, made again when the overview's width changes
  private final JPanel nationsRow = new JPanel(new BorderLayout());
  private boolean nationsRefitQueued;
  private JComponent problemsHeading = new JPanel();
  // changed while another tool showed: filled when shown again, not on every edit there
  private boolean stale;

  /**
   * @param showTerritory opens a territory in Territories
   * @param openTool shows the tool of this title
   * @param followStep shows the step's tool with the step as its goal, {@link MapView#showStep}
   * @param check runs Check, the engine's too, which then calls {@link #setValidated}
   */
  HomeEditor(
      final MapProject project,
      final Consumer<String> showTerritory,
      final Consumer<String> openTool,
      final Consumer<Step.Id> followStep,
      final Runnable check,
      final Runnable save,
      final Runnable play) {
    this.project = project;
    this.showTerritory = showTerritory;
    this.openTool = openTool;
    this.followStep = followStep;
    this.check = check;
    this.save = save;
    this.play = play;
    page.setBorder(null);
    // in line with the tool's title and intro line above it
    form.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_XS, 0, Theme.GAP_L, Theme.GAP_L));
    miniMap = new MiniMap(project, showTerritory, RIGHT_MIN_WIDTH);
    nationsRow.setOpaque(false);
    problems = project.problems();
    findPictureNotes();
    project.onChange(
        () -> {
          problems = project.problems();
          if (project.hasUnsavedChanges()) {
            // not read from the folder for every change: when Home shows again
            withNotes();
          } else {
            // just saved, which writes pictures for new nations and unit types
            findPictureNotes();
          }
          checked = false;
          if (page.isShowing()) {
            fill();
          } else {
            stale = true;
          }
        });
    fill();
  }

  @Override
  public String title() {
    return "Home";
  }

  @Override
  public Glyph glyph() {
    return Glyph.OVERVIEW;
  }

  @Override
  public String hint() {
    return "Your map at a glance: what to do next and what still keeps it from being played.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    // a picture imported in another tool is written at once, with no change to hear of
    final List<Problem> before = notes;
    findPictureNotes();
    if (stale || !before.equals(notes)) {
      fill();
    }
  }

  /**
   * Looks in the map's folder for pictures the game would miss, of the nations and unit types the
   * saved game has, as a save writes plain ones for new ones.
   */
  private void findPictureNotes() {
    pictureNotes =
        MapChecks.pictureNotes(project.game(), project.savedGame(), project.getFiles());
    withNotes();
  }

  /** What is worth a look: territories apart, and the pictures last found missing. */
  private void withNotes() {
    notes =
        Stream.concat(MapChecks.notes(project.game()).stream(), pictureNotes.stream()).toList();
  }

  /** The problems of Check, including the engine's. */
  void setValidated(final List<Problem> validated) {
    problems = validated;
    checked = true;
    fill();
  }

  List<Problem> problems() {
    return problems;
  }

  /** Whether the problems are Check's, the engine's included, rather than the quick ones. */
  boolean checked() {
    return checked;
  }

  /** What the new-map wizard found and left out, shown in the next-step card. */
  void welcome(final NewMap made) {
    final Game game = made.project().game();
    if (made.project().getFiles().hasOutlines()) {
      final int seas =
          (int) MapXml.territories(game).stream().filter(t -> MapXml.isWater(game, t)).count();
      final int land = MapXml.territories(game).size() - seas;
      welcome.add(
          "Found "
              + SearchList.count(land, land, "territory", "territories")
              + " and "
              + SearchList.count(seas, seas, "sea zone", "sea zones")
              + ".");
    }
    if (!made.refusedNations().isEmpty()) {
      welcome.add(
          "Left out: "
              + String.join(", ", made.refusedNations())
              + ". A nation needs a name of its own, not a territory's or a unit's, without : <"
              + " or (. Add them in Nations.");
    }
    fill();
  }

  /** All problems, scrolled to; for the header's problems, after Home is shown. */
  void showProblems() {
    allProblemsShown = true;
    fill();
    SwingUtilities.invokeLater(
        () ->
            problemsHeading.scrollRectToVisible(
                new Rectangle(0, 0, problemsHeading.getWidth(), page.getViewport().getHeight())));
  }

  private void fill() {
    stale = false;
    form.clear();
    final Game game = project.game();
    final JLabel name = Theme.newTitle(project.name());
    // a long name is cut at the end; the tool tip has all of it
    name.setToolTipText(project.name());
    form.row(name);
    form.subtitle(stats(game));
    final List<Step> steps =
        checked && problems.isEmpty()
            ? MapGuide.stepsAfterCleanCheck(project)
            : MapGuide.steps(project);
    fillLeft(steps);
    fillRight(steps);
    final JPanel columns = new JPanel(new Columns());
    columns.setOpaque(false);
    columns.add(left);
    columns.add(right);
    form.row(columns);
    form.end();
  }

  private static String stats(final Game game) {
    final int territories = MapXml.territories(game).size();
    final int nations = MapXml.nations(game).size();
    final int units = MapXml.unitTypes(game).size();
    return SearchList.count(territories, territories, "territory", "territories")
        + " · "
        + SearchList.count(nations, nations, "nation", "nations")
        + " · "
        + SearchList.count(units, units, "kind of units", "kinds of units");
  }

  private void fillLeft(final List<Step> steps) {
    left.clear();
    left.row(Theme.newSectionLabel("Guide"));
    left.row(card());
    left.row((JComponent) Box.createVerticalStrut(Theme.GAP_M));
    final long done = steps.stream().filter(Step::done).count();
    if (done == steps.size() && !finishedStepsShown) {
      left.row(stepsToggle("▸ Steps to a playable map · all done", true));
    } else {
      left.row(
          done == steps.size()
              ? stepsToggle("▾ Steps to a playable map", false)
              : heading("Steps to a playable map", done + " of " + steps.size()));
      steps.forEach(step -> left.row(stepRow(step)));
    }
    fillProblems();
    fillNotes(steps);
    final JButton files =
        tertiary("What's in the map folder? See Files.", () -> openTool.accept("Files"));
    left.row(leftAligned(files, Theme.GAP_L));
  }

  /** The one main action: the next step, why, and the button that does it. */
  private JComponent card() {
    final Next next =
        checked
            ? MapGuide.nextStepAfterCheck(project, problems)
            : MapGuide.nextStep(project, problems);
    final int textWidth = LEFT_WIDTH - 2 * Theme.GAP_L;
    final Form card = new Form(textWidth);
    Theme.setCardStyle(card);
    card.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_L, Theme.GAP_L, Theme.GAP_L, Theme.GAP_L));
    final JLabel label = Theme.newSecondaryLabel("Next step");
    label.setForeground(Theme.getAccentTextColor());
    card.row(label);
    card.row(Theme.newTitle(next.title()));
    card.row(note(next.why(), textWidth));
    welcome.forEach(line -> card.row(note(line, textWidth)));
    final JButton go =
        switch (next.kind()) {
          case GO_TO_TOOL ->
              button("Go to " + next.tool(), () -> next.step().ifPresent(followStep));
          case FIX_PROBLEMS -> button("Show the first problem", () -> show(problems.getFirst()));
          case CHECK -> button("Check (F6)", check);
          case SAVE -> button("Save (Ctrl+S)", save);
          case PLAY -> button("Play (F7)", play);
        };
    Theme.setPrimaryButtonStyle(go);
    card.row(leftAligned(go, Theme.GAP_S));
    if (next.kind() == Next.Kind.PLAY) {
      card.row(note("Share it: Map ▾ › Export as zip (F8)", textWidth));
    }
    return card;
  }

  private JButton stepsToggle(final String text, final boolean open) {
    final JButton toggle =
        tertiary(
            text,
            () -> {
              finishedStepsShown = open;
              fill();
            });
    toggle.setHorizontalAlignment(SwingConstants.LEADING);
    toggle
        .getAccessibleContext()
        .setAccessibleName(text.substring(2) + (open ? ", collapsed" : ", expanded"));
    return toggle;
  }

  private JComponent stepRow(final Step step) {
    final Status status =
        switch (step.state()) {
          case DONE -> Status.DONE;
          case TODO -> Status.TODO;
          case FAILING -> Status.PROBLEM;
        };
    final JLabel text = new JLabel(step.text());
    if (step.done()) {
      text.setForeground(Theme.getSecondaryTextColor());
    }
    text.setToolTipText(step.why());
    final JButton tool =
        step.tool().equals(MapGuide.CHECK)
            ? tertiary(MapGuide.CHECK, check)
            : tertiary(step.tool(), () -> followStep.accept(step.id()));
    tool.getAccessibleContext().setAccessibleName(step.tool() + ": " + step.text());
    return row(mark(status), text, tool);
  }

  private void fillProblems() {
    problemsHeading =
        heading("Problems", problems.isEmpty() ? "" : String.valueOf(problems.size()));
    left.row(problemsHeading);
    if (problems.isEmpty()) {
      final JLabel none =
          new JLabel(
              checked
                  ? "No problems: TripleA loads this game."
                  : "No problems in the quick checks. Check (F6) also loads the game.");
      final JPanel empty = new JPanel();
      empty.setOpaque(false);
      left.row(row(mark(Status.DONE), none, empty));
      return;
    }
    final int shown =
        allProblemsShown ? problems.size() : Math.min(SHOWN_PROBLEMS, problems.size());
    problems
        .subList(0, shown)
        .forEach(problem -> left.row(problemRow(problem)));
    if (shown < problems.size()) {
      left.row(
          leftAligned(
              tertiary(
                  "Show all " + problems.size(),
                  () -> {
                    allProblemsShown = true;
                    fill();
                  }),
              Theme.GAP_XS));
    }
    if (!checked) {
      left.row(
          note(
              "Found by the quick checks. Check (F6) also loads the game as TripleA does.",
              LEFT_WIDTH));
      final JButton checkNow = button("Check now", check);
      Theme.setSecondaryButtonStyle(checkNow);
      left.row(leftAligned(checkNow, Theme.GAP_XS));
    }
  }

  /**
   * What is worth a look but keeps no map from being played, eg a territory without neighbours:
   * calm and apart from the problems; not while the connections step still says it.
   */
  private void fillNotes(final List<Step> steps) {
    final boolean connected =
        steps.stream().anyMatch(s -> s.id() == Step.Id.CONNECTIONS && s.done());
    if (notes.isEmpty() || !connected) {
      return;
    }
    left.row(heading("Worth a look", String.valueOf(notes.size())));
    left.row(note("No problems: fine if you meant them.", LEFT_WIDTH));
    final int shown = allNotesShown ? notes.size() : Math.min(SHOWN_NOTES, notes.size());
    notes.subList(0, shown).forEach(note -> left.row(noteRow(note)));
    if (shown < notes.size()) {
      left.row(
          leftAligned(
              tertiary(
                  "Show all " + notes.size(),
                  () -> {
                    allNotesShown = true;
                    fill();
                  }),
              Theme.GAP_XS));
    }
  }

  /** The notes shown under "Worth a look", for tests. */
  List<Problem> notes() {
    return notes;
  }

  /** The map small with the nations' colors, then a tile per tool. */
  private void fillRight(final List<Step> steps) {
    right.clear();
    right.row(Theme.newSectionLabel("Overview"));
    if (project.getFiles().hasOutlines()) {
      miniMap.setProblems(
          problems.stream()
              .flatMap(problem -> problem.territory().stream())
              .collect(Collectors.toSet()));
      right.row(miniMap);
      right.row(nationsRow);
      fitNations();
    } else {
      right.row(noTerritories());
    }
    right.row((JComponent) Box.createVerticalStrut(Theme.GAP_S));
    final Map<String, Long> problemsByTool =
        problems.stream()
            .flatMap(p -> p.territory().map(t -> "Territories").or(p::tool).stream())
            .collect(Collectors.groupingBy(tool -> tool, Collectors.counting()));
    final List<Tile> tiles = tiles();
    final JPanel grid = new JPanel(new GridLayout(0, TILE_COLUMNS, Theme.GAP_S, Theme.GAP_S));
    grid.setOpaque(false);
    final List<Tile> main = mainTiles(tiles, steps);
    for (final Tile tile : allTilesShown ? tiles : main) {
      grid.add(tileButton(tile, problemsByTool.getOrDefault(tile.tool(), 0L), steps));
    }
    right.row(grid);
    // the folded tiles' problems, else they would not show
    final long foldedProblems =
        tiles.stream()
            .filter(tile -> !main.contains(tile))
            .mapToLong(tile -> problemsByTool.getOrDefault(tile.tool(), 0L))
            .sum();
    final JButton more =
        tertiary(
            allTilesShown
                ? "Fewer tools ▴"
                : "More tools ▾" + (foldedProblems == 0 ? "" : " · ! " + foldedProblems),
            () -> {
              allTilesShown = !allTilesShown;
              fill();
            });
    more.getAccessibleContext()
        .setAccessibleName(
            "More tools"
                + (allTilesShown ? ", expanded" : ", collapsed")
                + (allTilesShown || foldedProblems == 0
                    ? ""
                    : ", " + problemCount(foldedProblems)));
    right.row(leftAligned(more, Theme.GAP_XS));
  }

  /**
   * The tiles before "More tools": the ones most maps need; while the map is new (no nations or
   * no units yet, which every finished map has) only the tools of its steps, as the others'
   * numbers, eg 32 turn order steps, are the template's, not the map maker's.
   */
  private static List<Tile> mainTiles(final List<Tile> tiles, final List<Step> steps) {
    final List<Step.Id> start = List.of(Step.Id.NATIONS, Step.Id.UNITS);
    if (steps.stream().noneMatch(s -> !s.done() && start.contains(s.id()))) {
      return tiles.subList(0, MAIN_TILES);
    }
    final List<String> stepTools = steps.stream().map(Step::tool).toList();
    return tiles.stream().filter(tile -> stepTools.contains(tile.tool())).toList();
  }

  /** No outlines yet: a way to make them, not the main action, which the next step has. */
  private JComponent noTerritories() {
    final JPanel box = new JPanel(new BorderLayout());
    Theme.setCardStyle(box);
    box.add(
        Panels.empty(
            Glyph.TERRITORIES, "No territories yet", "Make them from a picture of the map."),
        BorderLayout.CENTER);
    final JButton make =
        tertiary("Make territories ›", () -> followStep.accept(Step.Id.TERRITORIES));
    final JPanel centred = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
    centred.setOpaque(false);
    centred.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_L, 0));
    centred.add(make);
    box.add(centred, BorderLayout.SOUTH);
    return box;
  }

  /** The nations' colors, or that nations show them, at the overview's width. */
  private void fitNations() {
    final List<String> nations = MapXml.nations(project.game());
    nationsRow.removeAll();
    nationsRow.add(
        nations.isEmpty()
            ? note("Add nations to see who owns what.", rightWidth)
            : nationChips(nations));
    nationsRow.revalidate();
    nationsRow.repaint();
  }

  private static String problemCount(final long count) {
    return count + (count == 1 ? " problem" : " problems");
  }

  /** The nations in their colors, as many as fit in a line, then how many more. */
  private JComponent nationChips(final List<String> nations) {
    final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    chips.setOpaque(false);
    final JLabel more = Theme.newSecondaryLabel("+" + nations.size() + " more");
    int used = 0;
    int shown = 0;
    for (final String nation : nations) {
      final JLabel chip =
          new JLabel(
              nation, new MapLegend.Swatch(project.nationColor(nation)), SwingConstants.LEADING);
      chip.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Theme.GAP_M));
      final int room =
          shown == nations.size() - 1 ? rightWidth : rightWidth - more.getPreferredSize().width;
      if (used + chip.getPreferredSize().width > room) {
        break;
      }
      chips.add(chip);
      used += chip.getPreferredSize().width;
      shown++;
    }
    if (shown < nations.size()) {
      more.setText("+" + (nations.size() - shown) + " more");
      more.setToolTipText(String.join(", ", nations.subList(shown, nations.size())));
      chips.add(more);
    }
    return chips;
  }

  /**
   * A tool on Home: what the map has there in one number (or ✓ / ○) and a short line.
   *
   * @param tool the tool's title, which a click opens
   * @param spoken the number with its noun for screen readers, eg "5 kinds"; empty for ✓ / ○
   */
  private record Tile(String tool, Glyph glyph, String number, String spoken, String line) {}

  /**
   * The tiles, the {@link #MAIN_TILES} most maps need first; counted again only after a change, as
   * a big map has 35,000 attachments to count.
   */
  private List<Tile> tiles() {
    final List<Object> state =
        List.of(project.game(), project.getFiles(), project.texts(), project.getFolder());
    // game, files and texts by identity, as each step makes new ones
    final boolean same =
        !tilesState.isEmpty()
            && tilesState.get(0) == state.get(0)
            && tilesState.get(1) == state.get(1)
            && tilesState.get(2) == state.get(2)
            && tilesState.get(3).equals(state.get(3));
    if (!same) {
      tilesState = state;
      tiles = countTiles(project.game());
      countings++;
    }
    return tiles;
  }

  /** How often the tiles were counted, for tests. */
  int countings() {
    return countings;
  }

  private List<Tile> countTiles(final Game game) {
    final int territories = MapXml.territories(game).size();
    final int seas = MapXml.seaZones(game).size();
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    final int triggers = count(attachments, TriggerXml.TRIGGER);
    final int conditions = count(attachments, TriggerXml.CONDITION);
    final boolean notes = !MapXml.notes(game).isBlank();
    final int frontiers = ProductionXml.frontiers(game).size();
    final MapTexts texts = project.texts();
    final int textEntries =
        Stream.of(Kind.values())
            .filter(kind -> kind != Kind.SOUNDS)
            .mapToInt(kind -> texts.entries(kind).size())
            .sum();
    final boolean saved = project.getFolder().isPresent();
    final List<String> nations = MapXml.nations(game);
    final long playing = nations.stream().filter(n -> MapXml.takesTurns(game, n)).count();
    final long withUnits =
        MapXml.placements(game).stream().map(UnitPlacement::getTerritory).distinct().count();
    // shortcut: lines short enough for one line at TILE_TEXT_WIDTH; a longer one wraps
    return List.of(
        tile(
            "Territories",
            Glyph.TERRITORIES,
            territories,
            "territory",
            "territories",
            SearchList.count(seas, seas, "sea zone", "sea zones")),
        tile(
            "Connections",
            Glyph.CONNECTIONS,
            MapXml.connections(game).size(),
            "connection",
            "connections",
            "neighbour pairs"),
        tile(
            "Units",
            Glyph.UNITS,
            MapXml.unitTypes(game).size(),
            "kind",
            "kinds",
            withUnits + " with units"),
        tile(
            "Turn order",
            Glyph.TURN_ORDER,
            RulesXml.steps(game).size(),
            "step",
            "steps",
            "steps in a round"),
        tile(
            "Triggers",
            Glyph.TRIGGERS,
            triggers,
            "trigger",
            "triggers",
            SearchList.count(conditions, conditions, "condition", "conditions")),
        new Tile("Game notes", Glyph.NOTES, notes ? "✓" : "○", "", notes ? "written" : "empty"),
        tile(
            "Production",
            Glyph.PRODUCTION,
            ProductionXml.rules(game).size(),
            "rule",
            "rules",
            SearchList.count(frontiers, frontiers, "frontier", "frontiers")),
        tile(
            "Nations",
            Glyph.NATIONS,
            nations.size(),
            "nation",
            "nations",
            playing + (playing == 1 ? " takes turns" : " take turns")),
        tile(
            "Game options",
            Glyph.OPTIONS,
            GameRulesXml.options(game).size(),
            "option",
            "options",
            "options"),
        tile("Canals", Glyph.CANALS, CanalXml.canals(game).size(), "canal", "canals", "canals"),
        tile(
            "Terrain effects",
            Glyph.EFFECTS,
            TerritoryEffectsXml.effects(game).size(),
            "terrain effect",
            "terrain effects",
            "terrain effects"),
        tile(
            "Map look",
            Glyph.MAP_LOOK,
            project.getFiles().properties().size(),
            "map setting",
            "map settings",
            "map settings"),
        tile(
            "Politics",
            Glyph.POLITICS,
            RulesXml.relationshipTypes(game).size(),
            "relationship",
            "relationships",
            "relationships"),
        tile(
            "Technology",
            Glyph.TECHNOLOGY,
            TechXml.techNames(game).size(),
            "technology",
            "technologies",
            "technologies"),
        tile(
            "Actions",
            Glyph.ACTIONS,
            ActionsXml.actions(game).size(),
            "action",
            "actions",
            "political & user"),
        tile("Texts", Glyph.TEXTS, textEntries, "text", "texts", "objectives, tips"),
        tile(
            "Sounds",
            Glyph.SOUNDS,
            texts.entries(Kind.SOUNDS).size(),
            "sound",
            "sounds",
            "sounds"),
        tile(
            "Attachments",
            Glyph.ATTACHMENTS,
            attachments.size(),
            "attachment",
            "attachments",
            "attachments"),
        new Tile(
            "Files",
            Glyph.FILES,
            saved ? "✓" : "○",
            "",
            saved ? "saved" : "not saved yet"));
  }

  private static Tile tile(
      final String tool,
      final Glyph glyph,
      final int count,
      final String noun,
      final String nounPlural,
      final String line) {
    return new Tile(
        tool, glyph, String.valueOf(count), SearchList.count(count, count, noun, nounPlural), line);
  }

  private static int count(final List<Attachment> attachments, final String kind) {
    return (int) attachments.stream().filter(a -> kind.equals(AttachmentsXml.kind(a))).count();
  }

  /**
   * The tile's button with a red count of its tool's problems; a click follows the tool's first
   * step not done yet, else opens the tool.
   */
  private JButton tileButton(final Tile tile, final long problemCount, final List<Step> steps) {
    final String badge =
        problemCount == 0
            ? ""
            : "&nbsp; <font color='"
                + Theme.convertColorToHex(Theme.getDangerColor())
                + "'><b>! "
                + problemCount
                + "</b></font>";
    final JButton button =
        new JButton(
            "<html><div style='width:"
                + Theme.htmlWidth(TILE_TEXT_WIDTH)
                + "'>"
                + SearchList.escape(tile.tool())
                + "<br><font size='+2'><b>"
                + tile.number()
                + "</b></font>"
                + badge
                + "<br><font color='"
                + Theme.convertColorToHex(Theme.getSecondaryTextColor())
                + "'>"
                + SearchList.escape(tile.line())
                + "</font></div></html>",
            MakerIcon.small(tile.glyph()));
    button.putClientProperty(FlatClientProperties.STYLE, "margin: 8,8,8,8");
    button.setHorizontalAlignment(SwingConstants.LEADING);
    button.setVerticalAlignment(SwingConstants.TOP);
    button.setVerticalTextPosition(SwingConstants.TOP);
    button.setToolTipText(tile.line());
    // eg "Units: 5 kinds, 3 with units", "Canals: 2 canals", "Game notes: written"
    final String said =
        tile.spoken().isEmpty()
            ? tile.line()
            : tile.spoken().endsWith(tile.line())
                ? tile.spoken()
                : tile.spoken() + ", " + tile.line();
    button
        .getAccessibleContext()
        .setAccessibleName(
            tile.tool()
                + ": "
                + said
                + (problemCount == 0 ? "" : ", " + problemCount(problemCount)));
    final Optional<Step> todo =
        steps.stream().filter(s -> !s.done() && s.tool().equals(tile.tool())).findFirst();
    button.addActionListener(
        e ->
            todo.ifPresentOrElse(
                step -> followStep.accept(step.id()), () -> openTool.accept(tile.tool())));
    return button;
  }

  /** A note's Show opens its tool, eg Connections, with its territory chosen there. */
  private JComponent noteRow(final Problem note) {
    final JButton show =
        tertiary(
            "Show",
            () ->
                MapMakerWindow.openTool(
                    page, note.tool().orElse("Territories"), note.territory().orElse(null)));
    show.getAccessibleContext().setAccessibleName("Show: " + note.text());
    final int textWidth = LEFT_WIDTH - MARK_WIDTH - 2 * Theme.GAP_S - show.getPreferredSize().width;
    return row(mark(Status.TODO), wrapped(note.text(), textWidth), show);
  }

  private JComponent problemRow(final Problem problem) {
    final JComponent action;
    if (problem.territory().isPresent()) {
      action = tertiary("Show", () -> show(problem));
    } else if (problem.tool().isPresent()) {
      action = tertiary("Open " + problem.tool().get(), () -> show(problem));
    } else {
      action = new JPanel();
      action.setOpaque(false);
    }
    action.getAccessibleContext().setAccessibleName("Show: " + problem.text());
    final int textWidth =
        LEFT_WIDTH - MARK_WIDTH - 2 * Theme.GAP_S - action.getPreferredSize().width;
    return row(mark(Status.PROBLEM), wrapped(problem.text(), textWidth), action);
  }

  /** Where the problem is fixed: its territory, else its tool, else the list of problems. */
  private void show(final Problem problem) {
    if (problem.territory().isPresent()) {
      showTerritory.accept(problem.territory().get());
    } else if (problem.tool().isPresent()) {
      openTool.accept(problem.tool().get());
    } else {
      showProblems();
    }
  }

  private static Form column(final int width) {
    final Form column = new Form(width);
    column.setBorder(BorderFactory.createEmptyBorder());
    column.setOpaque(false);
    return column;
  }

  /** A section's heading with a count on the right, a line above it as in a form. */
  private static JComponent heading(final String text, final String count) {
    final JPanel heading = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    heading.setOpaque(false);
    heading.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.getSeparatorColor()),
            BorderFactory.createEmptyBorder(Theme.GAP_M, 0, Theme.GAP_XS, 0)));
    heading.add(Theme.newSectionLabel(text), BorderLayout.CENTER);
    heading.add(Theme.newSecondaryLabel(count), BorderLayout.EAST);
    return heading;
  }

  /** ✓, ○ or ! in its color, as wide for each so the texts beside them line up. */
  private static JLabel mark(final Status status) {
    final JLabel mark = new JLabel(status.mark());
    mark.setForeground(status.color());
    mark.setHorizontalAlignment(SwingConstants.CENTER);
    mark.setVerticalAlignment(SwingConstants.TOP);
    mark.setPreferredSize(new Dimension(MARK_WIDTH, mark.getPreferredSize().height));
    mark.getAccessibleContext()
        .setAccessibleName(
            switch (status) {
              case DONE -> "done";
              case TODO -> "not done yet";
              case PROBLEM, WARNING -> "needs fixing";
            });
    return mark;
  }

  private static JLabel note(final String text, final int width) {
    final JLabel note = wrapped(text, width);
    note.setForeground(Theme.getSecondaryTextColor());
    return note;
  }

  /**
   * A text that wraps at this width, as high as its lines; measured, as an HTML width in pixels
   * comes out about a third wider.
   */
  private static JLabel wrapped(final String text, final int width) {
    final JLabel label = new JLabel("<html>" + SearchList.escape(text) + "</html>");
    final View view = (View) label.getClientProperty(BasicHTML.propertyKey);
    view.setSize(width, 0);
    label.setPreferredSize(
        new Dimension(width, (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS))));
    // the label measures its text at one line's width before painting it wrapped: top, not cut
    label.setVerticalAlignment(SwingConstants.TOP);
    return label;
  }

  private static JPanel row(final JLabel mark, final JLabel text, final JComponent action) {
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.setOpaque(false);
    row.add(mark, BorderLayout.WEST);
    row.add(text, BorderLayout.CENTER);
    // at the top and its own size, also beside a text of two lines
    final JPanel top = new JPanel(new BorderLayout());
    top.setOpaque(false);
    top.add(action, BorderLayout.NORTH);
    row.add(top, BorderLayout.EAST);
    return row;
  }

  private static JButton button(final String text, final Runnable action) {
    final JButton button = new JButton(text);
    button.addActionListener(e -> action.run());
    return button;
  }

  private static JButton tertiary(final String text, final Runnable action) {
    final JButton button = button(text, action);
    Theme.setTertiaryButtonStyle(button);
    return button;
  }

  private static JComponent leftAligned(final JComponent component, final int above) {
    final JPanel aligned = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    aligned.setOpaque(false);
    aligned.setBorder(BorderFactory.createEmptyBorder(above, 0, 0, 0));
    aligned.add(component);
    return aligned;
  }

  /**
   * The guide left of the overview when there is room, the overview growing with it; else the
   * overview below the guide.
   */
  private final class Columns implements LayoutManager {
    private boolean wide() {
      return room() >= RIGHT_MIN_WIDTH;
    }

    // what the guide leaves for the overview beside it
    private int room() {
      final Insets insets = form.getInsets();
      return form.getWidth() - insets.left - insets.right - LEFT_WIDTH - GUTTER;
    }

    /** The overview's width for the room there is; the parts sized to it are made again. */
    private void fitOverview() {
      if (form.getWidth() <= 0) {
        return;
      }
      final int width = wide() ? Math.min(room(), RIGHT_MAX_WIDTH) : LEFT_WIDTH;
      // narrower at once; wider not for a scroll bar's width, which a new height may bring and
      // take again
      final boolean stackChanged = (width == LEFT_WIDTH) != (rightWidth == LEFT_WIDTH);
      if (stackChanged || width < rightWidth || width - rightWidth > Theme.GAP_XL) {
        rightWidth = width;
        miniMap.setPreferredWidth(width);
        // the nations that fit in a line; later, not while laying out, once for many changes
        if (!nationsRefitQueued) {
          nationsRefitQueued = true;
          SwingUtilities.invokeLater(
              () -> {
                nationsRefitQueued = false;
                fitNations();
              });
        }
      }
    }

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
      fitOverview();
      final int leftHeight = left.getPreferredSize().height;
      final int rightHeight = right.getPreferredSize().height;
      return wide()
          ? new Dimension(LEFT_WIDTH + GUTTER + rightWidth, Math.max(leftHeight, rightHeight))
          : new Dimension(LEFT_WIDTH, leftHeight + Theme.GAP_XL + rightHeight);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
      return preferredLayoutSize(parent);
    }

    @Override
    public void layoutContainer(final Container parent) {
      fitOverview();
      final int leftHeight = left.getPreferredSize().height;
      left.setBounds(0, 0, LEFT_WIDTH, leftHeight);
      final int rightHeight = right.getPreferredSize().height;
      if (wide()) {
        right.setBounds(LEFT_WIDTH + GUTTER, 0, rightWidth, rightHeight);
      } else {
        right.setBounds(0, leftHeight + Theme.GAP_XL, rightWidth, rightHeight);
      }
    }

    @Override
    public void addLayoutComponent(final String name, final Component component) {}

    @Override
    public void removeLayoutComponent(final Component component) {}
  }
}
