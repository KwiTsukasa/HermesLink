import {formatDate, t, useI18n} from '../../../shared/i18n';
import {useEffect, useMemo, useState} from 'react';
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
import {SearchField} from '../components/SearchField';
import {useDetailQuery} from '../hooks/useDetailQuery';
import type {DetailScreenProps} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  rows,
  textValue,
} from '../utils/data';

const achievements = '/api/plugins/hermes-achievements';

export function AchievementsScreen({
  profile,
  sessionId,
  byRoute,
  onBack,
}: DetailScreenProps & {profile?: string; sessionId?: string}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [filter, setFilter] = useState<'all' | 'unlocked' | 'recent'>('all');
  const [search, setSearch] = useState('');
  const refresh = () => setRevision((value) => value + 1);
  const input = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const sessionInput = useMemo(
    () => ({...input, path: {session_id: sessionId}}),
    [input, sessionId],
  );
  const summary = useDetailQuery(
    byRoute.get(`GET ${achievements}/achievements`),
    input,
    revision,
    !sessionId,
  );
  const scan = useDetailQuery(
    byRoute.get(`GET ${achievements}/scan-status`),
    input,
    revision,
    !sessionId && !summary.error && !!summary.value,
  );
  const recent = useDetailQuery(
    byRoute.get(`GET ${achievements}/recent-unlocks`),
    input,
    revision,
    !sessionId && filter === 'recent' && !summary.error,
  );
  const session = useDetailQuery(
    byRoute.get(`GET ${achievements}/sessions/{session_id}/badges`),
    sessionInput,
    revision,
    !!sessionId,
  );
  const report = record(summary.value);
  const scanState =
    plain(record(scan.value).state) ||
    plain(record(record(report.scan_meta).status).state);
  const polling =
    !sessionId &&
    (scanState === 'running' ||
      (scanState === 'idle' && record(report.scan_meta).mode === 'pending')) &&
    !summary.loading &&
    !scan.loading &&
    !scan.error;

  useEffect(() => {
    if (!polling) return;
    const timer = setTimeout(() => setRevision((value) => value + 1), 3000);
    return () => clearTimeout(timer);
  }, [polling]);

  const source = sessionId ? session : filter === 'recent' ? recent : summary;
  const sourceItems = sessionId
    ? record(session.value).session_id === sessionId
      ? rows(session.value, ['badges'])
      : []
    : rows(source.value, ['achievements']);
  const query = search.trim().toLowerCase();
  const items = sourceItems.filter(
    (item) =>
      (filter !== 'unlocked' || item.unlocked === true) &&
      `${plain(item.name)} ${plain(item.description)}`.toLowerCase().includes(query),
  );

  return (
    <BusinessPage
      title={
        sessionId
          ? t('mobile.achievements.sessionBadges')
          : t('mobile.achievements.achievements')
      }
      onBack={onBack}
      onRefresh={refresh}
      loading={source.loading || summary.loading || scan.loading}
    >
      {!sessionId ? (
        <>
          {filter !== 'all' ? (
            <ResourceStatus
              resource={summary}
              label={t('mobile.achievements.achievementSummary')}
            />
          ) : null}
          <Card>
            <Fact
              label={t('settings.vault.sources.statusUnlocked')}
              value={
                typeof report.unlocked_count === 'number' &&
                typeof report.total_count === 'number'
                  ? `${report.unlocked_count} / ${report.total_count}`
                  : ''
              }
            />
            {scanState === 'running' ? (
              <Notice>
                {t('mobile.achievements.theServerIsScanningSessionHistory')}
              </Notice>
            ) : null}
            {scanState === 'failed' ? (
              <Fact
                label={t('mobile.achievements.scanFailed')}
                value={
                  plain(record(scan.value).last_error) ||
                  t('mobile.achievements.refreshAndTryAgain')
                }
              />
            ) : null}
            <View style={styles.actions}>
              {(
                [
                  ['all', t('artifacts.tabAll')],
                  ['unlocked', t('settings.vault.sources.statusUnlocked')],
                  ['recent', t('mobile.achievements.recentUnlocks')],
                ] as const
              ).map(([key, label]) => (
                <Button
                  key={key}
                  label={label}
                  selected={filter === key}
                  onPress={() => setFilter(key)}
                />
              ))}
            </View>
          </Card>
          <SearchField
            placeholder={t('mobile.achievements.searchAchievements')}
            value={search}
            onChange={setSearch}
            clearable
          />
        </>
      ) : null}
      <ResourceStatus
        resource={source}
        label={
          sessionId
            ? t('mobile.achievements.sessionBadges')
            : t('mobile.achievements.achievements')
        }
      />
      {items.map((item, index) => (
        <Card key={plain(item.id) || String(index)}>
          <Heading>
            {item.state === 'secret'
              ? t('mobile.achievements.secretAchievement')
              : plain(item.name)}
          </Heading>
          <Fact
            label={t('sidebar.filter.status')}
            value={
              item.unlocked === true
                ? t('settings.vault.sources.statusUnlocked')
                : item.state === 'secret'
                  ? t('mobile.achievements.undiscovered')
                  : t('mobile.achievements.locked')
            }
          />
          {item.state !== 'secret' ? (
            <>
              <Fact
                label={t('mobile.achievements.description')}
                value={plain(item.description)}
              />
              <Fact
                label={t('mobile.achievements.tier')}
                value={plain(record(item.tier).name) || plain(item.tier)}
              />
              <Fact
                label={t('mobile.achievements.nextTier')}
                value={plain(item.next_tier)}
              />
              <Fact
                label={t('mobile.achievements.progress')}
                value={
                  typeof item.progress_pct === 'number'
                    ? `${Math.max(0, Math.min(100, item.progress_pct))}%`
                    : textValue(item.progress)
                }
              />
              <Fact
                label={t('mobile.achievements.unlockedAt')}
                value={
                  typeof item.unlocked_at === 'number' && item.unlocked_at > 0
                    ? formatDate(item.unlocked_at * 1000)
                    : ''
                }
              />
            </>
          ) : null}
        </Card>
      ))}
      {!items.length && !source.loading && !source.error && !summary.error ? (
        <Notice>
          {sessionId
            ? t('mobile.achievements.thisSessionHasNoBadgesYet')
            : t('mobile.achievements.noMatchingAchievements')}
        </Notice>
      ) : null}
    </BusinessPage>
  );
}
