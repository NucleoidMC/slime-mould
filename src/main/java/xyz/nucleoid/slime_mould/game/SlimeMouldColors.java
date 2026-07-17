package xyz.nucleoid.slime_mould.game;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.util.Util;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;

public final class SlimeMouldColors {
    public static final DyeColor[] COLORS = Arrays.stream(DyeColor.values())
            .filter(color -> color != DyeColor.WHITE && color != DyeColor.BLACK)
            .toArray(DyeColor[]::new);

    public static List<DyeColor> shuffledColors(RandomSource random) {
        ObjectArrayList<DyeColor> colors = new ObjectArrayList<>(COLORS);
        Util.shuffle(colors, random);
        return colors;
    }
}
