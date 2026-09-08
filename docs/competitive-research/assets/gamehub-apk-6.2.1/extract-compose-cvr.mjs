#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const [inputRoot, outputJson, outputTsv, outputPcEngineTsv] = process.argv.slice(2);

if (!inputRoot || !outputJson || !outputTsv) {
  console.error("usage: extract-compose-cvr.mjs <composeResources> <output.json> <output.tsv> [pc-engine.tsv]");
  process.exit(2);
}

function walk(directory) {
  return fs.readdirSync(directory, {withFileTypes: true}).flatMap((entry) => {
    const child = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(child) : [child];
  });
}

function decodeBase64(value, source, lineNumber) {
  try {
    return Buffer.from(value, "base64").toString("utf8");
  } catch (error) {
    throw new Error(`${source}:${lineNumber}: invalid base64: ${error.message}`);
  }
}

const files = walk(inputRoot)
  .filter((file) => /\/values-zh(?:-rCN)?\/strings\.commonMain\.cvr$/.test(file))
  .sort();

const entries = [];
for (const file of files) {
  const relativeSource = path.relative(inputRoot, file);
  const moduleName = relativeSource.split(path.sep)[0];
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
  lines.forEach((line, index) => {
    if (!line || line === "version:0") return;
    const first = line.indexOf("|");
    const second = line.indexOf("|", first + 1);
    if (first <= 0 || second <= first) {
      throw new Error(`${relativeSource}:${index + 1}: unsupported CVR record`);
    }
    const type = line.slice(0, first);
    const key = line.slice(first + 1, second);
    const encodedValue = line.slice(second + 1);
    entries.push({
      module: moduleName,
      type,
      key,
      value: decodeBase64(encodedValue, relativeSource, index + 1),
      encodedValue,
      source: relativeSource,
      line: index + 1,
    });
  });
}

fs.writeFileSync(outputJson, `${JSON.stringify({schemaVersion: 1, locale: "zh-CN", entries}, null, 2)}\n`);

const escapeTsv = (value) => String(value).replaceAll("\\", "\\\\").replaceAll("\t", "\\t").replaceAll("\r", "\\r").replaceAll("\n", "\\n");
const columns = ["module", "type", "key", "value", "encodedValue", "source", "line"];
const rows = [columns.join("\t"), ...entries.map((entry) => columns.map((column) => escapeTsv(entry[column])).join("\t"))];
fs.writeFileSync(outputTsv, `${rows.join("\n")}\n`);

let pcEngineEntries = [];
if (outputPcEngineTsv) {
  pcEngineEntries = entries.filter((entry) =>
    entry.module === "com.xiaoji.egggame.common.winemu_core" ||
    /^(pc_|tp_|winemu_)/.test(entry.key),
  );
  const pcEngineRows = [
    columns.join("\t"),
    ...pcEngineEntries.map((entry) => columns.map((column) => escapeTsv(entry[column])).join("\t")),
  ];
  fs.writeFileSync(outputPcEngineTsv, `${pcEngineRows.join("\n")}\n`);
}

console.log(JSON.stringify({
  files: files.length,
  entries: entries.length,
  pcEngineEntries: pcEngineEntries.length,
  outputJson,
  outputTsv,
  outputPcEngineTsv,
}));
