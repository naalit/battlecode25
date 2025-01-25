package immanentize;

import battlecode.common.*;

import java.util.ArrayList;

import static immanentize.RobotPlayer.*;

public class Map {
  public class Ruin {
    public MapLocation center;
    public int allyTiles;
    public int enemyTiles;
    public boolean clearEnemyTilesOnSeen = false;
    public int roundSeen;
    public UnitType type;
    public int typeRound;
    public int lastSent = -1;

    public Ruin(MapLocation loc) {
      center = loc;
    }

    public UnitType type() throws GameActionException {
      if (typeRound == rc.getRoundNum()) return type;
      typeRound = rc.getRoundNum();

      var paintPercent = rc.getNumberTowers() < moneyTarget + 1 ? 0 : 70;
//      if (rc.getNumberTowers() < moneyTarget + 1) {
//        type = UnitType.LEVEL_ONE_MONEY_TOWER;
//      } else {
//        type = UnitType.LEVEL_ONE_PAINT_TOWER;
//      }
//      var big = rc.getMapHeight() >= 30 || rc.getMapWidth() >= 30;
//      var big2 = rc.getMapHeight() >= 35 && rc.getMapWidth() >= 35;
//
//      var paintPercent = big ? 20 : 30;
//      if (!map.isPaintTower) paintPercent = 100;
//      // money tower first ?
//      if (rc.getNumberTowers() <= (big ? big2 ? 5 : 4 : 2)) paintPercent = 0;
//      // then paint tower
//      if (rc.getNumberTowers() == 3 && !big) paintPercent = 100;
//      if (rc.getChips() > 10000) paintPercent = 100;

      if (!map.isPaintTower && rc.getNumberTowers() > (moneyTarget + 1) / 2) {
        paintPercent = 80;
      }

      type = (hash(center) % 100) >= paintPercent ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;

      if (rc.getNumberTowers() > (moneyTarget + 1) / 2 && rc.canSenseLocation(center.add(Direction.NORTH)) && rc.senseMapInfo(center.add(Direction.NORTH)).getMark().isSecondary()) {
        type = UnitType.LEVEL_ONE_DEFENSE_TOWER;
      }
      return type;
    }

    public boolean secondary(MapLocation loc) throws GameActionException {
      if (typeRound != rc.getRoundNum()) type();
      return rc.getTowerPattern(type)[loc.y - (center.y - 2)][loc.x - (center.x - 2)];
    }

    public void update() throws GameActionException {
      if (clearEnemyTilesOnSeen && rc.getLocation().isWithinDistanceSquared(center, 9)) {
        enemyTiles -= 1;
        clearEnemyTilesOnSeen = false;
      }
      var us = rc.getTeam();
      var them = us.opponent();
      for (var loc : rc.senseNearbyMapInfos(center, 8)) {
        var paint = loc.getPaint();
        var p = paint.isAlly() ? us :
            paint.isEnemy() ? them : null;
        if (tiles[loc.getMapLocation().x][loc.getMapLocation().y].paintTeam != p) {
          var old = tile(loc.getMapLocation()).paintTeam;

          if (old == us) allyTiles -= 1;
          else if (old == them) enemyTiles -= 1;
          if (p == us) allyTiles += 1;
          else if (p == them) enemyTiles += 1;

          tile(loc.getMapLocation()).paintTeam = p;
        }
      }
    }
  }

  public class Tower {
    public MapLocation loc;
    public Team team;
    public UnitType type;
    public int roundSeen;
    public int lastSent = -1;

    public Tower(MapLocation loc, Team team, UnitType type) {
      this.loc = loc;
      this.team = team;
      this.type = type.getBaseType();
    }
  }

  public class ResourcePattern {
    public MapLocation center;
    public boolean completed = false;

    public ResourcePattern(MapLocation loc) {
      center = loc;
    }

    public boolean secondary(MapLocation loc) {
      var corner = center.translate(-2, -2);
      return resourcePattern[loc.y - corner.y][loc.x - corner.x];
    }

    public void update() throws GameActionException {
      if (!rc.canSenseLocation(center)) return;
      if (!completed && rc.senseMapInfo(center).isResourcePatternCenter()) {
        completed = true;
      } else if (!completed && rc.senseMapInfo(center).getMark() != PaintType.ALLY_PRIMARY) {
        // if somebody removed the mark it was bc this rp didnt work anymore
        if (closestRP == this) closestRP = null;
        removeRP(this);
      } else if (!completed && rc.getLocation().isWithinDistanceSquared(center, 8)) {
        if (rc.canCompleteResourcePattern(center)) {
          rc.completeResourcePattern(center);
          completed = true;
        }
      } else if (completed && !rc.senseMapInfo(center).isResourcePatternCenter()) {
        completed = false;
      }
    }
  }

