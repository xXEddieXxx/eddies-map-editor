package eddies.mapeditor;

import eddies.mapeditor.MakerIcon.Glyph;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.Serial;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
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
import javax.swing.KeyStroke;
import javax.swing.OverlayLayout;
import javax.swing.SwingUtilities;
import org.triplea.swing.SwingComponents;

/**
 * The map that Territories and Connections share: one row above it, an editor's own tools left, eg
 * its brush, the map's controls right: the views (the game view first, the others in a grid, {@link
 * MapViewsButton}), zoom, Fit and the picture menu (picture on or off, load another, redraw
 * borders, find the territories again); below the row the banner of a mode a tool has on, {@link
 * #showBanner}; below the map the legend, {@link MapLegend}. Without outlines the map shows what to
 * do next instead, without row and legend.
 */
final class MapView extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  private static final double ZOOM_STEP = 1.25;
  private static final int BANNER_HEIGHT = 32;
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

  private final MapViewsButton views;
  private final MapLegend legend;
  private final JPanel top = new JPanel(new BorderLayout(0, Theme.GAP_S));
  private final JPanel editorTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
  private final JPanel banner = new JPanel(new BorderLayout(Theme.GAP_M, 0));
  private final JPanel bannerLine = new JPanel();
  private final JLabel bannerText = new JLabel();
  private final JButton bannerDone = new JButton("Done");
  private final JScrollPane scroll;
  private final JMenuItem findAgain = new JMenuItem("Find territories again…");
  // what the middle shows: "" the map, PICTURE_ONLY, else the number of territories without map;
  // built anew only when it changes
  private Optional<String> shownState = Optional.empty();
  // the picture the map was last fitted to, so a new one is fitted again
  private transient Optional<BufferedImage> fittedTo = Optional.empty();

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
    super(new BorderLayout(0, 8));
    this.project = project;
    this.choosePicture = choosePicture;
    this.findTerritories = findTerritories;
    canvas = new MapCanvas(project);
    scroll = SwingComponents.newJScrollPane(canvas);
    scroll.getVerticalScrollBar().setUnitIncrement(24);
    scroll.getHorizontalScrollBar().setUnitIncrement(24);
    legend = new MapLegend(project);
    views = new MapViewsButton(MapMode.GAME, this::showMode);
    final JButton zoomShown = new JButton();
    Theme.setBorderlessButtonStyle(zoomShown);
    zoomShown.setMargin(new Insets(0, 0, 0, 0));
    // as wide as the widest it shows, 300 %, so it never cuts to "50..."
    zoomShown.setText("300 %");
    zoomShown.setPreferredSize(new Dimension(zoomShown.getPreferredSize().width, 28));
    zoomShown.addActionListener(e -> canvas.zoomBy(1 / canvas.getZoom()));
    showZoom(zoomShown, canvas.getZoom());
    canvas.addPropertyChangeListener("zoom", e -> showZoom(zoomShown, (Double) e.getNewValue()));
    final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_XS, 0));
    tools.add(views);
    tools.add(
        Theme.newIconButton("−", "Zoom out (Ctrl+wheel)", () -> canvas.zoomBy(1 / ZOOM_STEP)));
    tools.add(zoomShown);
    tools.add(Theme.newIconButton("+", "Zoom in (Ctrl+wheel)", () -> canvas.zoomBy(ZOOM_STEP)));
    tools.add(Theme.newIconButton("⤢", "Fit the whole map (Ctrl+0)", canvas::zoomToFit));
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
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.add(editorTools, BorderLayout.WEST);
    row.add(tools, BorderLayout.EAST);
    top.add(row, BorderLayout.NORTH);
    top.add(newBanner(), BorderLayout.SOUTH);
    add(top, BorderLayout.NORTH);
    add(legend, BorderLayout.SOUTH);
    showMapOrEmptyState();
    project.onChange(this::showMapOrEmptyState);
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
    final JButton picture = Theme.newIconButton(Glyph.GAME_VIEW, "Map picture", () -> {});
    picture.setText("▾");
    // icon and arrow: wider than an icon button's square
    picture.setPreferredSize(null);
    // also on a right-click and Shift+F10
    picture.setComponentPopupMenu(pictureMenu);
    picture.addActionListener(e -> pictureMenu.show(picture, 0, picture.getHeight()));
    return picture;
  }

  /** The strip that says what a click does in a tool's mode, with Done to end it; hidden. */
  private JPanel newBanner() {
    banner.setBackground(Theme.getSelectionColor());
    banner.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_M, 0, Theme.GAP_XS));
    banner.setPreferredSize(new Dimension(0, BANNER_HEIGHT));
    bannerLine.setLayout(new BoxLayout(bannerLine, BoxLayout.X_AXIS));
    bannerLine.setOpaque(false);
    banner.add(bannerLine, BorderLayout.CENTER);
    Theme.setSecondaryButtonStyle(bannerDone);
    final JPanel done = new JPanel(new GridBagLayout());
    done.setOpaque(false);
    done.add(bannerDone);
    banner.add(done, BorderLayout.EAST);
    banner.setVisible(false);
    return banner;
  }

  /** The map and its legend in this view. */
  private void showMode(final MapMode mode) {
    canvas.setMode(mode);
    legend.explain(mode);
  }

  /**
   * The map once it has outlines, else what to do next: load a picture, or find the territories in
   * the one loaded, over the picture veiled; fitted to the view when it is new.
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
    final boolean newPicture = !picture.equals(fittedTo);
    fittedTo = picture;
    if (SwingUtilities.isDescendingFrom(scroll, this) && (!hadMap || newPicture)) {
      // fit once the view has its size
      SwingUtilities.invokeLater(canvas::zoomToFit);
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
              "Next, find the territories, sea zones and their neighbours in it. Areas between dark"
                  + " lines become territories, blue ones sea zones.",
              "Find territories",
              findTerritories));
    }
    final int territories = Integer.parseInt(state);
    return territories == 0
        ? Panels.empty(
            Glyph.GAME_VIEW,
            "No map picture yet",
            "Load a picture of your map: dark lines between territories, seas in blue. The editor"
                + " then finds the territories in it.",
            "Load map picture…",
            choosePicture)
        : Panels.empty(
            Glyph.GAME_VIEW,
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
    editorTools.add(tools);
    editorTools.revalidate();
    editorTools.repaint();
    canvas.onTerritoryClick(onClick);
    canvas.stopEditingPlaces();
    canvas.setMarked(Set.of());
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
    bannerText.setText(text);
    bannerLine.removeAll();
    bannerLine.add(bannerText);
    extra.ifPresent(
        component -> {
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
    revalidate();
    repaint();
  }

  void hideBanner() {
    canvas.onEscape(Optional.empty());
    banner.setVisible(false);
    revalidate();
    repaint();
  }

  /** The banner's text while it shows. */
  Optional<String> bannerText() {
    return banner.isVisible() ? Optional.of(bannerText.getText()) : Optional.empty();
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

  /** Shows the selected territory's places to edit, {@link MapCanvas#editPlaces}. */
  void editPlaces() {
    canvas.editPlaces();
  }

  /** Shows the decorations to move and remove; a click places the misc picture given. */
  void editDecorations(final String picture) {
    canvas.editDecorations(picture);
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

  void select(@Nullable final String territory) {
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
