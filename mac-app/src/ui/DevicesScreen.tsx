import React, {useState} from 'react';
import {View, Text, TouchableOpacity, TextInput, ScrollView, Switch} from 'react-native';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {connection} from '../net/ConnectionManager';
import {autoConnect} from '../features/autoConnect';
import {parsePairingPayload} from '../net/pairing';
import {
  useClipboardSync,
  useConnectionState,
  useDiscovery,
  usePairing,
  usePeer,
  usePhoneStatus,
} from '../hooks';

function isUrl(str: string): boolean {
  return /^(https?:\/\/)?.+\..+/.test(str.trim());
}

function normalizeUrl(str: string): string {
  const t = str.trim();
  return /^https?:\/\//i.test(t) ? t : `https://${t}`;
}

export function DevicesScreen(): React.JSX.Element {
  const {colors, s} = useTheme();
  const devices = useDiscovery();
  const {state, detail} = useConnectionState();
  const {required: pairingRequired, error: pairingError} = usePairing();
  const peer = usePeer();
  const status = usePhoneStatus();
  const clipboard = useClipboardSync();
  const [code, setCode] = useState('');
  const [urlInput, setUrlInput] = useState('');

  const connected = state === 'connected';

  function sendUrl(url: string): void {
    if (!isUrl(url)) return;
    connection.send('phone.openUrl', {url: normalizeUrl(url)});
    setUrlInput('');
  }

  const stateLabel: Record<string, string> = {
    idle: 'Not connected',
    connecting: 'Connecting…',
    pairing: 'Waiting for pairing code',
    connected: 'Connected',
    reconnecting: 'Reconnecting…',
    error: `Error: ${detail ?? ''}`,
  };
  const dot = connected ? colors.good : state === 'error' ? colors.bad : colors.textDim;

  return (
    <ScrollView contentContainerStyle={s.screen}>
      <PageTitle>Devices</PageTitle>

      <View style={s.card}>
        <View style={s.row}>
          <View style={{width: 8, height: 8, borderRadius: 4, backgroundColor: dot}} />
          <Text style={s.h2}>{stateLabel[state]}</Text>
        </View>
        {connected && peer && (
          <Text style={s.dim}>
            {peer.name} · {peer.platform}
            {status ? `  ·  🔋 ${status.battery.level}%${status.battery.charging ? ' ⚡' : ''}` : ''}
          </Text>
        )}
        <Text style={s.dim}>Paired phones reconnect automatically — no need to press Connect.</Text>
        {connected && (
          <TouchableOpacity style={s.btnGhost} onPress={() => connection.send('phone.ring', {})}>
            <Text style={[s.btnText, {color: colors.text}]}>Ring phone</Text>
          </TouchableOpacity>
        )}
        {(connected || state === 'reconnecting') && (
          <TouchableOpacity style={s.btnGhost} onPress={() => void autoConnect.forget()}>
            <Text style={[s.btnText, {color: colors.bad}]}>Forget device</Text>
          </TouchableOpacity>
        )}
      </View>

      <View style={s.card}>
        <View style={[s.row, {justifyContent: 'space-between'}]}>
          <Text style={s.h2}>Clipboard sync</Text>
          <Switch
            value={clipboard.enabled}
            onValueChange={clipboard.setEnabled}
            trackColor={{true: colors.accent, false: colors.border}}
          />
        </View>
        <Text style={s.dim}>
          {clipboard.enabled
            ? 'Text you copy syncs between this Mac and your phone automatically.'
            : 'Clipboard syncing is off.'}
        </Text>
      </View>

      {connected && (
        <View style={s.card}>
          <Text style={s.h2}>Send to phone</Text>
          <Text style={s.dim}>Open a URL on the phone's browser.</Text>
          <View style={[s.row, {gap: 8}]}>
            <TextInput
              style={[s.input, {flex: 1}]}
              placeholder="https://…"
              placeholderTextColor={colors.textDim}
              value={urlInput}
              onChangeText={setUrlInput}
              autoCapitalize="none"
              autoCorrect={false}
              onSubmitEditing={() => sendUrl(urlInput)}
            />
            <TouchableOpacity
              style={[s.btn, !isUrl(urlInput) && {opacity: 0.4}]}
              disabled={!isUrl(urlInput)}
              onPress={() => sendUrl(urlInput)}>
              <Text style={s.btnText}>Open</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {pairingRequired && (
        <View style={s.card}>
          <Text style={s.h2}>Pair this Mac</Text>
          <Text style={s.dim}>Enter the 6-digit code shown on your phone.</Text>
          <TextInput
            style={s.input}
            placeholder="123456"
            placeholderTextColor={colors.textDim}
            value={code}
            onChangeText={setCode}
            autoCapitalize="none"
          />
          {pairingError && <Text style={[s.dim, {color: colors.bad}]}>{pairingError} — re-enter the code.</Text>}
          <TouchableOpacity
            style={s.btn}
            onPress={() => connection.submitPairingSecret(parsePairingPayload(code).secret.trim())}>
            <Text style={s.btnText}>Pair</Text>
          </TouchableOpacity>
        </View>
      )}

      <View style={s.card}>
        <Text style={s.h2}>Discovered phones</Text>
        {devices.length === 0 && <Text style={s.dim}>Searching the local network…</Text>}
        {devices.map(d => (
          <View key={d.id} style={[s.row, {justifyContent: 'space-between'}]}>
            <View>
              <Text style={s.body}>{d.name}</Text>
              <Text style={s.dim}>{d.host}:{d.port}</Text>
            </View>
            <TouchableOpacity style={s.btn} onPress={() => connection.connect(d)}>
              <Text style={s.btnText}>Connect</Text>
            </TouchableOpacity>
          </View>
        ))}
      </View>
    </ScrollView>
  );
}
