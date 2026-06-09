import React, {useState} from 'react';
import {View, Text, ScrollView, Image, TouchableOpacity, TextInput} from 'react-native';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {useNotifications} from '../hooks';
import {notifications} from '../features/notifications';
import {nativeNotificationsAvailable} from '../native/notifier';
import {NotifPostedPayload} from '../protocol/types';

export function NotificationsScreen(): React.JSX.Element {
  const {s} = useTheme();
  const items = useNotifications();

  return (
    <ScrollView contentContainerStyle={s.screen}>
      <PageTitle>Notifications</PageTitle>
      <View style={s.card}>
        <Text style={s.dim}>
          {nativeNotificationsAvailable
            ? 'Mirrored from your phone as macOS banners. Messaging apps get smart replies.'
            : 'Native banners unavailable (rebuild the macOS app). Mirrored notifications still list here.'}
        </Text>
      </View>
      {items.length === 0 && (
        <View style={s.card}>
          <Text style={s.dim}>No notifications mirrored yet.</Text>
        </View>
      )}
      {items.map(n => (
        <NotificationItem key={n.key} n={n} />
      ))}
    </ScrollView>
  );
}

function NotificationItem({n}: {n: NotifPostedPayload}): React.JSX.Element {
  const {colors, s} = useTheme();
  const [draft, setDraft] = useState('');

  const send = (text: string) => {
    notifications.sendReply(n.key, text);
    setDraft('');
  };

  return (
    <View style={s.card}>
      <View style={[s.row, {alignItems: 'flex-start'}]}>
        {n.iconPng ? (
          <Image
            source={{uri: `data:image/png;base64,${n.iconPng}`}}
            style={{width: 36, height: 36, borderRadius: 8}}
          />
        ) : null}
        <View style={{flex: 1, gap: 2}}>
          <Text style={s.dim}>{n.appName || n.app}</Text>
          {!!n.title && <Text style={s.h2}>{n.title}</Text>}
          <Text style={s.body}>{n.text}</Text>
        </View>
      </View>

      {n.canReply && (
        <View style={{gap: 8, marginTop: 4}}>
          {n.suggestions.length > 0 && (
            <View style={{flexDirection: 'row', flexWrap: 'wrap', gap: 8}}>
              {n.suggestions.map(sug => (
                <TouchableOpacity key={sug} style={s.btnGhost} onPress={() => send(sug)}>
                  <Text style={[s.btnText, {color: colors.text}]}>{sug}</Text>
                </TouchableOpacity>
              ))}
            </View>
          )}
          <View style={s.row}>
            <TextInput
              style={[s.input, {flex: 1}]}
              placeholder="Reply…"
              placeholderTextColor={colors.textDim}
              value={draft}
              onChangeText={setDraft}
              onSubmitEditing={() => draft.trim() && send(draft)}
            />
            <TouchableOpacity style={s.btn} onPress={() => draft.trim() && send(draft)}>
              <Text style={s.btnText}>Send</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </View>
  );
}
