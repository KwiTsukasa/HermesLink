import {t, useI18n} from '../../../shared/i18n';
import {RefreshControl, ScrollView, StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing} from '../../../shared/theme/tokens';
import {Feedback} from '../../../shared/ui/Feedback';
import type {IconName} from '../../../shared/ui/Icon';
import {ListRow} from '../../../shared/ui/ListRow';
import type {RouteIndex} from '../types';
import {display, record} from '../utils/data';

type OverviewScreenProps = {
  data: unknown;
  loading: boolean;
  error?: string;
  routes: RouteIndex;
  onRefresh: () => void;
  profile?: string;
  onOpenModels: () => void;
  onOpenUsage: () => void;
  onOpenFiles: () => void;
  onOpenTerminal: () => void;
  onOpenProfiles: () => void;
  onOpenProjects: () => void;
  onOpenMcp: () => void;
  onOpenOps: () => void;
  onOpenMedia: () => void;
  onOpenLive: () => void;
  onOpenArtifacts: () => void;
  onOpenStarmap: () => void;
  onOpenGroups: () => void;
  onOpenKanban: () => void;
  onOpenAchievements: () => void;
  onOpenKnowledge: () => void;
  onOpenLanguage: () => void;
  onOpenAppearance: () => void;
};

type SettingsRow = {
  title: string;
  value?: string;
  icon: IconName;
  onPress?: () => void;
};

export function OverviewScreen({
  data,
  loading,
  error,
  routes,
  onRefresh,
  profile,
  onOpenModels,
  onOpenUsage,
  onOpenFiles,
  onOpenTerminal,
  onOpenProfiles,
  onOpenProjects,
  onOpenMcp,
  onOpenOps,
  onOpenMedia,
  onOpenLive,
  onOpenArtifacts,
  onOpenStarmap,
  onOpenGroups,
  onOpenKanban,
  onOpenAchievements,
  onOpenKnowledge,
  onOpenLanguage,
  onOpenAppearance,
}: OverviewScreenProps) {
  useI18n();
  const colors = useTheme();
  const model = record(data);
  const modelValue =
    !loading && !error
      ? [
          model.model
            ? display(model.model)
            : t('mobile.overview.noModelConfigured'),
          model.provider ? display(model.provider) : '',
        ]
          .filter(Boolean)
          .join(' · ')
      : undefined;
  const sections: {title: string; rows: SettingsRow[]}[] = [
    {
      title: t('settings.sections.appearance'),
      rows: [
        {
          title: t('settings.appearance.title'),
          icon: 'settings-gear',
          onPress: onOpenAppearance,
        },
        {title: t('language.label'), icon: 'globe', onPress: onOpenLanguage},
      ],
    },
    {
      title: t('settings.sections.model'),
      rows: [
        {
          title: t('mobile.modelDetails.modelOptions'),
          value: modelValue,
          icon: 'beaker',
          onPress: onOpenModels,
        },
        {
          title: t('commandCenter.sections.usage'),
          icon: 'graph',
          onPress: routes.has('GET /api/analytics/models') ? onOpenUsage : undefined,
        },
        {title: t('commandCenter.mcpServers'), icon: 'plug', onPress: onOpenMcp},
      ],
    },
    {
      title: t('settings.sections.workspace'),
      rows: [
        {
          title: t('profiles.title'),
          value: profile,
          icon: 'home',
          onPress: onOpenProfiles,
        },
        {title: t('commandCenter.projects'), icon: 'repo', onPress: onOpenProjects},
        {title: t('artifacts.tabFiles'), icon: 'folder', onPress: onOpenFiles},
        {title: t('sidebar.nav.artifacts'), icon: 'files', onPress: onOpenArtifacts},
        {
          title: t('rightSidebar.terminal'),
          icon: 'terminal',
          onPress: onOpenTerminal,
        },
      ],
    },
    {
      title: t('settings.sections.chat'),
      rows: [
        {
          title: t('mobile.groups.groupChat'),
          icon: 'comment-discussion',
          onPress: onOpenGroups,
        },
        {
          title: t('mobile.overview.voiceAndWebapp'),
          icon: 'symbol-event',
          onPress: onOpenMedia,
        },
      ],
    },
    {
      title: t('settings.sections.memory'),
      rows: [
        {title: t('starmap.title'), icon: 'graph', onPress: onOpenStarmap},
        {
          title: t('mobile.knowledge.knowledgeResources'),
          icon: 'book',
          onPress: onOpenKnowledge,
        },
      ],
    },
    {
      title: t('settings.sections.advanced'),
      rows: [
        {title: t('commandCenter.commandCenter'), icon: 'tools', onPress: onOpenOps},
        {
          title: t('mobile.kanban.boardTasks'),
          icon: 'list-unordered',
          onPress: onOpenKanban,
        },
        {
          title: t('mobile.overview.liveBoardActivity'),
          icon: 'pulse',
          onPress: onOpenLive,
        },
        {
          title: t('mobile.achievements.achievements'),
          icon: 'star',
          onPress: onOpenAchievements,
        },
      ],
    },
  ];

  return (
    <ScrollView
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={loading} onRefresh={onRefresh} />}
    >
      {sections.map(({title, rows}, index) => (
        <View key={title} style={styles.section}>
          <Text
            accessibilityRole="header"
            style={[styles.sectionTitle, {color: colors.sub}]}
          >
            {title}
          </Text>
          {index === 1 && loading ? (
            <Feedback
              tone="loading"
              message={t('mobile.overview.loadingCurrentModel')}
            />
          ) : null}
          {index === 1 && error ? (
            <Feedback tone="error" message={error} onRetry={onRefresh} />
          ) : null}
          {rows.map((row) => (
            <ListRow
              key={row.title}
              icon={row.icon}
              title={row.title}
              subtitle={row.value}
              onPress={row.onPress}
            />
          ))}
        </View>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.xxl,
    alignSelf: 'center',
    width: '100%',
    maxWidth: 740,
  },
  section: {marginTop: spacing.lg},
  sectionTitle: {
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 0.6,
    textTransform: 'uppercase',
  },
});
