# Chat

The Chat module of [FamLogic](../README.md).


Switch on **Chat** in Settings → Modules (on by default for new families). Then:
- Everyone who uses the app is in the **Family** chat. Start **one-to-one** chats or named **group** chats with the **+** button (app) or **New** (console).
- Send text and **photos**: the camera button takes a new photo or picks one from the gallery (shrunk and cleaned of location data on your server). You see **Seen by …** under your latest message, and each message shows its sender's picture (their emoji or initial, in their colour), so children who can't read yet know who wrote.
- **Mute** any chat from its **⋮** menu: for 1 hour, 8 hours, until 7:00 tomorrow, or until you unmute it. Muting only changes this device.
- **Delete** a message by long-pressing it (app) or with **Delete** under it (console): your own messages, or, for parents, any message in a chat with a child in it.
- New messages arrive as notifications with a **Reply** button, through the same always-on connection as SOS alerts (no Google push).
- **Parents can read any chat one of their children is in** (marked *Watching*), but can't post in it. Children see a note saying so. The main admin can read every chat.
- **A group with a child in it waits for a parent's OK** (*Needs OK*): parents are notified and say yes from the chat's **⋮** menu (app) or **Say yes** (console); deleting it says no. Parents' own groups start straight away. A child adding people to a group sends it back for approval.
- Parents can also **pause** a chat with a child in it (everyone can read, nobody can post), **block** it (hidden from the children in it, still visible to parents) or **delete** it with all its messages and photos.
- Parents choose per child whether they have chat at all, can start new chats, and can send photos (console → **Family → ⋯ → What they can use**).
- **Tablets:** in landscape the chat list sits beside the open chat; in portrait the chat pulls down like a shade over the list (tap the handle to fold it away, or go full screen). Younger children get bigger pictures and buttons, and a group shows the faces of who's in it.
- Messages are kept in `chat.db` and photos in `media/` in the data folder, both included in the daily backups (`backups/chat-<date>.db`; copy `media/` along with them).

How it's built: [ARCHITECTURE.md](ARCHITECTURE.md#chat-built-in-0160).
