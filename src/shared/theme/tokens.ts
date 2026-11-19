export type Palette = {
  bg: string;
  panel: string;
  ink: string;
  sub: string;
  line: string;
  accent: string;
  wash: string;
  danger: string;
};

export const appearancePresets = official.presets;
const defaultPreset = appearancePresets.find((preset) => preset.id === 'nous')!;
export const lightPalette: Palette = defaultPreset.light;
export const darkPalette: Palette = defaultPreset.dark;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

export const radius = {control: 8, panel: 12} as const;
export const touchTarget = 48;
import official from './official-presets.json';
