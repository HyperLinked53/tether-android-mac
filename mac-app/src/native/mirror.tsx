/**
 * Wrapper over the native `TetherMirror` view (defined in macos AppDelegate.mm). Declarative: set
 * `host`/`port` and flip `active` to connect to the phone's screen socket. The H.264 bytes are read
 * and decoded natively (AVSampleBufferDisplayLayer) — they never cross the JS bridge.
 */
import React from 'react';
import {requireNativeComponent, View, ViewProps, HostComponent} from 'react-native';

interface NativeProps extends ViewProps {
  host?: string;
  port?: number;
  active?: boolean;
}

let Native: HostComponent<NativeProps> | null = null;
try {
  Native = requireNativeComponent<NativeProps>('TetherMirror');
} catch {
  Native = null; // module missing (app not rebuilt) — fall back to a blank View
}

export const mirrorAvailable = !!Native;

export function MirrorView(props: NativeProps): React.JSX.Element {
  if (!Native) {
    const {host, port, active, ...rest} = props;
    return <View {...rest} />;
  }
  return <Native {...props} />;
}
