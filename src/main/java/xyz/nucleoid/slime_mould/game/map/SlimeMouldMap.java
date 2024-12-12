package xyz.nucleoid.slime_mould.game.map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.plasmid.api.game.world.generator.TemplateChunkGenerator;

public final class SlimeMouldMap {
    private final MapTemplate template;
    private final SlimeMouldPlate plate;

    public SlimeMouldMap(MapTemplate template, SlimeMouldPlate plate) {
        this.template = template;
        this.plate = plate;
    }

    public SlimeMouldPlate getPlate() {
        return this.plate;
    }

    public Vec3d getWaitingSpawn() {
        Vec3d center = this.plate.bounds.center();
        return center.add(0.0, 1.0, 0.0);
    }

    public ChunkGenerator asGenerator(MinecraftServer server) {
        return new TemplateChunkGenerator(server, this.template);
    }
}
