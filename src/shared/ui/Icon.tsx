import {StyleSheet, Text} from 'react-native';

import {useTheme} from '../theme/ThemeProvider';

// Codepoints from the Hermes 0.23.2 Webapp Codicon stylesheet.
const glyphs = {
  'layout-sidebar-left': 0xebf3,
  'chrome-close': 0xeab8,
  refresh: 0xeb37,
  search: 0xea6d,
  'chevron-left': 0xeab5,
  'chevron-right': 0xeab6,
  'chevron-down': 0xeab4,
  plus: 0xea60,
  'settings-gear': 0xeb51,
  beaker: 0xea79,
  extensions: 0xeae6,
  comment: 0xea6b,
  'comment-discussion': 0xeac7,
  files: 0xeaf0,
  watch: 0xeb7c,
  terminal: 0xea85,
  graph: 0xeb03,
  repo: 0xea62,
  folder: 0xea83,
  person: 0xea67,
  tools: 0xeb6d,
  pulse: 0xeb31,
  organization: 0xea7e,
  star: 0xea6a,
  book: 0xeaa4,
  globe: 0xeb01,
  warning: 0xea6c,
  home: 0xeb06,
  server: 0xeb50,
  'list-unordered': 0xeb17,
  plug: 0xeb2d,
  robot: 0xec20,
  more: 0xea7c,
  'arrow-left': 0xea9b,
  'sign-out': 0xea6e,
  'symbol-event': 0xea86,
  'symbol-misc': 0xeb63,
  output: 0xeb9d,
} as const;

export type IconName = keyof typeof glyphs;

export function Icon({
  name,
  color,
  size = 16,
}: {
  name: IconName;
  color?: string;
  size?: number;
}) {
  const theme = useTheme();
  return (
    <Text
      accessible={false}
      importantForAccessibility="no"
      style={[
        styles.glyph,
        {fontSize: size, lineHeight: size, color: color ?? theme.sub},
      ]}
    >
      {String.fromCodePoint(glyphs[name])}
    </Text>
  );
}

const styles = StyleSheet.create({
  glyph: {fontFamily: 'codicon', includeFontPadding: false, textAlign: 'center'},
});
