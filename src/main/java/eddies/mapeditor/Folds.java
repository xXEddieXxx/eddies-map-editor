package eddies.mapeditor;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Whether the editor's fold rows ("▸ More options (4)", Home's steps and More tools, …) are open:
 * as clicked, for the session, else closed, or open with "Show all options" (File menu), which
 * is kept per user in Java preferences: off for a new map maker, on for one who wants every
 * option in sight. The rows stay, so one can still be closed.
 */
final class Folds {
  private static final String SHOW_ALL = "showAllOptions";
  // what a click chose, by the row's id (Swing thread); static, so a choice outlives the forms the
  // editors build anew with every pick
  private static final Map<String, Boolean> CHOSEN = new HashMap<>();
  private static Preferences preferences = Preferences.userNodeForPackage(Folds.class);

  private Folds() {}

  /** Whether "Show all options" is on: fold rows then start open. */
  static boolean showsAll() {
    return preferences.getBoolean(SHOW_ALL, false);
  }

  /** Turns "Show all options" on or off; every row is then as the switch says again. */
  static void toggleShowAll() {
    preferences.putBoolean(SHOW_ALL, !showsAll());
    CHOSEN.clear();
  }

  static boolean isOpen(final String id) {
    return CHOSEN.getOrDefault(id, showsAll());
  }

  static void toggle(final String id) {
    CHOSEN.put(id, !isOpen(id));
  }

  /** Keeps the switch in these preferences from now on, forgetting the clicks: for tests. */
  static void keepIn(final Preferences others) {
    preferences = others;
    CHOSEN.clear();
  }
}
