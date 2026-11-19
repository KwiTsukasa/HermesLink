import {formatNumber, t, useI18n} from '../../../shared/i18n';
import {useCallback, useState} from 'react';
import {FlatList, StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Feedback} from '../../../shared/ui/Feedback';
import {Card, detailStyles, Heading} from '../components/DetailContent';
import {PageHeading} from '../components/PageHeading';
import {useWorkspaceQuery} from '../hooks/useWorkspaceQuery';
import {invoke} from '../services/workspaceApi';
import type {Entry} from '../types';
import {isRecord, profileQuery} from '../utils/data';

const periods = [7, 30, 90];

function Count({label, value}: {label: string; value: unknown}) {
  useI18n();
  const colors = useTheme();
  const formatted =
    typeof value === 'number' && Number.isFinite(value) ? formatNumber(value) : '—';
  return (
    <View style={styles.metric}>
      <Text style={[detailStyles.caption, {color: colors.sub}]}>{label}</Text>
      <Text selectable style={[styles.value, {color: colors.ink}]}>
        {formatted}
      </Text>
    </View>
  );
}

export function UsageScreen({
  entry,
  profile,
  onBack,
}: {
  entry?: Entry;
  profile?: string;
  onBack: () => void;
}) {
  useI18n();
  const colors = useTheme();
  const [days, setDays] = useState(30);
  const request = useCallback(async () => {
    const report = await invoke(entry, {query: {...profileQuery(profile), days}});
    if (
      !isRecord(report) ||
      report.period_days !== days ||
      !isRecord(report.totals) ||
      !Array.isArray(report.models)
    ) {
      throw new Error(t('mobile.usage.hermesReturnedInvalidUsageData'));
    }
    const models = report.models.map((item: unknown) => {
      if (!isRecord(item) || typeof item.model !== 'string') {
        throw new Error(t('mobile.usage.hermesReturnedInvalidModelStatistics'));
      }
      return {
        name: item.model,
        provider: typeof item.provider === 'string' ? item.provider : '',
        calls: item.api_calls,
        input: item.input_tokens,
        output: item.output_tokens,
      };
    });
    return {totals: report.totals, models};
  }, [entry, days, profile]);
  const {state, refresh} = useWorkspaceQuery(request);
  const report = 'data' in state ? state.data : undefined;

  return (
    <View style={detailStyles.screen}>
      <PageHeading
        title={t('mobile.usage.modelUsage')}
        onBack={onBack}
        onRefresh={refresh}
        loading={state.status === 'loading'}
      />
      <View style={styles.periods}>
        {periods.map((period) => (
          <Button
            key={period}
            variant="tab"
            label={t('mobile.usage.lastDays', {
              v1: String(period),
              count: Number(String(period)),
            })}
            selected={days === period}
            onPress={() => setDays(period)}
          />
        ))}
      </View>
      {state.status === 'error' && (
        <Feedback tone="error" message={state.error} onRetry={refresh} />
      )}
      {state.status === 'loading' && !report && (
        <Feedback tone="loading" message={t('mobile.usage.loadingModelUsage')} />
      )}
      {report && (
        <FlatList
          key={days}
          data={report.models}
          keyExtractor={(item) => JSON.stringify([item.name, item.provider])}
          contentContainerStyle={detailStyles.content}
          refreshing={state.status === 'loading'}
          onRefresh={refresh}
          ListHeaderComponent={
            <>
              <Card>
                <View style={styles.metrics}>
                  <Count
                    label={t('commandCenter.statApiCalls')}
                    value={report.totals.total_api_calls}
                  />
                  <Count
                    label={t('mobile.maintenance.sessions')}
                    value={report.totals.total_sessions}
                  />
                  <Count
                    label={t('mobile.usage.inputTokens')}
                    value={report.totals.total_input}
                  />
                  <Count
                    label={t('mobile.usage.outputTokens')}
                    value={report.totals.total_output}
                  />
                </View>
              </Card>
              <Heading>{t('mobile.usage.byModel')}</Heading>
            </>
          }
          ListEmptyComponent={
            <Feedback
              message={t('mobile.usage.noModelUsageRecordedForThisPeriod')}
            />
          }
          renderItem={({item}) => (
            <Card>
              <Text selectable style={[styles.model, {color: colors.ink}]}>
                {item.name}
              </Text>
              {item.provider ? (
                <Text style={{color: colors.accent}}>{item.provider}</Text>
              ) : null}
              <View style={styles.metrics}>
                <Count label={t('commandCenter.statApiCalls')} value={item.calls} />
                <Count label={t('mobile.usage.inputTokens')} value={item.input} />
                <Count label={t('mobile.usage.outputTokens')} value={item.output} />
              </View>
            </Card>
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  periods: {
    flexDirection: 'row',
    marginHorizontal: spacing.lg,
    marginBottom: spacing.md,
  },
  metrics: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    columnGap: spacing.lg,
    rowGap: spacing.xs,
  },
  metric: {minWidth: '40%', flexGrow: 1},
  model: {fontSize: 17, fontWeight: '600', marginBottom: spacing.xs},
  value: {fontSize: 18, fontVariant: ['tabular-nums'], fontWeight: '500'},
});
