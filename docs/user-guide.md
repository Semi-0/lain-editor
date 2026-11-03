# Editor user guide

Application files and examples mentioned below live in the configured backend project. The browser application itself is maintained in this repository. See [running.md](running.md) for standalone commands.

- Click or focus a block to select it. Its border highlights the selection;
  blocks have no title bars or visible Run, Split, Close, or Add buttons.
- **Cmd/Ctrl+K** or right-click opens the command palette for the selected block.
  Search commands and press Enter, use the arrow keys, or click a command.
  Escape or clicking outside dismisses it without changing the block.
- **Cmd/Ctrl+Enter** runs the selected block. Run prepares a complete replacement;
  a failed compilation keeps the previous running program. Parse and compiler
  errors from the base source, edited applied extensions, or a new extension are
  shown in the bottom status bar for the selected block. Another block's success
  or a save message does not hide that error; a successful run of the failed block
  clears it. Hover the status text to read the complete error.
- **Open file…** browses `.lain`, `.clj` and `.txt` files in the restricted server
  workspace, starting at its root. Opening creates a new draft block and does not
  run code. Use Directory, Up, and Filter files to navigate. **Upload file…** is
  optional: it saves a server copy before opening it.
- **Save file…**, or **Cmd/Ctrl+S**, saves current source, edited applied extensions
  and the pending extension draft as a `.lain` file in the server workspace.
  Paths are workspace-relative; missing directories are created. Existing files
  require **Replace existing file**. Saving does not run code. Paths outside the
  workspace and paths through symbolic links are rejected.
- **Download file** and **Download workspace** create local browser downloads.
  They also work while the remote connection is paused.
- **Split side by side / Split stacked** subdivide the selected tile with a new
  independent empty block. **Spawn new block** adds an empty block beside the
  entire layout. **Close block** closes the selection; the final block is kept.
  Drag dividers to resize tiles or the textarea handle for more editing space.
- The workspace header is hidden until the pointer approaches the top edge.
  Keyboard focus also reveals it. Its Commands button opens the same palette.
- **Toggle extension editor** in the palette reveals the extension draft;
  **Apply extension** adds it to that tile's existing bindings and
  topology. Successful extensions are retained in order. It is an append
  operation, not hot replacement of an individual definition or file.
- **Close extension editor / Reopen extension editor** hide or reveal each saved
  extension editor without changing its code or running anything. These commands
  also work after a backend restart or a workspace import. **Remove extension
  from runtime…** is available for extensions in an active session API run and
  confirms a restart of the run and remaining effects. If there is no matching
  live runtime, it stays visible but disabled with an explanation; Run explicitly
  to start the saved program again.
- **Spawn slot** scans the selected block's current application declarations and
  applies one ordinary extension defining its unresolved callable bindings. For
  `(consume-task agents queue)`, it generates
  `(define consume-task (network [input-1 input-2] (define out) out))`.
  Empty data inputs are left alone; generated outputs remain unknown. Repeated
  calls share one stub, and conflicting input counts reject the whole operation.
  Computed callables that already have a producer and nested lexical parameters
  are reported rather than overwritten. Run source/extension edits first and
  clear or apply an extension draft before using the command. The command is for
  ordinary blocks; incremental development mode retains its separate commit path.
- **Toggle display layers** in the palette opens the Layers panel, which controls
  Code, Result, Topology, and Cell details.
  Toggle visibility, use the up/down buttons for top-to-bottom order, and choose
  Overlay or Stack. The output binding setting is inside this panel.
  Palette commands reveal Value, Cell details, Topology, and select trace direction.
  Values and node inspection share the same SVG structural renderer as graph nodes.
  Records and layers use centered mini grids; linked lists use boxes and connecting dots.
  Clicking a cell opens formatted Strongest and Content sections with a Close button.
  New semantic snapshots retain cell content for replay; older frames without it show
  “not recorded”. Identity, connections, and ownership remain in Runtime details.
  Topology uses the existing relationship/dataflow semantic projection, followed
  by upstream, downstream, or both-direction tracing.
  The shared Leapfrog projection presents transformations and returned-member
  connections. Private expression results flow directly into their named binding,
  with definition aliases retained as metadata; the editor adds no separate tracer augmentation. Root views keep
  calls opaque. Opening a call enters exactly one application level.
