import React from 'react';
import {View, Text, TouchableOpacity, ScrollView, ActivityIndicator} from 'react-native';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {screenMirror} from '../features/screenMirror';
import {mirrorWindowAvailable} from '../native/mirrorWindow';
import {AudioTarget} from '../protocol/types';
import {useConnectionState, useScreenMirror} from '../hooks';

export function MirrorScreen(): React.JSX.Element {
  const {colors, s} = useTheme();
  const mirror = useScreenMirror();
  const {state} = useConnectionState();
  const connected = state === 'connected';
  const live = mirror.status === 'live';

  return (
    <ScrollView contentContainerStyle={s.screen}>
      <PageTitle>Screen mirroring</PageTitle>

      <View style={s.card}>
        <View style={[s.row, {justifyContent: 'space-between'}]}>
          <Text style={s.h2}>Phone screen</Text>
          {!live ? (
            <TouchableOpacity
              style={[s.btn, !connected && {opacity: 0.4}]}
              disabled={!connected || mirror.status === 'requesting'}
              onPress={() => screenMirror.requestStart()}>
              <Text style={s.btnText}>
                {mirror.status === 'requesting' ? 'Requesting…' : 'Start mirroring'}
              </Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity style={s.btnGhost} onPress={() => screenMirror.stop()}>
              <Text style={[s.btnText, {color: colors.text}]}>Stop</Text>
            </TouchableOpacity>
          )}
        </View>

        {!connected && <Text style={s.dim}>Connect a phone first.</Text>}
        {connected && !mirrorWindowAvailable && (
          <Text style={[s.dim, {color: colors.bad}]}>
            Native mirror module unavailable — rebuild the macOS app.
          </Text>
        )}
        {connected && mirror.status === 'requesting' && (
          <View style={s.row}>
            <ActivityIndicator color={colors.accent} />
            <Text style={s.dim}>Approve the screen-capture prompt on your phone…</Text>
          </View>
        )}
        <AudioToggle target={mirror.audio} onChange={t => screenMirror.setAudioTarget(t)} />

        {live && (
          <>
            <Text style={s.dim}>
              Mirroring in a separate window — drag it anywhere or resize it. Click/drag to control
              the phone, scroll to scroll, and type with your keyboard (into focused fields). Esc =
              Back. Back / Home / Recents sit in a strip below the mirror.
            </Text>
            <Text style={s.dim}>
              Control needs Tether's accessibility service enabled on the phone (Tether shows a
              prompt for it).
            </Text>
          </>
        )}
        {mirror.status === 'error' && (
          <Text style={[s.dim, {color: colors.bad}]}>Mirroring failed: {mirror.error}</Text>
        )}
        {connected && mirror.status === 'idle' && (
          <Text style={s.dim}>
            Opens the phone screen in its own movable window. Start from here, or tap "Start
            mirroring" on the phone. You'll approve a one-time screen-capture prompt on the phone.
          </Text>
        )}
      </View>
    </ScrollView>
  );
}

function AudioToggle({target, onChange}: {target: AudioTarget; onChange: (t: AudioTarget) => void}): React.JSX.Element {
  const {colors, s} = useTheme();
  const options: {key: AudioTarget; label: string}[] = [
    {key: 'phone', label: 'Phone'},
    {key: 'mac', label: 'Mac'},
  ];
  return (
    <View style={{gap: 6}}>
      <Text style={s.dim}>Audio output</Text>
      <View style={[s.row, {gap: 8}]}>
        {options.map(o => {
          const on = o.key === target;
          return (
            <TouchableOpacity
              key={o.key}
              onPress={() => onChange(o.key)}
              style={[
                {paddingVertical: 6, paddingHorizontal: 14, borderRadius: 8, borderWidth: 1},
                on
                  ? {backgroundColor: colors.accent, borderColor: colors.accent}
                  : {backgroundColor: 'transparent', borderColor: colors.border},
              ]}>
              <Text style={{color: on ? '#fff' : colors.textDim, fontWeight: '600', fontSize: 13}}>
                {o.label}
              </Text>
            </TouchableOpacity>
          );
        })}
      </View>
      {target === 'mac' && (
        <Text style={s.dim}>
          Phone audio plays on this Mac. The phone may still play it too — lower the phone's volume to
          avoid hearing both.
        </Text>
      )}
    </View>
  );
}
