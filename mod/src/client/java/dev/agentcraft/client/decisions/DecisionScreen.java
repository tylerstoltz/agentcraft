package dev.agentcraft.client.decisions;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.TextFieldView;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.console.TextModel;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.Worktree;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.permissions.PermissionBody;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The decision GUI: one open decision at a time from {@link DecisionQueue} (permissions, then
 * questions, then merges), with the asking agent, the question, scrollable context, options as
 * buttons (keys 1-9), a free-text answer for questions, Merge / Request changes (with feedback) /
 * Reject plus "Review diff" for merges, and Allow once / Always allow / Deny with the risk and
 * scope for permissions. Tab / Shift+Tab walk the queue. Not a pause screen: the HQ keeps moving.
 */
public class DecisionScreen extends Screen {
	private static final int MAX_W = 440;
	private static final int ADVANCE_MS = 450;

	private @Nullable String currentId;
	private final @Nullable Screen parent;
	private final TextModel answer = new TextModel(4000);
	private final TextFieldView answerView = new TextFieldView();
	private final TextUtil.Scroll scroll = new TextUtil.Scroll();
	private boolean textFocused;
	private boolean requestChanges;
	private int highlight;
	private long confirmRejectUntil;
	private @Nullable String sendingId;
	private @Nullable String status;
	private boolean statusError;
	private long statusAt;
	private @Nullable String answeredId;
	private @Nullable String answeredOption;
	private long advanceAt;
	private boolean closeWhenEmpty;
	private final Map<String, List<DiffLink.SummaryLine>> diffs = new HashMap<>();

	// layout of the last frame (hit-testing)
	private final List<Btn> buttons = new ArrayList<>();
	private int px;
	private int py;
	private int pw;
	private int ph;
	private int bodyX;
	private int bodyY;
	private int bodyW;
	private int bodyH;
	private int fieldX;
	private int fieldY;
	private int fieldW;
	private int fieldH;
	private int prevX0;
	private int nextX0;
	private int navY;

	private record Btn(String option, String label, int number, int x, int y, int w, boolean primary, boolean danger, Action action) {
	}

	private enum Action {
		OPTION, REVIEW_DIFF, SEND_TEXT, CANCEL_TEXT
	}

	/** A sample decision shown when nothing real is open (QA / first look); answers are never sent. */
	private @Nullable Decision preview;

	public DecisionScreen(@Nullable String decisionId, @Nullable Screen parent) {
		super(Component.literal("Decisions"));
		this.currentId = decisionId;
		this.parent = parent;
	}

	/** Show {@code sample} as a preview: it looks like the real thing, but nothing is sent to the Foreman. */
	public static DecisionScreen preview(Decision sample) {
		DecisionScreen s = new DecisionScreen(sample.id(), null);
		s.preview = sample;
		return s;
	}

