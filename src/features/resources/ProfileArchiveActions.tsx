import {t, useI18n} from '../../shared/i18n';
import {useState} from 'react';
import {StyleSheet, View} from 'react-native';

import {getHermesResources} from '../../native/HermesResources';
import {spacing} from '../../shared/theme/tokens';
import {Button} from '../../shared/ui/Button';
import {Feedback} from '../../shared/ui/Feedback';
import {Input} from '../../shared/ui/Input';

type Props = {
  profile?: string;
  onImported?: () => void;
};

export function ProfileArchiveActions({profile, onImported}: Props) {
  useI18n();
  const [showImport, setShowImport] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<{message: string; error: boolean}>();
  const native = getHermesResources();
  if (!native) return null;

  async function transfer() {
    if (!native || busy) return;
    const target = profile ?? name.trim();
    if (!target) return;
    setBusy(true);
    setFeedback(undefined);
    try {
      const done = profile
        ? await native.exportProfile(profile)
        : await native.importProfile(target);
      if (!done) return;
      if (!profile) {
        setName('');
        setShowImport(false);
        onImported?.();
      }
      setFeedback({
        message: profile
          ? t('mobile.profileArchiveActions.profileArchiveSaved')
          : t('mobile.profileArchiveActions.importedAsANewProfile'),
        error: false,
      });
    } catch (error) {
      setFeedback({
        message:
          error instanceof Error
            ? error.message
            : t('mobile.profileArchiveActions.profileArchiveTransferFailed'),
        error: true,
      });
    } finally {
      setBusy(false);
    }
  }

  const validName =
    /^[a-z0-9][a-z0-9_-]{0,63}$/.test(name.trim()) && name.trim() !== 'default';
  return (
    <View style={styles.actions}>
      {profile ? (
        <Button
          label={
            busy
              ? t('mobile.profileArchiveActions.preparingProfileArchive')
              : t('mobile.profileArchiveActions.exportProfileArchive')
          }
          busy={busy}
          disabled={busy}
          onPress={transfer}
        />
      ) : showImport ? (
        <>
          <Input
            accessibilityLabel={t('mobile.profileArchiveActions.newProfileName')}
            placeholder={t(
              'mobile.profileArchiveActions.newProfileNameSuchAsResearch',
            )}
            value={name}
            onChangeText={setName}
            maxLength={64}
            editable={!busy}
          />
          <Button
            label={
              busy
                ? t('mobile.profileArchiveActions.importingProfileArchive')
                : t('mobile.profileArchiveActions.selectATarGzProfileArchive')
            }
            busy={busy}
            disabled={busy || !validName}
            onPress={transfer}
          />
        </>
      ) : (
        <Button
          label={t('mobile.profileArchiveActions.importProfileArchive')}
          onPress={() => setShowImport(true)}
        />
      )}
      {feedback ? (
        <Feedback
          tone={feedback.error ? 'error' : 'neutral'}
          message={feedback.message}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({actions: {gap: spacing.sm}});
