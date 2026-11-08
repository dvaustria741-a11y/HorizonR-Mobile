package dev.horizonlab.parity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.data.registries.VanillaRegistries;

/**
 * Minecraft 1.21.11's Overworld terrain height, from the game itself: {@code getBaseHeight} for
 * {@code WORLD_SURFACE_WG} (the first height with no block above it, fluids counting) at columns on a grid.
 *
 * <pre>Vanilla1_21_11HeightOracle SEED X0 Z0 X1 Z1 STEP OUT</pre>
 *
 * writes one {@code x z height} line per column from (X0, Z0) to (X1, Z1) every STEP blocks.
 */
public final class Vanilla1_21_11HeightOracle {
    private Vanilla1_21_11HeightOracle() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        long seed = Long.parseLong(args[0]);
        int x0 = Integer.parseInt(args[1]);
        int z0 = Integer.parseInt(args[2]);
        int x1 = Integer.parseInt(args[3]);
        int z1 = Integer.parseInt(args[4]);
        int step = Integer.parseInt(args[5]);
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState state = RandomState.create(lookup, NoiseGeneratorSettings.OVERWORLD, seed);
        Holder<net.minecraft.world.level.biome.Biome> plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(plains), Holder.direct(settings));
        LevelHeightAccessor level = LevelHeightAccessor.create(-64, 384);
        StringBuilder out = new StringBuilder();
        out.append("seed ").append(seed).append('\n');
        for (int z = z0; z <= z1; z += step) {
            for (int x = x0; x <= x1; x += step) {
                out.append(x).append(' ').append(z).append(' ')
                        .append(generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, state)).append('\n');
            }
        }
        Files.writeString(Path.of(args[6]), out.toString(), StandardCharsets.UTF_8);
    }
}
