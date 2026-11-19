import type {PropsWithChildren} from 'react';
import {RefreshControl, ScrollView, StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Input} from '../../../shared/ui/Input';
import type {BusinessField as Field} from '../types';
import {detailStyles as styles} from './DetailContent';
import {PageHeading} from './PageHeading';

export function BusinessPage({
  title,
  onBack,
  onRefresh,
  loading = false,
  dialog = false,
  children,
}: PropsWithChildren<{
  title: string;
  onBack: () => void;
  onRefresh?: () => void;
  loading?: boolean;
  dialog?: boolean;
}>) {
  const colors = useTheme();
  return (
    <View
      style={[
        styles.screen,
        dialog && pageStyles.dialog,
        dialog && {borderColor: colors.line},
      ]}
    >
      <PageHeading
        title={title}
        onBack={onBack}
        close={dialog}
        onRefresh={onRefresh}
        loading={loading}
      />
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.content}
        refreshControl={
          onRefresh ? (
            <RefreshControl
              refreshing={loading}
              onRefresh={onRefresh}
              colors={[colors.accent]}
              tintColor={colors.accent}
            />
          ) : undefined
        }
      >
        {children}
      </ScrollView>
    </View>
  );
}

export function BusinessField({
  field,
  value,
  disabled,
  onChange,
}: {
  field: Field;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  const colors = useTheme();
  return (
    <View style={fieldStyles.field}>
      <Text style={[styles.itemTitle, {color: colors.ink}]}>{field.label}</Text>
      {field.choices ? (
        <View accessibilityLabel={field.label} style={fieldStyles.choices}>
          {field.choices.map((choice) => (
            <Button
              key={choice.value}
              label={choice.label}
              disabled={disabled}
              selected={value === choice.value}
              onPress={() => onChange(choice.value)}
            />
          ))}
        </View>
      ) : (
        <Input
          accessibilityLabel={field.label}
          placeholder={field.placeholder}
          value={value}
          multiline={field.multiline}
          secureTextEntry={field.secret}
          editable={!disabled}
          onChangeText={onChange}
        />
      )}
    </View>
  );
}

const fieldStyles = StyleSheet.create({
  field: {gap: spacing.xs, marginTop: spacing.md},
  choices: {flexDirection: 'row', flexWrap: 'wrap'},
});

const pageStyles = StyleSheet.create({
  dialog: {
    width: '94%',
    maxWidth: 600,
    alignSelf: 'center',
    marginVertical: spacing.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: 4,
  },
});
