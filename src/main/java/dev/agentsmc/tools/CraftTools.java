package dev.agentsmc.tools;

import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.body.Body;
import dev.agentsmc.body.Containers;
import dev.agentsmc.body.Crafting;
import dev.agentsmc.body.Recipes;
import dev.agentsmc.mcp.Schema;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.perception.Senses;
import dev.agentsmc.util.OnServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Crafting, smelting and containers, the way a survival player does them: recipes from the server's
 * recipe manager, crafting by hand or at a table in reach, furnaces and chests opened and worked
 * through the server's own menu code.
 */
public final class CraftTools {
	private static final int MAX_LINES = 8;

	private CraftTools() {
	}

	public static void register(Session s) {
		s.tools.register("recipe",
				"What an item is made from, from the server's recipes: ingredients with counts (#planks means any planks), how many one craft "
						+ "makes, and whether it fits the 2x2 inventory grid or needs a crafting table; furnace recipes say what to smelt.",
				Schema.of().agent(false).str("item", "Item id, e.g. wooden_pickaxe, torch, iron_ingot", true),
				args -> OnServer.call(s.server, () -> {
					ServerLevel level = s.server.overworld();
					String query = args.str("item");
					List<Item> items = Recipes.resolve(query);
					if (items.isEmpty()) throw new ToolException(unknown(query));
					if (items.size() > 1) return query + " is a group: " + names(items, 12) + ". Ask for one of those.";
					Item item = items.getFirst();
					List<Recipes.Way> ways = Recipes.ways(level, item);
					if (ways.isEmpty()) {
						String how = Recipes.obtained(item);
						return "No recipe makes " + Recipes.name(item) + (how != null ? "; it's " + how : "") + ".";
					}
					StringBuilder sb = new StringBuilder();
					for (int i = 0; i < Math.min(MAX_LINES, ways.size()); i++) sb.append(Recipes.describe(ways.get(i))).append('\n');
					if (ways.size() > MAX_LINES) sb.append("(+").append(ways.size() - MAX_LINES).append(" more)\n");
					return sb.toString().strip();
				}));

		s.tools.register("plan_craft",
				"Work backwards from an item to your inventory: which recipes to use, what raw materials are missing and how to get them, "
						+ "and the steps in order (gather, craft, place a table or furnace, smelt). Call this before a crafting job.",
				Schema.of().agent(true).str("item", "Item id, e.g. wooden_pickaxe", true).integer("count", "How many (default 1)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Item item = one(args.str("item"));
					int count = Math.max(1, Math.min(256, args.integer("count", 1)));
					ServerLevel level = a.level();
					boolean table = Containers.nearest(a.body(), (p, st) -> st.is(Blocks.CRAFTING_TABLE)) != null;
					boolean furnace = Containers.nearest(a.body(), (p, st) -> level.getBlockEntity(p) instanceof FurnaceBlockEntity) != null;
					Recipes.Plan plan = Recipes.plan(level, item, count, Containers.counts(a), table, furnace);
					StringBuilder sb = new StringBuilder(plan.headline()).append('\n');
					if (!plan.used().isEmpty()) sb.append("Uses from your inventory: ").append(list(plan.used())).append(".\n");
					sb.append(plan.missing().isEmpty() ? "Nothing missing." : "Missing: " + list(plan.missing()) + ".").append('\n');
					if (!plan.steps().isEmpty()) {
						List<String> steps = new ArrayList<>();
						for (int i = 0; i < plan.steps().size(); i++) steps.add((i + 1) + ". " + plan.steps().get(i));
						sb.append("Steps: ").append(String.join("; ", steps)).append('.');
					}
					return sb.toString().strip();
				}));

