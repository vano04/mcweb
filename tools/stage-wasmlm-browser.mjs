#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const [loaderPath, wasmPath, outputBase, maxPagesArg] = process.argv.slice(2);
if (!loaderPath || !wasmPath || !outputBase) {
    console.error("usage: stage-wasmlm-browser.mjs <loader.js> <image.wasm> <output-base> [max-pages]");
    process.exit(2);
}

const maxPages = Number(maxPagesArg ?? 65536);
if (!Number.isInteger(maxPages) || maxPages <= 0) {
    throw new Error(`invalid maximum page count: ${maxPagesArg}`);
}

const tool = path.join(path.dirname(fileURLToPath(import.meta.url)), "wasm-share-memory.mjs");
const runRewrite = (output, extraArgs) => {
    const result = spawnSync(process.execPath, [tool, wasmPath, output, `--max=${maxPages}`, ...extraArgs], {
        encoding: "utf8",
    });
    if (result.status !== 0) {
        process.stderr.write(result.stdout ?? "");
        process.stderr.write(result.stderr ?? "");
        throw new Error(`wasm shared-memory rewrite failed (${result.status})`);
    }
    process.stdout.write(result.stdout);
};

const primaryWasm = `${outputBase}.js.wasm`;
const agentWasm = `${outputBase}-agent.js.wasm`;
runRewrite(primaryWasm, []);
runRewrite(agentWasm, ["--strip-data", "--strip-start", "--export-globals"]);

let loader = fs.readFileSync(loaderPath, "utf8");
const replaceExactlyOnce = (needle, replacement, label) => {
    const first = loader.indexOf(needle);
    if (first < 0 || loader.indexOf(needle, first + needle.length) >= 0) {
        throw new Error(`generated loader ${label} marker must occur exactly once`);
    }
    loader = loader.slice(0, first) + replacement + loader.slice(first + needle.length);
};

replaceExactlyOnce(
    "const wasmImports = {};",
    `const wasmImports = {};\nif (globalThis.mcWebSharedMemory instanceof WebAssembly.Memory) {\n    wasmImports.mcweb = { memory: globalThis.mcWebSharedMemory };\n}`,
    "imports"
);
replaceExactlyOnce(
    `    const wasmPath = config.wasm_path || runtime.getCurrentFile() + ".wasm";
    const file = await runtime.fetchData(wasmPath);
    const result = await WebAssembly.instantiate(file, wasmImports);
    return {
        instance: result.instance,
        memory: result.instance.exports.memory,
    };`,
    `    const wasmPath = config.wasm_path || runtime.getCurrentFile() + ".wasm";
    let instance;
    if (globalThis.mcWebPrecompiledWasmModule instanceof WebAssembly.Module) {
        instance = await WebAssembly.instantiate(globalThis.mcWebPrecompiledWasmModule, wasmImports);
    } else {
        const file = await runtime.fetchData(wasmPath);
        const result = await WebAssembly.instantiate(file, wasmImports);
        instance = result.instance;
    }
    return {
        instance,
        memory: instance.exports.memory,
    };`,
    "instantiation"
);
replaceExactlyOnce(
    "runtime.data = data;\nwasmRun(vmArgs);",
    `runtime.data = data;\nif (globalThis.mcWebThreadAgentRuntime) {\n    globalThis.mcWebThreadAgentRuntime.start(getExports());\n} else {\n    if (globalThis.mcWebThreadRuntime) {\n        await globalThis.mcWebThreadRuntime.preparePrimary(getExports());\n        globalThis.mcWebThreadRuntime.startPrimary(getExports());\n    }\n    wasmRun(vmArgs);\n}`,
    "startup"
);
replaceExactlyOnce(
    "const createVM = function(vmArgs, data) {",
    "const createVM = async function(vmArgs, data) {",
    "async createVM"
);
replaceExactlyOnce(
    "data.wasm = await wasmInstantiate(config, vmArgs);\n   let vm = createVM(vmArgs, data);",
    `data.wasm = await wasmInstantiate(config, vmArgs);\n   if (globalThis.mcWebThreadAgentRuntime\n       && typeof globalThis.mcWebThreadAgentRuntime.instantiateDone === "function") {\n       globalThis.mcWebThreadAgentRuntime.instantiateDone();\n   }\n   let vm = await createVM(vmArgs, data);`,
    "await carrier preparation"
);

const outputLoader = `${outputBase}.js`;
fs.writeFileSync(outputLoader, loader);
console.log(`${loaderPath} -> ${outputLoader}`);
