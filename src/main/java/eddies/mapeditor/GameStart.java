package eddies.mapeditor;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.gameparser.GameParsingValidation;
import games.strategy.engine.framework.AutoSaveFileUtils;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.message.PlayerListing;
import games.strategy.engine.framework.startup.launcher.LaunchAction;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.net.LocalNoOpMessenger;
import games.strategy.net.Messengers;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ResourceLoader;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import games.strategy.triplea.ui.mapdata.MapData;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.triplea.ai.does.nothing.DoesNothingAi;
import org.triplea.sound.HeadlessSoundChannel;

/**
 * Starts a loaded game in memory as TripleA does for a game on this computer, without a window:
 * what TripleA checks when the game is chosen, what the setup screen reads of every nation, the
 * launch itself (TripleA's LocalLauncher) and the steps of the first round but its battles, every
 * nation played by the computer player that does nothing. The game's first exception is why it
 * does not start.
 */
@UtilityClass
@Slf4j
class GameStart {
  // a first round takes well under a second, also of a big game; with thousands of triggers
  // 20 s, as in the game itself: the steps begun in this time are what the check plays
  private static final long PLAY_MILLIS = 5_000;
  // a step that never ends is left
  private static final long MOST_MILLIS = 30_000;

  /**
   * Why TripleA does not start the game, empty when it runs its first round. The game is played
   * here, so it is one loaded for this only.
   *
   * @param mapFolder the map's folder, for the texts steps read from it (eg notifications)
   */
  static Optional<String> whyNot(final GameData data, @Nullable final Path mapFolder) {
    try {
      return refused(data)
          .or(() -> setupScreen(data))
          .or(() -> hasSettings() ? firstRound(data, mapFolder) : Optional.empty());
    } catch (final LinkageError e) {
      // a TripleA that starts its games otherwise: nothing to say about this one
      log.info("This TripleA's game start is not the one the start check knows", e);
      return Optional.empty();
    }
  }

  /**
   * Why TripleA's game window does not open on the map's files as they are saved: what it reads
   * of them before it shows and when it first draws (its MapData): outlines, a center and a unit
   * place for every territory, the map's size, every nation's colors. Empty when it opens.
   */
  static Optional<String> whyNotShown(final GameData data, final Path mapFolder) {
    try (ResourceLoader loader = new ResourceLoader(List.of(mapFolder))) {
      final MapData mapData = new MapData(loader);
      mapData.verify(data);
      // TripleA draws no territory whose line in place.txt holds no place ("No where to place")
      final Optional<String> withoutPlace =
          data.getMap().getTerritories().stream()
              .filter(territory -> mapData.getPlacementPoints(territory).isEmpty())
              .map(territory -> territory.getName())
              .findFirst();
      if (withoutPlace.isPresent()) {
        return Optional.of(
            "TripleA cannot show the map: no place for units in "
                + withoutPlace.get()
                + " (Territories, Places)");
      }
      mapData.getMapDimensions();
      mapData.impassableColor();
      Stream.concat(
              data.getPlayerList().getPlayers().stream(),
              Stream.of(data.getPlayerList().getNullPlayer()))
          .map(GamePlayer::getName)
          .forEach(
              nation -> {
                mapData.getPlayerColor(nation);
                mapData.getUnitColor(nation);
                mapData.getUnitBrightness(nation);
              });
      return Optional.empty();
    } catch (final RuntimeException e) {
      // TripleA names every territory in a line of its own: the first ones say enough
      final List<String> lines = message(e).lines().toList();
      return Optional.of(
          "TripleA cannot show the map: "
              + String.join("; ", lines.subList(0, Math.min(3, lines.size())))
              + (lines.size() > 3 ? "; and " + (lines.size() - 3) + " more" : ""));
    } catch (final LinkageError e) {
      log.info("This TripleA reads a map's files otherwise than the start check knows", e);
      return Optional.empty();
    }
  }

  /** TripleA's settings, which its steps read: the editor sets them up when it starts. */
  private static boolean hasSettings() {
    try {
      ClientSetting.defaultGameUri.getValue();
      return true;
    } catch (final IllegalStateException e) {
      return false;
    }
  }

  /** What TripleA checks of a game chosen to play; with an error the game counts as not there. */
  private static Optional<String> refused(final GameData data) {
    return new GameParsingValidation(data).validate().stream().findFirst();
  }