		s.tools.register("craft",
				"Craft an item now from the inventory. Uses the 2x2 inventory grid when the recipe fits, otherwise a crafting table within reach; "
						+ "if none is in reach but you carry one, it's placed beside you first. Returns what was crafted and the inventory change.",
				Schema.of().agent(true).str("item", "Item id, e.g. oak_planks, stick, crafting_table, wooden_pickaxe. A group like planks picks one you can make.", true)
						.integer("count", "How many items you want (default: one craft). 8 oak_planks means two crafts of 4.", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Item item = craftable(a, args.str("item"));
					int count = Math.max(1, Math.min(256, args.integer("count", 1)));
					return Crafting.craft(a.body(), item, count);
				}));

		s.tools.register("smelt",
				"Cook or smelt items in a furnace, smoker or blast furnace within reach: puts the items and enough fuel from the inventory into it "
						+ "(coal or charcoal first, then wood) and returns at once with the expected time. If none is in reach but you carry one, it's placed "
						+ "beside you. When it's done, take the output with container action=take.",
				Schema.of().agent(true).str("item", "What to cook, e.g. chicken, raw_iron, cobblestone", true)
						.integer("count", "How many (default: all you have, up to a stack)", false)
						.str("fuel", "Fuel to use, e.g. coal or planks (default: chosen from the inventory)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Item item = one(args.str("item"));
					return Crafting.smelt(a.body(), item, args.integer("count", 0), args.str("fuel", ""));
				}));

		s.tools.register("container",
				"Look into, take from or put into a chest, barrel, furnace, hopper, dispenser or shulker box within reach. The block is opened like a "
						+ "right-click, items move with the same clicks a player uses, and it's closed again. Without x, y, z it uses the nearest one in reach.",
				Schema.of().agent(true).enumStr("action", "look, take or put", true, "look", "take", "put")
						.str("item", "Item id or part of one. take with no item takes everything (from a furnace: the output)", false)
						.integer("count", "How many (default: all matching)", false)
						.xyz("Container", false),
				args -> OnServer.call(s.server, () -> container(s, args.str("agent"), args.str("action"), args.str("item", ""), args.integer("count", 0),
						args.has("x") && args.has("y") && args.has("z") ? new BlockPos(args.integer("x"), args.integer("y"), args.integer("z")) : null)));
	}

	// ---- container ------------------------------------------------------------------------------

	private static String container(Session s, String agent, String action, String query, int count, BlockPos at) {
		AgentPlayer a = s.agents.get(agent);
		Body body = a.body();
		ServerLevel level = a.level();
		BlockPos pos = at;
		if (pos == null) {
			pos = Containers.nearest(body, (p, st) -> level.getBlockEntity(p) instanceof BaseContainerBlockEntity);
			if (pos == null) throw new ToolException("No chest, barrel, furnace or other container within reach. goto one first (find_blocks can locate it).");
		} else if (!body.inReach(pos)) {
			throw new ToolException("Out of reach: " + Containers.xyz(pos) + " is " + Senses.relative(a, Vec3.atCenterOf(pos)) + ". goto it first.");
		}
		String what = Containers.blockName(level.getBlockState(pos));
		AbstractContainerMenu menu = Containers.open(body, pos);
		if (menu == null) throw new ToolException("The " + what + " at " + Containers.xyz(pos) + " didn't open" + (what.contains("chest") ? " (a chest needs air above it)." : "."));

		Map<Item, Integer> before = Containers.counts(a);
		List<Slot> theirs = Containers.slots(menu, a, false);
		String q = query.trim().toLowerCase(Locale.ROOT);
		Predicate<ItemStack> match = q.isEmpty() ? st -> true : st -> Recipes.name(st.getItem()).contains(q);
		int n = count > 0 ? count : Integer.MAX_VALUE;
		String did;
		try {
			switch (action) {
				case "take" -> {
					// From a furnace, "everything" means the cooked output; the input and fuel are still working.
					List<Slot> from = q.isEmpty() && menu instanceof AbstractFurnaceMenu ? List.of(menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT)) : theirs;
					int got = Containers.take(menu, a, from, match, n);
					did = got == 0 ? "Took nothing" + (q.isEmpty() ? "" : ": no " + q + " in it") + "." : "Took " + got + " item" + (got == 1 ? "" : "s") + ".";
				}
				case "put" -> {
					if (q.isEmpty()) throw new ToolException("Say which item to put.");
					boolean quick = menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu;
					int put = Containers.put(menu, a, theirs, match, n, quick);
					did = put == 0 ? "Put nothing: no " + q + " in the inventory, or no room." : "Put " + put + " item" + (put == 1 ? "" : "s") + ".";
				}
				default -> did = "";
			}
		} catch (ToolException e) {
			Containers.closeSoon(body);
			throw e;
		}
		String contents = describe(menu, theirs);
		String delta = Containers.delta(before, Containers.counts(a));
		Containers.closeSoon(body);
		StringBuilder sb = new StringBuilder();
		if (!did.isEmpty()) sb.append(did).append(' ');
		sb.append("The ").append(what).append(" at ").append(Containers.xyz(pos)).append(contents.startsWith("empty") ? " is " : " holds ").append(contents).append('.');
		if (!did.isEmpty()) sb.append(" Inventory: ").append(delta).append('.');
		return sb.toString();
	}

	/** A furnace by slot (input, fuel, output, progress); anything else as a list with slots used. */
	private static String describe(AbstractContainerMenu menu, List<Slot> theirs) {
		if (menu instanceof AbstractFurnaceMenu f) {
			boolean cooking = f.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).hasItem();
			String state = !f.isLit() ? "not burning" : cooking ? String.format(Locale.ROOT, "burning, %.0f%% through the current item", f.getBurnProgress() * 100)
					: "still burning, nothing to cook";
			return "input " + Containers.contents(List.of(f.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT))) + ", fuel "
					+ Containers.contents(List.of(f.getSlot(AbstractFurnaceMenu.FUEL_SLOT))) + ", output "
					+ Containers.contents(List.of(f.getSlot(AbstractFurnaceMenu.RESULT_SLOT))) + " (" + state + ")";
		}
		int used = 0;
		for (Slot sl : theirs) if (sl.hasItem()) used++;
		return Containers.contents(theirs) + " (" + used + " of " + theirs.size() + " slots used)";
	}

	// ---- items ----------------------------------------------------------------------------------

	/** Exactly one item, or a helpful error. */
	private static Item one(String query) {
		List<Item> items = Recipes.resolve(query);
		if (items.isEmpty()) throw new ToolException(unknown(query));
		if (items.size() > 1) throw new ToolException(query + " is a group: " + names(items, 12) + ". Name one of those.");
		return items.getFirst();
	}

	/** For craft: a group like "planks" becomes the member the inventory can make now, else the first one with a recipe. */
	private static Item craftable(AgentPlayer a, String query) {
		List<Item> items = Recipes.resolve(query);
		if (items.isEmpty()) throw new ToolException(unknown(query));
		if (items.size() == 1) return items.getFirst();
		StackedItemContents have = new StackedItemContents();
		a.getInventory().fillStackedContents(have);
		Item fallback = null;
		for (Item item : items) {
			for (Recipes.Way w : Recipes.ways(a.level(), item)) {
				if (!w.station().crafting()) continue;
				if (have.canCraft(w.holder().value(), null)) return item;
				if (fallback == null) fallback = item;
			}
		}
		if (fallback == null) throw new ToolException("Nothing in " + query + " can be crafted.");
		return fallback;
	}

	private static String unknown(String query) {
		List<String> close = Recipes.suggest(query);
		return "No item called " + query + "." + (close.isEmpty() ? "" : " Did you mean: " + String.join(", ", close) + "?");
	}

	private static String names(List<Item> items, int max) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < Math.min(max, items.size()); i++) out.add(Recipes.name(items.get(i)));
		return String.join(", ", out) + (items.size() > max ? ", ..." : "");
	}

	private static String list(Map<Item, Integer> counts) {
		List<String> out = new ArrayList<>();
		counts.forEach((item, n) -> out.add(n + " " + Recipes.name(item)));
		return String.join(", ", out);
	}
}
