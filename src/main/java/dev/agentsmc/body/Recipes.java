package dev.agentsmc.body;

import dev.agentsmc.perception.BlockCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What makes an item and how to get from the inventory to it, read from the server's own recipe
 * manager so datapacks and version changes are respected. Nothing here touches the world.
 */
public final class Recipes {
	/** Where a recipe is made. HAND is the 2x2 grid in the player's own inventory. */
	public enum Station {
		HAND("in the 2x2 inventory grid"), TABLE("at a crafting table"), FURNACE("in a furnace"), BLAST_FURNACE("in a blast furnace"),
		SMOKER("in a smoker"), CAMPFIRE("on a campfire"), STONECUTTER("in a stonecutter");

		public final String where;

		Station(String where) {
			this.where = where;
		}

		public boolean crafting() {
			return this == HAND || this == TABLE;
		}
	}

	/** One ingredient of a recipe and how many of it one craft uses. */
	public record Need(Ingredient ingredient, int count) {
		public String label() {
			return count + " " + Recipes.label(ingredient);
		}
	}

	/** One way to make an item: the recipe, what one craft yields, where, and what it uses. */
	public record Way(RecipeHolder<?> holder, Item result, int count, Station station, List<Need> needs, int cookTicks) {
		public String inputs() {
			List<String> parts = new ArrayList<>();
			for (Need n : needs) parts.add(n.label());
			return String.join(" + ", parts);
		}
	}

	private static final int MAX_DEPTH = 8;
	private static final double INF = 1e9;

	// The index is rebuilt when the recipe manager changes (a /reload makes a new one).
	private static RecipeManager indexedFor;
	private static Map<Item, List<Way>> index = Map.of();

	private Recipes() {
	}

	// ---- lookup ---------------------------------------------------------------------------------

	/** Every known way to make {@code item}, crafting first. Server thread. */
	public static List<Way> ways(ServerLevel level, Item item) {
		return index(level).getOrDefault(item, List.of());
	}

	private static Map<Item, List<Way>> index(ServerLevel level) {
		RecipeManager manager = level.getServer().getRecipeManager();
		if (manager == indexedFor) return index;
		Map<Item, List<Way>> out = new HashMap<>();
		var context = SlotDisplayContext.fromLevel(level);
		for (RecipeHolder<?> holder : manager.getRecipes()) {
			Recipe<?> r = holder.value();
			Station station = station(r);
			if (station == null || r.isSpecial() || r.placementInfo().isImpossibleToPlace()) continue;
			List<RecipeDisplay> displays = r.display();
			if (displays.isEmpty()) continue;
			ItemStack result;
			try {
				result = displays.getFirst().result().resolveForFirstStack(context);
			} catch (RuntimeException e) {
				continue; // a datapack recipe whose result can't be shown is skipped, not fatal
			}
			if (result.isEmpty()) continue;
			// Identical ingredients are counted together: a pickaxe uses "3 #planks", not "1 #planks" three times.
			Map<Ingredient, Integer> counts = new LinkedHashMap<>();
			for (Ingredient ing : r.placementInfo().ingredients()) counts.merge(ing, 1, Integer::sum);
			List<Need> needs = new ArrayList<>();
			counts.forEach((ing, n) -> needs.add(new Need(ing, n)));
			int cook = r instanceof AbstractCookingRecipe c ? c.cookingTime() : 0;
			out.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(new Way(holder, result.getItem(), result.getCount(), station, needs, cook));
		}
		for (List<Way> list : out.values()) list.sort(Comparator.comparingInt(w -> w.station().ordinal()));
		indexedFor = manager;
		index = out;
		return out;
	}

	private static Station station(Recipe<?> r) {
		RecipeType<?> type = r.getType();
		if (type == RecipeType.CRAFTING) {
			boolean small = r instanceof ShapedRecipe s ? s.getWidth() <= 2 && s.getHeight() <= 2 : r.placementInfo().ingredients().size() <= 4;
			return small ? Station.HAND : Station.TABLE;
		}
		if (type == RecipeType.SMELTING) return Station.FURNACE;
		if (type == RecipeType.BLASTING) return Station.BLAST_FURNACE;
		if (type == RecipeType.SMOKING) return Station.SMOKER;
		if (type == RecipeType.CAMPFIRE_COOKING) return Station.CAMPFIRE;
		if (type == RecipeType.STONECUTTING) return Station.STONECUTTER;
		return null;
	}

