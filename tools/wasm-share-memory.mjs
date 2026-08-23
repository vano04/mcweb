#!/usr/bin/env node
/**
 * Rewrite a GraalVM Web Image WasmLM module so its linear memory can be shared
 * between a page and its Workers.
 *
 * Web Image (WasmLM backend) emits a *declared*, non-shared, unbounded memory.
 * Every instantiation therefore gets its own fresh heap, which is exactly what
 * a threaded runtime must not have. Wasm gives no way to share a declared
 * memory, so the memory has to become an *import* the host supplies -- and a
 * shared memory must additionally carry a maximum.
 *
 * The rewrite is purely structural; no code is touched:
 *
 *   --max=<pages>     drop the memory section, append an imported memory with
 *                     limits {min, max, shared}. Memory index 0 is unchanged
 *                     (there is exactly one memory), and adding an import of
 *                     kind "memory" does not shift the function, table, or
 *                     global index spaces, so every existing instruction stays
 *                     valid.
 *   --strip-data      drop the data section (and its datacount). A secondary
 *                     instance must not re-run the image-heap segments over a
 *                     heap the primary instance is already mutating.
 *   --strip-start     drop the start section. WebImageWasmLMJavaMainSupport
 *                     .initialize() sets up the allocator; running it twice
 *                     over one shared heap corrupts the free list.
 *   --export-globals  export every mutable global (notably $stackPointer) so
 *                     the host can give each Worker its own shadow stack.
 *
 * Usage:
 *   node tools/wasm-share-memory.mjs in.wasm out.wasm --max=4096
 *   node tools/wasm-share-memory.mjs in.wasm worker.wasm --max=4096 \
 *        --strip-data --strip-start --export-globals
 */
import fs from "node:fs";

const SECTION = { custom: 0, type: 1, import: 2, func: 3, table: 4, memory: 5, global: 6, export: 7, start: 8, elem: 9, code: 10, data: 11, datacount: 12, tag: 13 };

function uleb(n) {
    const out = [];
    do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; out.push(b); } while (n);
    return Buffer.from(out);
}

class Reader {
    constructor(buf) { this.buf = buf; this.p = 0; }
    u8() { return this.buf[this.p++]; }
    uleb() { let r = 0, s = 0, b; do { b = this.buf[this.p++]; r |= (b & 0x7f) << s; s += 7; } while (b & 0x80); return r >>> 0; }
    bytes(n) { const s = this.buf.subarray(this.p, this.p + n); this.p += n; return s; }
    name() { return this.bytes(this.uleb()).toString(); }
    limits() { const flags = this.u8(); const min = this.uleb(); const max = (flags & 1) ? this.uleb() : null; return { flags, min, max }; }
}

/** Split a module into its raw sections, preserving order. */
function readSections(buf) {
    if (buf.readUInt32LE(0) !== 0x6d736100) throw new Error("not a wasm module");
    const sections = [];
    const r = new Reader(buf);
    r.p = 8;
    while (r.p < buf.length) {
        const id = r.u8();
        const size = r.uleb();
        const body = buf.subarray(r.p, r.p + size);
        sections.push({ id, body });
        r.p += size;
    }
    return { version: buf.subarray(0, 8), sections };
}

function writeSections(version, sections) {
    const parts = [version];
    for (const s of sections) parts.push(Buffer.from([s.id]), uleb(s.body.length), s.body);
    return Buffer.concat(parts);
}

/** Parse the memory section; returns {min, max} of memory 0. */
function readMemory(body) {
    const r = new Reader(body);
    const count = r.uleb();
    if (count !== 1) throw new Error(`expected exactly 1 declared memory, found ${count}`);
    return r.limits();
}

/**
 * Append an imported memory to the import section. Import entries are grouped
 * into per-kind index spaces by order of appearance, so appending a memory
 * leaves the func/table/global import indices untouched.
 */
function appendMemoryImport(body, { module, name, min, max }) {
    const r = new Reader(body);
    const count = r.uleb();
    const rest = body.subarray(r.p);
    const mod = Buffer.from(module, "utf8");
    const nm = Buffer.from(name, "utf8");
    const entry = Buffer.concat([
        uleb(mod.length), mod,
        uleb(nm.length), nm,
        Buffer.from([0x02]),        // import kind: memory
        Buffer.from([0x03]),        // limits flags: shared | has-max
        uleb(min), uleb(max),
    ]);
    return Buffer.concat([uleb(count + 1), rest, entry]);
}

