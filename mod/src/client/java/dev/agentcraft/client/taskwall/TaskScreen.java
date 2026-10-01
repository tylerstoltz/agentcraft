package dev.agentcraft.client.taskwall;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentRole;
import dev.agentcraft.client.foreman.Protocol.CiStatus;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Task detail (opened by clicking a card on the Task Wall, or {@code dev.screen {open:"task"}}):
 * title, status, assignee with their live state, description, dependencies with their status, CI,
 * branch, blocked reason; actions Retry / Prioritize / Reassign / Cancel through
 * {@code Foreman.taskAction}, with the Foreman's answer shown in place. Left/right browse the
 * other tasks in wall order. Paper panel, ink text, one clay primary action, no shadows.
 */
public class TaskScreen extends Screen {
	private static final int W = 320;
	private String taskId;
	private @Nullable String feedback;
	private boolean feedbackError;
	private boolean confirmCancel;
	private boolean reassignOpen;
	private final List<Btn> buttons = new ArrayList<>();
	private int px, py, ph;

	private record Btn(String id, String label, int x, int y, int w, int h, boolean primary, boolean enabled, @Nullable String agent) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public TaskScreen(String taskId) {
		super(Component.literal("Task"));
		this.taskId = taskId;
	}

	public String taskId() {
		return taskId;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private @Nullable Task task() {
		ForemanState s = Foreman.state();
		return s == null ? null : s.task(taskId);
	}

	/** Tasks in wall order (Todo, Doing, Review, Done; cancelled last). */
	static List<Task> wallOrder() {
		ForemanState s = Foreman.state();
		List<Task> out = new ArrayList<>();
		if (s == null) {
			return out;
		}
		for (TaskBoard.Col c : TaskBoard.Col.values()) {
			List<Task> col = new ArrayList<>();
			for (Task t : s.tasks().values()) {
				if (TaskBoard.shown(t) && TaskBoard.colOf(t) == c) {
					col.add(t);
				}
			}
			col.sort((a, b) -> {
				boolean ab = a.status() == TaskStatus.BLOCKED, bb = b.status() == TaskStatus.BLOCKED;
				if (ab != bb) {
					return ab ? -1 : 1;
				}
				if (c == TaskBoard.Col.DONE) {
					return Long.compare(b.updatedAt(), a.updatedAt());
				}
				return a.priority() != b.priority() ? Integer.compare(b.priority(), a.priority()) : Long.compare(a.createdAt(), b.createdAt());
			});
			out.addAll(col);
		}
		for (Task t : s.tasks().values()) {
			if (!TaskBoard.shown(t)) {
				out.add(t);
			}
		}
		return out;
	}

	private void browse(int dir) {
		List<Task> all = wallOrder();
		if (all.isEmpty()) {
			return;
		}
		int i = 0;
		for (int k = 0; k < all.size(); k++) {
			if (all.get(k).id().equals(taskId)) {
				i = k;
			}
		}
		taskId = all.get(Math.floorMod(i + dir, all.size())).id();
		feedback = null;
		confirmCancel = false;
		reassignOpen = false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractTransparentBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		buttons.clear();
		Task t = task();
		int ink = UiStyle.color("paper.text", 0xFF1F1E1D);
		int muted = UiStyle.color("paper.muted", 0xFF655E55);
		int error = UiStyle.color("paper.del_fg", 0xFF873C2A);
		int pathC = UiStyle.color("paper.path", 0xFF6C5415);
		Kit.Padding pad = Kit.padding("panel_paper");
		int inner = W - pad.left() - pad.right();
		// ---- measure first, so the panel hugs its content
		List<FormattedCharSequence> title = t == null ? List.of() : clip(TextUtil.wrap(font, t.title().replace("`", ""), inner), 2);
		List<FormattedCharSequence> desc = t == null || t.description() == null || t.description().isBlank() ? List.of()
			: clip(TextUtil.wrap(font, t.description().replace("`", ""), inner), 4);
		int h = pad.top() + 14 + 6 + title.size() * 10 + 6 + 22 + 4 + (desc.isEmpty() ? 0 : desc.size() * 10 + 6);
		int depRows = t == null ? 0 : Math.min(3, t.deps().size());
		h += depRows > 0 ? 12 + depRows * 11 + 4 : 0;
		h += 12; // CI + branch line
		boolean blocked = t != null && t.status() == TaskStatus.BLOCKED;
		List<FormattedCharSequence> reason = blocked && t.blockedReason() != null ? clip(TextUtil.wrap(font, "Blocked: " + t.blockedReason(), inner), 2)
			: List.of();
		h += reason.isEmpty() ? 0 : reason.size() * 10 + 4;
		List<FormattedCharSequence> summary = t != null && t.summary() != null && !t.summary().isBlank() && t.status() == TaskStatus.DONE
			? clip(TextUtil.wrap(font, t.summary(), inner), 2) : List.of();
		h += summary.isEmpty() ? 0 : summary.size() * 10 + 4;
		h += 3 + 6 + 20 + (reassignOpen ? 26 : 0) + 14 + 12 + pad.bottom();
		px = (width - W) / 2;
		py = Math.max(4, (height - h) / 2);
		ph = h;
		Panels.panel(g, px, py, W, h);
		int x = px + pad.left();
		int y = py + pad.top();
		if (t == null) {
			Panels.header(g, font, "Task " + taskId, x, y, inner);
			Panels.text(g, font, Foreman.state() != null && Foreman.state().hasData() ? "This task is gone." : "Waiting for the Foreman.", x, y + 22, muted);
			return;
		}
		// ---- header: id + status pill, browse arrows
		List<Task> all = wallOrder();
		int idx = 0;
		for (int k = 0; k < all.size(); k++) {
			if (all.get(k).id().equals(taskId)) {
				idx = k;
			}
		}
		Panels.sprite(g, Kit.HEADER, x, y, inner, 14);
		Panels.text(g, font, t.id(), x + 6, y + 3, muted);
		String status = statusLabel(t);
		int sx = x + 6 + font.width(t.id()) + 6;
		Panels.dot(g, statusFamily(t), sx, y + 3, false);
		Panels.text(g, font, status, sx + 10, y + 3, ink);
		String pos = (idx + 1) + " / " + all.size();
		int posX = x + inner - 6 - 12 - font.width(pos) - 12;
		button(g, "prev", "<", posX - 2, y + 1, 12, 12, false, true, null, mouseX, mouseY, true);
		Panels.text(g, font, pos, posX + 13, y + 3, muted);
		button(g, "next", ">", posX + 15 + font.width(pos), y + 1, 12, 12, false, true, null, mouseX, mouseY, true);
		y += 14 + 6;
		for (FormattedCharSequence l : title) {
			g.text(font, l, x, y, ink, false);
			y += 10;
		}
		y += 6;
		// ---- assignee
		ForemanState s = Foreman.state();
		Agent a = t.assignee() == null || s == null ? null : s.agent(t.assignee());
		if (t.assignee() != null) {
			Identifier framed = AgentCraft.id("textures/gui/portrait/" + t.assignee() + "_framed.png");
			g.blit(RenderPipelines.GUI_TEXTURED, framed, x, y, 0, 0, 20, 20, 20, 20);
			String n = a != null ? a.name() : t.assignee();
			Panels.text(g, font, n, x + 25, y + 1, UiStyle.agentOnLight(t.assignee()));
			String role = a != null && a.title() != null ? a.title() : a != null ? a.role().wire() : "";
			Panels.text(g, font, role, x + 25 + font.width(n) + 6, y + 1, muted);
			if (a != null) {
				String fam = a.isActive() ? a.state().family() : "idle";
				Panels.dot(g, fam, x + 25, y + 12, false);
				String act = !a.isActive() ? "off shift" : a.isPaused() ? "paused" : a.activity().isBlank() ? a.state().wire().replace('_', ' ') : a.activity();
				Panels.text(g, font, TextUtil.ellipsize(font, act, inner - 40), x + 35, y + 12, muted);
			}
		} else {
			Panels.text(g, font, "Unassigned", x, y + 6, muted);
		}
		y += 22 + 4;
		for (FormattedCharSequence l : desc) {
			g.text(font, l, x, y, muted, false);
			y += 10;
		}
		if (!desc.isEmpty()) {
			y += 6;
		}
		// ---- dependencies
		if (depRows > 0) {
			Panels.text(g, font, "Needs", x, y, ink);
			y += 12;
			for (int i = 0; i < depRows; i++) {
				String d = t.deps().get(i);
				Task dt = s == null ? null : s.task(d);
				String fam = dt == null ? "idle" : statusFamily(dt);
				Panels.dot(g, fam, x + 2, y + 1, false);
				String label = d + "  " + (dt == null ? "?" : dt.title().replace("`", ""));
				String st = dt == null ? "" : statusLabel(dt);
				int stw = font.width(st);
				Panels.text(g, font, TextUtil.ellipsize(font, label, inner - 14 - stw - 6), x + 12, y, ink);
				Panels.text(g, font, st, x + inner - stw, y, muted);
				y += 11;
			}
			if (t.deps().size() > depRows) {
				Panels.text(g, font, "+" + (t.deps().size() - depRows) + " more", x + inner - 50, y - 11, muted);
			}
			y += 4;
		}
		// ---- CI + branch
		String ci = switch (t.ci()) {
			case PASS -> "passing";
			case FAIL -> "failing";
			case RUNNING -> "running";
			default -> "not run";
		};
		Panels.text(g, font, "CI", x, y, ink);
		Panels.dot(g, t.ci() == CiStatus.PASS ? "done" : t.ci() == CiStatus.FAIL ? "error" : t.ci() == CiStatus.RUNNING ? "working" : "idle", x + 14, y + 1,
			false);
		Panels.text(g, font, ci, x + 24, y, t.ci() == CiStatus.FAIL ? error : muted);
		String prio = t.priority() > 0 ? "priority " + t.priority() : t.priority() < 0 ? "low priority" : "";
		int bx = x + 24 + font.width(ci) + 12;
		if (!prio.isEmpty()) {
			Panels.text(g, font, prio, bx, y, t.priority() > 0 ? ink : muted);
			bx += font.width(prio) + 12;
		}
		String branch = t.branch() != null ? t.branch() : t.worktree() != null ? t.worktree() : "";
		if (!branch.isEmpty()) {
			Panels.text(g, font, TextUtil.ellipsize(font, branch, x + inner - bx), bx, y, pathC);
		}
		y += 12;
		for (FormattedCharSequence l : reason) {
			g.text(font, l, x, y, error, false);
			y += 10;
		}
		if (!reason.isEmpty()) {
			y += 4;
		}
		for (FormattedCharSequence l : summary) {
			g.text(font, l, x, y, UiStyle.color("paper.add_fg", 0xFF455746), false);
			y += 10;
		}
		if (!summary.isEmpty()) {
			y += 4;
		}
		// ---- actions
		Panels.divider(g, x, y, inner);
		y += 3 + 6;
		boolean live = Foreman.connected();
		boolean cancelled = t.status() == TaskStatus.CANCELLED;
		boolean done = t.status() == TaskStatus.DONE;
		String primary = blocked || t.ci() == CiStatus.FAIL ? "retry" : t.status() == TaskStatus.TODO ? "prioritize" : "";
		int gap = 4;
		int bw = (inner - gap * 3) / 4;
		button(g, "retry", "Retry", x, y, bw, 20, primary.equals("retry"), live && !cancelled && t.status() != TaskStatus.TODO, null, mouseX, mouseY,
			false);
		button(g, "prioritize", "Prioritize", x + (bw + gap), y, bw, 20, primary.equals("prioritize"), live && !cancelled && !done, null, mouseX,
			mouseY, false);
		button(g, "reassign", "Reassign", x + 2 * (bw + gap), y, bw, 20, false, live && !cancelled && !done, null,
			mouseX, mouseY, false);
		button(g, "cancel", confirmCancel ? "Confirm" : "Cancel", x + 3 * (bw + gap), y, inner - 3 * (bw + gap), 20, confirmCancel,
			live && !cancelled && !done, null, mouseX, mouseY, false);
		y += 20;
		if (reassignOpen && s != null) {
			y += 4;
			int cx = x;
			for (Agent w : s.agents().values()) {
				if (w.role() == AgentRole.LEAD || w.id().equals(t.assignee())) {
					continue;
				}
				int cw = 8 + 4 + font.width(w.name()) + 10;
				if (cx + cw > x + inner) {
					break;
				}
				button(g, "to:" + w.id(), w.name(), cx, y, cw, 18, false, live, w.id(), mouseX, mouseY, false);
				cx += cw + 3;
			}
			y += 22;
		}
		y += 4;
		if (feedback != null) {
			Panels.text(g, font, TextUtil.ellipsize(font, feedback, inner), x, y, feedbackError ? error : muted);
		} else if (!live) {
			Panels.text(g, font, "Foreman offline: actions are disabled", x, y, muted);
		}
		y += 14;
		// ---- key hints
		int kx = x;
		kx = keycap(g, "Esc", "close", kx, y);
		keycap(g, "<  >", "browse", kx + 10, y);
	}

	private int keycap(GuiGraphicsExtractor g, String key, String verb, int x, int y) {
		int kw = font.width(key) + 8;
		Panels.sprite(g, Kit.KEYCAP, x, y - 2, kw, 12);
		Panels.text(g, font, key, x + 4, y, UiStyle.color("paper.text", 0xFF1F1E1D));
		Panels.text(g, font, verb, x + kw + 4, y, UiStyle.color("paper.muted", 0xFF655E55));
		return x + kw + 4 + font.width(verb);
	}

	private void button(GuiGraphicsExtractor g, String id, String label, int x, int y, int w, int h, boolean primary, boolean enabled,
		@Nullable String agent, int mx, int my, boolean small) {
		Btn b = new Btn(id, label, x, y, w, h, primary, enabled, agent);
		buttons.add(b);
		boolean hover = enabled && b.hit(mx, my);
		if (small) {
			Panels.sprite(g, hover ? Kit.button(false, "hover") : Kit.button(false, "normal"), x, y, w, h);
			Panels.text(g, font, label, x + (w - font.width(label)) / 2, y + 2, UiStyle.color("paper.text", 0xFF1F1E1D));
			return;
		}
		if (agent != null) {
			Panels.sprite(g, Kit.button(false, enabled ? hover ? "hover" : "normal" : "disabled"), x, y, w, h);
			g.blit(RenderPipelines.GUI_TEXTURED, AgentCraft.id("textures/gui/portrait/" + agent + ".png"), x + 5, y + 5, 0, 0, 8, 8, 8, 8);
			Panels.text(g, font, label, x + 17, y + 5, UiStyle.agentOnLight(agent));
			return;
		}
		if (primary && enabled) {
			// Panels.button colours a primary label with palette.ui.highlight, which is clay (= the button): draw it here in cream
			Panels.sprite(g, Kit.button(true, hover ? "hover" : "normal"), x, y, w, 20);
			String l = TextUtil.ellipsize(font, label, w - 12);
			Panels.text(g, font, l, x + (w - font.width(l)) / 2, y + 6, UiStyle.color("palette.ui.panel_hi", 0xFFFFFBF4));
			return;
		}
		Panels.button(g, font, label, x, y, w, primary, hover, !enabled);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			for (Btn b : List.copyOf(buttons)) {
				if (b.enabled() && b.hit(event.x(), event.y())) {
					press(b);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_LEFT) {
			browse(-1);
			return true;
		}
		if (event.key() == InputConstants.KEY_RIGHT) {
			browse(1);
			return true;
		}
		return super.keyPressed(event);
	}

	/** Press a button by id (also used by the dev command): prev next retry prioritize reassign cancel to:&lt;agent&gt;. */
	public void press(String id) {
		for (Btn b : List.copyOf(buttons)) {
			if (b.id().equals(id)) {
				press(b);
				return;
			}
		}
	}

	private void press(Btn b) {
		switch (b.id()) {
			case "prev" -> browse(-1);
			case "next" -> browse(1);
			case "reassign" -> reassignOpen = !reassignOpen;
			case "cancel" -> {
				if (!confirmCancel) {
					confirmCancel = true;
				} else {
					send("cancel", null);
					confirmCancel = false;
				}
			}
			case "retry" -> send("retry", null);
			case "prioritize" -> send("prioritize", null);
			default -> {
				if (b.agent() != null) {
					send("reassign", b.agent());
					reassignOpen = false;
				}
			}
		}
	}

	private void send(String action, @Nullable String arg) {
		String id = taskId;
		feedback = "Sending " + action + "...";
		feedbackError = false;
		Foreman.taskAction(id, action, arg).whenComplete((ack, err) -> {
			if (err != null) {
				feedback = "Not sent: " + (err.getMessage() == null ? err.toString() : err.getMessage());
				feedbackError = true;
			} else if (!ack.ok()) {
				feedback = "Foreman: " + ack.error();
				feedbackError = true;
			} else {
				feedback = switch (action) {
					case "retry" -> "Queued again";
					case "prioritize" -> "Moved to the top of the queue";
					case "cancel" -> "Cancelled";
					default -> "Reassigned to " + (arg == null ? "?" : nameOf(arg));
				};
				feedbackError = false;
			}
		});
	}

	private static String nameOf(String agentId) {
		ForemanState s = Foreman.state();
		Agent a = s == null ? null : s.agent(agentId);
		return a == null ? agentId : a.name();
	}

	static String statusLabel(Task t) {
		return switch (t.status()) {
			case TODO -> "To do";
			case DOING -> "Doing";
			case REVIEW -> "In review";
			case DONE -> "Done";
			case BLOCKED -> "Blocked";
			case CANCELLED -> "Cancelled";
			default -> t.status().wire();
		};
	}

	static String statusFamily(Task t) {
		return switch (t.status()) {
			case DOING -> "working";
			case REVIEW -> "thinking";
			case DONE -> "done";
			case BLOCKED -> "error";
			default -> "idle";
		};
	}

	private static List<FormattedCharSequence> clip(List<FormattedCharSequence> lines, int max) {
		return lines.size() <= max ? lines : new ArrayList<>(lines.subList(0, max));
	}
}
