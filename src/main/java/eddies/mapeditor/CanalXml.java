package eddies.mapeditor;

import games.strategy.triplea.attachments.CanalAttachment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapXml.AttachmentKey;

/**
 * The game's canals: the game reads a canal from a canal attachment on each of its two sea zones,
 * both with its name, its land territories and the units that pass freely. Every change returns a
 * new game, the given one when nothing changes; options the editor does not know stay.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class CanalXml {
  private static final String KIND = "CanalAttachment";
  private static final String NAME = "canalName";
  private static final String LANDS = "landTerritories";
  private static final String EXCLUDED = "excludedUnits";

  /**
   * A canal: its sea zones, the land territories whose owner lets ships through, and its
   * excludedUnits values as the game has them, eg "ALL" or "submarine:fighter": the unit types that
   * pass anyway; none for the game's default, the air units.
   */
  record Canal(
      String name, List<String> seaZones, List<String> lands, List<String> excludedUnits) {}

  /** The canals in the order the game lists them, by their name. */
  static List<Canal> canals(final Game game) {
    final Map<String, List<Attachment>> byName = new LinkedHashMap<>();
    for (final Attachment a : canalAttachments(game)) {
      byName.computeIfAbsent(nameOf(a), name -> new ArrayList<>()).add(a);
    }
    return byName.entrySet().stream()
        .map(
            e -> {
              final Attachment first = e.getValue().getFirst();
              return new Canal(
                  e.getKey(),
                  e.getValue().stream().map(Attachment::getAttachTo).toList(),
                  value(first, LANDS).map(CanalXml::split).orElse(List.of()),
                  TerritoryXml.values(game, keyOf(first), EXCLUDED));
            })
        .toList();
  }

  private static List<Attachment> canalAttachments(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> KIND.equals(AttachmentsXml.kind(a)))
        .toList();
  }

  private static AttachmentKey keyOf(final Attachment attachment) {
    return new AttachmentKey(
        attachment.getName(),
        CanalAttachment.class.getName(),
        "territory",
        attachment.getAttachTo());
  }

  private static String nameOf(final Attachment attachment) {
    return value(attachment, NAME).orElse("");
  }

  private static Optional<String> value(final Attachment attachment, final String option) {
    return AttachmentsXml.options(attachment).stream()
        .filter(o -> option.equals(o.getName()) && o.getValue() != null)
        .map(o -> o.getValue().strip())
        .findFirst();
  }

  private static List<String> split(final String names) {
    return names.isEmpty() ? List.of() : List.of(names.split(":"));
  }

  /**
   * The unit types that pass the canal freely: those its excludedUnits name, all for ALL, none for
   * NONE; without any the game's default, the air units.
   */
  static List<String> passFreely(final Game game, final Canal canal) {
    if (canal.excludedUnits().isEmpty()) {
      return airUnits(game);
    }
    final Set<String> units = new LinkedHashSet<>();
    for (final String value : canal.excludedUnits()) {
      if (value.equalsIgnoreCase("ALL")) {
        units.addAll(MapXml.unitTypes(game));
      } else if (!value.equalsIgnoreCase("NONE")) {
        units.addAll(split(value));
      }
    }
    return List.copyOf(units);
  }

  private static List<String> airUnits(final Game game) {
    return MapXml.unitTypes(game).stream()
        .filter(unit -> MapXml.unitFlag(game, unit, "isAir"))
        .toList();
  }

  /** The excludedUnits values for these unit types passing freely: none for the air units. */
  static List<String> excludedUnits(final Game game, final List<String> units) {
    if (Set.copyOf(units).equals(Set.copyOf(airUnits(game)))) {
      return List.of();
    }
    return units.isEmpty() ? List.of("NONE") : joined(units);
  }

  /**
   * The game with the canal as given in place of the one of the old name: an attachment on each of
   * its sea zones, those of sea zones it left removed. A canal without sea zones has none.
   */
  static Game withCanal(final Game game, final String old, final Canal canal) {
    // what passes freely is written only when chosen anew: a map's "ALL" stays as it is
    final boolean newExcluded =
        canals(game).stream()
            .filter(c -> c.name().equals(old))
            .findFirst()
            .map(c -> !c.excludedUnits().equals(canal.excludedUnits()))
            .orElse(true);
    Game changed = game;
    final Map<String, String> attachmentOn = new LinkedHashMap<>();
    for (final Attachment a : canalAttachments(game)) {
      if (old.equals(nameOf(a))) {
        if (canal.seaZones().contains(a.getAttachTo())) {
          attachmentOn.putIfAbsent(a.getAttachTo(), a.getName());
        } else {
          changed = without(changed, a);
        }
      }
    }
    // both sea zones' attachments by one name, as maps have them
    final String name =
        attachmentOn.values().stream()
            .findFirst()
            .orElseGet(() -> AttachmentsXml.newName(game, KIND));
    for (final String seaZone : canal.seaZones()) {
      final String attachment =
          attachmentOn.getOrDefault(
              seaZone,
              AttachmentsXml.has(changed, name, seaZone)
                  ? AttachmentsXml.newName(changed, KIND)
                  : name);
      final AttachmentKey key =
          new AttachmentKey(attachment, CanalAttachment.class.getName(), "territory", seaZone);
      changed = TerritoryXml.withValues(changed, key, NAME, List.of(canal.name()));
      changed = TerritoryXml.withValues(changed, key, LANDS, joined(canal.lands()));
      if (newExcluded || !attachmentOn.containsKey(seaZone)) {
        changed = TerritoryXml.withValues(changed, key, EXCLUDED, canal.excludedUnits());
      }
    }
    return changed;
  }

  private static List<String> joined(final List<String> names) {
    return names.isEmpty() ? List.of() : List.of(String.join(":", names));
  }

  /** The game without the canal's attachments. */
  static Game withoutCanal(final Game game, final String canal) {
    Game changed = game;
    for (final Attachment a : canalAttachments(game)) {
      if (canal.equals(nameOf(a))) {
        changed = without(changed, a);
      }
    }
    return changed;
  }

  private static Game without(final Game game, final Attachment attachment) {
    return AttachmentsXml.withoutAttachment(
        game, AttachmentsXml.attachments(game).indexOf(attachment));
  }
}
