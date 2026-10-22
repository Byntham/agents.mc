package dev.agentsmc.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks blocks one at a time the way a player holds the mouse button: look at the block, hold the
 * best tool, start, add up {@code getDestroyProgress} every tick, stop at 1.0, then wait five ticks
 * before the next block. {@link MineIntent} and {@link Navigator} both break blocks through this.
 */
final class Digger {
	enum Result {
		/** Turning, waiting out the pause between blocks, or holding the button. Call again next tick. */
		BUSY,
		/** The block is gone. */
		BROKEN,
		/** The block can't be broken. */
		FAILED
	}

	/** Ticks between one block breaking and the next one starting, as for a player holding the button. */
	static final int BREAK_DELAY = 5;

	private final Body body;
	private BlockPos target;
	private BlockState started;
	private Direction face = Direction.UP;
	private float progress;
	private boolean breaking;
	/** The agent's tick count when the next block may be started. */
	private int readyAt;
	private int held, broken, noGain, stopTries;

	Digger(Body body) {
		this.body = body;
	}

	/**
	 * Works on breaking {@code pos} this tick, looking at {@code hit}. Call it every tick until it
	 * returns {@link Result#BROKEN} or {@link Result#FAILED}. Moving on to another block abandons the
	 * one in progress, as letting go of the button would.
	 */
	Result tick(BlockPos pos, BlockHitResult hit) {
		var level = body.level();
		if (breaking && !pos.equals(target)) abort();
		target = pos;
		BlockState state = level.getBlockState(pos);
		if (breaking && !state.is(started.getBlock())) {
			breaking = false; // it went some other way: fell, or someone else broke it
			return Result.BROKEN;
		}
		if (state.isAir()) return Result.BROKEN;
		if (state.getDestroySpeed(level, pos) < 0) return Result.FAILED;

		body.face(hit.getLocation());
		if (!breaking) {
			if (body.agent.tickCount < readyAt) return Result.BUSY;
			if (!body.mayInteract(pos) || body.agent.blockActionRestricted(level, pos, body.agent.gameMode.getGameModeForPlayer())) return Result.FAILED;
			if (!body.facing(hit.getLocation(), 15)) return Result.BUSY; // finish turning first, like a player would
			face = hit.getDirection();
			body.holdToolFor(state);
			started = state;
			body.breakAction(pos, face, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
			breaking = true;
			progress = 0;
			held = 0;
			noGain = 0;
			stopTries = 0;
			if (!level.getBlockState(pos).is(state.getBlock())) return finished(); // creative mode breaks instantly
		}
		float gain = state.getDestroyProgress(body.agent, level, pos);
		if (gain <= 0) {
			// Nothing happens however long we hold: the block can't be broken by this player here.
			if (++noGain > 20) {
				abort();
				return Result.FAILED;
			}
		} else {
			noGain = 0;
		}
		progress += gain;
		if (held++ % 4 == 0) body.swingArm();
		if (progress >= 1f) {
			body.breakAction(pos, face, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
			if (!level.getBlockState(pos).is(state.getBlock())) return finished();
			// The server's own progress estimate disagreed (e.g. the tool changed). Keep holding a
			// while longer, but not forever: the server may be refusing the break outright.
			if (++stopTries > 40) {
				abort();
				return Result.FAILED;
			}
			progress = 0.9f;
		}
		return Result.BUSY;
	}

	private Result finished() {
		breaking = false;
		broken++;
		readyAt = body.agent.tickCount + BREAK_DELAY;
		return Result.BROKEN;
	}

	/** Lets go of the button if a block is half broken. */
	void abort() {
		if (breaking && target != null) body.breakAction(target, face, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
		breaking = false;
		progress = 0;
	}

	boolean breaking() {
		return breaking;
	}

	/** How far through the current block, 0 to 1. */
	float progress() {
		return progress;
	}

	/** Blocks broken so far. */
	int broken() {
		return broken;
	}

	// ---- looking at blocks ----------------------------------------------------------------------

	/** Where to look to break {@code pos}: the visible face if there is one, else the face toward the eyes. */
	static BlockHitResult hitOn(Body body, BlockPos pos) {
		BlockHitResult hit = body.sight(pos);
		if (hit != null) return hit;
		Vec3 eye = body.agent.getEyePosition(), c = Vec3.atCenterOf(pos);
		Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
		return new BlockHitResult(c.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5)), face, pos, false);
	}

	/**
	 * The solid block between the eyes and {@code target}, along the same ray {@link Body#sight}
	 * uses. Null if nothing is in the way.
	 */
	static BlockPos blocker(Body body, BlockPos target) {
		var agent = body.agent;
		Vec3 eye = agent.getEyePosition(), c = Vec3.atCenterOf(target);
		Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
		Vec3 aim = c.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
		Vec3 to = eye.add(aim.subtract(eye).scale(1.05));
		BlockHitResult hit = agent.level().clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, agent));
		if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) return null;
		return hit.getBlockPos();
	}

	// ---- estimates ------------------------------------------------------------------------------

	/**
	 * Seconds to break a block with the best tool in the inventory, or with {@code extra} if that is
	 * better, including the pause before the next block. Negative if the block can't be broken. Goes
	 * by the tool's base speed and ignores Efficiency and Haste, so it errs on the slow side.
	 */
	static double seconds(Body body, BlockPos pos, BlockState state, ItemStack extra) {
		float hardness = state.getDestroySpeed(body.level(), pos);
		if (hardness < 0) return -1;
		ItemStack tool = bestTool(body, state, extra);
		boolean right = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		double ticks = hardness == 0 ? 1 : Math.max(1, Math.ceil(hardness * (right ? 30 : 100) / tool.getDestroySpeed(state)));
		return (ticks + BREAK_DELAY) / 20.0;
	}

	/** The item {@link Body#holdToolFor} would pick, or {@code extra} if that scores higher. */
	static ItemStack bestTool(Body body, BlockState state, ItemStack extra) {
		Inventory inv = body.agent.getInventory();
		ItemStack best = body.agent.getMainHandItem(); // what stays in hand when nothing beats bare hands
		double bestScore = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			double v = s.isEmpty() ? 0 : Body.toolScore(s, state);
			if (v > bestScore) {
				bestScore = v;
				best = s;
			}
		}
		if (extra != null && Body.toolScore(extra, state) > bestScore) best = extra;
		return best;
	}
}
