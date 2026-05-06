// ==UserScript==
// @name         质量考试自动答题
// @namespace    https://faqrobot.local/
// @version      1.0
// @description  基于 FAQ Robot 知识库自动识别并回答考试题目
// @author       FAQ Robot
// @match        *://*/*
// @grant        GM_xmlhttpRequest
// @grant        GM_addStyle
// @connect      localhost
// @connect      127.0.0.1
// ==/UserScript==

(function () {
    'use strict';

    // ==================== 配置 ====================
    const API_BASE = 'http://localhost:8080/api/qa';
    const AUTO_DELAY = 800; // 每题答完后等待 ms
    const MATCH_THRESHOLD = 3.0; // 最低匹配分数

    // ==================== DOM 选择器 ====================
    const SEL = {
        qTitle: '.q-title div:last-child',
        qTypeBtn: '.q-title button span',
        optionItems: '.ab-item',
        optionLetter: '.ab-ik',
        optionText: '.ab-dc',
        nextBtn: 'button span',
        progress: '.abt-a'
    };

    // ==================== 状态 ====================
    let running = false;
    let stopRequested = false;
    let totalQuestions = 0;
    let currentIndex = 0;

    // ==================== UI ====================
    GM_addStyle(`
.faq-panel { position:fixed; top:120px; right:20px; z-index:99999;
  background:#fff; border-radius:12px; box-shadow:0 4px 24px rgba(0,0,0,.15);
  padding:16px; width:200px; font-family:"Microsoft YaHei",sans-serif; }
.faq-panel .faq-title { font-size:14px; font-weight:700; color:#303133; margin-bottom:10px; }
.faq-panel .faq-status { font-size:12px; color:#909399; margin-bottom:8px; line-height:1.6; }
.faq-btn { display:block; width:100%; padding:10px 0; border:none; border-radius:8px;
  font-size:14px; font-weight:600; cursor:pointer; transition:all .2s; }
.faq-btn-start { background:#409eff; color:#fff; }
.faq-btn-start:hover { background:#337ecc; }
.faq-btn-stop { background:#f56c6c; color:#fff; }
.faq-btn-stop:hover { background:#e04545; }
.faq-btn:disabled { background:#c0c4cc; cursor:not-allowed; }
.faq-log { margin-top:8px; font-size:11px; color:#e6a23c; max-height:80px; overflow-y:auto; }
`);

    let panel = document.createElement('div');
    panel.className = 'faq-panel';
    panel.innerHTML = `
<div class="faq-title">🤖 自动答题</div>
<div class="faq-status" id="faq-status">就绪，点击开始</div>
<button class="faq-btn faq-btn-start" id="faq-btn-start">开始自动答题</button>
<button class="faq-btn faq-btn-stop" id="faq-btn-stop" style="display:none;margin-top:6px;">停止</button>
<div class="faq-log" id="faq-log"></div>
`;
    document.body.appendChild(panel);

    let btnStart = document.getElementById('faq-btn-start');
    let btnStop = document.getElementById('faq-btn-stop');
    let statusEl = document.getElementById('faq-status');
    let logEl = document.getElementById('faq-log');

    function setStatus(msg, color) {
        statusEl.textContent = msg;
        statusEl.style.color = color || '#909399';
    }
    function addLog(msg) {
        logEl.textContent = msg;
    }

    // ==================== 工具函数 ====================

    /** 去除题号前缀 如 "1. xxx" -> "xxx" */
    function stripQuestionNumber(text) {
        return text.replace(/^\s*\d+\.\s*/, '').trim();
    }

    /** 从 fullContent 解析选项和答案 */
    function parseFullContent(content) {
        const options = {};
        const optRe = /"option([A-Z])"\s*:\s*"((?:[^"\\]|\\.)*)"/g;
        let m;
        while ((m = optRe.exec(content)) !== null) {
            options[m[1]] = m[2].replace(/\\"/g, '"').replace(/\\n/g, '\n');
        }
        const corrMatch = content.match(/"correct"\s*:\s*"([^"]*)"/);
        const correctLetters = corrMatch ? corrMatch[1].split(',').map(s => s.trim()) : [];
        return { options, correctLetters };
    }

    /** 读取页面题目 */
    function readQuestion() {
        let el = document.querySelector(SEL.qTitle);
        if (!el) return null;
        return stripQuestionNumber(el.textContent.trim());
    }

    /** 读取页面选项列表 {letter, text, el} */
    function readPageOptions() {
        const items = document.querySelectorAll(SEL.optionItems);
        const result = [];
        items.forEach(item => {
            const letterEl = item.querySelector(SEL.optionLetter);
            const textEl = item.querySelector(SEL.optionText);
            if (letterEl && textEl) {
                result.push({
                    letter: letterEl.textContent.trim(),
                    text: textEl.textContent.trim(),
                    el: item
                });
            }
        });
        return result;
    }

    /** 题型检测 */
    function isMultiChoice() {
        let btn = document.querySelector(SEL.qTypeBtn);
        return btn && btn.textContent.includes('多选');
    }

    /** 获取进度 */
    function readProgress() {
        let el = document.querySelector(SEL.progress);
        if (!el) return { current: 0, total: 0 };
        let totalEl = el.parentElement;
        if (!totalEl) return { current: 0, total: 0 };
        let fullText = totalEl.textContent.trim(); // "1/15"
        let parts = fullText.split('/');
        return {
            current: parseInt(parts[0]) || 0,
            total: parseInt(parts[1]) || 0
        };
    }

    /** 点击"下一题"按钮 */
    function clickNext() {
        let buttons = document.querySelectorAll(SEL.nextBtn);
        for (let btn of buttons) {
            if (btn.textContent.trim() === '下一题') {
                btn.closest('button').click();
                return true;
            }
        }
        return false;
    }

    /** 判断是否为最后一题 */
    function isLastQuestion() {
        let prog = readProgress();
        return prog.total > 0 && prog.current >= prog.total;
    }

    // ==================== 核心逻辑 ====================

    function searchKnowledge(question) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'POST',
                url: API_BASE + '/search',
                headers: { 'Content-Type': 'application/json' },
                data: JSON.stringify({ query: question, maxResults: 1 }),
                timeout: 8000,
                onload: function (resp) {
                    try {
                        let data = JSON.parse(resp.responseText);
                        resolve(data);
                    } catch (e) {
                        reject(new Error('JSON 解析失败'));
                    }
                },
                onerror: function () { reject(new Error('网络请求失败')); },
                ontimeout: function () { reject(new Error('请求超时')); }
            });
        });
    }

    async function answerOneQuestion() {
        let question = readQuestion();
        if (!question) {
            setStatus('未检测到题目', '#e6a23c');
            return false;
        }

        let prog = readProgress();
        currentIndex = prog.current;
        totalQuestions = prog.total;
        setStatus(`正在搜索: 第 ${currentIndex}/${totalQuestions || '?'} 题`, '#409eff');

        let result;
        try {
            result = await searchKnowledge(question);
        } catch (e) {
            setStatus('API 连接失败，重试中...', '#f56c6c');
            addLog(e.message);
            return false;
        }

        if (!result || result.length === 0) {
            setStatus('未找到匹配，请手动作答', '#e6a23c');
            addLog('知识库无匹配: ' + question);
            return false;
        }

        let top = result[0];
        if (top.score < MATCH_THRESHOLD) {
            setStatus(`匹配分数过低(${top.score.toFixed(1)})，请手动作答`, '#e6a23c');
            addLog('低分匹配: ' + question);
            return false;
        }

        let parsed = parseFullContent(top.fullContent);
        if (parsed.correctLetters.length === 0) {
            setStatus('解析答案失败', '#f56c6c');
            addLog('未解析到correct字段');
            return false;
        }

        let pageOptions = readPageOptions();
        let multi = isMultiChoice();
        let clickedCount = 0;

        for (let letter of parsed.correctLetters) {
            let optText = parsed.options[letter];
            if (!optText) {
                addLog(`选项${letter}文本未找到`);
                continue;
            }

            for (let po of pageOptions) {
                if (po.text === optText) {
                    po.el.click();
                    clickedCount++;
                    break;
                }
            }
        }

        if (clickedCount === 0) {
            setStatus('选项匹配失败，请手动作答', '#f56c6c');
            addLog('DOM中未找到匹配选项');
            return false;
        }

        setStatus(`已作答 第${currentIndex}/${totalQuestions||'?'}题 (${top.score.toFixed(1)}分)`, '#67c23a');
        addLog(`匹配: "${top.title}" -> ${parsed.correctLetters.join(',')}`);
        return true;
    }

    async function runLoop() {
        if (stopRequested || !running) return;

        // 先答题（最后一题也要答）
        await answerOneQuestion();

        if (stopRequested || !running) return;

        // 等待后检查是否为最后一题，是则停止不点下一题/交卷
        await sleep(AUTO_DELAY);

        if (stopRequested || !running) return;

        if (isLastQuestion()) {
            setStatus('答题完毕，请手动交卷', '#e6a23c');
            addLog('最后一题已作答，请点击交卷按钮');
            stop();
            return;
        }

        let clicked = clickNext();
        if (!clicked) {
            setStatus('未找到"下一题"按钮', '#f56c6c');
            return;
        }

        // 等待页面加载下一题
        await sleep(600);

        // 继续循环
        runLoop();
    }

    function sleep(ms) {
        return new Promise(r => setTimeout(r, ms));
    }

    function start() {
        running = true;
        stopRequested = false;
        btnStart.style.display = 'none';
        btnStop.style.display = 'block';
        setStatus('正在启动...', '#409eff');
        addLog('');
        runLoop();
    }

    function stop() {
        running = false;
        stopRequested = true;
        btnStart.style.display = 'block';
        btnStop.style.display = 'none';
        if (statusEl.textContent.indexOf('停止') < 0 && statusEl.textContent.indexOf('最后一题') < 0
            && statusEl.textContent.indexOf('交卷') < 0) {
            setStatus('已停止');
        }
    }

    btnStart.addEventListener('click', start);
    btnStop.addEventListener('click', stop);

    console.log('[FAQ Robot] 自动答题脚本已加载，悬浮面板在页面右侧');
})();
