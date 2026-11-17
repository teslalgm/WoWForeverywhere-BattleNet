# Playing through the Battle.net app (experimental)

This fork of [WoW Foreverywhere](https://github.com/YoruDev-Ryland/WoWForeverywhere) adds one thing: a **Battle.net (beta)** button that runs Blizzard's own Battle.net desktop app inside the game container. Battle.net signs you in and starts WoW Forever the way it does on a PC.

It is meant for players whose game cannot sign in on its own, typically with error **BLZ51914400** on the game's login screen. If the regular **Play** button of WoW Foreverywhere works for you, you don't need this.

> [!WARNING]
> This is a test build. It works on the one phone it was developed on and has rough edges, listed under [Known problems](#known-problems). It is unofficial and not affiliated with or endorsed by Blizzard Entertainment. You need your own Battle.net account with access to WoW Forever.

## What you need

| | |
| :--- | :--- |
| **Device** | A Snapdragon phone or handheld that already runs WoW Foreverywhere. Developed on a Snapdragon 8 Elite Gen 5 phone with 12 GB of RAM and Android 16. |
| **Memory** | Developed with 12 GB of RAM. Battle.net is heavy, and less memory is untested: Android may close the app while Battle.net is open. |
| **Game** | WoW Forever installed through WoW Foreverywhere (this build or the regular app), up to date. |
| **Storage** | Some extra internal storage for Battle.net, on top of what the app and the game already use. |

## Install

1. Download `WoW-Foreverywhere-bnettest.apk` from the newest entry on the [Releases page](../../releases) and install it. It appears as **WoW BNet Test** and installs next to the regular WoW Foreverywhere app, so you keep both.
2. Open it and let it find or download the game, exactly as in the regular app. If the regular app already downloaded the game, this build finds that copy.
3. On the phone, download Blizzard's Windows installer of the Battle.net desktop app from [download.battle.net](https://download.battle.net/) and save it in the phone's **Download** folder under the name `Battle.net-Setup.exe`. The app ships no Battle.net files of its own.

## First start

1. On the home screen press **Battle.net (beta)**. The first time, this runs the installer. Let it install to the folder it suggests.
2. Sign in with your Battle.net account. Once Battle.net is installed, the same button opens it directly.
3. In Battle.net, open the page of **WoW Forever**. Instead of Install, use **Locate the game** and pick the folder `WoW Forever` on drive `H:`. Select the folder itself, not the drive.
4. Battle.net then offers **Update**. Press it and wait until it finishes, including the "Reclaiming space" step. This adds Battle.net's own launcher file to the game folder; it does not download the game again.
5. In Battle.net's settings:
   - **App → When I launch a game:** keep the Battle.net window open. Do not choose "Exit Battle.net completely"; on a phone the game starts too slowly for that and then cannot sign in.
   - **Notifications:** turn them off. A notification popping up can leave the Battle.net window unresponsive.
6. Press **Play**.

## Every start after that

1. Press **Battle.net (beta)** and wait for Battle.net to sign in.
2. Press **Play** and wait. On a phone the game takes about two minutes to appear, and nothing seems to happen meanwhile.
3. The game signs in by itself and shows your characters. The app closes Battle.net on its own about 45 seconds after the game appears, which frees memory; the game runs smoothly from then on.

When you quit the game, the screen may keep showing the last picture. Tap the screen once and Battle.net (or the app) comes back.

## Known problems

Each has an entry under [Issues](../../issues):

- The Battle.net window can stay dark and unresponsive after its Settings window, an error message or a notification closes.
- The picture sometimes freezes while Battle.net starts or signs in. Close the session and start again.
- After quitting the game the last frame stays on screen until you tap it.
- Minimising the app during a Battle.net session gets it closed by Android.
- The game stutters for the first 45 seconds, until Battle.net is closed.
- Battle.net's news panel may show "Oops! Something went wrong". It is harmless.
- The two Battle.net settings above have to be set by hand.

## If something goes wrong

Turn on debug logging in the app's settings, reproduce the problem, close the session, and create a report from the app's main screen. Attach the report to a new entry under [Issues](../../issues) and say what you did and what you saw. Reports hold logs of the app, Wine, Battle.net and the game. They do not hold your password or login token, but they do include your game account name, so look through a report before posting it publicly.

## How it works

Battle.net is a 32-bit x86 Windows program and runs through FEX; the game is Blizzard's native Windows ARM64 client and runs directly under Wine, as in the regular app. Battle.net starts the game with a launcher token, so the game never takes the direct login path that is blocked on some devices. The game folder is shown to Battle.net as a plain folder on a drive of its own (`H:`), because Battle.net's Agent refuses folders reached through links and checks Windows permissions that Android's shared storage does not have.

The source is on the `feat/battle-net` branch. Licence: GPL-3.0, as upstream.
