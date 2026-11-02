(() => {
  var __async = (__this, __arguments, generator) => {
    return new Promise((resolve, reject) => {
      var fulfilled = (value) => {
        try {
          step(generator.next(value));
        } catch (e) {
          reject(e);
        }
      };
      var rejected = (value) => {
        try {
          step(generator.throw(value));
        } catch (e) {
          reject(e);
        }
      };
      var step = (x) => x.done ? resolve(x.value) : Promise.resolve(x.value).then(fulfilled, rejected);
      step((generator = generator.apply(__this, __arguments)).next());
    });
  };

  // src/pages/infra.js
  (function() {
    var REDIS_FIELDS = [
      ["redisHost", "host", "s"],
      ["redisPort", "port", "n"],
      ["redisPassword", "password", "s"],
      ["redisDatabase", "database", "n"]
    ];
    var ES_FIELDS = [
      ["esHost", "host", "s"],
      ["esPort", "port", "n"],
      ["esScheme", "scheme", "s"],
      ["esUsername", "username", "s"],
      ["esPassword", "password", "s"],
      ["esConnectTimeout", "connectTimeout", "n"],
      ["esSocketTimeout", "socketTimeout", "n"]
    ];
    var QD_FIELDS = [
      ["vectorUri", "uri", "s"],
      ["vectorToken", "token", "s"],
      ["vectorCollectionPrefix", "collectionPrefix", "s"],
      ["vectorMetric", "metric", "s"]
    ];
    var VES_FIELDS = [
      ["vesHost", "host", "s"],
      ["vesPort", "port", "n"],
      ["vesScheme", "scheme", "s"],
      ["vesUsername", "username", "s"],
      ["vesPassword", "password", "s"],
      ["vesConnectTimeout", "connectTimeout", "n"],
      ["vesSocketTimeout", "socketTimeout", "n"]
    ];
    var NAME_HELP = "\u540C\u7C7B\u578B\u5185\u4E0D\u80FD\u91CD\u540D\u3002";
    var STATE = { redis: {}, connections: [], bodies: [] };
    var FLAGS = { es: false, redis: false, vector: false, esSource: "-", redisSource: "-", vectorSource: "-" };
    function el(id) {
      return document.getElementById(id);
    }
    function readFields(fields) {
      var out = {};
      fields.forEach(function(f) {
        var node = el(f[0]);
        if (!node) {
          return;
        }
        var raw = node.value.trim();
        if (f[2] === "n") {
          out[f[1]] = raw === "" ? null : Number(raw);
        } else {
          out[f[1]] = raw === "" ? null : raw;
        }
      });
      return out;
    }
    function writeFields(fields, src) {
      fields.forEach(function(f) {
        var node = el(f[0]);
        if (!node) {
          return;
        }
        var v = src ? src[f[1]] : null;
        node.value = v == null ? "" : v;
      });
    }
    function setStatus(html, cls) {
      var node = el("infraStatus");
      node.className = "status-line " + cls;
      node.innerHTML = html;
    }
    function showResult(id, text, cls) {
      var box = el(id);
      box.textContent = text;
      box.className = "result show " + (cls || "");
    }
    function showHtmlResult(id, html, cls) {
      var box = el(id);
      box.innerHTML = html;
      box.className = "result show " + (cls || "");
    }
    function hideResult(id) {
      el(id).className = "result";
      el(id).textContent = "";
    }
    function setNameHelp(id, text, isErr) {
      var node = el(id);
      node.className = "help" + (isErr ? " err" : "");
      node.textContent = text;
    }
    function esc(v) {
      return Console.escapeHtml(v == null || v === "" ? "-" : v);
    }
    function facts(pairs) {
      return '<div class="facts">' + pairs.map(function(p) {
        return '<span class="fact">' + Console.escapeHtml(p[0]) + " <b>" + esc(p[1]) + "</b></span>";
      }).join("") + "</div>";
    }
    function ikChip(ik) {
      if (!ik) {
        return "";
      }
      var tone = ik.available === true ? "ok" : ik.available === false ? "no" : "unknown";
      var label = ik.available === true ? "IK \u5206\u8BCD\u5668\uFF1A\u53EF\u7528" : ik.available === false ? "IK \u5206\u8BCD\u5668\uFF1A\u672A\u5B89\u88C5" : "IK \u5206\u8BCD\u5668\uFF1A\u672A\u63A2\u6D4B";
      return '<span class="compat ' + tone + '">' + label + "</span>";
    }
    function renderEsProbe(data) {
      var compat = data.compatibility || {};
      var html = '<div class="probe"><div class="summary">\u2705 ' + esc(data.message) + "</div>" + facts([["\u7248\u672C", data.version], ["\u53D1\u884C\u7248", data.buildFlavor], ["\u96C6\u7FA4", data.clusterName]]) + '<div class="facts"><span class="compat ' + (compat.tone || "unknown") + '">' + esc(compat.label) + "</span>" + ikChip(data.ik) + "</div>";
      if (compat.note) {
        html += '<div class="note">' + esc(compat.note) + "</div>";
      }
      if (compat.hint) {
        html += '<div class="note strong">\u26A0\uFE0F ' + esc(compat.hint) + "</div>";
      }
      if (data.ik && data.ik.note) {
        html += '<div class="note' + (data.ik.available === false ? " strong" : "") + '">' + (data.ik.available === false ? "\u26A0\uFE0F " : "") + esc(data.ik.note) + "</div>";
      }
      return html + "</div>";
    }
    function renderRedisProbe(data) {
      var databases = data.databases == null ? "-" : data.database + " / \u5171 " + data.databases + " \u4E2A\u5E93";
      var html = '<div class="probe"><div class="summary">\u2705 ' + esc(data.message) + "</div>" + facts([
        ["\u7248\u672C", data.version],
        ["\u62D3\u6251", data.mode],
        ["\u5E93\u53F7", databases],
        ["\u5F53\u524D key \u6570", data.dbSize]
      ]);
      if (data.note) {
        html += '<div class="note strong">\u26A0\uFE0F ' + esc(data.note) + "</div>";
      }
      return html + "</div>";
    }
    function renderVectorProbe(data) {
      var html = '<div class="probe"><div class="summary">\u2705 ' + esc(data.message) + "</div>" + facts([
        ["\u7248\u672C", data.version],
        ["\u8DDD\u79BB\u5EA6\u91CF", data.metric],
        ["\u5DF2\u6709\u96C6\u5408\u6570", data.collectionCount]
      ]);
      if (data.note && data.note !== data.message) {
        html += '<div class="note strong">\u26A0\uFE0F ' + esc(data.note) + "</div>";
      }
      return html + "</div>";
    }
    function renderStatus() {
      var esOk = FLAGS.es, rOk = FLAGS.redis, vOk = FLAGS.vector;
      var msg;
      if (esOk && rOk) {
        msg = vOk ? "Redis\u3001\u5173\u952E\u5B57\u8FDE\u63A5\u4E0E\u5411\u91CF\u8FDE\u63A5\u5747\u5DF2\u5C31\u7EEA" : "Redis \u4E0E\u5173\u952E\u5B57\u8FDE\u63A5\u5DF2\u5C31\u7EEA\uFF1B\u5411\u91CF\u69FD\u672A\u7ED1\u5B9A\uFF0C\u77E5\u8BC6\u5E93\u53EA\u8D70\u5173\u952E\u8BCD\u8DEF";
      } else if (esOk) {
        msg = "\u5173\u952E\u5B57\u8FDE\u63A5\u5DF2\u5C31\u7EEA\uFF1BRedis \u672A\u914D\u7F6E\uFF0C\u4F1A\u8BDD\u8BB0\u5FC6\u4E0E\u4E2D\u65AD\u65AD\u70B9\u4E0D\u53EF\u7528";
      } else if (rOk) {
        msg = "Redis \u5DF2\u914D\u7F6E\uFF1B\u5173\u952E\u5B57\u69FD\u672A\u7ED1\u5B9A\uFF0C\u77E5\u8BC6\u5E93\u68C0\u7D22\u4E0E\u704C\u5E93\u4E0D\u53EF\u7528";
      } else {
        msg = "Redis \u4E0E\u5173\u952E\u5B57\u8FDE\u63A5\u5747\u672A\u914D\u7F6E\u3002\u670D\u52A1\u53EF\u4EE5\u6B63\u5E38\u542F\u52A8\uFF0C\u4F46\u4F1A\u8BDD\u8BB0\u5FC6\u4E0E\u77E5\u8BC6\u5E93\u4E0D\u53EF\u7528\uFF0C\u8BF7\u5148\u586B Redis \u5730\u5740\u3002";
      }
      setStatus(
        "<span>" + msg + '</span><span class="pill">Redis ' + (rOk ? "\u2713 " : "\u2717 ") + Console.escapeHtml(FLAGS.redisSource) + '</span><span class="pill">\u5173\u952E\u5B57 ' + (esOk ? "\u2713 " : "\u2717 ") + Console.escapeHtml(FLAGS.esSource) + '</span><span class="pill">\u5411\u91CF ' + (vOk ? "\u2713 " : "\u2717 ") + Console.escapeHtml(FLAGS.vectorSource) + "</span>",
        esOk && rOk ? "ok" : "warn"
      );
    }
    function normConn(c) {
      c.usage = c.usage || (c.type === "qdrant" ? "vector" : "keyword");
      c.name = c.name || c.id;
      return c;
    }
    function findConn(id) {
      for (var i = 0; i < STATE.connections.length; i++) {
        if (STATE.connections[i].id === id) {
          return STATE.connections[i];
        }
      }
      return null;
    }
    function connById(id) {
      return id ? findConn(id) : null;
    }
    function addressOf(c) {
      if (c.type === "qdrant") {
        var v = c.vector || {};
        return v.uri ? v.uri + (v.metric ? " \xB7 " + v.metric : "") + (v.collectionPrefix ? " \xB7 \u524D\u7F00 " + v.collectionPrefix : "") : "\u672A\u586B\u5730\u5740";
      }
      var es = c.es || {};
      return es.host ? es.host + ":" + (es.port || 9200) + " \xB7 " + (es.scheme || "http") + (es.username ? " \xB7 " + es.username : "") : "\u672A\u586B\u5730\u5740";
    }
    function refsOf(id) {
      var names = [];
      STATE.bodies.forEach(function(b) {
        if (b.keyword === id || b.vector === id) {
          names.push(b.name || b.id);
        }
      });
      return names;
    }
    function rowHtml(c) {
      var dot = c.type === "qdrant" ? "dot-vec" : "dot-es";
      var refs = refsOf(c.id);
      var usable = c.usable !== false;
      return '<tr class="conn-row"><td><span class="conn-name"><i class="conn-dot ' + dot + '"></i>' + Console.escapeHtml(c.id) + '</span></td><td class="mono">' + Console.escapeHtml(addressOf(c)) + '</td><td><span class="mbadge ' + (usable ? "ok" : "off") + '">' + (usable ? "\u5DF2\u914D\u7F6E" : "\u672A\u914D\u7F6E") + '</span></td><td><span class="refs">' + (refs.length ? refs.map(function(n) {
        return '<span class="ref-chip">' + Console.escapeHtml(n) + "</span>";
      }).join("") : '<span class="none">\u672A\u88AB\u5F15\u7528</span>') + '</span></td><td class="ta-r nowrap"><button class="linkbtn" data-edit="' + Console.escapeHtml(c.id) + '">\u7F16\u8F91</button><button class="linkbtn danger" data-del="' + Console.escapeHtml(c.id) + '">\u5220\u9664</button></td></tr>';
    }
    function emptyRow(text) {
      return '<tr><td colspan="5" class="muted-sm">' + Console.escapeHtml(text) + "</td></tr>";
    }
    function renderConns() {
      var kw = [], vec = [];
      STATE.connections.forEach(function(c) {
        if (c.usage === "vector") {
          vec.push(c);
        } else {
          kw.push(c);
        }
      });
      el("connBodyEs").innerHTML = kw.length ? kw.map(rowHtml).join("") : emptyRow("\u8FD8\u6CA1\u6709\u5173\u952E\u5B57\u8FDE\u63A5\uFF0C\u70B9\u300C+ \u65B0\u589E\u300D\u52A0\u4E00\u53F0");
      el("connBodyVec").innerHTML = vec.length ? vec.map(rowHtml).join("") : emptyRow("\u8FD8\u6CA1\u6709\u5411\u91CF\u8FDE\u63A5\uFF0C\u70B9\u300C+ \u65B0\u589E\u300D\u52A0\u4E00\u53F0");
      el("connCount").textContent = STATE.connections.length + " \u4E2A\u8FDE\u63A5";
    }
    var SLOT_META = {
      keyword: {
        label: "\u5173\u952E\u5B57",
        hint: "\u5168\u6587\u68C0\u7D22\uFF08BM25\uFF09\xB7 \u53EA\u80FD\u7ED1 Elasticsearch",
        empty: "\uFF08\u4E0D\u505A\u5168\u6587\u68C0\u7D22\uFF09",
        types: ["elasticsearch"]
      },
      vector: {
        label: "\u5411\u91CF",
        hint: "kNN \u8BED\u4E49\u68C0\u7D22 \xB7 Elasticsearch / Qdrant \u90FD\u884C",
        empty: "\uFF08\u4E0D\u505A\u8BED\u4E49\u68C0\u7D22\uFF09",
        types: ["elasticsearch", "qdrant"]
      }
    };
    var SLOT_ORDER = ["keyword", "vector"];
    function connsFor(slot) {
      var types = SLOT_META[slot].types;
      return STATE.connections.filter(function(c) {
        return types.indexOf(c.type) >= 0;
      });
    }
    function slotOptions(slot, selected) {
      var meta = SLOT_META[slot];
      var list = connsFor(slot);
      if (!list.length) {
        return '<option value="">\uFF08\u6CA1\u6709\u53EF\u7528\u4E8E' + meta.label + "\u7684\u8FDE\u63A5\uFF09</option>";
      }
      var html = '<option value="">' + meta.empty + "</option>";
      list.forEach(function(c) {
        html += '<option value="' + Console.escapeHtml(c.id) + '"' + (c.id === selected ? " selected" : "") + ">" + Console.escapeHtml(c.name || c.id) + "\uFF08" + (c.type === "qdrant" ? "Qdrant" : "Elasticsearch") + "\uFF09</option>";
      });
      return html;
    }
    function modesOf(b) {
      var hasKw = !!b.keyword;
      var hasVec = !!b.vector;
      if (hasKw && hasVec) {
        return { text: "\u5173\u952E\u5B57 + \u5411\u91CF", cls: "ok" };
      }
      if (hasKw) {
        return { text: "\u4EC5\u5173\u952E\u5B57", cls: "ok" };
      }
      if (hasVec) {
        return { text: "\u4EC5\u5411\u91CF", cls: "ok" };
      }
      return { text: "\u672A\u914D", cls: "off" };
    }
    function slotLine(label, control, hint, filled) {
      return '<div class="slot' + (filled ? "" : " empty") + '"><span class="slot-k">' + label + "</span>" + control + '<span class="slot-h">' + hint + "</span></div>";
    }
    function bodyCard(b) {
      var modes = modesOf(b);
      var rows = SLOT_ORDER.map(function(slot) {
        var selected = (slot === "keyword" ? b.keyword : b.vector) || "";
        var c = selected ? findConn(selected) : null;
        var hint = selected ? (c && c.location || selected) + (c && c.usable === false ? "\uFF08\u672A\u586B\u5730\u5740\uFF09" : "") : SLOT_META[slot].hint;
        var control = '<select class="form-control slot-v" data-body="' + Console.escapeHtml(b.id) + '" data-field="' + slot + '">' + slotOptions(slot, selected) + "</select>";
        return slotLine(SLOT_META[slot].label, control, hint, !!selected);
      }).join("");
      return '<article class="profile"><header class="profile-head"><span class="p-dot"></span><h4>' + Console.escapeHtml(b.name || b.id) + '</h4><span class="mbadge ' + modes.cls + '">' + modes.text + '</span><span class="spacer"></span><button class="linkbtn danger" data-delbody="' + Console.escapeHtml(b.id) + '">\u5220\u9664</button></header>' + rows + "</article>";
    }
    function renderProfiles() {
      var box = el("profileList");
      if (!STATE.bodies.length) {
        box.innerHTML = '<div class="muted-sm">\u8FD8\u6CA1\u6709\u77E5\u8BC6\u4F53</div>';
      } else {
        box.innerHTML = STATE.bodies.map(bodyCard).join("");
      }
      el("profCount").textContent = STATE.bodies.length + " \u4E2A\u77E5\u8BC6\u4F53";
    }
    function body() {
      return {
        redis: readFields(REDIS_FIELDS),
        connections: STATE.connections,
        bodies: STATE.bodies
      };
    }
    function postJson(payload) {
      return __async(this, null, function* () {
        return Console.api.post("/admin/infra", payload);
      });
    }
    function commit(rollback, resultId, okText, btn) {
      return __async(this, null, function* () {
        if (btn) {
          btn.disabled = true;
          btn.classList.add("btn-loading");
        }
        showResult(resultId, "\u4FDD\u5B58\u4E2D\u2026", "");
        try {
          var data = yield postJson(body());
          yield loadInfra(true);
          if (resultId) {
            showResult(resultId, "\u2705 " + okText, "ok");
          }
          /* \u4FDD\u5B58\u65F6\u7684\u81EA\u68C0\u7ED3\u8BBA\uFF08\u77E5\u8BC6\u5E93\u80FD\u529B\u4E0D\u5339\u914D / Redis \u6CA1\u6301\u4E45\u5316\u7B49\uFF09\u4EE5\u524D\u53EA\u5728\u542F\u52A8\u65E5\u5FD7\u91CC\uFF0C
             \u73B0\u5728\u8DDF\u7740\u54CD\u5E94\u56DE\u6765\uFF1A\u6709\u5C31\u660E\u8BF4\uFF0C\u6CA1\u6709\u5C31\u7167\u5E38\u53EA\u62A5\u6210\u529F */
          var warns = data && data.warnings || [];
          if (warns.length) {
            Console.toast("\u5DF2\u4FDD\u5B58\uFF0C\u4F46\u6709 " + warns.length + " \u6761\u63D0\u9192\uFF1A\n" + warns.join("\n"), { type: "warning", wide: true });
          } else {
            Console.toast(okText, { type: "success" });
          }
          return data;
        } catch (e) {
          if (rollback) {
            rollback();
          }
          renderConns();
          renderProfiles();
          showResult(resultId, "\u274C \u4FDD\u5B58\u5931\u8D25\uFF1A" + e.message, "err");
          return null;
        } finally {
          if (btn) {
            btn.disabled = false;
            btn.classList.remove("btn-loading");
          }
        }
      });
    }
    function loadInfra(quiet) {
      return __async(this, null, function* () {
        if (!quiet) {
          setStatus("<span>\u52A0\u8F7D\u4E2D\u2026</span>", "warn");
        }
        var s = yield Console.api.get("/admin/infra");
        STATE.redis = s.redis || {};
        STATE.connections = (s.connections || []).map(normConn);
        STATE.bodies = s.bodies || [];
        writeFields(REDIS_FIELDS, {
          host: s.redis.host,
          port: s.redis.port,
          database: s.redis.database,
          password: null
        });
        applyRedisKeyHint(s.redis);
        FLAGS.es = !!s.esConfigured;
        FLAGS.redis = !!s.redisConfigured;
        FLAGS.vector = !!s.vectorSlotConfigured;
        FLAGS.esSource = s.esSource || "-";
        FLAGS.redisSource = s.redisSource || "-";
        FLAGS.vectorSource = s.vectorSlotSource || "-";
        renderStatus();
        var badge = el("redisBadge");
        badge.className = "mbadge " + (FLAGS.redis ? "ok" : "off");
        badge.textContent = FLAGS.redis ? "\u5DF2\u751F\u6548" : "\u672A\u914D\u7F6E";
        renderConns();
        renderProfiles();
        return s;
      });
    }
    function applyRedisKeyHint(redis) {
      var isSet = redis && redis.passwordSet;
      el("redisKeyHelp").textContent = isSet ? "\u5F53\u524D\u5DF2\u914D\u7F6E\uFF1A" + redis.passwordMasked : "\u5F53\u524D\u672A\u914D\u7F6E";
      el("redisPassword").placeholder = isSet ? "\u5DF2\u914D\u7F6E\uFF0C\u7559\u7A7A\u4E0D\u53D8" : "\u7559\u7A7A\u8868\u793A\u4E0D\u4FEE\u6539";
    }
    function applyEsKeyHint(conn, helpId, inputId) {
      var es = conn && conn.es || {};
      var isSet = !!es.passwordSet;
      el(helpId).textContent = isSet ? "\u5F53\u524D\u5DF2\u914D\u7F6E\uFF1A" + es.passwordMasked : "\u5F53\u524D\u672A\u914D\u7F6E";
      el(inputId).placeholder = isSet ? "\u5DF2\u914D\u7F6E\uFF0C\u7559\u7A7A\u4E0D\u53D8" : "\u7559\u7A7A\u5219\u4E0D\u53D1\u9001\u8BA4\u8BC1\u5934";
    }
    function applyQdKeyHint(conn) {
      var v = conn && conn.vector || {};
      var isSet = !!v.tokenSet;
      el("vectorKeyHelp").textContent = isSet ? "\u5F53\u524D\u5DF2\u914D\u7F6E\uFF1A" + v.tokenMasked : "\u5F53\u524D\u672A\u914D\u7F6E";
      el("vectorToken").placeholder = isSet ? "\u5DF2\u914D\u7F6E\uFF0C\u7559\u7A7A\u4E0D\u53D8" : "\u7559\u7A7A\u5219\u4E0D\u53D1\u9001\u9274\u6743\u5934";
    }
    function showModal(id) {
      el(id).hidden = false;
      document.body.style.overflow = "hidden";
    }
    function hideModal(id) {
      el(id).hidden = true;
      document.body.style.overflow = "";
    }
    function resetPresetCards(pickId) {
      Array.prototype.forEach.call(
        document.querySelectorAll("#" + pickId + " .preset-card"),
        function(c) {
          c.classList.remove("is-on");
        }
      );
    }
    function testConn(kind, payload, resultId, btn) {
      btn.disabled = true;
      showResult(resultId, "\u6D4B\u8BD5\u4E2D\u2026\uFF08\u4F1A\u771F\u5B9E\u5EFA\u7ACB\u4E00\u6B21\u8FDE\u63A5\uFF0C\u5E76\u63A2\u6D4B\u7248\u672C\u4E0E\u80FD\u529B\uFF09", "");
      Console.api.post("/admin/infra/test?type=" + encodeURIComponent(kind), payload).then(function(data) {
        var render = kind === "redis" ? renderRedisProbe : kind === "es" ? renderEsProbe : renderVectorProbe;
        showHtmlResult(resultId, render(data), "ok");
      }).catch(function(e) {
        console.debug("[domainplane] \u8FDE\u63A5\u6D4B\u8BD5\u5931\u8D25\uFF1A", e);
        showResult(resultId, "\u8FDE\u63A5\u5931\u8D25\uFF1A" + e.message, "err");
      }).then(function() {
        btn.disabled = false;
      });
    }
    var esMode = "new";
    var esEditId = null;
    function openEsModal(mode, id) {
      esMode = mode;
      esEditId = id || null;
      var conn = connById(esEditId);
      el("connModalEsTitle").textContent = mode === "new" ? "\u65B0\u589E\u5173\u952E\u5B57\u8FDE\u63A5" : "\u7F16\u8F91\u8FDE\u63A5 \xB7 " + id;
      el("esPresetPick").hidden = mode !== "new";
      el("esNameRow").hidden = mode !== "new";
      el("esFormWrap").hidden = mode === "new";
      resetPresetCards("esPresetPick");
      hideResult("esResult");
      setNameHelp("esConnNameHelp", NAME_HELP, false);
      if (mode === "new") {
        el("esConnName").value = "";
        writeFields(ES_FIELDS, null);
        applyEsKeyHint(null, "esKeyHelp", "esPassword");
      } else {
        writeFields(ES_FIELDS, conn && conn.es);
        applyEsKeyHint(conn, "esKeyHelp", "esPassword");
      }
      el("saveEsBtn").textContent = mode === "new" ? "\u52A0\u5165\u8FDE\u63A5" : "\u4FDD\u5B58\u5E76\u751F\u6548";
      showModal("connModalEs");
    }
    Array.prototype.forEach.call(
      document.querySelectorAll("#esPresetPick .preset-card"),
      function(card) {
        card.addEventListener("click", function() {
          Array.prototype.forEach.call(
            document.querySelectorAll("#esPresetPick .preset-card"),
            function(c) {
              c.classList.toggle("is-on", c === card);
            }
          );
          el("esFormWrap").hidden = false;
          el("esConnName").focus();
        });
      }
    );
    function saveEs(btn) {
      return __async(this, null, function* () {
        var name = esMode === "new" ? el("esConnName").value.trim() : esEditId;
        if (esMode === "new") {
          if (!name) {
            setNameHelp("esConnNameHelp", "\u5148\u7ED9\u8FD9\u4E2A\u8FDE\u63A5\u8D77\u4E2A\u540D\u5B57\u3002", true);
            el("esConnName").focus();
            return false;
          }
          if (findConn(name)) {
            setNameHelp("esConnNameHelp", "\u5DF2\u7ECF\u6709\u53EB\u300C" + name + "\u300D\u7684\u8FDE\u63A5\u4E86\uFF0C\u6362\u4E00\u4E2A\u3002", true);
            el("esConnName").focus();
            return false;
          }
        }
        var conn = {
          id: name,
          name,
          type: "elasticsearch",
          usage: "keyword",
          es: readFields(ES_FIELDS),
          vector: null
        };
        var idx = -1;
        STATE.connections.forEach(function(c, i) {
          if (c.id === name) {
            idx = i;
          }
        });
        var prev = idx >= 0 ? STATE.connections[idx] : null;
        if (idx >= 0) {
          STATE.connections[idx] = conn;
        } else {
          STATE.connections.push(conn);
        }
        renderConns();
        var data = yield commit(function() {
          if (prev) {
            STATE.connections[idx] = prev;
          } else {
            STATE.connections.pop();
          }
        }, "esResult", "\u8FDE\u63A5\u300C" + name + "\u300D\u5DF2\u4FDD\u5B58\u5E76\u751F\u6548", btn);
        if (data) {
          hideModal("connModalEs");
        }
        return !!data;
      });
    }
    var vecMode = "new";
    var vecEditId = null;
    var vecPreset = "qdrant";
    function openVecModal(mode, id) {
      vecMode = mode;
      vecEditId = id || null;
      var conn = connById(vecEditId);
      vecPreset = conn ? conn.type === "qdrant" ? "qdrant" : "es" : null;
      el("connModalVecTitle").textContent = mode === "new" ? "\u65B0\u589E\u5411\u91CF\u8FDE\u63A5" : "\u7F16\u8F91\u8FDE\u63A5 \xB7 " + id;
      el("vecPresetPick").hidden = mode !== "new";
      el("vecNameRow").hidden = mode !== "new";
      resetPresetCards("vecPresetPick");
      hideResult("vectorResult");
      hideResult("vesResult");
      setNameHelp("vecConnNameHelp", NAME_HELP, false);
      if (mode === "new") {
        el("vecConnName").value = "";
        el("vecFormQdrant").hidden = true;
        el("vecFormEs").hidden = true;
        writeFields(QD_FIELDS, null);
        writeFields(VES_FIELDS, null);
        applyQdKeyHint(null);
        applyEsKeyHint(null, "vesKeyHelp", "vesPassword");
      } else {
        var isQd = vecPreset === "qdrant";
        el("vecFormQdrant").hidden = !isQd;
        el("vecFormEs").hidden = isQd;
        if (isQd) {
          writeFields(QD_FIELDS, conn.vector);
          applyQdKeyHint(conn);
        } else {
          writeFields(VES_FIELDS, conn.es);
          applyEsKeyHint(conn, "vesKeyHelp", "vesPassword");
        }
      }
      el("saveVectorBtn").textContent = mode === "new" ? "\u52A0\u5165\u8FDE\u63A5" : "\u4FDD\u5B58\u5E76\u751F\u6548";
      el("saveVecEsBtn").textContent = mode === "new" ? "\u52A0\u5165\u8FDE\u63A5" : "\u4FDD\u5B58\u5E76\u751F\u6548";
      showModal("connModalVec");
    }
    Array.prototype.forEach.call(
      document.querySelectorAll("#vecPresetPick .preset-card"),
      function(card) {
        card.addEventListener("click", function() {
          vecPreset = card.getAttribute("data-preset");
          Array.prototype.forEach.call(
            document.querySelectorAll("#vecPresetPick .preset-card"),
            function(c) {
              c.classList.toggle("is-on", c === card);
            }
          );
          el("vecFormQdrant").hidden = vecPreset !== "qdrant";
          el("vecFormEs").hidden = vecPreset !== "es";
          el("vecConnName").focus();
        });
      }
    );
    function saveVec(btn, resultId) {
      return __async(this, null, function* () {
        var isEs = vecPreset === "es";
        var name = vecMode === "new" ? el("vecConnName").value.trim() : vecEditId;
        if (vecMode === "new") {
          if (!name) {
            setNameHelp("vecConnNameHelp", "\u5148\u7ED9\u8FD9\u4E2A\u8FDE\u63A5\u8D77\u4E2A\u540D\u5B57\u3002", true);
            el("vecConnName").focus();
            return false;
          }
          if (findConn(name)) {
            setNameHelp("vecConnNameHelp", "\u5DF2\u7ECF\u6709\u53EB\u300C" + name + "\u300D\u7684\u8FDE\u63A5\u4E86\uFF0C\u6362\u4E00\u4E2A\u3002", true);
            el("vecConnName").focus();
            return false;
          }
        }
        var conn = {
          id: name,
          name,
          usage: "vector",
          type: isEs ? "elasticsearch" : "qdrant",
          es: isEs ? readFields(VES_FIELDS) : null,
          vector: isEs ? null : readFields(QD_FIELDS)
        };
        var idx = -1;
        STATE.connections.forEach(function(c, i) {
          if (c.id === name) {
            idx = i;
          }
        });
        var prev = idx >= 0 ? STATE.connections[idx] : null;
        if (idx >= 0) {
          STATE.connections[idx] = conn;
        } else {
          STATE.connections.push(conn);
        }
        renderConns();
        var data = yield commit(function() {
          if (prev) {
            STATE.connections[idx] = prev;
          } else {
            STATE.connections.pop();
          }
        }, resultId, "\u8FDE\u63A5\u300C" + name + "\u300D\u5DF2\u4FDD\u5B58\u5E76\u751F\u6548", btn);
        if (data) {
          hideModal("connModalVec");
        }
        return !!data;
      });
    }
    function redisTargetChanged() {
      var cur = readFields(REDIS_FIELDS);
      var tag = function(src) {
        return String(src.host == null ? "" : src.host) + "|" + String(src.database == null ? "" : src.database);
      };
      return tag(STATE.redis) !== tag(cur);
    }
    function saveRedis(btn) {
      return __async(this, null, function* () {
        if (redisTargetChanged()) {
          var ok = yield Console.confirm(
            '\u26A0\uFE0F \u4F60\u4FEE\u6539\u4E86 Redis \u7684\u5730\u5740\u6216\u5E93\u53F7\uFF0C\u8FD9\u7B49\u4E8E\u6362\u4E86\u4E00\u4E2A\u6570\u636E\u6E90\u3002\n\n\u5E73\u53F0\u7684\u5386\u53F2\u4F1A\u8BDD\u8BB0\u5FC6\u3001\u4EE5\u53CA\u672A\u5B8C\u6210\u7684\u68C0\u67E5\u70B9\uFF08\u7B49\u5F85\u4EBA\u5DE5\u786E\u8BA4\u7684\u53D6\u6D88/\u9000\u6B3E\u5355\u65AD\u70B9\uFF09\u90FD\u7559\u5728\u539F\u6765\u7684\u5730\u5740/\u5E93\u53F7\u91CC\uFF0C\u4E0D\u4F1A\u81EA\u52A8\u8FC1\u79FB\u8FC7\u6765\uFF1B\u8868\u73B0\u4E3A"\u5386\u53F2\u5BF9\u8BDD\u67E5\u4E0D\u5230\u3001\u5F85\u786E\u8BA4\u7684\u65AD\u70B9\u4E22\u5931"\u3002\n\n\u786E\u8BA4\u7EE7\u7EED\u4FDD\u5B58\u5417\uFF1F',
            { title: "\u6362 Redis \u6570\u636E\u6E90", okText: "\u7EE7\u7EED\u4FDD\u5B58", danger: true }
          );
          if (!ok) {
            showResult(
              "redisResult",
              "\u5DF2\u53D6\u6D88\uFF0C\u672C\u6B21\u672A\u4FDD\u5B58\uFF08\u914D\u7F6E\u4FDD\u6301\u539F\u6837\uFF09\u3002\u6362 Redis \u524D\u8BF7\u786E\u8BA4\u65E7\u5E93\u6570\u636E\u4E0D\u518D\u9700\u8981\u3002",
              "err"
            );
            return false;
          }
        }
        var data = yield commit(null, "redisResult", "Redis \u914D\u7F6E\u5DF2\u4FDD\u5B58\u5E76\u7ACB\u5373\u751F\u6548", btn);
        return !!data;
      });
    }
    Console.renderSidebar("infra");
    el("reloadBtn").addEventListener("click", function() {
      loadInfra(false).catch(function(e) {
        setStatus("<span>\u8BFB\u53D6\u5B58\u50A8\u914D\u7F6E\u5931\u8D25\uFF1A" + Console.escapeHtml(e.message) + "</span>", "warn");
      });
    });
    el("saveRedisBtn").addEventListener("click", function() {
      saveRedis(this);
    });
    el("testRedisBtn").addEventListener("click", function() {
      testConn("redis", { redis: readFields(REDIS_FIELDS) }, "redisResult", this);
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-add-conn]"), function(btn) {
      btn.addEventListener("click", function() {
        var seg = btn.getAttribute("data-add-conn");
        if (seg === "es") {
          openEsModal("new");
        } else {
          openVecModal("new");
        }
      });
    });
    function bindTable(bodyId, seg) {
      el(bodyId).addEventListener("click", function(e) {
        return __async(this, null, function* () {
          var t = e.target;
          if (!t.getAttribute) {
            return;
          }
          var edit = t.getAttribute("data-edit");
          if (edit) {
            if (seg === "es") {
              openEsModal("edit", edit);
            } else {
              openVecModal("edit", edit);
            }
            return;
          }
          var del = t.getAttribute("data-del");
          if (!del) {
            return;
          }
          var refs = refsOf(del);
          if (refs.length) {
            Console.toast(
              "\u300C" + del + "\u300D\u6B63\u88AB\u77E5\u8BC6\u4F53 " + refs.join("\u3001") + " \u4F7F\u7528\uFF0C\u5148\u5728\u77E5\u8BC6\u4F53\u91CC\u6362\u6389\u518D\u5220",
              { type: "error" }
            );
            return;
          }
          var ok = yield Console.confirm(
            "\u786E\u5B9A\u5220\u9664\u8FDE\u63A5\u300C" + del + "\u300D\u5417\uFF1F\u5220\u9664\u540E\u7ACB\u5373\u751F\u6548\u3002",
            { title: "\u5220\u9664\u8FDE\u63A5", okText: "\u5220\u9664", danger: true }
          );
          if (!ok) {
            return;
          }
          var idx = -1;
          STATE.connections.forEach(function(c, i) {
            if (c.id === del) {
              idx = i;
            }
          });
          if (idx < 0) {
            return;
          }
          var backup = STATE.connections.slice();
          STATE.connections.splice(idx, 1);
          renderConns();
          renderProfiles();
          setStatus("<span>\u6B63\u5728\u5220\u9664\u8FDE\u63A5\u300C" + Console.escapeHtml(del) + "\u300D\u2026</span>", "warn");
          try {
            yield postJson(body());
            yield loadInfra(true);
            Console.toast("\u5DF2\u5220\u9664\u8FDE\u63A5\u300C" + del + "\u300D", { type: "success" });
          } catch (err) {
            STATE.connections = backup;
            renderConns();
            renderProfiles();
            setStatus("<span>\u5220\u9664\u5931\u8D25\uFF1A" + Console.escapeHtml(err.message) + "</span>", "warn");
          }
        });
      });
    }
    bindTable("connBodyEs", "es");
    bindTable("connBodyVec", "vector");
    el("saveEsBtn").addEventListener("click", function() {
      saveEs(this);
    });
    el("testEsBtn").addEventListener("click", function() {
      testConn("es", {
        connection: {
          id: esEditId || el("esConnName").value.trim() || "",
          type: "elasticsearch",
          es: readFields(ES_FIELDS)
        }
      }, "esResult", this);
    });
    el("clearEsBtn").addEventListener("click", function() {
      writeFields(ES_FIELDS, null);
      showResult("esResult", "\u5DF2\u6E05\u7A7A\u8868\u5355\u5185\u5BB9\uFF08\u4EC5\u672C\u9875\uFF0C\u5C1A\u672A\u751F\u6548\uFF09\u3002\u53E3\u4EE4\u7559\u7A7A\u8868\u793A\u4FDD\u6301\u4E0D\u53D8\u3002", "");
    });
    el("cancelEsBtn").addEventListener("click", function() {
      hideModal("connModalEs");
    });
    el("connModalEsClose").addEventListener("click", function() {
      hideModal("connModalEs");
    });
    el("connModalEs").addEventListener("click", function(e) {
      if (e.target === el("connModalEs")) {
        hideModal("connModalEs");
      }
    });
    el("saveVectorBtn").addEventListener("click", function() {
      saveVec(this, "vectorResult");
    });
    el("saveVecEsBtn").addEventListener("click", function() {
      saveVec(this, "vesResult");
    });
    el("testVectorBtn").addEventListener("click", function() {
      testConn("qdrant", {
        connection: {
          id: vecEditId || el("vecConnName").value.trim() || "",
          type: "qdrant",
          vector: readFields(QD_FIELDS)
        }
      }, "vectorResult", this);
    });
    el("clearVectorBtn").addEventListener("click", function() {
      writeFields(QD_FIELDS, null);
      showResult("vectorResult", "\u5DF2\u6E05\u7A7A\u8868\u5355\u5185\u5BB9\uFF08\u4EC5\u672C\u9875\uFF0C\u5C1A\u672A\u751F\u6548\uFF09\u3002Token \u7559\u7A7A\u8868\u793A\u4FDD\u6301\u4E0D\u53D8\u3002", "");
    });
    el("cancelVectorBtn").addEventListener("click", function() {
      hideModal("connModalVec");
    });
    el("cancelVecEsBtn").addEventListener("click", function() {
      hideModal("connModalVec");
    });
    el("connModalVecClose").addEventListener("click", function() {
      hideModal("connModalVec");
    });
    el("connModalVec").addEventListener("click", function(e) {
      if (e.target === el("connModalVec")) {
        hideModal("connModalVec");
      }
    });
    document.addEventListener("keydown", function(e) {
      if (e.key !== "Escape") {
        return;
      }
      if (!el("connModalEs").hidden) {
        hideModal("connModalEs");
        return;
      }
      if (!el("connModalVec").hidden) {
        hideModal("connModalVec");
      }
    });
    el("profileList").addEventListener("change", function(e) {
      var t = e.target;
      if (!t.getAttribute) {
        return;
      }
      var bodyId = t.getAttribute("data-body");
      var field = t.getAttribute("data-field");
      if (!bodyId || !field) {
        return;
      }
      var target = null;
      STATE.bodies.forEach(function(b) {
        if (b.id === bodyId) {
          target = b;
        }
      });
      if (!target) {
        return;
      }
      target[field] = t.value || null;
      renderProfiles();
      if (!target.keyword && !target.vector) {
        setStatus("<span>\u77E5\u8BC6\u4F53\u300C" + Console.escapeHtml(target.name || target.id) + "\u300D\u4E24\u8DEF\u90FD\u7A7A\u7740\uFF1A\u81F3\u5C11\u9009\u4E00\u4E2A\u8FDE\u63A5\uFF0C\u5426\u5219\u4FDD\u5B58\u4F1A\u88AB\u62D2</span>", "warn");
      }
    });
    el("profileList").addEventListener("click", function(e) {
      var t = e.target;
      if (!t.getAttribute) {
        return;
      }
      var del = t.getAttribute("data-delbody");
      if (!del) {
        return;
      }
      STATE.bodies = STATE.bodies.filter(function(b) {
        return b.id !== del;
      });
      renderProfiles();
      setStatus("<span>\u5DF2\u79FB\u9664\u77E5\u8BC6\u4F53\u300C" + Console.escapeHtml(del) + "\u300D\uFF08\u5C1A\u672A\u4FDD\u5B58\uFF09\uFF1B\u4FDD\u5B58\u540E\u4ECD\u7ED1\u7740\u5B83\u7684\u57DF\u5C06\u6CA1\u6709\u77E5\u8BC6\u5E93</span>", "warn");
    });
    el("addProfileBtn").addEventListener("click", function() {
      var n = 1;
      while (STATE.bodies.some(function(b) {
        return b.id === "kb-" + n;
      })) {
        n++;
      }
      STATE.bodies.push({ id: "kb-" + n, name: "\u77E5\u8BC6\u4F53 " + n, keyword: null, vector: null });
      renderProfiles();
    });
    el("saveProfileBtn").addEventListener("click", function() {
      var backup = JSON.parse(JSON.stringify(STATE.bodies));
      commit(function() {
        STATE.bodies = backup;
      }, null, "\u77E5\u8BC6\u4F53\u5DF2\u4FDD\u5B58\u5E76\u7ACB\u5373\u751F\u6548", null);
    });
    Console.ready(function() {
      loadInfra(false).catch(function(e) {
        setStatus("<span>\u8BFB\u53D6\u5B58\u50A8\u914D\u7F6E\u5931\u8D25\uFF1A" + Console.escapeHtml(e.message) + "</span>", "warn");
      });
    });
  })();
})();
