import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useSyncExternalStore,
} from 'react';
import type {PropsWithChildren} from 'react';
import {
  DeviceEventEmitter,
  StyleSheet,
  TurboModuleRegistry,
  View,
} from 'react-native';
import type {TurboModule} from 'react-native';

import media from './media.json';
import official from './official.json';
import supplement from './supplement.json';
import availableLocales from './locales.json';

export type Locale = string;
export type TranslationValues = Record<string, string | number>;
type Messages = Readonly<Record<string, Readonly<Partial<Record<Locale, string>>>>>;

type LocaleSettings = {
  mode: 'system' | 'pinned';
  effective: Locale;
  locale?: Locale;
};

interface NativeLocaleModule extends TurboModule {
  getSettings(): Promise<LocaleSettings>;
  setLocale(locale: Locale | null): Promise<LocaleSettings>;
}

const catalog: Messages = {...official, ...supplement, ...media};
const listeners = new Set<() => void>();
export const locales = availableLocales;
const localeIds = new Set(locales.map((item) => item.id));

function deviceLocale(): Locale {
  const tag = Intl.DateTimeFormat().resolvedOptions().locale;
  if (localeIds.has(tag)) return tag;
  if (/^zh(?:$|[-_])/i.test(tag)) {
    return /^zh[-_](?:Hant|TW|HK|MO)/i.test(tag) && localeIds.has('zh-Hant')
      ? 'zh-Hant'
      : 'zh-CN';
  }
  const language = tag.split('-')[0];
  return localeIds.has(language) ? language : 'en';
}

let settings: LocaleSettings = {mode: 'system', effective: deviceLocale()};
let selectionRevision = 0;

function nativeLocale() {
  return TurboModuleRegistry.get<NativeLocaleModule>('HermesLocale');
}

function applySettings(next: LocaleSettings): void {
  if (
    next.mode === settings.mode &&
    next.effective === settings.effective &&
    next.locale === settings.locale
  )
    return;
  settings = next;
  for (const listener of listeners) listener();
}

export function getLocale(): Locale {
  return settings.effective;
}

export async function setLocale(locale: Locale | null): Promise<void> {
  const revision = ++selectionRevision;
  const native = nativeLocale();
  const next: LocaleSettings = native
    ? await native.setLocale(locale)
    : locale
      ? {mode: 'pinned', effective: locale, locale}
      : {mode: 'system', effective: deviceLocale()};
  if (revision === selectionRevision) applySettings(next);
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function t(key: string, values?: TranslationValues): string {
  const locale = getLocale();
  const count = values?.count;
  const variant =
    typeof count === 'number' && Number.isFinite(count) && catalog[`${key}.one`]
      ? catalog[`${key}.${new Intl.PluralRules(locale).select(count)}`]
      : undefined;
  const template =
    variant?.[locale] ?? catalog[key]?.[locale] ?? variant?.en ?? catalog[key]?.en;
  if (template === undefined) return key;
  return values
    ? template.replace(/\{([a-zA-Z0-9_]+)\}/g, (token, name: string) => {
        if (!Object.prototype.hasOwnProperty.call(values, name)) return token;
        const value = values[name];
        return typeof value === 'number' ? formatNumber(value) : value;
      })
    : template;
}

export function hasTranslation(key: string): boolean {
  const entry = catalog[key];
  return typeof entry?.[getLocale()] === 'string' || typeof entry?.en === 'string';
}

export function formatNumber(value: number): string {
  return new Intl.NumberFormat(getLocale()).format(value);
}

export function formatDate(
  value: Date | number,
  options?: Intl.DateTimeFormatOptions,
): string {
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  return new Intl.DateTimeFormat(
    getLocale(),
    options ?? {dateStyle: 'medium', timeStyle: 'short'},
  ).format(date);
}

type LocaleState = {
  locale: Locale;
  mode: LocaleSettings['mode'];
  setLocale: typeof setLocale;
  t: typeof t;
};
const LocaleContext = createContext<LocaleState>({
  locale: settings.effective,
  mode: settings.mode,
  setLocale,
  t,
});

export function LocaleProvider({children}: PropsWithChildren) {
  const selected = useSyncExternalStore(subscribe, () => settings);
  useEffect(() => {
    const native = nativeLocale();
    if (!native) return undefined;
    let mounted = true;
    const revision = selectionRevision;
    const changes = DeviceEventEmitter.addListener(
      'HermesLocaleChanged',
      applySettings,
    );
    native
      .getSettings()
      .then((next) => {
        if (mounted && revision === selectionRevision) applySettings(next);
      })
      .catch(() => {});
    return () => {
      mounted = false;
      changes.remove();
    };
  }, []);
  const state = useMemo(
    () => ({locale: selected.effective, mode: selected.mode, setLocale, t}),
    [selected],
  );
  const rtl = locales.some((item) => item.id === selected.effective && item.rtl);
  return (
    <LocaleContext.Provider value={state}>
      <View style={[styles.root, rtl ? styles.rtl : styles.ltr]}>{children}</View>
    </LocaleContext.Provider>
  );
}

export function useI18n(): LocaleState {
  return useContext(LocaleContext);
}

const styles = StyleSheet.create({
  root: {flex: 1},
  rtl: {direction: 'rtl'},
  ltr: {direction: 'ltr'},
});
