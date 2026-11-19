import {t} from '../shared/i18n';
import {TurboModuleRegistry} from 'react-native';
import type {TurboModule} from 'react-native';

export type OfficialDestinationId =
  | 'settings'
  | 'providers'
  | 'liveVoice'
  | 'mcp'
  | 'relay'
  | 'messaging'
  | 'groups';

export type OfficialDestination = {
  id: OfficialDestinationId;
  title: string;
  guidance: string;
  direct: boolean;
};

export type ProviderStatus = {
  id: string;
  label: string;
  connected: boolean | null;
  deviceFlowAvailable: boolean;
};

export type WebappSignInOption = {
  id: string;
  label: string;
  supportsPassword: boolean;
};

export type ProviderVerification = {
  id: string;
  userCode: string;
  expiresAt: number;
  intervalSeconds: number;
};

export type ProviderLoginStatus = {
  state: 'pending' | 'approved' | 'denied' | 'expired' | 'cancelled' | 'error';
  message?: string;
};

interface HermesOfficialModule extends TurboModule {
  destinations(): Promise<OfficialDestination[]>;
  open(id: OfficialDestinationId): Promise<void>;
  webappSignInOptions(): Promise<WebappSignInOption[]>;
  providers(): Promise<ProviderStatus[]>;
  beginProviderLogin(providerId: string): Promise<ProviderVerification>;
  openProviderVerification(id: string): Promise<void>;
  pollProviderLogin(id: string): Promise<ProviderLoginStatus>;
  cancelProviderLogin(id: string): Promise<void>;
}

export function getHermesOfficial(): HermesOfficialModule {
  const module = TurboModuleRegistry.get<HermesOfficialModule>('HermesOfficial');
  if (!module) {
    throw new Error(
      t('mobile.hermesOfficial.thisVersionIsMissingTheWebappEntryPoint'),
    );
  }
  return module;
}

export async function openOfficialDestination(
  id: OfficialDestinationId,
): Promise<void> {
  await getHermesOfficial().open(id);
}
