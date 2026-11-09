# Network play: what exists and what could be reused

This is a survey, written while building the 0.2.0 setup screen, of what Hedgewars already has for network play, so that a party mode or a network mode is designed on what exists. Nothing here is built into the app. Paths are relative to the repository root (https://github.com/Gadgit83/hw, branch `android-master`, which is upstream master plus the Android commits and this app) unless they start with `project_files/Android-native/`, and each was checked to exist when this was written. Where something is a reading of the code and was not run, it says so.

## Where the app stands

Update, build 0.3.0-mp1: this survey describes 0.2.0. The app now has an experimental relay that lets phones play each other (README, Network games); the relay is a small Java one written for this app, not the Haskell or Rust server surveyed below. The rest of this page is unchanged.

- The app is hotseat only. One phone runs the engine, all teams play on that phone, and computer teams are played by the engine's AI.
- The engine runs as a client of a local IPC socket on 127.0.0.1. The launcher (`project_files/Android-native/native/launcher/hp_ipc.c`) stands in for the Qt frontend on that socket and serves the game setup from `files/game.ipc`.
- SDL2_net is linked only because the engine uses it for that local IPC socket (`project_files/Android-native/native/CMakeLists.txt`). The app does not open any other connection. (The manifest requests `INTERNET`, which Android requires for any socket, loopback included.)
- There is no LAN play and no way to find or join another device. Without a relay server of some kind, none of the below works: Hedgewars network games are always client, server, client; two engines never talk to each other directly.

## How a Hedgewars network game works

The engine is deterministic lockstep. Every client simulates the whole game from the same setup and the same ordered inputs; nothing about the game state is sent. That is what makes it cheap to carry over a network, and also what makes a mismatch fatal.

**Engine side** (`hedgewars/uIO.pas`, `hedgewars/uCommandHandlers.pas`):

- The frontend starts a network game by sending the engine `TN` instead of `TL` (`ParseIPCCommand` sets `GameType := gmtNet`). The rest of the setup lines (`etheme`, `eseed`, `e$...`, `eaddteam`, `eaddhh`, and so on) are the same as for a local game. In a network game the Qt frontend marks other players' teams with `erdriven` (`QTfrontend/team.cpp`, `teamGameConfig`), which sets `ExtDriven`, so the engine takes that team's moves from the network.
- Every command of the team that is being driven locally is sent out through `SendIPC` (letters such as `L`, `l`, `R`, `r`, `A`, `a`, `j`, `J`, `S`, `,`, slot numbers 128 and up, and `N` for the end of a turn; `isSyncedCommand` lists them). Each carries the low 16 bits of the game tick, and `#` raises the high half. The frontend collects these (`HWGame::ParseMessage` default case, `flushNetBuffer`, `m_netSendBuffer` in `QTfrontend/game.cpp`) and sends them to the server.
- Messages from the other players come back to the engine through the same IPC socket and are queued by tick (`NetGetNextCmd` in `uIO.pas`); the engine runs the game only as far as the ticks it has been given, so a slow player makes everyone wait at their turn.
- Desync detection is in the engines, not the server. At the end of a turn the local engine computes a checksum over the game tick and every gear (`chNextTurn` in `uCommandHandlers.pas`, which calls `gear_checksum` from the Rust library) and sends it inside the `N` message. Every other engine computes the same checksum at the same point and compares it with the one that arrived (`lastTurnChecksum`, set when `N` is read in `NetGetNextCmd`); a difference stops the game with `Desync detected`. Because the checksum depends on the floating point style maths, collision and map generation, all clients must run identical engine code. On this port that code is the Rust library `libhwengine_future` plus the pas2c output, the same as upstream's, which is why the simulation was kept identical to upstream (decision D2 in the party project's log, https://github.com/Gadgit83/hedgewars-party, `docs/decisions.md`). Not tested here: a network game between this build and a desktop build.
- The network protocol number the engine expects is `cNetProtoVersion`, set at build time (`HEDGEWARS_PROTO_VER` is 60 in `CMakeLists.txt`; `hwengine --protocol` prints it).

**Official server, Haskell** (`gameServer/`, built from `gameServer/hedgewars-server.cabal`, entry point `hedgewars-server.hs`; default port 46631 in `gameServer/CoreTypes.hs` and `gameServer/Opts.hs`):

- The protocol is lines of text; a message is a few strings separated by newlines and ended by an empty line (`CONNECTED\nHedgewars server ...\n3\n\n`). It covers nicknames, protocol check, lobby, rooms, teams, scheme and map settings, chat and the game itself. The handlers are `HWProtoCore.hs`, `HWProtoLobbyState.hs`, `HWProtoInRoomState.hs`, `HWProtoNEState.hs` (clients that have not entered the lobby yet), with the actions they trigger in `Actions.hs`.
- Engine traffic travels as `EM` messages: base64 of length-prefixed engine IPC messages (`toEngineMsg` in `gameServer/EngineInteraction.hs`; the Qt client builds the same in `QTfrontend/net/newnetclient.cpp`, `RawSendNet("EM...")`, and decodes incoming ones into `FromNet`).
- The server is a relay, not a simulation. For an `EM` from a client, `handleCmd_inRoom ["EM", msg]` in `HWProtoInRoomState.hs` passes it through `checkNetCmd` in `EngineInteraction.hs`, which keeps only message types from a whitelist (`legalMessages`, the same letters as above plus chat and a few others), rejects a malformed hedgehog-say message, and works out which of the messages are timed. The legal messages are forwarded to all other clients in the room (`AnswerClients chans ["EM", legalMsgs]`), and the non-empty ones are stored in the room (`roundMsgs`, `lastFilteredTimedMsg`) so that a player who joins late, or a spectator, can be sent the whole game so far (`HWProtoLobbyState.hs`, `HWProtoInRoomState.hs`).
- The server does not compute or verify the checksum; it relays `N` like any other timed message. Detecting a desync is left to the engines, as above.
- Room admin, scheme and weapon set changes, votes (`Votes.hs`), flood detection (`FloodDetection.hs`) and the replay checker (`HWProtoChecker.hs`, official server only) are also here.

**Rust port of the server** (`rust/hedgewars-server`, with the protocol in `rust/hedgewars-network-protocol`):

- A tokio based reimplementation of the same server and protocol: `src/main.rs` (listens on 0.0.0.0, port 46631 by default, `-p` to change), `src/server/network.rs` (connections), `src/core/` (clients, rooms, server state), `src/handlers/` (in the lobby, in a room, and so on; `inroom.rs` has the `EngineMessage` handler that does the same validate and forward as the Haskell one). It builds without the official-server features (MySQL, TLS are optional cargo features).
- `rust/hedgewars-network-protocol` holds the message types (`messages.rs`), a nom parser (`parser.rs`) with the framing documented at its top, and tests. Its types are a usable description of the whole protocol and could be used from a Rust or JNI hub. Whether the Rust server is complete enough to replace the Haskell one was not tested here.
- It is the nearest thing to a server that could be embedded in an Android app: a single static binary or library with no GHC runtime, Linux and Android capable in principle (cargo targets are already used for `libhwengine_future.so`). Not built or tried here.

**Qt desktop client** (`QTfrontend/net/newnetclient.cpp`, `.h`, `tcpBase.cpp`; `QTfrontend/game.cpp` for the engine side): connects to the server, checks the protocol (`CONNECTED` and a minimum server version), handles lobby and room messages, and relays `EM` messages between the server and the local engine. A good reference for the order of messages in a real session.

**Old C client library** (`project_files/frontlib/`): a C reimplementation of the frontend's networking and engine IPC for the old Hedgeroid Android port (it has an `Android.mk`; `net/netconn.c`, `ipc/gameconn.c`, `model/`), using SDL_net 1.2 (`CMakeLists.txt` asks for SDL, SDL_net and zlib). It was written against an older protocol version and was not built or tested here; it is not part of this build. Read it as a second description of the client side, not as code to reuse unchanged.

**WebWars gateway** (`upstream/webwars/gateway/src/index.js`, from the WebWars clone that sits next to the engine checkout and is not tracked here): a Node.js process that accepts WebSocket connections and forwards each to a TCP connection to the Haskell server, turning each protocol message into a JSON array of its lines and back (`msg.join('\n') + '\n\n'` one way, splitting on `\n\n` the other). It is a small, working example of the pattern needed to put a browser or any WebSocket client on the existing server without changing the protocol.

## What this means for a party or network mode

No relay server exists in this app, so the first decision is where the relay lives. The options below are ordered by how little they ask of the engine.

1. **Local party, no Hedgewars protocol.** The host phone runs the engine for everything (as now). Controller phones are only input devices: they send button events to the host over a WebSocket on the LAN, the host feeds them to the engine as local touch or key events for the active team, and the TV gets a picture (decision D1 in the same log). No lockstep, no desync problem, no server. This is the plan in the roadmap and needs none of the code above, except possibly the lists of which commands the engine accepts (`doc/protocol.txt` documents the IPC commands, partly in Russian).
2. **Host phone as a Hedgewars server.** Embed the Rust server (`rust/hedgewars-server`, cross compiled for Android) in the app and let desktop or other Android Hedgewars clients join over the LAN with the normal Qt client. The host phone would also run an engine as a client of its own server (as a spectator or a player). The Android app would need a frontend for the lobby messages (`rust/hedgewars-network-protocol` gives the types and parser) and a way to hand the engine its `TN` setup and the `EM` stream, which is what `QTfrontend/net/newnetclient.cpp` and `QTfrontend/game.cpp` do on the desktop. Biggest work item: the client side (lobby, room, team and scheme handling), since the launcher today only serves a fixed setup file.
3. **Join an existing server** (official or self hosted). Same client work as in option 2 without hosting. The app already holds the `INTERNET` permission, and the engine on the phone must match the server's protocol number (`cNetProtoVersion`, 60 in this checkout) and produce checksums identical to the other players' engines. Because this port tracks upstream master and uses the same Rust simulation, that should hold, but it has not been tested.
4. **Web controller pages.** Controller phones need nothing installed if the host serves a page and a WebSocket (the WebWars gateway pattern, in reverse: the host is the server). This works with option 1 and does not conflict with 2 or 3.

Constraints and risks to keep in mind:

- A network game needs every engine on the same version; mixing this port with a desktop build is a desync risk until it has been tried. The checksum check will stop the game cleanly rather than let it go wrong silently.
- A Hedgewars game stops for everyone while one engine waits for inputs, so phones that sleep or lose Wi-Fi stall a game. The engine has a spectator mode (`e$spectate`, `chFastUntilLag`) that fast-forwards a late joiner, which is how the server catches players up.
- TV output is separate from all of this: Hedgewars network protocol carries no video, so a TV still needs the picture from the host phone (D1 in the same log) whichever of the options above is chosen.
- Licensing: the server and protocol code are GPL, like the engine, so anything built from them is GPL too. Fine for this project, but worth knowing before mixing in anything else.

## Not verified

None of the network paths above was run for this survey: no server was built or started, no network game was played, and the old frontlib was not compiled. The descriptions come from reading the code at the paths named, which were all present in the checkout at the time of writing. The checksum behaviour in particular is read from `chNextTurn`, `NetGetNextCmd` and `checkNetCmd`, not observed.
