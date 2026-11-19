const {createHash} = require('node:crypto');
const {copyFileSync, mkdirSync, readFileSync, writeFileSync} = require('node:fs');
const {join, resolve} = require('node:path');

const root = resolve(__dirname, '..');
const xterm = join(root, 'node_modules/@xterm/xterm');
const fit = join(root, 'node_modules/@xterm/addon-fit');
const renderer = join(root, 'src/features/terminal/renderer');
const output = process.argv[2];
if (!output) {
  throw new Error('Pass the generated Android assets directory.');
}

function read(path) {
  return readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
}

const scripts = [
  read(join(xterm, 'lib/xterm.js')),
  read(join(fit, 'lib/addon-fit.js')),
  read(join(renderer, 'terminal.js')),
].map((source) => source.replace(/\n\/\/# sourceMappingURL=.*?(?=\n|$)/g, ''));

if (scripts.some((source) => /<\/script/i.test(source))) {
  throw new Error('The terminal script contains an HTML closing script tag.');
}

const scriptPolicy = scripts
  .map(
    (source) => `'sha256-${createHash('sha256').update(source).digest('base64')}'`,
  )
  .join(' ');
const page = read(join(renderer, 'index.html'))
  .replace('TERMINAL_SCRIPT_HASHES', () => scriptPolicy)
  .replace('TERMINAL_STYLES', () => read(join(xterm, 'css/xterm.css')))
  .replace('TERMINAL_SCRIPTS', () =>
    scripts.map((source) => `<script>${source}</script>`).join('\n'),
  );

const directory = join(output, 'hermes-terminal');
mkdirSync(directory, {recursive: true});
writeFileSync(join(directory, 'index.html'), page);
copyFileSync(join(xterm, 'LICENSE'), join(directory, 'xterm-LICENSE.txt'));
copyFileSync(join(fit, 'LICENSE'), join(directory, 'xterm-addon-fit-LICENSE.txt'));
