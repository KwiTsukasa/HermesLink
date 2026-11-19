import {formatDate, t, useI18n} from '../../../shared/i18n';
import {useEffect, useMemo, useState} from 'react';
import {RefreshControl, ScrollView, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {Button} from '../../../shared/ui/Button';
import {
  Card,
  Fact,
  Heading,
  Notice,
  ResourceStatus,
  detailStyles as styles,
} from '../components/DetailContent';
import {PageHeading} from '../components/PageHeading';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {DetailScreenProps, Dict} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  requireAccepted,
  rows,
} from '../utils/data';
import {jobRoute} from './JobEditorScreen';

function jobOwner(job: Dict): string {
  return plain(job.profile) || plain(job.profile_name);
}

function jobId(job: Dict): string {
  return plain(job.id) || plain(job.job_id);
}

function timestamp(value: unknown): string {
  const raw = plain(value);
  const date =
    typeof value === 'number' && value > 0
      ? new Date(value < 1e12 ? value * 1000 : value)
      : raw
        ? new Date(raw)
        : null;
  return date && Number.isFinite(date.getTime()) ? formatDate(date) : raw;
}

const jobStates: Record<string, string> = {
  get completed() {
    return t('cron.states.completed');
  },
  get error() {
    return t('mobile.jobDetails.schedulingError');
  },
  get paused() {
    return t('commandCenter.maintenance.curatorPaused');
  },
  get scheduled() {
    return t('messaging.enabled');
  },
  get running() {
    return t('agents.running');
  },
  get pending() {
    return t('mobile.jobDetails.waitingToRun');
  },
};
const runStates: Record<string, string> = {
  get ok() {
    return t('mobile.jobDetails.success');
  },
  get success() {
    return t('mobile.jobDetails.success');
  },
  get completed() {
    return t('cron.states.completed');
  },
  get error() {
    return t('common.failed');
  },
  get failed() {
    return t('common.failed');
  },
  get cancelled() {
    return t('mobile.jobDetails.canceled');
  },
  get missed() {
    return t('mobile.jobDetails.missed');
  },
  get running() {
    return t('agents.running');
  },
};
const stateLabel = (states: Record<string, string>, value: unknown) => {
  const key = plain(value);
  return Object.prototype.hasOwnProperty.call(states, key) ? states[key] : key;
};