	public boolean isPreview() {
		return preview != null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInputCaptured() {
		return textFocused;
	}

	@Override
	protected void init() {
		Decision d = current();
		if (d != null) {
			currentId = d.id();
			if (d.kind() == DecisionKind.QUESTION && d.options().isEmpty()) {
				focusText(true);
			}
		}
	}

	@Override
	public void removed() {
		focusText(false);
		super.removed();
	}

	// ------------------------------------------------------------------ state

	/** The shown decision: the requested one while it is open (or just answered here), else the queue head. */
	public @Nullable Decision current() {
		if (preview != null) {
			return preview;
		}
		ForemanState s = Foreman.state();
		if (s == null) {
			return null;
		}
		if (currentId != null) {
			Decision d = s.decision(currentId);
			if (d != null && (d.isOpen() || currentId.equals(answeredId) || currentId.equals(sendingId))) {
				return d;
			}
			if (d != null && answeredId == null) {
				// answered somewhere else (console, another client) or withdrawn: say so, then move on
				long now = Util.getMillis();
				String how = d.answer() != null && d.answer().option() != null ? ": " + d.answer().option() : "";
				status = d.id() + " was " + (d.status() == Protocol.DecisionStatus.CANCELLED ? "withdrawn" : "answered" + how);
				statusError = false;
				statusAt = now;
				answeredId = d.id();
				answeredOption = d.answer() != null ? d.answer().option() : null;
				advanceAt = now + 1200;
				return d;
			}
		}
		Decision first = DecisionQueue.first();
		if (first != null && !first.id().equals(currentId)) {
			switchTo(first.id());
		}
		return first;
	}

	public @Nullable String currentId() {
		return currentId;
	}

	public int highlight() {
		return highlight;
	}

	public boolean textFocused() {
		return textFocused;
	}

	public boolean requestChangesMode() {
		return requestChanges;
	}

	public @Nullable String status() {
		return status;
	}

	public String answerText() {
		return answer.value();
	}

	private void switchTo(String id) {
		if (id.equals(currentId)) {
			return;
		}
		currentId = id;
		highlight = 0;
		requestChanges = false;
		confirmRejectUntil = 0;
		answer.clear();
		scroll.toTop();
		Decision d = Foreman.state() == null ? null : Foreman.state().decision(id);
		focusText(d != null && d.kind() == DecisionKind.QUESTION && d.options().isEmpty());
		if (sendingId == null) {
			status = null;
		}
	}

	private void focusText(boolean on) {
		if (textFocused == on) {
			return;
		}
		textFocused = on;
		answer.touch();
		minecraft.onTextInputFocusChange(this, on);
	}

	private void step(int dir) {
		if (preview != null) {
			return;
		}
		List<Decision> q = DecisionQueue.open();
		if (q.isEmpty()) {
			return;
		}
		int i = currentId == null ? -1 : DecisionQueue.indexOf(currentId);
		int n = q.size();
		int next = i < 0 ? 0 : ((i + dir) % n + n) % n;
		switchTo(q.get(next).id());
	}

	private boolean allowsText(Decision d) {
		return d.kind() == DecisionKind.QUESTION || d.kind() == DecisionKind.MERGE && requestChanges;
	}

	@Override
	public void tick() {
		ForemanState s = Foreman.state();
		if (s == null) {
			return;
		}
		long now = Util.getMillis();
		if (answeredId != null && advanceAt > 0 && now >= advanceAt) {
			advanceAt = 0;
			String done = answeredId;
			answeredId = null;
			Decision next = null;
			for (Decision d : DecisionQueue.open()) {
				if (!d.id().equals(done)) {
					next = d;
					break;
				}
			}
			if (next == null) {
				closeWhenEmpty = true;
				status = "All caught up ✓";
				statusError = false;
				statusAt = now;
			} else {
				switchTo(next.id());
			}
		}
		if (closeWhenEmpty && DecisionQueue.open().isEmpty() && now - statusAt > 900) {
			onClose();
			return;
		}
		// fetch the diff summary of a merge once
		Decision d = current();
		if (d != null && d.kind() == DecisionKind.MERGE && d.repoId() != null && d.worktree() != null && !diffs.containsKey(d.id())
			&& Foreman.connected()) {
			String id = d.id();
			diffs.put(id, List.of());
			DiffLink.summary(d.repoId(), d.worktree()).thenAccept(lines -> diffs.put(id, lines));
		}
	}

	// ------------------------------------------------------------------ answering

	private void choose(Decision d, String option) {
		if (sendingId != null || answeredId != null) {
			return;
		}
		if (Foreman.state().isStale()) {
			setStatus("Foreman offline: answers are disabled until it reconnects", true);
			return;
		}
		if (d.kind() == DecisionKind.MERGE && option.equals(Protocol.REQUEST_CHANGES)) {
			if (!requestChanges) {
				requestChanges = true;
				focusText(true);
				setStatus("Say what should change, then Enter", false);
				return;
			}
			if (answer.value().isBlank()) {
				setStatus("Type the feedback for the worker first", true);
				focusText(true);
				return;
			}
		}
		if (d.kind() == DecisionKind.MERGE && option.equals(Protocol.REJECT) && Util.getMillis() > confirmRejectUntil) {
			confirmRejectUntil = Util.getMillis() + 3000;
			setStatus("Reject abandons the branch: press " + (d.options().indexOf(option) + 1) + " again", true);
			return;
		}
		String text = answer.value().isBlank() ? null : answer.value().strip();
		if (d.kind() == DecisionKind.MERGE && !option.equals(Protocol.REQUEST_CHANGES)) {
			text = null;
		}
		send(d, option, text);
	}

	private void sendText(Decision d) {
		String text = answer.value().strip();
		if (d.kind() == DecisionKind.MERGE && requestChanges) {
			choose(d, Protocol.REQUEST_CHANGES);
			return;
		}
		if (text.isEmpty()) {
			setStatus("Type an answer, or pick an option (1-" + Math.max(1, d.options().size()) + ")", true);
			return;
		}
		send(d, null, text);
	}

	private void send(Decision d, @Nullable String option, @Nullable String text) {
		if (preview != null) {
			setStatus("Preview: \"" + (option != null ? option : "your answer") + "\" was not sent", false);
			return;
		}
		sendingId = d.id();
		setStatus("Sending…", false);
		DecisionsFeature.markAnswering(d.id());
		Foreman.answer(d.id(), option, text).whenComplete((Ack ack, Throwable err) -> {
			sendingId = null;
			if (err == null && ack != null && ack.ok()) {
				answeredId = d.id();
				answeredOption = option;
				advanceAt = Util.getMillis() + ADVANCE_MS;
				String what = option != null ? (d.kind() == DecisionKind.PERMISSION ? PermissionBody.buttonLabel(option) : option) : "your answer";
				setStatus("Answered ✓ " + what, false);
				DecisionsFeature.recordAnswer(d.id(), option, text);
				answer.clear();
				focusText(false);
				requestChanges = false;
			} else {
				DecisionsFeature.unmarkAnswering(d.id());
				String msg;
				if (err != null) {
					Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
					msg = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
				} else {
					msg = ack == null ? "no answer from the Foreman" : ack.error() != null ? ack.error() : "the Foreman refused the answer";
				}
				setStatus(msg, true);
			}
		});
	}

	private void setStatus(String s, boolean error) {
		status = s;
		statusError = error;
		statusAt = Util.getMillis();
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		Decision d = current();
		int k = e.key();
		if (e.isEscape()) {
			if (textFocused && (requestChanges || !answer.isEmpty())) {
				if (requestChanges) {
					requestChanges = false;
				}
				focusText(false);
				status = null;
				return true;
			}
			onClose();
			return true;
		}
		if (d == null) {
			if (TextKeys.isEnter(e) || Keys.matches(Keys.decisions, e)) {
				onClose();
			}
			return true;
		}
		if (k == InputConstants.KEY_TAB) {
			if (textFocused && !requestChanges && d.kind() != DecisionKind.QUESTION) {
				focusText(false);
			}
			step(e.hasShiftDown() ? -1 : 1);
			return true;
		}
		if (textFocused) {
			if (TextKeys.isEnter(e)) {
				if (e.hasShiftDown()) {
					answer.insert("\n");
				} else if (requestChanges) {
					choose(d, Protocol.REQUEST_CHANGES);
				} else if (answer.isEmpty() && !d.options().isEmpty()) {
					choose(d, d.options().get(Math.min(highlight, d.options().size() - 1)));
				} else {
					sendText(d);
				}
				return true;
			}
			if (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) {
				if (!answer.vertical(font, fieldW - 12, k == InputConstants.KEY_UP ? -1 : 1, e.hasShiftDown()) && k == InputConstants.KEY_UP && answer
					.isEmpty()) {
					focusText(false);
				}
				return true;
			}
			if (TextKeys.handle(e, answer)) {
				return true;
			}
			return true;
		}
		int digit = TextKeys.digit(e);
		if (digit > 0) {
			if (digit <= d.options().size()) {
				highlight = digit - 1;
				choose(d, d.options().get(digit - 1));
			} else {
				setStatus("No option " + digit, true);
			}
			return true;
		}
		if (TextKeys.isEnter(e) || k == InputConstants.KEY_SPACE) {
			if (!d.options().isEmpty()) {
				choose(d, d.options().get(Math.min(highlight, d.options().size() - 1)));
			} else {
				focusText(true);
			}
			return true;
		}
		if (k == InputConstants.KEY_LEFT || k == InputConstants.KEY_UP) {
			highlight = Math.max(0, highlight - 1);
			return true;
		}
		if (k == InputConstants.KEY_RIGHT || k == InputConstants.KEY_DOWN) {
			highlight = Math.min(Math.max(0, d.options().size() - 1), highlight + 1);
			return true;
		}
		if (k == InputConstants.KEY_PAGEUP) {
			scroll.scrollBy(-4);
			return true;
		}
		if (k == InputConstants.KEY_PAGEDOWN) {
			scroll.scrollBy(4);
			return true;
		}
		if (k == InputConstants.KEY_D && d.kind() == DecisionKind.MERGE) {
			reviewDiff(d);
			return true;
		}
		if (Keys.matches(Keys.decisions, e) && !allowsText(d)) {
			onClose();
			return true;
		}
		if (TextKeys.isPaste(e) && allowsText(d)) {
			focusText(true);
			TextKeys.handle(e, answer);
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		Decision d = current();
		if (d == null) {
			return false;
		}
		int cp = e.codepoint();
		if (textFocused) {
			if (cp >= 32) {
				answer.insert(e.codepointAsString());
			}
			return true;
		}
		// start typing an answer without clicking the field (digits pick options instead)
		if (allowsText(d) && cp > 32 && !(cp >= '0' && cp <= '9')) {
			focusText(true);
			answer.insert(e.codepointAsString());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		Decision d = current();
		double mx = e.x();
		double my = e.y();
		if (d == null) {
			return super.mouseClicked(e, doubleClick);
		}
		for (Btn b : buttons) {
			if (mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + 20) {
				switch (b.action()) {
					case OPTION -> {
						highlight = Math.max(0, d.options().indexOf(b.option()));
						choose(d, b.option());
					}
					case REVIEW_DIFF -> reviewDiff(d);
					case SEND_TEXT -> sendText(d);
					case CANCEL_TEXT -> {
						requestChanges = false;
						focusText(false);
						status = null;
					}
				}
				return true;
			}
		}
		if (fieldH > 0 && mx >= fieldX && mx < fieldX + fieldW && my >= fieldY && my < fieldY + fieldH) {
			focusText(true);
			int idx = answerView.hit(font, answer, fieldX, fieldY, fieldW, fieldStyle(d), mx, my);
			if (idx >= 0) {
				answer.moveTo(idx, e.hasShiftDown());
			}
			return true;
		}
		if (my >= navY && my < navY + 12) {
			if (mx >= prevX0 && mx < prevX0 + 12) {
				step(-1);
				return true;
			}
			if (mx >= nextX0 && mx < nextX0 + 12) {
				step(1);
				return true;
			}
		}
		if (textFocused && answer.isEmpty() && !requestChanges) {
			focusText(false);
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll.scrollBy(scrollY > 0 ? -2 : 2);
		return true;
	}

	private void reviewDiff(Decision d) {
		if (d.repoId() == null || d.worktree() == null) {
			setStatus("This merge has no worktree to diff", true);
			return;
		}
		if (!DiffLink.open(d.repoId(), d.worktree(), d, this)) {
			setStatus("The file list below is the diff summary (no diff screen in this build)", false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// blur the room (the player's menu blur setting) under a soft ink veil, so the paper is the focus
		extractBlurredBackground(g);
		int top = UiStyle.withAlpha(UiStyle.INK, 60);
		int bottom = UiStyle.withAlpha(UiStyle.INK, 110);
		g.fillGradient(0, 0, width, height, top, bottom);
	}

	private TextFieldView.Style fieldStyle(Decision d) {
		String ph = requestChanges ? "What should change? (Enter sends it to the worker)" : d.options().isEmpty() ? "Type your answer… (Enter sends)"
			: "Or type your own answer…";
		return new TextFieldView.Style(null, 0, ph, null, null, 0, 4);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		buttons.clear();
		fieldH = 0;
		Decision d = current();
		ForemanState s = Foreman.state();
		if (d == null || s == null) {
			drawEmpty(g);
			return;
		}
		List<Decision> queue = DecisionQueue.open();
		int qi = DecisionQueue.indexOf(d.id());
		int qn = queue.size();

		pw = Math.min(MAX_W, width - 24);
		Kit.Padding pad = Kit.padding("frame_brass");
		int cx = pad.left();
		int cw = pw - pad.left() - pad.right();

		// ---- measure
		List<FormattedCharSequence> qLines = font.split(Component.literal(d.question()).withStyle(net.minecraft.ChatFormatting.BOLD), cw);
		if (qLines.size() > 5) {
			qLines = qLines.subList(0, 5);
		}
		boolean fieldVisible = d.kind() == DecisionKind.QUESTION || requestChanges;
		int fieldHeight = fieldVisible ? answerView.height(font, answer, cw, fieldStyle(d)) : 0;
		List<Btn> rowButtons = layoutButtons(d, cw);
		int buttonRows = 1;
		for (Btn b : rowButtons) {
			buttonRows = Math.max(buttonRows, b.y() / 24 + 1);
		}
		int fixed = 18 /*header*/ + 26 /*agent*/ + qLines.size() * 10 + 6 + (fieldVisible ? fieldHeight + 6 : 0) + buttonRows * 24 + 16 /*footer*/;
		int maxH = height - 20;
		int bodyNatural = bodyNaturalHeight(d, cw);
		int bodyMax = Math.max(30, maxH - fixed - pad.top() - pad.bottom());
		int bodyHeight = Math.min(bodyNatural, bodyMax);
		ph = pad.top() + fixed + (bodyHeight > 0 ? bodyHeight + 6 : 0) + pad.bottom();
		px = (width - pw) / 2;
		py = Math.max(8, (height - ph) / 2 - 4);

		// ---- frame
		Panels.framed(g, px, py, pw, ph);
		int x = px + cx;
		int y = py + pad.top();

		// header: kind icon + title, queue position on the right
		String title = switch (d.kind()) {
			case PERMISSION -> "Permission needed";
			case MERGE -> "Merge review";
			default -> "Decision needed";
		};
		Panels.sprite(g, Kit.HEADER, x - 2, y - 2, cw + 4, 16);
		Panels.sprite(g, Kit.icon(d.kind() == DecisionKind.MERGE ? "merge" : d.kind() == DecisionKind.PERMISSION ? "bash" : "decision"), x + 2, y - 1, 12,
			12);
		g.text(font, title, x + 18, y + 1, UiBits.ink(), false);
		UiBits.pulsingDot(g, "waiting", x + 18 + font.width(title) + 6, y + 1);
		navY = y - 1;
		if (preview != null) {
			// a sample, not a real request: say so where the queue position would be
			String tag = "preview";
			int tw = font.width(tag) + 10;
			Panels.sprite(g, Kit.PILL, x + cw - tw, navY, tw, 11);
			g.text(font, tag, x + cw - tw + 5, navY + 2, UiBits.muted(), false);
			prevX0 = nextX0 = -100;
		} else if (qn > 1 || qi < 0) {
			String pos = (qi < 0 ? "✓" : (qi + 1) + "/" + qn);
			int pwid = font.width(pos) + 10;
			nextX0 = x + cw - 12;
			Panels.sprite(g, Kit.KEYCAP, nextX0, navY, 12, 12);
			g.text(font, "›", nextX0 + 4, navY + 2, UiBits.ink(), false);
			int pillX = nextX0 - 3 - pwid;
			Panels.sprite(g, Kit.PILL, pillX, navY, pwid, 11);
			g.text(font, pos, pillX + 5, navY + 2, UiBits.ink(), false);
			prevX0 = pillX - 15;
			Panels.sprite(g, Kit.KEYCAP, prevX0, navY, 12, 12);
			g.text(font, "‹", prevX0 + 4, navY + 2, UiBits.ink(), false);
		} else {
			prevX0 = nextX0 = -100;
		}
		y += 18;

		// agent row
		UiBits.framedPortrait(g, d.agentId(), x, y, 1);
		String name = UiBits.agentName(d.agentId());
		String verb = switch (d.kind()) {
			case PERMISSION -> " wants your permission";
			case MERGE -> " asks you to review a merge";
			default -> " asks you";
		};
		g.text(font, name, x + 26, y + 1, UiBits.nameOnLight(d.agentId()), false);
		g.text(font, TextUtil.ellipsize(font, verb, cw - 26 - font.width(name)), x + 26 + font.width(name), y + 1, UiBits.ink(), false);
		StringBuilder sub = new StringBuilder(DecisionQueue.kindLabel(d.kind())).append(" · ").append(UiBits.ago(d.createdAt()));
		Task t = d.taskId() == null ? null : s.task(d.taskId());
		if (t != null) {
			sub.append(" · ").append(t.id()).append(' ').append(UiBits.oneLine(t.title()));
		}
		g.text(font, TextUtil.ellipsize(font, sub.toString(), cw - 26), x + 26, y + 11, UiBits.muted(), false);
		y += 26;

		// question
		for (FormattedCharSequence line : qLines) {
			g.text(font, line, x, y, UiBits.ink(), false);
			y += 10;
		}
		y += 6;

		// body
		bodyX = x;
		bodyY = y;
		bodyW = cw;
		bodyH = bodyHeight;
		if (bodyHeight > 0) {
			drawBody(g, d, x, y, cw, bodyHeight);
			y += bodyHeight + 6;
		}

		// free-text field
		if (fieldVisible) {
			fieldX = x;
			fieldY = y;
			fieldW = cw;
			fieldH = answerView.draw(g, font, answer, x, y, cw, textFocused, fieldStyle(d));
			y += fieldH + 6;
		}

		// buttons
		long now = Util.getMillis();
		boolean busy = sendingId != null || answeredId != null || s.isStale();
		for (Btn b : rowButtons) {
			Btn placed = new Btn(b.option(), b.label(), b.number(), x + b.x(), y + b.y(), b.w(), b.primary(), b.danger(), b.action());
			buttons.add(placed);
			boolean hover = mouseX >= placed.x() && mouseX < placed.x() + placed.w() && mouseY >= placed.y() && mouseY < placed.y() + 20;
			boolean hl = b.action() == Action.OPTION && !textFocused && d.options().indexOf(b.option()) == highlight;
			boolean chosen = answeredId != null && answeredId.equals(d.id()) && b.action() == Action.OPTION && b.option().equals(answeredOption);
			UiBits.ButtonState st = chosen ? UiBits.ButtonState.PRESSED : busy && b.action() != Action.REVIEW_DIFF ? UiBits.ButtonState.DISABLED
				: hover || hl ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL;
			String label = chosen ? "\u2713 " + b.label() : b.label();
			int number = chosen ? 0 : b.number();
			if (b.option().equals(Protocol.REJECT) && now < confirmRejectUntil) {
				label = "Confirm reject";
			}
			if (b.option().equals(Protocol.REQUEST_CHANGES) && requestChanges) {
				label = "Send feedback";
			}
			UiBits.button(g, font, label, number, placed.x(), placed.y(), b.w(), b.primary(), st, b.danger());
		}
		y += buttonRows * 24;

		// footer: key hints left, status right
		int fy = py + ph - pad.bottom() - 12;
		drawFooter(g, d, x, fy, cw, queue, qi);
	}

	private void drawEmpty(GuiGraphicsExtractor g) {
		int w = Math.min(260, width - 24);
		int h = 64;
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		Panels.panel(g, x, y, w, h);
		Kit.Padding p = Kit.padding("panel_paper");
		boolean stale = Foreman.state() == null || Foreman.state().isStale();
		String head = status != null && !statusError ? status : stale ? "Foreman offline" : "No decisions waiting";
		Panels.dot(g, stale ? "idle" : "done", x + p.left(), y + p.top() + 1, false);
		g.text(font, head, x + p.left() + 12, y + p.top(), UiBits.ink(), false);
		String sub = stale ? "decisions show up again once it reconnects" : "your team will ask when they need you";
		g.text(font, TextUtil.ellipsize(font, sub, w - p.left() - p.right()), x + p.left(), y + p.top() + 14, UiBits.muted(), false);
		UiBits.hints(g, font, x + p.left(), y + h - p.bottom() - 13, false, "Esc", "close");
	}

	private void drawFooter(GuiGraphicsExtractor g, Decision d, int x, int y, int w, List<Decision> queue, int qi) {
		List<String> hints = new ArrayList<>();
		if (textFocused) {
			hints.add("Enter");
			hints.add(requestChanges ? "send feedback" : "send");
			hints.add("Esc");
			hints.add(requestChanges ? "back" : "stop typing");
		} else {
			if (!d.options().isEmpty()) {
				hints.add(d.options().size() == 1 ? "1" : "1-" + d.options().size());
				hints.add("choose");
			}
			if (d.kind() == DecisionKind.MERGE) {
				hints.add("D");
				hints.add("diff");
			}
			if (queue.size() > 1 && preview == null) {
				hints.add("Tab");
				hints.add("next");
			}
			hints.add("Esc");
			hints.add("later");
		}
		String[] arr = hints.toArray(String[]::new);
		int hw = UiBits.hintsWidth(font, arr);
		boolean showStatus = status != null && (statusError ? Util.getMillis() - statusAt < 7000 : true);
		int statusRoom = w - hw - 10;
		if (showStatus && statusRoom < 60) {
			// the status wins: keep only the first hint pair
			arr = new String[] {arr[0], arr[1]};
			hw = UiBits.hintsWidth(font, arr);
			statusRoom = w - hw - 10;
		}
		UiBits.hints(g, font, x, y, false, arr);
		if (showStatus) {
			String st = TextUtil.ellipsize(font, status, statusRoom);
			int color = statusError ? UiBits.errorText() : status.contains("✓") ? UiBits.okText() : UiBits.muted();
			g.text(font, st, x + w - font.width(st), y + 2, color, false);
		} else if (Foreman.state().isStale()) {
			String st = TextUtil.ellipsize(font, "Foreman offline: read-only", statusRoom);
			g.text(font, st, x + w - font.width(st), y + 2, UiBits.errorText(), false);
		} else if (queue.size() > 1 && preview == null) {
			Decision next = queue.get(((qi < 0 ? 0 : qi) + 1) % queue.size());
			if (!next.id().equals(d.id())) {
				String st = TextUtil.ellipsize(font, "next: " + UiBits.agentName(next.agentId()) + " · " + DecisionQueue.kindLabel(next.kind()),
					statusRoom);
				g.text(font, st, x + w - font.width(st), y + 2, UiBits.muted(), false);
			}
		}
	}

	private List<Btn> layoutButtons(Decision d, int cw) {
		List<Btn> out = new ArrayList<>();
		int bx = 0;
		int row = 0;
		if (d.kind() == DecisionKind.MERGE) {
			int w = UiBits.buttonWidth(font, "Review diff", 0);
			out.add(new Btn("", "Review diff", 0, 0, 0, w, false, false, Action.REVIEW_DIFF));
			bx = w + 10;
		}
		for (int i = 0; i < d.options().size(); i++) {
			String opt = d.options().get(i);
			String label = d.kind() == DecisionKind.PERMISSION ? PermissionBody.buttonLabel(opt) : opt;
			if (opt.equals(Protocol.REQUEST_CHANGES) && requestChanges) {
				label = "Send feedback";
			}
			// size for the widest label the button can show (Reject -> Confirm reject, Request changes -> Send feedback)
			int w = UiBits.buttonWidth(font, label, i + 1);
			if (opt.equals(Protocol.REJECT)) {
				w = Math.max(w, UiBits.buttonWidth(font, "Confirm reject", i + 1));
			} else if (opt.equals(Protocol.REQUEST_CHANGES)) {
				w = Math.max(w, UiBits.buttonWidth(font, "Request changes", i + 1));
			}
			w = Math.min(cw, w);
			if (bx > 0 && bx + w > cw) {
				row++;
				bx = 0;
			}
			boolean danger = opt.equals(Protocol.REJECT) || opt.equals(Protocol.DENY);
			boolean primary = i == 0 && !(d.kind() == DecisionKind.MERGE && requestChanges);
			if (d.kind() == DecisionKind.MERGE && requestChanges && opt.equals(Protocol.REQUEST_CHANGES)) {
				primary = true;
			}
			out.add(new Btn(opt, label, i + 1, bx, row * 24, w, primary, danger, Action.OPTION));
			bx += w + 4;
		}
		if (d.options().isEmpty() && d.kind() == DecisionKind.QUESTION) {
			int w = UiBits.buttonWidth(font, "Send answer", 0);
			out.add(new Btn("", "Send answer", 0, 0, 0, w, true, false, Action.SEND_TEXT));
		}
		if (requestChanges) {
			int w = UiBits.buttonWidth(font, "Cancel", 0);
			if (bx > 0 && bx + w > cw) {
				row++;
				bx = 0;
			}
			out.add(new Btn("", "Cancel", 0, bx, row * 24, w, false, false, Action.CANCEL_TEXT));
		}
		// right-align the last row of option buttons for a calmer layout
		return out;
	}

	// ------------------------------------------------------------------ bodies

	/** One line of a body well: text runs with colours, an optional row tint. */
	private record Run(String text, int color) {
	}

	private record Row(List<Run> runs, int bg, List<Run> right) {
		Row(List<Run> runs, int bg) {
			this(runs, bg, List.of());
		}

		static Row of(String text, int color) {
			return new Row(List.of(new Run(text, color)), 0);
		}
	}

	private int bodyNaturalHeight(Decision d, int cw) {
		if (d.kind() == DecisionKind.PERMISSION) {
			return PermissionBody.height(font, d, cw);
		}
		List<Row> rows = bodyRows(d, cw - 14);
		if (rows.isEmpty()) {
			return 0;
		}
		return 8 + rows.size() * 10;
	}

	private void drawBody(GuiGraphicsExtractor g, Decision d, int x, int y, int w, int h) {
		if (d.kind() == DecisionKind.PERMISSION) {
			g.enableScissor(x, y, x + w, y + h);
			PermissionBody.draw(g, font, d, x, y, w);
			g.disableScissor();
			return;
		}
		List<Row> rows = bodyRows(d, w - 14);
		Panels.inset(g, x, y, w, h);
		int view = Math.max(1, (h - 8) / 10);
		scroll.update(rows.size(), view);
		if (scroll.following() && rows.size() > view) {
			// start at the top, not the tail: context reads top-down
			scroll.toTop();
		}
		int ly = y + 4;
		g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
		for (int i = scroll.offset(); i < Math.min(rows.size(), scroll.offset() + view); i++) {
			Row r = rows.get(i);
			if (r.bg() != 0) {
				g.fill(x + 3, ly - 1, x + w - (scroll.scrollable() ? 10 : 3), ly + 9, r.bg());
			}
			int rx = x + 6;
			for (Run run : r.runs()) {
				g.text(font, run.text(), rx, ly, run.color(), false);
				rx += font.width(run.text());
			}
			int rw = 0;
			for (Run run : r.right()) {
				rw += font.width(run.text());
			}
			int rxr = x + w - (scroll.scrollable() ? 12 : 6) - rw;
			for (Run run : r.right()) {
				g.text(font, run.text(), rxr, ly, run.color(), false);
				rxr += font.width(run.text());
			}
			ly += 10;
		}
		g.disableScissor();
		Panels.scrollbar(g, x + w - 8, y + 2, h - 4, scroll, false);
	}

	private List<Row> bodyRows(Decision d, int w) {
		List<Row> rows = new ArrayList<>();
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		int path = UiStyle.color("paper.path", 0xFF6C5415);
		int addFg = UiStyle.color("paper.add_fg", 0xFF455746);
		int delFg = UiStyle.color("paper.del_fg", 0xFF873C2A);
		if (d.kind() == DecisionKind.MERGE) {
			ForemanState s = Foreman.state();
			Worktree wt = null;
			if (d.repoId() != null && d.worktree() != null && s.repo(d.repoId()) != null) {
				Repo r = s.repo(d.repoId());
				for (Worktree x : r.worktrees()) {
					if (x.id().equals(d.worktree())) {
						wt = x;
					}
				}
			}
			if (wt != null) {
				rows.add(new Row(List.of(new Run(TextUtil.ellipsize(font, wt.branch(), w - 50), path), new Run(" → " + wt.base(), path)), 0));
				List<Run> stat = new ArrayList<>(List.of(new Run(UiBits.plural(wt.files(), "file", "files") + "  ", ink), new Run("+" + wt.additions(), addFg),
					new Run(" −" + wt.deletions(), delFg), new Run("  ·  " + UiBits.plural(wt.ahead(), "commit", "commits"), muted)));
				String ctxl = d.context() == null ? "" : d.context().toLowerCase(java.util.Locale.ROOT);
				if (ctxl.contains("tests: pass")) {
					stat.add(new Run("  ·  tests pass", addFg));
				} else if (ctxl.contains("tests: fail")) {
					stat.add(new Run("  ·  tests fail", UiBits.errorText()));
				}
				rows.add(new Row(stat, 0));
			}
			List<DiffLink.SummaryLine> files = diffs.get(d.id());
			if (files != null && !files.isEmpty()) {
				for (DiffLink.SummaryLine l : files) {
					if (l.header()) {
						continue;
					}
					if (l.error()) {
						rows.add(Row.of(TextUtil.ellipsize(font, l.text(), w), UiBits.errorText()));
						continue;
					}
					String counts = l.additions() + l.deletions() > 0 ? "+" + l.additions() + " −" + l.deletions() : "";
					String pathPart = l.text().strip();
					int room = w - font.width(counts) - 16;
					List<Run> right = counts.isEmpty() ? List.of() : List.of(new Run("+" + l.additions(), addFg), new Run(" −" + l.deletions(), delFg));
					rows.add(new Row(List.of(new Run(TextUtil.ellipsize(font, pathPart, room), ink)), 0, right));
				}
			} else if (files != null && Foreman.connected()) {
				rows.add(Row.of("  loading the file list…", muted));
			}
		}
		String ctx = d.context() == null ? "" : d.context().strip();
		if (!ctx.isEmpty()) {
			if (!rows.isEmpty()) {
				rows.add(Row.of("", ink));
			}
			// +/- lines are tinted as a diff only inside a diff block (after a file path or an @@ header);
			// elsewhere "- item" is just a list
			boolean inDiff = false;
			for (String para : ctx.split("\n")) {
				String p = para.stripTrailing();
				int color = ink;
				int bg = 0;
				boolean isPath = p.matches("^[\\w./\\\\-]+\\.[a-zA-Z0-9]{1,5}$");
				boolean diffLine = p.startsWith("+") || p.startsWith("-") || p.startsWith("@@") || p.startsWith(" ");
				if (isPath || p.startsWith("@@")) {
					inDiff = true;
				} else if (!diffLine) {
					inDiff = false;
				}
				if (p.startsWith("Merge refused:")) {
					color = UiBits.errorText();
				} else if (inDiff && p.startsWith("@@")) {
					color = UiStyle.color("paper.hunk", 0xFF195E5D);
				} else if (inDiff && p.startsWith("+")) {
					color = addFg;
					bg = UiStyle.color("paper.add_bg", 0xFFD6DDCC);
				} else if (inDiff && p.startsWith("-")) {
					color = delFg;
					bg = UiStyle.color("paper.del_bg", 0xFFF2D5C4);
				} else if (isPath) {
					color = path;
				} else if (d.kind() == DecisionKind.MERGE && p.matches("^\\d+ files?, \\+\\d+ -\\d+.*") && !rows.isEmpty()) {
					// the diff stat line duplicates the stats row above
					continue;
				}
				for (String line : TextUtil.wrapPlain(font, p, w)) {
					rows.add(new Row(List.of(new Run(line, color)), bg));
				}
			}
		}
		return rows;
	}
}
