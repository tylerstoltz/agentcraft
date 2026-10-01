package dev.agentcraft.client.taskwall;

import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.monitor.DisplayDraw;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.CiStatus;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The kanban model of one Task Wall panel: four columns (Todo, Doing, Review, Done), blocked tasks
 * shown as red cards at the top of the column where they stalled (Doing when they have a worktree,
 * else Todo), cancelled tasks hidden. Laid out in board pixels and cached until the tasks, the
 * agents or the panel size change; card positions then glide to their new slots
 * ({@link #step}). One instance per panel origin, client thread only.
 */
final class TaskBoard {
	static final int TRIM = 2; // texels of brass trim on the outer edges
	static final int PAD = 4;
	static final int GAP = 4;
	static final int HEADER_H = 13;
	static final int CARD_H = 44;
	/** One-line card (title + face) used when a column holds more cards than fit at full height. */
	static final int COMPACT_H = 20;
	static final int CARD_MIN_W = 84;
	static final int CHIP_H = 11;
	/** Column width (px) under which the board switches to one list (small boards). */
	static final int LIST_BELOW = 72;
	/** Card glide time constant (s): ~95 % of the way after 3 tau. */
	static final float TAU = 0.085f;

	enum Col {
		TODO("Todo", "todo"), DOING("Doing", "doing"), REVIEW("Review", "review"), DONE("Done", "done");

		final String label;
		final String card;

		Col(String label, String card) {
			this.label = label;
			this.card = card;
		}
	}

	/** One column: header, lane rectangle, overflow chip. */
	static final class Column {
		final Col col;
		float x, w;
		int count;
		int blocked;
		FormattedCharSequence label = FormattedCharSequence.EMPTY;
		FormattedCharSequence countSeq = FormattedCharSequence.EMPTY;
		float countX;
		boolean compact;
		String family = "idle";
		@Nullable FormattedCharSequence chip;
		float chipX, chipY, chipW;
		final List<String> hidden = new ArrayList<>();

		Column(Col col) {
			this.col = col;
		}
	}

	/** A card: content (rebuilt with the layout), its slot and its animated position. */
	static final class Card {
		final String id;
		Task task;
		Col col;
		// content
		Identifier sprite = Kit.card("todo");
		FormattedCharSequence line1 = FormattedCharSequence.EMPTY;
		@Nullable FormattedCharSequence line2;
		int titleColor;
		int line2Color;
		@Nullable String assignee;
		@Nullable Identifier face;
		@Nullable FormattedCharSequence name;
		int nameColor;
		@Nullable FormattedCharSequence hint;
		float hintW;
		int hintColor;
		@Nullable String dot;
		boolean compact;
		// slot (target) and animated state
		float tx, ty, w, th = CARD_H, h = CARD_H;
		boolean visible;
		float x, y;
		float scale = 1f;
		boolean placed;
		boolean removing;
		@Nullable Col arrivedFrom;
		long arrivedAt;
		boolean moving;
		final DisplayDraw.Rects glow = new DisplayDraw.Rects();
		final DisplayDraw.Rects shadow = new DisplayDraw.Rects();

		Card(String id, Task task) {
			this.id = id;
			this.task = task;
			this.col = colOf(task);
		}
	}

	final BlockPos origin;
	long lastUsedNanos;
	int ppb;
	int panelW;
	int panelH;
	long seq = Long.MIN_VALUE;
	float pw, ph;          // panel px
	float ix0, iy0, ix1, iy1; // inside the trim
	float cardW;
	int perRow = 1;
	int capacity;          // card slots per column
	boolean listMode;      // one list instead of four columns (small boards)
	final List<Column> columns = new ArrayList<>(4);
	final Map<String, Card> cards = new LinkedHashMap<>();
	boolean everLaidOut;
	final DisplayDraw.Rects lanes = new DisplayDraw.Rects();
	final DisplayDraw.Rects shadows = new DisplayDraw.Rects();
	final DisplayDraw.Rects veil = new DisplayDraw.Rects();
	final DisplayDraw.Rects badge = new DisplayDraw.Rects();
	long lastStep;
	@Nullable String summary;
	int total;

	TaskBoard(BlockPos origin) {
		this.origin = origin;
	}

	/** Pixel density: 64 px/block up to 3 blocks high, then the board keeps ~192 px of height (bigger text for bigger walls). */
	static int density(int w, int h) {
		return h <= 3 ? 64 : Math.max(32, Math.round(192f / h));
	}

	static Col colOf(Task t) {
		return switch (t.status()) {
			case DOING -> Col.DOING;
			case REVIEW -> Col.REVIEW;
			case DONE -> Col.DONE;
			case BLOCKED -> t.worktree() != null || t.branch() != null ? Col.DOING : Col.TODO;
			default -> Col.TODO;
		};
	}

	static boolean shown(Task t) {
		return t.status() != TaskStatus.CANCELLED && t.status() != TaskStatus.UNKNOWN;
	}

	/** Re-lay out when the inputs changed; returns true when it did. */
	boolean sync(@Nullable ForemanState s, int panelW, int panelH, long seq, long now) {
		int ppb = density(panelW, panelH);
		if (seq == this.seq && ppb == this.ppb && panelW == this.panelW && panelH == this.panelH) {
			return false;
		}
		boolean resized = ppb != this.ppb || panelW != this.panelW || panelH != this.panelH;
		this.seq = seq;
		this.ppb = ppb;
		this.panelW = panelW;
		this.panelH = panelH;
		layout(s, now, resized || !everLaidOut);
		everLaidOut = true;
		return true;
	}

	private void layout(@Nullable ForemanState s, long now, boolean snap) {
		Font font = Minecraft.getInstance().font;
		pw = panelW * ppb;
		ph = panelH * ppb;
		float trim = ppb * TRIM / 16f;
		ix0 = trim + PAD;
		iy0 = trim + PAD - 1;
		ix1 = pw - trim - PAD;
		iy1 = ph - trim - PAD + 1;
		float colW = (ix1 - ix0 - GAP * 3) / 4f;
		// too narrow for four readable columns (small boards): one list, most urgent first
		listMode = colW < LIST_BELOW;
		if (listMode) {
			colW = ix1 - ix0;
		}
		perRow = Math.max(1, (int) ((colW + GAP) / (CARD_MIN_W + GAP)));
		cardW = (colW - GAP * (perRow - 1)) / perRow;
		float cardsTop = iy0 + HEADER_H + 3;
		float avail = iy1 - cardsTop;
		int rows = Math.max(1, (int) ((avail + GAP) / (CARD_H + GAP)));
		capacity = rows * perRow;

		// --- bucket the tasks
		Map<Col, List<Task>> by = new LinkedHashMap<>();
		for (Col c : Col.values()) {
			by.put(c, new ArrayList<>());
		}
		int total = 0;
		if (s != null) {
			for (Task t : s.tasks().values()) {
				if (shown(t)) {
					by.get(colOf(t)).add(t);
					total++;
				}
			}
		}
		Comparator<Task> blockedFirst = Comparator.comparing((Task t) -> t.status() != TaskStatus.BLOCKED);
		Comparator<Task> prio = Comparator.comparingInt(Task::priority).reversed().thenComparingLong(Task::createdAt);
		by.get(Col.TODO).sort(blockedFirst.thenComparing(prio));
		by.get(Col.DOING).sort(blockedFirst.thenComparing(prio));
		by.get(Col.REVIEW).sort(Comparator.comparingLong(Task::updatedAt));
		by.get(Col.DONE).sort(Comparator.comparingLong(Task::updatedAt).reversed());

		// --- columns + slots
		columns.clear();
		Set<String> present = new HashSet<>();
		List<Col> lanes = listMode ? List.of(Col.TODO) : List.of(Col.values());
		for (Col c : lanes) {
			Column col = new Column(c);
			col.x = ix0 + (listMode ? 0 : c.ordinal() * (colW + GAP));
			col.w = colW;
			List<Task> ts = by.get(c);
			if (listMode) {
				ts = new ArrayList<>();
				for (Col k : List.of(Col.DOING, Col.REVIEW, Col.TODO, Col.DONE)) {
					ts.addAll(by.get(k));
				}
				ts.sort(Comparator.comparing((Task t) -> t.status() != TaskStatus.BLOCKED));
			}
			col.count = ts.size();
			for (Task t : ts) {
				if (t.status() == TaskStatus.BLOCKED) {
					col.blocked++;
				}
			}
			col.family = listMode ? "working" : switch (c) {
				case TODO -> "idle";
				case DOING -> "working";
				case REVIEW -> "thinking";
				case DONE -> "done";
			};
			String label = listMode ? "TASKS" : c.label.toUpperCase(Locale.ROOT);
			String cs = Integer.toString(col.count);
			if (font.width(label) + font.width(cs) + (col.blocked > 0 ? 12 : 0) + 12 > colW) {
				// tiny board: the count says it all
				label = cs + (listMode ? " tasks" : "");
				cs = "";
			}
			col.label = seq(label);
			col.countSeq = seq(cs);
			col.countX = col.x + col.w - 4 - font.width(cs);
			// a column that overflows switches to compact one-line cards (title + face) before it hides any
			col.compact = ts.size() > capacity;
			int cardH = col.compact ? COMPACT_H : CARD_H;
			int rowsHere = Math.max(1, (int) ((avail + GAP) / (cardH + GAP)));
			int cap = rowsHere * perRow;
			int show = ts.size();
			if (ts.size() > cap) {
				float used = (float) Math.ceil(cap / (float) perRow) * (cardH + GAP);
				show = used + CHIP_H <= avail + GAP ? cap : cap - perRow;
			}
			for (int i = 0; i < ts.size(); i++) {
				Task t = ts.get(i);
				present.add(t.id());
				Card card = cards.get(t.id());
				boolean isNew = card == null;
				if (isNew) {
					card = new Card(t.id(), t);
					cards.put(t.id(), card);
				}
				Col before = card.col;
				card.task = t;
				card.col = listMode ? colOf(t) : c;
				card.removing = false;
				card.w = cardW;
				card.compact = col.compact;
				card.th = cardH;
				if (i < show) {
					int r = i / perRow;
					int k = i % perRow;
					card.tx = col.x + k * (cardW + GAP);
					card.ty = cardsTop + r * (cardH + GAP);
					card.visible = true;
				} else {
					// hidden behind the "+N more" chip: park it there (it glides out of the chip when it gets a slot)
					card.tx = col.x;
					card.ty = cardsTop + (show / perRow) * (cardH + GAP);
					card.visible = false;
					col.hidden.add(t.id());
				}
				if (isNew || snap || !card.placed) {
					card.x = card.tx;
					card.y = card.ty;
					card.h = card.th;
					card.placed = true;
					card.scale = isNew && !snap ? 0.6f : 1f;
				} else if (before != c) {
					card.arrivedFrom = before;
					card.arrivedAt = 0;
				}
				content(font, s, card);
			}
			if (!col.hidden.isEmpty()) {
				String more = "+" + col.hidden.size() + " more";
				col.chip = seq(more);
				col.chipW = font.width(more) + 10;
				col.chipX = col.x + (colW - col.chipW) / 2f;
				col.chipY = cardsTop + (float) Math.ceil(show / (float) perRow) * (cardH + GAP) - 1;
			}
			columns.add(col);
		}
		for (Iterator<Card> it = cards.values().iterator(); it.hasNext(); ) {
			Card c = it.next();
			if (!present.contains(c.id)) {
				if (snap) {
					it.remove();
				} else {
					c.removing = true;
				}
			}
		}
		summary = total + " tasks";
		this.total = total;
	}

	/** Card text + badges from the task and its assignee. */
	private void content(Font font, @Nullable ForemanState s, Card c) {
		Task t = c.task;
		boolean blocked = t.status() == TaskStatus.BLOCKED;
		boolean done = t.status() == TaskStatus.DONE;
		c.sprite = Kit.card(blocked ? "blocked" : c.col.card);
		int ink = UiStyle.color("paper.text", 0xFF1F1E1D);
		int muted = UiStyle.color("paper.muted", 0xFF655E55);
		int error = UiStyle.color("paper.del_fg", 0xFF873C2A);
		c.titleColor = done ? muted : ink;
		Kit.Padding p = Kit.padding("card_todo");
		float inner = c.w - p.left() - p.right();
		int titleW = (int) inner - 9; // keep room for the status dot top-right
		String title = t.title().replace("`", "").strip();
		c.line2 = null;
		if (c.compact) {
			c.line1 = seq(TextUtil.ellipsize(font, title, (int) inner - (t.assignee() != null ? 11 : 0)));
			c.assignee = t.assignee();
			c.face = t.assignee() == null || t.assignee().isBlank() ? null : dev.agentcraft.AgentCraft.id("textures/gui/portrait/" + t.assignee() + ".png");
			c.name = null;
			c.dot = null;
			c.hint = null;
			return;
		}
		List<String> lines = TextUtil.wrapPlain(font, title, titleW);
		if (lines.isEmpty()) {
			c.line1 = seq(t.id());
		} else {
			c.line1 = seq(lines.get(0));
			if (lines.size() > 1) {
				String rest = lines.size() > 2 ? TextUtil.ellipsize(font, lines.get(1) + " " + String.join(" ", lines.subList(2, lines.size())), (int) inner)
					: lines.get(1);
				c.line2 = seq(TextUtil.ellipsize(font, rest, (int) inner));
				c.line2Color = c.titleColor;
			}
		}
		// assignee: face + name, and the live state dot top-right
		c.assignee = t.assignee();
		c.face = null;
		c.name = null;
		c.dot = null;
		float footerLeft = 0;
		if (t.assignee() != null && !t.assignee().isBlank()) {
			Agent a = s == null ? null : s.agent(t.assignee());
			String n = a != null ? a.name() : t.assignee();
			c.face = dev.agentcraft.AgentCraft.id("textures/gui/portrait/" + t.assignee() + ".png");
			c.nameColor = UiStyle.agentOnLight(t.assignee());
			if (!done && a != null) {
				c.dot = a.isActive() ? a.state().family() : "idle";
			}
			c.name = seq(n);
			footerLeft = 10 + font.width(n);
		}
		// footer hint (right): the most useful single fact
		String hint;
		int hintColor = muted;
		List<String> pending = new ArrayList<>();
		if (s != null) {
			for (String d : t.deps()) {
				Task dt = s.task(d);
				if (dt == null || dt.status() != TaskStatus.DONE) {
					pending.add(d);
				}
			}
		}
		boolean fullHint = false;
		if (blocked) {
			// unassigned: the footer has room for the reason itself
			String reason = t.blockedReason() == null ? "" : t.blockedReason().strip();
			fullHint = c.name == null && !reason.isEmpty();
			hint = fullHint ? TextUtil.ellipsize(font, reason, (int) inner) : "blocked";
			hintColor = error;
		} else if (t.ci() == CiStatus.FAIL && !done) {
			hint = "CI failing";
			hintColor = error;
		} else if (!pending.isEmpty() && !done) {
			hint = "after " + String.join(", ", pending);
		} else if (t.ci() == CiStatus.RUNNING) {
			hint = "CI running";
		} else if (t.priority() > 0 && c.col == Col.TODO) {
			hint = "P" + t.priority() + "  " + t.id();
		} else {
			hint = t.id();
		}
		int room = (int) (inner - footerLeft - 4);
		if (!fullHint && font.width(hint) > room) {
			hint = font.width(t.id()) <= room ? t.id() : "";
		}
		c.hint = hint.isEmpty() ? null : seq(hint);
		c.hintW = font.width(hint);
		c.hintColor = hintColor;
	}

	/** Advance the glide/pop animations (call once per frame). */
	void step(long now) {
		float dt = lastStep == 0 ? 0 : Math.min(0.1f, (now - lastStep) / 1e9f);
		lastStep = now;
		float k = dt <= 0 ? 0 : (float) (1 - Math.exp(-dt / TAU));
		for (Iterator<Card> it = cards.values().iterator(); it.hasNext(); ) {
			Card c = it.next();
			if (c.removing) {
				c.scale -= dt * 5f;
				if (c.scale <= 0.05f) {
					it.remove();
				}
				continue;
			}
			c.h += (c.th - c.h) * k;
			if (Math.abs(c.th - c.h) < 0.3f) {
				c.h = c.th;
			}
			float dx = c.tx - c.x;
			float dy = c.ty - c.y;
			if (Math.abs(dx) < 0.3f && Math.abs(dy) < 0.3f) {
				c.x = c.tx;
				c.y = c.ty;
				if (c.moving && c.arrivedFrom != null && c.arrivedAt == 0) {
					c.arrivedAt = now;
				}
				c.moving = false;
			} else {
				c.x += dx * k;
				c.y += dy * k;
				c.moving = Math.abs(dx) > 2 || Math.abs(dy) > 2;
			}
			if (c.scale < 1f) {
				c.scale = Math.min(1f, c.scale + dt * 3.5f);
			}
		}
	}

	/** The card under board pixel (px, py), topmost first; or null. */
	@Nullable Card cardAt(float px, float py) {
		Card best = null;
		for (Card c : cards.values()) {
			if (!c.visible || c.removing) {
				continue;
			}
			if (px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h) {
				if (best == null || c.moving) {
					best = c;
				}
			}
		}
		return best;
	}

	/** The column under board pixel x, or null. */
	@Nullable Column columnAt(float px) {
		for (Column c : columns) {
			if (px >= c.x - GAP / 2f && px <= c.x + c.w + GAP / 2f) {
				return c;
			}
		}
		return null;
	}

	static FormattedCharSequence seq(String s) {
		return Component.literal(s).getVisualOrderText();
	}
}
