package dev.agentcraft.client.agents;

import dev.agentcraft.client.ui.WorldUi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Nameplate declutter: makes sure agent plates never cover each other on screen. Runs once per
 * frame on the client thread at the end of level extraction (Fabric
 * {@code LevelExtractionEvents.END_EXTRACTION}), after every agent's {@link AgentRenderState} was
 * extracted and before anything is submitted, and writes the result into those states.
 *
 * <ol>
 *   <li>Project every visible plate (in front of the camera, line of sight to the plate or head) to
 *       a screen rectangle. Plates scale exactly with 1/depth because they are camera-facing.</li>
 *   <li>Low-value plates (idle, done, off shift, Foreman offline: {@link AgentView#plateWeight()}
 *       &le; 2) collapse to the name-only pill when their full plate would overlap another plate.
 *       They expand again only after the full plate has had room (plus a margin) for a moment, so
 *       they don't flicker. The plate under the crosshair (or whose agent is targeted) always shows
 *       in full.</li>
 *   <li>Plates are placed in priority order (crosshair, waiting, error, working/thinking, then all
 *       low-value plates as one tier; within a tier nearest first, with a bonus for plates that
 *       already sit at their natural spot). Each takes the lowest lift above its agent's head that clears every plate placed
 *       before it. Lifts animate (fast up, calm down; a camera cut snaps); a lifted plate draws a
 *       leader line to its head. A crowd taller than {@value #MAX_LIFT_PLATES} plates hides its
 *       low-value plates; plates nearer than {@value #HIDE_NEARER} blocks are hidden.</li>
 *   <li>Every plate gets a small depth nudge by rank ({@link WorldUi#billboard} homothety: same look,
 *       nearer depth), so while two plates still overlap (mid-animation, same distance) the
 *       higher-ranked one hides the other completely. Plates are opaque ({@link WorldUi.Layer#SOLID}),
 *       so a covered plate's text can't ghost through.</li>
 * </ol>
 */
public final class PlateLayout {
	/** Screen pixels kept free between two plates. */
	private static final float GAP = 2f;
	/** Extra free screen pixels a compact plate needs around its full size before it expands again. */
	private static final float HYSTERESIS = 8f;
	/** Seconds a compact plate must have had room before it expands. */
	private static final float EXPAND_DELAY = 0.35f;
	/** Lift animation rates (1/s, exponential): rise fast to get out of the way, settle back down calmly. */
	private static final float RISE_RATE = 30f;
	private static final float FALL_RATE = 14f;
	/** Target changes up to this many plate px per frame are followed directly (no animation lag). */
	private static final float MAX_DRIFT_PER_FRAME = 4f;
	/** Depth nudge per rank, as a fraction of the camera distance (~1000x the polygon offset of text). */
	public static final float NUDGE_PER_RANK = 0.0015f;
	private static final int MAX_RANKS = 24;
	/** A plate already at its natural spot sorts as if it were this much nearer (blocks), so plates don't swap. */
	private static final float STICKY_DEPTH = 0.75f;
	/** Lifts are capped at this many full plate heights; a low-value plate that would need more is hidden. */
	private static final float MAX_LIFT_PLATES = 8f;
	/** Plates nearer to the camera than this (blocks) are hidden: the agent is in your face and the plate would fill the screen. */
	public static final float HIDE_NEARER = 1.3f;
	/** A camera jump (blocks / degrees in one frame) counts as a cut: plates snap instead of animating. */
	private static final double CUT_DISTANCE = 1.5;
	private static final float CUT_DEGREES = 25f;

	/** Per-agent layout memory across frames. */
	static final class Track {
		long frame = Long.MIN_VALUE;
		float lift;
		float targetLift;
		float prevTarget;
		boolean compact;
		float freeFor;
		boolean grounded = true;
		int rank = -1;
		boolean focused;
		boolean hasRect;
		float rx0, ry0, rx1, ry1;
		long visTick = Long.MIN_VALUE;
		boolean visible = true;
		float nudge;
		float scale = 1f;
		float depth;
		int weight;
		boolean capped;

		/** Not laid out this frame (hidden, behind a wall): forget the lift, take no space. */
		void clear() {
			hasRect = false;
			lift = 0;
			targetLift = 0;
			grounded = true;
			rank = -1;
		}
	}

	private static final class Item {
		AgentRenderState s;
		Track t;
		float sx, sy, k, depth, sortKey;
		int weight;
		boolean focused, compactable, compact, fresh, hidden;
		float x0, y0, x1, y1;

		/** Width of the full plate or of what stacks on it (speech bubble), whichever is wider (px). */
		float fullW() {
			return Math.max(s.plateFull.width(), s.stackWidth);
		}

		float fullX0() {
			return sx - fullW() * k / 2f;
		}

		float fullX1() {
			return sx + fullW() * k / 2f;
		}

		float fullY0() {
			return sy - (s.plateFull.height() + s.stackHeight) * k;
		}
	}

	private static final Map<String, Track> TRACKS = new HashMap<>();
	private static final List<Item> ITEMS = new ArrayList<>();
	/** Reused items (the layout runs every frame: no allocation once warmed up). */
	private static final List<Item> POOL = new ArrayList<>();
	private static final Comparator<Item> PRIORITY = Comparator.<Item>comparingInt(i -> -i.weight).thenComparingDouble(i -> i.sortKey)
		.thenComparing(i -> i.s.agentId);
	private static final Vector4f V = new Vector4f();
	private static float[] candidates = new float[16];
	private static long lastNanos;
	private static long frame;
	private static boolean snapNext;
	private static int overlaps;
	private static int laidOut;
	private static float layoutMicros;
	private static @Nullable Vec3 lastCamPos;
	private static float lastCamYaw;
	private static float lastCamPitch;

	private PlateLayout() {
	}

	private static Item item(int index) {
		while (POOL.size() <= index) {
			POOL.add(new Item());
		}
		return POOL.get(index);
	}

	/** Skip the lift animation and the expand delay once (QA: settle before a screenshot). */
	public static void snapNextFrame() {
		snapNext = true;
	}

	/** Pairs of drawn plates that overlapped on screen in the last frame (QA; 0 when settled). */
	public static int overlaps() {
		return overlaps;
	}

	/** Plates that took part in the last layout (visible ones). */
	public static int laidOut() {
		return laidOut;
	}

	/** The last layout of one agent's plate, or null if it was not laid out (QA). */
	static @Nullable Track track(String agentId) {
		Track t = TRACKS.get(agentId);
		return t == null || t.frame != frame || !t.hasRect ? null : t;
	}

	/** Mean cost of one layout pass in microseconds (exponential average; QA). */
	public static float layoutMicros() {
		return layoutMicros;
	}

	/** Client thread, once per frame, after entity extraction. */
	public static void layout(LevelRenderState level) {
		long start = System.nanoTime();
		run(level, start);
		layoutMicros += ((System.nanoTime() - start) / 1000f - layoutMicros) * 0.05f;
	}

	private static void run(LevelRenderState level, long now) {
		Minecraft mc = Minecraft.getInstance();
		float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		frame++;
		boolean snap = snapNext;
		snapNext = false;
		ITEMS.clear();
		overlaps = 0;
		laidOut = 0;
		ClientLevel lvl = mc.level;
		int w = mc.getWindow().getWidth();
		int h = mc.getWindow().getHeight();
		CameraRenderState cam = level.cameraRenderState;
		if (lvl == null || w <= 0 || h <= 0 || !cam.initialized) {
			return;
		}
		float f = cam.projectionMatrix.m11() * h * 0.5f;
		if (lastCamPos == null || lastCamPos.distanceToSqr(cam.pos) > CUT_DISTANCE * CUT_DISTANCE
			|| Math.abs(Mth.wrapDegrees(cam.yRot - lastCamYaw)) > CUT_DEGREES || Math.abs(cam.xRot - lastCamPitch) > CUT_DEGREES) {
			snap = true; // camera cut (dev.camera, teleport): lay out from scratch, no slide from the old view
		}
		lastCamPos = cam.pos;
		lastCamYaw = cam.yRot;
		lastCamPitch = cam.xRot;
		long tick = lvl.getGameTime();
		boolean screenOpen = mc.gui.screen() != null;
		BlockPos camBlock = BlockPos.containing(cam.pos);
		boolean camInSolid = lvl.getBlockState(camBlock).isSolidRender();

		for (EntityRenderState es : level.entityRenderStates) {
			if (!(es instanceof AgentRenderState s) || s.plateFull == null || s.plateCompact == null) {
				continue;
			}
			Track t = TRACKS.computeIfAbsent(s.agentId, id -> new Track());
			boolean fresh = t.frame != frame - 1;
			t.frame = frame;
			V.set((float) (s.x - cam.pos.x), (float) (s.y + s.plateBase - cam.pos.y), (float) (s.z - cam.pos.z), 1f);
			cam.viewRotationMatrix.transform(V);
			float depth = -V.z;
			if (depth < HIDE_NEARER) {
				// behind the camera, or so close the plate would fill the screen
				s.plate = null;
				t.clear();
				continue;
			}
			if (!visible(lvl, cam.pos, camBlock, camInSolid, s, t, tick)) {
				// hidden behind walls: drawn as is (depth-tested), takes no space
				t.clear();
				continue;
			}
			cam.projectionMatrix.transform(V);
			if (V.w <= 1e-6f) {
				continue;
			}
			Item it = item(ITEMS.size());
			it.s = s;
			it.compact = false;
			it.hidden = false;
			it.sortKey = 0;
			it.t = t;
			it.fresh = fresh;
			it.depth = depth;
			it.sx = (V.x / V.w * 0.5f + 0.5f) * w;
			it.sy = (0.5f - V.y / V.w * 0.5f) * h;
			it.k = WorldUi.PX * s.plateScale * f / depth;
			boolean underCrosshair = !screenOpen && t.hasRect && w * 0.5f >= t.rx0 && w * 0.5f <= t.rx1 && h * 0.5f >= t.ry0 && h * 0.5f <= t.ry1;
			it.focused = s.plateCrosshair || underCrosshair;
			// low-value plates (idle, done, off shift, offline) form one tier: among them the nearest wins
			it.weight = it.focused ? 100 : s.plateWeight <= 2 ? 0 : s.plateWeight;
			it.compactable = !it.focused && s.plateWeight <= 2 && s.plateCompact != s.plateFull;
			ITEMS.add(it);
		}
		int n = ITEMS.size();
		laidOut = n;
		if (n == 0) {
			return;
		}

		// 1. collapse low-value plates whose full plate would overlap another plate
		for (Item a : ITEMS) {
			Track t = a.t;
			if (!a.compactable) {
				a.compact = false;
			} else {
				boolean blocked = false;
				boolean crowded = false;
				for (Item b : ITEMS) {
					if (b == a) {
						continue;
					}
					blocked |= overlap(a.fullX0(), a.fullY0(), a.fullX1(), a.sy, b.fullX0(), b.fullY0(), b.fullX1(), b.sy, GAP);
					crowded |= overlap(a.fullX0(), a.fullY0(), a.fullX1(), a.sy, b.fullX0(), b.fullY0(), b.fullX1(), b.sy, GAP + HYSTERESIS);
				}
				if (blocked || (t.compact && crowded)) {
					t.freeFor = 0;
					a.compact = true;
				} else if (t.compact && !snap && !a.fresh) {
					t.freeFor += dt;
					a.compact = t.freeFor < EXPAND_DELAY;
				} else {
					a.compact = false;
				}
			}
			t.compact = a.compact;
			t.focused = a.focused;
			a.sortKey = a.depth - (t.grounded ? STICKY_DEPTH : 0f);
		}

		// 2. place in priority order, each at the lowest free lift
		ITEMS.sort(PRIORITY);
		int ranks = Math.min(n, MAX_RANKS);
		for (int i = 0; i < n; i++) {
			Item a = ITEMS.get(i);
			AgentRenderState s = a.s;
			Track t = a.t;
			Nameplate.Data d = a.compact ? s.plateCompact : s.plateFull;
			// the plate plus whatever stacks on it (speech bubble, "!" marker) is one block of space
			float pw = Math.max(d.width(), s.stackWidth) * a.k;
			float ph = (d.height() + s.stackHeight) * a.k;
			a.x0 = a.sx - pw / 2f;
			a.x1 = a.sx + pw / 2f;
			a.y1 = a.sy;
			a.y0 = a.sy - ph;
			float free = findLift(a, i);
			float lift = Math.min(free, MAX_LIFT_PLATES * s.plateFull.height() * a.k);
			t.capped = free > lift;
			if (t.capped && a.weight == 0) {
				// a crowd taller than the cap: the least important plates step out instead of overlapping
				a.hidden = true;
				s.plate = null;
				t.clear();
				continue;
			}
			a.y0 -= lift;
			a.y1 -= lift;
			t.prevTarget = t.targetLift;
			t.targetLift = lift / a.k;
			t.grounded = lift < 0.5f;
			if (a.fresh || snap || crosses(a, i, t.lift * a.k, lift, ph)) {
				t.lift = t.targetLift;
			} else {
				// feed-forward: follow a target that drifts a little each frame (a plate growing as its agent
				// walks closer) without lag; only real jumps animate
				float drift = t.targetLift - t.prevTarget;
				if (Math.abs(drift) < MAX_DRIFT_PER_FRAME) {
					t.lift += drift;
				}
				float rate = t.targetLift > t.lift ? RISE_RATE : FALL_RATE;
				t.lift += (t.targetLift - t.lift) * (1f - (float) Math.exp(-dt * rate));
				if (Math.abs(t.targetLift - t.lift) < 0.75f) {
					t.lift = t.targetLift;
				}
				t.lift = Math.max(0f, t.lift);
			}
			t.rank = i;
			t.nudge = NUDGE_PER_RANK * Math.max(0, ranks - 1 - i);
			t.scale = s.plateScale;
			t.depth = a.depth;
			t.weight = a.weight;
			s.plate = d;
			s.plateLift = t.lift;
			s.plateNudge = t.nudge;
			// the rectangle as drawn this frame (current, animated lift)
			float drawn = t.lift * a.k;
			t.rx0 = a.x0;
			t.rx1 = a.x1;
			t.ry0 = a.sy - ph - drawn;
			t.ry1 = a.sy - drawn;
			t.hasRect = true;
		}
		int shown = 0;
		for (int i = 0; i < n; i++) {
			if (ITEMS.get(i).hidden) {
				continue;
			}
			shown++;
			Track p = ITEMS.get(i).t;
			for (int j = i + 1; j < n; j++) {
				if (ITEMS.get(j).hidden) {
					continue;
				}
				Track q = ITEMS.get(j).t;
				if (overlap(p.rx0, p.ry0, p.rx1, p.ry1, q.rx0, q.ry0, q.rx1, q.ry1, -0.5f)) {
					overlaps++;
				}
			}
		}
		laidOut = shown;
		if ((frame & 1023) == 0) {
			TRACKS.values().removeIf(t -> frame - t.frame > 1024);
		}
	}

	/**
	 * Would sliding item {@code i} from lift {@code from} to lift {@code to} (screen px) carry it right
	 * across a plate placed before it (from above it to below it, or the reverse)? Then it jumps
	 * instead: swapping places through another plate reads worse than a quick cut. Moving up out of
	 * a plate's way (the usual case) still slides, behind the plate that took its spot.
	 */
	private static boolean crosses(Item a, int placed, float from, float to, float ph) {
		if (Math.abs(to - from) < 1f) {
			return false;
		}
		float curY0 = a.sy - ph - from;
		float curY1 = a.sy - from;
		float tgtY0 = a.sy - ph - to;
		float tgtY1 = a.sy - to;
		for (int j = 0; j < placed; j++) {
			Item p = ITEMS.get(j);
			if (p.hidden || !(a.x0 < p.x1 && a.x1 > p.x0)) {
				continue;
			}
			boolean curAbove = curY1 <= p.y0 + 1f;
			boolean curBelow = curY0 >= p.y1 - 1f;
			boolean tgtAbove = tgtY1 <= p.y0 + 1f;
			boolean tgtBelow = tgtY0 >= p.y1 - 1f;
			if (curAbove && tgtBelow || curBelow && tgtAbove) {
				return true;
			}
		}
		return false;
	}

	/** Lowest lift (screen px, >= 0) at which item {@code i} clears the {@code i} items placed before it. */
	private static float findLift(Item a, int placed) {
		if (candidates.length < placed + 1) {
			candidates = new float[placed + 8];
		}
		int m = 0;
		candidates[m++] = 0f;
		for (int j = 0; j < placed; j++) {
			Item p = ITEMS.get(j);
			if (!p.hidden && a.x0 < p.x1 + GAP && a.x1 > p.x0 - GAP) {
				float l = a.y1 - (p.y0 - GAP);
				if (l > 0) {
					candidates[m++] = l;
				}
			}
		}
		java.util.Arrays.sort(candidates, 0, m);
		for (int c = 0; c < m; c++) {
			float l = candidates[c];
			if (free(a.x0, a.y0 - l, a.x1, a.y1 - l, placed)) {
				return l;
			}
		}
		return candidates[m - 1];
	}

	private static boolean free(float x0, float y0, float x1, float y1, int placed) {
		for (int j = 0; j < placed; j++) {
			Item p = ITEMS.get(j);
			if (!p.hidden && overlap(x0, y0, x1, y1, p.x0, p.y0, p.x1, p.y1, GAP)) {
				return false;
			}
		}
		return true;
	}

	/** Do the rectangles come closer than {@code gap} on both axes (negative gap = must really overlap)? */
	private static boolean overlap(float ax0, float ay0, float ax1, float ay1, float bx0, float by0, float bx1, float by1, float gap) {
		return ax0 < bx1 + gap && ax1 > bx0 - gap && ay0 < by1 + gap && ay1 > by0 - gap;
	}

	/** Line of sight from the camera to the plate or the head, cached per game tick. */
	private static boolean visible(ClientLevel level, Vec3 cam, BlockPos camBlock, boolean camInSolid, AgentRenderState s, Track t, long tick) {
		if (t.visTick == tick) {
			return t.visible;
		}
		t.visTick = tick;
		t.visible = clear(level, cam, camBlock, camInSolid, new Vec3(s.x, s.y + s.plateBase + 0.2, s.z))
			|| clear(level, cam, camBlock, camInSolid, new Vec3(s.x, s.y + 1.6, s.z));
		return t.visible;
	}

	/**
	 * Is nothing solid between {@code target} and the camera? Cast from the target towards the camera.
	 * A spectator camera inside a wall sees through the wall it stands in (vanilla culls it), so then
	 * hits within 1.5 blocks of the camera don't count; the camera's own block never counts.
	 */
	private static boolean clear(ClientLevel level, Vec3 cam, BlockPos camBlock, boolean camInSolid, Vec3 target) {
		BlockHitResult hit = level.clip(new ClipContext(target, cam, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(camBlock)
			|| camInSolid && hit.getLocation().distanceToSqr(cam) < 1.5 * 1.5;
	}
}
