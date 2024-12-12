package xyz.nucleoid.slime_mould.game;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.plasmid.api.game.GameOpenContext;
import xyz.nucleoid.plasmid.api.game.GameOpenProcedure;
import xyz.nucleoid.plasmid.api.game.GameResult;
import xyz.nucleoid.plasmid.api.game.common.GameWaitingLobby;
import xyz.nucleoid.plasmid.api.game.common.config.PlayerLimiterConfig;
import xyz.nucleoid.plasmid.api.game.common.config.WaitingLobbyConfig;
import xyz.nucleoid.plasmid.api.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.api.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptor;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptorResult;
import xyz.nucleoid.plasmid.api.game.player.JoinOffer;
import xyz.nucleoid.slime_mould.game.map.SlimeMouldMap;
import xyz.nucleoid.slime_mould.game.map.SlimeMouldMapBuilder;
import xyz.nucleoid.stimuli.event.EventResult;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;

import java.util.OptionalInt;
import java.util.Set;

public final class SlimeMouldWaiting {
    private final ServerWorld world;
    private final SlimeMouldMap map;

    private SlimeMouldWaiting(ServerWorld world, SlimeMouldMap map) {
		this.world = world;
        this.map = map;
    }

    public static GameOpenProcedure open(GameOpenContext<SlimeMouldConfig> context) {
        SlimeMouldConfig config = context.config();
        SlimeMouldMapBuilder mapBuilder = new SlimeMouldMapBuilder(config.map);
        SlimeMouldMap map = mapBuilder.build(context.server());

        RuntimeWorldConfig worldConfig = new RuntimeWorldConfig()
                .setGenerator(map.asGenerator(context.server()));

        return context.openWithWorld(worldConfig, (activity, world) -> {
            OptionalInt maxPlayers = limit(config.players.playerConfig().maxPlayers(), SlimeMouldColors.COLORS.length);
            PlayerLimiterConfig limiterConfig = new PlayerLimiterConfig(maxPlayers, config.players.playerConfig().allowSpectators());
            WaitingLobbyConfig players = new WaitingLobbyConfig(limiterConfig, config.players.minPlayers(), config.players.thresholdPlayers(), config.players.countdown());
            GameWaitingLobby.addTo(activity, players);

            SlimeMouldWaiting waiting = new SlimeMouldWaiting(world, map);

            activity.listen(GameActivityEvents.REQUEST_START, () -> {
                SlimeMouldActive.open(activity.getGameSpace(), world, map, config);
                return GameResult.ok();
            });

            activity.listen(GamePlayerEvents.ACCEPT, waiting::onAcceptPlayers);
            activity.listen(GamePlayerEvents.OFFER, JoinOffer::accept);
            activity.listen(PlayerDeathEvent.EVENT, (player, source) -> {
                waiting.spawnPlayer(player);
                return EventResult.DENY;
            });

            activity.listen(PlayerDamageEvent.EVENT, (player, source, amount) -> EventResult.DENY);
        });
    }

    private JoinAcceptorResult onAcceptPlayers(JoinAcceptor acceptor) {
        return acceptor.teleport(this.world, this.map.getWaitingSpawn()).thenRunForEach(player -> {
            player.changeGameMode(GameMode.ADVENTURE);
        });
    }

    private void spawnPlayer(ServerPlayerEntity player) {
        player.changeGameMode(GameMode.ADVENTURE);

        Vec3d spawn = this.map.getWaitingSpawn();
        player.teleport(this.world, spawn.x, spawn.y, spawn.z, Set.of(), 0.0F, 0.0F, true);
    }

    private static OptionalInt limit(OptionalInt value, int max) {
        return value.isPresent() ? OptionalInt.of(Math.min(value.getAsInt(), max)) : OptionalInt.empty();
    }
}
