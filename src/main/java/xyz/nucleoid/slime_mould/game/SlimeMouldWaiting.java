package xyz.nucleoid.slime_mould.game;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.plasmid.game.GameOpenContext;
import xyz.nucleoid.plasmid.game.GameOpenProcedure;
import xyz.nucleoid.plasmid.game.GameResult;
import xyz.nucleoid.plasmid.game.common.GameWaitingLobby;
import xyz.nucleoid.plasmid.game.common.config.PlayerConfig;
import xyz.nucleoid.plasmid.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.slime_mould.game.map.SlimeMouldMap;
import xyz.nucleoid.slime_mould.game.map.SlimeMouldMapBuilder;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;

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
			int maxPlayers = Math.min(config.players.maxPlayers(), SlimeMouldColors.COLORS.length);
			PlayerConfig players = new PlayerConfig(config.players.minPlayers(), maxPlayers, config.players.thresholdPlayers(), config.players.countdown());
            GameWaitingLobby.addTo(activity, players);

            SlimeMouldWaiting waiting = new SlimeMouldWaiting(world, map);

            activity.listen(GameActivityEvents.REQUEST_START, () -> {
                SlimeMouldActive.open(activity.getGameSpace(), world, map, config);
                return GameResult.ok();
            });

            activity.listen(GamePlayerEvents.OFFER, waiting::onOffer);
            activity.listen(PlayerDeathEvent.EVENT, (player, source) -> {
                waiting.spawnPlayer(player);
                return ActionResult.FAIL;
            });

            activity.listen(PlayerDamageEvent.EVENT, (player, source, amount) -> ActionResult.FAIL);
        });
    }

    private PlayerOfferResult onOffer(PlayerOffer offer) {
		return offer.accept(this.world, this.map.getWaitingSpawn()).and(() -> {
			offer.player().changeGameMode(GameMode.ADVENTURE);
		});
    }

    private void spawnPlayer(ServerPlayerEntity player) {
        player.changeGameMode(GameMode.ADVENTURE);

        Vec3d spawn = this.map.getWaitingSpawn();
        player.teleport(this.world, spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
    }
}
