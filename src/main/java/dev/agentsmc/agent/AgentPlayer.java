package dev.agentsmc.agent;

import com.mojang.authlib.GameProfile;
import dev.agentsmc.body.Body;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A server-side player driven by an AI agent instead of a game client. Humans see it as a normal
 * player: it has a name tag, shows in the tab list, takes damage, and is saved with the world.
 *
 * <p>It plays survival like a player, with the chores taken out. An agent has no hands and eyes of
 * its own, so the fiddly parts of the game that a human handles without thinking are relaxed: it
 * reaches a little further, it doesn't drown or take fall damage, currents don't push it, it mines
 * at full speed under water and in the air, nearby drops come straight to it, and it keeps its
 * inventory when it dies. What it builds and crafts still comes from what it gathered.
 */
public final class AgentPlayer extends ServerPlayer {
	/** How far the body can reach a block to mine or place it, a bit past a player's 4.5. */
	public static final double BLOCK_REACH = 6.0;
	/** How far it can hit or use an entity, a bit past a player's 3. */
	public static final double ENTITY_REACH = 4.0;

	private final AgentManager manager;
	private final Body body;

	AgentPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, AgentManager manager) {
		super(server, level, profile, ClientInformation.createDefault());
		this.manager = manager;
		this.body = new Body(this);
		// Show all skin layers (hat, jacket, sleeves...), like a client with default settings would.
		this.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
		applyPerks();
	}

	/** The controller that moves this body and watches the world for it. */
	public Body body() {
		return body;
	}

	/**
	 * The standing advantages of an agent body, as attribute base values. Called again after a saved
	 * body is loaded, since the save carries the attributes it was written with.
	 */
	public void applyPerks() {
		getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(BLOCK_REACH);
		getAttribute(Attributes.ENTITY_INTERACTION_RANGE).setBaseValue(ENTITY_REACH);
		getAttribute(Attributes.SUBMERGED_MINING_SPEED).setBaseValue(1.0); // no slowdown with the eyes under water
	}

	@Override
	public void tick() {
		// A real client's packets would drive movement physics (doTick) and chunk tracking; do it here.
		if (level().getServer().getTickCount() % 10 == 0) {
			connection.resetPosition();
			level().getChunkSource().move(this);
		}
		// A client confirms a dimension change; until then vanilla keeps the player invulnerable.
		if (isChangingDimension()) hasChangedDimension();
		// Decide this tick's input before the physics tick reads it.
		if (isAlive()) body.tick();
		super.tick();
		doTick();
	}

	@Override
	protected void applyInput() {
		// A client would have sent movement packets; the body controller supplies the input instead.
		body.applyInput();
		super.applyInput();
	}

	@Override
	public boolean isClientAuthoritative() {
		// Players' movement is normally owned by their client, so the server skips things like fall
		// damage. An agent has no client: the server is the authority.
		return false;
	}

	@Override
	public boolean isPushedByFluid() {
		// A current that a player wades through with a few key presses pins a body in place.
		return false;
	}

	@Override
	public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
		// Staircases, holes and cliffs are the body's daily work; a misjudged drop shouldn't kill it.
		resetFallDistance();
		return false;
	}

	@Override
	public float getDestroySpeed(BlockState state) {
		float speed = super.getDestroySpeed(state);
		// Vanilla breaks five times slower with the feet off the ground. The body often digs from a
		// swim or a jump; let it dig at full speed there, as it does under water.
		if (!onGround()) speed *= 5;
		return speed;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.is(DamageTypes.DROWN)) return false; // it can stay under as long as the job takes
		boolean hurt = super.hurtServer(level, source, amount);
		if (hurt && amount > 0 && isAlive()) body.senses.onHurt(source, amount);
		return hurt;
	}

	@Override
	public void onEquippedItemBroken(ItemStack stack, EquipmentSlot slot) {
		super.onEquippedItemBroken(stack, slot);
		// A player hears the snap and sees the empty hand; an agent needs telling, or it keeps "mining" with its fist.
		body.event("tool", "Your " + dev.agentsmc.perception.Views.stack(stack.copyWithCount(1)) + " broke.", dev.agentsmc.perception.EventLog.WAKE);
	}

	@Override
	protected void dropEquipment(ServerLevel level) {
		// Nothing is dropped on death: the inventory goes to the respawned body (AgentManager.spawn).
		// Losing a full inventory is the worst setback an agent can have, and it can't run back for it.
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		manager.onDeath(this);
	}
}
