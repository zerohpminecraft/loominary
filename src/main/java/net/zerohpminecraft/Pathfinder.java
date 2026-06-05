package net.zerohpminecraft;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A small, bounded A* over walkable cells, used to route the bot around walls when navigating to
 * chests during restock/catalogue (the build floor is open, so {@link AutoPrintHandler}'s print
 * walk stays straight-line). Not a general pathfinder: it searches one bounded box at the start
 * Y ±a few, walking on solid ground with 1-block step-up and short drops. If it can't reach the
 * goal within the node/radius budget it returns null and the caller falls back to straight-line
 * steering, so navigation never regresses below v1.
 *
 * <p>A "cell" is the block a player's feet occupy: standable when the cell and the cell above are
 * passable (no collision — air, carpet, …) and the block below has a solid top to stand on.
 */
public final class Pathfinder {

    private Pathfinder() {}

    private static final int MAX_NODES = 8000;   // expansion budget — keeps a search to ~a few ms
    private static final int MAX_FALL  = 3;      // how far we'll let a step drop

    /**
     * A walkable path from {@code start} to {@code goal} as the cells to walk to (excluding
     * {@code start}, including {@code goal}), simplified to straight runs; or null if no path is
     * found within {@code maxRadius} (Chebyshev, horizontal) and the node budget.
     */
    public static List<BlockPos> findPath(World world, BlockPos start, BlockPos goal, int maxRadius) {
        if (world == null || start == null || goal == null) return null;
        start = start.toImmutable();
        goal = goal.toImmutable();
        if (start.equals(goal)) return Collections.emptyList();
        if (!walkable(world, goal)) return null;

        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Integer> gScore = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        gScore.put(start, 0);
        open.add(new Node(start, 0, heuristic(start, goal)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node cur = open.poll();
            BlockPos p = cur.pos;
            if (p.equals(goal)) return reconstruct(world, cameFrom, start, goal);
            // Stale queue entry (we found a better path to p already)?
            if (cur.g > gScore.getOrDefault(p, Integer.MAX_VALUE)) continue;
            expanded++;

            for (BlockPos np : neighbors(world, p)) {
                if (Math.abs(np.getX() - start.getX()) > maxRadius
                        || Math.abs(np.getZ() - start.getZ()) > maxRadius) continue;
                int tentative = cur.g + stepCost(p, np);
                if (tentative < gScore.getOrDefault(np, Integer.MAX_VALUE)) {
                    cameFrom.put(np, p);
                    gScore.put(np, tentative);
                    open.add(new Node(np, tentative, tentative + heuristic(np, goal)));
                }
            }
        }
        return null;   // budget exhausted or no route — caller falls back to straight-line
    }

    /** Standable: feet + head clear and a solid top to stand on. Exposed for stand-spot selection. */
    public static boolean walkable(World world, BlockPos pos) {
        return passable(world, pos) && passable(world, pos.up()) && solidTop(world, pos.down());
    }

    private static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG  = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static List<BlockPos> neighbors(World world, BlockPos p) {
        List<BlockPos> out = new ArrayList<>(8);
        // Orthogonal moves, with a 1-block step up or a short drop.
        for (int[] d : ORTHO) {
            BlockPos np = p.add(d[0], 0, d[1]);
            if (walkable(world, np)) {
                out.add(np);
            } else if (passable(world, np) && passable(world, np.up())
                    && passable(world, p.up().up()) && walkable(world, np.up())) {
                out.add(np.up());                               // step up one (needs headroom)
            } else {
                for (int dy = 1; dy <= MAX_FALL; dy++) {        // short drop
                    BlockPos down = np.down(dy);
                    if (walkable(world, down)) { out.add(down); break; }
                    if (solidTop(world, down)) break;           // hit ground we can't stand in
                }
            }
        }
        // Diagonal moves at the same level — only when both orthogonal corner cells are clear, so
        // the player (who has width) doesn't clip a wall corner. A* over these plus the
        // line-of-sight smoothing below yields near-straight, any-angle routes.
        for (int[] d : DIAG) {
            BlockPos np = p.add(d[0], 0, d[1]);
            if (walkable(world, np)
                    && passable(world, p.add(d[0], 0, 0)) && passable(world, p.add(d[0], 1, 0))
                    && passable(world, p.add(0, 0, d[1])) && passable(world, p.add(0, 1, d[1]))) {
                out.add(np);
            }
        }
        return out;
    }

    private static boolean passable(World world, BlockPos pos) {
        VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
        // Air, plus negligibly-thin blocks you walk straight over (carpet, pressure plates,
        // tripwire) so a carpeted floor doesn't block the route. Slabs etc. stay solid.
        return shape.isEmpty() || shape.getMax(Direction.Axis.Y) <= 0.1;
    }

    private static boolean solidTop(World world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static int stepCost(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dz = Math.abs(a.getZ() - b.getZ());
        int horiz = (dx != 0 && dz != 0) ? 14 : 10;          // ~10·√2 for a diagonal
        return horiz + 4 * Math.abs(a.getY() - b.getY());    // mild penalty for up/down
    }

    private static int heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return (int) (10.0 * Math.sqrt(dx * dx + dy * dy + dz * dz));   // euclidean, admissible
    }

    private static List<BlockPos> reconstruct(World world, Map<BlockPos, BlockPos> cameFrom,
                                              BlockPos start, BlockPos goal) {
        List<BlockPos> rev = new ArrayList<>();
        BlockPos p = goal;
        while (p != null && !p.equals(start)) { rev.add(p); p = cameFrom.get(p); }
        Collections.reverse(rev);
        return stringPull(world, start, rev);
    }

    /**
     * Line-of-sight "string pulling": collapse the grid path into the fewest straight segments
     * whose endpoints can still see each other (a walkable straight line). The mover walks each
     * segment in a straight line, so the route comes out at arbitrary x/z angles rather than the
     * grid's axis-aligned staircase.
     */
    private static List<BlockPos> stringPull(World world, BlockPos start, List<BlockPos> path) {
        if (path.size() < 2) return path;
        List<BlockPos> out = new ArrayList<>();
        BlockPos anchor = start;
        for (int i = 0; i < path.size() - 1; i++) {
            if (!lineWalkable(world, anchor, path.get(i + 1))) {
                out.add(path.get(i));        // last point still visible from the anchor — keep it
                anchor = path.get(i);
            }
        }
        out.add(path.get(path.size() - 1));
        return out;
    }

    /** True if a straight walk from {@code a} to {@code b} stays on walkable cells (no corner clipping). */
    private static boolean lineWalkable(World world, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5, ay = a.getY(), az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5, by = b.getY(), bz = b.getZ() + 0.5;
        double dist = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(dist / 0.4));
        int prevX = a.getX(), prevZ = a.getZ();
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            int cx = (int) Math.floor(ax + (bx - ax) * t);
            int cy = (int) Math.round(ay + (by - ay) * t);
            int cz = (int) Math.floor(az + (bz - az) * t);
            if (!walkable(world, new BlockPos(cx, cy, cz))) return false;
            if (cx != prevX && cz != prevZ) {     // crossed a diagonal — both corner cells must be clear
                if (!passable(world, new BlockPos(prevX, cy, cz))
                        || !passable(world, new BlockPos(cx, cy, prevZ))) return false;
            }
            prevX = cx; prevZ = cz;
        }
        return true;
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final int g;     // cost from start (for expansion + staleness)
        final int f;     // g + heuristic (priority)
        Node(BlockPos pos, int g, int f) { this.pos = pos; this.g = g; this.f = f; }
        @Override public int compareTo(Node o) { return Integer.compare(f, o.f); }
    }
}
