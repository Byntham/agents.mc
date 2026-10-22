package dev.agentsmc.body;

import dev.agentsmc.AgentsMc;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.perception.EventLog;
import dev.agentsmc.perception.Senses;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Drives an agent's body the way a game client drives a player's: every tick it decides the movement
 * input, where to look and what to do with the hands. The model doesn't steer tick by tick; it starts
 * an {@link Intent} (walk there, follow them, mine that, fight it) and reflexes handle surprises.
 *
 * <p>Everything here runs on the server thread, from {@link AgentPlayer#tick()} just before the
 * player's physics tick, so input set here is what {@code travel} sees this tick.
 */
public final class Body {
	/** How fast the head turns, degrees per tick. A real player turns faster, but this reads as deliberate. */
	private static final float TURN_RATE = 15f;
	private static final int RECENT = 6;

	public final AgentPlayer agent;
	public final Senses senses;
	public final Reflexes reflexes;

	private Intent current, paused;
	private final Deque<Intent> recent = new ArrayDeque<>();
	private int nextId = 1;

	// Input for this tick. Cleared at the start of every tick; intents and reflexes set it.
	float forward, strafe;
	boolean jump, sprint, sneak;
	private boolean moving;
	private Vec3 lookTarget;
	private Entity lookEntity;
	private Vec3 lastPos;
	/** Blocks per second over the last tick, for the status line. */
	private double speed;
	// One delayed task (closing a container a moment after opening it, so the lid is seen to move).
	private Runnable later;
	private int laterTicks;

	public Body(AgentPlayer agent) {
		this.agent = agent;
		this.senses = new Senses(this);
		this.reflexes = new Reflexes(this);
	}

	// ---- per tick -------------------------------------------------------------------------------

	/** Called before the player's physics each tick. */
	public void tick() {
		forward = strafe = 0;
		jump = sprint = sneak = moving = false;
		lookTarget = null;
		lookEntity = null;
		if (lastPos == null) lastPos = agent.position();
		if (later != null && --laterTicks <= 0) {
			Runnable task = later;
			later = null;
			task.run();
		}

		senses.tick();
		// Drops nearby come to the body on their own: no chasing items under leaves or down holes.
		gatherDrops(agent.getBoundingBox().inflate(2.5));
		// A reflex that moves the body (fleeing a creeper) owns this tick's movement: an intent
		// steering toward its goal would otherwise cancel the step away.
		boolean reflexMoving = reflexes.tick();
		if (current != null && !reflexMoving) {
			current.run(this);
			if (current.finished()) finish(current);
		}
		if (current == null && paused != null) {
			current = paused;
			paused = null;
			event("intent", "Resuming " + current.tag() + " (" + current.describe() + ")", EventLog.QUIET);
		}

		applyLook();
		// Sprinting in water is swimming: faster, and the only way through a current.
		boolean canSprint = sprint && agent.getFoodData().getFoodLevel() > 6 && !agent.isUsingItem() && !sneak;
		if (agent.isSprinting() != canSprint) agent.setSprinting(canSprint);
		if (agent.isShiftKeyDown() != sneak) agent.setShiftKeyDown(sneak);

		// A client reports its own movement for stats and hunger; nobody does that for an agent.
		Vec3 pos = agent.position();
		Vec3 d = pos.subtract(lastPos);
		speed = d.horizontalDistance() * 20;
		if (d.lengthSqr() > 1e-6 && agent.isAlive()) agent.checkMovementStatistics(d.x, d.y, d.z);
		lastPos = pos;
	}

	/** Runs {@code task} on this body's tick after {@code ticks} ticks, replacing any task already waiting. Null cancels. */
	public void later(int ticks, Runnable task) {
		later = task;
		laterTicks = ticks;
	}

	/** Called from {@link AgentPlayer#applyInput()}: copies this tick's input into the entity. */
	public void applyInput() {
		agent.zza = forward;
		agent.xxa = strafe;
		agent.setJumping(jump);
	}

	// ---- intents --------------------------------------------------------------------------------

	/** Starts an intent, cancelling whatever the body was doing. Returns it with its id assigned. */
	public <T extends Intent> T start(T intent) {
		if (current != null) {
			current.cancel("replaced by " + intent.kind());
			finish(current);
		}
		if (paused != null) {
			paused.cancel("replaced by " + intent.kind());
			finish(paused);
		}
		intent.id = nextId++;
		current = intent;
		return intent;
	}

	/**
	 * A reflex takes over the body. The current intent is paused if it can be and resumes when the
	 * reflex is done, otherwise it is cancelled.
	 */
	void pushReflex(Intent intent) {
		if (current != null) {
			if (current.pausable() && paused == null) {
				paused = current;
			} else {
				current.cancel("interrupted by " + intent.kind());
				finish(current);
			}
		}
		intent.id = nextId++;
		current = intent;
	}

	/** Cancels the current (and any paused) intent. Returns what was cancelled. */
	public List<Intent> cancelAll(String reason) {
		List<Intent> out = new ArrayList<>();
		if (current != null) {
			current.cancel(reason);
			out.add(current);
			finish(current);
		}
		if (paused != null) {
			paused.cancel(reason);
			out.add(paused);
			finish(paused);
		}
		return out;
	}

	public boolean cancel(int id, String reason) {
		if (current != null && current.id == id) {
			current.cancel(reason);
			finish(current);
			return true;
		}
		if (paused != null && paused.id == id) {
			paused.cancel(reason);
			finish(paused);
			return true;
		}
		return false;
	}

	private void finish(Intent intent) {
		if (intent == current) current = null;
		if (intent == paused) paused = null;
		try {
			intent.stop(this);
		} catch (RuntimeException e) {
			AgentsMc.LOGGER.warn("Agents MC: {} failed to stop {}", name(), intent.tag(), e);
		}
		recent.addFirst(intent);
		while (recent.size() > RECENT) recent.removeLast();
		String verb = switch (intent.status()) {
			case DONE -> "done";
			case FAILED -> "failed";
			case CANCELLED -> "cancelled";
			case RUNNING -> "stopped";
		};
		int salience = intent.status() == Intent.Status.CANCELLED ? EventLog.NOTABLE : EventLog.WAKE;
		event("intent", intent.tag() + " " + verb + ": " + intent.outcome(), salience);
	}

	public Intent current() {
		return current;
	}

	public Intent paused() {
		return paused;
	}

	public List<Intent> recent() {
		return List.copyOf(recent);
	}

	/** One line on what the body is doing, for status lines. */
	public String doing() {
		if (current == null) return "idle";
		String s = current.toString();
		if (paused != null) s += " (then back to " + paused.tag() + ")";
		return s;
	}

	// ---- movement -------------------------------------------------------------------------------

	/**
	 * Pushes toward a point this tick at {@code speed} (0..1 of walking speed), relative to where the
	 * body currently faces. Unless something else sets a look target, the body turns to face the way
	 * it is going.
	 */
	public void moveToward(Vec3 target, double speed) {
		Vec3 d = new Vec3(target.x - agent.getX(), 0, target.z - agent.getZ());
		if (d.lengthSqr() < 1e-6) return;
		d = d.normalize().scale(Mth.clamp(speed, 0, 1));
		double yaw = Math.toRadians(agent.getYRot());
		Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		forward = (float) d.dot(fwd);
		strafe = (float) d.dot(left);
		moving = true;
		if (lookTarget == null && lookEntity == null) {
			// Face the way we go, eyes level.
			lookTarget = agent.getEyePosition().add(d.x * 4, 0, d.z * 4);
		}
	}

	/** Moves directly away from a point this tick. */
	public void moveAwayFrom(Vec3 from, double speed) {
		Vec3 d = agent.position().subtract(from).multiply(1, 0, 1);
		if (d.lengthSqr() < 1e-4) d = new Vec3(1, 0, 0);
		moveToward(agent.position().add(d.normalize().scale(4)), speed);
	}

	public void jump() {
		jump = true;
	}

	public void sprint(boolean on) {
		sprint = on;
	}

	public void sneak(boolean on) {
		sneak = on;
	}

	/** Look at a point this tick (overrides facing the direction of travel). */
	public void face(Vec3 target) {
		lookTarget = target;
		lookEntity = null;
	}

	/** Look at an entity's eyes this tick. */
	public void face(Entity entity) {
		lookEntity = entity;
		lookTarget = null;
	}

	private void applyLook() {
		Vec3 target = lookEntity != null ? lookEntity.getEyePosition() : lookTarget;
		if (target == null) return;
		Vec3 eye = agent.getEyePosition();
		Vec3 d = target.subtract(eye);
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		if (h < 1e-4 && Math.abs(d.y) < 1e-4) return;
		float wantYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float wantPitch = (float) Math.toDegrees(-Math.atan2(d.y, h));
		// While walking the head follows the path; snapping would look like a bot.
		float yaw = agent.getYRot() + Mth.clamp(Mth.wrapDegrees(wantYaw - agent.getYRot()), -TURN_RATE, TURN_RATE);
		float pitch = agent.getXRot() + Mth.clamp(wantPitch - agent.getXRot(), -TURN_RATE, TURN_RATE);
		agent.setYRot(yaw);
		agent.setYHeadRot(yaw);
		agent.setYBodyRot(yaw);
		agent.setXRot(Mth.clamp(pitch, -90, 90));
	}

	/** Whether the body is looking within {@code degrees} of a point. */
	public boolean facing(Vec3 target, double degrees) {
		Vec3 d = target.subtract(agent.getEyePosition());
		if (d.lengthSqr() < 1e-6) return true;
		double dot = d.normalize().dot(agent.getLookAngle());
		return dot >= Math.cos(Math.toRadians(degrees));
	}

	public boolean isMoving() {
		return moving;
	}

	public double speed() {
		return speed;
	}

	// ---- hands and inventory --------------------------------------------------------------------

	public ServerLevel level() {
		return agent.level();
	}

	public String name() {
		return agent.getGameProfile().name();
	}

	/**
	 * Puts the best item for a job in the main hand: the one with the highest score among all
	 * inventory slots (hotbar and main inventory). Returns false if nothing scores above zero.
	 */
	public boolean holdBest(ToDoubleFunction<ItemStack> score) {
		Inventory inv = agent.getInventory();
		int best = -1;
		double bestScore = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			double v = score.applyAsDouble(s);
			if (v > bestScore) {
				bestScore = v;
				best = i;
			}
		}
		if (best < 0) return false;
		hold(best);
		return true;
	}

	/** Puts the first matching item in the main hand. */
	public boolean hold(Predicate<ItemStack> match) {
		return holdBest(s -> match.test(s) ? 1 : 0);
	}

	/** Puts the item in inventory slot {@code slot} in the main hand, swapping it into the hotbar if needed. */
	public void hold(int slot) {
		Inventory inv = agent.getInventory();
		if (slot < Inventory.SELECTION_SIZE) {
			if (inv.getSelectedSlot() != slot) {
				agent.stopUsingItem();
				inv.setSelectedSlot(slot);
			}
			return;
		}
		int sel = inv.getSelectedSlot();
		ItemStack a = inv.getItem(sel);
		inv.setItem(sel, inv.getItem(slot));
		inv.setItem(slot, a);
		agent.stopUsingItem();
	}

	/** The tool in the inventory that breaks this block fastest, with the right tool preferred when drops need it. */
	public void holdToolFor(BlockState state) {
		holdBest(s -> toolScore(s, state));
	}

	/** How good an item is for breaking a block; zero means no better than bare hands. */
	public static double toolScore(ItemStack s, BlockState state) {
		double v = s.getDestroySpeed(state);
		if (state.requiresCorrectToolForDrops() && !s.isCorrectToolForDrops(state)) v *= 0.1;
		// Don't dig with the sword if anything else will do: it wears fast and digs slowly.
		if (s.has(DataComponents.WEAPON) && v <= 1.5) v *= 0.5;
		return v > 1 ? v : 0; // bare hands score zero, so a block with no tool stays in the current hand
	}

	/** The best melee weapon: swords and axes by their attack damage. */
	public void holdWeapon() {
		holdBest(s -> {
			if (!s.has(DataComponents.WEAPON)) return 0;
			double v = 1;
			var mods = s.get(DataComponents.ATTRIBUTE_MODIFIERS);
			if (mods != null) {
				for (var m : mods.modifiers()) {
					if (m.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) v += m.modifier().amount();
				}
			}
			return v;
		});
	}

	public boolean inReach(BlockPos pos) {
		return agent.isWithinBlockInteractionRange(pos, 1.0);
	}

	public boolean inReach(Entity e) {
		return agent.isWithinEntityInteractionRange(e, 1.0);
	}

	/**
	 * Where a ray from the eyes to the centre of {@code target}'s nearest face lands. Null if another
	 * block is in the way. Placement and mining look at the block the way a player does.
	 */
	public BlockHitResult sight(BlockPos target) {
		Vec3 eye = agent.getEyePosition();
		BlockState state = agent.level().getBlockState(target);
		net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(agent.level(), target);
		// Aim at the block's real shape, not a full cube: a door is three sixteenths thick and a ray at
		// the cube's face centre would pass in front of it. Clamped to the block's own cell: a fence or
		// gate's shape reaches up into the cell above, where a ray finds nothing to click.
		net.minecraft.world.phys.AABB cell = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1);
		net.minecraft.world.phys.AABB box = shape.isEmpty() ? cell : shape.bounds().intersect(cell);
		Vec3 centre = Vec3.atLowerCornerOf(target).add(box.getCenter());
		// Aim at the face that points back toward the eyes, so the hit lands on the block's surface.
		Direction face = Direction.getApproximateNearest(eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
		Vec3 n = Vec3.atLowerCornerOf(face.getUnitVec3i());
		Vec3 faceAim = centre.add(n.x * box.getXsize() / 2, n.y * box.getYsize() / 2, n.z * box.getZsize() / 2);
		// Only solid blocks block the view: short grass in the agent's own block must not stop it mining the block it stands on.
		// Two tries: the face centre, then the middle of the shape for the odd angle where the face is grazed.
		BlockHitResult hit = null;
		for (Vec3 aim : new Vec3[]{faceAim, centre}) {
			Vec3 to = eye.add(aim.subtract(eye).normalize().scale(agent.blockInteractionRange() + 1.5));
			hit = agent.level().clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, agent));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return hit;
		}
		// The target itself has no collision shape (grass, flowers, torches): it's in view if nothing solid is in front of it.
		if (shape.isEmpty()) {
			double toTarget = eye.distanceTo(faceAim);
			if (hit.getType() != HitResult.Type.BLOCK || eye.distanceTo(hit.getLocation()) > toTarget - 0.2) return new BlockHitResult(faceAim, face, target, false);
		}
		return null;
	}



	/** A hit result on the face of {@code against} that points at {@code free}, for placing a block there. */
	public BlockHitResult placementHit(BlockPos free, BlockPos against) {
		Direction face = Direction.getApproximateNearest(free.getX() - against.getX(), free.getY() - against.getY(), free.getZ() - against.getZ());
		Vec3 loc = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
		return new BlockHitResult(loc, face, against, false);
	}

	/**
	 * Whether the server lets this player touch a block there: spawn protection and the world border.
	 * A client's packets go through this check; the body's calls into the game mode skip it, so it is
	 * made here.
	 */
	public boolean mayInteract(BlockPos pos) {
		return agent.level().mayInteract(agent, pos);
	}

	/** Right-clicks a block with what is in the main hand. */
	public InteractionResult useOn(BlockHitResult hit) {
		if (!mayInteract(hit.getBlockPos())) return InteractionResult.FAIL;
		ItemStack stack = agent.getMainHandItem();
		InteractionResult result = agent.gameMode.useItemOn(agent, agent.level(), stack, InteractionHand.MAIN_HAND, hit);
		if (result.consumesAction()) agent.swing(InteractionHand.MAIN_HAND, stack.getInteractAnimation(), true);
		return result;
	}

	/** Starts, continues or ends breaking a block, like holding or releasing the mouse button. */
	public void breakAction(BlockPos pos, Direction face, ServerboundPlayerActionPacket.Action action) {
		if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && !mayInteract(pos)) return;
		agent.gameMode.handleBlockBreakAction(pos, action, face, agent.level().getMaxY() + 1, 0);
	}

	public void swingArm() {
		agent.swing(InteractionHand.MAIN_HAND, agent.getMainHandItem().getAttackAnimation(), true);
	}

	// ---- light ----------------------------------------------------------------------------------

	private BlockPos lastTorch;

	/**
	 * Puts a torch down where it's dark, a few blocks apart, as a player does while digging: tunnels
	 * stay lit, mobs don't spawn in them, and humans can follow them. Needs torches in the inventory.
	 */
	public void maybeLight() {
		BlockPos feet = agent.blockPosition();
		if (lastTorch != null && lastTorch.distSqr(feet) < 36) return;
		if (agent.level().getMaxLocalRawBrightness(feet) >= 8) return;
		if (!agent.getInventory().contains(s -> s.is(net.minecraft.world.item.Items.TORCH))) return;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos pos = feet.relative(d);
			BlockState at = agent.level().getBlockState(pos);
			if (!at.isAir() || !agent.level().getBlockState(pos.below()).isFaceSturdy(agent.level(), pos.below(), Direction.UP)) continue;
			if (placeFromInventory(pos, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), s -> s.is(net.minecraft.world.item.Items.TORCH)) == Placed.PLACED) {
				lastTorch = pos;
				return;
			}
		}
	}

	// ---- placing and picking up -----------------------------------------------------------------

	/** What came of trying to place a block. */
	public enum Placed {
		PLACED, NO_ITEM, BLOCKED, PROTECTED, FAILED
	}

	/**
	 * Puts {@code state} at {@code pos} out of the inventory, using up the items it costs (one, or two
	 * for a double slab, four for four candles) from a stack {@code item} accepts. The item's data goes
	 * with it, so a shulker box keeps its contents and a named chest its name, and the block gets its
	 * placement callback, which adds a door's upper half or a bed's head. A player needs a block to
	 * click and a clear view of it; the body only needs the item, which is the part that matters:
	 * what it builds, it gathered. Nothing is placed inside a player or a mob.
	 */
	public Placed placeFromInventory(BlockPos pos, BlockState state, Predicate<ItemStack> item) {
		var level = agent.level();
		if (!mayInteract(pos)) return Placed.PROTECTED;
		if (!level.isUnobstructed(state, pos, net.minecraft.world.phys.shapes.CollisionContext.empty())) return Placed.BLOCKED;
		// A door, bed or tall plant takes a second cell, which its placement callback writes without
		// looking. Check it here: free, allowed, and nobody standing in it.
		BlockPos second = secondCell(state, pos);
		if (second != null) {
			if (!mayInteract(second)) return Placed.PROTECTED;
			if (!level.getBlockState(second).canBeReplaced()) return Placed.FAILED;
			if (!level.isUnobstructed(state, second, net.minecraft.world.phys.shapes.CollisionContext.empty())) return Placed.BLOCKED;
		}
		int cost = agent.isCreative() ? 0 : itemsFor(state);
		var inv = agent.getInventory();
		int slot = -1;
		for (int i = 0; i < inv.getContainerSize() && slot < 0; i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && item.test(st) && st.getCount() >= Math.max(cost, 1)) slot = i;
		}
		if (slot < 0) return Placed.NO_ITEM;
		ItemStack stack = inv.getItem(slot);
		ItemStack used = stack.copyWithCount(1);
		if (!level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL)) return Placed.FAILED;
		BlockState placed = level.getBlockState(pos);
		net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
		if (be != null) {
			be.applyComponentsFromItemStack(used);
			be.setChanged();
		}
		// The callback adds the other half of a door or bed. A second half placed on its own (a plan
		// listing both) gets none: its first half's callback made it, or will.
		if (!isSecondHalf(placed)) placed.getBlock().setPlacedBy(level, pos, placed, agent, used);
		stack.shrink(cost);
		var sound = placed.getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
		face(Vec3.atCenterOf(pos));
		swingArm();
		return Placed.PLACED;
	}

	/** How many of its item a block state is worth: a double slab is two slabs, four candles are four. */
	public static int itemsFor(BlockState state) {
		if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)
				&& state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE) == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) return 2;
		for (var p : new net.minecraft.world.level.block.state.properties.IntegerProperty[]{
				net.minecraft.world.level.block.state.properties.BlockStateProperties.CANDLES,
				net.minecraft.world.level.block.state.properties.BlockStateProperties.PICKLES,
				net.minecraft.world.level.block.state.properties.BlockStateProperties.EGGS,
				net.minecraft.world.level.block.state.properties.BlockStateProperties.LAYERS,
				net.minecraft.world.level.block.state.properties.BlockStateProperties.FLOWER_AMOUNT,
				net.minecraft.world.level.block.state.properties.BlockStateProperties.SEGMENT_AMOUNT}) {
			if (state.hasProperty(p)) return state.getValue(p);
		}
		return 1;
	}

	/** Whether a state is the half of a two-block block that its first half's callback creates. */
	public static boolean isSecondHalf(BlockState state) {
		if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) return true;
		return state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
				&& state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD;
	}

	/** The cell a first half's callback writes (a door's upper half, a bed's head), or null for a one-block state. */
	public static BlockPos secondCell(BlockState state, BlockPos pos) {
		if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) return pos.above();
		if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART) && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
				&& state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT) {
			return pos.relative(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING));
		}
		return null;
	}

	/**
	 * Takes item drops inside {@code box} into the inventory, pickup delay or not. Drops near the body,
	 * and the drops of a block it just broke, come to it instead of being chased. What a player just
	 * dropped stays theirs for the moment, as vanilla's own delay makes it. At most sixteen a tick,
	 * so a spill is taken over a few ticks rather than stalling one.
	 */
	public void gatherDrops(net.minecraft.world.phys.AABB box) {
		if (agent.isSpectator() || !agent.isAlive()) return;
		int budget = 16;
		for (net.minecraft.world.entity.item.ItemEntity e : agent.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box, net.minecraft.world.entity.item.ItemEntity::isAlive)) {
			if (--budget < 0) break;
			ItemStack st = e.getItem();
			if (st.isEmpty() || e.getAge() >= 5990) continue; // /give's animation-only item is born one tick from despawning; never a real drop
			if (e.hasPickUpDelay() && e.getOwner() instanceof net.minecraft.world.entity.player.Player owner && owner != agent) continue;
			int before = st.getCount();
			agent.getInventory().add(st); // takes what fits, leaving the rest in the stack
			int took = before - st.getCount();
			if (took > 0) agent.take(e, took); // the pick-up animation for anyone watching
			if (st.isEmpty()) e.discard();
			else e.setItem(st);
		}
	}

	/**
	 * Breaks the block at {@code pos} at once, as if mined with the best tool carried, with its drops
	 * going into the inventory (what doesn't fit drops where the block was). Mining's own rules hold:
	 * no drops without the right tool, the tool wears, the body tires. For build jobs, which take
	 * blocks out at a distance after waiting out the break time.
	 */
	public void removeToInventory(BlockPos pos) {
		var level = agent.level();
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !mayInteract(pos)) return;
		holdToolFor(state);
		ItemStack tool = agent.getMainHandItem();
		List<ItemStack> drops = agent.hasCorrectToolForDrops(state)
				? net.minecraft.world.level.block.Block.getDrops(state, level, pos, level.getBlockEntity(pos), agent, tool) : List.of();
		face(Vec3.atCenterOf(pos));
		swingArm();
		if (!level.destroyBlock(pos, false, agent, 512)) return;
		for (ItemStack d : drops) {
			agent.getInventory().add(d);
			if (!d.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, pos, d);
		}
		gatherDrops(new net.minecraft.world.phys.AABB(pos).inflate(1.5)); // a chest's contents, say
		if (!agent.isCreative()) {
			tool.mineBlock(level, state, pos, agent);
			agent.causeFoodExhaustion(0.005f);
		}
	}

	// ---- events ---------------------------------------------------------------------------------

	/** Records something this body noticed or did, for this agent only. */
	public void event(String type, String text, int salience) {
		AgentsMc.events().sensed(name(), type, null, text, salience);
	}
}
