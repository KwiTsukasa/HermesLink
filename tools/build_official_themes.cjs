const {parse} = require('@babel/parser');
const {createHash} = require('node:crypto');
const {existsSync, mkdirSync, readFileSync, writeFileSync} = require('node:fs');
const {dirname, join, resolve} = require('node:path');

const REF = 'ae2a81ccc00e9b7c06301523e5bb09608397149c';
const index = process.argv.indexOf('--source');
if (index < 0 || !process.argv[index + 1]) {
  throw new Error('Pass --source with the Store payload hermes-agent directory.');
}
const source = resolve(process.argv[index + 1]);
const root = resolve(__dirname, '..');
const check = process.argv.includes('--check');
const directory = 'apps/desktop/dist-webapp';
const sha = {};
const read = (file) => readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
if (JSON.parse(read(join(source, '..', 'manifest.json'))).ref !== REF) {
  throw new Error('The Store payload differs from the verified release.');
}
const manifest = JSON.parse(read(join(source, directory, 'renderer-manifest.json')));

function fromAsset(prefix) {
  const pattern = new RegExp(`^assets/${prefix}[^/]+\\.js$`);
  const entry = Object.values(manifest).find((candidate) => pattern.test(candidate.file));
  if (!entry) throw new Error(`Missing Webapp asset ${prefix}`);
  const relative = `${directory}/${entry.file}`;
  const bytes = readFileSync(join(source, relative));
  sha[relative] = createHash('sha256').update(bytes).digest('hex');
  const script = bytes.toString('utf8');
  const body = parse(script, {sourceType: 'module', attachComment: false}).program.body;
  return {body, script, names: new Map(body.flatMap((item) => item.declarations ?? [])
    .filter((item) => item.id.type === 'Identifier').map((item) => [item.id.name, item.init]))};
}

const provider = fromAsset('context-CP');
const storage = fromAsset('storage-');
const property = (item) => item.key?.name ?? item.key?.value;
const literal = (item) => item?.type === 'StringLiteral' ? item.value
  : item?.type === 'TemplateLiteral' && item.expressions.length === 0 ? item.quasis[0].value.cooked
  : undefined;
const exportedPalette = storage.body.flatMap((item) => item.specifiers ?? [])
  .find((item) => item.exported?.name === 'L');
const seedTable = storage.names.get(exportedPalette?.local?.name);
const registry = [...provider.names.values()].find((value) => value?.type === 'ObjectExpression'
  && ['nous', 'github', 'classic', 'cyberpunk'].every((id) =>
    value.properties.some((item) => property(item) === id)));

function staticValue(node, names, bindings = {}) {
  const text = literal(node);
  if (text !== undefined) return text;
  if (node?.type === 'NumericLiteral') return node.value;
  if (node?.type === 'Identifier') {
    return Object.prototype.hasOwnProperty.call(bindings, node.name) ? bindings[node.name]
      : staticValue(names.get(node.name), names, bindings);
  }
  if (node?.type === 'TemplateLiteral') {
    const values = node.expressions.map((expression) => staticValue(expression, names, bindings));
    if (values.some((value) => value === undefined)) return undefined;
    return node.quasis.map((part, index) => part.value.cooked + (values[index] ?? '')).join('');
  }
  if (node?.type === 'CallExpression' && node.callee?.type === 'Identifier' && node.arguments.length === 1) {
    const fn = names.get(node.callee.name);
    if (fn?.type === 'ArrowFunctionExpression' && fn.params.length === 1
        && fn.params[0].type === 'Identifier') {
      return staticValue(fn.body, names, {[fn.params[0].name]: staticValue(node.arguments[0], names, bindings)});
    }
  }
  return undefined;
}

function literals(node, names = provider.names) {
  if (node?.type === 'Identifier') return literals(names.get(node.name), names);
  if (node?.type !== 'ObjectExpression') throw new Error('Expected an official color dictionary.');
  return Object.fromEntries(node.properties.map((item) => [property(item), staticValue(item.value, names)]));
}

// The Webapp derives light colors for presets that only supply one dark palette.
function lightFromSingle(colors) {
  const primary = colors.ring || colors.primary;
  return {
    background: '#ffffff',
    foreground: '#161616',
    card: '#ffffff',
    mutedForeground: mix('#6b6b70', primary, 0.16),
    border: mix('#ececef', primary, 0.14),
    midground: colors.midground ?? primary,
    accent: mix('#ffffff', primary, 0.10),
    destructive: '#b94a3a',
  };
}

