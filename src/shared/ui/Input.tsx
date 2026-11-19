import {StyleSheet, TextInput} from 'react-native';
import type {TextInputProps} from 'react-native';

import {useTheme} from '../theme/ThemeProvider';
import {radius, spacing, touchTarget} from '../theme/tokens';

export function Input({style, multiline, ...props}: TextInputProps) {
  const colors = useTheme();
  return (
    <TextInput
      autoCapitalize="none"
      autoCorrect={false}
      placeholderTextColor={colors.sub}
      {...props}
      multiline={multiline}
      style={[
        styles.input,
        {color: colors.ink, backgroundColor: colors.panel, borderColor: colors.line},
        multiline && styles.multiline,
        style,
      ]}
    />
  );
}

const styles = StyleSheet.create({
  input: {
    minHeight: touchTarget,
    paddingHorizontal: spacing.md,
    borderWidth: 1,
    borderRadius: radius.control,
  },
  multiline: {minHeight: 96, paddingVertical: spacing.sm, textAlignVertical: 'top'},
});
