import React, {useEffect, useRef, useState} from 'react';
import {View, Text, TouchableOpacity, TextInput, ScrollView, Image} from 'react-native';
import {useTheme} from './theme';
import {PageTitle} from './components';
import {messaging} from '../features/messaging';
import {SmsMessage, SmsThread} from '../protocol/types';
import {useConnectionState, useMessaging} from '../hooks';

interface Target {
  threadId?: string;
  address: string;
  name?: string;
}

export function MessagesScreen(): React.JSX.Element {
  const {colors, s} = useTheme();
  const {threads, contacts, messages} = useMessaging();
  const {state} = useConnectionState();
  const connected = state === 'connected';

  const [target, setTarget] = useState<Target | null>(null);
  const [composing, setComposing] = useState(false);
  const [query, setQuery] = useState('');
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState<SmsMessage[]>([]);
  const threadScroll = useRef<ScrollView>(null);

  useEffect(() => {
    if (connected) messaging.loadThreads();
  }, [connected]);

  useEffect(() => {
    if (!connected || composing) return;
    const tick = () => {
      if (target?.threadId) messaging.openThread(target.threadId);
      else if (!target) messaging.loadThreads();
    };
    const id = setInterval(tick, 2000);
    return () => clearInterval(id);
  }, [connected, composing, target]);

  function openExisting(t: SmsThread): void {
    setComposing(false);
    setPending([]);
    setTarget({threadId: t.id, address: t.address, name: t.name});
    messaging.openThread(t.id);
  }

  function startCompose(): void {
    setComposing(true);
    setTarget(null);
    setQuery('');
    messaging.searchContacts('');
  }

  function pickContact(name: string | undefined, number: string): void {
    const norm = number.replace(/\s/g, '');
    const existing = threads.find(t => t.address.replace(/\s/g, '') === norm);
    setComposing(false);
    setPending([]);
    if (existing) {
      setTarget({threadId: existing.id, address: existing.address, name: existing.name});
      messaging.openThread(existing.id);
    } else {
      setTarget({address: number, name});
    }
  }

  function send(): void {
    if (!target || !draft.trim()) return;
    messaging.send(target.address, draft, target.threadId);
    if (!target.threadId) {
      setPending(p => [...p, {id: `p-${Date.now()}`, body: draft, date: Date.now(), mine: true}]);
    }
    setDraft('');
  }

  if (!connected) {
    return (
      <ScrollView contentContainerStyle={s.screen}>
        <PageTitle>Messages</PageTitle>
        <View style={s.card}><Text style={s.dim}>Connect a phone to load conversations.</Text></View>
      </ScrollView>
    );
  }

  if (composing) {
    const numberLike = /^[+\d][\d\s\-()]*$/.test(query.trim()) && query.trim().length >= 3;
    return (
      <ScrollView style={{flex: 1}} contentContainerStyle={s.screen}>
        <PageTitle right={
          <TouchableOpacity style={s.btnGhost} onPress={() => setComposing(false)}>
            <Text style={[s.btnText, {color: colors.text}]}>Cancel</Text>
          </TouchableOpacity>
        }>New message</PageTitle>
        <TextInput
          style={s.input}
          placeholder="Search contacts or type a number"
          placeholderTextColor={colors.textDim}
          autoFocus
          value={query}
          onChangeText={t => {
            setQuery(t);
            messaging.searchContacts(t);
          }}
        />
        {numberLike && (
          <TouchableOpacity style={s.card} onPress={() => pickContact(undefined, query.trim())}>
            <Text style={s.h2}>Send to {query.trim()}</Text>
            <Text style={s.dim}>Use this number</Text>
          </TouchableOpacity>
        )}
        {contacts.map(c => (
          <TouchableOpacity key={c.number} style={s.card} onPress={() => pickContact(c.name, c.number)}>
            <Text style={s.h2}>{c.name}</Text>
            <Text style={s.dim}>{c.number}</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>
    );
  }

  if (target) {
    const msgs = [...(target.threadId ? messages[target.threadId] ?? [] : []), ...pending];
    return (
      <View style={{flex: 1}}>
        <View style={{padding: 20, paddingBottom: 8, gap: 10}}>
          <View style={{flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'}}>
            <Text style={s.h1} numberOfLines={1}>{target.name || target.address}</Text>
            <TouchableOpacity style={s.btnGhost} onPress={() => setTarget(null)}>
              <Text style={[s.btnText, {color: colors.text}]}>Back</Text>
            </TouchableOpacity>
          </View>
          <View style={s.divider} />
        </View>
        <ScrollView
          ref={threadScroll}
          style={{flex: 1}}
          contentContainerStyle={{padding: 20, paddingTop: 0, gap: 12, flexGrow: 1}}
          onContentSizeChange={() => threadScroll.current?.scrollToEnd({animated: false})}>
          {msgs.length === 0 && <Text style={s.dim}>No messages yet.</Text>}
          {msgs.map(m => (
            <View
              key={m.id}
              style={[
                s.card,
                {
                  alignSelf: m.mine ? 'flex-end' : 'flex-start',
                  maxWidth: '80%',
                  backgroundColor: m.mine ? colors.accentLight : colors.card,
                  borderColor: m.mine ? colors.accent + '55' : colors.border,
                },
              ]}>
              {!!m.attachmentUrl && (
                <Image
                  source={{uri: m.attachmentUrl}}
                  style={{width: 200, height: 200, borderRadius: 8, marginBottom: m.body ? 6 : 0}}
                  resizeMode="cover"
                />
              )}
              {!!m.body && <Text style={s.body}>{m.body}</Text>}
            </View>
          ))}
        </ScrollView>
        <View style={[s.row, {padding: 12, gap: 8}]}>
          <TextInput
            style={[s.input, {flex: 1}]}
            placeholder="Text message"
            placeholderTextColor={colors.textDim}
            value={draft}
            onChangeText={setDraft}
            onSubmitEditing={send}
          />
          <TouchableOpacity style={s.btn} onPress={send}>
            <Text style={s.btnText}>Send</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  return (
    <ScrollView style={{flex: 1}} contentContainerStyle={s.screen}>
      <PageTitle right={
        <TouchableOpacity style={s.btn} onPress={startCompose}>
          <Text style={s.btnText}>New message</Text>
        </TouchableOpacity>
      }>Messages</PageTitle>
      {threads.length === 0 && <Text style={s.dim}>No conversations.</Text>}
      {threads.map(t => (
        <TouchableOpacity key={t.id} style={s.card} onPress={() => openExisting(t)}>
          <Text style={s.h2} numberOfLines={1}>{t.name || t.address || 'Unknown'}</Text>
          <Text style={s.dim} numberOfLines={1}>{t.snippet}</Text>
        </TouchableOpacity>
      ))}
    </ScrollView>
  );
}
