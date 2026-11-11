package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.Serial;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.triplea.swing.SwingComponents;

/**
 * The map that Territories and Connections share: above it the views (the game view first, the
 * others in a grid, {@link MapViewsButton}), Fit, the Picture menu (picture on or off, load
 * another, find the territories again), Find territories while a picture has none found yet, the
 * zoom buttons, and room for an editor's own tools, eg its brush; below it the legend, {@link
 * MapLegend}. A map without picture and outlines shows how to load a picture instead.
 */
final class MapView extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  private static final double ZOOM_STEP = 1.25;

  private final transient MapProject project;
  private final MapCanvas canvas;

  private final MapViewsButton views;
  private final MapLegend legend;
  private final JPanel editorTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
  private final JScrollPane scroll;
  private final JComponent emptyState;
  private final JButton find = new JButton("Find territories");
  private final JMenuItem findAgain = new JMenuItem("Find the territories again...");
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
    canvas = new MapCanvas(project);
    scroll = SwingComponents.newJScrollPane(canvas);
    scroll.getVerticalScrollBar().setUnitIncrement(24);
    scroll.getHorizontalScrollBar().setUnitIncrement(24);
    legend = new MapLegend(project);
    views = new MapViewsButton(MapMode.GAME, this::showMode);
    final JButton fit = new JButton("Fit");
    fit.setToolTipText(
        "Zooms so that the whole map fits. Ctrl and the mouse wheel zoom, dragging moves the map.");
    fit.addActionListener(e -> canvas.zoomToFit());
    final JButton zoomOut = new JButton("−");
    zoomOut.setToolTipText("Zoom out");
    zoomOut.getAccessibleContext().setAccessibleName("Zoom out");
    zoomOut.addActionListener(e -> canvas.zoomBy(1 / ZOOM_STEP));
    final JButton zoomIn = new JButton("+");
    zoomIn.setToolTipText("Zoom in");
    zoomIn.getAccessibleContext().setAccessibleName("Zoom in");
    zoomIn.addActionListener(e -> canvas.zoomBy(ZOOM_STEP));
    final JLabel zoomShown = new JLabel(percent(canvas.getZoom()));
    canvas.addPropertyChangeListener(
        "zoom", e -> zoomShown.setText(percent((Double) e.getNewValue())));
    final JCheckBoxMenuItem showPicture = new JCheckBoxMenuItem("Show the map's picture", true);
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
    pictureMenu.add("Load another picture...").addActionListener(e -> choosePicture.run());
    final JMenuItem redraw = pictureMenu.add("Redraw borders from a picture...");
    redraw.setToolTipText(
        "Draw the new borders as dark lines into the map's picture with a paint program, then"
            + " choose it here: the territories take the borders, keeping names, owners and units");
    redraw.addActionListener(e -> redrawBorders.run());
    findAgain.addActionListener(e -> findTerritories.run());
    pictureMenu.add(findAgain);
    final JButton picture = new JButton("Picture ▾");
    picture.setToolTipText("The map's picture: show it, load another, find its territories");
    picture.addActionListener(e -> pictureMenu.show(picture, 0, picture.getHeight()));
    find.setToolTipText(
        "Finds the territories, sea zones and their neighbours in the picture: areas between"
            + " dark lines, blue ones are seas");
    find.addActionListener(e -> findTerritories.run());
    Theme.setPrimaryButtonStyle(find);
    final JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    tools.add(views);
    tools.add(
        Help.button(
            "What the map shows: the game view as the map plays, or the owners, values, units or"
                + " connections. Drag with the mouse to move the map, Ctrl and the mouse wheel"
                + " zoom, Fit shows it whole."));
    tools.add(zoomOut);
    tools.add(zoomShown);
    tools.add(zoomIn);
    tools.add(fit);
    tools.add(picture);
    tools.add(
        Help.button(
            "The map's picture: territories with dark lines between them, the seas in blue. Find"
                + " territories finds the territories, sea zones and their neighbours in it;"
                + " loading another picture keeps the territories until they are found again."
                + " To move borders, draw them into the picture with a paint program and choose"
                + " Redraw borders: names, owners and units stay."));
    tools.add(find);
    // the tool's own controls in a row below: beside the map's they did not fit the middle
    final JPanel top = new JPanel(new BorderLayout(0, 6));
    top.add(tools, BorderLayout.NORTH);
    top.add(editorTools, BorderLayout.SOUTH);
    add(top, BorderLayout.NORTH);
    add(legend, BorderLayout.SOUTH);
    emptyState = newEmptyState(choosePicture);
    showMapOrEmptyState();
    project.onChange(this::showMapOrEmptyState);
  }

  /** The map and its legend in this view. */
  private void showMode(final MapMode mode) {
    canvas.setMode(mode);
    legend.explain(mode);
  }

  private static String percent(final double zoom) {
    return Math.round(zoom * 100) + " %";
  }

  /**
   * What the map area shows without picture and outlines: what to do, and the button to load a
   * picture.
   */
  private static JComponent newEmptyState(final Runnable choosePicture) {
    final JPanel empty = new JPanel(new GridBagLayout());
    final JPanel column = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.insets = new Insets(0, 0, 16, 0);
    final JLabel text =
        new JLabel(
            "<html><div style='text-align:center;width:420px'>No map yet. Load a picture of your"
                + " map: territories with dark lines between them, the seas in blue. Then Find"
                + " territories finds the territories, sea zones and their neighbours in it."
                + "</div></html>",
            SwingConstants.CENTER);
    text.setForeground(Theme.getSecondaryTextColor());
    column.add(text, c);
    final JButton load = new JButton("Load a picture of your map...");
    Theme.setPrimaryButtonStyle(load);
    load.addActionListener(e -> choosePicture.run());
    column.add(load, c);
    empty.add(column);
    return empty;
  }

  /**
   * The map once it has a picture or outlines, else the empty state; fitted to the view when it is
   * new. The Find territories button shows while the picture has none found yet.
   */
  private void showMapOrEmptyState() {
    final Optional<BufferedImage> picture = project.getFiles().picture();
    final boolean hasOutlines = project.getFiles().hasOutlines();
    // only a picture loaded here can be searched: an opened map has its tiles instead
    findAgain.setEnabled(picture.isPresent());
    find.setVisible(picture.isPresent() && !hasOutlines);
    final boolean hasMap = hasOutlines || picture.isPresent();
    final JComponent shown = hasMap ? scroll : emptyState;
    legend.setVisible(hasMap);
    final boolean newPicture = !picture.equals(fittedTo);
    fittedTo = picture;
    final boolean switched = shown.getParent() != this;
    if (switched) {
      remove(hasMap ? emptyState : scroll);
      add(shown, BorderLayout.CENTER);
      revalidate();
      repaint();
    }
    if (hasMap && (switched || newPicture)) {
      // fit once the view has its size
      SwingUtilities.invokeLater(canvas::zoomToFit);
    }
  }

  /** Takes the map for an editor: its mode, its tools above the map, what a click does. */
  void useFor(final MapMode mapMode, final JComponent tools, final Consumer<String> onClick) {
    views.showChosen(mapMode);
    showMode(mapMode);
    editorTools.removeAll();
    editorTools.add(tools);
    // a tool without controls of its own leaves no empty row
    editorTools.setVisible(tools.getComponentCount() > 0);
    editorTools.revalidate();
    editorTools.repaint();
    canvas.onTerritoryClick(onClick);
    canvas.stopEditingPlaces();
  }

  /** Shows the selected territory's places to edit, {@link MapCanvas#editPlaces}. */
  void editPlaces() {
    canvas.editPlaces();
  }

  /** Shows the decorations to move and remove; a click places the misc picture given. */
  void editDecorations(final String picture) {
    canvas.editDecorations(picture);
  }

  void stopEditingPlaces() {
    canvas.stopEditingPlaces();
  }

  void select(@Nullable final String territory) {
    canvas.setSelected(territory);
  }

  void scrollTo(final String territory) {
    canvas.scrollTo(territory);
  }
}
