// Execution panel: launches the open feature (or one of its scenarios) and shows the outcome live.
(function () {
  var SCENARIO = /^\s*(Scenario Outline|Scenario Template|Schema dello scenario|Scenario|Esempio):\s*(.*)$/;
  var $ = function (id) { return document.getElementById(id); };
  var run = null;       // {summary, features: [{name, status, scenarios: [{line, name, status, steps: []}]}]}
  var source = null;    // EventSource of the execution being watched

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text !== undefined) { node.textContent = text; }
    return node;
  }

  function buildPanel() {
    var panel = el('section');
    panel.id = 'run-panel';
    panel.innerHTML =
      '<div class="run-bar">' +
      '<label>Profilo <input id="run-profile" size="10"></label>' +
      '<label>Tag <input id="run-tags" size="14" placeholder="@smoke and not @wip"></label>' +
      '<select id="run-scope"></select>' +
      '<button id="run-start">Esegui</button>' +
      '<button id="run-cancel" hidden>Interrompi</button>' +
      '<span id="run-status"></span>' +
      '</div>' +
      '<div id="run-error" hidden></div>' +
      '<div id="run-results"></div>';
    document.querySelector('main').appendChild(panel);
  }

  function remembered(key, value) {
    try {
      if (value === undefined) { return localStorage.getItem(key) || ''; }
      localStorage.setItem(key, value);
    } catch (e) { /* storage unavailable */ }
    return value;
  }

  function openPath() {
    return $('editor').hidden ? null : $('current').textContent;
  }

  /** Lists "whole feature" and every scenario of the text in the editor. */
  function refreshScope() {
    var select = $('run-scope');
    var previous = select.value;
    var path = openPath();
    select.innerHTML = '';
    var all = el('option', '', path ? 'Tutta la feature aperta' : 'Tutte le feature');
    all.value = '';
    select.appendChild(all);
    if (path) {
      $('input').value.split('\n').forEach(function (line, index) {
        var match = SCENARIO.exec(line);
        if (match) {
          var option = el('option', '', match[1] + ': ' + match[2]);
          option.value = String(index + 1);
          select.appendChild(option);
        }
      });
    }
    var kept = Array.prototype.some.call(select.options, function (o) { return o.value === previous; });
    select.value = kept ? previous : '';
  }

  function request() {
    var path = openPath();
    var line = $('run-scope').value;
    var body = { profile: $('run-profile').value.trim() || null, tags: $('run-tags').value.trim() || null, paths: [] };
    if (path) { body.paths.push(line ? path + ':' + line : path); }
    return body;
  }

  function showError(message) {
    $('run-error').textContent = message || '';
    $('run-error').hidden = !message;
  }

  function post(url, body) {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    }).then(function (response) {
      return response.json().then(function (data) { return { ok: response.ok, data: data }; });
    });
  }

  function running() {
    return run !== null && (run.summary.status === 'starting' || run.summary.status === 'running');
  }

  function render() {
    var live = running();
    $('run-start').disabled = live;
    $('run-cancel').hidden = !live;
    var status = run === null ? '' : ({
      starting: 'Avvio...', running: 'In esecuzione...', passed: 'Superata', failed: 'Fallita',
      cancelled: 'Interrotta', error: 'Errore'
    }[run.summary.status] || run.summary.status);
    $('run-status').textContent = status;
    $('run-status').className = run === null ? '' : 'st-' + run.summary.status;
    var results = $('run-results');
    results.innerHTML = '';
    if (run === null) { return; }
    if (run.summary.status === 'error' && run.summary.error) { showError(run.summary.error); }
    run.features.forEach(function (feature) {
      var box = el('div', 'feature');
      box.appendChild(el('div', 'feature-name st-' + feature.status, feature.name));
      feature.scenarios.forEach(function (scenario) {
        var item = el('div', 'scenario');
        item.appendChild(el('div', 'scenario-name st-' + scenario.status, scenario.name));
        scenario.steps.forEach(function (step) {
          var row = el('div', 'step st-' + step.status);
          row.appendChild(el('span', 'step-text', step.keyword + ' ' + step.text));
          if (step.error) { row.appendChild(el('pre', 'step-error', step.error)); }
          item.appendChild(row);
        });
        box.appendChild(item);
      });
      results.appendChild(box);
    });
  }

  function feature(name) {
    for (var i = 0; i < run.features.length; i++) {
      if (run.features[i].name === name) { return run.features[i]; }
    }
    var created = { name: name, status: 'running', scenarios: [] };
    run.features.push(created);
    return created;
  }

  function scenario(event) {
    var scenarios = feature(event.feature).scenarios;
    for (var i = scenarios.length - 1; i >= 0; i--) {
      if (scenarios[i].line === event.line) { return scenarios[i]; }
    }
    var created = { line: event.line, name: event.name || '', status: 'running', steps: [] };
    scenarios.push(created);
    return created;
  }

  function on(name, handler) {
    source.addEventListener(name, function (message) {
      handler(JSON.parse(message.data));
      render();
    });
  }

  function watch() {
    if (source !== null) { source.close(); }
    run = null;
    showError('');
    source = new EventSource('/api/runs/events');
    on('run-started', function (data) { run = { summary: data, features: [] }; });
    on('feature-started', function (e) { feature(e.feature); });
    on('scenario-started', function (e) { scenario(e); });
    on('step-started', function (e) {
      scenario(e).steps.push({ keyword: e.keyword, text: e.text, status: 'running', error: null });
    });
    on('step-finished', function (e) {
      var steps = scenario(e).steps;
      var step = steps[steps.length - 1];
      if (step && step.status === 'running' && step.text === e.text) {
        step.status = e.status;
        step.error = e.error || null;
      } else {
        steps.push({ keyword: e.keyword, text: e.text, status: e.status, error: e.error || null });
      }
    });
    on('scenario-finished', function (e) { scenario(e).status = e.status; });
    on('feature-finished', function (e) { feature(e.feature).status = e.status; });
    on('run-finished', function (data) {
      if (run === null) { run = { summary: data, features: [] }; }
      run.summary = data;
      if (data.error) { showError(data.error); }
      else if (data.noScenarioSelected) { showError('La selezione non contiene alcuno scenario.'); }
      source.close();
      source = null;
    });
    source.onerror = function () {
      // The stream also ends when the execution does: only a lost connection is worth a word.
      if (source !== null && source.readyState === EventSource.CLOSED) {
        showError('Connessione persa con il server.');
      }
    };
  }

  function start() {
    var body = request();
    remembered('run.profile', $('run-profile').value);
    remembered('run.tags', $('run-tags').value);
    showError('');
    $('run-start').disabled = true;
    post('/api/runs', body).then(function (r) {
      if (!r.ok) {
        // Either another execution is running (watch it) or the start was refused.
        showError(r.data.error);
        $('run-start').disabled = false;
        if (r.data.error && r.data.error.indexOf('already running') >= 0) { watch(); }
        return;
      }
      watch();
    }, function () {
      showError('Server non raggiungibile.');
      $('run-start').disabled = false;
    });
  }

  buildPanel();
  $('run-profile').value = remembered('run.profile');
  $('run-tags').value = remembered('run.tags');
  $('run-start').addEventListener('click', start);
  $('run-cancel').addEventListener('click', function () {
    post('/api/runs/cancel').then(function (r) { if (!r.ok) { showError(r.data.error); } });
  });
  $('run-scope').addEventListener('focus', refreshScope);
  new MutationObserver(refreshScope).observe($('current'), { childList: true, characterData: true, subtree: true });
  refreshScope();

  // Whoever opens the page during (or after) an execution sees its state.
  fetch('/api/runs/current').then(function (r) { return r.json(); }).then(function (data) {
    if (data.run) { watch(); }
  });
})();
