'use strict';

const log = document.getElementById('log');
const input = document.getElementById('input');
const sendBtn = document.getElementById('send');

let busy = false;
/** 本轮的回答气泡。跨工具提示保持同一个，否则一次回答会被拆成好几段 */
let answer = null;
/** 本轮的思考块。与答案气泡分开，且答案一开始就不再往里写 */
let think = null;

function add(node) {
    const stick = log.scrollHeight - log.scrollTop - log.clientHeight < 80;
    log.appendChild(node);
    if (stick) {
        log.scrollTop = log.scrollHeight;
    }
    return node;
}

function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) { n.className = cls; }
    if (text != null) { n.textContent = text; }
    return n;
}

function say(text, cls) {
    return add(el('div', cls, text));
}

/**
 * 思考段：与答案气泡<b>刻意不同形</b>（左侧细线 + 小灰字），一眼能看出它是过程不是结果。
 *
 * 平台把思考放在独立的 THINKING 帧里，页面不需要猜 —— 只按帧类型分开渲染。
 * 关掉「输出思考」的档案不会产生这类帧，这段代码自然不会被触发。
 */
function appendThinking(text) {
    if (!think) {
        think = add(el('div', 'think'));
        think.appendChild(el('div', 'think-label', '思考中…'));
        think.body = el('div', 'think-body');
        think.appendChild(think.body);
    }
    think.body.textContent += text;
}

/** 第一段答案到来 / 本轮结束：给思考块定性（此后不再往里写） */
function closeThinking() {
    if (think) {
        think.querySelector('.think-label').textContent = '思考完毕';
        think.classList.add('done');
        think = null;
    }
}

function draw(event) {
    switch (event.type) {
        case 'TOKEN':
            closeThinking();
            if (!answer) {
                answer = add(el('div', 'bubble'));
                answer.classList.add('typing');
            }
            answer.textContent += event.content || '';
            break;

        case 'THINKING':
            appendThinking(event.content || '');
            break;

        case 'TOOL_CALL':
            say('调用工具 ' + event.content, 'note');
            break;

        case 'TOOL_RESULT':
            say(event.content + ' → ' + (event.payload || ''), 'note');
            break;

        case 'INTERRUPT':
            say('需要人工授权，已挂起 —— 这一轮还没结束，本示例暂不支持批准/拒绝。'
                + '直接发下一条消息会被服务端视为「拒绝」。', 'error');
            break;

        case 'STOPPED':
            say('已停止', 'note');
            break;

        case 'ERROR':
            say((event.codeName || 'ERROR') + '：' + (event.content || '')
                + (event.traceId ? '（traceId=' + event.traceId + '）' : ''), 'error');
            break;

        default:
            break;
    }
}

/** 结束本轮：撤掉光标，恢复输入 */
function done() {
    closeThinking();
    if (answer) {
        answer.classList.remove('typing');
        answer = null;
    }
    busy = false;
    sendBtn.disabled = false;
    input.placeholder = '说点什么，Enter 发送';
    input.focus();
}

/**
 * 接口地址：永远相对<b>当前页面所在的那个源</b>解析。
 *
 * 这行本身没错，问题在于"当前页面"可能是被别的东西提供的 ——
 * 用 IDE 的内置服务器打开、直接双击 html 文件（file://）、或者经某个预览代理打开时，
 * /api/chat 会落到那个服务器上，它当然没有这个接口，于是回一个 404。
 */
function apiUrl() {
    return new URL('/api/chat', location.href).href;
}

/** 页面是不是由例子应用自己提供的（判断依据见调用处注释） */
function likelyForeignOrigin() {
    return location.protocol === 'file:' || !location.hostname;
}

async function send() {
    if (busy) {
        return;
    }
    const message = input.value.trim();
    if (!message) {
        return;
    }
    input.value = '';
    say(message, 'user');
    answer = null;
    think = null;

    busy = true;
    sendBtn.disabled = true;
    input.placeholder = '回答中…';

    try {
        /* 请求体只有一句话：域与会话由后端补齐，页面不知道、也不用知道。 */
        const res = await fetch(apiUrl(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: message })
        });

        /* 必须先看 ok。
           之前这里只判了 res.body —— 而 500 的响应体是有的（Spring 的错误 JSON），
           于是它被当成 SSE 去解析、找不到 data: 行、循环正常结束：
           页面安安静静，用户看到的就是"发出去了，什么都没回来"。
           真正的失败原因（服务端不可达、域没绑模型…）就藏在那段 JSON 里。 */
        if (!res.ok) {
            let detail = '';
            try {
                detail = (await res.text()).slice(0, 500);
            } catch (ignored) {
                // 响应体读不出来就算了，状态码本身已经说明问题
            }
            /* 404 单独说清楚：它几乎总是"页面和后端不是同一个应用"。
               光甩一句 "HTTP 404 <html>…" 的话，看的人只会以为是后端坏了。 */
            if (res.status === 404) {
                say('接口不存在（404）：' + apiUrl(), 'error');
                say('这个页面来自 ' + location.origin + '，而 /api/chat 在它上面并不存在。'
                    + '例子应用的接口和页面在同一个进程里，所以请直接用浏览器打开'
                    + ' http://localhost:9530/ （或你启动例子时用的端口），'
                    + '不要用 IDE 的内置服务器打开 html、也不要双击文件打开。',
                    'error');
                throw new Error('页面与后端不同源');
            }
            throw new Error('HTTP ' + res.status + (detail ? ' ' + detail : ''));
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        for (;;) {
            const chunk = await reader.read();
            if (chunk.done) {
                break;
            }
            buf += decoder.decode(chunk.value, { stream: true });
            let cut;
            while ((cut = buf.indexOf('\n\n')) >= 0) {
                const block = buf.slice(0, cut);
                buf = buf.slice(cut + 2);
                const data = block.split('\n')
                    .filter(function (line) { return line.startsWith('data:'); })
                    .map(function (line) { return line.slice(5).trim(); })
                    .join('');
                if (!data) {
                    continue;
                }
                /* 单帧解析失败不该毁掉整条流：之前它在循环里裸抛，
                   会被外层 catch 成"调用失败"，后面的回答全丢。 */
                try {
                    draw(JSON.parse(data));
                } catch (bad) {
                    say('收到无法解析的帧：' + data.slice(0, 200), 'error');
                }
            }
        }
    } catch (e) {
        say('调用失败：' + e.message, 'error');
    } finally {
        done();
    }
}

sendBtn.onclick = send;

input.onkeydown = function (e) {
    if (e.key === 'Enter') {
        e.preventDefault();
        send();
    }
};

/* 页面一打开就把"发给谁"讲清楚 —— 出了 404 时这是第一个该看的坐标 */
if (likelyForeignOrigin()) {
    say('这个页面是用文件方式打开的（' + location.href + '），请求发不到后端。'
        + '请改用 http://localhost:9530/ 打开。', 'error');
}

input.focus();
