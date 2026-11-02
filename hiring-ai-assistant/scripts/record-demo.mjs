/**
 * 录制招聘全流程演示视频（README GIF 素材）。
 *
 * 前置：应用已在 localhost:8091 运行（canned 模式即可，无需 LLM key）。
 * 用法：node scripts/record-demo.mjs [输出目录]
 *      若本地未安装 playwright 包，可用全局安装：NODE_PATH=$(npm root -g) node scripts/record-demo.mjs
 * 产物：输出目录下自动命名的 .webm，打印路径；再用 ffmpeg 转 GIF（见 README）。
 */
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = await import('playwright').catch(() => require('playwright'));

const URL = 'http://localhost:8091/?pace=3';
const outDir = process.argv[2] || '.demo-video';

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 1280, height: 720 },
  recordVideo: { dir: outDir, size: { width: 1280, height: 720 } },
});
const page = await ctx.newPage();
await page.goto(URL);
await page.waitForFunction(() => document.querySelector('#status')?.textContent.includes('后端已连接'), null, { timeout: 15000 });

const hidden = await page.evaluate(() => document.hidden);
if (hidden) { console.error('WARN: document.hidden=true，无头模式会跳过动画，请加 --headful 重跑'); }

await page.waitForTimeout(1500);
await page.click('#runBtn');
await page.waitForFunction(() => document.querySelector('#status')?.textContent.includes('完成'), null, { timeout: 120000 });
await page.waitForTimeout(2500);

const path = await page.video().path();
await ctx.close();
await browser.close();
console.log(path);
