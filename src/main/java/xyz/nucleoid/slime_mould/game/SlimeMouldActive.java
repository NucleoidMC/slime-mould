package xyz.nucleoid.slime_mould.game;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.game.GameActivity;
import xyz.nucleoid.plasmid.api.game.GameCloseReason;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeamConfig;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeamKey;
import xyz.nucleoid.plasmid.api.game.common.widget.SidebarWidget;
import xyz.nucleoid.plasmid.api.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.api.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptor;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptorResult;
import xyz.nucleoid.plasmid.api.game.player.JoinOffer;
import xyz.nucleoid.plasmid.api.game.player.PlayerSet;
import xyz.nucleoid.plasmid.api.game.rule.GameRuleType;
import xyz.nucleoid.plasmid.api.util.ItemStackBuilder;
import xyz.nucleoid.plasmid.api.util.PlayerUtil;
import xyz.nucleoid.slime_mould.SlimeMould;
import xyz.nucleoid.slime_mould.game.map.SlimeMouldMap;
import xyz.nucleoid.slime_mould.game.map.SlimeMouldPlate;
import xyz.nucleoid.stimuli.event.EventResult;
import xyz.nucleoid.stimuli.event.block.BlockUseEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SlimeMouldActive {
    private static final long CLOSE_TICKS = 20 * 5;

    private static final Identifier GROWTH_ID = SlimeMould.identifier("growth");
    private static final Item GROWTH_ITEM = Items.WOODEN_HOE;

    private static final ItemStack BASE_GROWTH_STACK = ItemStackBuilder.of(GROWTH_ITEM)
            .setName(Component.translatable("text.slime_mould.growth_stack.name").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
            .addLore(Component.translatable("text.slime_mould.growth_stack.description"))
            .build();

    private static final Identifier STRETCHED_THIN_MODIFIER_ID = SlimeMould.identifier("stretched_thin");

    private static final AttributeModifier STRETCHED_THIN_MODIFIER = new AttributeModifier(
            STRETCHED_THIN_MODIFIER_ID,
            -0.5,
            AttributeModifier.Operation.ADD_MULTIPLIED_BASE
    );

    private final GameSpace gameSpace;
    private final ServerLevel world;
    private final SlimeMouldMap map;
    private final SlimeMouldConfig config;

    private final SidebarWidget sidebar;

    private final Map<GameProfile, Mould> playerToMould = new Object2ObjectOpenHashMap<>();

    private final SlimeMouldFood food;

    private final ItemStack growthStack;

    private boolean singlePlayer;

    private long lastFoodSpawnTime;

    private long closeTime = -1;

    private SlimeMouldActive(GameActivity activity, ServerLevel world, SlimeMouldMap map, SlimeMouldConfig config, GlobalWidgets widgets) {
        this.gameSpace = activity.getGameSpace();
        this.world = world;
        this.map = map;
        this.config = config;

        this.food = new SlimeMouldFood(activity, world);

        if (this.config.growCooldown > 0) {
            this.growthStack = BASE_GROWTH_STACK.copy();

            UseCooldown useCooldown = new UseCooldown(this.config.growCooldown / (float) SharedConstants.TICKS_PER_SECOND, Optional.of(GROWTH_ID));
            this.growthStack.set(DataComponents.USE_COOLDOWN, useCooldown);
        } else {
            this.growthStack = BASE_GROWTH_STACK;
        }

        this.sidebar = widgets.addSidebar(Component.translatable("text.slime_mould.sidebar.title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
    }

    public static void open(GameSpace gameSpace, ServerLevel world, SlimeMouldMap map, SlimeMouldConfig config) {
        gameSpace.setActivity(activity -> {
            GlobalWidgets widgets = GlobalWidgets.addTo(activity);

            SlimeMouldActive active = new SlimeMouldActive(activity, world, map, config, widgets);

            activity.deny(GameRuleType.CRAFTING);
            activity.deny(GameRuleType.PVP);
            activity.deny(GameRuleType.BLOCK_DROPS);
            activity.deny(GameRuleType.FALL_DAMAGE);
            activity.deny(GameRuleType.HUNGER);
            activity.deny(GameRuleType.THROW_ITEMS);
            activity.deny(GameRuleType.PVP);

            activity.listen(GameActivityEvents.ENABLE, active::onEnable);

            activity.listen(GamePlayerEvents.ACCEPT, active::onAcceptPlayers);
            activity.listen(GamePlayerEvents.OFFER, JoinOffer::acceptSpectators);

            activity.listen(GameActivityEvents.TICK, active::tick);
            activity.listen(BlockUseEvent.EVENT, active::onUseBlock);

            activity.listen(PlayerDamageEvent.EVENT, (player, source, amount) -> EventResult.DENY);
        });
    }

    private void onEnable() {
        int plateRadius = this.map.getPlate().radius;
        double spawnRadius = plateRadius * (3.0 / 4.0);

        PlayerSet players = this.gameSpace.getPlayers();

        List<DyeColor> colors = SlimeMouldColors.shuffledColors(this.world.getRandom());

        int i = 0;

        for (ServerPlayer player : players) {
            Mould mould = new Mould(player, colors.get(i), this.config.initialFoodLevel);
            this.playerToMould.put(player.getGameProfile(), mould);

            double theta = ((double) i / players.size()) * Math.PI * 2.0;
            this.spawnPlayer(player, mould, theta, spawnRadius);

            this.updateFoodBar(player, mould);

            i++;
        }

        this.spawnInitialFood();

        this.singlePlayer = players.size() == 1;
        this.lastFoodSpawnTime = this.world.getGameTime();

        this.updateSidebar();
    }

    private void spawnPlayer(ServerPlayer player, Mould mould, double theta, double radius) {
        float yaw = (float) Math.toDegrees(theta);

        BlockPos spawnPos = this.map.getPlate().getSpawnPos(theta, radius);
        player.teleportTo(this.world, spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, Set.of(), yaw, 0.0F, true);

        this.world.setBlockAndUpdate(spawnPos, mould.block);

        player.getInventory().add(this.growthStack.copy());

        AttributeInstance jumpStrength = player.getAttributes().getInstance(Attributes.JUMP_STRENGTH);
        jumpStrength.setBaseValue(0);

        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, Integer.MAX_VALUE, 0, false, false));
    }

    private void spawnInitialFood() {
        int foodCount = this.getTargetFoodCount();

        int i = 0;
        while (i < foodCount) {
            if (this.trySpawnFood()) {
                i++;
            }
        }
    }

    private void tick() {
        long time = this.world.getGameTime();

        if (this.closeTime > 0) {
            this.tickClosing(time);
            return;
        }

        this.tickFood(time);
        this.tickMoulds();

        if (!this.singlePlayer && this.playerToMould.size() <= 1) {
            this.closeTime = time + CLOSE_TICKS;
        }
    }

    private void tickFood(long time) {
        if (time - this.lastFoodSpawnTime >= 20 && this.food.getCount() < this.getTargetFoodCount()) {
            if (this.trySpawnFood()) {
                this.lastFoodSpawnTime = time;
            }
        }
    }

    private boolean trySpawnFood() {
        BlockPos foodSpawnPos = this.findFoodSpawnPos(this.world, this.world.getRandom());
        if (foodSpawnPos == null) {
            return false;
        }

        return this.food.addFood(foodSpawnPos);
    }

    @Nullable
    private BlockPos findFoodSpawnPos(ServerLevel world, RandomSource random) {
        SlimeMouldPlate plate = this.map.getPlate();
        BlockPos spawnPos = plate.getRandomSurfacePos(random);

        if (plate.testSurface(world, spawnPos).isSterile()) {
            return spawnPos.above();
        } else {
            return null;
        }
    }

    private void tickMoulds() {
        for (ServerPlayer player : this.gameSpace.getPlayers()) {
            Mould mould = this.playerToMould.get(player.getGameProfile());
            if (mould != null) {
                this.tickMould(player, mould);
            }
        }
    }

    private void tickMould(ServerPlayer player, Mould mould) {
        BlockPos pos = player.blockPosition();
        if (mould.moveTo(pos)) {
            this.onMouldMove(player, mould, pos);
        }
    }

    private void tickClosing(long time) {
        if (time >= this.closeTime) {
            this.gameSpace.close(GameCloseReason.FINISHED);
        }
    }

    private void onMouldMove(ServerPlayer player, Mould mould, BlockPos pos) {
        BlockState surface = player.level().getBlockState(pos.below());

        boolean slowed = surface != mould.block && !this.hasAdjacentMould(pos, mould);

        if (mould.updateSlowed(slowed)) {
            AttributeInstance attribute = player.getAttributes().getInstance(Attributes.MOVEMENT_SPEED);
            if (attribute != null) {
                if (slowed) {
                    attribute.addTransientModifier(STRETCHED_THIN_MODIFIER);
                } else {
                    attribute.removeModifier(STRETCHED_THIN_MODIFIER_ID);
                }
            }
        }
    }

    private InteractionResult onUseBlock(ServerPlayer player, InteractionHand hand, BlockHitResult result) {
        if (player.getItemInHand(hand).getItem() == GROWTH_ITEM) {
            Mould mould = this.playerToMould.get(player.getGameProfile());
            if (mould == null) {
                return InteractionResult.PASS;
            }

            BlockPos pos = result.getBlockPos();
            if (this.tryGrowInto(player, mould, pos)) {
                return InteractionResult.SUCCESS;
            }
        }

        return InteractionResult.PASS;
    }

    private boolean tryGrowInto(ServerPlayer player, Mould mould, BlockPos pos) {
        if (this.world.getBlockState(pos) == mould.block || player.getCooldowns().isOnCooldown(this.growthStack)) {
            return false;
        }

        SlimeMouldPlate.Surface surface = this.map.getPlate().testSurface(this.world, pos);
        if (!surface.isSterile() || !this.hasAdjacentMould(pos, mould)) {
            return false;
        }

        if (this.takeFoodFrom(mould)) {
            this.growInto(player, mould, pos);
            return true;
        } else {
            return false;
        }
    }

    private void growInto(ServerPlayer player, Mould mould, BlockPos pos) {
        UseCooldown useCooldown = this.growthStack.get(DataComponents.USE_COOLDOWN);
        if (useCooldown != null) {
            useCooldown.apply(this.growthStack, player);
        }

        if (this.food.removeFoodAt(pos.above())) {
            PlayerUtil.playSoundToPlayer(player, SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 1.0F, 1.0F);
            mould.food += this.config.foodLevelPerFood;
        }

        Mould existingMould = this.getMouldFor(player.level().getBlockState(pos));
        if (existingMould != null && --existingMould.score <= 0) {
            this.eliminate(existingMould);
        }

        player.level().setBlockAndUpdate(pos, mould.block);
        mould.score++;

        this.updateFoodBar(player, mould);
        this.updateSidebar();
    }

    private boolean hasAdjacentMould(BlockPos pos, Mould mould) {
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 4; i++) {
            mutablePos.setWithOffset(pos, Direction.from2DDataValue(i));
            if (this.world.getBlockState(mutablePos) == mould.block) {
                return true;
            }
        }

        return false;
    }

    @Nullable
    private Mould getMouldFor(BlockState block) {
        if (!SlimeMouldPlate.testSurface(block.getBlock()).isMould()) {
            return null;
        }

        for (Mould mould : this.playerToMould.values()) {
            if (mould.block == block) {
                return mould;
            }
        }

        return null;
    }

    private JoinAcceptorResult onAcceptPlayers(JoinAcceptor acceptor) {
        return acceptor.teleport(this.world, this.map.getWaitingSpawn()).thenRunForEach(player -> {
            player.setGameMode(GameType.SPECTATOR);
        });
    }

    private void updateFoodBar(ServerPlayer player, Mould mould) {
        player.experienceProgress = 1.0F;
        player.setExperienceLevels(mould.food);
    }

    private void updateSidebar() {
        this.sidebar.set(content -> {
            content.add(Component.translatable("text.slime_mould.sidebar.description").withStyle(ChatFormatting.GREEN));
            content.add(CommonComponents.EMPTY);

            this.playerToMould.values().stream()
                    .sorted(Comparator.comparingInt(mould -> -mould.score))
                    .limit(8)
                    .forEach(mould -> {
                        Component name = mould.team.config().name();
                        Component score = Component.literal(mould.score + "").withStyle(ChatFormatting.GOLD);

                        content.add(Component.translatable("text.slime_mould.sidebar.line", name, score));
                    });
        });
    }

    private boolean takeFoodFrom(Mould mould) {
        if (mould.food > 0) {
            mould.food--;
            return true;
        } else {
            this.eliminate(mould);
            return false;
        }
    }

    private void eliminate(Mould mould) {
        if (this.playerToMould.remove(mould.player, mould)) {
            this.gameSpace.getPlayers().sendMessage(
                    Component.translatable("text.slime_mould.eliminated", mould.player.name())
                            .withStyle(ChatFormatting.RED)
            );
        }
    }

    private int getTargetFoodCount() {
        return this.playerToMould.size() * this.config.foodSpawnPerPlayer;
    }

    static final class Mould {
        final GameProfile player;

        final DyeColor dyeColor;
        final GameTeam team;
        final BlockState block;

        BlockPos lastPos;
        boolean slowed;

        int food;
        int score = 1;

        Mould(ServerPlayer player, DyeColor dyeColor, int initialFood) {
            GameProfile profile = player.getGameProfile();
            this.player = profile;

            this.dyeColor = dyeColor;

            GameTeamKey key = new GameTeamKey(dyeColor.getName());
            GameTeamConfig teamConfig = GameTeamConfig.builder()
                    .setName(Component.literal(profile.name()))
                    .setColors(GameTeamConfig.Colors.from(dyeColor))
                    .build();

            this.team = new GameTeam(key, teamConfig);
            this.block = SlimeMouldPlate.getMouldBlock(dyeColor);

            this.food = initialFood;
        }

        boolean moveTo(BlockPos pos) {
            boolean moved = !pos.equals(this.lastPos);
            this.lastPos = pos;
            return moved;
        }

        boolean updateSlowed(boolean slowed) {
            boolean changed = this.slowed != slowed;
            this.slowed = slowed;
            return changed;
        }
    }
}