- The topology is an SVG graph laid out by Vijual Standard Stress. Drag nodes, pan the background,
  scroll to zoom. **Compact topology** is available in the command palette. Select a node for actual runtime metadata. Select
  a call and **Open this call body** to inspect that occurrence. **Return to root topology** in the palette returns to
  the display cell's trace. Display panels refresh every two seconds while visible.
- **Open workspace…** browses server workspace `.json` files. It restores layouts,
  titles, drafts, display settings and ordered extensions without executing them;
  use Run explicitly. **Upload workspace…** saves a server copy before opening.
  **Save workspace as…** chooses a workspace-relative `.json` path and makes it
  current; later **Save workspace** saves to that path. Existing targets require
  explicit replacement when choosing a path. Browser recovery storage preserves
  drafts and the current server path across refreshes. Runtime reconnect references
  stay separate from portable workspace documents.

JSON is a **source replay document**, not a serialized JVM or heap snapshot.
Clock timestamps, external effects, and transient runtime inputs are not frozen.
Loading a file stores its source and name; it does not create a file watcher.

The command palette contains **Hide or show visualizer**, **Fill or restore
visualizer**, and **Toggle fullscreen visualizer**. They apply to the current
value, topology, or cell-details output without adding a permanent pane toolbar.
Fullscreen hides the code temporarily; Escape restores the previous presentation.
These commands reuse mounted views and do not change source, runtime state,
saved layer preferences, or graph camera. Presentation is temporary and resets
when the pane is remounted.

Select a block and open **Visualizer settings…** with ⌘K / Ctrl+K to adjust
node spacing, layout budget, refinement, and value folding. These controls stay
out of the graph canvas. Resize the code and graph areas by dragging their dotted
divider or using the **Graphical view size** slider in those settings. A focused
divider supports Up/Down arrows and Home/End. The size is local to the pane and
is not saved in workspace files.

### Open editor files through POST requests

The running browser polls a tab-scoped command channel. List connected tabs:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data '{"op":"workspaces"}' http://127.0.0.1:4782/api
```

Copy the target `workspace` identifier, then submit a project-relative file:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data '{"op":"load-file","workspace":"WORKSPACE-ID","path":"examples/lain/top_down/stack-design.lain"}' \
  http://127.0.0.1:4782/api
```

This adds a **new pane** in that tab's current desktop, with the same source/name
transition used by the file picker. Existing drafts and runtime sessions are
preserved. It does **not execute** the source; Run remains explicit. The response
is only `queued`, not proof of display. Query the returned request identifier:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data '{"op":"load-status","workspace":"WORKSPACE-ID","request":"REQUEST-ID"}' \
  http://127.0.0.1:4782/api
```

Statuses are `queued`, `delivered`, `applied`, or `failed` with an explanation.
`applied` means the browser acknowledged the source insertion, not evaluation.
Changing desktop before delivery rejects the request rather than inserting it
elsewhere. Disconnected tabs expire after 60 seconds without polling; refresh
creates a new identifier. The queue allows 16 outstanding requests per tab and
retains the most recent 48 completed requests on subsequent submissions. Loads
allow only project-local `.lain` files up to 1 MiB, reject symlink traversal, and
retain the existing loopback-only and same-origin HTTP policy. Workspace
snapshots expose pane names and identifiers, not unsaved source. This is a local
development API, not an authenticated remote-control service.

Closing a block archives its exact editable source descriptor in the workspace
after runtime shutdown succeeds. Use the **Reopen closed block** entries in the
workspace menu or command palette. Reopening creates a fresh pane in **Not run**
state; Run explicitly replays the archived base source and extension sequence.
Archives are saved in workspace JSON, hold at most 64 entries, and contain no
runtime session or generation capability.

The same acknowledged channel exposes metadata-only listing and reopen requests:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data '{"op":"closed-blocks","workspace":"WORKSPACE-ID"}' \
  http://127.0.0.1:4782/api

curl -sS -H 'Content-Type: application/json' \
  --data '{"op":"reopen-closed","workspace":"WORKSPACE-ID","archive":"ARCHIVE-ID","desktop":"DESKTOP-ID"}' \
  http://127.0.0.1:4782/api
```

Query completion with `reopen-status`. Lost deliveries are idempotent under the
request's assigned fresh pane identity. Metadata listing includes title,
filename, archive identity, and close order, never archived draft text.

Use one browser workspace per server process: tabs connected to the same process
share its editor-session registry. Closing a tile stops its clocks and traces;
closing the server releases all owned sessions. Closing only the tab leaves the
server alive so the saved workspace can be reloaded.

