package eddies.mapeditor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Production.Rule.Result;
import eddies.mapeditor.MapTexts.Entry;

/**
 * The tabs of the game's buy window, as a production_tabs file says them (TripleA's
 * ProductionTabsProperties): its own tabs or TripleA's standard ones, each tab's name and what it
 * sells, and how many rows and columns a tab shows. For a nation TripleA reads
 * production_tabs.&lt;nation&gt;.properties and then production_tabs.properties over it: what the
 * file for all says counts first.
 *
 * @param standard TripleA's own tabs (All, Land, Air, ...), the tabs here not shown
 * @param rows the rows of a tab, 0 for TripleA to work them out, as it does when a tab holds more
 *     than rows x columns
 * @param tabs the tabs in their order; what one sells in the file's order, though TripleA shows it
 *     in the order of the nation's production rules
 */
record ProductionTabs(boolean standard, int rows, int columns, List<Tab> tabs) {
  /** The file for all nations. */
  static final String FILE = "production_tabs.properties";

  /** Every production_tabs file of a map folder, as a glob. */
  static final String FILES = "production_tabs*.properties";

  /** The names of TripleA's own tabs, TabbedProductionPanel; it shows those that sell something. */
  static final String STANDARD_TABS =
      "All, Land, Air, Sea, Construction, Upgrades/Consumes, Resources";

  private static final String PREFIX = "production_tabs.";
  private static final String STANDARD = PREFIX + "use_default_tabs";
  private static final String COUNT = PREFIX + "number_of_tabs";
  private static final String ROWS = PREFIX + "rows";
  private static final String COLUMNS = PREFIX + "columns";
  private static final String NAME = PREFIX + "tab_name.";
  private static final String UNITS = PREFIX + "tab_units.";

  /** A tab: its name and the unit types (or resources) it sells, by the names rules give them. */
  record Tab(String name, List<String> units) {
    Tab {
      units = List.copyOf(units);
    }
  }

  ProductionTabs {
    tabs = List.copyOf(tabs);
  }

  /** A nation's own file, eg production_tabs.Germans.properties. */
  static String fileOf(final String nation) {
    return PREFIX + nation + ".properties";
  }

  /** The tabs a file's text says; one without text says TripleA's standard tabs. */
  static ProductionTabs read(final String text) {
    return read(values(text));
  }

  /**
   * The tabs TripleA shows the nation: its own file's, with what the file for all says over them.
   */
  static ProductionTabs of(final MapTexts texts, final String nation) {
    final Map<String, String> values = values(texts.buyTabs(fileOf(nation)));
    values.putAll(values(texts.buyTabs(FILE)));
    return read(values);
  }

  private static Map<String, String> values(final String text) {
    final Map<String, String> values = new LinkedHashMap<>();
    MapTexts.entries(text).forEach(e -> values.put(e.key(), e.value()));
    return values;
  }

  private static ProductionTabs read(final Map<String, String> values) {
    final List<Tab> tabs = new ArrayList<>();
    for (int i = 1; i <= number(values, COUNT); i++) {
      tabs.add(
          new Tab(
              values.getOrDefault(NAME + i, ""),
              Stream.of(values.getOrDefault(UNITS + i, "").split(":"))
                  .filter(unit -> !unit.isEmpty())
                  .toList()));
    }
    return new ProductionTabs(
        Boolean.parseBoolean(values.getOrDefault(STANDARD, "true")),
        number(values, ROWS),
        number(values, COLUMNS),
        tabs);
  }

