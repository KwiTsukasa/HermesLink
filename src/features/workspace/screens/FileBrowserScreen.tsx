import {t, useI18n} from '../../../shared/i18n';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {ComponentRef} from 'react';
import {
  BackHandler,
  FlatList,
  Keyboard,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing, touchTarget} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Feedback} from '../../../shared/ui/Feedback';
import {ListRow} from '../../../shared/ui/ListRow';
import {detailStyles} from '../components/DetailContent';
import {PageHeading} from '../components/PageHeading';
import {SearchField} from '../components/SearchField';
import {useWorkspaceQuery} from '../hooks/useWorkspaceQuery';
import {
  childFilePath,
  getFilePreview,
  listManagedFiles,
  textDataUrl,
  type ManagedFile,
} from '../services/managedFilesApi';
import type {BusinessForm, RouteIndex} from '../types';
import {BusinessFormScreen} from './BusinessFormScreen';
import {FilePreviewScreen} from './FilePreviewScreen';

type Folder = {name: string; path: string | null};

const root: Folder = {
  get name() {
    return t('mobile.fileBrowser.rootFolder');
  },
  path: null,
};
const sizeUnits = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];

function formatSize(bytes: number): string {
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < sizeUnits.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${Number(value.toFixed(unit === 0 ? 0 : 1))} ${sizeUnits[unit]}`;
}

type FileBrowserScreenProps = {
  byRoute: RouteIndex;
  onBack: () => void;
  initial?: Folder;
};

export function FileBrowserScreen({
  byRoute,
  onBack,
  initial = root,
}: FileBrowserScreenProps) {
  useI18n();
  const [folders, setFolders] = useState([initial]);
  const [file, setFile] = useState<ManagedFile>();
  const [form, setForm] = useState<BusinessForm>();
  const [search, setSearch] = useState('');
  const current = folders[folders.length - 1];
  const request = useCallback(() => listManagedFiles(current.path), [current.path]);
  const {state, refresh} = useWorkspaceQuery(request);
  const loading = state.status === 'loading';
  const listing = state.status === 'success' || loading ? state.data : undefined;
  const entries = listing?.entries;
  const download = byRoute.get('GET /api/files/download');
  const mkdir = byRoute.get('POST /api/files/mkdir');
  const write = byRoute.get('POST /api/files/upload');
  const upload = byRoute.get('POST /api/files/upload-stream');
  const remove = byRoute.get('DELETE /api/files');
  const query = search.trim().toLowerCase();
  const matches = useMemo(() => {
    const items = entries ?? [];
    return query
      ? items.filter(({name}) => name.toLowerCase().includes(query))
      : items;
  }, [entries, query]);

  const selectFolder = useCallback((trail: Folder[]) => {
    Keyboard.dismiss();
    setSearch('');
    setFolders(trail);
  }, []);

  const back = useCallback(() => {
    if (form) {
      setForm(undefined);
    } else if (file) {
      setFile(undefined);
    } else if (folders.length > 1) {
      selectFolder(folders.slice(0, -1));
    } else {
      onBack();
    }
    return true;
  }, [file, folders, form, onBack, selectFolder]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', back);
    return () => subscription.remove();
  }, [back]);

  function folderMutation(kind: 'mkdir' | 'text' | 'upload') {
    const entry = {mkdir, text: write, upload}[kind];
    const path = listing?.path;
    if (!entry || entry.unavailable || !path) return;
    const isUpload = kind === 'upload';
    const isText = kind === 'text';
    const title = {
      mkdir: t('rightSidebar.remotePickerNewFolder'),
      text: t('mobile.fileBrowser.newTextFile'),
      upload: t('mobile.fileBrowser.uploadFile'),
    }[kind];
    setForm({
      title,
      action: title,
      detail: path,
      entry,
      pickFile: isUpload,
      fields: [
        {
          key: 'name',
          label:
            kind === 'mkdir'
              ? t('rightSidebar.remotePickerFolderName')
              : t('mobile.fileBrowser.fileName'),
          required: !isUpload,
          placeholder: isUpload
            ? t('mobile.fileBrowser.leaveBlankToUseTheOriginalFileName')
            : undefined,
        },
        ...(isText
          ? [{key: 'text', label: t('mobile.fileBrowser.content'), multiline: true}]
          : []),
      ],
      build: ({name, text, attachmentName}) => {
        const target = childFilePath(path, name.trim() || attachmentName || '');
        if (kind === 'mkdir') return {body: {path: target}};
        if (isUpload) return {body: {path: target, overwrite: false}};
        return {body: {path: target, data_url: textDataUrl(text), overwrite: false}};
      },
    });
  }

  function editFile(item: ManagedFile, original: string) {
    if (!write || write.unavailable) return;
    setForm({
      title: t('mobile.fileBrowser.editTextFile'),
      action: t('mobile.fileBrowser.saveFile'),
      detail: item.path,
      entry: write,
      fields: [
        {
          key: 'text',
          label: t('mobile.fileBrowser.content'),
          multiline: true,
          initial: original,
        },
      ],
      beforeSubmit: async () => {
        const currentFile = await getFilePreview(item.path);
        if (
          currentFile.binary ||
          currentFile.truncated ||
          currentFile.text !== original
        ) {
          throw new Error(t('mobile.fileBrowser.theFileChangedOnTheServerGoBack'));
        }
      },
      build: ({text}) => ({
        body: {path: item.path, data_url: textDataUrl(text), overwrite: true},
      }),
    });
  }

  function deleteFile(path: string, directory: boolean) {
    if (!remove || remove.unavailable) return;
    const title = directory
      ? t('mobile.fileBrowser.deleteFolder')
      : t('mobile.fileBrowser.deleteServerFile');
    setForm({
      title,
      action: title,
      entry: remove,
      detail: directory
        ? t('mobile.fileBrowser.recursiveDeletionRemovesEverythingInThisFolder', {
            v1: String(path),
          })
        : path,
      fields: directory
        ? [
            {
              key: 'recursive',
              label: t('mobile.fileBrowser.deletionScope'),
              initial: 'empty',
              choices: [
                {value: 'empty', label: t('mobile.fileBrowser.emptyFolderOnly')},
                {
                  value: 'recursive',
                  label: t('mobile.fileBrowser.includeAllContents'),
                },
              ],
            },
          ]
        : [],
      build: ({recursive}) => ({
        body: {path, recursive: directory && recursive === 'recursive'},
      }),
      onSuccess: () => {
        if (directory) selectFolder(folders.slice(0, -1));
        else setFile(undefined);
      },
    });
  }

  if (form)
    return <BusinessFormScreen form={form} onWrite={refresh} onBack={back} />;
  if (file)
    return (
      <FilePreviewScreen
        file={file}
        download={download}
        onBack={back}
        onEdit={
          write && !write.unavailable ? (text) => editFile(file, text) : undefined
        }
        onRemove={
          remove && !remove.unavailable
            ? () => deleteFile(file.path, false)
            : undefined
        }
      />
    );

  return (
    <>
      <PageHeading
        title={t('artifacts.tabFiles')}
        loading={loading}
        onBack={back}
        onRefresh={refresh}
      />
      <FolderTrail
        folders={folders}
        onSelect={(index) => selectFolder(folders.slice(0, index + 1))}
      />
      <View style={styles.search}>
        <SearchField
          clearable
          placeholder={t('mobile.fileBrowser.searchThisFolder')}
          value={search}
          onChange={setSearch}
        />
      </View>
      {listing && !loading ? (
        <View style={[detailStyles.actions, styles.toolbar]}>
          {upload && !upload.unavailable ? (
            <Button
              variant="accent"
              label={t('sendDiagnostics.upload')}
              onPress={() => folderMutation('upload')}
            />
          ) : null}
          {mkdir && !mkdir.unavailable ? (
            <Button
              label={t('rightSidebar.remotePickerNewFolder')}
              onPress={() => folderMutation('mkdir')}
            />
          ) : null}
          {write && !write.unavailable ? (
            <Button
              label={t('mobile.fileBrowser.newTextFile2')}
              onPress={() => folderMutation('text')}
            />
          ) : null}
          {folders.length > 1 && current.path && remove && !remove.unavailable ? (
            <Button
              label={t('mobile.fileBrowser.deleteThisFolder')}
              onPress={() => deleteFile(current.path!, true)}
            />
          ) : null}
        </View>
      ) : null}
      <FlatList
        key={current.path ?? ''}
        data={matches}
        keyExtractor={(item) => item.path}
        contentContainerStyle={styles.list}
        keyboardShouldPersistTaps="handled"
        refreshing={loading && entries !== undefined}
        onRefresh={refresh}
        ListEmptyComponent={
          state.status === 'error' ? (
            <Feedback tone="error" message={state.error} onRetry={refresh} />
          ) : loading ? (
            <Feedback
              tone="loading"
              message={t('mobile.fileBrowser.loadingFolder')}
            />
          ) : entries?.length && query ? (
            <Feedback message={t('mobile.fileBrowser.noMatchingFilesOrFolders')} />
          ) : (
            <Feedback
              message={t('mobile.fileBrowser.thisFolderHasNoBrowsableFiles')}
            />
          )
        }
        renderItem={({item}) => (
          <ListRow
            title={item.name}
            subtitle={
              item.directory
                ? t('assistant.catalogInstall.subdirLabel')
                : item.size === undefined
                  ? t('artifacts.tabFiles')
                  : t('mobile.fileBrowser.file', {v1: String(formatSize(item.size))})
            }
            onPress={() => {
              if (item.directory) {
                selectFolder([...folders, {name: item.name, path: item.path}]);
              } else {
                Keyboard.dismiss();
                setFile(item);
              }
            }}
          />
        )}
      />
    </>
  );
}

function FolderTrail({
  folders,
  onSelect,
}: {
  folders: Folder[];
  onSelect: (index: number) => void;
}) {
  useI18n();
  const colors = useTheme();
  const scroll = useRef<ComponentRef<typeof ScrollView>>(null);

  return (
    <ScrollView
      ref={scroll}
      horizontal
      style={[styles.trail, {borderColor: colors.line}]}
      contentContainerStyle={styles.trailContent}
      onContentSizeChange={() => scroll.current?.scrollToEnd({animated: false})}
    >
      {folders.map(({name, path}, index) => {
        const isCurrent = index === folders.length - 1;
        return (
          <View key={path ?? ''} style={styles.crumb}>
            {index > 0 ? (
              <Text
                accessible={false}
                style={[styles.separator, {color: colors.sub}]}
              >
                /
              </Text>
            ) : null}
            {isCurrent ? (
              <Text
                numberOfLines={1}
                style={[styles.crumbText, {color: colors.ink}]}
              >
                {name}
              </Text>
            ) : (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={t('mobile.fileBrowser.backTo', {
                  v1: String(name),
                })}
                onPress={() => onSelect(index)}
                style={styles.crumbButton}
              >
                <Text
                  numberOfLines={1}
                  style={[styles.crumbText, {color: colors.accent}]}
                >
                  {name}
                </Text>
              </Pressable>
            )}
          </View>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  list: {paddingHorizontal: spacing.lg, paddingBottom: spacing.xxl},
  search: {paddingTop: spacing.sm},
  toolbar: {paddingHorizontal: spacing.lg},
  trail: {flexGrow: 0, borderBottomWidth: StyleSheet.hairlineWidth},
  trailContent: {
    minHeight: touchTarget,
    paddingHorizontal: spacing.lg,
    alignItems: 'center',
  },
  crumb: {flexDirection: 'row', alignItems: 'center'},
  crumbButton: {
    minHeight: touchTarget,
    minWidth: touchTarget,
    justifyContent: 'center',
  },
  crumbText: {maxWidth: 160, fontWeight: '600'},
  separator: {paddingHorizontal: spacing.sm},
});
