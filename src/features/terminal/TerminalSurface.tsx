import {t, useI18n} from '../../shared/i18n';
import {useImperativeHandle, useRef} from 'react';
import type {ElementRef, Ref} from 'react';
import {findNodeHandle, requireNativeComponent, UIManager} from 'react-native';
import type {NativeSyntheticEvent, ViewProps} from 'react-native';

export type TerminalSize = {cols: number; rows: number};
export type TerminalKey =
  | 'escape'
  | 'tab'
  | 'interrupt'
  | 'enter'
  | 'up'
  | 'down'
  | 'left'
  | 'right';

export type TerminalRendererEvent =
  | ({kind: 'size'} & TerminalSize)
  | {kind: 'written'; sequence: number; viewId: string}
  | {kind: 'input'; data: string; viewId: string}
  | {kind: 'error'};

export type TerminalSurfaceHandle = {
  setInput(viewId: string | null): void;
  reset(): void;
  clear(): void;
  focus(): void;
  copy(): void;
  paste(): void;
  key(key: TerminalKey): void;
  write(data: string, sequence: number, viewId: string, replay: boolean): void;
};

const componentName = 'HermesTerminalSurface';
const NativeSurface = requireNativeComponent<
  ViewProps & {
    onTerminalMessage: (event: NativeSyntheticEvent<{message: string}>) => void;
  }
>(componentName);

function readMessage(message: string): TerminalRendererEvent | undefined {
  try {
    const event = JSON.parse(message);
    if (event?.kind === 'error') {
      return {kind: 'error'};
    }
    if (
      event?.kind === 'size' &&
      Number.isInteger(event.cols) &&
      event.cols >= 1 &&
      event.cols <= 2000 &&
      Number.isInteger(event.rows) &&
      event.rows >= 1 &&
      event.rows <= 1000
    ) {
      return {kind: 'size', cols: event.cols, rows: event.rows};
    }
    if (
      event?.kind === 'written' &&
      Number.isInteger(event.sequence) &&
      typeof event.viewId === 'string'
    ) {
      return {kind: 'written', sequence: event.sequence, viewId: event.viewId};
    }
    if (
      event?.kind === 'input' &&
      typeof event.data === 'string' &&
      event.data &&
      typeof event.viewId === 'string'
    ) {
      return {kind: 'input', data: event.data, viewId: event.viewId};
    }
  } catch {
    // A malformed renderer event has no effect on the remote shell.
  }
}

export function TerminalSurface({
  ref,
  onMessage,
  style,
}: Pick<ViewProps, 'style'> & {
  ref: Ref<TerminalSurfaceHandle>;
  onMessage: (event: TerminalRendererEvent) => void;
}) {
  useI18n();
  const nativeRef = useRef<ElementRef<typeof NativeSurface>>(null);

  useImperativeHandle(ref, () => {
    function send(
      command: string,
      args: Array<string | number | boolean | null> = [],
    ) {
      const tag = findNodeHandle(nativeRef.current);
      const config = UIManager.getViewManagerConfig(componentName) as {
        Commands?: Record<string, number>;
      } | null;
      const commandId = config?.Commands?.[command];
      if (typeof tag === 'number' && typeof commandId === 'number') {
        UIManager.dispatchViewManagerCommand(tag, commandId, args);
      }
    }

    return {
      setInput: (viewId) => send('setInput', [viewId]),
      reset: () => send('reset'),
      clear: () => send('clear'),
      focus: () => send('focus'),
      copy: () => send('copy'),
      paste: () => send('paste'),
      key: (key) => send('key', [key]),
      write: (data, sequence, viewId, replay) =>
        send('write', [data, sequence, viewId, replay]),
    };
  }, []);

  return (
    <NativeSurface
      ref={nativeRef}
      style={style}
      accessibilityLabel={t('mobile.terminalSurface.terminalOutputAndKeyboardInput')}
      onTerminalMessage={(event) => {
        const message = readMessage(event.nativeEvent.message);
        if (message) {
          onMessage(message);
        }
      }}
    />
  );
}
