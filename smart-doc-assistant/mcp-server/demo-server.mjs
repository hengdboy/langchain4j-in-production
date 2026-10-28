#!/usr/bin/env node
/**
 * Ch17 配套：极简 MCP stdio 服务器（教学用，零依赖，手写 JSON-RPC）
 *
 * 为什么要手写而不是用官方 SDK？
 * - 官方 SDK（@modelcontextprotocol/sdk）需要 npm install，且封装了协议细节；
 * - 手写版本只有 80 行，把 MCP 的协议本体（initialize / tools/list / tools/call）摊开给你看，
 *   学完你就能读懂任意 MCP 服务器的实现。
 *
 * 协议要点（MCP over stdio）：
 * - 传输通道 = 进程的 stdin/stdout：每行一个 JSON-RPC 消息（换行分隔）；
 * - stdout 是协议通道，绝对不能 console.log 调试输出（会污染协议流）——调试走 stderr；
 * - 握手：客户端先发 initialize → 服务器回协议版本/能力 → 客户端发 notifications/initialized；
 * - 工具发现：tools/list 返回工具清单（名称/描述/输入 JSON Schema）；
 * - 工具调用：tools/call 携带 { name, arguments }，返回 { content: [{ type: "text", text }] }。
 */

import readline from 'readline';

// ============ 本服务器暴露的工具 ============
const TOOLS = [
  {
    name: 'echo',
    description: '原样返回输入的文本',
    inputSchema: {
      type: 'object',
      properties: { text: { type: 'string', description: '要回显的文本' } },
      required: ['text'],
    },
  },
  {
    name: 'add',
    description: '两个数字相加',
    inputSchema: {
      type: 'object',
      properties: {
        a: { type: 'number', description: '第一个数' },
        b: { type: 'number', description: '第二个数' },
      },
      required: ['a', 'b'],
    },
  },
  {
    name: 'get_time',
    description: '返回当前时间（ISO 8601 格式）',
    inputSchema: { type: 'object', properties: {} },
  },
];

// ============ 本服务器暴露的提示模板（Ch19：prompts 原语） ============
const PROMPTS = [
  {
    name: 'customer-reply',
    description: '生成客服回复模板（按问题类型）',
    arguments: [{ name: 'issue', description: '问题类型：refund / shipping / technical', required: true }],
    template: (issue) => {
      const map = {
        refund: '您好，很抱歉给您带来不便。关于退换货：商品保持完好不影响二次销售可在 7 天内申请无理由退货；如为质量问题，运费由商家承担。请问需要我为您查询退货单号吗？',
        shipping: '您好，您的订单已确认，默认 24 小时内发货；偏远地区时效延长 1-2 天。可关注公众号绑定手机号获取物流实时推送。',
        technical: '您好，感谢反馈。请您先尝试：1) 退出重新登录；2) 清除缓存；3) 10 分钟后重试。若仍未解决，请提供报错截图，我为您转人工。',
        general: '您好，客服小助手为您服务。请描述您遇到的问题，我会尽快帮您解决。',
      };
      return map[issue] || map.general;
    },
  },
];

// ============ JSON-RPC 收发 ============
function send(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

// ============ 本服务器暴露的资源（Ch17：resources 原语） ============
const RESOURCES = [
  {
    uri: 'docs://demo/protocol-notes',
    name: 'MCP 协议学习笔记',
    mimeType: 'text/markdown',
    text: '# MCP 协议学习笔记\n\n' +
        '- MCP = Model Context Protocol，Anthropic 2024 年提出的开放标准；\n' +
        '- 三种原语：Tools（可调用）/ Resources（只读数据）/ Prompts（提示模板）；\n' +
        '- 传输：stdio（本地子进程）/ Streamable HTTP（远程）；\n' +
        '- 消息格式：JSON-RPC 2.0，每行一条；\n' +
        '- 生命周期：initialize → notifications/initialized → tools/list → tools/call。',
  },
];

function handleRequest(req) {
  const { id, method, params } = req;

  // 握手
  if (method === 'initialize') {
    return send({
      jsonrpc: '2.0',
      id,
      result: {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {}, resources: {}, prompts: {} },   // 声明：工具 + 资源 + 提示三种原语
        serverInfo: { name: 'demo-server', version: '0.1.0' },
      },
    });
  }
  // 客户端通知（无响应）
  if (method === 'notifications/initialized') return;

  // 健康检查
  if (method === 'ping') return send({ jsonrpc: '2.0', id, result: {} });

  // 资源发现（Ch17：resources 原语）
  if (method === 'resources/list') {
    return send({
      jsonrpc: '2.0', id,
      result: { resources: RESOURCES.map(({ uri, name, mimeType }) => ({ uri, name, mimeType })) },
    });
  }

  // 资源读取（Ch17：resources 原语）
  if (method === 'resources/read') {
    const uri = params.uri;
    const resource = RESOURCES.find((r) => r.uri === uri);
    if (!resource) {
      return send({ jsonrpc: '2.0', id, error: { code: -32602, message: '未知资源: ' + uri } });
    }
    return send({
      jsonrpc: '2.0', id,
      result: { contents: [{ uri: resource.uri, mimeType: resource.mimeType, text: resource.text }] },
    });
  }

  // 提示发现（Ch19：prompts 原语）
  if (method === 'prompts/list') {
    return send({ jsonrpc: '2.0', id, result: { prompts: PROMPTS } });
  }

  // 提示获取（Ch19：prompts 原语）
  if (method === 'prompts/get') {
    const name = params.name;
    const prompt = PROMPTS.find((p) => p.name === name);
    if (!prompt) {
      return send({ jsonrpc: '2.0', id, error: { code: -32602, message: '未知提示: ' + name } });
    }
    const issue = (params.arguments && params.arguments.issue) || 'general';
    return send({
      jsonrpc: '2.0', id,
      result: {
        description: prompt.description,
        messages: [{ role: 'user', content: { type: 'text', text: prompt.template(issue) } }],
      },
    });
  }

  // 工具发现
  if (method === 'tools/list') {
    return send({ jsonrpc: '2.0', id, result: { tools: TOOLS } });
  }

  // 工具调用
  if (method === 'tools/call') {
    const { name, arguments: args } = params;
    let text;
    switch (name) {
      case 'echo':
        text = 'echo: ' + args.text;
        break;
      case 'add':
        text = String(args.a + args.b);
        break;
      case 'get_time':
        text = new Date().toISOString();
        break;
      default:
        return send({ jsonrpc: '2.0', id, error: { code: -32602, message: '未知工具: ' + name } });
    }
    return send({
      jsonrpc: '2.0',
      id,
      result: { content: [{ type: 'text', text }] },   // MCP 工具结果标准结构
    });
  }

  send({ jsonrpc: '2.0', id, error: { code: -32601, message: '未支持的方法: ' + method } });
}

// 逐行读取 stdin（stdout 是协议通道，严禁 console.log）
const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  if (!line.trim()) return;
  try {
    handleRequest(JSON.parse(line));
  } catch (e) {
    console.error('[demo-server] 请求解析失败:', e.message);   // stderr，不污染协议流
  }
});
console.error('[demo-server] 已启动，等待 JSON-RPC 请求…');
