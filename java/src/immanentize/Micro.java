package immanentize;

import battlecode.common.*;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Supplier;

import static immanentize.RobotPlayer.*;

public class Micro {
  static final double FREE_PAINT_TARGET = 0.2;
  static final double KILL_VALUE = 200;
  static final double PAINT_VALUE = 0.5;
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

  record TargetType(Supplier<Optional<MapLocation>> fun) {
  }

  static boolean doTowers() {
    return rc.getChips() >= UnitType.LEVEL_ONE_PAINT_TOWER.moneyCost * 0.8;
  }

  static MapLocation exploreTarget = null;
  static TargetType[] targets = {
      // Paint resupply - URGENT
      new TargetType(() -> Optional.ofNullable(map.closestPaintTower).map(x -> x.loc).filter(_x -> rc.getPaint() < minPaint() + rc.getType().attackCost)),
      // Mopper: find nearby soldier
      new TargetType(() -> Optional.of(0)
          .filter(_x -> rc.getType() == UnitType.MOPPER && rc.getPaint() >= minPaint() + 10)
          .flatMap(_x -> Arrays.stream(nearbyAllies)
              .filter(x -> x.getType().isRobotType() && x.getType() != UnitType.MOPPER && x.paintAmount < x.getType().paintCapacity)
              .min(Comparator.comparingInt(x -> x.paintAmount))
              .map(x -> x.location))),
      // Soldier: target mark location
      new TargetType(() -> Optional.ofNullable(map.ruinTarget).map(x -> x.center.translate(0, 1)).filter(x -> rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost && !map.tile(x).isInRuin())),
      // Soldier: target unpainted tower squares
      new TargetType(() -> Optional.ofNullable(map.ruinTarget).filter(x -> doTowers() && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost).flatMap(ruin -> {
        Optional<MapLocation> target = Optional.empty();
        try {
          a:
          for (int x = -2; x < 3; x++) {
            for (int y = -2; y < 3; y++) {
              var loc = ruin.center.translate(x, y);
              if ((x == 0 && y == 0) || !rc.canSenseLocation(loc)) continue;
              if (!rc.senseMapInfo(loc).getPaint().isAlly() || (rc.senseMapInfo(loc).getPaint() == PaintType.ALLY_SECONDARY) != map.tile(loc).secondary()) {
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
      // Soldier: target nearly-full resource pattern
      new TargetType(() -> Optional.ofNullable(closestResource).filter(x -> rc.getType() == UnitType.SOLDIER && (rc.getPaint() >= minPaint() + rc.getType().attackCost || closestResourceSquaresLeft == 0) && closestResourceSquaresLeft < 12)),
      // Soldier: target ruins
      new TargetType(() -> Optional.ofNullable(map.ruinTarget)
          .map(x -> x.center)
          .filter(x -> rc.getType() == UnitType.SOLDIER
              && rc.getPaint() >= minPaint() + rc.getType().attackCost
              && doTowers())),
      // Mopper/splasher: find enemy paint
      new TargetType(() -> Optional.of(0)
          .filter(_x -> rc.getType() == UnitType.MOPPER)// || (rc.getType() == UnitType.SPLASHER && rc.getPaint() >= 100))
          .flatMap(_x -> Arrays.stream(rc.senseNearbyMapInfos())
              .filter(x -> x.getPaint().isEnemy())
              .min(Comparator.comparingInt(x -> x.getMapLocation().distanceSquaredTo(rc.getLocation())))
              .map(x -> x.getMapLocation()))),
      // Paint resupply - if nothing else important to do (for soldiers)
      new TargetType(() -> Optional.ofNullable(map.closestPaintTower).map(x -> x.loc).filter(_x -> rc.getPaint() < 50 + rc.getType().attackCost)),
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


  static class MicroLoc {
    MapLocation loc;
    double incomingDamage;
    double adjacentAllies;
    double attackScore;
    MapLocation attackTarget;
    double score;

    MicroLoc(MapLocation loc) {
      this.loc = loc;
    }
  }

  record MicroBot(UnitType type, MapLocation startPos, boolean canAttack, boolean canMove, int paint, int hp) {
  }

  static void processEnemies(MicroLoc[] locs, MicroBot bot) throws GameActionException {
    var targetTowers = bot.type.isRobotType() && bot.type != UnitType.MOPPER;
    var isMopper = bot.type == UnitType.MOPPER;
    var attackDist = bot.type.actionRadiusSquared;
    var damage = bot.type.attackStrength;
    var attack = (damage > 0 || isMopper) && bot.canAttack;
    for (var unit : nearbyEnemies) {
      if (unit.getType().isTowerType() != targetTowers && (bot.type.isTowerType() != unit.getType().isTowerType() || unit.type != UnitType.MOPPER))
        continue;
      for (var loc : locs) {
        if (attack && unit.getType().isTowerType() == targetTowers && unit.location.isWithinDistanceSquared(loc.loc, attackDist)) {
          var score = (double) Math.min(damage, unit.health);
          if (damage >= unit.health) score += KILL_VALUE;
          if (isMopper) {
            var paint = Math.min(10, unit.paintAmount);
            if (rc.senseMapInfo(unit.location).getPaint().isEnemy()) paint += 1;
            // TODO verify that this is true (5 paint to our team regardless of amount of paint stolen) (a dev said they think this is how it works)
            score = (paint + 5) * PAINT_VALUE;
          } else if (unit.type == UnitType.SOLDIER && rc.senseMapInfo(unit.location).getPaint() == PaintType.EMPTY) {
            score += PAINT_VALUE;
          }
          if (score > loc.attackScore) {
            loc.attackScore = score;
            loc.attackTarget = unit.location;
          }
        }
        if ((bot.type.isTowerType() != unit.getType().isTowerType() || unit.type == UnitType.MOPPER)
            && ((unit.type.attackStrength > 0 || unit.type == UnitType.MOPPER) && unit.location.isWithinDistanceSquared(loc.loc, unit.type.actionRadiusSquared))) {
          loc.incomingDamage += unit.type == UnitType.MOPPER ? Math.min(10, bot.paint) + 5 : Math.min(bot.hp, unit.type.attackStrength);
        }
      }
    }
  }

  static double[][] processSplasherLocs(MapLocation center) throws GameActionException {
    var locs = new double[9][9];
    //     x . .
    //   x x x . .
    // x x A x x .
    //   x x x
    //     x
    int[] aoeOffsetsX = {0, -1, 0, 1, -2, -1, 0, 1, 2, -1, 0, 1, 0};
    int[] aoeOffsetsY = {2, 1, 1, 1, 0, 0, 0, 0, 0, -1, -1, -1, -2};
    var damage = UnitType.SPLASHER.aoeAttackStrength;
    for (var unit : nearbyEnemies) {
      if (unit.getType().isRobotType()) continue;
      var x = unit.location.x - center.x + 3;
      var y = unit.location.y - center.y + 3;
      for (int i = 0; i < aoeOffsetsY.length; i++) {
        var ax = x + aoeOffsetsX[i];
        var ay = y + aoeOffsetsY[i];
        if (ax >= 0 && ay >= 0 && ax < 9 && ay < 9) {
          locs[ax][ay] += Math.min(damage, unit.health) + (damage >= unit.health ? KILL_VALUE : 0);
        }
      }
    }
    // Not sure we can narrow it down at all if we're allowed to move once and then attack...
    for (var tile : rc.senseNearbyMapInfos()) {
      if (!tile.isPassable()) continue;
      var p = tile.getPaint();
      if (p.isAlly()) continue;
      var x = tile.getMapLocation().x - center.x + 3;
      var y = tile.getMapLocation().y - center.y + 3;
      if (p == PaintType.EMPTY) {
        for (int i = 0; i < aoeOffsetsY.length; i++) {
          var ax = x + aoeOffsetsX[i];
          var ay = y + aoeOffsetsY[i];
          if (ax >= 0 && ay >= 0 && ax < 9 && ay < 9) {
            locs[ax][ay] += PAINT_VALUE;
          }
        }
      } else if (p.isEnemy()) {
        for (int i = -1; i < 2; i++) {
          for (int j = -1; j < 2; j++) {
            var ax = x + i;
            var ay = y + j;
            if (ax >= 0 && ay >= 0 && ax < 9 && ay < 9) {
              locs[ax][ay] += 2 * PAINT_VALUE;
            }
          }
        }
      }
    }
    return locs;
  }

  static void processAttacks(MicroLoc[] locs, MicroBot bot) throws GameActionException {
    processEnemies(locs, bot);
    if (bot.type == UnitType.SPLASHER && bot.canAttack) {
      var splashLocs = processSplasherLocs(bot.startPos);
      for (var loc : locs) {
        var x = loc.loc.x - bot.startPos.x + 3;
        var y = loc.loc.y - bot.startPos.y + 3;
        MapLocation bestLoc = null;
        var bestScore = 0.0;
        int[] aoeOffsetsX = {0, -1, 0, 1, -2, -1, 0, 1, 2, -1, 0, 1, 0};
        int[] aoeOffsetsY = {2, 1, 1, 1, 0, 0, 0, 0, 0, -1, -1, -1, -2};
        for (int i = 0; i < aoeOffsetsY.length; i++) {
          var ax = x + aoeOffsetsX[i];
          var ay = y + aoeOffsetsY[i];
          if (ax >= 0 && ay >= 0 && ax < 9 && ay < 9) {
            var score = splashLocs[ax][ay];
            // splasher min damage is 4.0 - TODO bikeshed
            if (score > 4.0 * PAINT_VALUE && score > bestScore) {
              bestScore = score;
              bestLoc = loc.loc.translate(aoeOffsetsX[i], aoeOffsetsY[i]);
            }
          }
        }
        loc.attackTarget = bestLoc;
        loc.attackScore = bestScore;
      }
    }
  }

  static ArrayDeque<MapLocation> recentLocs = new ArrayDeque<>();
  static int bfAttack;
  static int bfTarget;
  static int bfScore;
  static MapLocation lastTarget;
  static int rlCounter;

  static MicroLoc findBestLoc(MicroBot bot, boolean includeCenter) throws GameActionException {
    var locs = bot.canMove && bot.type.isRobotType() ? Arrays.stream(rc.senseNearbyMapInfos(bot.startPos, 2))
        .filter(x -> x.isPassable() && ((x.getMapLocation().equals(bot.startPos) && includeCenter) || !rc.canSenseRobotAtLocation(x.getMapLocation())))
        .map(x -> new MicroLoc(x.getMapLocation()))
        .toArray(n -> new MicroLoc[n]) : new MicroLoc[]{new MicroLoc(bot.startPos)};
    bfAttack = Clock.getBytecodeNum();
    processAttacks(locs, bot);
    bfTarget = Clock.getBytecodeNum();
    var pmul = bot.type == UnitType.MOPPER ? 2 : 1;
    MapLocation target = null;
    if (locs.length > 1) {
      for (var r : targets) {
        var x = r.fun().get();
        if (x.isPresent()) {
          target = x.get();
          break;
        }
      }
      if (target != null) {
        if (!target.equals(lastTarget)) {
          recentLocs.clear();
        }
        lastTarget = target;
        rc.setIndicatorLine(bot.startPos, target, 0, 0, 255);
      }
    }
//    var target = Arrays.stream(targets).flatMap(x -> x.fun().get().stream()).findFirst();
    bfScore = Clock.getBytecodeNum();
    MicroLoc best = null;
    var pTargetDist = target != null ? bot.startPos.distanceSquaredTo(target) : 0;
    for (var loc : locs) {
      var score = loc.attackScore - loc.incomingDamage;
//      for (var i = 0; i < rtargets.length; i++) {
//        var target = rtargets[i];//.fun().get();
//        //if (target.isEmpty()) continue;
//        score -= target.distanceSquaredTo(loc.loc) * 0.1 * (double) (rtargets.length - i) / rtargets.length;
//      }
      if (target != null) {
        score -= (target.distanceSquaredTo(loc.loc) - pTargetDist) * 0.02;// * (double) (rtargets.length - i) / rtargets.length;
      }
      if (loc.attackScore == 0.0 && recentLocs.contains(loc.loc)) {
        score -= (rlCounter + 1) * 0.2;
      }
      var paint = rc.senseMapInfo(loc.loc).getPaint();
      if (paint.isEnemy()) score -= 2 * pmul * PAINT_VALUE;
      else if (paint == PaintType.EMPTY) score -= pmul * PAINT_VALUE;
      loc.score = score;
      if (best == null || loc.score > best.score) best = loc;
    }
    return best;
  }

  static void doMicro() throws GameActionException {
    var start = Clock.getBytecodeNum();
    if (rc.getType().isTowerType()) {
      rc.attack(null);
    }
    var loc = findBestLoc(new MicroBot(rc.getType(), rc.getLocation(), rc.isActionReady() && rc.getPaint() >= minPaint() + rc.getType().attackCost, rc.isMovementReady(), rc.getPaint(), rc.getHealth()), true);
    if (!loc.loc.equals(rc.getLocation())) {
      if (recentLocs.contains(loc.loc)) rlCounter += 1;
      else rlCounter = 0;

      if (recentLocs.size() >= 12) recentLocs.removeFirst();
      recentLocs.addLast(rc.getLocation());

      rc.move(rc.getLocation().directionTo(loc.loc));
    } else if (rc.isMovementReady()) {
      exploreTarget = null;
    }
    // always do this
    if (rc.isActionReady() && rc.getPaint() >= minPaint() + rc.getType().attackCost && rc.senseMapInfo(rc.getLocation()).getPaint() == PaintType.EMPTY && rc.getType() == UnitType.SOLDIER) {
      rc.attack(rc.getLocation());
    } else if (loc.attackTarget != null) {
      rc.attack(loc.attackTarget);
      recentLocs.clear();
    }
    var end = Clock.getBytecodeNum();
    rc.setIndicatorString("micro: " + (end - start) + "bt: " + (bfAttack - start) + ", " + (bfTarget - bfAttack) + ", " + (bfScore - bfTarget) + ", " + (end - bfScore));
  }
}



