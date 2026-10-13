// Minimal Gherkin highlighter: turns source text into HTML with token classes.
(function (root) {
  var STRUCTURE = /^(\s*)(Feature|Funzionalit[àa]|Background|Contesto|Scenario Outline|Scenario Template|Schema dello scenario|Scenario|Esempio|Rule|Regola|Examples|Esempi)(:)/;
  var STEP = /^(\s*)(Given|When|Then|And|But|\*|Dato|Data|Dati|Date|Quando|Allora|E|Ma)(\s)/;

  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function span(cls, text) {
    return '<span class="tok-' + cls + '">' + escapeHtml(text) + '</span>';
  }

  function strings(text) {
    var out = '', last = 0, re = /"[^"]*"|<[^>\s]+>/g, m;
    while ((m = re.exec(text)) !== null) {
      out += escapeHtml(text.slice(last, m.index)) + span('string', m[0]);
      last = m.index + m[0].length;
    }
    return out + escapeHtml(text.slice(last));
  }

  function highlightLine(line, state) {
    var trimmed = line.trim();
    if (trimmed.indexOf('"""') === 0 || trimmed.indexOf('```') === 0) {
      state.docstring = !state.docstring;
      return span('docstring', line);
    }
    if (state.docstring) return span('docstring', line);
    if (trimmed.charAt(0) === '#') return span('comment', line);
    if (trimmed.charAt(0) === '@') return span('tag', line);
    if (trimmed.charAt(0) === '|') {
      var start = line.indexOf('|');
      return escapeHtml(line.slice(0, start)) + span('table', line.slice(start));
    }
    var m = STRUCTURE.exec(line);
    if (m) {
      return escapeHtml(m[1]) + span('keyword', m[2] + m[3]) + strings(line.slice(m[0].length));
    }
    m = STEP.exec(line);
    if (m) {
      return escapeHtml(m[1]) + span('step', m[2]) + escapeHtml(m[3]) + strings(line.slice(m[0].length));
    }
    return strings(line);
  }

  function highlight(text) {
    var state = { docstring: false };
    return text.split('\n').map(function (line) {
      return highlightLine(line, state);
    }).join('\n');
  }

  root.Gherkin = { highlight: highlight };
})(typeof window !== 'undefined' ? window : globalThis);
