package xyz.nucleoid.slime_mould.game;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.GameActivity;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.game.common.team.GameTeamConfig;
import xyz.nucleoid.plasmid.game.common.team.GameTeamKey;
import xyz.nucleoid.plasmid.game.common.widget.SidebarWidget;
import xyz.nucleoid.plasmid.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRuleType;
import xyz.nucleoid.plasmid.util.ItemStackBuilder;
import xyz.nucleoid.slime_mould.game.map.SlimeMouldMap;
import xyz.nucleoid.slime_mould.game.map.SlimeMouldPlate;
import xyz.nucleoid.stimuli.event.block.BlockUseEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SlimeMouldActive {
    private static final long CLOSE_TICKS = 20 * 5;

    private static final Item GROWTH_ITEM = Items.WOODEN_HOE;

    private static final ItemStackBuilder GROWTH_STACK = ItemStackBuilder.of(GROWTH_ITEM)
            .setName(Text.literal("Grow your mould!").formatted(Formatting.GREEN, Formatting.BOLD))
            .addLore(Text.literal("Right click on blocks adjacent to your own to grow"));

    private static final EntityAttributeModifier STRETCHED_THIN_MODIFIER = new EntityAttributeModifier(
            "slime_mould_stretched_thin",
            -0.5,
            EntityAttributeModifier.Operation.MULTIPLY_BASE
    );

    private final GameSpace gameSpace;
    private final ServerWorld world;
    private final SlimeMouldMap map;
    private final SlimeMouldConfig config;

    private final SidebarWidget sidebar;

    private final Map<GameProfile, Mould> playerToMould = new Object2ObjectOpenHashMap<>();

    private final SlimeMouldFood food;

    private boolean singlePlayer;

    private long lastFoodSpawnTime;

    private long closeTime = -1;

    private SlimeMouldActive(GameActivity activity, ServerWorld world, SlimeMouldMap map, SlimeMouldConfig config, GlobalWidgets widgets) {
        this.gameSpace = activity.getGameSpace();
        this.world = world;
        this.map = map;
        this.config = config;

        this.food = new SlimeMouldFood(activity, world);

        this.sidebar = widgets.addSidebar(Text.literal("Slime Mould!").formatted(Formatting.RED, Formatting.BOLD));
    }

    public static void open(GameSpace gameSpace, ServerWorld world, SlimeMouldMap map, SlimeMouldConfig config) {
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

            activity.listen(GamePlayerEvents.OFFER, active::onOffer);

            activity.listen(GameActivityEvents.TICK, active::tick);
            activity.listen(BlockUseEvent.EVENT, active::onUseBlock);

            activity.listen(PlayerDamageEvent.EVENT, (player, source, amount) -> ActionResult.FAIL);
        });
    }

    private void onEnable() {
        int plateRadius = this.map.getPlate().radius;
        double spawnRadius = plateRadius * (3.0 / 4.0);

        PlayerSet players = this.gameSpace.getPlayers();

        List<DyeColor> colors = SlimeMouldColors.shuffledColors(this.world.random);

        int i = 0;

        for (ServerPlayerEntity player : players) {
            Mould mould = new Mould(player, colors.get(i), this.config.initialFoodLevel);
            this.playerToMould.put(player.getGameProfile(), mould);

            double theta = ((double) i / players.size()) * Math.PI * 2.0;
            this.spawnPlayer(player, mould, theta, spawnRadius);

            this.updateFoodBar(player, mould);

            i++;
        }

        this.spawnInitialFood();

        this.singlePlayer = players.size() == 1;
        this.lastFoodSpawnTime = this.world.getTime();

        this.updateSidebar();
    }

    private void spawnPlayer(ServerPlayerEntity player, Mould mould, double theta, double radius) {
        float yaw = (float) Math.toDegrees(theta);

        BlockPos spawnPos = this.map.getPlate().getSpawnPos(theta, radius);
        player.teleport(this.world, spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, yaw, 0.0F);

        this.world.setBlockState(spawnPos, mould.block);

        player.getInventory().insertStack(GROWTH_STACK.build());
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, Integer.MAX_VALUE, 200, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, Integer.MAX_VALUE, 0, false, false));
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
        long time = this.world.getTime();

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
        BlockPos foodSpawnPos = this.findFoodSpawnPos(this.world, this.world.random);
        if (foodSpawnPos == null) {
            return false;
        }

        return this.food.addFood(foodSpawnPos);
    }

    @Nullable
    private BlockPos findFoodSpawnPos(ServerWorld world, Random random) {
        SlimeMouldPlate plate = this.map.getPlate();
        BlockPos spawnPos = plate.getRandomSurfacePos(random);

        if (plate.testSurface(world, spawnPos).isSterile()) {
            return spawnPos.up();
        } else {
            return null;
        }
    }

    private void tickMoulds() {
        for (ServerPlayerEntity player : this.gameSpace.getPlayers()) {
            Mould mould = this.playerToMould.get(player.getGameProfile());
            if (mould != null) {
                this.tickMould(player, mould);
            }
        }
    }

    private void tickMould(ServerPlayerEntity player, Mould mould) {
        BlockPos pos = player.getBlockPos();
        if (mould.moveTo(pos)) {
            this.onMouldMove(player, mould, pos);
        }
    }

    private void tickClosing(long time) {
        if (time >= this.closeTime) {
            this.gameSpace.close(GameCloseReason.FINISHED);
        }
    }

    private void onMouldMove(ServerPlayerEntity player, Mould mould, BlockPos pos) {
        BlockState surface = player.world.getBlockState(pos.down());

        boolean slowed = surface != mould.block && !this.hasAdjacentMould(pos, mould);

        if (mould.updateSlowed(slowed)) {
            EntityAttributeInstance attribute = player.getAttributes().getCustomInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
            if (attribute != null) {
                if (slowed) {
                    attribute.addTemporaryModifier(STRETCHED_THIN_MODIFIER);
                } else {
                    attribute.removeModifier(STRETCHED_THIN_MODIFIER);
                }
            }
        }
    }

    private ActionResult onUseBlock(ServerPlayerEntity player, Hand hand, BlockHitResult result) {
        if (player.getStackInHand(hand).getItem() == GROWTH_ITEM) {
            Mould mould = this.playerToMould.get(player.getGameProfile());
            if (mould == null) {
                return ActionResult.PASS;
            }

            BlockPos pos = result.getBlockPos();
            if (this.tryGrowInto(player, mould, pos)) {
                return ActionResult.SUCCESS;
            }
        }

        return ActionResult.PASS;
    }

    private boolean tryGrowInto(ServerPlayerEntity player, Mould mould, BlockPos pos) {
        if (this.world.getBlockState(pos) == mould.block || player.getItemCooldownManager().isCoolingDown(GROWTH_ITEM)) {
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

    private void growInto(ServerPlayerEntity player, Mould mould, BlockPos pos) {
        if (this.config.growCooldown > 0) {
            player.getItemCooldownManager().set(GROWTH_ITEM, this.config.growCooldown);
        }

        if (this.food.removeFoodAt(pos.up())) {
            player.playSound(SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 1.0F, 1.0F);
            mould.food += this.config.foodLevelPerFood;
        }

        Mould existingMould = this.getMouldFor(player.world.getBlockState(pos));
        if (existingMould != null && --existingMould.score <= 0) {
            this.eliminate(existingMould);
        }

        player.world.setBlockState(pos, mould.block);
        mould.score++;

        this.updateFoodBar(player, mould);
        this.updateSidebar();
    }

    private boolean hasAdjacentMould(BlockPos pos, Mould mould) {
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        for (int i = 0; i < 4; i++) {
            mutablePos.set(pos, Direction.fromHorizontal(i));
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

    private PlayerOfferResult onOffer(PlayerOffer offer) {
        return offer.accept(this.world, this.map.getWaitingSpawn()).and(() -> {
            offer.player().changeGameMode(GameMode.SPECTATOR);
        });
    }

    private void updateFoodBar(ServerPlayerEntity player, Mould mould) {
        player.experienceProgress = 1.0F;
        player.setExperienceLevel(mould.food);
    }

    private void updateSidebar() {
        this.sidebar.set(content -> {
            content.add(Text.literal("Expand your slime mould!").formatted(Formatting.GREEN));
            content.add(ScreenTexts.EMPTY);

            this.playerToMould.values().stream()
                    .sorted(Comparator.comparingInt(mould -> -mould.score))
                    .limit(8)
                    .forEach(mould -> {
                        content.add(
                            mould.team.config().name().copy()
                                    .append(": ")
                                    .append(Text.literal(mould.score + "").formatted(Formatting.GOLD))
                        );
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
                    Text.literal(mould.player.getName())
                            .append(" has been eliminated!")
                            .formatted(Formatting.RED)
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

        Mould(ServerPlayerEntity player, DyeColor dyeColor, int initialFood) {
            GameProfile profile = player.getGameProfile();
            this.player = profile;

            this.dyeColor = dyeColor;

            GameTeamKey key = new GameTeamKey(dyeColor.getName());
            GameTeamConfig teamConfig = GameTeamConfig.builder()
                    .setName(Text.literal(profile.getName()))
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
