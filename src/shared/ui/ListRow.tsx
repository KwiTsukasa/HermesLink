import {Pressable, StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../theme/ThemeProvider';
import {spacing, touchTarget} from '../theme/tokens';
import {Icon, type IconName} from './Icon';

type ListRowProps = {
  title: string;
  subtitle?: string;
  onPress?: () => void;
  icon?: IconName;
};

export function ListRow({title, subtitle, onPress, icon}: ListRowProps) {
  const colors = useTheme();
  const content = (
    <>
      {icon ? <Icon name={icon} size={17} /> : null}
      <View style={styles.text}>
        <Text style={[styles.title, {color: colors.ink}]}>{title}</Text>
        {subtitle ? (
          <Text numberOfLines={2} style={[styles.subtitle, {color: colors.sub}]}>
            {subtitle}
          </Text>
        ) : null}
      </View>
      {onPress ? <Icon name="chevron-right" size={14} /> : null}
    </>
  );
  const rowStyle = [styles.row, {borderColor: colors.line}];

  if (!onPress) {
    return <View style={rowStyle}>{content}</View>;
  }

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={subtitle ? `${title}\n${subtitle}` : title}
      onPress={onPress}
      style={({pressed}) => [rowStyle, pressed && {backgroundColor: colors.wash}]}
    >
      {content}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    minHeight: touchTarget,
    borderBottomWidth: StyleSheet.hairlineWidth,
    paddingVertical: spacing.sm,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  text: {flex: 1},
  title: {fontSize: 14, fontWeight: '500'},
  subtitle: {fontSize: 12, lineHeight: 17, marginTop: spacing.xs},
});
