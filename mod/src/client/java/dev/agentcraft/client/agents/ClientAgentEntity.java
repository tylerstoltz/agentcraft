package dev.agentcraft.client.agents;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.entity.AgentEntity;
import dev.agentcraft.entity.ModEntities;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The client-only agent NPC. Created and removed by {@link AgentManager}; never exists on the
 * server. It skips all of LivingEntity's physics/AI: each tick it only advances its
 * {@link AgentMotion}, updates the vanilla walk animation from the real distance moved, and runs
 * the {@link AgentHooks} tickers.
 */
public class ClientAgentEntity extends AgentEntity implements ClientAvatarEntity {
	private final ClientAvatarState avatarState = new ClientAvatarState();
	private final AgentView view;
	private final AgentMotion motion = new AgentMotion();
	private PlayerSkin skin;

	public ClientAgentEntity(ClientLevel level, String agentId, PlayerSkin skin) {
		super(ModEntities.AGENT, level);
		this.view = new AgentView(agentId);
		this.skin = skin;
	}

	public String agentId() {
		return view.id;
	}

	public AgentView view() {
		return view;
	}

	public AgentMotion motion() {
		return motion;
	}

	void setSkin(PlayerSkin skin) {
		this.skin = skin;
	}

	/** Teleport (no interpolation) to a feet position with a body yaw. */
	void snapTo(Vec3 p, float yaw) {
		this.snapTo(p.x, p.y, p.z, yaw, 0f);
		this.setOldPosAndRot();
		this.yBodyRot = yaw;
		this.yBodyRotO = yaw;
		this.yHeadRot = yaw;
		this.yHeadRotO = yaw;
		this.setDeltaMovement(Vec3.ZERO);
	}

	@Override
	public void tick() {
		// commonTick() already stored the previous position/rotation and counted the tick.
		this.yBodyRotO = this.yBodyRot;
		this.yHeadRotO = this.yHeadRot;
		Vec3 before = position();
		Vec3 after = motion.step(before);
		if (!after.equals(before)) {
			this.setPos(after);
		}
		float yaw = motion.yaw();
		this.setYRot(yaw);
		this.yBodyRot = yaw;
		this.yHeadRot = yaw;
		this.setDeltaMovement(after.subtract(before));
		view.pose = motion.walking() ? AgentPose.WALK : view.pose == AgentPose.WALK ? AgentPose.STAND : view.pose;
		this.calculateEntityAnimation(false);
		avatarState.tick(position(), getDeltaMovement());
		for (AgentHooks.Ticker t : AgentHooks.TICKERS) {
			try {
				t.tick(this);
			} catch (Throwable e) {
				AgentCraft.LOGGER.warn("agent ticker failed", e);
			}
		}
	}

	@Override
	public ClientAvatarState avatarState() {
		return avatarState;
	}

	@Override
	public PlayerSkin getSkin() {
		return skin;
	}

	@Override
	public Parrot.@Nullable Variant getParrotVariantOnShoulder(boolean left) {
		return null;
	}

	@Override
	public boolean showExtraEars() {
		return false;
	}
}
