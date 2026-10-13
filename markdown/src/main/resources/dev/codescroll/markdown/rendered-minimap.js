// SPDX-License-Identifier: MIT
(function () {
    "use strict";

    const MAX_VISITS = 24000;
    const MAX_BLOCKS = 1280;
    const MAX_HEADINGS = 256;
    const WIDTH = 96;
    const MAP_SCALE = 0.1;
    // Frame fill opacity at rest, while hovered and while dragged.
    const FRAME_ALPHA = [0.15, 0.25, 0.35];
    // Space reserved on the right of the page so the overlay never covers rendered content.
    const GUTTER = WIDTH + 24;
    const MAX_HEIGHT = 1600;
    const CONTROL = "code-scroll.markdown.control";
    const REQUEST = "code-scroll.markdown.state-request";
    const clamp = (value, min, max) => Math.min(max, Math.max(min, Number.isFinite(value) ? value : min));

    function sampleIndices(count, limit) {
        count = Math.max(0, Math.floor(count));
        limit = Math.max(0, Math.floor(limit));
        const size = Math.min(count, limit);
        return Array.from({length: size}, (_, i) => size <= 1 ? 0 : Math.floor(i * (count - 1) / (size - 1)));
    }

    function viewport(scrollTop, visible, total, height) {
        total = Math.max(1, total);
        height = Math.max(0, height);
        const size = Math.min(height, Math.max(8, visible / total * height));
        const range = Math.max(0, total - visible);
        return {top: range ? clamp(scrollTop, 0, range) / range * (height - size) : 0, height: size};
    }

    /**
     * Map height for a page: a fixed scale (about 2 map px per text line) while the page fits, and the
     * whole available height only for pages too long for that. A short page gets a short map.
     */
    function mapHeight(total, available) {
        return Math.max(1, Math.min(available, Math.ceil(Math.max(1, total) * MAP_SCALE)));
    }

    function scrollTarget(y, grab, visible, total, height) {
        const box = viewport(0, visible, total, height);
        const travel = height - box.height;
        return travel > 0 ? clamp((y - grab) / travel, 0, 1) * Math.max(0, total - visible) : 0;
    }

    function wheelPixels(delta, mode, visible) {
        if (!Number.isFinite(delta)) return 0;
        return clamp(delta * (mode === 1 ? 16 : mode === 2 ? visible : 1), -visible * 3, visible * 3);
    }

    /** Number of text lines drawn for a block: about one per 22 CSS px, never denser than 3 map px. */
    function textRows(blockHeight, mapHeight) {
        const lines = Math.max(1, Math.round(blockHeight / 22));
        return Math.max(1, Math.min(lines, Math.floor(mapHeight / 3) || 1, 64));
    }

    function bitmapSize(height, ratio) {
        ratio = clamp(ratio || 1, 1, 2);
        return {width: Math.ceil(WIDTH * ratio), height: Math.ceil(clamp(height, 1, MAX_HEIGHT) * ratio)};
    }

    function mount(win, extensionId) {
        const doc = win.document;
        const canvas = doc.createElement("canvas");
        canvas.setAttribute("aria-label", "Rendered Markdown minimap");
        canvas.setAttribute("role", "scrollbar");
        canvas.setAttribute("aria-orientation", "vertical");
        canvas.tabIndex = 0;
        canvas.title = "Scroll rendered Markdown preview";
        canvas.style.cssText = "position:fixed;right:12px;top:12px;width:96px;z-index:2147483647;" +
            "touch-action:none;cursor:pointer;outline:none;border-left:1px solid rgba(128,128,128,0.25);";
        const previousPadding = doc.documentElement.style.paddingRight;
        const reserve = value => { doc.documentElement.style.paddingRight = value ? GUTTER + "px" : previousPadding; };
        reserve(true);
        const ctx = canvas.getContext("2d", {alpha: false});
        if (!ctx) return null;

        let disposed = false;
        let enabled = true;
        let dirty = true;
        let samples = [];
        let frame = 0;
        let timer = 0;
        let lastSample = -Infinity;
        let drag = null;
        let hovered = false;
        let independentUntil = 0;
        // When true (the default), navigating with the map also scrolls the source editor.
        let syncSource = true;
        let observedBody = null;
        const listeners = [];
        const resize = new win.ResizeObserver(() => invalidate());

        function listen(target, type, handler, options) {
            target.addEventListener(type, handler, options);
            listeners.push(() => target.removeEventListener(type, handler, options));
        }

        function metrics() {
            const root = doc.scrollingElement || doc.documentElement;
            return {root, top: root.scrollTop, visible: root.clientHeight || win.innerHeight,
                total: Math.max(root.scrollHeight, root.clientHeight, 1)};
        }

        function invalidate() {
            dirty = true;
            schedule();
        }

        function schedule() {
            if (!disposed && enabled && !frame) frame = win.requestAnimationFrame(draw);
        }

        function collect(top) {
            const blocks = [];
            const headings = [];
            if (!doc.body) return [];
            const walker = doc.createTreeWalker(doc.body, win.NodeFilter.SHOW_ELEMENT);
            let node;
            let visits = 0;
            while (visits++ < MAX_VISITS && (node = walker.nextNode())) {
                if (/^H[1-6]$/.test(node.tagName)) headings.push(node);
                else if (/^(P|LI|PRE|TABLE|IMG|HR|BLOCKQUOTE)$/.test(node.tagName)) blocks.push(node);
            }
            const result = [];
            for (const [nodes, limit, heading] of [[blocks, MAX_BLOCKS, false], [headings, MAX_HEADINGS, true]]) {
                for (const index of sampleIndices(nodes.length, limit)) {
                    const element = nodes[index];
                    const rect = element.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) continue;
                    result.push({top: rect.top + top, height: rect.height,
                        left: rect.left, width: rect.width, heading});
                }
            }
            return result;
        }

        function draw(time) {
            frame = 0;
            if (disposed || !enabled) return;
            // IncrementalDOM patches the body. Keep our overlay outside that managed subtree.
            if (canvas.parentNode !== doc.documentElement) doc.documentElement.appendChild(canvas);
            if (observedBody !== doc.body) {
                resize.disconnect();
                observedBody = doc.body;
                if (observedBody) resize.observe(observedBody);
                dirty = true;
            }
            const {top, visible, total} = metrics();
            const height = mapHeight(total, Math.max(1, win.innerHeight - 24));
            const size = bitmapSize(height, win.devicePixelRatio);
            if (canvas.width !== size.width || canvas.height !== size.height) {
                canvas.width = size.width;
                canvas.height = size.height;
            }
            canvas.style.height = height + "px";
            ctx.setTransform(size.width / WIDTH, 0, 0, size.height / height, 0, 0);
            const style = win.getComputedStyle(doc.body || doc.documentElement);
            const rootStyle = win.getComputedStyle(doc.documentElement);
            const transparent = color => color === "transparent" || color === "rgba(0, 0, 0, 0)";
            let background = style.backgroundColor;
            if (transparent(background)) background = rootStyle.backgroundColor;
            if (transparent(background)) background = win.matchMedia("(prefers-color-scheme: dark)").matches ? "#202124" : "#ffffff";
            const foreground = style.color || "#808080";
            ctx.globalAlpha = 1;
            ctx.fillStyle = background;
            ctx.fillRect(0, 0, WIDTH, height);
            if (dirty && time - lastSample >= 120) {
                samples = collect(top);
                lastSample = time;
                dirty = false;
            } else if (dirty && !timer) {
                timer = win.setTimeout(() => { timer = 0; schedule(); }, Math.max(1, 120 - (time - lastSample)));
            }
            const contentWidth = Math.max(1, doc.body?.getBoundingClientRect().width || win.innerWidth);
            const scale = height / total;
            ctx.fillStyle = foreground;
            for (const sample of samples) {
                // Draw text blocks as thin text lines, like a code minimap, instead of solid bars.
                const y = clamp(sample.top * scale, 0, height);
                const h = Math.max(1, sample.height * scale);
                const x = sample.heading ? 5 : clamp(sample.left / contentWidth * (WIDTH - 10), 5, WIDTH - 6);
                const w = Math.max(1, Math.min(WIDTH - 5 - x, sample.width / contentWidth * (WIDTH - 10)));
                const rows = textRows(sample.height, h);
                const step = h / rows;
                const thickness = sample.heading ? Math.min(3, Math.max(1, step * 0.6)) : Math.min(2, Math.max(1, step * 0.4));
                ctx.globalAlpha = sample.heading ? 0.55 : 0.28;
                for (let row = 0; row < rows; row++) {
                    const last = rows > 1 && row === rows - 1;
                    ctx.fillRect(x, y + row * step, last ? Math.max(1, w * 0.6) : w, thickness);
                }
            }
            // No frame when the whole page is visible: there is nothing to scroll to.
            if (visible < total) {
                const box = viewport(top, visible, total, height);
                ctx.globalAlpha = FRAME_ALPHA[drag ? 2 : hovered ? 1 : 0];
                ctx.fillRect(0, box.top, WIDTH, box.height);
            }
            ctx.globalAlpha = 1;
            canvas.setAttribute("aria-valuemin", "0");
            canvas.setAttribute("aria-valuemax", String(Math.max(0, total - visible)));
            canvas.setAttribute("aria-valuenow", String(Math.round(top)));
        }

        function navigate(top) {
            const m = metrics();
            // Unless source sync is on, our navigation bypasses Markdown's preview -> source notification.
            independentUntil = syncSource ? 0 : win.performance.now() + 200;
            win.scrollTo({left: m.root.scrollLeft, top: clamp(top, 0, Math.max(0, m.total - m.visible)), behavior: "instant"});
            schedule();
        }

        function point(event) {
            return event.clientY - canvas.getBoundingClientRect().top;
        }

        function move(event) {
            if (!drag || drag.id !== event.pointerId) return;
            const m = metrics();
            navigate(scrollTarget(point(event), drag.grab, m.visible, m.total, canvas.getBoundingClientRect().height));
            event.preventDefault();
            event.stopPropagation();
        }

        listen(canvas, "pointerdown", event => {
            if (event.button !== 0 || !enabled) return;
            const m = metrics();
            const box = viewport(m.top, m.visible, m.total, canvas.getBoundingClientRect().height);
            const y = point(event);
            drag = {id: event.pointerId, grab: y >= box.top && y <= box.top + box.height ? y - box.top : box.height / 2};
            canvas.setPointerCapture(event.pointerId);
            canvas.focus({preventScroll: true});
            move(event);
        });
        listen(canvas, "pointermove", event => {
            const m = metrics();
            const box = viewport(m.top, m.visible, m.total, canvas.getBoundingClientRect().height);
            const y = point(event);
            const over = y >= box.top && y <= box.top + box.height;
            if (over !== hovered) { hovered = over; schedule(); }
            move(event);
        });
        listen(canvas, "pointerleave", () => { if (hovered) { hovered = false; schedule(); } });
        const endDrag = event => {
            if (!drag || drag.id !== event.pointerId) return;
            drag = null;
            schedule();
            if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
        };
        listen(canvas, "pointerup", endDrag);
        listen(canvas, "pointercancel", endDrag);
        listen(canvas, "lostpointercapture", endDrag);
        listen(canvas, "wheel", event => {
            if (event.ctrlKey || !enabled) return;
            const m = metrics();
            navigate(m.top + wheelPixels(event.deltaY, event.deltaMode, m.visible));
            event.preventDefault();
            event.stopPropagation();
        }, {passive: false});
        listen(canvas, "keydown", event => {
            const m = metrics();
            const targets = {ArrowDown: m.top + 40, ArrowUp: m.top - 40, PageDown: m.top + m.visible,
                PageUp: m.top - m.visible, Home: 0, End: m.total - m.visible};
            if (!(event.key in targets)) return;
            navigate(targets[event.key]);
            event.preventDefault();
            event.stopPropagation();
        });
        listen(doc, "scroll", event => {
            if (event.target !== doc && event.target !== doc.scrollingElement) return;
            schedule();
            if (enabled && win.performance.now() < independentUntil) event.stopImmediatePropagation();
        }, true);
        listen(doc, "pointerdown", event => { if (event.target !== canvas) independentUntil = 0; }, true);
        listen(doc, "wheel", event => { if (event.target !== canvas) independentUntil = 0; }, {capture: true, passive: true});
        listen(doc, "keydown", event => { if (event.target !== canvas) independentUntil = 0; }, true);
        listen(win, "resize", invalidate);
        listen(doc, "load", invalidate, true);
        listen(doc, "error", invalidate, true);

        const mutation = new win.MutationObserver(records => {
            if (records.some(record => record.target !== canvas &&
                !(record.type === "childList" && record.addedNodes.length === 1 && record.addedNodes[0] === canvas && !record.removedNodes.length))) {
                invalidate();
            }
        });
        mutation.observe(doc.documentElement, {subtree: true, childList: true, characterData: true, attributes: true});
        const media = win.matchMedia("(prefers-color-scheme: dark)");
        listen(media, "change", invalidate);
        if (doc.fonts) listen(doc.fonts, "loadingdone", invalidate);

        function dispose() {
            if (disposed) return;
            disposed = true;
            if (drag && canvas.hasPointerCapture(drag.id)) canvas.releasePointerCapture(drag.id);
            drag = null;
            mutation.disconnect();
            resize.disconnect();
            listeners.forEach(remove => remove());
            win.cancelAnimationFrame(frame);
            win.clearTimeout(timer);
            win.clearInterval(poll);
            canvas.remove();
            reserve(false);
            samples = [];
            if (win.__codeScrollMarkdownMinimap === api) delete win.__codeScrollMarkdownMinimap;
        }

        const api = {extensionId, dispose, setSync(value) {
            syncSource = !!value;
            if (syncSource) independentUntil = 0;
        }, setEnabled(value) {
            if (enabled === value) return;
            enabled = value;
            canvas.hidden = !value;
            reserve(value);
            if (!value) {
                independentUntil = 0;
                if (drag && canvas.hasPointerCapture(drag.id)) canvas.releasePointerCapture(drag.id);
                drag = null;
            } else invalidate();
        }};
        const poll = win.setInterval(() => win.__IntelliJTools?.messagePipe?.post(REQUEST, extensionId), 1000);
        listen(win, "pagehide", dispose);
        schedule();
        return api;
    }

    if (typeof module === "object" && module.exports) {
        module.exports = {sampleIndices, viewport, mapHeight, scrollTarget, wheelPixels, bitmapSize, textRows, mount};
        return;
    }
    const win = window;
    win.__codeScrollMarkdownMinimap?.dispose();
    const pipe = win.__IntelliJTools?.messagePipe;
    // BrowserPipe has no public JS unsubscribe. One page-scoped dispatcher never captures a mounted minimap.
    if (pipe && win.__codeScrollMarkdownControlPipe !== pipe) {
        win.__codeScrollMarkdownControlPipe = pipe;
        pipe.subscribe(CONTROL, data => {
            const current = win.__codeScrollMarkdownMinimap;
            if (!current) return;
            const [id, command, sync] = data.split(":");
            if (id !== current.extensionId) return;
            if (command === "dispose") current.dispose();
            else if (command === "on" || command === "off") {
                current.setEnabled(command === "on");
                if (sync === "sync" || sync === "nosync") current.setSync(sync === "sync");
            }
        });
    }
    win.__codeScrollMarkdownMinimap = mount(win, win.__codeScrollMarkdownExtensionId);
    delete win.__codeScrollMarkdownExtensionId;
})();
