# ClaudeCraft

A Fabric mod for Minecraft 26.3 that lets you play with Claude Code from in-game chat. Claude can see the world and act in it. It designs and places builds, runs any server command, summons mobs, gives items, changes the time and weather, and so on. Nothing is hard-coded: Claude gets general tools and decides how to use them.

## How it works

```
player chat ──► mod ──► `claude -p --resume <session>` (headless Claude Code)
                                   │
                                   ▼ MCP (http://127.0.0.1:<port>/mcp/<secret>)
                         mod's embedded MCP server ──► world (on the server thread)
Claude's replies ◄── stream-json ◄─┘
```

- The mod runs in the integrated server, so it works in singleplayer and in **Essential**-hosted worlds. Only the host needs Claude Code installed, but **every player needs the mod** (and Fabric API), because it adds custom items that clients have to know how to draw.
- Each world has **one shared conversation**, so Claude remembers earlier builds and requests across restarts. Messages are labeled with who sent them and where that player was standing. Messages sent while Claude is busy are batched into its next turn.
- Claude runs with **no built-in tools** (no shell and no file access) and only the `minecraft` MCP tools, all pre-approved. Chat can't make it touch your computer.

## Tools Claude gets

| Tool | Purpose |
|---|---|
| `get_context` | Player position, facing, looked-at block, inventory, health, time, weather, nearby entities, other players |
| `get_terrain` | Surface heightmap plus surface blocks for an area |
| `scan_area` | Read blocks in a box, in the same format `place_blueprint` uses |
| `place_blueprint` | Palette plus layered character grid. Claude's main way to build things it designed itself |
| `draw_shape` | WorldEdit-style sphere, dome, cylinder (any axis), cone, pyramid, torus, polyline and smooth curve (`//curve`), solid or hollow |
| `generate` | Fill a box wherever a math expression is true, like WorldEdit's `//generate` |
| `clone_region` | Copy a box with rotation, mirroring and stacking (`//copy`, `//paste`, `//stack`) |
| `overlay` | Cover the ground following the terrain, on top of it or replacing its top layer (`//overlay`) |
| `smooth_terrain` | Even out bumpy ground into gentle slopes (`//smooth`) |
| `naturalize` | Grass on top, then dirt, then stone (`//naturalize`) |
| `fill_region` | Box fill: solid, hollow, outline or walls, with an optional replace filter |
| `place_blocks` | Individual blocks at exact coordinates |
| `view_build` | Rendered picture of an area (isometric from any of four corners, or top-down) so Claude can see how its build looks |
| `wait_for_builds` | Wait for queued builds to finish and get their results |
| `define_item` | Invent a custom item: a voxel model (pixel art or 3D) plus behavior (damage, food, durability, right-click commands...) that every player sees instantly |
| `define_block` | Invent a custom block: a painted cube like a vanilla block, or a small voxel shape, with light, hardness, sound, collision and right-click commands |
| `give_item` | Give a player a custom item or block |
| `view_item` | Picture of a custom item's or block's model |
| `run_commands` | Any command, run as the requesting player (`@s`, `~`), at a capped permission level |
| `undo` | Revert the last turn's block changes |

Any `block` argument, and blueprint palette entries, can be a WorldEdit-style random mix such as `70%stone_bricks,30%mossy_stone_bricks`. Every placement tool also takes a WorldEdit-style `mask` that limits which blocks it may change, such as `minecraft:air` (never overwrite anything), `#minecraft:logs`, or `!#existing >#solid` (air resting on solid ground). Masks are checked as each block goes down.

### Custom items and blocks

Claude can invent items that don't exist in Minecraft and hand them out on the spot: a frost sword, a healing berry, a teleport wand. Each one is a voxel model Claude designs (16x16 pixel art like vanilla items, or a small 3D model) plus behavior: melee damage, food, durability, stack size, glint, rarity, and commands that run on right-click. Definitions are saved with the world and sent to every player as soon as they're made, so new items appear instantly with no resource pack or restart.

Custom blocks work the same way: Claude paints a cube's faces like a vanilla 16x16 texture, or sculpts a smaller shape (a lamp, a crystal), and sets its light, hardness, sound, collision and right-click commands. They're drawn straight into the world's chunk meshes like vanilla blocks, and Claude can use them anywhere in its building tools as `custom:<id>`. Redefining a block redraws every copy already placed.

Under the hood there's one registered item, `claudecraft:custom_item`, and each stack names its definition in a `claudecraft:definition` component, because Minecraft can't register new items while it runs. You can also give one with `/give @s claudecraft:custom_item[claudecraft:definition="ruby"]`, though `give_item` also applies the item's behavior. Blocks are one registered block, `claudecraft:custom_block`, with a numbered `variant` state per design (up to 256 per world).

### Builds you can watch

