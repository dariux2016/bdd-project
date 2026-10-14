(function () {
  var $ = function (id) { return document.getElementById(id); };
  var state = { path: null, version: null, original: '', selected: null, selectedType: null };

  function api(method, url, body) {
    var options = { method: method, headers: {} };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    return fetch(url, options).then(function (response) {
      return response.json().then(function (data) {
        return { status: response.status, ok: response.ok, data: data };
      });
    });
  }

  function q(path) { return '?path=' + encodeURIComponent(path); }

  function showMessage(text) {
    $('message').textContent = text || '';
    $('message').hidden = !text;
  }

  function renderHighlight() {
    // A trailing newline keeps the overlay as tall as the textarea.
    var marks = window.Validation ? window.Validation.marks() : null;
    $('highlight').innerHTML = Gherkin.highlight($('input').value, marks) + '\n';
  }

  function updateDirty() {
    var dirty = state.path !== null && $('input').value !== state.original;
    $('dirty').hidden = !dirty;
    $('save').disabled = !dirty;
  }

  function open(file) {
    state.path = file.path;
    state.version = file.version;
    state.original = file.content;
    $('input').value = file.content;
    $('current').textContent = file.path;
    $('editor').hidden = false;
    $('conflict').hidden = true;
    showMessage('');
    renderHighlight();
    updateDirty();
    document.dispatchEvent(new Event('bdd:opened'));
  }

  function loadFile(path) {
    if (state.path !== null && $('input').value !== state.original &&
        !confirm('Ci sono modifiche non salvate. Scartarle?')) {
      return Promise.resolve();
    }
    return api('GET', '/api/file' + q(path)).then(function (r) {
      if (r.ok) { open(r.data); } else { showMessage(r.data.error); }
    });
  }

  function save(overwrite) {
    var body = { content: $('input').value, version: state.version, overwrite: !!overwrite };
    return api('PUT', '/api/file' + q(state.path), body).then(function (r) {
      if (r.status === 409) {
        $('conflict').hidden = false;
      } else if (r.ok) {
        state.version = r.data.version;
        state.original = r.data.content;
        $('conflict').hidden = true;
        showMessage('');
        updateDirty();
      } else {
        showMessage(r.data.error);
      }
    });
  }

  function reload() {
    return api('GET', '/api/file' + q(state.path)).then(function (r) {
      if (r.ok) { open(r.data); } else { showMessage(r.data.error); }
    });
  }

  function renderNode(node) {
    var li = document.createElement('li');
    li.className = node.type;
    var label = document.createElement('span');
    label.textContent = node.name;
    if (node.path === state.selected) { label.className = 'selected'; }
    label.addEventListener('click', function () {
      state.selected = node.path;
      state.selectedType = node.type;
      if (node.type === 'file') { loadFile(node.path); }
      refreshTree();
    });
    li.appendChild(label);
    if (node.children) {
      var ul = document.createElement('ul');
      node.children.forEach(function (child) { ul.appendChild(renderNode(child)); });
      li.appendChild(ul);
    }
    return li;
  }

  function refreshTree() {
    return api('GET', '/api/tree').then(function (r) {
      var tree = $('tree');
      tree.innerHTML = '';
      if (r.ok) { tree.appendChild(renderNode(r.data)); }
    });
  }

  function parentOfSelection() {
    if (state.selected === null || state.selected === '') { return ''; }
    if (state.selectedType === 'folder') { return state.selected + '/'; }
    var i = state.selected.lastIndexOf('/');
    return i < 0 ? '' : state.selected.slice(0, i + 1);
  }

  $('input').addEventListener('input', function () { renderHighlight(); updateDirty(); });
  document.addEventListener('bdd:marks', renderHighlight);
  $('input').addEventListener('scroll', function () {
    $('highlight').scrollTop = $('input').scrollTop;
    $('highlight').scrollLeft = $('input').scrollLeft;
  });
  $('input').addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') {
      e.preventDefault();
      if (!$('save').disabled) { save(false); }
    }
  });
  $('save').addEventListener('click', function () { save(false); });
  $('reload').addEventListener('click', reload);
  $('overwrite').addEventListener('click', function () { save(true); });

  $('new-file').addEventListener('click', function () {
    var name = prompt('Nome della nuova feature (es. ordini/nuova.feature)', parentOfSelection());
    if (!name) { return; }
    api('POST', '/api/file' + q(name), { content: '' }).then(function (r) {
      if (!r.ok) { showMessage(r.data.error); return; }
      state.selected = name;
      state.selectedType = 'file';
      refreshTree().then(function () { return loadFile(name); });
    });
  });
  $('new-folder').addEventListener('click', function () {
    var name = prompt('Nome della nuova cartella', parentOfSelection());
    if (!name) { return; }
    api('POST', '/api/folder' + q(name)).then(function (r) {
      if (!r.ok) { showMessage(r.data.error); }
      refreshTree();
    });
  });
  $('rename').addEventListener('click', function () {
    if (!state.selected) { return; }
    var to = prompt('Nuovo percorso', state.selected);
    if (!to || to === state.selected) { return; }
    api('POST', '/api/rename', { from: state.selected, to: to }).then(function (r) {
      if (!r.ok) { showMessage(r.data.error); return; }
      if (state.path === state.selected) { state.path = to; $('current').textContent = to; }
      state.selected = to;
      refreshTree();
    });
  });
  $('delete').addEventListener('click', function () {
    if (!state.selected || !confirm('Eliminare ' + state.selected + '?')) { return; }
    api('DELETE', '/api/entry' + q(state.selected)).then(function (r) {
      if (!r.ok) { showMessage(r.data.error); return; }
      if (state.path !== null && (state.path === state.selected ||
          state.path.indexOf(state.selected + '/') === 0)) {
        state.path = null;
        state.original = '';
        $('editor').hidden = true;
        $('current').textContent = 'Nessun file aperto';
        updateDirty();
      }
      state.selected = null;
      refreshTree();
    });
  });

  refreshTree();
})();
