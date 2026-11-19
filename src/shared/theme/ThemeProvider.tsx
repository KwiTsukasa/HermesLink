import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type {PropsWithChildren} from 'react';
import {DeviceEventEmitter, TurboModuleRegistry, useColorScheme} from 'react-native';
import type {TurboModule} from 'react-native';

import {darkPalette, lightPalette} from './tokens';
import type {Palette} from './tokens';

export type AppearanceMode = 'system' | 'light' | 'dark';
export type AppearanceSnapshot = {
  profile: string;
  themeName: string;
  label: string;
  mode: AppearanceMode;
  sync: 'local' | 'cached' | 'syncing' | 'synced' | 'offline' | 'pending';
  revision: number;
  scopeVersion: number;
  available: boolean;
  signedIn: boolean;
  light: Palette;
  dark: Palette;
};

type Choice = {themeName?: string; mode?: AppearanceMode};
interface NativeAppearance extends TurboModule {
  getAppearance(profile: string): Promise<AppearanceSnapshot>;
  refresh(profile: string): Promise<AppearanceSnapshot>;
  update(
    profile: string,
    themeName: string | null,
    mode: string | null,
  ): Promise<AppearanceSnapshot>;
}

type AppearanceContextValue = {
  snapshot: AppearanceSnapshot;
  setProfile(profile: string): void;
  refresh(): Promise<void>;
  update(change: Choice): Promise<void>;
};

const EVENT = 'HermesAppearanceChanged';
const native = () => TurboModuleRegistry.get<NativeAppearance>('HermesAppearance');
const fallback = (profile: string): AppearanceSnapshot => ({
  profile,
  themeName: 'nous',
  label: 'Nous',
  mode: 'system',
  sync: 'local',
  revision: 0,
  scopeVersion: 0,
  available: true,
  signedIn: false,
  light: lightPalette,
  dark: darkPalette,
});

const ThemeContext = createContext(lightPalette);
const AppearanceContext = createContext<AppearanceContextValue>({
  snapshot: fallback('default'),
  setProfile: () => {},
  refresh: async () => {},
  update: async () => {},
});

export function ThemeProvider({children}: PropsWithChildren) {
  const systemDark = useColorScheme() === 'dark';
  const [profile, setProfile] = useState('default');
  const [snapshot, setSnapshot] = useState(() => fallback('default'));
  const active = useRef(profile);
  const newestScope = useRef(0);
  active.current = profile;

  const accept = useCallback((value: AppearanceSnapshot) => {
    if (
      value.profile === active.current &&
      value.scopeVersion >= newestScope.current
    ) {
      newestScope.current = value.scopeVersion;
      setSnapshot(value);
    }
  }, []);

  useEffect(() => {
    let mounted = true;
    const subscription = DeviceEventEmitter.addListener(EVENT, accept);
    const module = native();
    if (module) {
      module
        .getAppearance(profile)
        .then((value) => {
          if (mounted) accept(value);
        })
        .catch(() => undefined);
      module
        .refresh(profile)
        .then((value) => {
          if (mounted) accept(value);
        })
        .catch(() => undefined);
    }
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, [accept, profile]);

  const refresh = useCallback(async () => {
    const value = await native()?.refresh(profile);
    if (value) accept(value);
  }, [accept, profile]);

  const update = useCallback(
    async ({themeName, mode}: Choice) => {
      const value = await native()?.update(profile, themeName ?? null, mode ?? null);
      if (value) accept(value);
    },
    [accept, profile],
  );

  const visible = snapshot.profile === profile ? snapshot : fallback(profile);
  const palette =
    visible.mode === 'dark' || (visible.mode === 'system' && systemDark)
      ? visible.dark
      : visible.light;
  const state = useMemo(
    () => ({snapshot: visible, setProfile, refresh, update}),
    [visible, refresh, update],
  );
  return (
    <AppearanceContext.Provider value={state}>
      <ThemeContext.Provider value={palette}>{children}</ThemeContext.Provider>
    </AppearanceContext.Provider>
  );
}

export function useTheme() {
  return useContext(ThemeContext);
}

export function useAppearance() {
  return useContext(AppearanceContext);
}

export function useAppearanceProfile(profile?: string) {
  const {setProfile} = useAppearance();
  useEffect(() => {
    setProfile(profile || 'default');
  }, [profile, setProfile]);
}
