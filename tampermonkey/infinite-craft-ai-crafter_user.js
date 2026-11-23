// ==UserScript==
// @name         Infinite Craft — AI Crafter
// @namespace    https://neal.fun/infinite-craft/
// @version      2.0.0
// @description  Greedy relevance-scoring crafting agent with persistent pair memory.
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
    maxRefutedSteps: 12,
    maxElementsInPrompt: 2000,
    maxRecipesInPrompt: 200,
    maxFailuresInPrompt: 100,
    maxTriedPairsInPrompt: 1000,

    // Higher sampling temperature breaks the deterministic same-pair loop that
    // temperature 0 produces when the prompt barely changes between turns.
    primaryMaxTokens: 4096,
    fallbackMaxTokens: 8192,
    temperature: 0.7,

    // A long stretch without a discovery means the planner has stopped opening
    // new ground; the next prompt is forced into explore mode.
    consecutiveNoNewLimit: 12,
    maxGoalsStored: 8,
    maxHypothesesStored: 20,
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
    maxFailuresStored: 300,

    // v2 greedy planner rules.
    relevanceThreshold: 20,      // elements scoring below this are not used as parents
    scoreBatchSize: 25,          // names per relevance-scoring request
    maxScoreBatchesPerLoop: 4,
    noBestLimit: 8,              // crafts without a new best score before asking for new angles
    maxAngleRefreshes: 6,        // after this many refreshes with no progress, stop and report
    deadAfterCrafts: 8,          // element is "exhausted" after this many crafts with no new result
    loopWindow: 12,              // loop-cluster detection window (crafts)
    coolAttempts: 15,            // crafts an element sits out after a loop cluster
    pauseAtMilestones: true,     // confirm dialog when best relevance crosses a milestone
    milestones: [50, 70, 85],
    maxEventsExported: 3000
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
    activeRequest: null,
    rateLimitHits: 0,
    consecutiveNoNew: 0,
    dragMode: false,
    planContext: null,
    lastCraft: null
  };

  let kb = loadKB();

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
    const existing = data.triedPairs;
    const fresh = (existing && typeof existing === 'object' && !Array.isArray(existing)) ? existing : {};
    if (existing === fresh) return fresh;

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

  function loadPlan(data) {
    const p = (data && typeof data.plan === 'object') ? data.plan : {};
    const ladder = Array.isArray(p.ladder)
      ? p.ladder.filter(step => typeof step === 'string' && step.trim()).slice(0, CONFIG.ladderLength)
      : [];
    return {
      ladder: ladder,
      activeIndex: Number.isInteger(p.activeIndex) ? Math.max(0, Math.min(CONFIG.ladderLength - 1, p.activeIndex)) : 0,
      mode: p.mode === 'continuation' ? 'continuation' : 'fresh',
      refutedSteps: Array.isArray(p.refutedSteps)
        ? p.refutedSteps.filter(s => typeof s === 'string').slice(-CONFIG.maxRefutedSteps) : [],
      refutedRecipes: Array.isArray(p.refutedRecipes)
        ? p.refutedRecipes.filter(r => r && typeof r.a === 'string' && typeof r.b === 'string').slice(-12) : [],
      craftsSinceReplan: Number.isFinite(p.craftsSinceReplan) ? p.craftsSinceReplan : 0,
      updatedAt: Number(p.updatedAt) || 0
    };
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
      elements: (data.elements && typeof data.elements === 'object') ? data.elements : {},
      recipes: Array.isArray(data.recipes) ? data.recipes : [],
      failures: Array.isArray(data.failures) ? data.failures : [],
      triedPairs: loadTriedPairs(data),
      plan: loadPlan(data),
      goals: Array.isArray(data.goals) ? data.goals.slice(-CONFIG.maxGoalsStored) : [],
      hypotheses: Array.isArray(data.hypotheses) ? data.hypotheses.slice(-CONFIG.maxHypothesesStored) : [],
      patterns: Array.isArray(data.patterns)
        ? data.patterns.filter(n => typeof n === 'string')
        : [],
      relevance: (data.relevance && typeof data.relevance === 'object') ? data.relevance : {},
      elementStats: (data.elementStats && typeof data.elementStats === 'object') ? data.elementStats : {},
      attempts: Number(data.attempts) || 0,
      discoveries: Number(data.discoveries) || 0,
      updatedAt: Number(data.updatedAt) || 0
    };
    trimArray(loaded.recipes, CONFIG.maxRecipesStored);
    trimArray(loaded.failures, CONFIG.maxFailuresStored);
    trimArray(loaded.hypotheses, CONFIG.maxHypothesesStored);
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

  // Predicted products are checked against the real result so the next prompt can
  // refute bad guesses instead of letting the model repeat them confidently.
  function recordHypothesis(proposal, actual) {
    const predicted = proposal && typeof proposal.produces === 'string'
      ? proposal.produces.trim().slice(0, 120) : '';
    if (!predicted) return;
    kb.hypotheses.push({
      a: proposal.a,
      b: proposal.b,
      predicted: predicted,
      actual: actual ? String(actual) : null,
      correct: !!actual && predicted.toLowerCase() === String(actual).toLowerCase(),
      at: Date.now()
    });
    trimArray(kb.hypotheses, CONFIG.maxHypothesesStored);
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
    `;
    document.head.appendChild(style);
  }

  function buildPanel() {
    injectCSS();

    els.collapse = el('button', { id: 'icai-collapse', text: '▾', title: 'Collapse / expand' });
    const head = el('div', { id: 'icai-head' }, [
      el('strong', { text: 'AI Crafter · Infinite Craft' }),
      els.collapse
    ]);

    els.target = el('input', { id: 'icai-target', type: 'text', placeholder: 'Target element (e.g. Dragon)' });
    els.start = el('button', { id: 'icai-start', text: 'Start' });
    els.stop = el('button', { id: 'icai-stop', text: 'Stop' });
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
      el('label', { text: 'Delay ms' }), els.delay,
      el('label', { text: 'Free mode' }), els.free
    ]);

    els.drag = el('input', { id: 'icai-drag', type: 'checkbox' });
    els.dragDuration = el('input', { id: 'icai-drag-duration', type: 'number',
      attrs: { min: '200', max: '3000', step: '100' } });
    const dragRow = el('div', { cls: 'icai-row' }, [
      el('label', { text: 'Drag mode (animated)' }), els.drag,
      el('label', { text: 'Duration ms' }), els.dragDuration
    ]);

    els.log = el('div', { id: 'icai-log' });

    const body = el('div', { id: 'icai-body' }, [
      el('label', { text: 'Target element' }),
      els.target,
      el('div', { cls: 'icai-row' }, [els.start, els.stop]),
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

    panel = el('div', { id: 'icai-panel' }, [head, body]);
    (document.body || document.documentElement).appendChild(panel);

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
    els.collapse.addEventListener('click', () => {
      const hidden = body().style.display === 'none';
      body().style.display = hidden ? '' : 'none';
      els.collapse.textContent = hidden ? '▾' : '▸';
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
  }

  function body() {
    return document.getElementById('icai-body');
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
  }

  function setStatus(text) {
    if (els.status) els.status.textContent = text;
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
    kb.plan.ladder = [];
    kb.plan.activeIndex = 0;
    kb.plan.mode = 'fresh';
    kb.plan.refutedSteps = [];
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
    const active = plan.ladder[plan.activeIndex];
    const refuted = plan.refutedSteps.filter(s => s.toLowerCase() === active.toLowerCase()).length;
    if (refuted >= 2) return 'active step refuted twice';
    if (state.consecutiveNoNew >= CONFIG.consecutiveNoNewLimit) return 'no-progress watchdog';
    return null;
  }

  function applyPlanFeedback(target) {
    const plan = kb.plan;
    plan.craftsSinceReplan++;
    plan.updatedAt = Date.now();
    const last = state.lastCraft;
    const active = plan.ladder[plan.activeIndex] || '';
    if (last && last.result && active && last.result.toLowerCase() === active.toLowerCase()) {
      const next = firstNeedIndex(plan.ladder);
      plan.activeIndex = next;
      log('Plan advanced to step ' + next + ': ' + (plan.ladder[next] || '(complete)'), 'success');
      saveKB();
      return;
    }
    if (last && last.result && (last.result === last.a || last.result === last.b)) {
      if (!plan.refutedRecipes.some(r => r.a === last.a && r.b === last.b)) {
        plan.refutedRecipes.push({ a: last.a, b: last.b, result: last.result });
        plan.refutedRecipes = plan.refutedRecipes.slice(-12);
      }
      log('Plan: sticky result ' + last.result + ' refuted for ' + last.a + ' + ' + last.b, 'info');
    } else if (last && last.result && last.isNew === false && active &&
      last.result.toLowerCase() !== active.toLowerCase() &&
      !plan.ladder.some(step => step.toLowerCase() === last.result.toLowerCase())) {
      // Only a known-but-wrong result refutes a step; a NEW element is progress and
      // feeds firstNeedIndex/HAVE state instead of the refutation count.
      if (!plan.refutedSteps.some(s => s.toLowerCase() === active.toLowerCase())) {
        plan.refutedSteps.push(active);
        plan.refutedSteps = plan.refutedSteps.slice(-CONFIG.maxRefutedSteps);
      }
      log('Plan: step "' + active + '" refuted by result ' + last.result, 'error');
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
      // The plan is the strongest relevance signal; no hardcoded recipe tables.
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
{"strategy":"backward","analysis":"<=200 chars","ladder":["NEW name 1","NEW name 2","NEW name 3","NEW name 4","<TARGET>"],"active_index":0,"proposals":[{"a":"POOL name","b":"POOL name","produces":"ladder[0]","score":0,"why":"<=120 chars"}],"abstain":false}

RULES:
1. "ladder" is an array of exactly 5 STRINGS in execution order: the first thing you can craft now, then what that enables, ... ending with the exact string "<TARGET>".
2. The first 4 ladder strings MUST be NEW names NOT present in AVAILABLE ELEMENTS. Check each one. Television, Film, Science, Time, Story, Fiction, Doctor, Robot, Alien, Movie, Book, Word are all ALREADY AVAILABLE — do not use them; make specific compounds such as "Television Show", "Time Traveler", "Police Box".
3. "active_index" is always 0. Every proposal.produces MUST be exactly ladder[0], and ladder[0] must not equal either parent.
4. Every proposal.a and proposal.b MUST be copied verbatim from CANDIDATE PARENT POOL. Names outside the pool are forbidden as arguments. The target is never an argument.
5. proposals: 3 distinct pairs, none in FORBIDDEN. Scores 0-100 strictly decreasing, first >= 60.
Self-check before emitting: 5 ladder strings; first 4 absent from AVAILABLE ELEMENTS; produces == ladder[0]; both arguments in the pool; 3 distinct non-forbidden pairs; scores descending.`;

  function candidatePool(target, limit) {
    const ladder = kb.plan.ladder;
    const blocked = new Set(ladder.filter(step => !hasElement(step)).map(step => step.toLowerCase()));
    const pool = [];
    const add = (name) => {
      if (typeof name !== 'string' || !name.trim()) return;
      const clean = name.trim();
      const low = clean.toLowerCase();
      if (low === String(target).toLowerCase() || blocked.has(low)) return;
      if (pool.some(existing => existing.toLowerCase() === low)) return;
      pool.push(clean);
    };
    pickElementNames(target, limit, ladder, ladder[kb.plan.activeIndex] || '').forEach(add);
    for (const r of kb.recipes) {
      if (!r || r.isNew !== true) continue;
      add(r.a);
      add(r.b);
      if (pool.length >= limit) break;
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
      system += '\n\nCONTINUATION: copy the supplied ladder verbatim; set active_index to ' + activeIndex +
        '; make every proposal.produces exactly ladder[' + activeIndex + '] = "' + activeName +
        '". Keep rule 5.';
    }

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
    const hypothesisLines = kb.hypotheses.slice(-5).map(h =>
      '- ' + h.a + ' + ' + h.b + ': predicted ' + h.predicted + ' → got ' +
      (h.actual ? h.actual : 'no result') + (h.correct ? ' (CORRECT)' : ' (WRONG)'));

    const planStatus = continuation
      ? 'PLAN STATUS:\n' + ladder.map((step, i) =>
          (i + 1) + '. [' + (hasElement(step) ? 'HAVE' : 'NEED') + '] ' + step).join('\n') +
        '\nACTIVE STEP: index ' + activeIndex + ' = "' + activeName +
        '" (first NEED). All proposals must produce exactly "' + activeName + '".'
      : 'PLAN STATUS: (no plan — propose a new 5-name ladder)';

    const strategyLine = state.consecutiveNoNew >= CONFIG.consecutiveNoNewLimit
      ? 'STRATEGY OVERRIDE: explore. The last ' + state.consecutiveNoNew + ' crafts produced no new element.'
      : 'PLANNING: work backward from the target; the ladder names the chain, the active step is the only craftable goal now.';

    const parts = [
      'TARGET: ' + rawTarget + '\nCANONICAL TARGET: ' + target +
        '\nSUCCESS: the canonical target appears in DISCOVERED ELEMENTS (case-insensitive check, exact names when crafting).',
      planStatus,
      strategyLine,
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
      'LAST 10 CRAFT OUTCOMES (observed, not hypotheses):\n' +
        (outcomeLines.length ? outcomeLines.join('\n') : '(none yet)'),
      'HYPOTHESIS CHECK (your past product guesses vs observed results):\n' +
        (hypothesisLines.length ? hypothesisLines.join('\n') : '(none yet)'),
      'LEARNED NOTES:\n' +
        (kb.patterns.length ? kb.patterns.map(n => '- ' + n).join('\n') : '(none yet)'),
      'GOALS / SUBGOALS FROM LAST TURN (legacy notes):\n' + goals.join('\n'),
      'NOT YET EXPLORED: ' + unexploredHint(),
      'REQUEST: return exactly ' + CONFIG.proposalsPerTurn +
        ' DISTINCT proposals, sorted by descending score, each producing the ACTIVE STEP ("' +
        (activeName || target) + '").'
    ];

    state.planContext = {
      pool: pool,
      ladder: ladder.slice(),
      activeIndex: activeIndex,
      continuation: continuation,
      target: target
    };

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
    const body = {
      model: state.model,
      messages: messages,
      // Free gateway models are reasoning models. At a small budget they spend every
      // token on reasoning and return length/empty content, so turn reasoning off
      // and keep the JSON answer inside the budget where the endpoint allows it.
      max_tokens: CONFIG.primaryMaxTokens,
      reasoning: { enabled: false },
      temperature: CONFIG.temperature,
      stream: false
    };

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
            log('HTTP 429 (' + detail + '). Backing off ' + wait + ' ms [' + state.rateLimitHits + '/5].', 'error');
            await jitterSleep(wait, false);
            continue;
          }
          if (response.status === 401 || response.status === 402 || response.status === 403) {
            throw new StopSignal('Gateway auth/quota error ' + response.status + ': ' + detail, 'error');
          }
          if (response.status >= 500 && transientRetries < maxTransientRetries) {
            transientRetries++;
            log('Gateway ' + response.status + ' (' + detail + '); retrying.', 'error');
            await jitterSleep(1000 * transientRetries);
            continue;
          }
          return { status: response.status, detail: detail };
        }

        state.rateLimitHits = 0;
        let json;
        try {
          json = JSON.parse(response.responseText);
        } catch (e) {
          throw new KiloError('Malformed JSON from gateway: ' + e.message, 200, false);
        }
        const choice = json && json.choices && json.choices[0];
        return {
          status: 200,
          content: choice && choice.message && choice.message.content,
          finishReason: choice && choice.finish_reason
        };
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

    log('Reasoning blocked the answer; retrying ' + state.model + ' at ' + CONFIG.fallbackMaxTokens +
      ' tokens with a ' + CONFIG.fallbackTimeoutMs + ' ms timeout.', 'info');
    const fallbackBody = {
      model: state.model,
      messages: messages,
      max_tokens: CONFIG.fallbackMaxTokens,
      temperature: CONFIG.temperature,
      stream: false
    };
    // This endpoint rejects any reasoning-disabling field, even low effort.
    if (!/^liquid\/lfm-3-1\.7b:free$/i.test(state.model)) {
      fallbackBody.reasoning = { effort: 'low' };
    }
    const second = await postChatCompletion(fallbackBody, CONFIG.fallbackTimeoutMs, 1);
    if (second.status !== 200) {
      throw new KiloError('HTTP ' + second.status + ': ' + second.detail, second.status, false);
    }
    if (!hasContent(second) || second.finishReason === 'length') {
      throw new KiloError('Model ' + state.model + ' returned no content (finish_reason: length) even at ' + CONFIG.fallbackMaxTokens + ' tokens', 200, false);
    }
    return second.content;
  }

  function parseProposals(content) {
    if (typeof content !== 'string') return null;
    const text = content.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '').trim();

    let obj;
    try {
      obj = JSON.parse(text);
    } catch (e) {
      return null;
    }
    if (!obj || typeof obj !== 'object' || !Array.isArray(obj.proposals)) return null;
    const context = state.planContext;
    if (!context) return null;

    const ladder = Array.isArray(obj.ladder) && obj.ladder.length === CONFIG.ladderLength &&
      obj.ladder.every(step => typeof step === 'string' && step.trim().length > 0)
      ? obj.ladder.map(step => step.trim()) : null;
    if (!ladder || ladder[ladder.length - 1].toLowerCase() !== context.target.toLowerCase()) return null;
    if (context.continuation && ladder.some((step, i) =>
      step.toLowerCase() !== context.ladder[i].toLowerCase())) return null;
    const activeIndex = context.continuation ? context.activeIndex : 0;
    const activeStep = ladder[activeIndex];

    const raw = String(obj.strategy || '').toLowerCase();
    const strategy = ['backward', 'repair', 'explore'].indexOf(raw) !== -1 ? raw : 'backward';
    const analysis = typeof obj.analysis === 'string' ? obj.analysis.slice(0, 300) : '';
    const seen = new Set();
    const valid = [];
    let droppedTried = 0;
    let droppedInvalid = 0;

    for (const p of obj.proposals) {
      if (!p || typeof p !== 'object') {
        droppedInvalid++;
        continue;
      }
      const a = typeof p.a === 'string' ? p.a.trim() : '';
      const b = typeof p.b === 'string' ? p.b.trim() : '';
      if (!a || !b || !hasOwn(kb.elements, a) || !hasOwn(kb.elements, b)) {
        droppedInvalid++;
        continue;
      }
      const produces = typeof p.produces === 'string' ? p.produces.trim() : '';
      if (produces.toLowerCase() !== activeStep.toLowerCase()) {
        droppedInvalid++;
        continue;
      }
      if (!context.pool.some(n => n.toLowerCase() === a.toLowerCase()) ||
          !context.pool.some(n => n.toLowerCase() === b.toLowerCase())) {
        droppedInvalid++;
        continue;
      }
      const key = pairKey(a, b);
      if (isPairTried(a, b) || seen.has(key)) {
        droppedTried++;
        continue;
      }
      if (typeof p.score !== 'number' || !Number.isFinite(p.score)) {
        droppedInvalid++;
        continue;
      }
      const score = p.score;
      seen.add(key);
      // "produces" is a hypothesis kept only for post-hoc refutation in HYPOTHESIS CHECK.
      valid.push({
        a: a,
        b: b,
        produces: produces.slice(0, 120),
        score: Math.max(0, Math.min(100, Math.round(score))),
        why: typeof p.why === 'string' ? p.why.slice(0, 200) : ''
      });
    }
    if (valid.length < 2) return null;

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
    return valid;
  }

  async function askForProposals(messages) {
    for (let attempt = 1; attempt <= 2; attempt++) {
      assertRunning();
      let content;
      try {
        content = await callKilo(messages);
      } catch (e) {
        if (e instanceof StopSignal) throw e;
        throw new StopSignal('AI request failed: ' + e.message, 'error');
      }

      const batch = parseProposals(content);
      const mostlyTried = !!batch && batch.rawCount > 0 &&
        (batch.droppedTried / batch.rawCount) > CONFIG.repairDropRatio;
      if (batch && !mostlyTried && !batch.abstain) {
        kb.goals = batch.subgoals;
        kb.plan.ladder = batch.ladder.slice();
        kb.plan.activeIndex = batch.activeIndex;
        kb.plan.mode = state.planContext.continuation ? 'continuation' : 'fresh';
        kb.plan.craftsSinceReplan = 0;
        kb.plan.updatedAt = Date.now();
        // A committed fresh plan is the watchdog grace period; the dry spell resets.
        if (!state.planContext.continuation) state.consecutiveNoNew = 0;
        saveKB();
        log('AI strategy: ' + batch.strategy + (batch.analysis ? ' — ' + batch.analysis : ''), 'info');
        log('Plan ladder: ' + batch.ladder.join(' → '), 'info');
        log('Active step ' + batch.activeIndex + ': ' + batch.ladder[batch.activeIndex], 'info');
        log('Batch: ' + batch.rawCount + ' received, ' + batch.length + ' usable (' +
          batch.droppedTried + ' tried, ' + batch.droppedInvalid + ' invalid).', 'info');
        return batch;
      }

      if (attempt === 2) {
        const reason = batch
          ? (batch.abstain
              ? 'AI abstained twice; stopping.'
              : 'Frontier exhausted: ' + batch.droppedTried + '/' + batch.rawCount +
                ' proposals were already tried after repair. Stopping.')
          : 'AI kept returning malformed batches. Stopping.';
        clearPlan(reason);
        throw new StopSignal(reason, 'error');
      }
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
        text: stale ? id + ' (saved, not in catalog)' : id
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
      log('Free model catalog unavailable. Using unverified fallback ' + ids[0] + '.', 'error');
    }

    const saved = state.modelOverride;
    let chosen = null;
    if (saved && isSlowModel(saved)) {
      log('Warning: saved model ' + saved + ' is known-slow and may time out; keeping your choice.', 'error');
    }
    if (saved && (ids.indexOf(saved) !== -1 || isSlowModel(saved))) {
      chosen = saved;
      log('Using saved model override ' + saved + '. Clear it to use preferred fast model ' +
        CONFIG.preferredFastModel + '.', 'info');
    } else if (state.autoModel && ids.indexOf(state.autoModel) !== -1) {
      chosen = state.autoModel;
    } else {
      chosen = ids.indexOf(CONFIG.preferredFastModel) !== -1 ? CONFIG.preferredFastModel : ids[0];
      if (saved) log('Saved model ' + saved + ' is no longer free/current; using ' + chosen + '.', 'error');
    }
    state.autoModel = chosen;
    state.model = chosen;
    log('Using free model: ' + chosen, 'info');
    return chosen;
  }

  function resetModelToPreferred() {
    GM_setValue(STORAGE.model, '');
    state.modelOverride = '';
    state.autoModel = null;
    log('Saved model override cleared; refreshing the free model list.', 'info');
    void refreshModelOptions();
  }

  // ---------------------------------------------------------------------------
  // Crafting. IC.craft is called on the IC object itself to preserve `this`.
  // ---------------------------------------------------------------------------

  async function craftPair(a, b, IC, proposal) {
    // A tried pair must never reach IC.craft, even if every other filter misses it.
    if (isPairTried(a, b)) {
      log('Skipped already-tried pair: ' + a + ' + ' + b, 'info');
      return false;
    }
    const beforeItems = IC.getItems();
    const beforeNames = new Set(beforeItems.map(item => item && item.text));
    const beforeLen = beforeItems.length;
    let result;
    if (state.dragMode) {
      const dragOutcome = await craftViaDrag(a, b, IC);
      if (dragOutcome && dragOutcome.infraFailure) {
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
      recordHypothesis(proposal, null);
      updatePatterns();
      saveKB();
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
    recordHypothesis(proposal, name);
    updatePatterns();
    saveKB();
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
    // A paired mouseup is required: a bare mousedown leaves the book dragging.
    firePointer(entry, 'mousedown', pos.x, pos.y);
    firePointer(entry, 'mouseup', pos.x, pos.y);
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
    log('Drag mode: attempting ' + a + ' + ' + b, 'info');
    const createdIds = [];
    const positions = dragSpawnPositions();
    let watcher = null;
    let heldButton = false;
    let px = 0;
    let py = 0;
    let completed = false;
    try {
      const first = await spawnTileFromBook(a, positions[0], createdIds);
      assertRunning();
      if (!first.ok) return { infraFailure: true, reason: first.reason };
      const second = await spawnTileFromBook(b, positions[1], createdIds);
      assertRunning();
      if (!second.ok) return { infraFailure: true, reason: second.reason };

      // Tiles are selected by the ids just created, never by a stale name match.
      const tileA = findTileById(first.id);
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

      firePointer(tileA, 'mousedown', ax, ay);
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
        firePointer(page || window, 'mousemove', nx, ny, px, py);
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
      firePointer(page || window, 'mouseup', bx, by, px, py);
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
        return { infraFailure: true, reason: 'result observer was cancelled' };
      }
      if (outcome === NO_RESULT) {
        completed = true;
        return null;
      }
      if (typeof outcome !== 'string' || !outcome) {
        return { infraFailure: true, reason: 'unexpected result' };
      }
      completed = true;
      log('Drag mode produced ' + outcome, 'info');
      return outcome;
    } catch (e) {
      if (e instanceof StopSignal) throw e;
      return { infraFailure: true, reason: e.message };
    } finally {
      if (heldButton) {
        firePointer(page || window, 'mouseup', px, py, px, py);
        heldButton = false;
      }
      if (watcher) watcher.cancel();
      // Only tiles created by this drag are removed; the player's tiles stay put.
      if (!completed) removeSpawnedTiles(createdIds);
    }
  }

  // ---------------------------------------------------------------------------
  // Main control loop.
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // v2 greedy relevance planner.
  //
  // Loop: check inventory -> score every new element for relatedness to the
  // target (AI) -> craft the best-scoring untried pair -> learn -> repeat.
  // The AI never predicts recipes; it only judges "how close is this to the
  // target?" and proposes angles. Code owns pair selection and all bookkeeping.
  // ---------------------------------------------------------------------------

  const run2 = { events: [], problems: [], timeline: [], meta: {}, counts: {} };

  function resetRun2(target, mode) {
    run2.events = [];
    run2.problems = [];
    run2.timeline = [];
    run2.meta = {
      target: target, startedAt: new Date().toISOString(), endedAt: null,
      model: state.model || '', mode: mode, status: 'running', stopReason: ''
    };
    run2.counts = {
      crafts: 0, newDiscoveries: 0, knownResults: 0, noResults: 0, sticky: 0,
      aiRequests: 0, aiOk: 0, aiFailed: 0, loopClusters: 0, angleRefreshes: 0, hintsUsed: 0
    };
  }

  function ev(type, data) {
    run2.events.push(Object.assign({ at: new Date().toISOString(), type: type }, data || {}));
    if (run2.events.length > CONFIG.maxEventsExported) {
      run2.events.splice(0, run2.events.length - CONFIG.maxEventsExported);
    }
  }

  // Every malfunction is logged with a reason and evidence, not just "it failed".
  function problem(what, why, extra) {
    const rec = { at: new Date().toISOString(), what: what, why: why, evidence: extra || null };
    run2.problems.push(rec);
    ev('problem', rec);
    log('PROBLEM: ' + what + ' — ' + why, 'error');
  }

  function exportRunLog(auto) {
    try {
      const payload = {
        schemaVersion: 2,
        exportedAt: new Date().toISOString(),
        scriptVersion: '2.0.0',
        run: run2.meta,
        summary: Object.assign({}, run2.counts, {
          problems: run2.problems.length,
          pairsRemembered: Object.keys(kb.triedPairs).length
        }),
        bestScoreTimeline: run2.timeline,
        problems: run2.problems,
        events: run2.events
      };
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const stamp = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'infinite-craft-run-' + stamp + '.json';
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 5000);
      log('Run log ' + (auto ? 'auto-' : '') + 'exported (' + run2.events.length + ' events, ' +
        run2.problems.length + ' problems).', 'info');
    } catch (e) {
      log('Could not export run log: ' + e.message, 'error');
    }
  }

  function stat(name) {
    if (!kb.elementStats[name]) {
      kb.elementStats[name] = { crafts: 0, news: 0, sinceNew: 0, sticky: 0, coolUntil: 0 };
    }
    return kb.elementStats[name];
  }

  function isCooling(name, attempts) {
    const s = kb.elementStats[name];
    return !!s && s.coolUntil > attempts;
  }

  function isDead(name) {
    const s = kb.elementStats[name];
    return !!s && s.sinceNew >= CONFIG.deadAfterCrafts;
  }

  function parseJsonObject(content) {
    if (typeof content !== 'string' || !content.trim()) return { error: 'model returned empty content' };
    const t = content.trim().replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/, '');
    const s = t.indexOf('{');
    const e = t.lastIndexOf('}');
    if (s < 0 || e <= s) return { error: 'no JSON object found in the reply' };
    try {
      return { value: JSON.parse(t.slice(s, e + 1)) };
    } catch (err) {
      return { error: 'JSON.parse failed: ' + err.message };
    }
  }

  async function aiJson(messages, label) {
    run2.counts.aiRequests++;
    ev('ai_request', { label: label, model: state.model });
    let content;
    try {
      content = await callKilo(messages);
    } catch (e) {
      if (e instanceof StopSignal) throw e;
      run2.counts.aiFailed++;
      return { error: 'request failed: ' + (e && e.message ? e.message : String(e)), status: e && e.status, raw: '' };
    }
    const parsed = parseJsonObject(content);
    if (parsed.error) {
      run2.counts.aiFailed++;
      return { error: parsed.error, raw: String(content || '').slice(0, 300) };
    }
    run2.counts.aiOk++;
    return { value: parsed.value };
  }

  function keywordScore(name, ctx) {
    const tok = s => String(s).toLowerCase().split(/[^a-z0-9]+/).filter(t => t.length > 2);
    const mine = tok(name);
    const goal = tok(ctx.target);
    const themeTokens = [].concat.apply([], ctx.themes.map(tok));
    if (mine.some(t => goal.indexOf(t) >= 0)) return 85;
    if (mine.some(t => themeTokens.indexOf(t) >= 0)) return 55;
    return 10;
  }

  const SCORE_SYSTEM = 'You rate how closely related each Infinite Craft element is to a TARGET concept, to decide what to combine next. ' +
    'Score 0-100: 90+ = the target itself or a core part of it; 60-89 = strongly associated (same franchise, key prop, character, setting); ' +
    '30-59 = loosely thematic; 0-29 = unrelated. Judge ONLY semantic relatedness; do not guess recipes. ' +
    'Reply with ONE JSON object and no markdown: {"scores":[{"name":"<exact name given>","score":0,"why":"<=60 chars"}]} covering every name given.';

  async function scoreBatch(ctx, names, store) {
    const user = 'TARGET: ' + ctx.target + '\n' +
      (ctx.themes.length ? 'THEMES: ' + ctx.themes.join(', ') + '\n' : '') +
      'NAMES:\n' + names.map(n => '- ' + n).join('\n');
    const res = await aiJson([
      { role: 'system', content: SCORE_SYSTEM },
      { role: 'user', content: user }
    ], 'score');

    const byName = new Map();
    if (res.error) {
      ctx.aiFailures++;
      problem('Relevance scoring failed', res.error + (res.status ? ' (HTTP ' + res.status + ')' : ''),
        { names: names.length, raw: res.raw || '', model: state.model, consecutiveFailures: ctx.aiFailures });
    } else if (!Array.isArray(res.value.scores)) {
      ctx.aiFailures++;
      problem('Relevance scoring returned the wrong shape',
        'no "scores" array; keys were: ' + Object.keys(res.value).join(', '),
        { model: state.model, consecutiveFailures: ctx.aiFailures });
    } else {
      ctx.aiFailures = 0;
      for (const row of res.value.scores) {
        if (!row || typeof row.name !== 'string') continue;
        const sc = Number(row.score);
        if (!Number.isFinite(sc)) continue;
        byName.set(row.name.trim().toLowerCase(), {
          score: Math.max(0, Math.min(100, Math.round(sc))),
          why: String(row.why || '').slice(0, 80)
        });
      }
    }

    const missing = [];
    const scored = [];
    for (const n of names) {
      const hit = byName.get(n.toLowerCase());
      if (hit) {
        store[n.toLowerCase()] = { name: n, score: hit.score, why: hit.why, source: 'ai', at: Date.now() };
        scored.push({ name: n, score: hit.score });
      } else {
        const prior = store[n.toLowerCase()];
        missing.push(n);
        store[n.toLowerCase()] = {
          name: n, score: keywordScore(n, ctx), why: 'keyword fallback', source: 'keyword',
          retries: (prior && prior.retries ? prior.retries : 0) + 1, at: Date.now()
        };
      }
    }
    if (byName.size && missing.length) {
      problem('Model skipped some elements when scoring',
        missing.length + ' of ' + names.length + ' names were missing or malformed in the reply; used keyword scores instead',
        { missing: missing.slice(0, 15) });
    }
    scored.sort((x, y) => y.score - x.score);
    ev('scored', { count: scored.length, missing: missing.length, top: scored.slice(0, 5) });
    if (scored.length) {
      log('Scored ' + scored.length + '/' + names.length + ' elements. Top: ' +
        scored.slice(0, 3).map(s => s.name + ' ' + s.score).join(', '), 'info');
    }
  }

  async function scorePending(ctx) {
    const key = ctx.target.toLowerCase();
    if (!kb.relevance[key]) kb.relevance[key] = {};
    const store = kb.relevance[key];
    const pending = Object.keys(kb.elements)
      .filter(n => n.toLowerCase() !== key)
      .filter(n => {
        const r = store[n.toLowerCase()];
        return !r || (r.source === 'keyword' && (r.retries || 0) < 2);
      })
      .sort((x, y) => (Number(kb.elements[y].firstSeen) || 0) - (Number(kb.elements[x].firstSeen) || 0));
    if (!pending.length) return;
    const work = pending.slice(0, CONFIG.scoreBatchSize * CONFIG.maxScoreBatchesPerLoop);
    for (let i = 0; i < work.length; i += CONFIG.scoreBatchSize) {
      assertRunning();
      await scoreBatch(ctx, work.slice(i, i + CONFIG.scoreBatchSize), store);
      saveKB();
      if (ctx.aiFailures >= 3) {
        throw new StopSignal('AI scoring failed 3 times in a row (model ' + state.model +
          '). See PROBLEM lines above for the exact reason; try another model from the dropdown.', 'error');
      }
      if (i + CONFIG.scoreBatchSize < work.length) await jitterSleep(400);
    }
  }

  const ANGLES_SYSTEM = 'You steer an Infinite Craft bot toward a TARGET element. The game combines two elements into a related result chosen by its own AI, ' +
    'so recipes cannot be known in advance; you only judge which ideas are semantically near the target.\n' +
    'Reply with ONE JSON object and no markdown: {"themes":["short concept"],"pairs":[{"a":"OWNED name","b":"OWNED name","why":"<=80 chars"}]}\n' +
    'RULES: "themes" = 6 to 10 short concepts tied to the target (props, characters, setting, genre, key ideas): directions to move toward, not recipes. ' +
    '"pairs" = up to 6 combinations of OWNED elements (names copied exactly) most likely to produce the TARGET itself or something on those themes. ' +
    'Never repeat a pair listed under TRIED. If the reason says earlier angles failed, give clearly DIFFERENT themes.';

  async function refreshAngles(ctx, reason) {
    const key = ctx.target.toLowerCase();
    const store = kb.relevance[key] || {};
    const owned = Object.keys(kb.elements).filter(n => n.toLowerCase() !== key);
    const canon = new Map(owned.map(n => [n.toLowerCase(), n]));
    const rank = n => { const r = store[n.toLowerCase()]; return r ? r.score : -1; };
    const shown = owned.slice()
      .sort((x, y) => rank(y) - rank(x) || (Number(kb.elements[y].firstSeen) || 0) - (Number(kb.elements[x].firstSeen) || 0))
      .slice(0, 150);
    const tried = Object.values(kb.triedPairs)
      .filter(p => p && typeof p.a === 'string' && typeof p.b === 'string')
      .sort((x, y) => (Number(y.at) || 0) - (Number(x.at) || 0))
      .slice(0, 60)
      .map(p => p.a + ' + ' + p.b + ' -> ' + (p.result || 'nothing'));
    if (reason !== 'start') {
      ctx.refreshes++;
      run2.counts.angleRefreshes++;
    }
    const user = 'TARGET: ' + ctx.target + '\n' +
      'REASON: ' + (reason === 'start' ? 'first look at the inventory' : reason) + '\n' +
      (ctx.best.name ? 'BEST SO FAR: ' + ctx.best.name + ' (relevance ' + ctx.best.score + ')\n' : '') +
      (ctx.pastThemes.length ? 'PREVIOUS ANGLES (did not help): ' + ctx.pastThemes.join(', ') + '\n' : '') +
      'OWNED:\n' + shown.map(n => '- ' + n).join('\n') + '\n' +
      'TRIED:\n' + (tried.length ? tried.join('\n') : '(none)');
    const res = await aiJson([
      { role: 'system', content: ANGLES_SYSTEM },
      { role: 'user', content: user }
    ], 'angles:' + reason);

    if (res.error) {
      ctx.aiFailures++;
      problem('Angle request failed', res.error + (res.status ? ' (HTTP ' + res.status + ')' : ''),
        { reason: reason, raw: res.raw || '', model: state.model, consecutiveFailures: ctx.aiFailures });
      if (ctx.aiFailures >= 3) {
        throw new StopSignal('AI failed 3 times in a row (model ' + state.model + '); see PROBLEM lines.', 'error');
      }
      return;
    }
    ctx.aiFailures = 0;

    const themes = Array.isArray(res.value.themes)
      ? res.value.themes.filter(t => typeof t === 'string' && t.trim()).map(t => t.trim().slice(0, 40)).slice(0, 10) : [];
    if (!themes.length) problem('Angles reply had no themes', 'the model returned no usable "themes" array', { keys: Object.keys(res.value) });
    if (ctx.themes.length) ctx.pastThemes = ctx.pastThemes.concat(ctx.themes).slice(-20);
    ctx.themes = themes;

    const rawPairs = Array.isArray(res.value.pairs) ? res.value.pairs : [];
    const hints = [];
    const rejected = [];
    for (const p of rawPairs) {
      if (!p || typeof p.a !== 'string' || typeof p.b !== 'string') { rejected.push('malformed pair'); continue; }
      const a = canon.get(p.a.trim().toLowerCase());
      const b = canon.get(p.b.trim().toLowerCase());
      if (!a || !b) { rejected.push(p.a + ' + ' + p.b + ' (not owned: ' + (!a ? p.a : p.b) + ')'); continue; }
      if (isPairTried(a, b)) { rejected.push(a + ' + ' + b + ' (already tried)'); continue; }
      if (hints.some(h => pairKey(h.a, h.b) === pairKey(a, b))) { rejected.push(a + ' + ' + b + ' (duplicate)'); continue; }
      hints.push({ a: a, b: b, why: String(p.why || '').slice(0, 80) });
    }
    ctx.hints = hints;
    ev('angles', { reason: reason, themes: themes, hints: hints, rejected: rejected });
    log('Angles (' + reason + '): ' + (themes.join(', ') || 'none') + ' | ' + hints.length + ' suggested pairs usable, ' +
      rejected.length + ' rejected.', 'info');
    if (rejected.length) {
      log('Rejected suggestions: ' + rejected.slice(0, 4).join('; '), 'info');
    }
  }

  function showMilestone(ctx, top, milestone) {
    const msg = 'Milestone ' + milestone + ' reached for "' + ctx.target + '".\n\n' +
      'Closest element so far: ' + top.name + ' (relevance ' + top.score + ')' +
      (top.why ? ' — ' + top.why : '') + '\n' +
      'Crafts this run: ' + ctx.attempts + '\n\nPress OK to continue, Cancel to stop here.';
    let ok = true;
    try {
      ok = (page.confirm || window.confirm).call(page, msg);
    } catch (e) {
      log('Milestone dialog unavailable (' + e.message + '); continuing.', 'error');
    }
    ev('milestone', { milestone: milestone, best: top, continued: ok });
    if (!ok) throw new StopSignal('Stopped at milestone ' + milestone + ' (best: ' + top.name + ' ' + top.score + ').', 'info');
  }

  function updateBest(ctx) {
    const key = ctx.target.toLowerCase();
    const store = kb.relevance[key] || {};
    let top = null;
    for (const n of Object.keys(kb.elements)) {
      if (n.toLowerCase() === key) continue;
      const r = store[n.toLowerCase()];
      if (!r) continue;
      if (!top || r.score > top.score) top = { name: n, score: r.score, why: r.why };
    }
    if (!top) return;
    const first = ctx.best.score < 0;
    if (top.score > ctx.best.score) {
      ctx.best = top;
      ctx.craftsSinceBest = 0;
      run2.timeline.push({ at: new Date().toISOString(), crafts: ctx.attempts, name: top.name, score: top.score });
      ev('new_best', { name: top.name, score: top.score, why: top.why, crafts: ctx.attempts });
      log('New best: ' + top.name + ' (relevance ' + top.score + (top.why ? ', ' + top.why : '') + ')', 'success');
      if (first) {
        for (const m of CONFIG.milestones) if (top.score >= m) ctx.milestonesHit.add(m);
      } else if (CONFIG.pauseAtMilestones) {
        const crossed = CONFIG.milestones.filter(m => top.score >= m && !ctx.milestonesHit.has(m));
        crossed.forEach(m => ctx.milestonesHit.add(m));
        if (crossed.length) showMilestone(ctx, top, crossed[crossed.length - 1]);
      }
    }
  }

  function choosePair(ctx, relaxAll) {
    while (ctx.hints.length) {
      const h = ctx.hints.shift();
      if (!hasOwn(kb.elements, h.a) || !hasOwn(kb.elements, h.b)) continue;
      if (isPairTried(h.a, h.b)) continue;
      run2.counts.hintsUsed++;
      return { a: h.a, b: h.b, source: 'ai-hint', detail: h.why };
    }
    const key = ctx.target.toLowerCase();
    const store = kb.relevance[key] || {};
    const sc = n => { const r = store[n.toLowerCase()]; return r ? r.score : 10; };
    const owned = Object.keys(kb.elements).filter(n => n.toLowerCase() !== key);
    const cooling = owned.filter(n => isCooling(n, ctx.attempts));
    const dead = owned.filter(n => !isCooling(n, ctx.attempts) && isDead(n));
    const usable = owned.filter(n => !isCooling(n, ctx.attempts) && !isDead(n));
    let eligible = usable.filter(n => sc(n) >= (relaxAll ? 0 : CONFIG.relevanceThreshold));
    let relaxed = !!relaxAll;
    if (!relaxAll && eligible.length < 3) {
      eligible = usable.slice().sort((x, y) => sc(y) - sc(x)).slice(0, 8);
      relaxed = true;
      if (!ctx.relaxedLogged) {
        ctx.relaxedLogged = true;
        log('Fewer than 3 elements score >= ' + CONFIG.relevanceThreshold + '; using the top ' + eligible.length +
          ' instead so the bot can keep exploring.', 'info');
      }
    }
    eligible.sort((x, y) => sc(y) - sc(x));
    eligible = eligible.slice(0, 60);
    let best = null;
    let considered = 0;
    for (let i = 0; i < eligible.length; i++) {
      for (let j = i; j < eligible.length; j++) {
        const a = eligible[i];
        const b = eligible[j];
        if (isPairTried(a, b)) continue;
        considered++;
        let score = sc(a) + sc(b);
        if (a === ctx.best.name || b === ctx.best.name) score += 15;
        score -= 1.5 * (stat(a).crafts + (a === b ? 0 : stat(b).crafts));
        if (!best || score > best.score) best = { a: a, b: b, score: Math.round(score * 10) / 10 };
      }
    }
    ctx.lastChoice = {
      owned: owned.length, eligible: eligible.length, relaxed: relaxed, considered: considered,
      triedPairs: Object.keys(kb.triedPairs).length,
      cooling: cooling.slice(0, 8), dead: dead.slice(0, 8)
    };
    if (!best) return null;
    return {
      a: best.a, b: best.b, source: 'greedy', score: best.score,
      detail: sc(best.a) + '+' + sc(best.b) + (relaxed ? ', threshold relaxed' : '')
    };
  }

  function describeChoice(ctx) {
    const c = ctx.lastChoice;
    if (!c) return 'no pair information available';
    return c.owned + ' elements owned, ' + c.eligible + ' eligible' + (c.relaxed ? ' (threshold relaxed)' : '') +
      ', ' + c.considered + ' untried pairs among them, ' + c.triedPairs + ' pairs tried overall' +
      (c.cooling.length ? ', cooling: ' + c.cooling.join(', ') : '') +
      (c.dead.length ? ', exhausted: ' + c.dead.join(', ') : '');
  }

  function stuckReport(ctx) {
    const best = ctx.best.name ? ctx.best.name + ' (relevance ' + ctx.best.score + ')' : 'nothing scored';
    const hint = ctx.best.score < 40
      ? 'Nothing owned is strongly related to the target, so it may need elements this inventory cannot reach yet. Try crafting a few related basics by hand first.'
      : 'The best element is related but not producing anything closer; the target may need a different intermediate.';
    return 'Stuck: no better element after ' + ctx.refreshes + ' angle refreshes and ' + ctx.attempts +
      ' crafts. Best: ' + best + '. ' + hint + ' State: ' + describeChoice(ctx);
  }

  function detectLoop(ctx) {
    if (ctx.recent.length < CONFIG.loopWindow) return;
    if (ctx.recent.some(r => r.isNew)) return;
    const results = new Set(ctx.recent.map(r => String(r.result).toLowerCase()));
    if (results.size > 3) return;
    const freq = {};
    for (const r of ctx.recent) {
      for (const n of [r.a, r.b]) freq[n] = (freq[n] || 0) + 1;
    }
    let hot = Object.keys(freq).filter(n => freq[n] >= 4);
    if (!hot.length) hot = Object.keys(freq).sort((x, y) => freq[y] - freq[x]).slice(0, 2);
    hot.forEach(n => { stat(n).coolUntil = ctx.attempts + CONFIG.coolAttempts; });
    run2.counts.loopClusters++;
    const resultList = Array.from(results).join(', ');
    log('LOOP CLUSTER: the last ' + ctx.recent.length + ' crafts only produced {' + resultList + '}. Cooling ' +
      hot.join(', ') + ' for ' + CONFIG.coolAttempts + ' crafts.', 'error');
    ev('loop_cluster', { results: Array.from(results), cooled: hot, untilAttempt: ctx.attempts + CONFIG.coolAttempts });
    ctx.recent = [];
  }

  function recordOutcome(ctx, pick, isNew) {
    const last = state.lastCraft;
    const matched = last && last.a === pick.a && last.b === pick.b;
    const result = matched ? last.result : null;
    ctx.attempts++;
    kb.attempts++;
    ctx.craftsSinceBest++;
    run2.counts.crafts++;
    const sa = stat(pick.a);
    const sb = stat(pick.b);
    const both = pick.a === pick.b ? [sa] : [sa, sb];
    both.forEach(s => { s.crafts++; });
    let kind;
    if (result == null) {
      kind = 'nothing';
      run2.counts.noResults++;
      both.forEach(s => { s.sinceNew++; });
    } else if (isNew) {
      kind = 'new';
      run2.counts.newDiscoveries++;
      both.forEach(s => { s.news++; s.sinceNew = 0; });
      // A discovery changes what every element can combine with, so revive exhausted ones a bit.
      Object.keys(kb.elementStats).forEach(k => {
        kb.elementStats[k].sinceNew = Math.max(0, kb.elementStats[k].sinceNew - 4);
      });
    } else {
      kind = 'known';
      run2.counts.knownResults++;
      both.forEach(s => { s.sinceNew++; });
    }
    const sticky = result != null &&
      (String(result).toLowerCase() === pick.a.toLowerCase() || String(result).toLowerCase() === pick.b.toLowerCase());
    if (sticky) {
      run2.counts.sticky++;
      both.forEach(s => { s.sticky++; });
      log('Sticky result: ' + pick.a + ' + ' + pick.b + ' just returned a parent (' + result + '); no progress.', 'info');
    }
    ev('craft', { a: pick.a, b: pick.b, result: result, kind: kind, sticky: sticky, source: pick.source, pickScore: pick.score });
    ctx.recent.push({ a: pick.a, b: pick.b, result: result, isNew: kind === 'new' });
    if (ctx.recent.length > CONFIG.loopWindow) ctx.recent.shift();
    detectLoop(ctx);
  }

  async function run(rawTarget) {
    if (state.running) return;
    const target = canonicalizeTarget(rawTarget);
    state.running = true;
    state.stopped = false;
    state.rateLimitHits = 0;
    state.consecutiveNoNew = 0;
    setRunningUI(true);
    setStatus('Running…');
    resetRun2(target, state.dragMode ? 'drag' : 'instant');
    ev('run_start', { target: target, raw: rawTarget });
    log('Start → target: ' + rawTarget + ' (canonical: ' + target + ') | Mode: ' +
      (state.dragMode ? 'drag (animated)' : 'instant'), 'info');

    const ctx = {
      target: target, themes: [], pastThemes: [], hints: [], best: { name: '', score: -1, why: '' },
      craftsSinceBest: 0, refreshes: 0, milestonesHit: new Set(), recent: [], aiFailures: 0,
      attempts: 0, relaxedLogged: false, lastChoice: null
    };
    Object.keys(kb.elementStats).forEach(k => { kb.elementStats[k].coolUntil = 0; });

    try {
      const IC = await waitForIC(15000);
      state.IC = IC;
      syncElementsFromGame(IC);
      await resolveModel();
      run2.meta.model = state.model;
      assertRunning();
      log('Pair memory: ' + Object.keys(kb.triedPairs).length + ' pairs remembered from earlier runs; ' +
        Object.keys(kb.elements).length + ' elements known.', 'info');

      await refreshAngles(ctx, 'start');

      while (ctx.attempts < CONFIG.maxAttemptsPerRun) {
        assertRunning();
        if (hasElement(target)) {
          log('SUCCESS: "' + target + '" is discovered (' + ctx.attempts + ' crafts this run).', 'success');
          setStatus('Target found');
          run2.meta.status = 'success';
          ev('success', { crafts: ctx.attempts });
          break;
        }
        syncElementsFromGame(IC);
        await scorePending(ctx);
        updateBest(ctx);

        if (ctx.craftsSinceBest >= CONFIG.noBestLimit) {
          if (ctx.refreshes >= CONFIG.maxAngleRefreshes) throw new StopSignal(stuckReport(ctx), 'error');
          log('No new best in ' + ctx.craftsSinceBest + ' crafts (best: ' + ctx.best.name + ' ' + ctx.best.score +
            '). Asking the model for new angles.', 'info');
          await refreshAngles(ctx, 'no new best relevance in ' + ctx.craftsSinceBest + ' crafts');
          ctx.craftsSinceBest = 0;
          continue;
        }

        let pick = choosePair(ctx, false);
        if (!pick) {
          problem('No untried pair among eligible elements', describeChoice(ctx), null);
          pick = choosePair(ctx, true);
          if (pick) log('Relaxed the relevance threshold to keep going.', 'info');
        }
        if (!pick && ctx.refreshes < CONFIG.maxAngleRefreshes) {
          await refreshAngles(ctx, 'no untried pairs left');
          pick = choosePair(ctx, true);
        }
        if (!pick) throw new StopSignal('Frontier exhausted: ' + describeChoice(ctx), 'error');

        log('Next: ' + pick.a + ' + ' + pick.b + ' [' + pick.source + (pick.detail ? ': ' + pick.detail : '') + ']', 'info');
        const isNew = await craftPair(pick.a, pick.b, IC, { a: pick.a, b: pick.b });
        recordOutcome(ctx, pick, isNew);
        saveKB();
        updateCounters();
        setStatus('Craft ' + ctx.attempts + ' / ' + CONFIG.maxAttemptsPerRun + ' | best ' +
          (ctx.best.name || '-') + ' ' + (ctx.best.score >= 0 ? ctx.best.score : ''));
        await jitterSleep(CONFIG.attemptDelayMs);
      }

      if (!state.stopped && ctx.attempts >= CONFIG.maxAttemptsPerRun && !hasElement(target)) {
        log('Safety stop: reached ' + CONFIG.maxAttemptsPerRun + ' crafts this run.', 'error');
        setStatus('Safety stop');
        run2.meta.status = 'safety_stop';
        run2.meta.stopReason = 'craft cap reached';
      }
    } catch (e) {
      if (e instanceof StopSignal) {
        log(e.message, e.level);
        run2.meta.status = e.level === 'error' ? 'stopped_with_problem' : 'stopped';
        run2.meta.stopReason = e.message;
        ev('stop', { message: e.message, level: e.level });
      } else {
        const where = e && e.stack ? String(e.stack).split('\n').slice(0, 3).join(' | ') : '';
        problem('Unexpected script error', (e && e.message ? e.message : String(e)), { where: where });
        setStatus('Error');
        run2.meta.status = 'error';
        run2.meta.stopReason = e && e.message ? e.message : String(e);
      }
    } finally {
      run2.meta.endedAt = new Date().toISOString();
      state.IC = null;
      state.running = false;
      state.stopped = false;
      setRunningUI(false);
      saveKB();
      log('Run summary: ' + run2.counts.crafts + ' crafts, ' + run2.counts.newDiscoveries + ' new, ' +
        run2.counts.sticky + ' sticky, ' + run2.counts.loopClusters + ' loop clusters, ' +
        run2.problems.length + ' problems, best: ' + (ctx.best.name || '-') + ' (' + ctx.best.score + ').', 'info');
      if (run2.events.length > 1) exportRunLog(true);
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
  GM_registerMenuCommand('Export last run log', () => exportRunLog(false));
  GM_registerMenuCommand('Reset model to preferred fast model', resetModelToPreferred);
  log('Ready. ' + Object.keys(kb.elements).length + ' elements, ' +
    kb.recipes.length + ' recipes, ' + kb.patterns.length + ' notes loaded.', 'info');
})();
