package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatUIUtils;
import games.strategy.engine.ClientFileSystemHelper;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import lombok.extern.slf4j.Slf4j;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapProject.NewMap;

/**
 * The steps to a new map: its name and folder, its picture, in which the territories are found
 * (or Skip, for a map without one yet), and the nations that play it. Create makes and saves the
 * map ({@link MapProject#newMap}) in the background and hands it on.
 */
@Slf4j
final class NewMapWizard extends JPanel {
  @Serial private static final long serialVersionUID = 1L;

  private static final List<String> STEPS = List.of("Name", "Picture", "Nations");
  private static final Dimension DROP_ZONE = new Dimension(560, 260);
  private static final int NAME_WIDTH = 360;
  private static final List<String> PICTURE_ENDINGS = List.of("png", "jpg", "jpeg", "gif");
  private static final String PLACEHOLDER = "Germans\nRussians\nBritish";

  private final transient Consumer<NewMap> created;
  private final transient Path mapsFolder;
  private transient Path location;
  private final CardLayout cards = new CardLayout();
  private final JPanel steps = new JPanel(cards);
  private final List<JLabel> stepLabels = STEPS.stream().map(JLabel::new).toList();
  final JTextField name = new JTextField();
  final FieldError nameError = new FieldError(name);
  private final JLabel locationLabel = new JLabel();
  private final JLabel locationNote = Theme.newSecondaryLabel(" ");
  private final CardLayout zoneCards = new CardLayout();
  private final JPanel zone = new JPanel(zoneCards);
  private final JLabel preview = new JLabel("", SwingConstants.CENTER);
  private final JLabel pictureLine = Theme.newSecondaryLabel(" ");
  private final JPanel pictureRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
  private final JLabel skipNote =
      Theme.newSecondaryLabel("No picture? Skip this step and add one later in Territories.");
  final JTextArea nations = newNationsArea();
  private final JLabel nationCount = Theme.newSecondaryLabel(" ");
  private final JLabel status = new JLabel(" ");
  private final JProgressBar progress = new JProgressBar();
  private final JButton cancel = new JButton("Cancel");
  private final JButton back = new JButton("Back");
  final JButton skip = new JButton("Skip");
  final JButton next = new JButton("Next");
  private transient @Nullable BufferedImage picture;
  private boolean busy;
  private int step;

