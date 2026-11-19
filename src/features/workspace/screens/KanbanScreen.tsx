import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {ListRow} from '../../../shared/ui/ListRow';
import {BusinessPage} from '../components/BusinessPage';
import {
  Card,
  Fact,
  Notice,
  ResourceStatus,
  detailStyles as styles,
} from '../components/DetailContent';
import {SearchField} from '../components/SearchField';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {DetailScreenProps} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  rows,
  stringList,
  textValue,
} from '../utils/data';

export const kanban = '/api/plugins/kanban';
export const taskPath = `${kanban}/tasks/{task_id}`;
export const statusLabels: Record<string, string> = {
  get triage() {
    return t('mobile.kanban.triage');
  },
  get todo() {
    return t('mobile.kanban.todo');
  },
  get ready() {
    return t('mobile.kanban.ready');
  },
  get running() {
    return t('mobile.groups.running');
  },
  get blocked() {
    return t('mobile.kanban.blocked');
  },
  get scheduled() {
    return t('mobile.kanban.scheduled');
  },
  get review() {
    return t('mobile.kanban.inReview');
  },
  get done() {
    return t('cron.states.completed');
  },
  get archived() {
    return t('desktop.archived');
  },
};
export const statusLabel = (value: unknown) =>
  statusLabels[plain(value)] || plain(value);

