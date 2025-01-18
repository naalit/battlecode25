package immanentize;

import battlecode.common.*;

import java.util.ArrayList;

import static immanentize.RobotPlayer.*;

public class Map {
  public static Team paintTeam(PaintType paint) {
    return paint.isAlly() ? rc.getTeam() :
        paint.isEnemy() ? rc.getTeam().opponent() : null;
  }

  public class Ruin {
    public MapLocation center;
    public int allyTiles;
    public int enemyTiles;
    public int roundSeen;

    public Ruin(MapLocation loc) {
      center = loc;
    }

    public UnitType type() throws GameActionException {
      var type = (hash(center) % 2) == 0 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
      if (rc.getChips() > 10000) type = UnitType.LEVEL_ONE_PAINT_TOWER;
      // money tower first ?
      type = rc.getNumberTowers() <= 2 ? UnitType.LEVEL_ONE_MONEY_TOWER : type;
      // then paint tower
      type = rc.getNumberTowers() == 3 ? UnitType.LEVEL_ONE_PAINT_TOWER : type;
//      if (rc.getRoundNum() >= 150 && rc.getNumberTowers() > 3 && (hash(center) % 5) == 0)
//        type = UnitType.LEVEL_ONE_DEFENSE_TOWER;
      if (rc.canSenseLocation(center.add(Direction.NORTH)) && rc.senseMapInfo(center.add(Direction.NORTH)).getMark().isSecondary()) {
        type = UnitType.LEVEL_ONE_DEFENSE_TOWER;
      }
      return type;
    }

    public boolean secondary(MapLocation loc) throws GameActionException {
      return rc.getTowerPattern(type())[loc.y - (center.y - 2)][loc.x - (center.x - 2)];
    }

    public void update() throws GameActionException {
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

    public Tower(MapLocation loc, Team team, UnitType type) {
      this.loc = loc;
      this.team = team;
      this.type = type.getBaseType();
    }
  }

  public class Tile {
    public Tower tower;
    public MapLocation loc;
    public Ruin ruin;
    public boolean canBeRP = true;
    public int nextRPCheck;
    public Team paintTeam;

    public Tile(MapLocation loc) {
      tower = null;
      this.loc = loc;
    }

    public boolean isInRuin() {
      return ruin != null;
    }

    public boolean secondary() throws GameActionException {
      if (ruin != null) return ruin.secondary(loc);

      return resourcePattern[loc.x % 4][loc.y % 4];
    }

    public boolean rpCompatible() throws GameActionException {
      if (ruin == null) return true;
      var s = resourcePattern[loc.x % 4][loc.y % 4];
      return ruin.secondary(loc) == s;
    }

    public void checkRP() throws GameActionException {
      nextRPCheck = rc.getRoundNum() + 10;
      for (var tile : rc.senseNearbyMapInfos(loc, 8)) {
        // this should be exactly the correct tiles (bc 0,3 has r^2 9)
        if (!tile.isPassable()) {
          canBeRP = false;
          nextRPCheck = 5000;
          break;
        }
        if (tile.getPaint().isEnemy() || !map.tile(tile.getMapLocation()).rpCompatible()) {
          canBeRP = false;
          nextRPCheck = rc.getRoundNum() + 10;
          break;
        }
      }
    }
  }

  // these would ideally be private but we need to inline `tile()` in hot loops (micro)
  public final Tile[][] tiles;
  public final int height, width;
  // i think this is the optimal tiling
  public final boolean[][] resourcePattern;
  public ArrayList<Ruin> ruins = new ArrayList<>();
  public ArrayList<Tower> towers = new ArrayList<>();
  public Ruin ruinTarget;
  public Tower closestPaintTower;
  public Tower closestEnemyTower;

