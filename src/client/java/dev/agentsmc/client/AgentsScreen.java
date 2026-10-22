package dev.agentsmc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.Window;
import dev.agentsmc.net.MenuPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The agents menu, laid out like a chat app: agents down the left with what each is doing, the
 * selected agent's activity on the right, and a message box at the bottom. The state comes from the
 * server (see Brains.snapshot), polled twice a second while the menu is open.
 *
 * <p>A chat layout needs more room than Minecraft's GUI scale leaves (about 430 by 240 at 720p), so
 * the menu draws at a smaller whole-number pixel scale: one menu pixel per N window pixels, with N
 * chosen so the menu is at least about 620 pixels wide. The pixel font stays crisp. Layout and input
 * use these menu coordinates.
 */
public final class AgentsScreen extends Screen {
	private static final int BG = 0xFF0F0F13;
	private static final int SIDEBAR = 0xFF141418;
	private static final int BORDER = 0xFF26262D;
	private static final int ROW_SELECTED = 0xFF25252C;
	private static final int ROW_HOVER = 0xFF1C1C22;
	private static final int TEXT = 0xFFE6E6EA;
	private static final int TEXT_NAME = 0xFFC8C8D0;
	private static final int TEXT_DIM = 0xFF8D8D99;
	private static final int TEXT_FAINT = 0xFF5F5F6B;
	private static final int TOOL = 0xFF7E7E8B;
	private static final int BUBBLE = 0xFF26262E;
	private static final int ACCENT = 0xFF3B82F6;
	private static final int WORKING = 0xFFF5A524;
	private static final int WORKING_TEXT = 0xFFC4943F;
	private static final int STARTING = 0xFF60A5FA;
	private static final int IDLE = 0xFF5F5F6B;
	private static final int SAY = 0xFF4ADE80;
	private static final int ERROR = 0xFFF87171;
	private static final int FIELD = 0xFF18181D;
	private static final int FIELD_BORDER = 0xFF35353F;
	private static final int FIELD_FOCUSED = 0xFF4A4A56;
	private static final int CLAUDE = 0xFFD97757;
	private static final int ROW_H = 30;
	private static final int OPTION_H = 14;
	private static final int HEADER_H = 24;

	private record Agent(String name, String status, String activity, long last, boolean body, String preview, String model, String effort) {
		boolean busy() {
			return !status.equals("idle");
		}
	}

	private record Entry(long id, long time, String kind, String who, String text) {
	}

	/** A laid-out log entry: where it starts, its height, and how to draw it with its top at a given y. */
	private record Block(int y, int height, IntConsumer draw) {
	}

	private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	/** A model or effort the server offers. An empty id means Claude Code's default. */
	private record Option(String id, String label) {
	}

	private record Settings(String model, String effort) {
	}

	/** A change sent to the server, shown until a snapshot confirms it so the old value doesn't flash back. */
	private record Pending(String agent, Settings settings, long until) {
	}

	private enum Picker {
		MODEL, EFFORT
	}

	private List<Agent> agents = List.of();
	private List<Entry> log = List.of();
	private boolean loaded;
	private boolean canUse = true;
	private List<Option> models = List.of();
	private List<Option> efforts = List.of();
	/** What a new agent starts with, from the host's config. */
	private Settings defaults = new Settings("", "");
	/** The model and effort picked in the new-agent form. */
	private Settings newSettings = defaults;
	private Pending pending;
	/** The open model or effort list, or null. */
	private Picker picker;
	/** The highlighted row in the open list. */
	private int pickerIndex;
	private Hit modelChip, effortChip;
	private final List<Hit> pickerRows = new ArrayList<>();
	/** The agent whose log is shown, or null. */
	private String selected;
	/** Showing the new-agent form instead of an agent. */
	private boolean creating;
	/** Server clock minus ours, for "2m" labels. */
	private long clockOffset;
	private String error;
	private long errorUntil;
	/** Pixels scrolled up from the newest entry. */
	private int scroll;
	/** Pixels the agent list is scrolled down. */
	private int sideScroll;
	private int ticks;

	private EditBox composer;
	private EditBox nameBox;
	private String hint = "";
	private final List<Hit> hits = new ArrayList<>();

	/** Menu pixels to GUI pixels, at most 1. */
	private float zoom = 1;
	/** Size in menu pixels. */
	private int vw, vh;
	private int sideW, mainX, colX, colW, composerTop, logTop, logBottom;
	private List<FormattedCharSequence> createText = List.of();

