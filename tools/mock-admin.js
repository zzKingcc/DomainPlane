/* 视觉验收专用 init script：拦截 /admin/ 接口返回假数据，避免 401 跳登录页。
   仅用于本地截图核对布局，不参与任何提交。 */
(function () {
    var orig = window.fetch;
    var fake = {
        code: 0, success: true, initialized: true, authenticated: true, username: 'visual-check',
        domains: [], instances: [], prompts: [], documents: [], connections: [], profiles: [],
        items: [], messages: [], tools: [], toolsOfInstance: [], chats: [], stats: [], count: 0
    };
    /* 一份样例档案：模型卡片要渲染出来，截图才看得到卡片里的控件
       （「上下文长度」输入框、思考开关这些都在卡片正文里）。 */
    var sampleProfile = {
        alias: 'gpt-4o-mini', modelName: 'gpt-4o-mini', provider: 'openai',
        baseUrl: 'https://api.openai.com/v1', endpoints: ['chat'],
        input: ['text'], output: ['text'],
        bindable: true, bindableFamilies: ['chat'], usedByDomains: ['default'],
        usable: true, contextWindow: 128000, returnThinking: false
    };
    /* 一条样例 MCP 连接：卡片头上一排 7 个小按钮，是全站最挤的一处按钮行，
       视觉验收要看它们换行后齐不齐。 */
    var sampleConn = {
        name: 'weather-mcp', url: 'https://mcp.example.com/sse', transport: 'sse',
        enabled: true, ready: true, toolCount: 6, approvalPolicy: 'allow',
        needsAuth: true, hasHeaders: true, oauthClientId: 'demo-client',
        hasOauthClientSecret: false, lastError: ''
    };
    window.fetch = function (input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        if (url.indexOf('/admin/') >= 0) {
            var body = fake;
            if (url.indexOf('/admin/model-profiles/detect') >= 0) {
                /* 模拟一次探测的返回：新版 note 就是「支持 / 不支持 / 未确认」三行清单 */
                body = {
                    code: 0, success: true,
                    note: '实测确认：端点族 [chat]；输入 [text, image] → 输出 [text]。\n'
                        + '支持：对话（文本）、图片输入\n'
                        + '不支持：音频输入\n'
                        + '未确认：视频输入、文件输入、生图、语音合成、语音识别、向量化、重排\n'
                        + '（输出视频无通用探测端点，需手动声明。）',
                    perModality: {
                        'chat-text': 'CONFIRMED', 'chat-image-in': 'CONFIRMED',
                        'chat-audio-in': 'UNSUPPORTED', 'chat-video-in': 'UNCONFIRMED',
                        'chat-file-in': 'UNCONFIRMED', 'images-gen': 'UNCONFIRMED',
                        'tts': 'UNCONFIRMED', 'asr': 'UNCONFIRMED',
                        'embedding': 'UNCONFIRMED', 'rerank': 'UNCONFIRMED'
                    },
                    identified: true, bindable: true
                };
            } else if (url.indexOf('/admin/mcp-bindings') >= 0) {
                body = {
                    code: 0, success: true, domainBindings: {},
                    connectionBindings: { 'weather-mcp': ['default'] }
                };
            } else if (url.indexOf('/admin/mcp-connections') >= 0) {
                body = {
                    code: 0, success: true, connections: [sampleConn]
                };
            } else if (url.indexOf('/admin/domains') >= 0) {
                body = {
                    code: 0, success: true, domains: [{ name: 'default' }]
                };
            } else if (url.indexOf('/admin/model-profiles') >= 0) {
                body = {
                    code: 0, success: true, profiles: [sampleProfile],
                    domainBindings: { 'default': { chat: ['gpt-4o-mini'] } }
                };
            }
            return Promise.resolve(new Response(JSON.stringify(body), {
                status: 200, headers: { 'Content-Type': 'application/json' }
            }));
        }
        return orig.apply(this, arguments);
    };
})();
