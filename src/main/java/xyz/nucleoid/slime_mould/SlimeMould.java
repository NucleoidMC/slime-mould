package xyz.nucleoid.slime_mould;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import xyz.nucleoid.plasmid.game.GameType;
import xyz.nucleoid.slime_mould.game.SlimeMouldConfig;
import xyz.nucleoid.slime_mould.game.SlimeMouldWaiting;

public final class SlimeMould implements ModInitializer {
    public static final String ID = "slime_mould";

    @Override
    public void onInitialize() {
        GameType.register(
                new Identifier(SlimeMould.ID, "slime_mould"),
                SlimeMouldConfig.CODEC,
                SlimeMouldWaiting::open
        );
    }
}
