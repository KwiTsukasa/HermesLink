import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {FlatList, StyleSheet, View} from 'react-native';

import {spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Feedback} from '../../../shared/ui/Feedback';
import {ListRow} from '../../../shared/ui/ListRow';
import {SearchField} from '../components/SearchField';
import {
  catalogEntryLabel,
  catalogGroupId,
  catalogGroupLabel,
} from '../services/workspaceApi';
import type {Entry} from '../types';

type CatalogItem =
  | {kind: 'group'; id: string; label: string; size: number; formCount: number}
  | {kind: 'entry'; entry: Entry};

type CatalogScreenProps = {
  entries: readonly Entry[];
  group: string | null;
  search: string;
  onSearch: (value: string) => void;
  onOpenGroup: (group: string) => void;
  onOpenEntry: (entry: Entry) => void;
};

export function CatalogScreen({
  entries,
  group,
  search,
  onSearch,
  onOpenGroup,
  onOpenEntry,
}: CatalogScreenProps) {
  const {locale} = useI18n();
  const [formOnly, setFormOnly] = useState(true);
  const groups = useMemo(() => {
    const byGroup = new Map<string, Entry[]>();
    for (const entry of entries) {
      const id = catalogGroupId(entry);
      const existing = byGroup.get(id);
      if (existing) {
        existing.push(entry);
      } else {
        byGroup.set(id, [entry]);
      }
    }
    return byGroup;
  }, [entries]);

  const query = search.trim().toLowerCase();
  const items = useMemo<CatalogItem[]>(() => {
    if (!group && !query) {
      return Array.from(groups.entries())
        .map(([id, children]) => ({
          kind: 'group' as const,
          id,
          label: catalogGroupLabel(children[0]),
          size: children.length,
          formCount: children.filter((entry) => !entry.unavailable).length,
        }))
        .filter((item) => !formOnly || item.formCount > 0)
        .sort((first, second) => first.label.localeCompare(second.label, locale));
    }
    const candidates = group ? (groups.get(group) ?? []) : entries;
    const terms = query ? query.split(/\s+/) : [];
    return candidates
      .filter((entry) => {
        if (formOnly && entry.unavailable) return false;
        const text =
          `${catalogEntryLabel(entry)} ${entry.id} ${entry.method} ${entry.path} ${catalogGroupLabel(entry)} ${entry.description || ''}`.toLowerCase();
        return terms.every((term) => text.includes(term));
      })
      .map((entry) => ({kind: 'entry', entry}));
  }, [entries, formOnly, group, groups, locale, query]);

  return (
    <View style={styles.screen}>
      <SearchField
        placeholder={t('mobile.catalog.searchFeaturesOrEndpointPaths')}
        value={search}
        onChange={onSearch}
      />
      <View style={styles.filters}>
        <Button
          label={t('mobile.catalog.requestForms')}
          selected={formOnly}
          onPress={() => setFormOnly(true)}
        />
        <Button
          label={t('mobile.catalog.allEndpoints')}
          selected={!formOnly}
          onPress={() => setFormOnly(false)}
        />
      </View>
      <FlatList
        key={group ?? 'catalog'}
        data={items}
        keyExtractor={(item) =>
          item.kind === 'group' ? `group:${item.id}` : item.entry.id
        }
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={
          <Feedback message={t('mobile.catalog.noMatchingFeatures')} />
        }
        renderItem={({item}) => {
          if (item.kind === 'group') {
            return (
              <ListRow
                title={item.label}
                subtitle={
                  formOnly
                    ? t('mobile.catalog.requestForms2', {
                        v1: item.formCount,
                        count: item.formCount,
                      })
                    : t('mobile.catalog.entriesRequestForms', {
                        v1: item.size,
                        v2: item.formCount,
                      })
                }
                onPress={() => onOpenGroup(item.id)}
              />
            );
          }
          return (
            <ListRow
              title={catalogEntryLabel(item.entry)}
              subtitle={`${item.entry.unavailable ? t('mobile.catalog.readOnly') : t('mobile.catalog.requestForm')}${item.entry.kind === 'rpc' ? item.entry.method : `${item.entry.method} ${item.entry.path}`}`}
              onPress={() => onOpenEntry(item.entry)}
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
  filters: {flexDirection: 'row', flexWrap: 'wrap', paddingHorizontal: spacing.lg},
});
