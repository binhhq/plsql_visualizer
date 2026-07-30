// Runs the ENTIRE renderer IIFE from renderer.html against a minimal DOM stub,
// then drives the package filter and grades what got drawn.
//
//   node samples/tests/render-test.js

const { rendererSource, irText, check, summary } = require("./harness");

const src = rendererSource();

let idSeq = 0;
function El(tag) {
  const e = {
    tagName: tag, _id: ++idSeq, children: [], attrs: {}, listeners: {},
    style: {}, dataset: {}, value: "", max: "", min: "", placeholder: "",
    _text: "",
    classList: {
      _s: new Set(),
      add(...c) { c.forEach((x) => this._s.add(x)); },
      remove(...c) { c.forEach((x) => this._s.delete(x)); },
      toggle(c, on) { on === undefined ? (this._s.has(c) ? this._s.delete(c) : this._s.add(c)) : (on ? this._s.add(c) : this._s.delete(c)); },
      contains(c) { return this._s.has(c); },
    },
    get textContent() { return this._text; },
    set textContent(v) { this._text = String(v); if (v === "") this.children = []; },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    getAttribute(k) { return this.attrs[k]; },
    appendChild(c) { this.children.push(c); c.parentNode = this; return c; },
    addEventListener(t, fn) { (this.listeners[t] = this.listeners[t] || []).push(fn); },
    dispatch(t, ev) { (this.listeners[t] || []).forEach((fn) => fn.call(this, ev || { target: this, preventDefault() {}, key: "" })); },
    focus() {}, blur() {},
  };
  return e;
}

const byId = {};
function idEl(id) { return (byId[id] = byId[id] || El("div")); }

const canvasEl = El("div");
global.document = {
  getElementById: (id) => idEl(id),
  querySelector: (sel) => (sel === ".canvas" ? canvasEl : El("div")),
  createElement: (t) => El(t),
  createElementNS: (ns, t) => El(t),
  addEventListener() {},
};
global.window = { addEventListener() {} };
global.setTimeout = (fn) => { fn(); return 0; };
global.clearTimeout = () => {};

// Seed the IR the script parses out of the DOM.
idEl("ir-data").textContent = irText(src);

// Pull the real IIFE and run it.
const s = src.indexOf('(function () {\n  "use strict";');
const e = src.indexOf("})();", s) + 5;
if (s < 0 || e < 5) throw new Error("cannot locate the renderer IIFE — the anchors moved");
eval(src.slice(s, e));

// ---- drive it -------------------------------------------------------------
const search = idEl("search");
const nodesLayer = idEl("layer-nodes");
const edgesLayer = idEl("layer-edges");

function shot(label, expect) {
  const chipOn = idEl("scope-chip").classList.contains("on");
  const empty = canvasEl.classList.contains("empty");
  const state = {
    nodes: nodesLayer.children.length,
    edges: edgesLayer.children.length,
    empty: empty,
    chip: chipOn ? idEl("scope-text").textContent : null,
  };
  console.log(
    "  " + label.padEnd(22),
    "nodes=" + String(state.nodes).padStart(2),
    "edges=" + String(state.edges).padStart(2),
    "empty=" + (empty ? "Y" : "n"),
    "chip=" + (chipOn ? JSON.stringify(state.chip) : "off"),
  );
  if (empty) console.log(" ".repeat(24), "msg:", JSON.stringify(idEl("noscope").textContent));
  check("    " + label, state, expect);
  return state;
}

function type(q) {
  search.value = q;
  search.dispatch("input", { target: search });
}

const FULL = { nodes: 12, edges: 14, empty: false, chip: null };

console.log("initial render");
shot("(no filter)", FULL);

console.log("\npackage filter via input event:");
type("PKG_ORDER");
shot('"PKG_ORDER"', { nodes: 9, edges: 10, empty: false, chip: "scope: PKG_ORDER · 10 edges" });
type("pkg_validate");
shot('"pkg_validate"', { nodes: 3, edges: 2, empty: false, chip: "scope: PKG_VALIDATE · 2 edges" });
type("PKG_DYNAMIC");
shot('"PKG_DYNAMIC"', { nodes: 3, edges: 2, empty: false, chip: "scope: PKG_DYNAMIC · 2 edges" });

// A query matching nothing must show the empty state and NO chip. computeScope
// returns inScope [] here — truthy — so a naive `if (scope.inScope)` lights the
// chip up with an empty package name. That regressed once; this pins it.
type("tx9001");
shot('"tx9001"', { nodes: 0, edges: 0, empty: true, chip: null });
check("    no-match message names the packages",
  idEl("noscope").textContent,
  'No package matches "tx9001". Packages in this IR: PKG_DYNAMIC, PKG_ORDER, PKG_POSITION, PKG_VALIDATE');

type("");
shot('""', FULL);

console.log("\nclear button:");
type("PKG_ORDER");
idEl("scope-clear").dispatch("click");
shot("after clear", FULL);
check("    search box reset", search.value, "");

console.log("\nscrub + counter after scoping to PKG_VALIDATE:");
type("PKG_VALIDATE");
console.log("  scrub.max =", JSON.stringify(idEl("scrub").max), " counter =", JSON.stringify(idEl("counter").textContent));
// The scrub indexes the scoped edges, not the whole IR — otherwise stepping
// walks off the end of what is drawn.
check("    scrub.max", idEl("scrub").max, "2");
check("    counter", idEl("counter").textContent, "step 2 / 2");

summary("render-test");