	private List<Block> blocks;
	private long blocksKey = -1;
	private int blocksWidth;
	private int contentHeight;
	/** The graphics of the frame being drawn, for the log's draw callbacks. */
	private GuiGraphicsExtractor current;

	public AgentsScreen() {
		super(Component.literal("Agents"));
	}

	// ---- setup and state ----

	@Override
	protected void init() {
		Window window = minecraft.getWindow();
		int guiScale = Math.max(1, window.getGuiScale());
		int scale = Mth.clamp(window.getWidth() / 620, 1, guiScale);
		zoom = (float) scale / guiScale;
		vw = Math.round(width / zoom);
		vh = Math.round(height / zoom);

		sideW = Mth.clamp(vw * 3 / 10, 110, 170);
		mainX = sideW;
		int mainW = vw - sideW;
		colW = Math.min(mainW - 32, 400);
		colX = mainX + (mainW - colW) / 2;
		composerTop = vh - 46;
		logTop = HEADER_H + 1;
		logBottom = composerTop - 16;
		createText = font.split(Component.literal(
				"It joins the world as a player in front of you, and runs Claude Code on the host's computer. "
						+ "Give it a name, and a first task below if you like."), colW);
		int nameY = logTop + 28 + createText.size() * (font.lineHeight + 1) + 22;

		String draft = composer == null ? "" : composer.getValue();
		String name = nameBox == null ? "" : nameBox.getValue();
		composer = new EditBox(font, colX + 8, composerTop + 8, colW - 16, 12, Component.literal("Message"));
		composer.setBordered(false);
		composer.setMaxLength(2000);
		composer.setValue(draft);
		addRenderableWidget(composer);
		nameBox = new EditBox(font, colX + 7, nameY + 6, Math.min(colW, 180) - 14, 12, Component.literal("Agent name"));
		nameBox.setBordered(false);
		nameBox.setMaxLength(16);
		nameBox.setValue(name);
		addRenderableWidget(nameBox);
		blocks = null;
		updateWidgets();
		setInitialFocus(creating ? nameBox : composer);
		request();
	}

	private void updateWidgets() {
		nameBox.visible = creating;
		nameBox.active = creating;
		composer.setEditable(canUse && (creating || selected != null));
		if (!canUse) hint = "Only operators can talk to agents here";
		else if (creating) hint = "First task (optional)";
		else if (selected != null) hint = "Message " + selected + "…";
		else hint = "Create an agent with +";
	}

	@Override
	public void tick() {
		if (++ticks % 10 == 0) request();
	}

	private void request() {
		ClientPlayNetworking.send(new MenuPayloads.Request(selected == null ? "" : selected));
	}

	/** A state snapshot from the server. */
	void accept(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		clockOffset = o.get("now").getAsLong() - System.currentTimeMillis();
		canUse = o.get("canUse").getAsBoolean();
		models = options(o, "models");
		efforts = options(o, "efforts");
		defaults = new Settings(o.get("model").getAsString(), o.get("effort").getAsString());
		List<Agent> list = new ArrayList<>();
		for (JsonElement el : o.getAsJsonArray("agents")) {
			JsonObject a = el.getAsJsonObject();
			list.add(new Agent(a.get("name").getAsString(), a.get("status").getAsString(), a.get("activity").getAsString(),
					a.get("last").getAsLong(), a.get("body").getAsBoolean(), a.get("preview").getAsString(),
					a.get("model").getAsString(), a.get("effort").getAsString()));
		}
		agents = list;
		if (pending != null) {
			Agent p = agent(pending.agent());
			if (p == null || new Settings(p.model(), p.effort()).equals(pending.settings())) pending = null;
		}
		if (o.has("error")) showError(o.get("error").getAsString());

		String sel = o.has("selected") ? o.get("selected").getAsString() : null;
		if (sel != null && sel.equalsIgnoreCase(selected)) {
			selected = sel;
			List<Entry> entries = new ArrayList<>();
			for (JsonElement el : o.getAsJsonArray("log")) {
				JsonObject e = el.getAsJsonObject();
				entries.add(new Entry(e.get("id").getAsLong(), e.get("t").getAsLong(), e.get("k").getAsString(),
						e.has("who") ? e.get("who").getAsString() : null, e.get("text").getAsString()));
			}
			log = entries;
		} else if (selected != null && agent(selected) == null) {
			selected = null;
			log = List.of();
		}
		if (selected == null && !creating && !agents.isEmpty()) select(agents.getFirst().name());
		loaded = true;
		updateWidgets();
	}

