# Chat

The Chat module of [FamLogic](../README.md).


Switch on **Chat** in Settings → Modules (on by default for new families). Then:
- Everyone who uses the app is in the **Family** chat. Start **one-to-one** chats or named **group** chats with the **+** button (app) or **New** (console).
- Send text and **photos** (shrunk and cleaned of location data on your server). You see **Seen by …** under your latest message.
- New messages arrive as notifications with a **Reply** button, through the same always-on connection as SOS alerts (no Google push).
- **Parents can read any chat one of their children is in** (marked *Watching*), but can't post in it. Children see a note saying so. The main admin can read every chat.
- Messages are kept in `chat.db` and photos in `media/` in the data folder, both included in the daily backups (`backups/chat-<date>.db`; copy `media/` along with them).

How it's built: [ARCHITECTURE.md](ARCHITECTURE.md#chat-built-in-0160).
