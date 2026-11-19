import {t, useI18n} from '../../../shared/i18n';
import type {PropsWithChildren} from 'react';
import {StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {radius, spacing} from '../../../shared/theme/tokens';
import {Feedback} from '../../../shared/ui/Feedback';

export function Card({children}: PropsWithChildren) {
  useI18n();
  const colors = useTheme();
  return (
    <View
      style={[
        styles.card,
        {backgroundColor: colors.panel, borderColor: colors.line},
      ]}
    >
      {children}
    </View>
  );
}

export function Notice({children}: {children: string}) {
  useI18n();
  const colors = useTheme();
  return (
    <Text
      accessibilityLiveRegion="polite"
      style={[styles.notice, {color: colors.sub}]}
    >
      {children}
    </Text>
  );
}

export function Heading({children}: PropsWithChildren) {
  useI18n();
  const colors = useTheme();
  return (
    <Text accessibilityRole="header" style={[styles.heading, {color: colors.ink}]}>
      {children}
    </Text>
  );
}

export function Fact({label, value}: {label: string; value: string}) {
  useI18n();
  const colors = useTheme();
  if (!value) {
    return null;
  }
  return (
    <View style={styles.fact}>
      <Text style={[detailStyles.caption, {color: colors.sub}]}>{label}</Text>
      <Text selectable style={[styles.factValue, {color: colors.ink}]}>
        {value}
      </Text>
    </View>
  );
}

type ResourceStatusProps = {
  label: string;
  resource: {loading: boolean; error?: string};
};

export function ResourceStatus({resource, label}: ResourceStatusProps) {
  useI18n();
  if (resource.loading) {
    return (
      <Feedback
        tone="loading"
        message={t('mobile.detailContent.loading', {v1: String(label)})}
      />
    );
  }
  if (resource.error) {
    return (
      <Feedback
        tone="error"
        message={t('mobile.detailContent.couldNotLoad', {
          v1: String(label),
          v2: String(resource.error),
        })}
      />
    );
  }
  return null;
}

export const detailStyles = StyleSheet.create({
  screen: {flex: 1},
  content: {paddingHorizontal: spacing.lg, paddingBottom: spacing.xxl},
  title: {fontSize: 18, lineHeight: 24, fontWeight: '600'},
  caption: {fontSize: 13, lineHeight: 19, marginTop: spacing.xs},
  status: {fontSize: 13, fontWeight: '600', lineHeight: 20, marginTop: spacing.xs},
  document: {fontSize: 15, lineHeight: 24, marginTop: spacing.xs},
  actions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    marginTop: spacing.md,
  },
  itemTitle: {fontSize: 15, lineHeight: 21, fontWeight: '500'},
});

const styles = StyleSheet.create({
  card: {
    borderRadius: radius.control,
    borderWidth: StyleSheet.hairlineWidth,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  heading: {
    fontSize: 15,
    lineHeight: 22,
    fontWeight: '600',
    marginVertical: spacing.sm,
  },
  notice: {fontSize: 14, lineHeight: 22, marginVertical: spacing.sm},
  fact: {marginTop: spacing.sm},
  factValue: {fontSize: 15, lineHeight: 21},
});