  public class Tile {
    public Tower tower;
    public MapLocation loc;
    public Ruin ruin;
    public boolean canBeRP = true;
    public int nextRPCheck;
    public Team paintTeam;
    public ResourcePattern rp;

    public Tile(MapLocation loc) {
      tower = null;
      this.loc = loc;
    }

    public boolean isInRuin() {
      return ruin != null;
    }

    public boolean secondary() throws GameActionException {
      if (ruin != null) return ruin.secondary(loc);
      if (rp != null) return rp.secondary(loc);

      return false;
    }
  }

  // these would ideally be private but we need to inline `tile()` in hot loops (micro)
  public final Tile[][] tiles;
  public final int height, width;
  // i think this is the optimal tiling
  public final boolean[][] resourcePattern;
  public ArrayList<Ruin> ruins = new ArrayList<>();
  public ArrayList<Tower> towers = new ArrayList<>();
  public ArrayList<ResourcePattern> rps = new ArrayList<>();
  public Ruin ruinTarget;
  public Tower closestPaintTower;
  public Tower closestFriendlyTower;
  public boolean isPaintTower;
  public Tower closestEnemyTower;
  public ResourcePattern closestRP;
  public int[][] visitedTiles = new int[12][12];

  void updateVisited() {
    int cx = rc.getLocation().x / 5, cy = rc.getLocation().y / 5;
    visitedTiles[cx][cy] = rc.getRoundNum();
  }

  public MapLocation findUnvisitedTile() {
    int bx = 0, by = 0;
    int best = 3000;
    for (int i = 0; i < 8; i++) {
      int cx = rng() % (width / 5), cy = rng() % (height / 5);
      var t = visitedTiles[cx][cy];
      if (t < best) {
        bx = cx;
        by = cy;
        best = t;
      }
    }
    return best == 3000 ? null : new MapLocation(bx * 5 + 3, by * 5 + 3);
  }

  public int moneyTarget;

  public Map() {
    height = rc.getMapHeight();
    width = rc.getMapWidth();
    tiles = new Tile[width][];
    resourcePattern = rc.getResourcePattern();

    moneyTarget = 4;
    if (height < 30 || width < 30) moneyTarget = 3;
    if (height >= 35 && width >= 35) moneyTarget = 5;
  }

  public Tile tile(MapLocation loc) {
    if (tiles[loc.x] == null) {
      tiles[loc.x] = new Tile[height];
    }
    if (tiles[loc.x][loc.y] == null) {
      tiles[loc.x][loc.y] = new Tile(loc);
    }
    return tiles[loc.x][loc.y];
  }

  public ResourcePattern tryAddRP(MapLocation center) {
    if (tile(center).rp == null) {
      var r = new ResourcePattern(center);
      rps.add(r);
      for (int x = -2; x < 3; x++) {
        for (int y = -2; y < 3; y++) {
          tile(center.translate(x, y)).rp = r;
        }
      }
    }
    return tile(center).rp;
  }

  private void removeRP(ResourcePattern rp) {
    rps.remove(rp);
    for (int x = -2; x < 3; x++) {
      for (int y = -2; y < 3; y++) {
        tile(rp.center.translate(x, y)).rp = null;
      }
    }
  }

  public Ruin tryAddRuin(MapLocation ruin, int roundSeen) {
    if (tile(ruin).tower != null && tile(ruin).tower.roundSeen > roundSeen) return null;
    if (!tile(ruin).isInRuin()) {
      if (rc.getType().isTowerType() || rc.canSenseLocation(ruin)) {
        // Comms the ruin as soon as possible
        comms.midx = 0;
      }
      var r = new Ruin(ruin);
      ruins.add(r);
      for (int x = -2; x < 3; x++) {
        for (int y = -2; y < 3; y++) {
          var t = tile(ruin.translate(x, y));
          t.ruin = r;
          // RP centers can never be within sqrt(8) of a tower
          t.canBeRP = false;
          t.nextRPCheck = 2500;
        }
      }
      if (tile(ruin).tower != null) towers.remove(tile(ruin).tower);
      tile(ruin).tower = null;
    }
    var r = tile(ruin).ruin;
    r.roundSeen = Math.max(r.roundSeen, roundSeen);
    return r;
  }

