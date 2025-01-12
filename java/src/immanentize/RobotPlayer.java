package immanentize;

import battlecode.common.*;

import java.util.*;

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
    MapLocation best = null;
    for (var tile : rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared)) {
      if (!canPaint(tile)) continue;
      if (best == null) {
        best = tile.getMapLocation();
      } else if (map.tile(tile.getMapLocation()).isInRuin() != map.tile(best).isInRuin()) {
        if (map.tile(tile.getMapLocation()).isInRuin()) {
          best = tile.getMapLocation();
        }
      } else if (tile.getMapLocation().distanceSquaredTo(loc) < best.distanceSquaredTo(loc)) {
        best = tile.getMapLocation();
      }
    }
    if (best != null) {
      rc.attack(best, map.tile(best).secondary());
    }
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

    // TODO per-tower
    if (map.ruinTarget != null && !map.ruinTarget.center.equals(sentRuin)) {
      for (var i : nearbyAllies) {
        if (i.getType().isTowerType() && rc.canSendMessage(i.location)) {
          rc.sendMessage(i.location, encode(map.ruinTarget.center));
          sentRuin = map.ruinTarget.center;
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

        map.update();

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
            checkRuins();

            // Remove enemy paint
            MapLocation toMop = null;
            var isInRuin = false;
            if (rc.isActionReady()) {
              for (var tile : rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared)) {
                if (tile.getPaint().isEnemy() && rc.canAttack(tile.getMapLocation())) {
                  var ruin = map.ruinTarget != null && map.ruinTarget.center.isWithinDistanceSquared(tile.getMapLocation(), 8);
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
            if ((rc.getNumberTowers() > 2 || map.ruinTarget == null) && (rc.getRoundNum() < 50 || rc.getChips() > 1300)) {
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
              map.tryAddRuin(l);
            }
//            var nSent = 0;
//            if (map.ruinTarget != null) {
//              for (var i : nearbyAllies) {
//                if (i.location.isWithinDistanceSquared(rc.getLocation(), GameConstants.MESSAGE_RADIUS_SQUARED) && rc.canSendMessage(i.location)) {
//                  rc.sendMessage(i.location, encode(map.ruinTarget));
//                  if (++nSent >= GameConstants.MAX_MESSAGES_SENT_TOWER) {
//                    break;
//                  }
//                }
//              }
//            } else
            if (exploreIdx < exploreLocs.length) {
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
