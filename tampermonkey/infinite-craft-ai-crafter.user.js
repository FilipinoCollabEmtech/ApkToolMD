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

'use strict';

/*
 * Autonomous goal-seeking agent for neal.fun/infinite-craft.
 *
 * Architecture:
 *   UI layer      -> buildPanel/injectCSS: injects a collapsible side panel.
 *   Storage layer -> loadKB/saveKB: the knowledge base lives in GM storage.
 *   Game bridge   -> waitForIC/syncElementsFromGame/craftPair: reaches the page's IC API.
 *   AI layer      -> buildPrompt/callKilo/parseProposals: scored batch planner.
 *   Control loop  -> run(): craft a queue, learn, persist, replan on discovery.
 *
 * The AI never controls the DOM. It returns a JSON pair of exact element names;
 * this script validates those names against its own knowledge base before crafting.
 */
(() => {
  // ---------------------------------------------------------------------------
  // CONFIG — edit these defaults here, or override the model/delay in the panel.
  // ---------------------------------------------------------------------------
  const CONFIG = {
    apiBase: 'https://ai-gateway.kilo.ai/api/v1',

    // Empty string = use the first free model discovered from GET /models.
    // Only free models are ever selected; a saved key does not change that.
    // An exact ID here (or chosen in the panel dropdown) pins that model.
    model: '',

    // Pace: the game and the gateway both dislike hammering. Jitter avoids a
    // perfectly periodic request signature.
    attemptDelayMs: 1500,

    // Hard safety stop per Start press. Prevents an unbounded spend/loop.
    maxAttemptsPerRun: 250,

    // Planning is token-unconstrained, so feeds are generous: the dedup filter,
    // not prompt size, is what stops the agent from thrashing.
    // The tested contract is a flat 5-name ladder with three scored proposals.
    proposalsPerTurn: 3,
    ladderLength: 5,
    candidatePoolSize: 40,
    maxCraftsPerPlan: 8,
    // Reachable-first selection: keep the closest known elements in play and stop
    // when many crafts have produced nothing closer to the target.
    topKElements: 3,
    closenessStallLimit: 12,
    minViableCloseness: 0.05,
    maxElementsInPrompt: 2000,
    maxRecipesInPrompt: 200,
    maxFailuresInPrompt: 100,
    maxTriedPairsInPrompt: 1000,

    // Higher sampling temperature breaks the deterministic same-pair loop that
    // temperature 0 caused the same-pair loop when the prompt barely changed.
    primaryMaxTokens: 4096,
    fallbackMaxTokens: 8192,
    temperature: 0.7,

    // A long stretch without a discovery means the planner has stopped opening
    // new ground; the next prompt is forced into explore mode.
    consecutiveNoNewLimit: 12,
    maxGoalsStored: 8,
    maxRecipeMemoryEntries: 20000,
    // Batch that is mostly already-tried proposals gets one repair re-prompt.
    repairDropRatio: 0.5,

    requestTimeoutMs: 20000,
    // The large fallback body needs far longer than the primary; paired with a
    // single transport retry this bounds the worst case without cascading timeouts.
    fallbackTimeoutMs: 45000,

    // Measured-slow endpoints are excluded from the dropdown. An explicit saved
    // override is still honored, with a warning instead of a silent substitution.
    slowModelIds: ['stealth/glyph-cluster'],
    preferredFastModel: 'nvidia/nemotron-3.5-lightning:free',
    // Routers look fast but plan poorly; demote them for planning calls only.
    planningModelIds: [
      'nvidia/nemotron-3.5-lightning:free',
      'inclusionai/ling-3.2-flash:free'
    ],
    honorRouterOverrideForPlanning: false,

    // Animated drag is opt-in. Duration is the whole visible drag, split across
    // the move steps; the panel input overrides it and persists it.
    dragDurationMs: 900,

    // Used when GET /models fails or returns no free models. Snapshot from
    // 2026-11-23; IDs rot, so the log calls these out as unverified.
    fallbackFreeModels: [
      'inclusionai/ling-3.2-flash:free',
      'liquid/lfm-3-1.7b:free'
    ],

    // Item-count caps, not byte-size measurement: growth is bounded in practice
    // because each history entry is small. The prompt samples the tail of each
    // list (see CONFIG.max*InPrompt).
    maxRecipesStored: 500,
    maxFailuresStored: 300
  };

  const STORAGE = {
    kb: 'icai.kb.v1',
    key: 'icai.key',
    model: 'icai.model',
    delay: 'icai.delay',
    dragMode: 'icai.dragMode',
    dragDuration: 'icai.dragDuration'
  };

  // A real "Nothing" craft and an observer teardown must never collapse into the
  // same value: one is recorded, the other aborts without touching the KB.
  const NO_RESULT = { noResult: true };
  const CANCELLED = { cancelled: true };

  const BASE_ELEMENTS = ['Water', 'Fire', 'Wind', 'Earth'];
  const TARGET_STOP_WORDS = new Set(['of', 'the', 'and', 'in', 'on']);

  // Deterministic theme weights. These are planning hints, not game facts; for an
  // unmapped target we fall back to its literal tokens. Source-attributed recipes
  // below are editorial starting points that the game always gets to validate.
  const TARGET_THEME_LEXICON = {
    'doctor who': { doctor: 1, tardis: 1, alien: 0.9, ufo: 0.9, time: 0.8,
      police: 0.8, abduction: 0.7, companion: 0.6, travel: 0.5, machine: 0.5,
      rose: 0.4, steam: 0.3, hourglass: 0.3 },
    'godzilla': { godzilla: 1, dragon: 0.9, monster: 0.8, bulldozer: 0.7,
      lizard: 0.7, atomic: 0.6, radiation: 0.5, city: 0.4 },
    'star wars': { 'star wars': 1, tatooine: 0.9, dune: 0.8, planet: 0.7,
      jedi: 0.8, force: 0.7, space: 0.6, laser: 0.5, sand: 0.4 },
    'goku': { goku: 1, 'dragon ball': 0.9, 'dragon ball z': 0.9, saiyan: 0.8,
      fighting: 0.7, martial: 0.7, power: 0.6, dragon: 0.5 }
  };

  const RECIPE_MEMORY_SEED_VERSION = 2;

  // Hand-written starting recipes. `expected` is a seed fact supplied by us, not a
  // model prediction; a mismatch refutes only the seed, never a model guess.
  const SEED_RECIPES_RAW = [
    // Base progression (editorial).
    { target: '*', a: 'Fire', b: 'Water', result: 'Steam', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Fire', b: 'Steam', result: 'Engine', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Earth', b: 'Water', result: 'Plant', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Plant', b: 'Water', result: 'Swamp', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Fire', b: 'Swamp', result: 'Dragon', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Earth', b: 'Earth', result: 'Mountain', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Earth', b: 'Wind', result: 'Dust', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Lava', b: 'Water', result: 'Stone', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Stone', b: 'Fire', result: 'Metal', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Metal', b: 'Fire', result: 'Iron', verification: 'editorial', note: 'base' },
    { target: '*', a: 'Plant', b: 'Earth', result: 'Flower', verification: 'editorial', note: 'base' },
    // Doctor Who chain (source-attributed, version-dependent).
    { target: 'doctor who', a: 'Steam', b: 'Fire', result: 'Engine', verification: 'source-checked', note: 'DW prerequisite' },
    { target: 'doctor who', a: 'Plant', b: 'Engine', result: 'Car', verification: 'source-checked', note: 'DW prerequisite' },
    { target: 'doctor who', a: 'Wind', b: 'Water', result: 'Wave', verification: 'editorial', note: 'DW prerequisite' },
    { target: 'doctor who', a: 'Wave', b: 'Earth', result: 'Sand', verification: 'editorial', note: 'DW prerequisite' },
    { target: 'doctor who', a: 'Sand', b: 'Fire', result: 'Glass', verification: 'editorial', note: 'DW prerequisite' },
    { target: 'doctor who', a: 'Glass', b: 'Sand', result: 'Hourglass', verification: 'source-checked', note: 'DW prerequisite' },
    { target: 'doctor who', a: 'Hourglass', b: 'Sand', result: 'Time', verification: 'source-checked', note: 'DW prerequisite' },
    { target: 'doctor who', a: 'Hourglass', b: 'Car', result: 'Time Machine', verification: 'inferred', note: 'DW chain' },
    { target: 'doctor who', a: 'Iron', b: 'Flower', result: 'Rose', verification: 'inferred', note: 'DW chain' },
    { target: 'doctor who', a: 'Rose', b: 'Time Machine', result: 'Rose Tyler', verification: 'inferred', note: 'DW chain' },
    { target: 'doctor who', a: 'Rose Tyler', b: 'Time Machine', result: 'Doctor Who', verification: 'source-checked', note: 'DW target' },
    // Godzilla (source-attributed).
    { target: 'godzilla', a: 'Bulldozer', b: 'Dragon', result: 'Godzilla', verification: 'source-checked', note: 'Godzilla target' },
    { target: 'godzilla', a: 'Dragon', b: 'City', result: 'Godzilla', verification: 'inferred', note: 'Godzilla alt' },
    // Star Wars (source-attributed).
    { target: 'star wars', a: 'Dune', b: 'Planet', result: 'Tatooine', verification: 'source-checked', note: 'SW chain' },
    { target: 'star wars', a: 'Fire', b: 'Tatooine', result: 'Star Wars', verification: 'source-checked', note: 'SW target' },
    // Goku (source-attributed).
    { target: 'goku', a: 'Dragon Ball Z', b: 'Wind', result: 'Goku', verification: 'source-checked', note: 'Goku target' },
    { target: 'goku', a: 'Dragon Ball', b: 'Fire', result: 'Dragon Ball Z', verification: 'inferred', note: 'Goku chain' }
  ];

  // One row per pair: global seeds and stronger verification win, so a duplicate
  // cannot override a better-sourced version of the same combination.
  function dedupeSeedRecipes(rows) {
    const strength = { inferred: 0, editorial: 1, 'source-checked': 2 };
    const byKey = new Map();
    for (const row of rows) {
      const key = pairKey(row.a, row.b);
      const prior = byKey.get(key);
      if (!prior) {
        byKey.set(key, Object.assign({}, row));
        continue;
      }
      const score = (r) => (r.target === '*' ? 10 : 0) + (strength[r.verification] || 0);
      if (score(row) > score(prior)) byKey.set(key, Object.assign({}, row));
    }
    return Array.from(byKey.values());
  }

  const SEED_RECIPES = dedupeSeedRecipes(SEED_RECIPES_RAW);

  const GATEWAY_PATHS = {
    chat: '/chat/completions',
    models: '/models'
  };

  // Tampermonkey runs in a sandbox: plain `window` is the script's wrapper.
  // `unsafeWindow` is the page's real window, where the game registers `IC`.
  const page = (typeof unsafeWindow !== 'undefined') ? unsafeWindow : window;

  // ---------------------------------------------------------------------------
  // State and control-signal primitives.
  // ---------------------------------------------------------------------------

  class StopSignal extends Error {
    constructor(message, level = 'info') {
      super(message);
      this.name = 'StopSignal';
      this.level = level;
    }
  }

  class KiloError extends Error {
    constructor(message, status = 0, retryable = false) {
      super(message);
      this.name = 'KiloError';
      this.status = status;
      this.retryable = retryable;
    }
  }

  const state = {
    IC: null,
    running: false,
    stopped: false,
    key: '',
    freeMode: true,
    modelOverride: '',
    autoModel: null,
    freeModelIds: null,
    model: '',
    planningModel: '',
    craftsSinceClosenessGain: 0,
    bestCloseness: 0,
    activeRequest: null,
    rateLimitHits: 0,
    consecutiveNoNew: 0,
    dragMode: false,
    planContext: null,
    lastCraft: null,
    dragTouch: false,
    userToggledCollapse: false,
    session: null,
    requestPurpose: 'plan',
    lastAiResponse: null,
    lastDragDiagnostics: null,
    lastAnomalyRecord: null
  };

  const SCRIPT_VERSION = '1.0.0';
  const MAX_SESSION_EVENTS = 2000;
  const MAX_SESSION_LOG_LINES = 5000;
  const SENSITIVE_FIELD_NAMES = new Set([
    'key', 'apikey', 'api_key', 'token', 'accesstoken', 'access_token',
    'refreshtoken', 'refresh_token', 'authorization', 'auth', 'secret',
    'password', 'credential', 'credentials', 'bearer'
  ]);

  let kb = loadKB();

  function newSession(rawTarget, canonical) {
    return {
      startedAt: new Date().toISOString(),
      endedAt: null,
      target: rawTarget,
      canonical: canonical,
      model: state.model || '',
      mode: state.dragMode ? 'drag' : 'instant',
      status: 'running',
      startedMs: Date.now(),
      events: [],
      logLines: [],
      droppedEvents: 0,
      droppedLogLines: 0,
      aiRequestCount: 0,
      kbAtStart: null,
      kbAtEnd: null
    };
  }

  function sessionElapsedMs() {
    const session = state.session;
    return session ? Date.now() - session.startedMs : 0;
  }

  function kbSnapshot() {
    return {
      elementCount: Object.keys(kb.elements).length,
      elementNames: Object.keys(kb.elements),
      recipeCount: kb.recipes.length,
      recipes: kb.recipes.slice(),
      failureCount: kb.failures.length,
      failures: kb.failures.slice(),
      triedPairCount: Object.keys(kb.triedPairs).length,
      triedPairs: Object.values(kb.triedPairs).slice(),
      recipeMemory: Object.values(kb.recipeMemory).slice(),
      recipeMemoryCount: Object.keys(kb.recipeMemory).length,
      seedCount: Object.values(kb.recipeMemory).filter(e => e.source === 'seed').length,
      observedCount: Object.values(kb.recipeMemory).filter(e => e.source === 'observed').length,
      refutedSeeds: kb.refutedSeeds.slice(),
      goals: kb.goals.slice(),
      // kb.plan is mutated in place, so a live reference would make both snapshots
      // show the final state and erase the refutation delta.
      plan: JSON.parse(JSON.stringify(kb.plan)),
      patterns: kb.patterns.slice(),
      attempts: kb.attempts,
      discoveries: kb.discoveries
    };
  }

  function addedSince(before, after, keyFn) {
    const seen = new Set(before.map(keyFn));
    return after.filter(item => !seen.has(keyFn(item)));
  }

  function kbDelta(start, end) {
    if (!start || !end) return null;
    const keyOfRecipe = (r) => pairKey(r.a, r.b) + '|' + String(r.result);
    const keyOfPair = (p) => pairKey(p.a, p.b);
    const keyOfSeedRefutation = (s) => pairKey(s.a, s.b) + '|' + s.expected + '|' + s.at;
    return {
      newElements: addedSince(start.elementNames, end.elementNames, n => n),
      newRecipes: addedSince(start.recipes, end.recipes, keyOfRecipe),
      newFailures: addedSince(start.failures, end.failures, keyOfPair),
      newTriedPairs: addedSince(start.triedPairs, end.triedPairs, keyOfPair),
      newRefutedSeeds: addedSince(start.refutedSeeds, end.refutedSeeds, keyOfSeedRefutation),
      newRefutedRecipes: addedSince(
        start.plan.refutedRecipes, end.plan.refutedRecipes,
        r => pairKey(r.a, r.b) + '|' + String(r.result)
      ),
      newPatterns: addedSince(start.patterns, end.patterns, s => s),
      newMemoryEntries: addedSince(
        start.recipeMemory, end.recipeMemory,
        e => pairKey(e.a, e.b) + '|' + String(e.result)
      ),
      attemptsDelta: end.attempts - start.attempts,
      discoveriesDelta: end.discoveries - start.discoveries
    };
  }

  function completeRun(reason, attemptNumber) {
    if (!state.session) return;
    state.session.status = 'complete';
    recordEvent('run_complete', { reason: reason, attempt: attemptNumber });
  }

  function isSensitiveField(name) {
    const normalized = String(name).toLowerCase().replace(/[\s_-]+/g, '_').replace(/^_+|_+$/g, '');
    return SENSITIVE_FIELD_NAMES.has(normalized);
  }

  // Credential-shaped field names are dropped at every depth, and string values are
  // scrubbed of token markers reflected from gateway errors. This reduces but cannot
  // absolutely guarantee removal from arbitrary third-party error text.
  function sanitizeEventData(value) {
    if (typeof value === 'string') {
      return value
        .replace(/\bBearer\s+\S+/gi, '[redacted]')
        .replace(/\b(?:key|api[_-]?key|token|access[_-]?token|refresh[_-]?token|authorization|auth|secret|password|credential|credentials|bearer)\s*[:=]\s*\S+/gi, '[redacted]')
        .replace(/\b(?:sk|pk|gsk|ghp|gho|xox[baprs])-[A-Za-z0-9_-]{8,}/gi, '[redacted]');
    }
    if (Array.isArray(value)) return value.map(sanitizeEventData);
    if (value && typeof value === 'object') {
      const copy = {};
      for (const name of Object.keys(value)) {
        if (isSensitiveField(name)) continue;
        copy[name] = sanitizeEventData(value[name]);
      }
      return copy;
    }
    return value;
  }

  function recordEvent(type, data) {
    const session = state.session;
    if (!session) return;
    const event = { at: new Date().toISOString(), type: type };
    if (data && typeof data === 'object') {
      Object.assign(event, sanitizeEventData(data));
    }
    session.events.push(event);
    if (session.events.length > MAX_SESSION_EVENTS) {
      session.events.splice(0, session.events.length - MAX_SESSION_EVENTS);
      session.droppedEvents++;
    }
  }

  function recordAnomaly(data) {
    recordEvent('http_anomaly', data);
    // Hold the record itself: event-cap trimming can invalidate array indices.
    if (state.session) {
      state.lastAnomalyRecord = state.session.events[state.session.events.length - 1];
    }
  }

  function lastAiRequestSnapshot() {
    const session = state.session;
    if (!session) return null;
    for (let i = session.events.length - 1; i >= 0; i--) {
      if (session.events[i].type === 'ai_request') return session.events[i];
    }
    return null;
  }

  function errorRecord(error, extra) {
    const e = error || {};
    const record = {
      name: e.name || 'Error',
      message: e.message || String(error),
      stack: e.stack || '',
      status: e.status !== undefined ? e.status : null,
      code: e.code !== undefined ? e.code : null,
      detail: e.detail !== undefined ? e.detail : null,
      level: e.level || null,
      context: {
        target: state.session ? state.session.canonical : '',
        activeIndex: kb.plan.activeIndex,
        activeStep: kb.plan.ladder[kb.plan.activeIndex] || '',
        ladder: kb.plan.ladder.slice(),
        craftsSinceReplan: kb.plan.craftsSinceReplan,
        consecutiveNoNew: state.consecutiveNoNew,
        lastCraft: state.lastCraft,
        lastAiRequest: lastAiRequestSnapshot()
      }
    };
    if (extra) Object.assign(record, extra);
    return record;
  }

  function buildRunExport(session) {
    const events = session.events.slice();
    const logLines = session.logLines.slice();
    const endSnapshot = session.kbAtEnd || kbSnapshot();
    const summary = {
      attempts: 0,
      crafts: 0,
      newDiscoveries: 0,
      knownResults: 0,
      noResults: 0,
      failures: 0,
      refutations: 0,
      seedRefutations: 0,
      rePlans: 0,
      aiRequests: 0,
      acceptedResponses: 0,
      rejectedResponses: 0,
      finalLadder: kb.plan.ladder.slice(),
      activeStep: kb.plan.ladder[kb.plan.activeIndex] || ''
    };
    for (const event of events) {
      if (event.type === 'attempt') summary.attempts++;
      else if (event.type === 'craft') {
        summary.crafts++;
        if (event.infraFailure) summary.failures++;
        else if (event.isNew) summary.newDiscoveries++;
        else if (event.result) summary.knownResults++;
        else summary.noResults++;
      } else if (event.type === 'ai_request') summary.aiRequests++;
      else if (event.type === 'ai_response' && event.accepted === true) summary.acceptedResponses++;
      else if (event.type === 'ai_response' && event.accepted === false) summary.rejectedResponses++;
      else if (event.type === 'refutation') summary.refutations++;
      else if (event.type === 'seed_refuted') summary.seedRefutations++;
      else if (event.type === 'plan_clear') summary.rePlans++;
    }
    return {
      schemaVersion: 2,
      exportedAt: new Date().toISOString(),
      scriptVersion: SCRIPT_VERSION,
      run: {
        startedAt: session.startedAt,
        endedAt: session.endedAt,
        target: session.target,
        canonical: session.canonical,
        model: session.model,
        mode: session.mode,
        status: session.status
      },
      summary: summary,
      events: events,
      logLines: sanitizeEventData(logLines),
      export: {
        truncated: session.droppedEvents > 0 || session.droppedLogLines > 0,
        droppedEvents: session.droppedEvents,
        droppedLogLines: session.droppedLogLines,
        eventCap: MAX_SESSION_EVENTS,
        logLineCap: MAX_SESSION_LOG_LINES
      },
      kbAtStart: sanitizeEventData(session.kbAtStart),
      kbAtEnd: sanitizeEventData(endSnapshot),
      kbDelta: sanitizeEventData(kbDelta(session.kbAtStart, endSnapshot)),
      closenessContext: sanitizeEventData(session.closenessContext || null),
      notes: 'Current run only (since Start). Model predictions are not used; seed "expected" values are hand-written facts validated by the game.'
    };
  }

  function exportRunJson() {
    const session = state.session;
    if (!session) {
      log('No run to export yet — press Start first.', 'error');
      return;
    }
    try {
      const text = JSON.stringify(buildRunExport(session), null, 2);
      const blob = new Blob([text], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      const now = new Date();
      const pad = (n) => String(n).padStart(2, '0');
      const stamp = now.getFullYear() + pad(now.getMonth() + 1) + pad(now.getDate()) + '-' +
        pad(now.getHours()) + pad(now.getMinutes()) + pad(now.getSeconds());
      anchor.href = url;
      anchor.download = 'infinite-craft-run-' + stamp + '.json';
      anchor.style.display = 'none';
      (document.body || document.documentElement).appendChild(anchor);
      anchor.click();
      anchor.parentNode.removeChild(anchor);
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      log('Exported run JSON (' + session.events.length + ' events).', 'success');
    } catch (e) {
      log('Export failed: ' + e.message, 'error');
    }
  }

  // Stop must interrupt a sleeping delay immediately, so sleeps register a
  // wake callback that Stop fires. A plain setTimeout would ignore Stop for
  // up to the full delay.
  let sleepWaiters = [];

  function sleep(ms) {
    return new Promise((resolve) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        sleepWaiters = sleepWaiters.filter(fn => fn !== finish);
        resolve();
      };
      const timer = setTimeout(finish, Math.max(0, ms));
      sleepWaiters.push(finish);
    });
  }

  function wakeWaiters() {
    const waiters = sleepWaiters.slice();
    sleepWaiters = [];
    waiters.forEach(fn => fn());
  }

  function assertRunning() {
    if (state.stopped) throw new StopSignal('Stopped.');
  }

  async function jitterSleep(ms, jitter = true) {
    const spread = Math.min(400, ms * 0.4);
    const actual = jitter
      ? Math.max(0, Math.round(ms + (Math.random() - 0.5) * spread))
      : ms;
    await sleep(actual);
    assertRunning();
  }

  // ---------------------------------------------------------------------------
  // Persistence. The API key is stored separately and NEVER inside the KB.
  // ---------------------------------------------------------------------------

  // A delimiter that cannot occur in element names makes the canonical key
  // order-independent without colliding with names containing spaces or '+'.
  function pairKey(a, b) {
    return a < b ? a + '\u0000' + b : b + '\u0000' + a;
  }

  function loadTriedPairs(data) {
    const fresh = Object.create(null);
    const existing = data.triedPairs;
    if (existing && typeof existing === 'object' && !Array.isArray(existing)) {
      for (const key of Object.keys(existing)) {
        const p = existing[key];
        if (!p || !safeMapKey(key, p.a, p.b)) continue;
        fresh[key] = {
          a: p.a, b: p.b,
          result: typeof p.result === 'string' ? p.result : '',
          isNew: !!p.isNew,
          misses: Number(p.misses) || 0,
          at: Number(p.at) || 0
        };
      }
      return fresh;
    }

    // Pre-dedup knowledge bases have no triedPairs; reconstruct from history so
    // old recipes are not immediately re-proposed and burned again.
    const recipes = Array.isArray(data.recipes) ? data.recipes : [];
    const failures = Array.isArray(data.failures) ? data.failures : [];
    for (const r of recipes) {
      if (!r || typeof r.a !== 'string' || typeof r.b !== 'string') continue;
      fresh[pairKey(r.a, r.b)] = {
        a: r.a, b: r.b, result: String(r.result || ''),
        isNew: !!r.isNew, misses: 0, at: Number(r.at) || 0
      };
    }
    for (const f of failures) {
      if (!f || typeof f.a !== 'string' || typeof f.b !== 'string') continue;
      const key = pairKey(f.a, f.b);
      const prior = hasOwn(fresh, key) ? fresh[key] : null;
      fresh[key] = {
        a: f.a, b: f.b, result: null, isNew: false,
        misses: (prior ? Number(prior.misses) || 0 : 0) + 1,
        at: Number(f.at) || 0
      };
    }
    return fresh;
  }

  function trimRecipeMemory(memory) {
    const size = Object.keys(memory).length;
    if (size <= CONFIG.maxRecipeMemoryEntries) return;
    const removable = Object.keys(memory)
      .filter(k => memory[k].source !== 'seed')
      .sort((x, y) => (memory[x].updatedAt || 0) - (memory[y].updatedAt || 0));
    const drop = Math.min(removable.length, size - CONFIG.maxRecipeMemoryEntries);
    for (let i = 0; i < drop; i++) delete memory[removable[i]];
  }

  function safeMapKey(key, a, b) {
    if (key === '__proto__' || key === 'constructor' || key === 'prototype') return false;
    if (typeof a !== 'string' || typeof b !== 'string' || !a || !b) return false;
    return key === pairKey(a, b);
  }

  function safeElementName(name) {
    return typeof name === 'string' && !!name.length &&
      name !== '__proto__' && name !== 'constructor' && name !== 'prototype';
  }

  function loadRecipeMemory(data) {
    const stored = (data && data.recipeMemory && typeof data.recipeMemory === 'object' &&
      !Array.isArray(data.recipeMemory)) ? data.recipeMemory : null;
    const memory = Object.create(null);
    if (stored) {
      for (const key of Object.keys(stored)) {
        const e = stored[key];
        if (!e || !safeMapKey(key, e.a, e.b)) continue;
        memory[key] = {
          a: e.a, b: e.b,
          result: typeof e.result === 'string' ? e.result : '',
          isNew: !!e.isNew,
          source: e.source === 'seed' ? 'seed' : (e.source === 'migrated' ? 'migrated' : 'observed'),
          verification: typeof e.verification === 'string' ? e.verification : '',
          target: typeof e.target === 'string' ? e.target : '*',
          hits: Number(e.hits) || 0,
          confirms: Number(e.confirms) || 0,
          refutations: Number(e.refutations) || 0,
          at: Number(e.at) || 0,
          updatedAt: Number(e.updatedAt) || 0,
          lastRefutedAt: Number(e.lastRefutedAt) || 0,
          disabled: !!e.disabled
        };
      }
    } else {
      const recipes = Array.isArray(data.recipes) ? data.recipes : [];
      const failures = Array.isArray(data.failures) ? data.failures : [];
      for (const r of recipes) {
        if (!r || typeof r.a !== 'string' || typeof r.b !== 'string') continue;
        memory[pairKey(r.a, r.b)] = {
          a: r.a, b: r.b, result: String(r.result || ''), isNew: !!r.isNew,
          source: 'migrated', verification: '', target: '*', hits: 1,
          confirms: 0, refutations: 0, at: Number(r.at) || 0,
          updatedAt: Number(r.at) || 0, lastRefutedAt: 0, disabled: false
        };
      }
      for (const f of failures) {
        if (!f || typeof f.a !== 'string' || typeof f.b !== 'string') continue;
        memory[pairKey(f.a, f.b)] = {
          a: f.a, b: f.b, result: '', isNew: false,
          source: 'migrated', verification: '', target: '*', hits: 1,
          confirms: 0, refutations: 0, at: Number(f.at) || 0,
          updatedAt: Number(f.at) || 0, lastRefutedAt: 0, disabled: false
        };
      }
    }
    // Seeds never overwrite an observation; they only fill absent keys.
    if ((data && data.recipeMemorySeedVersion) !== RECIPE_MEMORY_SEED_VERSION) {
      for (const seed of SEED_RECIPES) {
        const key = pairKey(seed.a, seed.b);
        if (hasOwn(memory, key) && memory[key].source === 'observed') continue;
        memory[key] = {
          a: seed.a, b: seed.b, result: seed.result, isNew: false,
          source: 'seed', verification: seed.verification || 'editorial',
          target: seed.target || '*', hits: 0, confirms: 0, refutations: 0,
          at: 0, updatedAt: 0, lastRefutedAt: 0, disabled: false
        };
      }
    }
    trimRecipeMemory(memory);
    return memory;
  }

  function loadPlan(data) {
    const p = (data && typeof data.plan === 'object') ? data.plan : {};
    const ladder = Array.isArray(p.ladder)
      ? p.ladder.filter(step => typeof step === 'string' && step.trim()).slice(0, CONFIG.ladderLength)
      : [];
    return {
      ladder: ladder,
      activeIndex: Number.isInteger(p.activeIndex) ? Math.max(0, Math.min(CONFIG.ladderLength - 1, p.activeIndex)) : 0,
      mode: p.mode === 'continuation' ? 'continuation' : 'fresh',
      refutedRecipes: Array.isArray(p.refutedRecipes)
        ? p.refutedRecipes.filter(r => r && typeof r.a === 'string' && typeof r.b === 'string').slice(-12) : [],
      craftsSinceReplan: Number.isFinite(p.craftsSinceReplan) ? p.craftsSinceReplan : 0,
      updatedAt: Number(p.updatedAt) || 0
    };
  }

  function loadElements(data) {
    const result = Object.create(null);
    const source = (data && data.elements && typeof data.elements === 'object' &&
      !Array.isArray(data.elements)) ? data.elements : {};
    for (const name of Object.keys(source)) {
      if (!safeElementName(name)) continue;
      const meta = source[name];
      result[name] = meta && typeof meta === 'object'
        ? Object.assign({}, meta)
        : { emoji: '', firstSeen: 0 };
    }
    return result;
  }

  function loadKB() {
    const raw = GM_getValue(STORAGE.kb, null);
    let data = raw;
    if (typeof raw === 'string') {
      try {
        data = JSON.parse(raw);
      } catch (e) {
        log('Knowledge base was corrupt JSON; starting fresh (' + e.message + ')', 'error');
        data = null;
      }
    }
    if (!data || typeof data !== 'object') data = {};
    const loaded = {
      version: 1,
      elements: loadElements(data),
      recipes: Array.isArray(data.recipes) ? data.recipes : [],
      failures: Array.isArray(data.failures) ? data.failures : [],
      triedPairs: loadTriedPairs(data),
      plan: loadPlan(data),
      goals: Array.isArray(data.goals) ? data.goals.slice(-CONFIG.maxGoalsStored) : [],
      recipeMemory: loadRecipeMemory(data),
      refutedSeeds: Array.isArray(data.refutedSeeds) ? data.refutedSeeds.slice(-500) : [],
      recipeMemorySeedVersion: RECIPE_MEMORY_SEED_VERSION,
      patterns: Array.isArray(data.patterns)
        ? data.patterns.filter(n => typeof n === 'string')
        : [],
      attempts: Number(data.attempts) || 0,
      discoveries: Number(data.discoveries) || 0,
      updatedAt: Number(data.updatedAt) || 0
    };
    trimArray(loaded.recipes, CONFIG.maxRecipesStored);
    trimArray(loaded.failures, CONFIG.maxFailuresStored);
    return loaded;
  }

  function saveKB() {
    kb.updatedAt = Date.now();
    try {
      GM_setValue(STORAGE.kb, kb);
    } catch (e) {
      log('Could not save knowledge base: ' + e.message, 'error');
    }
  }

  function hasOwn(obj, key) {
    return Object.prototype.hasOwnProperty.call(obj, key);
  }

  function trimArray(arr, max) {
    if (arr.length > max) arr.splice(0, arr.length - max);
  }

  function isPairTried(a, b) {
    return hasOwn(kb.triedPairs, pairKey(a, b));
  }

  function markPairTried(a, b, result, isNew) {
    const key = pairKey(a, b);
    const prior = hasOwn(kb.triedPairs, key) ? kb.triedPairs[key] : null;
    kb.triedPairs[key] = {
      a: a,
      b: b,
      result: result,
      isNew: !!isNew,
      misses: result == null ? (prior ? Number(prior.misses) || 0 : 0) + 1 : 0,
      at: Date.now()
    };
  }

  // Repeated entries used to stack identical lines in the prompt and bias the
  // model toward them, so a known pair updates its existing history record.
  function recordRecipe(a, b, result, isNew) {
    const key = pairKey(a, b);
    const prior = kb.recipes.find(r => r && typeof r.a === 'string' && typeof r.b === 'string' && pairKey(r.a, r.b) === key);
    if (prior) {
      prior.repeats = (Number(prior.repeats) || 0) + 1;
      prior.at = Date.now();
      return;
    }
    kb.recipes.push({ a: a, b: b, result: result, isNew: !!isNew, repeats: 0, at: Date.now() });
    trimArray(kb.recipes, CONFIG.maxRecipesStored);
  }

  function recordFailure(a, b) {
    const key = pairKey(a, b);
    const prior = kb.failures.find(f => f && typeof f.a === 'string' && typeof f.b === 'string' && pairKey(f.a, f.b) === key);
    if (prior) {
      prior.repeats = (Number(prior.repeats) || 0) + 1;
      prior.at = Date.now();
      return;
    }
    kb.failures.push({ a: a, b: b, repeats: 0, at: Date.now() });
    trimArray(kb.failures, CONFIG.maxFailuresStored);
  }

  // Observed outcomes always win over seed expectations. A refuted seed is disabled
  // and remembered; the game result becomes the stored truth for that pair.
  function noteRecipeMemory(a, b, result, meta) {
    const key = pairKey(a, b);
    const prior = hasOwn(kb.recipeMemory, key) ? kb.recipeMemory[key] : null;
    const entry = prior || {
      a: a, b: b, result: '', isNew: false, source: 'observed', verification: '',
      target: (meta && meta.target) || '*', hits: 0, confirms: 0, refutations: 0,
      at: Date.now(), updatedAt: 0, lastRefutedAt: 0, disabled: false
    };
    entry.hits++;
    entry.updatedAt = Date.now();
    if (meta && meta.source) entry.source = meta.source;
    if (meta && meta.target) entry.target = meta.target;

    const expected = (meta && meta.expected) || '';
    if (!expected) {
      entry.result = result == null ? '' : String(result);
      if (result != null) entry.isNew = !!(meta && meta.isNew);
      if (entry.source === 'seed') entry.source = 'observed';
    } else if (result != null && String(result).toLowerCase() !== expected.toLowerCase()) {
      entry.refutations++;
      entry.lastRefutedAt = Date.now();
      entry.disabled = true;
      entry.result = String(result);
      entry.source = 'observed';
      kb.refutedSeeds.push({
        a: a, b: b, expected: expected, actual: String(result),
        target: entry.target, at: Date.now()
      });
      trimArray(kb.refutedSeeds, 500);
      recordEvent('seed_refuted', {
        a: a, b: b, expected: expected, actual: String(result),
        target: entry.target, pair: a + ' + ' + b
      });
      log('Seed refuted: ' + a + ' + ' + b + ' expected ' + expected + ', got ' + result, 'error');
    } else {
      entry.confirms++;
      if (result != null) {
        entry.result = String(result);
        if (meta && meta.isNew) entry.isNew = true;
      }
    }
    kb.recipeMemory[key] = entry;

    trimRecipeMemory(kb.recipeMemory);
  }

  function reachableSeedSteps(target) {
    const wanted = String(target).toLowerCase();
    return SEED_RECIPES.filter(seed =>
      seed.target === '*' || String(seed.target).toLowerCase() === wanted);
  }

  function findReachableSeedStep(target) {
    for (const seed of reachableSeedSteps(target)) {
      if (!hasElement(seed.a) || !hasElement(seed.b)) continue;
      if (hasElement(seed.result)) continue;
      if (isPairTried(seed.a, seed.b)) continue;
      const mem = kb.recipeMemory[pairKey(seed.a, seed.b)];
      if (mem && mem.disabled) continue;
      return seed;
    }
    return null;
  }

  function reachableKnownRecipes(target, limit) {
    const out = [];
    for (const key of Object.keys(kb.recipeMemory)) {
      const e = kb.recipeMemory[key];
      if (!e || e.disabled || !e.result || hasElement(e.result)) continue;
      if (!hasElement(e.a) || !hasElement(e.b)) continue;
      if (isPairTried(e.a, e.b)) continue;
      if (e.target !== '*' && String(e.target).toLowerCase() !== String(target).toLowerCase()) continue;
      out.push(e);
      if (out.length >= limit) break;
    }
    return out;
  }

  function loadSettings() {
    state.key = String(GM_getValue(STORAGE.key, '') || '');
    state.freeMode = state.key.length === 0;
    state.modelOverride = String(GM_getValue(STORAGE.model, CONFIG.model) || '');
    const savedDelay = Number(GM_getValue(STORAGE.delay, CONFIG.attemptDelayMs));
    if (Number.isFinite(savedDelay) && savedDelay >= 250) {
      CONFIG.attemptDelayMs = savedDelay;
    }
    const savedDrag = GM_getValue(STORAGE.dragMode, false);
    state.dragMode = savedDrag === true || savedDrag === 'true';
    const savedDragDuration = Number(GM_getValue(STORAGE.dragDuration, CONFIG.dragDurationMs));
    if (Number.isFinite(savedDragDuration) && savedDragDuration >= 200 && savedDragDuration <= 3000) {
      CONFIG.dragDurationMs = savedDragDuration;
    }
  }

  // ---------------------------------------------------------------------------
  // UI construction. Every untrusted string is written via textContent.
  // ---------------------------------------------------------------------------

  const els = {};
  let panel = null;
  let mobileQuery = null;
  // loadKB() runs before the panel exists; keep its early messages until els.log is ready.
  const pendingLogs = [];

  function el(tag, opts = {}, children = []) {
    const node = document.createElement(tag);
    if (opts.id) node.id = opts.id;
    if (opts.type) node.type = opts.type;
    if (opts.placeholder) node.placeholder = opts.placeholder;
    if (opts.cls) node.className = opts.cls;
    if (opts.title) node.title = opts.title;
    if (opts.text != null) node.textContent = opts.text;
    if (opts.value != null) node.value = opts.value;
    if (opts.attrs) {
      Object.keys(opts.attrs).forEach(k => node.setAttribute(k, opts.attrs[k]));
    }
    children.forEach(child => { if (child) node.appendChild(child); });
    return node;
  }

  function injectCSS() {
    if (document.getElementById('icai-style')) return;
    const style = document.createElement('style');
    style.id = 'icai-style';
    style.textContent = `
      #icai-panel {
        box-sizing: border-box;
        position: fixed; top: 12px; right: 12px; width: 340px;
        max-height: calc(100vh - 24px); display: flex; flex-direction: column;
        background: rgba(16, 18, 24, 0.96); color: #e8e8ef;
        border: 1px solid #3a3f52; border-radius: 8px;
        box-shadow: 0 8px 28px rgba(0,0,0,.55);
        font: 12px/1.45 system-ui, -apple-system, Segoe UI, sans-serif;
        z-index: 2147483000;
      }
      #icai-panel * { box-sizing: border-box; }
      #icai-head {
        display: flex; align-items: center; justify-content: space-between;
        padding: 8px 10px; background: #222636; cursor: default;
        border-bottom: 1px solid #3a3f52; border-radius: 7px 7px 0 0;
      }
      #icai-head strong { font-size: 13px; letter-spacing: .2px; }
      #icai-collapse {
        display: none;
        background: transparent; color: #cfd3e6; border: 1px solid #4a5069;
        border-radius: 4px; cursor: pointer; padding: 0 7px; font-size: 14px;
      }
      #icai-body { padding: 10px; overflow: hidden; display: flex; flex-direction: column; gap: 8px; }
      #icai-panel label { display: block; color: #9aa1bd; margin-bottom: 3px; font-size: 11px; }
      #icai-panel input[type=text], #icai-panel input[type=password],
      #icai-panel input[type=number], #icai-panel select {
        width: 100%; padding: 5px 7px; color: #e8e8ef; background: #0f1119;
        border: 1px solid #414762; border-radius: 4px; outline: none;
      }
      #icai-panel input:focus, #icai-panel select:focus { border-color: #7c8ad0; }
      #icai-panel button {
        padding: 5px 9px; color: #e8e8ef; background: #333a55;
        border: 1px solid #505a80; border-radius: 4px; cursor: pointer;
      }
      #icai-panel button:hover:not(:disabled) { background: #414a6d; }
      #icai-panel button:disabled { opacity: .42; cursor: not-allowed; }
      .icai-row { display: flex; gap: 6px; align-items: center; }
      .icai-row > * { flex: 1 1 auto; }
      .icai-row > button { flex: 0 0 auto; }
      #icai-status { color: #8fd0ff; min-height: 16px; }
      #icai-counters { color: #b9c0dc; }
      #icai-keyrow input { flex: 1 1 auto; }
      #icai-log {
        height: 210px; overflow-y: auto; background: #0a0c12;
        border: 1px solid #343a50; border-radius: 4px; padding: 5px 7px;
        font: 11px/1.4 ui-monospace, Consolas, monospace;
      }
      .icai-line { white-space: pre-wrap; word-break: break-word; padding: 1px 0; }
      .icai-info { color: #aeb6d2; }
      .icai-success { color: #7ee08a; }
      .icai-fail { color: #e8b57a; }
      .icai-error { color: #ff8b8b; }
      #icai-panel .icai-hint { color: #6f7694; font-size: 10px; }
      #icai-panel.icai-collapsed #icai-body { display: none; }
      .icai-head-status { display: none; }

      /* Narrow screens become a bottom sheet; the board stays visible above it. */
      @media (max-width: 768px) {
        #icai-panel {
          top: auto; left: 0; right: 0; bottom: 0; width: auto;
          max-height: min(55vh, calc(100dvh - 12px));
          border-radius: 10px 10px 0 0;
          padding-bottom: env(safe-area-inset-bottom, 0px);
        }
        #icai-panel.icai-collapsed {
          max-height: calc(52px + env(safe-area-inset-bottom, 0px));
        }
        #icai-head {
          min-height: 52px; border-radius: 10px 10px 0 0; cursor: pointer;
        }
        #icai-head strong { flex: 0 0 auto; font-size: 12px; }
        .icai-head-status {
          display: block; flex: 1 1 auto; margin-left: 8px; text-align: right;
          color: #8fd0ff; font-size: 12px; white-space: nowrap;
          overflow: hidden; text-overflow: ellipsis;
        }
        #icai-collapse { display: inline-flex; align-items: center; justify-content: center; min-width: 44px; min-height: 44px; font-size: 18px; }
        #icai-body { flex: 1 1 auto; overflow-y: auto; }
        #icai-log { height: 130px; flex: 0 0 auto; }
        #icai-panel button { min-width: 44px; min-height: 44px; }
        #icai-panel input[type=checkbox] { width: 44px; height: 44px; min-width: 44px; min-height: 44px; }
        #icai-panel input[type=text], #icai-panel input[type=password],
        #icai-panel input[type=number], #icai-panel select {
          min-height: 44px; font-size: 16px;
        }
        .icai-row { flex-wrap: wrap; }
      }
    `;
    document.head.appendChild(style);
  }

  function buildPanel() {
    injectCSS();

    els.collapse = el('button', { id: 'icai-collapse', text: '▾', title: 'Collapse / expand' });
    els.headStatus = el('span', { cls: 'icai-head-status', text: 'Idle' });
    els.head = el('div', { id: 'icai-head' }, [
      el('strong', { text: 'AI Crafter · Infinite Craft' }),
      els.headStatus,
      els.collapse
    ]);

    els.target = el('input', { id: 'icai-target', type: 'text', placeholder: 'Target element (e.g. Dragon)' });
    els.start = el('button', { id: 'icai-start', text: 'Start' });
    els.stop = el('button', { id: 'icai-stop', text: 'Stop' });
    els.export = el('button', { id: 'icai-export', text: 'Export run JSON' });
    els.status = el('div', { id: 'icai-status', text: 'Idle' });
    els.attempts = el('span', { id: 'icai-attempts', text: '0' });
    els.discoveries = el('span', { id: 'icai-discoveries', text: '0' });
    const counters = el('div', { id: 'icai-counters' }, [
      document.createTextNode('Attempts: '), els.attempts,
      document.createTextNode('   Discoveries: '), els.discoveries
    ]);

    els.key = el('input', { id: 'icai-key', type: 'password', placeholder: 'Kilo API key (optional)' });
    els.keySave = el('button', { text: 'Save' });
    els.keyClear = el('button', { text: 'Clear' });
    const keyRow = el('div', { id: 'icai-keyrow', cls: 'icai-row' }, [
      els.key, els.keySave, els.keyClear
    ]);

    els.model = el('select', { id: 'icai-model' });
    els.modelRefresh = el('button', { id: 'icai-model-refresh', text: 'Refresh' });
    els.modelReset = el('button', { id: 'icai-model-reset', text: 'Preferred',
      title: 'Clear the saved model override and use the preferred fast model' });
    const modelRow = el('div', { cls: 'icai-row' }, [els.model, els.modelRefresh, els.modelReset]);

    els.delay = el('input', { id: 'icai-delay', type: 'number', attrs: { min: '250', max: '30000', step: '250' } });
    els.free = el('input', { id: 'icai-free', type: 'checkbox' });
    const delayRow = el('div', { cls: 'icai-row' }, [
      el('label', { text: 'Delay ms', attrs: { for: 'icai-delay' } }), els.delay,
      el('label', { text: 'Free mode', attrs: { for: 'icai-free' } }), els.free
    ]);

    els.drag = el('input', { id: 'icai-drag', type: 'checkbox' });
    els.dragDuration = el('input', { id: 'icai-drag-duration', type: 'number',
      attrs: { min: '200', max: '3000', step: '100' } });
    const dragRow = el('div', { cls: 'icai-row' }, [
      el('label', { text: 'Drag mode (animated)', attrs: { for: 'icai-drag' } }), els.drag,
      el('label', { text: 'Duration ms', attrs: { for: 'icai-drag-duration' } }), els.dragDuration
    ]);

    els.log = el('div', { id: 'icai-log' });

    const body = el('div', { id: 'icai-body' }, [
      el('label', { text: 'Target element' }),
      els.target,
      el('div', { cls: 'icai-row' }, [els.start, els.stop]),
      el('div', { cls: 'icai-row' }, [els.export]),
      els.status,
      counters,
      el('label', { text: 'Kilo API key (stored in Tampermonkey, never logged)' }),
      keyRow,
      el('label', { text: 'Free model — loaded from /models; IDs rotate' }),
      modelRow,
      delayRow,
      dragRow,
      el('div', { cls: 'icai-hint', text: 'Drag mode animates each combination on the board; it is slower and visual. Instant is the default.' }),
      el('label', { text: 'Activity log' }),
      els.log,
      el('div', { cls: 'icai-hint', text: 'Toggle with the Tampermonkey menu command.' })
    ]);

    panel = el('div', { id: 'icai-panel' }, [els.head, body]);
    (document.body || document.documentElement).appendChild(panel);
    mobileQuery = window.matchMedia ? window.matchMedia('(max-width: 768px)') : null;
    setCollapsed(!!(mobileQuery && mobileQuery.matches));
    if (mobileQuery && typeof mobileQuery.addEventListener === 'function') {
      mobileQuery.addEventListener('change', (e) => {
        // An explicit user choice survives unrelated resizes; the next breakpoint
        // crossing re-applies the platform default.
        if (state.userToggledCollapse) {
          state.userToggledCollapse = false;
          return;
        }
        setCollapsed(e.matches);
      });
    }

    // The saved key is written through the DOM value property after creation.
    // This is the only place the key enters the DOM; it is never logged or used
    // in an attribute string.
    els.key.value = state.key;
    applyModelOptions(CONFIG.fallbackFreeModels.slice(), false);
    els.delay.value = String(CONFIG.attemptDelayMs);
    els.free.checked = state.freeMode;
    els.drag.checked = state.dragMode;
    els.dragDuration.value = String(CONFIG.dragDurationMs);

    wireEvents();
    pendingLogs.forEach(entry => log(entry.message, entry.level));
    pendingLogs.length = 0;
    updateCounters();
  }

  function wireEvents() {
    els.head.addEventListener('click', (e) => {
      if (e.target === els.collapse) {
        state.userToggledCollapse = true;
        setCollapsed(!panel.classList.contains('icai-collapsed'));
      } else if (panel.classList.contains('icai-collapsed')) {
        setCollapsed(false);
      }
    });

    els.start.addEventListener('click', () => {
      if (state.running) return;
      const target = els.target.value.trim();
      if (!target) {
        log('Enter a target word first.', 'error');
        return;
      }
      if (!state.key) {
        log('No API key saved; using anonymous free access.', 'info');
      }
      void run(target);
    });

    els.target.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !state.running) els.start.click();
    });

    els.stop.addEventListener('click', () => stop('Stop requested.'));

    els.keySave.addEventListener('click', () => {
      const next = els.key.value.trim();
      state.key = next;
      GM_setValue(STORAGE.key, next);
      state.autoModel = null;
      if (next) {
        state.freeMode = false;
        els.free.checked = false;
      }
      log(next ? 'API key saved (masked).' : 'API key cleared.', 'info');
    });

    els.keyClear.addEventListener('click', () => {
      els.key.value = '';
      state.key = '';
      GM_setValue(STORAGE.key, '');
      state.freeMode = true;
      els.free.checked = true;
      state.autoModel = null;
      log('API key cleared; free mode enabled.', 'info');
    });

    els.model.addEventListener('change', () => {
      state.modelOverride = els.model.value;
      GM_setValue(STORAGE.model, state.modelOverride);
      state.autoModel = null;
      log('Free model saved: ' + state.modelOverride, 'info');
    });

    els.modelRefresh.addEventListener('click', () => { void refreshModelOptions(); });
    els.modelReset.addEventListener('click', resetModelToPreferred);

    els.delay.addEventListener('change', () => {
      const value = Number(els.delay.value);
      if (!Number.isFinite(value) || value < 250 || value > 30000) {
        els.delay.value = String(CONFIG.attemptDelayMs);
        log('Delay must be between 250 and 30000 ms.', 'error');
        return;
      }
      CONFIG.attemptDelayMs = value;
      GM_setValue(STORAGE.delay, value);
      log('Attempt delay set to ' + value + ' ms.', 'info');
    });

    els.free.addEventListener('change', () => {
      state.freeMode = els.free.checked;
      state.autoModel = null;
      log(state.freeMode ? 'Free mode on; requests will be unauthenticated.' : 'Free mode off; using saved key.', 'info');
    });

    els.drag.addEventListener('change', () => {
      state.dragMode = els.drag.checked;
      GM_setValue(STORAGE.dragMode, state.dragMode);
      log(state.dragMode
        ? 'Drag mode on: combinations will be animated on the board.'
        : 'Drag mode off: instant crafting restored.', 'info');
    });

    els.dragDuration.addEventListener('change', () => {
      const value = Number(els.dragDuration.value);
      if (!Number.isFinite(value) || value < 200 || value > 3000) {
        els.dragDuration.value = String(CONFIG.dragDurationMs);
        log('Drag duration must be between 200 and 3000 ms.', 'error');
        return;
      }
      CONFIG.dragDurationMs = value;
      GM_setValue(STORAGE.dragDuration, value);
      log('Drag duration set to ' + value + ' ms.', 'info');
    });

    els.stop.disabled = true;
    els.export.addEventListener('click', exportRunJson);
    els.export.disabled = !state.session;
  }

  function log(message, level = 'info') {
    if (!els.log) {
      pendingLogs.push({ message: message, level: level });
      return;
    }
    const line = el('div', { cls: 'icai-line icai-' + level });
    const now = new Date();
    const stamp = [now.getHours(), now.getMinutes(), now.getSeconds()]
      .map(n => String(n).padStart(2, '0')).join(':');
    line.textContent = '[' + stamp + '] ' + message;
    els.log.appendChild(line);
    while (els.log.children.length > 200) els.log.removeChild(els.log.firstChild);
    els.log.scrollTop = els.log.scrollHeight;

    // The DOM keeps 200 lines for readability; the export keeps the full timeline.
    const session = state.session;
    if (!session) return;
    session.logLines.push(sanitizeEventData({
      at: new Date().toISOString(), level: level, text: message
    }));
    if (session.logLines.length > MAX_SESSION_LOG_LINES) {
      session.logLines.splice(0, session.logLines.length - MAX_SESSION_LOG_LINES);
      session.droppedLogLines++;
    }
  }

  function setStatus(text) {
    if (els.status) els.status.textContent = text;
    if (els.headStatus) els.headStatus.textContent = text;
  }

  function updateCounters() {
    if (els.attempts) els.attempts.textContent = String(kb.attempts);
    if (els.discoveries) els.discoveries.textContent = String(kb.discoveries);
  }

  function setRunningUI(on) {
    els.start.disabled = on;
    els.target.disabled = on;
    els.stop.disabled = !on;
    els.drag.disabled = on;
    els.dragDuration.disabled = on;
    els.export.disabled = !state.session;
  }

  function setCollapsed(collapsed) {
    if (!panel) return;
    panel.classList.toggle('icai-collapsed', collapsed);
    els.collapse.textContent = collapsed ? '▴' : '▾';
    els.collapse.title = collapsed ? 'Expand panel' : 'Collapse panel';
  }

  function togglePanel() {
    if (!panel) return;
    panel.style.display = panel.style.display === 'none' ? '' : 'none';
  }

  function stop(message) {
    if (!state.running) return;
    state.stopped = true;
    wakeWaiters();
    if (state.activeRequest && typeof state.activeRequest.abort === 'function') {
      try {
        state.activeRequest.abort();
      } catch (e) {
        log('Could not abort in-flight request: ' + e.message, 'error');
      }
    }
    if (message) log(message, 'info');
  }

  // ---------------------------------------------------------------------------
  // Game bridge. Names are exact and case-sensitive when passed to IC.craft.
  // ---------------------------------------------------------------------------

  function waitForIC(timeoutMs) {
    return new Promise((resolve, reject) => {
      const deadline = Date.now() + timeoutMs;
      const tick = () => {
        if (state.stopped) {
          reject(new StopSignal('Stopped while waiting for the game API.'));
          return;
        }
        const IC = page.IC;
        if (IC && typeof IC.getItems === 'function' && typeof IC.craft === 'function') {
          resolve(IC);
          return;
        }
        if (Date.now() >= deadline) {
          reject(new Error('Game API (window.IC) unavailable after ' + timeoutMs + ' ms.'));
          return;
        }
        setTimeout(tick, 250);
      };
      tick();
    });
  }

  function syncElementsFromGame(IC) {
    let added = 0;
    const items = IC.getItems();
    for (const item of items) {
      const name = item && item.text;
      if (!name || typeof name !== 'string') continue;
      if (!hasOwn(kb.elements, name)) {
        kb.elements[name] = {
          emoji: item.emoji || '',
          id: item.id,
          firstSeen: Date.now()
        };
        added++;
      }
    }
    if (added > 0) {
      kb.discoveries += added;
      saveKB();
      updateCounters();
    }
    return added;
  }

  function hasElement(name) {
    const needle = String(name).trim().toLowerCase();
    return Object.keys(kb.elements).some(key => key.toLowerCase() === needle);
  }

  // A canonical target keeps prompt text and goal checks stable while craft
  // arguments still come verbatim from discovered keys.
  function canonicalizeTarget(raw) {
    const trimmed = String(raw).trim().replace(/\s+/g, ' ');
    const exact = Object.keys(kb.elements).find(key => key.toLowerCase() === trimmed.toLowerCase());
    if (exact) return exact;
    return trimmed.split(' ').map((word, i) => {
      const lower = word.toLowerCase();
      if (i > 0 && TARGET_STOP_WORDS.has(lower)) return lower;
      return lower.charAt(0).toUpperCase() + lower.slice(1);
    }).join(' ');
  }

  function firstNeedIndex(ladder) {
    for (let i = 0; i < ladder.length; i++) {
      if (!hasElement(ladder[i])) return i;
    }
    return Math.max(0, ladder.length - 1);
  }

  function clearPlan(reason) {
    const ladderBefore = kb.plan.ladder.slice();
    const activeBefore = kb.plan.activeIndex;
    if (ladderBefore.length > 0) {
      recordEvent('plan_clear', {
        reason: reason || null,
        ladderBefore: ladderBefore,
        ladderAfter: [],
        activeIndexBefore: activeBefore,
        activeIndexAfter: 0,
        craftBudget: kb.plan.craftsSinceReplan,
        craftsSinceClosenessGain: state.craftsSinceClosenessGain
      });
    }
    kb.plan.ladder = [];
    kb.plan.activeIndex = 0;
    kb.plan.mode = 'fresh';
    kb.plan.craftsSinceReplan = 0;
    kb.plan.updatedAt = Date.now();
    // Sticky recipes are observed game facts, so they survive a re-plan.
    saveKB();
    if (reason) log('Re-planning (' + reason + '): ladder discarded.', 'error');
  }

  function planNeedsReplan(target) {
    const plan = kb.plan;
    if (plan.ladder.length !== CONFIG.ladderLength) return 'no valid ladder';
    if (plan.ladder[plan.ladder.length - 1].toLowerCase() !== target.toLowerCase()) return 'ladder target mismatch';
    if (plan.craftsSinceReplan >= CONFIG.maxCraftsPerPlan) return 'craft budget for plan exhausted';
    if (hasElement(plan.ladder[plan.activeIndex]) && firstNeedIndex(plan.ladder) !== plan.activeIndex) {
      return 'active step already discovered';
    }
    if (state.consecutiveNoNew >= CONFIG.consecutiveNoNewLimit) return 'no-progress watchdog';
    return null;
  }

  function applyPlanFeedback(target) {
    const plan = kb.plan;
    const ladderBefore = plan.ladder.slice();
    const activeBefore = plan.activeIndex;
    plan.craftsSinceReplan++;
    plan.updatedAt = Date.now();
    const last = state.lastCraft;
    if (!last || !last.result) {
      state.craftsSinceClosenessGain++;
      saveKB();
      return;
    }
    const ctx = buildClosenessContext(target);
    const resultScore = scoreCloseness(last.result, target, ctx);
    const active = plan.ladder[plan.activeIndex] || '';
    const activeScore = active ? scoreCloseness(active, target, ctx) : 0;

    if (resultScore >= CONFIG.minViableCloseness && resultScore > state.bestCloseness + 0.001) {
      state.bestCloseness = resultScore;
      state.craftsSinceClosenessGain = 0;
    } else {
      state.craftsSinceClosenessGain++;
    }

    // Advance on observed closeness, never on a model prediction or name equality.
    if (resultScore > activeScore + 0.001 || last.result.toLowerCase() === target.toLowerCase()) {
      const next = firstNeedIndex(plan.ladder);
      plan.activeIndex = next;
      recordEvent('plan_advance', {
        activeIndex: next,
        activeIndexBefore: activeBefore,
        activeIndexAfter: next,
        step: plan.ladder[next] || '',
        ladderBefore: ladderBefore,
        ladderAfter: plan.ladder.slice(),
        reason: 'observed result ' + last.result + ' has closer score ' + resultScore.toFixed(3) +
          ' than active step score ' + activeScore.toFixed(3),
        observedResult: last.result,
        closenessScore: resultScore,
        craft: state.lastCraft
      });
      log('Closeness gain: ' + last.result + ' (' + resultScore.toFixed(2) +
        '); plan advanced to step ' + next + '.', 'success');
    }

    // Sticky self-reproduction is an observed fact worth remembering, not a guess.
    if (last.result === last.a || last.result === last.b) {
      if (!plan.refutedRecipes.some(r => r.a === last.a && r.b === last.b)) {
        plan.refutedRecipes.push({ a: last.a, b: last.b, result: last.result });
        plan.refutedRecipes = plan.refutedRecipes.slice(-12);
      }
      recordEvent('refutation', {
        a: last.a, b: last.b, actual: last.result, sticky: true,
        reason: 'sticky recipe: observed result equals one of its parents',
        refutedRecipe: { a: last.a, b: last.b, result: last.result },
        plan: {
          ladderBefore: ladderBefore,
          ladderAfter: plan.ladder.slice(),
          activeIndexBefore: activeBefore,
          activeIndexAfter: plan.activeIndex,
          refutedRecipes: plan.refutedRecipes.slice()
        },
        craft: state.lastCraft
      });
      log('Plan: sticky result ' + last.result + ' observed for ' + last.a + ' + ' + last.b, 'info');
    }
    saveKB();
  }

  // ---------------------------------------------------------------------------
  // Learning. Deterministic, transparent heuristics — retrieval-style notes,
  // not trained weights. Notes are rebuilt from the full history each time so
  // they never drift from the underlying recipe/failure records.
  // ---------------------------------------------------------------------------

  function updatePatterns() {
    const notes = [];

    // Counting all crafts rewarded elements that only ever yielded known results,
    // which anchored the model on them. Only unproductive volume is surfaced now.
    const wastedCounts = Object.create(null);
    const productive = new Set();
    for (const r of kb.recipes) {
      if (!r || typeof r.a !== 'string' || typeof r.b !== 'string') continue;
      if (r.isNew === true) {
        productive.add(r.a);
        if (r.b !== r.a) productive.add(r.b);
        continue;
      }
      wastedCounts[r.a] = (wastedCounts[r.a] || 0) + 1;
      if (r.b !== r.a) wastedCounts[r.b] = (wastedCounts[r.b] || 0) + 1;
    }
    Object.keys(wastedCounts)
      .sort((x, y) => wastedCounts[y] - wastedCounts[x])
      .slice(0, 3)
      .forEach(name => {
        const n = wastedCounts[name];
        if (n >= 4 && !productive.has(name)) {
          notes.push(name + ': ' + n + ' crafts, none produced a new element');
        }
      });

    const failureCounts = Object.create(null);
    for (const f of kb.failures) {
      if (!f || typeof f.a !== 'string' || typeof f.b !== 'string') continue;
      const key = [f.a.toLowerCase(), f.b.toLowerCase()].sort().join(' + ');
      if (!failureCounts[key]) failureCounts[key] = { a: f.a, b: f.b, n: 0 };
      failureCounts[key].n++;
    }
    Object.keys(failureCounts)
      .map(key => failureCounts[key])
      .filter(v => v.n >= 2)
      .sort((x, y) => y.n - x.n)
      .slice(0, 3)
      .forEach(v => notes.push('Never combine ' + v.a + ' with ' + v.b + ' again (failed ' + v.n + ' times)'));

    const themes = [
      { base: 'Fire', words: ['fire', 'flame', 'heat', 'energy', 'steam', 'lava'] },
      { base: 'Water', words: ['water', 'steam', 'rain', 'ice', 'ocean', 'river'] },
      { base: 'Wind', words: ['wind', 'air', 'cloud', 'storm', 'sky', 'dust'] },
      { base: 'Earth', words: ['earth', 'stone', 'rock', 'soil', 'plant', 'life'] }
    ];
    for (const theme of themes) {
      const base = theme.base.toLowerCase();
      const results = kb.recipes
        .filter(r => r && (String(r.a).toLowerCase() === base || String(r.b).toLowerCase() === base))
        .map(r => String(r.result || '').toLowerCase());
      const hits = results.filter(word => theme.words.some(w => word.includes(w)));
      if (hits.length >= 2) {
        notes.push(theme.base + ' + X produced ' + hits.length + ' ' + theme.base.toLowerCase() + '-themed results');
      }
    }

    // Exhausted pairs are the strongest signal the planner can get: they turn a
    // repeated craft into an explicit ban in the next prompt.
    const tried = Object.keys(kb.triedPairs)
      .map(key => kb.triedPairs[key])
      .filter(p => p && typeof p.a === 'string' && typeof p.b === 'string')
      .sort((x, y) => (Number(y.misses) || 0) - (Number(x.misses) || 0) ||
        (Number(y.at) || 0) - (Number(x.at) || 0));
    tried.slice(0, 3).forEach(p => {
      if (Number(p.misses) > 1) {
        notes.push(p.a + ' + ' + p.b + ' → no result (' + p.misses + ' misses; exhausted)');
      } else if (p.result) {
        notes.push(p.a + ' + ' + p.b + ' → ' + p.result + ' (already crafted; do not repeat)');
      }
    });

    for (const pair of kb.plan.refutedRecipes) {
      notes.push(pair.a + ' + ' + pair.b + ' → ' + pair.result + ' (sticky recipe; do not repeat)');
    }
    for (const seed of kb.refutedSeeds.slice(-3)) {
      notes.push(seed.a + ' + ' + seed.b + ' → ' + seed.actual +
        ' (seed expected ' + seed.expected + '; refuted)');
    }

    // Retained notes keep their original position; new ones append. The cap then
    // drops the oldest entries, so the prompt always shows the freshest 12.
    const retained = kb.patterns.filter(n => notes.includes(n));
    const added = notes.filter(n => !kb.patterns.includes(n));
    kb.patterns = retained.concat(added).slice(-12);
  }

  // ---------------------------------------------------------------------------
  // Prompt + AI gateway. GM_xmlhttpRequest bypasses browser CORS; page fetch()
  // would be blocked from a neal.fun origin. @connect grants the host.
  // ---------------------------------------------------------------------------

  function bigrams(text) {
    const compact = String(text).toLowerCase().replace(/[^a-z0-9]+/g, '').trim();
    const out = new Set();
    for (let i = 0; i < compact.length - 1; i++) out.add(compact.slice(i, i + 2));
    return out;
  }

  function buildClosenessContext(target) {
    const key = String(target).toLowerCase().trim();
    const weights = TARGET_THEME_LEXICON[key] || null;
    const tokens = weights
      ? Object.keys(weights).map(t => ({ token: t, weight: weights[t] }))
      : key.split(/[^a-z0-9]+/).filter(Boolean).map(t => ({ token: t, weight: 1 }));
    const names = Object.keys(kb.elements);
    const seen = names.map(n => Number(kb.elements[n] && kb.elements[n].firstSeen) || 0);
    const degree = Object.create(null);
    for (const pairKeyName of Object.keys(kb.triedPairs)) {
      const p = kb.triedPairs[pairKeyName];
      if (!p) continue;
      degree[p.a] = (degree[p.a] || 0) + 1;
      degree[p.b] = (degree[p.b] || 0) + 1;
    }
    const memoryIndex = Object.create(null);
    const parentIndex = Object.create(null);
    for (const keyName of Object.keys(kb.recipeMemory)) {
      const e = kb.recipeMemory[keyName];
      if (!e || !e.result) continue;
      const low = e.result.toLowerCase();
      memoryIndex[low] = (memoryIndex[low] || 0) + 1;
      if (e.source === 'seed') continue;
      for (const parent of [e.a, e.b]) {
        if (!parentIndex[parent]) parentIndex[parent] = [];
        parentIndex[parent].push(e);
      }
    }
    return {
      target: key,
      tokens: tokens,
      minSeen: seen.length ? Math.min.apply(null, seen) : 0,
      maxSeen: seen.length ? Math.max.apply(null, seen) : 1,
      degree: degree,
      memoryIndex: memoryIndex,
      parentIndex: parentIndex,
      targetBigrams: bigrams(key),
      topK: CONFIG.topKElements
    };
  }

  function scoreCloseness(name, target, ctx, depth) {
    const level = depth || 0;
    if (!name || !ctx) return 0;
    const low = String(name).toLowerCase();
    let score = 0;
    for (const t of ctx.tokens) {
      if (low.indexOf(t.token) !== -1) score += 0.35 * t.weight;
    }
    const grams = bigrams(low);
    let shared = 0;
    for (const gram of grams) if (ctx.targetBigrams.has(gram)) shared++;
    if (grams.size) score += 0.25 * (shared / grams.size);
    if (ctx.degree[name]) score += Math.min(0.1, ctx.degree[name] * 0.02);
    const seen = Number(kb.elements[name] && kb.elements[name].firstSeen) || 0;
    if (ctx.maxSeen > ctx.minSeen) {
      score += 0.1 * ((seen - ctx.minSeen) / (ctx.maxSeen - ctx.minSeen));
    }
    if (ctx.memoryIndex[low]) score += Math.min(0.15, ctx.memoryIndex[low] * 0.05);
    // Memory recursion uses observed results only; depth is capped to bound cost.
    if (level < 2) {
      for (const e of (ctx.parentIndex[name] || [])) {
        score += 0.1 * scoreCloseness(e.result, target, ctx, level + 1);
      }
    }
    return Math.max(0, Math.min(1, score));
  }

  function rankElements(target, ctx) {
    const context = ctx || buildClosenessContext(target);
    return Object.keys(kb.elements)
      .map(name => ({ name: name, score: scoreCloseness(name, target, context) }))
      .sort((x, y) => y.score - x.score);
  }

  function selectPairs(target, count) {
    // A trapped element can score high on theme terms but still be unusable; never
    // anchor a required pair on a dead cluster.
    const excluded = deadClusterElements(target);
    const ranked = rankElements(target).filter(r => !excluded.has(r.name));
    const closest = ranked.length ? ranked[0].name : '';
    const pairs = [];
    const seen = new Set();
    const addPair = (a, b) => {
      if (!a || !b || isPairTried(a, b)) return;
      const key = pairKey(a, b);
      if (seen.has(key)) return;
      seen.add(key);
      pairs.push({ a: a, b: b });
    };
    // The closest element always participates; it is the reachable anchor.
    for (const entry of ranked) {
      if (entry.name === closest) continue;
      addPair(closest, entry.name);
      if (pairs.length >= count) break;
    }
    const top = ranked.slice(0, Math.max(CONFIG.topKElements, 2)).map(r => r.name);
    for (let i = 0; i < top.length; i++) {
      for (let j = i; j < top.length; j++) {
        addPair(top[i], top[j]);
        if (pairs.length >= count) break;
      }
      if (pairs.length >= count) break;
    }
    return { closestElement: closest, pairs: pairs.slice(0, count), ranked: ranked };
  }

  function pickElementNames(target, limit, ladder = [], activeName = '') {
    const entries = Object.keys(kb.elements).map(name => ({ name, meta: kb.elements[name] }));
    const maxSeen = entries.reduce((m, e) => Math.max(m, Number(e.meta && e.meta.firstSeen) || 0), 1);
    const lowerTarget = String(target).toLowerCase();
    const tokens = lowerTarget.split(/[^a-z0-9]+/).filter(t => t.length > 1);
    const ladderNames = ladder.map(step => String(step).toLowerCase());
    const activeLower = String(activeName).toLowerCase();

    return entries.map(e => {
      const low = e.name.toLowerCase();
      let score = 0;
      // Plan-aware ordering uses computed closeness, not static concept tables.
      if (activeLower && low === activeLower) score += 500;
      else if (ladderNames.indexOf(low) !== -1) score += 200;
      if (low === lowerTarget || low.includes(lowerTarget) || lowerTarget.includes(low)) score += 100;
      for (const token of tokens) if (low.includes(token)) score += 30;
      if (BASE_ELEMENTS.includes(e.name)) score += 20;
      score += 10 * ((Number(e.meta && e.meta.firstSeen) || 0) / maxSeen);
      return { name: e.name, score };
    })
      .sort((x, y) => y.score - x.score)
      .slice(0, limit)
      .map(x => x.name);
  }

  function uniqueLines(items, format) {
    const seen = new Set();
    const lines = [];
    for (const item of items) {
      const line = format(item);
      if (!seen.has(line)) {
        seen.add(line);
        lines.push(line);
      }
    }
    return lines;
  }

  function unexploredHint() {
    const crafts = Object.create(null);
    const productive = Object.create(null);
    for (const r of kb.recipes) {
      if (!r || typeof r.a !== 'string' || typeof r.b !== 'string') continue;
      for (const name of [r.a, r.b === r.a ? null : r.b]) {
        if (!name) continue;
        crafts[name] = (crafts[name] || 0) + 1;
        if (r.isNew === true) productive[name] = (productive[name] || 0) + 1;
      }
    }
    const wasted = Object.keys(crafts)
      .filter(name => (crafts[name] || 0) >= 4 && !(productive[name] > 0))
      .sort((x, y) => crafts[y] - crafts[x])
      .slice(0, 3)
      .map(name => name + ' (' + crafts[name] + ' crafts, 0 new results)');
    const coldBases = BASE_ELEMENTS.filter(name => !(productive[name] > 0));
    const hints = [];
    if (wasted.length) hints.push('deprioritize — many crafts, no new results: ' + wasted.join(', '));
    if (coldBases.length) hints.push('base elements with no new-result craft: ' + coldBases.join(', '));
    return hints.length ? hints.join('; ') + '.' : 'No clear signal; try untried structured pairs.';
  }

  // Tested flat-ladder contract. The deeper DAG variant produced malformed JSON;
  // a five-name ladder with a single active step was reliably compliant.
  const LADDER_SYSTEM = `You are an Infinite Craft backward-chaining planner. Output a flat 5-name ladder of NEW intermediates, then propose pool pairs for its first name only.

CONTRACT — ONE strict JSON object, no markdown, exactly these keys:
{"strategy":"backward","analysis":"<=200 chars","ladder":["NEW name 1","NEW name 2","NEW name 3","NEW name 4","<TARGET>"],"active_index":0,"proposals":[{"a":"POOL name","b":"POOL name","score":0,"why":"<=120 chars"}],"abstain":false}

RULES:
1. "ladder" is an array of exactly 5 STRINGS in execution order: the first thing you can craft now, then what that enables, ... ending with the exact string "<TARGET>".
2. ladder[0] is the label for a code-preselected REACHABLE PAIR of owned elements shown under REQUIRED PAIRS. It must be a plausible product of that pair, not an invented remote concept. Never use "Time Traveler", "Police Box", or "TARDIS" as rung 0.
3. "active_index" is always 0. ladder[0] must not equal either parent.
4. Every proposal.a and proposal.b MUST be copied verbatim from CANDIDATE PARENT POOL. At least one proposal must use the REQUIRED PAIR verbatim and include the closest element. Names outside the pool are forbidden as arguments. The target is never an argument.
5. proposals: 3 distinct pairs, none in FORBIDDEN. Scores 0-100 strictly decreasing, first >= 60. Product guesses are not part of this contract and are never used.
Self-check before emitting: 5 ladder strings; ladder[0] labels the reachable required pair and is not a banned rung; both arguments in the pool; 3 distinct non-forbidden pairs; scores descending.`;

  function deadClusterElements(target) {
    const excluded = new Set();
    const sticky = Object.create(null);
    const outside = new Set();
    for (const r of kb.recipes) {
      if (!r || typeof r.a !== 'string' || typeof r.b !== 'string') continue;
      if (r.result === r.a || r.result === r.b) {
        // Credit only the repeated product, not both parents.
        sticky[r.result] = (sticky[r.result] || 0) + 1;
      } else if (r.result &&
        String(r.result).toLowerCase() !== String(target).toLowerCase()) {
        outside.add(r.a);
        outside.add(r.b);
      }
    }
    for (const name of Object.keys(sticky)) {
      // Two sticky results and no escape to an outside product = dead cluster.
      // An element that ever produced something else is vetoed from exclusion.
      if (sticky[name] >= 2 && !outside.has(name) && !BASE_ELEMENTS.includes(name)) {
        excluded.add(name);
      }
    }
    return excluded;
  }

  function candidatePool(target, limit) {
    const excluded = deadClusterElements(target);
    const owned = Object.keys(kb.elements);
    const usedAsParent = new Set();
    for (const key of Object.keys(kb.triedPairs)) {
      const pair = kb.triedPairs[key];
      if (!pair) continue;
      usedAsParent.add(pair.a);
      usedAsParent.add(pair.b);
    }
    const byRecency = owned.slice().sort((x, y) =>
      (Number(kb.elements[y] && kb.elements[y].firstSeen) || 0) -
      (Number(kb.elements[x] && kb.elements[x].firstSeen) || 0));
    const newest = byRecency.slice(0, Math.ceil(limit / 2));
    const unused = byRecency.filter(n => !usedAsParent.has(n)).slice(0, Math.ceil(limit / 2));
    const pool = [];
    const seen = new Set();
    const add = (name) => {
      if (typeof name !== 'string' || !name.trim()) return;
      const clean = name.trim();
      const low = clean.toLowerCase();
      if (low === String(target).toLowerCase()) return;
      if (!BASE_ELEMENTS.includes(clean) && excluded.has(clean)) return;
      if (seen.has(low)) return;
      seen.add(low);
      pool.push(clean);
    };
    newest.forEach(add);
    unused.forEach(add);
    for (const name of byRecency) {
      if (pool.length >= limit) break;
      add(name);
    }
    return pool.slice(0, limit);
  }

  function buildPrompt(rawTarget, target) {
    const ladder = kb.plan.ladder;
    const continuation = ladder.length === CONFIG.ladderLength;
    const activeIndex = continuation ? firstNeedIndex(ladder) : 0;
    const activeName = ladder[activeIndex] || '';
    if (continuation) kb.plan.activeIndex = activeIndex;

    let system = LADDER_SYSTEM.replace(/<TARGET>/g, target);
    if (continuation) {
      system += '\n\nCONTINUATION: copy the supplied ladder verbatim and keep rule 5.';
    }

    const ctx = buildClosenessContext(target);
    const selection = selectPairs(target, CONFIG.proposalsPerTurn);
    const pool = candidatePool(target, CONFIG.candidatePoolSize);
    const names = pickElementNames(target, CONFIG.maxElementsInPrompt, ladder, activeName);
    const recipes = uniqueLines(
      kb.recipes.slice(-CONFIG.maxRecipesInPrompt),
      r => '- ' + r.a + ' + ' + r.b + ' = ' + (r.result || '?')
    );
    const failures = uniqueLines(
      kb.failures.slice(-CONFIG.maxFailuresInPrompt),
      f => '- ' + f.a + ' + ' + f.b + ' -> no result'
    );
    const triedPairs = Object.values(kb.triedPairs)
      .filter(p => p && typeof p.a === 'string' && typeof p.b === 'string')
      .sort((x, y) => (Number(y.at) || 0) - (Number(x.at) || 0))
      .slice(0, CONFIG.maxTriedPairsInPrompt)
      .map(p => '- ' + p.a + ' + ' + p.b + ' => ' + (p.result ? p.result : 'no result') +
        (p.isNew === true ? ' [NEW]' : ' [already known]') +
        (Number(p.misses) > 1 ? ' [misses=' + p.misses + ', exhausted]' : ''));
    const goals = kb.goals.length
      ? kb.goals.map(g => '- ' + g.element + ' (confidence ' + Number(g.confidence) + '): ' +
          (Array.isArray(g.recipes) && g.recipes.length
            ? g.recipes.map(r => r.a + ' + ' + r.b).join('; ')
            : '(no parent pairs recorded)'))
      : ['(none yet)'];
    const outcomes = kb.recipes.concat(kb.failures)
      .filter(o => o && typeof o.a === 'string' && typeof o.b === 'string' && Number(o.at))
      .sort((x, y) => Number(y.at) - Number(x.at))
      .slice(0, 10);
    const outcomeLines = uniqueLines(outcomes, o => '- ' + o.a + ' + ' + o.b + ' => ' +
      (hasOwn(o, 'result') ? String(o.result || 'no result') : 'no result') +
      (o.isNew === true ? ' [NEW]' : ' [already known]'));
    const knownRecipes = reachableKnownRecipes(target, 20).map(e =>
      '- ' + e.a + ' + ' + e.b + ' = ' + e.result + ' (' + e.source + '/' + e.verification + ')');
    const newDiscovery = state.lastCraft && state.lastCraft.isNew
      ? state.lastCraft.result : '(none since last plan)';

    const planStatus = continuation
      ? 'PLAN STATUS:\n' + ladder.map((step, i) =>
          (i + 1) + '. [' + (hasElement(step) ? 'HAVE' : 'NEED') + '] ' + step).join('\n') +
        '\nACTIVE STEP: index ' + activeIndex + ' = "' + activeName + '".'
      : 'PLAN STATUS: (no plan — propose a new 5-name ladder)';

    const strategyLine = 'PLANNING: work backward from the target using the reachable required pairs and the closeness ranking.';

    const parts = [
      'TARGET: ' + rawTarget + '\nCANONICAL TARGET: ' + target +
        '\nSUCCESS: the canonical target appears in DISCOVERED ELEMENTS (case-insensitive check, exact names when crafting).',
      planStatus,
      strategyLine,
      'CLOSENESS RANKING (name: score):\n' +
        (selection.ranked.slice(0, CONFIG.maxElementsInPrompt > 30 ? 30 : 10)
          .map(r => '- ' + r.name + ': ' + r.score.toFixed(3)).join('\n') || '(none)'),
      'CLOSIEST ELEMENT: ' + (selection.closestElement || '(none)'),
      'REQUIRED PAIRS (include verbatim in proposals):\n' +
        (selection.pairs.length
          ? selection.pairs.map(p => '- ' + p.a + ' + ' + p.b).join('\n')
          : '(none reachable — propose any untried pool pair)'),
      'NEW DISCOVERY SINCE LAST PLAN: ' + newDiscovery,
      'KNOWN RECIPES (reachable now; exact names):\n' +
        (knownRecipes.length ? knownRecipes.join('\n') : '(none)'),
      'FORBIDDEN / ALREADY ATTEMPTED PAIRS (' + triedPairs.length + ' shown — BANNED, both orderings are the same pair):\n' +
        (triedPairs.length ? triedPairs.join('\n') : '(none yet)'),
      'AVAILABLE ELEMENTS (exact, case-sensitive; ' + names.length + ' shown):\n' +
        (names.length ? names.map(n => '- ' + n).join('\n') : '(none)'),
      'CANDIDATE PARENT POOL (' + pool.length + ' names — proposal arguments MUST come from here):\n' +
        (pool.length ? pool.map(n => '- ' + n).join('\n') : '(none)'),
      'RECENT SUCCESSFUL RECIPES (' + recipes.length + '):\n' +
        (recipes.length ? recipes.join('\n') : '(none yet)'),
      'RECENT FAILURES (' + failures.length + '):\n' +
        (failures.length ? failures.join('\n') : '(none yet)'),
      'LAST 10 CRAFT OUTCOMES (observed):\n' +
        (outcomeLines.length ? outcomeLines.join('\n') : '(none yet)'),
      'LEARNED NOTES:\n' +
        (kb.patterns.length ? kb.patterns.map(n => '- ' + n).join('\n') : '(none yet)'),
      'GOALS / SUBGOALS FROM LAST TURN (legacy notes):\n' + goals.join('\n'),
      'NOT YET EXPLORED: ' + unexploredHint(),
      'REQUEST: return exactly ' + CONFIG.proposalsPerTurn +
        ' DISTINCT proposals, sorted by descending score, including a REQUIRED PAIR and the closest element.'
    ];

    state.planContext = {
      pool: pool,
      ladder: ladder.slice(),
      activeIndex: activeIndex,
      continuation: continuation,
      target: target,
      requiredPairs: selection.pairs.slice(),
      closestElement: selection.closestElement,
      closeness: ctx
    };
    if (state.session) {
      state.session.closenessContext = {
        target: ctx.target,
        tokens: ctx.tokens,
        topK: ctx.topK,
        closestElement: selection.closestElement,
        ranked: selection.ranked.slice(0, 10)
      };
    }

    return [
      { role: 'system', content: system },
      { role: 'user', content: parts.join('\n\n') }
    ];
  }

  function rawGatewayRequest(method, path, body, useAuth, timeoutMs = CONFIG.requestTimeoutMs) {
    return new Promise((resolve, reject) => {
      const headers = { 'Content-Type': 'application/json' };
      if (useAuth && state.key) headers.Authorization = 'Bearer ' + state.key;

      const request = GM_xmlhttpRequest({
        method: method,
        url: CONFIG.apiBase + path,
        headers: headers,
        data: body ? JSON.stringify(body) : undefined,
        timeout: timeoutMs,
        onload: (response) => {
          if (state.activeRequest === request) state.activeRequest = null;
          resolve(response);
        },
        onerror: (error) => {
          if (state.activeRequest === request) state.activeRequest = null;
          reject(new KiloError('Network error: ' + ((error && error.error) || 'unknown'), 0, true));
        },
        ontimeout: () => {
          if (state.activeRequest === request) state.activeRequest = null;
          recordAnomaly({
            level: 'error', status: 0, code: 'TIMEOUT',
            message: 'gateway request timed out', detail: null,
            attempt: 0, backoffMs: 0, endpoint: path, timeoutMs: timeoutMs,
            elapsedMs: sessionElapsedMs(), recovered: false
          });
          reject(new KiloError('Gateway request timed out', 0, true));
        },
        onabort: () => {
          if (state.activeRequest === request) state.activeRequest = null;
          if (state.stopped) reject(new StopSignal('Stopped during a gateway request.'));
          else reject(new KiloError('Gateway request aborted', 0, false));
        }
      });
      state.activeRequest = request;
    });
  }

  function extractGatewayError(text) {
    if (typeof text === 'string' && text.trim()) {
      try {
        const json = JSON.parse(text);
        if (json && json.error) {
          return String(json.error.message || json.error.code || 'unknown error');
        }
      } catch (e) {
        return text.slice(0, 200);
      }
    }
    return 'no error body';
  }

  async function callKilo(messages) {
    const useAuth = !state.freeMode && !!state.key;
    const planningModel = state.planningModel || state.model;
    if (!planningModel || isRouterModel(planningModel)) {
      log('No usable non-router planning model; refusing to send a planning request.', 'error');
      throw new StopSignal('No usable non-router planning model. Stopping.', 'error');
    }
    const body = {
      model: planningModel,
      messages: messages,
      // Free gateway models are reasoning models. At a small budget they spend every
      // token on reasoning and return length/empty content, so turn reasoning off
      // and keep the JSON answer inside the budget where the endpoint allows it.
      max_tokens: CONFIG.primaryMaxTokens,
      reasoning: { enabled: false },
      temperature: CONFIG.temperature,
      stream: false
    };

    if (state.session) state.session.aiRequestCount++;
    recordEvent('ai_request', {
      sequence: state.session ? state.session.aiRequestCount : 0,
      aiAttempt: state.session ? state.session.aiRequestCount : 0,
      purpose: state.requestPurpose || 'plan',
      isFallback: false,
      fallbackReason: null,
      model: body.model,
      messages: body.messages,
      max_tokens: body.max_tokens,
      temperature: body.temperature,
      reasoning: body.reasoning,
      stream: body.stream,
      timeoutMs: CONFIG.requestTimeoutMs,
      elapsedMs: sessionElapsedMs()
    });

    async function postChatCompletion(requestBody, timeoutMs = CONFIG.requestTimeoutMs, maxTransientRetries = 2) {
      let transientRetries = 0;
      for (;;) {
        assertRunning();
        let response;
        try {
          response = await rawGatewayRequest('POST', GATEWAY_PATHS.chat, requestBody, useAuth, timeoutMs);
        } catch (e) {
          if (e instanceof StopSignal) throw e;
          if (e.retryable && transientRetries < maxTransientRetries) {
            transientRetries++;
            recordAnomaly({
              level: 'error', status: e.status || 0, code: 'TRANSPORT',
              message: 'transport error: ' + e.message,
              detail: e.message, errorName: e.name,
              attempt: transientRetries, backoffMs: 800 * transientRetries,
              endpoint: GATEWAY_PATHS.chat, timeoutMs: timeoutMs,
              elapsedMs: sessionElapsedMs(), recovered: false
            });
            log('Gateway transport error (' + e.message + '); retrying.', 'error');
            await jitterSleep(800 * transientRetries);
            continue;
          }
          throw e;
        }

        if (response.status !== 200) {
          const detail = extractGatewayError(response.responseText);
          if (response.status === 429) {
            state.rateLimitHits++;
            if (state.rateLimitHits > 4) {
              throw new StopSignal('Repeated HTTP 429 rate limits. Stopping.', 'error');
            }
            const wait = Math.min(1000 * Math.pow(2, state.rateLimitHits - 1), 20000);
            recordAnomaly({
              level: 'error', status: 429, code: 'RATE_LIMIT',
              message: 'HTTP 429, backing off ' + wait + ' ms', detail: detail,
              attempt: state.rateLimitHits, backoffMs: wait,
              endpoint: GATEWAY_PATHS.chat, timeoutMs: timeoutMs,
              elapsedMs: sessionElapsedMs(), recovered: false
            });
            log('HTTP 429 (' + detail + '). Backing off ' + wait + ' ms [' + state.rateLimitHits + '/5].', 'error');
            await jitterSleep(wait, false);
            continue;
          }
          if (response.status === 401 || response.status === 402 || response.status === 403) {
            throw new StopSignal('Gateway auth/quota error ' + response.status + ': ' + detail, 'error');
          }
          if (response.status >= 500 && transientRetries < maxTransientRetries) {
            transientRetries++;
            recordAnomaly({
              level: 'error', status: response.status, code: 'SERVER_ERROR',
              message: 'gateway error, retrying', detail: detail,
              attempt: transientRetries, backoffMs: 1000 * transientRetries,
              endpoint: GATEWAY_PATHS.chat, timeoutMs: timeoutMs,
              elapsedMs: sessionElapsedMs(), recovered: false
            });
            log('Gateway ' + response.status + ' (' + detail + '); retrying.', 'error');
            await jitterSleep(1000 * transientRetries);
            continue;
          }
          return { status: response.status, detail: detail };
        }

        state.rateLimitHits = 0;
        if (state.lastAnomalyRecord) {
          state.lastAnomalyRecord.recovered = true;
          state.lastAnomalyRecord = null;
        }
        let json;
        try {
          json = JSON.parse(response.responseText);
        } catch (e) {
          throw new KiloError('Malformed JSON from gateway: ' + e.message, 200, false);
        }
        const choice = json && json.choices && json.choices[0];
        state.lastAiResponse = {
          status: 200,
          content: choice && choice.message && choice.message.content,
          finishReason: choice && choice.finish_reason,
          usage: json.usage || null,
          model: json.model || requestBody.model,
          isFallback: requestBody.max_tokens === CONFIG.fallbackMaxTokens,
          responseId: json.id || null
        };
        return state.lastAiResponse;
      }
    }

    const hasContent = (result) => typeof result.content === 'string' && result.content.trim().length > 0;
    const first = await postChatCompletion(body);
    if (first.status === 200 && hasContent(first)) return first.content;

    // Two endpoint classes need one reasoning-free retry: those that reject the
    // flag, and those that ignore it and still truncate on reasoning tokens.
    const mandatoryReasoning = first.status === 400 && /reasoning is mandatory/i.test(first.detail || '');
    const truncated = first.status === 200 && first.finishReason === 'length' && !hasContent(first);
    if (!mandatoryReasoning && !truncated) {
      if (first.status === 200) throw new KiloError('Gateway returned an empty assistant message', 200, false);
      throw new KiloError('HTTP ' + first.status + ': ' + first.detail, first.status, false);
    }

    const fallbackReason = mandatoryReasoning ? 'reasoning is mandatory' : 'reasoning budget truncated content';
    recordEvent('fallback', {
      reason: fallbackReason,
      model: planningModel,
      maxTokens: CONFIG.fallbackMaxTokens,
      timeoutMs: CONFIG.fallbackTimeoutMs,
      firstStatus: first.status,
      firstFinishReason: first.finishReason || null,
      elapsedMs: sessionElapsedMs()
    });
    log('Reasoning blocked the answer; retrying ' + planningModel + ' at ' + CONFIG.fallbackMaxTokens +
      ' tokens with a ' + CONFIG.fallbackTimeoutMs + ' ms timeout.', 'info');
    const fallbackBody = {
      model: planningModel,
      messages: messages,
      max_tokens: CONFIG.fallbackMaxTokens,
      temperature: CONFIG.temperature,
      stream: false
    };
    // This endpoint rejects any reasoning-disabling field, even low effort.
    if (!/^liquid\/lfm-3-1\.7b:free$/i.test(planningModel)) {
      fallbackBody.reasoning = { effort: 'low' };
    }
    if (state.session) state.session.aiRequestCount++;
    recordEvent('ai_request', {
      sequence: state.session ? state.session.aiRequestCount : 0,
      aiAttempt: state.session ? state.session.aiRequestCount : 0,
      purpose: state.requestPurpose || 'plan',
      isFallback: true,
      fallbackReason: fallbackReason,
      model: fallbackBody.model,
      messages: fallbackBody.messages,
      max_tokens: fallbackBody.max_tokens,
      temperature: fallbackBody.temperature,
      reasoning: fallbackBody.reasoning || null,
      stream: fallbackBody.stream,
      timeoutMs: CONFIG.fallbackTimeoutMs,
      elapsedMs: sessionElapsedMs()
    });
    const second = await postChatCompletion(fallbackBody, CONFIG.fallbackTimeoutMs, 1);
    if (second.status !== 200) {
      throw new KiloError('HTTP ' + second.status + ': ' + second.detail, second.status, false);
    }
    if (!hasContent(second) || second.finishReason === 'length') {
      throw new KiloError('Model ' + state.model + ' returned no content (finish_reason: length) even at ' + CONFIG.fallbackMaxTokens + ' tokens', 200, false);
    }
    return second.content;
  }

  function proposalRejection(reason, obj, details, tried, invalid) {
    return {
      rejected: true,
      reason: reason,
      rawObject: obj || null,
      droppedDetails: details || [],
      droppedTried: tried || 0,
      droppedInvalid: invalid || 0,
      rawCount: obj && Array.isArray(obj.proposals) ? obj.proposals.length : 0
    };
  }

  function parseProposals(content) {
    if (typeof content !== 'string') return null;
    const text = content.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '').trim();

    let obj;
    try {
      obj = JSON.parse(text);
    } catch (e) {
      return proposalRejection('malformed_json', null, [], 0, 0);
    }
    if (!obj || typeof obj !== 'object' || !Array.isArray(obj.proposals)) {
      return proposalRejection('missing_proposals_array', obj, [], 0, 0);
    }
    const context = state.planContext;
    if (!context) return proposalRejection('missing_plan_context', obj, [], 0, 0);

    const ladder = Array.isArray(obj.ladder) && obj.ladder.length === CONFIG.ladderLength &&
      obj.ladder.every(step => typeof step === 'string' && step.trim().length > 0)
      ? obj.ladder.map(step => step.trim()) : null;
    if (!ladder || ladder[ladder.length - 1].toLowerCase() !== context.target.toLowerCase()) {
      return proposalRejection('invalid_ladder_or_target', obj, [], 0, 0);
    }
    if (context.continuation && ladder.some((step, i) =>
      step.toLowerCase() !== context.ladder[i].toLowerCase())) {
      return proposalRejection('ladder_changed_on_continuation', obj, [], 0, 0);
    }
    const activeIndex = context.continuation ? context.activeIndex : 0;
    const activeStep = ladder[activeIndex];

    const raw = String(obj.strategy || '').toLowerCase();
    const strategy = ['backward', 'repair', 'explore'].indexOf(raw) !== -1 ? raw : 'backward';
    const analysis = typeof obj.analysis === 'string' ? obj.analysis.slice(0, 300) : '';
    const seen = new Set();
    const valid = [];
    let droppedTried = 0;
    let droppedInvalid = 0;
    const droppedDetails = [];

    for (const p of obj.proposals) {
      if (!p || typeof p !== 'object') {
        droppedInvalid++;
        droppedDetails.push({ reason: 'malformed_entry', proposal: p });
        continue;
      }
      const a = typeof p.a === 'string' ? p.a.trim() : '';
      const b = typeof p.b === 'string' ? p.b.trim() : '';
      if (!a || !b || !hasOwn(kb.elements, a) || !hasOwn(kb.elements, b)) {
        droppedInvalid++;
        droppedDetails.push({ reason: 'invalid_element_name', proposal: p });
        continue;
      }
      if (!context.pool.some(n => n.toLowerCase() === a.toLowerCase()) ||
          !context.pool.some(n => n.toLowerCase() === b.toLowerCase())) {
        droppedInvalid++;
        droppedDetails.push({ reason: 'outside_candidate_pool', proposal: p });
        continue;
      }
      const key = pairKey(a, b);
      if (isPairTried(a, b) || seen.has(key)) {
        droppedTried++;
        droppedDetails.push({ reason: isPairTried(a, b) ? 'already_tried' : 'duplicate_in_batch', proposal: p });
        continue;
      }
      if (typeof p.score !== 'number' || !Number.isFinite(p.score)) {
        droppedInvalid++;
        droppedDetails.push({ reason: 'invalid_score', proposal: p });
        continue;
      }
      const score = p.score;
      seen.add(key);
      valid.push({
        a: a,
        b: b,
        score: Math.max(0, Math.min(100, Math.round(score))),
        why: typeof p.why === 'string' ? p.why.slice(0, 200) : ''
      });
    }

    // Reachability gate: the model must follow code-selected pairs. Inject one if
    // it did not, rather than rejecting a whole batch over a fiction prediction.
    const required = (context.requiredPairs || []).find(p => !isPairTried(p.a, p.b));
    if (required && hasOwn(kb.elements, required.a) && hasOwn(kb.elements, required.b) &&
      context.pool.some(n => n.toLowerCase() === required.a.toLowerCase()) &&
      context.pool.some(n => n.toLowerCase() === required.b.toLowerCase())) {
      const rk = pairKey(required.a, required.b);
      const present = valid.some(p => pairKey(p.a, p.b) === rk);
      if (!present) {
        valid.push({
          a: required.a, b: required.b, score: 100,
          why: 'code-selected reachable pair', injected: true
        });
      } else {
        for (const p of valid) {
          if (pairKey(p.a, p.b) === rk) p.score = 100;
        }
      }
    }
    for (const p of valid) {
      const mem = kb.recipeMemory[pairKey(p.a, p.b)];
      if (mem && mem.source === 'seed' && !mem.disabled) p.score = 100;
    }
    if (valid.length < 2) {
      return proposalRejection('too_few_valid_proposals', obj, droppedDetails, droppedTried, droppedInvalid);
    }

    const subgoals = [];
    if (Array.isArray(obj.subgoals)) {
      for (const g of obj.subgoals) {
        if (!g || typeof g !== 'object') continue;
        // Backward chaining must be able to name undiscovered intermediates;
        // only the parent pairs are constrained to already-discovered names.
        const element = typeof g.element === 'string' ? g.element.trim().slice(0, 80) : '';
        if (!element) continue;
        const recipes = [];
        if (Array.isArray(g.recipes)) {
          for (const r of g.recipes) {
            if (!r || typeof r !== 'object') continue;
            const ra = typeof r.a === 'string' ? r.a.trim() : '';
            const rb = typeof r.b === 'string' ? r.b.trim() : '';
            if (!ra || !rb || !hasOwn(kb.elements, ra) || !hasOwn(kb.elements, rb)) continue;
            recipes.push({ a: ra, b: rb });
          }
        }
        subgoals.push({
          element: element,
          recipes: recipes.slice(0, 6),
          confidence: Math.max(0, Math.min(100, Math.round(Number(g.confidence) || 0)))
        });
        if (subgoals.length >= CONFIG.maxGoalsStored) break;
      }
    }

    valid.strategy = strategy;
    valid.analysis = analysis;
    valid.ladder = ladder;
    valid.activeIndex = activeIndex;
    valid.subgoals = subgoals;
    valid.abstain = obj.abstain === true;
    valid.rawCount = obj.proposals.length;
    valid.droppedTried = droppedTried;
    valid.droppedInvalid = droppedInvalid;
    valid.droppedDetails = droppedDetails;
    return valid;
  }

  async function askForProposals(messages) {
    for (let attempt = 1; attempt <= 2; attempt++) {
      assertRunning();
      state.requestPurpose = attempt === 2
        ? 'repair'
        : (state.planContext && state.planContext.continuation ? 'continuation' : 'plan');
      let content = '';
      try {
        content = await callKilo(messages);
      } catch (e) {
        if (e instanceof StopSignal) throw e;
        throw new StopSignal('AI request failed: ' + e.message, 'error');
      }

      const batch = parseProposals(content);
      const mostlyTried = !!batch && batch.rawCount > 0 &&
        (batch.droppedTried / batch.rawCount) > CONFIG.repairDropRatio;
      if (batch && !batch.rejected && !mostlyTried && !batch.abstain) {
        const previousLadder = kb.plan.ladder.slice();
        const previousActive = kb.plan.activeIndex;
        kb.goals = batch.subgoals;
        kb.plan.ladder = batch.ladder.slice();
        kb.plan.activeIndex = batch.activeIndex;
        kb.plan.mode = state.planContext.continuation ? 'continuation' : 'fresh';
        // Budget resets only when a genuinely fresh plan is committed, not on
        // every accepted continuation reply.
        if (!state.planContext.continuation) kb.plan.craftsSinceReplan = 0;
        kb.plan.updatedAt = Date.now();
        // A committed fresh plan is the watchdog grace period; the dry spell resets.
        if (!state.planContext.continuation) state.consecutiveNoNew = 0;
        saveKB();
        const responseMeta = state.lastAiResponse || {};
        recordEvent('ai_response', {
          accepted: true,
          rawContent: content,
          finishReason: responseMeta.finishReason || null,
          usage: responseMeta.usage || null,
          responseModel: responseMeta.model || state.model,
          responseId: responseMeta.responseId || null,
          isFallback: !!responseMeta.isFallback,
          elapsedMs: sessionElapsedMs(),
          strategy: batch.strategy,
          analysis: batch.analysis,
          abstain: batch.abstain,
          subgoals: batch.subgoals,
          ladder: batch.ladder.slice(),
          activeIndex: batch.activeIndex,
          received: batch.rawCount,
          used: batch.length,
          droppedTried: batch.droppedTried,
          droppedInvalid: batch.droppedInvalid,
          droppedDetails: batch.droppedDetails || [],
          proposals: batch.map(p => ({
            a: p.a, b: p.b, score: p.score, why: p.why, injected: !!p.injected
          }))
        });
        recordEvent('plan_created', {
          ladderBefore: previousLadder,
          ladderAfter: batch.ladder.slice(),
          activeIndexBefore: previousActive,
          activeIndexAfter: batch.activeIndex,
          mode: kb.plan.mode,
          reason: previousLadder.length ? 'continuation of accepted ladder' : 'new ladder from AI response',
          requiredPairs: state.planContext.requiredPairs || []
        });
        log('AI strategy: ' + batch.strategy + (batch.analysis ? ' — ' + batch.analysis : ''), 'info');
        log('Plan ladder: ' + batch.ladder.join(' → '), 'info');
        log('Active step ' + batch.activeIndex + ': ' + batch.ladder[batch.activeIndex], 'info');
        log('Batch: ' + batch.rawCount + ' received, ' + batch.length + ' usable (' +
          batch.droppedTried + ' tried, ' + batch.droppedInvalid + ' invalid).', 'info');
        return batch;
      }

      const failedMeta = state.lastAiResponse || {};
      const rejection = batch && batch.rejected ? batch : null;
      recordEvent('ai_response', {
        accepted: false,
        rawContent: content,
        finishReason: failedMeta.finishReason || null,
        usage: failedMeta.usage || null,
        responseModel: failedMeta.model || state.model,
        isFallback: !!failedMeta.isFallback,
        elapsedMs: sessionElapsedMs(),
        strategy: batch && !batch.rejected ? batch.strategy : null,
        analysis: batch && !batch.rejected ? batch.analysis : null,
        received: batch ? batch.rawCount : 0,
        droppedTried: batch ? batch.droppedTried : 0,
        droppedInvalid: batch ? batch.droppedInvalid : 0,
        droppedDetails: batch ? batch.droppedDetails : [],
        ladder: batch && !batch.rejected ? batch.ladder : null,
        subgoals: batch && !batch.rejected ? batch.subgoals : null,
        rejectionReason: rejection ? rejection.reason : null,
        rawResponseObject: rejection ? rejection.rawObject : null,
        failureReason: rejection
          ? rejection.reason
          : (batch ? (batch.abstain ? 'abstained' : 'mostly tried pairs') : 'malformed JSON or schema rejection')
      });
      if (attempt === 2) {
        const reason = batch
          ? (batch.rejected
              ? 'AI response rejected (' + batch.reason + '); stopping.'
              : (batch.abstain
                  ? 'AI abstained twice; stopping.'
                  : 'Frontier exhausted: ' + batch.droppedTried + '/' + batch.rawCount +
                    ' proposals were already tried after repair. Stopping.'))
          : 'AI kept returning malformed batches. Stopping.';
        clearPlan(reason);
        throw new StopSignal(reason, 'error');
      }
      recordEvent('repair', {
        attempt: attempt,
        reason: batch
          ? 'mostly tried (' + batch.droppedTried + '/' + batch.rawCount + ') or abstained'
          : 'invalid batch'
      });
      log(batch
        ? 'Repair: ' + batch.droppedTried + '/' + batch.rawCount + ' proposals were already tried; re-prompting with the forbidden list.'
        : 'Invalid batch (1/2); re-prompting with a stricter no-repeat instruction.', 'error');
      const forbidden = Object.values(kb.triedPairs)
        .filter(p => p && typeof p.a === 'string' && typeof p.b === 'string')
        .sort((x, y) => (Number(y.at) || 0) - (Number(x.at) || 0))
        .slice(0, 40)
        .map(p => p.a + ' + ' + p.b)
        .join('; ');
      const stricter = '\n\nHARD RULE (repair): use strategy "repair", do not abstain, and never propose any of these already-attempted pairs: ' +
        (forbidden || '(none)') + '. Every pair must use two exact names from CANDIDATE PARENT POOL, produce the ACTIVE STEP exactly, and be distinct within the batch.';
      messages = messages.slice();
      messages[messages.length - 1] = {
        role: 'user',
        content: messages[messages.length - 1].content + stricter
      };
      await jitterSleep(400);
    }
  }

  // Catalog fields are inconsistently typed across providers, so every free
  // signal is checked with its possible representations before trusting it.
  function isFreeModel(model) {
    if (!model || typeof model.id !== 'string' || !model.id) return false;
    if (model.isFree === true || model.isFree === 'true') return true;
    if (/:free$/i.test(model.id)) return true;
    const price = model.pricing && model.pricing.prompt;
    return price != null && price !== '' && Number(price) === 0;
  }

  function isSlowModel(id) {
    return CONFIG.slowModelIds.some(slow => id === slow || id.indexOf(slow + ':') === 0);
  }

  function isRouterModel(id) {
    return /^reflection\//i.test(id) || /router/i.test(id) || /beam/i.test(id);
  }

  function resolvePlanningModel(ids) {
    for (const id of CONFIG.planningModelIds) {
      if (ids.indexOf(id) !== -1 && !isSlowModel(id) && !isRouterModel(id)) return id;
    }
    const usable = ids.filter(id => !isRouterModel(id) && !isSlowModel(id));
    if (usable.length) return usable[0];
    // Fail closed: never hand planning to a router, even in a router-only catalog.
    return ids.find(id => !isRouterModel(id)) || '';
  }

  function getFreeModelIds(catalog) {
    const models = (catalog && Array.isArray(catalog.data)) ? catalog.data : [];
    const ids = [];
    for (const model of models) {
      if (!isFreeModel(model) || isSlowModel(model.id) || ids.indexOf(model.id) !== -1) continue;
      ids.push(model.id);
    }
    return ids;
  }

  async function fetchCatalog() {
    const response = await rawGatewayRequest('GET', GATEWAY_PATHS.models, null, false);
    if (response.status !== 200) throw new Error('HTTP ' + response.status);
    const catalog = JSON.parse(response.responseText);
    if (!catalog || !Array.isArray(catalog.data)) throw new Error('unexpected catalog format');
    return catalog;
  }

  async function fetchFreeModelIds() {
    return getFreeModelIds(await fetchCatalog());
  }

  function applyModelOptions(ids, fromCatalog) {
    const saved = state.modelOverride;
    const options = ids.slice();
    if (saved && options.indexOf(saved) === -1) options.push(saved);

    // A pre-catalog fallback selection must not survive into the live list, or the
    // dropdown would disagree with the fast model resolveModel() actually picks.
    let current = els.model.value;
    if (current && !fromCatalog && options.indexOf(current) === -1) current = '';
    if (fromCatalog && CONFIG.fallbackFreeModels.indexOf(current) !== -1 && current !== saved) current = '';
    if (!current) current = saved ||
      (fromCatalog && options.indexOf(CONFIG.preferredFastModel) !== -1 ? CONFIG.preferredFastModel : '');
    if (options.indexOf(current) === -1) current = options[0] || '';
    while (els.model.firstChild) els.model.removeChild(els.model.firstChild);
    for (const id of options) {
      // A saved ID may have rotated out of the catalog; keep it visible but labelled.
      const stale = id === saved && ids.indexOf(id) === -1;
      els.model.appendChild(el('option', {
        value: id,
        text: stale
          ? id + ' (saved, not in catalog)'
          : (isRouterModel(id) ? id + ' (router — planning falls back)' : id)
      }));
    }
    if (current) els.model.value = current;
    state.freeModelIds = fromCatalog ? ids.slice() : [];
  }

  async function refreshModelOptions() {
    log('Refreshing free model list…', 'info');
    try {
      const ids = await fetchFreeModelIds();
      if (!ids.length) throw new Error('catalog returned no free models');
      applyModelOptions(ids, true);
      log('Free model list loaded: ' + ids.length + ' option(s).', 'info');
    } catch (e) {
      if (e instanceof StopSignal) throw e;
      applyModelOptions(CONFIG.fallbackFreeModels.slice(), false);
      log('Free model list unavailable (' + e.message + '). Showing unverified fallback IDs.', 'error');
    }
  }

  async function resolveModel() {
    let catalog = null;
    let usedFallback = false;
    let ids = state.freeModelIds;
    if (!ids || !ids.length) {
      try {
        catalog = await fetchCatalog();
        ids = getFreeModelIds(catalog);
        state.freeModelIds = ids.slice();
      } catch (e) {
        if (e instanceof StopSignal) throw e;
        ids = [];
      }
    }
    if (!ids.length) {
      ids = CONFIG.fallbackFreeModels.slice();
      usedFallback = true;
      log('Free model catalog unavailable. Using unverified fallback ' + ids[0] + '.', 'error');
    }

    const saved = state.modelOverride;
    let path = 'auto';
    let reason = 'first free catalog id';
    let chosen = null;
    if (saved && isSlowModel(saved)) {
      log('Warning: saved model ' + saved + ' is known-slow and may time out; keeping your choice.', 'error');
    }
    if (saved && (ids.indexOf(saved) !== -1 || isSlowModel(saved))) {
      chosen = saved;
      path = 'saved_override';
      reason = isSlowModel(saved) ? 'saved override honored despite known-slow warning' : 'saved override present in free list';
      log('Using saved model override ' + saved + '. Clear it to use preferred fast model ' +
        CONFIG.preferredFastModel + '.', 'info');
    } else if (state.autoModel && ids.indexOf(state.autoModel) !== -1) {
      chosen = state.autoModel;
      path = 'auto';
      reason = 'previous auto-selected model still in free list';
    } else {
      chosen = ids.indexOf(CONFIG.preferredFastModel) !== -1 ? CONFIG.preferredFastModel : ids[0];
      path = ids.indexOf(CONFIG.preferredFastModel) !== -1 ? 'preferred_fast' : (usedFallback ? 'fallback' : 'auto');
      reason = path === 'preferred_fast'
        ? 'measured-fast preferred model present'
        : (usedFallback ? 'catalog unavailable, unverified fallback' : 'first free catalog id');
      if (saved) log('Saved model ' + saved + ' is no longer free/current; using ' + chosen + '.', 'error');
    }
    state.autoModel = chosen;
    state.model = chosen;
    const routerDemoted = !CONFIG.honorRouterOverrideForPlanning && isRouterModel(chosen);
    const planningReason = routerDemoted
      ? 'saved router model demoted for planning'
      : (CONFIG.honorRouterOverrideForPlanning ? 'router override honored for planning' : 'non-router model plans directly');
    state.planningModel = routerDemoted ? resolvePlanningModel(ids) : chosen;
    if (routerDemoted) {
      log('Saved router model ' + chosen + ' is weak for planning; planning calls will use ' +
        state.planningModel + '. Clear it with Preferred.', 'error');
    }
    recordEvent('model_selected', {
      model: chosen,
      planningModel: state.planningModel,
      routerDemoted: routerDemoted,
      planningReason: planningReason,
      path: path,
      reason: reason,
      savedOverride: saved || '',
      candidates: ids.slice(),
      preferred: CONFIG.preferredFastModel,
      usedFallback: usedFallback,
      knownSlow: isSlowModel(chosen),
      elapsedMs: sessionElapsedMs()
    });
    log('Using free model: ' + chosen, 'info');
    return chosen;
  }

  function resetModelToPreferred() {
    GM_setValue(STORAGE.model, '');
    state.modelOverride = '';
    state.autoModel = null;
    state.planningModel = '';
    log('Saved model override cleared; refreshing the free model list.', 'info');
    void refreshModelOptions();
  }

  // ---------------------------------------------------------------------------
  // Crafting. IC.craft is called on the IC object itself to preserve `this`.
  // ---------------------------------------------------------------------------

  async function craftPair(a, b, IC, proposal) {
    const startedMs = Date.now();
    // A tried pair must never reach IC.craft, even if every other filter misses it.
    if (isPairTried(a, b)) {
      log('Skipped already-tried pair: ' + a + ' + ' + b, 'info');
      return false;
    }
    const beforeItems = IC.getItems();
    const beforeNames = new Set(beforeItems.map(item => item && item.text));
    const beforeLen = beforeItems.length;
    // Only hand-written seeds carry `expected`; model guesses never do.
    const expected = proposal && typeof proposal.expected === 'string' ? proposal.expected.trim() : '';
    const executor = state.dragMode ? 'drag' : 'instant';
    let result;
    if (state.dragMode) {
      const dragOutcome = await craftViaDrag(a, b, IC);
      if (dragOutcome && dragOutcome.infraFailure) {
        recordEvent('craft', {
          a: a, b: b, executor: executor, infraFailure: true, reason: dragOutcome.reason,
          expected: expected || null,
          proposal: proposal || null,
          elementCountBefore: beforeLen,
          elementCountAfter: IC.getItems().length,
          elapsedMs: Date.now() - startedMs,
          dragDiagnostics: state.lastDragDiagnostics
        });
        log('Drag mode: ' + dragOutcome.reason + ' — aborting without recording the pair.', 'error');
        throw new StopSignal('Drag mode infrastructure failure. Stopping.', 'error');
      }
      result = typeof dragOutcome === 'string' && dragOutcome ? { text: dragOutcome } : null;
    } else {
      try {
        result = await IC.craft(a, b);
      } catch (e) {
        log('IC.craft threw: ' + e.message, 'error');
        throw new StopSignal('IC.craft failed: ' + e.message, 'error');
      }
    }

    if (result == null) {
      recordFailure(a, b);
      markPairTried(a, b, null, false);
      noteRecipeMemory(a, b, null, {
        expected: expected,
        source: expected ? 'seed' : 'observed',
        target: state.session ? state.session.canonical : ''
      });
      updatePatterns();
      saveKB();
      recordEvent('craft', {
        a: a, b: b, executor: executor, result: null, isNew: false,
        expected: expected || null, infraFailure: false,
        proposal: proposal || null,
        elementCountBefore: beforeLen,
        elementCountAfter: IC.getItems().length,
        elapsedMs: Date.now() - startedMs,
        dragDiagnostics: state.lastDragDiagnostics
      });
      log(a + ' + ' + b + ' → no result', 'fail');
      state.lastCraft = { a: a, b: b, result: null, isNew: false };
      assertRunning();
      return false;
    }

    const name = (result && result.text) ? result.text : String(result);
    syncElementsFromGame(IC);
    const isNew = IC.getItems().length > beforeLen && !beforeNames.has(name);
    recordRecipe(a, b, name, isNew);
    markPairTried(a, b, name, isNew);
    noteRecipeMemory(a, b, name, {
      expected: expected,
      isNew: isNew,
      source: expected ? 'seed' : 'observed',
      target: state.session ? state.session.canonical : ''
    });
    updatePatterns();
    saveKB();
    recordEvent('craft', {
      a: a, b: b, executor: executor, result: name, isNew: isNew,
      expected: expected || null,
      seedMatched: expected ? expected.toLowerCase() === name.toLowerCase() : null,
      infraFailure: false,
      fullResult: result,
      resultText: name,
      resultEmoji: result && result.emoji ? result.emoji : '',
      resultId: result && result.id !== undefined ? result.id : null,
      proposal: proposal || null,
      elementCountBefore: beforeLen,
      elementCountAfter: IC.getItems().length,
      elapsedMs: Date.now() - startedMs,
      dragDiagnostics: state.lastDragDiagnostics
    });
    log(a + ' + ' + b + ' → ' + name + (isNew ? '  ✓ NEW' : ''), isNew ? 'success' : 'info');
    state.lastCraft = { a: a, b: b, result: name, isNew: isNew };
    // The craft already executed; record it before honoring a Stop pressed mid-craft.
    assertRunning();
    return isNew;
  }

  // ---------------------------------------------------------------------------
  // Animated drag mode drives the game's own pointer-event drag so the craft is
  // visible. It is layout-dependent and slower than IC.craft by design; an
  // infrastructure failure is signalled separately so it cannot poison the KB.
  // ---------------------------------------------------------------------------

  function tick(ms = 20) {
    return new Promise((resolve, reject) => {
      setTimeout(() => {
        if (state.stopped) reject(new StopSignal('Stopped during drag mode.'));
        else resolve();
      }, Math.max(0, ms));
    });
  }

  async function nextFrame() {
    await new Promise(resolve => {
      if (page && typeof page.requestAnimationFrame === 'function') {
        page.requestAnimationFrame(resolve);
      } else {
        setTimeout(resolve, 16);
      }
    });
    assertRunning();
  }

  function findBookEntry(name) {
    const sidebar = document.querySelector('div#sidebar');
    if (!sidebar) return null;
    const entries = sidebar.querySelectorAll('div.item[data-item-text]');
    for (const entry of entries) {
      if (entry.getAttribute('data-item-text') === name) return entry;
    }
    return null;
  }

  async function revealBookEntry(name) {
    const sidebar = document.querySelector('div#sidebar');
    const search = sidebar && sidebar.querySelector('input.sidebar-input[placeholder="Search items..."]');
    if (search) {
      search.value = name;
      search.dispatchEvent(new Event('input', { bubbles: true }));
      await tick();
    }
    return findBookEntry(name);
  }

  // The game listens on the page's real window; events sent to the sandbox window
  // never reach those listeners. MouseEvent.view must be the page window too.
  function firePointer(target, type, x, y, prevX, prevY) {
    const view = page || window;
    const init = {
      bubbles: true,
      cancelable: true,
      button: 0,
      buttons: type === 'mousedown' ? 1 : (type === 'mouseup' ? 0 : 1),
      clientX: x,
      clientY: y,
      movementX: Number.isFinite(prevX) ? Math.round(x - prevX) : 0,
      movementY: Number.isFinite(prevY) ? Math.round(y - prevY) : 0,
      view: view
    };
    target.dispatchEvent(new MouseEvent(type, init));
  }

  // Touch APIs are unavailable on some desktop builds; probe by constructing a
  // Touch so a missing constructor falls back to the proven mouse path.
  function touchDragAvailable() {
    const TouchCtor = (page && page.Touch) || (typeof Touch !== 'undefined' ? Touch : null);
    const EventCtor = (page && page.TouchEvent) || (typeof TouchEvent !== 'undefined' ? TouchEvent : null);
    if (!TouchCtor || !EventCtor) return false;
    try {
      const probe = new TouchCtor({ identifier: 1, target: document.body, clientX: 0, clientY: 0 });
      new EventCtor('touchstart', {
        touches: [probe], targetTouches: [probe], changedTouches: [probe],
        bubbles: true, cancelable: true, view: page || window
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  function chooseDragInput() {
    const view = page || window;
    const coarse = view.matchMedia && view.matchMedia('(pointer: coarse)').matches;
    const touches = view.navigator && view.navigator.maxTouchPoints > 0;
    if (!coarse && !touches) return false;
    if (!touchDragAvailable()) {
      log('Touch drag is unavailable here; using the mouse drag path.', 'error');
      return false;
    }
    return true;
  }

  function fireTouch(target, phase, x, y, sourceEl) {
    const view = page || window;
    const TouchCtor = (page && page.Touch) || Touch;
    const EventCtor = (page && page.TouchEvent) || TouchEvent;
    const touch = new TouchCtor({
      identifier: 1,
      // Touch.target is the gesture origin, not the node receiving the event.
      target: sourceEl || target,
      clientX: x,
      clientY: y,
      pageX: x + (view.pageXOffset || 0),
      pageY: y + (view.pageYOffset || 0),
      screenX: x,
      screenY: y
    });
    const active = phase === 'end' ? [] : [touch];
    const type = phase === 'start' ? 'touchstart' : phase === 'move' ? 'touchmove' : 'touchend';
    target.dispatchEvent(new EventCtor(type, {
      touches: active,
      targetTouches: active,
      changedTouches: [touch],
      bubbles: true,
      cancelable: true,
      view: view
    }));
  }

  function fireDrag(target, phase, x, y, prevX, prevY, sourceEl) {
    if (state.dragTouch) {
      fireTouch(target, phase, x, y, sourceEl);
      return;
    }
    const type = phase === 'start' ? 'mousedown' : phase === 'move' ? 'mousemove' : 'mouseup';
    firePointer(target, type, x, y, prevX, prevY);
  }

  function currentInstanceIds() {
    return new Set(Array.from(document.querySelectorAll('div.instance[data-instance]'))
      .map(node => node.getAttribute('data-instance')));
  }

  function findTileById(id) {
    if (!id) return null;
    const nodes = document.querySelectorAll('div.instance[data-instance]');
    for (const node of nodes) {
      if (node.getAttribute('data-instance') === id) return node;
    }
    return null;
  }

  function removeSpawnedTiles(ids) {
    for (const id of ids) {
      const node = findTileById(id);
      if (node && node.parentNode) node.parentNode.removeChild(node);
    }
  }

  function dragSpawnPositions() {
    const board = document.querySelector('div[data-container]') ||
      document.querySelector('#instances') || document.body;
    const r = board.getBoundingClientRect();
    const baseX = Math.round(Math.max(16, r.left + 24));
    const baseY = Math.round(Math.max(16, r.top + 24));
    const gap = Math.round(Math.max(120, Math.min(320, r.width * 0.35)));
    return [{ x: baseX, y: baseY }, { x: baseX + gap, y: baseY }];
  }

  async function spawnTileFromBook(name, pos, createdIds) {
    const sidebar = document.querySelector('div#sidebar');
    const search = sidebar && sidebar.querySelector('input.sidebar-input[placeholder="Search items..."]');
    const priorSearch = search ? search.value : '';
    const entry = await revealBookEntry(name);
    assertRunning();
    if (!entry) return { ok: false, reason: 'could not find book entry for ' + name };

    const before = currentInstanceIds();
    // A paired release is required: a bare start leaves the book dragging.
    fireDrag(entry, 'start', pos.x, pos.y, undefined, undefined, entry);
    fireDrag(entry, 'end', pos.x, pos.y, undefined, undefined, entry);
    await tick(60);
    const fresh = Array.from(document.querySelectorAll('div.instance[data-instance]'))
      .filter(node => !before.has(node.getAttribute('data-instance')));
    // Register ids before any throwable assertion so Stop cleanup can find them.
    for (const spawned of fresh) createdIds.push(spawned.getAttribute('data-instance'));
    assertRunning();
    if (!fresh.length) return { ok: false, reason: 'no tile spawned for ' + name };

    const node = fresh[fresh.length - 1];
    if (search && priorSearch !== name) {
      search.value = priorSearch;
      search.dispatchEvent(new Event('input', { bubbles: true }));
      await tick();
      assertRunning();
    }
    return { ok: true, id: node.getAttribute('data-instance'), node: node };
  }

  function overlayAtPoint(x, y, heldNode) {
    const stack = typeof document.elementsFromPoint === 'function'
      ? document.elementsFromPoint(x, y)
      : [document.elementFromPoint(x, y)];
    for (const node of stack) {
      if (!node || node === heldNode || heldNode.contains(node)) continue;
      const cls = (typeof node.className === 'string') ? node.className : '';
      if (/turnstile|cf-challenge|ad-overlay|advert|adsbox|ad-container/i.test(cls + ' ' + (node.id || ''))) {
        return true;
      }
    }
    return false;
  }

  function waitForDragResult(excludedIds, timeoutMs, IC) {
    let cleanup = () => {};
    let resolveEarly = () => {};
    const promise = new Promise((resolve, reject) => {
      resolveEarly = resolve;
      const targets = ['#instances', '#instances-top']
        .map(sel => document.querySelector(sel)).filter(Boolean);
      if (!targets.length) {
        reject(new Error('no instance containers found'));
        return;
      }
      let observer;
      let timer;
      const dispose = () => {
        clearInterval(timer);
        if (observer) observer.disconnect();
      };
      cleanup = dispose;
      const finish = (fn, value) => { dispose(); fn(value); };
      const check = () => {
        const known = new Set(IC.getItems().map(item => item && item.text));
        const nodes = document.querySelectorAll('div.instance[data-instance]');
        for (const node of nodes) {
          const id = node.getAttribute('data-instance');
          if (!id || excludedIds.has(id)) continue;
          const label = node.querySelector('span.instance-text');
          if (!label || !label.textContent.trim()) continue;
          const text = label.textContent.trim();
          if (/^nothing$/i.test(text)) {
            finish(resolve, NO_RESULT);
            return true;
          }
          if (!known.has(text)) continue;
          finish(resolve, text);
          return true;
        }
        return false;
      };
      observer = new MutationObserver(() => { check(); });
      targets.forEach(t => observer.observe(t, {
        childList: true, subtree: true, characterData: true
      }));
      const deadline = Date.now() + timeoutMs;
      timer = setInterval(() => {
        if (state.stopped) {
          finish(reject, new StopSignal('Stopped while waiting for a drag result.'));
          return;
        }
        if (Date.now() >= deadline) {
          finish(reject, new Error('timed out waiting for a result tile'));
          return;
        }
        check();
      }, 100);
      check();
    });
    return { promise: promise, cancel: () => { cleanup(); resolveEarly(CANCELLED); } };
  }

  async function craftViaDrag(a, b, IC) {
    assertRunning();
    state.dragTouch = chooseDragInput();
    log('Drag mode: attempting ' + a + ' + ' + b + ' via ' +
      (state.dragTouch ? 'touch' : 'mouse') + ' events.', 'info');
    const createdIds = [];
    const positions = dragSpawnPositions();
    let watcher = null;
    let heldButton = false;
    let px = 0;
    let py = 0;
    let completed = false;
    let tileA = null;
    state.lastDragDiagnostics = {
      input: state.dragTouch ? 'touch' : 'mouse',
      spawnPositions: positions,
      createdIds: createdIds.slice(),
      steps: 12,
      stepDelayMs: Math.round(CONFIG.dragDurationMs / 12),
      spawnOk: false,
      hoverMatched: false,
      observerOutcome: null,
      cleanup: []
    };
    try {
      const first = await spawnTileFromBook(a, positions[0], createdIds);
      assertRunning();
      if (!first.ok) return { infraFailure: true, reason: first.reason };
      const second = await spawnTileFromBook(b, positions[1], createdIds);
      assertRunning();
      if (!second.ok) return { infraFailure: true, reason: second.reason };
      state.lastDragDiagnostics.spawnOk = true;

      // Tiles are selected by the ids just created, never by a stale name match.
      tileA = findTileById(first.id);
      const tileB = findTileById(second.id);
      if (!tileA || !tileB) {
        return { infraFailure: true, reason: 'could not locate both spawned tiles' };
      }
      const rectA = tileA.getBoundingClientRect();
      const rectB = tileB.getBoundingClientRect();
      const ax = rectA.left + rectA.width / 2;
      const ay = rectA.top + rectA.height / 2;
      const bx = rectB.left + rectB.width / 2;
      const by = rectB.top + rectB.height / 2;

      if (overlayAtPoint(bx, by, tileB)) {
        return { infraFailure: true, reason: 'an overlay covers the drop point' };
      }

      const excluded = currentInstanceIds();
      watcher = waitForDragResult(excluded, 20000, IC);
      watcher.promise.catch(() => {});

      fireDrag(tileA, 'start', ax, ay, undefined, undefined, tileA);
      heldButton = true;
      px = ax;
      py = ay;
      assertRunning();

      const steps = 12;
      const stepDelay = Math.max(0, Math.round(CONFIG.dragDurationMs / steps));
      for (let step = 1; step <= steps; step++) {
        assertRunning();
        const t = step / steps;
        const nx = step >= steps - 1 ? bx : ax + (bx - ax) * t;
        const ny = step >= steps - 1 ? by : ay + (by - ay) * t;
        fireDrag(page || window, 'move', nx, ny, px, py, tileA);
        px = nx;
        py = ny;
        await tick(stepDelay);
      }
      await nextFrame();
      assertRunning();

      const hovered = (tileB.matches && tileB.matches('.instance-hover')) ||
        tileB.querySelector('.instance-hover');
      if (!hovered) {
        return { infraFailure: true, reason: 'the intended tile never registered .instance-hover' };
      }
      state.lastDragDiagnostics.hoverMatched = true;
      fireDrag(page || window, 'end', bx, by, px, py, tileA);
      heldButton = false;

      let outcome;
      try {
        outcome = await watcher.promise;
      } catch (e) {
        if (e instanceof StopSignal) throw e;
        return { infraFailure: true, reason: e.message };
      }
      assertRunning();
      if (outcome === CANCELLED) {
        state.lastDragDiagnostics.observerOutcome = 'cancelled';
        return { infraFailure: true, reason: 'result observer was cancelled' };
      }
      if (outcome === NO_RESULT) {
        state.lastDragDiagnostics.observerOutcome = 'no_result';
        completed = true;
        return null;
      }
      if (typeof outcome !== 'string' || !outcome) {
        state.lastDragDiagnostics.observerOutcome = 'unexpected';
        return { infraFailure: true, reason: 'unexpected result' };
      }
      state.lastDragDiagnostics.observerOutcome = 'result:' + outcome;
      completed = true;
      log('Drag mode produced ' + outcome, 'info');
      return outcome;
    } catch (e) {
      if (e instanceof StopSignal) throw e;
      return { infraFailure: true, reason: e.message };
    } finally {
      // Cleanup must never mask the result (especially a StopSignal) leaving try.
      try {
        if (heldButton) {
          fireDrag(page || window, 'end', px, py, px, py, tileA);
          heldButton = false;
          state.lastDragDiagnostics.cleanup.push('released held pointer');
        }
        if (watcher) {
          watcher.cancel();
          state.lastDragDiagnostics.cleanup.push('cancelled result watcher');
        }
        // Only tiles created by this drag are removed; the player's tiles stay put.
        if (!completed) {
          removeSpawnedTiles(createdIds);
          state.lastDragDiagnostics.cleanup.push('removed ' + createdIds.length + ' spawned tiles');
        }
      } catch (e) {
        state.lastDragDiagnostics.cleanup.push('cleanup error: ' + e.message);
        log('Drag mode cleanup error: ' + e.message, 'error');
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Main control loop.
  // ---------------------------------------------------------------------------

  async function run(rawTarget) {
    if (state.running) return;
    const target = canonicalizeTarget(rawTarget);
    state.session = newSession(rawTarget, target);
    recordEvent('run_start', {
      target: rawTarget, canonical: target,
      mode: state.dragMode ? 'drag' : 'instant'
    });
    state.running = true;
    state.stopped = false;
    state.rateLimitHits = 0;
    state.consecutiveNoNew = 0;
    state.craftsSinceClosenessGain = 0;
    state.bestCloseness = 0;
    state.planningModel = '';
    setRunningUI(true);
    setStatus('Running…');
    log('Start → target: ' + rawTarget + ' (canonical: ' + target + ') | Mode: ' +
      (state.dragMode ? 'drag (animated)' : 'instant'), 'info');

    let attemptsThisRun = 0;
    let watchdogLogged = false;
    try {
      const IC = await waitForIC(15000);
      state.IC = IC;
      syncElementsFromGame(IC);
      state.session.kbAtStart = kbSnapshot();
      await resolveModel();
      state.session.model = state.model;
      assertRunning();

      planLoop:
      while (attemptsThisRun < CONFIG.maxAttemptsPerRun) {
        assertRunning();

        if (hasElement(target)) {
          completeRun('target already discovered', attemptsThisRun);
          log('SUCCESS: "' + target + '" is discovered (attempt ' + attemptsThisRun + ').', 'success');
          setStatus('Target found');
          break;
        }

        // A reachable seed step is deterministic progress and must always run before
        // any stall stop; the watchdog applies only to the LLM-driven path.
        const seedStep = findReachableSeedStep(target);
        if (!seedStep && state.craftsSinceClosenessGain >= CONFIG.closenessStallLimit) {
          state.session.status = 'stopped';
          recordEvent('run_stop', errorRecord({
            name: 'ClosenessStall',
            message: 'no closer element found after ' + state.craftsSinceClosenessGain + ' crafts'
          }, { reason: 'no closer element found' }));
          log('No closer element found after ' + state.craftsSinceClosenessGain + ' crafts; stopping.', 'error');
          setStatus('No closer element found');
          break;
        }

        const replanReason = planNeedsReplan(target);
        if (replanReason) clearPlan(replanReason);

        if (seedStep) {
          log('Seed step: ' + seedStep.a + ' + ' + seedStep.b + ' → ' + seedStep.result, 'info');
          const seeded = await craftPair(seedStep.a, seedStep.b, IC, {
            a: seedStep.a, b: seedStep.b,
            expected: seedStep.result, score: 100, why: 'seed'
          });
          kb.attempts++;
          attemptsThisRun++;
          recordEvent('attempt', { index: attemptsThisRun, source: 'seed' });
          state.consecutiveNoNew = seeded ? 0 : state.consecutiveNoNew + 1;
          applyPlanFeedback(target);
          // Seed crafts are exempt from the closeness watchdog; they advance the
          // chain deterministically even when no single result scores closer.
          if (seeded) state.craftsSinceClosenessGain = 0;
          saveKB();
          updateCounters();
          setStatus('Attempt ' + attemptsThisRun + ' / ' + CONFIG.maxAttemptsPerRun);
          if (hasElement(target)) {
            completeRun('target crafted from seed', attemptsThisRun);
            log('SUCCESS: "' + target + '" crafted from seed (attempt ' + attemptsThisRun + ').', 'success');
            setStatus('Target found');
            break;
          }
          if (seeded) {
            clearPlan('new element ' + state.lastCraft.result + ' discovered — re-plan from it');
          }
          continue;
        }

        if (state.consecutiveNoNew >= CONFIG.consecutiveNoNewLimit && !watchdogLogged) {
          log('Watchdog: ' + state.consecutiveNoNew + ' crafts without a new element; forcing explore on the next plan.', 'error');
          watchdogLogged = true;
        } else if (state.consecutiveNoNew < CONFIG.consecutiveNoNewLimit) {
          watchdogLogged = false;
        }

        const messages = buildPrompt(rawTarget, target);
        const batch = await askForProposals(messages);
        const queue = batch.slice().sort((x, y) => y.score - x.score);
        let skipped = 0;
        let executed = 0;
        log('Queue: ' + batch.length + ' usable proposals ordered by score.', 'info');

        for (const proposal of queue) {
          assertRunning();
          if (attemptsThisRun >= CONFIG.maxAttemptsPerRun) break;
          if (hasElement(target)) {
            completeRun('target crafted', attemptsThisRun);
            log('SUCCESS: "' + target + '" crafted (attempt ' + attemptsThisRun + ').', 'success');
            setStatus('Target found');
            break planLoop;
          }
          if (isPairTried(proposal.a, proposal.b)) {
            skipped++;
            continue;
          }
          const isNew = await craftPair(proposal.a, proposal.b, IC, proposal);
          kb.attempts++;
          attemptsThisRun++;
          recordEvent('attempt', { index: attemptsThisRun });
          executed++;
          state.consecutiveNoNew = isNew ? 0 : state.consecutiveNoNew + 1;
          applyPlanFeedback(target);
          saveKB();
          updateCounters();
          setStatus('Attempt ' + attemptsThisRun + ' / ' + CONFIG.maxAttemptsPerRun);

          if (hasElement(target)) {
            completeRun('target crafted', attemptsThisRun);
            log('SUCCESS: "' + target + '" crafted (attempt ' + attemptsThisRun + ').', 'success');
            setStatus('Target found');
            break planLoop;
          }
          if (isNew) {
            clearPlan('new element ' + state.lastCraft.result + ' discovered — re-plan from it');
            log('New element discovered; stopping queue to re-plan from it.', 'info');
            break;
          }
          await jitterSleep(CONFIG.attemptDelayMs);
        }
        log('Queue finished: ' + executed + ' crafted, ' + skipped + ' skipped as tried.', 'info');
      }

      if (!state.stopped && attemptsThisRun >= CONFIG.maxAttemptsPerRun && !hasElement(target)) {
        state.session.status = 'stopped';
        recordEvent('run_stop', errorRecord(
          { name: 'SafetyStop', message: 'safety stop after ' + CONFIG.maxAttemptsPerRun + ' attempts' },
          { reason: 'safety stop' }
        ));
        log('Safety stop: reached ' + CONFIG.maxAttemptsPerRun + ' attempts this run.', 'error');
        setStatus('Safety stop');
      }
    } catch (e) {
      if (e instanceof StopSignal) {
        state.session.status = 'stopped';
        recordEvent('run_stop', errorRecord(e, { reason: e.message }));
        log(e.message, e.level);
      } else {
        state.session.status = 'error';
        recordEvent('run_error', errorRecord(e, { reason: e && e.message ? e.message : String(e) }));
        log('Run error: ' + (e && e.message ? e.message : String(e)), 'error');
        setStatus('Error');
      }
    } finally {
      state.session.endedAt = new Date().toISOString();
      state.session.kbAtEnd = kbSnapshot();
      recordEvent('run_end', {
        status: state.session.status,
        elapsedMs: sessionElapsedMs(),
        attempts: kb.attempts
      });
      state.IC = null;
      state.running = false;
      state.stopped = false;
      setRunningUI(false);
      if (els.status && els.status.textContent === 'Running…') setStatus('Idle');
      wakeWaiters();
    }
  }

  // ---------------------------------------------------------------------------
  // Boot.
  // ---------------------------------------------------------------------------

  loadSettings();
  buildPanel();
  void refreshModelOptions();
  GM_registerMenuCommand('Toggle AI Crafter Panel', togglePanel);
  GM_registerMenuCommand('Reset model to preferred fast model', resetModelToPreferred);
  log('Ready. ' + Object.keys(kb.elements).length + ' elements, ' +
    kb.recipes.length + ' recipes, ' + kb.patterns.length + ' notes loaded.', 'info');
})();
