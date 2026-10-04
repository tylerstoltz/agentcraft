# Playing in your own launcher, and with friends

`tools/launch.sh` / `tools\launch.ps1` run the Fabric **development client** from this checkout. This
page covers the other ways to play:

1. [The mod in your regular Minecraft launcher](#1-the-mod-in-your-regular-minecraft-launcher)
2. [Friends on the Docker dedicated server](#2-friends-on-the-docker-dedicated-server)
3. [Friends on your world with "Open to LAN"](#3-friends-on-your-world-with-open-to-lan)

In every setup the **Foreman runs once, on the hosting machine**, next to your repo. Other players
need only the mod: their client reaches the Foreman through the Minecraft connection (the server's
relay), so the Foreman stays bound to `127.0.0.1` and is never exposed to the network.

## 1. The mod in your regular Minecraft launcher

**Everyone who plays needs:** Minecraft: Java Edition 26.3, Fabric Loader 0.19.5 or newer, Fabric API
for 26.3 (0.161.0+26.3 or newer) and the same AgentCraft jar. The official launcher brings its own
Java, so players do not need a JDK.

### Build the jar (once, on the machine with the checkout)

```sh
cd mod
sh ./gradlew build         # Windows: .\gradlew.bat build
```

This needs JDK 25 (if Gradle picks an older Java, set `JAVA_HOME` to the JDK 25 folder first). The
jar is `mod/build/libs/agentcraft-0.1.0.jar`; copy that one, not `-sources.jar`. Hand the same file
to your friends.

### Install Fabric and the mods

1. Run the [Fabric installer](https://fabricmc.net/use/installer/), pick Minecraft **26.3** and
   install the client. The launcher gets a `fabric-loader-26.3` installation.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3.
3. Put both jars in the `mods` folder (create it if it does not exist):

   | OS | `mods` folder |
   |---|---|
   | Windows | `%APPDATA%\.minecraft\mods` |
   | macOS | `~/Library/Application Support/minecraft/mods` |
   | Linux | `~/.minecraft/mods` |

   ```sh
   cp mod/build/libs/agentcraft-0.1.0.jar ~/.minecraft/mods/     # Linux example
   ```

### Set the launch options

The mod's defaults suit the scripted development client: it **mutes the game**, opens without taking
focus, jumps straight into the HQ world and opens the DevBridge screenshot API on `127.0.0.1:7879`. For
everyday play, in the launcher go to **Installations**, edit `fabric-loader-26.3`, open
**More options** and append to **JVM arguments**:

```
-Dagentcraft.mute=0 -Dagentcraft.focus=1 -Dagentcraft.dev=0
```

Any `AGENTCRAFT_*` setting from [mod/DEV.md](../mod/DEV.md) works this way: lower case, `_` becomes `.`
(`AGENTCRAFT_WORLD=normal` is `-Dagentcraft.world=normal`). Add `-Dagentcraft.autoworld=0` if you want
the title screen instead of being dropped into the `AgentCraft HQ` world on start; you then open it
from **Singleplayer** yourself. The first start without that flag creates the world (the creation
switches such as `-Dagentcraft.world=normal -Dagentcraft.gamemode=survival` apply only then). A world
you create by hand is an HQ world when you name it `AgentCraft HQ`.

### Start the Foreman, then the game

The game is your launcher now, so start only the Foreman from the checkout:

```sh
tools/launch.sh --no-game --backend sim               # simulated team, no API usage
tools/launch.sh --no-game --repo /path/to/your/repo   # real agents (add --use-claude-login if wanted)
tools/stop.sh                                         # later; tools/stop.sh --profile sim for sim
```

On Windows: `tools\launch.ps1 -NoGame -Backend sim` or `tools\launch.ps1 -NoGame -Repo C:\path\to\repo`,
and `tools\stop.ps1`.

Then start `fabric-loader-26.3` from the launcher. The order does not matter: the mod keeps
reconnecting to `127.0.0.1:7878` and the HUD shows "Reconnecting to the Foreman" until it is up.

## 2. Friends on the Docker dedicated server

[server-docker/](../server-docker/README.md) runs a Fabric 26.3 server with the mod. On the host:

```sh
(cd mod && sh ./gradlew build) && mkdir -p server-docker/mods && cp mod/build/libs/agentcraft-0.1.0.jar server-docker/mods/
cp server-docker/.env.example server-docker/.env      # then edit it, see below
tools/launch.sh --no-game --backend sim               # or --repo /path/to/repo
cd server-docker && docker compose up -d
```

In `.env`:

- `MC_WHITELIST=YourName,Alice,Bob`: the server runs with a **whitelist**, so everyone who joins,
  you included, must be listed. Otherwise they are kicked with "You are not white-listed".
- `MC_OPS=YourName`: ops may give goals, answer decisions, merge, and build on the HQ site. Players
  who are not op can still watch and review diffs. To let someone drive the agents without making
  them op, add their name to `server-docker/data/config/agentcraft-allowlist.json`, e.g.
  `["Alice"]` (re-read when the file changes).
- `AGENTCRAFT_RELAY_WATCH=trusted` (optional): watching shows agent logs, permission prompts and the
  code in every worktree. With `trusted`, players who are neither op nor allowlisted get no studio
  view at all. The default `all` lets every player who joins watch. Change it with
  `docker compose up -d` (recreates the container). A server run without Docker takes it as an
  environment variable or as `-Dagentcraft.relay.watch=trusted` on its `java` command line.

Whitelist and ops are applied when the container starts; for a running server use the server console
(`docker attach agentcraft-mc`, detach with Ctrl-P Ctrl-Q): `whitelist add Alice`, `op Alice`.

**Connecting.** Each player starts the `fabric-loader-26.3` installation from section 1 (no Foreman,
no checkout needed), then **Multiplayer → Add Server**:

| Who | Server address |
|---|---|
| You, on the host machine | `localhost` |
| Players on your network | the host's LAN IP, e.g. `192.168.1.20` (add `:PORT` if you changed `MC_PORT`) |

A dedicated server does not appear in the "LAN worlds" list; add it by address. Find the host's IP
with `hostname -I` (Linux), `ipconfig` (Windows, "IPv4 Address") or `ipconfig getifaddr en0` (macOS).
If others cannot connect, allow TCP port 25565 in the host firewall (Linux with ufw:
`sudo ufw allow 25565/tcp`). Do not open the Foreman port (7878): the server reaches it on loopback.

## 3. Friends on your world with "Open to LAN"

You can also host straight from your own game, with no server process:

1. Start the Foreman (section 1) and open your HQ world in the launcher.
2. Press <kbd>Esc</kbd> → **Open to LAN**. Leave **Allow Commands** OFF: ON gives every guest vanilla
   cheat commands (`/fill`, `/give`, ...), which can wreck the HQ whatever its protection says. AgentCraft
   does not treat it as trust either. Pick a **Port Number** (a fixed one such as `25565` is easier to put through a firewall), then
   **Start LAN World**. Chat shows the port.
3. Friends on the same network, with the same mods installed, open **Multiplayer**. The world shows up
   under "LAN worlds" after a few seconds. If it does not (Wi-Fi client isolation, VPNs and some
   routers block the discovery broadcast), they use **Direct Connection** with `HOST-IP:PORT`, e.g.
   `192.168.1.20:25565`.

What changes compared to a dedicated server:

- **You** reach the Foreman directly, as in singleplayer, and can do everything. Your client also
  drives the HQ's lamps, monitors and podium for everyone.
- **Guests** go through the relay inside your game. They can watch and review diffs. To let a guest
  give goals, answer decisions and merge, put their name in `config/agentcraft-allowlist.json` inside
  your Minecraft folder (for example `~/.minecraft/config/agentcraft-allowlist.json` containing
  `["Alice"]`). It is re-read when it changes and keeps applying in later sessions. (`/op` exists only
  on dedicated servers, so on a LAN world the allowlist is the way to trust someone.)
- **Who may watch**: every guest by default. To show the studio only to allowlisted guests, start
  *your* game (it is the server) with `-Dagentcraft.relay.watch=trusted` in the launcher's JVM
  arguments, or `AGENTCRAFT_RELAY_WATCH=trusted tools/launch.sh ...` with the dev launcher. It is read
  at startup, so restart the game to change it.
- **HQ protection** applies: guests cannot change anything on the HQ site, and allowlisted guests
  cannot either: driving the agents does not include building. `/agentcraft protect off` lifts it.
- The world is only up while you are in it. When you quit, guests are disconnected; the Foreman and
  the agents keep working.
- Allow your chosen port through the firewall, as for the dedicated server. If your OS asked whether
  Java may accept connections, allow it on private networks.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Disconnected while joining (missing registry entries or mods) | The guest lacks Fabric API or AgentCraft, or has a different AgentCraft build. Everyone should use the same jar |
| HUD stays on "Reconnecting to the Foreman" | The Foreman is not running on the **host** machine, or runs on another port (the mod and server use `AGENTCRAFT_PORT`, default 7878). A guest's link then reports "Foreman unreachable from the server" |
| Guest's goal or answer is refused: "Only ops or allowlisted players can do that on this server." | Op them (dedicated server) or add them to `agentcraft-allowlist.json` (see above) |
| Guest sees "Watching the studio needs op or the allowlist on this server." | The server runs with `AGENTCRAFT_RELAY_WATCH=trusted` |
| Game is silent | Launched without `-Dagentcraft.mute=0` |
| "You are not white-listed" (Docker) | Add the name to `MC_WHITELIST` and restart, or `whitelist add <name>` in the server console |
