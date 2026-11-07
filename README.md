# Silent Gear Simulator (NeoForge 1.21.1, client-only)

Press **K** in game (rebindable under Controls → *Silent Gear Simulator*) to open a planner where you
pick a Silent Gear item, choose the material and **grade of every material unit** in each part, toggle
upgrades, and see the **exact tooltip and icon** the crafted item would have on the server you're on.

## Where the data comes from

Nothing is hard-coded. Every value comes from data the server has already sent to your client:

| What | Source |
|---|---|
| Materials, parts, traits | Silent Gear's own sync packets (`SyncMaterialsPayload`, `SyncPartsPayload`, `SyncTraitsPayload`), stored in `SgRegistries.MATERIAL / PART / TRAIT` on the client. Server data packs and add-ons are included. |
| How many materials a part takes, which materials/grades are allowed | The server's `silentgear:compound_part` recipes, which 1.21.1 syncs to every client. Each unit is checked against the recipe's own `PartMaterialIngredient`. |
| Stats, traits, name, model/colours, tooltip | Silent Gear itself. The mod builds a real graded material item, crafts the part with `CompoundPartItem#create` and `PartInstance#from`, then calls `GearItem#construct`. That is the same path `ShapelessGearRecipe` follows on the server. |

When the server re-sends data (for example after `/reload`), the screen notices within a second and rebuilds.

## Modpacks (tested against the ATM10 Aeronautics pack files)

Everything is read from what the server sends, so data-pack/KubeJS materials, traits and recipe
changes are picked up automatically. ATM10 Aeronautics ships Silent Gear 4.2.1.1 + Silent Lib 10.6.0
(what this mod was written against) and also **Silent Gear Metalworks**, which moves metal parts
from the crafting grid into the Productive Metalworks foundry. The simulator handles this:

* Any recipe that outputs a Silent Gear part with a fixed material list (e.g.
  `productivemetalworks:item_casting`) is indexed alongside crafting-grid recipes.
* Materials only obtainable that way still appear in the picker, tagged `cast`, and the part starts
  from **exactly what the recipe outputs** (same unit count, ungraded).
* **Grading cast parts:** Silent Gear's Material Grader can grade the materials *inside* a finished
  part (one unit per pass) when `can_grade_parts` is on **or Silent Gear Metalworks is installed**,
  which it is in ATM10. The simulator applies the same rule, so cast parts get free per-unit grades,
  stored highest-first just as the grader leaves them. Without part grading they stay locked.
* Materials the grid accepts (wood rods, string bindings, gems, ...) keep free per-unit grades.
* **Part substitutes** such as blaze rods and breeze rods (the item itself *is* the rod) are offered too,
  tagged `item`, and built exactly the way Silent Gear converts them. They're single-unit and can't be graded: the item you hold is a plain blaze rod, which has no Silent Gear material data for the grader to work on.
* Materials Silent Gear allows in a part but that no readable recipe names (e.g. Uru tips, which are made
  by a machine that picks the material while it runs) are tagged `other`. They're simulated as if
  crafted normally, with a warning that the route couldn't be verified.

Build against the jars from the pack's own `mods` folder so the compile matches what you play.

## Using it

* **Gear item**: click the box at the top left to open a searchable popup list.
* **Part cells**: one cell per material unit (a pickaxe head has 3).
  * Left-click opens a **material popup** next to the cell. Type to search by **name or trait**
    (e.g. `malleable`), scroll, click a material (or Enter for the first match). Esc or a click outside
    closes it. ✕ removes the part.
  * **Sort** (under the search box): pick any stat the listed materials affect, and toggle ▲/▼.
    Values are what that material gives *this part* at the unit's current grade, and are shown on each row.
    The choice is remembered between popups.
  * Right-click or scroll: raise the grade. Shift+right-click or scroll down: lower it.
  * `All: X`: set every unit's grade at once. `◀ xN ▶`: material count, shown only if the server has recipes for several counts.
* **Material tooltips**: hovering a material in the popup, or a filled cell, shows Silent Gear's own
  material tooltip with the stats expanded and already on **the page for the part you're editing**
  (Tip page for a tip, Rod page for a rod, ...), at the grade of that unit. Use ← / → (or Silent Gear's
  cycle keys, C / Z by default, outside the popup) to look at other part pages.
* **Optional parts** (binding, grip, tip, coating, lining, setting, ...) start empty. Click `+` to add one.
* **Upgrades**: non-material parts (spoon, magnet, wide plate, ...), shown as checkboxes.
* **Mixed materials**: off by default. Standard Silent Gear only crafts parts from one material. Turn it on to plan for add-ons that allow mixing; the preview still warns you.
* **Right panel**: the live tooltip. Hover the big icon for the real hover tooltip.
  * **Stats / Traits / Parts checkboxes** keep Silent Gear's expanded sections open (as if holding
    Ctrl / Shift / Alt) in this screen only, for the preview and the material tooltips. A tiny
    client-side mixin on Silent Gear's `KeyTracker` does this; it does nothing outside the simulator.
* **Status line / red bullets**: anything the server would refuse, such as a missing required part, a material or grade the recipe rejects, a material count with no recipe, or a main material with no durability for that gear type.
* **Give (creative)**: only shown in creative mode. Puts the item in your inventory using the vanilla creative-inventory packet.

## Building

Maven access is needed (NeoForge, Mojang, Parchment).

1. Put the 1.21.1 jars of **Silent Gear** (4.2.x) and **Silent Lib** (10.6.x) into `libs/`. The ones from
   your `mods` folder work. They are compile-only and are never bundled.
2. `./gradlew build` produces `build/libs/sgsim-1.21.1-1.1.0.jar`
3. `./gradlew runClient` launches a dev client with Silent Gear loaded.

Put the built jar in your client's `mods` folder only. The server doesn't need it, and
`displayTest="IGNORE_ALL_VERSION"` means it never blocks joining.

## Known limits

* **Silent Gear's common config is not synced.** `silentgear-common.toml` contains per-property bonus
  multipliers. If a server changed them, copy the server's file into your local `config/` so the numbers match.
* Alloy/compound materials made in Silent Gear's alloy machines, and Starcharged/Charged modifiers, aren't offered as choices yet. Only the grade modifier is applied.
* Recalculation runs with no player, like the crafting recipe. Traits whose bonus depends on the
  holder's current state (health, light level, ...) show their crafted value, just as a freshly crafted item would.

## Code map

```
dev/sgsim/SgSimMod.java              client-only entry point, keybind
dev/sgsim/sim/GearCatalog.java       gear items, part slots, upgrades and valid materials from synced data
dev/sgsim/sim/RecipeIndex.java       compound-part recipes from the client recipe manager
dev/sgsim/sim/SlotDef.java           one part slot + its recipes and acceptance check
dev/sgsim/sim/GearSimulator.java     builds the item through Silent Gear's crafting code and collects problems
dev/sgsim/sim/SimState.java          the player's selections (stored by id, safe across reloads)
dev/sgsim/client/SimulatorScreen.java main GUI
dev/sgsim/client/ListPopup.java      searchable, sortable popup list (gear items, materials)
```

## AI disclosure

This mod was developed with AI assistance. The code, documentation and logo were made with help from Claude, an AI assistant by Anthropic. The logo is pixel art drawn by a script, not by an AI image generator.
