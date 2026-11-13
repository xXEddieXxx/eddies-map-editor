package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapXml.AttachmentKey;

/**
 * The territory attachment's options beyond those {@link MapXml} changes, eg isImpassable, the
 * original owner or the effects; and options the game reads more than once, eg resources, as a
 * list of values. Every change returns a new game, the given one when nothing changes.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class TerritoryXml {
  static final String IMPASSABLE = "isImpassable";
  static final String UNIT_PRODUCTION = "unitProduction";
  static final String ORIGINAL_OWNER = "originalOwner";
  static final String EFFECT = "territoryEffect";
  // a value as TripleA reads it with its count before it, eg "1:Longboat"
  private static final Pattern COUNTED =
      Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?):(.*)", Pattern.DOTALL);

  /** The territory's attachment, "territoryAttachment". */
  static AttachmentKey key(final String territory) {
    return new AttachmentKey(
        Constants.TERRITORY_ATTACHMENT_NAME,
        TerritoryAttachment.class.getName(),
        "territory",
        territory);
  }

  /**
   * The option's values in the attachment, in their order, each as TripleA reads it: a count
   * attribute before the value, eg "1:Longboat" for value="Longboat" count="1"; empty without the
   * option.
   */
  static List<String> values(final Game game, final AttachmentKey key, final String option) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    final int at = AttachmentsXml.lastIndexOf(attachments, key.name(), key.attachTo());
    return (at < 0 ? List.<Option>of() : AttachmentsXml.options(attachments.get(at)))
        .stream()
        .filter(o -> AttachmentsXml.isOption(o, option) && o.getValue() != null)
        .map(TriggerXml::joined)
        .toList();
  }

  /**
   * The game with the option's values these: the first takes the place of the option's first, the
   * others follow it; none takes the option out. A missing attachment is added after the others.
   */
  static Game withValues(
      final Game game, final AttachmentKey key, final String option, final List<String> values) {
    if (values(game, key, option).equals(values)) {
      return game;
    }
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
    final int at = AttachmentsXml.lastIndexOf(attachments, key.name(), key.attachTo());
    if (at < 0) {
      attachments.add(
          Attachment.builder()
              .name(key.name())
              .attachTo(key.attachTo())
              .javaClass(key.javaClass())
              .type(key.type())
              .options(values.stream().map(v -> written(option, v, false)).toList())
              .build());
    } else {
      final List<Option> options = new ArrayList<>();
      final boolean counted =
          AttachmentsXml.options(attachments.get(at)).stream()
              .anyMatch(
                  o ->
                      AttachmentsXml.isOption(o, option)
                          && o.getCount() != null
                          && !o.getCount().isEmpty());
      boolean placed = false;
      for (final Option o : AttachmentsXml.options(attachments.get(at))) {
        if (!AttachmentsXml.isOption(o, option)) {
          options.add(o);
        } else if (!placed) {
          // in the map's spelling of the option, eg "ConvoyAttached"
          values.forEach(v -> options.add(written(o.getName(), v, counted)));
          placed = true;
        }
      }
      if (!placed) {
        values.forEach(v -> options.add(written(option, v, false)));
      }
      attachments.set(at, attachments.get(at).toBuilder().options(List.copyOf(options)).build());
    }
    final AttachmentList list =
        Optional.ofNullable(game.getAttachmentList())
            .orElseGet(() -> AttachmentList.builder().build());
    return game.toBuilder()
        .attachmentList(list.toBuilder().attachments(List.copyOf(attachments)).build())
        .build();
  }

  /**
   * The option with the value as read; where the map writes this option's number as a count
   * attribute, the number goes there again.
   */
  private static Option written(final String name, final String value, final boolean counted) {
    final Matcher parts = COUNTED.matcher(value);
    return counted && parts.matches()
        ? Option.builder().name(name).value(parts.group(2)).count(parts.group(1)).build()
        : Option.builder().name(name).value(value).build();
  }

  /** The territory's option values, eg its resources "2:Ore". */
  static List<String> values(final Game game, final String territory, final String option) {
    return values(game, key(territory), option);
  }

  static Game withValues(
      final Game game, final String territory, final String option, final List<String> values) {
    return withValues(game, key(territory), option, values);
  }

  /** The game with the territory's option true, eg blockadeZone. */
  static Game withFlag(final Game game, final String territory, final String option) {
    return withValues(game, territory, option, List.of("true"));
  }

  /** The game without the territory's option, so it is false again. */
  static Game withoutFlag(final Game game, final String territory, final String option) {
    return withValues(game, territory, option, List.of());
  }

  /** How many units the territory's factory may place per turn: its own value, else its PUs. */
  static int unitProduction(final Game game, final String territory) {
    return values(game, territory, UNIT_PRODUCTION).stream()
        .flatMap(v -> parseInt(v).stream())
        .findFirst()
        .orElseGet(() -> MapXml.production(game, territory));
  }

  private static Optional<Integer> parseInt(final String value) {
    try {
      return Optional.of(Integer.parseInt(value.strip()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** The game with the territory placing so many units; as many as its PUs need no option. */
  static Game withUnitProduction(final Game game, final String territory, final int units) {
    final List<String> wanted =
        units == MapXml.production(game, territory) ? List.of() : List.of(String.valueOf(units));
    if (values(game, territory, UNIT_PRODUCTION).equals(wanted)) {
      return game;
    }
    // the game's "production" sets the unit production too: an own one must come after it
    final Game without = withValues(game, territory, UNIT_PRODUCTION, List.of());
    return withValues(without, territory, UNIT_PRODUCTION, wanted);
  }

  /** The nation that owned the territory first, for liberation; empty for the one at the start. */
  static Optional<String> originalOwner(final Game game, final String territory) {
    return values(game, territory, ORIGINAL_OWNER).stream().findFirst();
  }

  static Game withOriginalOwner(
      final Game game, final String territory, @Nullable final String nation) {
    return withValues(
        game, territory, ORIGINAL_OWNER, nation == null ? List.of() : List.of(nation));
  }

  /** The game with the territory's effects these, written as one option "forest:hill". */
  static Game withEffects(final Game game, final String territory, final List<String> effects) {
    if (MapXml.territoryEffects(game, territory).equals(effects)) {
      return game;
    }
    final List<String> written = effects.isEmpty() ? List.of() : List.of(String.join(":", effects));
    return withValues(game, territory, EFFECT, written);
  }

  /** The game with the effect on the territory too; one it has stays as it is. */
  static Game withEffect(final Game game, final String territory, final String effect) {
    final List<String> effects = MapXml.territoryEffects(game, territory);
    return effects.contains(effect)
        ? game
        : withEffects(game, territory, Stream.concat(effects.stream(), Stream.of(effect)).toList());
  }
}
