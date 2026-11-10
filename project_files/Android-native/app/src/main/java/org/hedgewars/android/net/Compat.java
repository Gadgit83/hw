package org.hedgewars.android.net;

/**
 * What a TV viewer compares to decide whether it may join: the {@code compat} string of the status GET on the viewer port.
 * The status {@code engine} field is only a human label (app version and engine commit); the TV's engine commit may legitimately differ
 * from the phone's. A TV joins only if its own compat string equals this one exactly.
 */
public final class Compat {
    private Compat() { }

    /**
     * The engine's network protocol number, cNetProtoVersion. It is defined by HEDGEWARS_PROTO_VER in the top-level CMakeLists.txt
     * (set(HEDGEWARS_PROTO_VER 60)) and compiled into the engine through hedgewars/config.inc.in. Change it here when that changes.
     */
    public static final int ENGINE_PROTOCOL = 60;

    /**
     * Simulation-compatibility counter. Bump it BY HAND, and only when an engine change can alter simulation results (anything that
     * changes what a game computes from the same messages: physics, weapons, AI, random number use, map generation, schemes, game
     * rules, Lua scripting behaviour) or the format or meaning of the messages between a phone and a TV (the setup lines, the framing,
     * the whitelisted message types, the e$spectate and backlog handling). Sound, touch, UI, rendering and launcher-only changes do
     * not need a bump. Start: 1.
     */
    public static final int SIM = 1;

    public static final String VALUE = "hwnet-" + ENGINE_PROTOCOL + "-sim" + SIM;

    /**
     * Why a phone may not join a host, or null when it may (phase 5, docs/tv-viewer-plan.md section 2.8). Written from the joining phone's
     * point of view and naming both sides, because the same text is shown by the relay's refusal and, before connecting, by the
     * list of discovered hosts. Order: the compat string (exact match), then the app version (exact match: phones also share the
     * launcher, the lobby protocol and the screens, so a stricter rule than the TV's), then the game data fingerprint.
     * A host that publishes no data fingerprint (the host tests) skips the data check; a phone that has none while the host has one
     * cannot prove it has the same data and is refused.
     */
    public static String refusal(String hostCompat, String hostVersion, String hostData,
                                 String myCompat, String myVersion, String myData) {
        if (hostCompat == null) hostCompat = "";
        if (myCompat == null) myCompat = "";
        if (!hostCompat.equals(myCompat)) {
            return "Different game versions: this phone is \"" + show(myCompat) + "\" and the host is \"" + show(hostCompat)
                    + "\" (games would not agree). Install the same app version on both phones.";
        }
        if (hostVersion != null && !hostVersion.isEmpty() && !hostVersion.equals(myVersion)) {
            return "This phone has " + show(myVersion) + " and the host has " + hostVersion + ": update the app on the phone that is behind.";
        }
        if (hostData != null && !hostData.isEmpty() && !hostData.equals(myData)) {
            return (myData == null || myData.isEmpty())
                    ? "This phone's game data could not be read, so it cannot be checked against the host's. Reinstall the app."
                    : "Game data differs: this phone has " + myData.substring(0, Math.min(8, myData.length()))
                    + " and the host has " + hostData.substring(0, Math.min(8, hostData.length()))
                    + ". Install the same build (the test and the full builds carry different data) on both phones.";
        }
        return null;
    }

    private static String show(String s) {
        if (s == null || s.isEmpty()) return "unknown (older app)";
        String t = s.length() > 40 ? s.substring(0, 40) : s;
        return t.replaceAll("[^A-Za-z0-9._ -]", "?");
    }
}
