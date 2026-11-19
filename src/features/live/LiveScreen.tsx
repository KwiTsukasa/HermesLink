import {formatDate, formatNumber, t, useI18n} from '../../shared/i18n';
import {useEffect, useRef, useState} from 'react';
import {AppState, ScrollView, StyleSheet, Text, View} from 'react-native';

import {getHermesLive, stopHermesLive} from '../../native/HermesLive';
import {getHermesWorkspace} from '../../native/HermesWorkspace';
import {useTheme} from '../../shared/theme/ThemeProvider';
import {radius, spacing} from '../../shared/theme/tokens';
import {Button} from '../../shared/ui/Button';
import {Feedback} from '../../shared/ui/Feedback';
import {PageHeading} from '../workspace/components/PageHeading';
import type {OperationInput} from '../workspace/types';
import {
  errorMessage,
  plainText,
  profileQuery,
  record,
  rows,
} from '../workspace/utils/data';
import {BotDesktopScreen} from './BotDesktopScreen';

type LiveScreenProps = {
  profile?: string;
  onBack: () => void;
  onOpenBoard?: (board: string) => void;
};

const EMPTY = {state: 'closed', events: [] as Record<string, unknown>[]};
const PHASES: Record<string, string> = {
  get closed() {
    return t('mobile.live.waitingToConnect');
  },
  get connecting() {
    return t('mobile.live.connectingToTheBoard');
  },
  get failed() {
    return t('mobile.live.connectionInterruptedReconnectToResume');
  },
  get invalid_data() {
    return t('mobile.live.hermesReturnedInvalidBoardEvents');
  },
  get live() {
    return t('mobile.live.receivingBoardEvents');
  },
  get signed_out() {
    return t('mobile.live.yourLoginHasExpiredSignInToHermes');
  },
  get unavailable() {
    return t('mobile.live.boardSubscriptionUnavailableCheckThePluginAndBoard');
  },
};
const EVENT_NAMES: Record<string, string> = {
  get created() {
    return t('mobile.live.newTask');
  },
  get updated() {
    return t('mobile.live.taskUpdated');
  },
  get ready() {
    return t('mobile.live.taskReady');
  },
  get claimed() {
    return t('mobile.live.taskClaimed');
  },
  get completed() {
    return t('mobile.live.taskCompleted');
  },
  get blocked() {
    return t('mobile.live.taskBlocked');
  },
  get failed() {
    return t('mobile.live.taskFailed');
  },
  get archived() {
    return t('mobile.live.taskArchived');
  },
  get comment() {
    return t('mobile.live.commentAdded');
  },
};

async function workspaceRead(id: string, args: OperationInput = {}) {
  const json = await getHermesWorkspace().execute(id, JSON.stringify(args), false);
  return record(record(JSON.parse(json)).value);
}

function eventSnapshot(json: string) {
  const value = record(JSON.parse(json));
  return {
    state: plainText(value.state) || 'closed',
    events: rows(value, ['events']),
  };
}

