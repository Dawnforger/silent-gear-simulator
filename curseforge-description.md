## Silent Gear Simulator

Plan a Silent Gear tool before you spend the materials. Press **K** in game to open a planner where you pick a gear item, choose the material and **grade of every material unit** in each part, toggle upgrades, and see the **exact tooltip and icon** the crafted item would have on the server you're playing on.

### Uses the server's own data

Nothing is hard-coded. Materials, parts, traits and recipes all come from what the server has already sent your client, so data packs, KubeJS changes and add-ons are included automatically. The item is built through Silent Gear's own crafting code, the same path the server uses when you craft it. After `/reload` the screen picks up the changes within a second.

### Features

- **Searchable gear and material lists.** Search materials by name or by trait (for example `malleable`), and sort them by any stat they give the part you're editing.
- **Per-unit grades.** A pickaxe head has 3 material units. Each one can have its own material and grade. Right-click or scroll to change a grade, or set all of them at once.
- **Material tooltips** open on the page for the part you're editing (Tip, Rod, ...), at that unit's grade.
- **Optional parts and upgrades**: bindings, grips, tips, coatings, plus non-material upgrades such as the spoon or magnet.
- **Live preview tooltip**, with checkboxes that keep the Stats, Traits and Parts sections expanded.
- **Validation.** Missing required parts, materials or grades a recipe rejects, and invalid material counts are listed in red.
- **Modpack aware.** Supports casting recipes (for example Silent Gear Metalworks in ATM10), part substitutes like blaze rods, and grading of cast parts when the pack allows it.
- **Give (creative)** puts the planned item straight into your inventory.

### Requirements

- NeoForge 1.21.1
- Silent Gear 4.2.x and Silent Lib 10.6.x

**Client-side only.** The server doesn't need it, and it never blocks joining a server.

### Known limits

- Silent Gear's common config isn't synced. If a server changed the stat multipliers in `silentgear-common.toml`, copy its file into your local `config/` folder so the numbers match.
- Alloy/compound materials and Starcharged/Charged modifiers can't be picked yet.
- Traits that depend on the holder's current state (health, light level, ...) show their freshly crafted value.

### AI disclosure

This mod was developed with AI assistance. The code, documentation and logo were made with help from Claude, an AI assistant by Anthropic. The logo is pixel art drawn by a script, not by an AI image generator.
