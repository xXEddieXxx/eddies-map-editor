package eddies.mapeditor;

import eddies.mapeditor.ActionsXml.Action;
import eddies.mapeditor.MapTexts.Kind;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;

/**
 * Find in the map (Ctrl+K): territories, nations, unit types, purchase lists and rules, game
 * options, triggers, conditions and other attachments, and texts by their names and ids, listed
 * by the tool that edits them; Open shows the tool with the item chosen.
 */
final class MapSearch {
  static final String TITLE = "Find in the map";
  private static final char SEPARATOR = '\u0001';
  // a hit's place in a list key is written in these, which no search has, eg not "112"
  private static final char DIGIT_ZERO = (char) 0xE000;

  /** Something the map has, the tool that edits it and what that tool is shown, by name. */
  record Hit(String tool, String item, String name, String kind) {}

  /** The dialog's pane and the hit last chosen in it. */
  record Search(Dialogs.Pane pane, Supplier<Optional<Hit>> chosen) {}

  private MapSearch() {}

  /** Asks what to find and opens it in its tool; nothing when cancelled. */
  static void show(final Component inWindow, final MapProject project) {
    final Search search = newSearch(hits(project.game(), project.texts()));
    Dialogs.show(inWindow, TITLE, search.pane())
        .filter(search.pane().verb()::equals)
        .flatMap(verb -> search.chosen().get())
        .ifPresent(hit -> MapMakerWindow.openTool(inWindow, hit.tool(), hit.item()));
  }

  /** The hits by tool, folded until a search. */
  static Search newSearch(final List<Hit> hits) {
    final AtomicReference<Optional<String>> chosen = new AtomicReference<>(Optional.empty());
    final AtomicReference<Runnable> recheck = new AtomicReference<>(() -> {});
    final SearchList list =
        new SearchList(
                "item",
                "items",
                () -> keys(hits),
                key -> hit(hits, key).name(),
                key -> hit(hits, key).kind(),
                key -> {
                  chosen.set(Optional.of(key));
                  recheck.get().run();
                })
            .groupFoldedBy(key -> hit(hits, key).tool(), "tool", "tools");
    list.setPreferredSize(new Dimension(460, 420));
    final Dialogs.Pane pane =
        Dialogs.newPane(
            "Type a name or id, eg a territory, a unit type or a trigger.",
            List.of(),
            list,
            "Open",
            () -> chosen.get().isPresent());
    recheck.set(pane.recheck());
    return new Search(pane, () -> chosen.get().map(key -> hit(hits, key)));
  }

  // a list's key is the hit's item, which the search finds, and its place, as items repeat
  private static List<String> keys(final List<Hit> hits) {
    final List<String> keys = new ArrayList<>(hits.size());
    for (int i = 0; i < hits.size(); i++) {
      final StringBuilder key = new StringBuilder(hits.get(i).item()).append(SEPARATOR);
      for (final char digit : Integer.toString(i).toCharArray()) {
        key.append((char) (DIGIT_ZERO + digit - '0'));
      }
      keys.add(key.toString());
    }
    return keys;
  }

  private static Hit hit(final List<Hit> hits, final String key) {
    int index = 0;
    for (final char digit : key.substring(key.lastIndexOf(SEPARATOR) + 1).toCharArray()) {
      index = index * 10 + digit - DIGIT_ZERO;
    }
    return hits.get(index);
  }

  /** Everything the search finds, in the sidebar's order of the tools. */
  static List<Hit> hits(final Game game, final MapTexts texts) {
    final List<Hit> hits = new ArrayList<>();
    for (final String territory : MapXml.territories(game)) {
      hits.add(
          new Hit(
              "Territories",
              territory,
              territory,
              MapXml.isWater(game, territory) ? "sea zone" : "territory"));
    }
    MapXml.nations(game).forEach(n -> hits.add(new Hit("Nations", n, n, "nation")));
    MapXml.unitTypes(game).forEach(u -> hits.add(new Hit("Units", u, u, "unit type")));
    final Map<String, String> listTitles = ProductionXml.listTitles(game);
    for (final String frontier : ProductionXml.frontiers(game)) {
      hits.add(
          new Hit(
              "Production",
              frontier,
              listTitles.getOrDefault(frontier, frontier),
              "purchase list"));
      ProductionXml.rulesIn(game, frontier)
          .forEach(rule -> hits.add(new Hit("Production", frontier, rule, "rule in " + frontier)));
    }
    GameRulesXml.options(game)
        .forEach(
            o -> hits.add(new Hit(RulesEditors.GAME_OPTIONS, o.getName(), o.getName(), "option")));
    final Map<String, Action> actions =
        ActionsXml.actions(game).stream()
            .collect(
                Collectors.toMap(a -> a.name() + SEPARATOR + a.nation(), a -> a, (a, b) -> a));
    for (final Attachment attachment : AttachmentsXml.attachments(game)) {
      hits.add(attachmentHit(attachment, actions));
    }
    for (final Kind kind : TextsEditor.KINDS) {
      for (final MapTexts.Entry entry : texts.entries(kind)) {
        hits.add(
            new Hit(
                TextsEditor.TITLE,
                entry.key(),
                MapTexts.title(kind, entry.key(), game),
                "text in " + kind.getFile()));
      }
    }
    return hits;
  }

  /** A trigger in Triggers, an objective in Objectives, an action in Actions, else Attachments. */
  private static Hit attachmentHit(final Attachment attachment, final Map<String, Action> actions) {
    final String name = Objects.toString(attachment.getName(), "");
    final String attachTo = Objects.toString(attachment.getAttachTo(), "");
    final String kind = AttachmentsXml.kindName(AttachmentsXml.kind(attachment)).one();
    final String line = kind + " of " + attachTo;
    final Function<String, Hit> in = tool -> new Hit(tool, name, name, line);
    final Optional<Action> action = Optional.ofNullable(actions.get(name + SEPARATOR + attachTo));
    if (action.isPresent()) {
      return new Hit("Actions", ActionsEditor.key(action.get()), name, line);
    }
    if (AttachmentsXml.kind(attachment).equals(TriggerXml.TRIGGER)) {
      return in.apply(TriggersEditor.TITLE);
    }
    if (ObjectiveXml.isObjective(attachment)) {
      return in.apply(ObjectivesEditor.TITLE);
    }
    return new Hit("Attachments", name + " of " + attachTo, name, line);
  }
}
