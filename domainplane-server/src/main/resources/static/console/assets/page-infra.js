(() => {
  // domainplane-server/frontend/src/pages/infra.js
  (function() {
    var MAP = {
      es: [
        ["esHost", "host", "s"],
        ["esPort", "port", "n"],
        ["esScheme", "scheme", "s"],
        ["esUsername", "username", "s"],
        ["esPassword", "password", "s"],
        ["esConnectTimeout", "connectTimeout", "n"],
        ["esSocketTimeout", "socketTimeout", "n"]
      ],
      redis: [
        ["redisHost", "host", "s"],
        ["redisPort", "port", "n"],
        ["redisPassword", "password", "s"],
        ["redisDatabase", "database", "n"]
      ]
    };
    var RESULT = { es: "esResult", redis: "redisResult" };
    var DIRTY = { es: "esDirty", redis: "redisDirty" };
    var BADGE = { es: "esBadge", redis: "redisBadge" };
    var LABEL = { es: "Elasticsearch", redis: "Redis" };
    var KEY_INPUT = { es: "esPassword", redis: "redisPassword" };
    var KEY_HELP = { es: "esKeyHelp", redis: "redisKeyHelp" };
    var KEY_EMPTY_HINT = { es: "\u672A\u8BBE\u7F6E\uFF08\u7559\u7A7A\u5219\u4E0D\u53D1\u9001\u8BA4\u8BC1\u5934\uFF09", redis: "\u672A\u8BBE\u7F6E\uFF08\u65E0\u5BC6\u7801\u6A21\u5F0F\uFF09" };
    var saved = { es: {}, redis: {} };
    var CONFIGURED = { es: false, redis: false };
    var SOURCE = { es: "-", redis: "-" };
    function el(id) {
      return document.getElementById(id);
    }
    function group(which) {
      var out = {};
      MAP[which].forEach(function(f) {
        var raw = el(f[0]).value.trim();
        if (f[2] === "n") {
          out[f[1]] = raw === "" ? null : Number(raw);
        } else {
          out[f[1]] = raw === "" ? null : raw;
        }
      });
      return out;
    }
    function collectAll() {
      return { es: group("es"), redis: group("redis") };
    }
    function restore(which, src) {
      MAP[which].forEach(function(f) {
        var v = src[f[1]];
        el(f[0]).value = v == null ? "" : v;
      });
    }
    function refreshDirty(which) {
      var list = which ? [which] : ["es", "redis"];
      list.forEach(function(w) {
        var dirty = JSON.stringify(group(w)) !== JSON.stringify(saved[w]);
        el(DIRTY[w]).style.display = dirty ? "inline" : "none";
      });
    }
    function setBadge(which, ok, text) {
      var node = el(BADGE[which]);
      node.textContent = text;
      node.className = "mbadge " + (ok ? "ok" : "off");
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
    function applyKeyHint(which, masked, isSet) {
      el(KEY_HELP[which]).textContent = isSet ? "\u5F53\u524D\u5DF2\u914D\u7F6E\uFF1A" + masked : "\u5F53\u524D\u672A\u914D\u7F6E";
      el(KEY_INPUT[which]).placeholder = isSet ? "\u5DF2\u914D\u7F6E\uFF0C\u7559\u7A7A\u4E0D\u53D8" : KEY_EMPTY_HINT[which];
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
    function renderStatus() {
      var esOk = CONFIGURED.es, rOk = CONFIGURED.redis;
      var msg;
      if (esOk && rOk) {
        msg = "Elasticsearch \u4E0E Redis \u5747\u5DF2\u914D\u7F6E";
      } else if (esOk) {
        msg = "Elasticsearch \u5DF2\u914D\u7F6E\uFF1BRedis \u672A\u914D\u7F6E\uFF0C\u4F1A\u8BDD\u8BB0\u5FC6\u4E0E\u4E2D\u65AD\u65AD\u70B9\u4E0D\u53EF\u7528";
      } else if (rOk) {
        msg = "Redis \u5DF2\u914D\u7F6E\uFF1BElasticsearch \u672A\u914D\u7F6E\uFF0C\u77E5\u8BC6\u5E93\u68C0\u7D22\u4E0E\u704C\u5E93\u4E0D\u53EF\u7528";
      } else {
        msg = "ES \u4E0E Redis \u5747\u672A\u914D\u7F6E\u3002\u670D\u52A1\u53EF\u4EE5\u6B63\u5E38\u542F\u52A8\uFF0C\u4F46\u77E5\u8BC6\u5E93\u4E0E\u4F1A\u8BDD\u8BB0\u5FC6\u4E0D\u53EF\u7528\uFF0C\u8BF7\u5148\u586B\u5199\u5730\u5740\u3002";
      }
      setStatus(
        "<span>" + msg + '</span><span class="pill">ES ' + (esOk ? "\u2713 " : "\u2717 ") + Console.escapeHtml(SOURCE.es) + '</span><span class="pill">Redis ' + (rOk ? "\u2713 " : "\u2717 ") + Console.escapeHtml(SOURCE.redis) + "</span>",
        esOk && rOk ? "ok" : "warn"
      );
    }
    function loadInfra() {
      setStatus("<span>\u52A0\u8F7D\u4E2D\u2026</span>", "warn");
      Console.request("/admin/infra").then(function(s) {
        el("esHost").value = s.es.host || "";
        el("esPort").value = s.es.port == null ? "" : s.es.port;
        el("esScheme").value = s.es.scheme || "";
        el("esUsername").value = s.es.username || "";
        el("esConnectTimeout").value = s.es.connectTimeout == null ? "" : s.es.connectTimeout;
        el("esSocketTimeout").value = s.es.socketTimeout == null ? "" : s.es.socketTimeout;
        el("esPassword").value = "";
        applyKeyHint("es", s.es.passwordMasked, !!s.es.passwordSet);
        el("redisHost").value = s.redis.host || "";
        el("redisPort").value = s.redis.port == null ? "" : s.redis.port;
        el("redisDatabase").value = s.redis.database == null ? "" : s.redis.database;
        el("redisPassword").value = "";
        applyKeyHint("redis", s.redis.passwordMasked, !!s.redis.passwordSet);
        el("settingsFileHint").textContent = "\u8BBE\u7F6E\u6587\u4EF6\uFF1A" + (s.settingsFile || "-") + "\uFF08\u77E5\u8BC6\u5E93\u7D22\u5F15 " + (s.indexPattern || "-") + "\uFF09";
        CONFIGURED.es = !!s.esConfigured;
        CONFIGURED.redis = !!s.redisConfigured;
        SOURCE.es = s.esSource || "-";
        SOURCE.redis = s.redisSource || "-";
        setBadge("es", CONFIGURED.es, CONFIGURED.es ? "\u5DF2\u914D\u7F6E" : "\u672A\u914D\u7F6E");
        setBadge("redis", CONFIGURED.redis, CONFIGURED.redis ? "\u5DF2\u914D\u7F6E" : "\u672A\u914D\u7F6E");
        renderStatus();
        saved.es = group("es");
        saved.redis = group("redis");
        refreshDirty();
      }).catch(function(e) {
        setStatus("<span>\u8BFB\u53D6\u5B58\u50A8\u914D\u7F6E\u5931\u8D25\uFF1A" + Console.escapeHtml(e.message) + "</span>", "warn");
      });
    }
    function testConnection(which, btn) {
      btn.disabled = true;
      showResult(RESULT[which], "\u6D4B\u8BD5\u4E2D\u2026\uFF08\u4F1A\u771F\u5B9E\u5EFA\u7ACB\u4E00\u6B21\u8FDE\u63A5\uFF0C\u5E76\u63A2\u6D4B\u7248\u672C\u4E0E\u80FD\u529B\uFF09", "");
      Console.jsonRequest("/admin/infra/test?type=" + encodeURIComponent(which), "POST", collectAll()).then(function(data) {
        if (data.success) {
          showHtmlResult(
            RESULT[which],
            which === "redis" ? renderRedisProbe(data) : renderEsProbe(data),
            "ok"
          );
        } else {
          console.debug("[domainplane] \u8FDE\u63A5\u6D4B\u8BD5\u5931\u8D25\uFF1A", data.error);
          showResult(RESULT[which], "\u8FDE\u63A5\u5931\u8D25\uFF1A" + Console.sanitizeError(data.error || "\u672A\u77E5\u539F\u56E0"), "err");
        }
      }).catch(function(e) {
        console.debug("[domainplane] \u8FDE\u63A5\u6D4B\u8BD5\u5931\u8D25\uFF1A", e);
        showResult(RESULT[which], "\u8FDE\u63A5\u5931\u8D25\uFF1A\u65E0\u6CD5\u8BBF\u95EE\u7BA1\u63A7\u63A5\u53E3", "err");
      }).then(function() {
        btn.disabled = false;
      });
    }
    function clearForm(which) {
      restore(which, {});
      refreshDirty(which);
      showResult(
        RESULT[which],
        "\u5DF2\u6E05\u7A7A\u300C" + LABEL[which] + "\u300D\u8868\u5355\u5185\u5BB9\uFF08\u4EC5\u672C\u9875\uFF0C\u5C1A\u672A\u751F\u6548\uFF09\u3002\u70B9\u300C\u4FDD\u5B58\u5E76\u751F\u6548\u300D\u540E\uFF0C\u7559\u7A7A\u5B57\u6BB5\u4F1A\u56DE\u843D\u5230\u670D\u52A1\u7AEF yaml \u914D\u7F6E\uFF1B\u53E3\u4EE4\u7559\u7A7A\u8868\u793A\u4FDD\u6301\u4E0D\u53D8\u3002",
        ""
      );
    }
    function buildPayload(which) {
      var all = collectAll();
      all[which === "es" ? "redis" : "es"] = saved[which === "es" ? "redis" : "es"];
      return all;
    }
    function redisTargetChanged() {
      var cur = group("redis");
      return String(saved.redis.host == null ? "" : saved.redis.host) !== String(cur.host == null ? "" : cur.host) || String(saved.redis.database == null ? "" : saved.redis.database) !== String(cur.database == null ? "" : cur.database);
    }
    function afterSaved(which, data) {
      el(KEY_INPUT[which]).value = "";
      saved[which] = group(which);
      refreshDirty(which);
      applyKeyHint(which, data[which + "PasswordMasked"], !!data[which + "PasswordSet"]);
      CONFIGURED[which] = which === "es" ? !!data.esConfigured : !!data.redisConfigured;
      SOURCE[which] = (which === "es" ? data.esSource : data.redisSource) || SOURCE[which];
      setBadge(which, CONFIGURED[which], CONFIGURED[which] ? "\u5DF2\u914D\u7F6E" : "\u672A\u914D\u7F6E");
      renderStatus();
    }
    function saveInfra(which, btn) {
      if (which === "redis" && redisTargetChanged()) {
        var ok = window.confirm(
          '\u26A0\uFE0F \u4F60\u4FEE\u6539\u4E86 Redis \u7684\u5730\u5740\u6216\u5E93\u53F7\uFF0C\u8FD9\u7B49\u4E8E\u6362\u4E86\u4E00\u4E2A\u6570\u636E\u6E90\u3002\n\n\u5E73\u53F0\u7684\u5386\u53F2\u4F1A\u8BDD\u8BB0\u5FC6\u3001\u4EE5\u53CA\u672A\u5B8C\u6210\u7684\u68C0\u67E5\u70B9\uFF08\u7B49\u5F85\u4EBA\u5DE5\u786E\u8BA4\u7684\u53D6\u6D88/\u9000\u6B3E\u5355\u65AD\u70B9\uFF09\u90FD\u7559\u5728\u539F\u6765\u7684\u5730\u5740/\u5E93\u53F7\u91CC\uFF0C\u4E0D\u4F1A\u81EA\u52A8\u8FC1\u79FB\u8FC7\u6765\uFF1B\u8868\u73B0\u4E3A"\u5386\u53F2\u5BF9\u8BDD\u67E5\u4E0D\u5230\u3001\u5F85\u786E\u8BA4\u7684\u65AD\u70B9\u4E22\u5931"\u3002\n\n\u786E\u8BA4\u7EE7\u7EED\u4FDD\u5B58\u5417\uFF1F'
        );
        if (!ok) {
          showResult(
            RESULT.redis,
            "\u5DF2\u53D6\u6D88\uFF0C\u672C\u6B21\u672A\u4FDD\u5B58\uFF08\u914D\u7F6E\u4FDD\u6301\u539F\u6837\uFF09\u3002\u6362 Redis \u524D\u8BF7\u786E\u8BA4\u65E7\u5E93\u6570\u636E\u4E0D\u518D\u9700\u8981\u3002",
            "err"
          );
          return;
        }
      }
      btn.disabled = true;
      showResult(RESULT[which], "\u4FDD\u5B58\u4E2D\u2026", "");
      Console.jsonRequest("/admin/infra", "POST", buildPayload(which)).then(function(data) {
        if (!data.success) {
          showResult(RESULT[which], "\u274C \u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(data.error || "\u672A\u77E5\u539F\u56E0"), "err");
          return;
        }
        afterSaved(which, data);
        var msg = "\u2705 \u300C" + LABEL[which] + "\u300D\u5DF2\u4FDD\u5B58\u5E76\u7ACB\u5373\u751F\u6548\uFF08\u6765\u6E90\uFF1A" + (which === "es" ? data.esSource : data.redisSource) + "\uFF09\u3002";
        showResult(RESULT[which], msg, "ok");
      }).catch(function(e) {
        showResult(RESULT[which], "\u274C \u8BF7\u6C42\u5931\u8D25\uFF1A" + e.message, "err");
      }).then(function() {
        btn.disabled = false;
      });
    }
    Console.renderSidebar("infra");
    var ALL_INPUTS = MAP.es.concat(MAP.redis).map(function(f) {
      return f[0];
    });
    ALL_INPUTS.forEach(function(id) {
      el(id).addEventListener("input", function() {
        refreshDirty();
      });
    });
    el("reloadBtn").addEventListener("click", loadInfra);
    el("testEsBtn").addEventListener("click", function() {
      testConnection("es", this);
    });
    el("testRedisBtn").addEventListener("click", function() {
      testConnection("redis", this);
    });
    el("clearEsBtn").addEventListener("click", function() {
      clearForm("es");
    });
    el("clearRedisBtn").addEventListener("click", function() {
      clearForm("redis");
    });
    el("saveEsBtn").addEventListener("click", function() {
      saveInfra("es", this);
    });
    el("saveRedisBtn").addEventListener("click", function() {
      saveInfra("redis", this);
    });
    Console.ready(loadInfra);
  })();
})();
