// Drives the trace_order toggle against the real renderer and the real traced IR.
//
//   node samples/tests/trace-mode-test.js
//
// The button shipped for a while as markup with no handler behind it, so this
// exists to prove the mode does something — and specifically that it does the
// honest thing with a write the traced run never reached.

const { rendererSource, irText, check, summary } = require("./harness");

const src = rendererSource();

let idSeq = 0;
function El(tag) {
  const e = {
    tagName: tag, _id: ++idSeq, children: [], attrs: {}, listeners: {},
    style: {}, dataset: {}, value: "", max: "", min: "", placeholder: "",
    disabled: false, _text: "",
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
    removeAttribute(k) { delete this.attrs[k]; },
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
  createTextNode: (t) => { const e = El("#text"); e.textContent = t; return e; },
  addEventListener() {},
};
global.window = { addEventListener() {} };
global.setTimeout = (fn) => { fn(); return 0; };
global.clearTimeout = () => {};

const IR = JSON.parse(irText(src));
idEl("ir-data").textContent = irText(src);

// The drawing engine, found by its marker — the page also carries a unit-loader
// script ahead of it that only makes sense against a live server.
const marker = src.indexOf("/* renderer:main");
const s = src.indexOf('(function () {\n  "use strict";', marker);
const e = src.indexOf("})();", s) + 5;
if (marker < 0 || s < 0 || e < 5) {
  throw new Error("cannot locate the renderer IIFE — the anchors moved");
}
eval(src.slice(s, e));

// ---- what the embedded IR contains ---------------------------------------
const ran = IR.edges.filter((x) => x.trace_order != null);
const neverRan = IR.edges.filter((x) => x.trace_order == null);
console.log(`embedded IR: ${IR.edges.length} edges — ${ran.length} traced, ${neverRan.length} without a trace position`);
console.log(`trace_source: ${JSON.stringify(IR.meta.trace_source)}`);
console.log();

const traceBtn = idEl("order-trace");
const staticBtn = idEl("order-static");

// The stub builds elements from ids, not from the markup, so the attributes the
// HTML declares are not there. A browser starts with these; seed them so the
// initial state under test is the real initial state.
staticBtn.setAttribute("aria-pressed", "true");
traceBtn.setAttribute("aria-pressed", "false");

console.log("static mode (initial):");
check("  trace button enabled", traceBtn.disabled, false);
check("  static pressed", staticBtn.getAttribute("aria-pressed"), "true");
check("  trace not pressed", traceBtn.getAttribute("aria-pressed"), "false");
check("  counter counts every edge", idEl("counter").textContent,
  "step " + IR.edges.length + " / " + IR.edges.length);
check("  freshness names the scenario", idEl("fresh-trace").textContent,
  "place_order_hose · captured 2026-07-30 10:35:56Z");
console.log();

console.log("switch to trace_order:");
traceBtn.dispatch("click");
check("  trace pressed", traceBtn.getAttribute("aria-pressed"), "true");
check("  static released", staticBtn.getAttribute("aria-pressed"), "false");
// Only what ran can be sequenced. The rest is still drawn — see below — but it
// has no position, so counting it would invent an order that never happened.
check("  sequence is the traced edges only", idEl("scrub").max, String(ran.length));
check("  counter says trace", idEl("counter").textContent,
  "trace " + ran.length + " / " + ran.length);
console.log("  scrub note:", JSON.stringify(idEl("scrub-note").textContent));
console.log();

console.log("what is on screen in trace mode:");
// Every edge is still drawn: dropping the ones that did not run would turn
// "this did not happen in this scenario" into "this does not exist".
check("  all edges still drawn", idEl("layer-edges").children.length, IR.edges.length);
console.log();

console.log("back to static:");
staticBtn.dispatch("click");
check("  static pressed again", staticBtn.getAttribute("aria-pressed"), "true");
check("  full sequence restored", idEl("scrub").max, String(IR.edges.length));
check("  counter says step", idEl("counter").textContent,
  "step " + IR.edges.length + " / " + IR.edges.length);
console.log();

console.log("trace mode survives a package filter:");
traceBtn.dispatch("click");
const search = idEl("search");
search.value = "PKG_DYNAMIC";
search.dispatch("input", { target: search });
check("  still in trace mode", traceBtn.getAttribute("aria-pressed"), "true");
check("  counter still says trace",
  idEl("counter").textContent.startsWith("trace "), true);

summary("trace-mode-test");
