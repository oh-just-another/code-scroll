// SPDX-License-Identifier: MIT
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const {sampleIndices, viewport, mapHeight, scrollTarget, wheelPixels, bitmapSize, textRows, mount} =
    require("../../main/resources/dev/codescroll/markdown/rendered-minimap.js");

test("bounded sampling spans the document and includes both endpoints", () => {
    assert.deepEqual(sampleIndices(0, 10), []);
    assert.deepEqual(sampleIndices(4, 10), [0, 1, 2, 3]);
    assert.deepEqual(sampleIndices(100, 1), [0]);
    assert.deepEqual(sampleIndices(100, 0), []);
    const indices = sampleIndices(24000, 1280);
    assert.equal(indices.length, 1280);
    assert.equal(indices[0], 0);
    assert.equal(indices.at(-1), 23999);
    assert.equal(new Set(indices).size, 1280);
});

test("viewport stays inside map, including a minimum-height thumb", () => {
    assert.deepEqual(viewport(0, 500, 500, 400), {top: 0, height: 400});
    assert.deepEqual(viewport(0, 500, 10000, 400), {top: 0, height: 20});
    assert.deepEqual(viewport(9500, 500, 10000, 400), {top: 380, height: 20});
    assert.deepEqual(viewport(999999, 100, 1000000, 400), {top: 392, height: 8});
    assert.deepEqual(viewport(-500, 500, 10000, 400), {top: 0, height: 20});
    assert.deepEqual(viewport(0, 500, 0, 4), {top: 0, height: 4});
});

test("drag mapping is invertible and clamps at both document ends", () => {
    for (const top of [0, 200, 5000, 990000]) {
        const box = viewport(top, 100, 1000000, 400);
        const result = scrollTarget(box.top + 3, 3, 100, 1000000, 400);
        assert.ok(Math.abs(result - top) < 0.00001);
    }
    assert.equal(scrollTarget(-100, 4, 500, 10000, 400), 0);
    assert.equal(scrollTarget(900, 4, 500, 10000, 400), 9500);
    assert.equal(scrollTarget(200, 4, 500, 500, 400), 0);
});

test("wheel normalizes pixel, line and page deltas with a bounded jump", () => {
    assert.equal(wheelPixels(12, 0, 500), 12);
    assert.equal(wheelPixels(-3, 1, 500), -48);
    assert.equal(wheelPixels(2, 2, 500), 1000);
    assert.equal(wheelPixels(1e10, 0, 500), 1500);
    assert.equal(wheelPixels(NaN, 0, 500), 0);
});

test("short pages get a short map at fixed scale; long pages fill the available height", () => {
    assert.equal(mapHeight(500, 800), 50);
    assert.equal(mapHeight(5000, 800), 500);
    assert.equal(mapHeight(100000, 800), 800);
    assert.equal(mapHeight(0, 800), 1);
});

test("text blocks become thin lines without exceeding the map space they occupy", () => {
    assert.equal(textRows(22, 100), 1);
    assert.equal(textRows(110, 100), 5);
    assert.equal(textRows(110, 6), 2);
    assert.equal(textRows(110, 1), 1);
    assert.equal(textRows(100000, 10000), 64);
});

test("canvas allocation is independent of document length and caps DPR", () => {
    assert.deepEqual(bitmapSize(500, 1), {width: 96, height: 500});
    assert.deepEqual(bitmapSize(1000000, 8), {width: 192, height: 3200});
    assert.deepEqual(bitmapSize(0, 0), {width: 96, height: 1});
});

