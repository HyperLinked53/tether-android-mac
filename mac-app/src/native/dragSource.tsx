/**
 * Wrapper over the native `TetherDragSource` view (defined in macos AppDelegate.mm). Wrap a
 * phone-file row in this to make it draggable out to Finder. RN macOS has no drag-*out* support, so
 * this is a native NSFilePromiseProvider drag source.
 *
 * `onDragArm` fires when the user starts dragging — the parent should then `fs.pull` the file and
 * push the resulting download URL back via `fileUrl` (the native side waits for it before writing).
 */
import React from 'react';
import {requireNativeComponent, View, ViewProps, HostComponent} from 'react-native';

interface NativeProps extends ViewProps {
  fileUrl?: string;
  filename?: string;
  /** Multi-file drag: armed download URLs, one per dragged file. Overrides fileUrl. */
  fileUrls?: string[];
  /** Multi-file drag: filenames matching fileUrls. Overrides filename. */
  filenames?: string[];
  onDragArm?: () => void;
  /** A click that wasn't a drag — carries Shift/Cmd state for range/toggle selection.
   *  Named onItemClick (not onPress) to avoid colliding with RCTView's built-in `press` event. */
  onItemClick?: (e: {nativeEvent: {shiftKey?: boolean; metaKey?: boolean}}) => void;
}

let Native: HostComponent<NativeProps> | null = null;
try {
  Native = requireNativeComponent<NativeProps>('TetherDragSource');
} catch {
  Native = null; // module missing (e.g. app not rebuilt) — fall back to a plain View
}

export const dragSourceAvailable = !!Native;

export function DragSource(props: React.PropsWithChildren<NativeProps>): React.JSX.Element {
  if (!Native) {
    const {fileUrl, filename, fileUrls, filenames, onDragArm, onItemClick, ...rest} = props;
    return <View {...rest} />;
  }
  return <Native {...props} />;
}
