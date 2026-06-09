import React, {useState, useEffect} from 'react';
import {View, Text, TouchableOpacity, ScrollView, ViewProps, ActivityIndicator} from 'react-native';
import RNFS from 'react-native-fs';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {fileTransfer, Transfer, INBOX} from '../features/fileTransfer';
import {remoteBrowse} from '../features/remoteBrowse';
import {FsEntry} from '../protocol/types';
import {filePickerAvailable, pickFile, savePanel} from '../native/filePicker';
import {DragSource} from '../native/dragSource';
import {useConnectionState, useTransfers, useRemoteBrowse} from '../hooks';

export function FilesScreen(): React.JSX.Element {
  const {colors, s} = useTheme();
  const transfers = useTransfers();
  const browse = useRemoteBrowse();
  const {state} = useConnectionState();
  const connected = state === 'connected';
  const [pickerError, setPickerError] = useState<string | null>(null);
  const [dropActive, setDropActive] = useState(false);

  async function pickAndSend() {
    setPickerError(null);
    if (!filePickerAvailable) {
      setPickerError('Native file picker unavailable — rebuild the macOS app.');
      return;
    }
    try {
      const file = await pickFile();
      if (file) await fileTransfer.sendFile(file);
    } catch (e) {
      setPickerError(`Could not send the file: ${String(e)}`);
      console.warn('pick failed', e);
    }
  }

  const dropProps = {
    draggedTypes: ['fileUrl'],
    onDragEnter: () => setDropActive(true),
    onDragLeave: () => setDropActive(false),
    onDrop: (e: {nativeEvent?: {dataTransfer?: {files?: Array<{uri?: string; name?: string; size?: number}>}}}) => {
      setDropActive(false);
      const files = e?.nativeEvent?.dataTransfer?.files ?? [];
      for (const f of files) {
        if (f?.uri) void fileTransfer.sendFile({path: f.uri, name: f.name ?? 'file', size: f.size ?? 0});
      }
    },
  } as unknown as ViewProps;

  return (
    <View
      {...dropProps}
      style={[{flex: 1}, dropActive && {borderWidth: 2, borderColor: colors.accent, borderRadius: 12}]}>
      <ScrollView contentContainerStyle={s.screen}>
        <PageTitle>Files</PageTitle>

        <View style={s.card}>
          <Text style={s.dim}>
            Drag files anywhere onto this window to send them to the phone, or use the button. Files
            you receive land in {`~/Documents/TetherInbox`}.
          </Text>
          <TouchableOpacity
            style={[s.btn, !connected && {opacity: 0.4}]}
            disabled={!connected}
            onPress={pickAndSend}>
            <Text style={s.btnText}>Send a file to phone</Text>
          </TouchableOpacity>
          {!connected && <Text style={s.dim}>Connect a phone first.</Text>}
          {pickerError && <Text style={[s.dim, {color: colors.bad}]}>{pickerError}</Text>}
        </View>

        <PhoneBrowser connected={connected} entries={browse.entries} path={browse.path}
          loading={browse.loading} error={browse.error} />

        <View style={s.card}>
          <Text style={s.h2}>Transfers</Text>
          {transfers.length === 0 && <Text style={s.dim}>No transfers yet.</Text>}
          {transfers.map(t => (
            <TransferRow key={t.id} t={t} />
          ))}
        </View>
      </ScrollView>
    </View>
  );
}

