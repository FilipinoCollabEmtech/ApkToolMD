# LEARN.md — Inside the Infinite Craft AI Crafter

This is a working companion to `infinite-craft-ai-crafter.user.js`. It explains the
mechanics you can reuse in any Tampermonkey script: sandbox escaping, injected UI,
GM storage, CORS bypass, a bounded LLM loop, and a transparent "learning" loop.

---

## 1. What a userscript is, and installing this one

A userscript is a JavaScript file that a browser extension injects into pages matching
a URL pattern. Tampermonkey is the extension. The script runs with extra privileges:
it can read and write values in its own storage, make cross-origin requests, and
register menu commands, while still living inside the page.

Install steps:

1. Open the Tampermonkey dashboard (puzzle-piece icon → Dashboard).
2. Click the **+** tab (Create a new userscript).
3. Delete the template. Paste the entire contents of
   `infinite-craft-ai-crafter.user.js` (starting with `// ==UserScript==`).
4. Save with Ctrl/Cmd+S.
5. Open https://neal.fun/infinite-craft/. The panel appears at top-right after the
   page settles (`@run-at document-idle`).
6. Type a target, e.g. `Dragon`, and press **Start**. Press **Stop** any time.

If the panel does not appear, open the Tampermonkey menu on that page and run
**Toggle AI Crafter Panel**. That command is registered via the `GM_registerMenuCommand`
directive.

Key entry: put your Kilo API key in the **Kilo API key** field and press **Save**. The
key is still supplied on every authenticated request, even though only free models are
used; the gateway also allows anonymous access, so leaving it blank works too. The
**Free model** dropdown is populated from the live catalog, with a **Refresh** button.
The key never appears in the log, the knowledge base, or the page's storage.

---

## 2. The metadata block, directive by directive

```js
// ==UserScript==
// @name         Infinite Craft — AI Crafter
// @namespace    https://neal.fun/infinite-craft/
// @version      1.0.0
// @description  AI-driven goal-seeking crafting agent with persistent learning.
// @match        https://neal.fun/infinite-craft*
// @grant        unsafeWindow
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_xmlhttpRequest
// @grant        GM_registerMenuCommand
// @connect      ai-gateway.kilo.ai
// @run-at       document-idle
// ==/UserScript==
```

`@name`, `@namespace`, `@version`, `@description` — identity shown in the dashboard.
The namespace is a stable URL so updates replace this script rather than creating a
duplicate.

`@match https://neal.fun/infinite-craft*` — the injection scope. The trailing `*` is
important: it matches the bare path, a trailing slash, and query strings. A script
with no `@match` (or `@include`) never runs.

`@grant` — declares each privileged API you use. Declaring a grant is what switches
Tampermonkey into "sandbox" mode; the APIs exist only because they are listed here.
Each one here has a job:

- `unsafeWindow` — access to the page's real `window` (see section 3).
- `GM_getValue` / `GM_setValue` — persistent script-scoped storage.
- `GM_xmlhttpRequest` — cross-origin HTTP without CORS (section 6).
- `GM_registerMenuCommand` — adds the panel toggle to the Tampermonkey menu.

`@connect ai-gateway.kilo.ai` — permission for `GM_xmlhttpRequest` to contact that
host. Without it, Tampermonkey prompts the user or blocks the request. Wildcards are
possible (`@connect *`) but a specific host is safer and avoids prompts.

`@run-at document-idle` — run after the DOM is built and the page has settled. The
game registers its `window.IC` object during component mount, so "idle" is a better
bet than `document-start`; the script still polls defensively in `waitForIC`.

Everything after the block is wrapped in `'use strict';` and an IIFE so
none of the script's names leak into the page or collide with another userscript.

---

## 3. Why `unsafeWindow` is required to reach `IC`

Tampermonkey does not run your code in the page's JavaScript realm. It gives you a
sandbox with its own `window` object. The game, meanwhile, does this in its bundle:

```js
window.IC = { getItems, craft, ... };
```

That `window` is the page's window. Reading `window.IC` from the sandbox returns
`undefined`, because you are looking at a different object. The escape hatch is:

```js
// the sandbox bridge
const page = (typeof unsafeWindow !== "undefined") ? unsafeWindow : window;
```

