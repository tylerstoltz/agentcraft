package dev.agentcraft.hq;

import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.Env;
import dev.agentcraft.world.WorldMarker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

/**
 * Where a relocatable HQ ({@link StudioHqBuilder}) goes in the world, and the earthworks that seat it
 * there. The builder works in its own coordinates (the meadow ground at y={@value StudioHqBuilder#GROUND},
 * the site box around x=0, z=9); the site is the offset added to them.
 *
 * <pre>
 * AGENTCRAFT_HQ_SITE    origin (the classic spot: x=0 z=0, ground y=64; default on flat worlds)
 *                       | auto (default otherwise: the best site near world spawn)
 *                       | spawn (centred on world spawn, whatever the ground)
 *                       | X,Z (centred there) | X,Y,Z (centred there, ground top at Y)
 * AGENTCRAFT_HQ_SEARCH  auto: search radius in blocks around spawn (default 256)
 * AGENTCRAFT_HQ_STRICT  1 = do not build when the best site is poor (mostly water, very rough)
 * </pre>
 *
 * The choice is made once, at the first build, and saved in the world marker ({@code site}), so later
 * rebuilds ({@code /agentcraft hq}) land on the same spot whatever the launch flags say.
 */
public final class HqSite {
	/** The builder's site box, in builder coordinates. */
	private static final int[] BOX = StudioHqBuilder.SITE;
	private static final int CENTER_X = (BOX[0] + BOX[3]) / 2;
	private static final int CENTER_Z = (BOX[2] + BOX[5]) / 2;
	/** Width of the sloped ring that blends the site into the natural terrain. */
	private static final int SKIRT = 14;
	/** Half-width of the averaging window for the site's edge height. */
	private static final int EDGE_BLUR = 4;
	/** Candidate spacing and coarse/fine sample spacing for {@code auto}. */
	private static final int STEP = 32;
	private static final int COARSE = 23;
	private static final int FINE = 8;
	private static final int FINALISTS = 8;

	/**
	 * @param origin the offset from builder to world coordinates
	 * @param terraform blend the box into the surrounding terrain (off for the classic flat site)
	 * @param report one line for the log / the player
	 */
	public record Site(BlockPos origin, boolean terraform, String report) {
		public static final Site CLASSIC = new Site(BlockPos.ZERO, false, "classic site at the origin");
	}

	/** Terrain statistics of a candidate (world coordinates). */
	private record Probe(int cx, int cz, int median, double roughness, int range, double water, double score) {
	}

	private HqSite() {
	}

	/** The saved site offset, or the origin when none is saved yet (classic site, or built before sites existed). */
	public static BlockPos savedOrigin(net.minecraft.server.MinecraftServer server) {
		JsonObject m = WorldMarker.load(server).json();
		if (m.has("site") && m.get("site").isJsonObject()) {
			JsonObject s = m.getAsJsonObject("site");
			return new BlockPos(s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt());
		}
		return BlockPos.ZERO;
	}

	/** The site of this world: the saved one, else chosen now from the launch flags and saved. Server thread. */
	public static Site resolve(ServerLevel level) {
		WorldMarker marker = WorldMarker.load(level.getServer());
		JsonObject saved = marker.json().has("site") && marker.json().get("site").isJsonObject() ? marker.json().getAsJsonObject("site") : null;
		if (saved != null) {
			BlockPos o = new BlockPos(saved.get("x").getAsInt(), saved.get("y").getAsInt(), saved.get("z").getAsInt());
			boolean terraform = !saved.has("terraform") || saved.get("terraform").getAsBoolean();
			return new Site(o, terraform, "saved site " + o.toShortString());
		}
		Site site = choose(level);
		JsonObject o = new JsonObject();
		o.addProperty("x", site.origin().getX());
		o.addProperty("y", site.origin().getY());
		o.addProperty("z", site.origin().getZ());
		o.addProperty("terraform", site.terraform());
		o.addProperty("chosen", site.report());
		marker.json().add("site", o);
		marker.save();
		return site;
	}

	private static Site choose(ServerLevel level) {
		boolean flat = level.getChunkSource().getGenerator() instanceof FlatLevelSource;
		String mode = Env.str("AGENTCRAFT_HQ_SITE", flat ? "origin" : "auto").toLowerCase(Locale.ROOT);
		BlockPos spawn = level.getRespawnData().pos();
		switch (mode) {
			case "origin" -> {
				return Site.CLASSIC;
			}
			case "spawn" -> {
				Probe p = probe(level, spawn.getX(), spawn.getZ(), FINE);
				return site(p, "centred on spawn " + spawn.getX() + "," + spawn.getZ());
			}
			case "auto" -> {
				return search(level, spawn, Math.max(STEP, Env.intValue("AGENTCRAFT_HQ_SEARCH", 256)));
			}
			default -> {
				int[] v;
				try {
					v = Arrays.stream(mode.split("[,\\s]+")).mapToInt(Integer::parseInt).toArray();
				} catch (NumberFormatException e) {
					v = new int[0];
				}
				if (v.length == 2) {
					return site(probe(level, v[0], v[1], FINE), "centred on " + v[0] + "," + v[1]);
				}
				if (v.length == 3) {
					return new Site(new BlockPos(v[0] - CENTER_X, v[1] - StudioHqBuilder.GROUND, v[2] - CENTER_Z), true,
						"centred on " + v[0] + "," + v[2] + " with ground at y=" + v[1]);
				}
				AgentCraft.LOGGER.warn("AGENTCRAFT_HQ_SITE='{}' is not origin|auto|spawn|X,Z|X,Y,Z; searching instead", mode);
				return search(level, spawn, 256);
			}
		}
	}