	private Agent agent(String name) {
		for (Agent a : agents) if (a.name().equalsIgnoreCase(name)) return a;
		return null;
	}

	private static List<Option> options(JsonObject o, String key) {
		List<Option> out = new ArrayList<>();
		for (JsonElement el : o.getAsJsonArray(key)) {
			JsonObject j = el.getAsJsonObject();
			out.add(new Option(j.get("id").getAsString(), j.get("label").getAsString()));
		}
		return out;
	}

	/** The model and effort the composer shows: the new agent's, or the selected agent's. Null if neither. */
	private Settings settings() {
		if (creating) return newSettings;
		Agent a = selected == null ? null : agent(selected);
		if (a == null) return null;
		if (pending != null && pending.agent().equalsIgnoreCase(a.name()) && System.currentTimeMillis() < pending.until()) return pending.settings();
		return new Settings(a.model(), a.effort());
	}

	// ---- actions ----

	private void select(String name) {
		closePicker();
		selected = name;
		creating = false;
		log = List.of();
		blocks = null;
		scroll = 0;
		updateWidgets();
		setFocused(composer);
		request();
	}

	private void startCreating() {
		if (!creating) newSettings = defaults;
		closePicker();
		creating = true;
		updateWidgets();
		setFocused(nameBox);
	}

	private void submit() {
		if (!canUse) return;
		String text = composer.getValue().strip();
		if (creating) {
			String name = nameBox.getValue().strip();
			if (!name.matches("[A-Za-z0-9_]{3,16}")) {
				showError("Names are 3 to 16 letters, digits or underscores.");
				setFocused(nameBox);
				return;
			}
			ClientPlayNetworking.send(new MenuPayloads.Action("spawn", name, text, newSettings.model(), newSettings.effort()));
			nameBox.setValue("");
			composer.setValue("");
			select(name);
		} else if (selected != null && !text.isEmpty()) {
			ClientPlayNetworking.send(new MenuPayloads.Action("send", selected, text));
			composer.setValue("");
			scroll = 0;
		}
	}

	private void togglePicker(Picker which) {
		if (picker == which) {
			closePicker();
			return;
		}
		Settings now = settings();
		if (now == null) return;
		picker = which;
		pickerIndex = Math.max(0, which == Picker.MODEL ? indexOf(models, now.model()) : indexOf(efforts, now.effort()));
	}

	private void closePicker() {
		picker = null;
		pickerRows.clear();
	}

	private void choose(Picker which, String id) {
		closePicker();
		setFocused(creating && nameBox.getValue().isBlank() ? nameBox : composer);
		Settings now = settings();
		if (now == null || !canUse) return;
		Settings next = which == Picker.MODEL ? new Settings(id, now.effort()) : new Settings(now.model(), id);
		if (next.equals(now)) return;
		if (creating) {
			newSettings = next;
		} else if (selected != null) {
			pending = new Pending(selected, next, System.currentTimeMillis() + 3000);
			ClientPlayNetworking.send(new MenuPayloads.Action("configure", selected, "", next.model(), next.effort()));
		}
	}

	private void stopSelected() {
		if (selected != null) ClientPlayNetworking.send(new MenuPayloads.Action("stop", selected, ""));
	}

	private void showError(String message) {
		error = message;
		errorUntil = System.currentTimeMillis() + 6000;
	}

	// ---- input (converted to menu coordinates) ----

