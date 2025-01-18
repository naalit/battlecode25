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
  static final double HP_TOTAL_VALUE = 3.0;
  static final double HP_FIXED_VALUE = 0.5;
  static final double KILL_VALUE = 2000.0;
  static final double MAP_PAINT_VALUE = 15.0;
  // This number is multiplied by both the fixed and variable components, then they're added together
  // So for FREE_PAINT_FIXED_VALUE = 1.0, the max value is FREE_PAINT_VALUE * 2
  static final double FREE_PAINT_VALUE = 2.0;
  static final double FREE_PAINT_FIXED_VALUE = 0.5;
  static final int SOLDIER_PAINT_MIN = 15;
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
    return rc.getChips() >= UnitType.LEVEL_ONE_PAINT_TOWER.moneyCost * 0.7;
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
      new TargetType(() -> Optional.ofNullable(closestResource).filter(x -> rc.getType() == UnitType.SOLDIER && (rc.getPaint() >= minPaint() + rc.getType().attackCost) && (!doTowers() || map.ruinTarget == null) && rc.getLocation().isWithinDistanceSquared(x, 8))),
      // Soldier: target mark location
      //new TargetType(() -> Optional.ofNullable(map.ruinTarget).map(x -> x.center.translate(0, 1)).filter(x -> rc.getType() == UnitType.SOLDIER && rc.getID() % 3 != 0 && rc.getPaint() >= minPaint() + rc.getType().attackCost && !map.tile(x).isInRuin())),
      // Soldier: target unpainted tower squares
      new TargetType(() -> Optional.ofNullable(map.ruinTarget).filter(x -> doTowers() && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost).flatMap(ruin -> {
        Optional<MapLocation> target = Optional.empty();
        try {
          MapLocation[] corners = {ruin.center.translate(-2, -2), ruin.center.translate(2, -2), ruin.center.translate(-2, 2), ruin.center.translate(2, 2)};
          for (var loc : corners) {
            if (!rc.canSenseLocation(loc)) continue;
            if (!rc.senseMapInfo(loc).getPaint().isAlly() || (rc.senseMapInfo(loc).getPaint() == PaintType.ALLY_SECONDARY) != map.tile(loc).secondary()) {
              target = Optional.of(loc);
              break;
            }
          }
        } catch (GameActionException e) {
          throw new RuntimeException(e);
        }
        return target;
      })),
      new TargetType(() -> Optional.ofNullable(map.ruinTarget).filter(x -> doTowers() && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost && rc.getLocation().isWithinDistanceSquared(x.center, 8)).map(r -> {
        var dx = rc.getLocation().x == r.center.x ? (rc.getLocation().y > r.center.y ? 1 : -1) : 0;
        var dy = rc.getLocation().y == r.center.y ? (rc.getLocation().x > r.center.x ? -1 : 1) : 0;
        return r.center.translate(dx, dy);
      })),
