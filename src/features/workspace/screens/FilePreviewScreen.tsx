import {t, useI18n} from '../../../shared/i18n';
import {useCallback, useEffect, useRef, useState} from 'react';
import {ScrollView, StyleSheet, Text} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {radius, spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Feedback} from '../../../shared/ui/Feedback';
import {ResourcePlaybackButton} from '../../resources/ResourcePlaybackButton';
import {PageHeading} from '../components/PageHeading';
import {useWorkspaceQuery} from '../hooks/useWorkspaceQuery';
import {
  fetchFileDownload,
  getFilePreview,
  type ManagedFile,
} from '../services/managedFilesApi';
import {saveFile} from '../services/workspaceApi';
import type {Entry} from '../types';
import {errorMessage} from '../utils/data';

const MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;

type SaveStatus = {kind: 'saving' | 'saved'} | {kind: 'error'; message: string};

type FilePreviewScreenProps = {
  file: ManagedFile;
  download?: Entry;
  onBack: () => void;
  onEdit?: (text: string) => void;
  onRemove?: () => void;
};

export function FilePreviewScreen({
  file,
  download,
  onBack,
  onEdit,
  onRemove,
}: FilePreviewScreenProps) {
  useI18n();
  const colors = useTheme();
  const request = useCallback(() => getFilePreview(file.path), [file.path]);
  const {state, refresh} = useWorkspaceQuery(request);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>();
  const saveTask = useRef<symbol | undefined>(undefined);
  const tooLarge = file.size !== undefined && file.size > MAX_DOWNLOAD_BYTES;
  const unavailable = download
    ? download.unavailable
    : t('mobile.filePreview.thisGatewayDoesNotSupportSavingFiles');
  const saveDisabled = tooLarge || !!unavailable;
  const saving = saveStatus?.kind === 'saving';

  useEffect(() => {
    return () => {
      saveTask.current = undefined;
    };
  }, []);

  async function save() {
    if (!download || saveDisabled || saveTask.current) {
      return;
    }
    const task = Symbol();
    saveTask.current = task;
    setSaveStatus({kind: 'saving'});
    try {
      const handle = await fetchFileDownload(download, file.path);
      if (saveTask.current !== task) {
        return;
      }
      const saved = await saveFile(handle);
      if (saveTask.current === task) {
        setSaveStatus(saved ? {kind: 'saved'} : undefined);
      }
    } catch (error) {
      if (saveTask.current === task) {
        setSaveStatus({kind: 'error', message: errorMessage(error)});
      }
    } finally {
      if (saveTask.current === task) {
        saveTask.current = undefined;
      }
    }
  }

  return (
    <>
      <PageHeading title={t('mobile.artifacts.filePreview')} onBack={onBack} />
      <ScrollView contentContainerStyle={styles.content}>
        <Text selectable style={[styles.name, {color: colors.ink}]}>
          {file.name}
        </Text>
        <ResourcePlaybackButton path={file.path} />
        <Button
          label={
            saving
              ? t('mobile.filePreview.preparingDownload')
              : t('mobile.artifacts.saveToPhone')
          }
          variant="accent"
          disabled={saveDisabled || saving}
          busy={saving}
          onPress={save}
        />
        {onEdit &&
        state.status === 'success' &&
        !state.data.binary &&
        !state.data.truncated ? (
          <Button
            label={t('mobile.filePreview.editText')}
            disabled={saving}
            onPress={() => onEdit(state.data.text)}
          />
        ) : null}
        {onRemove ? (
          <Button
            label={t('mobile.filePreview.deleteFile')}
            disabled={saving}
            onPress={onRemove}
          />
        ) : null}
        {tooLarge ? (
          <Feedback
            message={t('mobile.filePreview.theMobileLimitIs32MibPerSaved')}
          />
        ) : null}
        {unavailable ? <Feedback message={unavailable} /> : null}
        {saveStatus?.kind === 'saved' ? (
          <Feedback message={t('mobile.filePreview.savedToTheSelectedLocation')} />
        ) : null}
        {saveStatus?.kind === 'error' ? (
          <Feedback tone="error" message={saveStatus.message} />
        ) : null}
        {state.status === 'loading' ? (
          <Feedback
            tone="loading"
            message={t('mobile.filePreview.loadingFilePreview')}
          />
        ) : null}
        {state.status === 'error' ? (
          <Feedback tone="error" message={state.error} onRetry={refresh} />
        ) : null}
        {state.status === 'success' &&
          (state.data.binary ? (
            <Feedback
              message={
                saveDisabled
                  ? t('mobile.filePreview.thisFileCannotBePreviewedAsText')
                  : t('mobile.filePreview.saveThisFileAndOpenItInAnother')
              }
            />
          ) : state.data.text.length === 0 ? (
            <Feedback message={t('mobile.filePreview.thisFileIsEmpty')} />
          ) : (
            <>
              {state.data.truncated ? (
                <Feedback
                  message={t('mobile.filePreview.showingOnlyTheBeginningOfTheFile')}
                />
              ) : null}
              <Text
                selectable
                style={[
                  styles.preview,
                  {
                    color: colors.ink,
                    backgroundColor: colors.panel,
                    borderColor: colors.line,
                  },
                ]}
              >
                {state.data.text}
              </Text>
            </>
          ))}
      </ScrollView>
    </>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.xxl,
    gap: spacing.md,
  },
  name: {fontSize: 17, fontWeight: '600'},
  preview: {
    borderRadius: radius.panel,
    borderWidth: StyleSheet.hairlineWidth,
    padding: spacing.md,
    fontFamily: 'monospace',
    fontSize: 13,
    lineHeight: 20,
  },
});
