import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {Pressable, StyleSheet, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {radius, spacing} from '../../../shared/theme/tokens';
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
  textValue,
} from '../utils/data';

function timeValue(item: Dict) {
  if (typeof item.timestamp === 'number')
    return item.timestamp < 1e10 ? item.timestamp * 1000 : item.timestamp;
  return Date.parse(plain(item.timestamp)) || 0;
}

function Network({
  nodes,
  edges,
  onSelect,
}: {
  nodes: Dict[];
  edges: Dict[];
  onSelect: (id: string) => void;
}) {
  useI18n();
  const colors = useTheme();
  const [width, setWidth] = useState(0);
  const height = 288;
  const size = Math.min(width, height);
  const points = new Map(
    nodes
      .slice(0, 24)
      .reverse()
      .map((node, index, visible) => {
        const angle = index * 2.4;
        const distance = size * 0.39 * Math.sqrt((index + 1) / visible.length);
        return [
          plain(node.id),
          {
            node,
            left: width / 2 + Math.cos(angle) * distance,
            top: height / 2 + Math.sin(angle) * distance,
          },
        ];
      }),
  );
  return (
    <View
      accessibilityLabel={t('mobile.starmap.memoryAndSkillGraph')}
      onLayout={(event) => setWidth(event.nativeEvent.layout.width)}
      style={[
        graphStyles.plot,
        {height, backgroundColor: colors.panel, borderColor: colors.line},
      ]}
    >
      {width > 0
        ? edges.map((edge, index) => {
            const from = points.get(plain(edge.source));
            const to = points.get(plain(edge.target));
            if (!from || !to || from === to) return null;
            const x = to.left - from.left;
            const y = to.top - from.top;
            const length = Math.hypot(x, y);
            return (
              <View
                key={index}
                pointerEvents="none"
                style={[
                  graphStyles.edge,
                  {
                    left: (from.left + to.left - length) / 2,
                    top: (from.top + to.top) / 2,
                    width: length,
                    backgroundColor: colors.line,
                    transform: [{rotate: `${Math.atan2(y, x)}rad`}],
                  },
                ]}
              />
            );
          })
        : null}
      {width > 0
        ? Array.from(points, ([id, point]) => (
            <Pressable
              key={id}
              accessibilityRole="button"
              accessibilityLabel={plain(point.node.label) || id}
              hitSlop={3}
              onPress={() => onSelect(id)}
              style={[
                graphStyles.touch,
                {left: point.left - 10, top: point.top - 10},
              ]}
            >
              <View
                style={[
                  graphStyles.dot,
                  {
                    backgroundColor:
                      point.node.kind === 'memory' ? colors.accent : colors.ink,
                  },
                  point.node.kind === 'memory'
                    ? graphStyles.memoryDot
                    : graphStyles.skillDot,
                ]}
              />
            </Pressable>
          ))
        : null}
    </View>
  );
}

