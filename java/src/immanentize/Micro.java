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

  static boolean doTowers() {
    return rc.getChips() >= UnitType.LEVEL_ONE_PAINT_TOWER.moneyCost * 0.7 || nearbyAllies.length == 0
        || !map.isPaintTower;
  }

  static MapLocation exploreTarget = null;

  record Target(MapLocation loc, double score) {
  }

  static Target pickTarget(MicroBot bot) throws GameActionException {
    // Paint resupply - URGENT
    if (map.closestPaintTower != null && bot.paint < minPaint() + bot.type.attackCost)
      return new Target(map.closestPaintTower.loc, 2.0);
    if (map.closestFriendlyTower != null && bot.paint < minPaint() + bot.type.attackCost)
      return new Target(map.closestFriendlyTower.loc, 2.0);
    if (map.closestFriendlyTower != null && bot.type == UnitType.SOLDIER && map.ruinTarget != null
        && map.ruinTarget.lastSent == -1
        && nearbyAllies.length == 0 && bot.paint < bot.type.attackCost * (24 - map.ruinTarget.allyTiles) + minPaint())
      return new Target(map.closestFriendlyTower.loc, 2.0);
//    if (map.closestFriendlyTower != null && bot.type == UnitType.SOLDIER && map.closestEnemyTower != null
//        && map.closestEnemyTower.lastSent == -1
//        && nearbyAllies.length == 0)
//      return new Target(map.closestFriendlyTower.loc, 2.0);

    // Help out panicking tower
    if (comms.panickingTower != null)
      return new Target(comms.panickingTower, 1);

    // Mopper: find nearby soldier
    if (rc.getType() == UnitType.MOPPER && rc.getPaint() >= 50 + MIN_TRANSFER_MOPPER) {
      RobotInfo best = null;
      for (var x : nearbyAllies) {
        if (x.getType().isRobotType() && x.getType() != UnitType.MOPPER && x.paintAmount < x.getType().paintCapacity) {
          if (best == null || x.paintAmount < best.paintAmount) {
            best = x;
          }
        }
      }
      if (best != null) {
        return new Target(best.location, 1.0);
      }
    }

    if (rc.getNumberTowers() > (map.moneyTarget + 1) / 2 && map.closestRP != null && bot.type == UnitType.SOLDIER && (bot.paint >= minPaint() + bot.type.attackCost)
        && map.isPaintTower && (!doTowers() || map.ruinTarget == null/* || (rc.getID() % 3 == 0 && rc.getNumberTowers() > 5)*/) && bot.startPos.isWithinDistanceSquared(map.closestRP.center, 8)) {
      return new Target(map.closestRP.center, 1.0);
    }

    // Soldier: target unpainted tower squares
    if (map.ruinTarget != null && doTowers() && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost) {
      MapLocation[] corners = {map.ruinTarget.center.translate(-2, -2), map.ruinTarget.center.translate(2, -2), map.ruinTarget.center.translate(-2, 2), map.ruinTarget.center.translate(2, 2)};
      for (var loc : corners) {
        if (!rc.canSenseLocation(loc)) continue;
        if (!rc.senseMapInfo(loc).getPaint().isAlly() || (rc.senseMapInfo(loc).getPaint() == PaintType.ALLY_SECONDARY) != map.tile(loc).secondary()) {
          return new Target(loc, 1.0);
        }
      }
    }

    if (map.ruinTarget != null && doTowers() && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost && rc.getLocation().isWithinDistanceSquared(map.ruinTarget.center, 8)) {
      var dx = rc.getLocation().x == map.ruinTarget.center.x ? (rc.getLocation().y > map.ruinTarget.center.y ? 1 : -1) : 0;
      var dy = rc.getLocation().y == map.ruinTarget.center.y ? (rc.getLocation().x > map.ruinTarget.center.x ? -1 : 1) : 0;
      return new Target(map.ruinTarget.center.translate(dx, dy), 1.0);
    }

    // Soldier: target nearly-full resource pattern
    if (rc.getNumberTowers() > (map.moneyTarget + 1) / 2 && map.closestRP != null && bot.type == UnitType.SOLDIER && (bot.paint >= minPaint() + bot.type.attackCost) && (!doTowers() || map.ruinTarget == null || (rc.getID() % 5 == 0 && map.isPaintTower))) {
      return new Target(map.closestRP.center, 1);
    }

    // Soldier: target ruins
    if (map.ruinTarget != null && bot.type == UnitType.SOLDIER
        && (rc.getID() % 5 != 0 || !map.isPaintTower/* || rc.getNumberTowers() <= 5*/)
        && bot.paint >= minPaint() + bot.type.attackCost
        && doTowers()) {
      return new Target(map.ruinTarget.center, 1);
    }

    // Soldier(/splasher?): target enemy towers
    if (map.closestEnemyTower != null && bot.type != UnitType.MOPPER && nearbyAllies.length > 1 && bot.paint >= minPaint() + bot.type.attackCost * 3) {
      return new Target(map.closestEnemyTower.loc, 1);
    }

    if (map.ruinTarget != null && bot.type != UnitType.SOLDIER
        //&& (map.ruinTarget.enemyTiles > 0 || rc.getID() % 4 == 0)
        && (rc.getID() % 4 <= 1 || !map.isPaintTower/* || rc.getNumberTowers() <= 5*/)
        /*&& doTowers()*/ && !map.ruinTarget.center.isWithinDistanceSquared(bot.startPos, 8)) {
      return new Target(map.ruinTarget.center, 1.0);
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
        return new Target(best, 1.0);
    }

    // Paint resupply - if nothing else important to do (for soldiers)
    if (map.closestPaintTower != null && bot.paint < 50 + rc.getType().attackCost) {
      return new Target(map.closestPaintTower.loc, 1.0);
    }

    // Exploration
    return pickExploreTarget(bot);
  }

  static Target pickExploreTarget(MicroBot bot) {
    if (exploreTarget == null || exploreTarget.isWithinDistanceSquared(bot.startPos, 2)) {
      exploreTarget = map.findUnvisitedTile();
    }
    if (exploreTarget == null || exploreTarget.isWithinDistanceSquared(bot.startPos, 2)) {
      exploreTarget = new MapLocation(rng() % rc.getMapWidth(), rng() % rc.getMapHeight());
    }
    return new Target(exploreTarget, 1);
  }

  record Attack(double score,
                MapLocation attackTarget,
                Direction attackMopSwing) {
  }

  static class MicroLoc {
    MapLocation loc;
    double incomingDamage;
    double adjacentAllies;
    double transferScore;
    Attack attack = new Attack(0.0, null, null);
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

    var mopSwingDamage = Math.min(5, bot.paint) * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
    var mopDamage = (Math.min(10, bot.paint) + 5) * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;

    for (var unit : nearbyEnemies) {
      // We can't attack it and it can't attack us
      if (unit.type.isTowerType() != targetTowers && (bot.type.isTowerType() == unit.type.isTowerType() && unit.type != UnitType.MOPPER))
        continue;
      for (var loc : locs) {
        if (attack && unit.type.isTowerType() == targetTowers && unit.location.isWithinDistanceSquared(loc.loc, attackDist)) {
          double score = 0.0;
          if (isMopper) {
            score = Math.min(10, unit.paintAmount) * (1 - (double) unit.paintAmount / unit.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE + mopReturn;
            // TODO this isn't exactly correct - want it to match processTiles(), or do all this there but that would be more bt
            if (rc.senseMapInfo(unit.location).getPaint().isEnemy()) score += MAP_PAINT_VALUE;
          } else if (bot.type != UnitType.SOLDIER || unit.health < unit.type.health / 2 || nearbyAllies.length > 0 /*|| unit.type.getBaseType() != UnitType.LEVEL_ONE_DEFENSE_TOWER*/) {
            score = (double) Math.min(damage, unit.health) * ((1 - (double) unit.health / unit.type.health) + HP_FIXED_VALUE) * HP_TOTAL_VALUE;
            if (damage >= unit.health) score += KILL_VALUE;
            //score *= tyValues[unit.type.ordinal()];
            score -= cost;
          }
          if (score > loc.attack.score) {
            loc.attack = new Attack(score, unit.location, null);
          }
        }
        if ((bot.type.isTowerType() != unit.getType().isTowerType() || unit.type == UnitType.MOPPER)
            && ((unit.type.attackStrength > 0 || unit.type == UnitType.MOPPER) && unit.location.isWithinDistanceSquared(loc.loc, unit.type.actionRadiusSquared))) {
          loc.incomingDamage += unit.type == UnitType.MOPPER ? mopDamage : (double) Math.min(bot.hp, unit.type.attackStrength) * ((1 - (double) bot.hp / bot.type.health) + HP_FIXED_VALUE) * HP_TOTAL_VALUE;
        } else if (unit.type == UnitType.MOPPER && unit.location.isWithinDistanceSquared(loc.loc, 8)) {
          // Mop swing distance
          loc.incomingDamage += mopSwingDamage;
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
          if (loc.mopSwingScores[i] > loc.attack.score) {
            loc.attack = new Attack(loc.mopSwingScores[i], null, Direction.cardinalDirections()[i]);
          }
        }
      }
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
            if (v > loc.attack.score && loc.loc.isWithinDistanceSquared(l, 9)) {
              loc.attack = new Attack(v, l, null);
            }
            if (loc.attack.score < mscore) mscore = loc.attack.score;
          }
        }
      }
    } else if (bot.type == UnitType.MOPPER) {
      for (var tile : rc.senseNearbyMapInfos(bot.startPos, 8)) {
        if (!tile.isPassable() || !tile.getPaint().isEnemy()) continue;

        var mtile = tiles[tile.getMapLocation().x][tile.getMapLocation().y];
        var v = mtile != null && mtile.ruin != null ? 2 * MAP_PAINT_VALUE :
            tile.getPaint().isSecondary() ? 1.5 * MAP_PAINT_VALUE : MAP_PAINT_VALUE;

        for (var loc : locs) {
          if (v > loc.attack.score && loc.loc.isWithinDistanceSquared(tile.getMapLocation(), 2)) {
            loc.attack = new Attack(v, tile.getMapLocation(), null);
          }
        }
      }
    }
  }

  static void processAttacks(MicroLoc[] locs, MicroBot bot) throws GameActionException {
    var mopReady = bot.canAttack && bot.type == UnitType.MOPPER && bot.paint > 50 + MIN_TRANSFER_MOPPER;
    for (var unit : rc.senseNearbyRobots(bot.startPos, 8, rc.getTeam())) {
      for (var loc : locs) {
        if (unit.location.isWithinDistanceSquared(loc.loc, 2)) {
          if (mopReady && unit.type.isRobotType() && (unit.getType() != UnitType.MOPPER || unit.paintAmount < 40) && unit.paintAmount <= unit.type.paintCapacity - MIN_TRANSFER_MOPPER) {
            //loc.transferScore = (bot.paint - 50) * (1 - (double) unit.paintAmount / unit.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
            mopReady = false;
          } else if (unit.type.isRobotType() || bot.paint >= minPaint() + MIN_TRANSFER_TOWER || unit.paintAmount < towerKeepPaint() + MIN_TRANSFER_TOWER) {
            loc.adjacentAllies += 1;
          } else {
            // transfer target
            loc.transferScore = (unit.paintAmount - towerKeepPaint()) * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
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
  static Target microTarget;
  static int rlCounter;

  static Attack stayAttack;
  static int exploreTurns = 0;

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
    if (microTarget.loc != null) {
      rc.setIndicatorLine(bot.startPos, microTarget.loc, 0, 0, 255);
      if (!microTarget.loc.equals(lastTarget)) {
        lastTarget = microTarget.loc;
        recentLocs.clear();
      }
    }
    bfTarget = Clock.getBytecodeNum();
    processAttacks(locs, bot);
    bfScore = Clock.getBytecodeNum();
    var pmul = bot.type == UnitType.MOPPER ? 2 : 1;
    MicroLoc best = null;
    var pTargetDist = microTarget.loc != null ? bot.startPos.distanceSquaredTo(microTarget.loc) : 0;
    var totalNearby = nearbyAllies.length + nearbyEnemies.length + 2;
    // 0.9-1.1? we can adjust numbers. they start out at 0-1 so
    var attackMult = ((double) nearbyAllies.length + 1) / totalNearby * 0.4 + 0.8;
    var defenseMult = ((double) nearbyEnemies.length + 1) / totalNearby * 0.4 + 0.8;
    stayAttack = new Attack(0.0, null, null);
    for (var loc : locs) {
      if (loc.loc.equals(bot.startPos)) {
        stayAttack = loc.attack;
      }
    }

    if (locs.length == 1) return locs[0];

    var tmult = 0.1;//rc.getRoundNum() > 400 ? 0.01 : 0.1;
    for (var loc : locs) {
      var score = Math.max(loc.attack.score, stayAttack.score) * attackMult - loc.incomingDamage * defenseMult;
      if (microTarget.loc != null) {
        score -= (microTarget.loc.distanceSquaredTo(loc.loc) - pTargetDist) * tmult * microTarget.score;// * (double) (rtargets.length - i) / rtargets.length;
        //score -= Math.sqrt(target.distanceSquaredTo(loc.loc)) * tmult;
      }
      if (loc.attack.score == 0.0 && stayAttack.score == 0.0 && recentLocs.contains(loc.loc)) {
        score -= (rlCounter + 1) * 0.2;
        if (loc.loc.equals(bot.startPos)) {
          score -= (rlCounter + 1) * 0.05;
        }
        //score -= 10 * tmult;
        rc.setIndicatorDot(loc.loc, 0, 255, 0);
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

  static void doAttack(Attack attack) throws GameActionException {
    // always do this
    if (rc.getType() == UnitType.SOLDIER && rc.isActionReady() && rc.getPaint() >= minPaint() + rc.getType().attackCost && rc.senseMapInfo(rc.getLocation()).getPaint() == PaintType.EMPTY) {
      var s = map.tile(rc.getLocation()).secondary();
      rc.attack(rc.getLocation(), s);
    } else if (attack.score > 0.0) {
      if (attack.attackMopSwing != null) {
        rc.mopSwing(attack.attackMopSwing);
      } else {
        rc.setIndicatorDot(attack.attackTarget, 0, 0, 0);
        var s = map.tile(attack.attackTarget).secondary();
        if (rc.canAttack(attack.attackTarget)) {
          rc.attack(attack.attackTarget, s);
        }
      }
      recentLocs.clear();
    }
  }

  static void doMicro() throws GameActionException {
    var bot = new MicroBot(rc.getType(), rc.getLocation(), rc.isActionReady() && rc.getPaint() > 0 && (rc.getPaint() >= minPaint() + rc.getType().attackCost || rc.getType() == UnitType.MOPPER), rc.isMovementReady() && !rc.getType().isTowerType(), rc.getPaint(), rc.getHealth());
    var doPathfind = nearbyEnemies.length == 0 && bot.canMove &&
        (map.ruinTarget == null || !map.ruinTarget.center.isWithinDistanceSquared(rc.getLocation(), 8)
            || (map.ruinTarget.allyTiles < 24 && rc.getPaint() < minPaint() + rc.getType().attackCost));
    if (exploreTurns > 0) {
      microTarget = pickExploreTarget(bot);
      exploreTurns -= 1;
    } else {
      microTarget = pickTarget(bot);
    }

    if (doPathfind) {
      rlCounter = 0;
      PathHelper.targetMove(microTarget.loc);
      bot = new MicroBot(rc.getType(), rc.getLocation(), rc.isActionReady() && rc.getPaint() > 0 && (rc.getPaint() >= minPaint() + rc.getType().attackCost || rc.getType() == UnitType.MOPPER), false, rc.getPaint(), rc.getHealth());
    }

    var start = Clock.getBytecodeNum();
    if (rc.getType().isTowerType()) {
      rc.attack(null);
    }
    var loc = findBestLoc(bot, true);
    if (!loc.loc.equals(rc.getLocation())) {
      if (recentLocs.contains(loc.loc)) rlCounter += 1;
      else {
        if (recentLocs.size() >= 12) {
          recentLocs.removeFirst();
        }
        recentLocs.addLast(loc.loc);
        rlCounter = Math.max(0, rlCounter - 5);
      }

      // Attack before moving if advantageous
      if (stayAttack.score > 0.0 && stayAttack.score > loc.attack.score) {
        doAttack(stayAttack);
      }

      if (rc.canMove(rc.getLocation().directionTo(loc.loc))) {
        rc.move(rc.getLocation().directionTo(loc.loc));
      }
    } else if (rc.isMovementReady() && !doPathfind) {
      rlCounter += 1;
      exploreTarget = null;
      if (rlCounter > 20) {
        exploreTurns = 10;
      }
    }
    // always do this
    if (rc.isActionReady()) {
      doAttack(loc.attack);
    }
    var end = Clock.getBytecodeNum();
    if (!doPathfind) {
      rc.setIndicatorString("micro: " + (end - start) + "bt: " + (bfAttack - start) + ", " + (bfTarget - bfAttack) + ", " + (bfScore - bfTarget) + ", " + (end - bfScore));
    }
    //rc.setIndicatorString("rlcounter: " + rlCounter + " / " + exploreTurns);
  }
}



