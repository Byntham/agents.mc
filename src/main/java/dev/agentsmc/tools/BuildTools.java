package dev.agentsmc.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.agent.AgentState;
import dev.agentsmc.mcp.Schema;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.perception.BlockCodec;
import dev.agentsmc.util.OnServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tools that change blocks. Edits are applied the way a builder would expect: blocks are placed
 * first, then fences, panes, stairs, walls etc. reconnect to their neighbours, then neighbours get
 * a normal block update (so sand falls, water flows, redstone reacts). Every edit can be undone.
 */
public final class BuildTools {
	// Skipping block-entity side effects stops a replaced chest from spilling its items. Undo puts them back instead.
	private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	private BuildTools() {
	}

	public static void register(Session s) {
		s.tools.register("set_blocks", "Place specific blocks at specific positions. Use \"air\" to remove a block.",
				Schema.of().agent(true).raw("blocks", blockListSchema(), true),
				args -> {
					JsonArray list = args.array("blocks");
					if (list.size() > s.config.maxBuildBlocks) throw new ToolException("That is " + list.size() + " blocks; the limit is " + s.config.maxBuildBlocks);
					Map<BlockPos, BlockCodec.Spec> changes = new LinkedHashMap<>();
					Map<String, BlockCodec.Spec> cache = new HashMap<>();
					for (JsonElement el : list) {
						JsonObject o = el.getAsJsonObject();
						String block = o.get("block").getAsString();
						changes.put(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()),
								cache.computeIfAbsent(block, BlockCodec::parseSpec));
					}
					return OnServer.call(s.server, () -> apply(s, s.agents.get(args.str("agent")), "set " + changes.size() + " blocks", changes));
				});

		s.tools.register("fill",
				"Fill a box between two corners with one block. mode: solid (default), hollow (shell with air inside), "
						+ "outline (shell only, inside untouched). Optional 'replace' limits the fill to blocks of that type (e.g. \"air\" to only fill empty space).",
				Schema.of().agent(true)
						.integer("x1", "Corner 1 x", true).integer("y1", "Corner 1 y", true).integer("z1", "Corner 1 z", true)
						.integer("x2", "Corner 2 x", true).integer("y2", "Corner 2 y", true).integer("z2", "Corner 2 z", true)
						.str("block", "Block to fill with, e.g. \"stone_bricks\" or \"oak_log[axis=x]\"", true)
						.enumStr("mode", "Fill shape", false, "solid", "hollow", "outline")
						.str("replace", "Only replace blocks of this id or #tag (optional)", false),
				args -> {
					BlockCodec.Spec fill = BlockCodec.parseSpec(args.str("block"));
					BlockCodec.Spec air = BlockCodec.parseSpec("air");
					String mode = args.str("mode", "solid");
					Predicate<BlockState> replace = args.has("replace") ? replaceFilter(args.str("replace")) : st -> true;
					int x1 = Math.min(args.integer("x1"), args.integer("x2")), x2 = Math.max(args.integer("x1"), args.integer("x2"));
					int y1 = Math.min(args.integer("y1"), args.integer("y2")), y2 = Math.max(args.integer("y1"), args.integer("y2"));
					int z1 = Math.min(args.integer("z1"), args.integer("z2")), z2 = Math.max(args.integer("z1"), args.integer("z2"));
					long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
					if (volume > s.config.maxBuildBlocks) throw new ToolException("Box has " + volume + " blocks; limit is " + s.config.maxBuildBlocks);
					return OnServer.call(s.server, () -> {
						AgentPlayer a = s.agents.get(args.str("agent"));
						ServerLevel level = a.level();
						Map<BlockPos, BlockCodec.Spec> changes = new LinkedHashMap<>();
						for (int y = y1; y <= y2; y++) {
							for (int z = z1; z <= z2; z++) {
								for (int x = x1; x <= x2; x++) {
									boolean edge = x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2;
									BlockCodec.Spec target = edge || mode.equals("solid") ? fill : mode.equals("hollow") ? air : null;
									BlockPos pos = new BlockPos(x, y, z);
									if (target != null && replace.test(level.getBlockState(pos))) changes.put(pos, target);
								}
							}
						}
						return apply(s, a, "fill " + mode + " " + fill.text(), changes);
					});
				});

