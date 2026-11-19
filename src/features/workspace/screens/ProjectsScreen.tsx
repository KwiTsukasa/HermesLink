import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {ListRow} from '../../../shared/ui/ListRow';
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
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {gatewayRoute, invoke} from '../services/workspaceApi';
import type {DetailScreenProps, Dict} from '../types';
import {plainText as plain, profileQuery, record, rows} from '../utils/data';

function projectSessions(project: Dict) {
  const sessions = new Map<string, Dict>();
  for (const repository of rows(project.repos, [])) {
    for (const group of rows(repository.groups, [])) {
      for (const session of rows(group.sessions, [])) {
        const id = plain(session.id);
        if (id && !sessions.has(id)) sessions.set(id, session);
      }
    }
  }
  return Array.from(sessions.values());
}

export function ProjectsScreen({
  profile,
  id,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
  onOpenProject,
  onOpenFolder,
  onOpenSession,
}: DetailScreenProps & {
  profile?: string;
  id?: string;
  onOpenProject: (id: string) => void;
  onOpenFolder: (name: string, path: string) => void;
  onOpenSession: (id: string, owner?: string) => void;
}) {
  useI18n();
  const [search, setSearch] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const scope = useMemo(() => profileQuery(profile), [profile]);
  const input = useMemo(() => ({body: scope}), [scope]);
  const detailInput = useMemo(() => ({body: {...scope, id}}), [scope, id]);
  const sessionsInput = useMemo(
    () => ({body: {...scope, project_id: id, session_limit: 40}}),
    [scope, id],
  );
  const list = useDetailQuery(
    gatewayRoute(byRoute, 'projects.list'),
    input,
    revision,
  );
  const detail = useDetailQuery(
    gatewayRoute(byRoute, 'projects.get'),
    detailInput,
    revision,
    !!id,
  );
  const sessionEntry = gatewayRoute(byRoute, 'projects.project_sessions');
  const related = useDetailQuery(
    sessionEntry,
    sessionsInput,
    revision,
    !!id && !!sessionEntry && !sessionEntry.unavailable,
  );
  const projects = rows(list.value, ['projects']);
  const project = record(record(detail.value).project);
  const sessions = projectSessions(record(record(related.value).project));
  const active = plain(record(list.value).active_id);
  const name = plain(project.name) || id || '';
  const create = gatewayRoute(byRoute, 'projects.create');
  const update = gatewayRoute(byRoute, 'projects.update');
  const addFolder = gatewayRoute(byRoute, 'projects.add_folder');
  const archive = gatewayRoute(byRoute, 'projects.archive');
  const remove = gatewayRoute(byRoute, 'projects.delete');
  const setActive = gatewayRoute(byRoute, 'projects.set_active');
  const removeFolder = gatewayRoute(byRoute, 'projects.remove_folder');
  const setPrimary = gatewayRoute(byRoute, 'projects.set_primary');

  function editProject() {
    const entry = id ? update : create;
    if (!entry || entry.unavailable) return;
    const title = id
      ? t('mobile.projects.editProject')
      : t('sidebar.projects.createTitle');
    onOpenForm({
      title,
      action: id
        ? t('mobile.projects.saveProject')
        : t('mobile.projects.createProject'),
      detail: t('mobile.groups.profile', {
        v1: String(profile || t('mobile.groups.currentGateway')),
      }),
      entry,
      fields: [
        {
          key: 'name',
          label: t('mobile.projects.projectName'),
          initial: plain(project.name),
          required: true,
        },
        {
          key: 'description',
          label: t('mobile.achievements.description'),
          initial: plain(project.description),
          multiline: true,
        },
        ...(!id
          ? [
              {
                key: 'folders',
                label: t('mobile.projects.hostFoldersOnePerLine'),
                multiline: true,
              },
            ]
          : []),
      ],
      build: ({name: nextName, description, folders}) => ({
        body: {
          ...scope,
          name: nextName.trim(),
          description,
          ...(id
            ? {id}
            : {
                folders: folders
                  .split(/\r?\n/)
                  .map((line) => line.trim())
                  .filter(Boolean),
              }),
        },
      }),
      onSuccess: (value) => {
        const saved = record(record(value).project);
        if (!plain(saved.id) || (id && saved.id !== id))
          throw new Error(t('mobile.projects.theServerDidNotConfirmTheProject'));
      },
    });
  }

  function addProjectFolder() {
    if (!id || !addFolder || addFolder.unavailable) return;
    onOpenForm({
      title: t('mobile.projects.linkProjectFolder'),
      action: t('mobile.projects.addFolder'),
      detail: name,
      entry: addFolder,
      fields: [
        {key: 'path', label: t('mobile.projects.hostFolder'), required: true},
        {key: 'label', label: t('mobile.projects.folderNameOptional')},
      ],
      build: ({path, label}) => ({
        body: {
          ...scope,
          id,
          path: path.trim(),
          ...(label.trim() ? {label: label.trim()} : {}),
        },
      }),
      onSuccess: (value) => {
        if (record(record(value).project).id !== id)
          throw new Error(t('mobile.projects.theServerDidNotConfirmTheProject'));
      },
    });
  }

  function act(
    label: string,
    entry: typeof update,
    values: Dict,
    verify: (outcome: Dict) => boolean,
    explanation = '',
  ) {
    confirm(label, [name, explanation].filter(Boolean).join('\n'), async () => {
      const outcome = record(
        await invoke(entry, {body: {...scope, ...values}}, true),
      );
      if (!verify(outcome))
        throw new Error(t('mobile.projects.theServerDidNotConfirmTheProjectAction'));
      if (entry === remove) onBack();
    });
  }

  const query = search.trim().toLowerCase();
  const visible = projects.filter(
    (item) =>
      (showArchived || item.archived !== true) &&
      `${plain(item.name)} ${plain(item.description)} ${plain(item.primary_path)}`
        .toLowerCase()
        .includes(query),
  );
  return (
    <BusinessPage
      title={id ? t('mobile.projects.projectDetails') : t('commandCenter.projects')}
      onBack={onBack}
      onRefresh={refresh}
      loading={list.loading || detail.loading || related.loading || busy}
    >
      <ResourceStatus
        resource={id ? detail : list}
        label={
          id ? t('mobile.projects.projectDetails') : t('mobile.projects.projects')
        }
      />
      {!!feedback && <Notice>{feedback}</Notice>}
      {id ? (
        project.id === id ? (
          <>
            <Card>
              <Heading>{name}</Heading>
              <Fact
                label={t('sidebar.filter.status')}
                value={
                  project.archived
                    ? t('desktop.archived')
                    : active === id
                      ? t('mobile.projects.activeProject')
                      : t('mobile.groups.available')
                }
              />
              <Fact
                label={t('mobile.achievements.description')}
                value={plain(project.description)}
              />
              <Fact
                label={t('mobile.projects.primaryFolder')}
                value={plain(project.primary_path)}
              />
              <View style={styles.actions}>
                {update && !update.unavailable ? (
                  <Button
                    label={t('mobile.projects.editProject')}
                    disabled={busy || detail.loading}
                    onPress={editProject}
                  />
                ) : null}
                {setActive && !setActive.unavailable && project.archived !== true ? (
                  <Button
                    label={
                      active === id
                        ? t('mobile.projects.clearActiveProject')
                        : t('mobile.projects.setAsActiveProject')
                    }
                    disabled={busy || list.loading}
                    onPress={() =>
                      act(
                        active === id
                          ? t('mobile.projects.clearActiveProject')
                          : t('mobile.projects.setAsActiveProject'),
                        setActive,
                        active === id ? {} : {id},
                        (outcome) =>
                          active === id
                            ? outcome.active_id === null
                            : outcome.active_id === id,
                      )
                    }
                  />
                ) : null}
                {archive && !archive.unavailable ? (
                  <Button
                    label={
                      project.archived
                        ? t('mobile.projects.restoreProject')
                        : t('mobile.projects.archiveProject')
                    }
                    disabled={busy || detail.loading}
                    onPress={() =>
                      act(
                        project.archived
                          ? t('mobile.projects.restoreProject')
                          : t('mobile.projects.archiveProject'),
                        archive,
                        {id, restore: project.archived === true},
                        (outcome) =>
                          rows(outcome, ['projects']).find((item) => item.id === id)
                            ?.archived !== project.archived &&
                          rows(outcome, ['projects']).some((item) => item.id === id),
                      )
                    }
                  />
                ) : null}
                {remove && !remove.unavailable ? (
                  <Button
                    label={t('mobile.projects.deleteProject')}
                    disabled={busy || detail.loading}
                    onPress={() =>
                      act(
                        t('mobile.projects.deleteProject'),
                        remove,
                        {id},
                        (outcome) =>
                          Array.isArray(outcome.projects) &&
                          !rows(outcome, ['projects']).some(
                            (item) => item.id === id,
                          ),
                        t('mobile.projects.theServerWillRemoveTheProjectAndIts'),
                      )
                    }
                  />
                ) : null}
              </View>
            </Card>
            <Heading>{t('mobile.projects.linkedFolders')}</Heading>
            {rows(project.folders, []).map((folder) => {
              const path = plain(folder.path);
              const title = plain(folder.label) || path;
              return path ? (
                <Card key={path}>
                  <ListRow
                    title={title}
                    subtitle={
                      folder.is_primary ? t('mobile.projects.primaryFolder') : path
                    }
                    onPress={() => onOpenFolder(title, path)}
                  />
                  <View style={styles.actions}>
                    {folder.is_primary !== true &&
                    setPrimary &&
                    !setPrimary.unavailable ? (
                      <Button
                        label={t('mobile.projects.setAsPrimaryFolder')}
                        disabled={busy || detail.loading}
                        onPress={() =>
                          act(
                            t('mobile.projects.setAsPrimaryFolder'),
                            setPrimary,
                            {id, path},
                            (outcome) =>
                              record(outcome.project).primary_path === path,
                            path,
                          )
                        }
                      />
                    ) : null}
                    {removeFolder && !removeFolder.unavailable ? (
                      <Button
                        label={t('messaging.relayLink.unlink')}
                        disabled={busy || detail.loading}
                        onPress={() =>
                          act(
                            t('mobile.projects.unlinkFolder'),
                            removeFolder,
                            {id, path},
                            (outcome) =>
                              record(outcome.project).id === id &&
                              !rows(record(outcome.project).folders, []).some(
                                (item) => item.path === path,
                              ),
                            path,
                          )
                        }
                      />
                    ) : null}
                  </View>
                </Card>
              ) : null;
            })}
            {addFolder && !addFolder.unavailable ? (
              <Button
                label={t('mobile.projects.addFolder')}
                onPress={addProjectFolder}
                disabled={busy || detail.loading}
              />
            ) : null}
            {sessionEntry && !sessionEntry.unavailable && (
              <>
                <Heading>{t('mobile.projects.projectChats')}</Heading>
                <ResourceStatus
                  resource={related}
                  label={t('mobile.projects.projectChats')}
                />
                {sessions.map((session) => {
                  const sessionId = plain(session.id);
                  const title = plain(session.title) || sessionId;
                  return (
                    <ListRow
                      key={sessionId}
                      title={title}
                      subtitle={plain(session.preview) || plain(session.cwd)}
                      onPress={() =>
                        onOpenSession(sessionId, plain(session.profile) || undefined)
                      }
                    />
                  );
                })}
                {!sessions.length && !related.loading && !related.error ? (
                  <Notice>{t('mobile.projects.noProjectChats')}</Notice>
                ) : null}
              </>
            )}
          </>
        ) : !detail.loading && !detail.error ? (
          <Notice>{t('mobile.projects.theSelectedProjectWasNotFound')}</Notice>
        ) : null
      ) : (
        <>
          <SearchField
            placeholder={t('sidebar.projects.worktreeProjectPlaceholder')}
            value={search}
            onChange={setSearch}
            clearable
          />
          <View style={styles.actions}>
            {create && !create.unavailable ? (
              <Button
                label={t('sidebar.projects.createTitle')}
                variant="accent"
                onPress={editProject}
              />
            ) : null}
            <Button
              label={
                showArchived
                  ? t('mobile.projects.hideArchived')
                  : t('mobile.projects.includeArchived')
              }
              onPress={() => setShowArchived(!showArchived)}
            />
          </View>
          {visible.map((item) => {
            const key = plain(item.id);
            return key ? (
              <ListRow
                key={key}
                title={plain(item.name) || key}
                subtitle={[
                  active === key
                    ? t('mobile.projects.activeProject')
                    : item.archived
                      ? t('desktop.archived')
                      : '',
                  plain(item.primary_path),
                  plain(item.description),
                ]
                  .filter(Boolean)
                  .join(' · ')}
                onPress={() => onOpenProject(key)}
              />
            ) : null;
          })}
          {!visible.length && !list.loading && !list.error ? (
            <Notice>
              {query
                ? t('mobile.projects.noMatchingProjects')
                : t('mobile.projects.noProjectsYet')}
            </Notice>
          ) : null}
        </>
      )}
    </BusinessPage>
  );
}