	private MouseButtonEvent toMenu(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() / zoom, event.y() / zoom, event.buttonInfo());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (picker != null) {
			List<Option> options = picker == Picker.MODEL ? models : efforts;
			if (event.isEscape()) {
				closePicker();
				return true;
			}
			if ((event.isUp() || event.isDown()) && !options.isEmpty()) {
				pickerIndex = Math.floorMod(pickerIndex + (event.isUp() ? -1 : 1), options.size());
				return true;
			}
			if (event.isConfirmation()) {
				if (pickerIndex < options.size()) choose(picker, options.get(pickerIndex).id());
				return true;
			}
		}
		if (event.isConfirmation()) {
			if (creating && nameBox.isFocused()) {
				setFocused(composer);
				return true;
			}
			if (composer.isFocused()) {
				submit();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent e = toMenu(event);
		if (picker != null) {
			// An open list takes the click: pick an option, or close the list like a click outside a menu would.
			for (Hit h : List.copyOf(pickerRows)) {
				if (h.contains(e.x(), e.y())) {
					h.action().run();
					return true;
				}
			}
			closePicker();
			return true;
		}
		if (super.mouseClicked(e, doubleClick)) return true;
		for (Hit h : List.copyOf(hits)) {
			if (h.contains(e.x(), e.y())) {
				h.action().run();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(toMenu(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return super.mouseDragged(toMenu(event), dx / zoom, dy / zoom);
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(x / zoom, y / zoom);
	}

	@Override
	public boolean mouseScrolled(double guiX, double guiY, double scrollX, double scrollY) {
		double x = guiX / zoom, y = guiY / zoom;
		if (x < sideW) {
			sideScroll -= (int) (scrollY * ROW_H);
			return true;
		}
		if (x >= mainX && y >= logTop && y < logBottom) {
			scroll += (int) (scrollY * 18);
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		// Pausing a single-player world would pause the agents too.
		return false;
	}

	// ---- drawing ----

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, BG);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int guiMouseX, int guiMouseY, float partialTick) {
		hits.clear();
		current = g;
		int mouseX = (int) (guiMouseX / zoom), mouseY = (int) (guiMouseY / zoom);
		g.pose().pushMatrix();
		g.pose().scale(zoom, zoom);
		drawSidebar(g, mouseX, mouseY);
		drawHeader(g, mouseX, mouseY);
		if (creating) drawCreate(g);
		else if (selected != null) drawLog(g);
		else drawEmpty(g);
		drawComposer(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		drawPicker(g, mouseX, mouseY);
		g.pose().popMatrix();
	}

	private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
		g.fill(0, 0, sideW, vh, SIDEBAR);
		g.verticalLine(sideW - 1, 0, vh, BORDER);
		g.text(font, "Agents", 10, 9, TEXT, false);

		int px = sideW - 22, py = 5;
		Hit plus = new Hit(px, py, px + 16, py + 15, this::startCreating);
		if (creating || plus.contains(mx, my)) roundRect(g, px, py, px + 16, py + 15, creating ? ROW_SELECTED : ROW_HOVER);
		g.text(font, "+", px + 8 - font.width("+") / 2, py + 4, TEXT, false);
		hits.add(plus);

		int listTop = 24, listBottom = vh - 18;
		sideScroll = Mth.clamp(sideScroll, 0, Math.max(0, agents.size() * ROW_H - (listBottom - listTop)));
		g.enableScissor(0, listTop, sideW, listBottom);
		int y = listTop - sideScroll;
		for (Agent a : agents) {
			if (y + ROW_H <= listTop || y >= listBottom) {
				y += ROW_H;
				continue;
			}
			boolean sel = !creating && a.name().equalsIgnoreCase(selected);
			Hit row = new Hit(4, Math.max(y, listTop), sideW - 5, Math.min(y + ROW_H - 2, listBottom), () -> select(a.name()));
			if (sel) roundRect(g, 4, y, sideW - 5, y + ROW_H - 2, ROW_SELECTED);
			else if (row.contains(mx, my)) roundRect(g, 4, y, sideW - 5, y + ROW_H - 2, ROW_HOVER);
			hits.add(row);

			g.fill(10, y + 7, 14, y + 11, statusColor(a));
			String time = ago(a.last());
			int tw = font.width(time);
			g.text(font, time, sideW - 11 - tw, y + 6, TEXT_FAINT, false);
			g.text(font, ellipsize(a.name(), sideW - 34 - tw), 19, y + 6, sel ? TEXT : TEXT_NAME, false);
			String sub = a.busy() ? a.activity() : !a.body() ? "No body in the world" : a.preview();
			g.text(font, ellipsize(sub, sideW - 30), 19, y + 17, a.busy() ? WORKING_TEXT : TEXT_DIM, false);
			y += ROW_H;
		}
		g.disableScissor();
		if (loaded && agents.isEmpty()) g.text(font, "No agents yet", 10, 30, TEXT_FAINT, false);
		g.text(font, ellipsize("Chat works too: @Name …", sideW - 16), 10, vh - 13, TEXT_FAINT, false);
	}

	private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
		g.horizontalLine(mainX, vw, HEADER_H, BORDER);
		int x = mainX + 12;
		if (creating) {
			g.text(font, "New agent", x, 9, TEXT, false);
			return;
		}
		Agent a = selected == null ? null : agent(selected);
		g.text(font, "Agents", x, 9, TEXT_DIM, false);
		if (a == null) return;
		x += font.width("Agents  ");
		g.text(font, "/", x, 9, TEXT_FAINT, false);
		x += font.width("/  ");
		g.text(font, a.name(), x, 9, TEXT, false);

		int right = vw - 12;
		if (a.busy() && canUse) {
			String label = "Stop";
			int w = font.width(label) + 14;
			Hit stop = new Hit(right - w, 5, right, 19, this::stopSelected);
			boolean hover = stop.contains(mx, my);
			roundRect(g, stop.x0(), stop.y0(), stop.x1(), stop.y1(), hover ? 0xFF3A2A2E : 0xFF2A2A31);
			g.text(font, label, stop.x0() + 7, 8, hover ? ERROR : TEXT, false);
			hits.add(stop);
			right -= w + 10;
		}
		String status = switch (a.status()) {
			case "working" -> "Working";
			case "starting" -> "Starting";
			default -> a.body() ? "Idle" : "No body";
		};
		int sw = font.width(status);
		g.text(font, status, right - sw, 9, a.busy() ? statusColor(a) | 0xFF000000 : TEXT_DIM, false);
		g.fill(right - sw - 9, 11, right - sw - 5, 15, statusColor(a));
	}

	private void drawEmpty(GuiGraphicsExtractor g) {
		int cy = (logTop + logBottom) / 2 - 20;
		centered(g, loaded ? "No agents yet" : "Loading…", cy, TEXT);
		if (!loaded) return;
		centered(g, "Press + to create one, or run /agent spawn <name>.", cy + 14, TEXT_DIM);
		centered(g, "Each agent is a player run by Claude Code on the host's computer.", cy + 26, TEXT_FAINT);
	}

	private void drawCreate(GuiGraphicsExtractor g) {
		int y = logTop + 14;
		g.text(font, "Create an agent", colX, y, TEXT, false);
		y += 14;
		for (FormattedCharSequence line : createText) {
			g.text(font, line, colX, y, TEXT_DIM, false);
			y += font.lineHeight + 1;
		}
		int fx0 = nameBox.getX() - 7, fy0 = nameBox.getY() - 6, fx1 = fx0 + nameBox.getWidth() + 14, fy1 = fy0 + 22;
		g.text(font, "Name", colX, fy0 - 12, TEXT_DIM, false);
		roundRect(g, fx0, fy0, fx1, fy1, nameBox.isFocused() ? FIELD_FOCUSED : FIELD_BORDER);
		roundRect(g, fx0 + 1, fy0 + 1, fx1 - 1, fy1 - 1, FIELD);
		if (nameBox.getValue().isEmpty()) g.text(font, "e.g. Builder", nameBox.getX(), nameBox.getY() + 2, TEXT_FAINT, false);
		hits.add(new Hit(fx0, fy0, fx1, fy1, () -> setFocused(nameBox)));
		g.text(font, "Letters, digits and underscores, 3 to 16 long.", colX, fy1 + 6, TEXT_FAINT, false);
	}

	private void drawLog(GuiGraphicsExtractor g) {
		Agent a = agent(selected);
		layoutLog();
		int working = a != null && a.busy() ? 16 : 0;
		int total = contentHeight + working;
		int view = logBottom - logTop - 16;
		int maxScroll = Math.max(0, total - view);
		scroll = Mth.clamp(scroll, 0, maxScroll);
		int top = total <= view ? logTop + 12 : logBottom - 4 - total + scroll;

		if (log.isEmpty() && working == 0) {
			int cy = (logTop + logBottom) / 2 - 12;
			centered(g, "Say something to " + selected + " to get started.", cy, TEXT_DIM);
			centered(g, "Players can also write @" + selected + " in chat.", cy + 12, TEXT_FAINT);
			return;
		}

		g.enableScissor(mainX, logTop, vw, logBottom);
		for (Block b : blocks) {
			int y = top + b.y();
			if (y + b.height() < logTop || y > logBottom) continue;
			b.draw().accept(y);
		}
		if (working > 0) {
			int y = top + contentHeight + 6;
			int dots = (int) (System.currentTimeMillis() / 400 % 4);
			// The running tool is already the log's last line, so only repeat activity the log doesn't show.
			Entry last = log.isEmpty() ? null : log.getLast();
			boolean shown = last != null && last.kind().equals("tool") && last.text().equals(a.activity());
			String what = a.activity().isEmpty() || shown ? "Working" : a.activity();
			g.fill(colX, y + 2, colX + 4, y + 6, statusColor(a));
			g.text(font, ellipsize(what, colW - 30) + ".".repeat(dots), colX + 9, y, WORKING_TEXT, false);
		}
		g.disableScissor();

		if (total > view) {
			int trackH = logBottom - logTop - 8;
			int thumbH = Math.max(12, trackH * view / total);
			int thumbY = logTop + 4 + (trackH - thumbH) * (maxScroll - scroll) / Math.max(1, maxScroll);
			g.fill(vw - 5, thumbY, vw - 3, thumbY + thumbH, 0xFF3A3A44);
		}
	}

	/** Wraps the log into blocks, once per change of entries or width. */
	private void layoutLog() {
		long key = log.isEmpty() ? 0 : log.getLast().id() * 1000 + log.size();
		if (blocks != null && key == blocksKey && blocksWidth == colW) return;
		blocks = new ArrayList<>();
		int y = 0;
		String prev = null;
		for (Entry e : log) {
			if (prev != null) y += e.kind().equals("tool") && prev.equals("tool") ? 3 : 10;
			Block b = block(e, y);
			blocks.add(b);
			y += b.height();
			prev = e.kind();
		}
		contentHeight = y;
		blocksKey = key;
		blocksWidth = colW;
	}

	private Block block(Entry e, int y) {
		int lh = font.lineHeight + 1;
		switch (e.kind()) {
			case "user" -> {
				List<FormattedCharSequence> lines = font.split(Component.literal(e.text()), colW * 3 / 4 - 12);
				int w = 0;
				for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
				int bw = w + 12;
				int h = 11 + lines.size() * lh + 8;
				String who = e.who() == null ? "" : e.who();
				return new Block(y, h, top -> {
					current.text(font, who, colX + colW - font.width(who), top, TEXT_FAINT, false);
					roundRect(current, colX + colW - bw, top + 11, colX + colW, top + h, BUBBLE);
					for (int i = 0; i < lines.size(); i++) current.text(font, lines.get(i), colX + colW - bw + 6, top + 16 + i * lh, TEXT, false);
				});
			}
			case "tool" -> {
				String text = ellipsize(e.text(), colW - 10);
				return new Block(y, lh, top -> {
					current.text(font, "•", colX, top, TEXT_FAINT, false);
					current.text(font, text, colX + 8, top, TOOL, false);
				});
			}
			case "say" -> {
				List<FormattedCharSequence> lines = font.split(Component.literal(e.text()), colW - 10);
				int h = lh + 2 + lines.size() * lh;
				return new Block(y, h, top -> {
					current.fill(colX, top, colX + 2, top + h - 1, SAY);
					current.text(font, "Said in chat", colX + 8, top, SAY, false);
					for (int i = 0; i < lines.size(); i++) current.text(font, lines.get(i), colX + 8, top + lh + 2 + i * lh, TEXT, false);
				});
			}
			case "info" -> {
				String text = ellipsize(e.text(), colW - 40);
				return new Block(y, lh, top -> {
					int w = font.width(text);
					int cx = colX + colW / 2;
					current.horizontalLine(colX, cx - w / 2 - 7, top + 4, BORDER);
					current.horizontalLine(cx + w / 2 + 6, colX + colW, top + 4, BORDER);
					current.text(font, text, cx - w / 2, top, TEXT_FAINT, false);
				});
			}
			default -> {
				int color = e.kind().equals("error") ? ERROR : 0xFFD4D4DA;
				List<FormattedCharSequence> lines = font.split(Component.literal(e.text()), colW);
				return new Block(y, lines.size() * lh, top -> {
					for (int i = 0; i < lines.size(); i++) current.text(font, lines.get(i), colX, top + i * lh, color, false);
				});
			}
		}
	}

	private void drawComposer(GuiGraphicsExtractor g, int mx, int my) {
		if (error != null && System.currentTimeMillis() < errorUntil) {
			g.text(font, ellipsize(error, colW), colX, composerTop - 12, ERROR, false);
		}
		int x0 = colX, x1 = colX + colW, y0 = composerTop, y1 = vh - 8;
		roundRect(g, x0, y0, x1, y1, composer.isFocused() ? FIELD_FOCUSED : FIELD_BORDER);
		roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, FIELD);
		if (composer.getValue().isEmpty()) g.text(font, ellipsize(hint, colW - 20), composer.getX(), composer.getY() + 2, TEXT_FAINT, false);
		drawChips(g, mx, my, x0 + 4, y1 - 17);
		hits.add(new Hit(x0, y0, x1 - 22, y1, () -> setFocused(composer)));

		boolean ready = canUse && (creating || selected != null && !composer.getValue().isBlank());
		Hit send = new Hit(x1 - 20, y1 - 19, x1 - 5, y1 - 4, this::submit);
		roundRect(g, send.x0(), send.y0(), send.x1(), send.y1(), ready ? (send.contains(mx, my) ? 0xFF5B9BFF : ACCENT) : 0xFF2C2C34);
		g.text(font, "↑", send.x0() + 8 - font.width("↑") / 2, send.y0() + 4, ready ? 0xFFFFFFFF : TEXT_FAINT, false);
		hits.add(send);
	}

	/** The model and effort buttons under the message box. With nothing selected they show the defaults, greyed out. */
	private void drawChips(GuiGraphicsExtractor g, int mx, int my, int x, int y) {
		Settings now = settings();
		boolean live = now != null && canUse;
		if (now == null) now = defaults;
		modelChip = chip(g, mx, my, x, y, live, Picker.MODEL, modelText(now.model()), 0);
		effortChip = chip(g, mx, my, modelChip.x1() + 3, y, live, Picker.EFFORT, effortText(now.effort()), indexOf(efforts, now.effort()));
	}

	/** @param level for the effort button, how many bars to light */
	private Hit chip(GuiGraphicsExtractor g, int mx, int my, int x, int y, boolean live, Picker which, String label, int level) {
		int w = 4 + 9 + 4 + font.width(label) + (live ? 4 + 5 : 0) + 5;
		Hit hit = new Hit(x, y, x + w, y + 13, () -> togglePicker(which));
		if (live && (picker == which || hit.contains(mx, my))) roundRect(g, hit.x0(), hit.y0(), hit.x1(), hit.y1(), picker == which ? ROW_SELECTED : ROW_HOVER);
		int ix = x + 4, iy = y + 3;
		if (which == Picker.MODEL) spark(g, ix + 1, iy, live ? CLAUDE : TEXT_FAINT);
		else bars(g, ix, iy, level, live);
		int color = live ? TEXT_DIM : TEXT_FAINT;
		g.text(font, label, ix + 13, y + 3, color, false);
		if (live) {
			chevron(g, hit.x1() - 10, y + 5, color);
			hits.add(hit);
		}
		return hit;
	}

	private String modelText(String id) {
		return id.isEmpty() ? "Default model" : label(models, id);
	}

	private String effortText(String id) {
		return id.isEmpty() ? "Default effort" : label(efforts, id);
	}

	/** The open model or effort list, above its button and over everything else. */
	private void drawPicker(GuiGraphicsExtractor g, int mx, int my) {
		pickerRows.clear();
		Settings now = settings();
		if (picker == null || now == null || !canUse) {
			picker = null;
			return;
		}
		Picker which = picker;
		List<Option> options = which == Picker.MODEL ? models : efforts;
		String current = which == Picker.MODEL ? now.model() : now.effort();
		Hit anchor = which == Picker.MODEL ? modelChip : effortChip;
		String title = which == Picker.MODEL ? "Model" : "Reasoning";
		String defaultHint = "Claude Code picks";
		int w = font.width(title);
		for (Option o : options) w = Math.max(w, font.width(o.label()) + (o.id().isEmpty() ? 6 + font.width(defaultHint) : 0));
		w += 7 + 22;
		int h = 16 + options.size() * OPTION_H + 4;
		int x0 = anchor.x0(), x1 = x0 + w, y1 = anchor.y0() - 4, y0 = y1 - h;

		g.nextStratum();
		roundRect(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, FIELD_BORDER);
		roundRect(g, x0, y0, x1, y1, FIELD);
		g.text(font, title, x0 + 7, y0 + 6, TEXT_FAINT, false);
		int y = y0 + 16;
		for (int i = 0; i < options.size(); i++) {
			Option o = options.get(i);
			Hit row = new Hit(x0 + 3, y, x1 - 3, y + OPTION_H, () -> choose(which, o.id()));
			if (row.contains(mx, my)) pickerIndex = i;
			boolean chosen = o.id().equals(current);
			if (i == pickerIndex) roundRect(g, row.x0(), row.y0(), row.x1(), row.y1(), ROW_SELECTED);
			g.text(font, o.label(), x0 + 7, y + 3, chosen || i == pickerIndex ? TEXT : TEXT_NAME, false);
			if (o.id().isEmpty()) g.text(font, defaultHint, x0 + 7 + font.width(o.label()) + 6, y + 3, TEXT_FAINT, false);
			if (chosen) check(g, x1 - 14, y + 4, ACCENT);
			pickerRows.add(row);
			y += OPTION_H;
		}
	}

	/**
	 * Where a test should click, in menu coordinates: "model" or "effort" for a button, or
	 * "option:<id>" for a row of the open list. Null if it isn't on screen.
	 */
	public int[] locate(String what) {
		Hit hit = null;
		if (what.equals("model")) hit = modelChip;
		else if (what.equals("effort")) hit = effortChip;
		else if (what.startsWith("option:") && picker != null) {
			int i = indexOf(picker == Picker.MODEL ? models : efforts, what.substring("option:".length()));
			if (i >= 0 && i < pickerRows.size()) hit = pickerRows.get(i);
		}
		return hit == null ? null : new int[]{(hit.x0() + hit.x1()) / 2, (hit.y0() + hit.y1()) / 2};
	}

	// ---- helpers ----

	private static int indexOf(List<Option> options, String id) {
		for (int i = 0; i < options.size(); i++) if (options.get(i).id().equals(id)) return i;
		return -1;
	}

	private static String label(List<Option> options, String id) {
		int i = indexOf(options, id);
		return i < 0 ? id : options.get(i).label();
	}

	/** Claude's asterisk, 7 pixels across. */
	private static void spark(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x + 3, y, x + 4, y + 7, color);
		g.fill(x, y + 3, x + 7, y + 4, color);
		g.fill(x + 2, y + 2, x + 5, y + 5, color);
		for (int d : new int[]{1, 5}) {
			g.fill(x + d, y + 1, x + d + 1, y + 2, color);
			g.fill(x + d, y + 5, x + d + 1, y + 6, color);
		}
	}

	/**
	 * Five rising bars, one lit per effort level: three for high, five for max. None for the default,
	 * and all five for a custom effort from the config, which comes after the five levels.
	 */
	private static void bars(GuiGraphicsExtractor g, int x, int y, int level, boolean live) {
		int lit = Mth.clamp(level, 0, 5);
		for (int i = 0; i < 5; i++) {
			int h = 3 + i;
			int color = i < lit ? (live ? TEXT_DIM : TEXT_FAINT) : 0xFF3A3A44;
			g.fill(x + i * 2, y + 7 - h, x + i * 2 + 1, y + 7, color);
		}
	}

	private static void chevron(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x, y, x + 1, y + 1, color);
		g.fill(x + 4, y, x + 5, y + 1, color);
		g.fill(x + 1, y + 1, x + 2, y + 2, color);
		g.fill(x + 3, y + 1, x + 4, y + 2, color);
		g.fill(x + 2, y + 2, x + 3, y + 3, color);
	}