The `typeof` guard keeps the script parseable and usable in environments where
`unsafeWindow` is absent (Greasemonkey-style fallbacks). From then on the script uses
`page.IC` only. `waitForIC` polls `page.IC` every 250 ms for up to 15 s,
checking `typeof IC.getItems === 'function' && typeof IC.craft === 'function'` before
proceeding. If the game is slow or the API was renamed, you get a clear log line
instead of a `TypeError`.

Two details that matter at the craft boundary:

- Names are **exact and case-sensitive**. `"water"` is not `"Water"`. The script only
  ever passes strings taken from `kb.elements`, whose keys came verbatim from
  `IC.getItems()[i].text`.
- `IC.craft` is called as a method on the object (`IC.craft(a, b)`), not destructured
  into a bare function, so its internal `this` stays correct.

The goal check is the one deliberate exception to case sensitivity: `hasElement()`
lowercases both sides, because a user typing `dragon` should still match
a discovered `Dragon`. Craft arguments are never lowercased.

---

## 4. Building the panel with DOM APIs, and why `textContent` matters

`injectCSS()` appends a single `<style id="icai-style">` to `<head>`. Every
selector is scoped under `#icai-panel` so the page's own styles cannot leak in and the
panel's styles cannot leak out. It is fixed-position, 340 px wide, high `z-index`,
and dark-themed. It intentionally does not cover the whole board.

`buildPanel()` creates nodes with a tiny helper:

```js
// panel node helper
function el(tag, opts = {}, children = []) {
  const node = document.createElement(tag);
  if (opts.id) node.id = opts.id;
  if (opts.type) node.type = opts.type;
  if (opts.text != null) node.textContent = opts.text;
  // ...
}
```

This is safer than a big `innerHTML` template. The log writes AI- and user-derived
strings through `textContent` in `log()`:

```js
line.textContent = '[' + stamp + '] ' + message;
```

If that were `line.innerHTML = ...`, an element name containing `<img onerror=...>`
returned by the model would execute in the page. With `textContent`, the same string
is inert text. The rule generalizes: **any string you did not author yourself goes in
via `textContent` or a property assignment, never into an HTML parse.**

The API key follows the same principle. It is set once through the assignment
`els.key.value = state.key` — a DOM property, not an attribute written into markup.
It is read back with `.value`, stored with `GM_setValue`, and never interpolated into a
log message or a CSS/HTML string.

`wireEvents()` guards against double-start (`if (state.running) return;`),
disables Start and the target input while running, and enables Stop. `stop()` sets the
flag, wakes sleeping delays, and aborts any in-flight gateway request so "Stop" means
now, not "after the current 20-second timeout".

### Crafting modes: instant and animated drag

The **Drag mode (animated)** checkbox selects how a chosen pair is executed. It is off
by default: instant mode calls `IC.craft(a, b)` directly. With the toggle on,
`craftPair()` calls `craftViaDrag()` instead — real `mousedown`, `mousemove`, and
`mouseup` events spawn the two tiles from the book and drag one onto the other on the
board, so the combination is visible. The **Duration ms** input (`CONFIG.dragDurationMs`,
default 900) sets the whole drag time and is split across the move steps. Both
preferences persist under `icai.dragMode` / `icai.dragDuration`, and both controls are
disabled while a run is active so the mode cannot change mid-run.

Move and release events are dispatched on the page's real window through the `page`
reference from `unsafeWindow`. The game registers its listeners there, so events sent
to the sandbox window would never reach them, and `MouseEvent.view` must be that same
window. Book spawns send a matching `mouseup` too: a bare `mousedown` leaves the book
entry stuck in a dragging state.

Drag mode is slower and layout-dependent. It checks that the intended tile got
`.instance-hover`, watches `#instances` / `#instances-top` with a `MutationObserver`,
and cross-checks the resulting name against `IC.getItems()`. It normalizes to the same
`{text}` shape, so dedup, NEW detection, recipes, patterns, and logs behave exactly as
in instant mode. `waitForDragResult()` uses distinct sentinels: a real "Nothing" tile
resolves to `NO_RESULT`, which flows into `craftPair()`'s shared `result == null` branch
so `recordFailure()`, `markPairTried()`, `updatePatterns()`, and `saveKB()` all run.
`CANCELLED` is observer teardown. Infrastructure failures — missing hover on the
intended tile, covering overlay, observer timeout — abort the run **without** recording
a failure or marking the pair tried.

### Mobile layout and touch drag

