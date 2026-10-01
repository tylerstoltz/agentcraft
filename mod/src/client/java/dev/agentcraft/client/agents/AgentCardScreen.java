package dev.agentcraft.client.agents;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The agent card: right-click an agent to see who it is and what it is doing, and to steer it.
 * A compact paper card (brass frame while the agent needs you): portrait, name, title and role;
 * the state dot with a plain-words state and the activity; the current task (id, title, column,
 * branch) or the decision it waits on; the last log lines (tool calls with their kit icons, results,
 * errors); and three actions: <b>Message</b> (opens the console prefilled with {@code @id }, or an
 * inline message line when no console is registered), <b>Pause/Resume</b>, <b>Stop</b> (off shift;
 * <b>Spawn</b> brings an off-shift agent back). Keys: M message, P pause/resume, Esc close.
 * Live: it follows the Foreman state while open, and the world keeps running behind it.
 * Shootable as {@code dev.screen {open:"agent"}} (last clicked agent, else whoever needs you first)
 * or {@code dev.agents.card {agent}}.
 */
public final class AgentCardScreen extends Screen {
	private static final int W = 252;
	private static final int LOG_ROWS = 6;
	private static final int ROW = 10;
	private static @Nullable String lastAgent;
	private static final Map<String, Boolean> PORTRAITS = new HashMap<>();

	private final String agentId;
	private int x0;
	private int y0;
	private int h;
	private final Btn[] buttons = {new Btn("message"), new Btn("pause"), new Btn("stop")};
	private @Nullable String status;
	private boolean statusError;
	private long statusUntil;
	private @Nullable StringBuilder input;
	private long revision = -1;
	private int logCount = -1;
	private final List<LogEntry> logs = new ArrayList<>();

	private static final class Btn {
		final String id;
		String label = "";
		boolean primary;
		boolean disabled;
		int x, y, w;

		Btn(String id) {
			this.id = id;
		}

		boolean hit(double mx, double my) {
			return !disabled && mx >= x && mx < x + w && my >= y && my < y + 20;
		}
	}

	public AgentCardScreen(String agentId) {
		super(Component.literal("Agent"));
		this.agentId = agentId;
		lastAgent = agentId;
	}

	public String agentId() {
		return agentId;
	}

	/** The agent the card opens for when QA asks without an id: last clicked, else whoever needs you, else the first. */
	public static @Nullable String defaultAgent() {
		ForemanState st = Foreman.state();
		if (lastAgent != null && st != null && st.agent(lastAgent) != null) {
			return lastAgent;
		}
		AgentManager m = AgentManager.get();
		for (ClientAgentEntity e : m.entities().values()) {
			if ("waiting".equals(e.view().family)) {
				return e.agentId();
			}
		}
		if (st != null && !st.agents().isEmpty()) {
			return st.agents().keySet().iterator().next();
		}
		return null;
	}

	@Override
	protected void init() {
		layout();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// no dimming, no blur: the HQ stays alive behind the card
	}

	private @Nullable Agent agent() {
		ForemanState st = Foreman.state();
		return st == null ? null : st.agent(agentId);
	}

	private @Nullable AgentView view() {
		ClientAgentEntity e = AgentManager.get().entity(agentId);
		return e == null ? null : e.view();
	}

