const {parse} = require('@babel/parser');
const {createHash} = require('node:crypto');
const {existsSync, mkdirSync, readFileSync, writeFileSync} = require('node:fs');
const {dirname, join, resolve} = require('node:path');
const yaml = require('js-yaml');

const VERSION = '0.23.2';
const REF = 'ae2a81ccc00e9b7c06301523e5bb09608397149c';
const root = resolve(__dirname, '..');
const sourceOption = process.argv.indexOf('--source');
if (sourceOption < 0 || !process.argv[sourceOption + 1]) {
  throw new Error('Pass --source with the Store payload hermes-agent directory.');
}
const source = resolve(process.argv[sourceOption + 1]);
const check = process.argv.includes('--check');
const read = (file) => readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
const readJson = (file) => JSON.parse(read(file));
if (readJson(join(source, '..', 'manifest.json')).ref !== REF) {
  throw new Error('The Store payload differs from the verified v0.23.2 source.');
}

const hashes = {};
function readSource(path) {
  const bytes = readFileSync(join(source, path));
  hashes[path] = createHash('sha256').update(bytes).digest('hex');
  return bytes.toString('utf8').replace(/^\uFEFF/, '');
}

function write(path, contents) {
  const target = join(root, path);
  if (check) {
    if (!existsSync(target) || !readFileSync(target).equals(Buffer.from(contents))) {
      throw new Error(`Generated locale artifact differs: ${path}`);
    }
    return;
  }
  mkdirSync(dirname(target), {recursive: true});
  writeFileSync(target, contents);
}

const key = (property) => property.key?.name ?? property.key?.value;
const json = (value) => JSON.stringify(value, null, 2) + '\n';
const ordered = (object) => Object.fromEntries(Object.keys(object).sort().map((k) => [k, object[k]]));

const languageSource = readSource('agent/i18n_languages.py');
const languageRows = [...languageSource.matchAll(/^    "([^"]+)": \("((?:[^"\\]|\\.)*)", (True|False)\),$/gm)];
if (languageRows.length !== 17) {
  throw new Error('The installed locale identity table differs from v0.23.2.');
}
const upstreamIds = new Set(languageRows.map((row) => row[1]));
const nativeTag = (upstream) => ({zh: 'zh-CN', 'zh-hant': 'zh-Hant'})[upstream] || upstream;
const desktopKeys = new Set(JSON.parse(readSource('locales/_keys.desktop.json')).keys);
const tuiKeys = new Set(JSON.parse(readSource('locales/_keys.tui.json')));
const renderer = 'apps/desktop/dist-webapp';
const manifest = JSON.parse(readSource(`${renderer}/renderer-manifest.json`));
const actionsAsset = Object.values(manifest)
  .map((entry) => entry.file)
  .find((path) => /^assets\/actions-[^/]+\.js$/.test(path));
if (!actionsAsset) {
  throw new Error('The Webapp renderer actions asset is missing.');
}
const desktopSource = readSource(`${renderer}/${actionsAsset}`);
const ast = parse(desktopSource, {sourceType: 'module', attachComment: false});
const globals = new Map(ast.program.body
  .filter((statement) => statement.type === 'VariableDeclaration')
  .flatMap((statement) => statement.declarations)
  .filter((declaration) => declaration.id.type === 'Identifier')
  .map(({id, init}) => [id.name, init]));

const registry = [...globals.values()].find((node) => node?.type === 'ObjectExpression'
  && node.properties.length >= 8
  && node.properties.every((field) => upstreamIds.has(key(field)))
  && node.properties.some((field) => key(field) === 'en' && field.value.type === 'Identifier')
  && node.properties.some((field) => key(field) === 'zh' && field.value.type === 'Identifier'));
if (!registry) {
  throw new Error('The Webapp Desktop locale registry is missing.');
}
const english = registry.properties.find((entry) => key(entry) === 'en').value.name;
const traditional = registry.properties.find((entry) => key(entry) === 'zh-hant').value;
const overlayName = traditional.callee?.name;
const overlay = globals.get(overlayName);
if (overlay?.type !== 'ArrowFunctionExpression' || overlay.params.length !== 1
    || overlay.body?.type !== 'CallExpression'
    || overlay.body.arguments[0]?.name !== english
    || overlay.body.arguments[1]?.name !== overlay.params[0]?.name) {
  throw new Error('The Webapp Desktop locale overlay differs from the installed release.');
}
const mergeName = overlay.body.callee?.name;
const merge = ast.program.body.find((node) => node.type === 'FunctionDeclaration' && node.id.name === mergeName);
if (!merge || !desktopSource.slice(merge.start, merge.end).includes('Object.entries(')) {
  throw new Error('The Webapp Desktop locale merge differs from the installed release.');
}

// Interpret only static dictionaries and direct positional templates. Never run the installed JS.
function staticText(node, parameters = []) {
  if (node?.type === 'StringLiteral') {
    return node.value;
  }
  if (node?.type === 'TemplateLiteral') {
    const positions = node.expressions.map((expression) =>
      expression.type === 'Identifier' ? parameters.indexOf(expression.name) : -1);
    if (positions.some((position) => position < 0)) {
      return undefined;
    }
    return node.quasis.map((part, index) =>
      part.value.cooked + (index < positions.length ? `{${positions[index]}}` : '')).join('');
  }
  if (node?.type === 'ArrowFunctionExpression' && node.params.every((param) => param.type === 'Identifier')) {
    return staticText(node.body, node.params.map((param) => param.name));
  }
  return undefined;
}

function memberName(node) {
  return node.computed ? staticText(node.property) : node.property?.name;
}

