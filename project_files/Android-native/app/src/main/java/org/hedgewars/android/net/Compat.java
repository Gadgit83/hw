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
}
