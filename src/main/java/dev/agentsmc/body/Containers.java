package dev.agentsmc.body;

import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.perception.BlockCodec;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Working blocks with menus the way a player does: find one in reach (or place one from the
 * inventory), right-click it so the server opens its menu, move items with the same slot clicks a
 * client sends, and close it. Vanilla's menu code applies its own rules: which slot takes fuel, stack
 * limits, crafting results, furnace experience. Server thread only.
 */
public final class Containers {
	/** Ticks a chest or furnace stays open after the agent is done, so players see the lid move. */
	private static final int LINGER = 10;

	private Containers() {
	}

	// ---- finding and placing --------------------------------------------------------------------

	/** The nearest block within reach that matches, or null. */
	public static BlockPos nearest(Body body, BiPredicate<BlockPos, BlockState> match) {
		AgentPlayer a = body.agent;
		ServerLevel level = body.level();
		int r = (int) Math.ceil(a.blockInteractionRange()) + 1;
		Vec3 eye = a.getEyePosition();
		BlockPos centre = BlockPos.containing(eye);
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
			if (!body.inReach(p)) continue;
			BlockState state = level.getBlockState(p);
			if (state.isAir() || !match.test(p, state)) continue;
			double d = Vec3.atCenterOf(p).distanceToSqr(eye);
			if (d < bestDist) {
				bestDist = d;
				best = p.immutable();
			}
		}
		return best;
	}

	/**
	 * Places a block item from the inventory on a free spot beside the agent, like a player putting
	 * down a crafting table to use it. Returns where it went, or null if no spot worked.
	 */
	public static BlockPos place(Body body, Predicate<ItemStack> item) {
		AgentPlayer a = body.agent;
		ServerLevel level = body.level();
		BlockPos feet = a.blockPosition();
		List<BlockPos> spots = new ArrayList<>();
		for (int dy : new int[] {0, -1, 1}) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (dx != 0 || dz != 0) spots.add(feet.offset(dx, dy, dz));
				}
			}
		}
		// Same level first, then the closest; diagonals after the four sides.
		spots.sort(Comparator.comparingInt((BlockPos p) -> Math.abs(p.getY() - feet.getY())).thenComparingInt(p -> {
			int dx = p.getX() - feet.getX(), dz = p.getZ() - feet.getZ();
			return dx * dx + dz * dz;
		}));
		for (BlockPos pos : spots) {
			BlockState there = level.getBlockState(pos);
			if (!there.canBeReplaced() || !there.getFluidState().isEmpty()) continue;
			BlockPos below = pos.below();
			BlockState under = level.getBlockState(below);
			// Clicking a block that has its own menu would open it instead of placing against it.
			if (!under.isFaceSturdy(level, below, Direction.UP) || under.getMenuProvider(level, below) != null) continue;
			if (a.getBoundingBox().intersects(new AABB(pos)) || !body.inReach(below)) continue;
			if (!body.hold(item)) return null;
			Item held = a.getMainHandItem().getItem();
			BlockHitResult hit = body.placementHit(pos, below);
			if (!body.canSee(hit)) continue;
			a.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
			body.useOn(hit);
			if (level.getBlockState(pos).getBlock().asItem() == held) return pos;
		}
		return null;
	}

	// ---- opening and closing --------------------------------------------------------------------

	/**
	 * Right-clicks the block so the server opens its menu, as {@code use_block} does. Returns the
	 * open menu, or null if nothing opened (a chest with a block on top, say).
	 */
	public static AbstractContainerMenu open(Body body, BlockPos pos) {
		AgentPlayer a = body.agent;
		closeNow(body);
		a.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
		BlockHitResult hit = body.sight(pos);
		if (hit == null) {
			// A player can't click a chest through a wall; neither can the body.
			throw new ToolException("Can't see the " + blockName(a.level().getBlockState(pos)) + " at " + xyz(pos) + " from here: something is in the way. Move for a clear view of it.");
		}
		// Sneaking would place the held item against the block instead of opening it.
		if (a.isShiftKeyDown()) a.setShiftKeyDown(false);
		body.useOn(hit);
		return a.containerMenu != a.inventoryMenu ? a.containerMenu : null;
	}

	/** Closes the open menu a moment from now, so other players see the chest lid or furnace close. */
	public static void closeSoon(Body body) {
		AgentPlayer a = body.agent;
		AbstractContainerMenu menu = a.containerMenu;
		if (menu == a.inventoryMenu) return;
		body.later(LINGER, () -> {
			if (a.containerMenu == menu) a.closeContainer();
		});
	}

	/** Closes any open menu now. Closing returns items left in a crafting grid to the inventory. */
	public static void closeNow(Body body) {
		AgentPlayer a = body.agent;
		body.later(0, null);
		if (a.containerMenu != a.inventoryMenu) a.closeContainer();
	}

	// ---- slots ----------------------------------------------------------------------------------

	/** The menu's slots on the player's side ({@code own}) or the block's side. */
	public static List<Slot> slots(AbstractContainerMenu menu, Player p, boolean own) {
		List<Slot> out = new ArrayList<>();
		for (Slot s : menu.slots) if ((s.container == p.getInventory()) == own) out.add(s);
		return out;
	}

	/** A slot that can take {@code stack}: one already holding it with room to spare, else an empty one. */
	public static Slot target(List<Slot> slots, ItemStack stack) {
		for (Slot s : slots) {
			ItemStack in = s.getItem();
			if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < s.getMaxStackSize(in) && s.mayPlace(stack)) return s;
		}
		for (Slot s : slots) if (!s.hasItem() && s.mayPlace(stack)) return s;
		return null;
	}

	/** Shift-clicks a slot. Returns how many items left it. */
	public static int quickMove(AbstractContainerMenu menu, Player p, Slot from) {
		ItemStack before = from.getItem().copy();
		menu.clicked(from.index, 0, ContainerInput.QUICK_MOVE, p);
		ItemStack after = from.getItem();
		return ItemStack.isSameItemSameComponents(before, after) ? before.getCount() - after.getCount() : before.getCount();
	}

	/**
	 * Moves up to {@code n} items from one slot to another with mouse clicks: pick up the stack,
	 * left-click to drop all of it or right-click to drop one at a time, then put the rest back.
	 * Returns how many moved.
	 */
	public static int move(AbstractContainerMenu menu, Player p, Slot from, Slot to, int n) {
		if (n <= 0 || !from.hasItem() || !menu.getCarried().isEmpty()) return 0;
		menu.clicked(from.index, 0, ContainerInput.PICKUP, p);
		int moved = 0;
		while (moved < n && !menu.getCarried().isEmpty()) {
			int before = menu.getCarried().getCount();
			menu.clicked(to.index, n - moved >= before ? 0 : 1, ContainerInput.PICKUP, p);
			int after = menu.getCarried().getCount();
			if (after >= before) break; // the slot is full or won't take it
			moved += before - after;
		}
		if (!menu.getCarried().isEmpty()) menu.clicked(from.index, 0, ContainerInput.PICKUP, p);
		if (!menu.getCarried().isEmpty()) {
			// The source slot refilled somehow; drop the rest into any free slot on the player's side.
			Slot spare = target(slots(menu, p, true), menu.getCarried());
			if (spare != null) menu.clicked(spare.index, 0, ContainerInput.PICKUP, p);
		}
		return moved;
	}

	/** Takes up to {@code n} matching items from the block's slots into the inventory. Returns how many. */
	public static int take(AbstractContainerMenu menu, Player p, List<Slot> from, Predicate<ItemStack> match, int n) {
		List<Slot> mine = slots(menu, p, true);
		int left = n;
		for (Slot s : from) {
			if (left <= 0) break;
			if (!s.hasItem() || !match.test(s.getItem()) || !s.mayPickup(p)) continue;
			if (s.getItem().getCount() <= left) {
				left -= quickMove(menu, p, s);
			} else {
				Slot to = target(mine, s.getItem());
				if (to == null) break;
				left -= move(menu, p, s, to, left);
			}
		}
		return n - left;
	}

	/**
	 * Puts up to {@code n} matching items from the inventory into the block's slots. Whole stacks are
	 * shift-clicked when {@code quick} (chests, barrels, hoppers); otherwise every item goes to a
	 * chosen slot, since a furnace's shift-click sorts items by what they are.
	 */
	public static int put(AbstractContainerMenu menu, Player p, List<Slot> into, Predicate<ItemStack> match, int n, boolean quick) {
		int left = n;
		for (Slot s : slots(menu, p, true)) {
			if (left <= 0) break;
			if (!s.hasItem() || !match.test(s.getItem())) continue;
			if (quick && s.getItem().getCount() <= left) {
				int moved = quickMove(menu, p, s);
				left -= moved;
				if (moved == 0) break; // full
				continue;
			}
			while (left > 0 && s.hasItem()) {
				Slot to = target(into, s.getItem());
				int moved = to == null ? 0 : move(menu, p, s, to, Math.min(left, s.getItem().getCount()));
				if (moved == 0) return n - left;
				left -= moved;
			}
		}
		return n - left;
	}

	// ---- reporting ------------------------------------------------------------------------------

	/** Item counts in the main inventory and hotbar. */
	public static Map<Item, Integer> counts(Player p) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
		return out;
	}

	/** "+8 oak_planks, -2 oak_log", or "no change". */
	public static String delta(Map<Item, Integer> before, Map<Item, Integer> after) {
		Set<Item> items = new LinkedHashSet<>(after.keySet());
		items.addAll(before.keySet());
		List<String> parts = new ArrayList<>();
		for (Item item : items) {
			int d = after.getOrDefault(item, 0) - before.getOrDefault(item, 0);
			if (d != 0) parts.add((d > 0 ? "+" : "") + d + " " + Recipes.name(item));
		}
		return parts.isEmpty() ? "no change" : String.join(", ", parts);
	}

	/** "12 oak_log, 3 cobblestone" for a set of slots, or "empty". */
	public static String contents(List<Slot> slots) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		for (Slot s : slots) if (s.hasItem()) counts.merge(s.getItem().getItem(), s.getItem().getCount(), Integer::sum);
		if (counts.isEmpty()) return "empty";
		List<String> parts = new ArrayList<>();
		counts.forEach((item, n) -> parts.add(n + " " + Recipes.name(item)));
		return String.join(", ", parts);
	}

	/** "Inventory: 3 oak_log, 1 chicken." */
	public static String inventory(Player p) {
		Map<Item, Integer> c = counts(p);
		if (c.isEmpty()) return "The inventory is empty.";
		List<String> parts = new ArrayList<>();
		c.forEach((item, n) -> parts.add(n + " " + Recipes.name(item)));
		return "Inventory: " + String.join(", ", parts) + ".";
	}

	public static String blockName(BlockState state) {
		return BlockCodec.shortId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
	}

	public static String xyz(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}
}
