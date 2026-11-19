import {hasTranslation, t, useI18n} from '../../../shared/i18n';
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
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {DetailScreenProps, Dict, OperationInput} from '../types';
import {
  plainText as plain,
  profileQuery,
  requireAccepted,
  rows,
  textValue,
} from '../utils/data';

const platformRoute = '/api/messaging/platforms/{platform_id}';

function settingLabel(setting: Dict): string {
  const key = `messaging.fieldCopy.${plain(setting.key)}.label`;
  return hasTranslation(key) ? t(key) : plain(setting.prompt) || plain(setting.key);
}

function platformStatus(detail: Dict): string {
  if (detail.enabled !== true) return t('messaging.states.disabled');
  const state = plain(detail.state);
  const key = `messaging.states.${state}`;
  return state && hasTranslation(key) ? t(key) : state || t('messaging.enabled');
}

export function MessagingScreen({
  profile,
  id,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
  onOpenPlatform,
  onOpenRelay,
}: DetailScreenProps & {
  profile?: string;
  id?: string;
  onOpenPlatform: (id: string) => void;
  onOpenRelay: () => void;
}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [tested, setTested] = useState('');
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const scope = useMemo(() => profileQuery(profile), [profile]);
  const input = useMemo(() => ({query: scope}), [scope]);
  const platforms = useDetailQuery(
    byRoute.get('GET /api/messaging/platforms'),
    input,
    revision,
  );
  const pairing = useDetailQuery(
    byRoute.get('GET /api/pairing'),
    input,
    revision,
    !!id,
  );
  const items = rows(platforms.value, ['platforms']);
  const detail = items.find((item) => item.id === id);
  const name = plain(detail?.name) || id || '';
  const toggle = byRoute.get(`PUT ${platformRoute}`);
  const test = byRoute.get(`POST ${platformRoute}/test`);
  const settings = rows(detail?.env_vars, []);

  function editSetting(setting: Dict) {
    const key = plain(setting.key);
    if (!toggle || toggle.unavailable || !key || !id) return;
    const label = settingLabel(setting);
    onOpenForm({
      title: t('mobile.messaging.configurePlatform'),
      action: t('mobile.messaging.saveSettings'),
      detail: t('mobile.jobDetails.profile', {
        v1: String(name),
        v2: String(profile || t('mobile.groups.currentGateway')),
      }),
      entry: toggle,
      fields: [
        {
          key: 'value',
          label,
          secret: setting.is_password === true,
          required: true,
          placeholder: plain(setting.help) || key,
        },
      ],
      build: ({value}) => ({
        ...input,
        path: {platform_id: id},
        body: {env: {[key]: value.trim()}},
      }),
      onSuccess: (value) => {
        const result = requireAccepted(value);
        if (result.ok !== true || result.platform !== id)
          throw new Error(
            t('mobile.messaging.theServerDidNotConfirmThePlatformConfiguration'),
          );
      },
    });
  }

  function action(
    label: string,
    entry: typeof toggle,
    inputForAction: OperationInput,
    target = name,
  ) {
    confirm(
      label,
      t('mobile.jobDetails.profile', {
        v1: String(target),
        v2: String(profile || t('mobile.groups.currentGateway')),
      }),
      async () => {
        const result = requireAccepted(await invoke(entry, inputForAction, true));
        if (entry === test) {
          if (result.ok !== true)
            throw new Error(
              plain(result.detail) ||
                t('mobile.messaging.thePlatformDidNotConfirmASuccessfulConnection'),
            );
          setTested(
            plain(result.message) ||
              plain(result.detail) ||
              t('mobile.messaging.platformConnectionTestPassed'),
          );
        } else if (
          result.ok !== true ||
          (entry === toggle && result.platform !== id)
        ) {
          throw new Error(
            t('mobile.messaging.theServerDidNotConfirmThePlatformAction'),
          );
        }
      },
    );
  }

  function pairingRow(item: Dict, pending: boolean, index: number) {
    const user = textValue(item.user_id);
    const request = plain(item.request_id);
    const title = plain(item.user_name) || user;
    const entry = byRoute.get(
      pending ? 'POST /api/pairing/approve' : 'POST /api/pairing/revoke',
    );
    return (
      <Card key={request || `${user}:${index}`}>
        <Fact
          label={
            pending
              ? t('mobile.messaging.pairingRequest')
              : t('mobile.messaging.authorizedAccount')
          }
          value={title}
        />
        <Fact label={t('mobile.messaging.platformAccountId')} value={user} />
        {entry && !entry.unavailable && (pending ? !!request : !!user) ? (
          <Button
            label={
              pending
                ? t('mobile.messaging.approvePairing')
                : t('mobile.messaging.revokeAuthorization')
            }
            disabled={busy || pairing.loading}
            onPress={() =>
              action(
                pending
                  ? t('mobile.messaging.approvePairing')
                  : t('mobile.messaging.revokeAuthorization'),
                entry,
                {
                  body: {
                    ...scope,
                    platform: id,
                    ...(pending ? {request_id: request} : {user_id: user}),
                  },
                },
                `${name} · ${title} (${user})`,
              )
            }
          />
        ) : null}
      </Card>
    );
  }

  const pending = rows(pairing.value, ['pending']).filter(
    (item) => item.platform === id,
  );
  const approved = rows(pairing.value, ['approved']).filter(
    (item) => item.platform === id,
  );
  return (
    <BusinessPage
      title={
        id
          ? t('mobile.messaging.platformDetails')
          : t('shell.gatewayMenu.messagingPlatforms')
      }
      onBack={onBack}
      onRefresh={refresh}
      loading={platforms.loading || pairing.loading || busy}
    >
      <ResourceStatus
        resource={platforms}
        label={t('shell.gatewayMenu.messagingPlatforms')}
      />
      {!!feedback && <Notice>{feedback}</Notice>}
      {id ? (
        detail ? (
          <>
            <Card>
              <Heading>{name}</Heading>
              <Fact
                label={t('mobile.messaging.platformStatus')}
                value={platformStatus(detail)}
              />
              <Fact
                label={t('mobile.messaging.configurationStatus')}
                value={
                  detail.configured === true
                    ? t('skills.configured')
                    : t('mobile.messaging.setupRequired')
                }
              />
              <Fact
                label={t('mobile.messaging.defaultDeliveryChannel')}
                value={plain(detail.home_channel)}
              />
              <Fact label={t('common.error')} value={plain(detail.error_message)} />
              <Fact label={t('mobile.messaging.testResult')} value={tested} />
              <View style={styles.actions}>
                {test && !test.unavailable && detail.configured === true ? (
                  <Button
                    label={t('mobile.messaging.testPlatformConnection')}
                    disabled={busy || platforms.loading}
                    onPress={() => {
                      setTested('');
                      action(t('mobile.messaging.testPlatformConnection'), test, {
                        ...input,
                        path: {platform_id: id},
                      });
                    }}
                  />
                ) : null}
                {toggle &&
                !toggle.unavailable &&
                typeof detail.enabled === 'boolean' ? (
                  <Button
                    label={
                      detail.enabled
                        ? t('mobile.messaging.disablePlatform')
                        : t('mobile.messaging.enablePlatform')
                    }
                    disabled={busy || platforms.loading}
                    onPress={() =>
                      action(
                        detail.enabled
                          ? t('mobile.messaging.disablePlatform')
                          : t('mobile.messaging.enablePlatform'),
                        toggle,
                        {
                          ...input,
                          path: {platform_id: id},
                          body: {enabled: !detail.enabled},
                        },
                      )
                    }
                  />
                ) : null}
              </View>
            </Card>
            {settings.length ? (
              <>
                <Heading>{t('mobile.messaging.connectionSettings')}</Heading>
                {settings.map((setting) => {
                  const key = plain(setting.key);
                  return key ? (
                    <ListRow
                      key={key}
                      title={settingLabel(setting)}
                      subtitle={[
                        setting.required === true ? t('messaging.required') : '',
                        setting.is_set === true
                          ? t('skills.configured')
                          : t('mobile.messaging.setupRequired'),
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                      onPress={
                        toggle && !toggle.unavailable && !busy && !platforms.loading
                          ? () => editSetting(setting)
                          : undefined
                      }
                    />
                  ) : null;
                })}
              </>
            ) : null}
            <Heading>{t('mobile.messaging.pairingAndAuthorization')}</Heading>
            <ResourceStatus
              resource={pairing}
              label={t('mobile.messaging.pairing')}
            />
            {pending.map((item, index) => pairingRow(item, true, index))}
            {approved.map((item, index) => pairingRow(item, false, index))}
            {!pending.length &&
            !approved.length &&
            !pairing.loading &&
            !pairing.error ? (
              <Notice>{t('mobile.messaging.noPendingOrAuthorizedAccounts')}</Notice>
            ) : null}
          </>
        ) : !platforms.loading && !platforms.error ? (
          <Notice>{t('mobile.messaging.thisMessagingPlatformIsUnavailable')}</Notice>
        ) : null
      ) : (
        <>
          <ListRow
            title={t('mobile.messaging.sharedBotAccounts')}
            subtitle={t('mobile.messaging.viewAndManageLinkedTeamGatewayAccounts')}
            onPress={onOpenRelay}
          />
          {items.map((item) => {
            const key = plain(item.id);
            return key ? (
              <ListRow
                key={key}
                title={plain(item.name) || key}
                subtitle={[
                  item.enabled === true
                    ? t('messaging.enabled')
                    : t('mobile.mcp.disabled'),
                  item.configured === true
                    ? t('skills.configured')
                    : t('mobile.messaging.setupRequired'),
                ].join(' · ')}
                onPress={() => onOpenPlatform(key)}
              />
            ) : null;
          })}
          {!items.length && !platforms.loading && !platforms.error ? (
            <Notice>{t('mobile.messaging.noMessagingPlatformsAreAvailable')}</Notice>
          ) : null}
        </>
      )}
    </BusinessPage>
  );
}