function rgb(color) {
  return [1, 3, 5].map((start) => parseInt(color.slice(start, start + 2), 16));
}
function mix(first, second, percentage) {
  const b = rgb(second);
  return '#' + rgb(first).map((value, index) =>
    Math.round(value + (b[index] - value) * percentage).toString(16).padStart(2, '0')).join('');
}
function luminance(color) {
  const [r, g, b] = rgb(color).map((byte) => {
    const n = byte / 255;
    return n <= 0.03928 ? n / 12.92 : ((n + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(first, second) {
  const a = luminance(first);
  const b = luminance(second);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}
function legible(color, surface, minimum) {
  if (contrast(color, surface) >= minimum) return color;
  const target = luminance(surface) < 0.5 ? '#ffffff' : '#000000';
  let adjusted = color;
  for (let step = 1; step <= 5; step++) {
    adjusted = mix(color, target, step / 5);
    if (contrast(adjusted, surface) >= minimum) return adjusted;
  }
  return adjusted;
}

// Classic is derived from the release's CLI skin values by context.tsx's skin palette rule.
function classic(seed) {
  const background = seed.status_bar_bg;
  const foreground = seed.banner_text;
  const [r, g, b] = rgb(background);
  const dark = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 < 0.4;
  const sidebar = mix(background, foreground, dark ? 0.02 : 0.012);
  const minimum = Number(provider.script.match(/\bhe=(\d(?:\.\d+)?)/)?.[1]);
  if (!minimum) throw new Error('Classic theme contrast rule is missing.');
  const accent = legible(seed.ui_accent, sidebar, minimum);
  return {
    background, foreground, card: mix(background, foreground, dark ? 0.04 : 0.025),
    mutedForeground: seed.banner_dim, border: seed.banner_border, midground: accent,
    accent: mix(accent, background, dark ? 0.82 : 0.88), destructive: seed.ui_error,
  };
}

function palette(tokens) {
  const colors = {
    bg: tokens.background, panel: tokens.card, ink: tokens.foreground,
    sub: tokens.mutedForeground, line: tokens.border, accent: tokens.midground ?? tokens.ring,
    wash: tokens.accent, danger: tokens.destructive,
  };
  for (const [key, value] of Object.entries(colors)) {
    if (/^#[0-9a-f]{3}$/i.test(value ?? '')) {
      colors[key] = '#' + value.slice(1).split('').map((component) => component + component).join('');
    }
    const mixColor = /^color-mix\(in srgb, (#[0-9a-f]{6}) (\d+)%, (#[0-9a-f]{6}|transparent)\)$/i.exec(value ?? '');
    if (mixColor) {
      const weight = Number(mixColor[2]) / 100;
      colors[key] = mixColor[3] === 'transparent'
        ? mixColor[1] + Math.round(weight * 255).toString(16).padStart(2, '0')
        : mix(mixColor[1], mixColor[3], 1 - weight);
    }
  }
  if (Object.values(colors).some((color) => typeof color !== 'string' || !/^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(color))) {
    throw new Error(`An official theme has an unexpected palette format: ${JSON.stringify(colors)}.`);
  }
  return colors;
}

if (registry?.properties.length !== 12 || seedTable?.type !== 'ObjectExpression') {
  throw new Error('The installed Webapp theme registry differs from the release.');
}
const presets = registry.properties.map((item) => {
  const id = property(item);
  const definition = item.value.type === 'Identifier' ? provider.names.get(item.value.name) : item.value;
  const label = literal(definition.properties.find((value) => property(value) === 'label')?.value);
  if (id === 'classic') {
    const modes = ['colors', 'darkColors'].map((field) =>
      definition.properties.find((value) => property(value) === field).value.arguments[0].name);
    const [light, dark] = modes.map((name) => palette(classic(literals(provider.names.get(name)))));
    return {id, label, light, dark};
  }
  const seeds = seedTable.properties.find((value) => property(value) === id)?.value;
  const base = literals(seeds?.properties.find((value) => property(value) === 'colors')?.value, storage.names);
  const alternate = seeds.properties.find((value) => property(value) === 'darkColors')?.value;
  const light = palette(alternate ? base : lightFromSingle(base));
  const dark = palette(alternate ? literals(alternate, storage.names) : base);
  return {id, label, light, dark};
});

const result = JSON.stringify({version: '0.23.2', ref: REF, presets, sources: sha}, null, 2) + '\n';
for (const relative of ['src/shared/theme/official-presets.json',
  'android/app/src/main/assets/hermes-appearance/presets.json']) {
  const target = join(root, relative);
  if (check) {
    if (!existsSync(target) || read(target).replace(/\r\n/g, '\n') !== result) {
      throw new Error(`Generated theme artifact differs: ${relative}`);
    }
  } else {
    mkdirSync(dirname(target), {recursive: true});
    writeFileSync(target, result);
  }
}
process.stdout.write(`Verified ${presets.length} official Webapp theme presets.\n`);
