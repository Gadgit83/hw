package org.hedgewars.android;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What each setting on the Rules tab does, shown by the info button beside it and, for a game style, under the
 * Game style row. One place for all of it: a new setting adds its text here, and LaunchSmokeTest fails if one is missing.
 *
 * Keys: "style", "scheme", "weapons.link", "weapons.set", "flag.N" (a switch, Qt scheme entry N from 1 to 25) and
 * "value.N" (a number, entry 26 to 43; see Rules.java). The wording follows the Qt frontend's tooltips
 * (QTfrontend/ui/page/pagescheme.cpp), in British English. The game styles are keyed by script name, as in
 * Scripts/Multiplayer. Pure Java, so it runs on a plain JVM.
 */
final class RuleHelp {
    private RuleHelp() { }

    private static final Map<String, String> TEXT = new HashMap<>();
    private static final Map<String, String> STYLE = new HashMap<>();

    static {
        // The game style and the scheme and weapon set above the switches.
        put("style", "Normal is the standard game, played with the scheme and weapon set below. A game style is a script "
                + "that changes the rules, weapons or map.");
        put("scheme", "A preset of the rules below. Changing a value marks the scheme as edited; choosing another scheme "
                + "starts again from its preset.");
        put("weapons.link", "When on, choosing a scheme also chooses the weapon set with the same name, if there is one.");
        put("weapons.set", "The weapons each hedgehog starts with, and how often each weapon and crate turns up.");

        // Key values (Rules.VALUE_LABELS, entries 26 to 43).
        put("value.26", "Scales the damage and knockback of every weapon, in percent. 100% is normal.");
        put("value.27", "Seconds each hedgehog has in its turn. Unlimited means there is no time limit.");
        put("value.28", "The health each hedgehog starts the game with.");
        put("value.29", "The number of rounds played before sudden death begins. In sudden death the water rises and health drains.");
        put("value.30", "How often a crate drops before a turn, from every turn to never.");
        put("value.31", "The fuse of each mine in seconds, or random (0 to 5 seconds). Air mines use a quarter of this time.");
        put("value.32", "About how many mines are placed on a medium island map. Other maps scale this number.");
        put("value.33", "The chance, in percent, that a mine is a dud. Mines placed by hedgehogs are never duds.");
        put("value.34", "About how many explosive barrels are placed on a medium island map. Other maps scale this number.");
        put("value.35", "About how many air mines are placed on a medium island map. Other maps scale this number.");
        put("value.36", "About how many sentry bots are placed on a medium island map. Other maps scale this number.");
        put("value.37", "The chance, in percent, that a dropped crate is a health crate. The rest are weapon or utility crates.");
        put("value.38", "The health a hedgehog gains from collecting a health crate.");
        put("value.39", "How far the water rises each turn in sudden death. Set this and the health loss to 0 to turn sudden death off.");
        put("value.40", "The health each hedgehog loses per turn in sudden death, down to 1 health. 0 with water rise turns it off.");
        put("value.41", "The longest a rope can be, in percent of the normal length.");
        put("value.42", "The time a hedgehog has to get away after attacking, in percent of the normal time.");
        put("value.43", "What happens at the left and right edges of the map: None (a wall), Wrap (the map joins up), "
                + "Bounce (edges reflect) or Sea (edges meet the sea).");
        put("value.44", "How big the blast is when a hedgehog dies, in percent of the normal size. 100% is the classic game.");
        put("value.45", "How much land an explosion removes, in percent of the normal amount. Damage to hedgehogs is unchanged.");
        put("value.46", "Shows the path of the shot while you aim: Off, Short (the first 40% of the path) or Full. "
                + "It applies to everyone in a network game.");

        // More rules: the switches (Rules.FLAG_LABELS, entries 1 to 25).
        put("flag.1", "The hedgehog that acts at the start of each turn is chosen by the player, not taken in order.");
        put("flag.2", "Each clan starts in its own part of the terrain.");
        put("flag.3", "Most weapons cannot destroy the land.");
        put("flag.4", "An indestructible border all round the terrain.");
        put("flag.5", "Lower gravity: hedgehogs jump higher and further, and things fall more slowly.");
        put("flag.6", "Assisted aiming: a laser sight shows where a shot will go.");
        put("flag.7", "Every hedgehog has a personal forcefield, so it cannot be harmed.");
        put("flag.8", "Living hedgehogs are restored to full health at the end of each turn.");
        put("flag.9", "A hedgehog gets back 80% of the damage it does, as health.");
        put("flag.10", "Share your opponents' pain, share their damage.");
        put("flag.11", "Hedgehogs cannot move, so the game is all about aiming and firepower.");
        put("flag.12", "The order of play is random, not the order the teams were added in.");
        put("flag.13", "Each team has a king. If he dies, the whole team dies.");
        put("flag.14", "Before play starts, the teams take turns placing their hedgehogs.");
        put("flag.15", "Ammo is shared by all the teams of a clan (the same colour).");
        put("flag.16", "Random maps are made without girders.");
        put("flag.17", "Random maps are made without land objects.");
        put("flag.18", "A computer-controlled hedgehog respawns when it dies.");
        put("flag.19", "Attacking does not end your turn.");
        put("flag.20", "Weapons go back to their starting amounts at the end of each turn.");
        put("flag.21", "Each hedgehog has its own ammo instead of sharing it with its team.");
        put("flag.22", "There is no wind, so you need not worry about it.");
        put("flag.23", "The wind affects almost everything.");
        put("flag.24", "Teams in a clan take successive turns, sharing the turn time.");
        put("flag.25", "An indestructible border along the bottom of the map.");

        // Game styles: the description of each script in Scripts/Multiplayer, from its own header and in-game goals.
        style("Balanced_Random_Weapon", "Each turn, each hedgehog gets one to three random weapons, and they are reset every turn. "
                + "The ammo count tab sets how likely each weapon is.");
        style("Battalion", "Each hedgehog is a variant (such as Medic, Ninja or King). Each turn it gets random weapons and "
                + "helpers, and crates may be empty.");
        style("Capture_the_Flag", "Carry the enemy flag back to your base to score. The first team to the capture limit wins; "
                + "set it with the script parameter, captures=N.");
        style("Construction_Mode", "Build structures from crates, such as a healing station or teleporter nodes, to help your "
                + "team and hinder the others.");
        style("Continental_supplies", "Supply crates drop across a large map during the game.");
        style("DiagonalMaze", "A maze laid out on the diagonal of the map.");
        style("Frenzy", "A fast game inspired by Hysteria, with a fixed list of weapons.");
        style("Gravity", "The gravity changes during the game, between a minimum and a maximum over a set period.");
        style("HedgeEditor", "A level editor: place the gears by hand to build a mission. Use a weapon set with no delays.");
        style("Highlander", "Kill hedgehogs to take their weapons. Weapons are restocked at the start of each turn.");
        style("Mutant", "One hedgehog starts as the mutant. Its disease spreads to the others and drains their health.");
        style("No_Jumping", "Hedgehogs cannot jump. Ropes, jetpacks and parachutes still work.");
        style("Racer", "Race round the waypoints on the map. The number of rounds is set with the script parameter, rounds=N.");
        style("Random_Weapon", "Each turn, each hedgehog gets one random weapon.");
        style("ShoppaMap", "The Shoppa map layout, with extra room for ropes.");
        style("Space_Invasion", "Invaders attack from space. Girders should be disabled, as the script recommends.");
        style("TechRacer", "A timed racing course, used for the official time records.");
        style("The_Specialists", "Each hedgehog starts with its own specialist weapon set. The script parameter sets the order "
                + "for each team.");
        style("Tumbler", "Barrels and mines appear each turn, and crates bring bonus time and ammo. Set with the script parameter.");
        style("Tunnels", "A map full of tunnels for the hedgehogs to dig through.");
        style("WxW", "Wall to Wall: the first team builds the walls, then the game starts with the second team.");
    }

    private static void put(String key, String text) {
        TEXT.put(key, text);
    }

    private static void style(String script, String text) {
        STYLE.put(script, text);
    }

    /** The text for a rule key, or an empty string if there is none (the smoke test checks that there is one). */
    static String text(String key) {
        String s = TEXT.get(key);
        return s == null ? "" : s;
    }

    /** The keys that have text. */
    static Set<String> keys() {
        return Collections.unmodifiableSet(TEXT.keySet());
    }

    /** A game style's description by script name (underscores as in the file name), with a generic one for a style not listed. */
    static String style(String script) {
        String s = STYLE.get(script);
        return s != null ? s : "A game style: a script that plays with its own rules, weapons or map.";
    }

    /** Whether a game style has its own description. */
    static boolean hasStyle(String script) {
        return STYLE.containsKey(script);
    }
}
