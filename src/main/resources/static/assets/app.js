/* 角色 D · D-W1-1 公共脚本
 * 职责：登录态校验、按角色渲染菜单、写操作生成 UUID request_id、防重复连点。
 */
(function () {
  'use strict';

  var ROLE_TEXT = {
    student: '学生', librarian: '图书馆管理员', buyer: '采购员', sysadmin: '系统管理员'
  };

  var NAV = [
    { href: 'catalog.html',  text: '馆藏检索',       roles: ['student', 'librarian'] },
    { href: 'book-new.html', text: '新书登记',       roles: ['librarian'] },
    { href: 'my-loans.html', text: '我的借阅与预约', roles: ['student'] },
    { href: 'accounts.html', text: '账号管理',       roles: ['sysadmin'] }
  ];

  /* ---------------- request_id：写操作统一用 UUID v4 ---------------- */
  function newRequestId() {
    if (window.crypto && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0;
      return (c === 'x' ? r : ((r & 0x3) | 0x8)).toString(16);
    });
  }

  /* ---------------- 统一请求封装 ---------------- */
  function api(path, options) {
    options = options || {};
    var init = { method: options.method || 'GET', credentials: 'same-origin', headers: {} };
    if (options.body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(options.body);
    }
    return fetch(path, init).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        if (text) { try { data = JSON.parse(text); } catch (e) { data = text; } }
        if (!res.ok) {
          var err = new Error((data && (data.message || data.error_code)) || ('HTTP ' + res.status));
          err.status = res.status;
          err.data = data;
          throw err;
        }
        return data;
      });
    });
  }

  function esc(v) {
    return String(v === null || v === undefined ? '' : v)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  var toastTimer = null;
  function toast(msg, type) {
    var box = document.getElementById('toast');
    if (!box) { return; }
    box.textContent = msg;
    box.className = type === 'err' ? 'err' : 'ok';
    box.style.display = 'block';
    if (toastTimer) { clearTimeout(toastTimer); }
    toastTimer = setTimeout(function () { box.style.display = 'none'; }, 3600);
  }

  /* ---------------- 防重复连点：进入请求即锁定，失败才解锁 ---------------- */
  function lock(btn, busyText) {
    if (!btn) { return null; }
    var state = { text: btn.textContent, html: btn.innerHTML };
    btn.disabled = true;
    if (busyText) { btn.textContent = busyText; }
    return state;
  }

  function unlock(btn, state) {
    if (!btn || !state) { return; }
    btn.disabled = false;
    btn.innerHTML = state.html;
  }

  /* ---------------- 登录态与菜单 ---------------- */
  function homeFor(role) {
    if (role === 'sysadmin') { return 'accounts.html'; }
    if (role === 'buyer') { return 'index.html'; }
    return 'catalog.html';
  }

  function renderNav(ctx) {
    var nav = document.getElementById('nav');
    var who = document.getElementById('who');
    if (nav) {
      nav.innerHTML = NAV.filter(function (item) {
        return item.roles.indexOf(ctx.role) >= 0;
      }).map(function (item) {
        var active = location.pathname.indexOf(item.href) >= 0 ? ' class="active"' : '';
        return '<a href="' + item.href + '"' + active + '>' + item.text + '</a>';
      }).join('');
    }
    if (who) {
      who.innerHTML = esc(ctx.login_name) + '（' + (ROLE_TEXT[ctx.role] || ctx.role) + '）' +
        '<a href="#" id="logout">退出</a>';
      var out = document.getElementById('logout');
      if (out) {
        out.addEventListener('click', function (e) {
          e.preventDefault();
          api('/api/auth/logout', { method: 'POST' }).then(function () {
            location.href = 'login.html';
          }, function () {
            location.href = 'login.html';
          });
        });
      }
    }
  }

  /* ---------------- 各页面逻辑 ---------------- */
  var PAGES = {};

  /* 馆藏检索：学生可借出/预约，管理员只读并可进登记页 */
  PAGES.catalog = function (ctx) {
    var state = { keyword: '', category: '', items: [], reserved: {}, ready: true };

    function loadReservations() {
      if (ctx.role !== 'student') { return Promise.resolve(); }
      return api('/api/circulation/reservations').then(function (list) {
        state.reserved = {};
        (list || []).forEach(function (r) {
          if (r.active) { state.reserved[r.book_id] = true; }
        });
      }, function () { /* 预约接口异常不阻塞列表 */ });
    }

    function load() {
      var q = new URLSearchParams();
      if (state.keyword) { q.set('keyword', state.keyword); }
      if (state.category) { q.set('category', state.category); }
      q.set('page', '1');
      q.set('size', '50');
      return api('/api/books?' + q.toString()).then(function (res) {
        state.ready = true;
        state.items = Array.isArray(res) ? res : ((res && res.items) || []);
        render();
      }, function (err) {
        state.ready = false;
        state.items = [];
        render(err);
      });
    }

    function render(err) {
      var host = document.getElementById('list');
      var warn = document.getElementById('warn');
      if (!state.ready) {
        warn.style.display = 'block';
        warn.innerHTML = '馆藏接口尚未就绪（<code>/api/books</code> 属角色 C 的 C-W1-1，当前后端未实现）。' +
          '页面已按接口约定写好，接口上线后无需改动。';
        host.innerHTML = '<div class="empty">暂无数据</div>';
        return;
      }
      warn.style.display = 'none';
      if (!state.items.length) {
        host.innerHTML = '<div class="empty">没有符合条件的图书。</div>';
        return;
      }
      var rows = state.items.map(function (b) {
        var avail = b.available_count === undefined ? b.availableCount : b.available_count;
        var canBorrow = avail > 0;
        var actions = '';
        if (ctx.role === 'student') {
          actions += canBorrow
            ? '<button class="sm primary act-borrow" data-id="' + b.book_id + '">借出</button> '
            : '<button class="sm act-borrow" data-id="' + b.book_id + '" disabled>暂无库存</button> ';
          actions += state.reserved[b.book_id]
            ? '<button class="sm act-reserve" data-id="' + b.book_id + '" data-active="false">取消预约</button>'
            : '<button class="sm act-reserve" data-id="' + b.book_id + '" data-active="true">预约</button>';
        }
        return '<tr>' +
          '<td>' + esc(b.book_id) + '</td>' +
          '<td>' + esc(b.title) + '</td>' +
          '<td>' + esc(b.category) + '</td>' +
          '<td class="num"><span class="badge' + (canBorrow ? '' : ' zero') + '">' + esc(avail) + '</span></td>' +
          '<td>' + actions + '</td>' +
          '</tr>';
      }).join('');
      host.innerHTML = '<table><thead><tr>' +
        '<th class="num">book_id</th><th>书名</th><th>分类</th><th class="num">可借</th><th>操作</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table>';

      host.querySelectorAll('.act-borrow').forEach(function (btn) {
        btn.addEventListener('click', function () { borrow(btn); });
      });
      host.querySelectorAll('.act-reserve').forEach(function (btn) {
        btn.addEventListener('click', function () { reserve(btn); });
      });
    }

    function borrow(btn) {
      var bookId = Number(btn.dataset.id);
      var requestId = newRequestId();               /* 每次写操作新生成一个 UUID */
      var saved = lock(btn, '提交中…');
      api('/api/circulation/checkout', {
        method: 'POST',
        body: { bookId: bookId, requestId: requestId }
      }).then(function (r) {
        toast('借出成功：书目 ' + r.book_id + '，当前可借 ' + r.available_count + ' 本，应还 ' + r.due_on, 'ok');
        return loadReservations().then(load);
      }).catch(function (e) {
        unlock(btn, saved);
        toast('借出失败：' + e.message, 'err');
      });
    }

    function reserve(btn) {
      var bookId = Number(btn.dataset.id);
      var active = btn.dataset.active === 'true';
      var requestId = newRequestId();
      var saved = lock(btn, '提交中…');
      api('/api/circulation/reservations', {
        method: 'POST',
        body: { bookId: bookId, active: active, requestId: requestId }
      }).then(function () {
        toast(active ? '预约成功' : '已取消预约', 'ok');
        return loadReservations().then(load);
      }).catch(function (e) {
        unlock(btn, saved);
        toast((active ? '预约' : '取消预约') + '失败：' + e.message, 'err');
      });
    }

    document.getElementById('search').addEventListener('submit', function (e) {
      e.preventDefault();
      state.keyword = document.getElementById('kw').value.trim();
      state.category = document.getElementById('cat').value.trim();
      load();
    });
    document.getElementById('reset').addEventListener('click', function () {
      document.getElementById('kw').value = '';
      document.getElementById('cat').value = '';
      state.keyword = ''; state.category = '';
      load();
    });

    loadReservations().then(load);
  };

  /* 我的借阅与预约：学生本人 */
  PAGES['my-loans'] = function (ctx) {
    var titles = {};

    function loadTitleMap() {
      return api('/api/books?page=1&size=50').then(function (res) {
        var items = Array.isArray(res) ? res : ((res && res.items) || []);
        items.forEach(function (b) { titles[b.book_id] = b.title; });
      }, function () { /* 馆藏接口未就绪时退回显示编号 */ });
    }

    function nameOf(bookId) {
      return titles[bookId] ? esc(titles[bookId]) : '书目 #' + esc(bookId);
    }

    function load() {
      return Promise.all([
        api('/api/circulation/loans'),
        api('/api/circulation/reservations')
      ]).then(function (out) {
        renderLoans(out[0] || []);
        renderReservations(out[1] || []);
      }).catch(function (e) {
        toast('加载失败：' + e.message, 'err');
      });
    }

    function renderLoans(rows) {
      var host = document.getElementById('loans');
      if (!rows.length) {
        host.innerHTML = '<div class="empty">还没有借阅记录。</div>';
        return;
      }
      var html = rows.map(function (l) {
        var returned = l.returned_on;
        var action = returned
          ? '<span class="badge off">已归还</span>'
          : '<button class="sm primary act-return" data-id="' + l.loan_id + '">归还</button>';
        return '<tr>' +
          '<td>' + nameOf(l.book_id) + '</td>' +
          '<td>' + esc(l.borrowed_on) + '</td>' +
          '<td>' + esc(l.due_on) + '</td>' +
          '<td>' + (returned ? esc(returned) : '<span class="badge">在借</span>') + '</td>' +
          '<td>' + action + '</td>' +
          '</tr>';
      }).join('');
      host.innerHTML = '<table><thead><tr>' +
        '<th>书目</th><th>借出日</th><th>应还日</th><th>实还日</th><th>操作</th>' +
        '</tr></thead><tbody>' + html + '</tbody></table>';

      host.querySelectorAll('.act-return').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var loanId = Number(btn.dataset.id);
          var requestId = newRequestId();
          var saved = lock(btn, '提交中…');
          api('/api/circulation/checkin', {
            method: 'POST',
            body: { loanId: loanId, requestId: requestId }
          }).then(function (r) {
            toast('归还成功，可借数量已恢复为 ' + r.available_count, 'ok');
            return load();
          }).catch(function (e) {
            unlock(btn, saved);
            toast('归还失败：' + e.message, 'err');
          });
        });
      });
    }

    function renderReservations(rows) {
      var host = document.getElementById('reservations');
      var active = rows.filter(function (r) { return r.active; });
      if (!active.length) {
        host.innerHTML = '<div class="empty">当前没有有效预约。</div>';
        return;
      }
      host.innerHTML = '<table><thead><tr><th>书目</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
        active.map(function (r) {
          return '<tr><td>' + nameOf(r.book_id) + '</td><td><span class="badge">预约中</span></td>' +
            '<td><button class="sm act-cancel" data-id="' + r.book_id + '">取消预约</button></td></tr>';
        }).join('') + '</tbody></table>';

      host.querySelectorAll('.act-cancel').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var bookId = Number(btn.dataset.id);
          var requestId = newRequestId();
          var saved = lock(btn, '提交中…');
          api('/api/circulation/reservations', {
            method: 'POST',
            body: { bookId: bookId, active: false, requestId: requestId }
          }).then(function () {
            toast('已取消预约', 'ok');
            return load();
          }).catch(function (e) {
            unlock(btn, saved);
            toast('取消预约失败：' + e.message, 'err');
          });
        });
      });
    }

    loadTitleMap().then(load);
  };

  /* 新书登记：图书馆管理员 */
  PAGES['book-new'] = function () {
    var form = document.getElementById('form');
    var btn = document.getElementById('submit');
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var title = document.getElementById('title').value.trim();
      var category = document.getElementById('category').value.trim();
      var count = Number(document.getElementById('count').value);
      if (!title || !category || !(count >= 0)) {
        toast('书名、分类必填，初始可借数量须为非负数', 'err');
        return;
      }
      var requestId = newRequestId();
      var saved = lock(btn, '登记中…');
      api('/api/books', {
        method: 'POST',
        body: { title: title, category: category, availableCount: count, requestId: requestId }
      }).then(function (r) {
        document.getElementById('result').style.display = 'block';
        document.getElementById('result').innerHTML =
          '登记成功：book_id = <b>' + esc(r.book_id) + '</b>，可借 ' + esc(r.available_count) +
          ' 本，version = ' + esc(r.version);
        form.reset();
        unlock(btn, saved);
      }).catch(function (err) {
        unlock(btn, saved);
        document.getElementById('result').style.display = 'none';
        toast('登记失败：' + err.message, 'err');
      });
    });
  };

  /* 账号管理：系统管理员 */
  PAGES.accounts = function () {
    var majors = [];

    function loadMajors() {
      return api('/api/admin/majors').then(function (list) {
        majors = list || [];
        var sel = document.getElementById('major');
        sel.innerHTML = '<option value="">（无）</option>' + majors.map(function (m) {
          return '<option value="' + m.major_id + '">' + esc(m.code) + ' · ' + esc(m.name) + '</option>';
        }).join('');
      }, function () {});
    }

    function load() {
      return api('/api/admin/accounts').then(function (rows) {
        var host = document.getElementById('list');
        if (!rows || !rows.length) {
          host.innerHTML = '<div class="empty">还没有账号。</div>';
          return;
        }
        host.innerHTML = '<table><thead><tr>' +
          '<th class="num">user_id</th><th>登录名</th><th>角色</th><th>专业</th><th>编号</th><th>状态</th><th>操作</th>' +
          '</tr></thead><tbody>' +
          rows.map(function (a) {
            return '<tr>' +
              '<td class="num">' + esc(a.user_id) + '</td>' +
              '<td>' + esc(a.login_name) + '</td>' +
              '<td>' + esc(ROLE_TEXT[a.role] || a.role) + '</td>' +
              '<td>' + esc(a.major_id === null || a.major_id === undefined ? '—' : a.major_id) + '</td>' +
              '<td>' + esc(a.display_code || '—') + '</td>' +
              '<td>' + (a.is_active ? '<span class="badge">启用</span>' : '<span class="badge off">停用</span>') + '</td>' +
              '<td><button class="sm act-toggle" data-id="' + a.user_id + '" data-active="' + (a.is_active ? 'true' : 'false') + '">' +
              (a.is_active ? '停用' : '启用') + '</button></td>' +
              '</tr>';
          }).join('') + '</tbody></table>';

        host.querySelectorAll('.act-toggle').forEach(function (btn) {
          btn.addEventListener('click', function () {
            var userId = Number(btn.dataset.id);
            var next = btn.dataset.active !== 'true';
            var saved = lock(btn, '…');
            api('/api/admin/accounts/' + userId + '/status', {
              method: 'PATCH',
              body: { isActive: next }
            }).then(function () {
              toast(next ? '账号已启用' : '账号已停用', 'ok');
              return load();
            }).catch(function (e) {
              unlock(btn, saved);
              toast('操作失败：' + e.message, 'err');
            });
          });
        });
      }, function (e) {
        document.getElementById('list').innerHTML =
          '<div class="empty">加载失败：' + esc(e.message) + '</div>';
      });
    }

    var form = document.getElementById('form');
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var body = {
        loginName: document.getElementById('loginName').value.trim(),
        password: document.getElementById('password').value,
        role: document.getElementById('role').value,
        displayCode: document.getElementById('displayCode').value.trim() || null
      };
      var majorId = document.getElementById('major').value;
      body.majorId = majorId ? Number(majorId) : null;
      if (!body.loginName || !body.password) {
        toast('登录名与密码必填', 'err');
        return;
      }
      var btn = document.getElementById('submit');
      var saved = lock(btn, '创建中…');
      api('/api/admin/accounts', { method: 'POST', body: body }).then(function () {
        toast('账号已创建', 'ok');
        form.reset();
        unlock(btn, saved);
        return load();
      }).catch(function (err) {
        unlock(btn, saved);
        toast('创建失败：' + err.message, 'err');
      });
    });

    loadMajors().then(load);
  };

  /* ---------------- 启动 ---------------- */
  function boot() {
    var page = document.body.dataset.page;
    var allowed = (document.body.dataset.roles || '').split(',').filter(Boolean);

    api('/api/auth/me').then(function (ctx) {
      if (allowed.length && allowed.indexOf(ctx.role) < 0) {
        document.querySelector('main').innerHTML =
          '<div class="card"><h1>无权访问</h1>' +
          '<p class="sub">当前角色「' + (ROLE_TEXT[ctx.role] || ctx.role) + '」不能打开这个页面，正在返回可访问页面…</p></div>';
        setTimeout(function () { location.href = homeFor(ctx.role); }, 1200);
        return;
      }
      renderNav(ctx);
      var init = PAGES[page];
      if (init) { init(ctx); }
    }).catch(function () {
      location.href = 'login.html';
    });
  }

  document.addEventListener('DOMContentLoaded', boot);

  window.App = {
    api: api,
    esc: esc,
    toast: toast,
    newRequestId: newRequestId,
    lock: lock,
    unlock: unlock,
    homeFor: homeFor,
    ROLE_TEXT: ROLE_TEXT
  };
})();