At `max-width: 768px` the panel becomes a bottom sheet: full width, docked to the
bottom, capped at roughly 55vh when expanded, with the log scrolling inside its own
area so the board remains visible above. It starts collapsed as a ~52 px bar showing
the title, the current status, and the expand handle; tapping the bar or the handle
expands it. The collapse state is local UI state only — never persisted; a breakpoint
change re-applies the platform default unless you explicitly toggled the handle. Safe-area
insets are respected, and buttons, inputs, and checkboxes get 44 px targets with 16 px
input text so iOS does not auto-zoom. Each checkbox is associated with its `<label>` so
the whole row is tappable. Wider viewports keep the original right-docked layout with no
collapse handle.

On touch-capable devices `craftViaDrag()` selects the touch path: `chooseDragInput()`
checks for a coarse pointer or multiple touch points and probes whether the page can
construct `Touch` and `TouchEvent`. If it can, `fireDrag()` emits `touchstart`,
`touchmove`, and `touchend` with `clientX`/`clientY` on the page window; otherwise it
logs that touch drag is unavailable and uses the mouse path. Only one path is used per
drag. Hover assertion, dwell, observer result reading, Stop cleanup, and infrastructure
failure isolation are identical for both.

---

## 5. Persistence: GM storage vs. the page's own storage

Three storage systems are in play:

| Where | Owner | Used for |
|---|---|---|
| `GM_setValue` / `GM_getValue` | This userscript | Knowledge base, key, model, delay |
| Page `localStorage` / IndexedDB | The game | Its own save state and discovered items |
| In-memory `state` | This run | Stop flag, active request, cached model |

`loadKB()` reads `icai.kb.v1`, tolerates both a stored object and a JSON
string, and normalizes missing fields to defaults. `saveKB()` writes the
whole object back after every attempt. This is why progress survives tab closes,
browser restarts, and script updates: the game's IndexedDB is read through
`IC.getItems()`, but the recipes, failures, notes, and counters live in GM storage and
are yours.

The knowledge base shape (see the `STORAGE` block and `loadKB()`):

```js
{
  version: 1,
  elements: { "Water": { emoji, id, firstSeen }, ... },
  recipes:  [ { a, b, result, isNew, repeats, at } ],
  failures: [ { a, b, repeats, at } ],
  triedPairs: {
    "Fire\u0000Earth": { a, b, result, isNew, misses, at }
  },
  plan: {
    ladder: [ "Time Traveler", "Police Box", "Time Machine", "TARDIS", "Doctor Who" ],
    activeIndex: 0, mode: "continuation",
    refutedSteps: [], refutedRecipes: [], craftsSinceReplan: 0, updatedAt: 0
  },
  goals:    [ { element, recipes: [ { a, b } ], confidence } ],
  hypotheses: [ { a, b, predicted, actual, correct, at } ],
  patterns: [ "Fire + Earth → Lava (already crafted; do not repeat)", ... ],
  attempts: 0,
  discoveries: 0,
  updatedAt: 0
}
```

Keys: `icai.kb.v1`, `icai.key`, `icai.model`, `icai.delay`, `icai.dragMode`,
`icai.dragDuration`. The key is stored by
itself under `icai.key` — never inside `icai.kb.v1`, which is the object most likely
to be exported, inspected, or pasted into a bug report. The page's storage is never
written to by this script; reading `IC.getItems()` is enough.

Retention uses item-count caps, not byte-size measurement: `trimArray()`
keeps the last 500 recipes, 300 failures, and 20 hypotheses; `updatePatterns()` keeps 12 notes.
Since each entry is small, that bounds GM storage growth in practice while keeping
plenty of history for the prompt sampler.

---

## 6. CORS, and why `fetch()` is the wrong tool here

Browsers block a page on `neal.fun` from reading a response from
`ai-gateway.kilo.ai` unless the gateway sends permissive CORS headers. Whether it
does for this origin is unverified, so the script does not rely on it.

`GM_xmlhttpRequest` is a privileged browser-extension API. It is not subject to the
page's same-origin policy. With `@connect ai-gateway.kilo.ai` granted, it can POST to
the gateway from a `neal.fun` tab. `rawGatewayRequest()` wraps it in a
Promise and maps `onload` / `onerror` / `ontimeout` / `onabort`:

