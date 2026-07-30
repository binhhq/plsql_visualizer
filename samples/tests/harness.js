// Shared plumbing for the renderer tests: find renderer.html, pull the pieces
// the tests need out of it, and keep score.
//
// No dependencies, by design — `node samples/tests/<file>.js` and nothing else.

const fs = require("fs");
const path = require("path");

const RENDERER = path.join(__dirname, "..", "renderer.html");

function rendererSource() {
  return fs.readFileSync(RENDERER, "utf8");
}

// The IR the renderer draws is embedded in the page itself, so the tests
// exercise exactly the data a browser would — they own no fixture that could
// drift away from what ships.
function irText(src) {
  const m = src.match(/<script[^>]*id="ir-data"[^>]*>([\s\S]*?)<\/script>/);
  if (!m) throw new Error('no <script id="ir-data"> block in ' + RENDERER);
  return m[1];
}

let checks = 0;
let failures = 0;

function check(label, actual, expected) {
  checks++;
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    console.log("  ok   " + label + " = " + a);
    return true;
  }
  failures++;
  console.log("  FAIL " + label);
  console.log("         expected " + e);
  console.log("         actual   " + a);
  return false;
}

function summary(name) {
  const verdict = failures ? "FAILED" : "PASSED";
  console.log("\n" + verdict + " " + name + " — " + (checks - failures) + "/" + checks + " checks");
  process.exit(failures ? 1 : 0);
}

module.exports = { RENDERER, rendererSource, irText, check, summary };
