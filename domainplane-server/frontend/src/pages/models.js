/* ==========================================================================
   P1 · models 页脚本
   自 models.html 的内联 <script> 机械平移（1 块合并），逻辑未改。
   ========================================================================== */
(function () {
/* ============ 模型设置 ============
   一张统一档案表 + 一个槽位区：
     · 用途分配 —— 全局单选的向量槽，下拉列出所有声明了 embedding 的档案
     · 全部档案 —— 一张表列完，能力看端点族小签，占用看槽位与「已启用」徽标
   添加时只由用户手选输入 / 输出模态，保存原样落盘、零上游请求、不猜类型；
   端点族由服务端按这组模态做本地反查得到，一个都不选就留空（卡片不可用）。
   卡片上的「探测」是唯一花 token 的入口，二次确认后实测结果覆盖手选模态。
   域 → 对话 / 能力族的绑定在「域空间」页操作。 */
Console.renderSidebar('models');

    var PROFILES = [];
    /* 预置成"未配置"而不是 null：loadSettings 与 loadProfiles 是并发发出的，谁先回来都会
       调 renderProfiles，而它会读 EMBEDDING.alias。置 null 时先到的那个请求会抛 TypeError，
       又因 loadSettings 的 catch 只打 console.debug 而被静默吞掉，表现为整段渲染不出来。 */
    var EMBEDDING = { alias: null };   /* 当前启用的向量档案别名；null = 一个都没启用 */
    var SETTINGS = null;               /* /admin/settings：对话 / 向量 两组部署配置（yaml 兜底） */
    /* 添加弹窗里手选的模态。两组都空 = 一个都没选，照常保存但卡片不可用。 */
    var PICKED_INPUT = [];
    var PICKED_OUTPUT = [];

    var INPUT_MODS = ['text', 'image', 'audio', 'video', 'file'];
    var OUTPUT_MODS = ['text', 'image', 'audio', 'video', 'file', 'embedding', 'score'];
    var EP_LABEL = { chat: '对话', embedding: '向量', rerank: '重排', images: '生图',
                     tts: '语音合成', asr: '语音识别', video: '视频' };
    var MOD_LABEL = { text: '文本', image: '图片', audio: '音频', video: '视频',
                      file: '文件', embedding: '向量', score: '相关性分数' };


    function pfEl(id) { return document.getElementById(id); }

    function val(id) { var v = pfEl(id).value.trim(); return v === '' ? null : v; }

    /* ---------- 加载 ---------- */
    function loadSettings() {
        return Console.api.get('/admin/settings').then(function (s) {
            SETTINGS = s;
            Console.setModelsWarn(!s.chatConfigured);
            renderProfiles();
        }).catch(function (e) {
            console.debug('[domainplane] 读取设置失败：', e);
        });
    }

    /* HTML 与 src 是两棵树，提示元素可能还没同步到页面（历史上就漏过
       settingsFileHint / esFootHint 这类 hint）。这里必须空判：
       直接 pfEl(...).textContent 在元素缺失时抛 TypeError，会把整页打成"加载失败"。 */
    function setHint(id, value) {
        var el = pfEl(id);
        if (el) { el.textContent = value || '—'; }
    }

    function loadProfiles() {
        return Console.api.get('/admin/model-profiles').then(function (r) {
            /* 全量一张表：向量 / 对话 / 生图档案都在这里，不再有第二段向量列表。
               槽位读 assignments.embedding（顶层 embeddingAlias 只是给旧预览桩的兼容字段）。 */
            PROFILES = (r && r.profiles) || [];
            var asg = (r && r.assignments) || {};
            EMBEDDING = { alias: asg.embedding || (r && r.embeddingAlias) || null };
            /* 「配置文件」提示指向档案真正落盘的文件（models.json）：
               /admin/settings 回的 llm-settings.json 是 yaml 兜底设置，不是档案，
               照它显示会让人去编辑一个本页根本不写的文件。 */
            setHint('settingsFileHint', r && r.settingsFile);
            renderProfiles();
        }).catch(function (e) {
            pfEl('modelList').innerHTML = '<div class="dp-card" style="color:var(--red);">'
                + '加载失败：' + Console.escapeHtml(e.message) + '</div>';
            /* 下拉不能停在"上次的值"上：加载失败时那份数据已经不可信 */
            var sel = pfEl('embeddingSlot');
            sel.innerHTML = '<option value="">加载失败</option>';
            sel.disabled = true;
            setHint('embeddingSlotState', '—');
        });
    }

    /* ---------- 渲染 ---------- */
    function kv(k, v) { return '<div><span class="k">' + k + '</span>' + v + '</div>'; }
    function monoOrNone(v) {
        return v ? '<span class="mono">' + Console.escapeHtml(v) + '</span>'
                 : '<span class="perm-none">未配置</span>';
    }
    /* 卡片头统一为「模型：<模型名>」，不再区分对话 / 向量 */
    function modelTitle(name) { return '模型：' + monoOrNone(name); }

    /* 小标渲染：只列探到的（不显示未支持的） */
    function tagList(list, labelMap) {
        return (list || []).map(function (k) {
            return '<span class="tag public">' + Console.escapeHtml(labelMap[k] || k) + '</span>';
        }).join('');
    }

    /* 卡片头部：端点族 + 布尔能力。
       输入 / 输出模态改由 meta 那两行完整列出，头部标签位有限，多模态模型
       （如输入 text+image、输出 text+image）在头部容易被挤掉或看着像"只有文本"，
       而"能吃什么 / 产出什么"恰恰是挑模型时最该看的信息。

       端点族标签做成可点的「移除」入口：探测从不写「不支持」，历史误标就永远清不掉；
       自动撤销风险太大（一次 400 就把能力删了），所以让用户明说一次。 */
    function cardTags(p) {
        return (p.endpoints || []).map(function (k) {
            return '<button type="button" class="tag public tag-drop" title="移除「'
                + Console.escapeHtml(EP_LABEL[k] || k) + '」这个能力"'
                + ' data-drop-family="' + Console.escapeHtml(k) + '"'
                + ' data-drop-alias="' + Console.escapeHtml(p.alias) + '">'
                + Console.escapeHtml(EP_LABEL[k] || k)
                + '<span class="tag-x">×</span></button>';
        }).join('');
    }

    /* 同名模型（两家服务商各配一份）自动编号：别名 = 模型名[-N]，卡片上显示 #N */
    function aliasBase(modelName) {
        return (modelName || '').trim().replace(/\s+/g, '-') || 'model';
    }
    function dupNo(p) {
        var base = aliasBase(p.modelName);
        var alias = (p.alias || '').trim();
        if (alias === base) { return 0; }
        var m = alias.match(/-(\d+)$/);
        return (m && alias.slice(0, m.index) === base) ? parseInt(m[1], 10) : 0;
    }
    function dupBadge(p) {
        var n = dupNo(p);
        return n > 1 ? '<span class="mbadge mono">#' + n + '</span>' : '';
    }

    /* 「可看图」徽标 —— 视觉能力的唯一标识位。
       判据就是 input 含 image（后端 ModelProfile.MOD_IMAGE），不另立字段：
       输入模态本来就承载这个语义，再加一个 supportsVision 只会两处口径迟早不一致。
       放头部而不只靠 meta 那行小签：挑模型时"能不能看图"是最该先看到的信息，
       而多模态档案在 meta 行里与"文本"并排，扫一眼就过去了。
       半识别档案（类型已知、模态没读到）不显示，它连"能吃什么"都还不知道，
       这时盖一个"可看图"是谎报。 */
    function isVisionProfile(p) {
        return (p.input || []).indexOf('image') >= 0;
    }
    function visionBadge(p) {
        return isVisionProfile(p) ? '<span class="mbadge ok">可看图</span>' : '';
    }

    /* 半识别：类型已知（endpoints 非空）但模态为空，绑不了域
       （domainBindable() 要求模态齐备）。这种档案卡片上原来只显示「—」，
       分不清"服务商没给"还是"模型就是没有"，而两者对应的动作完全不同。 */
    function halfIdentified(p) {
        return !!(p.endpoints || []).length && !(p.input || []).length;
    }
    /* 模态单元格：探到了列小签；半识别时给一句"去探测"而不是干一个「—」，
       chat 探针必落 text→text 基线，探一次就能补齐，所以要把动作说到眼前。 */
    function modalityCell(p, list) {
        var tags = tagList(list, MOD_LABEL);
        if (tags) { return tags; }
        return halfIdentified(p)
            ? '<span class="perm-none">未识别 · 点「探测」补齐</span>'
            : '<span class="perm-none">—</span>';
    }

    /* ============ 槽位与统一卡片 ============ */
    /* 按别名找一份档案：服务端现在只有一张表（向量 / 对话 / 生图都在 PROFILES 里），
       不再需要"两处都查"的补丁 —— 那正是物理分桶时代「向量卡片丢了 API Key」
       「删除时不提示它正是向量模型」这类回归的来源。 */
    function findProfile(alias) {
        if (!alias) { return null; }
        return PROFILES.filter(function (p) { return p.alias === alias; })[0] || null;
    }
    function isEmbeddingProfile(p) {
        return (p.endpoints || []).indexOf('embedding') >= 0;
    }
    /* 能不能绑到域 —— 由服务端下发（profile.bindable，口径 = ModelProfile#domainBindable()：
       类型已知 + 模态已知 + 能当对话模型用）。前端不再自己实现谓词：两边各算一次必然漂移，
       口径一变就出现"页面说能绑、后端却拒绝"（或反之）。未识别的档案靠卡片上的「探测」补齐。 */
    function isChatProfile(p) {
        return p.bindable === true;
    }

    /* 卡片头部的「是否回写思考内容」开关。
       落 ModelProfile#returnThinking（档案级，不是调优 params 里的私有参数）：
       关 = 框架直接丢弃 reasoning_content，思考内容到不了调用者手里；
       开 = 经 onPartialThinking → THINKING 帧下发，由消费方决定怎么显示。
       模型侧跑不跑思考由服务商决定（DeepSeek 系默认返回思维链），本开关只管回传。
       视觉复用调优框里布尔参数那套轨道 + 滑块（.tune-switch），只外挂一行说明文字；
       null（未声明）与 false 一样显示关闭，两者对调用者的后果相同。 */
    function thinkSwitch(p) {
        /* 结构上内层套一个 .tune-switch：藏起来的 input 与轨道 / 滑块的样式都挂在那个类上，
           这里只借它的视觉，不重复第二套开关 CSS。 */
        return '<label class="think-switch">'
            + '<span class="tune-switch">'
            + '<input type="checkbox" data-think="' + Console.escapeHtml(p.alias) + '"'
            + (p.returnThinking === true ? ' checked' : '') + '>'
            + '<span class="tune-switch-track"><span class="tune-switch-knob"></span></span>'
            + '</span>'
            + '<span class="think-switch-label">是否回写思考内容</span>'
            + '</label>';
    }

    /* 一张统一档案卡片：所有档案同一套骨架，差异只是「声明了哪些用途」与「占没占槽位」。
       向量档案多出「启用」按钮；对话档案多出思考回写开关与自定义参数框。
       两张卡片合成一张后不需要再去重：档案只出现一次，槽位状态用徽标表达。 */
    function modelCard(p) {
        var active = p.alias === EMBEDDING.alias;
        var chat = isChatProfile(p);
        var isEmb = isEmbeddingProfile(p);
        /* 两个参数入口出不出，由服务端给同一个结论（customParams.applies = mayBeChat()）。
           图像 / 重排 / 向量档案不给框：值根本到不了请求体，渲染了只会在保存时被 /tuning 拒。 */
        var tuneable = !!(p.customParams && p.customParams.applies);

        /* 「启用」只在声明了 embedding 且当前没占槽位的档案上出现；
           置灰与槽位下拉同一判据（缺地址 / Key / 模型名）。 */
        var enableBtn = (isEmb && !active)
            ? '<button class="btn btn-sm btn-outline-primary" data-enable="' + Console.escapeHtml(p.alias) + '"'
                + (p.usable ? '' : ' disabled') + '>启用</button>'
            : '';
        /* 类型未声明要显眼：这种档案绑不了域、也当不了向量模型。
           以前"空端点族"被默认补成 chat，于是它看着像对话模型却永远调不通。 */
        var noType = !(p.endpoints || []).length
            ? '<span class="mbadge off">未声明类型</span>' : '';
        /* 域绑定对四类档案都成立：主模型绑 chat 桶，生图 / 语音 / 视频绑各自的能力桶，
           两种绑法都算「在用」。能绑哪几段由服务端下发（bindableFamilies），这里只按它判
           有没有可绑之处，不自己实现谓词。 */
        var used;
        if ((p.usedByDomains || []).length) {
            used = p.usedByDomains.map(function (d) {
                return '<span class="tag perm">' + Console.escapeHtml(d) + '</span>';
            }).join('');
        } else if ((p.bindableFamilies || []).length) {
            used = '<span class="perm-none">未绑定任何域</span>';
        } else {
            used = '<span class="perm-none">不参与域绑定</span>';
        }
        /* 思考回写开关只出现在对话档案上（思考是对话流的概念）。 */
        var thinkCtl = chat ? thinkSwitch(p) : '';
        var paramsBox = tuneable ? customParamsBox(p) : '';

        return '<section class="dp-card model-card' + (isEmb ? ' emb' : '') + '">'
            + '<header class="card-head">'
            +   '<span class="model-dot"></span>'
            +   '<h2>' + modelTitle(p.modelName || p.alias) + '</h2>'
            +   dupBadge(p)
            +   noType
            +   visionBadge(p)
            +   cardTags(p)
            +   (active ? '<span class="mbadge ok">已启用</span>' : '')
            /* declared=false：添加时一个模态都没选，端点族与模态全空。卡片照存，
               但绑不了域也进不了向量槽 —— 明确标「不可用」，等用户点「探测」。
               p.declared 由服务端下发；老视图没这个字段时按已声明处理。 */
            +   ((p.usable && p.declared !== false) ? '' : '<span class="mbadge off">不可用</span>')
            +   '<span class="spacer"></span>'
            +   thinkCtl
            +   enableBtn
            +   '<button class="btn btn-sm btn-outline-secondary" data-probe="'
            +       Console.escapeHtml(p.alias) + '">探测</button>'
            +   '<button class="btn btn-sm btn-outline-danger" data-del="'
            +       Console.escapeHtml(p.alias) + '">删除</button>'
            + '</header>'
            + '<div class="dp-card-body">'
            + '<div class="model-meta">'
            +   '<div><span class="k">输入模态</span>' + modalityCell(p, p.input) + '</div>'
            +   '<div><span class="k">输出模态</span>' + modalityCell(p, p.output) + '</div>'
            +   kv('服务商地址', '<span class="mono">'
                    + Console.escapeHtml(p.baseUrl || '-') + '</span>')
            +   (p.apiKeyMasked
                    ? kv('API Key', '<span class="mono">' + Console.escapeHtml(p.apiKeyMasked) + '</span>')
                    : '')
            +   '<div><span class="k">被哪些域使用</span>' + used + '</div>'
            + '</div>'
            +   paramsBox
            + '</div>'
            + '</section>';
    }

    /* 用途分配：全局唯一的向量槽。
       下拉只列「声明了 embedding 的档案」——服务端 setEmbeddingAlias 也只认这些，
       把没声明的摆进去只会让用户点了之后收到一句拒绝。不可用的置灰（缺地址 / Key / 模型名）。
       「未启用」是一个真实选项，用它即可取消配置，不必再另找清空按钮。 */
    function renderSlots() {
        var sel = pfEl('embeddingSlot');
        var choices = PROFILES.filter(isEmbeddingProfile);
        /* 「未启用」只是当前状态的占位（不可选）：下拉的职责是"选一个向量档案"。
           不提供取消入口是刻意的 —— 已灌库时取消会走到 rebuild，而没有模型可算向量，
           那条路在服务端会失败；本页不引入一个必然踩坑的动作。 */
        var lead = EMBEDDING.alias ? '' : '<option value="" disabled>未启用</option>';
        sel.innerHTML = lead + choices.map(function (p) {
            return '<option value="' + Console.escapeHtml(p.alias) + '"'
                + (p.usable ? '' : ' disabled') + '>'
                + Console.escapeHtml(p.modelName || p.alias) + '</option>';
        }).join('');
        sel.value = EMBEDDING.alias || '';
        /* 一个候选都没有时把下拉锁住，别让人点开一个空列表 */
        sel.disabled = !choices.length;
        var state;
        if (!choices.length) {
            state = '还没有声明「向量」用途的档案';
        } else if (!EMBEDDING.alias) {
            state = '未启用 · 可选 ' + choices.length + ' 个';
        } else {
            var cur = findProfile(EMBEDDING.alias);
            state = '已启用 · ' + ((cur && cur.modelName) || EMBEDDING.alias);
        }
        setHint('embeddingSlotState', state);
    }

    /* 换向量模型共用的二次确认。
       只有服务端判定「向量身份真变了」（地址 / 模型名 / 维度任一）时才会走到这里 ——
       换档案但模型没变（删了重建同一个模型、别名被加 -2）服务端直接切过去，不弹框。
       返回 'rebuild'（保留原文重灌）、'purge'（丢弃旧内容）或 null（本次不做任何改动）。
       确认框改成 Tabler 模态后是异步的（要等用户点），所以这个函数返回 Promise。 */
    async function askRebuildOrPurge(message) {
        var rebuild = await Console.confirm(message
            + '\n\n现在用新的向量把知识库重新算一遍吗？文档都还在，不用重新上传。',
            { title: '换向量模型：重灌知识库', okText: '重新算一遍', cancelText: '不重灌', danger: false });
        if (rebuild) { return 'rebuild'; }
        var purge = await Console.confirm(
            '清空知识库？\n\n'
            + '里面的文档和向量会被全部删除，以后要重新上传。',
            { title: '换向量模型：清空知识库', okText: '清空', danger: true });
        return purge ? 'purge' : null;
    }

    /* 启用向量模型：有已灌库内容时先要用户表态（重灌 / 丢弃），服务端会再校验一次。

       别名一律由调用方给出，两个入口共用这里：顶部槽位下拉与卡片上的「启用」。 */
    function applyEmbedding(alias) {
        /* P2：/admin/embedding-model 是 B 类（code + success 双写）。
           注意 requiresConfirmation 是「成功但需要二次确认」的业务字段，不是信封：
           code 仍为 0，所以要另外 PUT 一次带 mode 的请求，两次都可能失败。 */
        Console.api.put('/admin/embedding-model', { alias: alias })
            .then(function (r) {
                if (r && r.requiresConfirmation) {
                    /* askRebuildOrPurge 现在是异步的（要等用户在模态里点确认），
                       必须把它的 Promise return 出去，后面的 .then 才会等它 ——
                       忘了 return 就会直接走「已切换」，把用户的确认绕过去。 */
                    return askRebuildOrPurge(r.message || '知识库已有内容。').then(function (choice) {
                        if (!choice) {
                            loadProfiles();
                            return null;
                        }
                        return Console.api.put('/admin/embedding-model',
                            { alias: alias, mode: choice });
                    });
                }
                return r;
            })
            .then(function (r) {
                if (!r) { return; }
                /* wide：换档案但模型没变时，服务端会交代"为什么没让你重灌"，
                   那句话比默认 350px 的气泡宽，折成三行就没人读了。 */
                Console.toast(r.message || '已切换', { type: 'success', wide: true });
                loadProfiles();
                loadSettings();
            })
            .catch(function (e) {
                Console.toast('切换失败：' + e.message, { type: 'error' });
                /* 下拉是受控控件：失败或二次确认被撤销时都要拨回服务端当前值，
                   否则界面上选中的档案与实际槽位不符（loadProfiles 会重渲染下拉）。 */
                loadProfiles();
            });
    }

    function renderProfiles() {
        var box = pfEl('modelList');
        renderSlots();

        /* 一张表列完全部档案：不再按"是不是向量"拆两段，也不再把当前向量档案从列表里
           减掉 —— 那句 filter(alias !== EMBEDDING.alias) 是物理分桶时代的去重补丁，
           双类型档案本来就会在两段里各出现一次。占用关系改由槽位与「已启用」徽标表达。 */
        if (!PROFILES.length) {
            box.innerHTML = '<div class="empty-box">'
                + '<div class="empty-title">还没有配置任何模型</div>'
                + '<div class="empty-sub">点右上角「添加模型」登记一个连接并勾选用途；'
                + '对话模型要绑到域才能被调用（在「域空间」页操作）。</div>'
                + '</div>';
        } else {
            box.innerHTML = PROFILES.map(function (p) {
                return modelCard(p);
            }).join('');
        }

        box.querySelectorAll('button[data-del]').forEach(function (b) {
            b.addEventListener('click', function () { deleteProfile(b.getAttribute('data-del')); });
        });
        /* 卡片上的「启用」与顶部槽位下拉是同一个动作（换向量模型可能要重灌知识库）。 */
        box.querySelectorAll('button[data-enable]').forEach(function (b) {
            b.addEventListener('click', function () { applyEmbedding(b.getAttribute('data-enable')); });
        });
        /* 「探测」按钮：POST /admin/model-profiles/detect，实测端点族与模态后并回档案。
           绑定放在重建 innerHTML 之后，旧监听随旧节点一起丢掉，不会泄漏。 */
        box.querySelectorAll('button[data-probe]').forEach(function (b) {
            b.addEventListener('click', function () { probeProfile(b.getAttribute('data-probe'), b); });
        });
        /* 端点族标签上的「×」：手动移除一个误标的能力（探测不会自动撤，见 dropFamily 注释）。 */
        box.querySelectorAll('button[data-drop-family]').forEach(function (b) {
            b.addEventListener('click', function () {
                dropFamily(b.getAttribute('data-drop-alias'), b.getAttribute('data-drop-family'));
            });
        });
        /* 思考回写开关：拨动即存（PUT /admin/model-profiles/return-thinking）。 */
        box.querySelectorAll('input[data-think]').forEach(function (input) {
            input.addEventListener('change', function () {
                saveReturnThinking(input.getAttribute('data-think'), input.checked, input);
            });
        });
        /* 自定义参数文本框：改动才让「保存参数」变可点。 */
        box.querySelectorAll('.dp-params').forEach(bindParamsBox);

        pfEl('modelCount').textContent = PROFILES.length + ' 个';
    }

    /* ---------- 弹窗：开 / 关 / 重置 ---------- */
    /* 弹窗只有一种形态：添加一份模型档案。向量模型不是独立表单，
       它就是「档案里选中了哪一个 embedding 档案」。 */
    function openModal(title) {
        pfEl('profileModalTitle').textContent = title;
        combo.items = [];
        comboClose();
        updateSaveEnabled();
        pfEl('profileModal').hidden = false;
        document.body.style.overflow = 'hidden';
    }
    function closeModal() {
        comboClose();
        pfEl('profileModal').hidden = true;
        /* 密钥库弹窗可能还开着：只在自己是最后一道遮罩时才解锁滚动 */
        if (pfEl('connModal').hidden) { document.body.style.overflow = ''; }
    }
    /* ---------- 内置预设 ----------
       连接基底统一为 OpenAI 兼容：一条预设 = 平台默认地址，选中即把地址填进表单。
       密钥永远不代填。 */

    var PRESETS = null;          /* null = 还没拉过 */

    function loadPresets() {
        if (PRESETS !== null) { return Promise.resolve(PRESETS); }
        return Console.api.get('/admin/provider-presets')
            .then(function (r) {
                PRESETS = (r && r.presets) || [];
                return PRESETS;
            });
    }

    /* ---------- 预设下拉 ---------- */
    function psMenu() { return pfEl('pfPresetMenu'); }
    function psMenuOpen() {
        psMenu().style.display = 'block';
        pfEl('pfPreset').setAttribute('aria-expanded', 'true');
    }
    function psMenuClose() {
        psMenu().style.display = 'none';
        pfEl('pfPreset').setAttribute('aria-expanded', 'false');
    }
    function psMenuState(text) {
        psMenu().innerHTML = '<div class="combo-state">' + Console.escapeHtml(text) + '</div>';
    }
    /* 预设下拉分两段：上「内置配置项」是平台内置的（只带默认地址、不代填密钥），
       下「自定义配置项」是用户自己存的（地址 + 密钥一起带上）。
       两段共用一个选择框，所以名字整体唯一，服务端写入时把关。 */
    function renderPsMenu() {
        var html = '';
        (PRESETS || []).forEach(function (p, i) {
            if (i === 0) { html += '<div class="combo-group">内置配置项</div>'; }
            var tip = p.note ? p.note : p.baseUrl;
            html += '<div class="combo-item" role="option" data-ps="' + i + '"'
                + ' title="' + Console.escapeHtml(tip) + '">'
                + Console.escapeHtml(p.label) + '</div>';
        });
        (CONNECTIONS || []).forEach(function (c, i) {
            if (i === 0) { html += '<div class="combo-group">自定义配置项</div>'; }
            var tip = c.baseUrl;
            html += '<div class="combo-item" role="option" data-psc="'
                + Console.escapeHtml(c.name) + '" title="' + Console.escapeHtml(tip) + '">'
                + Console.escapeHtml(c.name) + '</div>';
        });
        if (!html) {
            psMenuState('没有可用配置');
            return;
        }
        psMenu().innerHTML = html;
    }
    function openPsMenu() {
        psMenuOpen();
        renderPsMenu();
        loadPresets().then(function () { renderPsMenu(); psMenuOpen(); })
            .catch(function (e) { psMenuState('读取预设失败：' + Console.sanitizeError(e.message)); psMenuOpen(); });
        /* 自定义段是尽力而为：拉不到就只显示内置段，不因为一段失败把整份列表都藏起来 */
        loadConnections(false).catch(function () { return null; })
            .then(function () { renderPsMenu(); });
    }

    /* 选中自定义配置：地址 + 密钥一起填入。明文只在这里按名取一次。
       该条没配密钥时只填地址，不去动已经填好的 Key，静默清空用户手填的密钥比不填更糟。 */
    function applyConnection(name) {
        /* P2：/admin/provider-connections/resolve 是 B 类，且是 POST（无 body）。
           信封判断交给 api.post，这里只留业务判断（没拿到 baseUrl 也算失败）。 */
        Console.api.post('/admin/provider-connections/resolve?name=' + encodeURIComponent(name), null)
            .then(function (r) {
                if (!r || !r.baseUrl) {
                    throw new Error(Console.sanitizeError((r && (r.detail || r.error)) || '取配置失败'));
                }
                pfEl('pfBaseUrl').value = r.baseUrl;
                var msg = '已选中配置「' + name + '」，地址已填入（' + r.baseUrl + '）';
                if (r.hasApiKey && r.apiKey) {
                    pfEl('pfApiKey').value = r.apiKey;
                    msg += '，并带入了密钥';
                } else {
                    msg += '；该条未配密钥，API Key 保持原样';
                }
                readSay(true, msg);
            })
            .catch(function (e) {
                readSay(false, Console.sanitizeError(e.message));
                /* 条目可能刚被删：刷新缓存，免得下次还点一个已经不存在的项 */
                loadConnections(true);
            });
    }

    /* 选中预设：填入地址，并提示密钥仍需自己填。
       预设不代填密钥；地址为空的自建预设（如有）不覆盖用户已填的地址。 */
    function applyPreset(idx) {
        var p = PRESETS[idx];
        if (!p) { return; }
        if (p.baseUrl) {
            pfEl('pfBaseUrl').value = p.baseUrl;
        }
        var msg = '已选中预设「' + p.label + '」';
        if (p.baseUrl) {
            msg += '，服务商地址已填入（' + p.baseUrl + '）';
        } else {
            msg += '，请填写服务商地址';
        }
        if (p.note) { msg += '。' + p.note; }
        if (p.needApiKey) {
            msg += '\n请填写 API Key：预设不代填密钥。';
        } else {
            msg += '\n该预设无需密钥，可留空。';
        }
        readSay(true, msg);
    }

    function resetForm() {
        ['pfBaseUrl', 'pfApiKey', 'pfModelName', 'pfPreset', 'pfParamsJson']
            .forEach(function (id) { pfEl(id).value = ''; });
        /* 模态回到"一个都没选"，连带把调优分组收起来 */
        setPickedModalities([], []);

        var box = pfEl('profileResult');
        box.textContent = '';
        box.className = 'result';
        box.style.color = '';
        var readBox = pfEl('readResult');
        readBox.textContent = '';
        readBox.style.color = '';
    }



    /* ---------- 输入 / 输出模态：用户手选，保存原样提交 ----------
       勾选状态只存两份（PICKED_INPUT / PICKED_OUTPUT），DOM 上的 .on 由它们渲染出来。
       chip 用 button 而不是 <label>：.field label 是 block 级样式（权重高于 .chip），
       套上去会把 chip 撑成整行；button.chip 是 CSS 里预留的结构型按钮。
       弹窗不再有「识别用途」：添加这一步零上游请求、零 token，
       识别统一留给建好之后卡片上的「探测」。 */
    /* 两棵树（src 与 static HTML）可能不同步：容器 / 分组缺节点时静默跳过，
       别让首屏渲染整段抛 TypeError —— 缺的都是可后补的结构，不是要补小字。 */
    function renderModalityChips(containerId, mods, picked) {
        var box = pfEl(containerId);
        if (!box) { return; }
        box.innerHTML = mods.map(function (m) {
            return '<button type="button" class="chip'
                + (picked.indexOf(m) >= 0 ? ' on' : '') + '"'
                + ' data-mod="' + Console.escapeHtml(m) + '">'
                + Console.escapeHtml(MOD_LABEL[m] || m) + '</button>';
        }).join('');
    }
    function renderModalityGroups() {
        renderModalityChips('pfInputMods', INPUT_MODS, PICKED_INPUT);
        renderModalityChips('pfOutputMods', OUTPUT_MODS, PICKED_OUTPUT);
        /* 参数分组只服务"产出文本"的模型：没勾文本输出时收起来，
           向量 / 生图档案的 params 根本到不了请求体，给了框才是骗人。 */
        var tune = pfEl('pfChatTune');
        if (tune) { tune.hidden = PICKED_OUTPUT.indexOf('text') < 0; }
    }
    function setPickedModalities(input, output) {
        /* 只认白名单：旧数据 / 手改请求体混进来的未知键不该落到 models.json */
        PICKED_INPUT = (input || []).filter(function (m) { return INPUT_MODS.indexOf(m) >= 0; });
        PICKED_OUTPUT = (output || []).filter(function (m) { return OUTPUT_MODS.indexOf(m) >= 0; });
        renderModalityGroups();
    }
    function toggleModality(side, m) {
        var cur = side === 'in' ? PICKED_INPUT : PICKED_OUTPUT;
        var next = cur.slice();
        var i = next.indexOf(m);
        if (i >= 0) { next.splice(i, 1); } else { next.push(m); }
        if (side === 'in') { setPickedModalities(next, PICKED_OUTPUT); }
        else { setPickedModalities(PICKED_INPUT, next); }
    }

    /* ---------- 模型名下拉：从服务商拉候选（POST /admin/models） ---------- */
    var combo = { items: [], visible: [], loading: false, active: -1 };

    function comboMenu() { return pfEl('pfModelMenu'); }
    function comboOpen() {
        comboMenu().style.display = 'block';
        pfEl('pfModelName').setAttribute('aria-expanded', 'true');
    }
    function comboClose() {
        comboMenu().style.display = 'none';
        pfEl('pfModelName').setAttribute('aria-expanded', 'false');
        combo.active = -1;
    }
    function comboState(text) {
        comboMenu().innerHTML = '<div class="combo-state">' + Console.escapeHtml(text) + '</div>';
    }
    function comboRender() {
        var kw = pfEl('pfModelName').value.trim().toLowerCase();
        combo.visible = kw
            ? combo.items.filter(function (id) { return id.toLowerCase().indexOf(kw) >= 0; })
            : combo.items.slice();
        if (!combo.visible.length) {
            comboState(combo.items.length ? '无匹配项' : '没有可选模型');
            return;
        }
        comboMenu().innerHTML = combo.visible.map(function (id, i) {
            return '<div class="combo-item' + (i === combo.active ? ' active' : '') + '"'
                + ' role="option" data-id="' + Console.escapeHtml(id) + '">'
                + Console.escapeHtml(id) + '</div>';
        }).join('');
    }
    function comboPick(id) {
        pfEl('pfModelName').value = id;
        comboClose();
    }
    /* 目录接口吃的是 LlmSettings 形状。表单只登记对话模型，候选池固定从 chat 端点拉 */
    function catalogPayload() {
        return { chatBaseUrl: val('pfBaseUrl'), chatApiKey: val('pfApiKey') };
    }
    function comboLoad() {
        if (!(pfEl('pfBaseUrl').value || '').trim()) {
            combo.items = [];
            comboState('请先填写服务商地址');
            comboOpen();
            return;
        }
        if (combo.loading) { return; }
        combo.loading = true;
        comboState('加载中…');
        comboOpen();
        /* P2：/admin/models 是 C 类（只有 success）。
           原来的 `if (!data.success)` 分支合并进 catch：message 已由 normalize 的
           pick() 清洗过（detail||error||message），所以直接显示 e.message。 */
        Console.api.post('/admin/models?type=chat', catalogPayload())
            .then(function (data) {
                combo.items = data.models || [];
                comboRender();
                comboOpen();
            })
            /* 用「拉取候选」而不是「连接」：页面里另有「预设配置信息」这个功能名，
               再说"连接失败"会被读成那边的配置出错 */
            .catch(function (e) { combo.items = []; comboState('拉取候选失败：' + e.message); })
            .then(function () { combo.loading = false; });
    }

    (function () {
        var input = pfEl('pfModelName');
        /* 必须按 id 绑，不能用 querySelector('.combo-btn') 取"弹窗里的第一个"：
           弹窗里可能不止一个 .combo，位置一旦调整就会静默绑错（点 A 的按钮弹出 B 的菜单，
           而 A 的 ▾ 变成死按钮）。位置依赖的写法在这里已经坑过一次。 */
        pfEl('pfModelBtn').addEventListener('click', comboLoad);
        input.addEventListener('focus', function () {
            if (combo.items.length && comboMenu().style.display !== 'block') {
                comboRender();
                comboOpen();
            }
        });
        input.addEventListener('input', function () {
            if (comboMenu().style.display === 'block' && combo.items.length) { comboRender(); }
        });

        input.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape') {
                if (comboMenu().style.display === 'block') { ev.stopPropagation(); comboClose(); }
                return;
            }
            if (comboMenu().style.display !== 'block' || !combo.visible.length) {
                if (ev.key === 'Enter') { ev.preventDefault(); }
                return;
            }
            var n = combo.visible.length;
            if (ev.key === 'ArrowDown' || ev.key === 'ArrowUp') {
                ev.preventDefault();
                combo.active = ev.key === 'ArrowDown' ? (combo.active + 1) % n : (combo.active - 1 + n) % n;
                comboRender();
                var act = comboMenu().querySelector('.combo-item.active');
                if (act && act.scrollIntoView) { act.scrollIntoView({ block: 'nearest' }); }
            } else if (ev.key === 'Enter') {
                ev.preventDefault();
                if (combo.active >= 0 && combo.visible[combo.active]) {
                    comboPick(combo.visible[combo.active]);
                } else {
                    comboClose();
                }
            }
        });
        comboMenu().addEventListener('click', function (ev) {
            var item = ev.target.closest ? ev.target.closest('.combo-item') : null;
            if (item) { comboPick(item.getAttribute('data-id')); }
        });
        /* "点到外面就收起"要按自己的 .combo 容器判，不能只判输入框，
           否则点 ▾ 按钮时菜单刚打开就被这条 document 监听收掉，表现为"点了没反应"。 */
        document.addEventListener('click', function (ev) {
            if (!ev.target.closest || !ev.target.closest('#pfModelCombo')) { comboClose(); }
        });
    })();





    /* 保存门禁：必填项（API Key / 模型名）在 save 里校验并提示，不禁用按钮 */
    function updateSaveEnabled() {
        pfEl('saveProfileBtn').disabled = false;
    }

    /* 预设 / 连接回填后在表单里给一句状态提示 */
    function readSay(ok, text) {
        var el = pfEl('readResult');
        el.textContent = text;
        el.style.color = ok ? '' : 'var(--red)';
    }

    /* 添加：只由用户在弹窗里手选输入 / 输出模态，随请求体原样提交，保存零上游请求。
       档案建好之后的改动都不走这个弹窗：实测识别走卡片上的「探测」（带二次确认，覆盖模态），
       调优走卡片上的框与「更新」，思考回写走卡片头部的开关（thinkSwitch）。 */
    function openAdd() {
        resetForm();
        openModal('添加模型');
        pfEl('pfBaseUrl').focus();
    }
    /* 别名已从表单移除：按模型名自动派生，重名加数字后缀（别名仍是绑定/删除用的键）。
       现在只有一张表，findProfile 查一次就够；分桶时代「只看 PROFILES 会派生出与向量档案
       同名的别名、保存时把那份向量档案整个覆盖掉」的坑已随合桶消失。 */
    function deriveAlias(modelName) {
        var base = aliasBase(modelName);
        function taken(a) {
            return a === 'default' || findProfile(a) !== null;
        }
        var alias = base, n = 2;
        while (taken(alias)) { alias = base + '-' + n; n++; }
        return alias;
    }

    function showResult(ok, message) {
        var box = pfEl('profileResult');
        /* 必须带 .show：.result 默认 display:none，只写 'result' 等于把提示藏起来，
           "请填写 API Key" 这类校验提示此前就是这样一条都没显示过。
           空文案则撤掉 .show 来真的清空。 */
        box.className = message ? 'result show ' + (ok ? 'ok' : 'err') : 'result';
        box.textContent = message || '';
    }

    /* ---------- 卡片：自定义参数（JSON）文本框 ----------
       对话档案一律有这一块（参数面完全由用户自助填，没有适配层）。
       温度、最大输出这类标准参数也在这里写，卡片上不再单独摆控件 ——
       一个入口比"控件 + 文本框"两处更不容易出现"改了一处、生效的是另一处"。
       收 JSON 文本而不是键值行，是因为服务端接口收的就是文本 —— 一个逗号写错时
       解析错误能带上行列号，比"参数不合法"这种话有用得多。 */
    function customParamsBox(p) {
        var cp = p.customParams || {};
        return '<div class="dp-params" data-params-alias="' + Console.escapeHtml(p.alias) + '">'
            + '<div class="dp-params-head">'
            +   '<span class="k">自定义参数</span>'
            +   '<span class="dp-params-sub">JSON · 留空即不发送</span>'
            + '</div>'
            + '<textarea class="mono form-control dp-params-json" rows="3" spellcheck="false"'
            +   ' placeholder=\'{"temperature": 0.7, "enable_thinking": false}\'>'
            +   Console.escapeHtml(cp.paramsJson || '')
            + '</textarea>'
            + '<div class="dp-params-foot">'
            +   '<span class="dp-params-status"></span>'
            +   '<button type="button" class="btn btn-sm btn-outline-primary dp-params-save" disabled>保存参数</button>'
            + '</div>'
            + '</div>';
    }

    /* 绑定一框：基准 = 渲染时框里的文本（服务端回填的已保存值）；有改动才可点。
       保存成功就地更新基准并留下「已保存 N 个键」，不重拉整张列表 ——
       重拉会把刚写上的提示一起冲掉，而卡片上没有别处显示这份 JSON。
       失败**不清空输入框**：用户的错误文本正是他要改的东西，清掉等于让他重打一遍。 */
    function bindParamsBox(boxEl) {
        var alias = boxEl.getAttribute('data-params-alias');
        var ta = boxEl.querySelector('.dp-params-json');
        var btn = boxEl.querySelector('.dp-params-save');
        var status = boxEl.querySelector('.dp-params-status');
        if (!alias || !ta || !btn) { return; }
        var base = (ta.value || '').trim();
        var sync = function () { btn.disabled = (ta.value || '').trim() === base; };
        ta.addEventListener('input', sync);
        btn.addEventListener('click', function () {
            if (status) { status.textContent = ''; status.className = 'dp-params-status'; }
            btn.disabled = true;
            Console.api.put('/admin/model-profiles/custom-params', { alias: alias, paramsJson: ta.value })
                .then(function (r) {
                    base = (ta.value || '').trim();
                    if (status) {
                        status.textContent = (r && r.message) || '已保存';
                        status.className = 'dp-params-status ok';
                    }
                })
                .catch(function (e) {
                    if (status) {
                        status.textContent = Console.sanitizeError(e.message);
                        status.className = 'dp-params-status err';
                    }
                })
                .then(sync);
        });
        sync();
    }


    /* 卡片头部「是否回写思考内容」开关的保存口：只改 returnThinking 一个字段，
       其余（连接信息 / 调优值 / 端点族）由服务端复制时原样带走（ModelProfile#withReturnThinking）。
       拨动即存：一个开关一个语义，不值得为它攒一批改动再点保存；也因此它不与
       调优框的「更新」共用动作，保存失败时把开关拨回去并明说原因：
       界面上开着、实际没存上，比保存失败本身更糟。 */
    function saveReturnThinking(alias, on, input) {
        input.disabled = true;
        Console.api.put('/admin/model-profiles/return-thinking', { alias: alias, returnThinking: on })
            .then(function () {
                /* 就地更新本地档案：重拉整张列表会把它处的状态一起冲掉 */
                var p = findProfile(alias);
                if (p) { p.returnThinking = on; }
            })
            .catch(function (e) {
                input.checked = !on;
                Console.toast('保存失败：' + Console.sanitizeError(e.message), { type: 'error' });
            })
            .then(function () { input.disabled = false; });
    }

    /* ---------- 保存 ----------
       只有一条保存路径：POST /admin/model-profiles（只添加新档案）。
       只提交用户手选的 input / output，不带 endpoints —— 服务端按这组模态本地反查端点族，
       整条路径零上游请求、零 token、不读清单、不测维度。
       一个都不选也照存（卡片标「不可用」），不会自动跑去探测。
       已存档案的改动不走这里：调优走 PUT /model-profiles/tuning，
       思考回写走 PUT /model-profiles/return-thinking。 */
    function save() {
        if (!val('pfApiKey')) { showResult(false, '请填写 API Key'); return; }
        var modelName = pfEl('pfModelName').value.trim();
        if (!modelName) { showResult(false, '请先填写模型名'); return; }
        /* 别名按模型名派生（重名加后缀），弹窗只添加新档案，
           已存档案的改动走卡片（调优 → 「更新」，思考回写 → 卡片头部开关）。 */
        var alias = deriveAlias(modelName);
        var body = {
            alias: alias,
            /* 手选模态原样提交；不传 endpoints，让服务端按模态反查端点族。
               两组都空是允许的：档案落盘，卡片标「不可用」，等用户点「探测」。 */
            input: PICKED_INPUT.slice(),
            output: PICKED_OUTPUT.slice(),
            baseUrl: pfEl('pfBaseUrl').value.trim(),
            apiKey: pfEl('pfApiKey').value,
            modelName: modelName,
            /* 新档案的思考回写默认关闭：要开，建成后拨卡片头部那颗开关
               （见 thinkSwitch / saveReturnThinking） */
            returnThinking: false
        };
        /* 自定义参数只在输出勾了「文本」时可填（分组也会跟着收起）：向量 / 生图 / 重排档案的
           params 根本到不了请求体，提交上去只会在服务端被拒。 */
        var chatPicked = PICKED_OUTPUT.indexOf('text') >= 0;
        if (chatPicked) {
            /* 自定义参数：JSON 在**这里**解析，写错当场说清是哪一位，别留给服务端只回一句
               "JSON 格式错误"。顶层必须是对象 —— 数组与标量都不是"一组参数"。
               温度 / 最大输出也走这里（卡片与弹窗上的同名控件都已删）。 */
            var rawParams = (pfEl('pfParamsJson').value || '').trim();
            if (rawParams !== '') {
                var parsed;
                try {
                    parsed = JSON.parse(rawParams);
                } catch (err) {
                    showResult(false, '自定义参数不是合法 JSON：' + err.message);
                    return;
                }
                if (!parsed || typeof parsed !== 'object'
                    || Object.prototype.toString.call(parsed) === '[object Array]') {
                    showResult(false, '自定义参数要是一个 JSON 对象，如 {"enable_thinking": false}');
                    return;
                }
                body.params = parsed;
            }
        }
        /* 向量维度不在新建时填：一律由模型决定，装配与建索引时按实测值取。 */
        var btn = pfEl('saveProfileBtn');
        btn.disabled = true;
        btn.classList.add('btn-loading');
        Console.api.post('/admin/model-profiles', body)
            .then(function () {
                closeModal();
                return loadProfiles();
            })
            .catch(function (e) { showResult(false, '保存失败：' + e.message); })
            .then(function () {
                btn.disabled = false;
                btn.classList.remove('btn-loading');
            });
    }

    /* 删除向量档案就是删档案本身（与对话档案同一条路径）；
       唯一差别是要提示「当前向量模型会被取消」，那段在下面。 */
    /* 手动移除一个端点族（纠正误标）。
       探测从不写「不支持」（一次 400 不能当"确定没有"），历史错标就只能由用户明说；
       自动撤销风险太大，所以这里做成显式动作 + 二次确认。 */
    async function dropFamily(alias, family) {
        var label = EP_LABEL[family] || family;
        var ok = await Console.confirm('把「' + alias + '」的「' + label + '」能力移除？\n\n'
            + '· 只动这个能力，其它端点族与连接信息（地址 / Key / 模型名）不变\n'
            + '· 恢复方式：重新手选模态，或再点一次「探测」\n'
            + '· 如果这个能力正被某个域绑定，那个域会立刻变成「无可调用」',
            { title: '移除能力', okText: '移除', danger: true });
        if (!ok) { return; }
        Console.api.put('/admin/model-profiles/families/remove',
                { alias: alias, families: [family] })
            .then(function (r) {
                Console.toast((r && r.message) || '已移除', { type: 'success', wide: true });
                return loadProfiles();
            })
            .catch(function (e) {
                Console.toast(Console.sanitizeError(e.message), { type: 'error' });
            });
    }

    async function deleteProfile(alias) {
        /* 一张表查一次就够。合桶之前纯向量档案不在 PROFILES 里，只查 PROFILES 会让
           「它正是当前向量模型」的警告消失，用户删掉正在用的向量模型却只看到普通确认。 */
        var target = findProfile(alias);
        var isEmb = target && isEmbeddingProfile(target);
        var isCurrentEmbedding = isEmb && EMBEDDING.alias === alias;

        var text = '确认删除模型 ' + alias + '？\n\n';
        if (isCurrentEmbedding) {
            /* 别在这里写"需换模型后重灌"：换回同一个模型（删了重建、别名被加后缀）
               不需要重灌，服务端会按向量身份（地址 / 模型名 / 维度）判掉这次无谓的全量 embed。
               把话说全，用户才不会因为怕重灌而不敢删、或者删了白重灌一遍。 */
            text += '⚠️ 它正是当前向量模型，删除后向量配置会一并取消，'
                + '知识库的灌库与向量检索将停用。已灌库的向量仍在索引里：'
                + '之后选回同一个模型就能直接复用，换成别的模型才需要重灌。\n\n';
        } else if (isEmb) {
            text += '它是一个向量档案，删除后需要重新选用向量模型。\n\n';
        } else {
            text += '该模型会从所有域的绑定里一并移除（级联清理），'
                + '这些域将立即变为「无可调用模型」。\n\n';
        }
        var ok = await Console.confirm(text,
            { title: '删除模型', okText: '删除', danger: true });
        if (!ok) { return; }
        /* P2：B 类。信封判断已由 api.del 承担 */
        Console.api.del('/admin/model-profiles?alias=' + encodeURIComponent(alias))
            .then(function (r) {
                if (r && r.message) { Console.toast(r.message, { type: 'success' }); }
                loadProfiles();
            })
            .catch(function (e) { Console.toast('删除失败：' + e.message, { type: 'error' }); });
    }

    /* ---------- 探测结果 ----------
       一个弹框、两个区：支持 / 不支持·未确认。
       失败项只给「名称 · HTTP 状态码 · 短原因」——服务商回执原文、尝试次数、
       端点族原始清单、协议长说明都不进弹框（要追细节去服务端日志）。
       后端 note / diagnostics 原样保留给批量探测与日志，这里只收敛展示。 */
    var PROBE_SHORT = {
        AUTH: '鉴权失败',
        RATE_LIMIT: '被限流',
        UPSTREAM: '上游错误',
        TIMEOUT: '超时',
        NETWORK: '连不上',
        CONFIG: '地址或模型名有误',
        REQUEST_FORMAT: '请求格式不符',
        RESPONSE_SHAPE: '响应格式不符',
        PROTOCOL: '需 WebSocket',
        UNSUPPORTED: '端点不存在',
        NOT_EXECUTED: '未执行',
        OK: '正常'
    };

    function probeHttp(d) {
        var c = d && d.statusCode;
        /* -2 超时 / -1 连不上 / null 未执行都不是 HTTP 响应，只给占位，不伪造状态码 */
        return (typeof c === 'number' && c > 0) ? 'HTTP ' + c : '—';
    }

    function probeResultHtml(r) {
        var diags = (r && r.diagnostics) || {};
        if (!Object.keys(diags).length) {
            return '<div class="probe-empty">未返回探测明细</div>';
        }
        var ok = [], bad = [];
        Object.keys(diags).forEach(function (k) {
            var d = diags[k] || {};
            var name = Console.escapeHtml(d.label || k);
            if (d.state === 'CONFIRMED') {
                ok.push('<span class="probe-chip">' + name + '</span>');
                return;
            }
            bad.push('<div class="probe-row">'
                + '<span class="probe-name">' + name + '</span>'
                + '<span class="probe-code">' + probeHttp(d) + '</span>'
                + '<span class="probe-desc">' + Console.escapeHtml(PROBE_SHORT[d.category] || '未确认') + '</span>'
                + '</div>');
        });
        return '<div class="probe-result">'
            + '<div><div class="probe-sec-head ok">支持 <span class="probe-count">' + ok.length + '</span></div>'
            + (ok.length ? '<div class="probe-chips">' + ok.join('') + '</div>'
                : '<div class="probe-empty">无</div>')
            + '</div>'
            + '<div><div class="probe-sec-head no">不支持 / 未确认 <span class="probe-count">' + bad.length + '</span></div>'
            + (bad.length ? '<div class="probe-rows">' + bad.join('') + '</div>'
                : '<div class="probe-empty">无</div>')
            + '</div>'
            + '</div>';
    }

    /* 探测一个档案：POST /admin/model-profiles/detect，服务端用一组最小请求实测端点族与模态。

       两次确认都是必须的：
       ① 费用 —— 添加和保存全程零请求，这个按钮是全页唯一会真花钱的入口（生图会真出一张图）；
       ② 写回方式 —— 默认只补不删；想把实测当唯一真相要单独选，因为探测失败的原因太多
          （超时、样本被拒、路由不全），拿"这次没确认到"去删能力是错的。 */
    async function probeProfile(alias, btn) {
        var ok = await Console.confirm('探测「' + alias + '」？\n\n'
            + '这是本页唯一会花 token 的操作：会发一组最小请求实测它的端点族与模态。\n'
            + '· 对话：16 个 token（请求体形态不被接受时自动换一种再试）\n'
            + '· 向量 / 重排：提交一条短文本\n'
            + '· 语音识别 / 语音合成：按极短音频计费\n'
            + '· 生图：若该服务商有生图端点，会真实生成一张默认尺寸的图\n\n'
            + '整次一条都没探测到（网络问题等）则保留原设置。继续？',
            { title: '探测模型端点与模态', okText: '开始探测', danger: false });
        if (!ok) { return; }

        var override = await Console.confirm('这次实测结果怎么用？\n\n'
            + '· 「只补不删」：把确认到的能力并进档案，没确认到的旧声明保持不动\n'
            + '· 「覆盖写回」：以实测为准，没确认到的端点族与模态会被清掉',
            { title: '写回方式', okText: '覆盖写回', cancelText: '只补不删', danger: false });

        if (btn) {
            btn.disabled = true;
            btn.classList.add('btn-loading');
            btn.setAttribute('data-orig-label', btn.textContent);
            btn.textContent = '探测中…';
        }
        /* P2：A 类，POST 无 body */
        Console.api.post('/admin/model-profiles/detect?alias=' + encodeURIComponent(alias)
                + '&mode=' + (override ? 'override' : 'confirmed'), null)
            .then(function (r) {
                return loadProfiles().then(function () {
                    /* 只弹一个框：两区（支持 / 不支持·未确认）+ 失败项的状态码与短原因。
                       写回方式放标题，不再叠一条长 toast；诊断回执原文一律不进弹框。 */
                    Console.confirm(probeResultHtml(r), {
                        title: '探测结果 · ' + (r && r.writeMode === 'override' ? '已覆盖写回' : '只补不删'),
                        okText: '知道了',
                        hideCancel: true,
                        html: true,
                        danger: false
                    });
                });
            })
            .catch(function (e) {
                if (btn) {
                    btn.disabled = false;
                    btn.classList.remove('btn-loading');
                    btn.textContent = btn.getAttribute('data-orig-label') || '探测';
                }
                Console.toast('探测失败：' + Console.sanitizeError(e.message), { type: 'error' });
            });
    }

