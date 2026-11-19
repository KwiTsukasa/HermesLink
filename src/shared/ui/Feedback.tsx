import {t, useI18n} from '../i18n';
import {ActivityIndicator, StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../theme/ThemeProvider';
import {spacing} from '../theme/tokens';
import {Button} from './Button';

type FeedbackProps = {
  message: string;
  tone?: 'neutral' | 'error' | 'loading';
  onRetry?: () => void;
};

export function Feedback({message, tone = 'neutral', onRetry}: FeedbackProps) {
  useI18n();
  const colors = useTheme();

  return (
    <View style={styles.container}>
      {tone === 'loading' ? <ActivityIndicator color={colors.accent} /> : null}
      <Text
        accessibilityRole={tone === 'error' ? 'alert' : undefined}
        accessibilityLiveRegion="polite"
        selectable={tone === 'error'}
        style={[
          styles.message,
          {color: tone === 'error' ? colors.danger : colors.sub},
        ]}
      >
        {message}
      </Text>
      {onRetry ? <Button label={t('common.retry')} onPress={onRetry} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {padding: spacing.lg, gap: spacing.sm, alignItems: 'center'},
  message: {textAlign: 'center', lineHeight: 22},
});
