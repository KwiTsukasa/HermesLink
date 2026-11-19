import {t, useI18n} from '../../../shared/i18n';
import {useEffect, useMemo, useState} from 'react';
import {Image, Linking, StyleSheet, View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {Feedback} from '../../../shared/ui/Feedback';
import {ListRow} from '../../../shared/ui/ListRow';
import {ResourcePlaybackButton} from '../../resources/ResourcePlaybackButton';
import {BusinessPage} from '../components/BusinessPage';
import {
  Card,
  Fact,
  Heading,
  Notice,
  ResourceStatus,
  detailStyles as styles,
} from '../components/DetailContent';
import {SearchField} from '../components/SearchField';
import {WorkspacePaneTabs} from '../components/WorkspaceTabs';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {executeOperation, saveFile} from '../services/workspaceApi';
import type {DetailScreenProps} from '../types';
import {artifactsInMessages, inlineImage, type Artifact} from '../utils/artifacts';
import {
  errorMessage,
  plainText as plain,
  profileQuery,
  record,
  rows,
} from '../utils/data';

export type ArtifactSession = {id: string; profile: string; title: string};
export type ArtifactTarget = {
  session: ArtifactSession;
  artifact?: Artifact;
  resolvedSessionId?: string;
};

const pageSize = 25;
const kindLabels = {
  get file() {
    return t('artifacts.tabFiles');
  },
  get image() {
    return t('artifacts.tabImages');
  },
  get link() {
    return t('artifacts.tabLinks');
  },
};

export function ArtifactsScreen({
  profile,
  target,
  byRoute,
  onBack,
  onOpen,
  onOpenSession,
  onOpenBadges,
}: DetailScreenProps & {
  profile?: string;
  target?: ArtifactTarget;
  onOpen: (target: ArtifactTarget) => void;
  onOpenSession: (id: string, profile: string) => void;
  onOpenBadges: (session: ArtifactSession) => void;
}) {
  useI18n();
  const [search, setSearch] = useState('');
  const [revision, setRevision] = useState(0);
  const [offset, setOffset] = useState(0);
  const [loaded, setLoaded] = useState<Artifact[]>([]);
  const [canonical, setCanonical] = useState('');
  const [notice, setNotice] = useState('');
  const [previewNotice, setPreviewNotice] = useState<{
    target: string;
    message: string;
  }>();
  const [filter, setFilter] = useState<Artifact['kind']>();
  const [saving, setSaving] = useState(false);
  const session = target?.session;
  const artifact = target?.artifact;
  const resolvedSessionId = target?.resolvedSessionId || session?.id;
  const listInput = useMemo(
    () => ({query: {...profileQuery(profile), limit: 30, min_messages: 1}}),
    [profile],
  );
  const historyInput = useMemo(
    () => ({
      path: {session_id: session?.id},
      query: {
        profile: session?.profile,
        offset,
        limit: pageSize,
        order: 'oldest',
        include_compacted: true,
        inline_images: false,
      },
    }),
    [session, offset],
  );
  const fileInput = useMemo(
    () => ({
      query: {
        path: artifact?.transport === 'file' ? artifact.target : undefined,
        profile: session?.profile,
        session_id: resolvedSessionId,
      },
    }),
    [artifact, resolvedSessionId, session],
  );
  const recent = useDetailQuery(
    byRoute.get('GET /api/profiles/sessions'),
    listInput,
    revision,
    !session,
  );
  const history = useDetailQuery(
    byRoute.get('GET /api/sessions/{session_id}/messages'),
    historyInput,
    revision,
    !!session && !artifact,
  );
  const preview = useDetailQuery(
    byRoute.get(
      artifact?.kind === 'image'
        ? 'GET /api/fs/read-data-url'
        : 'GET /api/fs/read-text',
    ),
    fileInput,
    revision,
    artifact?.transport === 'file',
  );
  const download = byRoute.get('GET /api/fs/download');
  const page = record(history.value);
  const pagination = record(page.pagination);
  const more = pagination.returned === pageSize && pagination.offset === offset;
  const query = search.trim().toLowerCase();

  useEffect(() => {
    if (!session || artifact || history.loading || history.error || !history.value)
      return;
    const result = record(history.value);
    const id = plain(result.session_id);
    if (result.profile !== session.profile || !id) {
      setNotice(t('mobile.artifacts.couldNotVerifyWhichProfileOwnsTheReturned'));
      return;
    }
    if (offset > 0 && canonical !== id) {
      setNotice(t('mobile.artifacts.theSessionChangedRefreshToReloadItsArtifacts'));
      return;
    }
    const pageArtifacts = artifactsInMessages(rows(result, ['messages']));
    if (offset === 0) setCanonical(id);
    setLoaded((previous) =>
      Array.from(
        new Map(
          (offset === 0 ? pageArtifacts : [...previous, ...pageArtifacts]).map(
            (item) => [item.target, item],
          ),
        ).values(),
      ),
    );
  }, [
    artifact,
    canonical,
    history.error,
    history.loading,
    history.value,
    offset,
    session,
  ]);

  function refresh() {
    setRevision((value) => value + 1);
    setNotice('');
    setOffset(0);
  }

  async function save() {
    if (
      !download ||
      download.unavailable ||
      saving ||
      artifact?.transport !== 'file'
    )
      return;
    setSaving(true);
    setPreviewNotice(undefined);
    try {
      const result = await executeOperation(download, fileInput);
      if (result.kind !== 'file')
        throw new Error(
          t('mobile.artifacts.theServerDidNotReturnADownloadableFile'),
        );
      if (await saveFile(result.handle))
        setPreviewNotice({
          target: artifact.target,
          message: t('mobile.artifacts.fileSaved'),
        });
    } catch (cause) {
      setPreviewNotice({target: artifact.target, message: errorMessage(cause)});
    } finally {
      setSaving(false);
    }
  }

  if (artifact && session) {
    const data = record(preview.value);
    const image =
      artifact.transport === 'inline' ? artifact.target : plain(data.dataUrl);
    const text = typeof data.text === 'string' ? data.text : '';
    return (
      <BusinessPage title={t('mobile.artifacts.artifactDetails')} onBack={onBack}>
        <Card>
          <Heading>{artifact.title}</Heading>
          <Fact label={t('mobile.artifacts.sourceSession')} value={session.title} />
          <Fact
            label={kindLabels[artifact.kind]}
            value={artifact.transport === 'inline' ? '' : artifact.target}
          />
          <View style={styles.actions}>
            {artifact.transport === 'web' ? (
              <Button
                label={t('mobile.artifacts.openInBrowser')}
                variant="accent"
                onPress={() => {
                  Linking.openURL(artifact.target).catch((error: unknown) =>
                    setPreviewNotice({
                      target: artifact.target,
                      message: errorMessage(error),
                    }),
                  );
                }}
              />
            ) : null}
            {artifact.transport === 'file' && download && !download.unavailable ? (
              <Button
                label={
                  saving
                    ? t('messaging.telegramQr.applying')
                    : t('mobile.artifacts.saveToPhone')
                }
                disabled={saving}
                variant="accent"
                onPress={save}
              />
            ) : null}
            <Button
              label={t('mobile.artifacts.openSourceSession')}
              onPress={() =>
                onOpenSession(resolvedSessionId || session.id, session.profile)
              }
            />
          </View>
        </Card>
        {image && inlineImage.test(image) ? (
          <Image
            accessibilityLabel={artifact.title}
            source={{uri: image}}
            resizeMode="contain"
            style={artifactStyles.image}
          />
        ) : null}
        {image && !inlineImage.test(image) ? (
          <Notice>{t('mobile.artifacts.saveTheImageToOpenItOnYour')}</Notice>
        ) : null}
        {artifact.transport === 'file' ? (
          <ResourceStatus
            resource={preview}
            label={t('mobile.artifacts.filePreview')}
          />
        ) : null}
        {artifact.transport === 'file' ? (
          <ResourcePlaybackButton
            path={artifact.target}
            profile={session.profile}
            sessionId={resolvedSessionId}
          />
        ) : null}
        {text && data.binary !== true ? (
          <Card>
            <Fact
              label={
                data.truncated
                  ? t('mobile.artifacts.textPreview')
                  : t('mobile.artifacts.textContent')
              }
              value={text}
            />
          </Card>
        ) : null}
        {data.binary === true ? (
          <Notice>{t('mobile.artifacts.saveTheFileToOpenItOnYour')}</Notice>
        ) : null}
        {previewNotice?.target === artifact.target ? (
          <Notice>{previewNotice.message}</Notice>
        ) : null}
      </BusinessPage>
    );
  }

  const source = session ? history : recent;
  const matches = loaded.filter(
    (item) =>
      (!filter || item.kind === filter) &&
      `${item.title} ${item.target}`.toLowerCase().includes(query),
  );
  return (
    <BusinessPage
      title={
        session ? t('mobile.artifacts.sessionArtifacts') : t('sidebar.nav.artifacts')
      }
      onBack={onBack}
      onRefresh={refresh}
      loading={source.loading}
    >
      <SearchField
        placeholder={
          session
            ? t('mobile.artifacts.filterSessionArtifacts')
            : t('mobile.artifacts.searchRecentSessions')
        }
        value={search}
        onChange={setSearch}
        clearable
      />
      <ResourceStatus
        resource={source}
        label={
          session
            ? t('mobile.artifacts.sessionHistory')
            : t('mobile.artifacts.recentSessions')
        }
      />
      {notice ? <Feedback tone="error" message={notice} onRetry={refresh} /> : null}
      {session ? (
        <>
          <Heading>{session.title}</Heading>
          <View style={styles.actions}>
            {!byRoute.get(
              'GET /api/plugins/hermes-achievements/sessions/{session_id}/badges',
            )?.unavailable &&
            byRoute.has(
              'GET /api/plugins/hermes-achievements/sessions/{session_id}/badges',
            ) ? (
              <Button
                label={t('mobile.artifacts.viewSessionBadges')}
                onPress={() =>
                  onOpenBadges({...session, id: canonical || session.id})
                }
              />
            ) : null}
          </View>
          <WorkspacePaneTabs
            centered
            items={[
              {
                id: 'all',
                label: t('artifacts.tabAll'),
                selected: !filter,
                onPress: () => setFilter(undefined),
              },
              ...(['image', 'file', 'link'] as const).map((kind) => ({
                id: kind,
                label: kindLabels[kind],
                selected: kind === filter,
                onPress: () => setFilter(kind),
              })),
            ]}
          />
          {matches.map((item) => (
            <ListRow
              key={item.target}
              title={item.title}
              subtitle={kindLabels[item.kind]}
              onPress={() =>
                onOpen({
                  session,
                  resolvedSessionId: canonical || session.id,
                  artifact: item,
                })
              }
            />
          ))}
          {more ? (
            <Button
              label={t('mobile.artifacts.keepLooking')}
              disabled={history.loading || !!notice}
              onPress={() => setOffset(offset + pageSize)}
            />
          ) : null}
          {!matches.length && !history.loading && !history.error ? (
            <Notice>
              {more
                ? t('mobile.artifacts.noMatchesInTheMessagesLoadedSoFar')
                : t('mobile.artifacts.noMatchingArtifacts')}
            </Notice>
          ) : null}
        </>
      ) : (
        <>
          <Notice>
            {t('mobile.artifacts.selectARecentSessionToSeeFilesImages')}
          </Notice>
          {rows(recent.value, ['sessions']).map((item) => {
            const id = plain(item.id);
            const owner = plain(item.profile) || profile || '';
            const title = plain(item.title) || plain(item.preview) || id;
            if (!id || !owner || !`${title} ${owner}`.toLowerCase().includes(query))
              return null;
            return (
              <ListRow
                key={`${owner}:${id}`}
                title={title}
                subtitle={owner}
                onPress={() => onOpen({session: {id, profile: owner, title}})}
              />
            );
          })}
        </>
      )}
    </BusinessPage>
  );
}

const artifactStyles = StyleSheet.create({image: {width: '100%', height: 300}});
