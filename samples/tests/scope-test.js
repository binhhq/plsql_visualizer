// Runs the REAL computeScope source, lifted verbatim out of renderer.html,
// against the REAL embedded IR. No DOM needed for the scope algorithm.
//
//   node samples/tests/scope-test.js

const { rendererSource, irText, check, summary } = require("./harness");

const src = rendererSource();
const IR = JSON.parse(irText(src));

// Real source lines: allEdges/nodeById/packages … through the end of computeScope.
const start = src.indexOf("  var allEdges = IR.edges.slice()");
const end = src.indexOf("  // --------------------------------------------------------------- layout");
if (start < 0 || end < 0) {
  throw new Error("cannot locate the scope block in renderer.html — the anchors moved");
}
let logic = src.slice(start, end);

// Strip only the DOM-writing lines (datalist + placeholder); scope logic untouched.
logic = logic
  .replace(/  var dl = document[\s\S]*?\n  \}\);\n/, "")
  .replace(/  document\.getElementById\("search"\)\.placeholder =[\s\S]*?;\n/, "");

const run = new Function("IR", logic + "\n return { computeScope, packages, unitOf, allEdges };");
const api = run(IR);

console.log("packages detected:", api.packages.join(", "));
console.log("total edges:", api.allEdges.length);
console.log();

check("packages", api.packages, ["PKG_DYNAMIC", "PKG_ORDER", "PKG_POSITION", "PKG_VALIDATE"]);
check("total edges", api.allEdges.length, 14);
console.log();

// Prints the subgraph a query yields, then grades it.
function report(q, expect) {
  const r = api.computeScope(q);
  const nodes = new Set();
  r.edges.forEach((e) => { nodes.add(e.from); nodes.add(e.to); });

  console.log(`query ${JSON.stringify(q)}  -> matched=[${r.matched.join(",")}] edges=${r.edges.length} nodes=${nodes.size}`);
  r.edges.forEach((e) => {
    const dir = api.unitOf(e.from) && r.inScope && r.inScope.includes(api.unitOf(e.from)) ? "out" : "in ";
    console.log(`    ${dir} step${String(e.step).padStart(2)} ${e.type.padEnd(5)} ${String(e.confidence || "static").padEnd(18)} ${e.from} -> ${e.to}`);
  });

  check(`  ${JSON.stringify(q)} matched`, r.matched, expect.matched);
  check(`  ${JSON.stringify(q)} edges`, r.edges.length, expect.edges);
  check(`  ${JSON.stringify(q)} nodes`, nodes.size, expect.nodes);
  console.log();
  return r;
}

const ALL = ["PKG_DYNAMIC", "PKG_ORDER", "PKG_POSITION", "PKG_VALIDATE"];
const ORDER_SCOPE = { matched: ["PKG_ORDER"], edges: 10, nodes: 9 };

const unfiltered = report("", { matched: ALL, edges: 14, nodes: 12 });
check("no query leaves inScope null", unfiltered.inScope, null);
console.log();

report("PKG_ORDER", ORDER_SCOPE);
report("pkg_order", ORDER_SCOPE);   // case-insensitive
report("order", ORDER_SCOPE);       // substring
report("PKG_VALIDATE", { matched: ["PKG_VALIDATE"], edges: 2, nodes: 3 });
report("PKG_DYNAMIC", { matched: ["PKG_DYNAMIC"], edges: 2, nodes: 3 });
report("tx9001", { matched: [], edges: 0, nodes: 0 });   // no such package

// --------------------------------------------------- the scope contract itself
// These are the rules design.md §8 and the renderer's own doc comment promise.
// The counts above would still pass if the wrong edges were picked; these say
// *which* edges have to be there.
console.log("scope contract:");

const idsOf = (q) => api.computeScope(q).edges.map((e) => e.id).sort();
const has = (q, id) => idsOf(q).includes(id);

const triggerEdge = api.allEdges.find((e) => e.confidence === "trigger-induced");
const dynamicEdge = api.allEdges.find((e) => e.confidence === "dynamic-unknown");
const inboundToValidate = api.allEdges.find(
  (e) => e.type === "call" && api.unitOf(e.to) === "PKG_VALIDATE" && api.unitOf(e.from) !== "PKG_VALIDATE"
);

// Honesty rule: a write PKG_ORDER caused via a trigger is PKG_ORDER's business.
// Hiding it would rebuild the exact blind spot this tool exists to remove.
check("PKG_ORDER keeps its trigger-induced write", has("PKG_ORDER", triggerEdge.id), true);
// "Who calls this?" must be answerable without clearing the filter.
check("PKG_VALIDATE keeps the inbound call", has("PKG_VALIDATE", inboundToValidate.id), true);
// The loud one never gets filtered out of its own package.
check("PKG_DYNAMIC keeps the dynamic-unknown write", has("PKG_DYNAMIC", dynamicEdge.id), true);
// A scope is a subset of the graph, never a rewrite of it.
const allIds = api.allEdges.map((e) => e.id);
check("scoped edges are a subset of the IR", idsOf("PKG_ORDER").every((id) => allIds.includes(id)), true);

summary("scope-test");
