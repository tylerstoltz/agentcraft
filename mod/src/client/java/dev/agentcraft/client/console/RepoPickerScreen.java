package dev.agentcraft.client.console;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.ConsoleLog.Tone;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.foreman.Protocol.FsEntry;
import dev.agentcraft.foreman.Protocol.FsGitState;
import dev.agentcraft.foreman.Protocol.FsListing;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Repo folder picker ({@code /repo add} with no path, or {@code /repo browse [path]}). Browses the
 * folders of the Foreman's machine through {@code fs.list} (on a server that is the server, not this
 * computer) and registers the chosen one with {@code repo.add}. A folder that is not a repository
 * root, or has no commits, can still be added: the panel says what will happen (git init, a first
 * commit of everything in it) and the add button asks for a second press before it does it.
 */
public class RepoPickerScreen extends Screen {
	private static final int ROW = 12;
	private static final long CONFIRM_NANOS = 5_000_000_000L;
	/** The confirming press must come at least this long after the arming one, so a double-click cannot do both. */
	private static final long CONFIRM_MIN_NANOS = 400_000_000L;
	/** Where the picker was last, so reopening it continues there (this game session only). */
	private static @Nullable String lastPath;

	private final @Nullable Screen parent;
	private final @Nullable String startPath;
	private @Nullable FsListing listing;
	private boolean hidden;
	private boolean loading;
	private int requestSeq;
	private long retryAt;
	/** Why the last listing failed (shown until the next navigation). */
	private @Nullable String notice;
	private @Nullable String feedback;
	private boolean feedbackError;
	private boolean sending;
	private boolean confirmInit;
	private long confirmArmedAt;
	private long confirmUntil;
	private int selected = -1;
	private final TextUtil.Scroll scroll = new TextUtil.Scroll();
	private final List<Btn> buttons = new ArrayList<>();
	private int listX, listY, listW, listH;

	private record Btn(String id, int x, int y, int w, int h, boolean enabled) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** One list row: the parent folder ("..") or a sub-folder. */
	private record Row(String label, String path, boolean repo, boolean up) {
	}

