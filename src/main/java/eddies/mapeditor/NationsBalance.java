package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.HeldUnits;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.swing.SwingComponents;

/**
 * All nations side by side, to see whether a game is balanced: what each starts with, and the
 * same summed per alliance. Worked out once from a game, not while it is shown.
 *
 * @param nations a row per nation, in the game's order
 * @param alliances a row per alliance; a nation in several alliances counts in each
 * @param resources the resources nations start with, PUs first: a column each
 * @param withoutCost the unit types at the start that no rule sells, which count 0 in the TUV
 * @param unitPus whether units make or cost PUs each turn (createsResourcesList), so the two
 *     columns of it show
 */
record NationsBalance(
    List<Row> nations,
    List<Row> alliances,
    List<String> resources,
    List<String> withoutCost,
    boolean unitPus) {

  /**
   * What a nation, or an alliance, starts with.
   *
   * @param alliances the nation's alliances, or for an alliance its nations
   * @param income the PUs its territories give each turn
   * @param fromUnits the PUs its units on the map make each turn, eg factories, oil fields
   * @param upkeep the PUs its units on the map cost each turn, as a number below 0
   * @param resources what it has of each of the balance's resources
   * @param units how many units it has on the map and held back
   * @param tuv their total unit value: each at what it costs to buy
   */
  record Row(
      String name,
      List<String> alliances,
      int territories,
      int income,
      int fromUnits,
      int upkeep,
      List<Integer> resources,
      int units,
      int tuv) {}

  /** The cells of the row in the table: the PUs units make and cost only where the game has any. */
  List<Object> cells(final Row row) {
    final List<Object> cells =
        new ArrayList<>(
            List.of(
                row.name(), String.join(", ", row.alliances()), row.territories(), row.income()));
    if (unitPus) {
      cells.addAll(List.of(row.fromUnits(), row.upkeep()));
    }
    cells.addAll(row.resources());
    cells.addAll(List.of(row.units(), row.tuv()));
    return List.copyOf(cells);
  }

  static NationsBalance of(final Game game) {
    // production is asked per territory: without the other attachments, a big game's 35,000
    final Game slim = MapXml.withTerritoryAttachmentsOnly(game);
    final List<String> nations = MapXml.nations(game);
    final Map<String, List<String>> owned = new HashMap<>();
    MapXml.ownersByTerritory(game)
        .forEach((territory, owner) -> owned.computeIfAbsent(owner, o -> new ArrayList<>()).add(territory));
    final Set<String> known = Set.copyOf(MapXml.territories(game));
    final List<String> resources =
        ProductionXml.resources(game).stream()
            .filter(
                r ->
                    r.equals(MapXml.PUS)
                        || nations.stream().anyMatch(n -> MapXml.startingResource(game, n, r) > 0))
            .sorted((a, b) -> Boolean.compare(b.equals(MapXml.PUS), a.equals(MapXml.PUS)))
            .toList();
    final UnitCosts costs = new UnitCosts(game);
    final Map<String, Integer> madePus = madePus(game);
    // TripleA multiplies the PUs units make as it does the territories' (EndTurnDelegate)
    final int multiplier = puMultiplier(game);
    final Set<String> withoutCost = new TreeSet<>();
    final List<Row> rows = new ArrayList<>();
    for (final String nation : nations) {
      final List<String> lands =
          owned.getOrDefault(nation, List.of()).stream().filter(known::contains).toList();
      int units = 0;
      int tuv = 0;
      final Map<String, Integer> counts = new LinkedHashMap<>();
      int fromUnits = 0;
      int upkeep = 0;
      for (final UnitPlacement placed : MapXml.placements(game)) {
        if (nation.equals(placed.getOwner())) {
          counts.merge(placed.getUnitType(), placed.getQuantity(), Integer::sum);
          // held units make nothing: TripleA pays for the units on the map
          final int pus =
              madePus.getOrDefault(placed.getUnitType(), 0) * placed.getQuantity() * multiplier;
          if (pus > 0) {
            fromUnits += pus;
          } else {
            upkeep += pus;
          }
        }
      }
      for (final HeldUnits held : MapXml.heldUnits(game)) {
        if (nation.equals(held.getPlayer())) {
          counts.merge(held.getUnitType(), held.getQuantity(), Integer::sum);
        }
      }
      for (final Map.Entry<String, Integer> count : counts.entrySet()) {
        units += count.getValue();
        final Optional<Integer> cost = costs.of(nation, count.getKey());
        if (cost.isEmpty()) {
          withoutCost.add(count.getKey());
        }
        tuv += count.getValue() * cost.orElse(0);
      }
      rows.add(
          new Row(
              nation,
              MapXml.alliances(game, nation),
              lands.size(),
              income(game, slim, nation, lands),
              fromUnits,
              upkeep,
              resources.stream().map(r -> MapXml.startingResource(game, nation, r)).toList(),
              units,
              tuv));
    }
    final List<Row> alliances = new ArrayList<>();
    for (final String alliance : MapXml.allAlliances(game)) {
      final List<Row> members = rows.stream().filter(r -> r.alliances().contains(alliance)).toList();
      alliances.add(
          new Row(
              alliance,
              members.stream().map(Row::name).toList(),
              members.stream().mapToInt(Row::territories).sum(),
              members.stream().mapToInt(Row::income).sum(),
              members.stream().mapToInt(Row::fromUnits).sum(),
              members.stream().mapToInt(Row::upkeep).sum(),
              java.util.stream.IntStream.range(0, resources.size())
                  .mapToObj(i -> members.stream().mapToInt(r -> r.resources().get(i)).sum())
                  .toList(),
              members.stream().mapToInt(Row::units).sum(),
              members.stream().mapToInt(Row::tuv).sum()));
    }
    return new NationsBalance(
        List.copyOf(rows),
        List.copyOf(alliances),
        resources,
        List.copyOf(withoutCost),
        !madePus.isEmpty());
  }

  /**
   * The PUs one unit of each type makes each turn, below 0 for what it costs, as TripleA's
   * createsResourcesList ("count:resource", of a resource the last); loops (foreach) read. Types
   * that make no PUs are left out.
   */
  private static Map<String, Integer> madePus(final Game game) {
    final Map<String, Integer> made = new HashMap<>();
    for (final Attachment attachment : AttachmentsXml.kept(game)) {
      if (!AttachmentsXml.isNamed(attachment, Constants.UNIT_ATTACHMENT_NAME)) {
        continue;
      }
      Optional<Integer> pus = Optional.empty();
      for (final Option option : AttachmentsXml.options(attachment)) {
        final String[] parts =
            AttachmentsXml.isOption(option, "createsResourcesList")
                ? TriggerXml.joined(option).split(":")
                : new String[0];
        if (parts.length == 2 && parts[1].strip().equals(MapXml.PUS)) {
          try {
            pus = Optional.of(Integer.parseInt(parts[0].strip()));
          } catch (final NumberFormatException e) {
            // TripleA does not load such a game: nothing counts
          }
        }
      }
      pus.filter(n -> n != 0).ifPresent(n -> made.put(attachment.getAttachTo(), n));
    }
    return made;
  }

  /** What one unit of the type is worth to the nation, as TripleA counts it; empty for no cost. */
  static Optional<Integer> unitCost(final Game game, final String nation, final String unitType) {
    return new UnitCosts(game).of(nation, unitType);
  }

  /** The PUs the nation collects from these territories of its own in its first turn. */
  static int income(final Game game, final String nation, final List<String> territories) {
    return income(game, game, nation, territories);
  }

  /**
   * As TripleA's Production counts them (Matches.territoryCanCollectIncomeFrom, times Multiply
   * PUs): not from a sea zone another nation owned first, a convoy route none of whose zones
   * the nation or an ally holds, nor, with Contested Territories Produce No Income, a territory
   * with enemy units; triggers that change owners at the start are not counted.
   *
   * @param attachments the game, or one with only its territory attachments, for speed
   */
  private static int income(
      final Game game, final Game attachments, final String nation, final List<String> territories) {
    final boolean contestedGiveNothing =
        GameRulesXml.optionValue(game, Constants.CONTESTED_TERRITORIES_PRODUCE_NO_INCOME)
            .map(value -> Boolean.parseBoolean(value.strip()))
            .orElse(false);
    final Map<String, String> owners = MapXml.ownersByTerritory(game);
    final Set<String> enemiesIn = new HashSet<>();
    if (contestedGiveNothing) {
      final Set<String> mine = Set.copyOf(territories);
      MapXml.placements(game).stream()
          .filter(p -> mine.contains(p.getTerritory()) && p.getQuantity() > 0)
          .filter(p -> RulesXml.startAtWar(game, nation, p.getOwner()))
          .forEach(p -> enemiesIn.add(p.getTerritory()));
    }
    final int production =
        territories.stream()
            .filter(t -> !enemiesIn.contains(t))
            .filter(
                t ->
                    !MapXml.isWater(game, t)
                        || TerritoryXml.originalOwner(attachments, t)
                            .map(nation::equals)
                            .orElse(true))
            .filter(t -> holdsConvoy(game, attachments, nation, owners, t))
            .mapToInt(t -> MapXml.production(attachments, t))
            .sum();
    return production * puMultiplier(game);
  }

  /** Whether a convoy route's land pays: a zone of its route the nation or an ally holds. */
  private static boolean holdsConvoy(
      final Game game,
      final Game attachments,
      final String nation,
      final Map<String, String> owners,
      final String territory) {
    if (!MapXml.hasTerritoryFlag(attachments, territory, CONVOY_ROUTE)) {
      return true;
    }
    final List<String> zones =
        TerritoryXml.values(attachments, territory, "convoyAttached").stream()
            .flatMap(value -> Stream.of(value.split(":")))
            .map(String::strip)
            .filter(zone -> !zone.isEmpty())
            .toList();
    return zones.isEmpty()
        || zones.stream()
            .anyMatch(
                zone ->
                    RulesXml.startAllied(game, nation, owners.get(zone))
                        && MapXml.hasTerritoryFlag(attachments, zone, CONVOY_ROUTE));
  }

  private static final String CONVOY_ROUTE = "convoyRoute";

  private static int puMultiplier(final Game game) {
    try {
      return GameRulesXml.optionValue(game, Constants.PU_MULTIPLIER)
          .map(value -> Integer.parseInt(value.strip()))
          .orElse(1);
    } catch (final NumberFormatException e) {
      return 1;
    }
  }

  /**
   * What a unit is worth, as TripleA's TuvCostsCalculator works it out for its battle
   * calculator and AI: the PUs of the rule in the nation's own purchase list that makes it,
   * divided by how many it makes and rounded up (of several rules the last); for a type the
   * nation cannot buy, the average of all rules that make it; a unit's "tuv" option instead of
   * either; plus the units it is made from (consumesUnits).
   */
  private static final class UnitCosts {
    private final Game game;
    private final Map<String, ProductionRule> rules = new HashMap<>();
    private final Map<String, Integer> forAll = new HashMap<>();
    private final Map<String, Integer> fixed = new HashMap<>();
    private final Map<String, Map<String, Integer>> madeFrom = new HashMap<>();
    private final Map<String, Map<String, Integer>> perNation = new HashMap<>();

    UnitCosts(final Game game) {
      this.game = game;
      final Set<String> unitTypes = Set.copyOf(MapXml.unitTypes(game));
      final Map<String, List<Integer>> different = new HashMap<>();
      for (final ProductionRule rule : ProductionXml.rules(game)) {
        rules.put(rule.getName(), rule);
        made(rule, unitTypes)
            .ifPresent(
                type ->
                    different.computeIfAbsent(type, t -> new ArrayList<>()).add(costOfOne(rule)));
      }
      different.forEach(
          (type, costs) ->
              forAll.put(
                  type,
                  (int) Math.round(costs.stream().mapToInt(Integer::intValue).average().orElse(0))));
      for (final Attachment attachment : AttachmentsXml.kept(game)) {
        if (!AttachmentsXml.isNamed(attachment, Constants.UNIT_ATTACHMENT_NAME)) {
          continue;
        }
        for (final Option option : Optional.ofNullable(attachment.getOptions()).orElse(List.of())) {
          if (AttachmentsXml.isOption(option, "tuv")) {
            number(option.getValue())
                .filter(tuv -> tuv > -1)
                .ifPresent(tuv -> fixed.put(attachment.getAttachTo(), tuv));
          } else if (AttachmentsXml.isOption(option, "consumesUnits")) {
            // "count:unit", the count also given apart
            final String[] parts =
                (Optional.ofNullable(option.getCount()).filter(c -> !c.isEmpty()).map(c -> c + ":")
                            .orElse("")
                        + option.getValue())
                    .split(":");
            if (parts.length == 2) {
              number(parts[0])
                  .ifPresent(
                      count ->
                          madeFrom
                              .computeIfAbsent(attachment.getAttachTo(), t -> new HashMap<>())
                              .put(parts[1], count));
            }
          }
        }
      }
      // a unit with a tuv of its own has a cost though no rule sells it
      fixed.forEach(forAll::put);
    }

    /** The unit type the rule makes: its first result, if that is a unit. */
    private static Optional<String> made(final ProductionRule rule, final Set<String> unitTypes) {
      return Optional.ofNullable(rule.getResults()).orElse(List.of()).stream()
          .findFirst()
          .map(result -> result.getResourceOrUnit())
          .filter(unitTypes::contains);
    }

    private static int costOfOne(final ProductionRule rule) {
      final int pus =
          Optional.ofNullable(rule.getCosts()).orElse(List.of()).stream()
              .filter(cost -> MapXml.PUS.equals(cost.getResource()))
              .mapToInt(cost -> Optional.ofNullable(cost.getQuantity()).orElse(0))
              .sum();
      final int made =
          Optional.ofNullable(rule.getResults()).orElse(List.of()).stream()
              .findFirst()
              .map(result -> Optional.ofNullable(result.getQuantity()).orElse(1))
              .orElse(1);
      return (int) Math.ceil((double) pus / Math.max(1, made));
    }

    private static Optional<Integer> number(final String text) {
      try {
        return Optional.of(Integer.parseInt(text.strip()));
      } catch (final NumberFormatException | NullPointerException e) {
        return Optional.empty();
      }
    }

    /** What one unit of the type is worth to the nation; empty when nothing gives it a cost. */
    Optional<Integer> of(final String nation, final String unitType) {
      final Map<String, Integer> costs = perNation.computeIfAbsent(nation, this::baseCosts);
      return costs.containsKey(unitType)
          ? Optional.of(total(unitType, costs, new HashSet<>()))
          : Optional.empty();
    }

    private Map<String, Integer> baseCosts(final String nation) {
      final Set<String> unitTypes = Set.copyOf(MapXml.unitTypes(game));
      final Map<String, Integer> costs = new HashMap<>();
      ProductionXml.frontierOf(game, nation)
          .ifPresent(
              frontier ->
                  ProductionXml.rulesIn(game, frontier).stream()
                      .map(rules::get)
                      .filter(java.util.Objects::nonNull)
                      .forEach(
                          rule ->
                              made(rule, unitTypes)
                                  .ifPresent(type -> costs.put(type, costOfOne(rule)))));
      forAll.forEach(costs::putIfAbsent);
      return costs;
    }

    private int total(final String unitType, final Map<String, Integer> costs, final Set<String> seen) {
      if (fixed.containsKey(unitType)) {
        return fixed.get(unitType);
      }
      int tuv = costs.getOrDefault(unitType, 0);
      final Map<String, Integer> parts = madeFrom.getOrDefault(unitType, Map.of());
      if (parts.isEmpty() || !seen.add(unitType)) {
        return tuv;
      }
      for (final Map.Entry<String, Integer> part : parts.entrySet()) {
        tuv += part.getValue() * total(part.getKey(), costs, seen);
      }
      seen.remove(unitType);
      return tuv;
    }
  }

  // ---- shown

  /**
   * The balance as two tables, the nations (a click on a column's header sorts them, a click on
   * a row opens the nation) and the alliances' totals below, with what the numbers leave out.
   */
  static JComponent table(final MapProject project, final Consumer<String> open) {
    final NationsBalance balance = of(project.game());
    final List<String> columns =
        new ArrayList<>(List.of("Nation", "Alliance", "Territories", "Income"));
    if (balance.unitPus()) {
      columns.addAll(List.of("PUs from units", "Upkeep"));
    }
    balance.resources().forEach(r -> columns.add(r.equals(MapXml.PUS) ? "Starting PUs" : r));
    columns.addAll(List.of("Units", "TUV"));
    final JTable nations = newTable(project, balance, balance.nations(), columns);
    nations.setAutoCreateRowSorter(true);
    nations.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    nations.setToolTipText("Click a nation to open it");
    nations.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            final int row = nations.rowAtPoint(e.getPoint());
            if (row >= 0) {
              open.accept(balance.nations().get(nations.convertRowIndexToModel(row)).name());
            }
          }
        });
    columns.set(0, "Alliance");
    columns.set(1, "Nations");
    final JTable alliances = newTable(project, balance, balance.alliances(), columns);
    final JPanel all = new JPanel();
    all.setLayout(new BoxLayout(all, BoxLayout.Y_AXIS));
    all.add(scrolled(nations, 12));
    all.add(Box.createVerticalStrut(Theme.GAP_L));
    all.add(leftAligned(Theme.newSecondaryLabel("Alliances, each the sum of its nations")));
    all.add(Box.createVerticalStrut(Theme.GAP_XS));
    all.add(scrolled(alliances, 6));
    final List<String> notes = new ArrayList<>();
    notes.add(
        "Income: the PUs a nation collects in its first turn, as TripleA's Production counts"
            + " them, times Multiply PUs: none from contested land, a sea zone another nation"
            + " owned first or a convoy route without a convoy zone held;"
            + " triggers that change owners at the start are not counted. TUV: its units at the start,"
            + " held ones too, each at what it costs to buy, as TripleA's battle calculator"
            + " counts them.");
    if (balance.unitPus()) {
      notes.add(
          "PUs from units and Upkeep: what its units on the map make and cost each turn"
              + " (createsResourcesList), times Multiply PUs; held ones do not count.");
    }
    if (balance.nations().stream().anyMatch(r -> r.alliances().size() > 1)) {
      notes.add("A nation in several alliances counts in each.");
    }
    all.add(Box.createVerticalStrut(Theme.GAP_S));
    notes.forEach(
        note ->
            all.add(
                leftAligned(
                    Theme.newSecondaryLabel(
                        "<html><div style='width:"
                            + Theme.htmlWidth(NOTE_WIDTH)
                            + "'>"
                            + note
                            + "</div></html>"))));
    if (!balance.withoutCost().isEmpty()) {
      final int count = balance.withoutCost().size();
      final JLabel without =
          new JLabel(
              "! "
                  + SearchList.count(count, count, "unit type", "unit types")
                  + " without a cost: counted as 0 in the TUV");
      without.setForeground(Theme.getWarningColor());
      without.setToolTipText(String.join(", ", balance.withoutCost()));
      all.add(leftAligned(without));
    }
    final JPanel page = new JPanel(new BorderLayout());
    page.add(all, BorderLayout.NORTH);
    return page;
  }

  private static JComponent leftAligned(final JComponent component) {
    final JPanel row = new JPanel(new BorderLayout());
    row.setOpaque(false);
    row.add(component, BorderLayout.WEST);
    return row;
  }

  private static JScrollPane scrolled(final JTable table, final int mostRows) {
    final JScrollPane scroll = SwingComponents.newJScrollPane(table);
    final int rows = Math.min(table.getRowCount(), mostRows);
    scroll.setPreferredSize(
        new Dimension(
            Math.max(720, table.getPreferredSize().width),
            table.getTableHeader().getPreferredSize().height + rows * table.getRowHeight() + 4));
    scroll.setBorder(BorderFactory.createLineBorder(Theme.getSeparatorColor()));
    return scroll;
  }

  // the notes wrap at about the tables' width, so the dialog is no wider than a screen
  private static final int NOTE_WIDTH = 860;

  private static JTable newTable(
      final MapProject project,
      final NationsBalance balance,
      final List<Row> rows,
      final List<String> columnNames) {
    final List<String> columns = List.copyOf(columnNames);
    final List<List<Object>> cells = rows.stream().map(balance::cells).toList();
    final int numbersFrom = 2;
    final JTable table =
        new JTable(
            new AbstractTableModel() {
              @Override
              public int getRowCount() {
                return rows.size();
              }

              @Override
              public int getColumnCount() {
                return columns.size();
              }

              @Override
              public String getColumnName(final int column) {
                return columns.get(column);
              }

              @Override
              public Class<?> getColumnClass(final int column) {
                return column < numbersFrom ? String.class : Integer.class;
              }

              @Override
              public Object getValueAt(final int row, final int column) {
                return cells.get(row).get(column);
              }
            });
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setRowHeight(Math.max(table.getRowHeight(), 28));
    table.setShowVerticalLines(false);
    table.setFillsViewportHeight(true);
    table.getTableHeader().setReorderingAllowed(false);
    final Set<String> nations = new LinkedHashSet<>(MapXml.nations(project.game()));
    table
        .getColumnModel()
        .getColumn(0)
        .setCellRenderer(
            new DefaultTableCellRenderer() {
              @Override
              public Component getTableCellRendererComponent(
                  final JTable in,
                  final Object value,
                  final boolean selected,
                  final boolean focused,
                  final int row,
                  final int column) {
                super.getTableCellRendererComponent(in, value, selected, focused, row, column);
                // a nation in its color, as in the list
                setIcon(
                    nations.contains(String.valueOf(value))
                        ? new MapLegend.Swatch(project.nationColor(String.valueOf(value)))
                        : null);
                return this;
              }
            });
    final DefaultTableCellRenderer number = new DefaultTableCellRenderer();
    number.setHorizontalAlignment(SwingConstants.RIGHT);
    for (int column = numbersFrom; column < columns.size(); column++) {
      table.getColumnModel().getColumn(column).setCellRenderer(number);
      table.getColumnModel().getColumn(column).setPreferredWidth(90);
    }
    table.getColumnModel().getColumn(0).setPreferredWidth(170);
    table.getColumnModel().getColumn(1).setPreferredWidth(190);
    return table;
  }
}
