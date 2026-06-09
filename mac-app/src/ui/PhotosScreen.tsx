import React, {useState} from 'react';
import {View, Text, ScrollView, Image, ViewProps, TouchableOpacity} from 'react-native';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {photos} from '../features/photos';
import {fileTransfer} from '../features/fileTransfer';
import {DragSource} from '../native/dragSource';
import {PhotoItem} from '../protocol/types';
import {useConnectionState, usePhotos} from '../hooks';

const CELL = 116;

export function PhotosScreen(): React.JSX.Element {
  const {colors, s} = useTheme();
  const items = usePhotos();
  const {state} = useConnectionState();
  const connected = state === 'connected';
  const [dropActive, setDropActive] = useState(false);

  const dropProps = {
    draggedTypes: ['fileUrl'],
    onDragEnter: () => setDropActive(true),
    onDragLeave: () => setDropActive(false),
    onDrop: (e: {nativeEvent?: {dataTransfer?: {files?: Array<{uri?: string; name?: string; size?: number}>}}}) => {
      setDropActive(false);
      for (const f of e?.nativeEvent?.dataTransfer?.files ?? []) {
        if (f?.uri) void fileTransfer.sendFile({path: f.uri, name: f.name ?? 'file', size: f.size ?? 0});
      }
    },
  } as unknown as ViewProps;

  return (
    <View
      {...dropProps}
      style={[{flex: 1}, dropActive && {borderWidth: 2, borderColor: colors.accent, borderRadius: 12}]}>
      <ScrollView style={{flex: 1}} contentContainerStyle={s.screen}>
        <PageTitle right={connected ? (
          <TouchableOpacity style={s.btnGhost} onPress={() => photos.load()}>
            <Text style={[s.btnText, {color: colors.text}]}>Refresh</Text>
          </TouchableOpacity>
        ) : undefined}>Photos</PageTitle>

        {!connected && <Text style={s.dim}>Connect a phone to browse its photos.</Text>}
        {connected && items.length === 0 && (
          <Text style={s.dim}>No photos yet — grant photo access on the phone, then Refresh.</Text>
        )}
        {connected && (
          <Text style={s.dim}>
            Drag a photo to your Mac to save it; drag images here to send them to the phone.
          </Text>
        )}

        <View style={{flexDirection: 'row', flexWrap: 'wrap', gap: 6}}>
          {items.map(p => (
            <PhotoCell key={p.id} photo={p} />
          ))}
        </View>
      </ScrollView>
    </View>
  );
}

function PhotoCell({photo}: {photo: PhotoItem}): React.JSX.Element {
  const {colors} = useTheme();
  const [armedUrl, setArmedUrl] = useState('');

  async function arm(): Promise<void> {
    try {
      const f = await photos.pull(photo.id);
      setArmedUrl(f.url);
    } catch (e) {
      console.warn('[photos] arm failed', e);
    }
  }

  return (
    <DragSource
      style={{width: CELL, height: CELL, borderRadius: 6, overflow: 'hidden', backgroundColor: colors.card}}
      filename={photo.name}
      fileUrl={armedUrl}
      onDragArm={arm}>
      <Image source={{uri: photo.thumbUrl}} style={{width: CELL, height: CELL}} resizeMode="cover" />
    </DragSource>
  );
}