function PhoneBrowser(props: {
  connected: boolean;
  entries: FsEntry[];
  path: string;
  loading: boolean;
  error?: string;
}): React.JSX.Element {
  const {colors, s} = useTheme();
  const {connected, entries, path, loading, error} = props;
  const folderName = path ? path.split('/').filter(Boolean).pop() ?? path : 'Phone';

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [anchor, setAnchor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const fileEntries = entries.filter(e => !e.isDir);

  useEffect(() => { setSelected(new Set()); setAnchor(null); }, [path]);

  function onFileClick(entry: FsEntry, mods: {shiftKey?: boolean; metaKey?: boolean}): void {
    setSelected(prev => {
      if (mods.shiftKey && anchor) {
        const order = fileEntries.map(e => e.path);
        const a = order.indexOf(anchor);
        const b = order.indexOf(entry.path);
        if (a >= 0 && b >= 0) {
          const [lo, hi] = a < b ? [a, b] : [b, a];
          return new Set(order.slice(lo, hi + 1));
        }
      }
      const next = new Set(prev);
      if (mods.metaKey) {
        if (next.has(entry.path)) next.delete(entry.path);
        else next.add(entry.path);
        return next;
      }
      return new Set([entry.path]);
    });
    if (!mods.shiftKey) setAnchor(entry.path);
  }

  const selectedEntries = fileEntries.filter(e => selected.has(e.path));

  async function downloadSelected(toChosenFolder: boolean): Promise<void> {
    if (busy || selectedEntries.length === 0) return;
    setBusy(true);
    try {
      let destDir = INBOX;
      if (toChosenFolder) {
        const chosen = await savePanel(selectedEntries[0].name);
        if (!chosen) return;
        destDir = chosen.includes('/') ? chosen.replace(/\/[^/]+$/, '') : chosen;
      } else {
        await RNFS.mkdir(INBOX).catch(() => {});
      }
      const pulled = await remoteBrowse.pullMultiple(selectedEntries.map(e => e.path));
      await Promise.all(pulled.map(f =>
        fileTransfer.downloadUrl({
          transferId: f.transferId, url: f.url, name: f.name, size: f.size,
          dest: `${destDir}/${f.name}`,
        }),
      ));
    } catch (e) {
      console.warn('[fs] batch download failed', e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={s.card}>
      <View style={[s.row, {justifyContent: 'space-between'}]}>
        <Text style={s.h2}>Phone files</Text>
        <View style={s.row}>
          {remoteBrowse.canGoUp() && (
            <TouchableOpacity onPress={() => remoteBrowse.up()}>
              <Text style={[s.dim, {color: colors.accent}]}>↑ Up</Text>
            </TouchableOpacity>
          )}
          <TouchableOpacity onPress={() => remoteBrowse.refresh()} disabled={!connected}>
            <Text style={[s.dim, {color: connected ? colors.accent : colors.textDim}]}>Refresh</Text>
          </TouchableOpacity>
        </View>
      </View>

      {connected && <Text style={s.dim} numberOfLines={1}>📂 {folderName}</Text>}
      {connected && fileEntries.length > 0 && selectedEntries.length === 0 && (
        <Text style={s.dim}>Click to select, Shift-click for a range, ⌘-click to toggle. Then drag any selected file out, or use the buttons below.</Text>
      )}

      {!connected && <Text style={s.dim}>Connect a phone to browse its files.</Text>}
      {connected && error === 'permission' && (
        <Text style={s.dim}>
          Enable "All files access" on the phone: open Tether on the phone and tap "Grant all files
          access", then Refresh.
        </Text>
      )}
      {connected && error && error !== 'permission' && error !== 'not_connected' && (
        <Text style={[s.dim, {color: colors.bad}]}>Couldn't open this folder ({error}).</Text>
      )}
      {connected && loading && (
        <View style={s.row}><ActivityIndicator color={colors.accent} /><Text style={s.dim}>Loading…</Text></View>
      )}
      {connected && !loading && !error && entries.length === 0 && (
        <Text style={s.dim}>Empty folder.</Text>
      )}

      {selectedEntries.length > 0 && (
        <View style={[s.row, {justifyContent: 'space-between', paddingVertical: 6,
          borderTopWidth: 1, borderTopColor: colors.border, marginTop: 4}]}>
          <Text style={[s.dim, {color: colors.accent}]}>{selectedEntries.length} selected</Text>
          <View style={s.row}>
            <TouchableOpacity onPress={() => downloadSelected(false)} disabled={busy}>
              <Text style={[s.dim, {color: busy ? colors.textDim : colors.accent}]}>↓ Download all</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => downloadSelected(true)} disabled={busy}>
              <Text style={[s.dim, {color: busy ? colors.textDim : colors.accent}]}>↓ Save all to…</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => { setSelected(new Set()); setAnchor(null); }}>
              <Text style={[s.dim, {color: colors.textDim}]}>Clear</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {connected && !error && entries.map(entry =>
        entry.isDir
          ? <FolderRow key={entry.path} entry={entry} />
          : (
            <PhoneFileRow
              key={entry.path}
              entry={entry}
              selected={selected.has(entry.path)}
              selectedEntries={selectedEntries}
              onClick={onFileClick}
            />
          ),
      )}
    </View>
  );
}

function FolderRow({entry}: {entry: FsEntry}): React.JSX.Element {
  const {s} = useTheme();
  return (
    <TouchableOpacity style={[s.row, {paddingVertical: 4}]} onPress={() => remoteBrowse.open(entry)}>
      <Text style={s.body} numberOfLines={1}>📁 {entry.name}</Text>
    </TouchableOpacity>
  );
}

function PhoneFileRow({entry, selected, selectedEntries, onClick}: {
  entry: FsEntry;
  selected: boolean;
  selectedEntries: FsEntry[];
  onClick: (entry: FsEntry, mods: {shiftKey?: boolean; metaKey?: boolean}) => void;
}): React.JSX.Element {
  const {colors, s} = useTheme();
  const [armedUrl, setArmedUrl] = useState('');
  const [armedUrls, setArmedUrls] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const dragAll = selected && selectedEntries.length > 1;
  const dragNames = dragAll ? selectedEntries.map(e => e.name) : null;

  async function arm(): Promise<void> {
    try {
      if (dragAll) {
        const pulled = await remoteBrowse.pullMultiple(selectedEntries.map(e => e.path));
        setArmedUrls(pulled.map(f => f.url));
      } else {
        const f = await remoteBrowse.pull(entry.path);
        setArmedUrl(f.url);
      }
    } catch (e) {
      console.warn('[fs] arm failed', e);
    }
  }

  async function download(toChosenFolder: boolean): Promise<void> {
    if (busy) return;
    setBusy(true);
    try {
      const f = await remoteBrowse.pull(entry.path);
      let dest: string;
      if (toChosenFolder) {
        const chosen = await savePanel(entry.name);
        if (!chosen) return;
        dest = chosen;
      } else {
        await RNFS.mkdir(INBOX).catch(() => {});
        dest = `${INBOX}/${entry.name}`;
      }
      await fileTransfer.downloadUrl({transferId: f.transferId, url: f.url, name: f.name, size: f.size, dest});
    } catch (e) {
      console.warn('[fs] download failed', e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={[s.row, {justifyContent: 'space-between', paddingVertical: 4},
      selected && {backgroundColor: colors.accent + '22', borderRadius: 6}]}>
      <DragSource
        style={{flex: 1}}
        filename={dragAll ? undefined : entry.name}
        fileUrl={dragAll ? undefined : armedUrl}
        filenames={dragAll ? dragNames ?? undefined : undefined}
        fileUrls={dragAll ? armedUrls : undefined}
        onDragArm={arm}
        onItemClick={e => onClick(entry, e.nativeEvent)}>
        <Text style={s.body} numberOfLines={1}>📄 {entry.name}</Text>
      </DragSource>
      <View style={s.row}>
        <TouchableOpacity onPress={() => download(false)} disabled={busy}>
          <Text style={[s.dim, {color: busy ? colors.textDim : colors.accent}]}>Download</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => download(true)} disabled={busy}>
          <Text style={[s.dim, {color: busy ? colors.textDim : colors.accent}]}>Save to…</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function TransferRow({t}: {t: Transfer}): React.JSX.Element {
  const {colors, s} = useTheme();
  const pct = t.size > 0 ? Math.min(1, t.transferred / t.size) : t.status === 'completed' ? 1 : 0;
  const arrow = t.direction === 'incoming' ? '↓' : '↑';
  const color = t.status === 'failed' ? colors.bad : t.status === 'completed' ? colors.good : colors.accent;
  return (
    <View style={{gap: 6}}>
      <View style={[s.row, {justifyContent: 'space-between'}]}>
        <Text style={s.body} numberOfLines={1}>{arrow} {t.name}</Text>
        <Text style={s.dim}>{t.status === 'failed' ? (t.error ?? 'failed') : `${Math.round(pct * 100)}%`}</Text>
      </View>
      <View style={s.progressTrack}>
        <View style={{height: 4, width: `${pct * 100}%`, backgroundColor: color}} />
      </View>
    </View>
  );
}
