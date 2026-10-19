(() => {
  // domainplane-server/frontend/src/pages/overview.js
  (function() {
    function loadRegistryStats() {
      var tools = document.getElementById("statTools");
      var domains = document.getElementById("statDomains");
      Console.request("/admin/domains").then(function(r) {
        var s = r && r.stats || {};
        var toolCount = s.toolCount || 0;
        var domainCount = s.domainCount || 0;
        tools.textContent = toolCount;
        tools.className = "v" + (toolCount ? " ok" : " warn");
        document.getElementById("statToolsNote").textContent = toolCount ? "\u5176\u4E2D " + (s.globalToolCount || 0) + " \u4E2A\u6302\u5728\u6839\u57DF\uFF08\u5168\u57DF\u53EF\u89C1\uFF09" : "\u672A\u6CE8\u518C\uFF1A\u6CA1\u6709\u626B\u63CF\u5230\u4EFB\u4F55\u5DE5\u5177\u63D0\u4F9B\u8005";
        domains.textContent = domainCount;
        domains.className = "v" + (domainCount ? " ok" : " warn");
        document.getElementById("statDomainsNote").textContent = domainCount ? "\u6839\u57DF " + (s.builtinCount || 0) + " \xB7 \u4EBA\u5DE5 " + (s.manualCount || 0) + " \xB7 \u6D3E\u751F " + (s.derivedCount || 0) : "\u672A\u6CE8\u518C\uFF1A\u6CA1\u6709\u4EFB\u4F55\u57DF";
      }).catch(function(e) {
        tools.textContent = "\u2014";
        tools.className = "v err";
        document.getElementById("statToolsNote").textContent = "\u8BFB\u53D6\u5931\u8D25";
        domains.textContent = "\u2014";
        domains.className = "v err";
        document.getElementById("statDomainsNote").textContent = e.message;
      });
    }
    function loadModelStats() {
      var chat = document.getElementById("statChat");
      var emb = document.getElementById("statEmb");
      Console.refreshModelsWarn().then(function(s) {
        chat.textContent = s.chatConfigured ? "\u5DF2\u5C31\u7EEA" : "\u672A\u914D\u7F6E";
        chat.className = "v " + (s.chatConfigured ? "ok" : "warn");
        document.getElementById("statChatNote").textContent = s.chatConfigured ? s.chatModelName || "" : "\u8BF7\u5230\u300C\u6A21\u578B\u8BBE\u7F6E\u300D\u6DFB\u52A0\u6A21\u578B\u6863\u6848";
        var embReady = !!s.embeddingConfigured;
        var embRegistered = s.embeddingProfileCount || 0;
        emb.textContent = embReady ? "\u5DF2\u5C31\u7EEA" : embRegistered ? "\u672A\u542F\u7528" : "\u672A\u914D\u7F6E";
        emb.className = "v " + (embReady ? "ok" : "warn");
        document.getElementById("statEmbNote").textContent = embReady ? s.embeddingModelName || "" : embRegistered ? "\u5DF2\u767B\u8BB0 " + embRegistered + " \u4E2A\u5411\u91CF\u6863\u6848\uFF0C\u8BF7\u5230\u300C\u6A21\u578B\u8BBE\u7F6E\u300D\u542F\u7528" : "\u77E5\u8BC6\u5E93\u68C0\u7D22\u4E0E\u704C\u5E93\u4E0D\u53EF\u7528";
      }).catch(function(e) {
        chat.textContent = "\u8BFB\u53D6\u5931\u8D25";
        chat.className = "v err";
        emb.textContent = "\u8BFB\u53D6\u5931\u8D25";
        emb.className = "v err";
        document.getElementById("statChatNote").textContent = e.message;
      });
    }
    Console.renderSidebar("overview");
    Console.ready(function() {
      loadModelStats();
      loadRegistryStats();
    });
  })();
})();
