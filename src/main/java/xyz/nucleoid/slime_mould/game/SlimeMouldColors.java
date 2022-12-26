package xyz.nucleoid.slime_mould.game;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Util;
import net.minecraft.util.math.random.Random;

import java.util.Arrays;
import java.util.List;

public final class SlimeMouldColors {
    public static final DyeColor[] COLORS = Arrays.stream(DyeColor.values())
            .filter(color -> color != DyeColor.WHITE && color != DyeColor.BLACK)
            .toArray(DyeColor[]::new);

    public static List<DyeColor> shuffledColors(Random random) {
        ObjectArrayList<DyeColor> colors = new ObjectArrayList<>(COLORS);
        Util.shuffle(colors, random);
        return colors;
    }
}