	private static Site site(Probe p, String how) {
		return new Site(new BlockPos(p.cx - CENTER_X, p.median - StudioHqBuilder.GROUND, p.cz - CENTER_Z), true,
			String.format(Locale.ROOT, "%s: ground y=%d, roughness %.1f, range %d, water %.0f%%", how, p.median, p.roughness, p.range,
				p.water * 100));
	}

	/** {@code auto}: coarse-sample a grid of candidates around spawn, fine-sample the best few, take the best. */
	private static Site search(ServerLevel level, BlockPos spawn, int radius) {
		long t0 = System.nanoTime();
		List<Probe> coarse = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx += STEP) {
			for (int dz = -radius; dz <= radius; dz += STEP) {
				if (dx * dx + dz * dz <= radius * radius) {
					coarse.add(probe(level, spawn.getX() + dx, spawn.getZ() + dz, COARSE, spawn));
				}
			}
		}
		coarse.sort(Comparator.comparingDouble(Probe::score));
		Probe best = null;
		for (Probe c : coarse.subList(0, Math.min(FINALISTS, coarse.size()))) {
			Probe f = probe(level, c.cx, c.cz, FINE, spawn);
			if (best == null || f.score < best.score) {
				best = f;
			}
		}
		if (best == null) {
			return Site.CLASSIC;
		}
		boolean poor = best.water > 0.25 || best.range > 24 || best.median < level.getSeaLevel();
		String how = String.format(Locale.ROOT, "best of %d sites within %d of spawn (%d ms)%s", coarse.size(), radius,
			(System.nanoTime() - t0) / 1_000_000, poor ? " - POOR" : "");
		Site s = site(best, how);
		if (poor && Env.flag("AGENTCRAFT_HQ_STRICT", false)) {
			throw new IllegalStateException("no good HQ site within " + radius + " blocks of spawn (" + s.report()
				+ "); raise AGENTCRAFT_HQ_SEARCH, pick one with AGENTCRAFT_HQ_SITE=X,Z, or unset AGENTCRAFT_HQ_STRICT");
		}
		if (poor) {
			AgentCraft.LOGGER.warn("HQ site is poor ({}); building anyway (AGENTCRAFT_HQ_STRICT=1 refuses)", s.report());
		}
		return s;
	}

	private static Probe probe(ServerLevel level, int cx, int cz, int spacing) {
		return probe(level, cx, cz, spacing, null);
	}

	/**
	 * Samples the generator's own terrain function over the footprint centred on (cx, cz): no chunk
	 * is generated, so hundreds of candidates cost about a second. Height = top of the solid ground
	 * (ocean floor), water = the surface is fluid above it.
	 */
	private static Probe probe(ServerLevel level, int cx, int cz, int spacing, @Nullable BlockPos spawn) {
		ChunkGenerator gen = level.getChunkSource().getGenerator();
		RandomState rs = level.getChunkSource().randomState();
		int x0 = cx - (CENTER_X - BOX[0]);
		int x1 = cx + (BOX[3] - CENTER_X);
		int z0 = cz - (CENTER_Z - BOX[2]);
		int z1 = cz + (BOX[5] - CENTER_Z);
		List<Integer> hs = new ArrayList<>();
		int wet = 0;
		for (int x = x0; x <= x1; x += spacing) {
			for (int z = z0; z <= z1; z += spacing) {
				int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1;
				int surface = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs) - 1;
				hs.add(floor);
				if (surface > floor) {
					wet++;
				}
			}
		}
		int[] h = hs.stream().mapToInt(Integer::intValue).sorted().toArray();
		int median = h[h.length / 2];
		double rough = 0;
		for (int v : h) {
			rough += Math.abs(v - median);
		}
		rough /= h.length;
		int range = h[(int) (h.length * 0.9)] - h[(int) (h.length * 0.1)];
		double water = wet / (double) h.length;
		// flat first; then dry, above the sea, and not up on a mountain (the HQ is a meadow in the woods)
		int sea = level.getSeaLevel();
		double score = rough * 2 + range * 0.5 + water * 60 + (median < sea ? 30 : 0) + Math.max(0, median - (sea + 24)) * 0.25;
		if (spawn != null) {
			score += Math.sqrt((double) (cx - spawn.getX()) * (cx - spawn.getX()) + (double) (cz - spawn.getZ()) * (cz - spawn.getZ())) / 48;
		}
		return new Probe(cx, cz, median, rough, range, water, score);
	}

	// ------------------------------------------------------------------ earthworks

	/**
	 * After the plan is applied at {@code origin}: clears everything above the box (trees, a hill top),
	 * then reshapes a {@value #SKIRT}-block ring around the box so the ground slopes smoothly from the
	 * site's edge to the natural terrain. Returns the number of blocks changed. Server thread.
	 */
	static int terraform(ServerLevel level, Plan p, BlockPos origin) {
		int dx = origin.getX();
		int dy = origin.getY();
		int dz = origin.getZ();
		int boxTop = p.maxY + dy;
		int changed = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int wx = p.minX + dx - SKIRT; wx <= p.maxX + dx + SKIRT; wx++) {
			for (int wz = p.minZ + dz - SKIRT; wz <= p.maxZ + dz + SKIRT; wz++) {
				int lx = wx - dx;
				int lz = wz - dz;
				int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
				if (p.inXZ(lx, lz)) {
					// inside the box: nothing natural may stand above it
					for (int y = boxTop + 1; y <= surface; y++) {
						changed += set(level, m.set(wx, y, wz), air);
					}
					continue;
				}
				int ex = Mth.clamp(lx, p.minX, p.maxX);
				int ez = Mth.clamp(lz, p.minZ, p.maxZ);
				double d = Math.sqrt((double) (lx - ex) * (lx - ex) + (double) (lz - ez) * (lz - ez));
				if (d > SKIRT) {
					continue;
				}
				int natural = groundTop(level, m, wx, wz, surface);
				int edge = (int) Math.round(edgeTop(p, ex, ez)) + dy;
				double t = d / SKIRT;
				t = t * t * (3 - 2 * t);
				int target = (int) Math.round(edge + (natural - edge) * t);
				if (target == natural) {
					continue;
				}
				BlockState top = surfaceBlock(level.getBlockState(m.set(wx, natural, wz)));
				// lower: dig down to the target; raise: fill up with dirt. Then the column above is open sky.
				for (int y = Math.min(natural, target) + 1; y <= target; y++) {
					changed += set(level, m.set(wx, y, wz), Blocks.DIRT.defaultBlockState());
				}
				for (int y = target + 1; y <= Math.max(surface, natural); y++) {
					changed += set(level, m.set(wx, y, wz), air);
				}
				changed += set(level, m.set(wx, target, wz), top);
			}
		}
		return changed;
	}

	/**
	 * The site's ground height near border cell (ex, ez), averaged over the box cells within
	 * {@value #EDGE_BLUR} blocks: the landscape's hills and the gateway path change height sharply
	 * along the border, and sloping each skirt column from its single nearest cell leaves ridges.
	 */
	private static double edgeTop(Plan p, int ex, int ez) {
		double sum = 0;
		int n = 0;
		for (int x = ex - EDGE_BLUR; x <= ex + EDGE_BLUR; x++) {
			for (int z = ez - EDGE_BLUR; z <= ez + EDGE_BLUR; z++) {
				if (p.inXZ(x, z)) {
					sum += p.top(x, z);
					n++;
				}
			}
		}
		return n == 0 ? p.top(ex, ez) : sum / n;
	}

	/** Top of the solid ground in a loaded column: skips trees, plants, snow and fluids. */
	private static int groundTop(ServerLevel level, BlockPos.MutableBlockPos m, int x, int z, int from) {
		int min = level.getMinY();
		for (int y = from; y > min; y--) {
			BlockState s = level.getBlockState(m.set(x, y, z));
			if (s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.canBeReplaced()
				|| !s.isSolidRender()) {
				continue;
			}
			return y;
		}
		return min;
	}

	/** The block to put on top of a reshaped column: keep sand / snowy ground, otherwise grass. */
	private static BlockState surfaceBlock(BlockState natural) {
		if (natural.is(BlockTags.SAND) || natural.is(Blocks.GRAVEL) || natural.is(Blocks.SNOW_BLOCK) || natural.is(Blocks.PODZOL)
			|| natural.is(Blocks.MYCELIUM) || natural.is(Blocks.COARSE_DIRT)) {
			return natural.getBlock().defaultBlockState();
		}
		return Blocks.GRASS_BLOCK.defaultBlockState();
	}

	private static int set(ServerLevel level, BlockPos pos, BlockState state) {
		if (level.getBlockState(pos) == state) {
			return 0;
		}
		level.setBlock(pos, state, Plan.FLAGS);
		return 1;
	}
}
