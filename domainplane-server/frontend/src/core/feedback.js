/* ==========================================================================
    Tabler 反馈组件层：提示气泡（toast）+ 二次确认（modal）

    原来用的是浏览器原生的 alert() / confirm()，三个问题：
    · 样式不可控，跟管控台的视觉完全不在一套；
    · alert 是阻塞式的，用户不关掉它就干不了别的；
    · confirm 的按钮是浏览器自己画的，跟页面里的 Tabler 按钮对不上。

    现在改成 Tabler 的组件外观（vendor/tabler.min.css 里已有 .toast / .modal 全套样式），
    行为自己实现：
      · **不引 tabler.min.js** —— 那是 Bootstrap JS + Popper 的整包，与本项目
        「零依赖、纯 CSS 套件、功能逻辑自己写」的路线冲突（用户明确要求过）；
      · **不引 CDN** —— 私有化部署要能完全离线跑。
    只借用外观，行为（显示/消失/焦点/回车/Esc）都在这里自己写。

    用法：
        Console.toast('已保存');                             // 成功提示
        Console.toast('保存失败：' + msg, { type: 'error' });
        if (!await Console.confirm('确认删除？')) { return; }  // 二次确认
    ========================================================================== */

var ICON = {
    success: '<path d="M5 12l5 5l10 -10"/>',
    error: '<circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/>',
    warning: '<path d="M10.24 3.6l-8.14 14.4a2 2 0 0 0 1.73 3h16.34a2 2 0 0 0 1.73 -3l-8.14 -14.4a2 2 0 0 0 -3.46 0z"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/>'
};

var TYPE_META = {
    success: { title: '操作成功', timeout: 4000 },
    error: { title: '出错了', timeout: 9000 },
    warning: { title: '注意', timeout: 7000 },
    info: { title: '提示', timeout: 5000 }
};

var MAX_TOASTS = 4;

/**
 * 造反馈层。document / escapeHtml 由 console.js 注入（页面预览与测试环境可能没有全局 document）。
 */
