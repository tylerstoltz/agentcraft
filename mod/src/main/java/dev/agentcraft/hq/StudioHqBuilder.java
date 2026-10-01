package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.FacingEntityBlock;
import dev.agentcraft.block.GlowStripBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.MergeStationBlock;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;

/**
 * The real AgentCraft HQ ("Warm Studio"): a long timber-framed studio hall with a steep slate roof
 * and an exposed-truss vault, fronted by an octagonal, copper-domed Goal Atrium (the entrance
 * rotunda), on a landscaped meadow with terraces, a pond and trees.
 *
 * <pre>
 *            x=-24 ........................... 0 ............................ x=24
 *   z=-10    north wall: 3 desk bays | big north window | 3 desk bays   (monitors face south)
 *            Library (west end)  Lounge (sw)  aisle  Meeting (se)  Test bench / terminals / merge (east end)
 *   z=6      south wall (terraces outside) ----+  opening  +----
 *   z=7..23                     Goal Atrium (octagon, centre 0,15): task wall W, podium + user E, hologram centre
 *   z=24                                    entrance (portico, steps, path south)
 * </pre>
 *
 * Floor blocks at y=65 (the hall sits one block above the meadow on a mud-brick plinth); agents
 * stand at y=66. Everything inside {@link #SITE} is rebuilt on every run (a {@link Plan} diff), so
 * the build is deterministic and idempotent, and nothing outside it is touched.
 */
public final class StudioHqBuilder implements HqBuilder {
	public static final String ID = "studio";

	// ------------------------------------------------------------------ dimensions
	static final int GROUND = 64;
	static final int FLOOR = 65;
	static final int FEET = 66;
	/** Hall exterior walls. */
	static final int HX = 24;
	static final int HZN = -10;
	static final int HZS = 6;
	/** Top wall row (66..71), frieze at 72. */
	static final int WALL_TOP = 71;
	static final int FRIEZE = 72;
	/** Roof eave height and ridge line. */
	static final int EAVE_Y = 72;
	static final int RIDGE_Z = -2;
	/** Goal Atrium (octagon) centre cell. */
	static final int AX = 0;
	static final int AZ = 15;
	static final int ATRIUM_TOP = 76;
	static final int DOME_BASE = 77;
	/** Site box: rebuilt completely, never anything outside it. */
	static final int[] SITE = {-46, 60, -36, 46, 100, 54};

	/** Desk bay centres (x), west to east, in cast order. */
	static final int[] DESK_X = {-15, -11, -7, 7, 11, 15};

	static final Direction N = Direction.NORTH;
	static final Direction S = Direction.SOUTH;
	static final Direction E = Direction.EAST;
	static final Direction W = Direction.WEST;

	// ------------------------------------------------------------------ materials
	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState PLASTER = ModBlocks.PLASTER_PANEL.defaultBlockState();
	static final BlockState PLASTER_FRAME = ModBlocks.PLASTER_FRAME.defaultBlockState();
	static final BlockState WALNUT = ModBlocks.WALNUT_PANEL.defaultBlockState();
	static final BlockState WALNUT_TRIM = ModBlocks.WALNUT_TRIM.defaultBlockState();
	static final BlockState TILE = ModBlocks.TERRACOTTA_TILE.defaultBlockState();
	static final BlockState PARQUET = ModBlocks.OAK_PARQUET.defaultBlockState();
	static final BlockState GLOW_PANEL = ModBlocks.GLOW_PANEL.defaultBlockState();
	static final BlockState PLINTH = Blocks.MUD_BRICKS.defaultBlockState();
	static final BlockState FLOOR_WOOD = Blocks.BIRCH_PLANKS.defaultBlockState();
	static final BlockState DARK_PLANKS = Blocks.DARK_OAK_PLANKS.defaultBlockState();
	static final BlockState GLASS_PANE = Blocks.GLASS_PANE.defaultBlockState();
	static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
	static final Block ROOF_STAIRS = Blocks.DEEPSLATE_TILE_STAIRS;
	static final Block ROOF_SLAB = Blocks.DEEPSLATE_TILE_SLAB;
	static final BlockState ROOF_FULL = Blocks.DEEPSLATE_TILES.defaultBlockState();
	static final Block CEIL_STAIRS = Blocks.PALE_OAK_STAIRS;
	static final BlockState CEIL_FULL = Blocks.PALE_OAK_PLANKS.defaultBlockState();
	static final BlockState COPPER = Blocks.CUT_COPPER.waxed().unaffected().defaultBlockState();
	static final Block COPPER_STAIRS = Blocks.CUT_COPPER_STAIRS.waxed().unaffected();
	static final Block COPPER_SLAB = Blocks.CUT_COPPER_SLAB.waxed().unaffected();
	static final Block COPPER_LANTERN = Blocks.COPPER_LANTERN.waxed().unaffected();