  /** What the setup screen reads of every nation for its row: a failure leaves the rows half made. */
  private static Optional<String> setupScreen(final GameData data) {
    final Map<String, Boolean> enabled = data.getPlayerList().getPlayersEnabledListing();
    final Map<String, String> playedBy = GamePlayer.currentPlayers(data);
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      try {
        Objects.requireNonNull(enabled.get(player.getName()), "not in the list of players");
        Objects.requireNonNull(playedBy.get(player.getName()), "no kind of player");
        Objects.requireNonNull(data.getAllianceTracker().getAlliancesPlayerIsIn(player));
        data.getProperties()
            .getPlayerPropertyOrThrow(Constants.getPropertyNameIncomePercentageFor(player));
      } catch (final RuntimeException e) {
        return Optional.of(
            "TripleA's setup screen fails at " + player.getName() + ": " + message(e));
      }
    }
    return Optional.empty();
  }

  private static Optional<String> firstRound(final GameData data, @Nullable final Path mapFolder) {
    return within(MOST_MILLIS, at -> play(data, mapFolder, at));
  }

  /**
   * What the round says, given where to tell which step it is at; a round still running after
   * this time proved nothing, so that is the reason, and its thread is interrupted.
   */
  static Optional<String> within(
      final long millis, final Function<AtomicReference<String>, Optional<String>> round) {
    final AtomicReference<Optional<String>> result = new AtomicReference<>(Optional.empty());
    final AtomicReference<String> at = new AtomicReference<>("the launch");
    // on a thread of its own: a step that waits for ever must not keep Check waiting
    final Thread thread = new Thread(() -> result.set(round.apply(at)), "start check");
    thread.setDaemon(true);
    thread.start();
    try {
      thread.join(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (thread.isAlive()) {
      thread.interrupt();
      return Optional.of(
          "TripleA did not finish " + at.get() + " within " + millis / 1000 + " s");
    }
    return result.get();
  }

  private static Optional<String> play(
      final GameData data, @Nullable final Path mapFolder, final AtomicReference<String> at) {
    final Path saves;
    try {
      saves = Files.createTempDirectory("eddies-start-check");
    } catch (final IOException e) {
      log.info("No folder for the start check's saved game", e);
      return Optional.empty();
    }
    final AtomicReference<String> ended = new AtomicReference<>();
    ServerGame game = null;
    boolean inStep = false;
    try {
      final PlayerTypes.Type computer = doesNothing();
      final Map<String, PlayerTypes.Type> types = new HashMap<>();
      data.getPlayerList().getPlayers().forEach(p -> types.put(p.getName(), computer));
      final LaunchAction launch = launchAction(saves, mapFolder, ended);
      // from here as TripleA's LocalLauncher.loadGame
      final PlayerListing listing =
          new PlayerListing(
              data.getPlayerList().getPlayersEnabledListing(),
              types,
              data.getGameName(),
              String.valueOf(data.getSequence().getRound()));
      listing.doPreGameStartDataModifications(data);
      final Set<Player> players =
          data.getGameLoader()
              .newPlayers(listing.getLocalPlayerTypeMap(new PlayerTypes(List.of(computer))));
      game =
          new ServerGame(
              data,
              players,
              new HashMap<>(),
              new Messengers(new LocalNoOpMessenger()),
              ClientNetworkBridge.NO_OP_SENDER,
              launch);
      game.setDelegateAutosavesEnabled(false);
      // a game that ends itself returns instead of waiting for its window to close
      game.setStopGameOnDelegateExecutionStop(true);
      data.getGameLoader().startGame(game, players, launch, null);
      game.setUpGameForRunningSteps();
      final long until = System.currentTimeMillis() + PLAY_MILLIS;
      // twice the steps: one run several times, eg a bid, is passed without its turn
      int most = 2 * data.getSequence().size() + 2;
      while (!game.isGameOver()
          && ended.get() == null
          && data.getSequence().getRound() == 1
          && most-- > 0
          && System.currentTimeMillis() < until) {
        final GameStep step = data.getSequence().getStep();
        at.set("step " + step.getName());
        // not the battles: units that start in one territory with enemies fight when the step
        // begins, and TripleA pauses after each loss for the player to watch
        if (step.getDelegate() instanceof BattleDelegate) {
          data.getSequence().next();
          continue;
        }
        inStep = true;
        game.runNextStep();
        inStep = false;
      }
      // a game over in its first round is a start too, eg a scenario lost when nobody moves
      return Optional.empty();
    } catch (final RuntimeException e) {
      log.info("The start check's game stopped at {}", at.get(), e);
      return Optional.of("TripleA stops at " + at.get() + ": " + message(e) + hint(message(e)));
    } finally {
      // only between steps: stopping a game inside a step waits for it, then ends the program
      if (game != null && !inStep && !game.isGameOver()) {
        try {
          game.stopGame();
        } catch (final RuntimeException e) {
          log.info("The start check's game did not stop", e);
        }
      }
      Optional.ofNullable(game).map(ServerGame::getResourceLoader).ifPresent(ResourceLoader::close);
      deleteAll(saves);
    }
  }

  /**
   * TripleA's computer player that does nothing, here really nothing: TripleA's buys and places
   * with what the nation has, pausing after each for the player to watch.
   */
  private static PlayerTypes.Type doesNothing() {
    return new PlayerTypes.Type(PlayerTypes.DOES_NOTHING_PLAYER_LABEL) {
      @Override
      public Player newPlayerWithName(final String name) {
        return new DoesNothingAi(name, getLabel()) {
          @Override
          protected void purchase(
              final boolean purchaseForBid,
              final int pusToSpend,
              final IPurchaseDelegate purchaseDelegate,
              final GameData data,
              final GamePlayer player) {}

          @Override
          protected void place(
              final boolean placeForBid,
              final IAbstractPlaceDelegate placeDelegate,
              final GameState data,
              final GamePlayer player) {}
        };
      }
    };
  }

  /**
   * What the launch asks its window for, without one. A proxy, as the questions differ between
   * TripleA's versions: the round's saved game goes to a folder of its own, a game that wants to
   * stop may, the rest is answered with nothing.
   */
  private static LaunchAction launchAction(
      final Path saves, @Nullable final Path mapFolder, final AtomicReference<String> ended) {
    final AutoSaveFileUtils saved =
        new AutoSaveFileUtils() {
          @Override
          public Path getOddRoundAutoSaveFile() {
            return saves.resolve("round.tsvg");
          }

          @Override
          public Path getEvenRoundAutoSaveFile() {
            return saves.resolve("round.tsvg");
          }
        };
    return (LaunchAction)
        Proxy.newProxyInstance(
            LaunchAction.class.getClassLoader(),
            new Class<?>[] {LaunchAction.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "startGame":
                  final IGame game = (IGame) args[1];
                  game.setResourceLoader(
                      new ResourceLoader(mapFolder == null ? List.of() : List.of(mapFolder)));
                  game.setDisplay(new HeadlessDisplay());
                  game.setSoundChannel(new HeadlessSoundChannel());
                  return null;
                case "promptGameStop":
                  ended.set(String.valueOf(args[0]));
                  return true;
                case "getAutoSaveFileUtils":
                  return saved;
                case "getAutoSaveFile":
                  return saves.resolve("game.tsvg");
                case "getPlayerTypes":
                  return PlayerTypes.getBuiltInPlayerTypes();
                case "getDefaultLocalPlayerType":
                  return PlayerTypes.WEAK_AI;
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                case "toString":
                  return "start check";
                default:
                  final Class<?> answer = method.getReturnType();
                  return answer == boolean.class
                      ? Boolean.TRUE
                      : answer == Optional.class ? Optional.empty() : null;
              }
            });
  }

  private static void deleteAll(final Path folder) {
    try (Stream<Path> files = Files.list(folder)) {
      for (final Path file : files.toList()) {
        Files.deleteIfExists(file);
      }
      Files.deleteIfExists(folder);
    } catch (final IOException e) {
      log.info("The start check's saved game stays in {}", folder, e);
    }
  }

  /** What to do about a reason TripleA gives in its own words, where the editor knows. */
  private static String hint(final String reason) {
    return reason.startsWith("Capital not found for")
        ? ". Give the nation a capital in Territories, or tick May have no capital in Nations"
        : "";
  }

  /** What went wrong: of an exception that only wraps another, eg a remote call's, the cause. */
  private static String message(final Throwable e) {
    Throwable told = e;
    while (told.getMessage() == null && told.getCause() != null) {
      told = told.getCause();
    }
    return Optional.ofNullable(told.getMessage()).orElse(told.getClass().getSimpleName());
  }
}
