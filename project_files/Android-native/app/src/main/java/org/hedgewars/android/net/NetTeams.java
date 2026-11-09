package org.hedgewars.android.net;

import org.hedgewars.android.GameConfig;
import org.hedgewars.android.GameData;
import org.hedgewars.android.SetupModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Teams as they travel in the lobby protocol: the per-team part of the setup (name, colour, hats, fort, ...).
 *
 * Everything that arrives from another phone is untrusted: it ends up as a line of the setup that every engine (and
 * the TV) reads, so a newline in a grave name would be an engine command. {@link #fromJson(Map, GameData)} therefore
 * accepts a grave, fort, voice, flag or hat only if the host's own game data has it, and cleans names exactly as
 * {@link GameConfig#clean} does for the host's own teams (no control characters, at most 32 characters).
 */
public final class NetTeams {
    private NetTeams() { }

    public static Map<String, Object> toJson(SetupModel.Team t) {
        List<Object> names = new ArrayList<>(), hats = new ArrayList<>();
        for (int h = 0; h < SetupModel.MAX_HOGS; h++) {
            names.add(t.hogName[h] == null ? "" : t.hogName[h]);
            hats.add(t.hogHat[h] == null ? "NoHat" : t.hogHat[h]);
        }
        return Json.obj("name", t.name, "color", (long) t.color, "level", (long) t.level, "hogs", (long) t.hogs,
                "grave", t.grave, "fort", t.fort, "voice", t.voice, "flag", t.flag, "hogNames", names, "hogHats", hats);
    }

    /** The value if the list has it, otherwise the default. With no game data nothing can be checked: the default. */
    static String allowed(List<String> list, String v, String def) {
        return v != null && list != null && list.contains(v) ? v : def;
    }

    /** A team from another phone, every field checked against the host's game data. */
    public static SetupModel.Team fromJson(Map<String, Object> m, GameData data) {
        SetupModel.Team t = new SetupModel.Team();
        t.name = GameConfig.cleanTeamName(Json.str(m, "name", "Team"), "Team");
        t.color = (int) Math.max(0, Math.min(SetupModel.COLORS.length - 1, Json.num(m, "color", 0)));
        t.level = (int) Math.max(0, Math.min(5, Json.num(m, "level", 0)));
        t.hogs = (int) Math.max(SetupModel.MIN_HOGS, Math.min(SetupModel.MAX_HOGS, Json.num(m, "hogs", 4)));
        t.grave = allowed(data == null ? null : data.graves(), Json.str(m, "grave", null), "Statue");
        t.fort = allowed(data == null ? null : data.forts(), Json.str(m, "fort", null), "Earth");
        t.voice = allowed(data == null ? null : data.voices(), Json.str(m, "voice", null), "Default");
        t.flag = allowed(data == null ? null : data.flags(), Json.str(m, "flag", null), "hedgewars");
        List<Object> names = Json.list(m, "hogNames"), hats = Json.list(m, "hogHats");
        List<String> hatList = data == null ? null : data.hats();
        for (int h = 0; h < SetupModel.MAX_HOGS; h++) {
            String fallback = "Hedgehog " + (h + 1);
            t.hogName[h] = GameConfig.clean(h < names.size() && names.get(h) instanceof String ? (String) names.get(h) : null, fallback);
            t.hogHat[h] = allowed(hatList, h < hats.size() && hats.get(h) instanceof String ? (String) hats.get(h) : null, "NoHat");
        }
        return t;
    }

    /** At most {@link SetupModel#MAX_TEAMS} teams are read; anything else in the list is ignored. */
    @SuppressWarnings("unchecked")
    public static List<SetupModel.Team> listFromJson(List<Object> l, GameData data) {
        List<SetupModel.Team> out = new ArrayList<>();
        for (Object o : l) {
            if (out.size() >= SetupModel.MAX_TEAMS) break;
            if (o instanceof Map) out.add(fromJson((Map<String, Object>) o, data));
        }
        return out;
    }

    public static List<Object> listToJson(List<SetupModel.Team> teams) {
        List<Object> l = new ArrayList<>();
        for (SetupModel.Team t : teams) l.add(toJson(t));
        return l;
    }
}