function browser() {
    class Target {
        listeners = [];
        addEventListener(type, fn, options) { this.listeners.push({type, fn, capture: options === true || options?.capture}); }
        removeEventListener(type, fn) { this.listeners = this.listeners.filter(item => item.type !== type || item.fn !== fn); }
        emit(type, props = {}) {
            const event = {target: this, preventDefault() { this.prevented = true; },
                stopPropagation() {}, stopImmediatePropagation() { this.stopped = true; }, ...props};
            const ordered = this.listeners.filter(item => item.type === type).sort((a, b) => Number(!!b.capture) - Number(!!a.capture));
            for (const item of ordered) { item.fn(event); if (event.stopped) break; }
            return event;
        }
    }
    class Element extends Target {
        constructor(tagName, top = 0) { super(); this.tagName = tagName; this.top = top; }
        style = {};
        attributes = {};
        children = [];
        parentNode = null;
        getBoundingClientRect() {
            return {top: this.tagName === "CANVAS" ? 12 : this.top - root.scrollTop,
                left: 20, width: 700, height: this.tagName === "CANVAS" ? 476 : 40};
        }
        setAttribute(name, value) { this.attributes[name] = value; }
        appendChild(child) { child.remove(); child.parentNode = this; this.children.push(child); }
        remove() {
            if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this);
            this.parentNode = null;
        }
        getContext() { return context; }
        setPointerCapture(id) { this.pointer = id; }
        hasPointerCapture(id) { return this.pointer === id; }
        releasePointerCapture() { this.pointer = null; }
        focus() {}
    }
    let layouts = 0;
    let visits = 0;
    let now = 0;
    let next = 1;
    const frames = new Map();
    const timers = new Map();
    const intervals = new Map();
    const observers = [];
    const fills = [];
    const context = {setTransform() {}, fillRect(...args) { fills.push(args); }, strokeRect() {}};
    const root = new Element("HTML");
    Object.assign(root, {scrollTop: 0, scrollLeft: 0, scrollHeight: 10000, clientHeight: 500});
    const doc = new Target();
    Object.assign(doc, {documentElement: root, scrollingElement: root, body: new Element("BODY"), fonts: new Target()});
    let canvas;
    doc.createElement = tag => (canvas = new Element(tag.toUpperCase()));
    doc.createTreeWalker = () => ({nextNode() {
        visits++;
        const node = new Element(visits % 50 === 0 ? "H2" : "P", visits % 10000);
        const rect = node.getBoundingClientRect.bind(node);
        node.getBoundingClientRect = () => { layouts++; return rect(); };
        return node;
    }});
    class Observer {
        constructor(callback) { this.callback = callback; this.connected = false; observers.push(this); }
        observe() { this.connected = true; }
        disconnect() { this.connected = false; }
    }
    const media = new Target();
    media.matches = false;
    const win = new Target();
    Object.assign(win, {document: doc, NodeFilter: {SHOW_ELEMENT: 1}, ResizeObserver: Observer, MutationObserver: Observer,
        innerHeight: 500, innerWidth: 800, devicePixelRatio: 4, performance: {now: () => now},
        getComputedStyle: () => ({color: "#333333", backgroundColor: "#ffffff"}), matchMedia: () => media,
        requestAnimationFrame: fn => { const id = next++; frames.set(id, fn); return id; },
        cancelAnimationFrame: id => frames.delete(id),
        setTimeout: fn => { const id = next++; timers.set(id, fn); return id; }, clearTimeout: id => timers.delete(id),
        setInterval: fn => { const id = next++; intervals.set(id, fn); return id; }, clearInterval: id => intervals.delete(id),
        scrollTo: ({top}) => { root.scrollTop = top; doc.emit("scroll"); },
        __IntelliJTools: {messagePipe: {post() {}}}});
    function tick(time) {
        now = time;
        const pending = [...frames.values()];
        frames.clear();
        pending.forEach(fn => fn(time));
    }
    return {win, doc, root, media, observers, frames, timers, intervals, fills, tick,
        get canvas() { return canvas; }, get layouts() { return layouts; }, get visits() { return visits; }};
}

test("mount samples with hard traversal/layout caps and does not alter the Markdown body", () => {
    const b = browser();
    const minimap = mount(b.win, "test");
    b.tick(0);
    assert.equal(b.canvas.parentNode, b.root);
    assert.equal(b.doc.body.children.length, 0);
    assert.equal(b.visits, 24000);
    assert.ok(b.layouts <= 1536);
    assert.equal(b.canvas.width, 192);
    assert.equal(b.canvas.height, 952);
    assert.ok(b.fills.length > 1);
    b.doc.emit("scroll");
    b.tick(10);
    assert.equal(b.visits, 24000, "scroll redraw reuses sampled geometry");
    minimap.dispose();
});

test("map navigation scrolls the source editor too by default", () => {
    const b = browser();
    let sourceNotifications = 0;
    b.doc.addEventListener("scroll", () => sourceNotifications++);
    const minimap = mount(b.win, "test");
    b.tick(0);
    b.canvas.emit("pointerdown", {button: 0, pointerId: 7, clientY: 250});
    assert.ok(b.root.scrollTop > 4000);
    assert.ok(sourceNotifications > 0, "preview scroll reaches Markdown's source synchronization");
    minimap.dispose();
});

test("with source sync off, map navigation scrolls only the preview", () => {
    const b = browser();
    let sourceNotifications = 0;
    b.doc.addEventListener("scroll", () => sourceNotifications++);
    const minimap = mount(b.win, "test");
    minimap.setSync(false);
    b.tick(0);
    const down = b.canvas.emit("pointerdown", {button: 0, pointerId: 7, clientY: 250});
    assert.equal(down.prevented, true);
    assert.ok(b.root.scrollTop > 4000);
    assert.equal(sourceNotifications, 0);
    b.canvas.emit("pointermove", {pointerId: 7, clientY: 800});
    assert.equal(b.root.scrollTop, 9500);
    b.canvas.emit("pointerup", {pointerId: 7});
    assert.equal(b.canvas.pointer, null);
    b.canvas.emit("wheel", {deltaY: -3, deltaMode: 1});
    assert.equal(b.root.scrollTop, 9452);
    b.canvas.emit("keydown", {key: "Home"});
    assert.equal(b.root.scrollTop, 0);
    b.doc.emit("wheel", {target: b.doc.body});
    b.doc.emit("scroll");
    assert.equal(sourceNotifications, 1, "ordinary preview scrolling still synchronizes normally");
    minimap.dispose();
});

