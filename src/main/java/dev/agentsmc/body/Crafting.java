package dev.agentsmc.body;

import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.mcp.ToolException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Crafting and smelting through the server's own menus: the 2x2 grid in the inventory or a crafting
 * table's 3x3, filled by the recipe-book placement the server runs for a client's recipe click, and
 * a furnace filled slot by slot. Recipes, counts, fuel values and cooking times are vanilla's.
 */
public final class Crafting {
	/** Never burned as fuel: tools, stations and the lava bucket's bucket. */
	private static final Set<Item> KEEP = Set.of(Items.CRAFTING_TABLE, Items.CHEST, Items.TRAPPED_CHEST, Items.BARREL, Items.LAVA_BUCKET,
			Items.BOOKSHELF, Items.LADDER, Items.JUKEBOX, Items.NOTE_BLOCK, Items.LECTERN);

	private Crafting() {
	}

	// ---- crafting -------------------------------------------------------------------------------

	/** Crafts at least {@code count} of {@code item} (whole crafts), at a table if the recipe needs one. */
	public static String craft(Body body, Item item, int count) {
		AgentPlayer a = body.agent;
		ServerLevel level = body.level();
		String name = Recipes.name(item);
		List<Recipes.Way> all = Recipes.ways(level, item);
		List<Recipes.Way> ways = all.stream().filter(w -> w.station().crafting()).toList();
		if (ways.isEmpty()) {
			if (!all.isEmpty()) throw new ToolException(name + " isn't crafted: " + Recipes.describe(all.getFirst()) + " Use smelt for furnace recipes.");
			String how = Recipes.obtained(item);
			throw new ToolException("Nothing crafts " + name + (how != null ? "; it's " + how : "") + ".");
		}
		// The first recipe the inventory covers, hand recipes before table ones.
		StackedItemContents have = new StackedItemContents();
		a.getInventory().fillStackedContents(have);
		Recipes.Way way = null;
		for (Recipes.Way w : ways) {
			if (have.canCraft(w.holder().value(), null)) {
				way = w;
				break;
			}
		}
		if (way == null) {
			Recipes.Way w = ways.getFirst();
			throw new ToolException("Not enough for " + name + ": one craft takes " + w.inputs() + " " + w.station().where + ". "
					+ Containers.inventory(a) + " plan_craft lists what's missing.");
		}

		Map<Item, Integer> before = Containers.counts(a);
		String placed = "";
		AbstractCraftingMenu menu;
		if (way.station() == Recipes.Station.HAND) {
			Containers.closeNow(body);
			menu = a.inventoryMenu;
		} else {
			BlockPos table = Containers.nearest(body, (p, s) -> s.is(Blocks.CRAFTING_TABLE));
			if (table == null) {
				if (!a.getInventory().contains(s -> s.is(Items.CRAFTING_TABLE))) {
					throw new ToolException(name + " needs a crafting table; craft one (4 planks) or stand next to one.");
				}
				table = Containers.place(body, s -> s.is(Items.CRAFTING_TABLE));
				if (table == null) throw new ToolException(name + " needs a crafting table, and there's no free spot beside you to place yours. Move to open ground.");
				placed = "Placed a crafting_table at " + Containers.xyz(table) + ". ";
			}
			AbstractContainerMenu open = Containers.open(body, table);
			if (!(open instanceof CraftingMenu cm)) {
				Containers.closeNow(body);
				throw new ToolException(placed + "The crafting table at " + Containers.xyz(table) + " didn't open.");
			}
			menu = cm;
		}

		int crafts = (count + way.count() - 1) / way.count();
		int done = 0;
		String stop = null;
		try {
			for (int i = 0; i < crafts; i++) {
				// One set of ingredients into the grid, as a client's recipe-book click does, then shift-click the result.
				menu.handlePlacement(false, false, way.holder(), level, a.getInventory());
				Slot result = menu.getResultSlot();
				if (!result.hasItem() || !result.getItem().is(item)) {
					stop = "ran out of ingredients";
					break;
				}
				menu.clicked(result.index, 0, ContainerInput.QUICK_MOVE, a);
				if (result.hasItem() && result.getItem().is(item)) {
					stop = "the inventory is full";
					break;
				}
				done++;
			}
		} finally {
			for (Slot s : menu.getInputGridSlots()) if (s.hasItem()) menu.clicked(s.index, 0, ContainerInput.QUICK_MOVE, a);
			if (menu != a.inventoryMenu) Containers.closeNow(body);
		}
		String delta = Containers.delta(before, Containers.counts(a));
		String where = way.station() == Recipes.Station.HAND ? "2x2 grid" : "crafting table";
		if (done == 0) throw new ToolException(placed + "Couldn't craft " + name + ": " + stop + ".");
		String head = done < crafts
				? String.format(Locale.ROOT, "Crafted %d of %d %s (%s): %s.", done * way.count(), count, name, where, stop)
				: String.format(Locale.ROOT, "Crafted %d %s (%d craft%s, %s).", done * way.count(), name, done, done == 1 ? "" : "s", where);
		return placed + head + " Inventory: " + delta + ".";
	}

