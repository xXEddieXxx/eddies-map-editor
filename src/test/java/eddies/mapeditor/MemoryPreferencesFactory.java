package eddies.mapeditor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Java preferences kept in memory for the whole test JVM (build.gradle.kts sets it as its
 * java.util.prefs.PreferencesFactory): no test reads what the developer chose on this computer,
 * eg "Show all options" or the recent maps, and none writes there.
 */
public final class MemoryPreferencesFactory implements PreferencesFactory {
  private static final Preferences USER = new Node(null, "");
  private static final Preferences SYSTEM = new Node(null, "");

  @Override
  public Preferences userRoot() {
    return USER;
  }

  @Override
  public Preferences systemRoot() {
    return SYSTEM;
  }

  private static final class Node extends AbstractPreferences {
    private final Map<String, String> values = new ConcurrentHashMap<>();

    Node(final AbstractPreferences parent, final String name) {
      super(parent, name);
    }

    @Override
    protected void putSpi(final String key, final String value) {
      values.put(key, value);
    }

    @Override
    protected String getSpi(final String key) {
      return values.get(key);
    }

    @Override
    protected void removeSpi(final String key) {
      values.remove(key);
    }

    @Override
    protected void removeNodeSpi() {
      values.clear();
    }

    @Override
    protected String[] keysSpi() {
      return values.keySet().toArray(String[]::new);
    }

    @Override
    protected String[] childrenNamesSpi() {
      // AbstractPreferences keeps the children it made itself
      return new String[0];
    }

    @Override
    protected AbstractPreferences childSpi(final String name) {
      return new Node(this, name);
    }

    @Override
    protected void syncSpi() {}

    @Override
    protected void flushSpi() {}
  }
}
