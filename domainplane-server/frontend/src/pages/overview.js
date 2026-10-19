/* ==========================================================================
   P1 · overview 页脚本
   自 overview.html 的内联 <script> 机械平移（1 块合并），逻辑未改。
   ========================================================================== */
(function () {
/* 工具数与域数都取自 /admin/domains（与「域空间」页同一口径）。
       刻意不在前端自己按 tools[].domains 去重统计，那样就多了一个"可见性判据"副本，
       一旦与 ToolRegistry 的过滤条件不一致，概览与域空间会各说各话。

       「0」要读成"未注册"而不是"出错了"：这两件事的处置完全不同（前者去写工具注解，
       后者查扫描），所以 0 一律配 warn 态与一句明确的未注册说明，不留光秃秃的 0。 */
    function loadRegistryStats() {
        var tools = document.getElementById('statTools');
        var domains = document.getElementById('statDomains');
        /* P2：/admin/domains 是 D 类裸对象，api.get 直接返回 data */
        Console.api.get('/admin/domains').then(function (r) {
            var s = (r && r.stats) || {};
            var toolCount = s.toolCount || 0;
            var domainCount = s.domainCount || 0;

            tools.textContent = toolCount;
            tools.className = 'v' + (toolCount ? ' ok' : ' warn');
            document.getElementById('statToolsNote').textContent = toolCount
                ? ('其中 ' + (s.globalToolCount || 0) + ' 个挂在根域（全域可见）')
                : '未注册：没有扫描到任何工具提供者';

            domains.textContent = domainCount;
            domains.className = 'v' + (domainCount ? ' ok' : ' warn');
            document.getElementById('statDomainsNote').textContent = domainCount
                ? ('根域 ' + (s.builtinCount || 0) + ' · 人工 ' + (s.manualCount || 0)
                   + ' · 派生 ' + (s.derivedCount || 0))
                : '未注册：没有任何域';
        }).catch(function (e) {
            tools.textContent = '—';
            tools.className = 'v err';
            document.getElementById('statToolsNote').textContent = '读取失败';
            domains.textContent = '—';
            domains.className = 'v err';
            document.getElementById('statDomainsNote').textContent = e.message;
        });
    }

    function loadModelStats() {
        var chat = document.getElementById('statChat');
        var emb = document.getElementById('statEmb');
        Console.refreshModelsWarn().then(function (s) {
            chat.textContent = s.chatConfigured ? '已就绪' : '未配置';
            chat.className = 'v ' + (s.chatConfigured ? 'ok' : 'warn');
            document.getElementById('statChatNote').textContent =
                s.chatConfigured ? (s.chatModelName || '') : '请到「模型设置」添加模型档案';

            /* 三态而不是两态：已就绪 / 未启用（有档案，等一次「启用」）/ 未配置（一个档案都没有）。
               把"有可用档案但没启用"报成"未配置"，会让人跑去重配一遍已经配好的东西；
               而未配置与未启用要给的指引本就不同。 */
            var embReady = !!s.embeddingConfigured;
            var embRegistered = s.embeddingProfileCount || 0;
            emb.textContent = embReady ? '已就绪' : (embRegistered ? '未启用' : '未配置');
            emb.className = 'v ' + (embReady ? 'ok' : 'warn');
            document.getElementById('statEmbNote').textContent = embReady
                ? (s.embeddingModelName || '')
                : (embRegistered
                    ? ('已登记 ' + embRegistered + ' 个向量档案，请到「模型设置」启用')
                    : '知识库检索与灌库不可用');
        }).catch(function (e) {
            chat.textContent = '读取失败';
            chat.className = 'v err';
            emb.textContent = '读取失败';
            emb.className = 'v err';
            document.getElementById('statChatNote').textContent = e.message;
        });
    }

    Console.renderSidebar('overview');
    /* 数据加载走 Console.ready：先确认登录态，未登录直接跳登录页，
       不会先打一轮受保护接口留下 401 日志（见 console.js ready 的注释） */
    Console.ready(function () {
        loadModelStats();
        loadRegistryStats();
    });
})();
