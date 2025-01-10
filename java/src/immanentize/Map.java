package immanentize;

import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.Team;

import java.util.Optional;

public class Map {
  public class Tile {
    public Optional<Boolean> secondary;
    public Optional<Team> tower;
    public MapLocation loc;
    public int lastCheckedRP;

    public Tile(MapLocation loc) {
      secondary = Optional.empty();
      tower = Optional.empty();
      this.loc = loc;
    }

    public boolean secondary() {
      return secondary.orElseGet(() -> {
        var adjX = loc.x + 3 * loc.y;
        return resourcePattern[adjX % resourcePattern.length];
      });
    }

    public boolean rpCompatible() {
      if (secondary.isEmpty()) return true;
      var adjX = loc.x + 3 * loc.y;
      var s = resourcePattern[adjX % resourcePattern.length];
      return secondary.get() == s;
    }
  }

  private Tile[][] tiles;
  private int height, width;
  // i think this is the optimal tiling
  private boolean[] resourcePattern = {
      true, false,
      true, false, true,
      false,
      true, false, false,
      false,
  };

  public MapLocation findRPCenter(MapLocation loc) {
    var vx = Math.max(loc.x - 1, 0);
    var vy = Math.max(loc.y - 1, 0);
    var cx = (vx - vy / 3) / 3;
    var cy = (vy + cx) / 3;
    RobotPlayer.rc.setIndicatorString("c " + cx + ", " + cy);
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
    height = RobotPlayer.rc.getMapHeight();
    width = RobotPlayer.rc.getMapWidth();
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

  public boolean hasOverlay(MapLocation loc) {
    var tile = tile(loc);
    return tile.secondary.isPresent();
  }
}
