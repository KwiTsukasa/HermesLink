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
import {SearchField} from '../components/SearchField';
import {useDetailQuery} from '../hooks/useDetailQuery';
import type {DetailScreenProps} from '../types';
import {plainText as plain, profileQuery, record, rows} from '../utils/data';

export function KnowledgeScreen({
  profile,
  byRoute,
  onBack,
  onOpenNode,
}: DetailScreenProps & {profile?: string; onOpenNode: (id: string) => void}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [search, setSearch] = useState('');
  const [source, setSource] = useState('');
  const [showProviders, setShowProviders] = useState(false);
  const refresh = () => setRevision((value) => value + 1);
  const input = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const graph = useDetailQuery(
    byRoute.get('GET /api/learning/graph'),
    input,
    revision,
  );
  const memory = useDetailQuery(
    byRoute.get('GET /api/memory'),
    input,
    revision,
    showProviders,
  );
  const curator = useDetailQuery(
    byRoute.get('GET /api/curator'),
    input,
    revision,
    showProviders,
  );
  const graphData = record(graph.value);
  const nodes = new Set(rows(graphData.nodes, []).map((item) => plain(item.id)));
  const cards = rows(graphData.memory, []);
  const summary = record(memory.value);
  const maintenance = record(curator.value);
  const builtin = record(summary.builtin_files);
  const filter = search.trim().toLowerCase();
  const matches = cards
    .map((item, index) => ({
      item,
      id: `memory:${plain(item.source)}:${index}:${plain(item.fingerprint)}`,
    }))
    .filter(
      ({item, id}) =>
        nodes.has(id) &&
        (!source || item.source === source) &&
        `${plain(item.title)} ${plain(item.body)}`.toLowerCase().includes(filter),
    );

  return (
    <BusinessPage
      title={t('mobile.knowledge.knowledgeResources')}
      onBack={onBack}
      onRefresh={refresh}
      loading={graph.loading || memory.loading || curator.loading}
    >
      <SearchField
        placeholder={t('mobile.knowledge.searchBuiltInMemory')}
        value={search}
        onChange={setSearch}
        clearable
      />
      <View style={styles.actions}>
        {(
          [
            ['', t('artifacts.tabAll')],
            ['memory', t('mobile.knowledge.longTermMemory')],
            ['profile', t('mobile.knowledge.userInformation')],
          ] as const
        ).map(([key, label]) => (
          <Button
            key={key}
            label={label}
            selected={source === key}
            onPress={() => setSource(key)}
          />
        ))}
        <Button
          label={
            showProviders
              ? t('mobile.knowledge.hideStorageStatus')
              : t('mobile.knowledge.storageAndMaintenance')
          }
          onPress={() => setShowProviders(!showProviders)}
        />
      </View>
      {showProviders ? (
        <>
          <ResourceStatus
            resource={memory}
            label={t('mobile.knowledge.memoryStorage')}
          />
          <Card>
            <Heading>{t('mobile.knowledge.memoryStorage')}</Heading>
            <Fact
              label={t('mobile.knowledge.currentProvider')}
              value={
                plain(summary.active) ||
                (memory.value ? t('mobile.knowledge.builtInMemory') : '')
              }
            />
            <Fact
              label={t('mobile.knowledge.longTermMemory')}
              value={
                typeof builtin.memory === 'number'
                  ? t('mobile.kanbanTask.bytes', {
                      v1: builtin.memory,
                      count: builtin.memory,
                    })
                  : ''
              }
            />
            <Fact
              label={t('mobile.knowledge.userInformation')}
              value={
                typeof builtin.user === 'number'
                  ? t('mobile.kanbanTask.bytes', {
                      v1: builtin.user,
                      count: builtin.user,
                    })
                  : ''
              }
            />
            {rows(summary.providers, []).map((item) => (
              <Fact
                key={plain(item.name)}
                label={plain(item.name)}
                value={
                  item.status === 'ready'
                    ? t('skills.configured')
                    : plain(item.status) ||
                      (item.available
                        ? t('mobile.groups.available')
                        : t('connectors.disabled'))
                }
              />
            ))}
          </Card>
          <ResourceStatus
            resource={curator}
            label={t('commandCenter.maintenance.curator')}
          />
          {curator.value ? (
            <Card>
              <Heading>{t('commandCenter.maintenance.curator')}</Heading>
              <Fact
                label={t('sidebar.filter.status')}
                value={
                  maintenance.paused === true
                    ? t('commandCenter.maintenance.curatorPaused')
                    : maintenance.enabled === true
                      ? t('messaging.enabled')
                      : t('mobile.knowledge.disabled')
                }
              />
              <Fact
                label={t('mobile.knowledge.interval')}
                value={
                  typeof maintenance.interval_hours === 'number'
                    ? t('mobile.knowledge.hours', {
                        v1: String(maintenance.interval_hours),
                        count: Number(String(maintenance.interval_hours)),
                      })
                    : ''
                }
              />
              <Fact
                label={t('mobile.jobDetails.lastRun')}
                value={plain(maintenance.last_run_at)}
              />
            </Card>
          ) : null}
        </>
      ) : null}
      <ResourceStatus
        resource={graph}
        label={t('mobile.knowledge.knowledgeResources')}
      />
      {matches.map(({item, id}) => (
        <ListRow
          key={id}
          title={plain(item.title) || t('starmap.memory')}
          subtitle={plain(item.body)}
          onPress={() => onOpenNode(id)}
        />
      ))}
      {!matches.length && !graph.loading && !graph.error ? (
        <Notice>{t('mobile.knowledge.noMatchingBuiltInMemories')}</Notice>
      ) : null}
    </BusinessPage>
  );
}
