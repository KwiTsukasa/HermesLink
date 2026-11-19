import {t, useI18n} from '../../../shared/i18n';
import {useState} from 'react';
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
import {useDetailQuery} from '../hooks/useDetailQuery';
import {gatewayRoute} from '../services/workspaceApi';
import type {DetailScreenProps} from '../types';
import {
  plainText as plain,
  record,
  rows,
  stringList,
  textValue,
} from '../utils/data';

const empty = {};
const modes: Record<string, string> = {
  get multiplex() {
    return t('mobile.gateways.sharedGateway');
  },
  get single() {
    return t('mobile.gateways.singleGateway');
  },
  get multiple() {
    return t('mobile.gateways.multipleIndependentGateways');
  },
  get none() {
    return t('mobile.gateways.noRunningGateways');
  },
};

export function GatewaysScreen({
  byRoute,
  onBack,
  onBrowser,
  onOpenProfile,
}: DetailScreenProps & {
  onBrowser: () => void;
  onOpenProfile: (name: string) => void;
}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [checking, setChecking] = useState(false);
  const [showPlan, setShowPlan] = useState(false);
  const refresh = () => setRevision((value) => value + 1);
  const pingEntry = gatewayRoute(byRoute, 'gateway.ping');
  const capsEntry = gatewayRoute(byRoute, 'gateway.capabilities');
  const registryEntry = byRoute.get('GET /api/gateway-connections');
  const status = useDetailQuery(byRoute.get('GET /api/status'), empty, revision);
  const canRegistry = !!registryEntry && !registryEntry.unavailable;
  const registry = useDetailQuery(registryEntry, empty, revision, canRegistry);
  const ping = useDetailQuery(pingEntry, empty, revision, checking);
  const caps = useDetailQuery(
    capsEntry,
    empty,
    revision,
    checking && !!capsEntry && !capsEntry.unavailable,
  );
  const planEntry = byRoute.get('GET /api/gateway/migrate/plan');
  const plan = useDetailQuery(planEntry, empty, revision, showPlan);
  const topology = record(status.value);
  const configured = record(registry.value);
  const migration = record(plan.value);
  const discovered = rows(topology.gateways, []);
  const parked = stringList(topology.parked_profiles);
  const profiles = stringList(topology.profiles);
  const gatewayMode =
    modes[plain(topology.gateway_mode)] || plain(topology.gateway_mode);

  return (
    <BusinessPage
      title={t('mobile.gateways.gatewayConnections')}
      onBack={onBack}
      onRefresh={refresh}
      loading={
        status.loading ||
        registry.loading ||
        ping.loading ||
        caps.loading ||
        plan.loading
      }
    >
      <ResourceStatus resource={status} label={t('mobile.gateways.hostGateway')} />
      <Card>
        <Heading>{t('mobile.gateways.currentService')}</Heading>
        <Fact
          label={t('mobile.gateways.hermesVersion')}
          value={plain(topology.version)}
        />
        <Fact label={t('mobile.gateways.gatewayMode')} value={gatewayMode} />
        <Fact
          label={t('mobile.gateways.parkedProfiles')}
          value={parked.join('、')}
        />
        <Fact
          label={t('mobile.gateways.visibleProfiles')}
          value={profiles.join('、')}
        />
        <View style={styles.actions}>
          {pingEntry && !pingEntry.unavailable ? (
            <Button
              label={t('mobile.gateways.checkCurrentConnection')}
              variant="accent"
              disabled={ping.loading}
              onPress={() => {
                if (checking) refresh();
                else setChecking(true);
              }}
            />
          ) : null}
          <Button
            label={t('mobile.gateways.manageGatewaysAndAuthorizationInTheWebapp')}
            onPress={onBrowser}
          />
        </View>
        {checking ? (
          <>
            <ResourceStatus
              resource={ping}
              label={t('mobile.gateways.gatewayConnections')}
            />
            {record(ping.value).ok === true && !ping.loading ? (
              <Fact
                label={t('mobile.gateways.gatewayConnections')}
                value={t('mobile.gateways.gatewayRespondedToTheHeartbeat')}
              />
            ) : null}
            <ResourceStatus
              resource={caps}
              label={t('mobile.gateways.gatewayCapabilities')}
            />
            {typeof record(caps.value).per_session_exclusive_submit === 'boolean' ? (
              <Fact
                label={t('mobile.gateways.sessionSubmission')}
                value={
                  record(caps.value).per_session_exclusive_submit
                    ? t(
                        'mobile.gateways.theGatewayLimitsConcurrentSubmissionsToOnePer',
                      )
                    : t('mobile.gateways.exclusiveSubmissionIsDisabled')
                }
              />
            ) : null}
          </>
        ) : null}
      </Card>
      {discovered.length > 0 ? (
        <Heading>{t('mobile.gateways.runningGateways')}</Heading>
      ) : null}
      {discovered.map((item) => {
        const name = plain(item.profile);
        const served = stringList(item.served_profiles);
        const ports = Object.entries(record(item.ports)).map(([platform, port]) =>
          [platform, textValue(port)].filter(Boolean).join(' '),
        );
        return name ? (
          <Card key={name}>
            <ListRow
              title={name}
              subtitle={t('mobile.gateways.gatewayProfile')}
              onPress={() => onOpenProfile(name)}
            />
            <Fact
              label={t('mobile.gateways.servedProfiles')}
              value={served.join('、')}
            />
            <Fact
              label={t('mobile.gateways.platformPorts')}
              value={ports.join(' · ')}
            />
          </Card>
        ) : null;
      })}
      {canRegistry ? (
        <>
          <Heading>{t('settings.subpages.gatewayDevices')}</Heading>
          <ResourceStatus
            resource={registry}
            label={t('mobile.gateways.savedConnections2')}
          />
          {rows(configured.connections, []).map((item) => {
            const id = plain(item.id);
            return id ? (
              <Card key={id}>
                <Heading>{plain(item.label) || id}</Heading>
                <Fact label={t('mobile.gateways.type')} value={plain(item.kind)} />
                <Fact
                  label={t('sidebar.filter.status')}
                  value={
                    configured.primary === id
                      ? t('mobile.gateways.primaryConnection')
                      : ''
                  }
                />
                <Fact label={t('preview.web.address')} value={plain(item.url)} />
              </Card>
            ) : null;
          })}
          {!registry.loading &&
          !registry.error &&
          !rows(configured.connections, []).length ? (
            <Notice>{t('mobile.gateways.noSavedConnections')}</Notice>
          ) : null}
        </>
      ) : null}
      {planEntry && !planEntry.unavailable ? (
        <Button
          label={
            showPlan
              ? t('mobile.gateways.hideMigrationCheck')
              : t('mobile.gateways.checkGatewayMigration')
          }
          onPress={() => setShowPlan(!showPlan)}
        />
      ) : null}
      {showPlan ? (
        <>
          <ResourceStatus
            resource={plan}
            label={t('mobile.gateways.gatewayMigrationEligibility')}
          />
          {!plan.loading && !plan.error ? (
            <Card>
              <Fact
                label={t('mobile.gateways.checkResult')}
                value={
                  migration.already_multiplexed === true
                    ? t('mobile.gateways.alreadyUsingASharedGateway')
                    : migration.eligible === true
                      ? t('mobile.gateways.currentIndependentGatewaysCanBeMerged')
                      : t('mobile.gateways.automaticMigrationRequirementsAreUnmet')
                }
              />
              <Fact
                label={t('mobile.gateways.participatingProfiles')}
                value={rows(migration.profiles, [])
                  .map((item) => plain(item.profile))
                  .filter(Boolean)
                  .join('、')}
              />
              <Fact
                label={t('mobile.gateways.blockers')}
                value={stringList(migration.blockers).join('\n')}
              />
              <Fact
                label={t('mobile.gateways.serverDetails')}
                value={stringList(migration.notices).join('\n')}
              />
            </Card>
          ) : null}
        </>
      ) : null}
    </BusinessPage>
  );
}
