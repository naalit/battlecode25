package immanentize;

import battlecode.common.*;

import java.util.*;
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

  static ArrayList<UnitType> SPAWN_LIST = new ArrayList<>(Arrays.asList(
      UnitType.MOPPER,
      UnitType.SOLDIER,
      UnitType.SPLASHER,
      UnitType.SOLDIER,
      UnitType.MOPPER,
      UnitType.SPLASHER
  ));
  static int spawnCounter;

  static final double FREE_PAINT_TARGET = 0.2;
  static final double KILL_VALUE = 200;
  static final double PAINT_VALUE = 1;
  static final int SOLDIER_PAINT_MIN = 10;
  static final int MOPPER_PAINT_MIN = 50;
  static final int SPLASHER_PAINT_MIN = 50;

  static int minPaint() {
    return switch (rc.getType()) {
      case SPLASHER -> SPLASHER_PAINT_MIN;
      case MOPPER -> MOPPER_PAINT_MIN;
      case SOLDIER -> SOLDIER_PAINT_MIN;
      default -> 0;
    };
  }

  static RobotInfo[] nearbyAllies, nearbyEnemies;

  static int rng_acc = 0;

  static int rng() {
    // xorshift or whatever
    if (rng_acc == 0) {
      var id = rc.getID() ^ 0b1000_1101_1010_1101_0111_0101_1010_0101;
      rng_acc = id ^ (id << 14);
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
      exploreTarget = null;
    }
  }

  static void maybeReplenishPaint() throws GameActionException {
    if (rc.getPaint() - minPaint() < (rc.getType().paintCapacity - minPaint()) * FREE_PAINT_TARGET) {
      if (rc.isActionReady()) {
        Arrays.stream(rc.senseNearbyRobots(2, rc.getTeam()))
            // TODO constant for this
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

  record MapColor(boolean secondary) {
  }

  static MapColor[][] mapColors;
  static boolean[][] resourcePattern;

  static MapLocation closestPaintTower;
  static MapLocation closestTower;
  static MapLocation ruinTarget;
  static MapLocation closestResource;
  static int closestResourceSquaresLeft;

  record TargetType(Supplier<Optional<MapLocation>> fun) {
  }

  static MapLocation exploreTarget = null;
  static TargetType[] targets = {
      // Paint resupply - URGENT
      new TargetType(() -> Optional.ofNullable(closestPaintTower).filter(_x -> rc.getPaint() < minPaint() + rc.getType().attackCost)),
      // Mopper: find nearby soldier
      new TargetType(() -> Optional.of(0)
          .filter(_x -> rc.getType() == UnitType.MOPPER && rc.getPaint() >= minPaint() + 10)
          .flatMap(_x -> Arrays.stream(nearbyAllies)
              .filter(x -> x.getType().isRobotType() && x.getType() != UnitType.MOPPER && x.paintAmount < x.getType().paintCapacity)
              .min(Comparator.comparingInt(x -> x.paintAmount))
              .map(x -> x.location))),
      // Soldier: target unpainted tower squares
      new TargetType(() -> Optional.ofNullable(ruinTarget).filter(x -> rc.getChips() > UnitType.LEVEL_ONE_PAINT_TOWER.moneyCost * 0.8 && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost).flatMap(ruin -> {
        Optional<MapLocation> target = Optional.empty();
        try {
          a:
          for (int x = -2; x < 3; x++) {
            for (int y = -2; y < 3; y++) {
              var loc = ruin.translate(x, y);
              if ((x == 0 && y == 0) || !rc.canSenseLocation(loc)) continue;
              if (!rc.senseMapInfo(loc).getPaint().isAlly() || (rc.senseMapInfo(loc).getPaint() == PaintType.ALLY_SECONDARY) != secondary(loc)) {
                target = Optional.of(loc);
                break a;
              }
            }
          }
        } catch (GameActionException e) {
          throw new RuntimeException(e);
        }
        return target;
      })),
      // Soldier: target mark location
      new TargetType(() -> Optional.ofNullable(ruinTarget).map(x -> x.translate(0, 1)).filter(x -> rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost && mapColors[x.x][x.y] == null)),
      // Soldier: target nearly-full resource pattern
      new TargetType(() -> Optional.ofNullable(closestResource).filter(x -> rc.getType() == UnitType.SOLDIER && (rc.getPaint() >= minPaint() + rc.getType().attackCost || closestResourceSquaresLeft == 0) && closestResourceSquaresLeft < 12)),
      // Soldier: target ruins
      new TargetType(() -> Optional.ofNullable(ruinTarget).filter(x -> rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost && rc.getChips() > UnitType.LEVEL_ONE_PAINT_TOWER.moneyCost * 0.8 && !x.isWithinDistanceSquared(rc.getLocation(), 2))),
      // Mopper/splasher: find enemy paint
      new TargetType(() -> Optional.of(0)
          .filter(_x -> rc.getType() == UnitType.MOPPER)// || (rc.getType() == UnitType.SPLASHER && rc.getPaint() >= 100))
          .flatMap(_x -> Arrays.stream(rc.senseNearbyMapInfos())
              .filter(x -> isEnemy(x.getPaint()))
              .min(Comparator.comparingInt(x -> x.getMapLocation().distanceSquaredTo(rc.getLocation())))
              .map(x -> x.getMapLocation()))),
      // Paint resupply - if nothing else important to do (for soldiers)
      new TargetType(() -> Optional.ofNullable(closestPaintTower).filter(_x -> rc.getPaint() < 50 + rc.getType().attackCost)),
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
    if (!rc.isMovementReady()) return;

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
        //rc.setIndicatorString("target " + targetIdx);
        navigate(target);
      }
    }

    if (targetIdx < 10000) {
      rc.setIndicatorLine(rc.getLocation(), target, 0, 255, 0);
    }
  }

  static boolean secondary(MapLocation map) {
    var color = mapColors[map.x][map.y];
    if (rc.getNumberTowers() >= GameConstants.MAX_NUMBER_OF_TOWERS) {
      color = null;
    }
    return color != null ? color.secondary : resourcePattern[map.y % 4][map.x % 4];
  }

  static boolean canPaint(MapInfo tile) throws GameActionException {
    return (tile.getPaint() == PaintType.EMPTY ||
        (tile.getPaint().isAlly() && (tile.getPaint() == PaintType.ALLY_SECONDARY) != secondary(tile.getMapLocation())))
        && tile.isPassable() && !tile.hasRuin();
  }

  static void doPaint() throws GameActionException {
    if (rc.getPaint() < minPaint() + rc.getType().attackCost || !rc.isActionReady()) return;
    // smaller is better
    var loc = rc.getLocation();
    Comparator<MapInfo> comp = Comparator.comparingInt(x -> (mapColors[x.getMapLocation().x][x.getMapLocation().y] != null) ? 0 : 1);
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
            rc.attack(x.getMapLocation(), secondary(x.getMapLocation()));
          } catch (GameActionException e) {
            throw new RuntimeException(e);
          }
        });
  }

  static final class AttackTarget {
    public final MapLocation target;
    public double score;

    AttackTarget(MapLocation target, double score) {
      this.target = target;
      this.score = score;
    }
  }

  static Optional<AttackTarget> pickSplasherAttackTarget(MapLocation loc) throws GameActionException {
    if (!rc.isActionReady()) return Optional.empty();
    if (rc.getPaint() < 100) return Optional.empty();

    var locs = Arrays.stream(rc.senseNearbyMapInfos(loc, UnitType.SPLASHER.actionRadiusSquared))
        .filter(l -> rc.canAttack(l.getMapLocation()))
        .map(l -> new AttackTarget(l.getMapLocation(), 0))
        .toArray(n -> new AttackTarget[n]);

    var aoeRad2 = 4;
    var centerAoeRad2 = 2;
    var damage = rc.getType().aoeAttackStrength;
    for (var unit : nearbyEnemies) {
      if (unit.getType().isRobotType()) continue;
      for (var l : locs) {
        if (!unit.location.isWithinDistanceSquared(loc, aoeRad2)) continue;
        l.score += Math.min(damage, unit.health);
        if (damage >= unit.health) l.score += KILL_VALUE;
      }
    }
    var start = Clock.getBytecodeNum();
    var st = rc.getRoundNum();
    // TODO adjust this constant
    for (var tile : rc.senseNearbyMapInfos(loc, 9)) {
      if (!tile.isPassable()) continue;
      var p = tile.getPaint();
      if (p.isAlly()) continue;
      for (var l : locs) {
        if (p == PaintType.EMPTY && tile.getMapLocation().isWithinDistanceSquared(l.target, aoeRad2)) {
          l.score += 1;
        } else if (p.isEnemy() && tile.getMapLocation().isWithinDistanceSquared(l.target, centerAoeRad2)) {
          l.score += 2;
        }
      }
    }
    var end = Clock.getBytecodeNum();
    rc.setIndicatorString("scan: " + start + "/" + st + " - " + end + "/" + rc.getRoundNum());
    return Arrays.stream(locs).max(Comparator.comparingDouble(x -> x.score));
  }

  /// Single target only (for towers and soldiers) (also moppers now, just single target attacks not sweeps)
  static Optional<AttackTarget> pickAttackTarget(MapLocation loc) throws GameActionException {
    if (!rc.isActionReady()) return Optional.empty();
    if (rc.getPaint() < minPaint() + rc.getType().attackCost) return Optional.empty();
    if (rc.getType() == UnitType.SPLASHER) return pickSplasherAttackTarget(loc);

    Optional<AttackTarget> best = Optional.empty();

    var isMopper = rc.getType() == UnitType.MOPPER;
    var targetTowers = !rc.getType().isTowerType() && !isMopper;
    var attackDist = rc.getType().actionRadiusSquared;
    // TODO tower damage modifiers from defense towers
    var damage = rc.getType().attackStrength;
    for (var unit : nearbyEnemies) {
      if (unit.getType().isTowerType() != targetTowers) continue;
      if (!unit.location.isWithinDistanceSquared(loc, attackDist)) continue;
      var score = (double) Math.min(damage, unit.health);
      if (damage >= unit.health) score += KILL_VALUE;
      if (isMopper) {
        var paint = Math.min(10, unit.paintAmount);
        // TODO verify that this is true (5 paint to our team regardless of amount of paint stolen) (a dev said they think this is how it works)
        score = (paint + 5) * PAINT_VALUE;
      }
      if (best.isEmpty() || score > best.get().score) {
        best = Optional.of(new AttackTarget(unit.location, score));
      }
    }

    return best;
  }

  static void doAttack() throws GameActionException {
    pickAttackTarget(rc.getLocation()).ifPresent(x -> {
      if (x.score <= 0) return;
      if (rc.getType() == UnitType.SPLASHER && x.score <= 4.0) return;
      try {
//        if (rc.getType() == UnitType.SPLASHER) {
//          rc.setIndicatorString("splasher score " + x.score);
//        }
        if (!rc.canAttack(x.target)) System.out.println("could not attack" + x.target);
        else rc.attack(x.target);

        if (rc.getType().isTowerType()) {
          rc.attack(null);
        }
      } catch (GameActionException e) {
        throw new RuntimeException(e);
      }
    });
  }

  ///  Precondition: rc.isMovementReady()
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
      var damage = (double) Math.min(unit.type.attackStrength, rc.getHealth());
      if (!isTower && unit.type == UnitType.MOPPER) {
        damage = (Math.min(rc.getPaint(), 10) + 5) * PAINT_VALUE;
      } else {
        if (unit.type.isTowerType() == isTower) continue;
        if (unit.type.attackStrength <= 0) continue;
      }
      for (var loc : locs) {
        if (unit.location.isWithinDistanceSquared(loc.loc, unit.type.actionRadiusSquared)) {
          loc.incomingDamage += damage;
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
            score += PAINT_VALUE * switch (tile.getPaint()) {
              case EMPTY -> -1;
              case ALLY_PRIMARY, ALLY_SECONDARY -> 0;
              case ENEMY_PRIMARY, ENEMY_SECONDARY -> -2 * (1 + loc.adjacentAllies);
            };
            if (target != null) {
              // Low coefficient, this is meant to be a tiebreaker
              score -= 0.02 * (tile.getMapLocation().distanceSquaredTo(target) - pTargetDist);
              // Okay but we do want to incentivize moving
              // It's kind of an open question when to stay out of danger on our own color and when to move across enemy territory to our target
              // I'm just going to put a general staying-in-place penalty here though? we'll see how this does
              if (loc.loc.equals(rc.getLocation())) {
                score -= 0.1;
              }
            }
            return score;
          } catch (GameActionException e) {
            e.printStackTrace();
            System.out.println("exception in microMove()");
            return 0;
          }
        }));
    best.filter(x -> {
      rc.setIndicatorString("micro pos: " + x.loc + " /" + targetIdx);
      if (x.loc.equals(rc.getLocation())) {
        // Retarget explore if we can't move forwards
        exploreTarget = null;
        return false;
      } else {
        return true;
      }
    }).ifPresent(l -> {
      try {
        rc.move(rc.getLocation().directionTo(l.loc));
      } catch (GameActionException e) {
        throw new RuntimeException(e);
      }
    });
  }

  static ArrayDeque<Integer> incomeFrames = new ArrayDeque<>();
  static int lastMoney = 0;

  static int encode(MapLocation loc) {
    return loc.x << 6 | loc.y;
  }

  static MapLocation decode(int msg) {
    return new MapLocation((msg >> 6) & 0b11_1111, msg & 0b11_1111);
  }

  static MapLocation sentRuin = null;
  static int[][] lastRPCheckTurn = new int[15][15];

  static void checkRuins() throws GameActionException {
    var income = rc.getChips() - lastMoney;
    lastMoney = rc.getChips();
    if (incomeFrames.size() > 12) {
      incomeFrames.removeFirst();
    }
    incomeFrames.addLast(income);

//    if (rc.getRoundNum() > 500) rc.disintegrate();

    // Also check resource patterns
    var rpCenter = new MapLocation(2 + 4 * (rc.getLocation().x / 4), 2 + 4 * (rc.getLocation().y / 4));
    if (rc.canSenseLocation(rpCenter)) {
      if (rpCenter.equals(closestResource)) {
        closestResource = null;
      }
      rc.setIndicatorDot(rpCenter, 0, 0, 255);
      if (rc.canCompleteResourcePattern(rpCenter)) {
        rc.completeResourcePattern(rpCenter);
        lastRPCheckTurn[rc.getLocation().x / 4][rc.getLocation().y / 4] = rc.getRoundNum();
//        if (rc.canMark(rpCenter)) {
//          rc.mark(rpCenter, true);
//        }
      } else if (rc.getType() == UnitType.SOLDIER && lastRPCheckTurn[rc.getLocation().x / 4][rc.getLocation().y / 4] < Math.max(rc.getRoundNum() - 25, 1)) {
        // we can complete this pattern! let's see if it's possible
        var pLeft = 25;
        for (var tile : rc.senseNearbyMapInfos(rpCenter, 8)) {
//          var t = mapColors[tile.getMapLocation().x][tile.getMapLocation().y];
//          if (t != null) {
//            rc.setIndicatorDot(tile.getMapLocation(), 255, 0, t.secondary ? 255 : 0);
//          }
          // this should be exactly the correct tiles (bc 0,3 has r^2 9)
          if (!tile.isPassable() || tile.getPaint().isEnemy() || secondary(tile.getMapLocation()) != resourcePattern[tile.getMapLocation().y % 4][tile.getMapLocation().x % 4]) {
//            rc.setIndicatorString("pleft: " +!tile.isPassable() +" "+ tile.getPaint().isEnemy() +" "+ (mapColors[tile.getMapLocation().x][tile.getMapLocation().y] != null));
            pLeft = -1;
            break;
          }
          if (tile.getPaint().isAlly() && tile.getPaint().isSecondary() == secondary(tile.getMapLocation())) {
            pLeft -= 1;
          }
        }
        if (pLeft != -1) {
          if (!rpCenter.equals(closestResource) || closestResourceSquaresLeft > pLeft || rpCenter.isWithinDistanceSquared(rc.getLocation(), 4)) {
            closestResourceSquaresLeft = pLeft;
          }
          closestResource = rpCenter;
        } else if (rpCenter.equals(closestResource)) {
          closestResource = null;
        }
        if (pLeft == 0 && rpCenter.isWithinDistanceSquared(rc.getLocation(), 2)) {
          if (rpCenter.equals(closestResource)) {
            closestResource = null;
          }
          lastRPCheckTurn[rc.getLocation().x / 4][rc.getLocation().y / 4] = rc.getRoundNum();
        }
      }
      if (closestResource != null) {
        rc.setIndicatorDot(closestResource, 255, 255, 255);
        rc.setIndicatorString("left: " + closestResourceSquaresLeft);
      }
    }

    for (var m : rc.readMessages(-1)) {
      var loc = decode(m.getBytes());
      if ((m.getBytes() & 1 << 13) != 0) {
        exploreTarget = loc;
      } else {
        ruinTarget = loc;
        rc.setIndicatorDot(ruinTarget, 255, 0, 0);
      }
    }

    if (rc.getNumberTowers() == GameConstants.MAX_NUMBER_OF_TOWERS) {
      ruinTarget = null;
      return;
    }

    if (ruinTarget != null && rc.canSenseRobotAtLocation(ruinTarget)) ruinTarget = null;
    if (ruinTarget != null && rc.canSenseLocation(ruinTarget)) {
      for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinTarget, 8)) {
        if (patternTile.getPaint().isEnemy()) {
          ruinTarget = null;
        }
      }
    }

    var cRuinTarget = ruinTarget;
    if (ruinTarget == null || !rc.canSenseLocation(ruinTarget)) {
      a:
      for (var ruin : rc.senseNearbyRuins(-1)) {
        if (rc.senseRobotAtLocation(ruin) != null) continue;
        for (MapInfo patternTile : rc.senseNearbyMapInfos(ruin, 8)) {
          if (isEnemy(patternTile.getPaint())) {
            continue a;
          }
        }
        // Okay we pick this ruin
        cRuinTarget = ruin;
        break;
      }
    }

    if (ruinTarget == null) {
      ruinTarget = cRuinTarget;
    }

    // Lower left corner
    var markLoc = cRuinTarget == null ? null : cRuinTarget.translate(0, 1);
    // TODO we don't need markers if we use hashes of ruin positions (though it means we can't dynamically decide which to build)
