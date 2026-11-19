import {useState} from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';

import {useI18n} from '../i18n';
import {Button} from '../ui/Button';
import {Feedback} from '../ui/Feedback';
import {useAppearance, useAppearanceProfile, useTheme} from './ThemeProvider';
import type {AppearanceMode} from './ThemeProvider';
import labels from './labels.json';
import {appearancePresets, radius, spacing, touchTarget} from './tokens';

function localLabel(key: keyof typeof labels, locale: string) {
  const translations = labels[key] as Readonly<Record<string, string>>;
  return translations[locale] ?? translations.en;
}

const modes: AppearanceMode[] = ['system', 'light', 'dark'];

export function AppearanceSettings({profile}: {profile?: string}) {
  useAppearanceProfile(profile);
  const {t, locale} = useI18n();
  const colors = useTheme();
  const {snapshot, refresh, update} = useAppearance();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const updating = busy || snapshot.sync === 'syncing';
  const status =
    snapshot.sync === 'cached'
      ? ''
      : snapshot.sync === 'syncing'
        ? t('common.loading')
        : localLabel(snapshot.sync, locale);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setFailed(false);
    try {
      await action();
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={styles.content}>
      <Text style={{color: colors.sub}}>
        {snapshot.profile === 'default'
          ? localLabel('host', locale)
          : localLabel('profile', locale).replace('{profile}', snapshot.profile)}
      </Text>
      <View style={styles.modes}>
        {modes.map((mode) => (
          <Button
            key={mode}
            label={t(`settings.modeOptions.${mode}.label`)}
            variant="tab"
            selected={snapshot.mode === mode}
            disabled={!snapshot.signedIn || updating}
            onPress={() => run(() => update({mode}))}
          />
        ))}
      </View>
      {appearancePresets.map((preset) => {
        const selected = snapshot.themeName === preset.id;
        return (
          <Pressable
            key={preset.id}
            accessibilityRole="button"
            accessibilityState={{selected, disabled: !snapshot.signedIn || updating}}
            disabled={!snapshot.signedIn || updating}
            onPress={() => run(() => update({themeName: preset.id}))}
            style={[
              styles.choice,
              {borderColor: selected ? colors.accent : colors.line},
            ]}
          >
            <Text style={{color: colors.ink}}>{preset.label}</Text>
            <View style={styles.swatches} accessibilityElementsHidden>
              <View
                style={[styles.swatch, {backgroundColor: preset.light.accent}]}
              />
              <View style={[styles.swatch, {backgroundColor: preset.dark.accent}]} />
            </View>
          </Pressable>
        );
      })}
      {!snapshot.available && snapshot.signedIn ? (
        <Feedback message={localLabel('unavailable', locale)} />
      ) : null}
      {status ? <Feedback message={status} /> : null}
      {failed ? (
        <Feedback tone="error" message={localLabel('failed', locale)} />
      ) : null}
      {snapshot.signedIn ? (
        <Button
          label={t('common.refresh')}
          disabled={updating}
          onPress={() => run(refresh)}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  content: {gap: spacing.sm, padding: spacing.md},
  modes: {flexDirection: 'row', gap: spacing.xs},
  choice: {
    borderRadius: radius.control,
    borderWidth: 1,
    minHeight: touchTarget,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  swatches: {flexDirection: 'row', gap: spacing.xs},
  swatch: {width: 18, height: 18, borderRadius: 9},
});
