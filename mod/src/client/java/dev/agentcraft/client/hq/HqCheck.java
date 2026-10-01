package dev.agentcraft.client.hq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.agents.GridPathfinder;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * {@code dev.hq.check}: measures the HQ instead of eyeballing it.
 * <ul>
 *   <li>reachability: the agents' own A* ({@link GridPathfinder}) from {@code entrance} and from
 *       {@code lounge} to every standing anchor (desks, seats excluded, station slots, user spots);</li>
 *   <li>light: block light at feet height over every walkable interior cell (min / mean, and the
 *       cells darker than {@code minLight}, default 9).</li>
 * </ul>
 */
final class HqCheck {
	/** Anchors that are points on blocks or cameras, not places an agent stands. */
	private static final Set<String> NOT_SPOTS = Set.of(AnchorNames.TASK_WALL, AnchorNames.DECISION_PODIUM, AnchorNames.GOAL_ATRIUM);

	private HqCheck() {
	}

	static void register() {
		DevBridge.register("dev.hq.check", 30_000, "{minLight?} -> HQ reachability (A* from entrance + lounge to every station anchor) and interior light levels",
			(req, mc) -> {
				int minLight = req.has("minLight") ? req.get("minLight").getAsInt() : 9;
				return DevBridge.onClient(mc, () -> check(mc, minLight));
			});
	}

	static boolean isSpot(String name) {
		return !name.startsWith(AnchorNames.CAM_PREFIX) && !name.startsWith(AnchorNames.MONITOR_PREFIX) && !name.startsWith("seat_")
			&& !NOT_SPOTS.contains(name) && !name.equals(AnchorNames.SPAWN);
	}

	static JsonObject check(Minecraft mc, int minLight) {
		JsonObject o = new JsonObject();
		ClientLevel level = mc.level;
		Anchors.Layout layout = Anchors.current();
		if (level == null || layout.isEmpty() || layout.bounds() == null) {
			o.addProperty("error", "no level or no layout");
			return o;
		}
		o.addProperty("layout", layout.name());
		JsonArray failures = new JsonArray();
		int spots = 0;
		double longest = 0;
		long t0 = System.nanoTime();
		for (String fromName : List.of(AnchorNames.ENTRANCE, AnchorNames.LOUNGE)) {
			Anchor from = layout.get(fromName);
			if (from == null) {
				failures.add("missing anchor " + fromName);
				continue;
			}
			for (Anchor a : layout.anchors().values()) {
				if (!isSpot(a.name())) {
					continue;
				}
				spots++;
				GridPathfinder pf = new GridPathfinder(level, layout.bounds());
				List<Vec3> route = pf.find(from.pos(), a.pos());
				if (route == null) {
					failures.add(fromName + " -> " + a.name());
					continue;
				}
				double len = 0;
				for (int i = 1; i < route.size(); i++) {
					len += route.get(i).distanceTo(route.get(i - 1));
				}
				longest = Math.max(longest, len);
			}
		}
		// every spot stands on its own floor cell (not on top of a chair or table), and slots of a
		// shared station are >= 1.6 apart (FEATURES.md "Slot spacing")
		JsonArray misplaced = new JsonArray();
		JsonArray crowded = new JsonArray();
		GridPathfinder grid = new GridPathfinder(level, layout.bounds());
		for (Anchor a : layout.anchors().values()) {
			if (!isSpot(a.name())) {
				continue;
			}
			BlockPos c = grid.cellAt(a.pos());
			if (c == null || c.getY() != (int) Math.floor(a.y() + 0.01)) {
				misplaced.add(a.name() + (c == null ? " (no floor)" : " (stands at y " + c.getY() + ")"));
			}
		}
		for (String station : AnchorNames.SHARED_STATIONS) {
			List<Anchor> slots = dev.agentcraft.client.agents.StationAssigner.slots(layout, station);
			for (int i = 0; i < slots.size(); i++) {
				for (int j = i + 1; j < slots.size(); j++) {
					Anchor p = slots.get(i);
					Anchor q = slots.get(j);
					double d = Math.hypot(p.x() - q.x(), p.z() - q.z());
					if (d < 1.6) {
						crowded.add(String.format(Locale.ROOT, "%s-%s %.2f", p.name(), q.name(), d));
					}
				}
			}
		}
		o.add("misplaced", misplaced);
		o.add("crowded", crowded);
		o.addProperty("routesChecked", spots);
		o.addProperty("longestRoute", Math.round(longest * 10) / 10.0);
		o.add("unreachable", failures);
		o.addProperty("pathMs", (System.nanoTime() - t0) / 1_000_000);
		// light at feet height over the walkable interior
		Anchors.Bounds b = layout.bounds();
		GridPathfinder pf = new GridPathfinder(level, b);
		int cells = 0;
		int dark = 0;
		int min = 15;
		long sum = 0;
		JsonArray darkest = new JsonArray();
		int feet = (int) Math.floor(layout.get(AnchorNames.ENTRANCE) != null ? layout.get(AnchorNames.ENTRANCE).y() + 0.01 : b.minY() + 1);
		for (int x = b.minX(); x <= b.maxX(); x++) {
			for (int z = b.minZ(); z <= b.maxZ(); z++) {
				if (Double.isNaN(pf.floor(x, feet, z))) {
					continue;
				}
				BlockPos at = new BlockPos(x, feet, z);
				if (level.getBrightness(LightLayer.SKY, at) >= 15) {
					continue; // outdoors (terrace, portico): only roofed cells count
				}
				int bl = level.getBrightness(LightLayer.BLOCK, at);
				cells++;
				sum += bl;
				min = Math.min(min, bl);
				if (bl < minLight) {
					dark++;
					if (darkest.size() < 60) {
						darkest.add(String.format(Locale.ROOT, "%d %d %d: %d", x, feet, z, bl));
					}
				}
			}
		}
		JsonObject light = new JsonObject();
		light.addProperty("walkableCells", cells);
		light.addProperty("minBlockLight", min);
		light.addProperty("meanBlockLight", cells == 0 ? 0 : Math.round(sum * 10.0 / cells) / 10.0);
		light.addProperty("below", minLight);
		light.addProperty("darkCells", dark);
		light.add("darkSample", darkest);
		o.add("light", light);
		return o;
	}
}
