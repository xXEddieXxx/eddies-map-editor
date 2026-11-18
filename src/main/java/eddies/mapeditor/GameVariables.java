package eddies.mapeditor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.Game;

/**
 * The game's {@code $variables$} (variableList), as a Choose list offers them below its plain
 * names: each by the kind of its elements, so a list of unit types offers the variables that hold
 * unit types alone.
 *
 * <p>TripleA's parser replaces "$Name$" in every option value of every attachment by the elements
 * joined with ":" (GameParser.setOptions calls GameDataVariables.replaceVariables on each value,
 * after the foreach's "@var@"; checked in 2026-11.16). Nothing outside the attachments is
 * replaced: a production rule, a placement, an alliance or an initial relationship takes no
 * variable, so their lists offer none.
 */
@UtilityClass
class GameVariables {
  /** What a variable's elements all are. */
  enum Kind {
    UNITS(MapXml::unitTypes),
    NATIONS(MapXml::nations),
    TERRITORIES(MapXml::territories);

    private final Function<Game, List<String>> names;

    Kind(final Function<Game, List<String>> names) {
      this.names = names;
    }
  }

  // how many elements the tool tip names before "and n more"
  private static final int NAMED = 8;

  /**
   * The variables whose every element is of the kind, "$Name$" to its elements, in the file's
   * order; a variable naming another is resolved through it. A variable of mixed elements, or an
   * empty one, is of no kind and offered nowhere.
   */
  static Map<String, List<String>> ofKind(final Game game, final Kind kind) {
    final Set<String> names = Set.copyOf(kind.names.apply(game));
    final Map<String, List<String>> found = new LinkedHashMap<>();
    AttachmentsXml.variables(game)
        .forEach(
            (name, elements) -> {
              if (isOfKind(elements, names)) {
                found.put("$" + name + "$", elements);
              }
            });
    return found;
  }

  /** The kind of the variable's elements, empty for a mixed or empty variable. */
  static Optional<Kind> kindOf(final Game game, final List<String> elements) {
    for (final Kind kind : Kind.values()) {
      if (isOfKind(elements, Set.copyOf(kind.names.apply(game)))) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }

  private static boolean isOfKind(final List<String> elements, final Set<String> names) {
    return !elements.isEmpty() && elements.stream().map(String::strip).allMatch(names::contains);
  }

  /** The elements for a tool tip: the first few, then how many more. */
  static String describe(final List<String> elements) {
    final String named =
        elements.stream().limit(NAMED).map(SearchList::escape).collect(Collectors.joining(", "));
    return "<html><div style='width:"
        + Theme.htmlWidth(260)
        + "'>"
        + named
        + (elements.size() > NAMED ? " and " + (elements.size() - NAMED) + " more" : "")
        + "</div></html>";
  }
}
