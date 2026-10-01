package dev.agentcraft.hq;

import static dev.agentcraft.hq.StudioHqBuilder.AX;
import static dev.agentcraft.hq.StudioHqBuilder.AZ;
import static dev.agentcraft.hq.StudioHqBuilder.FEET;
import static dev.agentcraft.hq.StudioHqBuilder.FLOOR;
import static dev.agentcraft.hq.StudioHqBuilder.GROUND;
import static dev.agentcraft.hq.StudioHqBuilder.HX;
import static dev.agentcraft.hq.StudioHqBuilder.HZN;
import static dev.agentcraft.hq.StudioHqBuilder.HZS;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Everything outside the walls: terraces, the entrance portico, paths with lanterns, the pond, trees
 * and a meadow of grass and flowers. All "random" choices come from a coordinate hash, so the
 * landscape is identical on every build.
 */
final class HqLandscape {
	private HqLandscape() {
	}

	static int hash(int x, int z, int salt) {
		long h = x * 73856093L ^ z * 19349663L ^ salt * 83492791L;
		h ^= h >>> 13;
		h *= 0x5bd1e995L;
		h ^= h >>> 15;
		h *= 0x27d4eb2dL;
		h ^= h >>> 16;
		return (int) (h & 0x7fffffff);
	}

	static double rnd(int x, int z, int salt) {
		return hash(x, z, salt) / (double) 0x7fffffff;
	}

	/** Ground work before the building (nothing yet: the plan's default is the meadow). */
	static void ground(Plan p) {
	}

	static void exterior(Plan p) {
		terraces(p);
		portico(p);
		paths(p);
		pond(p);
		garden(p);
		trees(p);
		foundationPlanting(p);
		meadow(p);
	}

	/** True when (x, z) is untouched meadow in the plan (grass with air above). */
	static boolean meadowAt(Plan p, int x, int z) {
		return p.get(x, GROUND, z).is(Blocks.GRASS_BLOCK) && p.isAir(x, FLOOR, z);
	}

	// ------------------------------------------------------------------ terraces + portico

