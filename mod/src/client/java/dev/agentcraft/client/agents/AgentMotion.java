package dev.agentcraft.client.agents;

import dev.agentcraft.layout.Anchor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic walking for one client-side agent: follows a path (from {@link GridPathfinder}) to
 * its target anchor at a calm walking pace, turning smoothly, then turns to the anchor's yaw. No
 * physics, no collisions, no server: the position is a pure function of the path and time, so it is
 * identical every run (good for QA) and never jitters.
 */
public final class AgentMotion {
	/** Walking speed in blocks per tick (2.9 blocks/s: an unhurried office walk; players sprint 5.6). */
	public static final double SPEED = 0.145;
	/** Max body turn per tick, degrees. */
	public static final float TURN = 24f;

	private @Nullable Anchor target;
	private final List<Vec3> path = new ArrayList<>();
	private int next;
	private boolean walking;
	private float yaw;
	private boolean placed;
	private long teleports;

	public @Nullable Anchor target() {
		return target;
	}

	public boolean walking() {
		return walking;
	}

	public boolean placed() {
		return placed;
	}

	public float yaw() {
		return yaw;
	}

	public long teleports() {
		return teleports;
	}

	/** Remaining path points (for debugging). */
	public List<Vec3> remainingPath() {
		return next < path.size() ? List.copyOf(path.subList(next, path.size())) : List.of();
	}

	/** Put the agent at {@code a} right away (first appearance, no path, or a settle request). */
	public Vec3 placeAt(Anchor a) {
		target = a;
		path.clear();
		next = 0;
		walking = false;
		yaw = a.yaw();
		placed = true;
		teleports++;
		return a.pos();
	}

	/**
	 * Walk to {@code a} along {@code route} (feet positions; first = current position). A null or
	 * empty route means "no path": the caller should {@link #placeAt} instead.
	 */
	public void walkTo(Anchor a, List<Vec3> route) {
		target = a;
		path.clear();
		path.addAll(route);
		next = path.size() > 1 ? 1 : path.size();
		walking = next < path.size();
		placed = true;
	}

	/**
	 * Advance one tick from {@code pos}. Returns the new feet position; {@link #yaw()} is the new body
	 * yaw.
	 */
	public Vec3 step(Vec3 pos) {
		if (target == null) {
			return pos;
		}
		if (!walking) {
			yaw = approachAngle(yaw, target.yaw(), TURN * 0.6f);
			return pos;
		}
		double budget = SPEED;
		Vec3 p = pos;
		while (budget > 1e-6 && next < path.size()) {
			Vec3 goal = path.get(next);
			Vec3 d = goal.subtract(p);
			double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
			if (horiz > 1e-4) {
				float want = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
				float diff = Math.abs(Mth.wrapDegrees(want - yaw));
				yaw = approachAngle(yaw, want, TURN);
				if (diff > 100) {
					// turn on the spot first instead of walking backwards
					break;
				}
			}
			double dist = d.length();
			if (dist <= budget) {
				p = goal;
				budget -= dist;
				next++;
			} else {
				p = p.add(d.scale(budget / dist));
				budget = 0;
			}
		}
		if (next >= path.size()) {
			walking = false;
		}
		return p;
	}

	private static float approachAngle(float from, float to, float max) {
		float diff = Mth.wrapDegrees(to - from);
		if (Math.abs(diff) <= max) {
			return Mth.wrapDegrees(to);
		}
		return Mth.wrapDegrees(from + Math.signum(diff) * max);
	}
}
