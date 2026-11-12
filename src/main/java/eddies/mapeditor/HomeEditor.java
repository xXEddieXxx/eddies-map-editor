package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapGuide.Next;
import eddies.mapeditor.MapGuide.Step;
import eddies.mapeditor.MapProject.NewMap;
import eddies.mapeditor.Theme.Status;

/**
 * The map at a glance: the next step with its one main action, the steps to a playable map and,
 * beside them when there is room, the map's problems, each with a way to where it is fixed.
 */
final class HomeEditor implements Editor {
  private static final int LEFT_WIDTH = 540;
  private static final int RIGHT_WIDTH = 380;
  private static final int GUTTER = Theme.GAP_XL;
  private static final int SHOWN_PROBLEMS = 8;
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
  private final Form right = column(RIGHT_WIDTH);
  private final List<String> welcome = new ArrayList<>();
  private List<Problem> problems;
  private boolean checked;
  private boolean allProblemsShown;
  private boolean finishedStepsShown;
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
    problems = project.problems();
    project.onChange(
        () -> {
          problems = project.problems();
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
    if (stale) {
      fill();
    }
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
        () -> right.scrollRectToVisible(new Rectangle(0, 0, right.getWidth(), right.getHeight())));
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
    fillLeft();
    fillRight();
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

  private void fillLeft() {
    left.clear();
    left.row(card());
    left.row((JComponent) Box.createVerticalStrut(Theme.GAP_M));
    final List<Step> steps =
        checked && problems.isEmpty()
            ? MapGuide.stepsAfterCleanCheck(project)
            : MapGuide.steps(project);
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

  private void fillRight() {
    right.clear();
    right.row(heading("Problems", problems.isEmpty() ? "" : String.valueOf(problems.size())));
    if (problems.isEmpty()) {
      right.row(
          Panels.empty(
              Glyph.VALIDATE,
              "No problems",
              checked
                  ? "TripleA loads this game."
                  : "The quick checks found nothing. Check (F6) also loads the game."));
      return;
    }
    final int shown =
        allProblemsShown ? problems.size() : Math.min(SHOWN_PROBLEMS, problems.size());
    problems.subList(0, shown).forEach(problem -> right.row(problemRow(problem)));
    if (shown < problems.size()) {
      right.row(
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
      right.row(
          note(
              "Found by the quick checks. Check (F6) also loads the game as TripleA does.",
              RIGHT_WIDTH));
      final JButton checkNow = button("Check now", check);
      Theme.setSecondaryButtonStyle(checkNow);
      right.row(leftAligned(checkNow, Theme.GAP_XS));
    }
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
        RIGHT_WIDTH - MARK_WIDTH - 2 * Theme.GAP_S - action.getPreferredSize().width;
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

  /** The card and steps left of the problems when there is room, else the problems below them. */
  private final class Columns implements LayoutManager {
    private boolean wide() {
      final Insets insets = form.getInsets();
      return form.getWidth() - insets.left - insets.right >= LEFT_WIDTH + GUTTER + RIGHT_WIDTH;
    }

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
      final int leftHeight = left.getPreferredSize().height;
      final int rightHeight = right.getPreferredSize().height;
      return wide()
          ? new Dimension(LEFT_WIDTH + GUTTER + RIGHT_WIDTH, Math.max(leftHeight, rightHeight))
          : new Dimension(LEFT_WIDTH, leftHeight + Theme.GAP_XL + rightHeight);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
      return preferredLayoutSize(parent);
    }

    @Override
    public void layoutContainer(final Container parent) {
      final int leftHeight = left.getPreferredSize().height;
      left.setBounds(0, 0, LEFT_WIDTH, leftHeight);
      final int rightHeight = right.getPreferredSize().height;
      if (wide()) {
        right.setBounds(LEFT_WIDTH + GUTTER, 0, RIGHT_WIDTH, rightHeight);
      } else {
        right.setBounds(0, leftHeight + Theme.GAP_XL, RIGHT_WIDTH, rightHeight);
      }
    }

    @Override
    public void addLayoutComponent(final String name, final Component component) {}

    @Override
    public void removeLayoutComponent(final Component component) {}
  }
}