test("rerender, theme, resize and image events coalesce; removed overlay is restored", () => {
    const b = browser();
    const minimap = mount(b.win, "test");
    b.tick(0);
    const mutation = b.observers[1];
    for (let i = 0; i < 50; i++) mutation.callback([{type: "characterData", target: b.doc.body}]);
    b.win.emit("resize");
    b.media.emit("change");
    b.doc.emit("load");
    assert.equal(b.frames.size, 1);
    b.tick(20);
    assert.equal(b.visits, 24000, "mutation sampling is throttled");
    assert.equal(b.timers.size, 1);
    const pending = [...b.timers.values()];
    b.timers.clear();
    pending.forEach(fn => fn());
    b.tick(120);
    assert.equal(b.visits, 48000);
    mutation.callback([{type: "attributes", target: b.canvas}]);
    assert.equal(b.frames.size, 0, "drawing does not create an observer feedback loop");
    b.canvas.remove();
    mutation.callback([{type: "childList", target: b.root, addedNodes: [], removedNodes: [b.canvas]}]);
    b.tick(250);
    assert.equal(b.canvas.parentNode, b.root);
    minimap.dispose();
});

test("settings disable cancels dragging; dispose removes every active listener, observer and timer", () => {
    const b = browser();
    const minimap = mount(b.win, "test");
    b.win.__codeScrollMarkdownMinimap = minimap;
    b.tick(0);
    b.canvas.emit("pointerdown", {button: 0, pointerId: 7, clientY: 250});
    assert.equal(b.root.style.paddingRight, "120px");
    minimap.setEnabled(false);
    assert.equal(b.canvas.hidden, true);
    assert.equal(b.root.style.paddingRight, undefined);
    assert.equal(b.canvas.pointer, null);
    b.tick(250);
    const visits = b.visits;
    b.observers[1].callback([{type: "attributes", target: b.doc.body}]);
    assert.equal(b.frames.size, 0);
    assert.equal(b.visits, visits);
    minimap.setEnabled(true);
    assert.equal(b.canvas.hidden, false);
    assert.equal(b.frames.size, 1);
    assert.equal(b.root.style.paddingRight, "120px");
    minimap.dispose();
    minimap.dispose();
    assert.equal(b.canvas.parentNode, null);
    // The reserved gutter is released, restoring the page's own padding.
    assert.equal(b.root.style.paddingRight, undefined);
    assert.equal(b.frames.size + b.timers.size + b.intervals.size, 0);
    assert.ok(b.observers.every(observer => !observer.connected));
    for (const target of [b.win, b.doc, b.canvas, b.media, b.doc.fonts]) assert.equal(target.listeners.length, 0);
    assert.equal(b.win.__codeScrollMarkdownMinimap, undefined);
});

test("injected script isolates extension tokens and installs just one page-scoped control dispatcher", () => {
    const source = fs.readFileSync(require.resolve("../../main/resources/dev/codescroll/markdown/rendered-minimap.js"), "utf8");
    const b = browser();
    const handlers = [];
    b.win.__IntelliJTools.messagePipe.subscribe = (type, handler) => handlers.push({type, handler});
    const execute = id => vm.runInNewContext("window.__codeScrollMarkdownExtensionId = '" + id + "';\n" + source, {window: b.win});
    execute("first");
    b.tick(0);
    assert.equal(handlers.length, 1);
    assert.equal(handlers[0].type, "code-scroll.markdown.control");
    const firstCanvas = b.canvas;
    execute("second");
    b.tick(200);
    assert.equal(firstCanvas.parentNode, null);
    assert.equal(handlers.length, 1);
    handlers[0].handler("first:dispose");
    assert.equal(b.win.__codeScrollMarkdownMinimap.extensionId, "second");
    handlers[0].handler("second:on");
    assert.equal(b.frames.size, 0, "unchanged polled state does not resample the DOM");
    handlers[0].handler("second:off");
    assert.equal(b.canvas.hidden, true);
    handlers[0].handler("second:on");
    assert.equal(b.canvas.hidden, false);
    handlers[0].handler("second:dispose");
    assert.equal(b.win.__codeScrollMarkdownMinimap, undefined);
    assert.equal(b.canvas.parentNode, null);
    assert.equal(b.intervals.size, 0);
});