	public RepoPickerScreen(@Nullable String startPath, @Nullable Screen parent) {
		super(Component.literal("Add a repo"));
		this.parent = parent;
		this.startPath = startPath != null ? startPath : lastPath;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		if (listing == null && !loading) {
			load(startPath);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ data

	private void load(@Nullable String path) {
		if (!Foreman.connected()) {
			retryAt = System.currentTimeMillis() + 1000;
			return;
		}
		int seq = ++requestSeq;
		loading = true;
		Foreman.listDir(path, hidden).whenComplete((l, err) -> {
			if (seq != requestSeq) {
				return; // a newer navigation won
			}
			loading = false;
			if (err != null) {
				Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				notice = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
				retryAt = System.currentTimeMillis() + 3000;
				if (listing == null && path != null) {
					load(null); // a bad start path: show home instead, with the reason
				}
				return;
			}
			listing = l;
			lastPath = l.path();
			selected = -1;
			scroll.toTop();
			confirmInit = false;
		});
	}

	private void go(@Nullable String path) {
		notice = null;
		feedback = null;
		load(path);
	}

	private List<Row> rows() {
		FsListing l = listing;
		List<Row> out = new ArrayList<>();
		if (l == null) {
			return out;
		}
		if (l.parent() != null) {
			out.add(new Row("..", l.parent(), false, true));
		}
		for (FsEntry e : l.entries()) {
			out.add(new Row(e.name(), join(l.path(), e.name()), e.repo(), false));
		}
		return out;
	}

	/** Child path in the Foreman's own separator style (it may run on another OS than this client). */
	private static String join(String dir, String name) {
		String sep = dir.contains("\\") && !dir.contains("/") ? "\\" : "/";
		return dir.endsWith(sep) ? dir + name : dir + sep + name;
	}

	private static String lastSegment(String path) {
		String p = path.replaceAll("[\\\\/]+$", "");
		int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
		return i >= 0 ? p.substring(i + 1) : p;
	}

	/** What adding the current folder does to it, or null when it is already a repository with commits. */
	private static @Nullable String warning(FsListing l) {
		return switch (l.git()) {
			case NONE -> "Not a git repository. Adding it runs git init here and commits everything in the folder as a first commit (files in "
				+ ".gitignore are left out). Agents only see committed files, so check it holds no secrets or big build output first.";
			case NO_COMMITS -> "This repository has no commits yet. Adding it commits everything in the folder as a first commit (files in "
				+ ".gitignore are left out).";
			case INSIDE -> "This folder is part of the repository " + (l.repoRoot() != null ? lastSegment(l.repoRoot()) : "above it")
				+ ". Adding it makes it a separate repository nested inside that one (git init) and commits everything in it. To work on the "
				+ "whole project, open the repository's root instead.";
			default -> null;
		};
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
		if (listing == null && !loading && Foreman.connected() && System.currentTimeMillis() > retryAt) {
			load(startPath);
		}
		if (confirmInit && System.nanoTime() > confirmUntil) {
			confirmInit = false;
		}
		int ink = UiStyle.color("paper.text", 0xFF1F1E1D);
		int muted = UiStyle.color("paper.muted", 0xFF655E55);
		int error = UiStyle.color("paper.del_fg", 0xFF873C2A);
		int pathC = UiStyle.color("paper.path", 0xFF6C5415);
		int ok = UiStyle.color("paper.add_fg", 0xFF455746);
		Kit.Padding pad = Kit.padding("panel_paper");
		int w = Math.min(380, width - 16);
		int h = Math.min(320, height - 16);
		int px = (width - w) / 2;
		int py = (height - h) / 2;
		int inner = w - pad.left() - pad.right();
		int x = px + pad.left();
		int y = py + pad.top();
		Panels.panel(g, px, py, w, h);
		Panels.header(g, font, "Add a repo", x, y, inner);
		y += 14 + 6;

		FsListing l = listing;
		boolean live = Foreman.connected();
		// ---- where we are
		String where = l != null ? l.path() : loading ? "Loading…" : live ? "" : "Foreman offline: waiting for it to reconnect";
		Panels.text(g, font, tail(font, where, inner), x, y, l != null ? pathC : muted);
		y += 12;
		if (l != null) {
			String fam;
			String state;
			switch (l.git()) {
				case REPO -> {
					fam = "done";
					state = l.registered() ? "Git repository · already added" : "Git repository";
				}
				case NO_COMMITS -> {
					fam = "waiting";
					state = "Git repository with no commits yet";
				}
				case INSIDE -> {
					fam = "waiting";
					state = "Inside the repository " + (l.repoRoot() != null ? lastSegment(l.repoRoot()) : "");
				}
				default -> {
					fam = "idle";
					state = "Not a git repository";
				}
			}
			Panels.dot(g, fam, x, y + 1, false);
			int rootBtnW = l.git() == FsGitState.INSIDE && l.repoRoot() != null ? font.width("Open root") + 10 : 0;
			Panels.text(g, font, TextUtil.ellipsize(font, state, inner - 12 - rootBtnW - 4), x + 10, y, ink);
			if (rootBtnW > 0) {
				smallButton(g, "root", "Open root", x + inner - rootBtnW, y - 2, rootBtnW, true, mouseX, mouseY);
			}
		}
		y += 14;

		// ---- bottom block, measured first so the list takes what is left
		List<String> warn = l == null ? List.of() : wrapOrEmpty(warning(l), inner);
		String line = feedback != null ? feedback : notice;
		boolean lineError = feedback != null ? feedbackError : notice != null;
		int bottomH = (warn.isEmpty() ? 0 : warn.size() * 10 + 4) + (line != null ? 12 : 0) + 3 + 6 + 20 + 6 + 10;
		listX = x;
		listY = y;
		listW = inner;
		listH = Math.max(ROW * 3 + 4, py + h - pad.bottom() - bottomH - y - 4);

		// ---- the folder list
		Panels.inset(g, listX, listY, listW, listH);
		List<Row> rows = rows();
		int view = Math.max(1, (listH - 4) / ROW);
		scroll.update(rows.size(), view);
		g.enableScissor(listX + 2, listY + 2, listX + listW - 2, listY + listH - 2);
		for (int i = scroll.offset(), k = 0; i < rows.size() && k < view; i++, k++) {
			Row r = rows.get(i);
			int ry = listY + 2 + k * ROW;
			boolean hover = mouseX >= listX && mouseX < listX + listW - 8 && mouseY >= ry && mouseY < ry + ROW;
			if (i == selected || hover) {
				g.fill(listX + 2, ry, listX + listW - 8, ry + ROW, UiStyle.withAlpha(UiStyle.CLAY, i == selected ? 60 : 30));
			}
			String label = r.up() ? ".. (up)" : r.label() + "/";
			int pillW = r.repo() ? font.width("git") + 8 : 0;
			Panels.text(g, font, TextUtil.ellipsize(font, label, listW - 16 - pillW - 6), listX + 6, ry + 2, r.up() ? muted : ink);
			if (r.repo()) {
				Panels.pill(g, font, "git", listX + listW - 10 - pillW, ry + 1, ok);
			}
		}
		if (l != null && rows.isEmpty()) {
			Panels.text(g, font, "No folders in here", listX + 6, listY + 4, muted);
		} else if (l != null && l.truncated() && scroll.offset() + view >= rows.size()) {
			Panels.text(g, font, "… more folders not shown", listX + 6, listY + 2 + Math.min(view, rows.size()) * ROW, muted);
		}
		g.disableScissor();
		Panels.scrollbar(g, listX + listW - 7, listY + 2, listH - 4, scroll, false);
		y = listY + listH + 4;

		// ---- warning, feedback, actions
		for (String wl : warn) {
			Panels.text(g, font, wl, x, y, error);
			y += 10;
		}
		if (!warn.isEmpty()) {
			y += 4;
		}
		if (line != null) {
			Panels.text(g, font, TextUtil.ellipsize(font, line, inner), x, y, lineError ? error : ok);
			y += 12;
		}
		Panels.divider(g, x, y, inner);
		y += 3 + 6;
		boolean canNav = live && !sending;
		int bx = x;
		bx += button(g, "up", "Up", bx, y, 36, false, canNav && l != null && l.parent() != null, mouseX, mouseY) + 4;
		bx += button(g, "home", "Home", bx, y, 44, false, canNav && l != null, mouseX, mouseY) + 4;
		button(g, "hidden", hidden ? "Hidden: on" : "Hidden: off", bx, y, 70, false, canNav, mouseX, mouseY);
		String primary;
		boolean primaryOn = canNav && l != null && !loading;
		if (l == null) {
			primary = "Add this folder";
		} else if (l.registered()) {
			primary = "Already added";
			primaryOn = false;
		} else if (l.git() == FsGitState.REPO) {
			primary = "Add this repo";
		} else if (l.git() == FsGitState.UNKNOWN) {
			primary = "Add this folder";
			primaryOn = false;
		} else {
			primary = confirmInit ? "Confirm git init" : "Init and add";
		}
		if (sending) {
			primary = "Adding…";
		}
		int pw = Math.max(90, font.width(primary) + 16);
		button(g, "add", primary, x + inner - pw, y, pw, true, primaryOn, mouseX, mouseY);
		button(g, "cancel", "Cancel", x + inner - pw - 4 - 50, y, 50, false, true, mouseX, mouseY);
		y += 20 + 6;
		int kx = keycap(g, "↑↓", "select", x, y);
		kx = keycap(g, "Enter", "open", kx + 10, y);
		keycap(g, "Backspace", "up", kx + 10, y);
	}

	private List<String> wrapOrEmpty(@Nullable String s, int w) {
		return s == null ? List.of() : TextUtil.wrapPlain(font, s, w);
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

	private void smallButton(GuiGraphicsExtractor g, String id, String label, int x, int y, int w, boolean enabled, int mx, int my) {
		Btn b = new Btn(id, x, y, w, 12, enabled);
		buttons.add(b);
		boolean hover = enabled && b.hit(mx, my);
		Panels.sprite(g, Kit.button(false, hover ? "hover" : "normal"), x, y, w, 12);
		Panels.text(g, font, label, x + (w - font.width(label)) / 2, y + 2, UiStyle.color("paper.text", 0xFF1F1E1D));
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
			if (doubleClick) {
				return true; // the second press of a double-click: one action per click (a toggle would undo itself)
			}
			for (Btn b : List.copyOf(buttons)) {
				if (b.enabled() && b.hit(event.x(), event.y())) {
					press(b.id());
					return true;
				}
			}
			double mx = event.x(), my = event.y();
			if (mx >= listX && mx < listX + listW - 8 && my >= listY + 2 && my < listY + listH - 2 && !sending) {
				int i = scroll.offset() + (int) ((my - listY - 2) / ROW);
				List<Row> rows = rows();
				if (i >= 0 && i < rows.size()) {
					go(rows.get(i).path());
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scroll.scrollBy(sy > 0 ? -3 : 3);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		List<Row> rows = rows();
		switch (event.key()) {
			case InputConstants.KEY_UP, InputConstants.KEY_DOWN -> {
				if (!rows.isEmpty()) {
					int d = event.key() == InputConstants.KEY_UP ? -1 : 1;
					selected = Math.max(0, Math.min(rows.size() - 1, selected < 0 ? (d > 0 ? 0 : rows.size() - 1) : selected + d));
					reveal(selected);
				}
				return true;
			}
			case InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN -> {
				scroll.scrollBy((event.key() == InputConstants.KEY_PAGEUP ? -1 : 1) * Math.max(1, scroll.view() - 1));
				return true;
			}
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				if (selected >= 0 && selected < rows.size() && !sending) {
					go(rows.get(selected).path());
				}
				return true;
			}
			case InputConstants.KEY_BACKSPACE -> {
				press("up");
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
		FsListing l = listing;
		switch (id) {
			case "up" -> {
				if (l != null && l.parent() != null && !sending) {
					go(l.parent());
				}
			}
			case "home" -> go(null);
			case "root" -> {
				if (l != null && l.repoRoot() != null) {
					go(l.repoRoot());
				}
			}
			case "hidden" -> {
				hidden = !hidden;
				go(l != null ? l.path() : startPath);
			}
			case "cancel" -> onClose();
			case "add" -> add();
			default -> {
			}
		}
	}

	private void add() {
		FsListing l = listing;
		if (l == null || sending) {
			return;
		}
		boolean init = l.git() != FsGitState.REPO;
		long now = System.nanoTime();
		if (init && confirmInit && now - confirmArmedAt < CONFIRM_MIN_NANOS) {
			return; // the second half of a double-click, not a confirmation
		}
		if (init && (!confirmInit || now > confirmUntil)) {
			confirmInit = true;
			confirmArmedAt = now;
			confirmUntil = now + CONFIRM_NANOS;
			feedback = null;
			return;
		}
		confirmInit = false;
		sending = true;
		feedback = init ? "Initializing and adding…" : "Adding…";
		feedbackError = false;
		String path = l.path();
		Foreman.addRepo(path, init).whenComplete((ack, err) -> {
			sending = false;
			if (err != null || ack == null || !ack.ok()) {
				Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				feedback = c != null ? (c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName())
					: ack != null && ack.error() != null ? ack.error() : "the Foreman refused it";
				feedbackError = true;
				return;
			}
			String rid = ack.result() != null && ack.result().has("repoId") ? ack.result().get("repoId").getAsString() : lastSegment(path);
			ConsoleLog.add(Tone.OK, "repo " + rid + " added: " + path + (init ? " (git init and a first commit)" : ""));
			ConsoleActions.setFeedback("repo " + rid + " added " + UiBits.CHECK, Tone.OK, false);
			if (minecraft.gui.screen() == this) {
				onClose();
			}
		});
	}
}
