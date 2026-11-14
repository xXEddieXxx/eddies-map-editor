package eddies.mapeditor;

import com.formdev.flatlaf.util.UIScale;
import eddies.mapeditor.MakerIcon.Glyph;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.OverlayLayout;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.triplea.swing.FileChooser;
import org.triplea.swing.SwingComponents;

/**
 * The map that Territories and Connections share: one toolbar above it, on a background of its
 * own, left the views ({@link MapViewsBar}) and an editor's own tools, fitted by {@link
 * ToolbarLayout}, eg its brush, right the map's controls: zoom, Fit and the picture menu
 * (picture on or off, load another, redraw borders, make the territories again); below the row a
 * second one of a fixed height, empty or with the banner of a mode a tool has on, {@link
 * #showBanner}, the goal it works towards above its text, {@link #showStep}, so the map keeps its
 * place; below the map the legend, {@link MapLegend}. Without outlines the map shows what to
 * do next instead, without toolbar and legend.
 */
final class MapView extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  private static final double ZOOM_STEP = 1.25;
  // the banner's row is always there, also empty: the map below never moves when a mode's banner
  // or the goal shows or hides
  static final int BANNER_HEIGHT = 40;
  private static final String EDIT_MODE = "Edit mode";
  // the veil over a picture without territories yet: the map's background at 70 %
  private static final Color VEIL =
      new Color(
          MapCanvas.MAP_BACKGROUND.getRed(),
          MapCanvas.MAP_BACKGROUND.getGreen(),
          MapCanvas.MAP_BACKGROUND.getBlue(),
          178);
  // the middle's state while the map has a picture but no territories found in it
  private static final String PICTURE_ONLY = "picture";

  private final transient MapProject project;
  private final transient Runnable choosePicture;
  private final transient Runnable findTerritories;
  private final MapCanvas canvas;

  private final MapViewsBar views;
  // the territory the tool chose, shown again when the map maker leaves Neighbours
  private @Nullable String chosen;
  private final MapLegend legend;
  private final JPanel top = new JPanel(new BorderLayout());
  private final JPanel editorTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
  private final JSeparator beforeEditorTools = new JSeparator(SwingConstants.VERTICAL);
  private final JPanel banner = new JPanel(new BorderLayout(Theme.GAP_M, 0));
  private final JPanel bannerLine = new JPanel();
  private final JLabel bannerText = new JLabel();
  private final JLabel editBadge = newEditBadge();
  private final JButton bannerDone = new JButton("Done");
  private final JScrollPane scroll;
  private final GoalStrip goal;
  private transient Consumer<MapGuide.Step.Id> followStep = step -> {};
  private final JMenuItem findAgain = new JMenuItem("Make territories again…");
  // what the middle shows: "" the map, PICTURE_ONLY, else the number of territories without map;
  // built anew only when it changes
  private Optional<String> shownState = Optional.empty();
  // the map's size when fitted last: lines drawn into the picture keep the view where it is
  private transient Optional<Dimension> fittedTo = Optional.empty();
  // an opened map is fitted once its view has a size, ie when a tool first shows it; after that
  // the map maker's own zoom and scrolling stay
  private boolean fitPending = true;

  /**
   * @param choosePicture asks for a picture of the map and loads it
   * @param findTerritories finds the territories in the picture, replacing the map's
   * @param redrawBorders asks for a picture with changed borders and redraws the territories' from
   *     it
   */
  MapView(
      final MapProject project,
      final Runnable choosePicture,
      final Runnable findTerritories,
      final Runnable redrawBorders) {
    // the map right under the toolbar's line; the legend keeps its own gap above it
    super(new BorderLayout());
    this.project = project;
    this.choosePicture = choosePicture;
    this.findTerritories = findTerritories;
    canvas = new MapCanvas(project);
    scroll = SwingComponents.newJScrollPane(canvas);
    scroll.getVerticalScrollBar().setUnitIncrement(24);
    scroll.getHorizontalScrollBar().setUnitIncrement(24);
    scroll
        .getViewport()
        .addComponentListener(
            new ComponentAdapter() {
              @Override
              public void componentResized(final ComponentEvent e) {
                fitOnce();
              }
            });
    views = new MapViewsBar(MapMode.GAME, this::viewChosen);
    legend = new MapLegend(project);
    goal = new GoalStrip(project);
    final JButton zoomShown = new JButton();
    Theme.setBorderlessButtonStyle(zoomShown);
    zoomShown.setMargin(new Insets(0, 0, 0, 0));
    // as wide as the widest it shows, 300 %, so it never cuts to "50..."
    zoomShown.setText("300 %");
    zoomShown.setPreferredSize(
        new Dimension(zoomShown.getPreferredSize().width, UIScale.scale(Theme.MAP_TOOL)));
    zoomShown.addActionListener(e -> canvas.zoomBy(1 / canvas.getZoom()));
    showZoom(zoomShown, canvas.getZoom());
    canvas.addPropertyChangeListener("zoom", e -> showZoom(zoomShown, (Double) e.getNewValue()));
    final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_XS, 0));
    tools.setOpaque(false);
    final JButton fit = Theme.newIconButton(Glyph.FIT, "Fit the map (Ctrl+0)", canvas::zoomToFit);
    // the glyph as big as the picture menu's, not the text's size
    fit.setIcon(new MakerIcon(Glyph.FIT, Theme.MAP_TOOL_ICON));
    for (final JButton button :
        List.of(
            Theme.newIconButton("−", "Zoom out (Ctrl+wheel)", () -> canvas.zoomBy(1 / ZOOM_STEP)),
            zoomShown,
            Theme.newIconButton("+", "Zoom in (Ctrl+wheel)", () -> canvas.zoomBy(ZOOM_STEP)),
            fit)) {
      if (button != zoomShown) {
        final int size = UIScale.scale(Theme.MAP_TOOL);
        button.setPreferredSize(new Dimension(size, size));
      }
      tools.add(button);
    }
    tools.add(newPictureButton(redrawBorders));
    getInputMap(WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK), "fit");
    getActionMap()
        .put(
            "fit",
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                canvas.zoomToFit();
              }
            });
    top.add(newToolbar(tools), BorderLayout.NORTH);
    top.add(newBanner(), BorderLayout.SOUTH);
    add(top, BorderLayout.NORTH);
    add(legend, BorderLayout.SOUTH);
    showMapOrEmptyState();
    project.onChange(this::showMapOrEmptyState);
  }

  /** The row above the map, laid out by {@link ToolbarLayout}. */
  private JPanel newToolbar(final JComponent mapTools) {
    editorTools.setOpaque(false);
    beforeEditorTools.setPreferredSize(
        new Dimension(beforeEditorTools.getPreferredSize().width, UIScale.scale(24)));
    final JPanel row = new JPanel();
    row.setLayout(new ToolbarLayout(row, mapTools));
    row.setBackground(Theme.getMapToolbarColor());
    // its line is below the banner's row, so toolbar and an empty row read as one
    row.setBorder(BorderFactory.createEmptyBorder(6, Theme.GAP_S, 7, Theme.GAP_XS));
    row.add(views);
    row.add(beforeEditorTools);
    row.add(editorTools);
    row.add(mapTools);
    return row;
  }

  /**
   * The toolbar's layout: the views, a line, the editor's tools, the map's tools at the right, all
   * in one row as long as it fits; shorter of room first the views as one button with a menu, so
   * the editor's tools keep their names, then those named tools as their glyphs ({@link
   * Theme#setNamedMapToolStyle}). If even that does not fit, it wraps: the editor's tools, then
   * the map's, on lines of their own, so nothing is cut or lies over another.
   */
  private final class ToolbarLayout implements LayoutManager {
    private static final List<Stage> STAGES =
        List.of(
            new Stage(MapViewsBar.Shape.TOGGLES, true),
            new Stage(MapViewsBar.Shape.DROPDOWN, true),
            new Stage(MapViewsBar.Shape.DROPDOWN, false));

    private final JPanel row;
    private final JComponent mapTools;
    // the lines laid out last, for the row's height
    private int lines = 1;

    private record Stage(MapViewsBar.Shape views, boolean toolNames) {}

    ToolbarLayout(final JPanel row, final JComponent mapTools) {
      this.row = row;
      this.mapTools = mapTools;
    }

    @Override
    public void addLayoutComponent(final String name, final Component component) {}

    @Override
    public void removeLayoutComponent(final Component component) {}

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
      final Insets insets = parent.getInsets();
      final int height = UIScale.scale(Theme.MAP_TOOL) * lines + Theme.GAP_XS * (lines - 1);
      return new Dimension(
          insets.left + insets.right + width(STAGES.getFirst()),
          insets.top + insets.bottom + height);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
      return new Dimension(0, preferredLayoutSize(parent).height);
    }

    @Override
    public void layoutContainer(final Container parent) {
      final Insets insets = parent.getInsets();
      final int room = parent.getWidth() - insets.left - insets.right;
      final Stage stage =
          STAGES.stream().filter(s -> width(s) <= room).findFirst().orElse(STAGES.getLast());
      views.show(stage.views());
      named(editorTools).forEach(tool -> showTool(tool, stage.toolNames()));
      final int lineHeight = UIScale.scale(Theme.MAP_TOOL);
      final int right = parent.getWidth() - insets.right;
      int y = insets.top;
      int laidLines = 1;
      int x = place(views, insets.left, y);
      if (editorTools.isVisible()) {
        final boolean sameLine = x + editorToolsWidth() <= right;
        if (beforeEditorTools.isVisible() != sameLine) {
          beforeEditorTools.setVisible(sameLine);
        }
        if (sameLine) {
          final int separatorY = y + (lineHeight - beforeEditorTools.getPreferredSize().height) / 2;
          x = place(beforeEditorTools, x + Theme.GAP_S, separatorY);
          x = place(editorTools, x + Theme.GAP_S, y);
        } else {
          y += lineHeight + Theme.GAP_XS;
          laidLines++;
          x = place(editorTools, insets.left, y);
        }
      }
      final int toolsWidth = mapTools.getPreferredSize().width;
      if (x + Theme.GAP_S + toolsWidth > right) {
        y += lineHeight + Theme.GAP_XS;
        laidLines++;
      }
      mapTools.setBounds(Math.max(insets.left, right - toolsWidth), y, toolsWidth, lineHeight);
      if (laidLines != lines) {
        lines = laidLines;
        // the row's height follows once this layout is done
        SwingUtilities.invokeLater(row::revalidate);
      }
    }

    /** Lays the component out at x, as big as it likes; where the next one may start. */
    private static int place(final Component component, final int x, final int y) {
      final Dimension size = component.getPreferredSize();
      component.setBounds(x, y, size.width, size.height);
      return x + size.width;
    }

    /** The line before the editor's tools and they, with their gaps. */
    private int editorToolsWidth() {
      return Theme.GAP_S
          + beforeEditorTools.getPreferredSize().width
          + Theme.GAP_S
          + editorTools.getPreferredSize().width;
    }

    /** The width of all in one line at this stage, measured without changing anything. */
    private int width(final Stage stage) {
      int width = views.width(stage.views()) + Theme.GAP_S + mapTools.getPreferredSize().width;
      if (editorTools.isVisible()) {
        width += editorToolsWidth();
        for (final AbstractButton tool : named(editorTools).toList()) {
          width += toolWidth(tool, stage.toolNames()) - tool.getPreferredSize().width;
        }
      }
      return width;
    }

    /** The editor's tools shown by name or glyph, eg Territories' Select. */
    private static Stream<AbstractButton> named(final Component component) {
      if (component instanceof AbstractButton button && Theme.mapToolName(button).isPresent()) {
        return Stream.of(button);
      }
      return component instanceof Container container
          ? Stream.of(container.getComponents()).flatMap(ToolbarLayout::named)
          : Stream.empty();
    }

    private static int toolWidth(final AbstractButton tool, final boolean byName) {
      return byName
          ? Theme.mapToolWidth(
              tool,
              Theme.mapToolGlyph(tool).filter(glyph -> Theme.mapToolKeepsGlyph(tool)),
              Theme.mapToolName(tool).orElseThrow())
          : Theme.mapToolWidth(tool, Theme.mapToolGlyph(tool), glyphText(tool));
    }

    /** Beside its glyph a tool that opens a menu keeps the arrow of its name, eg Places. */
    private static String glyphText(final AbstractButton tool) {
      return Theme.mapToolName(tool)
          .filter(name -> name.endsWith("▾"))
          .map(name -> "▾")
          .orElse("");
    }

    private static void showTool(final AbstractButton tool, final boolean byName) {
      final String text = byName ? Theme.mapToolName(tool).orElseThrow() : glyphText(tool);
      if (!text.equals(tool.getText())) {
        tool.setText(text);
        tool.setIcon(
            byName && !Theme.mapToolKeepsGlyph(tool)
                ? null
                : Theme.mapToolGlyph(tool).orElseThrow());
        Theme.fitMapTool(tool);
      }
    }
  }

  private static void showZoom(final JButton zoomShown, final double zoom) {
    final String percent = Math.round(zoom * 100) + " %";
    zoomShown.setText(percent);
    zoomShown.setToolTipText("Zoom " + percent + ". Click for 100 %");
  }

  /** The picture button: its menu shows the picture or not, loads another, redraws, finds again. */
  private JButton newPictureButton(final Runnable redrawBorders) {
    final JCheckBoxMenuItem showPicture = new JCheckBoxMenuItem("Show the map picture", true);
    showPicture.addActionListener(
        e -> {
          if (showPicture.isSelected()) {
            canvas.showPicture();
          } else {
            canvas.hidePicture();
          }
        });
    final JPopupMenu pictureMenu = new JPopupMenu();
    pictureMenu.add(showPicture);
    pictureMenu.addSeparator();
    pictureMenu.add("Load another picture…").addActionListener(e -> choosePicture.run());
    final JMenuItem redraw = pictureMenu.add("Redraw borders from a picture…");
    redraw.setToolTipText(
        "Draw the new borders as dark lines into the map's picture with a paint program, then"
            + " choose it here: the territories take the borders, keeping names, owners and units;"
            + " new areas become territories, merged ones are joined");
    redraw.addActionListener(e -> redrawBorders.run());
    findAgain.addActionListener(e -> findTerritories.run());
    pictureMenu.add(findAgain);
    final JMenuItem resize = pictureMenu.add("Resize map…");
    resize.setToolTipText(
        "Makes the whole map bigger or smaller: picture, borders and places. Units keep their"
            + " size, so a bigger map gives them more room");
    resize.addActionListener(e -> MapMakerWindow.resizeMap(this));
    final JMenuItem saveAs = pictureMenu.add("Save the map's picture as a file…");
    saveAs.setToolTipText(
        "The whole picture, without the relief, put together from its tiles in baseTiles: to"
            + " change it in a paint program, then Load another picture or Redraw borders");
    saveAs.addActionListener(e -> savePicture());
    final JButton picture = Theme.newIconButton(Glyph.PICTURE, "Map picture", () -> {});
    picture.setText("▾");
    picture.setIcon(new MakerIcon(Glyph.PICTURE, Theme.MAP_TOOL_ICON));
    // icon and arrow: wider than an icon button's square
    Theme.fitMapTool(picture);
    // also on a right-click and Shift+F10
    picture.setComponentPopupMenu(pictureMenu);
    picture.addActionListener(e -> pictureMenu.show(picture, 0, picture.getHeight()));
    return picture;
  }

  /** Asks where to, then writes the map's picture there in the background, as it may be big. */
  private void savePicture() {
    final MapFiles files = project.getFiles();
    if (files.picture().isEmpty() && files.folder().isEmpty()) {
      Dialogs.toast(this, "! The map has no picture yet");
      return;
    }
    final Optional<Path> chosen =
        FileChooser.builder()
            .parent((Frame) SwingUtilities.getAncestorOfClass(Frame.class, this))
            .title("Save the map's picture")
            .mode(FileDialog.SAVE)
            .directory(Path.of(System.getProperty("user.home")))
            .fileName(project.name().replaceAll("[^A-Za-z0-9_-]+", "_") + ".png")
            .fileExtension(".png")
            .build()
            .chooseFile();
    if (chosen.isEmpty()) {
      return;
    }
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws IOException {
        MapFolder.savePicture(files, chosen.get());
        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          Dialogs.toast(MapView.this, "✓ Saved the map's picture as " + chosen.get());
        } catch (final InterruptedException | ExecutionException e) {
          Dialogs.warn(
              MapView.this,
              "Save the map's picture",
              "Could not save the picture.\n"
                  + Optional.ofNullable(e.getCause()).orElse(e).getMessage());
        }
      }
    }.execute();
  }

  /** "Edit mode" in white on red, the danger color, as the lines being edited are. */
  private static JLabel newEditBadge() {
    final JLabel badge = new JLabel(EDIT_MODE);
    badge.setOpaque(true);
    badge.setBackground(Theme.getDangerColor());
    badge.setForeground(Color.WHITE);
    badge.setFont(badge.getFont().deriveFont(Font.BOLD));
    badge.setBorder(BorderFactory.createEmptyBorder(2, Theme.GAP_S, 2, Theme.GAP_S));
    return badge;
  }

  /**
   * The row of the strip that says what a click does in a tool's mode, with Done to end it; the
   * strip hidden, its row there.
   */
  private JPanel newBanner() {
    banner.setBackground(Theme.getSelectionColor());
    banner.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_M, 0, Theme.GAP_XS));
    bannerLine.setLayout(new BoxLayout(bannerLine, BoxLayout.X_AXIS));
    bannerLine.setOpaque(false);
    // the goal's line above the text, the buttons beside both
    final JPanel lines = new JPanel(new BorderLayout());
    lines.setOpaque(false);
    lines.add(goal, BorderLayout.NORTH);
    lines.add(bannerLine, BorderLayout.CENTER);
    banner.add(lines, BorderLayout.CENTER);
    Theme.setSecondaryButtonStyle(bannerDone);
    final JPanel done = new JPanel(new GridBagLayout());
    done.setOpaque(false);
    final GridBagConstraints beforeDone = new GridBagConstraints();
    beforeDone.insets = new Insets(0, 0, 0, Theme.GAP_XS);
    done.add(goal.next(), beforeDone);
    done.add(bannerDone);
    banner.add(done, BorderLayout.EAST);
    banner.setVisible(false);
    final JPanel row = new JPanel(new BorderLayout());
    row.setBackground(Theme.getMapToolbarColor());
    row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.getMapToolbarLineColor()));
    row.setPreferredSize(new Dimension(0, BANNER_HEIGHT));
    row.add(banner);
    return row;
  }

  /**
   * The view the map maker chose; Neighbours starts with no territory chosen, so it shows all
   * connections until a click chooses one; another view shows the tool's choice again.
   */
  private void viewChosen(final MapMode mode) {
    showMode(mode);
    canvas.setSelected(mode == MapMode.CONNECTIONS ? null : chosen);
  }

  /** The map and its legend in this view. */
  private void showMode(final MapMode mode) {
    canvas.setMode(mode);
    legend.explain(mode);
  }

  /**
   * The map once it has outlines, else what to do next: load a picture, or find the territories in
   * the one loaded, over the picture veiled; fitted to the view when it is new and of another
   * size, eg not after a line drawn into it.
   */
  private void showMapOrEmptyState() {
    final Optional<BufferedImage> picture = project.getFiles().picture();
    // only a picture loaded here can be searched: an opened map has its tiles instead
    findAgain.setEnabled(picture.isPresent());
    findAgain.setToolTipText(
        picture.isPresent()
            ? null
            : "Only a picture loaded here can be searched. An opened map has tiles instead.");
    final String state;
    if (project.getFiles().hasOutlines()) {
      state = "";
    } else if (picture.isPresent()) {
      state = PICTURE_ONLY;
    } else {
      state = String.valueOf(MapXml.territories(project.game()).size());
    }
    final boolean hadMap = SwingUtilities.isDescendingFrom(scroll, this);
    if (!shownState.equals(Optional.of(state))) {
      shownState = Optional.of(state);
      Optional.ofNullable(
              ((BorderLayout) getLayout()).getLayoutComponent(this, BorderLayout.CENTER))
          .ifPresent(this::remove);
      add(middle(state), BorderLayout.CENTER);
      top.setVisible(state.isEmpty());
      legend.setVisible(state.isEmpty());
      revalidate();
      repaint();
    }
    // fitted again only for a picture of another size than the one before, not after a line drawn
    // into it, nor once a picture in tiles is held to draw into
    final Optional<Dimension> size = picture.map(p -> new Dimension(p.getWidth(), p.getHeight()));
    final boolean newSize = fittedTo.isPresent() && size.isPresent() && !size.equals(fittedTo);
    size.ifPresent(s -> fittedTo = Optional.of(s));
    if (SwingUtilities.isDescendingFrom(scroll, this) && (!hadMap || newSize)) {
      fitPending = true;
      // fit once the view has its size
      SwingUtilities.invokeLater(this::fitOnce);
    }
  }

  /** Fits the map into the view, at most at 100 %, if that is due and the view has a size. */
  private void fitOnce() {
    final Dimension view = scroll.getViewport().getExtentSize();
    if (fitPending && view.width > 0 && view.height > 0 && project.getFiles().size().width > 0) {
      fitPending = false;
      canvas.zoomToFit(1);
    }
  }

  /** The map, or the empty state of the state, see {@link #showMapOrEmptyState}. */
  private JComponent middle(final String state) {
    if (state.isEmpty()) {
      return scroll;
    }
    if (state.equals(PICTURE_ONLY)) {
      return overPicture(
          Panels.empty(
              Glyph.TERRITORIES,
              "Picture loaded",
              "Next, make the territories, sea zones and their neighbours from it."
                  + " Areas between dark lines become territories, blue ones sea zones.",
              "Make territories",
              findTerritories));
    }
    final int territories = Integer.parseInt(state);
    return territories == 0
        ? Panels.empty(
            Glyph.PICTURE,
            "No map picture yet",
            "Load a picture of your map: dark lines between territories, seas in blue. The editor"
                + " then makes the territories from it.",
            "Load map picture…",
            choosePicture)
        : Panels.empty(
            Glyph.PICTURE,
            territories
                + (territories == 1 ? " territory" : " territories")
                + ", but no map picture",
            "Load the map's picture to see and click them. You can still change them in the list.",
            "Load map picture…",
            choosePicture);
  }

  /**
   * The empty state on a card over the picture, which shows through a veil, so the map maker sees
   * it is in; below the card's action, loading another picture.
   */
  private JComponent overPicture(final JComponent empty) {
    // the card's margin instead of the empty state's wide one
    empty.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_XL, Theme.GAP_XL, Theme.GAP_XS, Theme.GAP_XL));
    final JButton another = new JButton("Load another picture…");
    Theme.setTertiaryButtonStyle(another);
    another.setAlignmentX(Component.CENTER_ALIGNMENT);
    another.addActionListener(e -> choosePicture.run());
    final JPanel card = new JPanel();
    card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
    Theme.setCardStyle(card);
    card.add(empty);
    card.add(another);
    card.add(Box.createVerticalStrut(Theme.GAP_M));
    final JPanel veil =
        new JPanel(new GridBagLayout()) {
          @Serial private static final long serialVersionUID = 1L;

          @Override
          protected void paintComponent(final Graphics g) {
            g.setColor(VEIL);
            g.fillRect(0, 0, getWidth(), getHeight());
          }
        };
    veil.setOpaque(false);
    veil.add(card);
    final JPanel layers =
        new JPanel() {
          @Serial private static final long serialVersionUID = 1L;

          // the veil and the card lie over the map: paint them again when the map repaints
          @Override
          public boolean isOptimizedDrawingEnabled() {
            return false;
          }
        };
    layers.setLayout(new OverlayLayout(layers));
    layers.add(veil);
    layers.add(scroll);
    return layers;
  }

  /** Takes the map for an editor: its mode, its tools above the map, what a click does. */
  void useFor(final MapMode mapMode, final JComponent tools, final Consumer<String> onClick) {
    views.showChosen(mapMode);
    showMode(mapMode);
    editorTools.removeAll();
    tools.setOpaque(false);
    editorTools.add(tools);
    // a tool without controls of its own takes no room in the toolbar
    editorTools.setVisible(tools.getComponentCount() > 0);
    beforeEditorTools.setVisible(editorTools.isVisible());
    editorTools.revalidate();
    editorTools.repaint();
    legend.showNationsLink();
    canvas.onTerritoryClick(onClick);
    canvas.stopEditingPlaces();
    canvas.setMarked(Set.of());
    canvas.showPlaces(MapCanvas.ShownPlaces.NONE);
    canvas.hideEffects();
    canvas.hideTextures();
    hideBanner();
  }

  /**
   * Shows below the tools what a click or drag does now, until done: Done and Esc run done and hide
   * the banner, as does another editor taking the map.
   */
  void showBanner(final String text, final Runnable done) {
    showBanner(text, Optional.empty(), "Done", done);
  }

  /**
   * Like {@link #showBanner(String, Runnable)}, with extra after the text, eg a picture to choose,
   * and the verb on the button, eg "Cancel"; called again, it replaces text and extra.
   */
  void showBanner(
      final String text, final Optional<JComponent> extra, final String verb, final Runnable done) {
    showBanner(text, extra, verb, done, false);
  }

  /**
   * Like {@link #showBanner(String, Runnable)} for a mode that changes the map with each click, eg
   * Connections' connecting: the banner starts with an "Edit mode" badge.
   */
  void showEditBanner(final String text, final Runnable done) {
    editBadge.setText(EDIT_MODE);
    showBanner(text, Optional.empty(), "Done", done, true);
  }

  /**
   * Like {@link #showEditBanner(String, Runnable)}, with the badge saying what the mode changes,
   * eg "Edits the picture", so that stays readable when the text is cut, and extra after the text.
   */
  void showEditBanner(
      final String badge, final String text, final JComponent extra, final Runnable done) {
    editBadge.setText(badge);
    showBanner(text, Optional.of(extra), "Done", done, true);
  }

  private void showBanner(
      final String text,
      final Optional<JComponent> extra,
      final String verb,
      final Runnable done,
      final boolean edit) {
    bannerText.setText(text);
    bannerText.setToolTipText(text);
    // short of room the text is cut ("…", whole in its tool tip), never the controls after it
    bannerText.setMinimumSize(new Dimension(0, bannerText.getPreferredSize().height));
    bannerLine.removeAll();
    if (edit) {
      bannerLine.add(editBadge);
      bannerLine.add(Box.createHorizontalStrut(Theme.GAP_M));
    }
    bannerLine.add(bannerText);
    extra.ifPresent(
        component -> {
          component.setMinimumSize(component.getPreferredSize());
          // centered in the row like the text, at its own width
          component.setMaximumSize(component.getPreferredSize());
          bannerLine.add(Box.createHorizontalStrut(Theme.GAP_M));
          bannerLine.add(component);
        });
    bannerDone.setText(verb);
    final Runnable end =
        () -> {
          hideBanner();
          done.run();
        };
    for (final ActionListener listener : bannerDone.getActionListeners()) {
      bannerDone.removeActionListener(listener);
    }
    bannerDone.addActionListener(e -> end.run());
    canvas.onEscape(Optional.of(end));
    banner.setVisible(true);
    fitBanner();
  }

  void hideBanner() {
    endGoal();
    canvas.onEscape(Optional.empty());
    banner.setVisible(false);
    revalidate();
    repaint();
  }

  /** The banner's text while it shows. */
  Optional<String> bannerText() {
    return banner.isVisible() ? Optional.of(bannerText.getText()) : Optional.empty();
  }

  /**
   * Shows above the banner the step to a playable map the map maker works on, with a "Next" once
   * it is done, until the banner hides; without a banner, one that says what to do for it.
   */
  void showStep(final MapGuide.Step.Id step) {
    if (!banner.isVisible()) {
      showBanner(MapGuide.howTo(step), () -> {});
    }
    goal.showStep(step);
    fitBanner();
  }

  /** Like {@link #showStep}, a tool's own goal, eg "Back to Terrain effects", over a banner. */
  void showGoal(
      final String title, final String progress, final String action, final Runnable onAction) {
    goal.showGoal(title, progress, action, onAction);
    fitBanner();
  }

  void endGoal() {
    goal.end();
    fitBanner();
  }

  Optional<MapGuide.Step.Id> goalStep() {
    return goal.step();
  }

  /** The goal's line while it shows. */
  Optional<String> goalText() {
    return goal.isVisible() ? Optional.of(goal.text()) : Optional.empty();
  }

  /** What following a step does: show its tool and the step there. */
  void onFollowStep(final Consumer<MapGuide.Step.Id> follow) {
    followStep = follow;
    goal.onFollowStep(follow);
  }

  /** Follows the step into its tool, eg from a tool's "Mark one in Territories". */
  void followStep(final MapGuide.Step.Id step) {
    followStep.accept(step);
  }

  private void fitBanner() {
    revalidate();
    repaint();
  }

  /** Leaves out the legend's link to Nations until another tool takes the map, eg in Nations. */
  void hideNationsLink() {
    legend.hideNationsLink();
  }

  /** What the legend's problems button does, eg show the problem list. */
  void onShowProblems(final Runnable showProblems) {
    legend.onShowProblems(showProblems);
  }

  /** Shows the nation's land in this color while it is chosen, until {@link #endPreview}. */
  void previewColor(final String nation, final Color color) {
    canvas.previewColor(nation, color);
  }

  void endPreview() {
    canvas.endPreview();
  }

  /** Shows these territories together, eg a canal's, until another editor takes the map. */
  void mark(final Set<String> territories) {
    canvas.setMarked(territories);
  }

  /** A canal's territories in a strong color that stands out, the other canals' lighter. */
  void markCanals(final Set<String> territories, final Set<String> faintly) {
    canvas.setCanalMarks(territories, faintly);
  }

  /** Shows these places on every territory to drag, until another editor takes the map. */
  void showPlaces(final MapCanvas.ShownPlaces places) {
    canvas.showPlaces(places);
  }

  /** Shows each territory's terrain effects on the map, the painted one standing out. */
  void showEffects(final Optional<String> painted) {
    canvas.showEffects(painted);
  }

  void hideEffects() {
    canvas.hideEffects();
  }

  /** Fills each textured territory with its texture, until another editor takes the map. */
  void showTextures() {
    canvas.showTextures();
  }

  void hideTextures() {
    canvas.hideTextures();
  }

  /** A left drag paints the territories it passes, given at its end; until stopEditingPlaces. */
  void paintStrokes(final Consumer<List<String>> onStroke) {
    canvas.paintStrokes(onStroke);
  }

  /** Shows the selected territory's places to edit, {@link MapCanvas#editPlaces}. */
  void editPlaces() {
    canvas.editPlaces();
  }

  /** Shows the decorations to move and remove; a click places the misc picture given. */
  void editDecorations(final String picture) {
    canvas.editDecorations(picture);
  }

  /** How much bigger than the map's own pixels it shows on the screen. */
  double zoom() {
    return canvas.getZoom();
  }

  /** A line dragged on the map goes to onLine, eg to draw it into the picture. */
  void drawLine(final Consumer<List<Point>> onLine) {
    canvas.drawLine(onLine);
  }

  /** A line set point by point with clicks goes to onLine, {@link MapCanvas#drawLineByPoints}. */
  void drawLineByPoints(final Consumer<List<Point>> onLine) {
    canvas.drawLineByPoints(onLine);
  }

  /** An ellipse dragged from corner to corner goes to onLine, {@link MapCanvas#drawCircle}. */
  void drawCircle(final Consumer<List<Point>> onLine) {
    canvas.drawCircle(onLine);
  }

  /** A band dragged on the map goes to onLine, {@link MapCanvas#eraseLine}. */
  void eraseLine(
      final Consumer<List<Point>> onLine,
      final int width,
      final int lineWidth,
      final Consumer<MapFiles.SharedBorder> onBorder) {
    canvas.eraseLine(onLine, width, lineWidth, onBorder);
  }

  /**
   * A line dragged on the map, in its own pixels, goes to onLine, tinted while dragged by whether
   * it cuts; until places are edited again.
   */
  void drawLine(final Consumer<List<Point>> onLine, final Predicate<List<Point>> cuts) {
    canvas.drawLine(onLine, cuts);
  }

  /** Hatches the part of a territory a split would make, in the map's pixels. */
  void showCut(final List<Polygon> part) {
    canvas.setCut(part);
  }

  void clearCut() {
    canvas.setCut(List.of());
  }

  void stopEditingPlaces() {
    canvas.stopEditingPlaces();
  }

  /** As {@link MapCanvas#addUnitPlacesByClick}. */
  void addUnitPlacesByClick() {
    canvas.addUnitPlacesByClick();
  }

  void select(@Nullable final String territory) {
    chosen = territory;
    canvas.setSelected(territory);
  }

  void scrollTo(final String territory) {
    canvas.scrollTo(territory);
    // a view not laid out yet, or still to be fitted, has no room to scroll in: again after it
    if (scroll.getViewport().getExtentSize().width == 0 || !canvas.isShowing()) {
      SwingUtilities.invokeLater(() -> canvas.scrollTo(territory));
    }
  }
}