export function JobDetailsScreen({
  id,
  profile,
  byRoute,
  onBack,
  onWrite,
  onEdit,
}: DetailScreenProps & {
  id: string;
  profile?: string;
  onEdit: (profile: string, job: Dict) => void;
}) {
  useI18n();
  const c = useTheme();
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const runAction = useConfirmedAction(onWrite, refresh);
  const input = useMemo(
    () => ({path: {job_id: id}, query: profileQuery(profile)}),
    [id, profile],
  );
  const detailEntry = byRoute.get('GET ' + jobRoute);
  const validId = !!id && id !== 'unknown';
  const detail = useDetailQuery(detailEntry, input, revision, validId);
  const job = record(detail.value);
  const owner = jobOwner(job);
  const matches = validId && jobId(job) === id && (!profile || owner === profile);
  const runInput = useMemo(
    () => ({path: {job_id: id}, query: {...profileQuery(owner), limit: 20}}),
    [id, owner],
  );
  const history = useDetailQuery(
    byRoute.get(`GET ${jobRoute}/runs`),
    runInput,
    revision,
    matches && !!owner,
  );
  const runs = rows(history.value, ['runs']);
  const title = plain(job.name) || id;
  const state = plain(job.state);
  const schedule = record(job.schedule);
  const recurringError =
    state === 'error' && ['cron', 'interval'].includes(plain(schedule.kind));
  const paused = state === 'paused' || job.enabled === false || recurringError;
  const canManage =
    !!owner && state !== 'completed' && (state !== 'error' || recurringError);
  const action = paused
    ? t('mobile.jobDetails.resumeJob')
    : t('mobile.jobDetails.pauseJob');
  const route = byRoute.get(`POST ${jobRoute}/${paused ? 'resume' : 'pause'}`);
  const edit = byRoute.get(`PUT ${jobRoute}`);
  const trigger = byRoute.get(`POST ${jobRoute}/trigger`);
  const cancel = byRoute.get(`POST ${jobRoute}/cancel`);
  const remove = byRoute.get(`DELETE ${jobRoute}`);
  const loading = detail.loading || history.loading;

  useEffect(() => {
    if (!runAction.busy) return;
    const timer = setInterval(() => setRevision((value) => value + 1), 3000);
    return () => clearInterval(timer);
  }, [runAction.busy]);

  function changeState() {
    confirm(
      action,
      t('mobile.jobDetails.profile', {v1: String(title), v2: String(owner)}),
      async () => {
        const scoped = {path: {job_id: id}, query: {profile: owner}};
        const current = record(await invoke(detailEntry, scoped));
        if (
          jobId(current) !== id ||
          jobOwner(current) !== owner ||
          current.state !== job.state ||
          current.enabled !== job.enabled
        ) {
          throw new Error(
            t('mobile.jobDetails.theJobStatusOrOwnerChangedRefreshAnd'),
          );
        }
        const result = record(await invoke(route, scoped, true));
        if (
          jobId(result) !== id ||
          jobOwner(result) !== owner ||
          result.enabled !== paused
        ) {
          throw new Error(
            t('mobile.jobDetails.theGatewayDidNotConfirmTheJobStatus'),
          );
        }
      },
    );
  }

  function operate(entry: typeof route, kind: 'trigger' | 'cancel' | 'remove') {
    const label = {
      trigger: t('commandCenter.maintenance.runNow'),
      cancel: t('mobile.jobDetails.stopThisRun'),
      remove: t('mobile.jobDetails.deleteCronJob'),
    }[kind];
    const note = {
      trigger: paused
        ? t('mobile.jobDetails.runThisJobNowTheServerMayResume')
        : t('mobile.jobDetails.theServerWillRunTheJobPromptImmediately'),
      cancel: t('mobile.jobDetails.stopTheCurrentExecutionAndKeepTheSchedule'),
      remove: t('mobile.jobDetails.theServerWillDeleteThisJobSchedule'),
    }[kind];
    const confirmAction = kind === 'trigger' ? runAction.confirm : confirm;
    confirmAction(
      label,
      t('mobile.jobDetails.profile2', {
        v1: String(title),
        v2: String(owner),
        v3: String(note),
      }),
      async () => {
        const scoped = {path: {job_id: id}, query: {profile: owner}};
        const current = record(await invoke(detailEntry, scoped));
        if (
          jobId(current) !== id ||
          jobOwner(current) !== owner ||
          (kind === 'trigger' &&
            (current.state !== job.state || current.enabled !== job.enabled))
        ) {
          throw new Error(
            t('mobile.jobDetails.theJobStatusOrOwnerChangedRefreshAnd'),
          );
        }
        const result = requireAccepted(await invoke(entry, scoped, true));
        if (kind === 'cancel') {
          if (result.job_id !== id || typeof result.cancelled !== 'boolean') {
            throw new Error(
              t('mobile.jobDetails.theServerDidNotConfirmTheStopRequest'),
            );
          }
          return result.cancelled
            ? t('mobile.jobDetails.theServerAcceptedTheStopRequest')
            : t('mobile.jobDetails.thisJobHasNoActiveRunsToStop');
        } else if (kind === 'remove') {
          if (result.ok !== true)
            throw new Error(
              t('mobile.jobDetails.theServerDidNotConfirmJobDeletion'),
            );
          onBack();
        } else if (jobId(result) !== id) {
          throw new Error(
            t('mobile.jobDetails.theServerDidNotConfirmTheRunRequest'),
          );
        } else if (['error', 'failed'].includes(plain(result.last_status))) {
          throw new Error(
            plain(result.last_error) ||
              t('mobile.jobDetails.theServerReportedThatThisRunFailed'),
          );
        } else if (result.last_status === 'cancelled') {
          return t('mobile.jobDetails.thisRunStopped');
        } else if (result.last_status === 'ok') {
          return t('mobile.jobDetails.thisRunCompleted');
        } else {
          return t('mobile.jobDetails.theServerProcessedTheRunRequestSeeRecent');
        }
      },
    );
  }

  return (
    <View style={styles.screen}>
      <PageHeading
        title={t('mobile.jobDetails.cronJobDetails')}
        onBack={onBack}
        onRefresh={refresh}
        loading={loading || busy || runAction.busy}
      />
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={loading}
            onRefresh={busy ? undefined : refresh}
            colors={[c.accent]}
            tintColor={c.accent}
          />
        }
      >
        <ResourceStatus
          resource={detail}
          label={t('mobile.jobDetails.taskDetails')}
        />
        {!validId && (
          <Notice>{t('mobile.jobDetails.thisJobIsMissingAnIdentifiableId')}</Notice>
        )}
        {validId && !detail.loading && !detail.error && !matches && (
          <Notice>{t('mobile.jobDetails.theJobWasNotFoundInThisProfile')}</Notice>
        )}
        {matches && (
          <>
            <Card>
              <Text
                accessibilityRole="header"
                style={[styles.title, {color: c.ink}]}
              >
                {title}
              </Text>
              <Text style={[styles.caption, {color: c.sub}]}>
                {owner || t('mobile.jobDetails.profileNotReturned')}
              </Text>
              <Text style={[styles.status, {color: c.accent}]}>
                {stateLabel(jobStates, state) ||
                  (paused
                    ? t('commandCenter.maintenance.curatorPaused')
                    : t('mobile.jobDetails.unknownStatus'))}
              </Text>
              <Fact label={t('mobile.jobDetails.jobId')} value={id} />
              <Fact
                label={t('mobile.jobDetails.schedule')}
                value={plain(job.schedule_display) || plain(schedule.display)}
              />
              <Fact
                label={t('mobile.jobDetails.scheduleTimeZone')}
                value={plain(job.timezone)}
              />
              <Fact
                label={t('mobile.jobDetails.nextRun')}
                value={paused ? '' : timestamp(job.next_run_at)}
              />
              <Fact
                label={t('mobile.jobDetails.lastRun')}
                value={[
                  timestamp(job.last_run_at),
                  stateLabel(runStates, job.last_status),
                ]
                  .filter(Boolean)
                  .join(' · ')}
              />
              <Fact
                label={t('mobile.jobDetails.lastRunError')}
                value={plain(job.last_error)}
              />
              <Fact
                label={t('mobile.jobDetails.lastDeliveryError')}
                value={plain(job.last_delivery_error)}
              />
              <Fact
                label={t('mobile.jobDetails.pauseReason')}
                value={plain(job.paused_reason)}
              />
              {!!owner && (
                <View style={styles.actions}>
                  {canManage && route && !route.unavailable && (
                    <Button
                      label={busy ? t('messaging.telegramQr.applying') : action}
                      variant="accent"
                      disabled={busy || runAction.busy || detail.loading}
                      onPress={changeState}
                    />
                  )}
                  {edit && !edit.unavailable && (
                    <Button
                      label={t('composer.queueEdit')}
                      disabled={busy || runAction.busy || detail.loading}
                      onPress={() => onEdit(owner, job)}
                    />
                  )}
                  {trigger && !trigger.unavailable && state !== 'completed' && (
                    <Button
                      label={
                        runAction.busy
                          ? t('mobile.jobDetails.running')
                          : t('commandCenter.maintenance.runNow')
                      }
                      disabled={busy || runAction.busy || detail.loading}
                      onPress={() => operate(trigger, 'trigger')}
                    />
                  )}
                  {cancel && !cancel.unavailable && (
                    <Button
                      label={t('mobile.jobDetails.stopThisRun')}
                      disabled={busy}
                      onPress={() => operate(cancel, 'cancel')}
                    />
                  )}
                  {remove && !remove.unavailable && (
                    <Button
                      label={t('mobile.jobDetails.deleteJob')}
                      disabled={busy || runAction.busy || detail.loading}
                      onPress={() => operate(remove, 'remove')}
                    />
                  )}
                </View>
              )}
              {!!feedback && <Notice>{feedback}</Notice>}
              {!!runAction.feedback && <Notice>{runAction.feedback}</Notice>}
            </Card>
            {!!plain(job.prompt) && (
              <>
                <Heading>{t('mobile.jobDetails.jobPrompt')}</Heading>
                <Card>
                  <Text selectable style={[styles.document, {color: c.ink}]}>
                    {plain(job.prompt)}
                  </Text>
                </Card>
              </>
            )}
            <Heading>{t('mobile.jobDetails.recentRuns')}</Heading>
            <ResourceStatus resource={history} label={t('cron.runHistory')} />
            {!owner && (
              <Notice>{t('mobile.jobDetails.couldNotDetermineTheJobOwner')}</Notice>
            )}
            {!!owner && !history.loading && !history.error && !runs.length && (
              <Notice>{t('mobile.jobDetails.noRecordedRuns')}</Notice>
            )}
            {runs.map((run, index) => (
              <Card key={plain(run.id) || String(index)}>
                <Text style={[styles.itemTitle, {color: c.ink}]}>
                  {plain(run.title) ||
                    stateLabel(runStates, run.status) ||
                    (run.is_active === true
                      ? t('agents.running')
                      : t('mobile.jobDetails.jobRun'))}
                </Text>
                <Text style={[styles.caption, {color: c.sub}]}>
                  {[timestamp(run.started_at), plain(run.model)]
                    .filter(Boolean)
                    .join(' · ') || t('mobile.jobDetails.unknownTime')}
                </Text>
                {!!plain(run.preview) && (
                  <Text selectable style={[styles.document, {color: c.ink}]}>
                    {plain(run.preview)}
                  </Text>
                )}
              </Card>
            ))}
          </>
        )}
      </ScrollView>
    </View>
  );
}
