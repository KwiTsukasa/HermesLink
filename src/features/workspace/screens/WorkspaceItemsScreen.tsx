import {t, useI18n} from '../../../shared/i18n';
import {useMemo} from 'react';
import {FlatList, StyleSheet, View} from 'react-native';

import {spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Feedback} from '../../../shared/ui/Feedback';
import {ListRow} from '../../../shared/ui/ListRow';
import {detailStyles} from '../components/DetailContent';
import {SearchField} from '../components/SearchField';
import type {DetailTarget, Dict} from '../types';
import {rows, textValue} from '../utils/data';

type ItemRow = {
  key: string;
  title: string;
  subtitle: string;
  target?: DetailTarget;
};

function skillRow(skill: Dict, index: number): ItemRow {
  const name = textValue(skill.name);
  const profile =
    textValue(skill.profile) || textValue(skill.profile_name) || undefined;
  const description = textValue(skill.description);
  const status =
    skill.enabled === false ? t('mobile.mcp.disabled') : t('messaging.enabled');

  return {
    key: name ? `${profile ?? ''}:${name}` : `skill-${index}`,
    title: name || t('profiles.skillsLabel'),
    subtitle: [status, description].filter(Boolean).join(' · '),
    target: name ? {kind: 'skill', name, profile} : undefined,
  };
}

function jobRow(job: Dict, index: number): ItemRow {
  const id = textValue(job.id) || textValue(job.job_id);
  const profile = textValue(job.profile) || textValue(job.profile_name) || undefined;
  const status =
    job.enabled === false
      ? t('commandCenter.maintenance.curatorPaused')
      : textValue(job.state) || t('messaging.enabled');

  return {
    key: id ? `${profile ?? ''}:${id}` : `job-${index}`,
    title: textValue(job.name) || textValue(job.title) || id || t('cron.title'),
    subtitle: [status, profile].filter(Boolean).join(' · '),
    target: id ? {kind: 'job', id, profile} : undefined,
  };
}

type WorkspaceItemsScreenProps = {
  kind: 'skills' | 'jobs';
  data: unknown;
  loading: boolean;
  error?: string;
  search: string;
  onSearch: (value: string) => void;
  onRefresh: () => void;
  onOpenDetail: (target: DetailTarget) => void;
  onCreate?: () => void;
  onOpenHub?: () => void;
  profile?: string;
};

export function WorkspaceItemsScreen({
  kind,
  data,
  loading,
  error,
  search,
  onSearch,
  onRefresh,
  onOpenDetail,
  onCreate,
  onOpenHub,
  profile,
}: WorkspaceItemsScreenProps) {
  const {locale} = useI18n();
  const isSkills = kind === 'skills';
  const label = isSkills ? t('profiles.skillsLabel') : t('cron.title');
  const query = search.trim().toLocaleLowerCase(locale);
  const items = useMemo(() => {
    const itemsFromRequest = rows(data, [kind, 'items']).map(
      isSkills ? skillRow : jobRow,
    );
    return itemsFromRequest
      .filter((item) =>
        `${item.title} ${item.subtitle}`.toLocaleLowerCase(locale).includes(query),
      )
      .map((item) =>
        item.target && !item.target.profile
          ? {...item, target: {...item.target, profile}}
          : item,
      );
  }, [data, isSkills, kind, locale, profile, query]);

  let empty = (
    <Feedback
      message={
        query
          ? t('cron.emptyTitleSearch')
          : isSkills
            ? t('mobile.workspaceItems.noYet', {v1: label})
            : t('cron.emptyTitleNew')
      }
    />
  );
  if (error) {
    empty = <Feedback tone="error" message={error} onRetry={onRefresh} />;
  } else if (loading) {
    empty = (
      <Feedback
        tone="loading"
        message={t('mobile.detailContent.loading', {v1: String(label)})}
      />
    );
  }

  return (
    <View style={styles.screen}>
      <SearchField
        placeholder={isSkills ? t('skills.searchSkills') : t('cron.search')}
        value={search}
        onChange={onSearch}
      />
      <FlatList
        key={kind}
        data={items}
        keyExtractor={(item) => item.key}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        refreshing={loading}
        onRefresh={onRefresh}
        ListHeaderComponent={
          isSkills && (onCreate || onOpenHub) ? (
            <View style={detailStyles.actions}>
              {onCreate ? (
                <Button
                  label={t('mobile.workspaceItems.newSkill')}
                  icon="plus"
                  onPress={onCreate}
                />
              ) : null}
              {onOpenHub ? (
                <Button
                  label={t('skills.hub.pickerTitle')}
                  icon="extensions"
                  onPress={onOpenHub}
                />
              ) : null}
            </View>
          ) : undefined
        }
        ListEmptyComponent={empty}
        ListFooterComponent={
          !isSkills && onCreate && !loading && !error ? (
            <View style={styles.newCron}>
              <Button
                label={t('cron.newCron')}
                icon="plus"
                variant={items.length ? 'plain' : 'accent'}
                onPress={onCreate}
              />
            </View>
          ) : undefined
        }
        renderItem={({item}) => {
          const target = item.target;
          return (
            <ListRow
              title={item.title}
              icon={isSkills ? 'extensions' : 'watch'}
              subtitle={item.subtitle}
              onPress={target ? () => onOpenDetail(target) : undefined}
            />
          );
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  content: {paddingHorizontal: spacing.lg, paddingBottom: spacing.xxl},
  newCron: {alignItems: 'center', paddingVertical: spacing.sm},
});
