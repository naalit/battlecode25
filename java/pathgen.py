indent = 1
def emit(s, skip_n=False):
    global indent
    for line in s.split('\n'):
        if not skip_n and (line.strip().startswith('}') or line.strip().startswith('//}')):
            indent -= 1
        print(" " * (indent * 2) + line.strip())
        if not skip_n and line.strip().endswith('{'):
            indent += 1
class block:
    def __init__(self, s):
        emit(s + " {", True)
    def __enter__(self):
        #emit("{")
        global indent
        indent += 1
    def __exit__(self, *args):
        global indent
        indent -= 1
        emit("}", True)

PAINT_VALUE = 1
TURN_VALUE = 1

print("""
// GENERATED BY microgen.py
package immanentize;

import battlecode.common.*;
import static immanentize.Micro.*;
import static immanentize.RobotPlayer.*;

public class PathHelper {

""")

def g(x, y):
    return f"_v_{x+4}_{y+4}"

def paintCost(loc):
    return f"switch (rc.senseMapInfo({loc}).getPaint()) {{ case ENEMY_PRIMARY, ENEMY_SECONDARY -> {2 * PAINT_VALUE}; case EMPTY -> {PAINT_VALUE}; case ALLY_PRIMARY, ALLY_SECONDARY -> 0; }}"
def paintCostTile(tile):
    return f"switch ({tile}.getPaint()) {{ case ENEMY_PRIMARY, ENEMY_SECONDARY -> {2 * PAINT_VALUE}; case EMPTY -> {PAINT_VALUE}; case ALLY_PRIMARY, ALLY_SECONDARY -> 0; }}"

# Cache variables
for x in range(-4, 5):
    for y in range(-4, 5):
        if x*x + y*y <= 20:
            print(f"  static int {g(x, y)};")
emit("static boolean[] invalid;")
emit("static int[] robotCosts;")
emit("""
  static final Direction[] ORDERED_DIRECTIONS = {
      Direction.SOUTHWEST,
      Direction.WEST,
      Direction.NORTHWEST,
      Direction.SOUTH,
      Direction.NORTH,
      Direction.SOUTHEAST,
      Direction.EAST,
      Direction.NORTHEAST,
  };
  """)
ORDERED_DIRECTIONS = [
      "Direction.SOUTHWEST",
      "Direction.WEST",
      "Direction.NORTHWEST",
      "Direction.SOUTH",
      "Direction.NORTH",
      "Direction.SOUTHEAST",
      "Direction.EAST",
      "Direction.NORTHEAST",
  ]

with block("static void pathStart() throws GameActionException"):
    # Reset the scores and best directions for each location
    for x in range(-4, 5):
        for y in range(-4, 5):
            if x*x + y*y <= 20:
                emit(f'{g(x, y)} = 100000 << 3;')
    emit("var at = rc.getLocation();")

    # Store the positions of nearby robots in a lookup table so we can avoid them
    emit("invalid = new boolean[121];")
    emit("robotCosts = new int[121];")
    emit("""
        for (var r : rc.senseNearbyRobots(9)) {
          var x = r.location.x - at.x;
          var y = r.location.y - at.y;
          invalid[(5 + x) * 11 + 5 + y] = true;
          if (r.getTeam() == rc.getTeam()) {
            for (int dx = -1; dx <= 1; dx++) {
              for (int dy = -1; dy <= 1; dy++) {
                if ((dx != 0 || dy != 0) && (x+dx)*(x+dx) + (y+dy)*(y+dy) <= 9) {
                  robotCosts[(5 + x + dx) * 11 + 5 + y + dy] += 1 << 3;
                }
              }
            }
          }
        }
    """)

    emit("var height = rc.getMapHeight();")
    emit("var width = rc.getMapWidth();")
    for x in range(-4, 0):
        with block(f"if (at.x - {-x} < 0)"):
            for y in range(-4, 5):
                emit(f"invalid[{(5 + x) * 11 + 5 + y}] = true;")
        with block(f"if (at.x + {-x} >= width)"):
            for y in range(-4, 5):
                emit(f"invalid[{(5 - x) * 11 + 5 + y}] = true;")
        with block(f"if (at.y - {-x} < 0)"):
            for y in range(-4, 5):
                emit(f"invalid[{(5 + y) * 11 + 5 + x}] = true;")
        with block(f"if (at.y + {-x} >= height)"):
            for y in range(-4, 5):
                emit(f"invalid[{(5 + y) * 11 + 5 - x}] = true;")

    # Initialize locations adjacent to the starting point
    q = [0, 1, 2, 3, 0, 4, 5, 6, 7]
    for x in range(-1, 2):
        for y in range(-1, 2):
            if x*x + y*y != 0:
                didx = (x+1)*3 + y + 1
                didx = q[didx]
                dir = ORDERED_DIRECTIONS[didx]
                baseIdx = (5 + x) * 11 + 5 + y
                with block(f"if (rc.canMove({dir}))"):
                    emit(f"var loc = at.translate({x}, {y});")
                    cost = f'({paintCost("loc")} + {TURN_VALUE}) << 3 + robotCosts[{baseIdx}]'
                    emit(f"{g(x, y)} = ({cost}) | {didx};")