	private void layout() {
		Font font = this.font;
		int taskLines = taskLines(font).size();
		int pad = Kit.padding("panel_paper").top();
		AgentView v = view();
		int wait = v != null && v.awaitingDecision != null ? 11 : 0;
		h = pad + 22 + 6 + 12 + 4 + (taskLines > 0 ? taskLines * ROW + 12 : 12) + wait + 4 + (LOG_ROWS * ROW + 8) + 6 + (input != null ? 24 : 0) + 20 + 6 + 12
			+ Kit.padding("panel_paper").bottom();
		x0 = (this.width - W) / 2;
		y0 = Math.max(4, (this.height - h) / 2);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Agent ag = agent();
		AgentView v = view();
		ForemanState st = Foreman.state();
		if (ag == null || st == null) {
			onClose();
			return;
		}
		refresh(st);
		layout();
		Font font = this.font;
		Kit.Padding pp = Kit.padding("panel_paper");
		boolean needsYou = v != null && "waiting".equals(v.family);
		if (needsYou) {
			Panels.framed(g, x0, y0, W, h);
		} else {
			Panels.panel(g, x0, y0, W, h);
		}
		int ix = x0 + pp.left();
		int iw = W - pp.left() - pp.right();
		int y = y0 + pp.top();
		int ink = UiStyle.color("paper.text");
		int muted = UiStyle.color("paper.muted");

		// header: portrait, name, title + role, state on the right
		Identifier portrait = AgentCraft.id("textures/gui/portrait/" + agentId + "_framed.png");
		int textX = ix;
		if (hasPortrait(portrait)) {
			g.blit(RenderPipelines.GUI_TEXTURED, portrait, ix, y, 0, 0, 20, 20, 20, 20);
			textX = ix + 26;
		}
		Panels.text(g, font, ag.name(), textX, y + 1, UiStyle.agentOnLight(agentId));
		String role = ag.role() == Protocol.AgentRole.LEAD ? "lead" : "worker";
		String sub = (ag.title() != null ? ag.title() + " · " : "") + role;
		Panels.text(g, font, TextUtil.ellipsize(font, sub, iw - (textX - ix)), textX, y + 12, muted);
		String fam = v != null ? v.family : ag.state().family();
		String stateLabel = stateLabel(ag, v);
		int sw = font.width(stateLabel);
		int sx = ix + iw - sw;
		Panels.text(g, font, stateLabel, sx, y + 1, ink);
		Panels.dot(g, fam, sx - 11, y + 1, false);
		if ("waiting".equals(fam)) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 1000.0 * Math.PI * 2 / 1.2);
			Panels.sprite(g, Kit.dot("waiting", true), sx - 13, y - 1, 11, 11, UiStyle.withAlpha(0xFFFFFFFF, (int) (60 + 150 * pulse)));
			Panels.dot(g, fam, sx - 11, y + 1, false);
		}
		y += 22 + 4;
		Panels.divider(g, ix, y, iw);
		y += 6;

		// activity line
		String act = v != null ? v.activityLine() : ag.activity();
		Panels.text(g, font, TextUtil.ellipsize(font, act.isEmpty() ? "-" : act, iw), ix, y, ink);
		y += 12 + 4;

		// task (or the decision it waits on)
		List<String> tl = taskLines(font);
		Task task = ag.taskId() != null ? st.task(ag.taskId()) : null;
		if (task != null) {
			int pw = Panels.pill(g, font, task.id(), ix, y - 1, ink);
			for (int i = 0; i < tl.size(); i++) {
				Panels.text(g, font, tl.get(i), ix + pw + 5, y + i * ROW, ink);
			}
			y += Math.max(1, tl.size()) * ROW + 1;
			String meta = column(task) + (task.branch() != null ? "  " + task.branch() : "");
			Panels.text(g, font, TextUtil.ellipsize(font, meta, iw - pw - 5), ix + pw + 5, y, UiStyle.color("paper.path"));
			y += 11;
		} else {
			Panels.text(g, font, ag.isActive() ? "No task right now" : "Off shift", ix, y, muted);
			y += 12;
		}
		if (v != null && v.awaitingDecision != null) {
			Protocol.Decision d = st.decision(v.awaitingDecision);
			if (d != null) {
				String w = waitLine(d);
				Panels.text(g, font, TextUtil.ellipsize(font, w, iw), ix, y, UiStyle.color("paper.link"));
			}
			y += 11;
		}
		y += 4;

		// log tail
		int logH = LOG_ROWS * ROW + 8;
		Panels.inset(g, ix, y, iw, logH);
		Kit.Padding ip = Kit.padding("panel_inset");
		int ly = y + ip.top();
		int start = Math.max(0, logs.size() - LOG_ROWS);
		if (logs.isEmpty()) {
			Panels.text(g, font, "No log yet", ix + ip.left() + 1, ly, muted);
		}
		for (int i = start; i < logs.size(); i++) {
			LogEntry le = logs.get(i);
			int lx = ix + ip.left() + 1;
			int avail = iw - ip.left() - ip.right() - 2;
			String text = TextUtil.firstLine(le.text());
			if (le.kind() == Protocol.LogKind.TOOL) {
				Panels.sprite(g, Kit.icon(toolIcon(text)), lx, ly - 2, 12, 12);
				lx += 14;
				avail -= 14;
			}
			Panels.text(g, font, TextUtil.ellipsize(font, text, avail), lx, ly, logColor(le.kind()));
			ly += ROW;
		}
		y += logH + 6;

		// inline message line (only when no console screen is registered)
		if (input != null) {
			Panels.sprite(g, Kit.TEXT_FIELD_FOCUSED, ix, y, iw, 18);
			String shown = "@" + agentId + " " + input;
			boolean caret = (System.currentTimeMillis() / UiStyle.metric("metrics.caret_blink_ms", 500)) % 2 == 0;
			String vis = TextUtil.ellipsize(font, shown, iw - 12);
			Panels.text(g, font, vis, ix + 5, y + 5, ink);
			if (caret) {
				int cx = ix + 5 + font.width(vis) + 1;
				g.fill(cx, y + 4, cx + 1, y + 14, ink);
			}
			y += 24;
		}

		// actions
		layoutButtons(ag, ix, y, iw);
		for (Btn b : buttons) {
			if (b.primary && !b.disabled) {
				// Panels.button tints primary labels with palette.ui.highlight, which is the clay of the button
				// itself; draw the label in the light panel colour instead
				Panels.button(g, font, "", b.x, b.y, b.w, true, b.hit(mouseX, mouseY), false);
				String l = TextUtil.ellipsize(font, b.label, b.w - 12);
				Panels.text(g, font, l, b.x + (b.w - font.width(l)) / 2, b.y + 6, UiStyle.color("palette.ui.panel_hi", UiStyle.CREAM));
			} else {
				Panels.button(g, font, b.label, b.x, b.y, b.w, b.primary, b.hit(mouseX, mouseY), b.disabled);
			}
		}
		y += 20 + 6;

		// hints or the last action's result
		if (status != null && System.currentTimeMillis() < statusUntil) {
			Panels.text(g, font, TextUtil.ellipsize(font, status, iw), ix, y, statusError ? UiStyle.color("paper.del_fg") : UiStyle.color("paper.add_fg"));
		} else {
			int hx = ix;
			hx = hint(g, font, hx, y, input != null ? "Enter" : "M", input != null ? "send" : "message");
			if (input == null) {
				hx = hint(g, font, hx, y, "P", ag.isPaused() ? "resume" : "pause");
			}
			hint(g, font, hx, y, "Esc", input != null ? "cancel" : "close");
		}
	}

	private int hint(GuiGraphicsExtractor g, Font font, int x, int y, String key, String verb) {
		int kw = font.width(key) + 8;
		Panels.sprite(g, Kit.KEYCAP, x, y - 2, kw, 12);
		Panels.text(g, font, key, x + 4, y, UiStyle.color("paper.text"));
		Panels.text(g, font, verb, x + kw + 3, y, UiStyle.color("paper.muted"));
		return x + kw + 3 + font.width(verb) + 10;
	}

	private void layoutButtons(Agent ag, int ix, int y, int iw) {
		Btn msg = buttons[0];
		Btn pause = buttons[1];
		Btn stop = buttons[2];
		msg.label = input != null ? "Send" : "Message";
		msg.primary = true;
		msg.disabled = !Foreman.connected();
		pause.label = ag.isPaused() ? "Resume" : "Pause";
		pause.primary = false;
		pause.disabled = !Foreman.connected() || !ag.isActive();
		stop.label = ag.isActive() ? "Stop" : "Spawn";
		stop.primary = false;
		stop.disabled = !Foreman.connected();
		int gap = 6;
		int bw = (iw - 2 * gap) / 3;
		msg.x = ix;
		pause.x = ix + bw + gap;
		stop.x = ix + 2 * (bw + gap);
		for (Btn b : buttons) {
			b.y = y;
			b.w = bw;
		}
		stop.w = iw - 2 * (bw + gap);
	}

	private List<String> taskLines(Font font) {
		Agent ag = agent();
		ForemanState st = Foreman.state();
		if (ag == null || st == null || ag.taskId() == null) {
			return List.of();
		}
		Task t = st.task(ag.taskId());
		if (t == null) {
			return List.of();
		}
		int pw = font.width(t.id()) + Kit.padding("pill").left() + Kit.padding("pill").right();
		int avail = W - Kit.padding("panel_paper").left() - Kit.padding("panel_paper").right() - pw - 5;
		List<String> lines = TextUtil.wrapPlain(font, t.title(), avail);
		if (lines.size() > 2) {
			String rest = String.join(" ", lines.subList(1, lines.size()));
			lines = List.of(lines.get(0), TextUtil.ellipsize(font, rest, avail));
		}
		return lines;
	}

	private void refresh(ForemanState st) {
		int n = st.logCount(agentId);
		if (st.revision() != revision || n != logCount) {
			revision = st.revision();
			logCount = n;
			logs.clear();
			List<LogEntry> all = st.logs(agentId);
			logs.addAll(all.subList(Math.max(0, all.size() - LOG_ROWS), all.size()));
		}
	}

	private static String stateLabel(Agent a, @Nullable AgentView v) {
		if (v != null && v.stale) {
			return "Offline";
		}
		if (!a.isActive()) {
			return "Off shift";
		}
		if (a.isPaused()) {
			return "Paused";
		}
		if (v != null && v.awaitingUser && a.state() != AgentState.WAITING_USER && "waiting".equals(v.family)) {
			return "Waiting for you";
		}
		return switch (a.state()) {
			case WAITING_USER -> "Waiting for you";
			case UNKNOWN -> "Idle";
			default -> {
				String s = a.state().wire();
				yield Character.toUpperCase(s.charAt(0)) + s.substring(1);
			}
		};
	}

	private static String waitLine(Protocol.Decision d) {
		String what = switch (d.kind()) {
			case MERGE -> "your merge review";
			case PERMISSION -> "your permission";
			default -> "your answer";
		};
		return "Waiting for " + what + (d.taskId() != null ? " on " + d.taskId() : "") + " (" + d.id() + ")";
	}

	private static String column(Task t) {
		return switch (t.status()) {
			case TODO -> "To do";
			case DOING -> "Doing";
			case REVIEW -> "In review";
			case DONE -> "Done";
			case BLOCKED -> "Blocked";
			case CANCELLED -> "Cancelled";
			default -> "";
		};
	}

	private static int logColor(Protocol.LogKind k) {
		return switch (k) {
			case TOOL -> UiStyle.color("paper.path");
			case RESULT -> UiStyle.color("paper.add_fg");
			case ERROR -> UiStyle.color("paper.del_fg");
			case DIFF -> UiStyle.color("paper.hunk");
			default -> UiStyle.color("paper.text");
		};
	}

	/** Kit icon for a tool log line ("Read src/x.ts", "$ npm test", "write_memory ..."). */
	static String toolIcon(String text) {
		String t = text.toLowerCase(Locale.ROOT).trim();
		if (t.startsWith("read") || t.startsWith("grep") || t.startsWith("glob")) {
			return "read";
		}
		if (t.startsWith("edit") || t.startsWith("write ") || t.startsWith("multiedit") || t.startsWith("patch")) {
			return "edit";
		}
		if (t.contains("memory")) {
			return "memory";
		}
		if (t.startsWith("$ git") || t.startsWith("git") || t.startsWith("diff")) {
			return "git";
		}
		if (t.contains("test")) {
			return "test";
		}
		if (t.contains("merge")) {
			return "merge";
		}
		if (t.contains("send_message") || t.contains("ask_user")) {
			return "message";
		}
		if (t.contains("task")) {
			return "decision";
		}
		return "bash";
	}

	private boolean hasPortrait(Identifier id) {
		return PORTRAITS.computeIfAbsent(id.toString(), k -> Minecraft.getInstance().getResourceManager().getResource(id).isPresent());
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			for (Btn b : buttons) {
				if (b.hit(event.x(), event.y())) {
					press(b.id);
					return true;
				}
			}
			if (event.x() < x0 || event.x() > x0 + W || event.y() < y0 || event.y() > y0 + h) {
				onClose();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (input != null) {
			if (event.isEscape()) {
				input = null;
				return true;
			}
			if (event.isConfirmation()) {
				press("message");
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				if (!input.isEmpty()) {
					input.setLength(input.length() - 1);
				}
				return true;
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_M) {
			press("message");
			return true;
		}
		if (event.key() == InputConstants.KEY_P) {
			press("pause");
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (input != null && event.isAllowedChatCharacter()) {
			if (input.length() < 400) {
				input.appendCodePoint(event.codepoint());
			}
			return true;
		}
		return super.charTyped(event);
	}

	private void press(String id) {
		Agent ag = agent();
		if (ag == null) {
			return;
		}
		switch (id) {
			case "message" -> {
				if (input != null) {
					String text = input.toString().trim();
					if (text.isEmpty()) {
						return;
					}
					input = null;
					send(Foreman.message(agentId, text), "Sent to " + ag.name());
				} else if (!openConsole(this.minecraft, "@" + agentId + " ")) {
					input = new StringBuilder();
				}
			}
			case "pause" -> {
				if (!ag.isActive()) {
					return;
				}
				boolean resume = ag.isPaused();
				send(Foreman.agentAction(agentId, resume ? "resume" : "pause", null), ag.name() + (resume ? " resumed" : " paused"));
			}
			case "stop" -> {
				boolean spawn = !ag.isActive();
				send(Foreman.agentAction(agentId, spawn ? "spawn" : "stop", null), ag.name() + (spawn ? " is back on shift" : " is off shift"));
			}
			default -> {
			}
		}
	}

	private void send(java.util.concurrent.CompletableFuture<Protocol.Ack> f, String ok) {
		status = "...";
		statusError = false;
		statusUntil = System.currentTimeMillis() + 20_000;
		f.whenComplete((ack, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null || ack == null || !ack.ok()) {
				status = err != null ? "Could not reach the Foreman" : ack == null ? "No answer" : "Foreman: " + (ack.error() != null ? ack.error() : "refused");
				statusError = true;
			} else {
				status = ok;
				statusError = false;
			}
			statusUntil = System.currentTimeMillis() + 4000;
		}));
	}

	/**
	 * Open the console screen (whatever the console feature registered as "console") with
	 * {@code prefill} typed into its input, exactly as a player would type it. False when there is no
	 * console screen.
	 */
	public static boolean openConsole(Minecraft mc, String prefill) {
		Function<Minecraft, Screen> f = DevBridge.screens().get("console");
		if (f == null) {
			return false;
		}
		Screen s;
		try {
			s = f.apply(mc);
		} catch (RuntimeException ex) {
			AgentCraft.LOGGER.warn("console screen failed to open", ex);
			return false;
		}
		if (s == null) {
			return false;
		}
		mc.gui.setScreen(s);
		for (int i = 0; i < prefill.length();) {
			int cp = prefill.codePointAt(i);
			i += Character.charCount(cp);
			s.charTyped(new CharacterEvent(cp));
		}
		return true;
	}
}
