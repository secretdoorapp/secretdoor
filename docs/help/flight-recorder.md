# Flight recorder

The flight recorder writes diagnostic information about what the app is doing to a log file **on
your phone**. If SecretDoor misbehaves, a recorded log helps a developer find out why.

## What it records

- The app's log messages: errors, warnings and notes about what the app is doing.
- Basic device and app version information.

The recorder is meant to avoid secrets such as your master password, vault items and encryption
keys, but it is a diagnostic log, so read it before you share it (see below).

## How to use it

1. Open **Settings > About** and switch on **Flight recorder**.
2. Choose how long it should record: 1 hour, 8 hours, 24 hours or 1 week. It stops by itself.
3. Reproduce the problem.
4. Open **View recorded logs** to share or delete a log.

While it is on, a banner reminds you and shows when it will stop.

## Privacy

Nothing is uploaded. The log stays on your phone until you share it yourself, for example by
attaching it to a bug report. Delete recorded logs from the same screen when you no longer need
them.
