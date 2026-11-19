import {t, useI18n} from '../../../shared/i18n';
import {useEffect, useMemo, useRef, useState} from 'react';

import {Button} from '../../../shared/ui/Button';
import {useDetailQuery} from '../hooks/useDetailQuery';
import type {RouteIndex} from '../types';
import {plainText as plain, record, textValue} from '../utils/data';
import {Card, Fact, Notice, ResourceStatus} from './DetailContent';

export type BackgroundAction = {name: string; pid?: number; label: string};

export function backgroundAction(
  value: unknown,
  label: string,
): BackgroundAction | undefined {
  const outcome = record(value);
  const name = plain(outcome.action) || plain(outcome.name);
  return name && (typeof outcome.pid === 'number' || outcome.background === true)
    ? {name, pid: typeof outcome.pid === 'number' ? outcome.pid : undefined, label}
    : undefined;
}

export function ActionStatus({
  action,
  byRoute,
  onComplete,
}: {
  action: BackgroundAction;
  byRoute: RouteIndex;
  onComplete?: (successful: boolean) => void;
}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const input = useMemo(
    () => ({path: {name: action.name}, query: {lines: 20}}),
    [action.name],
  );
  const status = useDetailQuery(
    byRoute.get('GET /api/actions/{name}/status'),
    input,
    revision,
  );
  const result = record(status.value);
  const same = action.pid === undefined || action.pid === result.pid;
  const running = same && result.running === true;
  const exit = same ? textValue(result.exit_code) : '';
  const settled = useRef(false);
  const logs =
    same && Array.isArray(result.lines)
      ? result.lines.map(plain).filter(Boolean).join('\n')
      : '';

  useEffect(() => {
    if (!running || status.loading) return;
    const timer = setTimeout(() => setRevision((value) => value + 1), 2000);
    return () => clearTimeout(timer);
  }, [running, status.loading]);

  useEffect(() => {
    if (exit && !settled.current) {
      settled.current = true;
      onComplete?.(exit === '0');
    }
  }, [exit, onComplete]);

  return (
    <Card>
      <Fact
        label={action.label}
        value={
          running
            ? t('agents.running')
            : exit === '0'
              ? t('cron.states.completed')
              : exit
                ? t('mobile.actionStatus.exitCode', {v1: String(exit)})
                : t('mobile.actionStatus.submittedToTheServer')
        }
      />
      <ResourceStatus
        label={t('mobile.actionStatus.executionStatus')}
        resource={status}
      />
      {!same && !status.loading && !status.error ? (
        <Notice>
          {t('mobile.actionStatus.theServerReturnedADifferentExecutionRecord')}
        </Notice>
      ) : null}
      {logs ? (
        <Fact
          label={t('mobile.actionStatus.recentOutput')}
          value={logs.slice(-12000)}
        />
      ) : null}
      <Button
        label={t('mobile.actionStatus.refreshExecutionStatus')}
        onPress={refresh}
        disabled={status.loading}
      />
    </Card>
  );
}
