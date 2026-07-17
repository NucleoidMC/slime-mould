package xyz.nucleoid.slime_mould.game.map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

public final class SlimeMouldMapConfig {
    public static final Codec<SlimeMouldMapConfig> CODEC = RecordCodecBuilder.create(instance -> {
        return instance.group(
                Identifier.CODEC.fieldOf("template").forGetter(c -> c.template)
        ).apply(instance, SlimeMouldMapConfig::new);
    });

    public final Identifier template;

    public SlimeMouldMapConfig(Identifier template) {
        this.template = template;
    }
}
