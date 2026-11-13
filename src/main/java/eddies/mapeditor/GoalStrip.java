package eddies.mapeditor;

import eddies.mapeditor.MapGuide.Step;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.Serial;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The goal a tool's mode works towards, in a row above the mode's banner: a step to a playable map
 * ("Step 3 of 7 · Give every nation its land", its "?" and how far it is), or another tool's goal,
 * eg a terrain effect painted; {@link #next} leads on once the step is done.
 */
final class GoalStrip extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  static final int HEIGHT = 24;

  private final transient MapProject project;
  private final JLabel text = new JLabel();
  private final JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
  private final JLabel progress = Theme.newSecondaryLabel("");
  private final JButton next = new JButton();
  private transient Consumer<Step.Id> followStep = step -> {};
  private transient Runnable nextAction = () -> {};
  private transient Optional<Step.Id> step = Optional.empty();
  // whether the step was done when last seen: it turning done is news once
  private boolean wasDone;

  GoalStrip(final MapProject project) {
    super(new BorderLayout(Theme.GAP_M, 0));
    this.project = project;
    setOpaque(false);
    setPreferredSize(new Dimension(0, HEIGHT));
    left.setOpaque(false);
    add(left, BorderLayout.CENTER);
    add(progress, BorderLayout.EAST);
    Theme.setPrimaryButtonStyle(next);
    next.addActionListener(e -> nextAction.run());
    next.setVisible(false);
    setVisible(false);
    project.onChange(this::refresh);
  }

  /** What following a step does, eg the Next button's: show its tool and its goal. */
  void onFollowStep(final Consumer<Step.Id> follow) {
    followStep = follow;
  }

  /** Shows the step as the goal, until {@link #end}. */
  void showStep(final Step.Id shown) {
    step = Optional.of(shown);
    final Step current = current(MapGuide.steps(project), shown);
    left.removeAll();
    left.add(text);
    left.add(Help.button(current.text(), current.why()));
    wasDone = current.done();
    setVisible(true);
    refresh();
  }

  /** Shows a goal that is no step, eg a terrain effect painted, its action on {@link #next}. */
  void showGoal(
      final String title, final String howFar, final String action, final Runnable onAction) {
    step = Optional.empty();
    left.removeAll();
    left.add(text);
    // as tall as a step's "?", so the title lines up with the progress as there
    left.add(Box.createVerticalStrut(HEIGHT));
    text.setText(title);
    progress.setText(howFar);
    next.setText(action);
    nextAction = onAction;
    next.setVisible(true);
    setVisible(true);
    revalidate();
    repaint();
  }

  void end() {
    step = Optional.empty();
    next.setVisible(false);
    setVisible(false);
  }

  Optional<Step.Id> step() {
    return step;
  }

  /** The goal's line, eg "Step 3 of 7 · Give every nation its land". */
  String text() {
    return text.getText();
  }

  /** Leads on: to the next step once this one is done, or the goal's action; for the banner. */
  JButton next() {
    return next;
  }

  /** The step's line and progress again, the next step once it is done. */
  private void refresh() {
    if (step.isEmpty()) {
      return;
    }
    final List<Step> steps = MapGuide.steps(project);
    final Step current = current(steps, step.get());
    text.setText(
        "Step " + (steps.indexOf(current) + 1) + " of " + steps.size() + " · " + current.text());
    progress.setText(current.progress());
    // the same next step as Home's; "Next" only for one further down the list
    final Optional<Step> following = MapGuide.firstOpen(steps);
    final String lead =
        following.filter(then -> steps.indexOf(then) < steps.indexOf(current)).isPresent()
            ? "Still open: "
            : "Next: ";
    // also for a step that waits for one before it, eg capitals while no nation has land
    next.setVisible(following.filter(then -> then != current).isPresent());
    following.ifPresent(
        then -> {
          next.setText(lead + then.text() + " ›");
          nextAction = () -> followStep.accept(then.id());
        });
    if (current.done() && !wasDone) {
      following.ifPresentOrElse(
          then -> Toast.show(this, "✓ " + current.text(), lead + then.text(), nextAction),
          () -> Toast.show(this, "✓ " + current.text()));
    }
    wasDone = current.done();
    revalidate();
    repaint();
  }

  private static Step current(final List<Step> steps, final Step.Id id) {
    return steps.stream().filter(s -> s.id() == id).findFirst().orElseThrow();
  }
}
