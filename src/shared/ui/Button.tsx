import {Pressable, StyleSheet, Text} from 'react-native';

import {useTheme} from '../theme/ThemeProvider';
import {radius, spacing, touchTarget} from '../theme/tokens';
import {Icon, type IconName} from './Icon';

type ButtonProps = {
  label: string;
  onPress: () => void;
  variant?: 'plain' | 'accent' | 'tab';
  selected?: boolean;
  disabled?: boolean;
  busy?: boolean;
  icon?: IconName;
  iconOnly?: boolean;
  fitContent?: boolean;
};

export function Button({
  label,
  onPress,
  variant = 'plain',
  selected = false,
  disabled = false,
  busy = false,
  icon,
  iconOnly = false,
  fitContent = false,
}: ButtonProps) {
  const colors = useTheme();
  const emphasized = selected || variant === 'accent';
  const tab = variant === 'tab';
  const foreground =
    variant === 'accent' ? colors.bg : emphasized ? colors.accent : colors.ink;

  return (
    <Pressable
      accessibilityRole={tab ? 'tab' : 'button'}
      accessibilityLabel={label}
      accessibilityState={{
        disabled: disabled || busy,
        busy,
        selected: tab ? selected : undefined,
      }}
      disabled={disabled || busy}
      onPress={onPress}
      style={({pressed}) => [
        styles.button,
        tab && styles.tab,
        tab && fitContent && styles.fitTab,
        iconOnly && styles.iconOnly,
        {
          backgroundColor:
            variant === 'accent'
              ? colors.accent
              : pressed || (selected && !tab)
                ? colors.wash
                : 'transparent',
        },
        tab && selected && {borderBottomColor: colors.accent},
        disabled && styles.disabled,
      ]}
    >
      {icon ? <Icon name={icon} color={foreground} /> : null}
      {!iconOnly ? (
        <Text style={[styles.label, {color: foreground}]}>{label}</Text>
      ) : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: touchTarget,
    minWidth: touchTarget,
    borderRadius: radius.control,
    flexDirection: 'row',
    gap: spacing.xs,
    paddingHorizontal: spacing.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tab: {
    flex: 1,
    borderRadius: 0,
    borderBottomWidth: 2,
    borderBottomColor: 'transparent',
  },
  fitTab: {flex: 0},
  iconOnly: {paddingHorizontal: 0},
  disabled: {opacity: 0.5},
  label: {fontSize: 14, fontWeight: '500', textAlign: 'center', flexShrink: 1},
});
