import {t, useI18n} from '../../shared/i18n';
import {useCallback, useEffect, useRef, useState} from 'react';
import type {ElementRef} from 'react';
import {
  BackHandler,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import {useTheme} from '../../shared/theme/ThemeProvider';
import {radius, spacing, touchTarget} from '../../shared/theme/tokens';
import {Button} from '../../shared/ui/Button';
import {useConfirmationDialog} from '../../shared/ui/ConfirmationDialog';
import {Feedback} from '../../shared/ui/Feedback';
import {Input} from '../../shared/ui/Input';
import {PageHeading} from '../workspace/components/PageHeading';
import {TerminalSurface} from './TerminalSurface';
import type {TerminalKey} from './TerminalSurface';
import {useTerminalSession} from './useTerminalSession';

const keys = (): Array<[string, TerminalKey]> => [
  ['Esc', 'escape'],
  ['Tab', 'tab'],
  ['Ctrl+C', 'interrupt'],
  ['↑', 'up'],
  ['↓', 'down'],
  ['←', 'left'],
  ['→', 'right'],
  [t('mobile.terminal.enter'), 'enter'],
];

const stageLabels = {
  get checking() {
    return t('mobile.terminal.loadingTerminalSessions');
  },
  get idle() {
    return t('mobile.terminal.terminalDisconnected');
  },
  get connecting() {
    return t('settings.gateway.cloudConnecting');
  },
  get restoring() {
    return t('mobile.terminal.restoringTerminalHistory');
  },
  get connected() {
    return t('connectors.connected');
  },
  get detaching() {
    return t('mobile.terminal.detaching');
  },
  get closing() {
    return t('mobile.terminal.endingSession');
  },
};

export function TerminalScreen({onBack}: {onBack: () => void}) {
  useI18n();
  const showConfirmation = useConfirmationDialog();
  const colors = useTheme();
  const session = useTerminalSession();
  const {stage, detach, terminate} = session;
  const tabScroll = useRef<ElementRef<typeof ScrollView>>(null);
  const [launchOptions, setLaunchOptions] = useState(false);
  const [profile, setProfile] = useState('');
  const [cwd, setCwd] = useState('');
  const selected = session.tabs.find((tab) => tab.id === session.selectedId);
  const active = stage === 'connected';
  const attached = active || stage === 'restoring';
  const busy = stage !== 'idle' && !active;
  const detached = stage === 'idle' && selected?.state === 'detached';
  const canLaunch = Boolean(session.size) && !busy;
  const canReadOutput =
    !session.selectedId || session.selectedId === session.transcriptId;

  const back = useCallback(() => {
    if (stage === 'detaching' || stage === 'closing') {
      return;
    }
    if (attached) {
      showConfirmation(
        t('mobile.terminal.detachFromTheTerminal'),
        t('mobile.terminal.theSessionKeepsRunningAndCanBeResumed'),
        [
          {text: t('common.cancel'), style: 'cancel'},
          {
            text: t('mobile.terminal.detachAndGoBack'),
            onPress: () => detach(onBack),
          },
        ],
      );
    } else {
      onBack();
    }
  }, [attached, detach, onBack, showConfirmation, stage]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      back();
      return true;
    });
    return () => subscription.remove();
  }, [back]);

  function endSession() {
    if (!selected) {
      return;
    }
    const name = t('mobile.terminal.terminal', {v1: String(selected.number)});
    showConfirmation(
      t('mobile.terminal.end', {v1: String(name)}),
      t('mobile.terminal.endingThisShellSessionPreventsResumingItLater'),
      [
        {text: t('common.cancel'), style: 'cancel'},
        {
          text: t('mobile.terminal.endSession'),
          style: 'destructive',
          onPress: () => terminate(selected.id),
        },
      ],
    );
  }

  return (
    <View style={[styles.root, {backgroundColor: colors.bg}]}>
      <PageHeading title={t('rightSidebar.terminal')} onBack={back} />
      <View style={[styles.tabRow, {borderColor: colors.line}]}>
        <ScrollView
          ref={tabScroll}
          horizontal
          style={styles.tabs}
          contentContainerStyle={styles.tabContent}
          onContentSizeChange={() =>
            tabScroll.current?.scrollToEnd({animated: false})
          }
          keyboardShouldPersistTaps="always"
        >
          {session.tabs.map((tab) => {
            const current = tab.id === session.selectedId;
            return (
              <Pressable
                key={tab.id}
                accessibilityRole="tab"
                accessibilityState={{selected: current, disabled: busy}}
                disabled={busy}
                onPress={() => {
                  if (!current && active) {
                    session.reconnect(tab.id);
                  } else {
                    session.setSelectedId(tab.id);
                  }
                }}
                style={[
                  styles.tab,
                  {backgroundColor: current ? colors.wash : colors.panel},
                ]}
              >
                <Text
                  numberOfLines={1}
                  style={[
                    styles.tabLabel,
                    {color: current ? colors.accent : colors.ink},
                  ]}
                >
                  {t('mobile.terminal.terminal', {v1: tab.number})}
                  {tab.profile ? ` · ${tab.profile}` : ''}
                </Text>
              </Pressable>
            );
          })}
        </ScrollView>
        <Button
          label={t('rightSidebar.terminalNew')}
          disabled={!canLaunch}
          onPress={() => setLaunchOptions(true)}
        />
      </View>

      <View style={styles.statusRow}>
        <Text
          accessibilityLiveRegion="polite"
          style={[styles.status, {color: active ? colors.accent : colors.sub}]}
        >
          {detached
            ? t('mobile.terminal.detachedReadyToResume')
            : stageLabels[stage]}
        </Text>
        {attached ? (
          <Button label={t('mobile.terminal.detach')} onPress={() => detach()} />
        ) : null}
        {detached ? (
          <Button
            label={t('mobile.terminal.resume')}
            variant="accent"
            disabled={!session.size}
            onPress={() => session.reconnect(selected.id)}
          />
        ) : null}
        {active || detached ? (
          <Button label={t('mobile.terminal.endSession')} onPress={endSession} />
        ) : null}
        {stage === 'idle' && !selected && session.notice?.error ? (
          <Button
            label={t('mobile.terminal.refreshSessions')}
            onPress={session.refresh}
          />
        ) : null}
      </View>

      {session.notice ? (
        <Text
          accessibilityRole={session.notice.error ? 'alert' : undefined}
          accessibilityLiveRegion="polite"
          style={[
            styles.notice,
            {color: session.notice.error ? colors.danger : colors.sub},
          ]}
        >
          {session.notice.message}
        </Text>
      ) : null}

      <View style={styles.console}>
        <TerminalSurface
          key={session.rendererKey}
          style={styles.surface}
          ref={session.surface}
          onMessage={session.receive}
        />
        {session.rendererFailed ? (
          <View style={[styles.surfaceOverlay, {backgroundColor: colors.panel}]}>
            <Feedback
              tone="error"
              message={t('mobile.terminal.couldNotLoadTheTerminal')}
              onRetry={session.rebuildRenderer}
            />
          </View>
        ) : !canReadOutput ? (
          <View style={styles.surfaceOverlay}>
            <Text style={styles.consoleMessage}>
              {t('mobile.terminal.resumeTheSessionToSeeTerminalOutput')}
            </Text>
          </View>
        ) : null}
      </View>

      <View style={[styles.toolbar, {borderColor: colors.line}]}>
        <ScrollView
          horizontal
          keyboardShouldPersistTaps="always"
          style={styles.keys}
        >
          <Button
            label={t('mobile.terminal.keyboard')}
            disabled={!active}
            onPress={() => session.surface.current?.focus()}
          />
          {keys().map(([label, key]) => (
            <Button
              key={key}
              label={label}
              disabled={!active}
              onPress={() => session.surface.current?.key(key)}
            />
          ))}
          <Button
            label={t('common.copy')}
            disabled={!session.size || !canReadOutput || busy}
            onPress={() => session.surface.current?.copy()}
          />
          <Button
            label={t('mobile.terminal.clearScreen')}
            disabled={!session.size || !canReadOutput || busy}
            onPress={() => session.surface.current?.clear()}
          />
        </ScrollView>
        <Button
          label={t('contextMenu.edit.paste')}
          disabled={!active}
          onPress={() => session.surface.current?.paste()}
        />
      </View>

      <Modal
        transparent
        animationType="fade"
        visible={launchOptions}
        onRequestClose={() => setLaunchOptions(false)}
      >
        <View style={styles.scrim}>
          <ScrollView
            keyboardShouldPersistTaps="handled"
            contentContainerStyle={styles.optionLayout}
          >
            <View style={[styles.dialog, {backgroundColor: colors.panel}]}>
              <Text
                accessibilityRole="header"
                style={[styles.dialogTitle, {color: colors.ink}]}
              >
                {t('rightSidebar.terminalNew')}
              </Text>
              <Text style={{color: colors.sub}}>
                {active
                  ? t('mobile.terminal.theCurrentTerminalWillDetachANewTerminal')
                  : t('mobile.terminal.leaveThisBlankToUseTheServerDefaults')}
              </Text>
              <Input
                accessibilityLabel={t('sidebar.filter.profile')}
                placeholder={t('mobile.terminal.profileOptional')}
                value={profile}
                onChangeText={setProfile}
                maxLength={4096}
              />
              <Input
                accessibilityLabel={t('mobile.terminal.workingDirectory')}
                placeholder={t('mobile.terminal.workingDirectoryOptional')}
                value={cwd}
                onChangeText={setCwd}
                maxLength={4096}
              />
              <View style={styles.dialogActions}>
                <Button
                  label={t('common.cancel')}
                  onPress={() => setLaunchOptions(false)}
                />
                <Button
                  label={t('common.connect')}
                  variant="accent"
                  onPress={() => {
                    setLaunchOptions(false);
                    session.open(profile.trim() || null, cwd || null);
                    setProfile('');
                    setCwd('');
                  }}
                />
              </View>
            </View>
          </ScrollView>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  tabRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: spacing.sm,
  },
  tabs: {flex: 1},
  tabContent: {alignItems: 'center', gap: spacing.xs},
  tab: {
    minHeight: touchTarget,
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
    borderRadius: radius.control,
    maxWidth: 192,
  },
  tabLabel: {fontSize: 13, fontWeight: '600'},
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: touchTarget,
    paddingHorizontal: spacing.sm,
  },
  status: {flex: 1, fontSize: 13, paddingStart: spacing.sm},
  notice: {fontSize: 13, paddingHorizontal: spacing.lg, paddingBottom: spacing.sm},
  console: {flex: 1, backgroundColor: '#17151b'},
  surface: {flex: 1},
  surfaceOverlay: {
    ...StyleSheet.absoluteFill,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#17151b',
  },
  consoleMessage: {color: '#bdb5c5'},
  toolbar: {
    flexDirection: 'row',
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: spacing.xs,
  },
  keys: {flex: 1},
  scrim: {flex: 1, backgroundColor: '#0008'},
  optionLayout: {flexGrow: 1, justifyContent: 'center', padding: spacing.xl},
  dialog: {gap: spacing.md, borderRadius: radius.panel, padding: spacing.lg},
  dialogTitle: {fontSize: 21, fontWeight: '600'},
  dialogActions: {flexDirection: 'row', justifyContent: 'flex-end'},
});
