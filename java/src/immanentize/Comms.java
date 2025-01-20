package immanentize;

import battlecode.common.*;

import static immanentize.RobotPlayer.rc;
import static immanentize.RobotPlayer.map;

public class Comms {
  public static class Message {
    public enum Type {
      Ruin,
      Tower,
      RemoveWatchtower,
    }

    public Type type;
    public int round;
    public MapLocation loc;
    public UnitType towerType;
    public Team team;

    Message(Type type) {
      this.type = type;
    }

    Message(Type type, MapLocation loc, int round) {
      this.type = type;
      this.loc = loc;
      this.round = round;
    }

    Message(int encoded) {
      type = Type.values()[encoded >> 29];
      round = (encoded >> 12) & (2048 - 1);
      loc = new MapLocation((encoded >> 6) & 0b11_1111, encoded & 0b11_1111);
      towerType = (encoded & (1 << 24)) > 0 ? UnitType.LEVEL_ONE_MONEY_TOWER
          : (encoded & (1 << 25)) > 0 ? UnitType.LEVEL_ONE_PAINT_TOWER : UnitType.LEVEL_ONE_DEFENSE_TOWER;
      team = (encoded & (1 << 26)) > 0 ? rc.getTeam() : rc.getTeam().opponent();
    }

    int encode() {
      var i = 0;
      i |= type.ordinal() << 29;
      i |= round << 12;
      i |= loc.x << 6 | loc.y;
      if (towerType == UnitType.LEVEL_ONE_MONEY_TOWER) i |= 1 << 24;
      if (towerType == UnitType.LEVEL_ONE_PAINT_TOWER) i |= 1 << 25;
      if (team == rc.getTeam()) i |= 1 << 26;
      return i;
    }
  }

  int midx, uidx;

  void update() throws GameActionException {
    var rc = RobotPlayer.rc;
    for (var data : rc.readMessages(rc.getRoundNum() - 1)) {
      var m = new Message(data.getBytes());
      switch (m.type) {
        case Ruin -> {
          map.tryAddRuin(m.loc, m.round);
        }
        case Tower -> {
          map.tryAddTower(m.loc, m.team, m.towerType, m.round);
        }
        case RemoveWatchtower -> {
          if (rc.canRemoveMark(m.loc.add(Direction.NORTH))) {
            rc.removeMark(m.loc.add(Direction.NORTH));
          }
        }
      }
    }

    var sent = 0;
    var isTower = rc.getType().isTowerType();
    var max = isTower ? GameConstants.MAX_MESSAGES_SENT_TOWER - 5 : GameConstants.MAX_MESSAGES_SENT_ROBOT;
    var ulength = RobotPlayer.nearbyAllies.length;
    var mlength = map.ruins.size() + map.towers.size();
    if (ulength > 0 && mlength > 0) {
      var ustart = uidx % ulength;
      var mstart = midx % mlength;
      while (sent < max) {
        var unit = RobotPlayer.nearbyAllies[uidx++ % ulength];
        if (unit.getType().isTowerType() != isTower && rc.canSendMessage(unit.location)) {
          var i = midx++ % mlength;
          Message m;
          if (i < map.ruins.size()) {
            var ruin = map.ruins.get(i);
            m = new Message(Message.Type.Ruin, ruin.center, ruin.roundSeen);
          } else {
            var tower = map.towers.get(i - map.ruins.size());
            m = new Message(Message.Type.Tower, tower.loc, tower.roundSeen);
            m.towerType = tower.type;
            m.team = tower.team;
          }
          if (i == (mstart + 1) % mlength && (rc.getType().isTowerType() && /*rc.getType().getBaseType() == UnitType.LEVEL_ONE_DEFENSE_TOWER &&*/ RobotPlayer.turnsSinceSeenEnemy > 150)) {
            m.type = Message.Type.RemoveWatchtower;
            m.loc = rc.getLocation();
            rc.setIndicatorLine(rc.getLocation(), rc.getLocation().add(Direction.NORTH), 255, 0, 0);
          }
          rc.sendMessage(unit.location, m.encode());
          sent += 1;
        }
        if (uidx % ulength == ustart || midx % mlength == mstart) break;
      }
    }
    if (isTower && mlength != 0) {
      sent = 0;
      var mstart = midx % mlength;
      while (rc.canBroadcastMessage() && sent < 3) {
        var i = midx++ % mlength;
        Message m;
        if (i < map.ruins.size()) {
          var ruin = map.ruins.get(i);
          m = new Message(Message.Type.Ruin, ruin.center, ruin.roundSeen);
        } else {
          var tower = map.towers.get(i - map.ruins.size());
          m = new Message(Message.Type.Tower, tower.loc, tower.roundSeen);
          m.towerType = tower.type;
          m.team = tower.team;
        }
        rc.broadcastMessage(m.encode());
        if (midx % mlength == mstart) break;
        sent += 1;
      }
    } else if (sent == 0) {
      midx = 0;
    }
  }
}
