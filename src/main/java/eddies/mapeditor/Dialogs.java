package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.experimental.UtilityClass;
import org.triplea.swing.SwingComponents;

/**
 * The editor's dialogs, all alike: the text, then [Cancel] [verb] at the right, Enter the verb, Esc
 * Cancel; English, no icons. News that needs no answer is a toast instead.
 */
@UtilityClass
class Dialogs {
  private static final int MIN_WIDTH = 420;
  private static final int MAX_WIDTH = 560;
  private static final int BUTTON_MIN_WIDTH = 88;
  private static final int MAX_DETAILS = 6;
  private static final int SEARCH_FROM = 12;
  private static final String CANCEL = "Cancel";
  private static final String CLOSE = "Close";
  // on a pane's root: whether it is only text, which is kept to a readable width
  private static final String TEXT_ONLY_KEY = "Dialogs.textOnly";

  enum SaveChoice {
    SAVE,
    DONT_SAVE,
    CANCEL
  }

  /** A dialog's inside, built without a window, so tests can look at it. */
  record Pane(JComponent root, List<JButton> buttons, JButton verb, Runnable recheck) {}

  /** [Cancel] [verb]; whether the verb was chosen. */
  static boolean confirm(
      final Component parent, final String title, final String text, final String verb) {
    final Pane pane = newPane(text, List.of(), null, verb, () -> true);
    return show(parent, title, pane).filter(pane.verb()::equals).isPresent();
  }

  /** As confirm, with what goes with it listed and the verb filled in the danger color. */
  static boolean confirmRemove(
      final Component parent,
      final String title,
      final String text,
      final List<String> details,
      final String verb) {
    final Pane pane = newRemovePane(text, details, verb);
    return show(parent, title, pane).filter(pane.verb()::equals).isPresent();
  }

  static Pane newRemovePane(final String text, final List<String> details, final String verb) {
    final Pane pane = newPane(text, details, null, verb, () -> true);
    Theme.setDangerButtonStyle(pane.verb());
    return pane;
  }

  /** A name, checked as typed: the check returns the problem to show below the field, if any. */
  static Optional<String> askName(
      final Component parent,
      final String title,
      final String label,
      final String initial,
      final String verb,
      final Function<String, Optional<String>> problem) {
    final JTextField field = new JTextField(initial);
    final Pane pane = newNamePane(label, field, verb, problem);
    field.selectAll();
    return show(parent, title, pane)
        .filter(pane.verb()::equals)
        .map(chosen -> field.getText().strip());
  }

