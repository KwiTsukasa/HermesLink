import {t} from '../../shared/i18n';
import {useCallback, useEffect, useRef, useState} from 'react';
import {AppState, Keyboard} from 'react-native';

import {
  getHermesTerminal,
  subscribeHermesTerminal,
} from '../../native/HermesTerminal';
import type {HermesTerminalTab} from '../../native/HermesTerminal';
import type {
  TerminalRendererEvent,
  TerminalSize,
  TerminalSurfaceHandle,
} from './TerminalSurface';

type Stage =
  | 'checking'
  | 'idle'
  | 'connecting'
  | 'restoring'
  | 'connected'
  | 'detaching'
  | 'closing';
type Launch =
  | {kind: 'open'; profile: string | null; cwd: string | null}
  | {kind: 'reconnect'; tabId: string};
type AfterDetach = Launch | (() => void);

type Attempt = {
  id: string;
  bridge: ReturnType<typeof getHermesTerminal>;
  subscription?: ReturnType<typeof subscribeHermesTerminal>;
  pendingOutput?: {sequence: number; replayEnd: boolean};
  replaying?: boolean;
  tabId?: string;
  lastSize?: TerminalSize;
};

const messages = {
  get exited() {
    return t('mobile.useTerminalSession.theShellExited');
  },
  get terminated() {
    return t('mobile.useTerminalSession.theTerminalSessionEnded');
  },
  get left() {
    return t('mobile.useTerminalSession.theTerminalDisconnected');
  },
  get expired() {
    return t('mobile.useTerminalSession.theServerEndedThisSessionOpenANew');
  },
};

let nextView = 0;

function codeOf(error: unknown) {
  return (error as {code?: string} | null)?.code;
}

