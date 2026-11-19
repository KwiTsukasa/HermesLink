import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';

import {Button} from '../../../shared/ui/Button';
import {
  ActionStatus,
  backgroundAction,
  type BackgroundAction,
} from '../components/ActionStatus';
import {BusinessPage} from '../components/BusinessPage';
import {
  Card,
  Fact,
  Heading,
  Notice,
  ResourceStatus,
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
  requireAccepted,
  rows,
  textValue,
} from '../utils/data';

export function MaintenanceScreen({
  profile,
  mode,
  byRoute,
  onBack,
  onWrite,
}: DetailScreenProps & {profile?: string; mode: 'checkpoints' | 'hooks'}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [search, setSearch] = useState('');
  const [action, setAction] = useState<BackgroundAction>();
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const scoped = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const checkpoints = mode === 'checkpoints';
  const target = useDetailQuery(
    byRoute.get(checkpoints ? 'GET /api/ops/checkpoints' : 'GET /api/ops/hooks'),
    scoped,
    revision,
  );
  const output = record(target.value);
  const prune = byRoute.get('POST /api/ops/checkpoints/prune');
  const query = search.trim().toLowerCase();
  const sessions = rows(output.sessions, []);
  const hooks = rows(output.hooks, []).filter((item) =>
    `${plain(item.event)} ${plain(item.command)} ${plain(item.matcher)}`
      .toLowerCase()
      .includes(query),
  );

  function clean() {
    if (!prune || prune.unavailable) return;
    confirm(
      t('mobile.maintenance.pruneOldCheckpoints'),
      t('mobile.maintenance.profileTheServerWillPruneEligibleCheckpointsUnder', {
        v1: String(profile || t('mobile.groups.currentGateway')),
      }),
      async () => {
        const owner =
          profile ||
          plain(
            record(await invoke(byRoute.get('GET /api/profiles/active'))).current,
          );
        if (!owner)
          throw new Error(
            t('mobile.maintenance.theServerDidNotReturnTheCurrentProfile'),
          );
        const result = requireAccepted(
          await invoke(prune, {query: {profile: owner}}, true),
        );
        const pending = backgroundAction(
          result,
          t('mobile.maintenance.pruneOldCheckpoints'),
        );
        if (result.ok !== true || !pending)
          throw new Error(
            t('mobile.maintenance.theServerDidNotConfirmThePruneRequest'),
          );
        setAction(pending);
        return t('mobile.maintenance.theServerAcceptedThePruneRequest');
      },
    );
  }

  return (
    <BusinessPage
      title={
        checkpoints
          ? t('settings.subpages.safetyCheckpoints')
          : t('mobile.maintenance.eventHooks')
      }
      onBack={onBack}
      onRefresh={refresh}
      loading={target.loading || busy}
    >
      <ResourceStatus
        resource={target}
        label={
          checkpoints
            ? t('settings.subpages.safetyCheckpoints')
            : t('mobile.maintenance.eventHooks')
        }
      />
      {feedback ? <Notice>{feedback}</Notice> : null}
      {action ? (
        <ActionStatus
          key={`${action.name}:${action.pid}`}
          action={action}
          byRoute={byRoute}
          onComplete={refresh}
        />
      ) : null}
      {checkpoints ? (
        <>
          {typeof output.total_bytes === 'number' ? (
            <Card>
              <Fact
                label={t('mobile.maintenance.checkpointStorage')}
                value={t('mobile.kanbanTask.bytes', {
                  v1: output.total_bytes,
                  count: output.total_bytes,
                })}
              />
              <Fact
                label={t('mobile.maintenance.sessions')}
                value={`${sessions.length}`}
              />
              {sessions.length && prune && !prune.unavailable ? (
                <Button
                  label={t('mobile.maintenance.pruneOldCheckpoints')}
                  disabled={target.loading || busy}
                  onPress={clean}
                />
              ) : null}
            </Card>
          ) : null}
          {sessions.map((item) => (
            <Card key={plain(item.session)}>
              <Heading>{plain(item.session)}</Heading>
              <Fact
                label={t('cli.commands.snapshot.col_files')}
                value={textValue(item.files)}
              />
              <Fact
                label={t('mobile.maintenance.storageUsed')}
                value={
                  typeof item.bytes === 'number'
                    ? t('mobile.kanbanTask.bytes', {
                        v1: item.bytes,
                        count: item.bytes,
                      })
                    : ''
                }
              />
            </Card>
          ))}
          {!target.loading && !target.error && !sessions.length ? (
            <Notice>{t('mobile.maintenance.noCheckpoints')}</Notice>
          ) : null}
        </>
      ) : (
        <>
          <SearchField
            placeholder={t('mobile.maintenance.searchEventsOrCommands')}
            value={search}
            onChange={setSearch}
            clearable
          />
          {hooks.map((item, index) => (
            <Card key={`${plain(item.event)}:${index}`}>
              <Heading>{plain(item.event)}</Heading>
              <Fact
                label={t('mobile.maintenance.matcher')}
                value={plain(item.matcher)}
              />
              <Fact
                label={t('assistant.approval.command')}
                value={plain(item.command)}
              />
              <Fact
                label={t('mobile.maintenance.authorization')}
                value={
                  typeof item.approved === 'boolean'
                    ? item.approved
                      ? t('mobile.maintenance.approved')
                      : t('mobile.maintenance.awaitingApproval')
                    : ''
                }
              />
            </Card>
          ))}
          {!target.loading && !target.error && !hooks.length ? (
            <Notice>{t('mobile.maintenance.noMatchingEventHooks')}</Notice>
          ) : null}
        </>
      )}
    </BusinessPage>
  );
}
