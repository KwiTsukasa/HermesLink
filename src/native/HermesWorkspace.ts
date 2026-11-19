import {t} from '../shared/i18n';
import {TurboModuleRegistry} from 'react-native';
import type {TurboModule} from 'react-native';

interface HermesWorkspaceModule extends TurboModule {
  catalog(): Promise<string>;
  execute(id: string, parameters: string, confirmed: boolean): Promise<string>;
  listManagedFiles(path: string | null): Promise<string>;
  previewManagedFile(path: string): Promise<string>;
  pickAttachment(): Promise<string>;
  saveFile(handle: string): Promise<boolean>;
  openConversation(profile: string, sessionId: string): Promise<void>;
  closeWorkspace(): void;
}

export function getHermesWorkspace(): HermesWorkspaceModule {
  const module = TurboModuleRegistry.get<HermesWorkspaceModule>('HermesWorkspace');
  if (!module) {
    throw new Error(
      t('mobile.hermesWorkspace.thisVersionIsMissingTheWorkspaceModule'),
    );
  }
  return module;
}

export function closeWorkspace(): void {
  TurboModuleRegistry.get<HermesWorkspaceModule>(
    'HermesWorkspace',
  )?.closeWorkspace();
}
