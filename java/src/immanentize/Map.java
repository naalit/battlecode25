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

    public Ruin(MapLocation loc) {
      center = loc;
    }

    public UnitType type() {
      var type = (hash(center) % 2) == 0 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
      if (rc.getChips() > 10000) type = UnitType.LEVEL_ONE_PAINT_TOWER;
      // money tower first ?
      type = rc.getNumberTowers() <= 2 ? UnitType.LEVEL_ONE_MONEY_TOWER : type;
      if (rc.getRoundNum() >= 150 && rc.getNumberTowers() > 2 && (hash(center) % 5) == 0)
        type = UnitType.LEVEL_ONE_DEFENSE_TOWER;
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

          if (old == rc.getTeam()) allyTiles -= 1;
          else if (old == rc.getTeam().opponent()) enemyTiles -= 1;
          if (p == rc.getTeam()) allyTiles += 1;
          else if (p == rc.getTeam().opponent()) enemyTiles += 1;

          tile(loc.getMapLocation()).paintTeam = p;
        }
      }
    }
  }

  public class Tower {
    public MapLocation loc;
    public Team team;
    public UnitType type;

    public Tower(MapLocation loc, Team team, UnitType type) {
      this.loc = loc;
      this.team = team;
      this.type = type.getBaseType();
    }
  }

  public class Tile {
    public Tower tower;
    public MapLocation loc;
    public int lastCheckedRP;
    public Ruin ruin;
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

      var adjX = loc.x + 3 * loc.y;
      return resourcePattern[adjX % resourcePattern.length];
    }

    public boolean rpCompatible() throws GameActionException {
      if (ruin == null) return true;
      var adjX = loc.x + 3 * loc.y;
      var s = resourcePattern[adjX % resourcePattern.length];
      return ruin.secondary(loc) == s;
    }
  }

  private final Tile[][] tiles;
  private final int height, width;
  // i think this is the optimal tiling
  private final boolean[] resourcePattern = {
      true, false,
      true, false, true,
      false,
      true, false, false,
      false,
  };
  public ArrayList<Ruin> ruins = new ArrayList<>();
  public ArrayList<Tower> towers = new ArrayList<>();
  public Ruin ruinTarget;
  public Tower closestPaintTower;

  public MapLocation findRPCenter(MapLocation loc) {
    var vx = Math.max(loc.x - 1, 0);
    var vy = Math.max(loc.y - 1, 0);
    var cx = (vx - vy / 3) / 3;
    var cy = (vy + cx) / 3;
    //RobotPlayer.rc.setIndicatorString("c " + cx + ", " + cy);
    MapLocation best = null;
    var bestDist = 0;
    // not exact closest, so look one cell in each direction for the closest center
    for (int i = -1; i <= 1; i++) {
      for (int j = -1; j <= 1; j++) {
        var c = new MapLocation(3 * (cx + i) + (cy + j) + 2, 3 * (cy + j) - (cx + i) + 2);
        var d = loc.distanceSquaredTo(c);
        if (best == null || d < bestDist) {
          bestDist = d;
          best = c;
        }
      }
    }
    return best;
  }

  public Map() {
    height = rc.getMapHeight();
    width = rc.getMapWidth();
    tiles = new Tile[width][];
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

  public void tryAddRuin(MapLocation ruin) {
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
  }

  public void update() throws GameActionException {
    for (var ruinLoc : rc.senseNearbyRuins(-1)) {
      if (rc.canSenseRobotAtLocation(ruinLoc)) continue;
      tryAddRuin(ruinLoc);

      var ruin = tile(ruinLoc).ruin;
      ruin.update();
      if (ruin.allyTiles == 24) {
        if (rc.canCompleteTowerPattern(ruin.type(), ruinLoc)) {
          rc.completeTowerPattern(ruin.type(), ruinLoc);
          rc.setTimelineMarker("Tower built", 0, 255, 0);
        }
      }
    }

    for (var r : rc.senseNearbyRobots()) {
      if (r.type.isTowerType()) {
        if (tile(r.location).tower == null || tile(r.location).tower.team != r.team) {
          if (tile(r.location).tower != null) towers.remove(tile(r.location).tower);
          var tower = new Tower(r.location, r.team, r.type);
          towers.add(tower);
          tile(r.location).tower = tower;
          if (tile(r.location).ruin != null) ruins.remove(tile(r.location).ruin);
          tile(r.location).ruin = null;
        }
      }
    }

    ruinTarget = null;
    if (rc.getNumberTowers() < GameConstants.MAX_NUMBER_OF_TOWERS) {
      for (var ruin : ruins) {
        if ((rc.getType() == UnitType.MOPPER || ruin.enemyTiles == 0) &&
            (ruinTarget == null
                //|| ruin.allyTiles > ruinTarget.allyTiles
                || ruin.center.isWithinDistanceSquared(rc.getLocation(), ruinTarget.center.distanceSquaredTo(rc.getLocation())))) {
          ruinTarget = ruin;
        }
      }
    }

    closestPaintTower = null;
    for (var tower : towers) {
      if (tower.team == rc.getTeam() && tower.type == UnitType.LEVEL_ONE_PAINT_TOWER) {
        if (closestPaintTower == null || tower.loc.isWithinDistanceSquared(rc.getLocation(), closestPaintTower.loc.distanceSquaredTo(rc.getLocation()))) {
          closestPaintTower = tower;
        }
      }
    }
  }
}