def doLoc(x, y, r2):
    if x == 0 and y == 0:
        return
    baseIdx = (5 + x) * 11 + 5 + y
    with block(f"if (!invalid[{baseIdx}])"):
        emit(f"var tile = rc.senseMapInfo(at.translate({x}, {y}));")
        with block(f"if (tile.isPassable())"):
            cost = f"switch (tile.getPaint()) {{ case ENEMY_PRIMARY, ENEMY_SECONDARY -> {(2 * PAINT_VALUE + TURN_VALUE) << 3}; case EMPTY -> {(PAINT_VALUE + TURN_VALUE) << 3}; case ALLY_PRIMARY, ALLY_SECONDARY -> {TURN_VALUE << 3}; }}"

            if x*x+y*y <= 9:
                emit(f"var cost = {cost} + robotCosts[{baseIdx}];")
            else:
                emit(f"var cost = {cost};")
            maxD = max(x*x, y*y)

            c = f"{g(x, y)} - cost"
            for dx in range(-1, 2):
                for dy in range(-1, 2):
                    x2 = x + dx
                    y2 = y + dy
                    # Only consider locations that are in previous layers (their max coordinate is less than this one)
                    if x2*x2+y2*y2 <= r2 and (dx != 0 or dy != 0) and (x2*x2 <= maxD and y2*y2 <= maxD):
                        #i = (5 + x2) * 11 + 5 + y2
                        c = f"Math.min({c}, {g(x2, y2)})"
            emit(f"{g(x, y)} = {c} + cost;")

# Propagate scores and directions outward
# This could be run multiple times to be more accurate
# (this performs one iteration of Bellman-Ford, but in such an order that one iteration is pretty good)
with block("static void pathIter() throws GameActionException"):
    emit("var at = rc.getLocation();")
    # Visit each location in order of distance from the origin (mostly)
    sides = [1, 3, 5, 7, 5]
    offsets = [0, 0, 0, 0, 2]
    for layer in range(0, len(sides)):
        for i in range(0, sides[layer]):
            o = offsets[layer]
            if layer == len(sides) - 1 or (i != 0 and i+1 != sides[layer]):
                doLoc(-layer, -layer + o + i, 20)
                doLoc(layer, -layer + o + i, 20)
            doLoc(-layer + o + i, layer, 20)
            doLoc(-layer + o + i, -layer, 20)


def fLoc(x, y):
    with block(''):
        i = (5 + x) * 11 + 5 + y
        emit(f"var score = ({g(x, y)} >> 3) + 50 * (Math.abs({x} - tx) + Math.abs({y} - ty));")
        with block('if (score < bestScore)'):
            emit('bestScore = score;')
            emit(f'bestDir = ORDERED_DIRECTIONS[{g(x, y)} & 0b111];')
            emit(f'bestPos = at.translate({x}, {y});')

with block("static Direction pathFinish(MapLocation from, MapLocation to) throws GameActionException"):
    emit("var at = from;")
    emit("var tx = to.x - from.x;")
    emit("var ty = to.y - from.y;")
    # TODO implement the massive switch this [inline] would need
#     emit(f"""if (tx*tx + ty*ty <= r2) {{
#             // Go directly to target
#             var s = cache[inline (5 + tx) * 11 + 5 + ty];
#             if s != 100000 << 3 {{
#                 return ORDERED_DIRECTIONS[inline s & 0b111];
#             }}
#         }}
#         """)

    # We could pick the closest point to the target
    # but we basically never want to pick a specific point *unless* it's the target
    # e.g. if we have an obstacle right in the way we want to go around, but that way we would run into it
    # so we use score + max coordinate distance to target
    # These are the positions on the outside of the vision radius to search
    emit('var bestScore = 100000;')
    emit('Direction bestDir = null;')
    emit('MapLocation bestPos = null;')
    for i in range(0, 5):
        fLoc(-4, -4 + 2 + i)
        fLoc(4, -4 + 2 + i)
        fLoc(-4 + 2 + i, 4)
        fLoc(-4 + 2 + i, -4)
    fLoc(3, 3)
    fLoc(3, -3)
    fLoc(-3, 3)
    fLoc(-3, -3)
    with block('if (bestPos != null)'):
        emit('rc.setIndicatorLine(from, bestPos, 255, 255, 0);')
        emit('rc.setIndicatorLine(from, from.add(bestDir), 0, 255, 255);')
    emit('return bestDir;')

