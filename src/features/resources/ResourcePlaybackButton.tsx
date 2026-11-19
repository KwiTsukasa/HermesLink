import {t, useI18n} from '../../shared/i18n';
import {useState} from 'react';
import {View} from 'react-native';

import {getHermesResources} from '../../native/HermesResources';
import {Button} from '../../shared/ui/Button';
import {Feedback} from '../../shared/ui/Feedback';

const mediaType = /\.(avi|flac|m4a|mkv|mov|mp3|mp4|ogg|opus|wav|webm)$/i;

type Props = {
  path: string;
  profile?: string;
  sessionId?: string;
};

export function ResourcePlaybackButton({path, profile, sessionId}: Props) {
  useI18n();
  const [feedback, setFeedback] = useState<{path: string; message: string}>();
  const resources = getHermesResources();
  if (!resources || !mediaType.test(path) || (sessionId && !profile)) return null;

  return (
    <View>
      <Button
        label={t('mobile.resourcePlaybackButton.playOnline')}
        onPress={() => {
          setFeedback(undefined);
          resources
            .playMedia(path, profile ?? null, sessionId ?? null)
            .catch((error) => {
              setFeedback({
                path,
                message:
                  error instanceof Error
                    ? error.message
                    : t('mobile.resourcePlaybackButton.couldNotPlayTheHermesMedia'),
              });
            });
        }}
      />
      {feedback?.path === path ? (
        <Feedback tone="error" message={feedback.message} />
      ) : null}
    </View>
  );
}