export function LiveScreen({profile, onBack, onOpenBoard}: LiveScreenProps) {
  useI18n();
  const colors = useTheme();
  const [viewer] = useState(
    () => `live-${Date.now()}-${Math.random().toString(36).slice(2)}`,
  );
  const revision = useRef(0);
  const lastSnapshot = useRef<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [stream, setStream] = useState(EMPTY);
  const [server, setServer] = useState<Record<string, unknown>>({});
  const [serverError, setServerError] = useState('');
  const [displayStatus, setDisplayStatus] = useState<Record<string, unknown>>({});
  const [showDesktop, setShowDesktop] = useState(false);
  const [boards, setBoards] = useState<Record<string, unknown>[]>([]);
  const [board, setBoard] = useState('');
  const [boardError, setBoardError] = useState('');
  const [connectingError, setConnectingError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let current = true;
    setServerError('');
    workspaceRead('rest:GET:/api/status').then(
      (value) => current && setServer(value),
      (error: unknown) => current && setServerError(errorMessage(error)),
    );
    workspaceRead('rpc:display.status', {body: profileQuery(profile)}).then(
      (value) => current && setDisplayStatus(value),
      () => current && setDisplayStatus({}),
    );
    setLoading(true);
    setBoardError('');
    workspaceRead('rest:GET:/api/plugins/kanban/boards', {
      query: profileQuery(profile),
    }).then(
      (value) => {
        if (current) {
          const list = rows(value, ['boards']);
          setBoards(list);
          setBoard((selected) => {
            if (list.some((item) => item.slug === selected)) {
              return selected;
            }
            return plainText(value.current) || plainText(list[0]?.slug);
          });
          setLoading(false);
        }
      },
      (error: unknown) => {
        if (current) {
          setBoardError(errorMessage(error));
          setLoading(false);
        }
      },
    );
    return () => {
      current = false;
    };
  }, [profile, refresh]);

  useEffect(() => {
    if (showDesktop) {
      return;
    }
    let current = true;
    let busy = false;
    const timer = setInterval(() => {
      if (busy || AppState.currentState !== 'active') {
        return;
      }
      const observing = revision.current;
      busy = true;
      Promise.resolve()
        .then(() => getHermesLive().snapshot(viewer))
        .then((json) => {
          if (
            current &&
            observing === revision.current &&
            lastSnapshot.current !== json
          ) {
            lastSnapshot.current = json;
            setStream(json ? eventSnapshot(json) : EMPTY);
          }
        })
        .catch(() => undefined)
        .finally(() => {
          busy = false;
        });
    }, 750);
    return () => {
      current = false;
      revision.current += 1;
      lastSnapshot.current = null;
      clearInterval(timer);
      stopHermesLive(viewer);
    };
  }, [profile, showDesktop, viewer]);

  function chooseBoard(value: string) {
    if (value !== board) {
      revision.current += 1;
      lastSnapshot.current = null;
      stopHermesLive(viewer);
      setBoard(value);
      setStream(EMPTY);
      setConnectingError('');
    }
  }

  async function connect() {
    const current = ++revision.current;
    setConnectingError('');
    setStream((value) => ({...value, state: 'connecting'}));
    try {
      const json = await getHermesLive().watch(viewer, profile || '', board);
      if (current === revision.current) {
        lastSnapshot.current = json;
        setStream(eventSnapshot(json));
      }
    } catch (error) {
      if (current === revision.current) {
        setConnectingError(errorMessage(error));
        setStream((value) => ({...value, state: 'closed'}));
      }
    }
  }

  const watching = stream.state === 'live' || stream.state === 'connecting';
  const gatewayRunning = server.gateway_running;
  const version = plainText(server.version);
  const displayRunning = displayStatus.running === true;
  const displaySupported = displayStatus.supported;
  const selectedBoard = boards.find((item) => item.slug === board);
  const counts = record(selectedBoard?.counts);

  if (showDesktop) {
    return (
      <BotDesktopScreen profile={profile} onBack={() => setShowDesktop(false)} />
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <PageHeading title={t('mobile.live.liveBoard')} onBack={onBack} />
      <View
        style={[
          styles.card,
          {borderColor: colors.line, backgroundColor: colors.panel},
        ]}
      >
        <Text style={[styles.heading, {color: colors.ink}]}>
          {t('mobile.live.serverStatus')}
        </Text>
        <Text style={{color: colors.sub}}>
          {serverError ||
            (typeof gatewayRunning === 'boolean'
              ? gatewayRunning
                ? t('mobile.live.gatewayRunning')
                : t('mobile.live.gatewayStopped')
              : t('mobile.live.waitingForServerStatus'))}
          {version ? ` · v${version.replace(/^v/, '')}` : ''}
        </Text>
        {typeof displaySupported === 'boolean' ? (
          <Text style={{color: colors.sub}}>
            {displayRunning
              ? t('mobile.live.botDesktopRunning')
              : displaySupported
                ? t('mobile.live.botDesktopStopped')
                : t('mobile.live.thisServerDoesNotSupportTheBotDesktop')}
          </Text>
        ) : null}
        {displayRunning ? (
          <Button
            label={t('mobile.live.viewBotDesktop')}
            onPress={() => {
              setStream(EMPTY);
              setShowDesktop(true);
            }}
          />
        ) : null}
        <Button
          label={t('mobile.live.refreshServerStatusAndBoards')}
          onPress={() => setRefresh((value) => value + 1)}
        />
      </View>

      {loading ? (
        <Feedback tone="loading" message={t('mobile.live.loadingBoards')} />
      ) : null}
      {boardError ? <Feedback tone="error" message={boardError} /> : null}
      {!loading && !boardError && boards.length === 0 ? (
        <Feedback message={t('mobile.live.noBoardsAreAvailable')} />
      ) : null}
      {boards.length ? (
        <View style={styles.boardPicker}>
          <Text style={[styles.heading, {color: colors.ink}]}>
            {t('mobile.live.kanban')}
          </Text>
          <View style={styles.boards}>
            {boards.map((item) => {
              const slug = plainText(item.slug);
              return slug ? (
                <Button
                  key={slug}
                  label={plainText(item.name) || slug}
                  selected={slug === board}
                  onPress={() => chooseBoard(slug)}
                />
              ) : null;
            })}
          </View>
          {typeof selectedBoard?.total === 'number' ? (
            <Text style={{color: colors.sub}}>
              {t('mobile.live.taskCount', {count: selectedBoard.total})}
              {typeof counts.running === 'number'
                ? t('mobile.live.running', {
                    v1: formatNumber(counts.running),
                    count: counts.running,
                  })
                : ''}
            </Text>
          ) : null}
          {board && selectedBoard && onOpenBoard ? (
            <Button
              label={t('mobile.live.manageThisBoard')}
              onPress={() => onOpenBoard(board)}
            />
          ) : null}
          <Text style={{color: colors.sub}} accessibilityLiveRegion="polite">
            {PHASES[stream.state] || PHASES.closed}
          </Text>
          <Button
            label={
              watching
                ? t('mobile.live.stopSubscription')
                : t('mobile.live.connectToBoard')
            }
            variant="accent"
            disabled={!board}
            onPress={() => {
              if (watching) {
                revision.current += 1;
                lastSnapshot.current = null;
                stopHermesLive(viewer);
                setStream(EMPTY);
              } else {
                connect();
              }
            }}
          />
        </View>
      ) : null}
      {connectingError ? <Feedback tone="error" message={connectingError} /> : null}
      {stream.state === 'live' && !stream.events.length ? (
        <Feedback message={t('mobile.live.boardChangesAppearHere')} />
      ) : null}
      {stream.events.map((event) => {
        const kind = plainText(event.kind);
        const timestamp =
          typeof event.createdAt === 'number' ? event.createdAt * 1000 : 0;
        return (
          <View
            key={plainText(event.id)}
            style={[styles.event, {borderColor: colors.line}]}
          >
            <Text style={[styles.heading, {color: colors.ink}]}>
              {EVENT_NAMES[kind] || kind}
            </Text>
            <Text selectable style={{color: colors.sub}}>
              {plainText(event.taskId)}
              {timestamp > 0 ? ` · ${formatDate(timestamp)}` : ''}
            </Text>
          </View>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.xxl,
    gap: spacing.md,
  },
  card: {
    padding: spacing.md,
    borderWidth: 1,
    borderRadius: radius.panel,
    gap: spacing.sm,
  },
  boardPicker: {gap: spacing.sm},
  boards: {flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs},
  heading: {fontSize: 16, fontWeight: '600'},
  event: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    paddingVertical: spacing.sm,
    gap: spacing.xs,
  },
});
