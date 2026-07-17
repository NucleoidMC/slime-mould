package xyz.nucleoid.slime_mould.game.map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.util.ColoredBlocks;

public final class SlimeMouldPlate {
    public final BlockBounds bounds;
    public final int radius;

    public SlimeMouldPlate(BlockBounds bounds, int radius) {
        this.bounds = bounds;
        this.radius = radius;
    }

    public static BlockState getMouldBlock(DyeColor color) {
        return ColoredBlocks.glass(color).defaultBlockState();
    }

    public BlockPos getRandomSurfacePos(RandomSource random) {
        BlockPos min = this.bounds.min();
        BlockPos max = this.bounds.max();

        return new BlockPos(
                min.getX() + random.nextInt(max.getX() - min.getX() + 1),
                max.getY(),
                min.getZ() + random.nextInt(max.getZ() - min.getZ() + 1)
        );
    }

    public Surface testSurface(ServerLevel world, BlockPos pos) {
        if (this.bounds.contains(pos) && world.isEmptyBlock(pos.above())) {
            BlockState state = world.getBlockState(pos);
            return testSurface(state.getBlock());
        }
        return Surface.NONE;
    }

    public BlockPos getSpawnPos(double theta, double distance) {
        Vec3 plateCenter = this.bounds.centerTop();
        int plateY = this.bounds.max().getY();

        double spawnX = plateCenter.x + Math.cos(theta) * distance;
        double spawnZ = plateCenter.z - Math.sin(theta) * distance;

        return BlockPos.containing(spawnX, plateY, spawnZ);
    }

    public static Surface testSurface(Block block) {
        if (block == Blocks.STAINED_GLASS.white()) {
            return Surface.STERILE;
        } else if (block instanceof StainedGlassBlock) {
            return Surface.MOULD;
        } else {
            return Surface.NONE;
        }
    }

    public enum Surface {
        NONE,
        STERILE,
        MOULD;

        public boolean isOnPlate() {
            return this != NONE;
        }

        public boolean isSterile() {
            return this == STERILE;
        }

        public boolean isMould() {
            return this == MOULD;
        }
    }
}
