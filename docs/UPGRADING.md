# Upgrading

## From Thereabouts

Thereabouts became **FamLogic** in version 0.15.0; location sharing lives on as FamLogic's Thereabouts module.

- **Server:** change the image to `ghcr.io/spasm0dic/famlogic:latest` and redeploy. Keep the same data folder: on first start the server renames `thereabouts.json` to `famlogic.json`, so your family, fences, invites and settings carry over, and your family goes straight to the console (no first-run setup) with Thereabouts switched on. You'll need to log in to the console once more.
  - If you used the example `docker-compose.yml` with a *named volume*, keep your old volume name (`thereabouts-data`) under `volumes:` so the data isn't left behind.
- **App:** FamLogic has a new Android package name, so it installs as a separate app next to Thereabouts. On each phone: install FamLogic, sign in with your username and password (or a new invite from the console), then uninstall Thereabouts. Your old account and history are kept, since they're on your server. Invite links from before (`thereabouts://…`) still open FamLogic.
- **Pebble:** install the FamLogic watchapp and watchface from the new release; they replace the Thereabouts ones on the watch.
- **Index 01 ring:** your ring links keep working (they're on your server).