```js
GM_xmlhttpRequest({
  method: 'POST',
  url: 'https://ai-gateway.kilo.ai/api/v1/chat/completions',
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer <KEY>' },
  data: JSON.stringify({
    model: 'inclusionai/ling-3.2-flash:free',
    messages: [{ role: 'system', content: '...' }, { role: 'user', content: '...' }],
    max_tokens: 4096, reasoning: { enabled: false },
    temperature: 0.7, stream: false
  }),
  timeout: 20000,
  onload: (res) => { /* res.status, res.responseText */ }
});
```

Every free gateway model is a reasoning model. At a small budget one can consume all
of `max_tokens` on reasoning and still return HTTP 200 with `finish_reason: "length"`
and null or empty `content`. That is why the body sets `reasoning: { enabled: false }`
and raises `max_tokens` to `CONFIG.primaryMaxTokens` (4096) — tokens are effectively
unlimited here, so the budget goes into a 12-item plan rather than a single answer.
`temperature` is 0.7: greedy decoding at 0 reproduced the same pair again and again
when the prompt barely changed, while 0.7 produced distinct target-relevant pairs.

The `Authorization` header is added when a key exists and free mode is off — the
operator keeps the key saved even with free models:

```js
if (useAuth && state.key) headers.Authorization = 'Bearer ' + state.key;
```

With no key saved, the request goes out anonymously and the gateway's free tier still
serves it. A minimal success response:

```json
{
  "choices": [
    { "message": { "role": "assistant", "content": "{\"a\":\"Water\",\"b\":\"Fire\",\"reason\":\"Classic steam path\"}" } }
  ]
}
```

An error response looks like `{"error":{"message":"rate limited","code":"429"}}`
with an HTTP status of 429. `extractGatewayError()` reads that message
so the log says something useful instead of "HTTP 429".

Two endpoint classes need a second attempt: one rejects the flag with HTTP 400 and a
message containing "Reasoning is mandatory", and another ignores the flag and still
truncates on reasoning tokens. For those cases only, `callKilo()` retries once with
`max_tokens: 8192`, `reasoning: { effort: 'low' }`, and a 45 s timeout
(`CONFIG.fallbackTimeoutMs`), then returns that content. The low effort setting keeps
large-but-cheap reasoning models fast; `liquid/lfm-3-1.7b:free` hard-fails on any
reasoning field, so that one model gets no `reasoning` key and relies on the longer
timeout. The fallback caps transport retries at one, so the worst case is roughly two
45 s waits, and 429 backoff still stops after five hits. If the retry is still empty or
reports `finish_reason: "length"`, the error reads `Model <id> returned no content
(finish_reason: length) even at 8192 tokens` — that model cannot fit the answer in its
reasoning budget.

Model discovery uses the same wrapper with `GET /models` and **no** auth header. It is
free-only: `isFreeModel()` accepts an entry when `isFree` is true (or `"true"`), the id
ends in `:free`, or `pricing.prompt` is zero — all three are checked because providers
type those fields differently. `getFreeModelIds()` keeps the exact `data[].id` strings
of matching entries, excluding the measured-slow ids in `CONFIG.slowModelIds`.
`resolveModel()` prefers `CONFIG.preferredFastModel` when present, otherwise the first
remaining id, and `applyModelOptions()` keeps the dropdown on that same choice. A key
never changes the choice; a paid model cannot be selected silently. An explicitly saved
override stays honored even if it is a known-slow id — `resolveModel()` logs a warning
instead of silently replacing your choice.

At boot, `refreshModelOptions()` fills `<select id="icai-model">` with those exact IDs.
The small **Refresh** button next to it re-fetches the catalog on demand, which matters
because model IDs rotate. If the catalog is unreachable or has no free entries, the
dropdown is filled from `CONFIG.fallbackFreeModels` (a 2026-11-23 snapshot) and the log
says the list is unverified. The current selection lives in `icai.model` and is applied
immediately on change. If a saved ID is absent from the fetched list, it stays visible
as an extra option marked `(saved, not in catalog)` so you can still see it and pick a
current one; `resolveModel()` only uses it when it appears in the current free list,
otherwise it switches and logs why.

---

## 7. The AI loop, dedup, and the batch contract

A deep DAG of subgoals produced malformed JSON three times out of three in live tests.
Subgoals were decorative — `run()` never used them to pick a craft — scores were
self-reported, and the queue abandoned a chain on any NEW element. A flat five-name
ladder with code-controlled active step was valid 5/5, target-relevant 5/5, and showed
zero drift toward a generic anchor. There is no bundled recipe corpus: the ladder and
craft history are the only guidance.

