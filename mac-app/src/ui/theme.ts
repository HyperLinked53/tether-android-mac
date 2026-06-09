import {StyleSheet} from 'react-native';
import {createContext, useContext} from 'react';

// ── Colour palettes ───────────────────────────────────────────────────────────

export interface ColorScheme {
  bg:           string;
  panel:        string;
  card:         string;
  border:       string;
  borderStrong: string;
  text:         string;
  textSub:      string;
  textDim:      string;
  accent:       string;
  accentLight:  string;
  good:         string;
  bad:          string;
}

export const lightColors: ColorScheme = {
  bg:           '#f9f6f1',
  panel:        '#f1ece4',
  card:         '#ffffff',
  border:       '#e6dfd5',
  borderStrong: '#cfc7bb',
  text:         '#1c1814',
  textSub:      '#66584a',
  textDim:      '#9c8e7f',
  accent:       '#c25c2a',
  accentLight:  '#f6ede6',
  good:         '#4a7a56',
  bad:          '#a83232',
};

export const darkColors: ColorScheme = {
  bg:           '#1a1714',
  panel:        '#201d1a',
  card:         '#252119',
  border:       '#3a332a',
  borderStrong: '#4f4438',
  text:         '#f2ede6',
  textSub:      '#b8a894',
  textDim:      '#7d6d5e',
  accent:       '#d4673a',   // slightly brightened on dark bg for contrast
  accentLight:  '#2e1c11',
  good:         '#5a9468',
  bad:          '#c24444',
};

// ── Style factories ───────────────────────────────────────────────────────────

export function makeStyles(c: ColorScheme) {
  return StyleSheet.create({
    screen:        {flexGrow: 1, padding: 28, gap: 20, backgroundColor: c.bg},
    h1:            {color: c.text, fontSize: 24, fontWeight: '400', fontFamily: 'Georgia', letterSpacing: 0.3},
    h2:            {color: c.text, fontSize: 14, fontWeight: '600'},
    label:         {color: c.textDim, fontSize: 10, fontWeight: '700', letterSpacing: 1.4},
    body:          {color: c.text, fontSize: 14, lineHeight: 20},
    dim:           {color: c.textDim, fontSize: 13, lineHeight: 18},
    card:          {backgroundColor: c.card, borderRadius: 8, padding: 18, borderWidth: 1, borderColor: c.border, gap: 12},
    row:           {flexDirection: 'row', alignItems: 'center', gap: 10},
    divider:       {height: 1, backgroundColor: c.border},
    btn:           {backgroundColor: c.accent, paddingVertical: 8, paddingHorizontal: 18, borderRadius: 5, alignItems: 'center'},
    btnGhost:      {borderColor: c.borderStrong, borderWidth: 1, paddingVertical: 8, paddingHorizontal: 18, borderRadius: 5, alignItems: 'center'},
    btnText:       {color: '#fff', fontWeight: '600', fontSize: 13},
    input:         {backgroundColor: c.bg, color: c.text, borderColor: c.border, borderWidth: 1, borderRadius: 5, padding: 10, fontSize: 14},
    progressTrack: {height: 4, backgroundColor: c.border, borderRadius: 2, overflow: 'hidden'},
  });
}

export type AppStyles = ReturnType<typeof makeStyles>;

// Pre-create both so StyleSheet IDs are assigned once at module load.
export const lightStyles = makeStyles(lightColors);
export const darkStyles  = makeStyles(darkColors);

// ── Theme context ─────────────────────────────────────────────────────────────

export interface ThemeValue {
  isDark:  boolean;
  toggle:  () => void;
  colors:  ColorScheme;
  s:       AppStyles;
}

export const ThemeContext = createContext<ThemeValue>({
  isDark:  false,
  toggle:  () => {},
  colors:  lightColors,
  s:       lightStyles,
});

export function useTheme(): ThemeValue {
  return useContext(ThemeContext);
}

// Legacy static exports — used by any file not yet consuming useTheme().
export const colors = lightColors;
export const s      = lightStyles;
