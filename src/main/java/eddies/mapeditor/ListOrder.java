package eddies.mapeditor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import lombok.experimental.UtilityClass;

/**
 * The order of a list the game file keeps, which the game shows in that order: a purchase list's
 * rules in the buy window, the unit types where TripleA lists them, the options on the setup
 * screen. An entry moves one up or down, or the list takes an order; the same list again is no
 * change, so the caller makes no step of it.
 */
@UtilityClass
class ListOrder {
  /** The list with the entry at from put at to; the same list when either is out of it. */
  static <T> List<T> moved(final List<T> list, final int from, final int to) {
    if (from < 0 || to < 0 || from >= list.size() || to >= list.size() || from == to) {
      return list;
    }
    final List<T> moved = new ArrayList<>(list);
    moved.add(to, moved.remove(from));
    return List.copyOf(moved);
  }

  /** The list in the comparator's order, entries it holds equal kept as they were. */
  static <T> List<T> ordered(final List<T> list, final Comparator<? super T> order) {
    final List<T> ordered = new ArrayList<>(list);
    ordered.sort(order);
    return List.copyOf(ordered);
  }

  /** A to Z by the name, as a player reads it: case does not count, then as written. */
  static <T> Comparator<T> byName(final Function<T, String> name) {
    return Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER).thenComparing(name);
  }
}
