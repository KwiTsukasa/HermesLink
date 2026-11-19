import {t} from '../../../shared/i18n';
import type {Dict} from '../types';

export function isRecord(value: unknown): value is Dict {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function record(value: unknown): Dict {
  return isRecord(value) ? value : {};
}

export function rows(value: unknown, keys: string[]): Dict[] {
  if (Array.isArray(value)) {
    return value.filter(isRecord);
  }
  const data = record(value);
  for (const key of keys) {
    const items = data[key];
    if (Array.isArray(items)) {
      return items.filter(isRecord);
    }
  }
  return [];
}

export function display(value: unknown): string {
  if (value == null || value === '') {
    return '—';
  }
  if (typeof value === 'boolean') {
    return value
      ? t('platform.discord.prompt.affirm')
      : t('gateway.status.state_no');
  }
  return typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
}

export function textValue(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}

export function plainText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

export function profileQuery(profile?: string): Dict {
  return profile ? {profile} : {};
}

export function errorMessage(error: unknown): string {
  const message = record(error).message;
  return typeof message === 'string'
    ? message
    : t('mobile.data.theOperationFailedTryAgain');
}

export function requireAccepted(value: unknown): Dict {
  const response = record(value);
  if (response.ok === false || response.success === false) {
    throw new Error(
      plainText(response.error) ||
        plainText(response.message) ||
        plainText(response.detail) ||
        t('mobile.data.theGatewayDeclinedTheOperation'),
    );
  }
  return response;
}

export function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.map(plainText).filter(Boolean) : [];
}
