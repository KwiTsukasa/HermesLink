import {useCallback, useEffect, useRef, useState} from 'react';
import {View} from 'react-native';

import {getHermesMedia, subscribeToMedia} from '../../native/HermesMedia';
import type {MediaEvent, MediaStatus} from '../../native/HermesMedia';
import {formatDate, useI18n} from '../../shared/i18n';
import {Button} from '../../shared/ui/Button';
import {Feedback} from '../../shared/ui/Feedback';
import {Input} from '../../shared/ui/Input';
import {BusinessPage} from '../workspace/components/BusinessPage';
import {
  Card,
  Fact,
  Heading,
  Notice,
  detailStyles as styles,
} from '../workspace/components/DetailContent';
import {useOfficialTools} from './useOfficialTools';

type AudioAction = 'file' | 'microphone' | 'speech';
type AudioOperation = {action: AudioAction; cancelling: boolean};
type AudioPhase =
  | 'opening'
  | 'starting'
  | 'listening'
  | 'finishing'
  | 'generating'
  | 'playing';

const phases: Record<AudioPhase, string> = {
  opening: 'mobile.media.openOrTranscribe',
  starting: 'mobile.media.startingMicrophone',
  listening: 'composer.listening',
  finishing: 'composer.transcribing',
  generating: 'mobile.media.generatingSpeech',
  playing: 'mobile.media.playingSpeech',
};

const audioModes = {
  relay: 'mobile.media.modeRelay',
  direct: 'mobile.media.modeDirect',
  unknown: 'mobile.media.unknown',
};

export function useMediaAudio() {
  const {t} = useI18n();
  const [status, setStatus] = useState<MediaStatus>();
  const [statusError, setStatusError] = useState('');
  const [loadingStatus, setLoadingStatus] = useState(true);
  const [action, setAction] = useState<AudioAction>();
  const [phase, setPhase] = useState<AudioPhase>();
  const [audioError, setAudioError] = useState('');
  const [transcript, setTranscript] = useState('');
  const [partial, setPartial] = useState('');
  const operation = useRef<AudioOperation | null>(null);
  const statusWork = useRef({revision: 0, pending: false});

  const refresh = useCallback(async () => {
    const work = statusWork.current;
    const revision = ++work.revision;
    work.pending = true;
    setLoadingStatus(true);
    setStatusError('');
    try {
      const value = await getHermesMedia().getStatus();
      if (revision === work.revision) setStatus(value);
    } catch (error) {
      if (revision === work.revision) {
        setStatus(undefined);
        setStatusError(error instanceof Error ? error.message : String(error));
      }
    } finally {
      if (revision === work.revision) {
        work.pending = false;
        setLoadingStatus(false);
      }
    }
  }, []);

  const finish = useCallback((current: AudioOperation) => {
    if (operation.current !== current) return;
    operation.current = null;
    setAction(undefined);
    setPhase(undefined);
    setPartial('');
  }, []);

  useEffect(() => {
    const work = statusWork.current;
    const receive = (event: MediaEvent) => {
      const current = operation.current;
      if (
        !current ||
        current.action !== (event.kind === 'speech' ? 'speech' : 'microphone')
      )
        return;
      switch (event.state) {
        case 'listening':
        case 'finishing':
          if (event.kind === 'transcription' && event.partial !== undefined)
            setPartial(event.partial);
          setPhase(event.state);
          break;
        case 'generating':
        case 'playing':
          setPhase(event.state);
          break;
        case 'complete':
          if (event.kind === 'transcription') setTranscript(event.text ?? '');
          finish(current);
          break;
        case 'error':
          setAudioError(event.message || t('common.error'));
          finish(current);
          break;
        case 'idle':
          finish(current);
          break;
      }
    };
    const unsubscribe = subscribeToMedia(receive);
    return () => {
      unsubscribe();
      const needsCancellation = operation.current || work.pending;
      operation.current = null;
      work.revision++;
      work.pending = false;
      if (needsCancellation) {
        try {
          getHermesMedia()
            .cancelAudio()
            .catch(() => {});
        } catch {
          // This app build may not include the Android audio module.
        }
      }
    };
  }, [finish, t]);

  function report(error: unknown) {
    setAudioError(error instanceof Error ? error.message : String(error));
  }

  async function start(kind: AudioAction, text = '') {
    if (operation.current || !status) return;
    if (kind === 'microphone' && status.sttStreaming !== true) return;
    const current = {action: kind, cancelling: false};
    operation.current = current;
    setAction(kind);
    setAudioError('');
    if (kind !== 'speech') setTranscript('');
    setPhase(
      kind === 'file'
        ? 'opening'
        : kind === 'microphone'
          ? 'starting'
          : 'generating',
    );
    try {
      const media = getHermesMedia();
      if (kind === 'file') {
        const result = await media.pickAndTranscribe();
        if (operation.current === current) {
          setTranscript(result.text);
          finish(current);
        }
      } else if (kind === 'microphone') {
        await media.startLiveTranscription();
      } else {
        await media.speak(text);
      }
    } catch (error) {
      if (operation.current === current && !current.cancelling) {
        report(error);
        finish(current);
      }
    }
  }

  async function stop() {
    const current = operation.current;
    if (!current || current.cancelling) return;
    const cancelling = current.action !== 'microphone' || phase !== 'listening';
    try {
      const media = getHermesMedia();
      if (current.action === 'speech') await media.stopSpeaking();
      else if (cancelling) {
        // The document picker and pending permission reject their start promises first.
        current.cancelling = true;
        await media.cancelAudio();
        finish(current);
      } else await media.stopLiveTranscription();
    } catch (error) {
      current.cancelling = false;
      if (operation.current === current) report(error);
    }
  }

  return {
    status,
    statusError,
    loadingStatus,
    action,
    phase,
    audioError,
    transcript,
    partial,
    refresh,
    start,
    stop,
  };
}

