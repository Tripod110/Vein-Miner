package com.aj.veinminer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VeinMiner implements ModInitializer {
	public static final String MOD_ID = "veinminer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int MAX_BLOCKS = 64;

	// data/veinminer/tags/blocks/vein_mineable.json
	private static final TagKey<Block> VEIN_MINEABLE =
			TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, "vein_mineable"));

	// Prevent recursion when we break additional blocks (event re-triggers)
	private static final Set<UUID> ACTIVE = Collections.newSetFromMap(new ConcurrentHashMap<>());

	@Override
	public void onInitialize() {
		LOGGER.info("VeinMiner initialized.");

		PlayerBlockBreakEvents.AFTER.register((world, player, originPos, originState, blockEntity) -> {
			if (!(world instanceof ServerLevel level)) return;
			if (!(player instanceof ServerPlayer sp)) return;

			boolean shift = sp.isShiftKeyDown();
			boolean inTag = originState.is(VEIN_MINEABLE);
			boolean fallback = isFallbackMineable(originState);

			// DEBUG: keep this until everything works reliably
			LOGGER.info("BREAK event: block={} shift={} inTag={} fallback={}",
					BuiltInRegistries.BLOCK.getKey(originState.getBlock()),
					shift,
					inTag,
					fallback
			);

			// Shift-to-activate
			if (!shift) return;

			// Allow either: tag match OR fallback match
			if (!inTag && !fallback) return;

			UUID id = sp.getUUID();
			if (!ACTIVE.add(id)) return;

			try {
				int broke = veinMine(level, sp, originPos, originState);
				LOGGER.info("VeinMiner: broke {} extra blocks", broke);
			} finally {
				ACTIVE.remove(id);
			}
		});
	}

	private static int veinMine(ServerLevel level, ServerPlayer player, BlockPos origin, BlockState originState) {
		Block target = originState.getBlock();

		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		HashSet<BlockPos> visited = new HashSet<>();

		queue.add(origin);
		visited.add(origin);

		int broken = 0;

		while (!queue.isEmpty() && broken < MAX_BLOCKS) {
			BlockPos pos = queue.removeFirst();

			// Origin is already broken at AFTER time; use captured originState there
			BlockState state = pos.equals(origin) ? originState : level.getBlockState(pos);
			if (state.getBlock() != target) continue;

			// Break everything except origin (already broken)
			if (!pos.equals(origin)) {
				if (player.gameMode.destroyBlock(pos)) {
					broken++;
				}
			}

			// ALWAYS explore neighbors
			for (Direction dir : Direction.values()) {
				BlockPos next = pos.relative(dir);
				if (!visited.add(next)) continue;

				if (level.getBlockState(next).getBlock() == target) {
					queue.addLast(next);
				}
			}
		}

		return broken;
	}

	// Fallback so the mod works even if the custom tag JSON isn't being loaded for some reason.
	private static boolean isFallbackMineable(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (id == null) return false;

		String path = id.getPath();

		// logs
		if (path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_hyphae")) return true;

		// ores
		if (path.endsWith("_ore")) return true;

		// special case
		return path.equals("ancient_debris");
	}
}
