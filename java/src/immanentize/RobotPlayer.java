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

  static final int SEED = -1918011995;
//  static final int SEED = -1713814911;

  static int rng() {
    // xorshift or whatever
    if (rng_acc == 0) {
      var id = rc.getID() ^ SEED;
      rng_acc = id ^ (id << 14);
    }

    rng_acc ^= rng_acc >> 7;
    rng_acc ^= rng_acc << 9;
    rng_acc ^= rng_acc >> 13;
    return Math.abs(rng_acc);
  }

  static int towerKeepPaint() {
    return 0;//rc.getNumberTowers() > 3 ? 0 : 100;
  }

  static final int MIN_TRANSFER_TOWER = 50;
  static final int MIN_TRANSFER_MOPPER = 10;

  static void maybeReplenishPaint() throws GameActionException {
    if (rc.getPaint() - minPaint() < (rc.getType().paintCapacity - minPaint()) * Micro.FREE_PAINT_TARGET) {
      if (rc.isActionReady()) {
        Arrays.stream(rc.senseNearbyRobots(2, rc.getTeam()))
            // TODO constant for this
            .filter(x -> x.getType().isTowerType() && x.paintAmount >= towerKeepPaint() + MIN_TRANSFER_TOWER)
            .findFirst()
            .ifPresent(x -> {
              // really annoying that we have to do this (since λ doesn't support throws)
              try {
                rc.transferPaint(x.location, -Math.min(rc.getType().paintCapacity - rc.getPaint(), x.paintAmount - towerKeepPaint()));
              } catch (GameActionException e) {
                throw new RuntimeException(e);
              }
            });
      }
    }
  }

  static Map map;
  static Comms comms;

  static int hash(MapLocation loc) {
    var v = (loc.x << 12 | loc.y) ^ (SEED ^ 0b110101110110101110);
    v ^= v >> 9;
    v ^= v << 13;
    return Math.abs(v);
  }

  static int upgradeTurns = 0;
  static UnitType toSpawn = null;
  static int turnsSinceSeenEnemy = 0;
  static boolean wantsToChangeTowerType = false;
  static int spawnCount = 0;
  static boolean panicMode = false;
  static MapLocation panicTarget = null;
  static int nearbyFriendlySoldiers;
  static int splasherStartTurn = -1;
  static int mopCount = 0;

  public static void run(RobotController rc) throws GameActionException {
    RobotPlayer.rc = rc;
    spawnCounter = rng() % SPAWN_LIST.size();
    // This is in [x][y], but the patterns are in [y][x], as far as i can tell. not that it should matter since they're rotationally symmetrical i think
    map = new Map();
    comms = new Comms();
    final MapLocation[] exploreLocs = {
        new MapLocation(0, 0),
        new MapLocation(rc.getMapWidth() - 1, 0),
        new MapLocation(0, rc.getMapHeight() - 1),
        new MapLocation(rc.getMapWidth() - 1, rc.getMapHeight() - 1),
    };

    while (true) {
      try {
        nearbyAllies = rc.senseNearbyRobots(GameConstants.VISION_RADIUS_SQUARED, rc.getTeam());
        nearbyEnemies = rc.senseNearbyRobots(GameConstants.VISION_RADIUS_SQUARED, rc.getTeam().opponent());
        if (nearbyEnemies.length == 0) turnsSinceSeenEnemy += 1;
        else turnsSinceSeenEnemy = 0;
        if (rc.getType().isTowerType()) {
          var nearbySoldiers = 0;
          var panicDist = 1000000;
          for (var i : rc.senseNearbyRobots(UnitType.SOLDIER.actionRadiusSquared, rc.getTeam().opponent())) {
            if (i.type == UnitType.SOLDIER) {
              nearbySoldiers += 1;
              var d = i.location.distanceSquaredTo(rc.getLocation());
              if (d < panicDist) {
                panicDist = d;
                panicTarget = i.location;
              }
            }
          }
          panicMode = nearbySoldiers >= 2 || (nearbySoldiers == 1 && rc.getHealth() < rc.getType().health / 3);

          nearbyFriendlySoldiers = 0;
          var mopCount = 0;
          for (var i : nearbyAllies) {
            if (i.type == UnitType.SOLDIER) {
              nearbyFriendlySoldiers += 1;
            }
            if (i.type == UnitType.MOPPER) {
              mopCount += 1;
            }
          }
          if (mopCount >= nearbySoldiers) {
            panicMode = false;
          }
        }

        map.update();
        var q = Clock.getBytecodeNum();
        comms.update();

//        if (rc.getType() == UnitType.SOLDIER && Micro.exploreTarget == null && rng() % 30 == 0) {
//          Micro.exploreTarget = exploreLocs[rng() % exploreLocs.length];
//        }
        // Making sure columns in view are initialized before the turn starts so we can optimize checks to `tiles[x][y]`
        for (int x = -4; x < 5; x++) {
          var loc = rc.getLocation().translate(x, 0);
          if (rc.onTheMap(loc)) {
            map.tile(loc);
          }
        }

        switch (rc.getType()) {
          case SOLDIER, SPLASHER -> {
            Micro.doMicro();

            maybeReplenishPaint();
          }
          case MOPPER -> {
            Micro.doMicro();

            // Resupply nearby soldiers/splashers
            // TODO put this in micro
            if (rc.getPaint() >= 60 && rc.isActionReady()) {
              for (var unit : rc.senseNearbyRobots(rc.getType().actionRadiusSquared, rc.getTeam())) {
                if (unit.getType().isRobotType() && (unit.getType() != UnitType.MOPPER || unit.paintAmount < 40) && unit.paintAmount < unit.getType().paintCapacity) {
                  var transfer = Math.min(rc.getPaint() - 50, unit.getType().paintCapacity - unit.paintAmount);
                  rc.transferPaint(unit.location, transfer);
                  break;
                }
              }
            }

            maybeReplenishPaint();
          }
          default -> {
            // Towers

            // Attack if possible
            Micro.doMicro();

            // Self upgrade if possible (for paint towers first)
            if (rc.getNumberTowers() > 2 && rc.canUpgradeTower(rc.getLocation())) {
              upgradeTurns += 1;
              if (upgradeTurns > 3 * rc.getType().level + rc.getType().moneyPerTurn / 5) {
                rc.upgradeTower(rc.getLocation());
              }
            } else {
              upgradeTurns = 0;
            }

            // Try to spawn a unit
            if (rc.getRoundNum() < 4) {
              toSpawn = UnitType.SOLDIER;
            }
            if (rc.getRoundNum() < 20 || /*spawnCount == 0 ||*/ rc.getChips() > 1200 || panicMode) {
              if (panicMode) toSpawn = UnitType.MOPPER;
              if (toSpawn == null && (nearbyAllies.length < 12)) {
                var splasherChance = 1.0 / 7.0;
                // after splashers have been ruled out
                var mopperChance = 1.0 / 5.0;
                // More splashers on bigger maps
                if (rc.getMapHeight() >= 40 && rc.getMapWidth() >= 40) {
                  splasherChance = 1.0 / 5.0;
                  //mopperChance = 1.0 / 3.0;
                }
                // More moppers on smaller maps
                if (rc.getMapHeight() <= 25 && rc.getMapWidth() <= 25) {
                  //ssplasherChance = 1.0 / 10.0;
                  mopperChance = 3.0 / 5.0;
                } else if (rc.getRoundNum() < 30) {
                  mopperChance = 0;
                }
                if (rc.getNumberTowers() < 3 || rc.getRoundNum() < 60) {
                  splasherChance = 0;
                }
                if (rc.getNumberTowers() > map.moneyTarget) {
                  if (splasherStartTurn == -1) splasherStartTurn = rc.getRoundNum();
//                  if (rc.getRoundNum() > splasherStartTurn + 20) {
                    splasherChance = 1 / 2.0;
                    mopperChance = 2 / 5.0;
//                  } else {
//                    splasherChance = 2 / 3.0;
//                    mopperChance = 1 / 2.0;
//                  }
                } else {
                  splasherStartTurn = -1;
                }
                if (!panicMode && mopCount >= Math.max(2, nearbyAllies.length / 3)) {
                  mopperChance = 0;
                }
//                if (mopCount == 0 && map.ruinTarget != null && map.ruinTarget.enemyTiles > 0) {
//                  mopperChance *= 3.0 / 2;
//                }
//                if (nearbyAllies.length > 4 && nearbyFriendlySoldiers <= 1) {
//                  splasherChance *= 0.8;
//                  mopperChance *= 0.8;
//                }
//                if (rc.getRoundNum() > 300) {
//                  mopperChance = 2.0 / 5.0;
//                  if (rc.getMapHeight() >= 40 && rc.getMapWidth() >= 40) {
//                    splasherChance = 1 / 4.0;
//                  } else {
//                    splasherChance = 1 / 5.0;
//                  }
//                }
//                if (rc.getRoundNum() > 600) {
//                  if (rc.getMapHeight() >= 40 && rc.getMapWidth() >= 40) {
//                    splasherChance = 1 / 3.0;
//                  } else {
//                    splasherChance = 1 / 4.0;
//                  }
//                }

                toSpawn = (double) (rng() % 100) < splasherChance * 100.0 ? UnitType.SPLASHER
                    : rng() % 100 < mopperChance * 100.0 ? UnitType.MOPPER
                    : UnitType.SOLDIER;
                rc.setIndicatorString("chance: " + splasherChance + " / " + mopperChance + " --> " + toSpawn + " (sample " + (rng() % 100) + ")");
              }
              if (toSpawn != null && rc.getPaint() >= toSpawn.paintCost + (panicMode ? 0 : towerKeepPaint())) {
                Direction bdir = null;
                var selfPaint = false;
                var panicDist = 100000;
                // Spawn on our paint if possible
                for (var dir : MOVE_DIRECTIONS) {
                  if (rc.canBuildRobot(toSpawn, rc.getLocation().add(dir))) {
                    var d = panicMode ? panicTarget.distanceSquaredTo(rc.getLocation().add(dir)) : 0;
                    var paint = rc.senseMapInfo(rc.getLocation().add(dir)).getPaint().isAlly();
                    if (bdir == null || d < panicDist || (d == panicDist && !selfPaint && paint)) {
                      bdir = dir;
                      selfPaint = paint;
                      panicDist = d;
                    }
                  }
                }
                if (bdir != null) {
                  rc.buildRobot(toSpawn, rc.getLocation().add(bdir));
                  spawnCount += 1;
                  toSpawn = null;
                }
              }
              if (toSpawn != null && rc.getType().paintPerTurn == 0 && rc.getPaint() < toSpawn.paintCost + (panicMode ? 0 : towerKeepPaint())) {
                toSpawn = null;
              }
            }

            if (rc.getType().getBaseType() == UnitType.LEVEL_ONE_DEFENSE_TOWER && turnsSinceSeenEnemy > 150) {
              wantsToChangeTowerType = true;
            }
            if (rc.getChips() > 40000 && rc.getNumberTowers() > 5 && rc.getType().getBaseType() == UnitType.LEVEL_ONE_MONEY_TOWER && rc.getID() % 3 == 0) {
              wantsToChangeTowerType = true;
            }
            if (rc.getChips() > 100000 && rc.getNumberTowers() > 5 && rc.getType().getBaseType() == UnitType.LEVEL_ONE_MONEY_TOWER && rc.getID() % 5 <= 1) {
              wantsToChangeTowerType = true;
            }
            if (wantsToChangeTowerType && !rc.senseMapInfo(rc.getLocation().add(Direction.NORTH)).getMark().isAlly()) {
              rc.disintegrate();
            }

            if (panicMode) rc.setIndicatorString("PANIC MODE");
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
