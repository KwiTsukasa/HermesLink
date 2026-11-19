import {t, useI18n} from '../../../shared/i18n';
import {StyleSheet, View} from 'react-native';

import {spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Icon} from '../../../shared/ui/Icon';
import {Input} from '../../../shared/ui/Input';

type SearchFieldProps = {
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  clearable?: boolean;
};

export function SearchField({
  placeholder,
  value,
  onChange,
  clearable,
}: SearchFieldProps) {
  useI18n();
  return (
    <View style={styles.container}>
      <View pointerEvents="none" style={styles.searchIcon}>
        <Icon name="search" size={16} />
      </View>
      <Input
        accessibilityLabel={placeholder}
        placeholder={placeholder}
        returnKeyType="search"
        style={styles.input}
        value={value}
        onChangeText={onChange}
      />
      {clearable && value ? (
        <Button
          label={t('common.clear')}
          icon="chrome-close"
          iconOnly
          onPress={() => onChange('')}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.sm,
    flexDirection: 'row',
    alignItems: 'center',
  },
  searchIcon: {
    position: 'absolute',
    start: spacing.lg + spacing.md,
    top: spacing.lg,
    zIndex: 1,
  },
  input: {flex: 1, paddingStart: 36},
});