  /** The wizard's content; a new map goes into the location, which Change… can change. */
  NewMapWizard(final Path location, final Consumer<NewMap> created) {
    super(new BorderLayout(0, Theme.GAP_L));
    this.created = created;
    this.mapsFolder = location;
    this.location = location;
    steps.add(newNameStep(), STEPS.get(0));
    steps.add(newPictureStep(), STEPS.get(1));
    steps.add(newNationsStep(), STEPS.get(2));
    final JPanel stepBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    for (final JLabel label : stepLabels) {
      if (stepBar.getComponentCount() > 0) {
        final JLabel between = new JLabel("›");
        between.setForeground(Theme.getSecondaryTextColor());
        spaced(stepBar, between);
      }
      spaced(stepBar, label);
    }
    cancel.addActionListener(e -> window().ifPresent(Window::dispose));
    back.addActionListener(e -> showStep(step - 1));
    // no picture: the map gets one later, in Territories
    skip.addActionListener(e -> showStep(step + 1));
    next.addActionListener(e -> next());
    Theme.setPrimaryButtonStyle(next);
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
    buttons.add(cancel);
    buttons.add(back);
    buttons.add(skip);
    buttons.add(next);
    progress.setIndeterminate(true);
    progress.setPreferredSize(new Dimension(120, progress.getPreferredSize().height));
    progress.setVisible(false);
    final JPanel working =
        spaced(new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)), progress, status);
    final JPanel footer = new JPanel(new BorderLayout(Theme.GAP_M, 0));
    footer.setBorder(Theme.newFooterBorder());
    footer.add(working, BorderLayout.CENTER);
    footer.add(buttons, BorderLayout.EAST);
    final JPanel body = new JPanel(new BorderLayout(0, Theme.GAP_XL));
    body.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_L, Theme.GAP_XL, 0, Theme.GAP_XL));
    body.add(stepBar, BorderLayout.NORTH);
    body.add(steps, BorderLayout.CENTER);
    add(body, BorderLayout.CENTER);
    add(footer, BorderLayout.SOUTH);
    showStep(0);
  }

  /** Shows the wizard; once the map is made, hands it on. */
  static void show(final Frame owner, final Consumer<NewMap> created) {
    final JDialog dialog = new JDialog(owner, "New map", true);
    final NewMapWizard wizard =
        new NewMapWizard(
            ClientFileSystemHelper.getUserMapsFolder(),
            made -> {
              dialog.dispose();
              created.accept(made);
            });
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    dialog.setContentPane(wizard);
    dialog.getRootPane().setDefaultButton(wizard.next);
    dialog.setMinimumSize(new Dimension(560, 480));
    dialog.setSize(640, 560);
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }

  private Optional<JDialog> window() {
    return Optional.ofNullable(SwingUtilities.getWindowAncestor(this))
        .filter(JDialog.class::isInstance)
        .map(JDialog.class::cast);
  }

  private JComponent newNameStep() {
    name.setPreferredSize(new Dimension(NAME_WIDTH, name.getPreferredSize().height));
    OnTyping.attach(name, this::checkName);
    final JButton change = new JButton("Change…");
    change.addActionListener(e -> changeLocation());
    locationLabel.setForeground(Theme.getSecondaryTextColor());
    final JPanel saved =
        spaced(
            new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)),
            new JLabel("Saved in"),
            locationLabel,
            change);
    showLocation();
    return newPage(
        "What is your map called?", name, nameError, gap(Theme.GAP_M), saved, locationNote);
  }

  private void changeLocation() {
    MapMakerWindow.chooseFolder(this, "Where should the new map go?", location)
        .ifPresent(
            chosen -> {
              location = chosen;
              showLocation();
              checkName();
            });
  }

  private void showLocation() {
    locationLabel.setText(
        SearchList.cutMiddle(
            location.toString(), locationLabel.getFontMetrics(locationLabel.getFont()), 360));
    locationLabel.setToolTipText(location.toString());
    locationNote.setText(
        location.equals(mapsFolder)
            ? "TripleA lists the maps in this folder."
            : "TripleA lists only the maps in its maps folder: move the map there to play it.");
  }

  /** A taken name or one without letters shows below the field; Next waits for a good name. */
  private void checkName() {
    nameError.setProblem(
        name.getText().isBlank()
            ? Optional.empty()
            : MapFolder.whyNoNewMap(location, name.getText()));
    updateButtons();
  }

  private JComponent newPictureStep() {
    final JButton choose = new JButton("Choose picture…");
    choose.addActionListener(e -> choosePicture());
    final JPanel ask = new JPanel(new FlowLayout(FlowLayout.CENTER, Theme.GAP_S, 0));
    ask.setOpaque(false);
    ask.add(new JLabel("Drop a PNG, JPG or GIF here, or"));
    ask.add(choose);
    final JLabel glyph = new JLabel(new MakerIcon(Glyph.GAME_VIEW, 32));
    final JPanel empty = new JPanel(new GridBagLayout());
    empty.setOpaque(false);
    final GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.insets = new Insets(0, 0, Theme.GAP_M, 0);
    empty.add(glyph, c);
    c.insets = new Insets(0, 0, 0, 0);
    empty.add(ask, c);
    zone.add(empty, "empty");
    zone.add(preview, "picture");
    zone.setPreferredSize(DROP_ZONE);
    zone.setMinimumSize(DROP_ZONE);
    zone.setBorder(
        BorderFactory.createDashedBorder(Theme.getSeparatorColor(), 1.5f, 6, 4, true));
    zone.setTransferHandler(newDropHandler());
    final JButton another = new JButton("Choose another…");
    Theme.setTertiaryButtonStyle(another);
    another.addActionListener(e -> choosePicture());
    final JButton remove = new JButton("Remove");
    Theme.setTertiaryButtonStyle(remove);
    remove.addActionListener(e -> showPicture(null, null, ""));
    spaced(pictureRow, pictureLine, another, remove);
    pictureRow.setVisible(false);
    return newPage(
        "Add a picture of your map",
        newNote(
            "Dark lines between territories, seas in blue. The editor finds the territories, sea"
                + " zones and their neighbours."),
        zone,
        pictureRow,
        skipNote);
  }

  private TransferHandler newDropHandler() {
    return new TransferHandler() {
      @Serial private static final long serialVersionUID = 1L;

      @Override
      public boolean canImport(final TransferSupport support) {
        return !busy && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
      }

      @Override
      public boolean importData(final TransferSupport support) {
        if (!canImport(support)) {
          return false;
        }
        try {
          final List<?> files =
              (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
          final Optional<Path> dropped =
              files.stream()
                  .map(file -> ((File) file).toPath())
                  .filter(NewMapWizard::isPicture)
                  .findFirst();
          dropped.ifPresentOrElse(
              NewMapWizard.this::readPicture,
              () -> showStatus(Theme.Status.PROBLEM, "Drop a PNG, JPG or GIF"));
          return dropped.isPresent();
        } catch (final UnsupportedFlavorException | IOException e) {
          return false;
        }
      }
    };
  }

  private static boolean isPicture(final Path file) {
    final String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return PICTURE_ENDINGS.stream().anyMatch(ending -> fileName.endsWith("." + ending));
  }

  private JComponent newNationsStep() {
    OnTyping.attach(nations, this::countNations);
    countNations();
    return newPage(
        "Who plays on it?",
        newNote("One nation per line. You can add more later in Nations."),
        new JScrollPane(nations),
        nationCount);
  }

  private void countNations() {
    final int count = (int) nations.getText().lines().filter(line -> !line.isBlank()).count();
    nationCount.setText(SearchList.count(count, count, "nation", "nations"));
  }

  /** Eight lines with an example in grey until something is typed; FlatLaf has none for areas. */
  private static JTextArea newNationsArea() {
    return new JTextArea(8, 0) {
      @Serial private static final long serialVersionUID = 1L;

      @Override
      protected void paintComponent(final Graphics g) {
        super.paintComponent(g);
        if (!getText().isEmpty()) {
          return;
        }
        final Insets insets = getInsets();
        final FontMetrics metrics = g.getFontMetrics(getFont());
        g.setColor(Theme.getPlaceholderColor());
        g.setFont(getFont());
        int y = insets.top + metrics.getAscent();
        for (final String line : PLACEHOLDER.split("\n")) {
          FlatUIUtils.drawString(this, g, line, insets.left, y);
          y += metrics.getHeight();
        }
      }
    };
  }

  /**
   * A step's page: the question, then its parts in a column from the top, each as wide as it
   * wants (a part with no width of its own, like the nations, as wide as the page).
   */
  private static JPanel newPage(final String question, final JComponent... parts) {
    final JPanel page = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.anchor = GridBagConstraints.NORTHWEST;
    c.weightx = 1;
    c.insets = new Insets(0, 0, Theme.GAP_M, 0);
    page.add(Theme.newTitle(question), c);
    for (final JComponent part : parts) {
      c.fill =
          part instanceof JScrollPane ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
      c.insets = new Insets(0, 0, Theme.GAP_S, 0);
      page.add(part, c);
    }
    c.weighty = 1;
    page.add(new JPanel(), c);
    return page;
  }

  /** The parts in a row, a small gap between them but none before the first. */
  private static JPanel spaced(final JPanel row, final JComponent... parts) {
    for (final JComponent part : parts) {
      if (row.getComponentCount() > 0) {
        row.add(Box.createHorizontalStrut(Theme.GAP_S));
      }
      row.add(part);
    }
    return row;
  }

  private static JComponent gap(final int height) {
    final JPanel gap = new JPanel();
    gap.setPreferredSize(new Dimension(0, height));
    return gap;
  }

  // as wide as the drop zone: Swing's HTML draws a CSS px as 4/3 of a screen pixel
  private static JLabel newNote(final String text) {
    final JLabel note =
        new JLabel(
            "<html><div style='width:"
                + Theme.htmlWidth(DROP_ZONE.width)
                + "'>"
                + SearchList.escape(text)
                + "</div></html>");
    note.setForeground(Theme.getSecondaryTextColor());
    return note;
  }

  private void showStep(final int shown) {
    step = shown;
    cards.show(steps, STEPS.get(step));
    for (int i = 0; i < stepLabels.size(); i++) {
      final JLabel label = stepLabels.get(i);
      label.setText((i < step ? "✓ " : (i + 1) + "  ") + STEPS.get(i));
      label.putClientProperty(FlatClientProperties.STYLE_CLASS, i == step ? "semibold" : null);
      label.setForeground(
          i == step
              ? Theme.getAccentTextColor()
              : i < step ? Theme.getSuccessColor() : Theme.getSecondaryTextColor());
    }
    showStatus(Theme.Status.TODO, " ");
    updateButtons();
  }

  private void updateButtons() {
    final boolean last = step == STEPS.size() - 1;
    cancel.setVisible(step == 0);
    back.setVisible(step > 0);
    back.setEnabled(!busy);
    skip.setVisible(step == 1 && picture == null);
    skip.setEnabled(!busy);
    next.setText(last ? "Create map" : "Next");
    next.setEnabled(
        !busy
            && switch (step) {
              case 0 -> !name.getText().isBlank() && !nameError.hasProblem();
              case 1 -> picture != null;
              default -> true;
            });
  }

  private void next() {
    if (step < STEPS.size() - 1) {
      showStep(step + 1);
    } else {
      create();
    }
  }

  private void showStatus(final Theme.Status kind, final String text) {
    status.setText(text);
    status.setForeground(kind.color());
  }

  private void choosePicture() {
    MapMakerWindow.chooseFileToOpen(
            this,
            "Choose the picture of your map",
            Path.of(System.getProperty("user.home")),
            PICTURE_ENDINGS.toArray(String[]::new))
        .ifPresent(this::readPicture);
  }

  // a big map's picture takes a moment to read; Next waits for it
  private void readPicture(final Path file) {
    working(true);
    showStatus(Theme.Status.TODO, "Reading " + file.getFileName() + "…");
    new SwingWorker<ReadPicture, Void>() {
      @Override
      protected ReadPicture doInBackground() throws IOException {
        final BufferedImage read = MapPicture.read(file);
        return new ReadPicture(read, smaller(read));
      }

      @Override
      protected void done() {
        working(false);
        try {
          final ReadPicture read = get();
          showPicture(
              read.picture(),
              read.preview(),
              file.getFileName()
                  + " · "
                  + read.picture().getWidth()
                  + " × "
                  + read.picture().getHeight());
          showStatus(Theme.Status.TODO, " ");
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
          showStatus(
              Theme.Status.PROBLEM, "Could not read the picture: " + e.getCause().getMessage());
        }
      }
    }.execute();
  }

  /** The picture in the zone with its name and size below it, or the empty zone again. */
  private void showPicture(
      @Nullable final BufferedImage shown,
      @Nullable final BufferedImage small,
      final String description) {
    picture = shown;
    preview.setIcon(small == null ? null : new ImageIcon(small));
    zoneCards.show(zone, shown == null ? "empty" : "picture");
    pictureLine.setText(description);
    pictureRow.setVisible(shown != null);
    skipNote.setVisible(shown == null);
    updateButtons();
  }

  /** A picture as read and as the preview shows it. */
  private record ReadPicture(BufferedImage picture, BufferedImage preview) {}

  // the whole picture inside the zone, never larger than it is
  private static BufferedImage smaller(final BufferedImage picture) {
    final double scale =
        Math.min(
            1,
            Math.min(
                (DROP_ZONE.width - 8.0) / picture.getWidth(),
                (DROP_ZONE.height - 8.0) / picture.getHeight()));
    final int width = Math.max(1, (int) (picture.getWidth() * scale));
    final int height = Math.max(1, (int) (picture.getHeight() * scale));
    final BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = small.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(picture, 0, 0, width, height, null);
    g.dispose();
    return small;
  }

  private void working(final boolean now) {
    busy = now;
    setCursor(now ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    updateButtons();
  }

  /** What Create makes from the steps as they are now; it may run on any thread. */
  Callable<NewMap> making() {
    final String mapName = name.getText().strip();
    final BufferedImage chosen = picture;
    final List<String> playing = nations.getText().lines().toList();
    final Path folder = location;
    return () -> MapProject.newMap(mapName, chosen, playing, folder);
  }

  /** Makes and saves the map in the background, as finding territories and tiles takes a while. */
  private void create() {
    final Callable<NewMap> making = making();
    working(true);
    // the map is written whole or not at all: no closing halfway
    window()
        .ifPresent(dialog -> dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE));
    progress.setVisible(true);
    showStatus(Theme.Status.TODO, "Creating the map: finding territories and writing files…");
    new SwingWorker<NewMap, Void>() {
      @Override
      protected NewMap doInBackground() throws Exception {
        return making.call();
      }

      @Override
      protected void done() {
        window()
            .ifPresent(dialog -> dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE));
        // the buttons come back whatever happened, so the wizard is never stuck
        working(false);
        progress.setVisible(false);
        showStatus(Theme.Status.TODO, " ");
        try {
          created.accept(get());
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
          log.info("Could not create the map {}", name.getText(), e.getCause());
          Dialogs.warn(
              NewMapWizard.this,
              "New map",
              "Could not create the map: " + e.getCause().getMessage());
        }
      }
    }.execute();
  }
}