	// ---- smelting -------------------------------------------------------------------------------

	/**
	 * Puts {@code count} of {@code input} and enough fuel into a furnace in reach (placing one from the
	 * inventory if needed). Returns at once; the furnace cooks on its own.
	 */
	public static String smelt(Body body, Item input, int count, String fuelQuery) {
		AgentPlayer a = body.agent;
		ServerLevel level = body.level();
		String name = Recipes.name(input);
		ItemStack probe = new ItemStack(input);
		int have = Containers.counts(a).getOrDefault(input, 0);
		if (have == 0) throw new ToolException("No " + name + " in the inventory. " + Containers.inventory(a));

		BlockPos pos = Containers.nearest(body, (p, s) -> level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f && cooking(level, f, probe) != null);
		String placed = "";
		if (pos == null) {
			Item station = null;
			for (Item it : List.of(Items.FURNACE, Items.SMOKER, Items.BLAST_FURNACE)) {
				if (a.getInventory().contains(s -> s.is(it)) && cooking(level, type(it), probe) != null) {
					station = it;
					break;
				}
			}
			if (station == null) {
				if (cooking(level, RecipeType.SMELTING, probe) == null) throw new ToolException(name + " can't be smelted. recipe shows what it's for.");
				boolean other = Containers.nearest(body, (p, s) -> level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity) != null;
				throw new ToolException("Smelting " + name + " needs a furnace; craft one (8 cobblestone) or stand next to one."
						+ (other ? " The furnace in reach can't cook it." : ""));
			}
			Item chosen = station;
			pos = Containers.place(body, s -> s.is(chosen));
			if (pos == null) throw new ToolException("No free spot beside you to place the " + Recipes.name(station) + ". Move to open ground.");
			placed = "Placed a " + Recipes.name(station) + " at " + Containers.xyz(pos) + ". ";
		}
		if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) throw new ToolException(placed + "No furnace at " + Containers.xyz(pos));
		String what = Containers.blockName(level.getBlockState(pos));
		AbstractCookingRecipe recipe = cooking(level, furnace, probe);
		if (!(Containers.open(body, pos) instanceof AbstractFurnaceMenu menu)) {
			Containers.closeNow(body);
			throw new ToolException(placed + "The " + what + " at " + Containers.xyz(pos) + " didn't open.");
		}

