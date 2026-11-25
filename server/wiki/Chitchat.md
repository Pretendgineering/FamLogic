# Chat

The Chat module of [FamLogic](Home.md).


Switch on **Chat** in Settings → Modules (on by default for new families). Then:
- Everyone who uses the app is in the **Family** chat. Start **one-to-one** chats or named **group** chats with the **+** button (app) or **New** (console).
- Send text and **photos**: the camera button takes a new photo or picks one from the gallery (shrunk and cleaned of location data on your server). You see **Seen by …** under your latest message, and each message shows its sender's picture (their emoji or initial, in their colour), so children who can't read yet know who wrote.
- **Talking:** hold the talk button (grown-ups: a microphone; children: a smiley with sound waves; children and tablets get a wide talk bar above the message box) and speak; let go and your words are sent (younger children, who then hear a cheerful "Sent!") or typed into the message box (everyone else). If nothing was understood, a voice says "Oops, try again!". Parents can switch talking off per child (Access & Control → Chitchat).
- **The family chat's picture:** the family's initial (from its name: "The Smiths" gives S) in an ornate blackletter (UnifrakturMaguntia, OFL) inside a gold ring, unless a parent picks an icon for it.
- **Reading aloud:** a small speaker beside each message with words in it (not photos or emoji-only messages) reads it aloud, in the sender's chosen voice (Settings → You → **Reading Voice**: female, male or no preference; parents can set it for children in Access & Control). Emoji aren't read out, "hellooooo" is said as "hello", and keyboard mashes are skipped. Younger children get a bigger speaker button that presses in like a real button, hear everything read a little slower, and hear new messages read out as they arrive.
- **Where speech runs:** listening on your server (Whisper) or the phone's own (Look and Feel → Voice; Automatic uses the phone's on the Google edition when it has it); reading aloud on your server (Kokoro, warm male and female voices) whenever it can, otherwise the phone's own text to speech.
- **Chat icons:** parents choose the Family chat's emoji and colour, and anyone in a group can change the group's (**⋮ → Change icon…**).
- **Mute** any chat from its **⋮** menu (children only when a parent allows it): for 1 hour, 8 hours, until 7:00 tomorrow, or until you unmute it. Muting only changes this device.
- **Photos** wait in the message box with an ✕ until you press Send, so a wrong pick or a bad shot never goes out by accident.
- **Delete** a message by long-pressing it (app) or with **Delete** under it (console): your own messages (children only when a parent allows it), or, for parents, any message in a chat with a child in it.
- New messages arrive as notifications with a **Reply** button, through the same always-on connection as SOS alerts (no Google push).
- **Parents can read any chat one of their children is in** (marked *Watching*), but can't post in it. Children see a note saying so. The main admin can read every chat.
- **A group with a child in it waits for a parent's OK** (*Needs OK*): parents are notified and say yes from the chat's **⋮** menu (app) or **Say yes** (console); deleting it says no. Parents' own groups start straight away. A child adding people to a group sends it back for approval.
- Parents can also **pause** a chat with a child in it (everyone can read, nobody can post), **block** it (hidden from the children in it, still visible to parents) or **delete** it with all its messages and photos.
- Parents choose per child whether they have chat at all, can start new chats, and can send photos (console → **Family → ⋯ → What they can use**).
- **Opening a chat:** in portrait a chat opens full screen (Back returns to the list). For younger children the list splits open instead: the rows above slide up, the chat's name and picture stay pinned at the top, and the chat fills the space below, edge to edge. The list holds still while it's open, so scrolling the messages never drags the list along; tap the chat's row (or ✕) to close it. Landscape tablets show the list beside the open chat. Younger children get bigger pictures and buttons, and a group shows the faces of who's in it.
- Messages are kept in `chat.db` and photos in `media/` in the data folder, both included in the daily backups (`backups/chat-<date>.db`) and in the full backup zip from Settings → Server.

How it's built: [Architecture](Architecture.md#chat-built-in-0160).
