import React from 'react';
import {View, Text} from 'react-native';
import {useTheme} from './theme';

/** Editorial page title: Georgia serif with a hairline rule below. Optionally accepts a right-side action. */
export function PageTitle({
  children,
  right,
}: {
  children: string;
  right?: React.ReactNode;
}): React.JSX.Element {
  const {colors, s} = useTheme();
  return (
    <View style={{gap: 10, marginBottom: 4}}>
      <View style={{flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'}}>
        <Text style={s.h1}>{children}</Text>
        {right}
      </View>
      <View style={s.divider} />
    </View>
  );
}

/** Uppercase label used as a section or card sub-header. */
export function SectionLabel({children}: {children: string}): React.JSX.Element {
  const {colors} = useTheme();
  return (
    <Text style={{color: colors.textDim, fontSize: 10, fontWeight: '700', letterSpacing: 1.4}}>
      {children.toUpperCase()}
    </Text>
  );
}
