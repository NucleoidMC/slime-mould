package xyz.nucleoid.slime_mould;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import xyz.nucleoid.plasmid.api.game.GameType;
import xyz.nucleoid.slime_mould.game.SlimeMouldConfig;
import xyz.nucleoid.slime_mould.game.SlimeMouldWaiting;

public final class SlimeMould implements ModInitializer {
    private static final String ID = "slime_mould";

    @Override
    public void onInitialize() {
        GameType.register(
                SlimeMould.identifier("slime_mould"),
                SlimeMouldConfig.CODEC,
                SlimeMouldWaiting::open
        );
    }

    public static Identifier identifier(String path) {
        return Identifier.of(SlimeMould.ID, path);
    }
}
