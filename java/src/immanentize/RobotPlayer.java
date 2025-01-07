package immanentize;

import battlecode.common.*;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Supplier;

@SuppressWarnings({"Convert2MethodRef", "CallToPrintStackTrace"})
public class RobotPlayer {
  static RobotController rc;

  /**
   * Array containing all the possible movement directions.
   */
  static final Direction[] MOVE_DIRECTIONS = {
      Direction.NORTH,
      Direction.NORTHEAST,
      Direction.EAST,
      Direction.SOUTHEAST,
      Direction.SOUTH,
      Direction.SOUTHWEST,
      Direction.WEST,
      Direction.NORTHWEST,
  };

  static final UnitType[] INITIAL_SPAWN_LIST = {
      UnitType.SOLDIER,
      UnitType.SOLDIER,
      UnitType.MOPPER,
  };
  static final UnitType[] SPAWN_LIST = {
      UnitType.MOPPER,
      UnitType.SOLDIER,
      UnitType.MOPPER,
      UnitType.SOLDIER,
  };
  static int spawnCounter;

  static final double FREE_PAINT_TARGET = 0.2;

  static RobotInfo[] nearbyAllies, nearbyEnemies;

  static int rng_acc = 0;

  static int rng() {
    // xorshift or whatever
    if (rng_acc == 0) {
      rng_acc = rc.getID() ^ (rc.getID() << 14);
    }

    rng_acc ^= rng_acc >> 7;
    rng_acc ^= rng_acc << 9;
    rng_acc ^= rng_acc >> 13;
    return rng_acc;
  }

  static MapLocation cachedTarget = null;
  static ArrayDeque<MapLocation> cachedLocs = new ArrayDeque<>();

  static boolean isEnemy(PaintType paint) {
    return paint == PaintType.ENEMY_PRIMARY || paint == PaintType.ENEMY_SECONDARY;
  }

  static void navigate(MapLocation target) throws GameActionException {
    if (!rc.isMovementReady()) {
      return;
    }
    if (cachedTarget == null || target.distanceSquaredTo(cachedTarget) > 2 || target.isWithinDistanceSquared(rc.getLocation(), 2)) {
      cachedLocs.clear();
    }
    cachedTarget = target;

    var best = Direction.CENTER;
    var bestDist = 100000000;
    var bestPaint = PaintType.ENEMY_PRIMARY;
    var pDist = rc.getLocation().distanceSquaredTo(target);
    for (var dir : MOVE_DIRECTIONS) {
      if (rc.canMove(dir)) {
        var loc = rc.getLocation().add(dir);
        if (cachedLocs.contains(loc)) {
          continue;
        }
        var dist = loc.distanceSquaredTo(target);
        // TODO do we want this?
        if (dist > pDist) {
          continue;
        }
        var paint = rc.senseMapInfo(loc).getPaint();
        if (bestPaint.isAlly() && !paint.isAlly()) {
          continue;
        } else if (bestPaint.isAlly() == paint.isAlly()) {
          if (isEnemy(paint) && !isEnemy(bestPaint)) {
            continue;
          } else if (isEnemy(paint) == isEnemy(bestPaint)) {
            if (dist > bestDist) {
              continue;
            }
          }
        }

        best = dir;
        bestDist = dist;
        bestPaint = paint;
      }
    }
    if (best != Direction.CENTER) {
      rc.move(best);
      if (cachedLocs.size() >= 16) {
        cachedLocs.removeFirst();
      }
      cachedLocs.addLast(rc.getLocation());
    } else if (!cachedLocs.isEmpty()) {
      rc.setIndicatorString("clearing nav cache");
      cachedLocs.clear();
      navigate(target);
    } else {
      rc.setIndicatorString("failed to navigate");
    }
  }

