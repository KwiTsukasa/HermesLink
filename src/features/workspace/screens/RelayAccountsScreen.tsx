import {t, useI18n} from '../../../shared/i18n';
import {useState} from 'react';

import {Button} from '../../../shared/ui/Button';
import {BusinessPage} from '../components/BusinessPage';
import {
  Card,
  Fact,
  Heading,
  Notice,
  ResourceStatus,
} from '../components/DetailContent';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {DetailScreenProps} from '../types';
import {plainText as plain, record, rows} from '../utils/data';

const emptyInput = {};
const platformNames: Record<string, string> = {
  telegram: 'Telegram',
  discord: 'Discord',
  slack: 'Slack',
};

export function RelayAccountsScreen({
  byRoute,
  onBack,
  onWrite,
  onOpenOfficial,
}: DetailScreenProps & {onOpenOfficial: () => void}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const entry = byRoute.get('GET /api/messaging/relay');
  const status = useDetailQuery(entry, emptyInput, revision);
  const unlink = byRoute.get(
    'DELETE /api/messaging/relay/{platform}/accounts/{platform_user_id}',
  );
  const report = record(status.value);
  const platforms = rows(report.platforms, []);

  function disconnect(platform: string, userId: string, title: string) {
    confirm(
      t('mobile.relayAccounts.unlinkAccount'),
      t('mobile.relayAccounts.accountId', {
        v1: String(platformNames[platform] || platform),
        v2: String(title),
        v3: String(userId),
      }),
      async () => {
        const latest = record(await invoke(entry));
        const current = rows(latest.platforms, []).find(
          (item) => item.platform === platform,
        );
        if (
          latest.managed_by === 'portal' ||
          !rows(current?.accounts, []).some((item) => item.platformUserId === userId)
        )
          throw new Error(
            t('mobile.relayAccounts.theAccountLinkChangedRefreshToReloadIt'),
          );
        const result = record(
          await invoke(unlink, {path: {platform, platform_user_id: userId}}, true),
        );
        if (result.unlinked !== true)
          throw new Error(
            t('mobile.relayAccounts.theServerDidNotConfirmThatTheAccount'),
          );
      },
    );
  }

  return (
    <BusinessPage
      title={t('mobile.messaging.sharedBotAccounts')}
      onBack={onBack}
      onRefresh={refresh}
      loading={status.loading || busy}
    >
      <ResourceStatus
        resource={status}
        label={t('messaging.relayLink.linkedAccounts')}
      />
      {feedback ? <Notice>{feedback}</Notice> : null}
      <Button
        label={t('mobile.relayAccounts.manageLinksInTheWebapp')}
        onPress={onOpenOfficial}
      />
      {report.managed_by === 'portal' ? (
        <Notice>
          {t('mobile.relayAccounts.accountLinksForThisInstanceAreManagedBy')}
        </Notice>
      ) : null}
      {platforms.map((item) => {
        const platform = plain(item.platform);
        if (!platform) return null;
        const accounts = rows(item.accounts, []);
        return (
          <Card key={platform}>
            <Heading>{platformNames[platform] || platform}</Heading>
            {!accounts.length ? (
              <Notice>{t('mobile.relayAccounts.noLinkedAccounts')}</Notice>
            ) : null}
            {accounts.map((account) => {
              const userId = plain(account.platformUserId);
              const name =
                plain(account.displayName) || plain(account.handle) || userId;
              return userId ? (
                <Card key={userId}>
                  <Fact label={name} value={userId} />
                  {report.managed_by !== 'portal' &&
                  unlink &&
                  !unlink.unavailable ? (
                    <Button
                      label={t('mobile.relayAccounts.unlink')}
                      disabled={busy || status.loading}
                      onPress={() => disconnect(platform, userId, name)}
                    />
                  ) : null}
                </Card>
              ) : null;
            })}
          </Card>
        );
      })}
      {!status.loading && !status.error && !platforms.length ? (
        <Notice>
          {t('mobile.relayAccounts.thisGatewayHasNoSharedBotPlatformsConfigured')}
        </Notice>
      ) : null}
    </BusinessPage>
  );
}
