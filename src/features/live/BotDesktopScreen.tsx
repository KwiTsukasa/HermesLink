import {t, useI18n} from '../../shared/i18n';
import {useState} from 'react';
import {requireNativeComponent, StyleSheet, Text, View} from 'react-native';
import type {NativeSyntheticEvent, ViewProps} from 'react-native';

import {useTheme} from '../../shared/theme/ThemeProvider';
import {spacing} from '../../shared/theme/tokens';
import {Button} from '../../shared/ui/Button';
import {Feedback} from '../../shared/ui/Feedback';
import {PageHeading} from '../workspace/components/PageHeading';

const Surface = requireNativeComponent<
  ViewProps & {
    profile: string;
    onDisplayState: (event: NativeSyntheticEvent<{state: string}>) => void;
  }
>('HermesDisplaySurface');

const FRAME_STATES: Record<string, string> = {
  get connecting() {
    return t('mobile.botDesktop.connectingToTheBotDesktop');
  },
  get waiting() {
    return t('mobile.botDesktop.connectedWaitingForTheFirstFrame');
  },
  get paused() {
    return t('mobile.botDesktop.viewingPaused');
  },
  get unavailable() {
    return t('mobile.botDesktop.theDesktopImageIsUnavailableCheckThatThe');
  },
  get signed_out() {
    return t('mobile.botDesktop.yourLoginHasExpiredSignInToHermes');
  },
  get timeout() {
    return t('mobile.botDesktop.theBotDesktopDidNotSendAFrame');
  },
  get invalid_stream() {
    return t('mobile.botDesktop.theServerDisplayProtocolOrResolutionExceedsThe');
  },
  get failed() {
    return t('mobile.botDesktop.couldNotConnectToTheBotDesktop');
  },
  get disconnected() {
    return t('mobile.botDesktop.theDisplayConnectionClosed');
  },
};

type BotDesktopScreenProps = {
  profile?: string;
  onBack: () => void;
};

export function BotDesktopScreen({profile, onBack}: BotDesktopScreenProps) {
  useI18n();
  const colors = useTheme();
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState('connecting');
  const opening = state === 'connecting' || state === 'waiting';
  return (
    <View style={styles.page}>
      <PageHeading title={t('mobile.botDesktop.botDesktop')} onBack={onBack} />
      <Text style={[styles.caption, {color: colors.sub}]}>
        {profile
          ? t('mobile.botDesktop.viewOnly', {v1: String(profile)})
          : t('mobile.botDesktop.viewOnly2')}
      </Text>
      <Surface
        key={attempt}
        style={styles.surface}
        profile={profile || ''}
        onDisplayState={(event) => setState(event.nativeEvent.state)}
      />
      {state === 'viewing' ? null : (
        <View style={styles.feedback}>
          <Feedback
            message={FRAME_STATES[state] || FRAME_STATES.failed}
            tone={opening ? 'loading' : state === 'paused' ? 'neutral' : 'error'}
          />
          {!opening ? (
            <Button
              label={t('connectors.grant')}
              onPress={() => {
                setState('connecting');
                setAttempt((previous) => previous + 1);
              }}
            />
          ) : null}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  page: {flex: 1, paddingHorizontal: spacing.lg, paddingBottom: spacing.md},
  caption: {paddingVertical: spacing.sm},
  surface: {flex: 1, minHeight: 180, backgroundColor: '#000'},
  feedback: {alignItems: 'center'},
});
