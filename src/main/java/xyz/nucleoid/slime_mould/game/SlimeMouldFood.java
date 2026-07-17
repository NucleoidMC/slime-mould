package xyz.nucleoid.slime_mould.game;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.EntityTypes;
import xyz.nucleoid.plasmid.api.game.GameActivity;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.event.GamePlayerEvents;

import java.util.Iterator;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.cubemob.Slime;

public final class SlimeMouldFood implements Iterable<SlimeMouldFood.Instance> {
    private final GameSpace gameSpace;
    private final ServerLevel world;
    private final Slime slimeEntity;

    private int nextEntityId = -1;

    private final Long2ObjectMap<Instance> food = new Long2ObjectOpenHashMap<>();

    public SlimeMouldFood(GameActivity activity, ServerLevel world) {
        this.gameSpace = activity.getGameSpace();
        this.world = world;

        Slime slimeEntity = new Slime(EntityTypes.SLIME, this.world);
        slimeEntity.setInvulnerable(true);
        slimeEntity.setNoGravity(true);
        slimeEntity.setNoAi(true);

        this.slimeEntity = slimeEntity;

        activity.listen(GamePlayerEvents.ADD, player -> {
            for (Instance food : this.food.values()) {
                this.sendFoodTo(food, player);
            }
        });

        activity.listen(GamePlayerEvents.REMOVE, player -> {
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

        for (ServerPlayer player : this.gameSpace.getPlayers()) {
            this.sendFoodTo(food, player);
        }

        return true;
    }

    public boolean removeFoodAt(BlockPos position) {
        Instance food = this.food.remove(position.asLong());
        if (food != null) {
            for (ServerPlayer player : this.gameSpace.getPlayers()) {
                this.removeFoodFor(food, player);
            }
            return true;
        }

        return false;
    }

    private void sendFoodTo(Instance food, ServerPlayer player) {
        RandomSource random = player.level().getRandom();

        Slime entity = this.slimeEntity;
        entity.setId(food.entityId);
        entity.setUUID(Mth.createInsecureUUID(random));
        entity.setPosRaw(food.position.getX() + 0.5, food.position.getY(), food.position.getZ() + 0.5);
        entity.setYRot(random.nextFloat() * 360.0F);

        ServerGamePacketListenerImpl networkHandler = player.connection;
        networkHandler.send(entity.getAddEntityPacket(new ServerEntity(this.world, entity, 0, false, new ServerEntity.Synchronizer() {
            @Override
            public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {

            }

            @Override
            public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {

            }

            @Override
            public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> predicate) {

            }
        })));
        networkHandler.send(new ClientboundSetEntityDataPacket(food.entityId, entity.getEntityData().getNonDefaultValues()));
    }

    private void removeFoodFor(Instance food, ServerPlayer player) {
        player.connection.send(new ClientboundRemoveEntitiesPacket(food.entityId));
    }

    @Override
    public Iterator<Instance> iterator() {
        return this.food.values().iterator();
    }

    public int getCount() {
        return this.food.size();
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
