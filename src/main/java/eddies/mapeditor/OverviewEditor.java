package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapGuide.Part;
import eddies.mapeditor.MapGuide.Step;

/**
 * The map at a glance: on the right the next steps to a playable map and its problems, each with a
 * button to where it is done; in the middle the map's anatomy, the parts of its folder, what each
 * is for, whether the map has it and which tool makes it.
 */
final class OverviewEditor implements Editor {
  private static final Color DONE = new Color(0x2E9E5B);
  private static final Color MISSING = new Color(0xC0392B);

  private final MapProject project;
  private final Consumer<String> showTerritory;
  private final Consumer<String> openTool;
  private final Consumer<Path> openFile;
  private final Runnable check;
  private final Form form = new Form(280);
  private final Form anatomy = new Form(560);
  private final JPanel page = new JPanel(new BorderLayout());
  private final JComponent preview;
  private List<Problem> problems;
  private boolean engineChecked;
  // changed while another tool showed: filled when shown again, not on every edit there
  private boolean stale;

  /**
   * @param showTerritory opens a territory in Territories
   * @param openTool shows the tool of this title
   * @param openFile opens the file or folder in the file browser
   * @param check runs Check, the engine's too
   */
  OverviewEditor(
      final MapProject project,
      final Consumer<String> showTerritory,
      final Consumer<String> openTool,
      final Consumer<Path> openFile,
      final Runnable check) {
    this.project = project;
    this.showTerritory = showTerritory;
    this.openTool = openTool;
    this.openFile = openFile;
    this.check = check;
    problems = project.problems();
    page.add(SwingComponents.newJScrollPane(form), BorderLayout.CENTER);
    preview = SwingComponents.newJScrollPane(anatomy);
    project.onChange(
        () -> {
          problems = project.problems();
          engineChecked = false;
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
    return "Overview";
  }

  @Override
  public Glyph glyph() {
    return Glyph.OVERVIEW;
  }

  @Override
  public String hint() {
    return "Your map at a glance: the next steps to a playable map, its problems, and in the"
        + " middle what its folder holds and which tool makes each part.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  /** The map's anatomy, in the middle. */
  @Override
  public Optional<JComponent> preview() {
    return Optional.of(preview);
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
    engineChecked = true;
    fill();
  }

  List<Problem> problems() {
    return problems;
  }

  private void fill() {
    stale = false;
    fillSteps();
    fillAnatomy();
  }

  private void fillSteps() {
    form.clear();
    final Game game = project.game();
    form.title(project.name());
    form.note(
        MapXml.territories(game).size()
            + " territories, "
            + MapXml.nations(game).size()
            + " nations, "
            + MapXml.unitTypes(game).size()
            + " kinds of units");
    form.section(
        "Next steps",
        "The steps to a map that can be played, in their order. The button goes where a step is"
            + " done.");
    final List<Step> steps =
        engineChecked && problems.isEmpty()
            ? MapGuide.stepsAfterCleanCheck(project)
            : MapGuide.steps(project);
    for (final Step step : steps) {
      final JButton go =
          step.tool().equals(title()) ? new JButton("Check") : new JButton(step.tool());
      go.addActionListener(
          e -> {
            if (step.tool().equals(title())) {
              check.run();
            } else {
              openTool.accept(step.tool());
            }
          });
      go.setToolTipText(step.text());
      form.row(row(mark(step.done(), true), step.text(), go));
    }
    form.section(
        problems.isEmpty() ? "No problems" : problems.size() + " problems",
        "What still keeps the map from being played. Check (F6) also loads the game as the"
            + " game engine does.");
    if (problems.isEmpty()) {
      form.note(engineChecked ? "The game engine loads this game." : "Nothing found so far.");
    }
    for (final Problem problem : problems) {
      final JButton show = new JButton("Show");
      show.getAccessibleContext().setAccessibleName("Show: " + problem.text());
      problem.territory().ifPresent(t -> show.addActionListener(e -> showTerritory.accept(t)));
      form.row(
          row(
              mark(false, true),
              problem.text(),
              // an empty slot as wide and high as the button, so all rows line up
              problem.territory().isPresent()
                  ? show
                  : (JComponent) Box.createRigidArea(show.getPreferredSize())));
    }
    form.end();
  }

  private void fillAnatomy() {
    anatomy.clear();
    anatomy.title(
        "Map anatomy",
        "What a map's folder holds and what each part is for. Parts marked ✓ are there, ! are"
            + " missing although the game needs them, ○ are missing and only make the map nicer."
            + " The buttons open the tool that makes a part, or the file in the file browser.");
    for (final Part part : MapGuide.anatomy(project)) {
      final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
      buttons.setOpaque(false);
      if (part.tool() != null) {
        final String tool = part.tool();
        final JButton edit = new JButton(tool);
        edit.getAccessibleContext().setAccessibleName(tool + ": " + part.name());
        edit.addActionListener(e -> openTool.accept(tool));
        buttons.add(edit);
      }
      if (part.present()) {
        final JButton open = new JButton("Open");
        open.getAccessibleContext().setAccessibleName("Open " + part.name());
        open.addActionListener(e -> openFile.accept(part.file()));
        buttons.add(open);
      }
      final JPanel row = row(mark(part.present(), part.required()), "", buttons);
      final JLabel name = new JLabel(part.name());
      name.setFont(name.getFont().deriveFont(Font.BOLD));
      final JTextArea purpose = wrapping(part.purpose());
      purpose.setForeground(Theme.getSecondaryTextColor());
      final JPanel texts = new JPanel(new BorderLayout());
      texts.setOpaque(false);
      texts.add(name, BorderLayout.NORTH);
      texts.add(purpose, BorderLayout.SOUTH);
      row.add(texts, BorderLayout.CENTER);
      row.setBorder(BorderFactory.createEmptyBorder(2, part.depth() * 24, 2, 0));
      anatomy.row(row);
    }
    anatomy.end();
  }

  /** ✓ when done or there, else ! when needed and ○ when not. */
  private static JLabel mark(final boolean done, final boolean needed) {
    final JLabel mark = new JLabel(done ? "✓" : needed ? "!" : "○");
    mark.setFont(mark.getFont().deriveFont(Font.BOLD));
    // as wide for each sign, so the texts beside them line up
    mark.setHorizontalAlignment(JLabel.CENTER);
    mark.setVerticalAlignment(JLabel.TOP);
    mark.setPreferredSize(new Dimension(12, mark.getPreferredSize().height));
    mark.setForeground(done ? DONE : needed ? MISSING : Theme.getSecondaryTextColor());
    mark.getAccessibleContext().setAccessibleName(done ? "done" : needed ? "missing" : "optional");
    return mark;
  }

  private static JPanel row(final JLabel mark, final String text, final JComponent action) {
    final JPanel row = new JPanel(new BorderLayout(8, 0));
    row.add(mark, BorderLayout.WEST);
    if (!text.isEmpty()) {
      row.add(wrapping(text), BorderLayout.CENTER);
    }
    // at the top and its own size, also beside a text of two lines
    final JPanel top = new JPanel(new BorderLayout());
    top.setOpaque(false);
    top.add(action, BorderLayout.NORTH);
    row.add(top, BorderLayout.EAST);
    return row;
  }

  /**
   * A text that looks like a label and wraps at the room it gets beside its button, so a long step
   * stays whole however wide the column is.
   */
  private static JTextArea wrapping(final String text) {
    final JTextArea area = new JTextArea(text);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setBorder(null);
    area.setFont(UIManager.getFont("Label.font"));
    area.setForeground(UIManager.getColor("Label.foreground"));
    return area;
  }
}
