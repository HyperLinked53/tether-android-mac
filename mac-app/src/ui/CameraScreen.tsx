import React from 'react';
import {View, Text, TouchableOpacity, ScrollView, ActivityIndicator} from 'react-native';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {cameraWebcam} from '../features/cameraWebcam';
import {cameraWindowAvailable} from '../native/cameraWindow';
import {CameraFacing} from '../protocol/types';
import {useConnectionState, useCameraWebcam} from '../hooks';

const RESOLUTION_TIERS: {label: string; maxWidth: number; detail: string}[] = [
  {label: '480p', maxWidth: 854,  detail: '~0.3 MP · 2 Mbps'},
  {label: '720p', maxWidth: 1280, detail: '~0.9 MP · 4 Mbps'},
  {label: '1080p', maxWidth: 1920, detail: '~2 MP · 8 Mbps'},
];

export function CameraScreen(): React.JSX.Element {
  const {colors, s} = useTheme();
  const cam = useCameraWebcam();
  const {state} = useConnectionState();
  const connected = state === 'connected';
  const live = cam.status === 'live';

  return (
    <ScrollView contentContainerStyle={s.screen}>
      <PageTitle>Phone as webcam</PageTitle>

      <View style={s.card}>
        <View style={[s.row, {justifyContent: 'space-between'}]}>
          <Text style={s.h2}>Camera preview</Text>
          {!live ? (
            <TouchableOpacity
              style={[s.btn, !connected && {opacity: 0.4}]}
              disabled={!connected || cam.status === 'requesting'}
              onPress={() => cameraWebcam.requestStart(cam.facing)}>
              <Text style={s.btnText}>
                {cam.status === 'requesting' ? 'Starting…' : 'Start camera'}
              </Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity style={s.btnGhost} onPress={() => cameraWebcam.stop()}>
              <Text style={[s.btnText, {color: colors.text}]}>Stop</Text>
            </TouchableOpacity>
          )}
        </View>

        {!connected && <Text style={s.dim}>Connect a phone first.</Text>}

        {connected && !cameraWindowAvailable && (
          <Text style={[s.dim, {color: colors.bad}]}>
            Native camera module unavailable — rebuild the macOS app.
          </Text>
        )}

        {connected && cam.status === 'requesting' && (
          <View style={s.row}>
            <ActivityIndicator color={colors.accent} />
            <Text style={s.dim}>Opening camera…</Text>
          </View>
        )}

        {connected && cam.status === 'error' && cam.error?.includes('permission') && (
          <Text style={[s.dim, {color: colors.bad}]}>
            Camera permission not granted. Open Tether on your phone, go to Settings → Apps → Tether → Permissions and enable Camera.
          </Text>
        )}
        {connected && cam.status === 'error' && !cam.error?.includes('permission') && (
          <Text style={[s.dim, {color: colors.bad}]}>Camera error: {cam.error}</Text>
        )}

        {connected && cam.status === 'idle' && (
          <Text style={s.dim}>
            Streams the phone's camera to a floating Mac window in real time. Works over Wi-Fi and
            USB tethering.
            {'\n\n'}Note: the phone screen must stay on while the webcam is active.
          </Text>
        )}

        {live && (
          <Text style={s.dim}>
            Live in a separate window — drag it anywhere or resize it.
            {'\n'}Resolution: {cam.width}×{cam.height}
            {'\n'}Keep the phone screen on while using the webcam — turning it off will stop the camera feed.
          </Text>
        )}

        <FacingToggle facing={cam.facing} disabled={!connected} />
        <ResolutionPicker maxWidth={cam.maxWidth} disabled={!connected} />
        <MicToggle enabled={cam.micEnabled} disabled={!connected} />
      </View>

      <VirtualMicCard />
      <VirtualCameraCard />
    </ScrollView>
  );
}