//    if (ruinTarget != null) {
//      rc.setIndicatorDot(ruinTarget, 255, 255, 255);
//    }
    if (cRuinTarget != null && rc.canSenseLocation(markLoc)) {
      // primary = money, secondary = paint
//      var avgIncome = incomeFrames.stream().reduce((x, y) -> x + y).get() / incomeFrames.size();
//      var numTowers = rc.getNumberTowers();
//      // target 25 chips/tower?
//      var percent = (double) avgIncome / ((double) numTowers * 45.0) * 100.0;
      // just hardcoding 3/5 money towers for now
      var type = (rng() % 5) >= 2 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
//      type = rc.getNumberTowers() % 2 == 0 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
      var okay = false;
      if (!rc.senseMapInfo(markLoc).getMark().isAlly()) {
        rc.setIndicatorDot(markLoc, 0, 255, 0);
        if (rc.canMark(markLoc)) {
          rc.mark(markLoc, type != UnitType.LEVEL_ONE_MONEY_TOWER);
          okay = true;
        } else {
          //System.out.println("cant mark corner???");
        }
      } else {
        type = rc.senseMapInfo(markLoc).getMark() == PaintType.ALLY_PRIMARY ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
        okay = true;
      }
      if (okay) {
        if (mapColors[markLoc.x][markLoc.y] == null) {
          // Put in the pattern
          var pattern = rc.getTowerPattern(type);
          for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
              mapColors[cRuinTarget.x - 2 + x][cRuinTarget.y - 2 + y] = new MapColor(pattern[y][x]);
            }
          }
        }
        // Complete the ruin if we can.
        if (rc.canCompleteTowerPattern(type, cRuinTarget)) {
          rc.completeTowerPattern(type, cRuinTarget);
          if (cRuinTarget.equals(ruinTarget)) {
            ruinTarget = null;
          }
          rc.setTimelineMarker("Tower built", 0, 255, 0);
        }
      }
    }

    // TODO per-tower
    if (ruinTarget != null && !ruinTarget.equals(sentRuin)) {
      for (var i : nearbyAllies) {
        if (i.getType().isTowerType() && rc.canSendMessage(i.location)) {
          rc.sendMessage(i.location, encode(ruinTarget));
          sentRuin = ruinTarget;
        }
      }
    }
  }

  static int upgradeTurns = 0;
  static UnitType toSpawn = null;
  static Integer[] ids = {0, 0, 0, 0};

  public static void run(RobotController rc) throws GameActionException {
    RobotPlayer.rc = rc;
    spawnCounter = rng() % SPAWN_LIST.size();
    // This is in [x][y], but the patterns are in [y][x], as far as i can tell. not that it should matter since they're rotationally symmetrical i think
    mapColors = new MapColor[rc.getMapWidth()][rc.getMapHeight()];
    resourcePattern = rc.getResourcePattern();
    final MapLocation[] exploreLocs = {
        new MapLocation(0, 0),
        new MapLocation(rc.getMapWidth() - 1, 0),
        new MapLocation(0, rc.getMapHeight() - 1),
        new MapLocation(rc.getMapWidth() - 1, rc.getMapHeight() - 1),
    };
    var exploreIdx = 7;
    if (rc.getRoundNum() < 3) {
      exploreIdx = rc.getType().paintPerTurn > 0 ? 0 : 2;
    }

    while (true) {
      try {
        nearbyAllies = rc.senseNearbyRobots(GameConstants.VISION_RADIUS_SQUARED, rc.getTeam());
        nearbyEnemies = rc.senseNearbyRobots(GameConstants.VISION_RADIUS_SQUARED, rc.getTeam().opponent());

        if (!rc.getType().isTowerType()) {
          for (var i : nearbyAllies) {
            if (i.getType().isTowerType()) {
              if (i.getType().paintPerTurn > 0) {
                if (closestPaintTower == null || i.location.distanceSquaredTo(rc.getLocation()) < closestPaintTower.distanceSquaredTo(rc.getLocation())) {
                  closestPaintTower = i.location;
                }
              }
              if (closestTower == null || i.location.distanceSquaredTo(rc.getLocation()) < closestTower.distanceSquaredTo(rc.getLocation())) {
                closestTower = i.location;
              }
              if (mapColors[i.location.x - 1][i.location.y - 1] != null) {
                // remove pattern
                for (var x = -2; x <= 2; x++) {
                  for (var y = -2; y <= 2; y++) {
                    mapColors[i.location.x + x][i.location.y + y] = null;
                  }
                }
              }
            }

          }
        }

        switch (rc.getType()) {
          case SOLDIER -> {
            // Attack if possible
            doAttack();

            // Try to build ruins
            checkRuins();

            maybeReplenishPaint();

            // Move
            doMove();

            // Try to paint a square in range
            doPaint();
          }
          case SPLASHER -> {
            // Attack if possible
            doAttack();

            // Try to build ruins
            checkRuins();

            maybeReplenishPaint();

            // Move
            doMove();
          }
          case MOPPER -> {
            // First attack enemies
            doAttack();

            // Resupply nearby soldiers/splashers
            if (rc.getPaint() >= minPaint() + 10 && rc.isActionReady()) {
              for (var unit : rc.senseNearbyRobots(rc.getType().actionRadiusSquared, rc.getTeam())) {
                if (unit.getType().isRobotType() && unit.getType() != UnitType.MOPPER && unit.paintAmount < unit.getType().paintCapacity) {
                  var transfer = Math.min(rc.getPaint() - minPaint(), unit.getType().paintCapacity - unit.paintAmount);
                  rc.transferPaint(unit.location, transfer);
                  break;
                }
              }
            }

            // Target ruins the enemy is trying to build
            var b = ruinTarget;
            checkRuins();
            ruinTarget = b;

            if (ruinTarget != null && rc.canSenseRobotAtLocation(ruinTarget)
                && rc.canSenseLocation(ruinTarget.add(rc.getLocation().directionTo(ruinTarget)).add(rc.getLocation().directionTo(ruinTarget)))) {
              ruinTarget = null;
            }
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
            if (toMop != null)
              rc.attack(toMop);

            maybeReplenishPaint();

            doMove();
          }
          default -> {
            // Towers

            // Attack if possible
            doAttack();

            // Self upgrade if possible (for money towers first)
            if (rc.canUpgradeTower(rc.getLocation())) {
              upgradeTurns += 1;
              if (upgradeTurns > 2 || rc.getType().moneyPerTurn > 0) {
                rc.upgradeTower(rc.getLocation());
              }
            } else {
              upgradeTurns = 0;
            }

            // Try to spawn a unit
            if (rc.getRoundNum() < 4) {
              toSpawn = UnitType.SOLDIER;
            }
            if (toSpawn == null) {
              var splasherChance = 1.0 / 3.0;
              // after splashers have been ruled out
              var mopperChance = 1.0 / 2.0;
              // More splashers on bigger maps
              if (rc.getMapHeight() >= 40 && rc.getMapWidth() >= 40) {
                splasherChance = 2.0 / 5.0;
                mopperChance = 1.0 / 3.0;
              }
              // More moppers on smaller maps
              if (rc.getMapHeight() <= 20 && rc.getMapWidth() <= 20) {
                splasherChance = 1.0 / 5.0;
                mopperChance = 2.0 / 5.0;
              }
              if (rc.getNumberTowers() < 3) {
                splasherChance = 0;
              }
              toSpawn = (double) (rng() % 100) < splasherChance * 100.0 ? UnitType.SPLASHER
                  : rng() % 100 < mopperChance * 100.0 ? UnitType.MOPPER
                  : UnitType.SOLDIER;
              rc.setIndicatorString("chance: " + splasherChance + " / " + mopperChance + " --> " + toSpawn + " (sample " + (rng() % 100) + ")");
            }
            Direction bdir = null;
            var selfPaint = false;
            // Spawn on our paint if possible
            for (var dir : MOVE_DIRECTIONS) {
              if (rc.canBuildRobot(toSpawn, rc.getLocation().add(dir))) {
                if (bdir == null || !selfPaint) {
                  var paint = rc.senseMapInfo(rc.getLocation().add(dir)).getPaint().isAlly();
                  bdir = dir;
                  selfPaint = paint;
                }
              }
            }
            if (bdir != null) {
              rc.buildRobot(toSpawn, rc.getLocation().add(bdir));
              toSpawn = null;
            }

            // TODO better comms system
            for (var m : rc.readMessages(-1)) {
              var l = decode(m.getBytes());
              if (!l.equals(ruinTarget) && (ruinTarget == null || l.distanceSquaredTo(rc.getLocation()) < ruinTarget.distanceSquaredTo(rc.getLocation()))) {
                ruinTarget = l;
                rc.setIndicatorDot(ruinTarget, 255, 0, 0);
              }
            }
            var nSent = 0;
            if (ruinTarget != null) {
              for (var i : nearbyAllies) {
                if (i.location.isWithinDistanceSquared(rc.getLocation(), GameConstants.MESSAGE_RADIUS_SQUARED) && rc.canSendMessage(i.location)) {
                  rc.sendMessage(i.location, encode(ruinTarget));
                  if (++nSent >= GameConstants.MAX_MESSAGES_SENT_TOWER) {
                    break;
                  }
                }
              }
            } else if (exploreIdx < exploreLocs.length) {
              for (var i : nearbyAllies) {
                if (Arrays.stream(ids).anyMatch(n -> n == i.getID())) continue;
                if (rc.canSendMessage(i.location)) {
                  rc.setIndicatorLine(rc.getLocation(), i.location, 255, 0, 0);
                  rc.sendMessage(i.location, 1 << 13 | encode(exploreLocs[exploreIdx]));
                  ids[exploreIdx] = i.getID();
                  exploreIdx++;
                }
              }
            }
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
        rc.setIndicatorString("exception");
      }

      Clock.yield();
    }
  }
}
