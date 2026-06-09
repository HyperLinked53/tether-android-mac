import React, {useEffect, useState} from 'react';
import {View, Text, TouchableOpacity, ScrollView} from 'react-native';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {media} from '../features/media';
import {useConnectionState, useMedia} from '../hooks';

function fmt(ms: number): string {
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  return `${m}:${String(s % 60).padStart(2, '0')}`;
}

export function MediaScreen(): React.JSX.Element {
  const {colors, s} = useTheme();
  const {state} = useConnectionState();
  const info = useMedia();
  const connected = state === 'connected';

  const [position, setPosition] = useState(0);
  useEffect(() => {
    if (!info) { setPosition(0); return; }
    const receivedAt = Date.now();
    const seedPos = info.position;

    if (!info.isPlaying) { setPosition(seedPos); return; }

    setPosition(seedPos);
    const id = setInterval(() => {
      const elapsed = Date.now() - receivedAt;
      const p = Math.min(seedPos + elapsed, info.duration || Infinity);
      setPosition(p);
    }, 500);
    return () => clearInterval(id);
  }, [info]);

  const duration = info?.duration ?? 0;
  const progress = duration > 0 ? Math.min(position / duration, 1) : 0;

  return (
    <ScrollView contentContainerStyle={s.screen}>
      <PageTitle>Media</PageTitle>

      {!connected && (
        <View style={s.card}>
          <Text style={s.dim}>Connect a phone to see what's playing.</Text>
        </View>
      )}

      {connected && !info && (
        <View style={s.card}>
          <Text style={s.dim}>Nothing playing on the phone right now.</Text>
        </View>
      )}

      {connected && info && (
        <View style={s.card}>
          {info.appName ? (
            <Text style={[s.dim, {fontSize: 11, textTransform: 'uppercase', letterSpacing: 1}]}>
              {info.appName}
            </Text>
          ) : null}

          <Text style={[s.body, {fontSize: 18, fontWeight: '700', marginTop: 4}]} numberOfLines={2}>
            {info.title || 'Unknown track'}
          </Text>
          {info.artist ? <Text style={s.dim} numberOfLines={1}>{info.artist}</Text> : null}
          {info.album  ? <Text style={[s.dim, {fontSize: 11}]} numberOfLines={1}>{info.album}</Text> : null}

          {duration > 0 && (
            <View style={{marginTop: 12, gap: 4}}>
              <View style={s.progressTrack}>
                <View style={{height: 4, width: `${progress * 100}%`, backgroundColor: colors.accent, borderRadius: 2}} />
              </View>
              <View style={[s.row, {justifyContent: 'space-between'}]}>
                <Text style={[s.dim, {fontSize: 11}]}>{fmt(position)}</Text>
                <Text style={[s.dim, {fontSize: 11}]}>{fmt(duration)}</Text>
              </View>
            </View>
          )}

          <View style={[s.row, {justifyContent: 'center', gap: 24, marginTop: 8}]}>
            <TouchableOpacity onPress={() => media.previous()}>
              <Text style={{fontSize: 28, color: colors.accent}}>⏮</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => media.play_pause()}>
              <Text style={{fontSize: 36, color: colors.accent}}>
                {info.isPlaying ? '⏸' : '▶'}
              </Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => media.next()}>
              <Text style={{fontSize: 28, color: colors.accent}}>⏭</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </ScrollView>
  );
}