  // TripleA fails on a number it cannot read; here it counts as not set
  private static int number(final Map<String, String> values, final String key) {
    try {
      return Math.max(0, Integer.parseInt(values.getOrDefault(key, "0").strip()));
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  /**
   * The file's text saying these tabs: only what differs from what the text says is written, each
   * in its line or added at the end; comments and keys the editor does not know stay, tabs beyond
   * the last go.
   */
  String writeInto(final String text) {
    final ProductionTabs old = read(text);
    String written = text;
    if (standard != old.standard) {
      written = MapTexts.withEntry(written, STANDARD, String.valueOf(standard));
    }
    if (tabs.size() != old.tabs.size()) {
      written = MapTexts.withEntry(written, COUNT, String.valueOf(tabs.size()));
    }
    if (rows != old.rows) {
      written = MapTexts.withEntry(written, ROWS, String.valueOf(rows));
    }
    if (columns != old.columns) {
      written = MapTexts.withEntry(written, COLUMNS, String.valueOf(columns));
    }
    for (int i = 0; i < tabs.size(); i++) {
      final Optional<Tab> before =
          i < old.tabs.size() ? Optional.of(old.tabs.get(i)) : Optional.empty();
      final Tab tab = tabs.get(i);
      // a new tab gets both lines: TripleA fails on a tab without its units
      if (before.filter(b -> b.name().equals(tab.name())).isEmpty()) {
        written = MapTexts.withEntry(written, NAME + (i + 1), tab.name());
      }
      if (before.filter(b -> b.units().equals(tab.units())).isEmpty()) {
        written = MapTexts.withEntry(written, UNITS + (i + 1), String.join(":", tab.units()));
      }
    }
    for (int i = tabs.size() + 1; i <= old.tabs.size(); i++) {
      written = MapTexts.withoutEntry(MapTexts.withoutEntry(written, NAME + i), UNITS + i);
    }
    return written;
  }

  /** With TripleA's own tabs shown; the tabs here stay in the file. */
  ProductionTabs withStandardTabs() {
    return new ProductionTabs(true, rows, columns, tabs);
  }

  /** With the tabs here shown. */
  ProductionTabs withOwnTabs() {
    return new ProductionTabs(false, rows, columns, tabs);
  }

  ProductionTabs withSize(final int newRows, final int newColumns) {
    return new ProductionTabs(standard, newRows, newColumns, tabs);
  }

  ProductionTabs withTabs(final List<Tab> newTabs) {
    return new ProductionTabs(standard, rows, columns, newTabs);
  }

  /** What the game's production rules sell, by the name tabs list it: unit types and resources. */
  static List<String> sold(final Game game) {
    return ProductionXml.rules(game).stream()
        .flatMap(rule -> ProductionXml.result(rule).stream())
        .map(Result::getResourceOrUnit)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  /** What these tabs leave out of what is sold; empty with TripleA's standard tabs. */
  List<String> missing(final List<String> sold) {
    if (standard) {
      return List.of();
    }
    final Set<String> inTabs =
        tabs.stream().flatMap(tab -> tab.units().stream()).collect(Collectors.toSet());
    return sold.stream().filter(unit -> !inTabs.contains(unit)).toList();
  }

  /**
   * Nations whose buy window TripleA cannot open: with own tabs, everything a nation's frontier
   * sells must be in a tab (TabbedProductionPanel.checkLists). A line per group of nations that
   * miss the same.
   */
  static List<String> problems(final MapTexts texts, final Game game) {
    if (texts.buyTabs().values().stream().allMatch(String::isBlank)) {
      return List.of();
    }
    final Map<List<String>, Set<String>> nationsByMissing = new LinkedHashMap<>();
    for (final String nation : MapXml.nations(game)) {
      final List<String> sold =
          ProductionXml.frontierOf(game, nation).stream()
              .flatMap(frontier -> ProductionXml.rulesIn(game, frontier).stream())
              .flatMap(
                  name ->
                      ProductionXml.rules(game).stream().filter(r -> name.equals(r.getName())))
              .flatMap(rule -> ProductionXml.result(rule).stream())
              .map(Result::getResourceOrUnit)
              .filter(Objects::nonNull)
              .distinct()
              .toList();
      final List<String> missing = of(texts, nation).missing(sold);
      if (!missing.isEmpty()) {
        nationsByMissing.computeIfAbsent(missing, m -> new LinkedHashSet<>()).add(nation);
      }
    }
    return nationsByMissing.entrySet().stream()
        .map(
            e ->
                NationsEditor.shortList(List.copyOf(e.getValue()))
                    + " cannot open the buy window: "
                    + NationsEditor.shortList(e.getKey())
                    + (e.getKey().size() == 1 ? " is" : " are")
                    + " in no tab")
        .toList();
  }
}