The server executes local Lain with the normal runtime capabilities. Transactional
preparation protects editor/runtime state from compilation failure; it does not
undo external effects invoked by program code. The editor uses the JVM compiler, scheduler, and semantic-tracing operators described
below. File and networked environment prototypes remain opt-in.

Frontend checks run in this repository with `clojure -M:test`, `clojure -M:editor/cljs-test`, and `node target/lain-editor/tests.js`. Backend integration tests remain in datalog-research.

## Program-driven display

`def-cells` declares one or more empty named cells, equivalent to consecutive
`define` forms. `relationship:dataflow` returns its result cell and can be nested,
passed as an operator, or called with an explicit output for compatibility.

```clojure
(def-cells input raw)
(-> 5 input)
(relationship:roots input raw)
(display (relationship:dataflow raw))
```

`display` is an editor extension operator with one input. It forwards live content
using the same layered transport as `->` and returns the pane's display cell.
Graph values reveal Topology; other values reveal Result. Each pane has one sink;
multiple incompatible writes obey ordinary cell merge/contradiction semantics.
Run, Extend, and workspace replay select this sink automatically. The saved output
binding `@display` denotes it; selecting another binding in Layers still allows
explicit inspection. Panes without a display call retain their configured target.
A pending source stays pending, and later propagation updates the display.
No host callback or extra global display registry is involved.