  static void maybeReplenishPaint() throws GameActionException {
    if (rc.getPaint() - 50 < (rc.getType().paintCapacity - 50) * FREE_PAINT_TARGET) {
      if (rc.isActionReady()) {
        Arrays.stream(rc.senseNearbyRobots(2, rc.getTeam()))
            .filter(x -> x.getType().isTowerType() && x.paintAmount > 50)
            .findFirst()
            .ifPresent(x -> {
              // really annoying that we have to do this (since λ doesn't support throws)
              try {
                rc.transferPaint(x.location, -Math.min(rc.getType().paintCapacity - rc.getPaint(), x.paintAmount));
              } catch (GameActionException e) {
                throw new RuntimeException(e);
              }
            });
      }
    }
  }

  static MapLocation closestPaintTower;
  static MapLocation closestTower;
  static MapLocation ruinTarget;

  record TargetType(Supplier<Optional<MapLocation>> fun) {
  }

  static MapLocation exploreTarget = null;
  static TargetType[] targets = {
      // Paint resupply - URGENT
      new TargetType(() -> Optional.ofNullable(closestPaintTower).filter(_x -> rc.getPaint() < 50)),
      // Mopper: find nearby soldier
      new TargetType(() -> Optional.of(0)
          .filter(_x -> rc.getType() == UnitType.MOPPER && rc.getPaint() > 50)
          .flatMap(_x -> Arrays.stream(nearbyAllies)
              .filter(x -> x.getType() == UnitType.SOLDIER && x.paintAmount < UnitType.SOLDIER.paintCapacity)
              .min(Comparator.comparingInt(x -> x.paintAmount))
              .map(x -> x.location))),
      // Mopper: find enemy paint
      new TargetType(() -> Optional.of(0)
          .filter(_x -> rc.getType() == UnitType.MOPPER)
          .flatMap(_x -> Arrays.stream(rc.senseNearbyMapInfos())
              .filter(x -> isEnemy(x.getPaint()))
              .min(Comparator.comparingInt(x -> x.getMapLocation().distanceSquaredTo(rc.getLocation())))
              .map(x -> x.getMapLocation()))),
      // Paint resupply
      new TargetType(() -> Optional.ofNullable(closestPaintTower).filter(_x -> rc.getPaint() - 50 < (rc.getType().paintCapacity - 50) * FREE_PAINT_TARGET)),
      // Soldier/mopper: target ruins
      new TargetType(() -> Optional.ofNullable(ruinTarget)),
      // Exploration
      new TargetType(() ->
          Optional.ofNullable(exploreTarget)
              .filter(x -> x.distanceSquaredTo(rc.getLocation()) > 2)
              .or(() -> {
                exploreTarget = new MapLocation(rng() % rc.getMapWidth(), rng() % rc.getMapHeight());
                return Optional.of(exploreTarget);
              })
      ),
  };

  static MapLocation target = null;
  static int targetIdx;

  static void doMove() throws GameActionException {
    if (target == null || target.distanceSquaredTo(rc.getLocation()) < 2) {
      targetIdx = 10000;
      target = null;
    }
    if (targetIdx < targets.length && targets[targetIdx].fun.get().isEmpty()) {
      targetIdx = 10000;
      target = null;
    }
    for (int i = 0; i < targets.length && i <= targetIdx; i++) {
      // messy... :(
      // obv what we want is: `for (t, i) in targets.iter().enumerate()`
      int finalI = i;
      targets[i].fun.get().ifPresent(x -> {
        targetIdx = finalI;
        target = x;
      });
    }

    if (nearbyEnemies.length > 0) {
      microMove();
    } else {
      if (targetIdx < 10000) {
        rc.setIndicatorString("target " + targetIdx);
        navigate(target);
      }
    }

    if (targetIdx < 10000) {
      rc.setIndicatorLine(rc.getLocation(), target, 0, 255, 0);
    }
  }

  static boolean canPaint(MapInfo tile) throws GameActionException {
    return (tile.getPaint() == PaintType.EMPTY || (tile.getMark().isAlly() && tile.getPaint().isAlly() && tile.getPaint() != tile.getMark()))
        && tile.isPassable() && !tile.hasRuin();
  }

