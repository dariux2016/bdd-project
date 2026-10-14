// The catalog page: the phrases the server knows, filtered by source, language and text.
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var sources = {};
  var timer = null;

  function load() {
    var query = [];
    ['source', 'language'].forEach(function (name) {
      if ($(name).value) { query.push(name + '=' + encodeURIComponent($(name).value)); }
    });
    if ($('text').value.trim()) { query.push('q=' + encodeURIComponent($('text').value.trim())); }
    return fetch('/api/catalog' + (query.length ? '?' + query.join('&') : ''))
      .then(function (response) {
        return response.json().then(function (data) { return { ok: response.ok, data: data }; });
      })
      .then(function (r) {
        $('message').hidden = r.ok;
        if (!r.ok) {
          $('message').textContent = r.data.error;
          return;
        }
        render(r.data.phrases);
      });
  }

  function cell(row, text, className) {
    var td = document.createElement('td');
    td.textContent = text;
    if (className) { td.className = className; }
    row.appendChild(td);
  }

  function render(phrases) {
    var body = document.querySelector('#phrases tbody');
    body.innerHTML = '';
    phrases.forEach(function (phrase) {
      sources[phrase.source] = true;
      var row = document.createElement('tr');
      cell(row, phrase.pattern, 'pattern');
      cell(row, phrase.language);
      cell(row, phrase.source);
      cell(row, phrase.parameters.map(function (p) {
        return p.name + ' (' + p.type.toLowerCase() + ')';
      }).join(', '));
      cell(row, phrase.origin);
      body.appendChild(row);
    });
    $('count').textContent = phrases.length + (phrases.length === 1 ? ' frase' : ' frasi');
    fillSources();
  }

  function fillSources() {
    var select = $('source');
    var current = select.value;
    Object.keys(sources).sort().forEach(function (name) {
      var exists = Array.prototype.some.call(select.options, function (o) { return o.value === name; });
      if (!exists) {
        var option = document.createElement('option');
        option.value = name;
        option.textContent = name;
        select.appendChild(option);
      }
    });
    select.value = current;
  }

  $('filters').addEventListener('submit', function (e) { e.preventDefault(); });
  $('source').addEventListener('change', load);
  $('language').addEventListener('change', load);
  $('text').addEventListener('input', function () {
    clearTimeout(timer);
    timer = setTimeout(load, 200);
  });

  load();
})();
