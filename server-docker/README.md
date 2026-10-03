# AgentCraft dedicated server (Docker)

A Fabric 26.3 server in [itzg/minecraft-server](https://docker-minecraft-server.readthedocs.io) that
hosts the studio for several players. Players need only the AgentCraft mod (and Fabric API) in their
own client; the Foreman runs once, on the server machine.

## Run it

```sh
# 1. the mod jar
(cd ../mod && sh ./gradlew build) && mkdir -p mods && cp ../mod/build/libs/agentcraft-*.jar mods/

# 2. settings: players, world type, game mode (all optional)
cp .env.example .env

# 3. the Foreman on this machine (sim = no API usage), then the server
../tools/launch.sh --no-game --backend sim     # or --repo /path/to/repo [--use-claude-login]
docker compose up -d
docker attach agentcraft-mc                   # server console; detach with Ctrl-P Ctrl-Q
```

Fabric API is downloaded automatically. The first start downloads Minecraft and Fabric, creates the
world and builds the HQ (a few seconds on the classic meadow, about 15 s on real terrain while the
site is chosen).

Players connect with **Multiplayer → Add Server** and the host's LAN IP (`localhost` on the host
itself); everyone, you included, must be in `MC_WHITELIST`. Installing the mod in a regular launcher,
firewall notes and the Open to LAN alternative: [docs/multiplayer.md](../docs/multiplayer.md).

## Who can do what

- `MC_WHITELIST`: who may join. `MC_OPS`: ops.
- **Ops** (and names or UUIDs in `data/config/agentcraft-allowlist.json`, a JSON array, re-read when
  it changes) may give goals, answer decisions and merge. Everyone else can watch and review diffs.
- **The HQ site is protected**: only ops can break or place blocks inside the area the HQ builder
  owns (building, grounds, pond, garden). `/agentcraft protect off` lifts that.
- Every forwarded action is logged with the player's name (`[relay] Alice -> decision.answer ...`).

## World options

Set in `.env` before the first start (an existing `data/<MC_LEVEL>` keeps its own settings):

| Setting | Default | |
|---|---|---|
| `MC_LEVEL_TYPE` | `minecraft:flat` | `minecraft:normal` for real terrain (also set `MC_STRUCTURES=true`) |
| `MC_SEED` | random | |
| `MC_GAMEMODE` | `creative` | `creative` = calm studio rules; `survival` = vanilla play around a protected HQ |
| `MC_DIFFICULTY` | `peaceful` | |
| `MC_HARDCORE` | `false` | |
| `AGENTCRAFT_HQ_SITE` | `auto` on real terrain | `spawn`, `X,Z` or `X,Y,Z` to place the HQ yourself |

In game (ops): `/agentcraft mode studio|survival`, `/agentcraft protect on|off`, `/agentcraft hq`
(rebuild in place).

## Why `network_mode: host`

The server connects to the Foreman on `ws://127.0.0.1:7878`, and the Foreman accepts loopback only.
Sharing the host's network namespace keeps it that way: the Foreman is never reachable from the LAN.
The game port (25565) binds on every interface, as a native server would.
