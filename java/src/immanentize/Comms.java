package immanentize;

import battlecode.common.GameConstants;
import battlecode.common.MapLocation;

import java.util.ArrayList;

public class Comms {
  public static class Message {
    public enum Type {
      Ruin,
      AllyTower,
      EnemyTower,
    }

    public Type type;
    public int round;
    public MapLocation loc;

    Message(Type type) {
      this.type = type;
    }

    Message(Type type, MapLocation loc, int round) {
      this.type = type;
      this.loc = loc;
      this.round = round;
    }

    Message(Type type, TLoc loc) {
      this.type = type;
      this.loc = loc.loc;
      this.round = loc.round;
    }

    Message(int encoded) {
      type = Type.values()[encoded >> 30];
      round = (encoded >> 12) & (2048 - 1);
      loc = new MapLocation((encoded >> 6) & 0b11_1111, encoded & 0b11_1111);
    }

    int encode() {
      var i = 0;
      i |= type.ordinal() << 30;
      i |= round << 12;
      i |= loc.x << 6 | loc.y;
      return i;
    }
  }

  public static final class TLoc {
    public final MapLocation loc;
    public int round;

    public TLoc(MapLocation loc, int round) {
      this.loc = loc;
      this.round = round;
    }
  }

  ArrayList<TLoc> allyTowers;
  ArrayList<TLoc> enemyTowers;
  ArrayList<TLoc> ruins;
  int midx, uidx;

  void process() {
    var rc = RobotPlayer.rc;
    for (var data : rc.readMessages(rc.getRoundNum() - 1)) {
      var m = new Message(data.getBytes());
      switch (m.type) {
        case Ruin -> {
          if (!ruins.contains(m.loc)) {
            ruins.add(new TLoc(m.loc, m.round));
          }
        }
        case EnemyTower -> {
          if (!enemyTowers.contains(m.loc)) {
            enemyTowers.add(new TLoc(m.loc, m.round));
          }
        }
        case AllyTower -> {
          if (!allyTowers.contains(m.loc)) {
            allyTowers.add(new TLoc(m.loc, m.round));
          }
        }
      }
    }

    if (ruins.isEmpty()) return;
    var sent = 0;
    var tower = rc.getType().isTowerType();
    var max = tower ? GameConstants.MAX_MESSAGES_SENT_TOWER : GameConstants.MAX_MESSAGES_SENT_ROBOT;
    var ulength = RobotPlayer.nearbyAllies.length;
    var mlength = ruins.size();
    var startu = uidx % ulength;
    var startm = midx % mlength;
    while (sent < max) {
      var unit = RobotPlayer.nearbyAllies[uidx++ % ulength];
      if (unit.getType().isTowerType() != tower && rc.canSendMessage(unit.location)) {
        var m = new Message(Message.Type.Ruin, ruins.get(midx++ % mlength));
      }
    }
  }
}
