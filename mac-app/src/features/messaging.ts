/**
 * SMS/MMS messaging, Mac side (protocol `sms.*`). Drives the Android handler: list conversations
 * (by contact name), open a thread's full history (SMS + received MMS with images), send SMS, search
 * contacts to compose, and receive inbound SMS live. MMS image URLs arrive with a `%HOST%`
 * placeholder (the phone can't know its own LAN IP), rewritten here to the connected host so the
 * Mac's <Image> can load them from the phone's file HTTP server.
 */
import {connection} from '../net/ConnectionManager';
import {
  Envelope, SmsContact, SmsContactListPayload, SmsIncomingPayload, SmsMessage,
  SmsMessageListPayload, SmsThread, SmsThreadListPayload,
} from '../protocol/types';

export interface MessagingState {
  threads: SmsThread[];
  contacts: SmsContact[];
  messages: Record<string, SmsMessage[]>; // by threadId
}

type Listener = (state: MessagingState) => void;

class MessagingService {
  private state: MessagingState = {threads: [], contacts: [], messages: {}};
  private listeners = new Set<Listener>();
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;
    connection.on('message', env => this.onMessage(env));
    connection.on('state', s => {
      if (s === 'connected') this.loadThreads();
    });
  }

  onChange(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  loadThreads(): void {
    connection.send('sms.threads', {});
  }

  openThread(threadId: string): void {
    connection.send('sms.messages', {threadId});
  }

  searchContacts(query: string): void {
    connection.send('sms.contacts', {query});
  }

  /** Send an SMS; optimistically append a sent bubble to the thread if we know it. */
  send(address: string, body: string, threadId?: string): void {
    if (!body.trim()) return;
    connection.send('sms.send', {address, body});
    if (threadId) {
      const msg: SmsMessage = {id: `local-${Date.now()}`, body, date: Date.now(), mine: true};
      this.set({...this.state, messages: {...this.state.messages, [threadId]: [...(this.state.messages[threadId] ?? []), msg]}});
    }
  }

  private onMessage(env: Envelope): void {
    switch (env.type) {
      case 'sms.threadList':
        this.set({...this.state, threads: (env.payload as SmsThreadListPayload).threads});
        break;
      case 'sms.messageList': {
        const p = env.payload as SmsMessageListPayload;
        const messages = p.messages.map(m => this.resolveHost(m));
        this.set({...this.state, messages: {...this.state.messages, [p.threadId]: messages}});
        break;
      }
      case 'sms.incoming': {
        const p = env.payload as SmsIncomingPayload;
        this.applyIncoming(p);
        break;
      }
      case 'sms.contactList':
        this.set({...this.state, contacts: (env.payload as SmsContactListPayload).contacts});
        break;
    }
  }

  /** Rewrite the MMS image URL's host placeholder to the phone we're connected to. */
  private resolveHost(m: SmsMessage): SmsMessage {
    if (!m.attachmentUrl?.includes('%HOST%')) return m;
    const host = connection.currentHost();
    if (!host) return m;
    return {...m, attachmentUrl: m.attachmentUrl.replace('%HOST%', host)};
  }

  private applyIncoming(p: SmsIncomingPayload): void {
    const messages = {...this.state.messages};
    if (messages[p.threadId]) messages[p.threadId] = [...messages[p.threadId], p.message];

    // Bump the thread to the top with the new snippet, or add it if unseen.
    const existing = this.state.threads.find(t => t.id === p.threadId);
    const updated: SmsThread = existing
      ? {...existing, snippet: p.message.body, date: p.message.date}
      : {id: p.threadId, address: p.address, name: p.name, snippet: p.message.body, date: p.message.date};
    const threads = [updated, ...this.state.threads.filter(t => t.id !== p.threadId)];
    this.set({...this.state, threads, messages});
  }

  private set(state: MessagingState): void {
    this.state = state;
    this.listeners.forEach(l => l(state));
  }
}

export const messaging = new MessagingService();