/** Count imported globals so exported global indices can be computed. */
function countImportedGlobals(body) {
    const r = new Reader(body);
    const n = r.uleb();
    let globals = 0;
    for (let i = 0; i < n; i++) {
        r.name(); r.name();
        const kind = r.u8();
        if (kind === 0) r.uleb();
        else if (kind === 1) { r.u8(); r.limits(); }
        else if (kind === 2) r.limits();
        else if (kind === 3) { r.u8(); r.u8(); globals++; }
    }
    return globals;
}

/** List locally-defined globals as {index, mutable}. */
function readGlobals(body, importedGlobals) {
    const r = new Reader(body);
    const n = r.uleb();
    const out = [];
    for (let i = 0; i < n; i++) {
        r.u8();                       // value type
        const mutable = r.u8() === 1;
        // Skip the init expression: a constant expression terminated by `end`.
        let depth = 0;
        for (;;) {
            const op = r.u8();
            if (op === 0x0b && depth === 0) break;
            if (op === 0x41 || op === 0x42) r.uleb();          // i32/i64.const
            else if (op === 0x43) r.p += 4;                    // f32.const
            else if (op === 0x44) r.p += 8;                    // f64.const
            else if (op === 0x23 || op === 0x24) r.uleb();     // global.get/set
            else if (op === 0xd0) r.uleb();                    // ref.null
            else if (op === 0xd2) r.uleb();                    // ref.func
            else if (op === 0xfd) { r.uleb(); r.p += 16; }     // v128.const
        }
        out.push({ index: importedGlobals + i, mutable });
    }
    return out;
}

function appendGlobalExports(body, globals, prefix) {
    const r = new Reader(body);
    const count = r.uleb();
    const rest = body.subarray(r.p);
    const parts = [];
    let added = 0;
    for (const g of globals) {
        if (!g.mutable) continue;
        const nm = Buffer.from(`${prefix}${g.index}`, "utf8");
        parts.push(uleb(nm.length), nm, Buffer.from([0x03]), uleb(g.index));
        added++;
    }
    return { body: Buffer.concat([uleb(count + added), rest, ...parts]), added };
}

function main(argv) {
    const [inPath, outPath] = argv.filter((a) => !a.startsWith("--"));
    if (!inPath || !outPath) {
        console.error("usage: wasm-share-memory.mjs <in.wasm> <out.wasm> --max=<pages> [--strip-data] [--strip-start] [--export-globals]");
        process.exit(2);
    }
    const flag = (n) => argv.includes(`--${n}`);
    const opt = (n, d) => { const a = argv.find((x) => x.startsWith(`--${n}=`)); return a ? a.slice(n.length + 3) : d; };

    const importModule = opt("import-module", "mcweb");
    const importName = opt("import-name", "memory");
    const globalPrefix = opt("global-prefix", "mcweb.global.");

    const { version, sections } = readSections(fs.readFileSync(inPath));
    const memSection = sections.find((s) => s.id === SECTION.memory);
    if (!memSection) throw new Error("no memory section: this is a Wasm-GC image, which has no linear memory to share");
    const mem = readMemory(memSection.body);

    const max = Number(opt("max", mem.max ?? 65536));
    if (max < mem.min) throw new Error(`--max=${max} is below the module's minimum of ${mem.min} pages`);

    const importSection = sections.find((s) => s.id === SECTION.import);
    if (!importSection) throw new Error("no import section to extend");

    const out = [];
    const notes = [];
    for (const s of sections) {
        if (s.id === SECTION.memory) { notes.push(`memory: declared min=${mem.min} -> imported ${importModule}.${importName} shared min=${mem.min} max=${max}`); continue; }
        if (s.id === SECTION.data && flag("strip-data")) { notes.push("data: stripped"); continue; }
        if (s.id === SECTION.datacount && flag("strip-data")) continue;
        if (s.id === SECTION.start && flag("strip-start")) { notes.push("start: stripped"); continue; }
        if (s.id === SECTION.import) { out.push({ id: s.id, body: appendMemoryImport(s.body, { module: importModule, name: importName, min: mem.min, max }) }); continue; }
        out.push({ id: s.id, body: s.body });
    }

    if (flag("export-globals")) {
        const importedGlobals = countImportedGlobals(importSection.body);
        const globalSection = out.find((s) => s.id === SECTION.global);
        const exportSection = out.find((s) => s.id === SECTION.export);
        if (globalSection && exportSection) {
            const globals = readGlobals(globalSection.body, importedGlobals);
            const { body, added } = appendGlobalExports(exportSection.body, globals, globalPrefix);
            exportSection.body = body;
            notes.push(`globals: exported ${added} mutable global(s) as "${globalPrefix}<index>"`);
        }
    }

    fs.writeFileSync(outPath, writeSections(version, out));
    console.log(`${inPath} -> ${outPath}`);
    for (const n of notes) console.log(`  ${n}`);
}

main(process.argv.slice(2));
