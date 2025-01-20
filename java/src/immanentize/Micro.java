package immanentize;

import battlecode.common.*;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.function.Supplier;

import static immanentize.RobotPlayer.*;

public class Micro {
  static final double FREE_PAINT_TARGET = 0.2;
  static final double HP_TOTAL_VALUE = 3.0;
  static final double HP_FIXED_VALUE = 0.5;
  static final double KILL_VALUE = 2000.0;
  static final double MAP_PAINT_VALUE = 15.0;
  // This number is multiplied by both the fixed and variable components, then they're added together
  // So for FREE_PAINT_FIXED_VALUE = 1.0, the max value is FREE_PAINT_VALUE * 2
  static final double FREE_PAINT_VALUE = 2.0;
  static final double FREE_PAINT_FIXED_VALUE = 0.5;
  static final int SOLDIER_PAINT_MIN = 15;
  static final int MOPPER_PAINT_MIN = 40;
  static final int SPLASHER_PAINT_MIN = 40;

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
    return rc.getChips() >= UnitType.LEVEL_ONE_PAINT_TOWER.moneyCost * 0.7;
  }

  static MapLocation exploreTarget = null;

  static MapLocation pickTarget(MicroBot bot) throws GameActionException {
    // Paint resupply - URGENT
    if (map.closestPaintTower != null && bot.paint < minPaint() + bot.type.attackCost)
      return map.closestPaintTower.loc;

    // Mopper: find nearby soldier
    if (rc.getType() == UnitType.MOPPER && rc.getPaint() >= 60) {
      RobotInfo best = null;
      for (var x : nearbyAllies) {
        if (x.getType().isRobotType() && x.getType() != UnitType.MOPPER && x.paintAmount < x.getType().paintCapacity) {
          if (best == null || x.paintAmount < best.paintAmount) {
            best = x;
          }
        }
      }
      if (best != null) {
        return best.location;
      }
    }

    if (map.closestRP != null && rc.getType() == UnitType.SOLDIER && (rc.getPaint() >= minPaint() + rc.getType().attackCost)
        && (!doTowers() || map.ruinTarget == null) && rc.getLocation().isWithinDistanceSquared(map.closestRP.center, 8)) {
      return map.closestRP.center;
    }

    // Soldier: target unpainted tower squares
    if (map.ruinTarget != null && doTowers() && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost) {
      MapLocation[] corners = {map.ruinTarget.center.translate(-2, -2), map.ruinTarget.center.translate(2, -2), map.ruinTarget.center.translate(-2, 2), map.ruinTarget.center.translate(2, 2)};
      for (var loc : corners) {
        if (!rc.canSenseLocation(loc)) continue;
        if (!rc.senseMapInfo(loc).getPaint().isAlly() || (rc.senseMapInfo(loc).getPaint() == PaintType.ALLY_SECONDARY) != map.tile(loc).secondary()) {
          return loc;
        }
      }
    }

    if (map.ruinTarget != null && doTowers() && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost && rc.getLocation().isWithinDistanceSquared(map.ruinTarget.center, 8)) {
      var dx = rc.getLocation().x == map.ruinTarget.center.x ? (rc.getLocation().y > map.ruinTarget.center.y ? 1 : -1) : 0;
      var dy = rc.getLocation().y == map.ruinTarget.center.y ? (rc.getLocation().x > map.ruinTarget.center.x ? -1 : 1) : 0;
      return map.ruinTarget.center.translate(dx, dy);
    }

    // Soldier: target nearly-full resource pattern
    if (map.closestRP != null && bot.type == UnitType.SOLDIER && (bot.paint >= minPaint() + bot.type.attackCost) && (!doTowers() || map.ruinTarget == null)) {
      return map.closestRP.center;
    }

    // Soldier: target ruins
    if (map.ruinTarget != null && bot.type == UnitType.SOLDIER
        && rc.getID() % 3 != 0
        && bot.paint >= minPaint() + bot.type.attackCost
        && doTowers()) {
      return map.ruinTarget.center;
    }

    // Soldier(/splasher?): target enemy towers
    if (map.closestEnemyTower != null && bot.type != UnitType.MOPPER && nearbyAllies.length > 0 && bot.paint >= minPaint() + bot.type.attackCost * 3) {
      return map.closestEnemyTower.loc;
    }

    // Mopper/splasher: find enemy paint
    if (bot.type == UnitType.MOPPER) {
      // || (rc.getType() == UnitType.SPLASHER && rc.getPaint() >= 100))
      MapLocation best = null;
      var bestD = 0;
      for (var i : rc.senseNearbyMapInfos()) {
        if (i.getPaint().isEnemy()) {
          var d = i.getMapLocation().distanceSquaredTo(bot.startPos);
          if (best == null || d < bestD) {
            best = i.getMapLocation();
            bestD = d;
          }
        }
      }
      if (best != null)
        return best;
    }

    if (map.ruinTarget != null && bot.type == UnitType.MOPPER
        && map.ruinTarget.enemyTiles > 0 && doTowers()) {
      return map.ruinTarget.center;
    }

    // Paint resupply - if nothing else important to do (for soldiers)
    if (map.closestPaintTower != null && bot.paint < 50 + rc.getType().attackCost) {
      return map.closestPaintTower.loc;
    }

    // Exploration
    if (exploreTarget == null || exploreTarget.isWithinDistanceSquared(bot.startPos, 2)) {
      exploreTarget = map.findUnvisitedTile();
    }
    if (exploreTarget == null || exploreTarget.isWithinDistanceSquared(bot.startPos, 2)) {
      exploreTarget = new MapLocation(rng() % rc.getMapWidth(), rng() % rc.getMapHeight());
    }
    return exploreTarget;
  }

  static class MicroLoc {
    MapLocation loc;
    double incomingDamage;
    double adjacentAllies;
    double attackScore;
    MapLocation attackTarget;
    Direction attackMopSwing;
    double[] mopSwingScores = new double[4];
    double score;

    MicroLoc(MapLocation loc) {
      this.loc = loc;
    }
  }

  record MicroBot(UnitType type, MapLocation startPos, boolean canAttack, boolean canMove, int paint, int hp) {
  }

  static final double[] tyValues = {
      1,    //      SOLDIER
      2,    //      SPLASHER
      1,    //      MOPPER
      //
      10,   //      LEVEL_ONE_PAINT_TOWER
      15,   //      LEVEL_TWO_PAINT_TOWER
      20,   //      LEVEL_THREE_PAINT_TOWER
      //
      10,   //      LEVEL_ONE_MONEY_TOWER
      15,   //      LEVEL_TWO_MONEY_TOWER
      20,   //      LEVEL_THREE_MONEY_TOWER
      //
      10,   //      LEVEL_ONE_DEFENSE_TOWER
      15,   //      LEVEL_TWO_DEFENSE_TOWER
      20,   //      LEVEL_THREE_DEFENSE_TOWER
  };

  static void processEnemies(MicroLoc[] locs, MicroBot bot) throws GameActionException {
    var targetTowers = bot.type.isRobotType() && bot.type != UnitType.MOPPER;
    var isMopper = bot.type == UnitType.MOPPER;
    // TODO verify that this is true (5 paint to our team regardless of amount of paint stolen) (a dev said they think this is how it works)
    var mopReturn = isMopper ? Math.min(5, bot.type.paintCapacity - bot.paint) * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE : 0.0;
    var attackDist = bot.type.actionRadiusSquared;
    var damage = bot.type.attackStrength;
    var attack = (damage > 0 || isMopper) && bot.canAttack;
    var cost = bot.type.attackCost * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
    for (var unit : nearbyEnemies) {
      // We can't attack it and it can't attack us
      if (unit.type.isTowerType() != targetTowers && (bot.type.isTowerType() == unit.type.isTowerType() && unit.type != UnitType.MOPPER))
        continue;
      for (var loc : locs) {
        if (attack && unit.type.isTowerType() == targetTowers && unit.location.isWithinDistanceSquared(loc.loc, attackDist)) {
          double score;
          if (isMopper) {
            score = Math.min(10, unit.paintAmount) * (1 - (double) unit.paintAmount / unit.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE + mopReturn;
            // TODO this isn't exactly correct - want it to match processTiles(), or do all this there but that would be more bt
            if (rc.senseMapInfo(unit.location).getPaint().isEnemy()) score += MAP_PAINT_VALUE;
          } else {
            score = (double) Math.min(damage, unit.health) * ((1 - (double) unit.health / unit.type.health) + HP_FIXED_VALUE) * HP_TOTAL_VALUE;
            if (damage >= unit.health) score += KILL_VALUE;
            //score *= tyValues[unit.type.ordinal()];
            score -= cost;
          }
          if (score > loc.attackScore) {
            loc.attackScore = score;
            loc.attackTarget = unit.location;
          }
        }
        if ((bot.type.isTowerType() != unit.getType().isTowerType() || unit.type == UnitType.MOPPER)
            && ((unit.type.attackStrength > 0 || unit.type == UnitType.MOPPER) && unit.location.isWithinDistanceSquared(loc.loc, unit.type.actionRadiusSquared))) {
          loc.incomingDamage += unit.type == UnitType.MOPPER ? (Math.min(10, bot.paint) + 5) * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE
              : (double) Math.min(bot.hp, unit.type.attackStrength) * ((1 - (double) bot.hp / bot.type.health) + HP_FIXED_VALUE) * HP_TOTAL_VALUE;
        }
        if (attack && isMopper && unit.type.isRobotType()) {
          for (int i = 0; i < 4; i++) {
            var dir = Direction.cardinalDirections()[i];
            var dx = unit.location.x - loc.loc.x;
            var dy = unit.location.y - loc.loc.y;
            int r = dx * dir.dx + dy * dir.dy;
            if ((r == 1 || r == 2) && Math.abs(dx * (1 - Math.abs(dir.dx)) + dy * (1 - Math.abs(dir.dy))) <= 1) {
              // mop swing hits
              loc.mopSwingScores[i] += Math.min(5, unit.paintAmount) * (1 - (double) unit.paintAmount / unit.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
            }
          }
        }
      }
    }
    if (isMopper && bot.canAttack) {
      for (var loc : locs) {
        for (int i = 0; i < 4; i++) {
          // Swinging has 20 cooldown, single-target mopping has 30, so adjust for the frequency at which we can do these actions
          loc.mopSwingScores[i] *= 3.0 / 2;
          if (loc.mopSwingScores[i] > loc.attackScore) {
            loc.attackMopSwing = Direction.cardinalDirections()[i];
            loc.attackScore = loc.mopSwingScores[i];
            loc.attackTarget = null;
          }
        }
      }
//      for (var loc : locs) {
//        for (var dir : Direction.cardinalDirections()) {
//          var score = 0.0;
//          var l1 = loc.loc.add(dir);
//          var l2 = l1.add(dir);
//          var a = dir.rotateRight().rotateRight();
//          var b = dir.rotateLeft().rotateLeft();
//          Direction[] ds = {a, Direction.CENTER, b};
//          for (var d : ds) {
//            var l1_ = l1.add(d);
//            var l2_ = l2.add(d);
//            if (rc.canSenseRobotAtLocation(l1_)) {
//              var unit = rc.senseRobotAtLocation(l1_);
//              if (unit.team != rc.getTeam() && unit.type.isRobotType()) {
//                score += Math.min(5, unit.paintAmount) * (1 - (double) unit.paintAmount / unit.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
//                // no paint to mopper for swinging right? TODO verify
//                // score += paint + Math.min(5, bot.type.paintCapacity - bot.paint) * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
//              }
//            }
//            if (rc.canSenseRobotAtLocation(l2_)) {
//              var unit = rc.senseRobotAtLocation(l2_);
//              if (unit.team != rc.getTeam() && unit.type.isRobotType()) {
//                score += Math.min(5, unit.paintAmount) * (1 - (double) unit.paintAmount / unit.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
//                // no paint to mopper for swinging right? TODO verify
//                // score += paint + Math.min(5, bot.type.paintCapacity - bot.paint) * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
//              }
//            }
//          }
//          // Swinging has 20 cooldown, single-target mopping has 30, so adjust for the frequency at which we can do these actions
//          score *= 3.0 / 2;
//          if (score > loc.attackScore) {
//            loc.attackScore = score;
//            loc.attackTarget = null;
//            loc.attackMopSwing = dir;
//          }
//        }
//      }
    }
  }

  static void processTiles(MicroLoc[] locs, MicroBot bot) throws GameActionException {
    if (!bot.canAttack) return;
    // for soldiers: normal action radius 9, adjusted for movement is 18
    // for moppers: 2 / 8
    var tiles = map.tiles;
    if (bot.type == UnitType.SOLDIER) {
      if (bot.paint < minPaint() + UnitType.SOLDIER.attackCost) return;
      var mscore = 0.0;
      var cost = bot.type.attackCost * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
      if (!map.isPaintTower) cost = MAP_PAINT_VALUE * 1.1;
      var ptile = lastTarget == null ? null : bot.startPos.add(bot.startPos.directionTo(lastTarget));
      var rtarget = map.ruinTarget != null ? map.ruinTarget.center : new MapLocation(70, 70);
      var rptarget = map.closestRP != null ? map.closestRP.center : new MapLocation(70, 70);

      for (var tile : rc.senseNearbyMapInfos(bot.startPos, 18)) {
        if (!tile.isPassable()) continue;
        var p = tile.getPaint();
        var v = 0.0;
        if (p.isEnemy()) continue;
        var l = tile.getMapLocation();

        if (rtarget.isWithinDistanceSquared(l, 8)) {
          if (p == PaintType.EMPTY || p.isSecondary() != map.ruinTarget.secondary(l))
            v = 2 * MAP_PAINT_VALUE;
        } else if (rptarget.isWithinDistanceSquared(l, 8)) {
          if (p == PaintType.EMPTY || p.isSecondary() != map.closestRP.secondary(l))
            v = 1.5 * MAP_PAINT_VALUE;
        } else if (p == PaintType.EMPTY) {
          v = MAP_PAINT_VALUE;
        }
        if (l.equals(ptile)) {
          v *= 1.2;
        }
        v -= cost;

        if (v > mscore) {
          mscore = 1000.0;
          for (var loc : locs) {
            if (v > loc.attackScore && loc.loc.isWithinDistanceSquared(l, 9)) {
              loc.attackScore = v;
              loc.attackTarget = l;
            }
            if (loc.attackScore < mscore) mscore = loc.attackScore;
          }
        }
      }
    } else if (bot.type == UnitType.MOPPER) {
      for (var tile : rc.senseNearbyMapInfos(bot.startPos, 8)) {
        if (!tile.isPassable() || !tile.getPaint().isEnemy()) continue;

        var mtile = tiles[tile.getMapLocation().x][tile.getMapLocation().y];
        var v = mtile != null && mtile.ruin != null ? 2 * MAP_PAINT_VALUE : MAP_PAINT_VALUE;

        for (var loc : locs) {
          if (v > loc.attackScore && loc.loc.isWithinDistanceSquared(tile.getMapLocation(), 2)) {
            loc.attackScore = v;
            loc.attackTarget = tile.getMapLocation();
            loc.attackMopSwing = null;
          }
        }
      }
    }
  }

  static void processAttacks(MicroLoc[] locs, MicroBot bot) throws GameActionException {
    var mopReady = bot.canAttack && bot.type == UnitType.MOPPER && bot.paint > 60;
    for (var unit : rc.senseNearbyRobots(bot.startPos, 8, rc.getTeam())) {
      for (var loc : locs) {
        if (unit.location.isWithinDistanceSquared(loc.loc, 2)) {
          if (mopReady && unit.type.isRobotType() && (unit.getType() != UnitType.MOPPER || unit.paintAmount < 40) && unit.paintAmount <= unit.type.paintCapacity - 10) {
            mopReady = false;
          } else if (unit.type.isRobotType() || bot.paint >= minPaint() + 50 || unit.paintAmount < 50) {
            loc.adjacentAllies += 1;
          }
        }
      }
    }

    processEnemies(locs, bot);
    processTiles(locs, bot);
    if (bot.type == UnitType.SPLASHER && bot.canAttack) {
      var startBt = Clock.getBytecodeNum();
      var startTurn = rc.getRoundNum();

      MicroHelper.processSplasherLocsGenHelper(bot.startPos, locs);

      var endBt = Clock.getBytecodeNum();
      var endTurn = rc.getRoundNum();
      var bt = GameConstants.ROBOT_BYTECODE_LIMIT * (endTurn - startTurn) + (endBt - startBt);
      rc.setIndicatorString("splasher scan: " + bt + "bt (" + startBt + "/" + startTurn + " - " + endBt + "/" + endTurn + ")");
    }
  }

  static ArrayDeque<MapLocation> recentLocs = new ArrayDeque<>();
  static int bfAttack;
  static int bfTarget;
  static int bfScore;
  static MapLocation lastTarget;
  static int rlCounter;

  static MicroLoc findBestLoc(MicroBot bot, boolean includeCenter) throws GameActionException {
    var locs = new MicroLoc[]{new MicroLoc(bot.startPos)};
    if (bot.canMove && bot.type.isRobotType()) {
      var locs_ = new MicroLoc[9];
      var nlocs = 0;
      var dirs = Direction.allDirections();
      for (int i = 0; i < 9; i++) {
        var dir = dirs[i];
        if (!includeCenter && dir == Direction.CENTER) continue;
        var l = bot.startPos.add(dir);
        if (dir == Direction.CENTER || (rc.canSenseLocation(l) && rc.senseMapInfo(l).isPassable() && !rc.canSenseRobotAtLocation(l))) {
          locs_[i] = new MicroLoc(l);
          nlocs += 1;
        }
      }
      locs = new MicroLoc[nlocs];
      int j = 0;
      for (int i = 0; i < 9; i++) {
        if (locs_[i] != null) {
          locs[j++] = locs_[i];
        }
      }
    }
    bfAttack = Clock.getBytecodeNum();
    var target = pickTarget(bot);
    if (target != null) {
      rc.setIndicatorLine(bot.startPos, target, 0, 0, 255);
    }
    bfTarget = Clock.getBytecodeNum();
    processAttacks(locs, bot);
    bfScore = Clock.getBytecodeNum();
    var pmul = bot.type == UnitType.MOPPER ? 2 : 1;
    MicroLoc best = null;
    var pTargetDist = target != null ? bot.startPos.distanceSquaredTo(target) : 0;
    var totalNearby = nearbyAllies.length + nearbyEnemies.length + 2;
    // 0.9-1.1? we can adjust numbers. they start out at 0-1 so
    var attackMult = ((double) nearbyAllies.length + 1) / totalNearby * 0.4 + 0.8;
    var defenseMult = ((double) nearbyEnemies.length + 1) / totalNearby * 0.4 + 0.8;
    for (var loc : locs) {
      var score = loc.attackScore * attackMult - loc.incomingDamage * defenseMult;
//      for (var i = 0; i < rtargets.length; i++) {
//        var target = rtargets[i];//.fun().get();
//        //if (target.isEmpty()) continue;
//        score -= target.distanceSquaredTo(loc.loc) * 0.1 * (double) (rtargets.length - i) / rtargets.length;
//      }
      if (target != null) {
        score -= (target.distanceSquaredTo(loc.loc) - pTargetDist) * 0.003;// * (double) (rtargets.length - i) / rtargets.length;
      }
      if (loc.attackScore == 0.0 && recentLocs.contains(loc.loc)) {
        score -= (rlCounter + 1) * 0.2;
      }
      var paint = rc.senseMapInfo(loc.loc).getPaint();
      if (paint.isEnemy())
        score -= 2 * pmul * FREE_PAINT_VALUE * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE);
      else if (paint == PaintType.EMPTY)
        score -= pmul * FREE_PAINT_VALUE * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE);
      score -= (paint.isEnemy() ? 2 : 1) * loc.adjacentAllies * FREE_PAINT_VALUE * 2 * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE); // both robots lose paint
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
    var loc = findBestLoc(new MicroBot(rc.getType(), rc.getLocation(), rc.isActionReady() && (rc.getPaint() >= minPaint() + rc.getType().attackCost || rc.getType() == UnitType.MOPPER), rc.isMovementReady(), rc.getPaint(), rc.getHealth()), true);
    if (!loc.loc.equals(rc.getLocation())) {
      if (recentLocs.contains(loc.loc)) rlCounter += 1;
      else rlCounter = 0;

      if (recentLocs.size() >= 12) recentLocs.removeFirst();
      recentLocs.addLast(rc.getLocation());

      if (rc.canMove(rc.getLocation().directionTo(loc.loc))) {
        rc.move(rc.getLocation().directionTo(loc.loc));
      }
    } else if (rc.isMovementReady()) {
      if (recentLocs.size() >= 12) recentLocs.removeFirst();
      recentLocs.addLast(rc.getLocation());
      rlCounter += 1;
      exploreTarget = null;
    }
    // always do this
    if (rc.getType() == UnitType.SOLDIER && rc.isActionReady() && rc.getPaint() >= minPaint() + rc.getType().attackCost && rc.senseMapInfo(rc.getLocation()).getPaint() == PaintType.EMPTY) {
      var s = map.tile(rc.getLocation()).secondary();
      rc.attack(rc.getLocation(), s);
    } else if (loc.attackScore > 0.0) {
      if (loc.attackMopSwing != null) {
        //System.out.println("swinging mop !!!!! " + loc.attackMopSwing + ": " + loc.attackScore);
        rc.mopSwing(loc.attackMopSwing);
      } else {
        //rc.setIndicatorString("" + loc.attackScore + ": " + loc.attackTarget);
        rc.setIndicatorDot(loc.attackTarget, 0, 0, 0);
        var s = map.tile(loc.attackTarget).secondary();
        if (rc.canAttack(loc.attackTarget)) {
          rc.attack(loc.attackTarget, s);
        }
        recentLocs.clear();
      }
    }
    var end = Clock.getBytecodeNum();
    rc.setIndicatorString("micro: " + (end - start) + "bt: " + (bfAttack - start) + ", " + (bfTarget - bfAttack) + ", " + (bfScore - bfTarget) + ", " + (end - bfScore));
    //rc.setIndicatorString("rlcounter: " + rlCounter);
  }
}