export function useTerminalSession() {
  const surface = useRef<TerminalSurfaceHandle>(null);
  const attempt = useRef<Attempt | null>(null);
  const continuation = useRef<AfterDetach | null>(null);
  const viewport = useRef<TerminalSize>(undefined);
  const alive = useRef(false);
  const query = useRef(0);
  const stageRef = useRef<Stage>('checking');
  const [stage, setStage] = useState<Stage>('checking');
  const [tabs, setTabs] = useState<HermesTerminalTab[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [transcriptId, setTranscriptId] = useState<string>();
  const [size, setSize] = useState<TerminalSize>();
  const [rendererKey, setRendererKey] = useState(0);
  const [rendererFailed, setRendererFailed] = useState(false);
  const [notice, setNotice] = useState<{message: string; error?: boolean}>();

  const changeStage = useCallback((next: Stage) => {
    stageRef.current = next;
    setStage(next);
  }, []);

  const release = useCallback((current: Attempt) => {
    if (attempt.current === current) {
      attempt.current = null;
      current.subscription?.remove();
      current.bridge.close(current.id);
    }
  }, []);

  const refresh = useCallback(async () => {
    const stamp = ++query.current;
    try {
      const fresh = await getHermesTerminal().getTabs();
      if (alive.current && stamp === query.current) {
        setTabs(fresh);
        setSelectedId((id) =>
          fresh.some((tab) => tab.id === id) ? id : fresh[0]?.id,
        );
      }
    } catch {
      if (alive.current && stamp === query.current) {
        setNotice({
          message: t(
            'mobile.useTerminalSession.couldNotLoadTerminalSessionsTryAgain',
          ),
          error: true,
        });
      }
    } finally {
      if (
        alive.current &&
        stamp === query.current &&
        stageRef.current === 'checking'
      ) {
        changeStage('idle');
      }
    }
  }, [changeStage]);

  useEffect(() => {
    alive.current = true;
    refresh();
    return () => {
      alive.current = false;
      continuation.current = null;
      if (attempt.current) {
        release(attempt.current);
      }
    };
  }, [refresh, release]);

  function rebuildRenderer() {
    viewport.current = undefined;
    setSize(undefined);
    setRendererFailed(false);
    setRendererKey((key) => key + 1);
  }

  function showError(error: unknown, fallback: string) {
    const code = codeOf(error);
    if (code === 'SIGN_IN' || code === 'STALE_LOGIN') {
      rebuildRenderer();
      setTabs([]);
      setSelectedId(undefined);
      setNotice({
        message: t('mobile.useTerminalSession.yourLoginHasExpiredReturnToChatAnd'),
        error: true,
      });
      return;
    }
    const message = (error as {message?: unknown} | null)?.message;
    setNotice({
      message: typeof message === 'string' && message ? message : fallback,
      error: true,
    });
  }

  function makeAttempt(tabId?: string): Attempt {
    const bridge = getHermesTerminal();
    const current: Attempt = {
      bridge,
      tabId,
      id: `terminal-${Date.now()}-${++nextView}`,
    };
    attempt.current = current;
    current.subscription = subscribeHermesTerminal(current.id, (event) => {
      if (!alive.current || attempt.current !== current) {
        return;
      }
      current.tabId = event.tabId;
      if (event.kind === 'output') {
        current.pendingOutput = {
          sequence: event.sequence,
          replayEnd: event.replayEnd,
        };
        surface.current?.write(event.data, event.sequence, current.id, event.replay);
        return;
      }
      if (event.kind === 'connected') {
        setSelectedId(event.tabId);
        setTranscriptId(event.tabId);
        setNotice(
          event.truncated
            ? {
                message: t(
                  'mobile.useTerminalSession.earlierOutputIsOutsideTheRetainedHistory',
                ),
              }
            : undefined,
        );
        current.replaying = event.reconnected;
        changeStage(event.reconnected ? 'restoring' : 'connected');
        surface.current?.setInput(event.reconnected ? null : current.id);
        refresh();
        return;
      }

      const next = continuation.current;
      continuation.current = null;
      surface.current?.setInput(null);
      release(current);
      changeStage('idle');
      if (event.kind === 'error') {
        showError(
          event,
          event.canReconnect
            ? t('mobile.useTerminalSession.disconnectedTheSessionCanBeResumed')
            : t('mobile.useTerminalSession.theTerminalConnectionClosed'),
        );
      } else if (event.kind === 'closed') {
        setNotice({message: messages[event.reason]});
      } else {
        setNotice({
          message: t(
            'mobile.useTerminalSession.detachedTheTerminalIsStillRunningOnThe',
          ),
        });
      }
      refresh();

      if (event.kind === 'detached' && next && AppState.currentState === 'active') {
        if (typeof next === 'function') {
          next();
        } else {
          connect(next);
        }
      }
    });
    return current;
  }

  async function connect(launch: Launch) {
    const dimensions = viewport.current;
    if (!dimensions || stageRef.current !== 'idle') {
      return;
    }
    Keyboard.dismiss();
    surface.current?.setInput(null);
    surface.current?.reset();
    setTranscriptId(undefined);
    setNotice(undefined);
    changeStage('connecting');
    let current: Attempt | undefined;
    try {
      current = makeAttempt(launch.kind === 'reconnect' ? launch.tabId : undefined);
      current.lastSize = dimensions;
      if (launch.kind === 'reconnect') {
        setSelectedId(launch.tabId);
        await current.bridge.reconnect(
          current.id,
          launch.tabId,
          dimensions.cols,
          dimensions.rows,
        );
      } else {
        await current.bridge.open(
          current.id,
          dimensions.cols,
          dimensions.rows,
          launch.profile,
          launch.cwd,
        );
      }
    } catch (error) {
      if (alive.current && (!current || attempt.current === current)) {
        if (current) {
          release(current);
        }
        changeStage('idle');
        showError(
          error,
          t('mobile.useTerminalSession.couldNotConnectToTheTerminal'),
        );
        refresh();
      }
    }
  }

  async function detach(next?: AfterDetach) {
    const current = attempt.current;
    if (
      !current?.tabId ||
      (stageRef.current !== 'connected' && stageRef.current !== 'restoring')
    ) {
      return;
    }
    continuation.current = next ?? null;
    changeStage('detaching');
    surface.current?.setInput(null);
    Keyboard.dismiss();
    try {
      await current.bridge.detach(current.id, current.tabId);
    } catch (error) {
      if (alive.current && attempt.current === current) {
        continuation.current = null;
        changeStage(current.replaying ? 'restoring' : 'connected');
        surface.current?.setInput(current.replaying ? null : current.id);
        showError(
          error,
          t('mobile.useTerminalSession.couldNotDetachFromTheTerminal'),
        );
      }
    }
  }

  function switchTo(launch: Launch) {
    if (stageRef.current === 'connected') {
      detach(launch);
    } else {
      connect(launch);
    }
  }

  async function terminate(tabId: string) {
    const previous = stageRef.current;
    if (previous !== 'idle' && previous !== 'connected') {
      return;
    }
    const attached = attempt.current;
    if (attached && attached.tabId !== tabId) {
      detach(() => terminate(tabId));
      return;
    }
    setNotice(undefined);
    changeStage('closing');
    surface.current?.setInput(null);
    Keyboard.dismiss();
    let current: Attempt | undefined;
    try {
      current = attached ?? makeAttempt(tabId);
      await current.bridge.terminate(current.id, tabId);
    } catch (error) {
      if (alive.current && (!current || attempt.current === current)) {
        if (current && !attached) {
          release(current);
        }
        changeStage(previous);
        surface.current?.setInput(
          previous === 'connected' ? (attached?.id ?? null) : null,
        );
        showError(
          error,
          t('mobile.useTerminalSession.couldNotEndTheTerminalSessionTryAgain'),
        );
        refresh();
      }
    }
  }

  useEffect(() => {
    const current = attempt.current;
    if (
      !current?.tabId ||
      !size ||
      stage !== 'connected' ||
      (size.cols === current.lastSize?.cols && size.rows === current.lastSize?.rows)
    ) {
      return;
    }
    const tabId = current.tabId;
    const timer = setTimeout(() => {
      if (attempt.current === current && stageRef.current === 'connected') {
        current.bridge
          .resize(current.id, tabId, size.cols, size.rows)
          .then(() => {
            current.lastSize = size;
          })
          .catch(() => {
            if (attempt.current === current && stageRef.current === 'connected') {
              setNotice({
                message: t('mobile.useTerminalSession.couldNotResizeTheTerminal'),
                error: true,
              });
            }
          });
      }
    }, 80);
    return () => clearTimeout(timer);
  }, [size, stage]);

  function receive(event: TerminalRendererEvent) {
    if (event.kind === 'size') {
      const dimensions = {cols: event.cols, rows: event.rows};
      viewport.current = dimensions;
      setSize(dimensions);
      return;
    }
    const current = attempt.current;
    if (event.kind === 'error') {
      setRendererFailed(true);
      viewport.current = undefined;
      setSize(undefined);
      if (stageRef.current === 'connected' || stageRef.current === 'restoring') {
        detach();
      } else if (current && stageRef.current === 'connecting') {
        release(current);
        changeStage('idle');
        refresh();
      }
      return;
    }
    if (!current) {
      return;
    }
    if (event.kind === 'written') {
      const output = current.pendingOutput;
      if (event.viewId === current.id && event.sequence === output?.sequence) {
        current.pendingOutput = undefined;
        if (output.replayEnd) {
          current.replaying = false;
          if (stageRef.current === 'restoring') {
            changeStage('connected');
            surface.current?.setInput(current.id);
          }
        }
        current.bridge.ackOutput(current.id, event.sequence);
      }
    } else if (
      event.viewId === current.id &&
      current.tabId &&
      stageRef.current === 'connected'
    ) {
      current.bridge
        .write(current.id, current.tabId, event.data)
        .catch((error: unknown) => {
          if (attempt.current === current && stageRef.current === 'connected') {
            if (codeOf(error) === 'STALE_LOGIN' || codeOf(error) === 'SIGN_IN') {
              release(current);
              changeStage('idle');
            }
            showError(
              error,
              t('mobile.useTerminalSession.couldNotSendTerminalInput'),
            );
          }
        });
    }
  }

  return {
    stage,
    tabs,
    selectedId,
    setSelectedId,
    transcriptId,
    size,
    rendererFailed,
    rendererKey,
    notice,
    surface,
    refresh,
    receive,
    rebuildRenderer,
    detach,
    terminate,
    open: (profile: string | null, cwd: string | null) =>
      switchTo({kind: 'open', profile, cwd}),
    reconnect: (tabId: string) => switchTo({kind: 'reconnect', tabId}),
  };
}
