// Live validation and autocompletion of the step phrases of the open feature.
// The matching lives in the server (/api/validate, /api/catalog): this only shows its answers.
(function () {
  var DELAY = 300;
  var LINE_HEIGHT = 19.5;
  var PADDING = 10;
  var MAX_SUGGESTIONS = 30;
  var STEP = /^(\s*)(Given|When|Then|And|But|\*|Dato|Data|Dati|Date|Quando|Allora|E|Ma)(\s+)(.*)$/;

  var $ = function (id) { return document.getElementById(id); };
  var marks = {};
  var timer = null;
  var pending = null;
  var sequence = 0;
  var catalogs = {};
  var suggestions = [];
  var active = 0;
  var suppress = false;

  var input = $('input');

  function languageOf(text) {
    var m = /^\s*#\s*language\s*:\s*([\w-]+)\s*$/m.exec(text);
    return m ? m[1] : 'en';
  }

  // ---- validation ----

  function render(result) {
    var problems = [];
    marks = {};
    result.errors.forEach(function (e) {
      marks[e.line] = 'error';
      problems.push({ line: e.line, kind: 'error', text: e.message });
    });
    result.steps.forEach(function (s) {
      if (s.status !== 'matched') {
        marks[s.line] = s.status;
        problems.push({ line: s.line, kind: s.status, text: s.message });
      }
    });
    problems.sort(function (a, b) { return a.line - b.line; });

    var list = $('problems');
    list.innerHTML = '';
    problems.forEach(function (p) {
      var li = document.createElement('li');
      li.className = 'problem problem-' + p.kind;
      li.textContent = 'Riga ' + p.line + ': ' + p.text;
      li.addEventListener('click', function () { goTo(p.line); });
      list.appendChild(li);
    });
    list.hidden = problems.length === 0;
    document.dispatchEvent(new Event('bdd:marks'));
  }

  function goTo(line) {
    var lines = input.value.split('\n');
    var offset = 0;
    for (var i = 0; i < line - 1 && i < lines.length; i++) { offset += lines[i].length + 1; }
    input.focus();
    input.setSelectionRange(offset, offset + (lines[line - 1] || '').length);
    input.scrollTop = Math.max(0, (line - 3) * LINE_HEIGHT);
  }

  function validateNow() {
    if ($('editor').hidden) { return; }
    var mine = ++sequence;
    if (pending) { pending.abort(); }
    pending = new AbortController();
    fetch('/api/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: input.value }),
      signal: pending.signal
    }).then(function (response) {
      return response.json().then(function (data) { return { ok: response.ok, data: data }; });
    }).then(function (r) {
      // An answer to text that has been edited since is not worth showing.
      if (mine === sequence && r.ok) { render(r.data); }
    }).catch(function () { /* aborted or offline: the next edit validates again */ });
  }

  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(validateNow, DELAY);
  }

  // ---- autocompletion ----

  function catalog(language) {
    if (!catalogs[language]) {
      catalogs[language] = fetch('/api/catalog?language=' + encodeURIComponent(language))
        .then(function (response) { return response.json(); })
        .then(function (data) { return data.phrases; })
        .catch(function () { delete catalogs[language]; return []; });
    }
    return catalogs[language];
  }

  function caretLine() {
    var before = input.value.slice(0, input.selectionStart);
    var index = before.split('\n').length - 1;
    var start = before.lastIndexOf('\n') + 1;
    var end = input.value.indexOf('\n', input.selectionStart);
    return {
      index: index,
      start: start,
      end: end < 0 ? input.value.length : end,
      text: input.value.slice(start, end < 0 ? input.value.length : end),
      typedTo: input.selectionStart - start
    };
  }

  function hide() {
    suggestions = [];
    $('suggestions').hidden = true;
  }

  function show(phrases, line) {
    suggestions = phrases;
    active = 0;
    var list = $('suggestions');
    list.innerHTML = '';
    phrases.forEach(function (phrase, i) {
      var li = document.createElement('li');
      li.textContent = phrase.template;
      li.title = phrase.origin;
      if (i === active) { li.className = 'active'; }
      // mousedown, not click: the textarea must not lose its caret first.
      li.addEventListener('mousedown', function (e) { e.preventDefault(); accept(i); });
      list.appendChild(li);
    });
    list.style.top = (PADDING + (line.index + 1) * LINE_HEIGHT - input.scrollTop) + 'px';
    list.hidden = phrases.length === 0;
  }

  function complete(force) {
    var line = caretLine();
    var m = STEP.exec(line.text);
    if (!m) { hide(); return; }
    var textStart = m[1].length + m[2].length + m[3].length;
    if (line.typedTo < textStart) { hide(); return; }
    var typed = line.text.slice(textStart, line.typedTo).toLowerCase();
    if (typed === '' && !force && !/\s$/.test(line.text.slice(0, line.typedTo))) { hide(); return; }
    var tokens = typed.split(/\s+/).filter(Boolean);
    var language = languageOf(input.value);
    catalog(language).then(function (phrases) {
      // The caret may have moved while the catalog was loading.
      var now = caretLine();
      if (now.index !== line.index) { return; }
      var found = phrases.filter(function (phrase) {
        var text = (phrase.template + ' ' + phrase.pattern).toLowerCase();
        return tokens.every(function (token) { return text.indexOf(token) >= 0; });
      });
      found.sort(function (a, b) {
        var aStarts = a.template.toLowerCase().indexOf(typed) === 0 ? 0 : 1;
        var bStarts = b.template.toLowerCase().indexOf(typed) === 0 ? 0 : 1;
        return aStarts - bStarts || a.template.length - b.template.length;
      });
      show(found.slice(0, MAX_SUGGESTIONS), now);
    });
  }

  function accept(i) {
    var phrase = suggestions[i];
    var line = caretLine();
    var m = STEP.exec(line.text);
    if (!phrase || !m) { hide(); return; }
    var from = line.start + m[1].length + m[2].length + m[3].length;
    var before = input.value.slice(0, from);
    input.value = before + phrase.template + input.value.slice(line.end);
    // The first parameter is selected, so that typing replaces it.
    var placeholder = /"[^"]*"/.exec(phrase.template);
    if (placeholder) {
      input.setSelectionRange(from + placeholder.index + 1, from + placeholder.index + placeholder[0].length - 1);
    } else {
      input.setSelectionRange(from + phrase.template.length, from + phrase.template.length);
    }
    hide();
    suppress = true;
    input.dispatchEvent(new Event('input'));
  }

  function move(delta) {
    var items = $('suggestions').children;
    items[active].className = '';
    active = (active + delta + suggestions.length) % suggestions.length;
    items[active].className = 'active';
    items[active].scrollIntoView({ block: 'nearest' });
  }

  input.addEventListener('input', function () {
    schedule();
    if (suppress) { suppress = false; return; }
    complete(false);
  });
  input.addEventListener('keydown', function (e) {
    if (e.ctrlKey && e.key === ' ') {
      e.preventDefault();
      complete(true);
      return;
    }
    if (suggestions.length === 0 || $('suggestions').hidden) { return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); move(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
    else if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); accept(active); }
    else if (e.key === 'Escape') { e.preventDefault(); hide(); }
  });
  input.addEventListener('blur', hide);
  input.addEventListener('scroll', hide);

  document.addEventListener('bdd:opened', function () {
    marks = {};
    $('problems').hidden = true;
    catalogs = {};
    hide();
    validateNow();
  });

  window.Validation = { marks: function () { return marks; } };
})();
