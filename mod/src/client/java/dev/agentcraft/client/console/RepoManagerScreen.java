package dev.agentcraft.client.console;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.ConsoleLog.Tone;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.foreman.ForemanState;
import dev.agentcraft.foreman.Protocol.Ack;
import dev.agentcraft.foreman.Protocol.Repo;
import dev.agentcraft.foreman.Protocol.RepoHealth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The repo manager ({@code /repo}, Ctrl+R in the console, or a click on the console's repo tag): the
 * registered repos with their state, which one new goals go to ({@link RepoTarget}), and adding,
 * removing or repairing one. Removing only unregisters it (the folder and its history stay); a
 * repo whose checkout broke (folder gone, {@code .git} deleted, base branch gone) can be repaired by
 * adding its folder again, which for a folder that is no longer a repository runs {@code git init}
 * and a first commit, so that press asks for a second one.
 */
public class RepoManagerScreen extends Screen {
	private static final int ROW = 24;
	private static final long CONFIRM_NANOS = 5_000_000_000L;
	/** The confirming press must come at least this long after the arming one, so a double-click cannot do both. */
	private static final long CONFIRM_MIN_NANOS = 400_000_000L;

	private final @Nullable Screen parent;
	private int selected = -1;
	/** Row to scroll into view once the list has been laid out (the first frame does not know its height yet). */
	private int revealRow = -1;
	/** A repo just added through the picker: selected once it shows up in the state. */
	private @Nullable String selectWhenListed;
	private @Nullable String feedback;
	private boolean feedbackError;
	private boolean sending;
	/** "remove" or "repair" while that button waits for its confirming press. */
	private @Nullable String confirming;
	private @Nullable String confirmingRepo;
	private long confirmArmedAt;
	private long confirmUntil;
	private final TextUtil.Scroll scroll = new TextUtil.Scroll();
	private final List<Btn> buttons = new ArrayList<>();
	private int listX, listY, listW, listH;

	private record Btn(String id, int x, int y, int w, int h, boolean enabled) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public RepoManagerScreen(@Nullable Screen parent) {
		super(Component.literal("Repos"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ data

	private static List<Repo> repos() {
		ForemanState s = Foreman.state();
		return s == null ? List.of() : new ArrayList<>(s.repos().values());
	}

	private @Nullable Repo selectedRepo(List<Repo> rs) {
		return selected >= 0 && selected < rs.size() ? rs.get(selected) : null;
	}

	/** Keeps the selection valid as repos come and go; starts on the current target. */
	private void syncSelection(List<Repo> rs) {
		if (selectWhenListed != null) {
			for (int i = 0; i < rs.size(); i++) {
				if (rs.get(i).id().equals(selectWhenListed)) {
					selected = i;
					selectWhenListed = null;
					revealRow = i;
					break;
				}
			}
		}
		if (selected < 0 && !rs.isEmpty()) {
			Repo target = RepoTarget.resolve(Foreman.state());
			selected = 0;
			for (int i = 0; i < rs.size(); i++) {
				if (target != null && rs.get(i).id().equals(target.id())) {
					selected = i;
				}
			}
			revealRow = selected;
		}
		if (selected >= rs.size()) {
			selected = rs.size() - 1;
		}
	}

	/** What repairing does for this state, or null when there is nothing to repair. */
	private static @Nullable String repairText(Repo r) {
		return switch (r.health()) {
			case MISSING -> "The folder " + r.path() + " is gone. Remove the repo, or put the folder back.";
			case NOT_GIT -> "The folder is no longer a git repository (its .git was deleted). Repair runs git init there and commits everything in it as a "
				+ "fresh first commit (files in .gitignore are left out). Or remove the repo.";
			case NO_COMMITS -> "The repository has no commits. Repair commits everything in the folder as a first commit (files in .gitignore are left "
				+ "out).";
			case NO_BRANCH -> "The base branch " + r.branch() + " is gone. Repair makes the branch checked out there now the base.";
			default -> null;
		};
	}

	private static boolean repairNeedsInit(Repo r) {
		return r.health() == RepoHealth.NOT_GIT || r.health() == RepoHealth.NO_COMMITS;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractTransparentBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		buttons.clear();
		if (confirming != null && System.nanoTime() > confirmUntil) {
			confirming = null;
		}
		List<Repo> rs = repos();
		syncSelection(rs);
		Repo sel = selectedRepo(rs);
		if (confirming != null && (sel == null || !sel.id().equals(confirmingRepo))) {
			confirming = null;
		}
		ForemanState s = Foreman.state();
		Repo target = RepoTarget.resolve(s);
		boolean live = Foreman.connected();

		int ink = UiStyle.color("paper.text", 0xFF1F1E1D);
		int muted = UiStyle.color("paper.muted", 0xFF655E55);
		int error = UiStyle.color("paper.del_fg", 0xFF873C2A);
		int pathC = UiStyle.color("paper.path", 0xFF6C5415);
		int ok = UiStyle.color("paper.add_fg", 0xFF455746);
		Kit.Padding pad = Kit.padding("panel_paper");
		int w = Math.min(400, width - 16);
		int h = Math.min(300, height - 16);
		int px = (width - w) / 2;
		int py = (height - h) / 2;
		int inner = w - pad.left() - pad.right();
		int x = px + pad.left();
		int y = py + pad.top();
		Panels.panel(g, px, py, w, h);
		Panels.header(g, font, "Repos", x, y, inner);
		y += 14 + 6;

		// ---- where goals go
		String where;
		int whereC;
		if (s == null || !s.hasData()) {
			where = live ? "Loading…" : "Foreman offline: waiting for it to reconnect";
			whereC = muted;
		} else if (target == null) {
			where = rs.isEmpty() ? "No repos yet: add one to give goals somewhere to go" : "No repo picked for new goals yet: pick one below";
			whereC = UiStyle.CLAY_DARK;
		} else {
			where = "New goals go to " + target.name() + (target.usable() ? "" : " (" + target.health().problem() + ")");
			whereC = target.usable() ? ink : error;
		}
		Panels.text(g, font, TextUtil.ellipsize(font, where, inner), x, y, whereC);
		y += 14;

		// ---- bottom block, measured first so the list takes what is left
		String detail = sel == null ? null : repairText(sel);
		if (detail == null && sel != null && "remove".equals(confirming)) {
			detail = "Remove " + sel.name() + " from AgentCraft? Its folder, history and branches stay where they are. Press Remove again to confirm.";
		}
		List<String> detailLines = detail == null ? List.of() : TextUtil.wrapPlain(font, detail, inner);
		int bottomH = (detailLines.isEmpty() ? 0 : detailLines.size() * 10 + 4) + (feedback != null ? 12 : 0) + 3 + 6 + 20 + 6 + 10;
		listX = x;
		listY = y;
		listW = inner;
		listH = Math.max(ROW * 2 + 4, py + h - pad.bottom() - bottomH - y - 4);

		// ---- the repo list
		Panels.inset(g, listX, listY, listW, listH);
		int view = Math.max(1, (listH - 4) / ROW);
		scroll.update(rs.size(), view);
		if (revealRow >= 0) {
			reveal(revealRow);
			revealRow = -1;
		}
		g.enableScissor(listX + 2, listY + 2, listX + listW - 2, listY + listH - 2);
		for (int i = scroll.offset(), k = 0; i < rs.size() && k < view; i++, k++) {
			Repo r = rs.get(i);
			int ry = listY + 2 + k * ROW;
			boolean hover = mouseX >= listX && mouseX < listX + listW - 8 && mouseY >= ry && mouseY < ry + ROW;
			boolean isTarget = target != null && target.id().equals(r.id());
			if (i == selected || hover) {
				g.fill(listX + 2, ry, listX + listW - 8, ry + ROW, UiStyle.withAlpha(UiStyle.CLAY, i == selected ? 60 : 30));
			}
			if (isTarget) {
				g.fill(listX + 2, ry, listX + 4, ry + ROW, UiStyle.CLAY);
			}
			// right side: state pills
			int rx = listX + listW - 10;
			List<String[]> pills = new ArrayList<>();
			if (!r.usable()) {
				pills.add(new String[] {r.health().problem(), "error"});
			} else {
				if (r.activeWorktrees() > 0) {
					pills.add(new String[] {r.activeWorktrees() + (r.activeWorktrees() == 1 ? " worktree" : " worktrees"), "working"});
				}
				if (r.dirty()) {
					pills.add(new String[] {"uncommitted", "waiting"});
				}
			}
			if (isTarget) {
				pills.add(new String[] {"goals go here", "target"});
			}
			for (String[] pl : pills) {
				int pw = font.width(pl[0]) + 8;
				rx -= pw;
				int color = switch (pl[1]) {
					case "error" -> error;
					case "target" -> UiStyle.CLAY_DARK;
					case "working" -> ok;
					default -> muted;
				};
				Panels.pill(g, font, pl[0], rx, ry + 2, color);
				rx -= 3;
			}
			String num = i < 9 ? (i + 1) + "  " : "   ";
			Panels.text(g, font, num, listX + 7, ry + 3, muted);
			int nx = listX + 7 + font.width("9  ");
			Panels.text(g, font, TextUtil.ellipsize(font, r.name(), Math.max(20, rx - nx - 4)), nx, ry + 3, r.usable() ? ink : error);
			String sub = r.branch() + (r.head() != null ? " @ " + r.head() : "") + " · ";
			int sw = font.width(sub);
			Panels.text(g, font, sub, nx, ry + 13, muted);
			Panels.text(g, font, tail(font, r.path(), listW - 16 - (nx - listX) - sw), nx + sw, ry + 13, pathC);
		}
		if (rs.isEmpty() && s != null && s.hasData()) {
			Panels.text(g, font, "No repos registered. Add one to get started.", listX + 6, listY + 6, muted);
		}
		g.disableScissor();
		Panels.scrollbar(g, listX + listW - 7, listY + 2, listH - 4, scroll, false);
		y = listY + listH + 4;

		// ---- detail, feedback, actions
		for (String dl : detailLines) {
			Panels.text(g, font, dl, x, y, sel != null && !sel.usable() ? error : ink);
			y += 10;
		}
		if (!detailLines.isEmpty()) {
			y += 4;
		}
		if (feedback != null) {
			Panels.text(g, font, TextUtil.ellipsize(font, feedback, inner), x, y, feedbackError ? error : ok);
			y += 12;
		}
		Panels.divider(g, x, y, inner);
		y += 3 + 6;
		boolean canSend = live && !sending;
		int bx = x;
		bx += button(g, "add", "Add…", bx, y, 44, false, canSend, mouseX, mouseY) + 4;
		String removeLabel = "remove".equals(confirming) ? "Confirm remove" : "Remove";
		button(g, "remove", removeLabel, bx, y, Math.max(56, font.width(removeLabel) + 14), false, canSend && sel != null, mouseX, mouseY);
		String primary;
		boolean primaryOn;
		String primaryId;
		if (sel != null && !sel.usable()) {
			primaryId = "repair";
			primary = sel.health() == RepoHealth.MISSING ? "Repair" : "repair".equals(confirming) ? "Confirm repair" : "Repair";
			primaryOn = canSend && sel.health() != RepoHealth.MISSING;
		} else {
			primaryId = "use";
			primary = sel != null && target != null && target.id().equals(sel.id()) ? "Keep this one" : "Use for goals";
			primaryOn = sel != null && !sending;
		}
		if (sending) {
			primary = "Working…";
		}
		int pw = Math.max(90, font.width(primary) + 16);
		button(g, primaryId, primary, x + inner - pw, y, pw, true, primaryOn, mouseX, mouseY);
		button(g, "close", "Close", x + inner - pw - 4 - 46, y, 46, false, true, mouseX, mouseY);
		y += 20 + 6;
		int kx = keycap(g, "↑↓", "select", x, y);
		kx = keycap(g, "Enter", sel != null && !sel.usable() ? "repair" : "use", kx + 10, y);
		kx = keycap(g, "Del", "remove", kx + 10, y);
		keycap(g, "Esc", "back", kx + 10, y);
	}

	/** The end of a long path, which is the part that tells folders apart. */
	private static String tail(Font font, String s, int maxWidth) {
		if (font.width(s) <= maxWidth) {
			return s;
		}
		int i = 0;
		while (i < s.length() && font.width(TextUtil.ELLIPSIS + s.substring(i)) > maxWidth) {
			i++;
		}
		return TextUtil.ELLIPSIS + s.substring(i);
	}

	/** Draws a 20 px button and registers its hit box; returns its width. */
	private int button(GuiGraphicsExtractor g, String id, String label, int x, int y, int w, boolean primary, boolean enabled, int mx, int my) {
		Btn b = new Btn(id, x, y, w, 20, enabled);
		buttons.add(b);
		boolean hover = enabled && b.hit(mx, my);
		if (primary && enabled) {
			// Panels.button colours a primary label with palette.ui.highlight, which is clay (= the button): draw it in cream
			Panels.sprite(g, Kit.button(true, hover ? "hover" : "normal"), x, y, w, 20);
			String l = TextUtil.ellipsize(font, label, w - 12);
			Panels.text(g, font, l, x + (w - font.width(l)) / 2, y + 6, UiStyle.color("palette.ui.panel_hi", 0xFFFFFBF4));
		} else {
			Panels.button(g, font, label, x, y, w, primary, hover, !enabled);
		}
		return w;
	}

	private int keycap(GuiGraphicsExtractor g, String key, String verb, int x, int y) {
		int kw = font.width(key) + 8;
		Panels.sprite(g, Kit.KEYCAP, x, y - 2, kw, 12);
		Panels.text(g, font, key, x + 4, y, UiStyle.color("paper.text", 0xFF1F1E1D));
		Panels.text(g, font, verb, x + kw + 4, y, UiStyle.color("paper.muted", 0xFF655E55));
		return x + kw + 4 + font.width(verb);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			double mx = event.x(), my = event.y();
			boolean onList = mx >= listX && mx < listX + listW - 8 && my >= listY + 2 && my < listY + listH - 2;
			if (onList) {
				int i = scroll.offset() + (int) ((my - listY - 2) / ROW);
				List<Repo> rs = repos();
				if (i >= 0 && i < rs.size()) {
					// click selects, a double-click uses it
					if (doubleClick && i == selected && rs.get(i).usable()) {
						use();
					} else {
						selected = i;
					}
					return true;
				}
			}
			if (doubleClick) {
				return true; // the second press of a double-click on a button: one action per click
			}
			for (Btn b : List.copyOf(buttons)) {
				if (b.enabled() && b.hit(mx, my)) {
					press(b.id());
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scroll.scrollBy(sy > 0 ? -1 : 1);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		List<Repo> rs = repos();
		int digit = TextKeys.digit(event);
		if (digit > 0 && digit <= rs.size()) {
			selected = digit - 1;
			reveal(selected);
			return true;
		}
		switch (event.key()) {
			case InputConstants.KEY_UP, InputConstants.KEY_DOWN -> {
				if (!rs.isEmpty()) {
					int d = event.key() == InputConstants.KEY_UP ? -1 : 1;
					selected = Math.max(0, Math.min(rs.size() - 1, selected < 0 ? 0 : selected + d));
					reveal(selected);
				}
				return true;
			}
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				// Enter is the primary button: use it, or repair it when it is broken
				Repo sel = selectedRepo(rs);
				if (sel != null && sel.usable()) {
					use();
				} else if (sel != null && Foreman.connected() && !sending) {
					press("repair");
				}
				return true;
			}
			case InputConstants.KEY_DELETE -> {
				if (selectedRepo(rs) != null && Foreman.connected() && !sending) {
					press("remove");
				}
				return true;
			}
			default -> {
				return super.keyPressed(event);
			}
		}
	}

	private void reveal(int row) {
		if (row < scroll.offset()) {
			scroll.scrollBy(row - scroll.offset());
		} else if (row >= scroll.offset() + scroll.view()) {
			scroll.scrollBy(row - (scroll.offset() + scroll.view() - 1));
		}
	}

	private void press(String id) {
		switch (id) {
			case "add" -> minecraft.gui.setScreen(new RepoPickerScreen(null, this, rid -> selectWhenListed = rid));
			case "remove" -> remove();
			case "repair" -> repair();
			case "use" -> use();
			case "close" -> onClose();
			default -> {
			}
		}
	}

	private void use() {
		Repo sel = selectedRepo(repos());
		if (sel == null) {
			return;
		}
		RepoTarget.select(sel.id());
		ConsoleActions.setFeedback("new goals go to " + sel.name() + " " + UiBits.CHECK, Tone.OK, false);
		onClose();
	}

	/** Two presses within a few seconds (not a double-click) for actions that change things. */
	private boolean confirmed(String action, Repo r) {
		long now = System.nanoTime();
		if (action.equals(confirming) && r.id().equals(confirmingRepo) && now <= confirmUntil) {
			if (now - confirmArmedAt < CONFIRM_MIN_NANOS) {
				return false; // the second half of a double-click, not a confirmation
			}
			confirming = null;
			return true;
		}
		confirming = action;
		confirmingRepo = r.id();
		confirmArmedAt = now;
		confirmUntil = now + CONFIRM_NANOS;
		feedback = null;
		return false;
	}

	private void remove() {
		Repo sel = selectedRepo(repos());
		if (sel == null || sending || !confirmed("remove", sel)) {
			return;
		}
		String name = sel.name();
		send(Foreman.removeRepo(sel.id()), "Removing " + name + "…", ack -> {
			String note = ack.result() != null && ack.result().has("note") ? ack.result().get("note").getAsString() : null;
			ConsoleLog.add(Tone.OK, "repo " + name + " removed (its folder is untouched)" + (note != null ? ": " + note : ""));
			return "Removed " + name + (note != null ? " (" + note + ")" : "");
		});
	}

	private void repair() {
		Repo sel = selectedRepo(repos());
		if (sel == null || sending || sel.health() == RepoHealth.MISSING) {
			return;
		}
		boolean init = repairNeedsInit(sel);
		if (init && !confirmed("repair", sel)) {
			return;
		}
		String name = sel.name();
		send(Foreman.addRepo(sel.path(), init), "Repairing " + name + "…", ack -> {
			ConsoleLog.add(Tone.OK, "repo " + name + " repaired" + (init ? " (git init and a first commit)" : ""));
			return "Repaired " + name;
		});
	}

	private interface OkText {
		String text(Ack ack);
	}

	private void send(CompletableFuture<Ack> f, String pending, OkText ok) {
		sending = true;
		feedback = pending;
		feedbackError = false;
		f.whenComplete((ack, err) -> {
			sending = false;
			if (err == null && ack != null && ack.ok()) {
				feedback = ok.text(ack);
				feedbackError = false;
				return;
			}
			Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
			feedback = c != null ? (c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName())
				: ack != null && ack.error() != null ? ack.error() : "the Foreman refused it";
			feedbackError = true;
		});
	}
}
