/**
 * React bindings over the connection/discovery/file-transfer singletons.
 */
import {useEffect, useState} from 'react';
import {connection, ConnectionState} from './net/ConnectionManager';
import {discovery, DiscoveredDevice} from './net/Discovery';
import {fileTransfer, Transfer} from './features/fileTransfer';
import {remoteBrowse, BrowseState} from './features/remoteBrowse';
import {screenMirror, MirrorState} from './features/screenMirror';
import {clipboardSync} from './features/clipboardSync';
import {messaging, MessagingState} from './features/messaging';
import {photos} from './features/photos';
import {PhotoItem} from './protocol/types';
import {notifications} from './features/notifications';
import {media} from './features/media';
import {autoConnect} from './features/autoConnect';
import {AutoDisconnectSetting, getAutoDisconnect} from './util/store';
import {cameraWebcam, CameraState} from './features/cameraWebcam';
import {DeviceInfo, MediaInfoPayload, NotifPostedPayload, StatusReportPayload} from './protocol/types';

export function useConnectionState(): {state: ConnectionState; detail?: string} {
  const [value, setValue] = useState<{state: ConnectionState; detail?: string}>({state: connection.getState()});
  useEffect(() => connection.on('state', (state, detail) => setValue({state, detail})), []);
  return value;
}

export function useDiscovery(): DiscoveredDevice[] {
  const [devices, setDevices] = useState<DiscoveredDevice[]>([]);
  useEffect(() => {
    discovery.start(); // idempotent; scanning runs for the app's lifetime (don't stop on unmount)
    return discovery.onChange(setDevices);
  }, []);
  return devices;
}

export function usePeer(): DeviceInfo | null {
  const [peer, setPeer] = useState<DeviceInfo | null>(null);
  useEffect(() => connection.on('peer', setPeer), []);
  return peer;
}

export function usePairing(): {required: boolean; error: string | null} {
  const [required, setRequired] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const offReq = connection.on('pairingRequired', () => setRequired(true));
    const offFail = connection.on('pairingFailed', msg => setError(msg));
    const offState = connection.on('state', s => {
      if (s === 'connected') {
        setRequired(false);
        setError(null);
      } else if (s === 'idle') {
        setRequired(false);
      }
    });
    return () => {
      offReq();
      offFail();
      offState();
    };
  }, []);
  return {required, error};
}

export function useTransfers(): Transfer[] {
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  useEffect(() => {
    fileTransfer.wire();
    return fileTransfer.onChange(setTransfers);
  }, []);
  return transfers;
}

export function useRemoteBrowse(): BrowseState {
  const [state, setState] = useState<BrowseState>({path: '', entries: [], loading: false});
  useEffect(() => {
    remoteBrowse.wire();
    return remoteBrowse.onChange(setState);
  }, []);
  return state;
}

export function useScreenMirror(): MirrorState {
  const [state, setState] = useState<MirrorState>(screenMirror.getState());
  useEffect(() => {
    screenMirror.wire();
    return screenMirror.onChange(setState);
  }, []);
  return state;
}

export function useClipboardSync(): {enabled: boolean; setEnabled: (v: boolean) => void} {
  const [enabled, setEnabledState] = useState(clipboardSync.isEnabled());
  useEffect(() => {
    clipboardSync.wire();
    return clipboardSync.onChange(setEnabledState);
  }, []);
  return {enabled, setEnabled: v => clipboardSync.setEnabled(v)};
}

export function useMessaging(): MessagingState {
  const [state, setState] = useState<MessagingState>({threads: [], contacts: [], messages: {}});
  useEffect(() => {
    messaging.wire();
    return messaging.onChange(setState);
  }, []);
  return state;
}

export function usePhotos(): PhotoItem[] {
  const [items, setItems] = useState<PhotoItem[]>([]);
  useEffect(() => {
    photos.wire();
    return photos.onChange(setItems);
  }, []);
  return items;
}

export function useNotifications(): NotifPostedPayload[] {
  const [items, setItems] = useState<NotifPostedPayload[]>([]);
  useEffect(() => {
    notifications.wire();
    return notifications.onChange(setItems);
  }, []);
  return items;
}

export function usePhoneStatus(): StatusReportPayload | null {
  const [status, setStatus] = useState<StatusReportPayload | null>(null);
  useEffect(() => {
    const offMsg = connection.on('message', env => {
      if (env.type === 'status.report') setStatus(env.payload as StatusReportPayload);
    });
    const offState = connection.on('state', s => {
      if (s === 'connected') connection.send('status.query', {});
    });
    return () => {
      offMsg();
      offState();
    };
  }, []);
  return status;
}

export function useMedia(): MediaInfoPayload | null {
  const [info, setInfo] = useState<MediaInfoPayload | null>(null);
  useEffect(() => {
    media.wire();
    return media.onChange(setInfo);
  }, []);
  return info;
}

export function useCameraWebcam(): CameraState {
  const [state, setState] = useState<CameraState>(cameraWebcam.getState());
  useEffect(() => {
    cameraWebcam.wire();
    return cameraWebcam.onChange(setState);
  }, []);
  return state;
}

export function useAutoDisconnect(): {
  setting: AutoDisconnectSetting;
  update: (s: AutoDisconnectSetting) => void;
} {
  const [setting, setSetting] = useState<AutoDisconnectSetting>({option: 'never', customHours: 2});
  useEffect(() => { void getAutoDisconnect().then(setSetting); }, []);
  function update(s: AutoDisconnectSetting) {
    setSetting(s);
    void autoConnect.updateSetting(s);
  }
  return {setting, update};
}