	static BlockState post() {
		return St.log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y);
	}

	static BlockState beam(Direction.Axis axis) {
		return St.log(Blocks.STRIPPED_DARK_OAK_LOG, axis);
	}

	/** Interior structure (pilasters, tie beams, king posts): warm stripped oak, lighter than the walnut frame. */
	static BlockState timber(Direction.Axis axis) {
		return St.log(Blocks.STRIPPED_OAK_LOG, axis);
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String description() {
		return "the Warm Studio HQ: timber hall + copper-domed Goal Atrium on a landscaped meadow";
	}

	@Override
	public void build(ServerLevel level, Anchors.Builder a) {
		long t0 = System.nanoTime();
		Plan p = new Plan(SITE[0], SITE[1], SITE[2], SITE[3], SITE[4], SITE[5], y -> y > GROUND ? AIR
			: y == GROUND ? Blocks.GRASS_BLOCK.defaultBlockState() : y >= GROUND - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
		long tPlan0 = System.nanoTime();
		HqLandscape.ground(p);
		hallShell(p);
		hallRoof(p);
		atriumShell(p);
		dome(p);
		floors(p);
		desks(p, a);
		library(p, a);
		workshop(p, a);
		lounge(p, a);
		meeting(p, a);
		atrium(p, a);
		hallLighting(p);
		HqLandscape.exterior(p);
		cameras(a);
		p.settleGrass();
		long tPlan = System.nanoTime() - tPlan0;
		Plan.Stats st = p.apply(level);
		a.bounds(-HX + 1, FLOOR, HZN + 1, HX - 1, FLOOR + 16, AZ + 8);
		a.spot(AnchorNames.ENTRANCE, AX, FEET, AZ + 7, 180);
		a.put(AnchorNames.SPAWN, AX + 0.5, FEET, AZ + 7.5, 180, 0);
		AgentCraft.LOGGER.info("Studio HQ: planned in {} ms, applied {} cells in {} ms ({} changed, {} connections, {} bindings); total {} ms",
			tPlan / 1_000_000, st.cells(), st.micros() / 1000, st.changed(), st.connected(), st.bound(), (System.nanoTime() - t0) / 1_000_000);
		if (st.changed() + st.connected() < 40 && !st.sample().isEmpty()) {
			AgentCraft.LOGGER.info("Studio HQ: changed cells: {}", st.sample());
		}
	}

	// ================================================================== geometry helpers

	/** Inside the octagon's walkable interior (relative to its centre cell). */
	static boolean octIn(int dx, int dz) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		return ax <= 8 && az <= 8 && ax + az <= 12;
	}

	/** Part of the octagon's footprint (interior or wall). */
	static boolean octFoot(int dx, int dz) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		return ax <= 9 && az <= 9 && ax + az <= 14;
	}

	static boolean octWall(int dx, int dz) {
		return octFoot(dx, dz) && !octIn(dx, dz);
	}

	/** Octagonal "radius" of an atrium cell (8 = the last walkable ring). */
	static int octR(int dx, int dz) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		return Math.max(Math.max(ax, az), ax + az - 4);
	}

	/** Roof top height at z (both slopes), from the eaves at z=-11 / 7 to the ridge. */
	static int roofY(int z) {
		return EAVE_Y + Math.min(z + 11, 7 - z);
	}

	static boolean isPostX(int x) {
		int ax = Math.abs(x);
		return ax == 24 || ax == 21 || ax == 17 || ax == 13 || ax == 9 || ax == 5;
	}

	// ================================================================== hall

	private static void hallShell(Plan p) {
		// plinth + subfloor under the whole hall
		p.fill(-HX, GROUND, HZN, HX, GROUND, HZS, Blocks.DIRT.defaultBlockState());
		for (int x = -HX; x <= HX; x++) {
			for (int z = HZN; z <= HZS; z++) {
				boolean edge = Math.abs(x) == HX || z == HZN || z == HZS;
				p.set(x, FLOOR, z, edge ? PLINTH : FLOOR_WOOD);
			}
		}
		// long walls
		for (int x = -HX; x <= HX; x++) {
			wallColumn(p, x, HZN, true);
			if (!octFoot(x - AX, HZS - AZ)) {
				wallColumn(p, x, HZS, false);
			}
		}
		// gable end walls (x = +-24): plinth, wall rows, frieze, then the gable triangle
		for (int z = HZN; z <= HZS; z++) {
			for (int sx : new int[] {-HX, HX}) {
				gableColumn(p, sx, z);
			}
		}
		// interior pilasters in front of the posts, tie beams, king posts, struts
		for (int x = -21; x <= 21; x++) {
			if (!isPostX(x) || Math.abs(x) == 24) {
				continue;
			}
			p.fill(x, FEET, HZN + 1, x, WALL_TOP, HZN + 1, timber(Direction.Axis.Y));
			if (Math.abs(x) >= 9) {
				p.fill(x, FEET, HZS - 1, x, WALL_TOP, HZS - 1, timber(Direction.Axis.Y));
			}
			// tie beam across the hall + king post up to the ridge
			p.fill(x, FRIEZE, HZN + 1, x, FRIEZE, HZS - 1, timber(Direction.Axis.Z));
			p.fill(x, FRIEZE + 1, RIDGE_Z, x, roofY(RIDGE_Z) - 2, RIDGE_Z, timber(Direction.Axis.Y));
			// struts from the king post to the beam (upside-down stairs)
			for (int i = 1; i <= 3; i++) {
				p.set(x, FRIEZE + 1 + (3 - i), RIDGE_Z - i, St.stairs(Blocks.OAK_STAIRS, S, true));
				p.set(x, FRIEZE + 1 + (3 - i), RIDGE_Z + i, St.stairs(Blocks.OAK_STAIRS, N, true));
			}
		}
	}

	/** One column of a long (north/south) wall at x. */
	private static void wallColumn(Plan p, int x, int z, boolean north) {
		p.set(x, FLOOR, z, PLINTH);
		if (isPostX(x)) {
			p.fill(x, FEET, z, x, WALL_TOP, z, post());
		} else {
			p.set(x, FEET, z, WALNUT_TRIM);
			int ax = Math.abs(x);
			if (north) {
				if (ax <= 3) {
					// the big north window (centre bay)
					p.fill(x, FEET + 1, z, x, WALL_TOP, z, GLASS_PANE);
				} else if (ax == 4) {
					p.fill(x, FEET + 1, z, x, WALL_TOP, z, PLASTER);
				} else {
					p.fill(x, FEET + 1, z, x, FEET + 3, z, PLASTER);
					p.fill(x, FEET + 4, z, x, WALL_TOP, z, GLASS_PANE);
				}
			} else {
				p.fill(x, FEET + 1, z, x, FEET + 4, z, GLASS_PANE);
				p.set(x, WALL_TOP, z, PLASTER_FRAME);
			}
		}
		p.set(x, FRIEZE, z, beam(Direction.Axis.X));
	}

	private static void gableColumn(Plan p, int x, int z) {
		boolean corner = z == HZN || z == HZS;
		p.set(x, FLOOR, z, PLINTH);
		if (corner || z == RIDGE_Z || z == -6 || z == 2) {
			p.fill(x, FEET, z, x, WALL_TOP, z, post());
		} else {
			p.set(x, FEET, z, WALNUT_TRIM);
			p.fill(x, FEET + 1, z, x, FEET + 2, z, PLASTER);
			p.fill(x, FEET + 3, z, x, WALL_TOP - 1, z, GLASS_PANE);
			p.set(x, WALL_TOP, z, PLASTER_FRAME);
		}
		p.set(x, FRIEZE, z, beam(Direction.Axis.Z));
		// gable triangle up to under the roof: collar beam, a round "observatory" window, a king post above it
		int top = roofY(z) - 1;
		for (int y = FRIEZE + 1; y <= top; y++) {
			p.set(x, y, z, PLASTER);
		}
		if (FRIEZE + 1 <= top) {
			p.set(x, FRIEZE + 1, z, beam(Direction.Axis.Z));
		}
		double dz = z - RIDGE_Z;
		double cy = FRIEZE + 4.5;
		for (int y = FRIEZE + 2; y <= top; y++) {
			double dy = y - cy;
			double r = Math.sqrt(dz * dz + dy * dy);
			if (r < 2.4) {
				p.set(x, y, z, GLASS_PANE);
			} else if (r < 3.2) {
				p.set(x, y, z, DARK_PLANKS);
			} else if (z == RIDGE_Z) {
				p.set(x, y, z, post());
			}
		}
	}

	private static void hallRoof(Plan p) {
		for (int x = -HX - 1; x <= HX + 1; x++) {
			for (int z = HZN - 1; z <= HZS + 1; z++) {
				if (octFoot(x - AX, z - AZ)) {
					continue;
				}
				int y = roofY(z);
				if (z == RIDGE_Z) {
					p.set(x, y, z, ROOF_FULL);
					p.set(x, y + 1, z, St.slab(COPPER_SLAB, false));
				} else {
					p.set(x, y, z, St.stairs(ROOF_STAIRS, z < RIDGE_Z ? S : N, false));
				}
				// sloped timber ceiling underneath (inside the walls only)
				if (Math.abs(x) < HX && z > HZN && z < HZS) {
					if (z == RIDGE_Z) {
						p.set(x, y - 1, z, CEIL_FULL);
					} else {
						p.set(x, y - 1, z, St.stairs(CEIL_STAIRS, z < RIDGE_Z ? N : S, true));
					}
				}
			}
		}
		dormer(p, -15, true);
		dormer(p, 15, true);
		dormer(p, -15, false);
		dormer(p, 15, false);
		// lounge chimney: the stack rises from the fireplace breast (z 4..5) through the south slope
		int ct = roofY(4) + 4;
		p.fill(-11, FRIEZE, 4, -10, ct, 5, Blocks.MUD_BRICKS.defaultBlockState());
		p.fill(-11, ct + 1, 4, -10, ct + 1, 5, St.slab(Blocks.MUD_BRICK_SLAB, false));
		p.set(-11, ct + 1, 4, Blocks.CAMPFIRE.defaultBlockState());
	}

	/**
	 * A gabled wall dormer on the south slope, centred on x = cx, its window flush with the wall below:
	 * front wall (posts + 3 panes) at the wall line, its own little roof ridged along z, cheeks down to
	 * the main roof, and the main roof cut away inside so its light reaches the hall.
	 */
	private static void dormer(Plan p, int cx, boolean south) {
		int zf = south ? HZS : HZN;
		int out = south ? 1 : -1; // towards the eave
		int ridge = 79;
		for (int k = -3; k <= 3; k++) {
			int x = cx + k;
			int hd = ridge - Math.abs(k);
			// dormer roof from the overhang back until it meets the main roof
			for (int z = zf + out; z != RIDGE_Z - out; z -= out) {
				int yr = roofY(z);
				if (yr >= hd) {
					break;
				}
				p.set(x, hd, z, k == 0 ? ROOF_FULL : St.stairs(ROOF_STAIRS, k < 0 ? E : W, false));
				if (k == 0) {
					p.set(x, hd + 1, z, St.slab(COPPER_SLAB, false));
				}
				if (z == zf || z == zf + out) {
					continue;
				}
				if (Math.abs(k) <= 1) {
					// inside: cut the main roof and its ceiling so the dormer opens into the vault
					p.set(x, yr, z, AIR);
					p.set(x, yr - 1, z, AIR);
				} else if (Math.abs(k) == 2) {
					// cheek wall from the main roof up to the dormer roof
					for (int y = yr + 1; y < hd; y++) {
						p.set(x, y, z, PLASTER);
					}
				}
			}
		}
		// front wall, flush with the hall wall below
		for (int k = -2; k <= 2; k++) {
			int x = cx + k;
			int hd = ridge - Math.abs(k);
			for (int y = FRIEZE + 1; y < hd; y++) {
				BlockState st;
				if (Math.abs(k) == 2) {
					st = post();
				} else if (y <= FRIEZE + 3) {
					st = GLASS_PANE;
				} else if (y == FRIEZE + 4) {
					st = beam(Direction.Axis.X);
				} else {
					st = PLASTER;
				}
				p.set(x, y, zf, st);
			}
		}
	}

	// ================================================================== atrium (octagon)

	private static void atriumShell(Plan p) {
		for (int dx = -9; dx <= 9; dx++) {
			for (int dz = -9; dz <= 9; dz++) {
				if (!octFoot(dx, dz)) {
					continue;
				}
				int x = AX + dx;
				int z = AZ + dz;
				p.set(x, GROUND, z, Blocks.DIRT.defaultBlockState());
				if (octIn(dx, dz)) {
					// clear the interior right up into the dome
					for (int y = FEET; y < DOME_BASE + DOME_COURSES.length; y++) {
						p.set(x, y, z, AIR);
					}
					continue;
				}
				p.set(x, FLOOR, z, PLINTH);
				for (int y = FEET; y <= ATRIUM_TOP; y++) {
					p.set(x, y, z, atriumWall(dx, dz, y));
				}
				p.set(x, ATRIUM_TOP + 1, z, beam(Math.abs(dx) > Math.abs(dz) ? Direction.Axis.Z : Direction.Axis.X));
			}
		}
		// opening into the hall (north side, x -3..3) with a stepped arch
		for (int x = -3; x <= 3; x++) {
			for (int y = FEET; y <= FEET + 4; y++) {
				p.set(x, y, HZS, AIR);
			}
		}
		p.set(-3, FEET + 4, HZS, St.stairs(Blocks.DARK_OAK_STAIRS, E, true));
		p.set(3, FEET + 4, HZS, St.stairs(Blocks.DARK_OAK_STAIRS, W, true));
		p.fill(-2, FEET + 5, HZS, 2, FEET + 5, HZS, beam(Direction.Axis.X));
		p.fill(-4, FEET, HZS, -4, FEET + 5, HZS, post());
		p.fill(4, FEET, HZS, 4, FEET + 5, HZS, post());
		// entrance (south side, x -1..1)
		int zs = AZ + 9;
		for (int x = -1; x <= 1; x++) {
			for (int y = FEET; y <= FEET + 3; y++) {
				p.set(x, y, zs, AIR);
			}
		}
		p.set(-1, FEET + 3, zs, St.stairs(Blocks.DARK_OAK_STAIRS, E, true));
		p.set(1, FEET + 3, zs, St.stairs(Blocks.DARK_OAK_STAIRS, W, true));
		p.fill(-1, FEET + 4, zs, 1, FEET + 4, zs, beam(Direction.Axis.X));
		p.fill(-1, FEET + 5, zs, 1, FEET + 7, zs, GLASS_PANE);
		p.fill(-2, FEET, zs, -2, FEET + 7, zs, post());
		p.fill(2, FEET, zs, 2, FEET + 7, zs, post());
		// entrance floor sill
		for (int x = -1; x <= 1; x++) {
			p.set(x, FLOOR, zs, DARK_PLANKS);
		}
	}

	/** Wall material of an octagon wall cell at height y. */
	private static BlockState atriumWall(int dx, int dz, int y) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		boolean orthoX = ax == 9 && az <= 4; // west / east sides
		boolean orthoZ = az == 9 && ax <= 4; // north / south sides
		boolean cornerPost = (ax == 9 && az == 5) || (az == 9 && ax == 5);
		if (cornerPost) {
			return post();
		}
		if (y == FEET) {
			return WALNUT_TRIM;
		}
		if (orthoX || orthoZ) {
			int along = orthoX ? az : ax;
			boolean north = orthoZ && dz < 0;
			if (y == 72) {
				return beam(orthoX ? Direction.Axis.Z : Direction.Axis.X);
			}
			if (north) {
				return y == 71 ? PLASTER_FRAME : PLASTER;
			}
			// timber posts split each face into three framed panels; a drum window in the middle
			if (along == 2) {
				return post();
			}
			if (y >= 73 && y <= 75 && along <= 1) {
				return GLASS_PANE;
			}
			if (orthoX && y == FEET + 3) {
				return beam(Direction.Axis.Z); // mid-rail: two tiers of framed panels on the east/west faces
			}
			return y == 71 ? PLASTER_FRAME : PLASTER;
		}
		boolean southDiag = dz > 0;
		// south diagonals: tall faceted windows; north diagonals (they face the hall roof): framed plaster
		if (southDiag && y >= FEET + 1 && y <= 75 && ax + az == 13 && ax >= 6 && ax <= 7) {
			return GLASS;
		}
		if (southDiag && y >= FEET + 1 && y <= 75 && ax + az == 14 && ax >= 6 && ax <= 8 && az >= 6) {
			return GLASS;
		}
		if (y == 72) {
			return DARK_PLANKS;
		}
		return y == 71 ? PLASTER_FRAME : PLASTER;
	}

	/** Octagon of "size" a around the atrium centre (a = 9 is the wall's outer footprint). */
	static boolean octInside(int dx, int dz, int a) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		return ax <= a && az <= a && ax + az <= (3 * a + 1) / 2;
	}

	/** Dome courses: octagon size per course above the walls (convex: upright at the base, flatter on top). */
	static final int[] DOME_COURSES = {9, 9, 8, 8, 7, 6, 4, 2};

	private static Direction outward(int dx, int dz) {
		return Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? E : W) : (dz > 0 ? S : N);
	}

	/**
	 * The copper dome, built as octagonal courses: each course is an octagon one or two steps
	 * smaller than the one below; its exposed rim is copper stairs facing outwards (a slab and a
	 * stair where it steps in by two), so the eight facets read as clean straight runs. The shell is
	 * two blocks thick and lined with copper (no light-coloured notches at the hips; a warm copper
	 * vault inside). A glazed lantern with a copper cap and a finial sits on top.
	 */
	private static void dome(Plan p) {
		int n = DOME_COURSES.length;
		for (int k = 0; k < n; k++) {
			int y = DOME_BASE + k;
			int a = DOME_COURSES[k];
			int next = k + 1 < n ? DOME_COURSES[k + 1] : 1;
			for (int dx = -a; dx <= a; dx++) {
				for (int dz = -a; dz <= a; dz++) {
					if (!octInside(dx, dz, a)) {
						continue;
					}
					int x = AX + dx;
					int z = AZ + dz;
					if (octInside(dx, dz, a - 2)) {
						p.set(x, y, z, AIR);
						continue;
					}
					boolean outer = !octInside(dx, dz, a - 1);
					boolean exposed = !octInside(dx, dz, next);
					Direction in = outward(dx, dz).getOpposite();
					BlockState s;
					if (!exposed) {
						s = COPPER;
					} else if (next <= a - 2 && outer) {
						s = St.slab(COPPER_SLAB, false);
					} else {
						s = St.stairs(COPPER_STAIRS, in, false);
					}
					p.set(x, y, z, s);
				}
			}
		}
		// lantern on top: glazed 3x3 with copper corner posts, a copper cap, a finial
		int top = DOME_BASE + n;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
				p.set(AX + dx, top, AZ + dz, corner ? COPPER : GLASS);
				p.set(AX + dx, top + 1, AZ + dz, corner ? St.slab(COPPER_SLAB, false) : St.stairs(COPPER_STAIRS,
					dx < 0 ? E : dx > 0 ? W : dz < 0 ? S : N, false));
			}
		}
		// the top course's inside (a = 3 -> the oculus under the lantern) is open
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (octInside(dx, dz, 1)) {
					p.set(AX + dx, top - 1, AZ + dz, AIR);
				}
			}
		}
		p.set(AX, top + 1, AZ, COPPER);
		p.set(AX, top + 2, AZ, Blocks.LIGHTNING_ROD.waxed().unaffected().defaultBlockState());
		// cornice: upside-down walnut stairs round the top of the octagon wall, facing in (overhang out)
		for (int dx = -10; dx <= 10; dx++) {
			for (int dz = -10; dz <= 10; dz++) {
				if (octFoot(dx, dz) || !octInside(dx, dz, 10)) {
					continue;
				}
				int ax = Math.abs(dx);
				int az = Math.abs(dz);
				Direction in = ax >= az ? (dx > 0 ? W : E) : (dz > 0 ? N : S);
				if (!octFoot(dx + in.getStepX(), dz + in.getStepZ())) {
					continue;
				}
				int x = AX + dx;
				int z = AZ + dz;
				if (p.isAir(x, ATRIUM_TOP, z)) {
					p.set(x, ATRIUM_TOP, z, St.stairs(Blocks.DARK_OAK_STAIRS, in, true));
				}
			}
		}
	}

	// ================================================================== floors

	private static void floors(Plan p) {
		// hall: birch with a walnut border and a terracotta runner on the main axis
		for (int x = -HX + 1; x <= HX - 1; x++) {
			for (int z = HZN + 1; z <= HZS - 1; z++) {
				BlockState f = FLOOR_WOOD;
				if (Math.abs(x) == HX - 1 || z == HZN + 1 || z == HZS - 1) {
					f = DARK_PLANKS;
				}
				if (Math.abs(x) <= 1 && z >= HZN + 4) {
					f = Math.abs(x) == 1 ? DARK_PLANKS : TILE;
				}
				p.set(x, FLOOR, z, f);
			}
		}
		// the opening's threshold
		for (int x = -3; x <= 3; x++) {
			p.set(x, FLOOR, HZS, Math.abs(x) <= 1 ? TILE : DARK_PLANKS);
		}
		// atrium: concentric octagonal rings
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				if (!octIn(dx, dz)) {
					continue;
				}
				int r = octR(dx, dz);
				BlockState f;
				if (r <= 1) {
					f = DARK_PLANKS;
				} else if (r == 2) {
					f = TILE;
				} else if (r == 3) {
					f = DARK_PLANKS;
				} else if (r == 8) {
					f = DARK_PLANKS;
				} else if (r == 7) {
					f = TILE;
				} else {
					f = FLOOR_WOOD;
				}
				p.set(AX + dx, FLOOR, AZ + dz, f);
			}
		}
	}

	// ================================================================== stations

	private static void desks(Plan p, Anchors.Builder a) {
		List<String> ids = Cast.ids();
		BlockState desk = St.slab(Blocks.DARK_OAK_SLAB, true);
		for (int i = 0; i < DESK_X.length; i++) {
			int bx = DESK_X[i];
			String id = i < ids.size() ? ids.get(i) : "agent" + i;
			int zd = HZN + 1; // -9: desk + monitor row against the north wall
			for (int x = bx - 1; x <= bx + 1; x++) {
				p.set(x, FEET, zd, desk);
				for (int y = FEET + 1; y <= FEET + 2; y++) {
					p.set(x, y, zd, ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, S).setValue(MonitorBlock.LIT, true));
					p.bind(x, y, zd, id);
				}
			}
			// status lamp set into the wall above the monitor
			p.set(bx, FEET + 3, HZN, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
			p.bind(bx, FEET + 3, HZN, "agent:" + id);
			p.set(bx - 1, FEET + 3, HZN, PLASTER_FRAME);
			p.set(bx + 1, FEET + 3, HZN, PLASTER_FRAME);
			// chair (backrest to the south), plant at the bay edge
			p.set(bx - 1, FEET, zd + 2, St.stairs(Blocks.DARK_OAK_STAIRS, S, false));
			p.set(bx - 2, FEET, zd + 1, St.of(Blocks.POTTED_FERN));
			p.set(bx + 2, FEET, zd + 1, St.of(Blocks.POTTED_FERN));
			// anchors: stand at the desk, the chair, the screen, a close-up camera
			a.put(AnchorNames.desk(id), bx + 0.05, FEET, zd + 1.5, 180, 0);
			a.put("seat_" + id, bx - 0.5, FEET + 0.5, zd + 2.5, 180, 0);
			a.put(AnchorNames.monitor(id), bx + 0.5, FEET + 2.0, zd + 0.25 + 0.002, 0, 0);
			a.cameraLookAt("desk_" + id, bx + 2.0, FEET + 2.3, zd + 4.8, bx + 0.9, FEET + 1.95, zd + 0.25);
		}
		// window seat + planters in the centre bay under the big north window
		for (int x = -3; x <= 3; x++) {
			p.set(x, FEET, HZN + 1, St.stairs(Blocks.DARK_OAK_STAIRS, N, false));
		}
		p.set(-4, FEET, HZN + 1, Blocks.MOSS_BLOCK.defaultBlockState());
		p.set(4, FEET, HZN + 1, Blocks.MOSS_BLOCK.defaultBlockState());
		p.set(-4, FEET + 1, HZN + 1, Blocks.FLOWERING_AZALEA.defaultBlockState());
		p.set(4, FEET + 1, HZN + 1, Blocks.AZALEA.defaultBlockState());
	}

	private static void library(Plan p, Anchors.Builder a) {
		int xw = -HX + 1; // -23
		BlockState archive = ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, E);
		// west wall: three archive bays between walnut pilasters, three shelves high
		for (int z = HZN + 1; z <= HZS - 1; z++) {
			boolean pil = z == -6 || z == RIDGE_Z || z == 2;
			for (int y = FEET; y <= FEET + 2; y++) {
				if (pil) {
					p.set(xw, y, z, post());
				} else if (y == FEET + 1 && (z == -4 || z == 0 || z == 4)) {
					p.set(xw, y, z, ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, E));
				} else {
					p.set(xw, y, z, archive);
					p.bind(xw, y, z, "shared");
				}
			}
			p.set(xw, FEET + 3, z, pil ? post() : St.stairs(Blocks.DARK_OAK_STAIRS, W, true));
		}
		// north wall shelves in the corner (x -23..-19)
		for (int x = -22; x <= -19; x++) {
			for (int y = FEET; y <= FEET + 2; y++) {
				p.set(x, y, HZN + 1, y == FEET + 1 ? Blocks.CHISELED_BOOKSHELF.defaultBlockState()
					.setValue(HorizontalDirectionalBlock.FACING, S) : Blocks.BOOKSHELF.defaultBlockState());
			}
		}
		// freestanding double shelf (memory archive both sides) and a catalog
		for (int z = -1; z <= 1; z++) {
			p.set(-18, FEET, z, ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, W));
			p.bind(-18, FEET, z, "shared");
			p.set(-18, FEET + 1, z, z == 0 ? St.of(Blocks.POTTED_FLOWERING_AZALEA) : St.candle(Blocks.CANDLE, 2, true));
		}
		p.set(-18, FEET, -2, ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, W));
		p.set(-18, FEET, 2, ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, W));
		// lecterns and a reading table
		p.set(-20, FEET, -4, St.facing(Blocks.LECTERN, W));
		p.set(-20, FEET, 3, St.facing(Blocks.LECTERN, W));
		// parquet "rug" for the library floor
		for (int x = -22; x <= -19; x++) {
			for (int z = -5; z <= 4; z++) {
				p.set(x, FLOOR, z, PARQUET);
			}
		}
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 1), -21.5, FEET, -2.5, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 2), -21.5, FEET, 1.5, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 3), -19.0, FEET, -4.4, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 4), -19.0, FEET, 3.6, 90, 0);
	}

	private static void workshop(Plan p, Anchors.Builder a) {
		int xe = HX - 1; // 23
		BlockState bench = St.slab(Blocks.DARK_OAK_SLAB, true);
		// test bench along the east wall (z -6..-2): CI lamps set into the gable wall, a terminal, lab glassware
		for (int z = -6; z <= -2; z++) {
			p.set(xe, FEET, z, bench);
		}
		for (int i = 0; i < 3; i++) {
			int z = -5 + i;
			p.set(HX, FEET + 2, z, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
			p.bind(HX, FEET + 2, z, "ci:#" + (i + 1));
		}
		p.set(xe, FEET + 1, -6, Blocks.BREWING_STAND.defaultBlockState());
		p.set(xe, FEET + 1, -2, St.of(Blocks.POTTED_CACTUS));
		p.set(xe, FEET + 1, -4, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, W));
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 1), 21.6, FEET, -4.6, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 2), 21.6, FEET, -2.4, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 3), 19.8, FEET, -3.5, -90, 0);
		// terminals: north wall, NE corner, facing south
		for (int x = 19; x <= 21; x += 2) {
			p.set(x, FEET, HZN + 1, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, S));
		}
		p.set(20, FEET, HZN + 1, bench);
		p.set(22, FEET, HZN + 1, bench);
		p.set(22, FEET + 1, HZN + 1, St.of(Blocks.POTTED_FERN));
		p.set(20, FEET + 1, HZN + 1, St.candle(Blocks.CANDLE, 3, true));
		a.put(AnchorNames.slot(AnchorNames.TERMINAL, 1), 19.5, FEET, -7.6, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.TERMINAL, 2), 21.5, FEET, -7.6, 180, 0);
		// merge station: two worktops against the east wall (z 1..2), facing west
		for (int z = 1; z <= 2; z++) {
			p.set(xe, FEET, z, ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, W).setValue(MergeStationBlock.ACTIVE,
				false));
		}
		p.set(xe, FEET, 0, bench);
		p.set(xe, FEET, 3, bench);
		p.set(xe, FEET + 1, 3, St.lantern(COPPER_LANTERN, false));
		a.put(AnchorNames.slot(AnchorNames.MERGESTATION, 1), 21.5, FEET, 1.5, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.MERGESTATION, 2), 21.5, FEET, 3.4, -90, 0);
	}

	private static void lounge(Plan p, Anchors.Builder a) {
		// rug
		for (int x = -14; x <= -6; x++) {
			for (int z = -3; z <= 3; z++) {
				boolean border = x == -14 || x == -6 || z == -3 || z == 3;
				p.set(x, FEET, z, St.carpet(border ? DyeColor.BROWN : DyeColor.WHITE));
			}
		}
		// fireplace on the south wall (x -12..-9), the chimney stack rises from it through the roof
		for (int x = -12; x <= -9; x++) {
			for (int y = FEET; y <= WALL_TOP; y++) {
				boolean jamb = x == -12 || x == -9;
				if (y <= FEET + 1 && !jamb) {
					p.set(x, y, HZS - 1, y == FEET ? Blocks.CAMPFIRE.defaultBlockState() : AIR);
				} else {
					p.set(x, y, HZS - 1, Blocks.MUD_BRICKS.defaultBlockState());
				}
			}
			p.set(x, FEET + 2, HZS - 2, St.slab(Blocks.MUD_BRICK_SLAB, true));
		}
		// sofa (wool stairs) facing the fire, coffee table, a plant
		Block sofa = Blocks.WOOL_STAIRS.pick(DyeColor.ORANGE);
		for (int x = -13; x <= -8; x++) {
			p.set(x, FEET, -3, St.stairs(sofa, N, false));
		}
		p.set(-14, FEET, -3, St.stairs(sofa, E, false));
		for (int x = -12; x <= -10; x++) {
			p.set(x, FEET, 0, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
		p.set(-11, FEET + 1, 0, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		p.set(-12, FEET + 1, 0, St.candle(Blocks.CANDLE, 3, true));
		p.set(-14, FEET, 3, St.of(Blocks.POTTED_FERN));
		// six slots in two staggered rows facing each other across the table, >= 1.6 apart
		double[][] slots = {{-12.9, -1.5, 0}, {-10.9, -1.4, 0}, {-8.9, -1.6, 0}, {-12.0, 2.3, 180}, {-9.9, 2.5, 180}, {-7.7, 1.9, 180}};
		for (int i = 0; i < slots.length; i++) {
			a.put(AnchorNames.slot(AnchorNames.LOUNGE, i + 1), slots[i][0], FEET + 0.0625, slots[i][1], (float) slots[i][2], 0);
		}
	}

	private static void meeting(Plan p, Anchors.Builder a) {
		// rug + table (x 8..11, z -1..1), chairs around
		for (int x = 6; x <= 13; x++) {
			for (int z = -3; z <= 3; z++) {
				boolean border = x == 6 || x == 13 || z == -3 || z == 3;
				p.set(x, FEET, z, St.carpet(border ? DyeColor.BROWN : DyeColor.LIGHT_GRAY));
			}
		}
		BlockState top = St.slab(Blocks.DARK_OAK_SLAB, true);
		for (int x = 8; x <= 11; x++) {
			p.set(x, FEET, 0, top);
		}
		p.set(9, FEET + 1, 0, St.lantern(COPPER_LANTERN, false));
		for (int x = 8; x <= 11; x += 3) {
			p.set(x, FEET, -2, St.stairs(Blocks.BIRCH_STAIRS, N, false));
			p.set(x, FEET, 2, St.stairs(Blocks.BIRCH_STAIRS, S, false));
		}
		a.put(AnchorNames.slot(AnchorNames.MEETING, 1), 9.0, FEET, -1.5, 0, 0);
		a.put(AnchorNames.slot(AnchorNames.MEETING, 2), 11.4, FEET, 1.6, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.MEETING, 3), 7.1, FEET, 0.5, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.MEETING, 4), 12.9, FEET, 0.5, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.MEETING, 5), 9.4, FEET, 1.5, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.MEETING, 6), 10.9, FEET, -1.3, 0, 0);
	}

	private static void atrium(Plan p, Anchors.Builder a) {
		// --- hologram plinth (centre): goal lamp on a copper base; the hologram is drawn by the lamp's BER
		p.set(AX, FEET, AZ, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
		p.bind(AX, FEET, AZ, "goal:atrium");
		// a low 3x3 copper dais around the projector lamp
		for (int sx = -1; sx <= 1; sx++) {
			for (int sz = -1; sz <= 1; sz++) {
				if (sx != 0 || sz != 0) {
					p.set(AX + sx, FEET, AZ + sz, St.slab(COPPER_SLAB, false));
				}
			}
		}
		a.put(AnchorNames.GOAL_ATRIUM, AX + 0.5, FEET, AZ + 0.5, 180, 0);

		// --- task wall: west side, 7 x 4 board facing east, walnut surround, light strip above
		int xb = AX - 8;
		BlockState board = ModBlocks.TASK_BOARD.defaultBlockState().setValue(PanelBlock.FACING, E);
		for (int z = AZ - 3; z <= AZ + 3; z++) {
			for (int y = FEET + 1; y <= FEET + 4; y++) {
				p.set(xb, y, z, board);
			}
			p.set(xb, FEET, z, WALNUT_TRIM);
			p.set(xb, FEET + 5, z, WALNUT);
			p.set(xb + 1, FEET + 5, z, ModBlocks.GLOW_STRIP.defaultBlockState().setValue(GlowStripBlock.FACING, E));
		}
		for (int y = FEET; y <= FEET + 5; y++) {
			p.set(xb, y, AZ - 4, post());
			p.set(xb, y, AZ + 4, post());
		}
		a.put(AnchorNames.TASK_WALL, xb + 1 - 0.875 + 0.002, FEET + 3.0, AZ + 0.5, -90, 0);
		p.setIfAir(xb + 1, FEET + 1, AZ - 3, St.light(13));
		p.setIfAir(xb + 1, FEET + 1, AZ + 3, St.light(13));

		// --- decision podium: east side, facing west; alcove with the decision lamp and the bell
		int xp = AX + 6;
		p.set(xp, FEET, AZ, ModBlocks.DECISION_PODIUM.defaultBlockState().setValue(FacingEntityBlock.FACING, W).setValue(DecisionPodiumBlock.OPEN,
			false));
		a.put(AnchorNames.DECISION_PODIUM, xp + 0.5, FEET + 0.95, AZ + 0.5, 90, 0);
		int xa = AX + 8;
		for (int z = AZ - 2; z <= AZ + 2; z++) {
			for (int y = FEET; y <= FEET + 5; y++) {
				boolean side = Math.abs(z - AZ) == 2;
				p.set(xa, y, z, side ? post() : y == FEET ? WALNUT_TRIM : WALNUT);
			}
		}
		p.set(xa, FEET + 4, AZ, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.OFF));
		p.bind(xa, FEET + 4, AZ, "decisions");
		p.set(xa - 1, FEET + 3, AZ - 1, St.bell(W, BellAttachType.SINGLE_WALL));
		p.set(xa - 1, FEET + 3, AZ + 1, St.lantern(COPPER_LANTERN, false));
		p.set(xa - 1, FEET + 2, AZ + 1, St.slab(Blocks.DARK_OAK_SLAB, true));
		// user console beside the podium
		p.set(xp + 1, FEET, AZ + 3, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, W));
		// the player's spot in front of the podium; waiting agents stand beside it, facing that spot
		double ux = xp - 0.5;
		double uz = AZ + 0.5;
		a.put("podium_user", ux, FEET, uz, -90, 0);
		double[][] userSlots = {{xp - 1.2, AZ - 1.6}, {xp - 1.2, AZ + 2.6}, {xp - 2.9, AZ - 0.9}};
		for (int i = 0; i < userSlots.length; i++) {
			double sx = userSlots[i][0];
			double sz = userSlots[i][1];
			float yaw = (float) Math.toDegrees(Math.atan2(-(ux - sx), uz - sz));
			a.put(AnchorNames.slot(AnchorNames.USER, i + 1), sx, FEET, sz, yaw, 0);
		}

		// --- benches + planters on the diagonals
		for (int sx : new int[] {-1, 1}) {
			// NW / NE: benches facing the centre
			p.set(AX + sx * 5, FEET, AZ - 6, St.stairs(Blocks.DARK_OAK_STAIRS, sx < 0 ? W : E, false));
			p.set(AX + sx * 6, FEET, AZ - 5, St.stairs(Blocks.DARK_OAK_STAIRS, sx < 0 ? W : E, false));
			p.set(AX + sx * 4, FEET, AZ - 7, St.of(Blocks.POTTED_FERN));
			// SW / SE: planters with azalea
			p.set(AX + sx * 6, FEET, AZ + 5, Blocks.MOSS_BLOCK.defaultBlockState());
			p.set(AX + sx * 5, FEET, AZ + 6, Blocks.MOSS_BLOCK.defaultBlockState());
			p.set(AX + sx * 6, FEET + 1, AZ + 5, Blocks.FLOWERING_AZALEA.defaultBlockState());
			p.set(AX + sx * 5, FEET + 1, AZ + 6, Blocks.AZALEA.defaultBlockState());
		}
		// --- light: a cornice ledge round the drum with a hidden light cove, a copper chandelier above the hologram
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				if (!octIn(dx, dz) || octR(dx, dz) != 8) {
					continue;
				}
				p.set(AX + dx, ATRIUM_TOP, AZ + dz, St.slab(Blocks.DARK_OAK_SLAB, true));
				if ((dx + dz) % 3 == 0) {
					p.setIfAir(AX + dx, ATRIUM_TOP + 1, AZ + dz, St.light(13));
				}
			}
		}
		for (int y = DOME_BASE + 1; y < DOME_BASE + DOME_COURSES.length; y++) {
			p.set(AX, y, AZ, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
		}
		for (int sx = -2; sx <= 2; sx += 4) {
			p.set(AX + sx, DOME_BASE, AZ, St.lantern(COPPER_LANTERN, true));
			p.set(AX, DOME_BASE, AZ + sx, St.lantern(COPPER_LANTERN, true));
			p.set(AX + sx, DOME_BASE + 1, AZ, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
			p.set(AX, DOME_BASE + 1, AZ + sx, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
		}
		p.fill(AX - 2, DOME_BASE + 2, AZ, AX + 2, DOME_BASE + 2, AZ, St.chain(Blocks.IRON_CHAIN, Direction.Axis.X));
		p.fill(AX, DOME_BASE + 2, AZ - 2, AX, DOME_BASE + 2, AZ + 2, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Z));
		p.set(AX, DOME_BASE + 2, AZ, COPPER);
		// hidden fill light (invisible light blocks) so no corner of the atrium is dark
		for (int dx = -8; dx <= 8; dx += 4) {
			for (int dz = -8; dz <= 8; dz += 4) {
				if (octIn(dx, dz) && (dx != 0 || dz != 0)) {
					p.setIfAir(AX + dx, FEET + 1, AZ + dz, St.light(14));
					p.setIfAir(AX + dx, FEET + 6, AZ + dz, St.light(12));
				}
			}
		}
	}

	private static void hallLighting(Plan p) {
		for (int x = -21; x <= 21; x++) {
			if (!isPostX(x)) {
				continue;
			}
			for (int z : new int[] {-5, 1}) {
				p.set(x, FRIEZE - 1, z, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
				p.set(x, FRIEZE - 2, z, St.lantern(Blocks.LANTERN, true));
			}
		}
		// warm wash along the tops of the long walls
		for (int x = -HX + 1; x <= HX - 1; x++) {
			if (isPostX(x)) {
				continue;
			}
			p.setIfAir(x, WALL_TOP, HZN + 1, ModBlocks.GLOW_STRIP.defaultBlockState().setValue(GlowStripBlock.FACING, S));
			if (!octFoot(x - AX, HZS - AZ)) {
				p.setIfAir(x, WALL_TOP, HZS - 1, ModBlocks.GLOW_STRIP.defaultBlockState().setValue(GlowStripBlock.FACING, N));
			}
		}
		// invisible fill light (light blocks: no model, no collision) two blocks above the floor, so
		// the hall never has a dark corner at night; the visible lanterns and strips stay the key light
		for (int x = -22; x <= 22; x += 4) {
			for (int z = -8; z <= 4; z += 4) {
				p.setIfAir(x, FEET + 1, z, St.light(14));
			}
		}
	}

	// ================================================================== cameras

	private static void cameras(Anchors.Builder a) {
		int az = AZ;
		a.cameraLookAt("exterior_hero", 26, 78, 47, -1, 72, 6);
		a.cameraLookAt("night", 17, 69.5, 41, -5, 70, 9);
		a.cameraLookAt("entrance_atrium", AX + 0.5, FEET + 1.7, az + 7.6, AX + 0.5, FEET + 3.2, az - 2);
		a.cameraLookAt("task_wall", AX - 2.2, FEET + 2.9, az + 0.5, AX - 7.9, FEET + 2.9, az + 0.5);
		a.cameraLookAt("decision_podium", AX + 1.8, FEET + 2.25, az + 3.9, AX + 6.2, FEET + 1.25, az - 0.9);
		a.cameraLookAt("wide_interior", 19.6, FEET + 4.1, 3.3, -6, FEET + 0.9, -6.0);
		a.cameraLookAt("library", -15.0, FEET + 3.0, 4.0, -22.5, FEET + 1.0, -2.0);
		a.cameraLookAt("console", AX + 2.5, FEET + 2.2, az + 6.5, AX + 7.5, FEET + 1.0, az + 2.5);
		a.cameraLookAt("merge_station", 17.0, FEET + 2.4, -2.5, 22.6, FEET + 0.9, 2.0);
		a.cameraLookAt("testbench", 16.0, FEET + 2.6, -2.0, 23, FEET + 1.0, -6.0);
		a.cameraLookAt("lounge", -4.0, FEET + 3.0, -5.0, -11, FEET + 0.5, 1.0);
		a.cameraLookAt("hall", 0.5, FEET + 2.2, HZS + 2.0, 0.5, FEET + 2.0, HZN);
		// QA: the editing agent's desk in the showcase is Juniper's
		int jx = DESK_X[1];
		a.cameraLookAt("agent_desk", jx + 2.0, FEET + 2.3, HZN + 5.8, jx + 0.9, FEET + 1.95, HZN + 1.25);
	}
}