  private void removeRuin(Ruin ruin) {
    ruins.remove(ruin);
    for (int x = -2; x < 3; x++) {
      for (int y = -2; y < 3; y++) {
        tile(ruin.center.translate(x, y)).ruin = null;
      }
    }
  }

  public Tower tryAddTower(MapLocation loc, Team team, UnitType type, int roundSeen) {
    if (tile(loc).ruin != null && tile(loc).ruin.roundSeen > roundSeen) return null;
    if (tile(loc).tower == null || tile(loc).tower.team != team || tile(loc).tower.type != type.getBaseType()) {
      if (tile(loc).tower != null) towers.remove(tile(loc).tower);
      var tower = new Tower(loc, team, type);
      towers.add(tower);
      tile(loc).tower = tower;
      if (tile(loc).ruin != null) removeRuin(tile(loc).ruin);
      tower.roundSeen = Math.max(tower.roundSeen, roundSeen);
      return tower;
    } else {
      tile(loc).tower.roundSeen = Math.max(tile(loc).tower.roundSeen, roundSeen);
      return tile(loc).tower;
    }
  }

  public void update() throws GameActionException {
    updateVisited();

    if (rc.getType().isTowerType()) {
      tryAddTower(rc.getLocation(), rc.getTeam(), rc.getType().getBaseType(), rc.getRoundNum());
    }

    for (var ruinLoc : rc.senseNearbyRuins(-1)) {
      if (rc.canSenseRobotAtLocation(ruinLoc)) continue;
      var ruin = tryAddRuin(ruinLoc, rc.getRoundNum());
      ruin.update();
      if (ruin.allyTiles == 24) {
        if (rc.canCompleteTowerPattern(ruin.type(), ruinLoc) && (rc.senseMapInfo(ruinLoc.add(Direction.NORTH)).getMark().isAlly() || rc.canMark(ruinLoc.add(Direction.NORTH)))) {
          rc.completeTowerPattern(ruin.type(), ruinLoc);
          rc.setTimelineMarker("Tower built", 0, 255, 0);
          rc.mark(ruinLoc.add(Direction.NORTH), /*rc.senseMapInfo(ruinLoc.add(Direction.NORTH)).getMark().isAlly()*/ true);
        }
      }
    }

    for (var r : rc.senseNearbyRobots()) {
      if (r.type.isTowerType()) {
        tryAddTower(r.location, r.team, r.type, rc.getRoundNum());
      }
    }

    ruinTarget = null;
    var bestD = rc.getID() % 5 < 2 ? (height / 3) * (height / 3) + (width / 3) * (width / 3) : 100000000;
    if (rc.getType() != UnitType.SPLASHER || rc.getID() % 3 != 0) {
      if (rc.getNumberTowers() < GameConstants.MAX_NUMBER_OF_TOWERS) {
        for (var ruin : ruins) {
          if ((rc.getType() != UnitType.SOLDIER || ruin.enemyTiles == 0 || (rc.getNumberTowers() > 4 && rc.getID() % 7 < 2)) &&
              (rc.getType() == UnitType.SOLDIER || ruin.enemyTiles > 0 || rc.getID() % 4 == 0) &&
              (rc.getType() != UnitType.SPLASHER || ruin.enemyTiles >= 3 || ruin.clearEnemyTilesOnSeen || rc.getID() % 4 == 0)) {
            var d = ruin.center.distanceSquaredTo(rc.getLocation());
            if (d < bestD || (rc.getType() != UnitType.SOLDIER && ruinTarget != null && ruinTarget.enemyTiles == 0 && ruin.enemyTiles > 0)) {
              rc.setIndicatorDot(ruin.center, 255, 255, 255);
              ruinTarget = ruin;
              bestD = d;
            } else {
              rc.setIndicatorDot(ruin.center, 255, 0, 255);
            }
          } else {
            rc.setIndicatorDot(ruin.center, 255, 0, 255);
          }
        }
      }
    }

    closestPaintTower = null;
    closestEnemyTower = null;
    isPaintTower = false;
    for (var tower : towers) {
      rc.setIndicatorDot(tower.loc, 255, 0, 0);
      if (tower.team == rc.getTeam() && (tower.type == UnitType.LEVEL_ONE_PAINT_TOWER || (rc.canSenseLocation(tower.loc) && rc.senseRobotAtLocation(tower.loc).paintAmount >= towerKeepPaint() + MIN_TRANSFER_TOWER))) {
        if (!isPaintTower && tower.type == UnitType.LEVEL_ONE_PAINT_TOWER) isPaintTower = true;
        if (closestPaintTower == null || tower.loc.isWithinDistanceSquared(rc.getLocation(), closestPaintTower.loc.distanceSquaredTo(rc.getLocation()))) {
          closestPaintTower = tower;
        }
      }
      if (tower.team == rc.getTeam().opponent()) {
        if (closestEnemyTower == null || tower.loc.isWithinDistanceSquared(rc.getLocation(), closestEnemyTower.loc.distanceSquaredTo(rc.getLocation()))) {
          closestEnemyTower = tower;
        }
      } else {
        if (closestFriendlyTower == null || tower.loc.isWithinDistanceSquared(rc.getLocation(), closestFriendlyTower.loc.distanceSquaredTo(rc.getLocation()))) {
          closestFriendlyTower = tower;
        }
      }
    }

    if (rc.getType() == UnitType.SOLDIER) {
      closestRP = null;
      if (tile(rc.getLocation()).rp != null) tile(rc.getLocation()).rp.update();
      if (tile(rc.getLocation()).rp != null && !tile(rc.getLocation()).rp.completed) {
        closestRP = tile(rc.getLocation()).rp;
      } else {
        for (var rp : rps) {
          if (!rp.completed && (closestRP == null || rp.center.isWithinDistanceSquared(rc.getLocation(), closestRP.center.distanceSquaredTo(rc.getLocation())))) {
            closestRP = rp;
          }
        }
      }
      if (closestRP != null) {
        closestRP.update();
      }
      if (closestRP != null) {
        rc.setIndicatorDot(closestRP.center, 0, 0, 255);
        for (var l : rc.senseNearbyMapInfos()) {
          if (l.getMark() == PaintType.ALLY_PRIMARY && tile(l.getMapLocation()).rp == null) {
            var rp = tryAddRP(l.getMapLocation());
            rp.update();
          }
        }
        if (closestRP.center.isWithinDistanceSquared(rc.getLocation(), 2)) {
          // Re-check for overlap
          var keep = true;
          a:
          for (int x = -2; x < 3; x++) {
            for (int y = -2; y < 3; y++) {
              var l2 = closestRP.center.translate(x, y);
              var t2 = tile(l2);
              if ((t2.rp != null || t2.ruin != null) && t2.secondary() != resourcePattern[y + 2][x + 2]) {
                keep = false;
                rc.setIndicatorDot(t2.loc, 255, 0, 0);
                break a;
              }
            }
          }
          if (!keep) {
            if (rc.canRemoveMark(closestRP.center)) {
              rc.removeMark(closestRP.center);
            }
            removeRP(closestRP);
            closestRP = null;
          }
        }
      } else {
        // Try to find a new RP position
        for (var l : rc.senseNearbyMapInfos()) {
          if (l.getMark() == PaintType.ALLY_PRIMARY && tile(l.getMapLocation()).rp == null) {
            var rp = tryAddRP(l.getMapLocation());
            rp.update();
            if (!rp.completed) {
              closestRP = rp;
              break;
            }
          }
        }
        if (closestRP == null) {
          a:
          for (var l : rc.senseNearbyMapInfos(2)) {
            var t = tile(l.getMapLocation());
            if (t.canBeRP || t.nextRPCheck < rc.getRoundNum()) {
              for (int x = -2; x < 3; x++) {
                for (int y = -2; y < 3; y++) {
                  var l2 = l.getMapLocation().translate(x, y);
                  if (!rc.onTheMap(l2)) {
                    t.canBeRP = false;
                    t.nextRPCheck = 2500;
                    continue a;
                  }
                  // can always sense location
                  var m = rc.senseMapInfo(l2);
                  // if there's another center within the radius the tiling def doesn't work
                  if (m.hasRuin() || !m.isPassable() || m.getMark().isAlly()) {
                    t.canBeRP = false;
                    t.nextRPCheck = 2500;
                    continue a;
                  }
                  if (m.getPaint().isEnemy()) {
                    t.canBeRP = false;
                    t.nextRPCheck = rc.getRoundNum() + 10;
                    continue a;
                  }
                  var t2 = tile(l2);
                  if ((t2.rp != null || t2.ruin != null) && t2.secondary() != resourcePattern[y + 2][x + 2]) {
                    t.canBeRP = false;
                    // if there's already a RP there blocking this one then we don't want to ever put this one here
                    t.nextRPCheck = t2.rp == null ? rc.getRoundNum() + 10 : 2500;
                    continue a;
                  }
                }
              }
              // At this point t is a valid RP location
              closestRP = tryAddRP(t.loc);
              rc.mark(t.loc, false);
            }
          }
        }
      }
    }
  }
}
