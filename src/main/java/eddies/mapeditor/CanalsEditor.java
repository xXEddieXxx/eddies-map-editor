package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.CanalXml.Canal;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.Theme.Status;

/**
 * The canals: a canal joins two sea zones, and ships pass it only while their side holds its land
 * territories. Choose or make one, then click its sea zones and land territories on the map.
 */
final class CanalsEditor implements Editor {
  private static final String LIST = "list";
  private static final String CANAL = "canal";
  private static final String NO_MAP = "no map";

  private final MapProject project;
  private final MapView map;
  private final JPanel page = new JPanel(new CardLayout());
  private final SearchList list;
  private final Form inspector = new Form(250);
  private @Nullable String selected;
  // a new canal before its first sea zone: the game has no place for it until then
  private Optional<Canal> draft = Optional.empty();

  CanalsEditor(final MapProject project, final MapView map) {
    this.project = project;
    this.map = map;
    list =
        new SearchList("canal", "canals", this::names, n -> n, this::detail, this::chooseInList)
            .withAdd(
                "New canal",
                "A new canal: name it, then click its sea zones and land on the map",
                this::addCanal)
            .withEmpty(
                Panels.empty(
                    Glyph.CANALS,
                    "No canals yet",
                    "A canal lets ships pass between two sea zones while their side holds the"
                        + " land, eg Suez.",
                    "New canal…",
                    this::addCanal));
    // the fields start where the tool's title does: the panel has its margin already
    inspector.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_XS, 0, Theme.GAP_L, Theme.GAP_L));
    page.add(list, LIST);
    page.add(SwingComponents.newJScrollPane(inspector), CANAL);
    page.add(
        Panels.empty(Glyph.CANALS, "Canals need the map", "Load its picture first."), NO_MAP);
    project.onChange(this::refresh);
    showInspector();
  }

  @Override
  public boolean usesMap() {
    return true;
  }

  @Override
  public String title() {
    return "Canals";
  }

  @Override
  public Glyph glyph() {
    return Glyph.CANALS;
  }

  @Override
  public String hint() {
    return "Canals and straits, eg Suez: ships pass between its two sea zones only while their"
        + " side holds its land territories. Choose a canal or make a new one, then click its sea"
        + " zones and land territories on the map; a click again takes one out.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    map.useFor(MapMode.GAME, new JPanel(), this::click);
    map.select(null);
    showOnMap();
  }

  private List<String> names() {
    final List<String> names =
        new ArrayList<>(CanalXml.canals(project.game()).stream().map(Canal::name).toList());
    draft.map(Canal::name).filter(name -> !names.contains(name)).ifPresent(names::add);
    return names;
  }

  private String detail(final String name) {
    return canal(name).map(c -> String.join(" · ", territories(c))).orElse("");
  }

  /** The canal's sea zones, then its land territories. */
  private static List<String> territories(final Canal canal) {
    return Stream.concat(canal.seaZones().stream(), canal.lands().stream()).toList();
  }

  /** The canal of the name in the game, else the new one being made. */
  private Optional<Canal> canal(final String name) {
    return CanalXml.canals(project.game()).stream()
        .filter(c -> c.name().equals(name))
        .findFirst()
        .or(() -> draft.filter(d -> d.name().equals(name)));
  }

  private Optional<Canal> shown() {
    return Optional.ofNullable(selected).flatMap(this::canal);
  }

  /** A choice in the list also brings the canal into view, at its first sea zone or land. */
  private void chooseInList(final String name) {
    choose(name);
    canal(name).flatMap(c -> territories(c).stream().findFirst()).ifPresent(map::scrollTo);
  }

  private void choose(@Nullable final String name) {
    selected = name;
    list.choose(name);
    refresh();
  }

  private void refresh() {
    list.refresh();
    showInspector();
    // the map is shared: only while this tool shows it
    if (page.isShowing()) {
      showOnMap();
    }
  }

  /** The open canal's territories marked, and the banner that says what a click does. */
  private void showOnMap() {
    final Optional<Canal> canal = shown();
    map.mark(canal.map(c -> Set.copyOf(territories(c))).orElse(Set.of()));
    canal.ifPresentOrElse(
        c ->
            map.showBanner(
                "Editing "
                    + c.name()
                    + ": click sea zones and land territories to add or take them out.",
                () -> choose(null)),
        map::hideBanner);
  }

  /**
   * Adds or takes out the territory clicked; without an open canal, opens the one there, if any.
   */
  void click(final String territory) {
    final Optional<Canal> shown = shown();
    if (shown.isEmpty()) {
      CanalXml.canals(project.game()).stream()
          .filter(c -> c.seaZones().contains(territory) || c.lands().contains(territory))
          .findFirst()
          .ifPresentOrElse(
              c -> choose(c.name()),
              () ->
                  Dialogs.toast(
                      page, "No canal there. Choose one in the list or make a new one."));
      return;
    }
    final Canal canal = shown.get();
    if (MapXml.isWater(project.game(), territory)) {
      change(canal, c -> withSeaZones(c, toggled(c.seaZones(), territory)));
    } else {
      change(canal, c -> withLands(c, toggled(c.lands(), territory)));
    }
  }

  private static Canal withSeaZones(final Canal canal, final List<String> seaZones) {
    return new Canal(canal.name(), seaZones, canal.lands(), canal.excludedUnits());
  }

  private static Canal withLands(final Canal canal, final List<String> lands) {
    return new Canal(canal.name(), canal.seaZones(), lands, canal.excludedUnits());
  }

  private static List<String> toggled(final List<String> names, final String name) {
    return names.contains(name)
        ? names.stream().filter(n -> !n.equals(name)).toList()
        : Stream.concat(names.stream(), Stream.of(name)).toList();
  }

  /** The canal changed as one step; one left without sea zones stays here until it has one. */
  private void change(final Canal old, final UnaryOperator<Canal> how) {
    final Canal changed = how.apply(old);
    draft = changed.seaZones().isEmpty() ? Optional.of(changed) : Optional.empty();
    selected = changed.name();
    project.edit(g -> CanalXml.withCanal(g, old.name(), changed));
    // a change to a canal without sea zones is none to the game
    refresh();
  }

  private void addCanal() {
    Dialogs.askName(
            page,
            "New canal",
            "Name",
            "",
            "Create",
            n ->
                canal(n).isPresent()
                    ? Optional.of("There is a canal of that name")
                    : Optional.empty())
        .ifPresent(
            name -> {
              draft = Optional.of(new Canal(name, List.of(), List.of(), List.of()));
              choose(name);
            });
  }

  private void showInspector() {
    final Optional<Canal> shown = shown();
    final String card;
    if (shown.isPresent()) {
      card = CANAL;
    } else if (names().isEmpty() && !project.getFiles().hasOutlines()) {
      // existing canals stay listable without the map's picture
      card = NO_MAP;
    } else {
      card = LIST;
    }
    ((CardLayout) page.getLayout()).show(page, card);
    inspector.clear();
    shown.ifPresent(this::fillInspector);
    inspector.revalidate();
    inspector.repaint();
  }

  private void fillInspector(final Canal canal) {
    final Game game = project.game();
    final JButton back = new JButton("← All canals");
    Theme.setTertiaryButtonStyle(back);
    back.addActionListener(e -> choose(null));
    final JPanel backRow = leftAligned(back);
    // the link's text in line with the fields; its hover background reaches out to the left
    backRow.setBorder(BorderFactory.createEmptyBorder(0, -back.getInsets().left, 0, 0));
    inspector
        .row(backRow)
        .title(canal.name())
        .subtitle(
            count(canal.seaZones().size(), "sea zone", "sea zones")
                + " · "
                + count(canal.lands().size(), "land", "lands"));
    final JTextField name = new JTextField(canal.name());
    name.getAccessibleContext().setAccessibleName("Name of the canal");
    inspector.field("Name", name);
    final FieldError error = inspector.error(name);
    name.addActionListener(e -> rename(canal.name(), name.getText(), error));
    name.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            rename(canal.name(), name.getText(), error);
          }
        });
    final int seaZones = canal.seaZones().size();
    inspector.row(
        Theme.newStatusLabel(
            seaZones == 2 ? Status.DONE : Status.TODO,
            "Two sea zones"
                + switch (seaZones) {
                  case 2 -> "";
                  case 0, 1 -> " (" + seaZones + " of 2)";
                  default -> " (" + seaZones + " chosen)";
                }));
    for (final String seaZone : canal.seaZones()) {
      inspector.row(
          takeOutRow(
              seaZone,
              () -> change(canal, c -> withSeaZones(c, toggled(c.seaZones(), seaZone)))));
    }
    inspector.row(
        Theme.newStatusLabel(
            canal.lands().isEmpty() ? Status.TODO : Status.DONE, "At least one land"));
    for (final String land : canal.lands()) {
      inspector.row(
          takeOutRow(land, () -> change(canal, c -> withLands(c, toggled(c.lands(), land)))));
    }
    inspector.note("Ships pass while their side holds all of them.");
    inspector.section("Units that pass freely");
    final List<String> free = CanalXml.passFreely(game, canal);
    final JLabel value =
        new JLabel(
            canal.excludedUnits().isEmpty()
                ? "Air units (default)"
                : free.isEmpty() ? "None" : String.join(", ", free));
    final JButton choose = new JButton("Choose…");
    Theme.setSecondaryButtonStyle(choose);
    choose.setToolTipText("Choose the unit types that pass freely");
    choose.addActionListener(
        e ->
            Dialogs.pickMany(page, "Units that pass freely", MapXml.unitTypes(game), free, "Choose")
                .ifPresent(
                    units ->
                        change(
                            canal,
                            c ->
                                new Canal(
                                    c.name(),
                                    c.seaZones(),
                                    c.lands(),
                                    CanalXml.excludedUnits(game, units)))));
    final JPanel units = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    units.setOpaque(false);
    units.add(value, BorderLayout.CENTER);
    units.add(choose, BorderLayout.EAST);
    inspector.row(units);
    inspector
        .remove(
            "Remove canal",
            () -> {
              draft = Optional.empty();
              selected = null;
              project.edit(g -> CanalXml.withoutCanal(g, canal.name()));
              refresh();
            })
        .end();
  }

  private static String count(final int count, final String noun, final String nounPlural) {
    return count + " " + (count == 1 ? noun : nounPlural);
  }

  private static JPanel leftAligned(final JComponent component) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setOpaque(false);
    row.add(component);
    return row;
  }

  private static JPanel takeOutRow(final String name, final Runnable takeOut) {
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.setOpaque(false);
    row.add(new JLabel(name), BorderLayout.CENTER);
    row.add(
        Theme.newIconButton("×", "Take " + name + " out of the canal", takeOut),
        BorderLayout.EAST);
    return row;
  }

  /**
   * The canal of the old name renamed as typed, as one step; an empty or taken name keeps the old
   * one and says so below the field, which keeps the text typed.
   */
  private void rename(final String old, final String typed, final FieldError error) {
    final String renamed = typed.strip();
    // the field of a canal renamed or closed meanwhile loses its focus too
    final Optional<Canal> canal = canal(old).filter(c -> old.equals(selected));
    if (canal.isEmpty() || renamed.equals(old)) {
      error.setProblem(Optional.empty());
      return;
    }
    if (renamed.isEmpty() || canal(renamed).isPresent()) {
      error.setProblem(Optional.of("Empty or another canal's name. Kept \"" + old + "\"."));
      return;
    }
    change(canal.get(), c -> new Canal(renamed, c.seaZones(), c.lands(), c.excludedUnits()));
  }
}