export function KanbanScreen({
  profile,
  slug,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
  onOpenBoard,
  onOpenTask,
}: DetailScreenProps & {
  profile?: string;
  slug?: string;
  onOpenBoard: (slug: string) => void;
  onOpenTask: (id: string) => void;
}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [search, setSearch] = useState('');
  const [tenant, setTenant] = useState('');
  const [selected, setSelected] = useState('');
  const [archived, setArchived] = useState(false);
  const [visible, setVisible] = useState(30);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const scope = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const taskScope = useMemo(
    () => ({query: {...profileQuery(profile), board: slug}}),
    [profile, slug],
  );
  const boardInput = useMemo(
    () => ({
      query: {
        ...taskScope.query,
        include_archived: archived,
        ...(tenant ? {tenant} : {}),
      },
    }),
    [archived, taskScope, tenant],
  );
  const rosterEntry = byRoute.get(`GET ${kanban}/boards`);
  const roster = useDetailQuery(rosterEntry, scope, revision);
  const board = useDetailQuery(
    byRoute.get(`GET ${kanban}/board`),
    boardInput,
    revision,
    !!slug,
  );
  const boards = rows(roster.value, ['boards']);
  const currentSlug = plain(record(roster.value).current);
  const currentBoard = boards.find((item) => item.slug === slug);
  const name = plain(currentBoard?.name) || slug || '';
  const columns = rows(board.value, ['columns']);
  const createBoard = byRoute.get(`POST ${kanban}/boards`);
  const editBoard = byRoute.get(`PATCH ${kanban}/boards/{slug}`);
  const archiveBoard = byRoute.get(`DELETE ${kanban}/boards/{slug}`);
  const switchBoard = byRoute.get(`POST ${kanban}/boards/{slug}/switch`);
  const createTask = byRoute.get(`POST ${kanban}/tasks`);
  const query = search.trim().toLowerCase();
  const tasks = columns
    .filter((column) => !selected || column.name === selected)
    .flatMap((column) => rows(column.tasks, []))
    .filter((task) =>
      `${plain(task.title)} ${plain(task.id)} ${plain(task.tenant)} ${plain(task.assignee)}`
        .toLowerCase()
        .includes(query),
    );

  function edit(kind: 'create' | 'update') {
    const entry = kind === 'create' ? createBoard : editBoard;
    if (!entry || entry.unavailable) return;
    const title =
      kind === 'create' ? t('mobile.kanban.newBoard') : t('mobile.kanban.editBoard');
    onOpenForm({
      title,
      action:
        kind === 'create'
          ? t('mobile.kanban.createBoard')
          : t('mobile.kanban.saveBoard'),
      entry,
      detail:
        name ||
        t('mobile.groups.profile', {
          v1: String(profile || t('mobile.groups.currentGateway')),
        }),
      fields: [
        ...(kind === 'create'
          ? [
              {
                key: 'slug',
                label: t('mobile.kanban.boardSlug'),
                required: true,
                placeholder: t('mobile.kanban.forExampleTeamNotes'),
              },
            ]
          : []),
        {
          key: 'name',
          label: t('mobile.kanban.boardName'),
          initial: name,
          required: true,
        },
        {
          key: 'description',
          label: t('mobile.achievements.description'),
          initial: plain(currentBoard?.description),
          multiline: true,
        },
      ],
      build: ({slug: next, name: titleValue, description}) => ({
        ...scope,
        ...(kind === 'update' ? {path: {slug}} : {}),
        body: {
          name: titleValue.trim(),
          description,
          ...(kind === 'create' ? {slug: next.trim().toLowerCase()} : {}),
        },
      }),
      beforeSubmit:
        kind === 'update'
          ? async () => {
              const latest = rows(await invoke(rosterEntry, scope), ['boards']).find(
                (item) => item.slug === slug,
              );
              if (
                !latest ||
                latest.name !== currentBoard?.name ||
                latest.description !== currentBoard?.description
              ) {
                throw new Error(
                  t('mobile.kanban.theBoardNameOrDescriptionChangedRefreshAnd'),
                );
              }
            }
          : undefined,
      onSuccess: (value, input) => {
        const saved = record(record(value).board);
        const expectedSlug = kind === 'create' ? input.body?.slug : slug;
        if (
          saved.slug !== expectedSlug ||
          saved.name !== input.body?.name ||
          plain(saved.description) !== plain(input.body?.description)
        )
          throw new Error(t('mobile.kanban.theGatewayDidNotConfirmTheChangesOr'));
      },
    });
  }

  function draft() {
    if (!createTask || createTask.unavailable || !slug) return;
    const idempotencyKey = `mobile-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
    onOpenForm({
      title: t('mobile.kanban.newTriageTask'),
      action: t('mobile.kanban.createTriageTask'),
      detail: t('mobile.kanban.board', {v1: String(name)}),
      entry: createTask,
      fields: [
        {key: 'title', label: t('mobile.kanban.taskTitle'), required: true},
        {key: 'body', label: t('mobile.kanban.taskDescription'), multiline: true},
        {key: 'tenant', label: t('mobile.kanban.tenantOptional'), initial: tenant},
      ],
      build: ({title, body, tenant: group}) => ({
        ...taskScope,
        body: {
          title: title.trim(),
          body,
          triage: true,
          idempotency_key: idempotencyKey,
          ...(group.trim() ? {tenant: group.trim()} : {}),
        },
      }),
      onSuccess: (value, input) => {
        const task = record(record(value).task);
        if (
          !plain(task.id) ||
          task.status !== 'triage' ||
          task.title !== input.body?.title
        )
          throw new Error(t('mobile.kanban.theGatewayDidNotConfirmTheTriageTask'));
      },
    });
  }

  function manageBoard(archive: boolean) {
    const entry = archive ? archiveBoard : switchBoard;
    const title = archive
      ? t('mobile.kanban.archiveBoard')
      : t('mobile.kanban.setAsServerDefaultBoard');
    confirm(
      title,
      t('mobile.jobDetails.profile', {
        v1: String(name),
        v2: String(profile || t('mobile.groups.currentGateway')),
      }),
      async () => {
        const response = record(await invoke(entry, {...scope, path: {slug}}, true));
        if (archive) {
          const latest = await invoke(rosterEntry, scope);
          if (rows(latest, ['boards']).some((item) => item.slug === slug))
            throw new Error(t('mobile.kanban.theBoardIsStillListedAsActiveRefresh'));
          onBack();
        } else if (response.current !== slug) {
          throw new Error(t('mobile.kanban.theGatewayDidNotConfirmTheDefaultBoard'));
        }
      },
    );
  }

  return (
    <BusinessPage
      title={slug ? name : t('mobile.live.kanban')}
      onBack={onBack}
      onRefresh={refresh}
      loading={roster.loading || board.loading || busy}
    >
      <ResourceStatus resource={roster} label={t('mobile.kanban.boards')} />
      {feedback ? <Notice>{feedback}</Notice> : null}
      {slug ? (
        <>
          <Card>
            <Fact label={t('mobile.live.kanban')} value={name} />
            <Fact
              label={t('mobile.achievements.description')}
              value={plain(currentBoard?.description)}
            />
            <Fact
              label={t('commandCenter.projects')}
              value={plain(currentBoard?.project_name)}
            />
            <View style={styles.actions}>
              {createTask &&
              !createTask.unavailable &&
              !board.error &&
              board.value ? (
                <Button
                  label={t('mobile.kanban.newTriageTask')}
                  variant="accent"
                  disabled={busy || board.loading}
                  onPress={draft}
                />
              ) : null}
              {currentBoard && editBoard && !editBoard.unavailable ? (
                <Button
                  label={t('mobile.kanban.editBoard')}
                  disabled={busy}
                  onPress={() => edit('update')}
                />
              ) : null}
              {currentBoard &&
              switchBoard &&
              !switchBoard.unavailable &&
              currentSlug !== slug ? (
                <Button
                  label={t('mobile.kanban.setAsDefaultBoard')}
                  disabled={busy}
                  onPress={() => manageBoard(false)}
                />
              ) : null}
              {currentBoard &&
              slug !== 'default' &&
              archiveBoard &&
              !archiveBoard.unavailable ? (
                <Button
                  label={t('mobile.kanban.archiveBoard')}
                  disabled={busy}
                  onPress={() => manageBoard(true)}
                />
              ) : null}
            </View>
          </Card>
          <SearchField
            placeholder={t('mobile.kanban.searchTasksTenantsOrAssignees')}
            value={search}
            onChange={setSearch}
            clearable
          />
          <View style={styles.actions}>
            <Button
              label={t('mobile.kanban.allStatuses')}
              selected={!selected}
              onPress={() => setSelected('')}
            />
            {columns
              .filter((column) => rows(column.tasks, []).length > 0)
              .map((column) => {
                const key = plain(column.name);
                return (
                  <Button
                    key={key}
                    label={statusLabel(key)}
                    selected={selected === key}
                    onPress={() => {
                      setSelected(key);
                      setVisible(30);
                    }}
                  />
                );
              })}
            <Button
              label={
                archived
                  ? t('mobile.kanban.hideArchivedTasks')
                  : t('mobile.kanban.includeArchivedTasks')
              }
              onPress={() => {
                setSelected('');
                setArchived(!archived);
              }}
            />
          </View>
          {stringList(record(board.value).tenants).length ? (
            <View style={styles.actions}>
              <Button
                label={t('mobile.kanban.allTenants')}
                selected={!tenant}
                onPress={() => setTenant('')}
              />
              {stringList(record(board.value).tenants).map((value) => (
                <Button
                  key={value}
                  label={value}
                  selected={tenant === value}
                  onPress={() => {
                    setSelected('');
                    setTenant(value);
                  }}
                />
              ))}
            </View>
          ) : null}
          <ResourceStatus resource={board} label={t('mobile.kanban.boardTasks')} />
          {tasks.slice(0, visible).map((item) => {
            const id = plain(item.id);
            return id ? (
              <ListRow
                key={id}
                title={plain(item.title) || id}
                subtitle={[
                  statusLabel(item.status),
                  plain(item.tenant),
                  plain(item.assignee),
                  typeof item.comment_count === 'number' && item.comment_count > 0
                    ? t('mobile.kanban.comments', {
                        v1: String(textValue(item.comment_count)),
                        count: Number(String(textValue(item.comment_count))),
                      })
                    : '',
                ]
                  .filter(Boolean)
                  .join(' · ')}
                onPress={() => onOpenTask(id)}
              />
            ) : null;
          })}
          {tasks.length > visible ? (
            <Button
              label={t('mobile.kanban.showMoreTasks')}
              onPress={() => setVisible(visible + 30)}
            />
          ) : null}
          {!tasks.length && !board.loading && !board.error ? (
            <Notice>{t('mobile.kanban.noMatchingTasks')}</Notice>
          ) : null}
        </>
      ) : (
        <>
          {createBoard &&
          !createBoard.unavailable &&
          !roster.loading &&
          !roster.error ? (
            <Button
              label={t('mobile.kanban.newBoard')}
              variant="accent"
              onPress={() => edit('create')}
            />
          ) : null}
          {boards.map((item) => {
            const key = plain(item.slug);
            return key ? (
              <ListRow
                key={key}
                title={plain(item.name) || key}
                subtitle={[
                  currentSlug === key ? t('mobile.kanban.defaultBoard') : '',
                  typeof item.total === 'number'
                    ? t('mobile.kanban.tasks', {
                        v1: String(textValue(item.total)),
                        count: Number(String(textValue(item.total))),
                      })
                    : '',
                  plain(item.project_name),
                ]
                  .filter(Boolean)
                  .join(' · ')}
                onPress={() => onOpenBoard(key)}
              />
            ) : null;
          })}
          {!boards.length && !roster.loading && !roster.error ? (
            <Notice>{t('mobile.kanban.noBoards')}</Notice>
          ) : null}
        </>
      )}
    </BusinessPage>
  );
}