`run()` is the conductor:

1. `canonicalizeTarget()` fixes the goal string (exact discovered match, otherwise Title
   Case with a small stop-word set). `waitForIC(15000)` gets the game API and
   `syncElementsFromGame(IC)` merges discoveries.
2. `resolveModel()` → pick a free model.
3. `planNeedsReplan()` checks the persisted ladder; `clearPlan()` discards it with a
   logged reason when needed. `buildPrompt(rawTarget, target)` supplies PLAN STATUS and
   CANDIDATE PARENT POOL and computes the active step in code.
4. `askForProposals(messages)` → `callKilo()`, then `parseProposals()`, which validates
   the ladder, active index, producing step, and parent-pool membership.
5. Sort usable proposals by descending `score`, then execute the queue. Before each
   craft, `isPairTried()` gate. `craftPair()` records the outcome, `markPairTried()`,
   `recordHypothesis()`, and `applyPlanFeedback()` advance or refute the active step.
6. Increment attempts with jitter delay; re-check target and Stop between every step.
7. A NEW element still stops the queue immediately — but the ladder and its updated
   active index persist, so the next turn continues the same chain instead of
   restarting. Otherwise continue through the queue until it is exhausted.

**Hard dedup.** `pairKey(a, b)` sorts the two exact names and joins them with a NUL
delimiter, so `Fire + Earth` and `Earth + Fire` share one key; self-pairs such as
`Fire + Fire → Volcano` are legal and get the key `Fire\u0000Fire`. `kb.triedPairs`
stores `{a, b, result, isNew, misses, at}` for every attempt. `parseProposals()` drops
tried pairs, the execution queue drops them again, and `craftPair()` refuses them a
third time — no tried pair can reach `IC.craft`. `recordRecipe()` and `recordFailure()`
update an existing record instead of appending a duplicate, removing the
18-identical-lines attention bias. Legacy KBs without `triedPairs` are seeded from
recipe and failure history on load.

**Contract.** The fixed system text in `LADDER_SYSTEM` has `<TARGET>` replaced with the
canonical target. It demands one strict JSON object, no markdown:

```json
{
  "strategy": "backward",
  "analysis": "<=200 chars",
  "ladder": ["NEW name 1", "NEW name 2", "NEW name 3", "NEW name 4", "<TARGET>"],
  "active_index": 0,
  "proposals": [
    {"a": "POOL name", "b": "POOL name", "produces": "ladder[0]", "score": 0, "why": "<=120 chars"}
  ],
  "abstain": false
}
```

On a continuation turn the code supplies the ladder and the computed index; the model
must copy the ladder verbatim and make every `produces` equal `ladder[active_index]`.

An abbreviated prompt:

```text
TARGET: doctor who
CANONICAL TARGET: Doctor Who
SUCCESS: the canonical target appears in DISCOVERED ELEMENTS (case-insensitive check,
exact names when crafting).
PLAN STATUS:
1. [HAVE] Lizard
2. [NEED] Time Traveler
3. [NEED] Police Box
4. [NEED] Time Machine
5. [NEED] Doctor Who
ACTIVE STEP: index 1 = "Time Traveler" (first NEED). All proposals must produce exactly "Time Traveler".
FORBIDDEN / ALREADY ATTEMPTED PAIRS (4 shown — BANNED): ...
AVAILABLE ELEMENTS (exact, case-sensitive): ...
CANDIDATE PARENT POOL (40 names — proposal arguments MUST come from here): ...
RECENT SUCCESSFUL RECIPES / RECENT FAILURES / LAST 10 CRAFT OUTCOMES ... [NEW] / [already known]
HYPOTHESIS CHECK: - Human + Chef: predicted Doctor → got Cook (WRONG)
LEARNED NOTES / NOT YET EXPLORED: ...
REQUEST: return exactly 3 DISTINCT proposals, sorted by descending score, each producing the ACTIVE STEP ("Time Traveler").
```

An example response:

```json
{
  "strategy": "backward",
  "analysis": "Combine time and travel to open the second rung.",
  "ladder": ["Time Traveler", "Police Box", "Time Machine", "TARDIS", "Doctor Who"],
  "active_index": 0,
  "proposals": [
    {"a":"Time","b":"Traveler","produces":"Time Traveler","score":88,"why":"direct parent pair"},
    {"a":"Hourglass","b":"Human","produces":"Time Traveler","score":74,"why":"travel motif"},
    {"a":"Clock","b":"Human","produces":"Time Traveler","score":61,"why":"time plus person"}
  ],
  "abstain": false
}
```

