/**
 * Conduit Mac — root component. A simple sidebar navigates between feature screens, all sharing
 * the single `connection` singleton.
 */
import React, {useCallback, useEffect, useState} from 'react';
import {SafeAreaView, View, Text, TouchableOpacity, StatusBar} from 'react-native';
import {
  ThemeContext, ThemeValue,
  lightColors, darkColors,
  lightStyles, darkStyles,
  useTheme,
} from './src/ui/theme';
import {getItem, setItem} from './src/util/store';
import {connection} from './src/net/ConnectionManager';
import {discovery} from './src/net/Discovery';
import {notifications} from './src/features/notifications';
import {fileTransfer} from './src/features/fileTransfer';
import {remoteBrowse} from './src/features/remoteBrowse';
import {screenMirror} from './src/features/screenMirror';
import {clipboardSync} from './src/features/clipboardSync';
import {messaging} from './src/features/messaging';
import {photos} from './src/features/photos';
import {autoConnect} from './src/features/autoConnect';
import {touchpad} from './src/features/touchpad';
import {media} from './src/features/media';
import {cameraWebcam} from './src/features/cameraWebcam';
import {useConnectionState} from './src/hooks';
import {DevicesScreen} from './src/ui/DevicesScreen';
import {FilesScreen} from './src/ui/FilesScreen';
import {PhotosScreen} from './src/ui/PhotosScreen';
import {MessagesScreen} from './src/ui/MessagesScreen';
import {NotificationsScreen} from './src/ui/NotificationsScreen';
import {MirrorScreen} from './src/ui/MirrorScreen';
import {TrackpadScreen} from './src/ui/TrackpadScreen';
import {MediaScreen} from './src/ui/MediaScreen';
import {CameraScreen} from './src/ui/CameraScreen';

type Tab = 'devices' | 'files' | 'photos' | 'messages' | 'notifications' | 'mirror' | 'camera' | 'trackpad' | 'media';

const TABS: {key: Tab; label: string}[] = [
  {key: 'devices',       label: 'Devices'},
  {key: 'files',         label: 'Files'},
  {key: 'photos',        label: 'Photos'},
  {key: 'messages',      label: 'Messages'},
  {key: 'notifications', label: 'Notifications'},
  {key: 'mirror',        label: 'Screen'},
  {key: 'camera',        label: 'Camera'},
  {key: 'trackpad',      label: 'Trackpad'},
  {key: 'media',         label: 'Media'},
];

export default function App(): React.JSX.Element {
  const [isDark, setIsDark] = useState(false);

  // Load persisted preference once on mount.
  useEffect(() => {
    void getItem<boolean>('darkMode').then(stored => {
      if (stored != null) setIsDark(stored);
    });
  }, []);

  const toggle = useCallback(() => {
    setIsDark(prev => {
      const next = !prev;
      void setItem('darkMode', next);
      return next;
    });
  }, []);

  const themeValue: ThemeValue = {
    isDark,
    toggle,
    colors: isDark ? darkColors : lightColors,
    s:      isDark ? darkStyles  : lightStyles,
  };

  return (
    <ThemeContext.Provider value={themeValue}>
      <AppShell isDark={isDark} />
    </ThemeContext.Provider>
  );
}

function AppShell({isDark}: {isDark: boolean}): React.JSX.Element {
  const {colors, toggle} = useTheme();
  const [tab, setTab] = useState<Tab>('devices');
  const {state} = useConnectionState();

  useEffect(() => {
    void (async () => {
      await connection.init();
      notifications.wire();
      fileTransfer.wire();
      remoteBrowse.wire();
      screenMirror.wire();
      clipboardSync.wire();
      messaging.wire();
      photos.wire();
      touchpad.wire();
      media.wire();
      cameraWebcam.wire();
      discovery.start();
      void autoConnect.wire();
    })();
  }, []);

  const dotColor = state === 'connected' ? colors.good
                 : state === 'error'     ? colors.bad
                 : colors.borderStrong;

  return (
    <SafeAreaView style={{flex: 1, backgroundColor: colors.bg}}>
      <StatusBar barStyle={isDark ? 'light-content' : 'dark-content'} />
      <View style={{flex: 1, flexDirection: 'row'}}>

        {/* ── Sidebar ── */}
        <View style={{
          width: 192,
          backgroundColor: colors.panel,
          borderRightWidth: 1,
          borderRightColor: colors.border,
          paddingTop: 24,
          paddingBottom: 16,
        }}>
          {/* Wordmark */}
          <Text style={{
            color: colors.text,
            fontSize: 17,
            fontWeight: '400',
            fontFamily: 'Georgia',
            letterSpacing: 0.6,
            paddingHorizontal: 18,
            marginBottom: 20,
          }}>
            Tether
          </Text>

          {/* Nav items */}
          <View style={{gap: 1}}>
            {TABS.map(t => {
              const active = t.key === tab;
              return (
                <TouchableOpacity
                  key={t.key}
                  onPress={() => setTab(t.key)}
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    paddingVertical: 8,
                    paddingRight: 14,
                    paddingLeft: active ? 15 : 18,
                    marginHorizontal: 8,
                    borderRadius: 6,
                    borderLeftWidth: active ? 2 : 0,
                    borderLeftColor: colors.accent,
                    backgroundColor: active ? colors.accentLight : 'transparent',
                  }}>
                  <Text style={{
                    color: active ? colors.accent : colors.textSub,
                    fontSize: 13,
                    fontWeight: active ? '600' : '400',
                  }}>
                    {t.label}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>

          <View style={{flex: 1}} />

          {/* Theme toggle */}
          <TouchableOpacity
            onPress={toggle}
            style={{
              paddingHorizontal: 18,
              paddingVertical: 10,
              flexDirection: 'row',
              alignItems: 'center',
              gap: 7,
            }}>
            <Text style={{fontSize: 12, color: colors.textDim}}>
              {isDark ? '○' : '●'}
            </Text>
            <Text style={{color: colors.textDim, fontSize: 11, fontWeight: '500'}}>
              {isDark ? 'Light mode' : 'Dark mode'}
            </Text>
          </TouchableOpacity>

          {/* Connection status */}
          <View style={{
            paddingHorizontal: 18,
            paddingTop: 8,
            borderTopWidth: 1,
            borderTopColor: colors.border,
            flexDirection: 'row',
            alignItems: 'center',
            gap: 7,
          }}>
            <View style={{width: 6, height: 6, borderRadius: 3, backgroundColor: dotColor}} />
            <Text style={{color: colors.textDim, fontSize: 11, fontWeight: '500', textTransform: 'capitalize'}}>
              {state}
            </Text>
          </View>
        </View>

        {/* ── Content ── */}
        <View style={{flex: 1, backgroundColor: colors.bg}}>
          {tab === 'devices'       && <DevicesScreen />}
          {tab === 'files'         && <FilesScreen />}
          {tab === 'photos'        && <PhotosScreen />}
          {tab === 'messages'      && <MessagesScreen />}
          {tab === 'notifications' && <NotificationsScreen />}
          {tab === 'mirror'        && <MirrorScreen />}
          {tab === 'camera'        && <CameraScreen />}
          {tab === 'trackpad'      && <TrackpadScreen />}
          {tab === 'media'         && <MediaScreen />}
        </View>
      </View>
    </SafeAreaView>
  );
}