//      new TargetType(() -> Optional.ofNullable(map.ruinTarget).filter(x -> doTowers() && rc.getType() == UnitType.SOLDIER && rc.getPaint() >= minPaint() + rc.getType().attackCost).flatMap(ruin -> {
//        Optional<MapLocation> target = Optional.empty();
//        try {
//          a:
//          for (int x = -2; x < 3; x++) {
//            for (int y = -2; y < 3; y++) {
//              var loc = ruin.center.translate(x, y);
//              if ((x == 0 && y == 0) || !rc.canSenseLocation(loc)) continue;
//              if (!rc.senseMapInfo(loc).getPaint().isAlly() || (rc.senseMapInfo(loc).getPaint() == PaintType.ALLY_SECONDARY) != map.tile(loc).secondary()) {
//                target = Optional.of(loc);
//                break a;
//              }
//            }
//          }
//        } catch (GameActionException e) {
//          throw new RuntimeException(e);
//        }
//        return target;
//      })),
      // Soldier: target nearly-full resource pattern
      new TargetType(() -> Optional.ofNullable(closestResource).filter(x -> rc.getType() == UnitType.SOLDIER && (rc.getPaint() >= minPaint() + rc.getType().attackCost) && (!doTowers() || map.ruinTarget == null))),
      // || closestResourceSquaresLeft == 0))),// && closestResourceSquaresLeft < 12)),
      // Soldier: target ruins
      new TargetType(() -> Optional.ofNullable(map.ruinTarget)
          .map(x -> x.center)
          .filter(x -> rc.getType() == UnitType.SOLDIER
              && rc.getID() % 3 != 0
              && rc.getPaint() >= minPaint() + rc.getType().attackCost
              && doTowers())),
      // Soldier(/splasher?): target enemy towers
      //new TargetType(() -> Optional.ofNullable(map.closestEnemyTower).map(x -> x.loc).filter(x -> rc.getType() != UnitType.MOPPER && rc.getID() % 3 != 0 && rc.getPaint() >= minPaint() + rc.getType().attackCost)),
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
    var attackDist = bot.type.actionRadiusSquared;
    var damage = bot.type.attackStrength;
    var attack = (damage > 0 || isMopper) && bot.canAttack;
    var cost = bot.type.attackCost * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE;
    for (var unit : nearbyEnemies) {
      if (unit.getType().isTowerType() != targetTowers && (bot.type.isTowerType() != unit.getType().isTowerType() || unit.type != UnitType.MOPPER))
        continue;
      for (var loc : locs) {
        if (attack && unit.getType().isTowerType() == targetTowers && unit.location.isWithinDistanceSquared(loc.loc, attackDist)) {
          rc.setIndicatorDot(unit.location, 255, 0, 255);
          var score = (double) Math.min(damage, unit.health) * ((1 - (double) unit.health / unit.type.health) + HP_FIXED_VALUE) * HP_TOTAL_VALUE;
          if (damage >= unit.health) score += KILL_VALUE;
          //score *= tyValues[unit.type.ordinal()];
          if (isMopper) {
            var paint = Math.min(10, unit.paintAmount) * (1 - (double) unit.paintAmount / unit.type.paintCapacity);
            if (rc.senseMapInfo(unit.location).getPaint().isEnemy()) paint += 1;
            // TODO verify that this is true (5 paint to our team regardless of amount of paint stolen) (a dev said they think this is how it works)
            score = (paint + Math.min(5, bot.type.paintCapacity - bot.paint) * (1 - (double) bot.paint / bot.type.paintCapacity) + HP_TOTAL_VALUE) * FREE_PAINT_VALUE;
          }
          score -= cost;
          if (score > loc.attackScore) {
            loc.attackScore = score;
            loc.attackTarget = unit.location;
          }
        }
        if ((bot.type.isTowerType() != unit.getType().isTowerType() || unit.type == UnitType.MOPPER)
            && ((unit.type.attackStrength > 0 || unit.type == UnitType.MOPPER) && unit.location.isWithinDistanceSquared(loc.loc, unit.type.actionRadiusSquared))) {
          loc.incomingDamage += unit.type == UnitType.MOPPER ? (Math.min(10, bot.paint) + 5) * (1 - (double) bot.paint / bot.type.paintCapacity + FREE_PAINT_FIXED_VALUE) * FREE_PAINT_VALUE : (double) Math.min(bot.hp, unit.type.attackStrength) * ((1 - (double) bot.hp / bot.type.health) + HP_FIXED_VALUE) * HP_TOTAL_VALUE;
          ;
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

      for (var tile : rc.senseNearbyMapInfos(bot.startPos, 18)) {
        if (!tile.isPassable()) continue;
        var p = tile.getPaint();
        var v = 0.0;
        if (p.isEnemy()) continue;

//        var mtile = tiles[tile.getMapLocation().x][tile.getMapLocation().y];
//        if (p == PaintType.EMPTY) v = mtile != null && mtile.ruin != null ? 2 * MAP_PAINT_VALUE : MAP_PAINT_VALUE;
//        else {
//          var s = mtile != null && mtile.ruin != null ? mtile.ruin.secondary(tile.getMapLocation())
//              : map.resourcePattern[tile.getMapLocation().x % 4][tile.getMapLocation().y % 4];
//          if (s != p.isSecondary()) v = mtile != null && mtile.ruin != null ? 2 * MAP_PAINT_VALUE : MAP_PAINT_VALUE;
//        }
        if (map.ruinTarget != null && map.ruinTarget.center.isWithinDistanceSquared(tile.getMapLocation(), 8)) {
          if (p == PaintType.EMPTY || p.isSecondary() != map.tile(tile.getMapLocation()).secondary())
            v = 2 * MAP_PAINT_VALUE;
        } else if (closestResource != null && closestResource.isWithinDistanceSquared(tile.getMapLocation(), 8)) {
          if (p == PaintType.EMPTY || p.isSecondary() != map.tile(tile.getMapLocation()).secondary())
            v = 1.5 * MAP_PAINT_VALUE;
        } else if (p == PaintType.EMPTY) {
          v = MAP_PAINT_VALUE;
        }
        v -= cost;

        if (v > mscore) {
          mscore = 1000.0;
          for (var loc : locs) {
            if (v > loc.attackScore && loc.loc.isWithinDistanceSquared(tile.getMapLocation(), 9)) {
              loc.attackScore = v;
              loc.attackTarget = tile.getMapLocation();
            }
            if (loc.attackScore < mscore) mscore = loc.attackScore;
          }
        }
      }

    } else if (bot.type == UnitType.MOPPER) {
      for (var tile : rc.senseNearbyMapInfos(bot.startPos, 8)) {
        if (!tile.isPassable()) continue;
        var p = tile.getPaint();
        if (!p.isEnemy()) continue;

        var mtile = tiles[tile.getMapLocation().x][tile.getMapLocation().y];
        var v = mtile != null && mtile.ruin != null ? 2 * MAP_PAINT_VALUE : MAP_PAINT_VALUE;

        for (var loc : locs) {
          if (v > loc.attackScore && loc.loc.isWithinDistanceSquared(tile.getMapLocation(), 2)) {
            loc.attackScore = v;
            loc.attackTarget = tile.getMapLocation();
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
          if (mopReady && unit.type.isRobotType() && unit.type != UnitType.MOPPER && unit.paintAmount < unit.type.paintCapacity) {
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
      //processSplasherAttack(locs, bot);

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
          recentLocs.add(bot.startPos);
        }
        lastTarget = target;
        rc.setIndicatorLine(bot.startPos, target, 0, 0, 255);
      }
    }
//    var target = Arrays.stream(targets).flatMap(x -> x.fun().get().stream()).findFirst();
    bfScore = Clock.getBytecodeNum();
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
    var loc = findBestLoc(new MicroBot(rc.getType(), rc.getLocation(), rc.isActionReady() && rc.getPaint() >= minPaint() + rc.getType().attackCost, rc.isMovementReady(), rc.getPaint(), rc.getHealth()), true);
    if (!loc.loc.equals(rc.getLocation())) {
      if (recentLocs.contains(loc.loc)) rlCounter += 1;
      else rlCounter = 0;

      if (recentLocs.size() >= 12) recentLocs.removeFirst();
      recentLocs.addLast(rc.getLocation());

      rc.move(rc.getLocation().directionTo(loc.loc));
    } else if (rc.isMovementReady()) {
      if (recentLocs.size() >= 12) recentLocs.removeFirst();
      recentLocs.addLast(rc.getLocation());
      rlCounter += 1;
      exploreTarget = null;
    }
    // always do this
    if (rc.isActionReady() && rc.getPaint() >= minPaint() + rc.getType().attackCost && rc.senseMapInfo(rc.getLocation()).getPaint() == PaintType.EMPTY && rc.getType() == UnitType.SOLDIER) {
      var s = map.tile(rc.getLocation()).secondary();
      rc.attack(rc.getLocation(), s);
    } else if (loc.attackScore > 0.0) {
      //rc.setIndicatorString("" + loc.attackScore + ": " + loc.attackTarget);
      rc.setIndicatorDot(loc.attackTarget, 0, 0, 0);
      var s = map.tile(loc.attackTarget).secondary();
      rc.attack(loc.attackTarget, s);
      recentLocs.clear();
    }
    var end = Clock.getBytecodeNum();
    rc.setIndicatorString("micro: " + (end - start) + "bt: " + (bfAttack - start) + ", " + (bfTarget - bfAttack) + ", " + (bfScore - bfTarget) + ", " + (end - bfScore));
    //rc.setIndicatorString("rlcounter: " + rlCounter);
  }
}