	private static void check(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x, y + 2, x + 1, y + 3, color);
		g.fill(x + 1, y + 3, x + 2, y + 4, color);
		g.fill(x + 2, y + 4, x + 3, y + 5, color);
		for (int i = 0; i < 4; i++) g.fill(x + 3 + i, y + 3 - i, x + 4 + i, y + 4 - i, color);
	}

	private int statusColor(Agent a) {
		return switch (a.status()) {
			case "working" -> pulse(WORKING);
			case "starting" -> pulse(STARTING);
			default -> a.body() ? IDLE : 0xFF3F3F48;
		};
	}

	private static int pulse(int color) {
		double t = (System.currentTimeMillis() % 1200) / 1200.0;
		int alpha = (int) (150 + 105 * (0.5 + 0.5 * Math.cos(t * Math.PI * 2)));
		return alpha << 24 | color & 0xFFFFFF;
	}

	private String ago(long time) {
		long s = Math.max(0, (System.currentTimeMillis() + clockOffset - time) / 1000);
		if (s < 10) return "now";
		if (s < 60) return s + "s";
		if (s < 3600) return s / 60 + "m";
		if (s < 86400) return s / 3600 + "h";
		return s / 86400 + "d";
	}

	private String ellipsize(String s, int width) {
		if (font.width(s) <= width) return s;
		return font.plainSubstrByWidth(s, Math.max(0, width - font.width("…"))) + "…";
	}

	private void centered(GuiGraphicsExtractor g, String text, int y, int color) {
		String t = ellipsize(text, vw - mainX - 16);
		g.text(font, t, mainX + (vw - mainX - font.width(t)) / 2, y, color, false);
	}

	/** A rectangle with its corner pixels cut, which reads as slightly rounded. */
	private static void roundRect(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 1, y0, x1 - 1, y1, color);
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
	}
}
