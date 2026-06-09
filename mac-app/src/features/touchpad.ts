/**
 * Phone-as-trackpad feature (Mac side). Listens for trackpad.* messages from the Android app
 * and forwards them to the native TetherCursor module, which posts CoreGraphics HID events.
 */
import {connection} from '../net/ConnectionManager';
import {cursor} from '../native/cursor';
import {Envelope} from '../protocol/types';

class TouchpadFeature {
  private wired = false;

  wire(): void {
    if (this.wired) return;
    this.wired = true;
    connection.on('message', env => this.onMessage(env));
  }

  private onMessage(env: Envelope): void {
    if (!cursor) return;
    switch (env.type) {
      case 'trackpad.move': {
        const p = env.payload as {dx: number; dy: number};
        cursor.move(Number(p.dx), Number(p.dy));
        break;
      }
      case 'trackpad.scroll': {
        const p = env.payload as {dx: number; dy: number};
        cursor.scroll(Number(p.dx), Number(p.dy));
        break;
      }
      case 'trackpad.tap':
        cursor.click();
        break;
      case 'trackpad.rightTap':
        cursor.rightClick();
        break;
      case 'trackpad.doubleTap':
        cursor.doubleClick();
        break;
      case 'trackpad.key': {
        const p = env.payload as {text?: string; code?: string};
        if (p.code === 'backspace') cursor.pressKey(0x33); // kVK_Delete
        else if (p.code === 'enter') cursor.pressKey(0x24); // kVK_Return
        else if (p.text) cursor.typeText(p.text);
        break;
      }
    }
  }
}

export const touchpad = new TouchpadFeature();