function FacingToggle({
  facing,
  disabled,
}: {
  facing: CameraFacing;
  disabled: boolean;
}): React.JSX.Element {
  const {colors, s} = useTheme();
  const options: {key: CameraFacing; label: string}[] = [
    {key: 'front', label: 'Front'},
    {key: 'back', label: 'Back'},
  ];
  return (
    <View style={{gap: 6}}>
      <Text style={s.dim}>Camera</Text>
      <View style={[s.row, {gap: 8}]}>
        {options.map(o => {
          const on = o.key === facing;
          return (
            <TouchableOpacity
              key={o.key}
              disabled={disabled}
              onPress={() => cameraWebcam.switchCamera(o.key)}
              style={[
                {paddingVertical: 6, paddingHorizontal: 14, borderRadius: 8, borderWidth: 1},
                on
                  ? {backgroundColor: colors.accent, borderColor: colors.accent}
                  : {backgroundColor: 'transparent', borderColor: colors.border},
                disabled && {opacity: 0.4},
              ]}>
              <Text style={{color: on ? '#fff' : colors.textDim, fontWeight: '600', fontSize: 13}}>
                {o.label}
              </Text>
            </TouchableOpacity>
          );
        })}
      </View>
    </View>
  );
}

function ResolutionPicker({
  maxWidth,
  disabled,
}: {
  maxWidth: number;
  disabled: boolean;
}): React.JSX.Element {
  const {colors, s} = useTheme();
  return (
    <View style={{gap: 6}}>
      <Text style={s.dim}>Resolution</Text>
      <View style={[s.row, {gap: 8}]}>
        {RESOLUTION_TIERS.map(tier => {
          const on = tier.maxWidth === maxWidth;
          return (
            <TouchableOpacity
              key={tier.label}
              disabled={disabled}
              onPress={() => cameraWebcam.setResolution(tier.maxWidth)}
              style={[
                {paddingVertical: 6, paddingHorizontal: 14, borderRadius: 8, borderWidth: 1},
                on
                  ? {backgroundColor: colors.accent, borderColor: colors.accent}
                  : {backgroundColor: 'transparent', borderColor: colors.border},
                disabled && {opacity: 0.4},
              ]}>
              <Text style={{color: on ? '#fff' : colors.textDim, fontWeight: '600', fontSize: 13}}>
                {tier.label}
              </Text>
            </TouchableOpacity>
          );
        })}
      </View>
      <Text style={[s.dim, {fontSize: 11}]}>
        {RESOLUTION_TIERS.find(t => t.maxWidth === maxWidth)?.detail ?? ''}
        {maxWidth >= 1920 ? ' — requires a good Wi-Fi connection' : ''}
      </Text>
    </View>
  );
}

function MicToggle({enabled, disabled}: {enabled: boolean; disabled: boolean}): React.JSX.Element {
  const {colors, s} = useTheme();
  return (
    <View style={{gap: 6}}>
      <Text style={s.dim}>Microphone</Text>
      <TouchableOpacity
        disabled={disabled}
        onPress={() => cameraWebcam.toggleMic()}
        style={[
          s.row,
          {gap: 8, paddingVertical: 6, paddingHorizontal: 14, borderRadius: 8, borderWidth: 1,
           alignSelf: 'flex-start',
           backgroundColor: enabled ? colors.accent + '22' : 'transparent',
           borderColor: enabled ? colors.accent : colors.border},
          disabled && {opacity: 0.4},
        ]}>
        <View style={{
          width: 12, height: 12, borderRadius: 6,
          backgroundColor: enabled ? colors.accent : colors.border,
        }} />
        <Text style={{color: enabled ? colors.accent : colors.textDim, fontWeight: '600', fontSize: 13}}>
          {enabled ? 'On' : 'Off'}
        </Text>
      </TouchableOpacity>
      {enabled && (
        <Text style={[s.dim, {fontSize: 11}]}>
          Install the Tether Microphone driver below to use in Zoom / FaceTime.
        </Text>
      )}
    </View>
  );
}