emit('''
// Greedy pathfinding - go in the lowest rubble direction that's strictly closer to the target
static Direction pathfindGreedy(MapLocation target) throws GameActionException {
    var loc = rc.getLocation();

    var dir = loc.directionTo(target);

    // Try going around obstacles, first left, then right
    MapLocation[] options = { loc.add(dir), loc.add(dir.rotateLeft()), loc.add(dir.rotateRight()),
        loc.add(dir.rotateLeft().rotateLeft()), loc.add(dir.rotateRight().rotateRight()) };
    MapLocation best = null;
    var best_pass = 100;
    var prev_dist2 = loc.distanceSquaredTo(target);
    for (var i : options) {
        var found = false;
        for (var l : lastLocs) {
            if (i.equals(l)) {
                found = true;
                break;
            }
        }
        if (found) {
            continue;
        }
        if (i.equals(target) && rc.canMove(loc.directionTo(i))) {
            best = i;
            best_pass = -1;
        } else if (i.isWithinDistanceSquared(target, prev_dist2 - 1) && rc.canMove(loc.directionTo(i))) {
            var pass = switch (rc.senseMapInfo(i).getPaint()) {
                case ENEMY_PRIMARY, ENEMY_SECONDARY -> 2;
                case EMPTY -> 1;
                case ALLY_PRIMARY, ALLY_SECONDARY -> 0;
            };
            // Account for adjacent-ally paint penalties
            pass += rc.senseNearbyRobots(i, 2, rc.getTeam()).length;
            if (pass < best_pass) {
                best = i;
                best_pass = pass;
            }
        }
    }
    if (best != null) {
        return loc.directionTo(best);
    } else {
        return null;
    }
}


static final int N_SAVED_LOCS = 3;
static MapLocation[] lastLocs = new MapLocation[N_SAVED_LOCS];
static int lastLocIdx = 0;
static void addLoc(MapLocation loc) {
    lastLocs[lastLocIdx] = loc;
    lastLocIdx = (lastLocIdx + 1) % N_SAVED_LOCS;
}
static boolean hasLoc(MapLocation loc) {
    for (var i : lastLocs) {
        if (loc.equals(i)) {
            return true;
        }
    }
    return false;
}
static MapLocation savedTarget;

static int greedyTurns = 0;
static boolean targetMove(MapLocation target) throws GameActionException {
    var loc = rc.getLocation();

    if (target == null || target.equals(loc)) {
        return false;
    }

    if (!target.equals(savedTarget) || target.isWithinDistanceSquared(loc, 2)) {
        java.util.Arrays.fill(lastLocs, null);
        savedTarget = target;
    }

    rc.setIndicatorLine(loc, target, 0, 0, 255);

    //if (target.isWithinDistanceSquared(loc, 8) && rc.canMove(loc.directionTo(target))) {
    //    rc.move(pathfindGreedy();
    //    return true;
    //}

    Direction dir = null;
    if (greedyTurns <= 0 && Clock.getBytecodesLeft() >= 3000 && !(target.isWithinDistanceSquared(loc, 8) && rc.canMove(loc.directionTo(target)))) {
        var btc = Clock.getBytecodeNum();
        var tStart = rc.getRoundNum();
        pathStart();
        var startBtc = Clock.getBytecodeNum();
        if (rc.getRoundNum() != tStart) { startBtc += GameConstants.ROBOT_BYTECODE_LIMIT; }
        pathIter();
        var iterBtc = Clock.getBytecodeNum();
        if (rc.getRoundNum() != tStart) { iterBtc += GameConstants.ROBOT_BYTECODE_LIMIT; }
        dir = pathFinish(loc, target);
        var finishBtc = Clock.getBytecodeNum();
        if (rc.getRoundNum() != tStart) { finishBtc += GameConstants.ROBOT_BYTECODE_LIMIT; }

        var greedy = false;
        var oldDir = dir;
        if (dir != null && hasLoc(loc.add(dir))) {
            greedyTurns = 5;
            dir = pathfindGreedy(target);
            greedy = true;
        }

        rc.setIndicatorString("BFS BTC: " + (finishBtc - btc)
            + ", start: " + (startBtc - btc)
            + ", iter: " + (iterBtc - startBtc)
            + ", end: " + (finishBtc - iterBtc)
            + ". g? " + greedy);
    } else {
        rc.setIndicatorString("GREEDY PATHFINDING ENABLED");
        greedyTurns -= 1;
        dir = pathfindGreedy(target);
        if (greedyTurns == 0) {
            // Reset lastLocs when switching out of greedy mode
            java.util.Arrays.fill(lastLocs, null);
        }
    }

    if (dir != null) {
        if (rc.canMove(dir)) {
            if (!target.isWithinDistanceSquared(loc, 2)) {
                addLoc(loc);
            }
            rc.move(dir);
            return true;
        } else {
            System.out.println("Went over and moved illegally; would throw exception! Dir = " + dir);
        }
    } else if (rc.isMovementReady()) {
        exploreTarget = null;
    }
    return false;
}
''')

print("}")