/* ---------- 自定义配置项（config/provider-connections.json）----------
       定位：它是"快捷填充"，不是引用关系。
       一条自定义配置 = 名称 + 服务商地址 +（可选）密钥；三项合成一条而不是拆开，
       因为换服务商时这几个字段从来一起变。
       它和内置配置项共用模型表单顶部那个预设下拉（分两段展示），选中后把地址与密钥一起填进表单，
       档案保存时仍各自保存明文副本；删除一条**不影响**任何已保存档案，
       反过来在表单里改了也不回写这一条。
       列表只回脱敏密钥，明文只在选中某个名称时按名取一次，见 /admin/provider-connections/resolve。 */

    var CONNECTIONS = null;   /* null = 还没拉过；[] = 库里为空。刻意区分：没拉过不该显示"库里没有" */

    function loadConnections(force) {
        if (CONNECTIONS !== null && !force) { return Promise.resolve(CONNECTIONS); }
        return Console.api.get('/admin/provider-connections')
            .then(function (r) {
                CONNECTIONS = (r && r.connections) || [];
                return CONNECTIONS;
            })
            .catch(function (e) {
                CONNECTIONS = null;
                throw e;
            });
    }

    /* ---------- 预设配置信息弹窗（维护自定义配置项） ---------- */
    function cnSay(ok, text) {
        var el = pfEl('cnResult');
        el.textContent = text;
        el.className = 'result' + (text ? (ok ? ' show ok' : ' show err') : '');
    }
    function renderConnectionList() {
        var box = pfEl('cnList');
        if (!CONNECTIONS || !CONNECTIONS.length) {
            box.innerHTML = '<div class="empty-box">还没有自定义配置项。'
                + '添加后，它会出现在模型表单顶部的预设下拉里（「自定义配置项」一段），'
                + '选中即把地址与密钥一起填入。</div>';
            return;
        }
        box.innerHTML = CONNECTIONS.map(function (c) {
            /* 地址原样展示（不是凭据，排障时要看到真实地址）；密钥只给脱敏值 */
            var key = c.hasApiKey ? (c.maskedApiKey || '****') : '（无密钥）';
            return '<div class="dp-card"><div class="card-head">'
                + '<div><b>' + Console.escapeHtml(c.name) + '</b>'
                + '<div class="mono hint" style="margin:2px 0 0;">' + Console.escapeHtml(c.baseUrl) + '</div></div>'
                + '<span class="spacer"></span>'
                + '<span class="mono hint" style="margin:0;">' + Console.escapeHtml(key) + '</span>'
                + '<button class="btn btn-sm btn-outline-danger" data-cn-del="' + Console.escapeHtml(c.name) + '">删除</button>'
                + '</div></div>';
        }).join('');
    }
    function openConnModal() {
        pfEl('connModal').hidden = false;
        document.body.style.overflow = 'hidden';
        cnSay(true, '');
        loadConnections(true)
            .then(function () {
                renderConnectionList();
                pfEl('cnName').focus();
            })
            .catch(function (e) {
                renderConnectionList();
                cnSay(false, '读取自定义配置项失败：' + e.message);
            });
    }
    function closeConnModal() {
        pfEl('connModal').hidden = true;
        /* profileModal 可能同时开着（先开模型窗再开连接窗），只在自己是最后一道遮罩时才解锁滚动 */
        if (pfEl('profileModal').hidden) { document.body.style.overflow = ''; }
    }
    function addConnection() {
        var name = (pfEl('cnName').value || '').trim();
        var baseUrl = (pfEl('cnBaseUrl').value || '').trim();
        var secret = (pfEl('cnApiKey').value || '').trim();
        if (!name) { cnSay(false, '名称不能为空'); return; }
        if (!baseUrl) { cnSay(false, '服务商地址不能为空'); return; }
        var btn = pfEl('cnAddBtn');
        btn.disabled = true;
        btn.classList.add('btn-loading');
        Console.api.post('/admin/provider-connections', { name: name, baseUrl: baseUrl, apiKey: secret })
            .then(function (r) {
                /* 成功就把明文从表单里抹掉：它已经落盘，继续留在输入框只会多一处副本 */
                pfEl('cnName').value = '';
                pfEl('cnBaseUrl').value = '';
                pfEl('cnApiKey').value = '';
                setHint('cnFileHint', r && r.settingsFile);
                cnSay(true, (r && r.message) || ('已添加「' + name + '」'));
                return loadConnections(true);
            })
            .then(function () { renderConnectionList(); })
            .catch(function (e) { cnSay(false, Console.sanitizeError(e.message)); })
            .then(function () { btn.disabled = false; btn.classList.remove('btn-loading'); });
    }
    async function deleteConnection(name) {
        var ok = await Console.confirm('删除自定义配置「' + name + '」？\n\n'
            + '已保存的模型档案不受影响：它们各自保存的是独立副本，不是对这一条的引用。',
            { title: '删除自定义配置', okText: '删除', danger: true });
        if (!ok) { return; }
        /* P2：B 类。信封判断已由 api.del 承担 */
        Console.api.del('/admin/provider-connections?name=' + encodeURIComponent(name))
            .then(function (r) {
                cnSay(true, (r && r.message) || ('已删除「' + name + '」'));
                return loadConnections(true);
            })
            .then(function () { renderConnectionList(); })
            .catch(function (e) { cnSay(false, Console.sanitizeError(e.message)); });
    }

    /* ---------- 预设下拉的事件绑定 ----------
       与模型名下拉同一套判定：收起要判 .combo 容器，不能只判输入框，
       否则点按钮会"刚打开就被收掉"。 */
    (function () {
        pfEl('pfPresetBtn').addEventListener('click', openPsMenu);
        psMenu().addEventListener('click', function (ev) {
            if (!ev.target.closest) { return; }
            var builtIn = ev.target.closest('[data-ps]');
            if (builtIn) {
                psMenuClose();
                pfEl('pfPreset').value = '';
                applyPreset(parseInt(builtIn.getAttribute('data-ps'), 10));
                return;
            }
            var custom = ev.target.closest('[data-psc]');
            if (custom) {
                psMenuClose();
                pfEl('pfPreset').value = '';
                applyConnection(custom.getAttribute('data-psc'));
            }
        });

        document.addEventListener('click', function (ev) {
            if (!ev.target.closest || !ev.target.closest('#pfPresetCombo')) { psMenuClose(); }
        });
    })();

    /* ---------- 事件绑定 ---------- */
    pfEl('openConnBtn').addEventListener('click', openConnModal);
    pfEl('connModalClose').addEventListener('click', closeConnModal);
    pfEl('connModal').addEventListener('click', function (e) {
        if (e.target === pfEl('connModal')) { closeConnModal(); }
    });
    pfEl('cnAddBtn').addEventListener('click', addConnection);
    pfEl('cnClearBtn').addEventListener('click', function () {
        pfEl('cnName').value = '';
        pfEl('cnBaseUrl').value = '';
        pfEl('cnApiKey').value = '';
        cnSay(true, '');
    });
    pfEl('cnList').addEventListener('click', function (e) {
        var btn = e.target.closest ? e.target.closest('[data-cn-del]') : null;
        if (btn) { deleteConnection(btn.getAttribute('data-cn-del')); }
    });

    /* 向量槽下拉：选中即切换（含「未启用」= 取消配置）。
       失败 / 二次确认被撤销时由 applyEmbedding 重拉列表复位下拉。 */
    pfEl('embeddingSlot').addEventListener('change', function () {
        var v = pfEl('embeddingSlot').value;
        applyEmbedding(v || null);
    });

    /* 模态 chips 用委托：renderModalityGroups 每次重画 innerHTML，逐颗绑定会随重画失效。
       两个容器各管一组，点到的 chip 只改自己那一侧。 */
    ['pfInputMods', 'pfOutputMods'].forEach(function (id) {
        var box = pfEl(id);
        if (!box) { return; }
        box.addEventListener('click', function (e) {
            var chip = e.target && e.target.closest ? e.target.closest('[data-mod]') : null;
            if (chip) {
                toggleModality(id === 'pfInputMods' ? 'in' : 'out', chip.getAttribute('data-mod'));
            }
        });
    });
    /* 首屏先把两组模态 chips 与调优分组的初始状态画出来（弹窗还没打开也要是确定的） */
    setPickedModalities([], []);

    pfEl('addModelBtn').addEventListener('click', openAdd);
    pfEl('saveProfileBtn').addEventListener('click', save);
    pfEl('resetProfileFormBtn').addEventListener('click', resetForm);
    pfEl('profileModalClose').addEventListener('click', closeModal);
    pfEl('profileModal').addEventListener('click', function (e) {
        if (e.target === pfEl('profileModal')) { closeModal(); }
    });
    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') { return; }
        /* 下拉优先收：它浮在遮罩层之上，不先关掉的话第一次 Esc 只关了下拉，看不出层还在 */
        if (!pfEl('connModal').hidden) { closeConnModal(); return; }
        if (!pfEl('profileModal').hidden) { closeModal(); }
    });

    Console.ready(function () {
        loadSettings();
        loadProfiles();
    });
})();