Placement calls return immediately and the blocks animate in the background, one queued build after another, while Claude keeps designing. Claude queues the part it already knows first (the site, a foundation, the base of the legs), often with a longer animation, and works out the rest while that goes up, so players see progress right away.

- **Animated placement.** Each build goes up a few blocks at a time, bottom to top, sweeping each layer row by row like a 3D printer.
- **Sounds and particles.** Every block plays its own placement sound at its position with a puff of particles. Clearing plays break sounds, top-down. Undo animates the same way.
- **Pacing.** The default is 40 blocks a second, and big builds speed up so none takes longer than 12 seconds. Claude can set `duration_seconds` on any build to stretch it out while it designs the next part. Results, including any problems, come back with Claude's later tool calls, and Claude calls `wait_for_builds` before announcing it's done.
- **Status line.** While Claude is working, the action bar shows a Claude Code-style orange spinner with what it's doing, the elapsed time and how many blocks are left: `✻ Building… (23s · 1,204 blocks to go)`.
- **Correct shapes.** Fences, panes and stairs connect as they appear. Placement order doesn't matter: a torch can go up before the wall it hangs on. A final pass works the way structure loading does, and anything still unsupported, such as half a door, is reported back to Claude so it can fix it.

## Install

1. Install [Claude Code](https://claude.com/claude-code) on the host machine and log in once by running `claude` in a terminal.
2. `./gradlew build`, then copy `build/libs/claudecraft-<version>.jar` into `.minecraft/mods` next to Fabric API.
3. Start a world and type in chat.

The mod looks for `claude` on `PATH`, in `~/.local/bin` and in `~/.claude/local`. If yours is installed elsewhere, set `claudePath` in the config.

## In-game commands

- `/claude ask <message>`: talk to Claude without the chat trigger. Also works from the server console.
- `/claude stop`: stop the current turn, halt any build in progress (blocks already placed stay and can be undone), and clear the queue.
- `/claude undo [steps]`: revert Claude's block changes. One step is one turn.
- `/claude reset`: start a fresh conversation.
- `/claude status`

## Config: `config/claudecraft/config.json`

| Key | Default | Meaning |
|---|---|---|
| `triggerPrefix` | `""` | Empty sends every chat message to Claude. Set it to something like `"@claude"` to only send messages that start with that |
| `allowedPlayers` | `[]` | Empty lets everyone talk to Claude. Otherwise list the player names allowed to |
| `commandPermissionLevel` | `2` | Gamemaster level: `/give`, `/fill`, `/tp`, `/summon` and so on are allowed. `/op`, `/ban` and `/stop` need level 3 or 4 |
| `model` | `""` | Passed to `--model`. Empty uses your Claude Code default |
| `extraArgs` | `[]` | Extra CLI args, e.g. `["--effort","high"]` or `["--max-budget-usd","1"]` |
| `turnTimeoutSeconds` | `900` | Kills a turn that runs longer than this |
| `maxBlocksPerCall`, `maxScanVolume` | `100000`, `32768` | Safety limits |
| `animateBuilds` | `true` | `false` places every stage instantly |
| `buildBlocksPerTick` | `2` | Minimum animation speed (20 ticks per second) |
| `maxBuildSeconds` | `12` | Large stages speed up to finish within this time |
| `buildEffects` | `true` | Block sounds and particles while animating |
| `showToolActivity` | `true` | Shows the animated status line in the action bar while Claude works |

`config/claudecraft/system-prompt.md` is Claude's guidance on chat style, pacing builds, coordinates and building quality. It's re-read every turn, so you can tweak it while the game is running. Mod updates refresh it automatically unless you've edited it. Your edits are never overwritten.

## Notes and limitations

- Every chat message that gets sent to Claude is a Claude Code turn and uses your plan or API usage. With two people chatting, consider a `triggerPrefix`. Claude is told to reply `NO_REPLY` (hidden) when a message isn't meant for it, but that still costs a turn.
- Undo history is kept in memory. It's lost when the world closes, and it doesn't cover blocks changed with raw `/fill` or `/setblock`, or chest contents.
- The MCP endpoint only listens on localhost, behind a random per-launch secret URL.

## Development

`./gradlew runServer` starts a dev server. Typing `claude ask build a tower at 0 ~ 0` in its console exercises the whole pipeline with no client needed. The JDK 25 toolchain is downloaded automatically if your system Java has no compiler.

`java src/main/java/dev/claudecraft/Shapes.java` (likewise `Renderer.java`) runs a quick self-check of the shape math or the build renderer.

`xvfb-run -a ./gradlew runClientGameTest` (with `WAYLAND_DISPLAY` unset, so the game uses the virtual display) launches the real game headless, defines a few custom items, checks their names and right-click commands, and saves screenshots to `build/run/clientGameTest/screenshots`.
