package xyz.nucleoid.slime_mould.game;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import xyz.nucleoid.plasmid.game.GameLogic;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.event.PlayerAddListener;
import xyz.nucleoid.plasmid.game.event.PlayerRemoveListener;

import java.util.Iterator;

public final class SlimeMouldFood implements Iterable<SlimeMouldFood.Instance> {
    private final GameSpace gameSpace;
    private final SlimeEntity slimeEntity;

    private int nextEntityId = -1;

    private final Long2ObjectMap<Instance> food = new Long2ObjectOpenHashMap<>();

    public SlimeMouldFood(GameLogic logic) {
        this.gameSpace = logic.getSpace();

        SlimeEntity slimeEntity = new SlimeEntity(EntityType.SLIME, this.gameSpace.getWorld());
        slimeEntity.setInvulnerable(true);
        slimeEntity.setNoGravity(true);
        slimeEntity.setAiDisabled(true);

        this.slimeEntity = slimeEntity;

        logic.on(PlayerAddListener.EVENT, player -> {
            for (Instance food : this.food.values()) {
                this.sendFoodTo(food, player);
            }
        });

        logic.on(PlayerRemoveListener.EVENT, player -> {
            for (Instance food : this.food.values()) {
                this.removeFoodFor(food, player);
            }
        });
    }

    public boolean addFood(BlockPos position) {
        if (this.food.containsKey(position.asLong())) {
            return false;
        }

        Instance food = new Instance(position, this.nextEntityId--);
        this.food.put(position.asLong(), food);

        for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
            this.sendFoodTo(food, player);
        }

        return true;
    }

    public boolean removeFoodAt(BlockPos position) {
        Instance food = this.food.remove(position.asLong());
        if (food != null) {
            for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
                this.removeFoodFor(food, player);
            }
            return true;
        }

        return false;
    }

    private void sendFoodTo(Instance food, ServerPlayerEntity player) {
        SlimeEntity entity = this.slimeEntity;
        entity.setEntityId(food.entityId);
        entity.setPos(food.position.getX() + 0.5, food.position.getY(), food.position.getZ() + 0.5);

        ServerPlayNetworkHandler networkHandler = player.networkHandler;
        networkHandler.sendPacket(entity.createSpawnPacket());
        networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(food.entityId, entity.getDataTracker(), true));
    }

    private void removeFoodFor(Instance food, ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(food.entityId));
    }

    @Override
    public Iterator<Instance> iterator() {
        return this.food.values().iterator();
    }

    public static final class Instance {
        public final BlockPos position;
        private final int entityId;

        Instance(BlockPos position, int entityId) {
            this.entityId = entityId;
            this.position = position;
        }
    }
}
