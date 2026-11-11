package eddies.mapeditor;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.ExtensionMethod;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PropertyList.Property.Value;
import org.triplea.map.data.elements.Technology;
import org.triplea.map.data.elements.Technology.PlayerTech;
import org.triplea.map.data.elements.Technology.PlayerTech.Category;
import org.triplea.map.data.elements.Technology.PlayerTech.Category.Tech;
import org.triplea.map.data.elements.Technology.Technologies;
import org.triplea.map.data.elements.Technology.Technologies.TechName;
import eddies.mapeditor.MapXml.AttachmentKey;

/**
 * The technologies: those of the game, the categories each nation researches them in, and the
 * built-in ones a nation has at the start (its techAttachment). Every change returns a new model,
 * as in {@link MapXml}.
 */
@UtilityClass
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
class TechXml {
  /** The engine's own technologies by the option that gives them, eg "jetPower". */
  static final List<String> BUILT_IN =
      Arrays.stream(TechAdvance.class.getFields())
          .filter(f -> Modifier.isStatic(f.getModifiers()))
          .filter(f -> f.getName().startsWith("TECH_PROPERTY_"))
          .map(TechXml::constant)
          .toList();

  private static String constant(final Field field) {
    try {
      return (String) field.get(null);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The game's technologies; none when it has no list and the engine makes its standard ones. */
  static List<TechName> techs(final Game game) {
    return Optional.ofNullable(game.getTechnology())
        .map(Technology::getTechnologies)
        .map(Technologies::getTechNames)
        .map(List::<TechName>copyOf)
        .orElse(List.of());
  }

  static List<String> techNames(final Game game) {
    return techs(game).stream().map(TechName::getName).toList();
  }

  /** The built-in technology it is, eg "jetPower"; empty for one whose effects attachments give. */
  static Optional<String> builtIn(final TechName tech) {
    return Stream.of(tech.getTech(), tech.getName())
        .filter(BUILT_IN::contains)
        .findFirst();
  }

  /**
   * The game with a new technology, built in or not; nothing changes for a blank or taken name. A
   * game without a list gets TripleA's standard technologies first, which it had till now.
   */
  static Game withTech(final Game given, final String name, @Nullable final String builtIn) {
    final Game game = withStandardTechs(given);
    if (name.isBlank() || techNames(game).contains(name)) {
      return given;
    }
    final List<TechName> techs =
        Stream.concat(
                techs(game).stream(),
                Stream.of(
                    TechName.builder()
                        .name(name)
                        .tech(builtIn == null || builtIn.equals(name) ? null : builtIn)
                        .build()))
            .toList();
    return withTechnology(game, techs, playerTechs(game));
  }

  /**
   * The game with the technologies and categories the engine makes for a game without a list,
   * written out; a game with a list stays as it is.
   */
  static Game withStandardTechs(final Game game) {
    if (game.getTechnology() != null) {
      return game;
    }
    // the engine's own choice, which goes by the game's WW2V2 and WW2V3 options
    final GameData data = new GameData();
    MapXml.properties(game).stream()
        .filter(p -> Constants.WW2V2.equals(p.getName()) || Constants.WW2V3.equals(p.getName()))
        .forEach(
            p ->
                data.getProperties()
                    .set(
                        p.getName(),
                        Boolean.parseBoolean(
                            Optional.ofNullable(p.getValue())
                                .or(
                                    () ->
                                        Optional.ofNullable(p.getValueProperty())
                                            .map(Value::getData))
                                .orElse("")
                                .strip())));
    final GamePlayer anyone = new GamePlayer("anyone", data);
    data.getPlayerList().addPlayerId(anyone);
    TechAdvance.createDefaultTechAdvances(data);
    final List<TechName> techs =
        data.getTechnologyFrontier().getTechs().stream()
            .map(t -> TechName.builder().name(t.getProperty()).build())
            .toList();
    final List<Category> categories =
        anyone.getTechnologyFrontierList().getFrontiers().stream()
            .map(
                f ->
                    Category.builder()
                        .name(f.getName())
                        .techs(
                            f.getTechs().stream()
                                .map(t -> Tech.builder().name(t.getProperty()).build())
                                .toList())
                        .build())
            .toList();
    return withTechnology(
        game,
        techs,
        MapXml.nations(game).stream()
            .map(n -> PlayerTech.builder().player(n).categories(categories).build())
            .toList());
  }

  /** The game without the technology, also out of each nation's categories. */
  static Game withoutTech(final Game game, final String name) {
    final Optional<TechName> removed =
        techs(game).stream().filter(t -> name.equals(t.getName())).findFirst();
    if (removed.isEmpty()) {
      return game;
    }
    return withTechnology(
        game,
        techs(game).stream().filter(t -> t != removed.get()).toList(),
        playerTechs(game).stream()
            .map(p -> withoutTechIn(p, techs(game), removed.get()))
            .toList());
  }

  private static PlayerTech withoutTechIn(
      final PlayerTech nation, final List<TechName> all, final TechName tech) {
    return nation.toBuilder()
        .categories(
            categories(nation).stream()
                .map(
                    c ->
                        c.toBuilder()
                            .techs(techsOf(c).stream().filter(t -> !isTech(all, t, tech)).toList())
                            .build())
                .toList())
        .build();
  }

  /**
   * Whether the tech of a category is the technology, found as the engine does: the first
   * technology that is built in as it, eg "jetPower", else the one of its name.
   */
  private static boolean isTech(
      final List<TechName> all, final Tech tech, final TechName technology) {
    final String name = tech.getName();
    return all.stream()
            .filter(t -> builtIn(t).orElse(t.getName()).equals(name))
            .findFirst()
            .or(() -> all.stream().filter(t -> t.getName().equals(name)).findFirst())
            .filter(t -> t == technology)
            .isPresent();
  }

  private static List<PlayerTech> playerTechs(final Game game) {
    return Optional.ofNullable(game.getTechnology())
        .map(Technology::getPlayerTechs)
        .map(List::<PlayerTech>copyOf)
        .orElse(List.of());
  }

  private static List<Category> categories(final PlayerTech nation) {
    return Optional.ofNullable(nation.getCategories()).map(List::copyOf).orElse(List.of());
  }

  private static List<Tech> techsOf(final Category category) {
    return Optional.ofNullable(category.getTechs()).map(List::copyOf).orElse(List.of());
  }

  /** The nation's categories, eg "Air and Naval Advances", its research charts. */
  static List<String> categories(final Game game, final String nation) {
    return playerTechs(game).stream()
        .filter(p -> nation.equals(p.getPlayer()))
        .flatMap(p -> categories(p).stream())
        .map(Category::getName)
        .distinct()
        .toList();
  }

  /** The category the nation researches the technology in; empty when it cannot research it. */
  static Optional<String> categoryOf(final Game game, final String nation, final String tech) {
    final Optional<TechName> technology =
        techs(game).stream().filter(t -> tech.equals(t.getName())).findFirst();
    return technology.flatMap(
        t ->
            playerTechs(game).stream()
                .filter(p -> nation.equals(p.getPlayer()))
                .flatMap(p -> categories(p).stream())
                .filter(c -> techsOf(c).stream().anyMatch(x -> isTech(techs(game), x, t)))
                .map(Category::getName)
                .findFirst());
  }

  /**
   * The game with the nation researching the technology in the category, a new one if the nation
   * has none of this name.
   */
  static Game withTechIn(
      final Game game, final String nation, final String tech, final String category) {
    return gameWithTechIn(game, nation, tech, category);
  }

  /** The game with the nation not researching the technology. */
  static Game withoutTechIn(final Game game, final String nation, final String tech) {
    return gameWithTechIn(game, nation, tech, null);
  }

  private static Game gameWithTechIn(
      final Game game, final String nation, final String tech, @Nullable final String category) {
    final Optional<TechName> technology =
        techs(game).stream().filter(t -> tech.equals(t.getName())).findFirst();
    if (technology.isEmpty()) {
      return game;
    }
    final List<PlayerTech> nations = new ArrayList<>(playerTechs(game));
    int at = -1;
    for (int i = 0; i < nations.size(); i++) {
      if (nation.equals(nations.get(i).getPlayer())) {
        nations.set(i, withoutTechIn(nations.get(i), techs(game), technology.get()));
        at = at < 0 ? i : at;
      }
    }
    if (category != null) {
      if (at < 0) {
        nations.add(PlayerTech.builder().player(nation).categories(List.of()).build());
        at = nations.size() - 1;
      }
      nations.set(at, withTechAdded(nations.get(at), category, tech));
    }
    return withTechnology(game, techs(game), nations);
  }

  private static PlayerTech withTechAdded(
      final PlayerTech nation, final String category, final String tech) {
    final List<Category> categories = new ArrayList<>(categories(nation));
    final Tech added = Tech.builder().name(tech).build();
    final int at =
        categories.stream().map(Category::getName).toList().indexOf(category);
    if (at < 0) {
      categories.add(Category.builder().name(category).techs(List.of(added)).build());
    } else {
      final Category was = categories.get(at);
      categories.set(
          at,
          was.toBuilder()
              .techs(Stream.concat(techsOf(was).stream(), Stream.of(added)).toList())
              .build());
    }
    return nation.toBuilder().categories(categories).build();
  }

  /** The game without the nation's categories, as the engine refuses those of no nation. */
  static Game withoutNation(final Game game, final String nation) {
    return game.getTechnology() == null
        ? game
        : withTechnology(
            game,
            techs(game),
            playerTechs(game).stream().filter(p -> !nation.equals(p.getPlayer())).toList());
  }

  /** Whether the nation has the built-in technology at the start, eg "jetPower". */
  static boolean hasAtStart(final Game game, final String nation, final String builtIn) {
    return MapXml.option(game, Constants.TECH_ATTACHMENT_NAME, nation, builtIn)
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  static Game withAtStart(final Game game, final String nation, final String builtIn) {
    return MapXml.withOption(game, techKey(nation), builtIn, "true");
  }

  static Game withoutAtStart(final Game game, final String nation, final String builtIn) {
    return MapXml.withOption(game, techKey(nation), builtIn, null);
  }

  private static AttachmentKey techKey(final String nation) {
    return new AttachmentKey(
        Constants.TECH_ATTACHMENT_NAME, TechAttachment.class.getName(), "player", nation);
  }

  private static Game withTechnology(
      final Game game, final List<TechName> techs, final List<PlayerTech> nations) {
    final Technology technology =
        Optional.ofNullable(game.getTechnology()).orElseGet(() -> Technology.builder().build());
    final Technologies names =
        Optional.ofNullable(technology.getTechnologies())
            .orElseGet(() -> Technologies.builder().build());
    return game.toBuilder()
        .technology(
            technology.toBuilder()
                .technologies(names.toBuilder().techNames(techs).build())
                .playerTechs(nations)
                .build())
        .build();
  }
}
