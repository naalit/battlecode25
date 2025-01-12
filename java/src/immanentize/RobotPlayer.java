package immanentize;

import battlecode.common.*;

import java.util.*;
import java.util.function.Supplier;

import static immanentize.Micro.minPaint;

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

  static void maybeReplenishPaint() throws GameActionException {
    if (rc.getPaint() - minPaint() < (rc.getType().paintCapacity - minPaint()) * Micro.FREE_PAINT_TARGET) {
      if (rc.isActionReady()) {
        Arrays.stream(rc.senseNearbyRobots(2, rc.getTeam()))
            // TODO constant for this
            .filter(x -> x.getType().isTowerType() && x.paintAmount > (x.getType().paintPerTurn > 0 ? 200 : 0))
            .findFirst()
            .ifPresent(x -> {
              // really annoying that we have to do this (since λ doesn't support throws)
              try {
                rc.transferPaint(x.location, -Math.min(rc.getType().paintCapacity - rc.getPaint(), x.paintAmount - (x.getType().paintPerTurn > 0 ? 200 : 0)));
              } catch (GameActionException e) {
                throw new RuntimeException(e);
              }
            });
      }
    }
  }

  static Map map;

  static MapLocation closestPaintTower;
  static MapLocation closestTower;
  static MapLocation ruinTarget;
  static MapLocation closestResource;
  static int closestResourceSquaresLeft;

  static boolean canPaint(MapInfo tile) throws GameActionException {
    return (tile.getPaint() == PaintType.EMPTY ||
        (tile.getPaint().isAlly() && (tile.getPaint() == PaintType.ALLY_SECONDARY) != map.tile(tile.getMapLocation()).secondary()))
        && tile.isPassable() && !tile.hasRuin();
  }

  static void doPaint() throws GameActionException {
    if (rc.getPaint() < minPaint() + rc.getType().attackCost || !rc.isActionReady()) return;
    // smaller is better
    var loc = rc.getLocation();
    Comparator<MapInfo> comp = Comparator.comparingInt(x -> ruinTarget != null && ruinTarget.isWithinDistanceSquared(x.getMapLocation(), 4) ? 0 : 1);
    comp = comp.thenComparingInt(x -> map.hasOverlay(x.getMapLocation()) ? 0 : 1);
    comp = comp.thenComparingInt(x -> x.getMapLocation().distanceSquaredTo(loc));
//    if (target != null) {
//      comp = comp.thenComparingInt(x -> x.getMapLocation().distanceSquaredTo(target));
//    }
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
            rc.attack(x.getMapLocation(), map.tile(x.getMapLocation()).secondary());
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

  static int hash(MapLocation loc) {
    var v = (loc.x << 12 | loc.y) ^ 0b110101110110101110;
    v ^= v >> 9;
    v ^= v << 13;
    return v;
  }

  static MapLocation sentRuin = null;

  static void checkRuins() throws GameActionException {
    var income = rc.getChips() - lastMoney;
    lastMoney = rc.getChips();
    if (incomeFrames.size() > 12) {
      incomeFrames.removeFirst();
    }
    incomeFrames.addLast(income);

    // Also check resource patterns
    var rpCenter = map.findRPCenter(rc.getLocation());
    var onTheMap = rc.onTheMap(rpCenter.translate(-2, -2)) && rc.onTheMap(rpCenter.translate(2, 2));
    if (onTheMap && rc.canSenseLocation(rpCenter)) {
      rc.setIndicatorDot(rpCenter, 0, 0, 255);
      if (rpCenter.equals(closestResource)) {
        closestResource = null;
      }
      if (rc.canCompleteResourcePattern(rpCenter)) {
        rc.completeResourcePattern(rpCenter);
        map.tile(rpCenter).lastCheckedRP = rc.getRoundNum();
//        if (rc.canMark(rpCenter)) {
//          rc.mark(rpCenter, true);
//        }
      } else if (rc.getType() == UnitType.SOLDIER && map.tile(rpCenter).lastCheckedRP < Math.max(rc.getRoundNum() - 25, 1)) {
        // we can complete this pattern! let's see if it's possible
        var pLeft = 25;
        for (var tile : rc.senseNearbyMapInfos(rpCenter, 8)) {
          // this should be exactly the correct tiles (bc 0,3 has r^2 9)
          if (!tile.isPassable() || tile.getPaint().isEnemy() || !map.tile(tile.getMapLocation()).rpCompatible()) {
            pLeft = -1;
            break;
          }
          if (tile.getPaint().isAlly() && tile.getPaint().isSecondary() == map.tile(tile.getMapLocation()).secondary()) {
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
          map.tile(rpCenter).lastCheckedRP = rc.getRoundNum();
        }
      }
      if (closestResource != null) {
        rc.setIndicatorDot(closestResource, 255, 255, 255);
        //rc.setIndicatorString("left: " + closestResourceSquaresLeft);
      }
    }

//    for (var m : rc.readMessages(-1)) {
//      var loc = decode(m.getBytes());
//      if ((m.getBytes() & 1 << 13) != 0) {
//        exploreTarget = loc;
//      } else {
//        ruinTarget = loc;
//        rc.setIndicatorDot(ruinTarget, 255, 0, 0);
//      }
//    }

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
//    if (ruinTarget == null || !rc.canSenseLocation(ruinTarget)) {
    a:
    for (var ruin : rc.senseNearbyRuins(-1)) {
      if (rc.senseRobotAtLocation(ruin) != null) continue;
      for (MapInfo patternTile : rc.senseNearbyMapInfos(ruin, 8)) {
        if (patternTile.getPaint().isEnemy()) {
          continue a;
        }
      }
      // Okay we pick this ruin
      if (cRuinTarget == null || ruin.isWithinDistanceSquared(rc.getLocation(), cRuinTarget.distanceSquaredTo(rc.getLocation()))) {
        cRuinTarget = ruin;
      }
    }
//    }

//    if (ruinTarget == null) {
    ruinTarget = cRuinTarget;
//    }

    if (cRuinTarget != null && rc.canSenseLocation(cRuinTarget)) {
      rc.setIndicatorDot(cRuinTarget, 0, 0, 0);
      // primary = money, secondary = paint
//      var avgIncome = incomeFrames.stream().reduce((x, y) -> x + y).get() / incomeFrames.size();
//      var numTowers = rc.getNumberTowers();
//      // target 25 chips/tower?
//      var percent = (double) avgIncome / ((double) numTowers * 25.0) * 100.0;
      // just hardcoding 1/2 money towers for now (after the first money tower)
      var type = (hash(cRuinTarget) % 2) == 0 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
      if (rc.getChips() > 10000) type = UnitType.LEVEL_ONE_PAINT_TOWER;
      // money tower first ?
      type = rc.getNumberTowers() <= 2 ? UnitType.LEVEL_ONE_MONEY_TOWER : type;
      if (rc.getRoundNum() >= 150 && rc.getNumberTowers() > 2 && (hash(cRuinTarget) % 5) == 0)
        type = UnitType.LEVEL_ONE_DEFENSE_TOWER;

      if (!map.hasOverlay(cRuinTarget.translate(1, 1))) {
        // Put in the pattern
        var pattern = rc.getTowerPattern(type);
        for (int x = 0; x < 5; x++) {
          for (int y = 0; y < 5; y++) {
            map.tile(cRuinTarget.translate(-2 + x, -2 + y)).secondary = Optional.of(pattern[y][x]);
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
    map = new Map();
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
              if (map.hasOverlay(i.location.translate(-1, -1))) {
                // remove pattern
                for (var x = -2; x <= 2; x++) {
                  for (var y = -2; y <= 2; y++) {
                    map.tile(i.location.translate(x, y)).secondary = Optional.empty();
                  }
                }
              }
            }
          }
        }
        if (closestPaintTower != null && rc.canSenseLocation(closestPaintTower) && !rc.canSenseRobotAtLocation(closestPaintTower))
          closestPaintTower = null;

        switch (rc.getType()) {
          case SOLDIER -> {
            Micro.doMicro();

            // Try to build ruins
            checkRuins();

            maybeReplenishPaint();

            // Try to paint a square in range
            doPaint();
          }
          case SPLASHER -> {
            Micro.doMicro();

            // Try to build ruins
            checkRuins();

            maybeReplenishPaint();
          }
          case MOPPER -> {
            Micro.doMicro();

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
                if (patternTile.getPaint().isEnemy()) {
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
                if (tile.getPaint().isEnemy() && rc.canAttack(tile.getMapLocation())) {
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
          }
          default -> {
            // Towers

            // Attack if possible
            Micro.doMicro();

            // Self upgrade if possible (for money towers first)
            if (rc.getNumberTowers() > 2 && rc.canUpgradeTower(rc.getLocation())) {
              upgradeTurns += 1;
//              if (upgradeTurns > 2 || rc.getType().moneyPerTurn > 0) {
              rc.upgradeTower(rc.getLocation());
//              }
            } else {
              upgradeTurns = 0;
            }

            // Try to spawn a unit
            if (rc.getRoundNum() < 4) {
              toSpawn = UnitType.SOLDIER;
            }
            if ((rc.getNumberTowers() > 2 || ruinTarget == null) && (rc.getRoundNum() < 50 || rc.getChips() > 1300)) {
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
                  mopperChance = 3.0 / 5.0;
                }
                if (rc.getNumberTowers() < 3 || rc.getRoundNum() < 100) {
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