  public MapLocation findRPCenter(MapLocation loc) throws GameActionException {
    var cx = loc.x / 4;
    var cy = loc.y / 4;
    //RobotPlayer.rc.setIndicatorString("c " + cx + ", " + cy);
    MapLocation best = null;
    var bestDist = 0;
    // not exact closest, so look one cell in each direction for the closest center
    var checked = 0;
    for (int i = -1; i <= 1; i++) {
      for (int j = -1; j <= 1; j++) {
        var c = new MapLocation(4 * (cx + i) + 2, 4 * (cy + j) + 2);
        if (c.x < 2 || c.y < 2 || c.x > width - 3 || c.y > height - 3) continue;
        var t = tile(c);
        if (rc.canSenseLocation(c) && rc.senseMapInfo(c).isResourcePatternCenter()) {
          t.canBeRP = false;
          t.nextRPCheck = rc.getRoundNum() + 25;
        }
        if (checked < 4 && t.nextRPCheck < rc.getRoundNum()) {
          t.checkRP();
          checked += 1;
        }
        if (t.canBeRP) {
          var d = loc.distanceSquaredTo(c);
          if (best == null || d < bestDist) {
            bestDist = d;
            best = c;
          }
        }
      }
    }
    return best;
  }

  public Map() {
    height = rc.getMapHeight();
    width = rc.getMapWidth();
    tiles = new Tile[width][];
    resourcePattern = rc.getResourcePattern();
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

  public Ruin tryAddRuin(MapLocation ruin, int roundSeen) {
    if (tile(ruin).tower != null && tile(ruin).tower.roundSeen > roundSeen) return null;
    if (!tile(ruin).isInRuin()) {
      var r = new Ruin(ruin);
      ruins.add(r);
      for (int x = -2; x < 3; x++) {
        for (int y = -2; y < 3; y++) {
          tile(ruin.translate(x, y)).ruin = r;
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
    if (rc.getType().isTowerType()) {
      tryAddTower(rc.getLocation(), rc.getTeam(), rc.getType().getBaseType(), rc.getRoundNum());
    }

    for (var ruinLoc : rc.senseNearbyRuins(-1)) {
      if (rc.canSenseRobotAtLocation(ruinLoc)) continue;
      var ruin = tryAddRuin(ruinLoc, rc.getRoundNum());
      ruin.roundSeen = rc.getRoundNum();
      ruin.update();
      if (ruin.allyTiles == 24) {
        if (rc.canCompleteTowerPattern(ruin.type(), ruinLoc) && rc.canMark(ruinLoc.add(Direction.NORTH))) {
          rc.completeTowerPattern(ruin.type(), ruinLoc);
          rc.setTimelineMarker("Tower built", 0, 255, 0);
          rc.mark(ruinLoc.add(Direction.NORTH), rc.senseMapInfo(ruinLoc.add(Direction.NORTH)).getMark().isAlly());
        }
      }
    }

    for (var r : rc.senseNearbyRobots()) {
      if (r.type.isTowerType()) {
        var tower = tryAddTower(r.location, r.team, r.type, rc.getRoundNum());
        tower.roundSeen = rc.getRoundNum();
      }
    }

    ruinTarget = null;
    if (rc.getNumberTowers() < GameConstants.MAX_NUMBER_OF_TOWERS) {
      for (var ruin : ruins) {
        if ((rc.getType() == UnitType.MOPPER || ruin.enemyTiles == 0) &&
            (ruinTarget == null
                //|| ruin.allyTiles > ruinTarget.allyTiles
                || ruin.center.isWithinDistanceSquared(rc.getLocation(), ruinTarget.center.distanceSquaredTo(rc.getLocation())))) {
          rc.setIndicatorDot(ruin.center, 255, 255, 255);
          ruinTarget = ruin;
        } else {
          rc.setIndicatorDot(ruin.center, 255, 0, 255);
        }
      }
    }

    closestPaintTower = null;
    closestEnemyTower = null;
    for (var tower : towers) {
      rc.setIndicatorDot(tower.loc, 255, 0, 0);
      if (tower.team == rc.getTeam() && tower.type == UnitType.LEVEL_ONE_PAINT_TOWER) {
        if (closestPaintTower == null || tower.loc.isWithinDistanceSquared(rc.getLocation(), closestPaintTower.loc.distanceSquaredTo(rc.getLocation()))) {
          closestPaintTower = tower;
        }
      }
      if (tower.team == rc.getTeam().opponent()) {
        if (closestEnemyTower == null || tower.loc.isWithinDistanceSquared(rc.getLocation(), closestEnemyTower.loc.distanceSquaredTo(rc.getLocation()))) {
          closestEnemyTower = tower;
        }
      }
    }
  }
}
