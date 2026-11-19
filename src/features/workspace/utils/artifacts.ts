import {t} from '../../../shared/i18n';
import type {Dict} from '../types';
import {plainText as plain, record} from './data';

export type Artifact = {
  kind: 'file' | 'image' | 'link';
  target: string;
  title: string;
  transport: 'file' | 'web' | 'inline';
};

const images = /\.(?:png|jpe?g|gif|webp|bmp|svg)$/i;
const documents =
  /\.(?:pdf|txt|json|md|csv|xlsx?|docx?|pptx?|html|zip|tar|gz|flac|m4a|mkv|mp3|ogg|opus|wav|webm|mp4|mov)$/i;
const namedResult =
  /^(?:artifact(?:s|_(?:file|image|path|url))?|attachments?|download_(?:file|path|url)|(?:file|files)_(?:created|written|modified)|(?:generated|output|result)_(?:file|image|path|url)|saved_to|screenshot_path|media_tag)$/i;
const outputTool =
  /(?:^|_)(?:create|creation|download|export|generate|generation|render|save|speech|tts|write)(?:_|$)/i;
const outputField =
  /^(?:(?:image|audio|video|media)(?:_(?:file|path|url))?|path|file_path|local_path|url)$/i;
export const inlineImage =
  /^data:image\/(?:png|jpeg|gif|webp|bmp);base64,[A-Za-z0-9+/]+={0,2}$/i;

function identify(
  value: string,
  label: string,
  image: boolean,
  explicit: boolean,
): Artifact | undefined {
  let target = value
    .trim()
    .replace(/^[`'"]|[`'"]$/g, '')
    .replace(/^MEDIA:\s*/i, '');
  if (inlineImage.test(target) && target.length <= 250_000) {
    return {
      kind: 'image',
      target,
      title: label || t('mobile.artifacts.inlineImage'),
      transport: 'inline',
    };
  }
  if (/^https?:\/\//i.test(target)) {
    try {
      const url = new URL(target);
      if (!url.hostname || url.username || url.password || /[\s<>"`]/.test(target))
        return undefined;
      const name = url.pathname.split('/').filter(Boolean).pop() || url.hostname;
      return {
        kind: image || images.test(url.pathname) ? 'image' : 'link',
        target,
        title: label || name,
        transport: 'web',
      };
    } catch {
      return undefined;
    }
  }
  if (/^file:\/\//i.test(target)) {
    try {
      const url = new URL(target);
      if (url.hostname && url.hostname !== 'localhost') return undefined;
      target = decodeURIComponent(url.pathname).replace(/^\/(?=[A-Za-z]:\/)/, '');
    } catch {
      return undefined;
    }
  }
  target = target.replace(/^sandbox:(?=\/)/, '').replace(/^\.[\\/]/, '');
  const file = target.replace(/\\/g, '/');
  const tail = file.split('/').pop() || '';
  const rooted = /^(?:[A-Za-z]:\/|\/|~\/)/.test(file);
  if (
    !tail ||
    file.startsWith('//') ||
    file.split('/').some((segment) => segment === '..') ||
    /[%?#]/.test(file) ||
    (!rooted && !explicit) ||
    /:/.test(file.replace(/^[A-Za-z]:\//, ''))
  )
    return undefined;
  if (!rooted && !file.includes('/') && !images.test(file) && !documents.test(file))
    return undefined;
  if (!images.test(file) && !documents.test(file) && !explicit) return undefined;
  return {
    kind: image || images.test(file) ? 'image' : 'file',
    target,
    title: label || tail,
    transport: 'file',
  };
}

export function artifactsInMessages(messages: readonly Dict[]): Artifact[] {
  const artifacts = new Map<string, Artifact>();
  function add(value: string, label = '', image = false, explicit = false) {
    const artifact = identify(value, label, image, explicit);
    if (artifact && !artifacts.has(artifact.target))
      artifacts.set(artifact.target, artifact);
  }

  function extractText(text: string) {
    for (const match of text.matchAll(/(!?)\[([^\]]*)\]\(([^)\r\n]+)\)/g)) {
      add(match[3], match[2], match[1] === '!', true);
    }
    for (const match of text.matchAll(/https?:\/\/[^\s<>"'\x60)]+/gi))
      add(match[0].replace(/[.,;!]+$/, ''));
    for (const match of text.matchAll(
      /(?:^|[\s("'\x60])((?:[A-Za-z]:[\\/]|\/|~[\\/]|file:\/\/|sandbox:\/)[^\s<>"'\x60)]+)/g,
    )) {
      if (match.index !== undefined && text[match.index - 1] === '/') continue;
      add(match[1].replace(/[.,;!]+$/, ''));
    }
    for (const match of text.matchAll(/(?:^|\n)\s*MEDIA:\s*([^\r\n]+)/gi))
      add(match[1], '', false, true);
  }

  function structured(
    value: unknown,
    toolOutputs: boolean,
    inherited = false,
    depth = 0,
  ) {
    if (depth > 12) return;
    if (typeof value === 'string') {
      if (inherited) add(value, '', false, true);
    } else if (Array.isArray(value)) {
      value.forEach((item) => structured(item, toolOutputs, inherited, depth + 1));
    } else {
      for (const [field, item] of Object.entries(record(value))) {
        const known =
          namedResult.test(field) || (toolOutputs && outputField.test(field));
        const valueField = inherited && /^(?:url|uri|path|src|file)$/i.test(field);
        structured(item, toolOutputs, known || valueField, depth + 1);
      }
    }
  }

  for (const row of messages) {
    if (row.role !== 'assistant' && row.role !== 'tool') continue;
    const text = plain(row.content) || plain(row.text) || plain(row.context);
    const tool = plain(row.tool_name) || plain(row.name);
    if (row.role === 'assistant') {
      extractText(text);
      if (Array.isArray(row.content)) {
        for (const part of row.content.map(record)) {
          if (part.type === 'text') extractText(plain(part.text));
          if (part.type === 'image_url')
            add(plain(record(part.image_url).url), '', true);
        }
      }
      continue;
    }
    const generates = outputTool.test(tool) || /^bfl_flux3_/i.test(tool);
    if (tool === 'browser_vision') {
      for (const match of text.matchAll(/Screenshot path:\s*([^\r\n<>]+)/gi))
        add(match[1], '', true, true);
    }
    structured(row.content, generates);
    if (text) {
      const unwrapped =
        text.match(
          /^<untrusted_tool_result\b[^>]*>[\s\S]*?\r?\n\r?\n([\s\S]*)<\/untrusted_tool_result>$/i,
        )?.[1] || text;
      try {
        const payload: unknown = JSON.parse(unwrapped);
        if (tool === 'terminal') extractText(plain(record(payload).output));
        structured(payload, generates);
      } catch {
        if (generates || tool === 'terminal') extractText(unwrapped);
      }
    }
  }
  return Array.from(artifacts.values());
}