**Deterministic control.** `kb.plan` stores `{ ladder, activeIndex, mode, refutedSteps,
refutedRecipes, craftsSinceReplan, updatedAt }` — all bounded, no KB version bump.
`firstNeedIndex()` computes the active step as the first ladder name not yet discovered.
After each craft, `applyPlanFeedback()` advances that index when the observed result
equals the step, records a sticky recipe (`result === a || result === b`) as a pattern
note, or adds a refuted step when the result is a different product. `parseProposals()`
drops any proposal whose ladder is not exactly five strings ending in the canonical
target, whose producing name differs from the active step, whose arguments are outside
CANDIDATE PARENT POOL, or whose score is not numeric; fewer than two survivors triggers
the repair re-prompt. `planNeedsReplan()` discards the ladder on a malformed length, a
target mismatch, a stale active step, a refuted-twice step, eight crafts without a
re-plan, or the twelve-craft no-progress watchdog. There are no hardcoded recipe pairs
or concept tables — the ladder and craft history are the only knowledge used.

`callKilo()` handles transport, including the one reasoning-free retry described above:
HTTP 200 extracts `choices[0].message.content`; 429 triggers exponential backoff
(1 s, 2 s, 4 s, 8 s) and stops on the 5th consecutive 429; 401/402/403 stop
immediately with the gateway's own message; 5xx retry twice; malformed JSON and empty
content are errors, not proposals.

`jitterSleep()` adds ±20% random jitter to the delay. `sleep()`
registers a wake callback so Stop interrupts the wait instead of idling out the full
delay. `assertRunning()` throws a `StopSignal` at every await boundary,
which `run()` catches without treating a deliberate stop as a crash.

---

## 8. What "learning" actually means here

There is no training. Nothing is fine-tuned, and no weights change. What the script
calls learning is **retrieval-style memory**: it keeps a structured history, distills
it into short deterministic notes, and feeds those notes back into the next prompt.

`updatePatterns()` regenerates the qualifying notes from the full history after every result —
success or failure — so the notes always agree with the records. It emits four kinds of notes:

- **Wasted volume (de-prioritizing)** — `"Human: 14 crafts, none produced a new element"`,
  counting only `kb.recipes` entries with `isNew !== true`. Positive frequency used to
  anchor the model on repeated known results, so it is no longer rewarded.
- **Repeat failures** — `"Never combine Water with Dragon again (failed 2 times)"`,
  from counting normalized `(a, b)` pairs in `kb.failures`.
- **Theme affinity** — `"Fire + X produced 5 fire-themed results"`, from matching
  result names against a small hardcoded keyword list per base element.
- **Exhausted pairs** — `"Fire + Earth → Lava (already crafted; do not repeat)"`,
  from the most repeated or most recent `kb.triedPairs` entries.

At most 12 notes are kept; oldest drop out. The heuristic is intentionally simple and
readable — open `icai.kb.v1` in the dashboard's Storage tab and you can see exactly
what the AI saw. The model still does the creative pairing; the notes steer it away
from known dead ends and toward ingredients that have been productive.

---

## 9. Reading the log, and tuning the knobs

Log lines are timestamped and colour-coded: `info` (grey-blue), `success` (green,
also marks `✓ NEW` discoveries), `fail` (amber, `→ no result`), `error` (red).
The box keeps the last 200 entries and auto-scrolls. A normal stretch looks like:

```text
[14:02:11] Using free model: inclusionai/ling-3.2-flash:free
[14:02:12] AI strategy: backward — Lizard is the closest known ancestor; try heat variants.
[14:02:12] Batch: 3 received, 3 usable (0 tried, 0 invalid).
[14:02:13] Queue: 3 usable proposals ordered by score.
[14:02:15] Lizard + Fire → Dragon  ✓ NEW
[14:02:15] New element discovered; stopping queue to re-plan with fresh state.
[14:02:15] SUCCESS: "Dragon" crafted (attempt 7).
```

The `CONFIG` block holds the tuning surface. The model is picked from the free-model
dropdown, the delay from the number field, and the drag preferences from the mode
checkbox and duration field; they are saved to `icai.model`, `icai.delay`,
`icai.dragMode`, and `icai.dragDuration`:

- `attemptDelayMs` (default 1500) — pause between attempts. Raise to 3000+ if you see
  gateway 429s or the game feels sluggish. Jitter is applied on top.
- `maxAttemptsPerRun` (default 250) — safety stop. The run ends with a clear log line
  when it is reached; pressing Start again resumes from the saved KB.
- `proposalsPerTurn` (3), `ladderLength` (5), `candidatePoolSize` (40),
  `maxCraftsPerPlan` (8) — the tested flat-ladder contract and its re-plan budget.
- `maxElementsInPrompt` (2000), `maxRecipesInPrompt` (200),
  `maxFailuresInPrompt` (100), `maxTriedPairsInPrompt` (1000) — generous feeds.
  Tokens are effectively unlimited, so progress matters more than prompt size; shrink
  these only if a gateway rejects the request as too large.
- `primaryMaxTokens` (4096), `fallbackMaxTokens` (8192), `temperature` (0.7) —
  the reasoning-budget fix plus sampling that breaks deterministic loops.
- `requestTimeoutMs` (20000) and `fallbackTimeoutMs` (45000) — the large fallback body
  is measured in tens of seconds, so it gets its own budget and only one transport retry.
- `slowModelIds`, `preferredFastModel` — exclude chronically slow free endpoints from
  the dropdown and pick a measured-fast default when available.
- `maxHypothesesStored` (20) — cap on predicted-vs-actual records fed back to the prompt.
- `consecutiveNoNewLimit` (12) — dry-spell watchdog; also a re-plan trigger and forces
  `explore` on the next plan. `maxRefutedSteps` (12) bounds refutation history.
- `repairDropRatio` (0.5) — batch drop fraction that triggers one repair re-prompt.
- `dragDurationMs` (900) — total visible drag time in animated mode; the panel's
  duration input overrides it and validates 200–3000 ms.

HTTP **429** means "too many requests". The gateway's free tier is 200 requests/hour
per IP. The script backs off exponentially and stops after five hits; wait for the
hour to roll over, lower the delay, or use **Refresh** to pick a different free model.

A saved `icai.model` override is honored even when it is a known-slow id; the Start log
says `Using saved model override ...` and tells you how to clear it. Use the panel's
**Preferred** button or the `Reset model to preferred fast model` menu command to clear
that override and return to `CONFIG.preferredFastModel`.

---

## 10. Failure modes and how to diagnose them