function VirtualMicCard(): React.JSX.Element {
  const {colors, s} = useTheme();
  return (
    <View style={s.card}>
      <Text style={s.h2}>Using as microphone in Zoom / FaceTime</Text>
      <Text style={s.dim}>
        Tether streams the phone&apos;s microphone to the Mac and routes it through{' '}
        <Text style={{fontWeight: '600', color: colors.text}}>BlackHole</Text> — a free, open-source
        virtual audio device. BlackHole acts as a loopback: Tether plays the phone audio into it,
        and Zoom / FaceTime see it as a selectable microphone input.
      </Text>

      <Text style={[s.dim, {marginTop: 10, fontWeight: '600', color: colors.text}]}>
        Install BlackHole (one-time)
      </Text>
      <Text style={s.dim}>
        Open Terminal and run:{'\n'}
        {'   '}brew install blackhole-2ch{'\n\n'}
        If you don&apos;t have Homebrew: visit brew.sh to install it first (free, takes ~2 min).
      </Text>

      <Text style={[s.dim, {marginTop: 10, fontWeight: '600', color: colors.text}]}>
        How to use
      </Text>
      <Text style={s.dim}>
        Enable the Microphone toggle above (in the Camera preview card). Tether automatically sets
        BlackHole as your Mac&apos;s system microphone input, so{' '}
        <Text style={{fontWeight: '600', color: colors.text}}>every app</Text> — Zoom, FaceTime,
        Google Meet, Teams, Discord, Slack, browsers — receives the phone audio with no per-app
        configuration. When you turn the toggle off, your previous microphone is restored.
      </Text>

      <Text style={[s.dim, {marginTop: 10, fontWeight: '600', color: colors.text}]}>
        Notes
      </Text>
      <Text style={s.dim}>
        The phone screen must stay on (same requirement as the camera).
        {'\n'}BlackHole is made by Existential Audio and signed with a Developer ID, so macOS 26
        accepts it without security warnings.
      </Text>
    </View>
  );
}

function VirtualCameraCard(): React.JSX.Element {
  const {colors, s} = useTheme();
  return (
    <View style={s.card}>
      <Text style={s.h2}>Using in Zoom / FaceTime</Text>
      <Text style={s.dim}>
        macOS 26 removed the old CMIO DAL plug-in format. The replacement (Camera Extension)
        requires a paid Apple Developer account — that&apos;s on the roadmap. For now, use OBS:
      </Text>

      <Text style={[s.dim, {marginTop: 10, fontWeight: '600', color: colors.text}]}>
        Setup (one-time)
      </Text>
      <Text style={s.dim}>
        1. Install OBS Studio (free — obsproject.com).{'\n'}
        2. Launch OBS. In the Scenes panel keep the default scene.{'\n'}
        3. In Sources: click + → Window Capture.{'\n'}
        4. Name it anything, click OK.{'\n'}
        5. In the Window drop-down, choose &quot;Camera — Tether&quot; (start the camera preview
        first so the window exists).{'\n'}
        6. Click OK. You should see the camera feed in OBS.{'\n'}
        7. Click Start Virtual Camera (bottom-right Controls panel).
      </Text>

      <Text style={[s.dim, {marginTop: 10, fontWeight: '600', color: colors.text}]}>
        In Zoom / FaceTime
      </Text>
      <Text style={s.dim}>
        Choose OBS Virtual Camera as your camera input.
      </Text>

      <Text style={[s.dim, {marginTop: 10, fontWeight: '600', color: colors.text}]}>
        "Camera — Tether" not in OBS&apos;s window list?
      </Text>
      <Text style={s.dim}>
        macOS requires you to grant OBS permission to capture each app&apos;s windows separately.{'\n'}
        Fix: System Settings → Privacy &amp; Security → Screen &amp; System Audio Recording →
        find OBS → toggle it off and back on. Then restart OBS. When OBS tries to add a Window
        Capture, macOS will ask &quot;Allow OBS to record from Tether?&quot; — click Allow.
      </Text>
    </View>
  );
}
