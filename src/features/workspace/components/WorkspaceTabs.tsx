import {t} from '../../../shared/i18n';
import {ScrollView, StyleSheet} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import type {WorkspaceTab} from '../types';

export const tabLabels: Record<WorkspaceTab, string> = {
  get overview() {
    return t('commandCenter.nav.settings.title');
  },
  get skills() {
    return t('profiles.skillsLabel');
  },
  get jobs() {
    return t('cron.title');
  },
  get catalog() {
    return t('mobile.workspaceTabs.featureCatalog');
  },
};

export function WorkspacePaneTabs({
  items,
  centered,
}: {
  items: readonly {
    id: string;
    label: string;
    selected: boolean;
    onPress: () => void;
  }[];
  centered?: boolean;
}) {
  const colors = useTheme();
  return (
    <ScrollView
      accessibilityRole="tablist"
      horizontal
      showsHorizontalScrollIndicator={false}
      keyboardShouldPersistTaps="handled"
      contentContainerStyle={[styles.tabItems, centered && styles.centered]}
      style={[styles.tabs, {borderColor: colors.line}]}
    >
      {items.map((item) => (
        <Button
          key={item.id}
          variant="tab"
          fitContent
          selected={item.selected}
          label={item.label}
          onPress={item.onPress}
        />
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  tabs: {
    flexGrow: 0,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  tabItems: {flexGrow: 1, paddingHorizontal: spacing.lg},
  centered: {justifyContent: 'center'},
});
