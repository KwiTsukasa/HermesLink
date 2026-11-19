import {t} from '../shared/i18n';
import {TurboModuleRegistry} from 'react-native';
import type {TurboModule} from 'react-native';

interface HermesLiveModule extends TurboModule {
  watch(viewer: string, profile: string, board: string): Promise<string>;
  snapshot(viewer: string): Promise<string | null>;
  stop(viewer: string): void;
}

export function getHermesLive(): HermesLiveModule {
  const module = TurboModuleRegistry.get<HermesLiveModule>('HermesLive');
  if (!module) {
    throw new Error(t('mobile.hermesLive.thisVersionIsMissingTheLiveBoardModule'));
  }
  return module;
}

export function stopHermesLive(viewer: string): void {
  TurboModuleRegistry.get<HermesLiveModule>('HermesLive')?.stop(viewer);
}