| Symptom in log | Likely cause | What to do |
|---|---|---|
| `Game API (window.IC) unavailable after 15000 ms` | Game still mounting, or its internal API was renamed/removed | Reload and retry; if persistent, inspect `unsafeWindow.IC` in devtools — the internal API is runtime-unverified |
| `Free model list unavailable ... Showing unverified fallback IDs` | `GET /models` failed or host blocked while populating the dropdown | Check `@connect`, then press **Refresh** |
| `Free model catalog unavailable. Using unverified fallback ...` | Same failure at run start | Press **Refresh**; the fallback IDs are free-only but may have rotated |
| `Saved model ... is no longer free/current; using ...` | The stored ID is not in the current free list | Pick a current entry from the dropdown |
| `Using saved model override ... Clear it ...` | A saved `icai.model` is winning over the preferred fast model | Press **Preferred** in the panel or use the reset menu command |
| `Re-planning (...): ladder discarded` | Malformed length, target mismatch, stale/refuted step, craft budget, or no-progress watchdog | Read the logged reason; a fresh ladder is requested next turn |
| `Plan advanced to step N` / `Plan: step "..." refuted` | Observed result matched or contradicted the active ladder step | Normal feedback; the ladder persists with an updated active index |
| `Warning: saved model ... is known-slow and may time out` | Saved override is in `CONFIG.slowModelIds` | Your choice was kept; switch to a faster free model if it stalls |
| `Gateway auth/quota error 401/402/403` | Saved key rejected or provider quota hit | Re-enter the key, or clear it to run anonymously |
| `HTTP 429 ... Backing off` | Free-tier rate limit | Wait; increase delay; reduce prompt sizes |
| `Gateway request timed out` after the reasoning retry | Slow provider on the 8192-token path | The fallback now uses `effort: low` and 45 s; switch models if it persists |
| `Repair: n/m proposals were already tried` | Model kept proposing forbidden pairs | One automatic repair re-prompt with the explicit list; no tried pair was crafted |
| `Invalid batch (1/2); re-prompting ...` | Malformed JSON or invalid names | Automatic re-prompt; check the model if repeated |
| `AI kept returning malformed batches. Stopping.` | Two unusable answers in a row | Switch free model; fail-closed stop, nothing was crafted |
| `Frontier exhausted: n/m proposals were already tried after repair` | No untried pairs left that the model can propose | Try a different target or a stronger model; the run stopped by design |
| `Watchdog: ... crafts without a new element; forcing explore` | Dry spell / degraded planner | The next plan is forced to `explore`; check the log for repeated failures |
| `→ no result` repeatedly | Normal game outcome, or a Turnstile challenge was triggered | Turnstile is interaction-only; if every craft fails, solve the challenge in the page, then retry |
| `IC.craft threw` | Game API changed or call signature shifted | Check console; instant mode needs a working `IC.craft`, or switch to drag mode |
| Names rejected as invalid | Case mismatch | Use names from the `AVAILABLE ELEMENTS` section of the generated prompt — the exact keys of `icai.kb.v1` — which the script passes verbatim |
| `Drag mode: the intended tile never registered .instance-hover` | Drop landed on the wrong node or drag events did not reach the page window | Check board layout and overlays; try a longer duration; the pair was NOT recorded |
| `Drag mode: an overlay covers the drop point` | Ad or Turnstile element over the board | Dismiss the overlay and restart; no KB record was made |
| `Drag mode: timed out waiting for a result tile` | Result observer saw no recognized tile, often node reuse or a layout change | Retry; this is an infrastructure failure, so the run aborts without a false ban |
| `Drag mode: could not find book entry for ...` | Book item not searchable or sidebar failed to filter | Confirm the element exists in the sidebar; no pair was marked tried |
| `Drag mode infrastructure failure. Stopping.` | Any of the above abort conditions | The run stopped cleanly; nothing was recorded for that pair |

`craftViaDrag()` is only reached when the **Drag mode (animated)** toggle is on; it
depends on undocumented internals — book entries `div.item[data-item-text]`,
`.instance-hover`, and a `MutationObserver` on `#instances` / `#instances-top`. It
dispatches window-level events on the page's real `window`, pairs each book `mousedown`
with a `mouseup`, and only removes tiles it created itself when a drag is aborted.
The `NO_RESULT` and `CANCELLED` sentinels keep the two outcomes separate. A genuine
"Nothing" tile is recorded through the normal no-result branch — failure history,
tried-pair ban, pattern notes, and persistence all run, exactly as in instant mode. A
cancelled observer, missing hover on the intended tile, covering overlay, or observer
timeout is infrastructure failure: the run aborts cleanly and nothing is written to
the knowledge base, so a broken drag cannot create a false ban.

---

## 11. Make it yours

**New target strategy.** Replace the prioritization in `pickElementNames()`.
It currently boosts active-step and ladder matches highest, then adds target-token
overlap, base-element status, and recency.
Bias toward elements sharing an emoji category, or toward names whose recipes produced
genuinely NEW results (`isNew === true`). Avoid bonuses based on raw per-element craft
counts: rewarding repeated known-result crafts anchored the planner on one element.
Keep the return value an array of exact names.

**Change the prompt.** Edit `LADDER_SYSTEM` and the `parts` array in `buildPrompt()`.
If you change the output format, change `parseProposals()` to match — the validator is
what keeps bad model output out of the game.
(Feeds are deliberately generous via the CONFIG.max*InPrompt knobs; tokens are effectively unlimited. Keep the returned JSON compact so :free models still produce it within max_tokens.)

**Extend the schema.** Add a field in `loadKB()` with a default, update it
where you need it, and `saveKB()` will persist it automatically. For example, track
`resultCounts` per element to feed popularity into the prompt. Bump `version` if you
later write a migration, and give the storage key a new suffix (`icai.kb.v2`) so old
data is not silently misread.

**Add a UI control.** Add a node in `buildPanel()`, a handler in
`wireEvents()`, and a persist line in `loadSettings()`. Keep
untrusted strings on `textContent` and keep secrets out of the log.

The core idea transfers to any LLM-driven agent: let the model propose, make a
deterministic validator gate every action, keep a compact evidence trail, and give
the human an immediate, honest Stop button.
