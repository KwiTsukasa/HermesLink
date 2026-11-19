import {t, useI18n} from '../../../shared/i18n';
import {StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';

type PageHeadingProps = {
  title: string;
  loading?: boolean;
  onBack?: () => void;
  onRefresh?: () => void;
  close?: boolean;
};

export function PageHeading({
  title,
  loading,
  onBack,
  onRefresh,
  close,
}: PageHeadingProps) {
  useI18n();
  const colors = useTheme();
  return (
    <View style={styles.header}>
      {onBack && !close ? (
        <Button
          label={t('common.back')}
          icon="chevron-left"
          iconOnly
          onPress={onBack}
        />
      ) : null}
      <Text
        accessibilityRole="header"
        numberOfLines={2}
        style={[styles.title, {color: colors.ink}]}
      >
        {title}
      </Text>
      {onRefresh ? (
        <Button
          label={t('common.refresh')}
          icon="refresh"
          iconOnly
          disabled={loading}
          busy={loading}
          onPress={onRefresh}
        />
      ) : null}
      {onBack && close ? (
        <Button
          label={t('common.close')}
          icon="chrome-close"
          iconOnly
          onPress={onBack}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  header: {
    minHeight: 48,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.xs,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  title: {flex: 1, fontSize: 17, fontWeight: '600'},
});