function memberValues(node, name, references) {
  if (node?.type === 'Identifier' && !references.has(node.name)) {
    return memberValues(globals.get(node.name), name, new Set([...references, node.name]));
  }
  if (node?.type === 'MemberExpression') {
    return memberValues(node.object, memberName(node), references)
      .flatMap((value) => memberValues(value, name, references));
  }
  if (node?.type === 'CallExpression' && node.callee?.name === mergeName) {
    return node.arguments.flatMap((value) => memberValues(value, name, references));
  }
  if (node?.type === 'ObjectExpression') {
    let result = [];
    for (const property of node.properties) {
      if (property.type === 'SpreadElement') {
        const spread = memberValues(property.argument, name, references);
        if (spread.length) {
          result = spread;
        }
      } else if (!property.computed && key(property) === name) {
        result = [property.value];
      }
    }
    return result;
  }
  return [];
}

function desktopDictionary(node, prefix, output, references = new Set()) {
  if (node?.type === 'Identifier' && !references.has(node.name)) {
    return desktopDictionary(globals.get(node.name), prefix, output, new Set([...references, node.name]));
  }
  if (node?.type === 'CallExpression' && node.callee?.name === overlayName && node.arguments.length === 1) {
    // The renderer falls back to English for missing fields. Store only actual localized overrides.
    return desktopDictionary(node.arguments[0], prefix, output, references);
  }
  if (node?.type === 'CallExpression' && node.callee?.name === mergeName) {
    for (const value of node.arguments) {
      desktopDictionary(value, prefix, output, references);
    }
    return;
  }
  if (node?.type === 'MemberExpression') {
    for (const value of memberValues(node.object, memberName(node), references)) {
      desktopDictionary(value, prefix, output, references);
    }
    return;
  }
  if (node?.type === 'ObjectExpression') {
    for (const property of node.properties) {
      if (property.type === 'SpreadElement') {
        desktopDictionary(property.argument, prefix, output, references);
      } else if (!property.computed && typeof key(property) === 'string') {
        desktopDictionary(property.value, prefix ? `${prefix}.${key(property)}` : key(property),
          output, references);
      }
    }
    return;
  }
  if (desktopKeys.has(prefix)) {
    const template = staticText(node);
    if (template !== undefined) {
      output[prefix] = template;
    }
  }
}

function flatten(node, prefix, output, approved) {
  if (typeof node === 'string' && (!approved || approved.has(prefix))) {
    output[prefix] = node;
  } else if (node && typeof node === 'object' && !Array.isArray(node)) {
    for (const [name, value] of Object.entries(node)) {
      flatten(value, prefix ? `${prefix}.${name}` : name, output, approved);
    }
  }
}

const metadata = [];
const desktopCounts = {};
const catalog = Object.create(null);
const localeSource = 'src/shared/i18n';
const nativeAssets = 'android/app/src/main/assets/hermes-i18n';
for (const [, upstream, quotedEndonym, rtl] of languageRows) {
  const id = nativeTag(upstream);
  const messages = Object.create(null);
  const surfaces = ['core'];
  flatten(yaml.load(readSource(`locales/${upstream}.yaml`)), '', messages);
  const tuiFile = `locales/${upstream}.tui.yaml`;
  if (existsSync(join(source, tuiFile))) {
    flatten(yaml.load(readSource(tuiFile)), '', messages, tuiKeys);
    surfaces.push('tui');
  }
  const desktopEntry = registry.properties.find((entry) => key(entry) === upstream);
  if (desktopEntry) {
    const desktop = Object.create(null);
    desktopDictionary(desktopEntry.value, '', desktop);
    desktopCounts[id] = Object.keys(desktop).length;
    Object.assign(messages, desktop);
    surfaces.push('desktop');
  }
  for (const [name, message] of Object.entries(messages)) {
    (catalog[name] ||= Object.create(null))[id] = message;
  }
  metadata.push({id, upstream, endonym: JSON.parse(`"${quotedEndonym}"`), rtl: rtl === 'True', surfaces});
  write(`${nativeAssets}/locales/${id}.json`, json(ordered(messages)));
}

if (catalog['common.cancel']?.en !== 'Cancel' || catalog['common.cancel']?.['zh-CN'] !== '取消'
    || desktopCounts.en < 2000 || desktopCounts['zh-CN'] < 2000 || metadata.length !== 17) {
  throw new Error('The extracted Webapp dictionaries failed the release checks.');
}
metadata.sort((a, b) => a.id === 'en' ? -1 : b.id === 'en' ? 1
  : a.upstream < b.upstream ? -1 : a.upstream > b.upstream ? 1 : 0);
const official = ordered(catalog);
const languageJson = json(metadata).replace(/"surfaces": \[\n([^\]]*)\]/g,
  (_, values) => `"surfaces": [${values.trim().replace(/,\s+/g, ', ')}]`);
write(`${localeSource}/official.json`, json(official));
write(`${localeSource}/locales.json`, languageJson);
write(`${nativeAssets}/locales.json`, languageJson);
write(`${nativeAssets}/LICENSE.txt`, readSource('LICENSE').replace(/\r\n/g, '\n'));
write(`${localeSource}/provenance.json`, json({
  version: VERSION,
  ref: REF,
  license: 'MIT',
  localeCount: metadata.length,
  keyCount: Object.keys(official).length,
  desktopTranslations: desktopCounts,
  sources: ordered(hashes),
}));
process.stdout.write(`Verified ${metadata.length} locales, ${Object.keys(official).length} official message keys.\n`);