		Map<Item, Integer> before = Containers.counts(a);
		Slot in = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT), fuelSlot = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT), out = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT);
		if (in.hasItem() && !ItemStack.isSameItemSameComponents(in.getItem(), probe)) {
			String busy = Containers.contents(List.of(in));
			Containers.closeSoon(body);
			throw new ToolException(placed + "The " + what + " is busy with " + busy + "; take it out first (container action=take).");
		}
		int space = in.getMaxStackSize(probe) - in.getItem().getCount();
		int want = Math.min(Math.min(count <= 0 ? have : count, have), space);
		int moved = 0;
		for (Slot s : Containers.slots(menu, a, true)) {
			if (moved >= want) break;
			if (s.hasItem() && ItemStack.isSameItemSameComponents(s.getItem(), probe)) moved += Containers.move(menu, a, s, in, want - moved);
		}
		if (moved == 0 && !in.hasItem()) {
			Containers.closeSoon(body);
			throw new ToolException(placed + "Couldn't put " + name + " into the " + what + ".");
		}

		// Fuel: enough burn time for everything now in the input slot, counting fuel already there.
		LootContext ctx = fuelContext(level, furnace);
		int queued = in.getItem().getCount();
		int needTicks = queued * recipe.cookingTime() - burn(fuelSlot.getItem(), ctx) * fuelSlot.getItem().getCount();
		String fuelNote = "";
		if (needTicks > 0) {
			ItemStack fuel = chooseFuel(menu, a, fuelSlot, input, needTicks, fuelQuery, ctx);
			if (fuel == null) {
				fuelNote = fuelSlot.hasItem() ? " The fuel slot holds " + Containers.contents(List.of(fuelSlot)) + " and you have no more of it; it won't cook everything."
						: " No fuel in the inventory, so it won't cook. Get coal, charcoal or wood and put it in the fuel slot (container action=put).";
			} else {
				int per = burn(fuel, ctx);
				int n = Math.min((needTicks + per - 1) / per, fuelSlot.getMaxStackSize(fuel) - fuelSlot.getItem().getCount());
				int got = 0;
				for (Slot s : Containers.slots(menu, a, true)) {
					if (got >= n) break;
					if (s.hasItem() && ItemStack.isSameItemSameComponents(s.getItem(), fuel)) got += Containers.move(menu, a, s, fuelSlot, n - got);
				}
				if (got < n) fuelNote = " Only enough fuel for part of it.";
			}
		}

		// Expected time: the recipe's cooking time, sped up by a fuel that says so.
		ItemStack fuelNow = fuelSlot.getItem();
		float speed = fuelNow.isEmpty() ? 1f : ResolvableFloat.getFromItem(fuelNow, DataComponents.COOKING_FUEL, CookingFuel::speedMultiplier, ctx, 1f);
		int perItem = speed > 0 ? (int) Math.ceil(recipe.cookingTime() / speed) : recipe.cookingTime();
		ItemStack result = recipe.assemble(new SingleRecipeInput(probe));
		String outNote = out.hasItem() ? " The output slot already holds " + Containers.contents(List.of(out)) + "." : "";
		String delta = Containers.delta(before, Containers.counts(a));
		Containers.closeSoon(body);
		return String.format(Locale.ROOT, "%sPut %d %s into the %s at %s (input now %d, fuel %s). Cooking takes %.0f s per item: %d %s ready in about %.0f s.%s%s "
						+ "Then take it with container action=take. Inventory: %s.",
				placed, moved, name, what, Containers.xyz(pos), queued, Containers.contents(List.of(fuelSlot)), perItem / 20.0, queued * result.getCount(),
				Recipes.name(result.getItem()), queued * perItem / 20.0, fuelNote, outNote, delta);
	}

	/**
	 * The fuel to add: coal or charcoal first, then logs and planks, then anything else that burns
	 * (never tools or stations). Within a group, the one that covers the job with least waste. The
	 * fuel slot only stacks one item, so a slot already holding fuel decides it.
	 */
	private static ItemStack chooseFuel(AbstractFurnaceMenu menu, AgentPlayer a, Slot fuelSlot, Item input, int needTicks, String query, LootContext ctx) {
		Map<Item, ItemStack> kinds = new LinkedHashMap<>();
		Map<Item, Integer> counts = new LinkedHashMap<>();
		for (Slot s : Containers.slots(menu, a, true)) {
			ItemStack st = s.getItem();
			if (st.isEmpty() || st.is(input) || KEEP.contains(st.getItem()) || st.has(DataComponents.MAX_DAMAGE) || burn(st, ctx) <= 0) continue;
			if (fuelSlot.hasItem() && !ItemStack.isSameItemSameComponents(fuelSlot.getItem(), st)) continue;
			if (query != null && !query.isBlank() && !Recipes.name(st.getItem()).contains(query.trim().toLowerCase(Locale.ROOT))) continue;
			kinds.putIfAbsent(st.getItem(), st.copyWithCount(1));
			counts.merge(st.getItem(), st.getCount(), Integer::sum);
		}
		Comparator<ItemStack> order = Comparator.comparingInt((ItemStack st) -> st.is(Items.COAL) || st.is(Items.CHARCOAL) ? 0 : st.is(ItemTags.LOGS) || st.is(ItemTags.PLANKS) ? 1 : 2)
				.thenComparingInt(st -> counts.get(st.getItem()) * burn(st, ctx) >= needTicks ? 0 : 1)
				.thenComparingInt(st -> {
					int per = burn(st, ctx);
					return ((needTicks + per - 1) / per) * per - needTicks;
				})
				.thenComparingInt(st -> -counts.get(st.getItem()));
		return kinds.values().stream().min(order).orElse(null);
	}

	/** How many ticks one of this item burns in this furnace, from the item's cooking_fuel component. */
	private static int burn(ItemStack stack, LootContext ctx) {
		if (stack.isEmpty()) return 0;
		return ResolvableInt.getFromItem(stack, DataComponents.COOKING_FUEL, CookingFuel::burnTime, ctx, 0);
	}

	/** The same loot context the furnace builds to resolve fuel values. */
	private static LootContext fuelContext(ServerLevel level, AbstractFurnaceBlockEntity f) {
		LootParams params = new LootParams.Builder(level)
				.withParameter(LootContextParams.BLOCK_STATE, f.getBlockState())
				.withParameter(LootContextParams.BLOCK_ENTITY, f)
				.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(f.getBlockPos()))
				.withParameter(LootContextParams.CONTAINER, f)
				.create(LootContextParamSets.CONTAINER_PROCESS);
		return new LootContext.Builder(params).create(Optional.empty());
	}

	private static RecipeType<? extends AbstractCookingRecipe> type(AbstractFurnaceBlockEntity f) {
		return f instanceof BlastFurnaceBlockEntity ? RecipeType.BLASTING : f instanceof SmokerBlockEntity ? RecipeType.SMOKING : RecipeType.SMELTING;
	}

	private static RecipeType<? extends AbstractCookingRecipe> type(Item station) {
		return station == Items.BLAST_FURNACE ? RecipeType.BLASTING : station == Items.SMOKER ? RecipeType.SMOKING : RecipeType.SMELTING;
	}

	private static AbstractCookingRecipe cooking(ServerLevel level, AbstractFurnaceBlockEntity f, ItemStack stack) {
		return cooking(level, type(f), stack);
	}

	/** The cooking recipe of this type for the item, or null. */
	private static AbstractCookingRecipe cooking(ServerLevel level, RecipeType<? extends AbstractCookingRecipe> type, ItemStack stack) {
		RecipeManager rm = level.recipeAccess();
		SingleRecipeInput in = new SingleRecipeInput(stack);
		if (type == RecipeType.BLASTING) return rm.getRecipeFor(RecipeType.BLASTING, in, level).map(h -> (AbstractCookingRecipe) h.value()).orElse(null);
		if (type == RecipeType.SMOKING) return rm.getRecipeFor(RecipeType.SMOKING, in, level).map(h -> (AbstractCookingRecipe) h.value()).orElse(null);
		return rm.getRecipeFor(RecipeType.SMELTING, in, level).map(h -> (AbstractCookingRecipe) h.value()).orElse(null);
	}
}
