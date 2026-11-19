import {t, useI18n} from '../../../shared/i18n';
import {useMemo} from 'react';

import {BusinessPage} from '../components/BusinessPage';
import {Notice, ResourceStatus} from '../components/DetailContent';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {BusinessField, DetailScreenProps, Dict} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  rows,
  textValue,
} from '../utils/data';
import {BusinessFormScreen} from './BusinessFormScreen';

export const jobRoute = '/api/cron/jobs/{job_id}';

function scheduleText(job: Dict): string {
  const schedule = record(job.schedule);
  if (schedule.kind === 'cron') return plain(schedule.expr);
  if (schedule.kind === 'interval' && typeof schedule.minutes === 'number')
    return `every ${schedule.minutes}m`;
  if (schedule.kind === 'once') return plain(schedule.run_at);
  return plain(job.schedule);
}

export function JobEditorScreen({
  byRoute,
  onBack,
  onWrite,
  profile,
  job,
}: DetailScreenProps & {profile?: string; job?: Dict}) {
  useI18n();
  const input = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const delivery = useDetailQuery(
    byRoute.get('GET /api/cron/delivery-targets'),
    input,
    0,
  );
  const create = !job;
  const id = plain(job?.id);
  const entry = byRoute.get(create ? 'POST /api/cron/jobs' : `PUT ${jobRoute}`);
  const title = create ? t('cron.createTitle') : t('cron.edit');
  if (!entry || entry.unavailable) {
    return (
      <BusinessPage title={title} onBack={onBack}>
        <Notice>
          {entry?.unavailable ||
            t('mobile.jobEditor.thisGatewayDoesNotOfferThisOperation')}
        </Notice>
      </BusinessPage>
    );
  }
  if (delivery.loading) {
    return (
      <BusinessPage title={title} onBack={onBack}>
        <ResourceStatus
          resource={delivery}
          label={t('mobile.jobEditor.deliveryTargets')}
        />
      </BusinessPage>
    );
  }
  const currentDelivery = plain(job?.deliver) || 'local';
  const choices = new Map([['local', t('mobile.jobEditor.saveOnServerOnly')]]);
  if (job && currentDelivery !== 'local')
    choices.set(
      currentDelivery,
      t('mobile.jobEditor.keepCurrent', {v1: String(currentDelivery)}),
    );
  for (const target of rows(delivery.value, ['targets'])) {
    const value = plain(target.id);
    if (value && !choices.has(value) && target.home_target_set === true) {
      choices.set(value, plain(target.name) || value);
    }
  }
  const fields: BusinessField[] = [
    {
      key: 'name',
      label: t('mobile.jobEditor.jobName'),
      initial: plain(job?.name),
      required: true,
    },
    {
      key: 'schedule',
      label: t('mobile.jobDetails.schedule'),
      initial: scheduleText(job ?? {}),
      required: true,
      placeholder: '0 9 * * * / every 30m / 2026-11-20T09:00:00',
    },
    {
      key: 'timezone',
      label: t('mobile.jobEditor.ianaTimeZone'),
      initial: plain(job?.timezone),
      placeholder: t('mobile.jobEditor.leaveBlankToUseTheProfileTimeZone'),
    },
    {
      key: 'deliver',
      label: t('webhooks.fieldDeliver'),
      initial: currentDelivery,
      choices: Array.from(choices, ([value, label]) => ({value, label})),
    },
  ];
  if (job?.no_agent !== true) {
    fields.push({
      key: 'prompt',
      label: t('mobile.jobDetails.jobPrompt'),
      initial: typeof job?.prompt === 'string' ? job.prompt : '',
      required: create,
      multiline: true,
    });
  }
  if (create) {
    fields.push({
      key: 'paused',
      label: t('mobile.jobEditor.initialStatus'),
      initial: 'paused',
      choices: [
        {value: 'paused', label: t('mobile.jobEditor.startPaused')},
        {value: 'enabled', label: t('mobile.jobEditor.enableAfterCreation')},
      ],
    });
  }
  return (
    <BusinessFormScreen
      form={{
        title,
        action: create
          ? t('mobile.jobEditor.createJob')
          : t('mobile.jobEditor.saveJob'),
        detail: t('mobile.groups.profile', {
          v1: String(profile || t('mobile.groups.currentGateway')),
        }),
        fields,
        entry,
        build: ({name, schedule, timezone, deliver, prompt, paused}) => {
          const updates: Dict = {
            name: name.trim(),
            schedule: schedule.trim(),
            deliver,
          };
          if (create)
            return {
              ...input,
              body: {
                ...updates,
                prompt,
                paused: paused === 'paused',
                ...(timezone.trim() ? {timezone: timezone.trim()} : {}),
              },
            };
          const previous = {
            name: plain(job.name),
            schedule: scheduleText(job),
            deliver: currentDelivery,
          };
          for (const key of Object.keys(previous) as Array<keyof typeof previous>) {
            if (updates[key] === previous[key]) delete updates[key];
          }
          if (timezone.trim() !== plain(job.timezone))
            updates.timezone = timezone.trim() || null;
          if (prompt !== undefined && prompt !== textValue(job.prompt))
            updates.prompt = prompt;
          if (!Object.keys(updates).length)
            throw new Error(t('mobile.jobEditor.noChangesToThisJob'));
          return {...input, path: {job_id: id}, body: {updates}};
        },
        beforeSubmit: job
          ? async (parameters) => {
              const current = record(
                await invoke(byRoute.get(`GET ${jobRoute}`), {
                  ...input,
                  path: {job_id: id},
                }),
              );
              const updates = record(parameters.body?.updates);
              const changed = Object.keys(updates).some((key) =>
                key === 'schedule'
                  ? scheduleText(current) !== scheduleText(job)
                  : current[key] !== job[key],
              );
              if (
                current.id !== id ||
                (current.profile !== profile && current.profile_name !== profile) ||
                changed
              ) {
                throw new Error(
                  t('mobile.jobEditor.theFieldsYouEditedHaveChangedGoBack'),
                );
              }
            }
          : undefined,
        onSuccess: (value) => {
          const result = record(value);
          if (
            !plain(result.id) ||
            (id && result.id !== id) ||
            (profile &&
              result.profile !== profile &&
              result.profile_name !== profile)
          ) {
            throw new Error(
              t('mobile.jobEditor.theServerDidNotConfirmTheSelectedCron'),
            );
          }
        },
      }}
      onBack={onBack}
      onWrite={onWrite}
    />
  );
}
