import {t} from '../shared/i18n';
import {DeviceEventEmitter, TurboModuleRegistry} from 'react-native';
import type {TurboModule} from 'react-native';

export type HermesTerminalState =
  | 'idle'
  | 'connecting'
  | 'connected'
  | 'detaching'
  | 'detached'
  | 'closing';

export type HermesTerminalTab = {
  id: string;
  number: number;
  profile: string | null;
  state: HermesTerminalState;
};

export type HermesTerminalEvent = {tabId: string} & (
  | {kind: 'connected'; reconnected: boolean; truncated: boolean}
  | {
      kind: 'output';
      data: string;
      sequence: number;
      replay: boolean;
      replayEnd: boolean;
    }
  | {kind: 'detached'}
  | {
      kind: 'closed';
      reason: 'exited' | 'terminated' | 'left' | 'expired';
    }
  | {kind: 'error'; code: string; message: string; canReconnect: boolean}
);

interface HermesTerminalModule extends TurboModule {
  getTabs(): Promise<HermesTerminalTab[]>;
  open(
    viewId: string,
    cols: number,
    rows: number,
    profile: string | null,
    cwd: string | null,
  ): Promise<string>;
  reconnect(
    viewId: string,
    tabId: string,
    cols: number,
    rows: number,
  ): Promise<void>;
  write(viewId: string, tabId: string, text: string): Promise<void>;
  resize(viewId: string, tabId: string, cols: number, rows: number): Promise<void>;
  detach(viewId: string, tabId: string): Promise<void>;
  terminate(viewId: string, tabId: string): Promise<void>;
  close(viewId: string): void;
  ackOutput(viewId: string, sequence: number): void;
}

export function getHermesTerminal(): HermesTerminalModule {
  const module = TurboModuleRegistry.get<HermesTerminalModule>('HermesTerminal');
  if (!module) {
    throw new Error(
      t('mobile.hermesTerminal.thisVersionIsMissingTheHostTerminalModule'),
    );
  }
  return module;
}

export function subscribeHermesTerminal(
  viewId: string,
  listener: (event: HermesTerminalEvent) => void,
) {
  return DeviceEventEmitter.addListener(
    'HermesTerminalEvent',
    (event: HermesTerminalEvent & {viewId: string}) => {
      if (event.viewId === viewId) {
        listener(event);
      }
    },
  );
}
