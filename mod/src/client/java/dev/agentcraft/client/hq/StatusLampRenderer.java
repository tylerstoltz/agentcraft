package dev.agentcraft.client.hq;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.StatusLampBlockEntity;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import dev.agentcraft.client.world.StationRenderState;
import dev.agentcraft.client.world.StationRenderer;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;

/**
 * Status lamp BER. The lamp colour itself is the block state (driven by {@link HqWorldDriver}); this
 * adds what a block state cannot:
 * <ul>
 *   <li>{@code waiting} lamps breathe (an additive clay glow over the lens, ~0.8 Hz), so "needs you"
 *       reads from across the hall even in daylight;</li>
 *   <li>the lamp bound to {@code goal:atrium} is the Goal Atrium's projector: it draws the
 *       hologram above itself (goal text, progress ring, task and agent counts) with a soft light
 *       beam from the lens up to the card.</li>
 * </ul>
 */
public class StatusLampRenderer extends StationRenderer<StatusLampBlockEntity, StatusLampRenderer.State> {
	public static final String ATRIUM_BINDING = "goal:atrium";
	public static final String DECISIONS_BINDING = "decisions";
	public static final String MERGE_BINDING = "merge";
	/** Height of the hologram card's centre above the lamp block's origin (blocks). */
	static final float HOLO_Y = 4.1f;
	/** Card size in kit pixels and its world scale (blocks per pixel = PX * K). */
	static final int CARD_W = 220;
	static final int CARD_H = 80;
	static final float K = 0.92f;
	static final int TEXT_W = 140;

	public static class State extends StationRenderState {
		public LampStatus status = LampStatus.OFF;
		public boolean hologram;
		public boolean stale;
		public boolean hasGoal;
		public double progress;
		public String statusWord = "";
		public int statusColor;
		public List<String> goalLines = List.of();
		public String percent = "";
		public String line1 = "";
		public String line2a = "";
		public String line2b = "";
		public int cardW = CARD_W;
		/** Waiting niche glow (lamps bound {@code decisions} / {@code merge}): face with air in front, frame half width (blocks). */
		public @Nullable Direction frameFace;
		public float frameHalf;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true; // the hologram floats well above its block
	}

	@Override
	public int getViewDistance() {
		return 96;
	}

