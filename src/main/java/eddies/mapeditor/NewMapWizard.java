package eddies.mapeditor;

import games.strategy.engine.ClientFileSystemHelper;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import lombok.extern.slf4j.Slf4j;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MapProject.NewMap;

/**
 * The steps to a new map: its name, its picture, in which the territories are found, and the
 * nations that play it. Create makes and saves the map ({@link MapProject#newMap}) in the
 * background and hands it on.
 */
@Slf4j
final class NewMapWizard extends JDialog {
  @Serial private static final long serialVersionUID = 1L;

  private static final List<String> STEPS = List.of("Name", "Picture", "Nations");
  private static final int PREVIEW_WIDTH = 420;

  private final transient Consumer<NewMap> created;
  private final CardLayout cards = new CardLayout();
  private final JPanel steps = new JPanel(cards);
  private final JLabel stepLabel = new JLabel();
  private final JLabel status = new JLabel(" ");
  private final JButton back = new JButton("Back");
  private final JButton next = new JButton("Next");
  private final JTextField name = new JTextField(24);
  private final JLabel preview = new JLabel("No picture yet", SwingConstants.CENTER);
  private final JTextArea nations = new JTextArea(8, 24);
  private transient @Nullable BufferedImage picture;
  private int step;

  private NewMapWizard(final Frame owner, final Consumer<NewMap> created) {
    super(owner, "Create a new map", true);
    this.created = created;
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    steps.add(newNameStep(), STEPS.get(0));
    steps.add(newPictureStep(), STEPS.get(1));
    steps.add(newNationsStep(), STEPS.get(2));
    stepLabel.setForeground(Theme.getSecondaryTextColor());
    status.setForeground(Theme.getSecondaryTextColor());
    back.addActionListener(e -> showStep(step - 1));
    next.addActionListener(e -> next());
    Theme.setPrimaryButtonStyle(next);
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    buttons.add(back);
    buttons.add(next);
    final JPanel footer = new JPanel(new BorderLayout(12, 0));
    footer.setBorder(Theme.newFooterBorder());
    footer.add(status, BorderLayout.CENTER);
    footer.add(buttons, BorderLayout.EAST);
    final JPanel content = new JPanel(new BorderLayout(0, 12));
    content.setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));
    content.add(stepLabel, BorderLayout.NORTH);
    content.add(steps, BorderLayout.CENTER);
    content.add(footer, BorderLayout.SOUTH);
    setContentPane(content);
    setMinimumSize(new Dimension(560, 520));
    showStep(0);
    pack();
    setLocationRelativeTo(owner);
  }

  /** Shows the wizard; once the map is made, hands it on. */
  static void show(final Frame owner, final Consumer<NewMap> created) {
    new NewMapWizard(owner, created).setVisible(true);
  }

  private JComponent newNameStep() {
    final JPanel page = newPage("What is your map called?");
    final JPanel field = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    field.add(name);
    page.add(field);
    page.add(
        newNote(
            "The map gets a folder of this name in your maps folder, where the game finds it."));
    return page;
  }

  private JComponent newPictureStep() {
    final JPanel page = newPage("Choose a picture of your map");
    page.add(
        newNote(
            "Territories with dark lines between them, the seas in blue. The map maker finds the"
                + " territories, sea zones and their neighbours in it. You can also add it later."));
    final JButton choose = new JButton("Choose picture...");
    choose.addActionListener(e -> choosePicture());
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.add(choose);
    page.add(row);
    preview.setPreferredSize(new Dimension(PREVIEW_WIDTH, 220));
    preview.setBorder(BorderFactory.createLineBorder(Theme.getSeparatorColor()));
    page.add(preview);
    return page;
  }

  private JComponent newNationsStep() {
    final JPanel page = newPage("Who plays on it?");
    page.add(newNote("One nation per line, eg Germans. You can add more later in Nations."));
    page.add(SwingComponents.newJScrollPane(nations));
    return page;
  }

  /** A step's page: the question, then what the step adds below it, all on the left. */
  private static JPanel newPage(final String question) {
    final JPanel page =
        new JPanel() {
          @Serial private static final long serialVersionUID = 1L;

          @Override
          protected void addImpl(final Component child, final Object constraints, final int at) {
            ((JComponent) child).setAlignmentX(LEFT_ALIGNMENT);
            super.addImpl(child, constraints, at);
          }
        };
    page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
    final JLabel title = new JLabel(question);
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() * 1.4f));
    page.add(title);
    return page;
  }

  private static JLabel newNote(final String text) {
    final JLabel note =
        new JLabel("<html><div style='width:420px'>" + SearchList.escape(text) + "</div></html>");
    note.setForeground(Theme.getSecondaryTextColor());
    note.setBorder(BorderFactory.createEmptyBorder(8, 0, 12, 0));
    return note;
  }

  private void showStep(final int shown) {
    step = shown;
    cards.show(steps, STEPS.get(step));
    stepLabel.setText("Step " + (step + 1) + " of " + STEPS.size() + ": " + STEPS.get(step));
    back.setEnabled(step > 0);
    next.setText(step == STEPS.size() - 1 ? "Create map" : "Next");
    status.setText(" ");
  }

  private void next() {
    if (step == 0) {
      final Optional<String> refused =
          MapFolder.whyNoNewMap(ClientFileSystemHelper.getUserMapsFolder(), name.getText());
      if (refused.isPresent()) {
        status.setText(refused.get());
        return;
      }
    }
    if (step < STEPS.size() - 1) {
      showStep(step + 1);
    } else {
      create();
    }
  }

  private void choosePicture() {
    final Optional<Path> file =
        MapMakerWindow.chooseFileToOpen(
            this,
            "Choose the picture of your map",
            Path.of(System.getProperty("user.home")),
            "png",
            "jpg",
            "jpeg",
            "gif");
    if (file.isEmpty()) {
      return;
    }
    // a big map's picture takes a moment to read; Next waits for it
    next.setEnabled(false);
    status.setText("Reading " + file.get().getFileName() + "...");
    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new SwingWorker<ReadPicture, Void>() {
      @Override
      protected ReadPicture doInBackground() throws IOException {
        final BufferedImage read = MapPicture.read(file.get());
        return new ReadPicture(read, smaller(read));
      }

      @Override
      protected void done() {
        setCursor(Cursor.getDefaultCursor());
        next.setEnabled(true);
        try {
          final ReadPicture read = get();
          picture = read.picture();
          preview.setText(null);
          preview.setIcon(new ImageIcon(read.preview()));
          status.setText(
              file.get().getFileName()
                  + ", "
                  + read.picture().getWidth()
                  + "×"
                  + read.picture().getHeight());
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
          status.setText("Could not read the picture: " + e.getCause().getMessage());
        }
      }
    }.execute();
  }

  /** A picture as read and as the preview shows it. */
  private record ReadPicture(BufferedImage picture, BufferedImage preview) {}

  private static BufferedImage smaller(final BufferedImage picture) {
    final int height = Math.max(1, picture.getHeight() * PREVIEW_WIDTH / picture.getWidth());
    final BufferedImage small =
        new BufferedImage(PREVIEW_WIDTH, height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = small.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(picture, 0, 0, PREVIEW_WIDTH, height, null);
    g.dispose();
    return small;
  }

  /** Makes and saves the map in the background, as finding territories and tiles takes a while. */
  private void create() {
    final String mapName = name.getText().strip();
    final BufferedImage chosen = picture;
    final List<String> playing = nations.getText().lines().toList();
    back.setEnabled(false);
    next.setEnabled(false);
    // the map is written whole or not at all: no closing halfway
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    status.setText("Creating the map: finding its territories and writing its files...");
    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new SwingWorker<NewMap, Void>() {
      @Override
      protected NewMap doInBackground() throws Exception {
        return MapProject.newMap(
            mapName, chosen, playing, ClientFileSystemHelper.getUserMapsFolder());
      }

      @Override
      protected void done() {
        setCursor(Cursor.getDefaultCursor());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        // the buttons come back whatever happened, so the wizard is never stuck
        back.setEnabled(true);
        next.setEnabled(true);
        status.setText(" ");
        try {
          final NewMap made = get();
          dispose();
          created.accept(made);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
          log.info("Could not create the map {}", mapName, e.getCause());
          JOptionPane.showMessageDialog(
              NewMapWizard.this,
              "Could not create the map:\n" + e.getCause().getMessage(),
              "Create a new map",
              JOptionPane.WARNING_MESSAGE);
        }
      }
    }.execute();
  }
}