  static Pane newNamePane(
      final String label,
      final JTextField field,
      final String verb,
      final Function<String, Optional<String>> problem) {
    final FieldError error = new FieldError(field);
    final JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    for (final JComponent part : List.of(new JLabel(label), field, error)) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      content.add(part);
      content.add(Box.createVerticalStrut(Theme.GAP_XS));
    }
    field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
    final Pane pane =
        newPane(
            "",
            List.of(),
            content,
            verb,
            () -> !field.getText().isBlank() && !error.hasProblem());
    final Runnable check =
        () -> {
          final String name = field.getText().strip();
          // a blank name only disables the verb: nothing to blame before anything is typed
          error.setProblem(name.isEmpty() ? Optional.empty() : problem.apply(name));
          pane.recheck().run();
        };
    OnTyping.attach(field, check);
    check.run();
    return pane;
  }

  /** Fields the caller built, eg a Form; whether the verb was chosen. */
  static boolean ask(
      final Component parent, final String title, final JComponent content, final String verb) {
    return ask(parent, title, content, verb, () -> true);
  }

  /** As ask, the verb enabled only while valid says so. */
  static boolean ask(
      final Component parent,
      final String title,
      final JComponent content,
      final String verb,
      final BooleanSupplier valid) {
    final Pane pane = newPane("", List.of(), content, verb, valid);
    return show(parent, title, pane).filter(pane.verb()::equals).isPresent();
  }

  /** One of the items, with a search above them from 12 items; empty when cancelled. */
  static <T> Optional<T> pick(
      final Component parent,
      final String title,
      final String text,
      final List<T> items,
      final Function<T, String> label,
      final String verb) {
    final AtomicReference<Optional<T>> picked = new AtomicReference<>(Optional.empty());
    final Pane pane = newPickPane(text, items, label, verb, picked::set);
    return show(parent, title, pane).filter(pane.verb()::equals).flatMap(chosen -> picked.get());
  }

  /** The pane of pick; onSelect is told the item of the row selected, empty when none. */
  static <T> Pane newPickPane(
      final String text,
      final List<T> items,
      final Function<T, String> label,
      final String verb,
      final Consumer<Optional<T>> onSelect) {
    final List<String> labels = items.stream().map(label).toList();
    // the items the rows show, by row: labels may repeat, so a row maps back by its index
    final AtomicReference<List<Integer>> rows =
        new AtomicReference<>(IntStream.range(0, items.size()).boxed().toList());
    final JList<String> list = new JList<>(labels.toArray(String[]::new));
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setVisibleRowCount(Math.min(12, Math.max(4, labels.size())));
    final JPanel content = new JPanel(new BorderLayout(0, Theme.GAP_S));
    content.setOpaque(false);
    content.add(SwingComponents.newJScrollPane(list), BorderLayout.CENTER);
    if (items.size() >= SEARCH_FROM) {
      final JTextField search = newSearchField();
      OnTyping.attach(
          search,
          () -> {
            final String filter = search.getText().strip().toLowerCase(Locale.ROOT);
            rows.set(
                IntStream.range(0, items.size())
                    .filter(i -> labels.get(i).toLowerCase(Locale.ROOT).contains(filter))
                    .boxed()
                    .toList());
            list.setListData(rows.get().stream().map(labels::get).toArray(String[]::new));
          });
      content.add(search, BorderLayout.NORTH);
    }
    final Pane pane = newPane(text, List.of(), content, verb, () -> !list.isSelectionEmpty());
    list.addListSelectionListener(
        e -> {
          final int row = list.getSelectedIndex();
          onSelect.accept(
              row < 0 ? Optional.empty() : Optional.of(items.get(rows.get().get(row))));
          pane.recheck().run();
        });
    return pane;
  }

  /** Which of the names to tick, those given ticked first; empty when cancelled. */
  static Optional<List<String>> pickMany(
      final Component parent,
      final String title,
      final List<String> names,
      final Collection<String> ticked,
      final String verb) {
    final JPanel ticks = new JPanel(new GridLayout(0, 1));
    final List<JCheckBox> boxes =
        names.stream().map(name -> new JCheckBox(name, ticked.contains(name))).toList();
    boxes.forEach(ticks::add);
    final JScrollPane scroll = SwingComponents.newJScrollPane(ticks);
    scroll.setPreferredSize(
        new Dimension(320, Math.min(420, ticks.getPreferredSize().height + 8)));
    final JPanel content = new JPanel(new BorderLayout(0, 6));
    content.setOpaque(false);
    content.add(scroll, BorderLayout.CENTER);
    if (names.size() >= SEARCH_FROM) {
      final JTextField search = newSearchField();
      // hidden boxes keep their tick
      OnTyping.attach(
          search,
          () -> {
            final String filter = search.getText().strip().toLowerCase(Locale.ROOT);
            ticks.removeAll();
            boxes.stream()
                .filter(box -> box.getText().toLowerCase(Locale.ROOT).contains(filter))
                .forEach(ticks::add);
            ticks.revalidate();
            ticks.repaint();
          });
      content.add(search, BorderLayout.NORTH);
    }
    return ask(parent, title, content, verb)
        ? Optional.of(boxes.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList())
        : Optional.empty();
  }

  /** A problem the map maker must read before going on: one "Close" button. */
  static void warn(final Component parent, final String title, final String text) {
    show(parent, title, newPane(text, List.of(), null, List.of(), List.of(CLOSE), () -> true));
  }

  /**
   * A refusal because attachments still name something, eg a removal: [Close] [Show in
   * Attachments], which opens the first attachment named; with only variables naming it, a warn.
   */
  static void refuseNamed(
      final Component parent, final String title, final String text, final List<String> named) {
    final Optional<String> first = firstAttachment(named);
    final Pane pane = newRefusePane(text, named);
    show(parent, title, pane)
        .filter(chosen -> first.isPresent() && chosen == pane.verb())
        .ifPresent(chosen -> MapMakerWindow.openTool(parent, "Attachments", first.get()));
  }

  static Pane newRefusePane(final String text, final List<String> named) {
    return newPane(
        text,
        List.of(),
        null,
        List.of(),
        firstAttachment(named).isPresent()
            ? List.of(CLOSE, "Show in Attachments")
            : List.of(CLOSE),
        () -> true);
  }

  // whereNamed lists variables as "variable name"; Attachments does not show those
  private static Optional<String> firstAttachment(final List<String> named) {
    return named.stream().filter(place -> !place.startsWith("variable ")).findFirst();
  }

  /** Save, don't save or cancel, eg before closing a map with unsaved changes. */
  static SaveChoice askToSave(final Component parent, final String title, final String text) {
    final Pane pane = newSavePane(text);
    return show(parent, title, pane)
        .map(
            chosen ->
                chosen == pane.verb()
                    ? SaveChoice.SAVE
                    : chosen == pane.buttons().getFirst() ? SaveChoice.DONT_SAVE : SaveChoice.CANCEL)
        .orElse(SaveChoice.CANCEL);
  }

  /** [Don't save] at the left, away from the mouse on the verb, then [Cancel] [Save]. */
  static Pane newSavePane(final String text) {
    final Pane pane =
        newPane(text, List.of(), null, List.of("Don't save"), List.of(CANCEL, "Save"), () -> true);
    Theme.setDestructiveButtonStyle(pane.buttons().getFirst());
    return pane;
  }

  /** News that needs no answer, for 4 s; the caller puts "✓ " or "! " before the text. */
  static void toast(final Component anyInWindow, final String text) {
    Toast.show(anyInWindow, text);
  }

  /** As toast, with an action, eg "Show", for 8 s. */
  static void toast(
      final Component anyInWindow,
      final String text,
      final String action,
      final Runnable onAction) {
    Toast.show(anyInWindow, text, action, onAction);
  }

  /**
   * The text (its first line in semibold, the rest below it), at most 6 details, the content, and
   * [Cancel] [verb]; the verb enabled while valid says so, checked again by recheck.
   */
  static Pane newPane(
      final String text,
      final List<String> details,
      @Nullable final JComponent content,
      final String verb,
      final BooleanSupplier valid) {
    return newPane(text, details, content, List.of(), List.of(CANCEL, verb), valid);
  }

  private static Pane newPane(
      final String text,
      final List<String> details,
      @Nullable final JComponent content,
      final List<String> left,
      final List<String> right,
      final BooleanSupplier valid) {
    final JPanel body = new JPanel();
    body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
    body.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_L, Theme.GAP_L, Theme.GAP_L, Theme.GAP_L));
    final List<JComponent> parts = new ArrayList<>();
    if (!text.isEmpty()) {
      final int lineEnd = text.indexOf('\n');
      final JLabel lead = wrapping(lineEnd < 0 ? text : text.substring(0, lineEnd));
      lead.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
      parts.add(lead);
      if (lineEnd >= 0) {
        parts.add(wrapping(text.substring(lineEnd + 1)));
      }
    }
    details.stream()
        .limit(MAX_DETAILS)
        .forEach(detail -> parts.add(Theme.newSecondaryLabel("• " + detail)));
    if (details.size() > MAX_DETAILS) {
      parts.add(Theme.newSecondaryLabel("and " + (details.size() - MAX_DETAILS) + " more"));
    }
    Optional.ofNullable(content).ifPresent(parts::add);
    for (final JComponent part : parts) {
      if (body.getComponentCount() > 0) {
        body.add(Box.createVerticalStrut(Theme.GAP_S));
      }
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      body.add(part);
    }
    final List<JButton> buttons = new ArrayList<>();
    final JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    final JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.TRAILING, Theme.GAP_S, 0));
    left.forEach(label -> leftButtons.add(newButton(label, buttons)));
    right.forEach(label -> rightButtons.add(newButton(label, buttons)));
    final JPanel footer = new JPanel(new BorderLayout());
    footer.setBorder(Theme.newFooterBorder());
    footer.add(leftButtons, BorderLayout.WEST);
    footer.add(rightButtons, BorderLayout.EAST);
    final JPanel root = new JPanel(new BorderLayout());
    root.add(body, BorderLayout.CENTER);
    root.add(footer, BorderLayout.SOUTH);
    final JButton verb = buttons.getLast();
    Theme.setPrimaryButtonStyle(verb);
    final Runnable recheck = () -> verb.setEnabled(valid.getAsBoolean());
    recheck.run();
    // Enter the verb, also from a field; Esc the Cancel button, or the only one
    bind(root, KeyEvent.VK_ENTER, verb);
    bind(
        root,
        KeyEvent.VK_ESCAPE,
        buttons.stream()
            .filter(b -> b.getText().equals(CANCEL) || b.getText().equals(CLOSE))
            .findFirst()
            .orElse(verb));
    root.putClientProperty(TEXT_ONLY_KEY, content == null);
    return new Pane(root, List.copyOf(buttons), verb, recheck);
  }

  private static void bind(final JComponent root, final int key, final JButton button) {
    final String name = "Dialogs.key" + key;
    root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
    root.getActionMap()
        .put(
            name,
            new AbstractAction() {
              @Override
              public void actionPerformed(final ActionEvent e) {
                if (button.isEnabled()) {
                  button.doClick();
                }
              }
            });
  }

  // a short text on one line, a long one wrapped within the dialog
  private static JLabel wrapping(final String text) {
    final String html = SearchList.escape(text).replace("\n", "<br>");
    final JLabel label = new JLabel("<html>" + html + "</html>");
    final int width = MAX_WIDTH - 2 * Theme.GAP_L;
    if (label.getPreferredSize().width > width) {
      label.setText(
          "<html><div style='width:" + Theme.htmlWidth(width) + "'>" + html + "</div></html>");
    }
    return label;
  }

  private static JButton newButton(final String label, final List<JButton> buttons) {
    // asked each time: a verb styled primary later gets a wider (semibold) font
    final JButton button =
        new JButton(label) {
          @Override
          public Dimension getPreferredSize() {
            final Dimension size = super.getPreferredSize();
            return new Dimension(Math.max(BUTTON_MIN_WIDTH, size.width), size.height);
          }
        };
    Theme.setSecondaryButtonStyle(button);
    buttons.add(button);
    return button;
  }

  private static JTextField newSearchField() {
    final JTextField search = new JTextField();
    search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search");
    search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    return search;
  }

  /**
   * Shows the pane in a modal dialog over the parent's window; the button chosen, empty when the
   * window was closed.
   */
  private static Optional<JButton> show(
      @Nullable final Component parent, final String title, final Pane pane) {
    final Window owner =
        parent instanceof Window window
            ? window
            : Optional.ofNullable(parent).map(SwingUtilities::getWindowAncestor).orElse(null);
    final JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
    dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    fill(dialog.getRootPane(), pane);
    final AtomicReference<JButton> chosen = new AtomicReference<>();
    for (final JButton button : pane.buttons()) {
      button.addActionListener(
          e -> {
            chosen.set(button);
            dialog.dispose();
          });
    }
    // shortcut: polls valid 5 times a second; switch to listeners if profiling shows it
    final Timer recheck = new Timer(200, e -> pane.recheck().run());
    dialog.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowOpened(final WindowEvent e) {
            recheck.start();
          }

          @Override
          public void windowClosed(final WindowEvent e) {
            recheck.stop();
          }
        });
    dialog.pack();
    dialog.setSize(
        width(pane, dialog.getWidth(), dialog.getGraphicsConfiguration().getBounds().width),
        dialog.getHeight());
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
    return Optional.ofNullable(chosen.get());
  }

  /**
   * The pane in the window's root pane, its verb the default button: Enter in a field that keeps
   * Enter for itself, eg an editable combo box, still reaches it.
   */
  static void fill(final JRootPane rootPane, final Pane pane) {
    rootPane.setContentPane(pane.root());
    rootPane.setDefaultButton(pane.verb());
  }

  /**
   * How wide the dialog is: a text only between 420 and 560 px, so it reads well; with content at
   * least as wide as it wants, so nothing is cut, up to the screen.
   */
  static int width(final Pane pane, final int packed, final int screen) {
    return Boolean.TRUE.equals(pane.root().getClientProperty(TEXT_ONLY_KEY))
        ? Math.clamp(packed, MIN_WIDTH, MAX_WIDTH)
        : Math.min(Math.max(packed, MIN_WIDTH), screen);
  }
}
