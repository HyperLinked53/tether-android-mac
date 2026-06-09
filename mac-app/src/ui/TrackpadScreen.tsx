import React from 'react';
import {View, Text, ScrollView} from 'react-native';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {cursor} from '../native/cursor';
import {useConnectionState} from '../hooks';

export function TrackpadScreen(): React.JSX.Element {
  const {s} = useTheme();
  const {state} = useConnectionState();
  const connected = state === 'connected';
  const cursorAvailable = !!cursor;

  return (
    <ScrollView contentContainerStyle={s.screen}>
      <PageTitle>Trackpad</PageTitle>
      <View style={s.card}>
        <Text style={s.h2}>Use your phone as a Mac trackpad</Text>
        <Text style={s.dim}>
          Open the Tether app on your Android phone and tap <Text style={{fontWeight: '700'}}>Remote Cursor</Text>.
          Your phone's touchscreen becomes a trackpad for this Mac.
        </Text>
        <Text style={s.dim}>
          {'  • '}Drag one finger — move cursor{'\n'}
          {'  • '}Drag two fingers — scroll{'\n'}
          {'  • '}Tap — left click{'\n'}
          {'  • '}Two-finger tap — right click{'\n'}
          {'  • '}Double tap — double click
        </Text>
        {!connected && <Text style={s.dim}>Connect a phone first.</Text>}
        {connected && !cursorAvailable && (
          <Text style={s.dim}>
            Native cursor module not loaded — rebuild the macOS app after the last AppDelegate.mm change.
          </Text>
        )}
        {connected && cursorAvailable && (
          <Text style={s.dim}>
            Ready. Open Remote Cursor on the phone to start.{'\n\n'}
            If the cursor doesn't move, grant Tether access in:{'\n'}
            System Settings → Privacy & Security → Accessibility.
          </Text>
        )}
      </View>
    </ScrollView>
  );
}