		s.tools.register("build_layers",
				"Build a structure from layered character grids, the same format get_blocks returns. "
						+ "origin (x,y,z) is the north-west-bottom corner. layers[0] is the bottom layer at y; each layer is a list of rows; "
						+ "row 0 is the north edge (z), each next row is one block further south (+z); character 0 of a row is at x, then +x eastward. "
						+ "palette maps single characters to blocks. A space means 'leave unchanged' and '.' always means air. "
						+ "Use block states for orientation, e.g. \"oak_stairs[facing=east]\", \"oak_door[facing=south,half=lower]\".",
				Schema.of().agent(true).xyz("Origin (north-west-bottom corner)", true)
						.raw("palette", paletteSchema("Map of single character to block, e.g. {\"#\": \"cobblestone\", \"p\": \"oak_planks\"}"), true)
						.raw("layers", layersSchema(), true),
				args -> {
					Map<Character, BlockCodec.Spec> palette = new HashMap<>();
					palette.put('.', BlockCodec.parseSpec("air"));
					for (Map.Entry<String, JsonElement> e : args.object("palette").entrySet()) {
						if (e.getKey().length() != 1) throw new ToolException("Palette keys must be single characters, got '" + e.getKey() + "'");
						if (e.getKey().equals(" ")) throw new ToolException("Space is reserved for 'leave unchanged'");
						if (e.getKey().equals(".")) {
							if (!BlockCodec.parse(e.getValue().getAsString()).isAir()) throw new ToolException("'.' is reserved for air");
							continue;
						}
						palette.put(e.getKey().charAt(0), BlockCodec.parseSpec(e.getValue().getAsString()));
					}
					int ox = args.integer("x"), oy = args.integer("y"), oz = args.integer("z");
					Map<BlockPos, BlockCodec.Spec> changes = new LinkedHashMap<>();
					JsonArray layers = args.array("layers");
					for (int ly = 0; ly < layers.size(); ly++) {
						JsonArray rows = layers.get(ly).getAsJsonArray();
						for (int r = 0; r < rows.size(); r++) {
							String row = rows.get(r).getAsString();
							for (int c = 0; c < row.length(); c++) {
								char ch = row.charAt(c);
								if (ch == ' ') continue;
								BlockCodec.Spec st = palette.get(ch);
								if (st == null) throw new ToolException("Character '" + ch + "' (layer " + ly + ", row " + r + ", column " + c + ") is not in the palette");
								changes.put(new BlockPos(ox + c, oy + ly, oz + r), st);
							}
						}
					}
					if (changes.size() > s.config.maxBuildBlocks) throw new ToolException("Structure has " + changes.size() + " blocks; limit is " + s.config.maxBuildBlocks);
					return OnServer.call(s.server, () -> apply(s, s.agents.get(args.str("agent")), "build_layers", changes));
				});

