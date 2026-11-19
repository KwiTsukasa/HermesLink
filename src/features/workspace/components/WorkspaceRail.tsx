import {useState} from 'react';
import {Modal, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';

import {t, useI18n} from '../../../shared/i18n';
import {useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing, touchTarget} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {Icon, type IconName} from '../../../shared/ui/Icon';

export type WorkspaceDestination =
  | 'newSession'
  | 'skills'
  | 'messaging'
  | 'artifacts'
  | 'jobs'
  | 'projects'
  | 'profiles'
  | 'groups'
  | 'kanban'
  | 'live'
  | 'files'
  | 'terminal'
  | 'catalog';

type Destination = {id: WorkspaceDestination; label: string; icon: IconName};

const sessionNav: Destination[] = [
  {id: 'skills', label: 'commandCenter.nav.capabilities.title', icon: 'symbol-misc'},
  {id: 'messaging', label: 'sidebar.nav.messaging', icon: 'comment'},
  {id: 'artifacts', label: 'sidebar.nav.artifacts', icon: 'files'},
  {id: 'jobs', label: 'sidebar.nav.cron', icon: 'watch'},
];

const botNav: Destination[] = [
  {id: 'groups', label: 'mobile.groups.groupChat', icon: 'comment-discussion'},
  {id: 'kanban', label: 'mobile.kanban.boardTasks', icon: 'list-unordered'},
  {id: 'live', label: 'mobile.overview.liveBoardActivity', icon: 'pulse'},
];

const workspaceNav: Destination[] = [
  {id: 'projects', label: 'sidebar.projects.sectionLabel', icon: 'repo'},
  {id: 'files', label: 'artifacts.tabFiles', icon: 'folder'},
  {id: 'terminal', label: 'rightSidebar.terminal', icon: 'terminal'},
  {
    id: 'catalog',
    label: 'mobile.workspaceTabs.featureCatalog',
    icon: 'list-unordered',
  },
];

type RailProps = {
  active?: WorkspaceDestination;
  profile?: string;
  canOpenNewSession?: boolean;
  onNavigate: (destination: WorkspaceDestination) => void;
  width: number;
  onClose?: () => void;
  disabled?: boolean;
};

export function workspaceRailLayout(width: number) {
  const docked = width >= 744;
  return {
    docked,
    railWidth: docked ? 260 : Math.min(260, Math.max(0, width - touchTarget)),
  };
}

function RailContent({
  active,
  profile,
  canOpenNewSession,
  onNavigate,
  width,
  disabled,
}: RailProps) {
  useI18n();
  const colors = useTheme();
  const [group, setGroup] = useState<'sessions' | 'bots'>(
    active && botNav.some((item) => item.id === active) ? 'bots' : 'sessions',
  );

  function row({id, label, icon}: Destination, title = t(label)) {
    const selected = active === id;
    return (
      <Pressable
        key={id}
        accessibilityRole="button"
        accessibilityLabel={title}
        accessibilityState={{selected, disabled}}
        disabled={disabled}
        onPress={() => onNavigate(id)}
        style={({pressed}) => [
          styles.destination,
          (selected || pressed) && {
            backgroundColor: colors.wash,
            borderColor: colors.line,
          },
        ]}
      >
        <Icon name={icon} color={selected ? colors.ink : colors.sub} size={16} />
        <Text
          numberOfLines={2}
          style={[styles.destinationLabel, {color: colors.ink}]}
        >
          {title}
        </Text>
        {id === 'profiles' ? <Icon name="chevron-down" size={13} /> : null}
      </Pressable>
    );
  }

  return (
    <View
      style={[
        styles.rail,
        {width, backgroundColor: colors.panel, borderColor: colors.line},
      ]}
    >
      <View style={[styles.tabBar, {borderColor: colors.line}]}>
        <Button
          label={t('sidebar.sessions')}
          variant="tab"
          fitContent
          selected={group === 'sessions'}
          onPress={() => setGroup('sessions')}
        />
        <Button
          label={t('common.bots')}
          variant="tab"
          fitContent
          selected={group === 'bots'}
          onPress={() => setGroup('bots')}
        />
      </View>
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.menu}
        keyboardShouldPersistTaps="handled"
        showsVerticalScrollIndicator={false}
      >
        {group === 'sessions' ? (
          <>
            {canOpenNewSession
              ? row({
                  id: 'newSession',
                  label: 'sidebar.nav.new-session',
                  icon: 'robot',
                })
              : null}
            {sessionNav.map((item) => row(item))}
          </>
        ) : (
          botNav.map((item) => row(item))
        )}
        <View style={[styles.divider, {borderColor: colors.line}]} />
        {workspaceNav.map((item) => row(item))}
      </ScrollView>
      <View style={[styles.footer, {borderColor: colors.line}]}>
        {row(
          {id: 'profiles', label: 'profiles.title', icon: 'home'},
          profile || t('profiles.title'),
        )}
      </View>
    </View>
  );
}

export function WorkspaceRail({open, ...props}: RailProps & {open?: boolean}) {
  useI18n();
  if (open === undefined) return <RailContent {...props} />;
  return (
    <Modal
      visible={open}
      transparent
      animationType="fade"
      onRequestClose={props.onClose}
    >
      <View accessibilityViewIsModal style={styles.overlay}>
        <Pressable
          accessibilityLabel={t('common.close')}
          accessibilityRole="button"
          style={StyleSheet.absoluteFill}
          onPress={props.onClose}
        />
        <RailContent {...props} />
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  rail: {flexShrink: 0, height: '100%', borderEndWidth: StyleSheet.hairlineWidth},
  tabBar: {flexDirection: 'row', borderBottomWidth: StyleSheet.hairlineWidth},
  scroll: {flex: 1},
  menu: {paddingHorizontal: spacing.sm, paddingVertical: spacing.xs},
  destination: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'transparent',
    borderRadius: 4,
  },
  destinationLabel: {flex: 1, fontSize: 14, fontWeight: '500'},
  divider: {borderTopWidth: StyleSheet.hairlineWidth, marginVertical: spacing.sm},
  footer: {paddingHorizontal: spacing.sm, borderTopWidth: StyleSheet.hairlineWidth},
  overlay: {flex: 1, flexDirection: 'row', backgroundColor: 'rgba(0,0,0,0.52)'},
});