  static void doPaint_() throws GameActionException {
    // ~2000bt faster
    var start = Clock.getBytecodeNum();
    if (rc.getPaint() < 55 || !rc.isActionReady()) return;
    MapInfo best = null;
    var bestDistR = 10000000;
    var bestDistT = 10000000;
    for (var tile : rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared)) {
      if (!canPaint(tile)) continue;
      if (best == null) best = tile;
      var distR = tile.getMapLocation().distanceSquaredTo(rc.getLocation());
      var distT = target == null ? 10000000 : tile.getMapLocation().distanceSquaredTo(target);
      if (distR < bestDistR || (distR == bestDistR && distT < bestDistT)) {
        best = tile;
        bestDistR = distR;
        bestDistT = distT;
      }
    }
    if (best != null) {
      boolean useSecondaryColor = best.getMark() == PaintType.ALLY_SECONDARY;
      rc.attack(best.getMapLocation(), useSecondaryColor);
    }
    var end = Clock.getBytecodeNum();
    rc.setIndicatorString("paint time (new): " + (end - start) + "bt");
  }

  static void doPaint() throws GameActionException {
    var start = Clock.getBytecodeNum();
    if (rc.getPaint() < 55 || !rc.isActionReady()) return;
    // smaller is better
    var loc = rc.getLocation();
    Comparator<MapInfo> comp = Comparator.comparingInt(x -> x.getMark().isAlly() ? 0 : 1);
    comp = comp.thenComparingInt(x -> x.getMapLocation().distanceSquaredTo(loc));
    if (target != null) {
      comp = comp.thenComparingInt(x -> x.getMapLocation().distanceSquaredTo(target));
    }
    Arrays.stream(rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared))
        .filter(x -> {
          try {
            return canPaint(x);
          } catch (GameActionException e) {
            throw new RuntimeException(e);
          }
        })
        .min(comp)
        .ifPresent(x -> {
          try {
            boolean useSecondaryColor = x.getMark() == PaintType.ALLY_SECONDARY;
            rc.attack(x.getMapLocation(), useSecondaryColor);
          } catch (GameActionException e) {
            throw new RuntimeException(e);
          }
        });
    var end = Clock.getBytecodeNum();
    rc.setIndicatorString("paint time (old): " + (end - start) + "bt");
  }

  record AttackTarget(MapLocation target, double score) {
  }

  /// Single target only (for towers and soldiers)
  static Optional<AttackTarget> pickAttackTarget(MapLocation loc) throws GameActionException {
    if (!rc.isActionReady()) return Optional.empty();
    if (rc.getType().isRobotType() && rc.getPaint() <= 50) return Optional.empty();
    final int KILL_VALUE = 200;

    Optional<AttackTarget> best = Optional.empty();

    var targetTowers = !rc.getType().isTowerType();
    var attackDist = rc.getType().actionRadiusSquared;
    // TODO tower damage modifiers from defense towers
    var damage = rc.getType().attackStrength;
    for (var unit : nearbyEnemies) {
      if (unit.getType().isTowerType() != targetTowers) continue;
      if (!unit.location.isWithinDistanceSquared(loc, attackDist)) continue;
      var score = Math.min(damage, unit.health);
      if (damage >= unit.health) score += KILL_VALUE;
      if (best.isEmpty() || score > best.get().score) {
        best = Optional.of(new AttackTarget(unit.location, score));
      }
    }

    return best;
  }

  static void doAttack() throws GameActionException {
    pickAttackTarget(rc.getLocation()).ifPresent(x -> {
      try {
        if (!rc.canAttack(x.target)) System.out.println("could not attack" + x.target);
        else rc.attack(x.target);
      } catch (GameActionException e) {
        throw new RuntimeException(e);
      }
    });
  }

  static void microMove() throws GameActionException {
    class MicroLoc {
      final MapLocation loc;
      double incomingDamage = 0;
      int adjacentAllies = 0;

      MicroLoc(MapLocation loc) {
        this.loc = loc;
      }
    }

    var locs = Arrays.stream(Direction.allDirections())
        .filter(x -> x == Direction.CENTER || rc.canMove(x))
        .map(x -> new MicroLoc(rc.getLocation().add(x)))
        .toArray(n -> new MicroLoc[n]);
    var isTower = rc.getType().isTowerType();
    for (var unit : nearbyEnemies) {
      if (unit.type.isTowerType() == isTower) continue;
      if (unit.type.attackStrength <= 0) continue;
      for (var loc : locs) {
        if (unit.location.isWithinDistanceSquared(loc.loc, unit.type.actionRadiusSquared)) {
          loc.incomingDamage += unit.type.attackStrength;
        }
      }
    }
    // TODO is this the better way to do it or should we be senseNearbyRobots()-ing for each location?
    for (var unit : nearbyAllies) {
      for (var loc : locs) {
        if (unit.location.isWithinDistanceSquared(loc.loc, 2)) {
          loc.adjacentAllies += 1;
        }
      }
    }
    var pTargetDist = target == null ? 0 : rc.getLocation().distanceSquaredTo(target);
    var best = Arrays.stream(locs)
        .max(Comparator.comparingDouble(loc -> {
          try {
            var tile = rc.senseMapInfo(loc.loc);
            var score = 0.0;
            score -= loc.incomingDamage;
            score += pickAttackTarget(tile.getMapLocation()).map(x -> x.score).orElse(0.0);
            // Paint penalties
            // TODO paint/hp tradeoff?
            score += switch (tile.getPaint()) {
              case EMPTY -> -1;
              case ALLY_PRIMARY, ALLY_SECONDARY -> 0;
              case ENEMY_PRIMARY, ENEMY_SECONDARY -> -2 * (1 + loc.adjacentAllies);
            };
            if (target != null) {
              // Low coefficient, this is meant to be a tiebreaker
              score -= 0.02 * (tile.getMapLocation().distanceSquaredTo(target) - pTargetDist);
            }
            return score;
          } catch (GameActionException e) {
            e.printStackTrace();
            System.out.println("exception in microMove()");
            return 0;
          }
        }));
    best.ifPresent(x -> rc.setIndicatorString("micro pos: " + x.loc));
    best.filter(x -> !x.loc.equals(rc.getLocation())).ifPresent(l -> {
      try {
        rc.move(rc.getLocation().directionTo(l.loc));
      } catch (GameActionException e) {
        throw new RuntimeException(e);
      }
    });
  }

  public static void run(RobotController rc) throws GameActionException {
    RobotPlayer.rc = rc;
    spawnCounter = rng() % SPAWN_LIST.length;

    while (true) {
      try {
        nearbyAllies = rc.senseNearbyRobots(GameConstants.VISION_RADIUS_SQUARED, rc.getTeam());
        nearbyEnemies = rc.senseNearbyRobots(GameConstants.VISION_RADIUS_SQUARED, rc.getTeam().opponent());

        if (!rc.getType().isTowerType()) {
          for (var i : nearbyAllies) {
            if (i.getType().paintPerTurn > 0) {
              if (closestPaintTower == null || i.location.distanceSquaredTo(rc.getLocation()) < closestPaintTower.distanceSquaredTo(rc.getLocation())) {
                closestPaintTower = i.location;
              }
            }
            if (i.getType().isTowerType()) {
              if (closestTower == null || i.location.distanceSquaredTo(rc.getLocation()) < closestTower.distanceSquaredTo(rc.getLocation())) {
                closestTower = i.location;
              }
            }

          }
        }

        switch (rc.getType()) {
          case SOLDIER -> {
            // Attack if possible
            doAttack();

            if (ruinTarget != null && rc.canSenseRobotAtLocation(ruinTarget)) ruinTarget = null;
            if (ruinTarget != null && rc.canSenseLocation(ruinTarget)) {
              for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinTarget, 8)) {
                if (isEnemy(patternTile.getPaint())) {
                  ruinTarget = null;
                }
              }
            }

            // Try to build ruins
            if (rc.isActionReady()) {
              a:
              for (var ruin : rc.senseNearbyRuins(-1)) {
                if (rc.senseRobotAtLocation(ruin) != null) continue;
                for (MapInfo patternTile : rc.senseNearbyMapInfos(ruin, 8)) {
                  if (isEnemy(patternTile.getPaint())) {
                    continue a;
                  }
                }
                // Okay we pick this ruin
                ruinTarget = ruin;

                var dir = rc.getLocation().directionTo(ruin);
                // Mark the pattern we need to draw to build a tower here if we haven't already.
                var shouldBeMarked = ruin.subtract(dir);
                var type = (rng() % 4) == 1 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
                if (rc.senseMapInfo(shouldBeMarked).getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(type, ruin)) {
                  rc.markTowerPattern(type, ruin);
                }
                // Complete the ruin if we can.
                if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin)) {
                  rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruin);
                  ruinTarget = null;
                  rc.setTimelineMarker("Tower built", 0, 255, 0);
                } else if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, ruin)) {
                  rc.completeTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, ruin);
                  ruinTarget = null;
                  rc.setTimelineMarker("Tower built", 0, 255, 0);
                }

              }
            }

            // Try to paint a square in range
            if (rc.getRoundNum() % 2 == 0) doPaint();
            else doPaint_();

            maybeReplenishPaint();

            // Move
            doMove();
          }
          case SPLASHER -> {

          }
          case MOPPER -> {
            // Target ruins the enemy is trying to build
            if (ruinTarget != null && rc.canSenseLocation(ruinTarget)) ruinTarget = null;
            a:
            for (var ruin : rc.senseNearbyRuins(-1)) {
              if (rc.senseRobotAtLocation(ruin) != null) continue;
              for (MapInfo patternTile : rc.senseNearbyMapInfos(ruin, 8)) {
                if (isEnemy(patternTile.getPaint())) {
                  ruinTarget = ruin;
                  break a;
                }
              }
            }

            // Remove enemy paint
            MapLocation toMop = null;
            var isInRuin = false;
            if (rc.isActionReady()) {
              for (var tile : rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared)) {
                if (isEnemy(tile.getPaint()) && rc.canAttack(tile.getMapLocation())) {
                  var ruin = ruinTarget != null && ruinTarget.isWithinDistanceSquared(tile.getMapLocation(), 8);
                  if (!isInRuin || ruin) {
                    toMop = tile.getMapLocation();
                    isInRuin = ruin;
                  }
                }
              }
            }
            rc.attack(toMop);

            // Resupply nearby soldiers
            if (rc.getPaint() > 50 && rc.isActionReady()) {
              for (var unit : rc.senseNearbyRobots(rc.getType().actionRadiusSquared, rc.getTeam())) {
                if (unit.getType() == UnitType.SOLDIER && unit.paintAmount < unit.getType().paintCapacity) {
                  var transfer = Math.min(rc.getPaint() - 50, unit.getType().paintCapacity - unit.paintAmount);
                  rc.transferPaint(unit.location, transfer);
                  break;
                }
              }
            }

            maybeReplenishPaint();

            doMove();
          }
          default -> {
            // Towers

            // Attack if possible
            doAttack();

            // Try to spawn a unit
            var toSpawn = rc.getRoundNum() < 4 ? UnitType.SOLDIER : SPAWN_LIST[spawnCounter];
            for (var dir : MOVE_DIRECTIONS) {
              if (rc.canBuildRobot(toSpawn, rc.getLocation().add(dir))) {
                rc.buildRobot(toSpawn, rc.getLocation().add(dir));
                spawnCounter = (spawnCounter + 1) % SPAWN_LIST.length;
              }
            }

          }
        }
      } catch (GameActionException e) {
        e.printStackTrace();
        rc.setIndicatorString("exception");
      }

      Clock.yield();
    }
  }
}
