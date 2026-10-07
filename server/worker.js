// =============================================================
// Xlink Nano Worker服务端 v15.4 (生产级加固版)
// 平台：Cloudflare Worker (JavaScript ES Module)
//
// 终审修复重点：
//   1. 解决 cfgCache 单例在热更新/多实例环境下的污染问题，改用 WeakMap 关联。
//   2. 扩展 Header 鉴权 (Authorization: Bearer <Token>)，兼容 Query Token。
//   3. 补齐 port (1~65535) 范围防御校验。
//   4. 扩充黑名单 (拦截 169.254.169.254 云元数据 IP 及本地内网地址)。
// =============================================================

import { connect } from 'cloudflare:sockets';

const WS_OPEN      = 1;
const WS_PING_MS   = 25_000;
const T_DIRECT     = 2_000;
const T_PROXY      = 3_000;
const T_FALLBACK   = 5_000;
const TEXT_DECODER = new TextDecoder();
const WS_PING_BUF  = new Uint8Array(0);

const RE_IPV4      = /^(\d{1,3}\.){3}\d{1,3}$/;
const RE_V6_PORT   = /^\[([^\]]+)\](?::(\d+))?$/;
const RE_PORT      = /^\d+$/;

// ★ 核心修复：全面防御云厂商元数据劫持与内网嗅探
const BLOCKED_HOSTS = new Set([
    'speed.cloudflare.com',
    'localhost',
    '127.0.0.1',
    '::1',
    '169.254.169.254',
]);

const FAKE_HTML = '<!DOCTYPE html>\n<html>\n<head><title>Welcome to nginx!</title></head>\n<body>\n<center><h1>Welcome to nginx!</h1></center>\n<hr><center>nginx/1.18.0 (Ubuntu)</center>\n</body>\n</html>';
const FAKE_INIT = {
    status: 200,
    headers: { 'Server': 'nginx/1.18.0 (Ubuntu)', 'Content-Type': 'text/html' },
};

// ★ 核心修复：基于 WeakMap 关联 env，支持热更新与多租户隔离
const cfgCache = new WeakMap();

function getCfg(env) {
    if (!env) {
        return {
            TOKEN: 'my-secret-key-888',
            FALLBACK_HOST: '[2602:fc59:11:64::6812:2c00]',
            FALLBACK_PORT: 443,
        };
    }
    if (cfgCache.has(env)) return cfgCache.get(env);

    const cfg = {
        TOKEN:         (env.TOKEN || 'my-secret-key-888').trim(),
        FALLBACK_HOST: (env.FALLBACK_HOST || '[2602:fc59:11:64::6812:2c00]').trim(),
        FALLBACK_PORT: Number(env.FALLBACK_PORT) || 443,
    };
    cfgCache.set(env, cfg);
    return cfg;
}

export default {
    async fetch(request, env, ctx) {
        let cfg;
        try { cfg = getCfg(env); } catch { return new Response(FAKE_HTML, FAKE_INIT); }

        const url = URL.parse(request.url);
        if (!url) return new Response(FAKE_HTML, FAKE_INIT);

        // ★ 核心修复：优先从 Authorization Header 提取 Token，兼容 Query 参数
        const authHeader = request.headers.get('Authorization');
        const headerToken = authHeader?.startsWith('Bearer ') ? authHeader.slice(7).trim() : null;
        const queryToken = url.searchParams.get('token');
        const clientToken = headerToken || queryToken;

        if (clientToken !== cfg.TOKEN)
            return new Response(FAKE_HTML, FAKE_INIT);

        if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket')
            return new Response(FAKE_HTML, FAKE_INIT);

        const { 0: client, 1: server } = new WebSocketPair();
        server.accept({ allowHalfOpen: true });
        server.binaryType = 'arraybuffer';

        const urlProxyIP = url.searchParams.get('pyip') || url.searchParams.get('ip') || null;

        ctx.waitUntil(
            handleSession(server, urlProxyIP, cfg).catch(() => {
                try { server.close(1011); } catch {}
            })
        );

        return new Response(null, { status: 101, webSocket: client });
    },
};

function wsToStreams(ws) {
    const readable = new ReadableStream({
        start(ctrl) {
            ws.addEventListener('message', ({ data }) => {
                if (data instanceof ArrayBuffer) ctrl.enqueue(new Uint8Array(data));
            });
            ws.addEventListener('close', () => { try { ctrl.close();            } catch {} }, { once: true });
            ws.addEventListener('error', () => { try { ctrl.error(new Error()); } catch {} }, { once: true });
        },
        cancel() { try { ws.close(); } catch {} },
    }, { highWaterMark: 0 });

    const writable = new WritableStream({
        write(chunk) { if (ws.readyState === WS_OPEN) ws.send(chunk); },
        close()      { try { ws.close(1000); } catch {} },
        abort()      { try { ws.close(1011); } catch {} },
    }, { highWaterMark: 0 });

    return { readable, writable };
}