export function MediaToolsScreen({onBack}: {onBack: () => void}) {
  const {t, locale} = useI18n();
  const [speech, setSpeech] = useState('');
  const audio = useMediaAudio();
  const official = useOfficialTools();
  const refreshAudio = audio.refresh;
  const refreshOfficial = official.refresh;

  useEffect(() => {
    refreshAudio();
  }, [refreshAudio]);
  useEffect(() => {
    refreshOfficial();
  }, [locale, refreshOfficial]);

  return (
    <BusinessPage
      title={t('mobile.media.title')}
      onBack={onBack}
      onRefresh={() => {
        if (!audio.action) refreshAudio();
        refreshOfficial();
      }}
      loading={audio.loadingStatus || official.loading}
    >
      <Heading>{t('settings.sections.voice')}</Heading>
      <Notice>{t('mobile.media.serverAudioProcessing')}</Notice>
      {audio.loadingStatus ? (
        <Feedback tone="loading" message={t('common.loading')} />
      ) : null}
      {audio.statusError ? (
        <Feedback tone="error" message={audio.statusError} onRetry={refreshAudio} />
      ) : null}
      <Card>
        <Heading>{t('settings.subpages.voiceTranscription')}</Heading>
        {audio.status ? (
          <Fact
            label={t('mobile.media.processingMode')}
            value={t(audioModes[audio.status.sttMode])}
          />
        ) : null}
        <View style={styles.actions}>
          <Button
            label={t('mobile.media.chooseAudio')}
            disabled={!audio.status || !!audio.action}
            onPress={() => audio.start('file')}
          />
          <Button
            label={t('mobile.media.startMicrophone')}
            disabled={!audio.status?.sttStreaming || !!audio.action}
            onPress={() => audio.start('microphone')}
          />
          {audio.action === 'file' || audio.action === 'microphone' ? (
            <Button
              label={t(
                audio.phase === 'listening'
                  ? 'composer.stopDictation'
                  : 'common.cancel',
              )}
              onPress={() => audio.stop()}
            />
          ) : null}
        </View>
        {audio.status && audio.status.sttStreaming !== true ? (
          <Notice>
            {t(
              audio.status.sttStreaming === false
                ? 'mobile.media.streamingUnavailable'
                : 'mobile.media.streamingUnknown',
            )}
          </Notice>
        ) : null}
        {audio.phase && audio.action !== 'speech' ? (
          <Notice>{t(phases[audio.phase])}</Notice>
        ) : null}
        <Fact label={t('mobile.media.partialTranscript')} value={audio.partial} />
        <Fact label={t('mobile.media.transcript')} value={audio.transcript} />
      </Card>
      <Card>
        <Heading>{t('settings.subpages.voiceSpeech')}</Heading>
        {audio.status ? (
          <Fact
            label={t('mobile.media.processingMode')}
            value={t(audioModes[audio.status.ttsMode])}
          />
        ) : null}
        <Input
          accessibilityLabel={t('mobile.media.speechText')}
          placeholder={t('mobile.media.speechText')}
          value={speech}
          onChangeText={setSpeech}
          multiline
          maxLength={20000}
        />
        <View style={styles.actions}>
          <Button
            label={t('assistant.thread.readAloud')}
            disabled={!audio.status || !!audio.action || !speech.trim()}
            onPress={() => audio.start('speech', speech)}
          />
          {audio.action === 'speech' ? (
            <Button
              label={t('assistant.thread.stopReading')}
              onPress={() => audio.stop()}
            />
          ) : null}
        </View>
        {audio.phase && audio.action === 'speech' ? (
          <Notice>{t(phases[audio.phase])}</Notice>
        ) : null}
      </Card>
      {audio.audioError ? (
        <Feedback tone="error" message={audio.audioError} />
      ) : null}
      <Heading>{t('mobile.media.officialPages')}</Heading>
      {official.destinations.map((destination) => (
        <Card key={destination.id}>
          <Heading>{destination.title}</Heading>
          <Notice>{destination.guidance}</Notice>
          <Button
            label={t('mobile.media.openPage', {page: destination.title})}
            onPress={() => official.openPage(destination.id)}
          />
        </Card>
      ))}
      {official.pagesError ? (
        <Feedback
          tone="error"
          message={official.pagesError}
          onRetry={refreshOfficial}
        />
      ) : null}
      <Heading>{t('settings.providers.connectAccount')}</Heading>
      {official.providers.map((provider) => (
        <Card key={provider.id}>
          <Heading>{provider.label}</Heading>
          <Notice>
            {t(
              provider.connected === null
                ? 'mobile.media.unknown'
                : provider.connected
                  ? 'settings.providers.connected'
                  : 'mobile.media.notConnected',
            )}
          </Notice>
          {provider.deviceFlowAvailable && provider.connected !== true ? (
            <Button
              label={t('mobile.media.authorizeProvider', {provider: provider.label})}
              disabled={official.busy || !!official.verification}
              onPress={() => official.begin(provider)}
            />
          ) : null}
          {official.verification?.provider.id === provider.id ? (
            <ProviderVerificationPanel official={official} />
          ) : null}
        </Card>
      ))}
      {official.verification &&
      !official.providers.some(
        (provider) => provider.id === official.verification?.provider.id,
      ) ? (
        <Card>
          <Heading>{official.verification.provider.label}</Heading>
          <ProviderVerificationPanel official={official} />
        </Card>
      ) : null}
      {official.loginState ? (
        <Notice>{t(`mobile.media.authorization.${official.loginState}`)}</Notice>
      ) : null}
      {official.loginError ? (
        <Feedback tone="error" message={official.loginError} />
      ) : null}
      {official.providersError ? (
        <Feedback
          tone="error"
          message={official.providersError}
          onRetry={refreshOfficial}
        />
      ) : null}
      {!official.loading &&
      !official.providersError &&
      official.providers.length === 0 ? (
        <Notice>{t('mobile.media.noProviders')}</Notice>
      ) : null}
    </BusinessPage>
  );
}

function ProviderVerificationPanel({
  official,
}: {
  official: ReturnType<typeof useOfficialTools>;
}) {
  const {t} = useI18n();
  const verification = official.verification?.code;
  if (!verification) return null;
  return (
    <>
      <Notice>{t('mobile.media.verificationInstructions')}</Notice>
      <Fact
        label={t('mobile.media.verificationCode')}
        value={verification.userCode}
      />
      <Fact
        label={t('mobile.media.expiresAt')}
        value={formatDate(verification.expiresAt)}
      />
      <View style={styles.actions}>
        <Button
          label={t('mobile.media.verifyInBrowser')}
          disabled={official.busy}
          onPress={() => official.verify()}
        />
        <Button
          label={t('mobile.media.checkAuthorization')}
          disabled={official.busy}
          onPress={() => official.check()}
        />
        <Button
          label={t('common.cancel')}
          disabled={official.busy}
          onPress={() => official.cancel()}
        />
      </View>
    </>
  );
}