	/**
	 * Items a query names: an item id ("oak_planks", "minecraft:stick"), or a tag ("#planks", or
	 * "planks" when no item has that name). Empty when nothing matches.
	 */
	public static List<Item> resolve(String query) {
		String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		boolean tag = q.startsWith("#");
		if (tag) q = q.substring(1);
		Identifier id = Identifier.tryParse(q.contains(":") ? q : "minecraft:" + q);
		if (id == null) return List.of();
		if (!tag) {
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
			if (item.isPresent() && item.get() != net.minecraft.world.item.Items.AIR) return List.of(item.get());
		}
		List<Item> members = new ArrayList<>();
		for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, id))) members.add(h.value());
		return members;
	}

	/** Up to eight item ids containing the query, for "did you mean". */
	public static List<String> suggest(String query) {
		String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("#", "");
		List<String> out = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			String n = name(item);
			if (n.contains(q)) out.add(n);
			if (out.size() >= 8) break;
		}
		return out;
	}

	public static String name(Item item) {
		return BlockCodec.shortId(BuiltInRegistries.ITEM.getKey(item).toString());
	}

	/** "#planks" for a tag, "stick" for one item, "a|b|c" for a short list. */
	public static String label(Ingredient ing) {
		if (ing.display() instanceof SlotDisplay.TagSlotDisplay t) {
			var key = t.tag().unwrapKey();
			if (key.isPresent()) return "#" + BlockCodec.shortId(key.get().location().toString());
		}
		List<Holder<Item>> items = ing.items().toList();
		List<String> names = new ArrayList<>();
		for (int i = 0; i < Math.min(3, items.size()); i++) names.add(name(items.get(i).value()));
		return String.join("|", names) + (items.size() > 3 ? "|..." : "");
	}

	/** One line per way, for the recipe tool. */
	public static String describe(Way w) {
		String out = w.count() + " " + name(w.result());
		return switch (w.station()) {
			case HAND -> out + " <- " + w.inputs() + ". Fits the 2x2 inventory grid.";
			case TABLE -> out + " <- " + w.inputs() + ". Needs a crafting table (3x3).";
			case STONECUTTER -> out + " <- " + w.inputs() + " in a stonecutter.";
			default -> String.format(Locale.ROOT, "%s <- smelt %s %s (%.0f s each).", out, w.inputs(), w.station().where, w.cookTicks() / 20.0);
		};
	}

	/** How a player gets an item that nobody crafts, in a few words, or null if it isn't a raw resource. */
	public static String obtained(Item item) {
		String n = name(item);
		String hint = HINTS.get(n);
		if (hint != null) return hint;
		if (n.endsWith("_log")) return "chopped from trees";
		if (n.endsWith("_stem")) return "chopped from huge nether fungi";
		if (n.endsWith("_wool")) return "sheared from sheep";
		if (n.endsWith("_sapling")) return "dropped by leaves";
		if (n.startsWith("raw_")) return "mined from " + n.substring(4) + " ore";
		if (n.endsWith("_ore")) return "found underground; mine it with a pickaxe";
		return null;
	}

	private static final Map<String, String> HINTS = Map.ofEntries(
			Map.entry("cobblestone", "mined from stone with a pickaxe"), Map.entry("cobbled_deepslate", "mined from deepslate with a pickaxe"),
			Map.entry("blackstone", "mined in the nether"), Map.entry("coal", "mined from coal ore"), Map.entry("diamond", "mined from diamond ore"),
			Map.entry("emerald", "mined from emerald ore or traded"), Map.entry("lapis_lazuli", "mined from lapis ore"),
			Map.entry("redstone", "mined from redstone ore"), Map.entry("quartz", "mined from nether quartz ore"),
			Map.entry("amethyst_shard", "mined from amethyst clusters"), Map.entry("dirt", "dug up"), Map.entry("sand", "dug up"),
			Map.entry("red_sand", "dug up"), Map.entry("gravel", "dug up"), Map.entry("clay_ball", "dug from clay"), Map.entry("flint", "dropped by gravel"),
			Map.entry("snowball", "dug from snow"), Map.entry("chicken", "dropped by chickens"), Map.entry("feather", "dropped by chickens"),
			Map.entry("egg", "laid by chickens"), Map.entry("beef", "dropped by cows"), Map.entry("leather", "dropped by cows"),
			Map.entry("porkchop", "dropped by pigs"), Map.entry("mutton", "dropped by sheep"), Map.entry("rabbit", "dropped by rabbits"),
			Map.entry("rabbit_hide", "dropped by rabbits"), Map.entry("cod", "fished or dropped by cod"), Map.entry("salmon", "fished or dropped by salmon"),
			Map.entry("string", "dropped by spiders"), Map.entry("spider_eye", "dropped by spiders"), Map.entry("bone", "dropped by skeletons"),
			Map.entry("gunpowder", "dropped by creepers"), Map.entry("rotten_flesh", "dropped by zombies"), Map.entry("ender_pearl", "dropped by endermen"),
			Map.entry("blaze_rod", "dropped by blazes"), Map.entry("slime_ball", "dropped by slimes"), Map.entry("ink_sac", "dropped by squid"),
			Map.entry("wheat", "farmed"), Map.entry("wheat_seeds", "dropped by grass"), Map.entry("sugar_cane", "grows by water"),
			Map.entry("carrot", "farmed or found in villages"), Map.entry("potato", "farmed or found in villages"), Map.entry("beetroot", "farmed"),
			Map.entry("melon_slice", "dropped by melons"), Map.entry("pumpkin", "grows wild"), Map.entry("bamboo", "grows in jungles"),
			Map.entry("cactus", "grows in deserts"), Map.entry("kelp", "grows in oceans"), Map.entry("apple", "dropped by oak leaves"), Map.entry("obsidian", "water on lava, mined with a diamond pickaxe"));

	// ---- planning -------------------------------------------------------------------------------

	/** The outcome of working backwards from an item to the inventory. */
	public record Plan(String headline, Map<Item, Integer> used, Map<Item, Integer> missing, List<String> steps) {
	}

	/**
	 * Works backwards from {@code count} of {@code target} to what the inventory holds: chooses a
	 * recipe for every intermediate (preferring ones the inventory already covers), adds up how much of
	 * each is needed across the whole tree, and lists the crafts in an order that works. A crafting
	 * table and a furnace are added when a step needs one and none is at hand.
	 */
	public static Plan plan(ServerLevel level, Item target, int count, Map<Item, Integer> inventory, boolean tableAtHand, boolean furnaceAtHand) {
		Item table = net.minecraft.world.item.Items.CRAFTING_TABLE, furnace = net.minecraft.world.item.Items.FURNACE;
		// A first pass finds out which stations the recipes need; a table or furnace in the bag is placed, not crafted.
		Planner probe = new Planner(level, inventory);
		probe.expand(target);
		boolean needFurnace = probe.uses(Station.FURNACE) && !furnaceAtHand && inventory.getOrDefault(furnace, 0) == 0 && target != furnace;
		if (needFurnace) probe.expand(furnace);
		boolean needTable = probe.uses(Station.TABLE) && !tableAtHand && inventory.getOrDefault(table, 0) == 0 && target != table;

		// Stations are expanded first so their crafts come before the steps that use them.
		Planner p = new Planner(level, inventory);
		Map<Item, Integer> roots = new LinkedHashMap<>();
		if (needTable) roots.put(table, 1);
		if (needFurnace) roots.put(furnace, 1);
		roots.merge(target, count, Integer::sum);
		for (Item root : roots.keySet()) p.expand(root);
		p.demand(roots);

		List<String> steps = new ArrayList<>();
		p.missing.forEach((item, n) -> {
			String how = obtained(item);
			steps.add("get " + n + " " + name(item) + (how != null ? " (" + how + ")" : ""));
		});
		boolean tablePlaced = tableAtHand, furnacePlaced = furnaceAtHand;
		for (Item item : p.order) {
			int crafts = p.crafts.getOrDefault(item, 0);
			if (crafts == 0) continue;
			Way w = p.chosen.get(item);
			if (w.station() == Station.TABLE && !tablePlaced) {
				steps.add("place the crafting_table");
				tablePlaced = true;
			}
			if (w.station() == Station.FURNACE && !furnacePlaced) {
				steps.add("place the furnace");
				furnacePlaced = true;
			}
			int made = crafts * w.count();
			if (w.station() == Station.FURNACE) {
				steps.add(String.format(Locale.ROOT, "smelt %d %s into %d %s (furnace with fuel, %d s)", crafts, name(p.pick(w.needs().getFirst().ingredient())),
						made, name(item), Math.round(crafts * w.cookTicks() / 20.0)));
			} else {
				String where = w.station() == Station.TABLE ? "crafting table" : "2x2 grid";
				steps.add("craft " + made + " " + name(item) + " (" + (crafts > 1 ? crafts + " crafts, " : "") + where + ")");
			}
		}
		if (p.uses(Station.FURNACE) && !p.hasFuel()) steps.add("you need fuel too: coal, charcoal, or any wood (1 plank smelts 1.5 items)");

		Way top = p.chosen.get(target);
		String headline;
		if (inventory.getOrDefault(target, 0) >= count) headline = "You already have " + inventory.get(target) + " " + name(target) + ".";
		else if (top == null) {
			String how = obtained(target);
			headline = name(target) + " has no recipe" + (how != null ? ": it's " + how : " you can make from here") + ".";
		} else headline = name(target) + " needs " + top.inputs() + " " + top.station().where + " (makes " + top.count() + ").";
		return new Plan(headline, p.used, p.missing, steps);
	}

	/** The working state of one plan. */
	private static final class Planner {
		final ServerLevel level;
		final Map<Item, Integer> inventory;
		final Map<Item, Double> cost = new HashMap<>();
		/** The chosen way for each item; absent means it's gathered, not made. */
		final Map<Item, Way> chosen = new HashMap<>();
		/** The concrete item picked for each ingredient of a chosen way. */
		final Map<Ingredient, Item> picked = new HashMap<>();
		/** Producers before consumers. */
		final List<Item> order = new ArrayList<>();
		final Set<Item> expanded = new HashSet<>();
		final Map<Item, Integer> crafts = new HashMap<>();
		final Map<Item, Integer> used = new LinkedHashMap<>();
		final Map<Item, Integer> missing = new LinkedHashMap<>();

		Planner(ServerLevel level, Map<Item, Integer> inventory) {
			this.level = level;
			this.inventory = inventory;
		}

		/** Ways the planner will use: hand and table crafting, and the furnace. */
		List<Way> usable(Item item) {
			List<Way> out = new ArrayList<>();
			for (Way w : ways(level, item)) if (w.station().crafting() || w.station() == Station.FURNACE) out.add(w);
			return out;
		}

		/**
		 * A rough cost of getting one of {@code item}: 0 if the inventory has it, 1 per raw resource
		 * to gather, a little more for smelting. Only used to choose between recipes and tag members.
		 */
		double cost(Item item, int depth, Set<Item> stack) {
			if (inventory.getOrDefault(item, 0) > 0) return 0;
			Double known = cost.get(item);
			if (known != null) return known;
			if (depth > MAX_DEPTH || !stack.add(item)) return INF;
			double best = obtained(item) != null ? 1 : INF;
			for (Way w : usable(item)) {
				double c = w.station() == Station.FURNACE ? 0.5 : 0.01;
				for (Need n : w.needs()) c += optionCost(n.ingredient(), depth + 1, stack);
				best = Math.min(best, c);
			}
			if (best >= INF && usable(item).isEmpty()) best = 1; // nothing makes it: gather it somehow
			stack.remove(item);
			cost.put(item, best);
			return best;
		}

		double optionCost(Ingredient ing, int depth, Set<Item> stack) {
			double best = INF;
			for (Holder<Item> h : ing.items().toList()) best = Math.min(best, cost(h.value(), depth, stack));
			return best;
		}

		/** The cheapest member of an ingredient; ties go to what the inventory has most of, then oak and the like. */
		Item pick(Ingredient ing) {
			return picked.computeIfAbsent(ing, k -> {
				Item best = null;
				double bestCost = 0;
				int bestHave = 0;
				boolean bestCommon = false;
				for (Holder<Item> h : ing.items().toList()) {
					Item item = h.value();
					double c = cost(item, 0, new HashSet<>());
					int have = inventory.getOrDefault(item, 0);
					boolean common = name(item).startsWith("oak_") || name(item).equals("cobblestone");
					boolean tie = Math.abs(c - bestCost) < 1e-9;
					if (best == null || c < bestCost - 1e-9 || (tie && (have > bestHave || (have == bestHave && common && !bestCommon)))) {
						best = item;
						bestCost = c;
						bestHave = have;
						bestCommon = common;
					}
				}
				return best;
			});
		}

		/** Chooses ways for {@code item} and everything below it, appending to {@link #order} after its inputs. */
		void expand(Item item) {
			expand(item, 0, new HashSet<>());
		}

		private void expand(Item item, int depth, Set<Item> stack) {
			if (expanded.contains(item) || depth > MAX_DEPTH || !stack.add(item)) return;
			Way way = choose(item);
			if (way != null) {
				chosen.put(item, way);
				for (Need n : way.needs()) {
					Item input = pick(n.ingredient());
					if (input != null && !stack.contains(input)) expand(input, depth + 1, stack);
				}
			}
			stack.remove(item);
			expanded.add(item);
			order.add(item);
		}

		/** The cheapest way to make more of {@code item}, ignoring how many the inventory already has; null if it's gathered. */
		Way choose(Item item) {
			Way best = null;
			double bestCost = INF;
			for (Way w : usable(item)) {
				double c = w.station() == Station.FURNACE ? 0.5 : 0.01;
				for (Need n : w.needs()) c += optionCost(n.ingredient(), 1, new HashSet<>(Set.of(item)));
				// Prefer the 2x2 grid, then the recipe named after the item (stick over stick_from_bamboo).
				if (w.station() == Station.TABLE) c += 0.001;
				if (!w.holder().id().identifier().getPath().equals(name(item))) c += 0.002;
				if (c < bestCost) {
					best = w;
					bestCost = c;
				}
			}
			// A raw resource is gathered unless the inventory already covers a recipe for it (leather from rabbit hide, say).
			if (bestCost >= INF || (obtained(item) != null && bestCost >= 1)) return null;
			return best;
		}

		boolean uses(Station s) {
			for (Way w : chosen.values()) if (w.station() == s) return true;
			return false;
		}

		boolean hasFuel() {
			for (Item item : inventory.keySet()) if (item.components().has(net.minecraft.core.component.DataComponents.COOKING_FUEL)) return true;
			return false;
		}

		/** Adds up demand from the roots down, consumers before producers, using the inventory once. */
		void demand(Map<Item, Integer> roots) {
			Map<Item, Integer> demand = new HashMap<>(roots);
			Map<Item, Integer> left = new HashMap<>(inventory);
			for (int i = order.size() - 1; i >= 0; i--) {
				Item item = order.get(i);
				int want = demand.getOrDefault(item, 0);
				if (want == 0) continue;
				int take = Math.min(want, left.getOrDefault(item, 0));
				if (take > 0) {
					left.merge(item, -take, Integer::sum);
					used.merge(item, take, Integer::sum);
				}
				int net = want - take;
				if (net == 0) continue;
				Way w = chosen.get(item);
				if (w == null) {
					missing.merge(item, net, Integer::sum);
					continue;
				}
				int n = (net + w.count() - 1) / w.count();
				crafts.merge(item, n, Integer::sum);
				for (Need need : w.needs()) {
					Item input = pick(need.ingredient());
					if (input != null) demand.merge(input, need.count() * n, Integer::sum);
				}
			}
		}
	}
}