	private static void terraces(Plan p) {
		BlockState deck = Blocks.SPRUCE_PLANKS.defaultBlockState();
		for (int side : new int[] {-1, 1}) {
			int xa = side < 0 ? -HX : 10;
			int xb = side < 0 ? -10 : HX;
			for (int x = xa; x <= xb; x++) {
				for (int z = HZS + 1; z <= HZS + 4; z++) {
					boolean edge = z == HZS + 4 || x == xa || x == xb;
					p.set(x, GROUND, z, Blocks.DIRT.defaultBlockState());
					p.set(x, FLOOR, z, edge ? StudioHqBuilder.PLINTH : deck);
				}
			}
			// planters along the outer edge, a step down in the middle
			int mid = side < 0 ? -17 : 17;
			for (int x = xa + 1; x <= xb - 1; x++) {
				int z = HZS + 4;
				if (Math.abs(x - mid) <= 1) {
					p.set(x, FLOOR, z, St.slab(Blocks.MUD_BRICK_SLAB, false));
					p.set(x, FLOOR, z + 1, St.slab(Blocks.SPRUCE_SLAB, false));
					continue;
				}
				if ((x - xa) % 4 == 2) {
					p.set(x, FEET, z, Blocks.MOSS_BLOCK.defaultBlockState());
					p.set(x, FEET + 1, z, (hash(x, z, 5) & 1) == 0 ? Blocks.FLOWERING_AZALEA.defaultBlockState() : Blocks.AZALEA.defaultBlockState());
				} else if ((x - xa) % 4 == 0 && x != xa + 0) {
					p.set(x, FEET, z, Blocks.SPRUCE_FENCE.defaultBlockState());
					p.set(x, FEET + 1, z, St.lantern(Blocks.LANTERN, false));
				}
			}
			// window boxes under the hall windows (on the deck, against the wall)
			for (int x = xa + 1; x <= xb - 1; x++) {
				if (StudioHqBuilder.isPostX(x) || Math.abs(x) < 10) {
					continue;
				}
				if (hash(x, 3, 9) % 3 == 0) {
					p.set(x, FEET, HZS + 1, St.trapdoor(Blocks.SPRUCE_TRAPDOOR, Direction.SOUTH, true, false));
				}
			}
			// benches facing out over the meadow
			for (int bx = mid - 5; bx <= mid + 5; bx += 10) {
				p.set(bx, FEET, HZS + 2, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
				p.set(bx + 1, FEET, HZS + 2, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
			}
		}
	}

	private static void portico(Plan p) {
		int z0 = AZ + 10; // 25
		int z1 = AZ + 13; // 28
		for (int x = -4; x <= 4; x++) {
			for (int z = z0; z <= z1; z++) {
				p.set(x, GROUND, z, Blocks.DIRT.defaultBlockState());
				p.set(x, FLOOR, z, Math.abs(x) == 4 || z == z1 ? StudioHqBuilder.PLINTH : StudioHqBuilder.DARK_PLANKS);
			}
			// two steps down to the path
			if (Math.abs(x) <= 2) {
				p.set(x, FLOOR, z1 + 1, St.slab(Blocks.MUD_BRICK_SLAB, false));
			}
		}
		for (int x = -2; x <= 2; x++) {
			p.set(x, FLOOR, z1, St.slab(Blocks.MUD_BRICK_SLAB, true));
		}
		for (int sx : new int[] {-3, 3}) {
			for (int z : new int[] {z0, z1}) {
				p.fill(sx, FEET, z, sx, FEET + 4, z, StudioHqBuilder.post());
			}
			p.set(sx, FEET + 5, z1, StudioHqBuilder.beam(Direction.Axis.X));
		}
		p.fill(-3, FEET + 5, z1, 3, FEET + 5, z1, StudioHqBuilder.beam(Direction.Axis.X));
		p.fill(-3, FEET + 5, z0, -3, FEET + 5, z1, StudioHqBuilder.beam(Direction.Axis.Z));
		p.fill(3, FEET + 5, z0, 3, FEET + 5, z1, StudioHqBuilder.beam(Direction.Axis.Z));
		for (int x = -4; x <= 4; x++) {
			for (int z = z0; z <= z1 + 1; z++) {
				p.set(x, FEET + 6, z, St.slab(StudioHqBuilder.COPPER_SLAB, false));
			}
		}
		p.set(0, FEET + 4, z0 + 1, St.lantern(StudioHqBuilder.COPPER_LANTERN, true));
		p.set(0, FEET + 5, z0 + 1, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
		// potted plants beside the door
		p.set(-2, FEET, z0, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		p.set(2, FEET, z0, St.of(Blocks.POTTED_FLOWERING_AZALEA));
	}

	// ------------------------------------------------------------------ paths

	private static void path(Plan p, int x, int z) {
		if (p.get(x, GROUND, z).is(Blocks.GRASS_BLOCK) && p.isAir(x, FLOOR, z)) {
			p.set(x, GROUND, z, Blocks.DIRT_PATH.defaultBlockState());
		}
	}

	private static void lanternPost(Plan p, int x, int z) {
		if (!meadowAt(p, x, z)) {
			return;
		}
		p.set(x, FLOOR, z, Blocks.SPRUCE_FENCE.defaultBlockState());
		p.set(x, FLOOR + 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
		p.set(x, FLOOR + 2, z, St.lantern(Blocks.LANTERN, false));
	}

	private static void paths(Plan p) {
		int z1 = AZ + 15;
		// main path south from the portico, gently curving
		for (int z = z1; z <= 54; z++) {
			int cx = (int) Math.round(Math.sin((z - z1) / 9.0) * 2.0);
			for (int x = cx - 1; x <= cx + 1; x++) {
				path(p, x, z);
			}
			if ((z - z1) % 7 == 3) {
				lanternPost(p, cx + ((z - z1) / 7 % 2 == 0 ? 3 : -3), z);
			}
		}
		// terrace steps -> meadow paths
		// terrace steps -> curve in towards the portico (passes east of the pond)
		for (int side : new int[] {-1, 1}) {
			int mid = side * 17;
			for (int z = HZS + 6; z <= AZ + 15; z++) {
				double t = (z - HZS - 6) / (double) (AZ + 15 - HZS - 6);
				int x = mid - side * (int) Math.round(13 * (1 - Math.cos(t * Math.PI / 2)));
				path(p, x, z);
				path(p, x - side, z);
			}
		}
		// a ring path around the back of the hall
		for (int x = -HX - 3; x <= HX + 3; x++) {
			path(p, x, HZN - 4);
		}
		for (int z = HZN - 4; z <= HZS + 6; z++) {
			path(p, -HX - 3, z);
			path(p, HX + 3, z);
		}
	}

	// ------------------------------------------------------------------ pond

	private static void pond(Plan p) {
		double cx = -24;
		double cz = 21;
		for (int x = -36; x <= -12; x++) {
			for (int z = 12; z <= 32; z++) {
				double dx = (x - cx) / 8.5;
				double dz = (z - cz) / 6.0;
				double wob = 0.12 * Math.sin(x * 0.9 + z * 0.4) + 0.1 * Math.cos(z * 1.3 - x * 0.2);
				double d = Math.sqrt(dx * dx + dz * dz) + wob;
				if (d > 1.18 || p.get(x, GROUND, z).is(Blocks.DIRT_PATH)) {
					continue;
				}
				if (d > 1.0) {
					// bank: mud, moss, the odd stone
					int h = hash(x, z, 31) % 10;
					p.set(x, GROUND, z, h < 3 ? Blocks.MOSS_BLOCK.defaultBlockState() : h < 5 ? Blocks.MUD.defaultBlockState()
						: Blocks.GRASS_BLOCK.defaultBlockState());
					if (h == 9) {
						p.set(x, FLOOR, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
					}
					continue;
				}
				int depth = d < 0.55 ? 3 : d < 0.85 ? 2 : 1;
				p.set(x, GROUND - depth, z, (hash(x, z, 33) & 3) == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState());
				for (int y = GROUND - depth + 1; y <= GROUND; y++) {
					p.set(x, y, z, Blocks.WATER.defaultBlockState());
				}
				p.set(x, FLOOR, z, Blocks.AIR.defaultBlockState());
				int h = hash(x, z, 35) % 100;
				if (h < 9 && d < 0.95) {
					p.set(x, FLOOR, z, Blocks.LILY_PAD.defaultBlockState());
				} else if (h < 13 && depth >= 2) {
					p.set(x, GROUND - depth + 1, z, Blocks.SEAGRASS.defaultBlockState());
				}
			}
		}
		// a small spruce dock reaching in from the east
		for (int x = -19; x <= -15; x++) {
			for (int z = 20; z <= 21; z++) {
				p.set(x, FLOOR, z, St.slab(Blocks.SPRUCE_SLAB, false));
			}
		}
		p.set(-19, FLOOR, 19, Blocks.SPRUCE_FENCE.defaultBlockState());
		p.set(-19, FLOOR + 1, 19, St.lantern(Blocks.LANTERN, false));
		p.set(-16, FLOOR + 0, 23, Blocks.FIREFLY_BUSH.defaultBlockState());
		p.set(-28, FLOOR, 13, Blocks.FIREFLY_BUSH.defaultBlockState());
		p.set(-34, FLOOR, 23, Blocks.FIREFLY_BUSH.defaultBlockState());
	}

	// ------------------------------------------------------------------ trees

	enum Kind {
		BIRCH(Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, null, 7, 2.3, 3.4),
		POPLAR(Blocks.POPLAR_LOG, Blocks.YELLOW_POPLAR_LEAVES, Blocks.ORANGE_POPLAR_LEAVES, 4, 2.3, 5.6),
		AZALEA(Blocks.OAK_LOG, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES, 4, 3.6, 2.3),
		OAK(Blocks.DARK_OAK_LOG, Blocks.OAK_LEAVES, null, 6, 4.2, 2.8),
		CHERRY(Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES, null, 4, 3.4, 2.3);

		final Block log;
		final Block leaves;
		final Block accent;
		final int trunk;
		final double rx;
		final double ry;

		Kind(Block log, Block leaves, Block accent, int trunk, double rx, double ry) {
			this.log = log;
			this.leaves = leaves;
			this.accent = accent;
			this.trunk = trunk;
			this.rx = rx;
			this.ry = ry;
		}
	}

	/**
	 * A lobed tree: a trunk, two to four branches ending in leaf lobes around a main crown, each lobe
	 * a jittered ellipsoid. Birches and poplars stay narrow; azaleas, oaks and cherries spread.
	 */
	static void tree(Plan p, int x, int z, Kind k, int seed) {
		int trunk = k.trunk + hash(x, z, seed) % 3;
		int base = FLOOR;
		for (int y = base; y < base + trunk; y++) {
			p.set(x, y, z, St.log(k.log, Direction.Axis.Y));
		}
		int top = base + trunk;
		// lobes: (dx, dy, dz, rx, ry)
		java.util.List<double[]> lobes = new java.util.ArrayList<>();
		lobes.add(new double[] {0, k.ry * 0.45, 0, k.rx, k.ry});
		boolean narrow = k == Kind.POPLAR || k == Kind.BIRCH;
		int branches = k == Kind.POPLAR ? 0 : narrow ? 2 : 3 + hash(x, z, seed + 3) % 2;
		double a0 = rnd(x, z, seed + 4) * Math.PI * 2;
		for (int i = 0; i < branches; i++) {
			double ang = a0 + i * Math.PI * 2 / branches + (rnd(x + i, z, seed + 5) - 0.5) * 0.8;
			double reach = narrow ? k.rx * 0.55 : k.rx * (0.75 + rnd(x, z + i, seed + 6) * 0.35);
			double bx = Math.cos(ang) * reach;
			double bz = Math.sin(ang) * reach;
			double by = -k.ry * (narrow ? 0.9 : 0.35) - rnd(x - i, z, seed + 7) * 1.2;
			lobes.add(new double[] {bx, by, bz, k.rx * (narrow ? 0.7 : 0.62), k.ry * (narrow ? 0.6 : 0.62)});
			// branch: logs from the trunk towards the lobe centre (sideways axis)
			if (!narrow) {
				int steps = (int) Math.ceil(reach);
				for (int t = 1; t <= steps; t++) {
					double f = t / (double) steps;
					int lx = x + (int) Math.round(bx * f * 0.8);
					int lz = z + (int) Math.round(bz * f * 0.8);
					int ly = (int) Math.round(top + by * 0.5 - 1 + f);
					Direction.Axis ax = Math.abs(bx) > Math.abs(bz) ? Direction.Axis.X : Direction.Axis.Z;
					if (p.isAir(lx, ly, lz)) {
						p.set(lx, ly, lz, St.log(k.log, ax));
					}
				}
			}
		}
		for (double[] l : lobes) {
			double cx = x + 0.5 + l[0];
			double cy = top + l[1];
			double cz = z + 0.5 + l[2];
			int r = (int) Math.ceil(l[3]) + 1;
			int ry = (int) Math.ceil(l[4]) + 1;
			for (int ix = (int) Math.floor(cx) - r; ix <= (int) Math.floor(cx) + r; ix++) {
				for (int iz = (int) Math.floor(cz) - r; iz <= (int) Math.floor(cz) + r; iz++) {
					for (int iy = (int) Math.floor(cy) - ry; iy <= (int) Math.floor(cy) + ry; iy++) {
						if (iy <= base + 1) {
							continue;
						}
						double ex = (ix + 0.5 - cx) / l[3];
						double ey = (iy + 0.5 - cy) / l[4];
						double ez = (iz + 0.5 - cz) / l[3];
						double d = ex * ex + ey * ey + ez * ez;
						double jitter = (rnd(ix * 7 + iy, iz * 13 - iy, seed) - 0.5) * (k == Kind.POPLAR ? 0.25 : 0.55);
						if (d + jitter > 1.0 || !p.isAir(ix, iy, iz)) {
							continue;
						}
						Block leaf = k.accent != null && rnd(ix * 3 + iy, iz + iy * 13, seed + 1) < 0.35 ? k.accent : k.leaves;
						p.set(ix, iy, iz, St.leaves(leaf));
					}
				}
			}
		}
		// leaf litter under the crown
		int lr = (int) Math.ceil(k.rx);
		for (int dx = -lr; dx <= lr; dx++) {
			for (int dz = -lr; dz <= lr; dz++) {
				if ((dx != 0 || dz != 0) && meadowAt(p, x + dx, z + dz) && rnd(x + dx, z + dz, seed + 7) < 0.4) {
					p.set(x + dx, FLOOR, z + dz, St.leafLitter(Direction.from2DDataValue(hash(dx, dz, seed) & 3), 1 + hash(x + dx, z + dz, seed) % 4));
				}
			}
		}
	}

	private static void trees(Plan p) {
		int[][] spots = {
			// x, z, kind ordinal (0 birch, 1 poplar, 2 azalea, 3 oak, 4 cherry). The south-east quadrant is
			// kept open: it is the hero/night sight line (cam_exterior_hero, cam_night).
			{-30, -18, 1}, {-18, -21, 0}, {-5, -23, 3}, {9, -21, 1}, {22, -19, 0}, {35, -15, 2},
			{-39, 0, 2}, {-41, 14, 0}, {-35, 32, 1}, {-25, 41, 4}, {-12, 45, 0}, {-31, 8, 0},
			{39, -3, 1}, {42, 12, 0}, {37, 25, 3}, {3, 48, 1}, {22, 53, 0}, {29, -7, 0},
		};
		Kind[] kinds = Kind.values();
		for (int i = 0; i < spots.length; i++) {
			int[] s = spots[i];
			if (meadowAt(p, s[0], s[1])) {
				tree(p, s[0], s[1], kinds[s[2]], 100 + i);
			}
		}
	}

	// ------------------------------------------------------------------ front garden

	/**
	 * A small formal cottage garden east of the entrance path: four raised beds of tall flowers in a
	 * clipped azalea hedge, gravel walks with a bench and a lantern at the crossing, fireflies at night.
	 */
	private static void garden(Plan p) {
		int x0 = 6;
		int x1 = 20;
		int z0 = 31;
		int z1 = 43;
		int cx = 13;
		int cz = 37;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				boolean walk = Math.abs(x - cx) <= 0 || Math.abs(z - cz) <= 0;
				if (edge) {
					boolean gate = Math.abs(z - cz) <= 0 && x == x0;
					if (gate) {
						p.set(x, GROUND, z, Blocks.GRAVEL.defaultBlockState());
					} else if (meadowAt(p, x, z)) {
						p.set(x, FLOOR, z, St.leaves(Blocks.AZALEA_LEAVES));
					}
					continue;
				}
				if (walk) {
					p.set(x, GROUND, z, Blocks.GRAVEL.defaultBlockState());
					p.set(x, FLOOR, z, AIR_STATE);
					continue;
				}
				// beds: a coarse-dirt rim, tall flowers in the middle, low ones at the edges
				boolean rim = x == x0 + 1 || x == x1 - 1 || z == z0 + 1 || z == z1 - 1 || Math.abs(x - cx) == 1 || Math.abs(z - cz) == 1;
				p.set(x, GROUND, z, Blocks.GRASS_BLOCK.defaultBlockState());
				int h = hash(x, z, 61) % 9;
				if (rim) {
					p.set(x, FLOOR, z, switch (h % 4) {
						case 0 -> Blocks.ALLIUM.defaultBlockState();
						case 1 -> Blocks.AZURE_BLUET.defaultBlockState();
						case 2 -> Blocks.LILY_OF_THE_VALLEY.defaultBlockState();
						default -> St.flowerBed(Blocks.PINK_PETALS, Direction.from2DDataValue(h & 3), 4);
					});
				} else {
					Block tall = switch (h % 3) {
						case 0 -> Blocks.PEONY;
						case 1 -> Blocks.LILAC;
						default -> Blocks.ROSE_BUSH;
					};
					p.set(x, FLOOR, z, St.lower(tall));
					p.set(x, FLOOR + 1, z, St.upper(tall));
				}
			}
		}
		// crossing: bench + lantern, fireflies by the hedge
		p.set(cx + 1, FLOOR, cz + 1, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
		p.set(cx - 1, FLOOR, cz + 1, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
		p.set(cx + 1, FLOOR, cz - 1, Blocks.SPRUCE_FENCE.defaultBlockState());
		p.set(cx + 1, FLOOR + 1, cz - 1, St.lantern(Blocks.LANTERN, false));
		p.set(x1 + 1, FLOOR, z0 + 3, Blocks.FIREFLY_BUSH.defaultBlockState());
		p.set(x1 + 1, FLOOR, z1 - 2, Blocks.FIREFLY_BUSH.defaultBlockState());
		p.set(x0 + 4, FLOOR, z1 + 1, Blocks.FIREFLY_BUSH.defaultBlockState());
	}

	private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();

	// ------------------------------------------------------------------ planting + meadow

	private static void foundationPlanting(Plan p) {
		// low azalea hedge along the north and gable walls
		for (int x = -HX - 1; x <= HX + 1; x++) {
			for (int z : new int[] {HZN - 1}) {
				if (meadowAt(p, x, z) && hash(x, z, 41) % 5 != 0) {
					p.set(x, FLOOR, z, St.leaves(hash(x, z, 42) % 3 == 0 ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.AZALEA_LEAVES));
				}
			}
		}
		for (int z = HZN - 1; z <= HZS + 5; z++) {
			for (int x : new int[] {-HX - 1, HX + 1}) {
				if (meadowAt(p, x, z) && hash(x, z, 43) % 4 != 0) {
					p.set(x, FLOOR, z, St.leaves(hash(x, z, 44) % 3 == 0 ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.AZALEA_LEAVES));
				}
			}
		}
		// flower beds around the atrium
		for (int dx = -11; dx <= 11; dx++) {
			for (int dz = -11; dz <= 11; dz++) {
				int x = AX + dx;
				int z = AZ + dz;
				if (StudioHqBuilder.octFoot(dx, dz) || !StudioHqBuilder.octFoot(clamp(dx), clamp(dz)) && !near(dx, dz)) {
					continue;
				}
				if (meadowAt(p, x, z)) {
					int h = hash(x, z, 47) % 6;
					p.set(x, FLOOR, z, switch (h) {
						case 0 -> Blocks.ALLIUM.defaultBlockState();
						case 1 -> Blocks.OXEYE_DAISY.defaultBlockState();
						case 2 -> St.flowerBed(Blocks.PINK_PETALS, Direction.NORTH, 3);
						case 3 -> Blocks.AZURE_BLUET.defaultBlockState();
						case 4 -> Blocks.FERN.defaultBlockState();
						default -> St.flowerBed(Blocks.WILDFLOWERS, Direction.EAST, 4);
					});
				}
			}
		}
	}

	private static int clamp(int d) {
		return d > 0 ? d - 1 : d < 0 ? d + 1 : 0;
	}

	private static boolean near(int dx, int dz) {
		return StudioHqBuilder.octFoot(clamp(clamp(dx)), clamp(clamp(dz)));
	}

	private static void meadow(Plan p) {
		for (int x = p.minX; x <= p.maxX; x++) {
			for (int z = p.minZ; z <= p.maxZ; z++) {
				if (!meadowAt(p, x, z)) {
					continue;
				}
				double r = rnd(x, z, 51);
				// flower drifts: low-frequency patches
				double patch = Math.sin(x * 0.21 + Math.cos(z * 0.17) * 2.0) + Math.cos(z * 0.23 - x * 0.07);
				BlockState s = null;
				if (r < 0.26) {
					s = Blocks.SHORT_GRASS.defaultBlockState();
				} else if (r < 0.30) {
					if (p.isAir(x, FLOOR + 1, z)) {
						p.set(x, FLOOR + 1, z, St.upper(Blocks.TALL_GRASS));
						s = St.lower(Blocks.TALL_GRASS);
					}
				} else if (r < 0.36 && patch > 0.9) {
					s = St.flowerBed(Blocks.WILDFLOWERS, Direction.from2DDataValue(hash(x, z, 52) & 3), 1 + hash(x, z, 53) % 4);
				} else if (r < 0.40 && patch < -1.1) {
					s = switch (hash(x, z, 54) % 5) {
						case 0 -> Blocks.POPPY.defaultBlockState();
						case 1 -> Blocks.CORNFLOWER.defaultBlockState();
						case 2 -> Blocks.DANDELION.defaultBlockState();
						case 3 -> Blocks.OXEYE_DAISY.defaultBlockState();
						default -> Blocks.LILY_OF_THE_VALLEY.defaultBlockState();
					};
				} else if (r < 0.405) {
					s = Blocks.BUSH.defaultBlockState();
				}
				if (s != null) {
					p.set(x, FLOOR, z, s);
				}
			}
		}
	}
}
