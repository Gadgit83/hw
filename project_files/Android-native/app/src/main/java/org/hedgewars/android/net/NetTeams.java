package org.hedgewars.android.net;

import org.hedgewars.android.SetupModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Teams as they travel in the lobby protocol: the per-team part of the setup (name, colour, hats, fort, ...). */
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

    public static SetupModel.Team fromJson(Map<String, Object> m) {
        SetupModel.Team t = new SetupModel.Team();
        t.name = Json.str(m, "name", "Team");
        t.color = (int) Math.max(0, Math.min(SetupModel.COLORS.length - 1, Json.num(m, "color", 0)));
        t.level = (int) Math.max(0, Math.min(5, Json.num(m, "level", 0)));
        t.hogs = (int) Math.max(SetupModel.MIN_HOGS, Math.min(SetupModel.MAX_HOGS, Json.num(m, "hogs", 4)));
        t.grave = Json.str(m, "grave", "Statue");
        t.fort = Json.str(m, "fort", "Earth");
        t.voice = Json.str(m, "voice", "Default");
        t.flag = Json.str(m, "flag", "hedgewars");
        List<Object> names = Json.list(m, "hogNames"), hats = Json.list(m, "hogHats");
        for (int h = 0; h < SetupModel.MAX_HOGS; h++) {
            t.hogName[h] = h < names.size() && names.get(h) instanceof String ? (String) names.get(h) : "Hedgehog " + (h + 1);
            t.hogHat[h] = h < hats.size() && hats.get(h) instanceof String ? (String) hats.get(h) : "NoHat";
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    public static List<SetupModel.Team> listFromJson(List<Object> l) {
        List<SetupModel.Team> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map) out.add(fromJson((Map<String, Object>) o));
        return out;
    }

    public static List<Object> listToJson(List<SetupModel.Team> teams) {
        List<Object> l = new ArrayList<>();
        for (SetupModel.Team t : teams) l.add(toJson(t));
        return l;
    }
}
