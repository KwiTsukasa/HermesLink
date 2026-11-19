import {hasTranslation, t} from '../../../shared/i18n';
import {getHermesWorkspace} from '../../../native/HermesWorkspace';
import type {
  Attachment,
  Entry,
  OperationInput,
  OperationResult,
  RouteIndex,
} from '../types';
import {record} from '../utils/data';

export async function getCatalog(): Promise<Entry[]> {
  const catalog = record(JSON.parse(await getHermesWorkspace().catalog()));
  if (!Array.isArray(catalog.entries)) {
    throw new Error(t('mobile.workspace.invalidFeatureCatalog'));
  }
  return catalog.entries as Entry[];
}

export function indexRoutes(entries: readonly Entry[]): RouteIndex {
  return new Map(
    entries.map((entry) => [`${entry.method.toUpperCase()} ${entry.path}`, entry]),
  );
}

export function catalogGroupId(entry: Entry): string {
  return entry.groupId || entry.group || entry.kind;
}

export function catalogGroupLabel(entry: Entry): string {
  return entry.groupKey && hasTranslation(entry.groupKey)
    ? t(entry.groupKey)
    : entry.group || entry.kind;
}

export function catalogEntryLabel(entry: Entry): string {
  return entry.labelKey && hasTranslation(entry.labelKey)
    ? t(entry.labelKey)
    : entry.label;
}

export function gatewayRoute(routes: RouteIndex, method: string): Entry | undefined {
  return routes.get(`${method.toUpperCase()} /api/ws`);
}

export async function executeOperation(
  entry: Entry,
  input: OperationInput = {},
  confirmed = false,
): Promise<OperationResult> {
  if (entry.unavailable) {
    throw new Error(entry.unavailable);
  }

  const raw = await getHermesWorkspace().execute(
    entry.id,
    JSON.stringify(input),
    confirmed,
  );
  const envelope = record(JSON.parse(raw));
  if (typeof envelope.fileHandle === 'string') {
    return {
      kind: 'file',
      handle: envelope.fileHandle,
      name:
        typeof envelope.fileName === 'string'
          ? envelope.fileName
          : t('mobile.workspace.hermesDownload'),
    };
  }
  return {kind: 'value', value: envelope.value};
}

export async function invoke(
  entry: Entry | undefined,
  input: OperationInput = {},
  confirmed = false,
): Promise<unknown> {
  if (!entry) {
    throw new Error(t('mobile.jobEditor.thisGatewayDoesNotOfferThisOperation'));
  }
  const result = await executeOperation(entry, input, confirmed);
  if (result.kind === 'file') {
    throw new Error(t('mobile.workspace.openTheFeatureCatalogToDownloadTheFile'));
  }
  return result.value;
}

export async function pickAttachment(): Promise<Attachment> {
  const data = record(JSON.parse(await getHermesWorkspace().pickAttachment()));
  if (typeof data.id !== 'string' || typeof data.name !== 'string') {
    throw new Error(t('mobile.workspace.couldNotReadFileInformation'));
  }
  return {id: data.id, name: data.name};
}

export function saveFile(handle: string): Promise<boolean> {
  return getHermesWorkspace().saveFile(handle);
}
