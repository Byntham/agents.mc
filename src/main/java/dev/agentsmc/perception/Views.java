package dev.agentsmc.perception;

import dev.agentsmc.agent.AgentManager;
import dev.agentsmc.mcp.ToolException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Renders parts of the world as compact text for language models. Everything here must run on the
 * server thread.
 *
 * <p>Conventions shared by every view: absolute block coordinates; +x east, +z south, +y up; grids
 * print rows north to south (z ascending) and columns west to east (x ascending).
 */
public final class Views {
	public static final int MAX_BLOCKS_VOLUME = 32 * 32 * 32;
	public static final int MAX_SURFACE_RADIUS = 48;
	public static final int MAX_SEARCH_RADIUS = 64;

	private Views() {
	}

	// ---- 3D block grids -------------------------------------------------------------------------

	/**
	 * @param hints characters the agent wants used for blocks it knows (usually its build palette);
	 *              a block gets the most specific hint it matches, otherwise a generated character
	 */
	public static String blocks(ServerLevel level, BlockPos a, BlockPos b, BlockCodec.Detail detail, Map<Character, BlockCodec.Spec> hints) {
		int x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX());
		int y1 = Math.max(Math.min(a.getY(), b.getY()), level.getMinY());
		int y2 = Math.min(Math.max(a.getY(), b.getY()), level.getMaxY());
		int z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ());
		long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
		if (y1 > y2) throw new ToolException("Region is outside the world's build height (" + level.getMinY() + ".." + level.getMaxY() + ")");
		if (volume > MAX_BLOCKS_VOLUME) {
			throw new ToolException("Region has " + volume + " blocks; the limit is " + MAX_BLOCKS_VOLUME + " (e.g. 32x32x32). Split it up.");
		}

		Palette palette = new Palette();
		hints.forEach((c, spec) -> palette.fix(c, spec.text()));
		Map<BlockState, Character> chars = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		List<String> layers = new ArrayList<>();
		List<Boolean> empty = new ArrayList<>();
		int labelWidth = Math.max(label(z1).length(), label(z2).length());
		for (int y = y1; y <= y2; y++) {
			StringBuilder layer = new StringBuilder();
			boolean allAir = true;
			for (int z = z1; z <= z2; z++) {
				layer.append("  ").append(pad(label(z), labelWidth)).append("  ");
				for (int x = x1; x <= x2; x++) {
					char c = chars.computeIfAbsent(level.getBlockState(pos.set(x, y, z)), st -> symbol(st, detail, palette, hints));
					if (c != Palette.AIR) allAir = false;
					layer.append(c);
				}
				layer.append('\n');
			}
			layers.add(layer.toString());
			empty.add(allAir);
		}

		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "Blocks x %d..%d, y %d..%d, z %d..%d (%dx%dx%d) in %s.\n",
				x1, x2, y1, y2, z1, z2, x2 - x1 + 1, y2 - y1 + 1, z2 - z1 + 1, dimension(level)));
		sb.append("One grid per y level, bottom to top. Rows are z (north to south, labelled). Columns are x (west to east), starting at x=")
				.append(x1).append("; the ruler shows the last digit of x.\n");
		if (detail == BlockCodec.Detail.AUTO) sb.append("Block properties at their default value are omitted.\n");
		palette.appendLegend(sb);
		String ruler = " ".repeat(labelWidth + 4) + ruler(x1, x2) + "\n";

		for (int i = 0; i < layers.size(); ) {
			int j = i;
			while (j + 1 < layers.size() && layers.get(j + 1).equals(layers.get(i))) j++;
			String range = j == i ? "y=" + (y1 + i) : "y=" + (y1 + i) + ".." + (y1 + j);
			if (empty.get(i)) {
				sb.append(range).append(": all air\n");
			} else {
				sb.append(range).append(j == i ? ":\n" : " (identical layers):\n").append(ruler).append(layers.get(i));
			}
			i = j + 1;
		}
		return sb.toString();
	}

	private static char symbol(BlockState state, BlockCodec.Detail detail, Palette palette, Map<Character, BlockCodec.Spec> hints) {
		Character best = null;
		int bestSpecificity = -1;
		for (Map.Entry<Character, BlockCodec.Spec> h : hints.entrySet()) {
			BlockCodec.Spec spec = h.getValue();
			if (spec.matches(state) && spec.specified().size() > bestSpecificity) {
				best = h.getKey();
				bestSpecificity = spec.specified().size();
			}
		}
		return best != null ? best : palette.symbol(BlockCodec.id(state, detail));
	}

	// ---- top-down surface map -------------------------------------------------------------------

	public static String surface(ServerLevel level, int cx, int cz, int radius, BlockCodec.Detail detail, ServerPlayer self,
								 boolean heights, boolean includeLeaves) {
		if (radius < 1 || radius > MAX_SURFACE_RADIUS) throw new ToolException("radius must be 1.." + MAX_SURFACE_RADIUS);
		int x1 = cx - radius, x2 = cx + radius, z1 = cz - radius, z2 = cz + radius;
		int w = x2 - x1 + 1, h = z2 - z1 + 1;
		char[][] top = new char[h][w];
		int[][] height = new int[h][w];
		Palette palette = new Palette();
		Heightmap.Types heightmap = includeLeaves ? Heightmap.Types.MOTION_BLOCKING : Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = z1; z <= z2; z++) {
			for (int x = x1; x <= x2; x++) {
				int y = level.getHeight(heightmap, x, z) - 1;
				height[z - z1][x - x1] = y;
				top[z - z1][x - x1] = y < level.getMinY() ? Palette.AIR : palette.symbol(BlockCodec.id(level.getBlockState(pos.set(x, y, z)), detail));
			}
		}

		List<String> marks = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			int px = p.getBlockX(), pz = p.getBlockZ();
			if (px < x1 || px > x2 || pz < z1 || pz > z2) continue;
			boolean isSelf = p == self;
			top[pz - z1][px - x1] = isSelf ? Palette.SELF : Palette.OTHER;
			marks.add(String.format(Locale.ROOT, "  %c = %s%s at %d %d %d", isSelf ? Palette.SELF : Palette.OTHER,
					p.getName().getString(), isSelf ? " (you)" : AgentManager.isAgent(p) ? " (agent)" : " (human)", px, p.getBlockY(), pz));
		}

		int labelWidth = Math.max(label(z1).length(), label(z2).length());
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "Surface x %d..%d, z %d..%d in %s. Each column shows its topmost solid or liquid block. Grass, flowers and similar plants are skipped. %s\n",
				x1, x2, z1, z2, dimension(level), includeLeaves ? "Leaves count, so trees show their canopy." : "Leaves are skipped too, so you see the ground under trees."));
		sb.append("Rows are z (north to south, labelled). Columns are x (west to east), starting at x=").append(x1).append(".\n");
		palette.appendLegend(sb);
		if (!marks.isEmpty()) {
			sb.append("Players:\n");
			marks.forEach(m -> sb.append(m).append('\n'));
		}
		sb.append("Top blocks:\n").append(" ".repeat(labelWidth + 4)).append(ruler(x1, x2)).append('\n');
		for (int r = 0; r < h; r++) {
			sb.append("  ").append(pad(label(z1 + r), labelWidth)).append("  ").append(top[r]).append('\n');
		}
		if (heights) {
			sb.append("Heights (y of that top block), same layout; column headers are x:\n");
			sb.append("  ").append(" ".repeat(labelWidth)).append(" ");
			for (int x = x1; x <= x2; x++) sb.append(String.format(Locale.ROOT, "%4d", x));
			sb.append('\n');
			for (int r = 0; r < h; r++) {
				sb.append("  ").append(pad(label(z1 + r), labelWidth)).append(" ");
				for (int c = 0; c < w; c++) sb.append(String.format(Locale.ROOT, "%4d", height[r][c]));
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	// ---- entities -------------------------------------------------------------------------------

	public static String entities(ServerLevel level, Vec3 center, double radius, int limit, Entity exclude) {
		if (radius <= 0 || radius > MAX_SEARCH_RADIUS) throw new ToolException("radius must be 1.." + MAX_SEARCH_RADIUS);
		List<Entity> found = new ArrayList<>(level.getEntities(exclude, AABB.ofSize(center, radius * 2, radius * 2, radius * 2),
				e -> e.distanceToSqr(center) <= radius * radius));
		found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(center)));
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "%d entities within %.0f blocks of %.1f %.1f %.1f, nearest first%s:\n",
				found.size(), radius, center.x, center.y, center.z, found.size() > limit ? " (showing " + limit + ")" : ""));
		for (Entity e : found.subList(0, Math.min(limit, found.size()))) {
			sb.append("  ").append(describe(e)).append(String.format(Locale.ROOT, " (%.1f away)\n", Math.sqrt(e.distanceToSqr(center))));
		}
		return sb.toString();
	}

	public static String describe(Entity e) {
		StringBuilder sb = new StringBuilder();
		String type = BlockCodec.shortId(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
		if (e instanceof ServerPlayer p) {
			sb.append(AgentManager.isAgent(p) ? "agent " : "player ").append(p.getName().getString());
		} else if (e instanceof ItemEntity item) {
			sb.append("item ").append(stack(item.getItem()));
		} else {
			sb.append(type);
			if (e.hasCustomName()) sb.append(" \"").append(e.getCustomName().getString()).append('"');
		}
		sb.append(String.format(Locale.ROOT, " at %.1f %.1f %.1f", e.getX(), e.getY(), e.getZ()));
		if (e instanceof LivingEntity living) {
			sb.append(String.format(Locale.ROOT, ", health %.0f/%.0f", living.getHealth(), living.getMaxHealth()));
			if (!living.getMainHandItem().isEmpty()) sb.append(", holding ").append(stack(living.getMainHandItem()));
		}
		return sb.toString();
	}

	// ---- agent status ---------------------------------------------------------------------------

	public static String status(ServerPlayer agent) {
		ServerLevel level = agent.level();
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "%s in %s at %.2f %.2f %.2f (block %d %d %d)\n", agent.getName().getString(), dimension(level),
				agent.getX(), agent.getY(), agent.getZ(), agent.getBlockX(), agent.getBlockY(), agent.getBlockZ()));
		sb.append(String.format(Locale.ROOT, "Facing %s (yaw %.0f, pitch %.0f). Gamemode %s. Health %.0f/%.0f, food %d/20.\n",
				agent.getDirection().getName(), agent.getYRot() + 0f, agent.getXRot() + 0f, agent.gameMode().getName(),
				agent.getHealth(), agent.getMaxHealth(), agent.getFoodData().getFoodLevel()));
		sb.append(footing(agent)).append('\n');
		HitResult hit = agent.pick(20, 1f, false);
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos p = bhr.getBlockPos();
			sb.append(String.format(Locale.ROOT, "Looking at: %s at %d %d %d (%s face)\n",
					BlockCodec.id(level.getBlockState(p)), p.getX(), p.getY(), p.getZ(), bhr.getDirection().getName()));
		} else {
			sb.append("Looking at: nothing within 20 blocks\n");
		}
		long time = Math.floorMod(level.getDefaultClockTime(), 24000L);
		sb.append(String.format(Locale.ROOT, "Time of day: %d/24000 (%s). Weather: %s.\n", time, phase(time),
				level.isThundering() ? "thunderstorm" : level.isRaining() ? "rain" : "clear"));
		sb.append("Holding: ").append(agent.getMainHandItem().isEmpty() ? "nothing" : stack(agent.getMainHandItem())).append('\n');
		List<String> items = new ArrayList<>();
		var inv = agent.getInventory().getNonEquipmentItems();
		for (int i = 0; i < inv.size(); i++) {
			if (!inv.get(i).isEmpty()) items.add("[" + i + "] " + stack(inv.get(i)));
		}
		sb.append("Inventory: ").append(items.isEmpty() ? "empty" : String.join(", ", items)).append('\n');
		return sb.toString();
	}

	/**
	 * Where a player is right now, relative to {@code from} (may be null), and what they are pointing
	 * at. The pointed-at block lets a human say "build here" by looking at a spot.
	 */
	public static String player(ServerPlayer p, ServerPlayer from) {
		ServerLevel level = p.level();
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "%s (%s) in %s at %.1f %.1f %.1f (block %d %d %d), as of server tick %d.\n",
				p.getName().getString(), AgentManager.isAgent(p) ? "agent" : "human", dimension(level),
				p.getX(), p.getY(), p.getZ(), p.getBlockX(), p.getBlockY(), p.getBlockZ(), level.getServer().getTickCount()));
		if (from != null && from != p) {
			if (from.level() != level) {
				sb.append("They are in a different dimension from ").append(from.getName().getString()).append(".\n");
			} else {
				Vec3 d = p.position().subtract(from.position());
				sb.append(String.format(Locale.ROOT, "From %s: %.1f blocks away, %s (dx %+.0f, dy %+.0f, dz %+.0f).\n",
						from.getName().getString(), d.length(), compass(d.x, d.z), d.x, d.y, d.z));
			}
		}
		sb.append(String.format(Locale.ROOT, "Facing %s (yaw %.0f, pitch %.0f). Health %.0f/%.0f. Holding %s.\n",
				p.getDirection().getName(), p.getYRot() + 0f, p.getXRot() + 0f, p.getHealth(), p.getMaxHealth(),
				p.getMainHandItem().isEmpty() ? "nothing" : stack(p.getMainHandItem())));
		sb.append(footing(p)).append('\n');
		HitResult hit = p.pick(64, 1f, false);
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos b = bhr.getBlockPos();
			BlockPos free = b.relative(bhr.getDirection());
			sb.append(String.format(Locale.ROOT, "Looking at %s at %d %d %d (%s face, %.1f blocks away). A block placed against that face would go at %d %d %d.\n",
					BlockCodec.id(level.getBlockState(b)), b.getX(), b.getY(), b.getZ(), bhr.getDirection().getName(),
					Math.sqrt(p.getEyePosition().distanceToSqr(hit.getLocation())), free.getX(), free.getY(), free.getZ()));
		} else {
			sb.append("Looking at no block within 64 blocks.\n");
		}
		return sb.toString();
	}

	/** Compass direction of a horizontal offset. North is -z, east is +x. */
	private static String compass(double dx, double dz) {
		if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return "directly above or below";
		String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
		double yaw = Math.toDegrees(Math.atan2(-dx, dz)); // Minecraft yaw of the offset: 0 south, 90 west
		return names[(int) Math.floorMod(Math.round(yaw / 45.0), 8L)];
	}

	/**
	 * Whether the agent is standing, flying, swimming or falling, from collision geometry rather than
	 * the onGround flag, which only updates on the next physics tick (wrong right after a teleport).
	 */
	private static String footing(ServerPlayer agent) {
		ServerLevel level = agent.level();
		BlockPos below = BlockPos.containing(agent.getX(), agent.getY() - 0.01, agent.getZ());
		String flying = agent.getAbilities().flying ? "Flying. " : "";
		if (agent.isInWater()) return flying + "In water.";
		if (!level.noCollision(agent, agent.getBoundingBox().move(0, -0.01, 0))) {
			return flying + "Standing on " + BlockCodec.id(level.getBlockState(below)) + " at " + below.getX() + " " + below.getY() + " " + below.getZ() + ".";
		}
		BlockPos.MutableBlockPos p = below.mutable();
		for (int i = 0; i < 64 && p.getY() >= level.getMinY(); i++, p.move(0, -1, 0)) {
			BlockState s = level.getBlockState(p);
			var shape = s.getCollisionShape(level, p);
			if (!shape.isEmpty() || !s.getFluidState().isEmpty()) {
				// Measure from the block's real top: slabs, chests and the like are shorter than a full block.
				double top = p.getY() + (shape.isEmpty() ? 1 : shape.max(Direction.Axis.Y));
				return String.format(Locale.ROOT, "%sIn the air, %.1f blocks above %s at %d %d %d.", flying,
						agent.getY() - top, BlockCodec.id(s), p.getX(), p.getY(), p.getZ());
			}
		}
		return flying + "In the air, with no ground within 64 blocks below.";
	}

	// ---- search and inspect ---------------------------------------------------------------------

	public static String findBlocks(ServerLevel level, BlockPos center, int radius, String query, int limit) {
		if (radius < 1 || radius > MAX_SEARCH_RADIUS) throw new ToolException("radius must be 1.." + MAX_SEARCH_RADIUS);
		Predicate<BlockState> match = matcher(query);
		record Hit(BlockPos pos, BlockState state, double dist) {
		}
		List<Hit> hits = new ArrayList<>();
		int yMin = Math.max(level.getMinY(), center.getY() - radius), yMax = Math.min(level.getMaxY(), center.getY() + radius);
		for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
			for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				for (int sy = yMin >> 4; sy <= yMax >> 4; sy++) {
					LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
					if (section.hasOnlyAir() || !section.maybeHas(match)) continue;
					for (int ly = 0; ly < 16; ly++) {
						int y = (sy << 4) + ly;
						if (y < yMin || y > yMax) continue;
						for (int lz = 0; lz < 16; lz++) {
							for (int lx = 0; lx < 16; lx++) {
								BlockState s = section.getBlockState(lx, ly, lz);
								if (!match.test(s)) continue;
								BlockPos p = new BlockPos((cx << 4) + lx, y, (cz << 4) + lz);
								double d = Math.sqrt(p.distSqr(center));
								if (d <= radius) hits.add(new Hit(p, s, d));
							}
						}
					}
				}
			}
		}
		hits.sort(Comparator.comparingDouble(Hit::dist));
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "Found %d blocks matching '%s' within %d of %d %d %d, nearest first%s:\n", hits.size(), query, radius,
				center.getX(), center.getY(), center.getZ(), hits.size() > limit ? " (showing " + limit + ")" : ""));
		for (Hit hit : hits.subList(0, Math.min(limit, hits.size()))) {
			sb.append(String.format(Locale.ROOT, "  %s at %d %d %d (%.1f away)\n", BlockCodec.id(hit.state()),
					hit.pos().getX(), hit.pos().getY(), hit.pos().getZ(), hit.dist()));
		}
		return sb.toString();
	}

	public static String inspect(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "%d %d %d: %s\n", pos.getX(), pos.getY(), pos.getZ(), BlockCodec.id(state, BlockCodec.Detail.FULL)));
		sb.append("Light level: ").append(level.getMaxLocalRawBrightness(pos.above())).append(" above this block\n");
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof Container container) {
			List<String> items = new ArrayList<>();
			for (int i = 0; i < container.getContainerSize(); i++) {
				if (!container.getItem(i).isEmpty()) items.add("[" + i + "] " + stack(container.getItem(i)));
			}
			sb.append("Container with ").append(container.getContainerSize()).append(" slots: ")
					.append(items.isEmpty() ? "empty" : String.join(", ", items)).append('\n');
		} else if (be != null) {
			String nbt = be.saveWithoutMetadata(level.registryAccess()).toString();
			sb.append("Block entity data: ").append(nbt.length() > 2000 ? nbt.substring(0, 2000) + "..." : nbt).append('\n');
		}
		return sb.toString();
	}

	// ---- helpers --------------------------------------------------------------------------------

	private static Predicate<BlockState> matcher(String query) {
		String q = query.trim();
		if (q.startsWith("#")) {
			Identifier id = Identifier.tryParse(q.substring(1));
			if (id == null) throw new ToolException("Invalid tag " + q);
			TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
			return s -> s.is(tag);
		}
		BlockState parsed = BlockCodec.parse(q);
		if (q.contains("[")) return s -> s == parsed;
		Block block = parsed.getBlock();
		return s -> s.is(block);
	}

	public static String stack(ItemStack stack) {
		String id = BlockCodec.shortId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		return (stack.getCount() > 1 ? stack.getCount() + "x " : "") + id;
	}

	public static String dimension(ServerLevel level) {
		return BlockCodec.shortId(level.dimension().identifier().toString());
	}

	private static String phase(long t) {
		if (t < 1000) return "sunrise";
		if (t < 6000) return "morning";
		if (t < 9000) return "midday";
		if (t < 12000) return "afternoon";
		if (t < 13000) return "sunset";
		if (t < 23000) return "night";
		return "dawn";
	}

	private static String label(int z) {
		return "z=" + z;
	}

	private static String pad(String s, int width) {
		return " ".repeat(Math.max(0, width - s.length())) + s;
	}

	private static String ruler(int x1, int x2) {
		StringBuilder sb = new StringBuilder();
		for (int x = x1; x <= x2; x++) sb.append(Math.floorMod(x, 10));
		return sb.toString();
	}
}
