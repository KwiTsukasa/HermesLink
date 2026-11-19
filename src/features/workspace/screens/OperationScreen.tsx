import {t, useI18n} from '../../../shared/i18n';
import {ScrollView, StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {radius, spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Feedback} from '../../../shared/ui/Feedback';
import {Input} from '../../../shared/ui/Input';
import {PageHeading} from '../components/PageHeading';
import {useOperation} from '../hooks/useOperation';
import {catalogEntryLabel} from '../services/workspaceApi';
import type {Entry} from '../types';
import {display} from '../utils/data';
import {isFileField} from '../utils/operationForm';

const MAX_DISPLAY_LENGTH = 30_000;
const scopeLabels = {
  get path() {
    return t('artifacts.colLocationFile');
  },
  get query() {
    return t('mobile.operation.query');
  },
  get body() {
    return t('mobile.operation.requestBody');
  },
};

type OperationScreenProps = {
  entry: Entry;
  onBack: () => void;
  onWrite: () => void;
};

export function OperationScreen({entry, onBack, onWrite}: OperationScreenProps) {
  useI18n();
  const colors = useTheme();
  const operation = useOperation(entry, onWrite);
  const fileField = operation.fields.find(({field}) => isFileField(field))?.field;
  const busy = !!operation.pending;
  const result = operation.result;
  const output = result?.kind === 'value' ? display(result.value) : undefined;

  return (
    <ScrollView
      keyboardShouldPersistTaps="handled"
      contentContainerStyle={styles.scroll}
    >
      <PageHeading title={catalogEntryLabel(entry)} onBack={onBack} />
      <View style={styles.content}>
        <Text selectable style={[styles.route, {color: colors.sub}]}>
          {entry.method} {entry.path}
        </Text>
        {entry.description ? (
          <Text style={{color: colors.sub}}>{entry.description}</Text>
        ) : null}
        {entry.unavailable ? (
          <Feedback message={entry.unavailable} />
        ) : (
          <>
            {operation.fields.map(({field, key, scope}) => {
              if (isFileField(field)) {
                return null;
              }
              const label = `${scopeLabels[scope]} · ${field.name}${field.required ? ' *' : ''}`;
              const secret =
                /^(?:api_key|bearer_token|password|secret|access_token|refresh_token)$/i.test(
                  field.name,
                );
              return (
                <View key={key} style={styles.field}>
                  <Text style={{color: colors.ink}}>{label}</Text>
                  <Input
                    accessibilityLabel={label}
                    accessibilityHint={field.description}
                    editable={!busy}
                    secureTextEntry={secret}
                    multiline={
                      !secret &&
                      (scope === 'body' ||
                        field.type === 'array' ||
                        field.type === 'object')
                    }
                    placeholder={
                      field.default == null ? field.type : display(field.default)
                    }
                    value={operation.values[key] ?? ''}
                    onChangeText={(value) => operation.updateValue(key, value)}
                  />
                </View>
              );
            })}
            {fileField ? (
              <Button
                label={
                  operation.attachment?.name ??
                  t('mobile.operation.chooseFile', {
                    v1: String(fileField.required ? ' *' : ''),
                  })
                }
                disabled={busy}
                busy={operation.pending === 'pick'}
                onPress={operation.selectFile}
              />
            ) : null}
            <Button
              label={
                operation.pending === 'execute'
                  ? t('mobile.operation.running')
                  : entry.constraints.mutating
                    ? t('mobile.operation.runOperation')
                    : t('display.cute.verb.read')
              }
              variant="accent"
              disabled={busy}
              busy={operation.pending === 'execute'}
              onPress={operation.submit}
            />
          </>
        )}
        {operation.error ? (
          <Feedback tone="error" message={operation.error} />
        ) : null}
        {result?.kind === 'file' ? (
          <Button
            label={t('mobile.operation.save', {v1: String(result.name)})}
            disabled={busy}
            busy={operation.pending === 'save'}
            onPress={() => operation.saveResult(result.handle)}
          />
        ) : null}
        {output !== undefined ? (
          <Text
            selectable
            style={[
              styles.output,
              {color: colors.ink, backgroundColor: colors.panel},
            ]}
          >
            {output.length > MAX_DISPLAY_LENGTH
              ? t('mobile.operation.outputTruncated', {
                  v1: String(output.slice(0, MAX_DISPLAY_LENGTH)),
                })
              : output}
          </Text>
        ) : null}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: {paddingBottom: spacing.xxl},
  content: {paddingHorizontal: spacing.lg, gap: spacing.md},
  field: {gap: spacing.xs},
  route: {fontFamily: 'monospace', fontSize: 13},
  output: {
    fontFamily: 'monospace',
    fontSize: 13,
    padding: spacing.md,
    borderRadius: radius.control,
  },
});
