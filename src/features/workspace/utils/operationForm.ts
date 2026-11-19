import {t} from '../../../shared/i18n';
import type {Attachment, Entry, Field, OperationInput} from '../types';
import {isRecord} from './data';

const scopes = ['path', 'query', 'body'] as const;

export type OperationField = {
  scope: (typeof scopes)[number];
  key: string;
  field: Field;
};

export function operationFields(entry: Entry): OperationField[] {
  return scopes.flatMap((scope) =>
    (entry.params[scope] ?? []).map((field) => ({
      scope,
      field,
      key: `${scope}.${field.name}`,
    })),
  );
}

export function isFileField(field: Field) {
  return field.type.toLowerCase().includes('file');
}

function parseField(field: Field, value: string): unknown {
  const type = field.type.toLowerCase();
  const text = value.trim();
  if (type === 'string') {
    return value;
  }
  if (field.nullable && text === 'null') {
    return null;
  }

  switch (type) {
    case 'integer':
    case 'int':
    case 'number':
    case 'float': {
      const number = Number(text);
      const integer = type === 'integer' || type === 'int';
      if (
        !text ||
        !Number.isFinite(number) ||
        (integer && !Number.isSafeInteger(number))
      ) {
        throw new Error(
          t('mobile.operationForm.mustBeA', {
            v1: String(field.name),
            v2: String(
              integer
                ? t('mobile.operationForm.integer')
                : t('mobile.operationForm.number'),
            ),
          }),
        );
      }
      return number;
    }
    case 'boolean':
    case 'bool':
      if (text !== 'true' && text !== 'false') {
        throw new Error(
          t('mobile.operationForm.mustBeTrueOrFalse', {v1: String(field.name)}),
        );
      }
      return text === 'true';
    case 'object':
    case 'array':
    case 'json':
    case 'any': {
      let parsed: unknown;
      try {
        parsed = JSON.parse(value);
      } catch {
        if (type === 'any') {
          return value;
        }
        throw new Error(
          t('mobile.operationForm.mustContainValidJson', {v1: String(field.name)}),
        );
      }
      if (type === 'object' && !isRecord(parsed)) {
        throw new Error(
          t('mobile.operationForm.mustBeAJsonObject', {v1: String(field.name)}),
        );
      }
      if (type === 'array' && !Array.isArray(parsed)) {
        throw new Error(
          t('mobile.operationForm.mustBeAJsonArray', {v1: String(field.name)}),
        );
      }
      return parsed;
    }
    default:
      return value;
  }
}

export function buildOperationInput(
  fields: readonly OperationField[],
  values: Readonly<Record<string, string>>,
  attachment: Attachment | null,
): OperationInput {
  const parameters: Required<Omit<OperationInput, 'attachment'>> = {
    path: {},
    query: {},
    body: {},
  };
  for (const {field, key, scope} of fields) {
    if (isFileField(field)) {
      if (!attachment && field.required) {
        throw new Error(
          t('mobile.operationForm.selectAFileFor', {v1: String(field.name)}),
        );
      }
      continue;
    }
    const value = values[key];
    if (value === undefined) {
      if (field.required) {
        throw new Error(t('mobile.operationForm.enter', {v1: String(field.name)}));
      }
      continue;
    }
    parameters[scope][field.name] = parseField(field, value);
  }
  return attachment ? {...parameters, attachment: attachment.id} : parameters;
}
