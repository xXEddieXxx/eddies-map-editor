package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;

/**
 * The political and user actions of a game: the buttons a nation may press in its politics or user
 * actions step. An action is an attachment whose name starts with the prefix TripleA finds it by;
 * its "text" names its texts in politicstext.properties or actionstext.properties. Every change
 * returns a new game; options the editor does not show are kept in their place.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class ActionsXml {
  static final String POLITICAL = "PoliticalActionAttachment";
  static final String USER = "UserActionAttachment";
  static final String TEXT = "text";
  static final String CONDITIONS = "conditions";
  static final String RELATIONSHIP_CHANGE = "relationshipChange";
  static final String ACTIVATE_TRIGGER = "activateTrigger";
  static final String COST_RESOURCES = "costResources";
  static final String CHANCE = "chance";
  static final String ATTEMPTS_PER_TURN = "attemptsPerTurn";
  static final String ACTION_ACCEPT = "actionAccept";

  /** The texts the game reads for a political action, each as "&lt;text&gt;.&lt;part&gt;". */
  static final List<String> POLITICAL_TEXTS =
      List.of(
          "BUTTON",
          "DESCRIPTION",
          "NOTIFICATION_SUCCESS",
          "OTHER_NOTIFICATION_SUCCESS",
          "NOTIFICATION_FAILURE",
          "OTHER_NOTIFICATION_FAILURE",
          "ACCEPT_QUESTION");

  /** The texts the game reads for a user action; the target's fall back to the others'. */
  static final List<String> USER_TEXTS =
      List.of(
          "BUTTON",
          "DESCRIPTION",
          "NOTIFICATION_SUCCESS",
          "TARGET_NOTIFICATION_SUCCESS",
          "OTHER_NOTIFICATION_SUCCESS",
          "NOTIFICATION_FAILURE",
          "TARGET_NOTIFICATION_FAILURE",
          "OTHER_NOTIFICATION_FAILURE",
          "ACCEPT_QUESTION");

  /** An action: its attachment's name and the nation it belongs to. */
  record Action(String name, String nation) {
    boolean isPolitical() {
      return AttachmentsXml.readName(name)
          .startsWith(Constants.POLITICALACTION_ATTACHMENT_PREFIX);
    }

    boolean matches(final Attachment attachment) {
      return name.equals(attachment.getName()) && nation.equals(attachment.getAttachTo());
    }

    /** The parts of its texts, eg "BUTTON". */
    List<String> textParts() {
      return isPolitical() ? POLITICAL_TEXTS : USER_TEXTS;
    }

    MapTexts.Kind textKind() {
      return isPolitical() ? MapTexts.Kind.POLITICS : MapTexts.Kind.ACTIONS;
    }
  }

  /** The actions in the order of the game, political ones and user ones. */
  static List<Action> actions(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .filter(ActionsXml::isAction)
        .map(a -> new Action(a.getName(), a.getAttachTo()))
        .toList();
  }

  private static boolean isAction(final Attachment attachment) {
    final String kind = AttachmentsXml.kind(attachment);
    final String name =
        AttachmentsXml.readName(Objects.toString(attachment.getName(), ""));
    return attachment.getAttachTo() != null
        && (POLITICAL.equals(kind) && name.startsWith(Constants.POLITICALACTION_ATTACHMENT_PREFIX)
            || USER.equals(kind) && name.startsWith(Constants.USERACTION_ATTACHMENT_PREFIX));
  }

  private static int indexOf(final Game game, final Action action) {
    final List<Attachment> attachments = AttachmentsXml.attachments(game);
    return IntStream.range(0, attachments.size())
        .filter(i -> action.matches(attachments.get(i)))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No action " + action));
  }

  /** The values the option has, each where it is given, eg every relationshipChange. */
  static List<String> values(final Game game, final Action action, final String option) {
    return AttachmentsXml.options(AttachmentsXml.attachments(game).get(indexOf(game, action)))
        .stream()
        .filter(o -> AttachmentsXml.isOption(o, option))
        .map(o -> Objects.toString(o.getValue(), ""))
        .toList();
  }

  /** The names an option lists, eg its conditions, also when it is given more than once. */
  static List<String> names(final Game game, final Action action, final String option) {
    return values(game, action, option).stream()
        .flatMap(v -> Arrays.stream(v.split(":")))
        .map(String::strip)
        .filter(n -> !n.isEmpty())
        .toList();
  }

  static Optional<String> value(final Game game, final Action action, final String option) {
    return values(game, action, option).stream().findFirst();
  }

  /** The text key of the action, eg "Axis_To_War"; its texts are "Axis_To_War.BUTTON" and so on. */
  static String textKey(final Game game, final Action action) {
    return value(game, action, TEXT).orElse("");
  }

  /**
   * The game with the option given these values, at the place of its first; none takes it out. The
   * same game when they are so already.
   */
  static Game withValues(
      final Game game, final Action action, final String option, final List<String> values) {
    if (values(game, action, option).equals(values)) {
      return game;
    }
    final int index = indexOf(game, action);
    final Attachment attachment = AttachmentsXml.attachments(game).get(index);
    final List<Option> options = new ArrayList<>();
    boolean placed = false;
    for (final Option o : AttachmentsXml.options(attachment)) {
      if (!AttachmentsXml.isOption(o, option)) {
        options.add(o);
      } else if (!placed) {
        values.forEach(v -> options.add(Option.builder().name(o.getName()).value(v).build()));
        placed = true;
      }
    }
    if (!placed) {
      values.forEach(v -> options.add(Option.builder().name(option).value(v).build()));
    }
    return AttachmentsXml.withAttachment(
        game, index, attachment.toBuilder().options(List.copyOf(options)).build());
  }

  /** The game with the option listing the names in one value, or without it for none. */
  static Game withNames(
      final Game game, final Action action, final String option, final List<String> names) {
    return withValues(
        game, action, option, names.isEmpty() ? List.of() : List.of(String.join(":", names)));
  }

  /**
   * The conditions an action may name: those declared before it, as the game reads attachments in
   * order and finds a condition only once it is read. A new action comes last, so it may name all.
   */
  static List<String> conditionsBefore(final Game game, final Action action) {
    return namesBefore(game, action, "RulesAttachment");
  }

  /** The triggers a user action may fire: those declared before it. */
  static List<String> triggersBefore(final Game game, final Action action) {
    return namesBefore(game, action, "TriggerAttachment");
  }

  private static List<String> namesBefore(final Game game, final Action action, final String kind) {
    final List<String> nations = MapXml.nations(game);
    return AttachmentsXml.attachments(game).subList(0, indexOf(game, action)).stream()
        .filter(a -> kind.equals(AttachmentsXml.kind(a)))
        .filter(a -> nations.contains(a.getAttachTo()))
        .map(Attachment::getName)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  /**
   * The name of a new action of the kind: it starts with the prefix TripleA finds it by and is one
   * no attachment has, eg "politicalActionAttachment_Axis_To_War".
   */
  static String newName(final Game game, final String kind, final String title) {
    final String prefix =
        POLITICAL.equals(kind)
            ? Constants.POLITICALACTION_ATTACHMENT_PREFIX
            : Constants.USERACTION_ATTACHMENT_PREFIX;
    final Set<String> taken =
        AttachmentsXml.attachments(game).stream()
            .map(Attachment::getName)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return RulesXml.freeName(taken, prefix + "_" + textKeyOf(title));
  }

  /** The words as a text key, eg "Axis_To_War" for "Axis to war". */
  static String textKeyOf(final String title) {
    final String key = title.strip().replaceAll("[^A-Za-z0-9_]+", "_");
    return key.isEmpty() ? "Action" : key;
  }

  /**
   * The game with the new action of the nation after all attachments, so it may name any condition,
   * with its text key; a political one needs a relationship change to load, eg
   * "Germans:Russians:War".
   */
  static Game withAction(
      final Game game,
      final String kind,
      final String name,
      final String nation,
      final String textKey,
      final List<String> relationshipChanges) {
    final Game added = AttachmentsXml.withNewAttachment(game, kind, name, nation);
    final Action action = new Action(name, nation);
    return withValues(
        withValues(added, action, TEXT, List.of(textKey)),
        action,
        RELATIONSHIP_CHANGE,
        relationshipChanges);
  }

  /** The game without the action. Ask {@link RulesXml#whereNamed} first: a trigger may name it. */
  static Game withoutAction(final Game game, final Action action) {
    return AttachmentsXml.withoutAttachment(game, indexOf(game, action));
  }
}