The [AMB learning workspace](https://github.com/Semi-0/datalog-research/blob/main/examples/lain/amb/editor/README.md) adds
opt-in pane-local search bindings, an explicit no-good display view, and recorded
learning playback through the existing history/reducer operators. The solver
continues to own learning and repair; the visualizer only projects its values.

### Premise-versioned development mode

**Toggle incremental development** in the palette opens each pane's opt-in
development panel. The ordinary
isolated-session path remains the default. Enabling the panel starts a separate
`:versioned-premise` compiler session and persists only its portable source and
commit ledger in workspace JSON. **Commit current source**, or Cmd/Ctrl+Enter
while the mode is active, replaces that block's premise version while retaining
the running network. Failed or stale commits retain the last successful ledger
and generation. Returning to ordinary mode releases the live development
session; reopening or restarting reconstructs it by replay rather than restoring
JVM capabilities.

Development transitions remain pure and declare effects. Controllers own HTTP and lifecycle; Missionary synchronizes derived presentation state, and Replicant renders pure Hiccup.

## Semantic tracing examples

Open `examples/lain/semantic_tracing/workspace.json` for eight runnable panels:
unary, binary, three-input application, true/false branching, accumulator recursion,
two-output application, and a returned closure with a captured binding.
Each example declares output cells with `def-cells` and directly calls
`(display (relationship:roots-semantic seed))`. Declarations and expressions
are written on separate lines.
`relationship:roots` also returns a cell as an ordinary callable expression;
`(relationship:dataflow (relationship:roots seed))` is the explicit composition.
Both observers preserve their explicit-output forms. See
`examples/lain/semantic_tracing/README.md` for results and call-body navigation.

## Desktops and Vijual Standard Stress layout

Fresh startup contains one blank block on desktop **1**, with no example programs.
The **New workspace** palette command returns to this blank state.
The always-visible top strip shows only desktop numbers and selects desktops. **Cmd/Ctrl+K → New desktop**
creates an independent desktop with one empty block. **Switch to desktop: …**
selects a desktop from the palette. Selection and split ratios are retained per
desktop. Switching unmounts only browser views; programs and clocks on hidden
desktops continue. Each pane belongs to exactly one desktop, with at least one
pane retained per desktop. Save/Open JSON includes all desktops and their programs.
Version 1 files import as Desktop 1; new saves use version 2 with a global `panes`
array, `desktops` containing names/layouts/selection, and `activeDesktop`.

The editor uses Standard Stress via
`graph.vijual.layout.nodes/create-layout` with `:stress-method :standard`, and uses
`graph.vijual.layout.nodes/layout-flow`, the documented core.async
adapter over the existing incremental scan/emit state machine. There is one
owned flow per pane's current generation and inspected call occurrence. Complete
node upserts retain the last public positions. Edge additions/removals are sent
as transactions; released drags submit position upserts without persistent pins. Each command
requests at most 300 relaxation iterations when node IDs, directed edge membership,
or rendered node dimensions change. Size changes reuse cached graph distances.
Dragging and label updates with unchanged dimensions use zero stress relaxation
iterations. Hard clearance can reposition conflicting neighbors. Input reordering is not a layout
change. Zero-iteration transactions still validate and update node metadata and
report geometric diagnostics; they do not run stress sweeps. Commands return Vijual's public revision,
nodes, signed deltas, and metrics. The HTTP client uses the full public snapshot
so canceling an older reply cannot lose a delta. Distances and numeric caches are
retained by Vijual; the browser does not implement a second solver. Standard
Stress uses size-aware graph-distance targets without Balanced's extra
equal-edge-length, axis-alignment, inferred mirror, or clearance preferences.
Standard was retained after the user's visual comparison found its AMB graph
output better than Balanced's; this is a workspace-specific observation, not a
general benchmark result. Size-triggered relaxation responds to growing cards,
but Standard's distance targets remain soft. A separate hard-clearance stage
uses exact rendered rectangle dimensions and a 28-unit gap. It leaves clear
positions unchanged and greedily relocates conflicts; it is independent of the
stress method and camera, not a new stress objective. Corrected final positions
are written back through public zero-iteration flow updates so later requests
do not resurrect the pre-clearance geometry. `:exact-clearance-moves` reports
corrections. This guarantees node separation, not crossing-free edges or optimal
packing.
The editor retains the same 28-pixel node gap, 110-pixel edge target and 0.05
displacement tolerance for comparison. New nodes start from Vijual’s layered ordering
before stress relaxation; existing nodes retain their positions; drag anchors last only while held. The
300-iteration budget is preserved. These are soft
constraints, so conflicting topologies or pinned positions may retain crossings
or tilted edges. See [the solver contract](https://github.com/Semi-0/datalog-research/blob/main/graph/vijual/layout/README.md#balanced-stress).

On graph mount, the server captures actual solver milestones in batches of up to
10 iterations, within the same 300-iteration limit. Published milestones pass
through hard clearance without changing the underlying stress solve. The browser interpolates those
samples over 1.1 seconds without owning or changing the camera. Initial layout
is framed once, unless the user has already interacted with the viewport.
Node payload or relationship changes briefly fill affected nodes and fade out
over 900 milliseconds. Unchanged polling preserves the mounted graph, camera,
and positions without replaying hints. Dragging or zooming ends an active layout
animation without refitting; unmounting cancels animation resources. Every
interpolated frame and initial/size-updated scene also passes through the same
rectangle-clearance function. While dragging, only the held node is a temporary
anchor; other nodes move out of its way. Pointer release/cancellation removes
that local anchor, and later size/topology solves can move any node. Reduced-motion
preferences skip layout motion and use a short static update hint.

Layout streams survive desktop switching. Successful Run/Extend, changing the
inspected call occurrence, closing a pane, workspace replacement, and server
shutdown retire the relevant streams. Failed preparation leaves them usable.
There is at most one current layout stream per runtime pane. Layout computation
never changes the program Net, semantic identities, or relationships.

`frontend/topology/viewport.cljs` sizes each node frame to its full label with
28 pixels of horizontal padding, a minimum width of 44 pixels, and a shared
32-pixel height. Cells use rounded frames; propagators use square frames.
Code and graph labels retain the same 12-pixel font size.
Framing translates Vijual's coordinates without compressing distances or
repacking nodes onto a grid, preserving the solver's clearances and crossings.
It never stretches a sparse graph to fill a pane or changes node/text scale.
When a pane cannot accommodate the geometry, the canvas grows and scrolls.
Tile/code/detail resizing does not refit the camera. Layout responses update only
world coordinates and never reset pan, zoom, viewBox or canvas dimensions. The
palette's **Compact topology** explicitly restores this framing after
pan/zoom; deliberate zoom still scales the view. No frame-time guarantee is claimed.

## Display layers

Each pane owns an ordered `layers` array with `{id, visible}` entries for `code`,
`result`, `topology`, and `cell`, plus `layerMode` (`overlay` or `stack`). The first
entry is foremost in Overlay mode and first vertically in Stack mode. The small
Layers panel edits this configuration and the shared output binding. Overlay is
the default. Foreground code/result reserve their space above a topology below
them; moving topology to the front lets it cover the layers beneath it. The
panel itself remains accessible from the command palette when every content
layer is hidden.

The optional Result layer uses plain text beneath the source without a binding caption,
Run status, or graph toolbar. Fit and return-to-root actions remain in the
command palette. **Toggle display layers** opens or closes the same compact panel. Visibility
and order persist in saved JSON and local recovery, including across desktops.
Older files infer their output layers from their saved display mode.

`workspace/layers.cljc` owns validation and pure transitions.
`frontend/workspace/composition.cljs` changes
visibility, order, and insets without recreating source or graph views.
`frontend/workspace/outputs.cljs` shares one inspection response between result/cell/topology layers.
Only a visible topology requires a graph projection; hiding all output layers
cancels sampling. Layer changes never run or replace the program. The existing
Vijual layout ownership and resize fitting remain intact.

The semantic-tracing gallery contains explicit `relationship:roots` and
`relationship:dataflow` expressions and publishes the `semantic` graph-valued cell
with `display`.
This displays the published Lain tracer output without editor augmentation.
For a consolidated tour of the built-in current-session trace, retained
relationship projections, directional observers, modifiers, and replay, open
[`examples/lain/tracer_gallery/workspace.json`](https://github.com/Semi-0/datalog-research/blob/main/examples/lain/tracer_gallery/workspace.json).
For ordinary non-graph display cells, the editor uses the same shared projection
of the current runtime graph and its actual boundary copies.
Choosing **Show topology** hides the separate scalar Result layer; Layers can
enable it again explicitly. The gallery accumulator is source-level tail-position
recursion, not a claim of tail-call elimination or bounded topology growth.

The direct scalar input-return connection limitation is recorded in
[`doc/direct-input-return-connection-bug.md`](https://github.com/Semi-0/datalog-research/blob/main/doc/direct-input-return-connection-bug.md).
It is a runtime boundary issue; this status-bar change does not alter its semantics.

## Call inspection

Topology labels use node names only. The tracer emits no generated captions.
The compiler names literals from their source text and application returns from
their source expression, for example `0` and `(+ left right)`. Returned member
ports retain their local binding names. Names survive seeding and declaration
replay without changing cell IDs or values. Anonymous network values may remain
unnamed. An unused application fallback ID is reserved without creating a cell.
Cells and propagators remain distinct. A private expression result with exactly
one definition consumer is represented by its actual named binding cell; this
removes the definition hop without merging a cell with a propagator. Alias
metadata retains the original cell for inspection. Independently named cells
keep their separate state and copy flow, shown as `->` rather than `define`.
Explicit routing and returned-member copy propagators remain visible. Values
and closure details are available on selection.
Selecting a call shows its real boundary ports and return. **Open this call body**
enters one level; **Chase child calls** explicitly requests the bounded occurrence
table. Branch entries then come from actual `if` condition cells. Rows can open
their own body; raw metadata is folded below. The table describes structural
nesting, not scheduler history. Limits of 64 calls / 32 levels are reported.
The common scope and producer rules are documented in
`propagators/doc/semantic-tracing-datalog.md`.

An otherwise empty identity body retains its actual return copy chain. An
unchanged-input annotation requires an identity path through known `->` and
that call's outbound boundaries, ending at exactly one local input cell. Equal
values alone never establish this relationship. Inspection never mutates the Net.

## Optional value rows

Compose `(relationship:with-values (relationship:roots-semantic input))` inside
`display` to show each cell's current value beneath its name. Ordinary operators
still return their result cells. This modifier adds only graph presentation
metadata; names, kinds, values, edges and abstraction scope are preserved.
Opening a child body inherits the modifier. Scalars occupy one extra line;
layered values show every public layer with the base first, bounded previews of
large nested payloads, and line wrapping. Cells remain rounded and propagators
square; only cell frames grow to accommodate their rows. Graph labels retain
the same 12-pixel size as code and feed their dimensions to Vijual.

See `examples/lain/value_tracing/workspace.json` for scalar, layered-object and
waiting-input panels. The existing fill-and-fade hint responds to observed value
and relationship changes, including value-row changes. It is sampled at the
editor's two-second polling interval and is not a scheduler activation timeline.

Compiler-generated literals are named `CONSTANT` and application outputs `RESULT`;
explicit bindings retain their own names. Rendering removes keyword-prefix colons
and replaces interior colons with spaces (`p:slot` becomes `p slot`), while quoted
strings and cell identities remain unchanged. Wires, arrowheads, and list dots use
one shared color. Node sizes follow rendered content; graph layout does not shrink
text to force the topology into a pane.
