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
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {DetailScreenProps, OperationInput} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  requireAccepted,
  rows,
  stringList,
} from '../utils/data';

const hookRoute = '/api/webhooks/{name}';

export function WebhooksScreen({
  profile,
  name,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
  onOpenHook,
}: DetailScreenProps & {
  profile?: string;
  name?: string;
  onOpenHook: (name: string) => void;
}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const input = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const list = byRoute.get('GET /api/webhooks');
  const result = useDetailQuery(list, input, revision);
  const subscriptions = rows(result.value, ['subscriptions']);
  const detail = subscriptions.find((item) => item.name === name);
  const platformEnabled = record(result.value).enabled;
  const create = byRoute.get('POST /api/webhooks');
  const enable = byRoute.get('POST /api/webhooks/enable');
  const toggle = byRoute.get(`PUT ${hookRoute}/enabled`);
  const remove = byRoute.get(`DELETE ${hookRoute}`);

  function newWebhook() {
    if (!create || create.unavailable) return;
    onOpenForm({
      title: t('mobile.webhooks.newWebhook'),
      action: t('mobile.webhooks.createSubscription'),
      detail: t('mobile.webhooks.profileSaveTheSigningSecretInYourSender', {
        v1: String(profile || t('mobile.groups.currentGateway')),
      }),
      entry: create,
      fields: [
        {
          key: 'name',
          label: t('mobile.webhooks.subscriptionName'),
          placeholder: t('mobile.webhooks.forExampleBuildMonitor'),
          required: true,
        },
        {key: 'description', label: t('mobile.achievements.description')},
        {
          key: 'events',
          label: t('mobile.webhooks.eventsCommaSeparated'),
          placeholder: t('mobile.webhooks.leaveBlankToReceiveAllEvents'),
        },
        {
          key: 'prompt',
          label: t('mobile.webhooks.promptToRunWhenAnEventArrives'),
          multiline: true,
          required: true,
        },
        {
          key: 'secret',
          label: t('mobile.webhooks.signingSecret'),
          secret: true,
          required: true,
        },
      ],
      build: (values) => {
        const id = values.name.trim().toLowerCase();
        if (!/^[a-z0-9][a-z0-9_-]*$/.test(id))
          throw new Error(
            t('mobile.webhooks.useLowercaseLettersDigitsHyphensAndUnderscoresFor'),
          );
        return {
          ...input,
          body: {
            name: id,
            description: values.description,
            events: values.events
              .split(/[,，\r\n]/)
              .map((event) => event.trim())
              .filter(Boolean),
            prompt: values.prompt,
            secret: values.secret,
            deliver: 'log',
          },
        };
      },
      beforeSubmit: async (parameters) => {
        const latest = await invoke(list, input);
        if (
          rows(latest, ['subscriptions']).some(
            (item) => item.name === parameters.body?.name,
          )
        ) {
          throw new Error(
            t('mobile.webhooks.thatSubscriptionNameAlreadyExistsChooseADifferent'),
          );
        }
      },
      onSuccess: (value) => {
        if (!plain(record(value).name))
          throw new Error(
            t('mobile.webhooks.theServerDidNotConfirmSubscriptionCreation'),
          );
      },
    });
  }

  function act(kind: 'enable' | 'toggle' | 'remove') {
    const next = detail?.enabled !== true;
    const title = {
      enable: t('mobile.webhooks.enableWebhookPlatform'),
      toggle: next
        ? t('mobile.webhooks.enableSubscription')
        : t('mobile.webhooks.disableSubscription'),
      remove: t('mobile.webhooks.deleteSubscription'),
    }[kind];
    const entry = {enable, toggle, remove}[kind];
    const parameters: OperationInput =
      kind === 'enable'
        ? input
        : {
            ...input,
            path: {name},
            body: kind === 'toggle' ? {enabled: next} : undefined,
          };
    confirm(
      title,
      [
        name,
        t('mobile.groups.profile', {
          v1: String(profile || t('mobile.groups.currentGateway')),
        }),
        kind === 'enable'
          ? t('mobile.webhooks.theServerMayRestartTheMessagingGateway')
          : '',
      ]
        .filter(Boolean)
        .join('\n'),
      async () => {
        let target = parameters;
        if (kind === 'remove' && !profile) {
          const current = plain(
            record(await invoke(byRoute.get('GET /api/profiles/active'))).current,
          );
          if (!current)
            throw new Error(
              t('mobile.webhooks.theServerDidNotReturnTheCurrentProfile'),
            );
          target = {...parameters, query: {profile: current}};
        }
        const confirmation = requireAccepted(await invoke(entry, target, true));
        if (
          confirmation.ok !== true ||
          (kind === 'toggle' && confirmation.enabled !== next)
        )
          throw new Error(
            t('mobile.webhooks.theServerDidNotConfirmTheWebhookAction'),
          );
        if (kind === 'remove') onBack();
      },
    );
  }

  return (
    <BusinessPage
      title={name ? t('mobile.webhooks.webhookSubscriptionDetails') : 'Webhook'}
      onBack={onBack}
      onRefresh={refresh}
      loading={result.loading || busy}
    >
      <ResourceStatus resource={result} label="Webhook" />
      {!!feedback && <Notice>{feedback}</Notice>}
      {name ? (
        detail ? (
          <Card>
            <Heading>{name}</Heading>
            <Fact
              label={t('sidebar.filter.status')}
              value={
                detail.enabled === true
                  ? t('messaging.enabled')
                  : t('mobile.mcp.disabled')
              }
            />
            <Fact
              label={t('mobile.achievements.description')}
              value={plain(detail.description)}
            />
            <Fact
              label={t('mobile.webhooks.receivingUrl')}
              value={plain(detail.url)}
            />
            <Fact
              label={t('webhooks.fieldEvents')}
              value={
                stringList(detail.events).join('、') ||
                t('mobile.webhooks.allEvents')
              }
            />
            <Fact
              label={t('mobile.webhooks.gatewayPrompt')}
              value={plain(detail.prompt)}
            />
            <Fact
              label={t('mobile.webhooks.delivery')}
              value={plain(detail.deliver)}
            />
            <Fact
              label={t('webhooks.fieldSignature')}
              value={
                detail.secret_set === true
                  ? plain(detail.signature) || t('mobile.webhooks.secretConfigured')
                  : t('mobile.webhooks.noSecretConfigured')
              }
            />
            <View style={styles.actions}>
              {toggle && !toggle.unavailable ? (
                <Button
                  label={
                    detail.enabled === true
                      ? t('mobile.webhooks.disableSubscription')
                      : t('mobile.webhooks.enableSubscription')
                  }
                  disabled={busy || result.loading}
                  onPress={() => act('toggle')}
                />
              ) : null}
              {remove && !remove.unavailable ? (
                <Button
                  label={t('mobile.webhooks.deleteSubscription')}
                  disabled={busy || result.loading}
                  onPress={() => act('remove')}
                />
              ) : null}
            </View>
          </Card>
        ) : !result.loading && !result.error ? (
          <Notice>{t('mobile.webhooks.thisSubscriptionNoLongerExists')}</Notice>
        ) : null
      ) : (
        <>
          {platformEnabled === true || platformEnabled === false ? (
            <Card>
              <Fact
                label={t('mobile.webhooks.webhookPlatform')}
                value={
                  platformEnabled
                    ? t('messaging.enabled')
                    : t('mobile.knowledge.disabled')
                }
              />
              <View style={styles.actions}>
                {!platformEnabled && enable && !enable.unavailable ? (
                  <Button
                    label={t('mobile.messaging.enablePlatform')}
                    variant="accent"
                    disabled={busy}
                    onPress={() => act('enable')}
                  />
                ) : null}
                {platformEnabled && create && !create.unavailable ? (
                  <Button
                    label={t('webhooks.newSubscription')}
                    variant="accent"
                    disabled={busy}
                    onPress={newWebhook}
                  />
                ) : null}
              </View>
            </Card>
          ) : null}
          {subscriptions.map((item) => {
            const key = plain(item.name);
            return key ? (
              <ListRow
                key={key}
                title={key}
                subtitle={[
                  item.enabled === true
                    ? t('messaging.enabled')
                    : t('mobile.mcp.disabled'),
                  plain(item.description),
                ]
                  .filter(Boolean)
                  .join(' · ')}
                onPress={() => onOpenHook(key)}
              />
            ) : null;
          })}
          {!subscriptions.length && !result.loading && !result.error ? (
            <Notice>{t('mobile.webhooks.noWebhookSubscriptions')}</Notice>
          ) : null}
        </>
      )}
    </BusinessPage>
  );
}
