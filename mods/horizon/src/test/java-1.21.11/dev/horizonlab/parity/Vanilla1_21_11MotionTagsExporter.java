package dev.horizonlab.parity;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minecraft 1.21.11 decides what blocks motion in a heightmap with {@code BlockState.blocksMotion()}, where 26.3
 * reads the block tags {@code minecraft:blocks_motion_in_heightmap} and {@code ..._no_leaves}. This writes
 * those two tags for 1.21.11 from the game's own method: a block is in the first when every state blocks motion,
 * and in the second when it is also not leaves. A block whose states differ cannot be one tag entry; those are
 * listed under {@code mixed} so the difference is visible instead of being rounded.
 */
public final class Vanilla1_21_11MotionTagsExporter {
    private Vanilla1_21_11MotionTagsExporter() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<String> motion = new ArrayList<>();
        List<String> noLeaves = new ArrayList<>();
        List<String> mixed = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            boolean any = false;
            boolean all = true;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                boolean blocks = state.blocksMotion();
                any |= blocks;
                all &= blocks;
            }
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (all) {
                motion.add(id);
                if (!(block instanceof LeavesBlock)) {
                    noLeaves.add(id);
                }
            } else if (any) {
                mixed.add(id);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blocks_motion_in_heightmap", motion);
        out.put("blocks_motion_in_heightmap_no_leaves", noLeaves);
        out.put("mixed", mixed);
        Files.writeString(Path.of(args[0]), new Gson().toJson(out), StandardCharsets.UTF_8);
    }
}
