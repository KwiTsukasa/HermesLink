import {formatDate, t, useI18n} from '../../../shared/i18n';
import {useMemo, useRef, useState} from 'react';
import {View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {BusinessPage} from '../components/BusinessPage';
import {
  Card,
  Fact,
  Heading,
  Notice,
  ResourceStatus,
  detailStyles as styles,
} from '../components/DetailContent';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {executeOperation, invoke, saveFile} from '../services/workspaceApi';
import type {DetailScreenProps, Dict} from '../types';
import {
  errorMessage,
  plainText as plain,
  profileQuery,
  record,
  rows,
  textValue,
} from '../utils/data';
import {kanban, statusLabel, statusLabels, taskPath} from './KanbanScreen';

const permittedMoves = [
  'triage',
  'todo',
  'ready',
  'blocked',
  'scheduled',
  'review',
  'done',
  'archived',
];

function taskDate(raw: unknown) {
  return typeof raw === 'number' && raw > 0
    ? formatDate(raw < 1e12 ? raw * 1000 : raw)
    : '';
}

export function KanbanTaskScreen({
  profile,
  board,
  id,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
}: DetailScreenProps & {profile?: string; board: string; id: string}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [moveOpen, setMoveOpen] = useState(false);
  const [fileMessage, setFileMessage] = useState('');
  const [downloading, setDownloading] = useState(false);
  const downloadInFlight = useRef(false);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const input = useMemo(
    () => ({query: {...profileQuery(profile), board}, path: {task_id: id}}),
    [profile, board, id],
  );
  const detailEntry = byRoute.get(`GET ${taskPath}`);
  const detail = useDetailQuery(detailEntry, input, revision);
  const value = record(detail.value);
  const task = record(value.task);
  const title = plain(task.title) || id;
  const attachments = rows(value.attachments, []).filter(
    (item) => item.task_id === id,
  );
  const comments = rows(value.comments, []);
  const edit = byRoute.get(`PATCH ${taskPath}`);
  const postComment = byRoute.get(`POST ${taskPath}/comments`);
  const upload = byRoute.get(`POST ${taskPath}/attachments`);
  const list = byRoute.get(`GET ${taskPath}/attachments`);
  const download = byRoute.get(`GET ${kanban}/attachments/{attachment_id}`);
  const remove = byRoute.get(`DELETE ${kanban}/attachments/{attachment_id}`);

  async function latestTask() {
    const current = record(record(await invoke(detailEntry, input)).task);
    if (current.id !== task.id)
      throw new Error(t('mobile.kanbanTask.theTaskChangedOnTheGatewayRefreshTo'));
    return current;
  }

  function updateTask() {
    if (!edit || edit.unavailable) return;
    onOpenForm({
      title: t('mobile.kanbanTask.editTask'),
      action: t('mobile.jobEditor.saveJob'),
      detail: t('mobile.kanbanTask.board', {v1: String(title), v2: String(board)}),
      entry: edit,
      fields: [
        {
          key: 'title',
          label: t('mobile.kanban.taskTitle'),
          initial: title,
          required: true,
        },
        {
          key: 'body',
          label: t('mobile.kanban.taskDescription'),
          initial: typeof task.body === 'string' ? task.body : '',
          multiline: true,
        },
      ],
      build: ({title: next, body}) => ({...input, body: {title: next.trim(), body}}),
      beforeSubmit: async () => {
        const current = await latestTask();
        if (current.title !== task.title || current.body !== task.body)
          throw new Error(
            t('mobile.kanbanTask.theTitleOrDescriptionChangedRefreshBeforeEditing'),
          );
      },
      onSuccess: (response, operation) => {
        const saved = record(record(response).task);
        if (saved.id !== id || saved.title !== operation.body?.title)
          throw new Error(
            t('mobile.kanbanTask.theServerDidNotConfirmTheTaskChanges'),
          );
      },
    });
  }

  function move(next: string) {
    if (!edit || edit.unavailable || !permittedMoves.includes(next)) return;
    const note =
      task.status === 'running'
        ? t('mobile.kanbanTask.changingStatusMayEndTheCurrentRun')
        : ['ready', 'review'].includes(next) && task.assignee
          ? t('mobile.kanbanTask.enteringThisStatusMayStartExecutionIfThe')
          : '';
    confirm(
      t('mobile.kanbanTask.moveTask'),
      [
        `${title} · ${board}`,
        `${statusLabel(task.status)} → ${statusLabel(next)}`,
        note,
      ]
        .filter(Boolean)
        .join('\n'),
      async () => {
        const current = await latestTask();
        if (current.status !== task.status || current.assignee !== task.assignee)
          throw new Error(
            t('mobile.kanbanTask.theTaskStatusOrAssigneeChangedRefreshTo'),
          );
        const result = record(
          record(await invoke(edit, {...input, body: {status: next}}, true)).task,
        );
        if (result.id !== id || !plain(result.status))
          throw new Error(
            t('mobile.kanbanTask.theServerDidNotConfirmTheTaskStatus'),
          );
        setMoveOpen(false);
        return t('mobile.kanbanTask.theTaskIsNow', {
          v1: String(statusLabel(result.status)),
        });
      },
    );
  }

  function addComment() {
    if (!postComment || postComment.unavailable) return;
    onOpenForm({
      title: t('mobile.kanbanTask.addATaskComment'),
      action: t('mobile.kanbanTask.postComment'),
      detail: t('mobile.kanbanTask.board', {v1: String(title), v2: String(board)}),
      entry: postComment,
      fields: [
        {
          key: 'text',
          label: t('mobile.kanbanTask.comments'),
          required: true,
          multiline: true,
        },
      ],
      build: ({text}) => ({...input, body: {body: text}}),
      onSuccess: (response) => {
        if (record(response).ok !== true)
          throw new Error(t('mobile.kanbanTask.theServerDidNotConfirmTheComment'));
      },
    });
  }

  function uploadFile() {
    if (!upload || upload.unavailable) return;
    onOpenForm({
      title: t('mobile.kanbanTask.addATaskAttachment'),
      action: t('mobile.kanbanTask.uploadAttachment'),
      detail: t('mobile.kanbanTask.board', {v1: String(title), v2: String(board)}),
      entry: upload,
      fields: [],
      pickFile: true,
      build: () => input,
      onSuccess: (response) => {
        if (record(record(response).attachment).task_id !== id)
          throw new Error(
            t('mobile.kanbanTask.theServerDidNotConfirmTheAttachment'),
          );
      },
    });
  }

  async function saveAttachment(attachment: Dict) {
    const attachmentId = attachment.id;
    if (
      !download ||
      download.unavailable ||
      downloadInFlight.current ||
      typeof attachmentId !== 'number'
    )
      return;
    downloadInFlight.current = true;
    setDownloading(true);
    setFileMessage('');
    try {
      const result = await executeOperation(download, {
        query: input.query,
        path: {attachment_id: attachmentId},
      });
      if (result.kind !== 'file')
        throw new Error(t('mobile.kanbanTask.theServerDidNotReturnADownload'));
      if (await saveFile(result.handle))
        setFileMessage(t('mobile.kanbanTask.attachmentSaved'));
    } catch (cause) {
      setFileMessage(errorMessage(cause));
    } finally {
      downloadInFlight.current = false;
      setDownloading(false);
    }
  }

  function removeAttachment(attachment: Dict) {
    const attachmentId = attachment.id;
    if (
      !remove ||
      remove.unavailable ||
      !list ||
      list.unavailable ||
      typeof attachmentId !== 'number'
    )
      return;
    confirm(
      t('mobile.kanbanTask.deleteTaskAttachment'),
      t('mobile.kanbanTask.taskBoard', {
        v1: String(plain(attachment.filename)),
        v2: String(title),
        v3: String(board),
      }),
      async () => {
        const current = rows(await invoke(list, input), ['attachments']);
        if (!current.some((item) => item.id === attachmentId && item.task_id === id))
          throw new Error(t('mobile.kanbanTask.thisTaskAttachmentWasNotFound'));
        const result = record(
          await invoke(
            remove,
            {query: input.query, path: {attachment_id: attachmentId}},
            true,
          ),
        );
        if (result.ok !== true || result.id !== attachmentId)
          throw new Error(
            t('mobile.kanbanTask.theServerDidNotConfirmAttachmentDeletion'),
          );
      },
    );
  }

  return (
    <BusinessPage
      title={t('mobile.jobDetails.taskDetails')}
      onBack={onBack}
      onRefresh={refresh}
      loading={detail.loading || busy}
    >
      <ResourceStatus resource={detail} label={t('mobile.kanban.boardTasks')} />
      {feedback ? <Notice>{feedback}</Notice> : null}
      {task.id === id && (
        <>
          <Card>
            <Heading>{title}</Heading>
            <Fact
              label={t('sidebar.filter.status')}
              value={statusLabel(task.status)}
            />
            <Fact
              label={t('mobile.kanbanTask.assignee')}
              value={plain(task.assignee) || t('mobile.kanbanTask.unassigned')}
            />
            <Fact label={t('mobile.kanbanTask.tenant')} value={plain(task.tenant)} />
            <Fact
              label={t('mobile.kanbanTask.priority')}
              value={textValue(task.priority)}
            />
            <Fact
              label={t('mobile.achievements.description')}
              value={plain(task.body)}
            />
            <Fact
              label={t('mobile.kanbanTask.result')}
              value={plain(task.result) || plain(task.latest_summary)}
            />
            <View style={styles.actions}>
              {edit && !edit.unavailable ? (
                <>
                  <Button
                    label={t('mobile.kanbanTask.editTask')}
                    onPress={updateTask}
                    disabled={detail.loading || busy}
                  />
                  <Button
                    label={
                      moveOpen
                        ? t('mobile.kanbanTask.hideStatusOptions')
                        : t('mobile.kanbanTask.moveTask')
                    }
                    onPress={() => setMoveOpen(!moveOpen)}
                    disabled={detail.loading || busy}
                  />
                </>
              ) : null}
            </View>
            {moveOpen && edit && !edit.unavailable ? (
              <View style={styles.actions}>
                {permittedMoves
                  .filter((next) => next !== task.status)
                  .map((next) => (
                    <Button
                      key={next}
                      label={statusLabels[next]}
                      onPress={() => move(next)}
                      disabled={detail.loading || busy}
                    />
                  ))}
              </View>
            ) : null}
          </Card>
          <Heading>{t('mobile.kanbanTask.comments')}</Heading>
          {postComment && !postComment.unavailable ? (
            <Button
              label={t('mobile.live.commentAdded')}
              onPress={addComment}
              disabled={detail.loading || busy}
            />
          ) : null}
          {comments.length ? (
            comments.map((comment) => (
              <Card key={textValue(comment.id)}>
                <Fact
                  label={[plain(comment.author), taskDate(comment.created_at)]
                    .filter(Boolean)
                    .join(' · ')}
                  value={plain(comment.body)}
                />
              </Card>
            ))
          ) : (
            <Notice>{t('mobile.kanbanTask.noComments')}</Notice>
          )}
          <Heading>{t('settings.subpages.chatAttachments')}</Heading>
          {upload && !upload.unavailable ? (
            <Button
              label={t('mobile.kanbanTask.addAnAttachmentFromYourPhone')}
              onPress={uploadFile}
              disabled={detail.loading || busy}
            />
          ) : null}
          {fileMessage ? <Notice>{fileMessage}</Notice> : null}
          {attachments.length ? (
            attachments.map((attachment) => (
              <Card key={textValue(attachment.id)}>
                <Heading>
                  {plain(attachment.filename) ||
                    t('settings.subpages.chatAttachments')}
                </Heading>
                <Fact
                  label={t('cli.commands.snapshot.col_size')}
                  value={
                    typeof attachment.size === 'number'
                      ? t('mobile.kanbanTask.bytes', {
                          v1: attachment.size,
                          count: attachment.size,
                        })
                      : ''
                  }
                />
                <View style={styles.actions}>
                  {download && !download.unavailable ? (
                    <Button
                      label={t('mobile.artifacts.saveToPhone')}
                      disabled={downloading}
                      onPress={() => saveAttachment(attachment)}
                    />
                  ) : null}
                  {remove && !remove.unavailable && list && !list.unavailable ? (
                    <Button
                      label={t('mobile.kanbanTask.deleteAttachment')}
                      disabled={detail.loading || busy || downloading}
                      onPress={() => removeAttachment(attachment)}
                    />
                  ) : null}
                </View>
              </Card>
            ))
          ) : (
            <Notice>{t('mobile.kanbanTask.noAttachments')}</Notice>
          )}
          {rows(value.runs, []).length ? (
            <Heading>{t('mobile.kanbanTask.recentRuns')}</Heading>
          ) : null}
          {rows(value.runs, [])
            .slice(-10)
            .reverse()
            .map((run) => (
              <Card key={textValue(run.id)}>
                <Fact
                  label={[
                    plain(run.outcome) || plain(run.status),
                    taskDate(run.started_at),
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                  value={plain(run.summary) || plain(run.error) || textValue(run.id)}
                />
              </Card>
            ))}
        </>
      )}
    </BusinessPage>
  );
}
