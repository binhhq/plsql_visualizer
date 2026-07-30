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

const menu = idEl("proc-menu");

function type(q) {
  search.value = q;
  search.dispatch("input", { target: search });
}

// What the typeahead is offering, as "PKG.PROC" strings.
function offered() {
  return menu.children
    .filter((li) => !li.classList.contains("none"))
    .map((li) => li.children.map((c) => c.textContent).join(""));
}

// Selecting is a mousedown, not a click — a click would blur the input and
// close the menu before the choice landed.
function pick(i) {
  menu.children[i].dispatch("mousedown", { preventDefault() {} });
}

const FULL = { nodes: 13, edges: 15, empty: false, chip: null };

console.log("initial render");
shot("(no filter)", FULL);

// Typing offers procedures; it must NOT touch the graph. Naming a procedure is
// not the same as choosing one, and revealing on keystroke was the behaviour
// this replaced.
console.log("\ntyping offers procedures and leaves the graph alone:");
type("PKG_ORDER");
console.log("  offers:", JSON.stringify(offered()));
check("    matches for \"PKG_ORDER\"", offered(), ["PKG_ORDER.MARK_FILLED", "PKG_ORDER.SUBMIT"]);
shot("  graph untouched", FULL);

type("submit");
check("    matching on the subprogram half", offered(), ["PKG_ORDER.SUBMIT"]);

type("tx9001");
check("    no match offers nothing", offered(), []);
check("    and says so", menu.children[0].textContent, 'no procedure matches "tx9001"');
shot("  still untouched", FULL);

console.log("\nselecting a procedure scopes to it:");
type("PKG_VALIDATE");
pick(0);
shot('picked PKG_VALIDATE.CHECK_ORDER',
  { nodes: 3, edges: 2, empty: false, chip: "focus: PKG_VALIDATE.CHECK_ORDER · 2 edges" });
check("    search box cleared on pick", search.value, "");
check("    menu closed", menu.classList.contains("open"), false);

// The scrub indexes the focused edges, not the whole IR — otherwise stepping
// walks off the end of what is drawn.
check("    scrub.max", idEl("scrub").max, "2");
check("    counter", idEl("counter").textContent, "step 2 / 2");

// Picking another procedure replaces the view rather than adding to it.
type("LOG_DYNAMIC");
pick(0);
// Three writes, one of them the dynamic-unknown target and one only the trace
// could name — so focusing this procedure is what makes that pair visible.
shot("picked PKG_DYNAMIC.LOG_DYNAMIC",
  { nodes: 4, edges: 3, empty: false, chip: "focus: PKG_DYNAMIC.LOG_DYNAMIC · 3 edges" });

console.log("\nclear button drops the focus:");
idEl("scope-clear").dispatch("click");
shot("after clear", FULL);

// Clicking the focused procedure again is the way back out — the same gesture
// in both directions, so there is no dead end.
console.log("\nclicking a procedure node focuses it, clicking it again clears:");
const unitNode = nodesLayer.children.find((g) =>
  (g.attrs["aria-label"] || "").indexOf("PKG_VALIDATE.CHECK_ORDER") !== -1);
unitNode.dispatch("click", { preventDefault() {} });
shot("clicked the node", { nodes: 3, edges: 2, empty: false,
  chip: "focus: PKG_VALIDATE.CHECK_ORDER · 2 edges" });
nodesLayer.children
  .find((g) => (g.attrs["aria-label"] || "").indexOf("PKG_VALIDATE.CHECK_ORDER") !== -1)
  .dispatch("click", { preventDefault() {} });
shot("clicked it again", FULL);

// classList.toggle(name, undefined) flips instead of clearing, so an unset
// filter used to invert .dimmed on every render — the graph strobed between
// full and 16% opacity once per playback tick. Repeated renders with no filter
// active must leave every edge undimmed.
console.log("\nrepeated renders with no filter must not strobe .dimmed:");
type("");
const scrubEl = idEl("scrub");
const dimmed = () => edgesLayer.children.filter((g) => g.classList.contains("dimmed")).length;
const strobe = [dimmed()];
for (let i = 0; i < 3; i++) { scrubEl.dispatch("input"); strobe.push(dimmed()); }
console.log("  dimmed count per render:", JSON.stringify(strobe));
check("    dimmed edges across 4 renders", strobe, [0, 0, 0, 0]);

// Every edge in the steppable sequence carries an ordinal; the never-ran set
// carries none, because it holds no position in the run.
const discs = () => edgesLayer.children
  .filter((g) => g.children.some((c) => c.attrs && c.attrs.class === "seq-bg")).length;
console.log("\nordinal discs:");
check("    one per sequenced edge (static: all 15)", discs(), 15);
type("PKG_VALIDATE");
pick(0);
check("    renumbered from 1 for the focused sequence", discs(), 2);
idEl("scope-clear").dispatch("click");

summary("render-test");
