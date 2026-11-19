import {DeviceEventEmitter, TurboModuleRegistry} from 'react-native';
import type {TurboModule} from 'react-native';

import {t} from '../shared/i18n';

type AudioMode = 'relay' | 'direct' | 'unknown';

export type MediaStatus = {
  sttStreaming: boolean | null;
  sttMode: AudioMode;
  ttsMode: AudioMode;
  liveMode: 'chained' | 'gpt-live' | 'unknown';
  gptLiveAvailable: boolean | null;
};

export type MediaEvent =
  | {
      kind: 'transcription';
      state: 'listening' | 'finishing' | 'complete' | 'error' | 'idle';
      partial?: string;
      text?: string;
      message?: string;
    }
  | {
      kind: 'speech';
      state: 'generating' | 'playing' | 'complete' | 'error' | 'idle';
      message?: string;
    };

interface HermesMediaModule extends TurboModule {
  getStatus(): Promise<MediaStatus>;
  pickAndTranscribe(): Promise<{text: string}>;
  startLiveTranscription(): Promise<void>;
  stopLiveTranscription(): Promise<void>;
  speak(text: string): Promise<void>;
  stopSpeaking(): Promise<void>;
  cancelAudio(): Promise<void>;
}

export function getHermesMedia(): HermesMediaModule {
  const module = TurboModuleRegistry.get<HermesMediaModule>('HermesMedia');
  if (!module) throw new Error(t('mobile.media.bridgeUnavailable'));
  return module;
}

export function subscribeToMedia(listener: (event: MediaEvent) => void) {
  const transcription = DeviceEventEmitter.addListener(
    'HermesMediaTranscription',
    (event: Omit<Extract<MediaEvent, {kind: 'transcription'}>, 'kind'>) =>
      listener({...event, kind: 'transcription'}),
  );
  const speech = DeviceEventEmitter.addListener(
    'HermesMediaSpeech',
    (event: Omit<Extract<MediaEvent, {kind: 'speech'}>, 'kind'>) =>
      listener({...event, kind: 'speech'}),
  );
  return () => {
    transcription.remove();
    speech.remove();
  };
}