export function createFeedback(options) {
    var opts = options || {};
    var doc = opts.document || (typeof document !== 'undefined' ? document : null);
    var escapeHtml = opts.escapeHtml || function (s) { return String(s == null ? '' : s); };
    var win = opts.window || (doc ? doc.defaultView : null);

    if (!doc) {
        /* 没有 document（理论上不会发生）：退化成什么都不做，别让页面崩在这里 */
        return {
            toast: function () { },
            confirm: function () { return Promise.resolve(false); }
        };
    }

    /* ================= 提示气泡（toast） ================= */

    var stack = null;

    function ensureStack() {
        if (stack && stack.parentNode) { return stack; }
        stack = doc.createElement('div');
        stack.className = 'dp-toast-stack';
        stack.setAttribute('aria-live', 'polite');
        stack.setAttribute('aria-atomic', 'false');
        doc.body.appendChild(stack);
        return stack;
    }

    function svg(paths) {
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"'
            + ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
            + paths + '</svg>';
    }

    function dismissToast(el) {
        if (!el || el.__dpClosed) { return; }
        el.__dpClosed = true;
        if (el.__dpTimer) { clearTimeout(el.__dpTimer); }
        el.classList.add('dp-toast-hide');
        /* 退场动画走完再摘掉节点 */
        win.setTimeout(function () {
            if (el.parentNode) { el.parentNode.removeChild(el); }
        }, 200);
    }

    /**
     * 弹一条提示。
     * @param {string} message 正文
     * @param {{type?:'success'|'error'|'warning'|'info', title?:string, timeout?:number, wide?:boolean}} [o]
     *        wide=true 时放宽气泡宽度（探测结果这类「几行清单」用得上，默认 350px 会折得很碎）
     */
    function toast(message, o) {
        var cfg = o || {};
        var type = TYPE_META[cfg.type] ? cfg.type : 'info';
        var meta = TYPE_META[type];
        var box = ensureStack();

        var el = doc.createElement('div');
        el.className = 'toast show dp-toast dp-toast-' + type + (cfg.wide ? ' dp-toast-wide' : '');
        el.setAttribute('role', type === 'error' ? 'alert' : 'status');
        el.innerHTML = '<div class="toast-header dp-toast-head">'
            + '<span class="dp-toast-icon">' + svg(ICON[type]) + '</span>'
            + '<span class="dp-toast-title">' + escapeHtml(cfg.title || meta.title) + '</span>'
            + '<span class="dp-toast-spacer"></span>'
            + '<button type="button" class="btn-close" aria-label="关闭"></button>'
            + '</div>'
            + '<div class="toast-body dp-toast-body">' + escapeHtml(message) + '</div>';

        var closeBtn = el.querySelector('.btn-close');
        if (closeBtn) {
            closeBtn.addEventListener('click', function () { dismissToast(el); });
        }

        box.appendChild(el);

        /* 只保留最近几条：旧的不撤走会一直压在右下角 */
        var all = box.querySelectorAll('.toast');
        for (var i = 0; i < all.length - MAX_TOASTS; i++) {
            dismissToast(all[i]);
        }

        var timeout = typeof cfg.timeout === 'number' ? cfg.timeout : meta.timeout;
        if (timeout > 0) {
            el.__dpTimer = win.setTimeout(function () { dismissToast(el); }, timeout);
        }
        return el;
    }

    /* ================= 二次确认（modal） ================= */

    var pending = null;   /* { resolve: Function, prevFocus: Element } */

    function closeConfirm(result) {
        if (!pending) { return; }
        var p = pending;
        pending = null;

        if (p.modal && p.modal.parentNode) { p.modal.parentNode.removeChild(p.modal); }
        if (p.backdrop && p.backdrop.parentNode) { p.backdrop.parentNode.removeChild(p.backdrop); }
        doc.body.style.overflow = p.prevOverflow;
        /* 每次开框都挂过一个 keydown，收尾时必须摘掉，否则开得越多挂得越多 */
        doc.removeEventListener('keydown', onKeydown);

        /* 焦点还给刚才那个按钮，别让键盘用户「丢」在页面上 */
        if (p.prevFocus && p.prevFocus.focus) {
            try { p.prevFocus.focus(); } catch { /* 元素可能已被换掉，忽略 */ }
        }
        p.resolve(result);
    }

    function onKeydown(e) {
        if (!pending) { return; }
        if (e.key === 'Escape' || e.key === 'Esc') {
            e.preventDefault();
            closeConfirm(false);
        }
    }

    /**
     * 二次确认。返回 Promise<boolean>：true = 用户点了确认。
     * @param {string} message 正文（可含换行）；html=true 时是已转义好的 HTML 片段
     * @param {{title?:string, okText?:string, cancelText?:string, danger?:boolean,
     *          html?:boolean, hideCancel?:boolean}} [o]
     *        html=true 用于分区结果展示（调用方自己转义），hideCancel=true 只留一个按钮。
     *        两个选项都不传时行为与纯文本确认框完全一致。
     */
    function confirm(message, o) {
        var cfg = o || {};
        /* 同一时刻只允许一个确认框：新的来了就把旧的按「取消」收掉，
           否则两个框叠在一起，Esc / 回车该听谁的说不清。 */
        if (pending) { closeConfirm(false); }

        return new Promise(function (resolve) {
            var prevFocus = doc.activeElement;
            var prevOverflow = doc.body.style.overflow;

            var modal = doc.createElement('div');
            modal.className = 'modal dp-confirm';
            modal.setAttribute('tabindex', '-1');
            modal.setAttribute('role', 'dialog');
            modal.setAttribute('aria-modal', 'true');
            modal.setAttribute('aria-labelledby', 'dpConfirmTitle');
            modal.innerHTML = '<div class="modal-dialog" role="document">'
                + '<div class="modal-content">'
                + '<div class="modal-header">'
                + '<h5 class="modal-title" id="dpConfirmTitle">'
                + escapeHtml(cfg.title || '确认操作') + '</h5>'
                + '<button type="button" class="btn-close" data-dp-confirm="cancel" aria-label="关闭"></button>'
                + '</div>'
                + '<div class="modal-body dp-confirm-body' + (cfg.html ? ' dp-confirm-html' : '') + '">'
                + (cfg.html ? message : escapeHtml(message)) + '</div>'
                + '<div class="modal-footer">'
                + (cfg.hideCancel ? '' : '<button type="button" class="btn btn-outline-secondary" data-dp-confirm="cancel">'
                    + escapeHtml(cfg.cancelText || '取消') + '</button>')
                + '<button type="button" class="' + (cfg.danger === false ? 'btn btn-outline-primary' : 'btn btn-outline-danger')
                + '" data-dp-confirm="ok">' + escapeHtml(cfg.okText || '确认') + '</button>'
                + '</div>'
                + '</div>'
                + '</div>';

            var backdrop = doc.createElement('div');
            backdrop.className = 'modal-backdrop show';

            pending = {
                resolve: resolve,
                prevFocus: prevFocus,
                prevOverflow: prevOverflow,
                modal: modal,
                backdrop: backdrop
            };

            doc.body.appendChild(modal);
            doc.body.appendChild(backdrop);
            modal.classList.add('show');
            /* 弹窗期间锁住背景滚动，免得鼠标滚轮在遮罩上把底下的页面带走 */
            doc.body.style.overflow = 'hidden';

            modal.addEventListener('click', function (e) {
                var t = e.target;
                var act = t && t.getAttribute ? t.getAttribute('data-dp-confirm') : null;
                if (act === 'ok') { closeConfirm(true); return; }
                if (act === 'cancel') { closeConfirm(false); return; }
                /* 点在遮罩上（= 点在 .modal 容器本身，不是 .modal-content 里）也算取消 */
                if (t === modal) { closeConfirm(false); }
            });
            backdrop.addEventListener('click', function () { closeConfirm(false); });
            doc.addEventListener('keydown', onKeydown);

            var okBtn = modal.querySelector('[data-dp-confirm="ok"]');
            if (okBtn) { okBtn.focus(); }
        });
    }

    return {
        toast: toast,
        confirm: confirm
    };
}
