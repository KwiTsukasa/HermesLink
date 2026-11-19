import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {ListRow} from '../../../shared/ui/ListRow';
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
  detailStyles as styles,
} from '../components/DetailContent';
import {SearchField} from '../components/SearchField';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {DetailScreenProps, OperationInput} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  requireAccepted,
  stringList,
  textValue,
} from '../utils/data';

const sourceLabels = {
  get gateway() {
    return t('shell.gatewayMenu.gateway');
  },
  get errors() {
    return t('common.error');
  },
  agent: 'Agent',
};
type Source = keyof typeof sourceLabels;

export function OpsScreen({
  profile,
  screen,
  byRoute,
  onBack,
  onWrite,
  onOpenLogs,
  onOpenWebhooks,
  onOpenMessaging,
  onOpenGateways,
  onOpenMaintenance,
}: DetailScreenProps & {
  profile?: string;
  screen?: 'logs';
  onOpenLogs: () => void;
  onOpenWebhooks: () => void;
  onOpenMessaging: () => void;
  onOpenGateways: () => void;
  onOpenMaintenance: (screen: 'checkpoints' | 'hooks') => void;
}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [source, setSource] = useState<Source>('gateway');
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [level, setLevel] = useState('ALL');
  const [action, setAction] = useState<BackgroundAction>();
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const input = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const logInput = useMemo(
    () => ({
      query: {
        ...profileQuery(profile),
        file: source,
        lines: 100,
        level,
        ...(query ? {search: query} : {}),
      },
    }),
    [profile, source, level, query],
  );
  const status = useDetailQuery(
    byRoute.get('GET /api/status'),
    input,
    revision,
    screen !== 'logs',
  );
  const logs = useDetailQuery(
    byRoute.get('GET /api/logs'),
    logInput,
    revision,
    screen === 'logs',
  );
  const details = record(status.value);
  const output = stringList(record(logs.value).lines).join('\n');
  const gatewayRunning = details.gateway_running;

  function run(
    label: string,
    key: string,
    parameters: OperationInput,
    explanation?: string,
  ) {
    const entry = byRoute.get(key);
    confirm(
      label,
      [profile || t('mobile.groups.currentGateway'), explanation]
        .filter(Boolean)
        .join('\n'),
      async () => {
        const result = requireAccepted(await invoke(entry, parameters, true));
        if (result.ok !== true)
          throw new Error(t('mobile.ops.theServerDidNotConfirmTheRequest'));
        const current = backgroundAction(result, label);
        if (current) setAction(current);
        return t('mobile.ops.requestAccepted');
      },
    );
  }

  function command(label: string, key: string, parameters = input, detail?: string) {
    const entry = byRoute.get(key);
    return entry && !entry.unavailable ? (
      <Button
        key={key}
        label={label}
        disabled={busy || status.loading}
        onPress={() => {
          setAction(undefined);
          run(label, key, parameters, detail);
        }}
      />
    ) : null;
  }

  if (screen === 'logs')
    return (
      <BusinessPage
        title={t('mobile.ops.serverLogs')}
        onBack={onBack}
        onRefresh={refresh}
        loading={logs.loading}
      >
        <View style={styles.actions}>
          {(Object.keys(sourceLabels) as Source[]).map((key) => (
            <Button
              key={key}
              label={sourceLabels[key]}
              selected={source === key}
              onPress={() => setSource(key)}
            />
          ))}
        </View>
        <SearchField
          placeholder={t('mobile.ops.searchLogs')}
          value={search}
          onChange={(value) => {
            setSearch(value);
            if (!value) setQuery('');
          }}
          clearable
        />
        <View style={styles.actions}>
          <Button
            label={t('mobile.ops.filter')}
            onPress={() => setQuery(search.trim())}
            disabled={logs.loading}
          />
          {[
            ['ALL', t('mobile.ops.allLevels')],
            ['WARNING', t('mobile.ops.warningAndAbove')],
            ['ERROR', t('mobile.ops.errorAndAbove')],
          ].map(([key, label]) => (
            <Button
              key={key}
              label={label}
              selected={level === key}
              onPress={() => setLevel(key)}
            />
          ))}
        </View>
        <ResourceStatus resource={logs} label={t('sidebar.logs')} />
        {output ? (
          <Card>
            <Fact
              label={t('commandCenter.recentLogs')}
              value={output.slice(-24000)}
            />
          </Card>
        ) : !logs.loading && !logs.error ? (
          <Notice>{t('mobile.ops.noMatchingLogs')}</Notice>
        ) : null}
      </BusinessPage>
    );

  return (
    <BusinessPage
      title={t('commandCenter.commandCenter')}
      onBack={onBack}
      onRefresh={refresh}
      loading={status.loading || busy}
    >
      <ResourceStatus resource={status} label={t('mobile.ops.gatewayStatus')} />
      {!!feedback && !action && <Notice>{feedback}</Notice>}
      {action ? (
        <ActionStatus
          key={`${action.name}:${action.pid ?? ''}`}
          action={action}
          byRoute={byRoute}
        />
      ) : null}
      {!status.error && typeof gatewayRunning === 'boolean' ? (
        <Card>
          <Heading>{t('mobile.ops.messagingGateway')}</Heading>
          <Fact
            label={t('mobile.gateways.hermesVersion')}
            value={plain(details.version)}
          />
          <Fact
            label={t('mobile.ops.gatewayStatus')}
            value={
              gatewayRunning
                ? [t('agents.running'), plain(details.gateway_state)]
                    .filter(Boolean)
                    .join(' · ')
                : t('mobile.ops.stopped')
            }
          />
          <Fact
            label={t('mobile.ops.activeAgents')}
            value={textValue(details.active_agents)}
          />
          <Fact
            label={t('mobile.ops.activeChats')}
            value={textValue(details.active_sessions)}
          />
          <Fact
            label={t('mobile.ops.lastExitReason')}
            value={plain(details.gateway_exit_reason)}
          />
          <View style={styles.actions}>
            {!gatewayRunning
              ? command(t('mobile.ops.startGateway'), 'POST /api/gateway/start')
              : null}
            {gatewayRunning
              ? command(
                  t('commandCenter.restartGateway'),
                  'POST /api/gateway/restart',
                  input,
                  t(
                    'mobile.ops.activeChatsAndMessagingServicesMayDisconnectTemporarily',
                  ),
                )
              : null}
            {gatewayRunning
              ? command(
                  t('mobile.ops.stopGateway'),
                  'POST /api/gateway/stop',
                  input,
                  t('mobile.ops.theGatewayWillStopReceivingNewMessages'),
                )
              : null}
          </View>
        </Card>
      ) : null}
      <ListRow
        title={t('mobile.gateways.gatewayConnections')}
        subtitle={t('mobile.ops.checkTopologyConnectionsAndAuthorization')}
        onPress={onOpenGateways}
      />
      <ListRow
        title={t('mobile.ops.serverLogs')}
        subtitle={t('mobile.ops.readLogsBySourceAndLevel')}
        onPress={onOpenLogs}
      />
      <ListRow
        title={t('shell.gatewayMenu.messagingPlatforms')}
        subtitle={t('mobile.ops.viewPlatformConfigurationAndPairingRequests')}
        onPress={onOpenMessaging}
      />
      <ListRow
        title="Webhook"
        subtitle={t('mobile.ops.createAndManageWebhookSubscriptions')}
        onPress={onOpenWebhooks}
      />
      <ListRow
        title={t('settings.subpages.safetyCheckpoints')}
        subtitle={t('mobile.ops.viewCheckpointStorageAndPruneByRetentionPolicy')}
        onPress={() => onOpenMaintenance('checkpoints')}
      />
      <ListRow
        title={t('mobile.maintenance.eventHooks')}
        subtitle={t('mobile.ops.viewExistingHooksMatchersAndAuthorization')}
        onPress={() => onOpenMaintenance('hooks')}
      />
      <Heading>{t('mobile.ops.diagnosticCommands')}</Heading>
      <View style={styles.actions}>
        {command(t('commandCenter.maintenance.doctor'), 'POST /api/ops/doctor')}
        {command(
          t('commandCenter.maintenance.securityAudit'),
          'POST /api/ops/security-audit',
        )}
        {command(t('mobile.ops.measurePromptSize'), 'POST /api/ops/prompt-size')}
      </View>
    </BusinessPage>
  );
}
