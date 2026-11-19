import {TurboModuleRegistry} from 'react-native';
import type {TurboModule} from 'react-native';

interface HermesResourcesModule extends TurboModule {
  exportProfile(name: string): Promise<boolean>;
  importProfile(name: string): Promise<boolean>;
  playMedia(
    path: string,
    profile: string | null,
    sessionId: string | null,
  ): Promise<void>;
}

export function getHermesResources(): HermesResourcesModule | null {
  return TurboModuleRegistry.get<HermesResourcesModule>('HermesResources') ?? null;
}
