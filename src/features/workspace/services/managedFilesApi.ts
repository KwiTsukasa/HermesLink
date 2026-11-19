import {t} from '../../../shared/i18n';
import {getHermesWorkspace} from '../../../native/HermesWorkspace';
import type {Entry} from '../types';
import {record} from '../utils/data';
import {executeOperation} from './workspaceApi';

export type ManagedFile = {
  name: string;
  path: string;
  directory: boolean;
  size?: number;
};

function comparablePath(path: string): string {
  return path
    .replace(/\\/g, '/')
    .replace(/^[A-Z](?=:)/, (drive) => drive.toLowerCase())
    .replace(/\/$/, '');
}

export async function listManagedFiles(
  path: string | null,
): Promise<{path: string; entries: ManagedFile[]}> {
  const result = record(
    JSON.parse(await getHermesWorkspace().listManagedFiles(path)),
  );
  if (typeof result.path !== 'string' || !Array.isArray(result.entries)) {
    throw new Error(t('mobile.managedFiles.invalidFolderListing'));
  }
  const directory = comparablePath(result.path);
  if (path !== null && directory !== comparablePath(path)) {
    throw new Error(t('mobile.managedFiles.theFolderLocationChangedGoUpOneLevel'));
  }

  const files = new Map<string, ManagedFile>();
  for (const value of result.entries) {
    const file = record(value);
    if (
      file.broken_link === true ||
      typeof file.name !== 'string' ||
      !file.name ||
      typeof file.path !== 'string' ||
      typeof file.is_directory !== 'boolean'
    ) {
      continue;
    }
    const child = comparablePath(file.path);
    if (child !== `${directory}/${file.name}`) {
      continue;
    }
    files.set(child, {
      name: file.name,
      path: file.path,
      directory: file.is_directory,
      size:
        typeof file.size === 'number' &&
        Number.isSafeInteger(file.size) &&
        file.size >= 0
          ? file.size
          : undefined,
    });
  }
  return {path: result.path, entries: Array.from(files.values())};
}

export function childFilePath(parent: string, input: string): string {
  const name = input.trim();
  const windows = /^[A-Za-z]:[/\\]/.test(parent);
  if (
    !name ||
    name === '.' ||
    name === '..' ||
    /[/\\:%?#]/.test(name) ||
    (windows && (/[<>"|*]/.test(name) || /[. ]$/.test(name)))
  ) {
    throw new Error(t('mobile.managedFiles.enterASingleValidFileOrFolderName'));
  }
  const delimiter = parent.includes('\\') ? '\\' : '/';
  return parent.replace(/[/\\]+$/, '') + delimiter + name;
}

export function textDataUrl(text: string): string {
  const alphabet =
    'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const escaped = encodeURIComponent(text);
  const bytes: number[] = [];
  for (let index = 0; index < escaped.length; index++) {
    if (escaped[index] === '%') {
      bytes.push(Number.parseInt(escaped.slice(index + 1, index + 3), 16));
      index += 2;
    } else {
      bytes.push(escaped.charCodeAt(index));
    }
  }
  if (bytes.length > 32 * 1024)
    throw new Error(t('mobile.managedFiles.textEditingIsLimitedTo32Kib'));
  let base64 = '';
  for (let index = 0; index < bytes.length; index += 3) {
    const first = bytes[index];
    const second = bytes[index + 1] ?? 0;
    const third = bytes[index + 2] ?? 0;
    base64 +=
      alphabet[Math.floor(first / 4)] +
      alphabet[(first % 4) * 16 + Math.floor(second / 16)];
    base64 +=
      index + 1 < bytes.length
        ? alphabet[(second % 16) * 4 + Math.floor(third / 64)]
        : '=';
    base64 += index + 2 < bytes.length ? alphabet[third % 64] : '=';
  }
  return `data:text/plain;base64,${base64}`;
}

export async function getFilePreview(path: string) {
  const result = record(
    JSON.parse(await getHermesWorkspace().previewManagedFile(path)),
  );
  const {binary, text, truncated} = result;
  if (
    typeof binary !== 'boolean' ||
    typeof text !== 'string' ||
    typeof truncated !== 'boolean'
  ) {
    throw new Error(t('mobile.managedFiles.invalidFilePreview'));
  }
  return {binary, text, truncated};
}

export async function fetchFileDownload(
  entry: Entry,
  path: string,
): Promise<string> {
  const result = await executeOperation(entry, {query: {path}});
  if (result.kind !== 'file') {
    throw new Error(t('mobile.managedFiles.hermesDidNotReturnAFileToSave'));
  }
  return result.handle;
}