export function StarmapScreen({
  profile,
  nodeId,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
  onOpenNode,
}: DetailScreenProps & {
  profile?: string;
  nodeId?: string;
  onOpenNode: (id: string) => void;
}) {
  useI18n();
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState<'all' | 'memory' | 'skill'>('all');
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const scope = useMemo(() => profileQuery(profile), [profile]);
  const input = useMemo(() => ({query: scope}), [scope]);
  const detailInput = useMemo(
    () => ({query: {...scope, id: nodeId}}),
    [scope, nodeId],
  );
  const graph = useDetailQuery(
    byRoute.get('GET /api/learning/graph'),
    input,
    revision,
  );
  const detailEntry = byRoute.get('GET /api/learning/node');
  const detail = useDetailQuery(detailEntry, detailInput, revision, !!nodeId);
  const data = record(graph.value);
  const nodes = rows(data.nodes, []).filter((item) => !!plain(item.id));
  const edges = rows(data.edges, []);
  const node = nodes.find((item) => item.id === nodeId);
  const content = record(detail.value);
  const body = typeof content.content === 'string' ? content.content : '';
  const title = plain(node?.label) || plain(content.label) || nodeId || '';
  const category = plain(node?.category);
  const matches = nodes.filter(
    (item) =>
      (kind === 'all' || item.kind === kind) &&
      `${plain(item.label)} ${plain(item.category)}`
        .toLowerCase()
        .includes(query.trim().toLowerCase()),
  );
  matches.sort((a, b) => timeValue(b) - timeValue(a));
  const adjacent = new Set(
    edges.flatMap((edge) =>
      edge.source === nodeId
        ? [edge.target]
        : edge.target === nodeId
          ? [edge.source]
          : [],
    ),
  );
  const update = byRoute.get('PUT /api/learning/node');
  const remove = byRoute.get('DELETE /api/learning/node');

  async function confirmNode() {
    const current = record(await invoke(detailEntry, detailInput));
    if (
      current.id !== nodeId ||
      current.kind !== content.kind ||
      current.content !== content.content
    )
      throw new Error(t('mobile.starmap.thisNodeChangedRefreshAndTryAgain'));
  }

  function edit() {
    if (!update || update.unavailable) return;
    onOpenForm({
      title: t('mobile.starmap.editGraphNode'),
      action: t('mobile.starmap.saveNode'),
      detail: title,
      entry: update,
      fields: [
        {
          key: 'content',
          label:
            content.kind === 'skill'
              ? 'SKILL.md'
              : t('mobile.starmap.memoryContent'),
          initial: body,
          required: true,
          multiline: true,
        },
      ],
      beforeSubmit: confirmNode,
      build: ({content: next}) => ({...input, body: {id: nodeId, content: next}}),
    });
  }

  function deleteNode() {
    const action =
      content.kind === 'skill'
        ? t('mobile.starmap.archiveSkill')
        : t('mobile.starmap.deleteMemory');
    confirm(
      action,
      t('mobile.jobDetails.profile', {
        v1: String(title),
        v2: String(profile || t('mobile.groups.currentGateway')),
      }),
      async () => {
        await confirmNode();
        const result = requireAccepted(
          await invoke(remove, {...input, body: {id: nodeId}}, true),
        );
        if (result.ok !== true)
          throw new Error(t('mobile.starmap.theServerDidNotConfirmTheGraphChange'));
        onBack();
      },
    );
  }

  return (
    <BusinessPage
      title={nodeId ? t('mobile.starmap.graphNode') : t('starmap.title')}
      onBack={onBack}
      onRefresh={refresh}
      loading={graph.loading || detail.loading || busy}
    >
      <ResourceStatus resource={graph} label={t('starmap.title')} />
      {nodeId ? (
        <>
          <ResourceStatus
            resource={detail}
            label={t('mobile.starmap.nodeContent')}
          />
          {feedback ? <Notice>{feedback}</Notice> : null}
          <Card>
            <Heading>{title}</Heading>
            <Fact
              label={t('mobile.starmap.nodeCategory')}
              value={node?.kind === 'memory' ? t('starmap.memory') : category}
            />
            <Fact
              label={t('mobile.starmap.timesUsed')}
              value={textValue(node?.useCount)}
            />
            <Fact label={t('mobile.starmap.content')} value={body} />
            {content.id === nodeId && !detail.loading && !detail.error ? (
              <View style={styles.actions}>
                {update && !update.unavailable ? (
                  <Button
                    label={t('mobile.starmap.editNode')}
                    disabled={busy}
                    onPress={edit}
                  />
                ) : null}
                {remove && !remove.unavailable ? (
                  <Button
                    label={
                      content.kind === 'skill'
                        ? t('mobile.starmap.archiveSkill')
                        : t('mobile.starmap.deleteMemory')
                    }
                    disabled={busy}
                    onPress={deleteNode}
                  />
                ) : null}
              </View>
            ) : null}
          </Card>
          {adjacent.size ? (
            <Heading>{t('mobile.starmap.relatedNodes')}</Heading>
          ) : null}
          {nodes
            .filter((item) => adjacent.has(item.id))
            .map((item) => (
              <ListRow
                key={plain(item.id)}
                title={plain(item.label) || plain(item.id)}
                subtitle={plain(item.category)}
                onPress={() => onOpenNode(plain(item.id))}
              />
            ))}
        </>
      ) : (
        <>
          <SearchField
            placeholder={t('mobile.starmap.searchLearningRecords')}
            value={query}
            onChange={setQuery}
            clearable
          />
          <View style={styles.actions}>
            {(
              [
                ['all', t('artifacts.tabAll')],
                ['memory', t('starmap.memory')],
                ['skill', t('profiles.skillsLabel')],
              ] as const
            ).map(([id, label]) => (
              <Button
                key={id}
                label={label}
                selected={kind === id}
                onPress={() => setKind(id)}
              />
            ))}
          </View>
          {matches.length ? (
            <>
              <Notice>
                {matches.length > 24
                  ? t('mobile.starmap.theGraphShowsTheLatest24MatchesPurple')
                  : t('mobile.starmap.purpleSquaresAreMemoriesAndCirclesAreSkills')}
              </Notice>
              <Network nodes={matches} edges={edges} onSelect={onOpenNode} />
            </>
          ) : !graph.loading && !graph.error ? (
            <Notice>{t('mobile.starmap.noMatchingLearningRecords')}</Notice>
          ) : null}
          {matches.map((item) => (
            <ListRow
              key={plain(item.id)}
              title={plain(item.label) || plain(item.id)}
              subtitle={[
                item.kind === 'memory'
                  ? t('starmap.memory')
                  : plain(item.category) || t('profiles.skillsLabel'),
                plain(item.state),
              ]
                .filter(Boolean)
                .join(' · ')}
              onPress={() => onOpenNode(plain(item.id))}
            />
          ))}
        </>
      )}
    </BusinessPage>
  );
}

const graphStyles = StyleSheet.create({
  plot: {
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.panel,
    marginVertical: spacing.md,
    overflow: 'hidden',
  },
  edge: {position: 'absolute', height: 1},
  touch: {
    position: 'absolute',
    width: 20,
    height: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dot: {width: 12, height: 12},
  memoryDot: {borderRadius: 2},
  skillDot: {borderRadius: 8},
});
