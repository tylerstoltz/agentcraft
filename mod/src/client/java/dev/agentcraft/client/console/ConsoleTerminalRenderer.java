package dev.agentcraft.client.console;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.agentcraft.block.entity.ConsoleTerminalBlockEntity;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import dev.agentcraft.client.world.StationRenderState;
import dev.agentcraft.client.world.StationRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Console terminal BER: the leaning paper screen shows a live prompt (brass {@code >} + blinking
 * caret + the last command you sent), how to open the console, and what is waiting for you.
 * Drawn full-bright over the screen face (model: 12x7 px plane at z 9.5/16, tilted 22.5 degrees back
 * about (8, 9, 11)).
 */
public class ConsoleTerminalRenderer extends StationRenderer<ConsoleTerminalBlockEntity, ConsoleTerminalRenderer.State> {
	private static final float PPB = 96f;
	/** Screen size in text px (12 x 7 model px at 96 px per block). */
	private static final int SW = 72;
	private static final int SH = 42;

	public static class State extends StationRenderState {
		public String last = "";
		public int waiting;
		public boolean stale;
		public boolean live;
		public String key = "`";
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	/** A flat opaque rectangle in the colour {@code argb}: the bubble sprite's centre texel, tinted, on the solid layer. */
	private static void solidRect(PoseStack poseStack, SubmitNodeCollector collector, float x0, float y0, float x1, float y1, int argb, int light) {
		TextureAtlasSprite sp = WorldUi.sprite(Kit.BUBBLE);
		float u = sp.getU(0.5f);
		float v = sp.getV(0.5f);
		int tint = tintFor(argb, 0xFFFBF4);
		collector.order(0).submitCustomGeometry(poseStack, WorldUi.guiAtlasSolid(), (pose, vc) -> WorldUi.quad(pose, vc, x0, y0, x1, y1, u, v, u, v, tint,
			light));
	}

	/** The tint that turns {@code base} (0xRRGGBB) into {@code want} when multiplied. */
	private static int tintFor(int want, int base) {
		int r = Math.min(255, ((want >> 16) & 0xFF) * 255 / Math.max(1, (base >> 16) & 0xFF));
		int g = Math.min(255, ((want >> 8) & 0xFF) * 255 / Math.max(1, (base >> 8) & 0xFF));
		int b = Math.min(255, (want & 0xFF) * 255 / Math.max(1, base & 0xFF));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	@Override
	protected void extractStation(ConsoleTerminalBlockEntity be, State s, float partialTicks) {
		ForemanState st = Foreman.state();
		s.live = st != null && st.hasData();
		s.stale = st == null || st.isStale();
		s.waiting = DecisionsFeature.waitingCount();
		String last = ConsoleActions.lastSent();
		if (last == null && !ConsoleLog.history().isEmpty()) {
			last = ConsoleLog.history().get(ConsoleLog.history().size() - 1);
		}
		Font font = Minecraft.getInstance().font;
		s.last = last == null ? "" : TextUtil.ellipsize(font, last.replace('\n', ' '), SW - 22);
		s.key = Keys.console == null ? "`" : Keys.label(Keys.console);
	}

	@Override
	public void submit(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = WorldUi.uiLight();
		poseStack.pushPose();
		poseStack.translate(0.5f, 0.5f, 0.5f);
		poseStack.rotateDegrees(Axis.YP, -modelRotation(s.facing));
		poseStack.translate(-0.5f, -0.5f, -0.5f);
		poseStack.translate(8 / 16f, 9 / 16f, 11 / 16f);
		poseStack.rotateDegrees(Axis.XP, 22.5f);
		poseStack.translate(-8 / 16f, -9 / 16f, -11 / 16f);
		poseStack.translate(14 / 16f, 17 / 16f, 9.5f / 16f - 0.0025f);
		poseStack.scale(-1f / PPB, -1f / PPB, 1f);

		// cover the baked prompt with the warm e-ink paper and its scanlines
		int bg = UiStyle.color("monitor.bg", 0xFFEDE5D7);
		int scan = UiStyle.color("monitor.scanline", 0xFFE7DECE);
		// the paper is drawn opaque in the solid pass (like nameplates), so the text and dots on top of
		// it always win the depth test; it sits a hair behind the text plane
		poseStack.pushPose();
		poseStack.translate(0, 0, 0.0012f);
		solidRect(poseStack, collector, 0, 0, SW, SH, bg, light);
		for (int y = 6; y < SH; y += 12) {
			solidRect(poseStack, collector, 0, y, SW, y + 6, scan, light);
		}
		poseStack.popPose();
		int ink = UiStyle.color("monitor.text", 0xFF1F1E1D);
		int muted = UiStyle.color("monitor.muted", 0xFF655E55);
		// prompt
		WorldUi.submitText(poseStack, collector, ">", 4, 4, UiStyle.color("monitor.tool", 0xFF624E16), light);
		Font font = Minecraft.getInstance().font;
		float cx = 11;
		if (!s.last.isEmpty()) {
			WorldUi.submitText(poseStack, collector, s.last, cx, 4, ink, light);
			cx += font.width(s.last) + 1;
		}
		if ((int) (s.timeSeconds * 2) % 2 == 0) {
			WorldUi.submitText(poseStack, collector, "_", cx, 4, ink, light);
		}
		// how to open
		WorldUi.submitText(poseStack, collector, s.key + " or Enter", 4, 17, muted, light);
		// what is waiting
		if (!s.live) {
			WorldUi.submitText(poseStack, collector, "no Foreman", 4, 29, muted, light);
		} else if (s.waiting > 0) {
			WorldUi.submitSprite(poseStack, collector, WorldUi.Layer.OVERLAY, Kit.dot("waiting", false), 4, 30, 7, 7, 0f, 0xFFFFFFFF, light);
			WorldUi.submitText(poseStack, collector, s.waiting + " waiting", 14, 29, UiStyle.color("monitor.error", 0xFF9A2F2B), light);
		} else {
			WorldUi.submitSprite(poseStack, collector, WorldUi.Layer.OVERLAY, Kit.dot(s.stale ? "idle" : "done", false), 4, 30, 7, 7, 0f, 0xFFFFFFFF,
				light);
			WorldUi.submitText(poseStack, collector, s.stale ? "offline" : "all clear", 14, 29, UiStyle.color("monitor.result", 0xFF485A48), light);
		}
		poseStack.popPose();
	}
}