		s.tools.register("undo", "Revert the agent's most recent build (set_blocks, fill or build_layers). Can be called repeatedly, up to 32 edits back.",
				Schema.of().agent(true),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					AgentState.Edit edit = s.agents.state(args.str("agent")).popEdit();
					if (edit == null) return "Nothing to undo.";
					// Undo where the build happened, even if the agent has since moved to another dimension.
					ServerLevel level = s.server.getLevel(edit.dimension());
					if (level == null) return "Can't undo: the dimension it was built in no longer exists.";
					Map<BlockPos, BlockCodec.Spec> restore = new LinkedHashMap<>();
					edit.before().forEach((pos, state) -> restore.put(pos, BlockCodec.exact(state)));
					return applyChanges(s, a, level, "undo " + edit.description(), restore, edit.blockEntities());
				}));
	}

	private static String apply(Session s, AgentPlayer agent, String description, Map<BlockPos, BlockCodec.Spec> changes) {
		if (changes.size() > s.config.maxBuildBlocks) throw new ToolException("That is " + changes.size() + " blocks; the limit is " + s.config.maxBuildBlocks);
		return applyChanges(s, agent, agent.level(), description, changes, null);
	}

	/**
	 * Applies the changes and reports what happened. For a build (not an undo) it also checks every
	 * requested position afterwards and lists the ones that didn't end up as asked, so the agent
	 * doesn't need to read the build back.
	 *
	 * @param restoreEntities for an undo, the block-entity data to put back; null for a build
	 */
	private static String applyChanges(Session s, AgentPlayer agent, ServerLevel level, String description,
									   Map<BlockPos, BlockCodec.Spec> changes, Map<BlockPos, CompoundTag> restoreEntities) {
		boolean isBuild = restoreEntities == null;
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Map<BlockPos, CompoundTag> entities = new HashMap<>();
		int outside = 0, unchanged = 0;
		for (Map.Entry<BlockPos, BlockCodec.Spec> e : changes.entrySet()) {
			BlockPos pos = e.getKey();
			BlockState target = e.getValue().state();
			if (level.isOutsideBuildHeight(pos)) {
				outside++;
				continue;
			}
			BlockState old = level.getBlockState(pos);
			if (old == target) {
				unchanged++;
				continue;
			}
			before.put(pos, old);
			BlockEntity be = level.getBlockEntity(pos);
			if (be != null) entities.put(pos, be.saveWithFullMetadata(level.registryAccess()));
			level.setBlock(pos, target, SET_FLAGS);
		}
		int placed = before.size();

		if (!before.isEmpty()) {
			// Reconnect changed blocks and the blocks touching them (fences, panes, stairs, walls, redstone dust...).
			Set<BlockPos> touched = new LinkedHashSet<>(before.keySet());
			for (BlockPos pos : before.keySet()) for (Direction d : Direction.values()) touched.add(pos.relative(d));
			for (BlockPos pos : touched) {
				BlockState current = level.getBlockState(pos);
				BlockState updated = Block.updateFromNeighbourShapes(current, level, pos);
				if (updated != current) {
					before.putIfAbsent(pos, current);
					level.setBlock(pos, updated, SET_FLAGS);
				}
			}
			for (BlockPos pos : before.keySet()) level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
		}

		if (!isBuild) restoreBlockEntities(level, restoreEntities);
		if (isBuild && !before.isEmpty()) {
			s.agents.state(agent.getName().getString()).pushEdit(new AgentState.Edit(description, level.dimension(), before, entities));
		}

		StringBuilder sb = new StringBuilder();
		sb.append(description).append(": changed ").append(placed).append(" blocks");
		if (before.size() > placed) sb.append(" (+").append(before.size() - placed).append(" neighbours reshaped to connect)");
		if (unchanged > 0) sb.append(", ").append(unchanged).append(" positions already had the requested block (often air)");
		if (outside > 0) sb.append(", ").append(outside).append(" skipped (outside build height ").append(level.getMinY()).append("..").append(level.getMaxY()).append(")");
		if (!before.isEmpty()) {
			int[] box = bounds(before.keySet());
			sb.append(String.format(" within x %d..%d, y %d..%d, z %d..%d", box[0], box[3], box[1], box[4], box[2], box[5]));
			s.events.add("build", agent.getName().getString(), agent.getName().getString() + " " + description + " (" + before.size() + " blocks) around "
					+ (box[0] + box[3]) / 2 + " " + (box[1] + box[4]) / 2 + " " + (box[2] + box[5]) / 2);
		}
		sb.append(".");
		if (isBuild) appendMismatches(sb, level, changes);
		if (isBuild && !before.isEmpty()) sb.append(sb.charAt(sb.length() - 1) == '\n' ? "Call undo to revert." : " Call undo to revert.");
		return sb.toString();
	}

	private static void restoreBlockEntities(ServerLevel level, Map<BlockPos, CompoundTag> saved) {
		saved.forEach((pos, tag) -> {
			BlockEntity be = level.getBlockEntity(pos);
			if (be == null) return;
			try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(AgentsMc.LOGGER)) {
				be.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), tag));
			}
			be.setChanged();
			BlockState state = level.getBlockState(pos);
			level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
		});
	}

	private static void appendMismatches(StringBuilder sb, ServerLevel level, Map<BlockPos, BlockCodec.Spec> changes) {
		int count = 0;
		StringBuilder list = new StringBuilder();
		for (Map.Entry<BlockPos, BlockCodec.Spec> e : changes.entrySet()) {
			BlockPos pos = e.getKey();
			if (level.isOutsideBuildHeight(pos)) continue;
			BlockState actual = level.getBlockState(pos);
			if (e.getValue().matches(actual)) continue;
			if (++count <= 20) {
				list.append(String.format(Locale.ROOT, "\n  %d %d %d: wanted %s, got %s", pos.getX(), pos.getY(), pos.getZ(), e.getValue().text(), BlockCodec.id(actual)));
				if (actual.isAir() && !e.getValue().state().isAir()) list.append(" (it probably needs a block to attach to or stand on)");
			}
		}
		if (count == 0) {
			sb.append(" Verified: every position has the requested block.");
		} else {
			sb.append(" ").append(count).append(count == 1 ? " position does" : " positions do").append(" not match the request:").append(list);
			if (count > 20) sb.append("\n  ...and ").append(count - 20).append(" more");
			sb.append('\n');
		}
	}

	private static int[] bounds(Set<BlockPos> positions) {
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (BlockPos p : positions) {
			b[0] = Math.min(b[0], p.getX());
			b[1] = Math.min(b[1], p.getY());
			b[2] = Math.min(b[2], p.getZ());
			b[3] = Math.max(b[3], p.getX());
			b[4] = Math.max(b[4], p.getY());
			b[5] = Math.max(b[5], p.getZ());
		}
		return b;
	}

	private static Predicate<BlockState> replaceFilter(String query) {
		if (query.startsWith("#")) {
			Identifier id = Identifier.tryParse(query.substring(1));
			if (id == null) throw new ToolException("Invalid tag " + query);
			TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
			return st -> st.is(tag);
		}
		Block block = BlockCodec.parse(query).getBlock();
		if (block == Blocks.AIR) return BlockState::isAir;
		return st -> st.is(block);
	}

	private static JsonObject blockListSchema() {
		JsonObject item = new JsonObject();
		item.addProperty("type", "object");
		JsonObject props = new JsonObject();
		for (String c : new String[]{"x", "y", "z"}) {
			JsonObject p = new JsonObject();
			p.addProperty("type", "integer");
			props.add(c, p);
		}
		JsonObject block = new JsonObject();
		block.addProperty("type", "string");
		block.addProperty("description", "Block id or state, e.g. \"stone\", \"oak_stairs[facing=north]\", \"air\"");
		props.add("block", block);
		item.add("properties", props);
		JsonArray req = new JsonArray();
		for (String r : new String[]{"x", "y", "z", "block"}) req.add(r);
		item.add("required", req);
		JsonObject arr = new JsonObject();
		arr.addProperty("type", "array");
		arr.addProperty("description", "Blocks to place");
		arr.add("items", item);
		return arr;
	}

	static JsonObject paletteSchema(String description) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.addProperty("description", description);
		JsonObject v = new JsonObject();
		v.addProperty("type", "string");
		o.add("additionalProperties", v);
		return o;
	}

	private static JsonObject layersSchema() {
		JsonObject row = new JsonObject();
		row.addProperty("type", "string");
		JsonObject layer = new JsonObject();
		layer.addProperty("type", "array");
		layer.add("items", row);
		JsonObject o = new JsonObject();
		o.addProperty("type", "array");
		o.addProperty("description", "Bottom-to-top list of layers; each layer is a list of row strings (north to south)");
		o.add("items", layer);
		return o;
	}
}