	@Override
	protected void extractStation(StatusLampBlockEntity be, State s, float partialTicks) {
		s.status = be.getBlockState().getValue(StatusLampBlock.STATUS);
		s.hologram = ATRIUM_BINDING.equals(s.binding);
		ForemanState fs = Foreman.state();
		// offline: the lamps hold their last state, but nothing pretends you are needed right now
		s.stale = fs == null || fs.isStale();
		s.frameFace = null;
		if (s.status == LampStatus.WAITING && !s.stale && (DECISIONS_BINDING.equals(s.binding) || MERGE_BINDING.equals(s.binding))
			&& be.getLevel() != null) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				if (be.getLevel().getBlockState(be.getBlockPos().relative(d)).isAir()) {
					s.frameFace = d;
					break;
				}
			}
			s.frameHalf = DECISIONS_BINDING.equals(s.binding) ? 2.5f : 1.5f;
		}
		if (s.hologram) {
			extractHologram(s);
		}
	}

	private static void extractHologram(State s) {
		ForemanState st = Foreman.state();
		Font font = Minecraft.getInstance().font;
		Goal g = st == null ? null : st.goal();
		s.stale = st == null || st.isStale();
		s.hasGoal = g != null;
		int total = 0;
		int done = 0;
		int doing = 0;
		int review = 0;
		int working = 0;
		int onShift = 0;
		int waitingUser = 0;
		if (st != null) {
			for (Task t : st.tasks().values()) {
				if (t.status() == TaskStatus.CANCELLED || g != null && t.goalId() != null && !t.goalId().equals(g.id())) {
					continue;
				}
				total++;
				switch (t.status()) {
					case DONE -> done++;
					case DOING -> doing++;
					case REVIEW -> review++;
					default -> {
					}
				}
			}
			for (Agent a : st.agents().values()) {
				if (!a.isActive()) {
					continue;
				}
				onShift++;
				String fam = a.state().family();
				if (fam.equals("working") || fam.equals("thinking")) {
					working++;
				}
			}
			waitingUser = st.openDecisions().size();
		}
		if (g != null) {
			s.progress = Math.max(0, Math.min(1, g.progress()));
			s.percent = Math.round(s.progress * 100) + "%";
			s.statusWord = g.status().wire().toUpperCase(Locale.ROOT);
			String fam = switch (g.status()) {
				case PLANNING -> "thinking";
				case ACTIVE -> "working";
				case DONE -> "done";
				case FAILED -> "error";
				default -> "idle";
			};
			s.statusColor = UiStyle.status(fam);
			List<String> lines = TextUtil.wrapPlain(font, g.text(), TEXT_W);
			if (lines.size() > 2) {
				lines = List.of(lines.get(0), TextUtil.ellipsize(font, lines.get(1) + " " + lines.get(2), TEXT_W));
			}
			s.goalLines = List.copyOf(lines);
			s.line1 = done + " of " + total + " tasks done" + (doing > 0 ? "  ·  " + doing + " in progress" : "");
			s.line2a = plural(working, "agent") + " working" + (review > 0 ? "  ·  " + review + " in review" : "");
			s.line2b = waitingUser > 0 ? "  ·  " + waitingUser + " need" + (waitingUser == 1 ? "s" : "") + " you" : "";
		} else {
			s.progress = 0;
			s.percent = "–";
			s.statusWord = "NO GOAL";
			s.statusColor = UiStyle.status("idle");
			s.goalLines = List.of("What should the team", "work on?");
			s.line1 = "Press ` and type a goal";
			s.line2a = st == null || !st.hasData() ? "Foreman not connected" : plural(onShift, "agent") + " on shift";
			s.line2b = "";
		}
		if (s.stale && st != null && st.hasData()) {
			s.line2a = "Foreman offline · last known state";
			s.line2b = "";
		}
		int w = Math.max(font.width(s.line1), font.width(s.line2a) + font.width(s.line2b));
		for (String l : s.goalLines) {
			w = Math.max(w, font.width(l));
		}
		s.cardW = Math.max(CARD_W, 84 + w + 12);
	}

	static String plural(int n, String word) {
		return n + " " + word + (n == 1 ? "" : "s");
	}

	@Override
	public void submit(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (s.status == LampStatus.WAITING && !s.stale) {
			breathe(s, poseStack, collector);
		}
		if (s.frameFace != null) {
			frame(s, poseStack, collector);
		}
		if (s.hologram) {
			beam(s, poseStack, collector);
			card(s, poseStack, collector, camera);
		}
	}

	// ------------------------------------------------------------------ waiting glow

	private static void breathe(State s, PoseStack poseStack, SubmitNodeCollector collector) {
		float wave = 0.5f + 0.5f * (float) Math.sin(s.timeSeconds * Math.PI * 2 * 0.8);
		int a = (int) (40 + 150 * wave);
		int argb = (a << 24) | (UiStyle.CLAY & 0xFFFFFF);
		for (Direction d : Direction.Plane.HORIZONTAL) {
			poseStack.pushPose();
			toFace(poseStack, d, -0.004f, 16f);
			collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vc) -> {
				quad(pose, vc, 3, 3, 13, 13, argb, argb);
			});
			poseStack.popPose();
		}
	}

	/**
	 * A soft clay glow framing the niche around a waiting {@code decisions} / {@code merge} lamp, so
	 * "a decision is waiting" reads from across the room: a thin bright line with a halo fading
	 * outwards, breathing with the lamp. Drawn on the wall plane in front of the lamp, centred on it,
	 * from the floor (3 blocks below the lamp) to 3 blocks above its bottom.
	 */
	private static void frame(State s, PoseStack poseStack, SubmitNodeCollector collector) {
		float wave = 0.5f + 0.5f * (float) Math.sin(s.timeSeconds * Math.PI * 2 * 0.8);
		int core = ((int) (120 + 110 * wave) << 24) | (UiStyle.CLAY & 0xFFFFFF);
		int halo = ((int) (40 + 50 * wave) << 24) | (UiStyle.CLAY & 0xFFFFFF);
		int clear = UiStyle.CLAY & 0xFFFFFF;
		float half = s.frameHalf * 16f;
		float x0 = 8 - half;
		float x1 = 8 + half;
		float y0 = -32f;
		float y1 = 64f;
		float t = 1.5f; // core line
		float h = 7f; // halo reach
		poseStack.pushPose();
		toFace(poseStack, s.frameFace, -0.006f, 16f);
		collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vc) -> {
			// core lines
			quad(pose, vc, x0, y0, x1, y0 + t, core, core);
			quad(pose, vc, x0, y1 - t, x1, y1, core, core);
			quad(pose, vc, x0, y0, x0 + t, y1, core, core);
			quad(pose, vc, x1 - t, y0, x1, y1, core, core);
			// inner halo, fading towards the middle of the niche
			quad(pose, vc, x0 + t, y0 + t, x1 - t, y0 + t + h, halo, clear);
			quad(pose, vc, x0 + t, y1 - t - h, x1 - t, y1 - t, clear, halo);
			hquad(pose, vc, x0 + t, y0 + t, x0 + t + h, y1 - t, halo, clear);
			hquad(pose, vc, x1 - t - h, y0 + t, x1 - t, y1 - t, clear, halo);
		});
		poseStack.popPose();
	}

	/** A quad with a horizontal colour gradient (left -> right). */
	private static void hquad(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float x1, float y1, int left, int right) {
		vc.addVertex(pose, x0, y0, 0).setColor(left);
		vc.addVertex(pose, x0, y1, 0).setColor(left);
		vc.addVertex(pose, x1, y1, 0).setColor(right);
		vc.addVertex(pose, x1, y0, 0).setColor(right);
	}

	// ------------------------------------------------------------------ hologram

	/** Two crossed, upward-fading additive planes from the lens to the card. */
	private static void beam(State s, PoseStack poseStack, SubmitNodeCollector collector) {
		float bottom = 1.02f;
		float top = HOLO_Y - CARD_H / 2f * WorldUi.PX * K + 0.05f;
		float pulse = 0.85f + 0.15f * (float) Math.sin(s.timeSeconds * 1.3);
		int base = s.hasGoal ? s.statusColor : UiStyle.CREAM;
		int lo = ((int) (70 * pulse * (s.stale ? 0.4f : 1f)) << 24) | (base & 0xFFFFFF);
		int hi = base & 0xFFFFFF; // alpha 0
		collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vc) -> {
			float rb = 0.16f;
			float rt = 0.95f;
			// x plane and z plane, both windings so they show from either side
			for (int side = 0; side < 2; side++) {
				vc.addVertex(pose, 0.5f - rb, bottom, 0.5f).setColor(lo);
				vc.addVertex(pose, 0.5f + rb, bottom, 0.5f).setColor(lo);
				vc.addVertex(pose, 0.5f + rt, top, 0.5f).setColor(hi);
				vc.addVertex(pose, 0.5f - rt, top, 0.5f).setColor(hi);
				vc.addVertex(pose, 0.5f, bottom, 0.5f - rb).setColor(lo);
				vc.addVertex(pose, 0.5f, bottom, 0.5f + rb).setColor(lo);
				vc.addVertex(pose, 0.5f, top, 0.5f + rt).setColor(hi);
				vc.addVertex(pose, 0.5f, top, 0.5f - rt).setColor(hi);
				// reversed winding
				vc.addVertex(pose, 0.5f - rt, top, 0.5f).setColor(hi);
				vc.addVertex(pose, 0.5f + rt, top, 0.5f).setColor(hi);
				vc.addVertex(pose, 0.5f + rb, bottom, 0.5f).setColor(lo);
				vc.addVertex(pose, 0.5f - rb, bottom, 0.5f).setColor(lo);
				vc.addVertex(pose, 0.5f, top, 0.5f - rt).setColor(hi);
				vc.addVertex(pose, 0.5f, top, 0.5f + rt).setColor(hi);
				vc.addVertex(pose, 0.5f, bottom, 0.5f + rb).setColor(lo);
				vc.addVertex(pose, 0.5f, bottom, 0.5f - rb).setColor(lo);
				if (side == 0) {
					break;
				}
			}
		});
	}

	private static void card(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		Font font = Minecraft.getInstance().font;
		int light = WorldUi.uiLight();
		float bob = 0.04f * (float) Math.sin(s.timeSeconds * 0.9);
		poseStack.pushPose();
		WorldUi.billboard(poseStack, camera, 0.5, HOLO_Y + bob, 0.5);
		poseStack.scale(K, K, K);
		int cw = s.cardW;
		float x0 = -cw / 2f;
		float y0 = -CARD_H / 2f;
		WorldUi.submitNineSlice(poseStack, collector, WorldUi.Layer.SOLID, Kit.TOOLTIP, x0, y0, cw, CARD_H, 0xFFFFFFFF, light);
		// progress ring (2x) with the percentage inside
		float rx = x0 + 9;
		float ry = y0 + (CARD_H - 64) / 2f + 2;
		WorldUi.submitSprite(poseStack, collector, WorldUi.Layer.OVERLAY, Kit.progressRing(s.progress), rx, ry, 64, 64, 0xFFFFFFFF, light);
		int pw = font.width(s.percent);
		WorldUi.submitText(poseStack, collector, s.percent, rx + 32 - pw / 2f, ry + 28, UiStyle.CREAM, light);
		// text column
		float tx = x0 + 84;
		float ty = y0 + 9;
		int dim = s.stale ? 0x99 : 0xFF;
		WorldUi.submitText(poseStack, collector, "GOAL", tx, ty, UiStyle.withAlpha(UiStyle.BRASS, dim), light);
		WorldUi.submitText(poseStack, collector, s.statusWord, tx + font.width("GOAL") + 6, ty, UiStyle.withAlpha(s.statusColor, dim), light);
		float ly = ty + 13;
		for (String line : s.goalLines) {
			WorldUi.submitText(poseStack, collector, line, tx, ly, UiStyle.withAlpha(UiStyle.CREAM, dim), light);
			ly += 10;
		}
		float ry2 = y0 + CARD_H - 30;
		WorldUi.submitFill(poseStack, collector, tx, ry2 - 4, x0 + cw - 10, ry2 - 3, UiStyle.withAlpha(UiStyle.BRASS, 0x88), light);
		int muted = UiStyle.color("ink_ui.activity", 0xFFC4BDB2);
		WorldUi.submitText(poseStack, collector, s.line1, tx, ry2, UiStyle.withAlpha(muted, dim), light);
		WorldUi.submitText(poseStack, collector, s.line2a, tx, ry2 + 10, UiStyle.withAlpha(muted, dim), light);
		if (!s.line2b.isEmpty()) {
			WorldUi.submitText(poseStack, collector, s.line2b, tx + font.width(s.line2a), ry2 + 10, UiStyle.withAlpha(UiStyle.CLAY, dim), light);
		}
		poseStack.popPose();
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float x1, float y1, int top, int bottom) {
		vc.addVertex(pose, x0, y0, 0).setColor(top);
		vc.addVertex(pose, x0, y1, 0).setColor(bottom);
		vc.addVertex(pose, x1, y1, 0).setColor(bottom);
		vc.addVertex(pose, x1, y0, 0).setColor(top);
	}
}
