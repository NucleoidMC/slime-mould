package xyz.nucleoid.slime_mould.game.map;

import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.map_templates.MapTemplateMetadata;
import xyz.nucleoid.map_templates.MapTemplateSerializer;
import xyz.nucleoid.map_templates.TemplateRegion;
import xyz.nucleoid.plasmid.api.game.GameOpenException;

import java.io.IOException;

public final class SlimeMouldMapBuilder {
    private final SlimeMouldMapConfig config;

    public SlimeMouldMapBuilder(SlimeMouldMapConfig config) {
        this.config = config;
    }

    public SlimeMouldMap build(MinecraftServer server) {
        MapTemplate template;
        try {
            template = MapTemplateSerializer.loadFromResource(server, this.config.template);
        } catch (IOException e) {
            throw new GameOpenException(Text.translatable("text.slime_mould.template_load_failed"), e);
        }

        MapTemplateMetadata metadata = template.getMetadata();

        SlimeMouldPlate plate = this.buildPlate(template, metadata);
        return new SlimeMouldMap(template, plate);
    }

    private SlimeMouldPlate buildPlate(MapTemplate template, MapTemplateMetadata metadata) {
        TemplateRegion plate = metadata.getFirstRegion("plate");
        if (plate == null) {
            throw new GameOpenException(Text.translatable("text.slime_mould.no_plate_region"));
        }

        int radius = this.computePlateRadius(template, plate.getBounds());
        return new SlimeMouldPlate(plate.getBounds(), radius);
    }

    private int computePlateRadius(MapTemplate template, BlockBounds plateBounds) {
        BlockPos plateCenter = BlockPos.ofFloored(plateBounds.center());
        BlockPos plateSize = plateBounds.size();
        int plateRadius = Math.min(plateSize.getX(), plateSize.getZ()) / 2;

        BlockPos.Mutable surfacePos = new BlockPos.Mutable();
        BlockPos.Mutable abovePos = new BlockPos.Mutable();

        for (int i = 0; i < 4; i++) {
            Direction direction = Direction.fromHorizontal(i);
            surfacePos.set(plateCenter);

            for (int x = 0; x <= plateRadius; x++) {
                BlockState surface = template.getBlockState(surfacePos);
                BlockState above = template.getBlockState(abovePos.set(surfacePos, Direction.UP));

                if (!SlimeMouldPlate.testSurface(surface.getBlock()).isOnPlate() || !above.isAir()) {
                    plateRadius = x;
                    break;
                }

                surfacePos.move(direction);
            }
        }

        return plateRadius;
    }
}
