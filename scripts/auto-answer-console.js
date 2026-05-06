/**
 * 质量考试自动答题 - 浏览器控制台脚本
 *
 * 使用方法:
 *   1. 在浏览器打开考试页面
 *   2. 按 F12 打开开发者工具 -> Console
 *   3. 复制粘贴本脚本全部内容，回车运行
 *   4. 输入 autoAnswer.start() 开始自动答题
 *   5. 输入 autoAnswer.stop() 手动停止
 *
 * 前提: faq-robot 服务已启动在 localhost:8080
 */

(function () {
    'use strict';

    const API_BASE = 'http://localhost:8080/api/qa';
    const AUTO_DELAY = 800;

    // ============ 状态 ============
    let running = false;
    let stopRequested = false;
    let retryCount = 0;
    const MAX_RETRIES = 3;

    // ============ 工具函数 ============

    function stripQuestionNumber(text) {
        return text.replace(/^\s*\d+\.\s*/, '').trim();
    }

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

    function readQuestion() {
        let el = document.querySelector('.q-title div:last-child');
        return el ? stripQuestionNumber(el.textContent.trim()) : null;
    }

    function readPageOptions() {
        const items = document.querySelectorAll('.ab-item');
        const result = [];
        items.forEach(item => {
            const letterEl = item.querySelector('.ab-ik');
            const textEl = item.querySelector('.ab-dc');
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

    function isMultiChoice() {
        let btn = document.querySelector('.q-title button span');
        return btn && btn.textContent.includes('多选');
    }

    function readProgress() {
        let el = document.querySelector('.abt-a');
        if (!el) return { current: 0, total: 0 };
        let fullText = el.parentElement.textContent.trim();
        let parts = fullText.split('/');
        return {
            current: parseInt(parts[0]) || 0,
            total: parseInt(parts[1]) || 0
        };
    }

    function clickNext() {
        let buttons = document.querySelectorAll('button span');
        for (let btn of buttons) {
            if (btn.textContent.trim() === '下一题') {
                btn.closest('button').click();
                return true;
            }
        }
        return false;
    }

    function isLastQuestion() {
        let prog = readProgress();
        return prog.total > 0 && prog.current >= prog.total;
    }

    function sleep(ms) {
        return new Promise(r => setTimeout(r, ms));
    }

    // ============ 核心 ============

    async function searchKnowledge(question) {
        let resp = await fetch(API_BASE + '/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: question, maxResults: 1 })
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        return resp.json();
    }

    async function answerOne() {
        let question = readQuestion();
        if (!question) {
            console.warn('[AutoAnswer] 未检测到题目');
            return false;
        }
        let prog = readProgress();
        console.log(`[AutoAnswer] 第 ${prog.current}/${prog.total || '?'} 题: ${question}`);

        let data;
        try {
            data = await searchKnowledge(question);
            retryCount = 0;
        } catch (e) {
            retryCount++;
            console.error('[AutoAnswer] API 请求失败:', e.message);
            if (retryCount < MAX_RETRIES) {
                console.log(`[AutoAnswer] 重试 ${retryCount}/${MAX_RETRIES}...`);
                await sleep(1000);
                return answerOne();
            }
            return false;
        }

        if (!data || data.length === 0) {
            console.warn('[AutoAnswer] 无匹配结果，跳过');
            return false;
        }

        let top = data[0];
        let parsed = parseFullContent(top.fullContent);
        if (parsed.correctLetters.length === 0) {
            console.warn('[AutoAnswer] 解析答案失败');
            return false;
        }

        let pageOptions = readPageOptions();
        let clicked = 0;
        for (let letter of parsed.correctLetters) {
            let optText = parsed.options[letter];
            if (!optText) continue;
            for (let po of pageOptions) {
                if (po.text === optText) {
                    po.el.click();
                    clicked++;
                    break;
                }
            }
        }

        if (clicked === 0) {
            console.warn('[AutoAnswer] 选项匹配失败');
            return false;
        }

        console.log(`[AutoAnswer] ✓ 答案: ${parsed.correctLetters.join(',')} (匹配: "${top.title}", 分数:${top.score.toFixed(1)})`);
        return true;
    }

    async function runLoop() {
        if (stopRequested || !running) return;

        // 先答题（最后一题也要答）
        await answerOne();
        if (stopRequested || !running) return;

        await sleep(AUTO_DELAY);
        if (stopRequested || !running) return;

        // 最后一题答完就停，不点交卷
        if (isLastQuestion()) {
            console.log('[AutoAnswer] 答题完毕，最后一题已作答，请手动点击交卷');
            stop();
            return;
        }

        let ok = clickNext();
        if (!ok) {
            console.error('[AutoAnswer] 未找到"下一题"按钮');
            stop();
            return;
        }

        await sleep(600);
        runLoop();
    }

    function start() {
        running = true;
        stopRequested = false;
        retryCount = 0;
        console.log('[AutoAnswer] 开始自动答题...');
        runLoop();
    }

    function stop() {
        running = false;
        stopRequested = true;
        console.log('[AutoAnswer] 已停止');
    }

    // ============ 暴露接口 ============
    window.autoAnswer = { start, stop };

    console.log('✅ 自动答题脚本已加载');
    console.log('  autoAnswer.start() — 开始自动答题');
    console.log('  autoAnswer.stop()  — 停止答题');
    console.log('  注意: 自动答题不会点击"交卷"，最后一题会自动停止');
})();
